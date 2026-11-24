// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.notify

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.ramflux.data.AccountAccessRepository
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.ConversationDirectoryRepository
import org.ramflux.data.ConversationSummary
import org.ramflux.data.clientDebugEvent

/** Posts the generic message notification. Implementations never ask for the notification permission. */
interface MessageNotifier {
    /** True only when the user already allows this app's notifications and its message channel. */
    fun allowed(): Boolean

    /** Posts (or replaces) the notification for [token]; it carries no sender, title, text or conversation id. */
    fun post(token: String)
}

/** A conversation a tapped notification opens; a group conversation carries its group id, as a list tap does. */
data class NotificationTarget(val account: String, val conversation: String, val group: String? = null) {
    override fun toString() = "NotificationTarget(<redacted>)"
}

/**
 * Maps the random tokens carried by notifications back to their conversation, in memory only: a token outlives
 * neither the process nor [MAX_ROUTES] newer conversations. One token per conversation, so a second message
 * replaces the conversation's notification instead of stacking another.
 */
@Singleton
class NotificationRoutes @Inject constructor(private val accounts: AccountAccessRepository) {
    private val lock = Any()
    private val routes = LinkedHashMap<String, NotificationTarget>(MAX_ROUTES, LOAD_FACTOR, true)
    private val pending = MutableStateFlow<NotificationTarget?>(null)

    /** The conversation of the last tapped notification, waiting until the shell is shown. */
    val pendingOpen: StateFlow<NotificationTarget?> = pending

    fun issue(target: NotificationTarget): String = synchronized(lock) {
        routes.entries.firstOrNull { it.value == target }?.key ?: UUID.randomUUID().toString().also { token ->
            routes[token] = target
            while (routes.size > MAX_ROUTES) routes.remove(routes.keys.first())
        }
    }

    /** A tapped notification: an unknown or forged token opens nothing. */
    fun open(token: String?) {
        val target = token?.let { synchronized(lock) { routes[it] } } ?: return
        pending.value = target
    }

    /**
     * Takes the pending conversation once the shell is shown (an unlocked session). It is returned only while its
     * account is still the active unlocked account; otherwise it is dropped.
     */
    suspend fun claim(): NotificationTarget? {
        val target = pending.value ?: return null
        pending.compareAndSet(target, null)
        val catalog = (accounts.list() as? ClientResult.Ready)?.value ?: return null
        val active = catalog.accounts.singleOrNull { it.id == catalog.activeId && it.unlocked } ?: return null
        return target.takeIf { it.account == active.id }
    }

    private companion object {
        const val MAX_ROUTES = 64
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * Decides which conversations get a notification from successive reads of the first directory page: the first
 * read is the baseline, then a conversation whose unread count grew notifies unless it is muted. A conversation
 * that appears with unread messages counts from zero.
 */
class UnreadBaseline {
    private var counts: Map<String, ULong>? = null

    fun next(rows: List<ConversationSummary>): List<String> {
        val previous = counts
        counts = rows.associate { it.id to it.unread }
        if (previous == null) return emptyList()
        return rows.filter { !it.muted && it.unread > (previous[it.id] ?: 0uL) }.map { it.id }
    }
}

/**
 * Local message notifications while the process is alive with an open session and the app is not in the
 * foreground, driven by the shared event hub (the conversation directory's topics, never `*`). Each change
 * re-reads the first directory page of the active unlocked account and compares unread counts. In the background
 * the client is suspended (no network, accounts kept unlocked), so nothing is posted after the app has stopped. Real
 * background delivery needs the node's push design (requests R21 and R28, see docs/bridge.md). While the app is in
 * front the same unread growth feeds [InAppBanners] instead, and nothing is posted to the system.
 */
class MessageNotificationDriver(
    private val session: ClientSessionRepository,
    private val accounts: AccountAccessRepository,
    private val conversations: ConversationDirectoryRepository,
    private val notifier: MessageNotifier,
    private val routes: NotificationRoutes,
    private val banners: InAppBanners? = null,
    private val foreground: () -> Boolean
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope): Job = scope.launch {
        // Live means open and not suspended: a suspended client has no event stream, and a resumed one needs a new
        // subscription (a stream ended by the hub or by the closing client never comes back by itself).
        session.status.map { it.phase == ClientPhase.OPEN && !it.suspended }.distinctUntilChanged().flatMapLatest { live ->
            if (!live) flowOf(null) else merge(flowOf(Unit), accounts.revisions.map { }).map { activeAccount() }
        }.distinctUntilChanged().collectLatest { account ->
            if (account == null) return@collectLatest
            clientDebugEvent("notify.driver.watch")
            val baseline = UnreadBaseline()
            while (true) {
                merge(flowOf(Unit), conversations.changes(account).map { }).conflate().collect { read(account, baseline) }
                // The stream ended while the session is still live: subscribe again after a short pause.
                clientDebugEvent("notify.driver.resubscribe")
                delay(RESUBSCRIBE_MS)
            }
        }
    }

    private suspend fun activeAccount(): String? {
        val catalog = (accounts.list() as? ClientResult.Ready)?.value ?: return null
        return catalog.accounts.singleOrNull { it.id == catalog.activeId && it.unlocked }?.id
    }

    private suspend fun read(account: String, baseline: UnreadBaseline) {
        val page = (conversations.page(account, null) as? ClientResult.Ready)?.value
        if (page == null) {
            clientDebugEvent("notify.driver.page_failed")
            return
        }
        val grown = baseline.next(page.rows)
        if (grown.isEmpty()) return
        val front = foreground()
        clientDebugEvent("notify.driver.grown:" + grown.size + (if (front) ".front" else ".back"))
        if (front) {
            // In front: an in-app banner, never a system notification.
            banners?.let { sink -> grown.forEach { id -> page.rows.firstOrNull { it.id == id }?.let { sink.announce(account, it) } } }
            return
        }
        if (!notifier.allowed()) return
        val groups = page.rows.associate { it.id to it.groupId }
        for (conversation in grown) notifier.post(routes.issue(NotificationTarget(account, conversation, groups[conversation])))
    }

    private companion object {
        const val RESUBSCRIBE_MS = 2_000L
    }
}

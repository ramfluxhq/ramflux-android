// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.notify

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.ramflux.data.AppLockPhase
import org.ramflux.data.CallSummary
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.ConversationSummary
import org.ramflux.data.MessageHistoryRepository
import org.ramflux.data.MessageSummary
import org.ramflux.data.clientDebugEvent

/**
 * What the one-line preview of a banner shows. Only a user text message carries its text; every other kind of
 * content is a fixed text that the host view takes from resources.
 */
sealed interface BannerPreview {
    /** A user text message, already sanitised by [BannerText.line]. */
    class Text(val line: String) : BannerPreview {
        override fun toString() = "Text(<redacted>)"
    }

    /** A call row. */
    data object Call : BannerPreview

    /** A system event of the conversation (a service row). */
    data object Update : BannerPreview

    /** A message that only carries media. */
    data object Attachment : BannerPreview

    /** Anything else (deleted, not yet readable, unknown): the generic "New message". */
    data object Generic : BannerPreview
}

/** One banner. It lives in memory only: it is never saved, logged or handed to the platform. */
class InAppBanner(
    val serial: Long,
    val account: String,
    val conversation: String,
    val group: String?,
    val sender: String,
    val preview: BannerPreview
) {
    override fun toString() = "InAppBanner(<redacted>)"
}

/** Sanitising of peer-supplied text before it is drawn in a banner. */
object BannerText {
    const val MAX_CHARS = 80

    /**
     * One line of at most [MAX_CHARS] characters: line breaks and tabs become a space, other control characters
     * are dropped, runs of blanks collapse and the ends are trimmed.
     */
    fun line(raw: String): String {
        val out = StringBuilder()
        var pendingSpace = false
        var index = 0
        var count = 0
        while (index < raw.length && count < MAX_CHARS) {
            val point = raw.codePointAt(index)
            index += Character.charCount(point)
            val blank = point == '\n'.code || point == '\r'.code || point == '\t'.code || Character.isWhitespace(point) ||
                Character.isSpaceChar(point)
            if (blank) {
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (Character.isISOControl(point)) continue
            if (pendingSpace) {
                out.append(' ')
                count++
                pendingSpace = false
                if (count >= MAX_CHARS) break
            }
            out.appendCodePoint(point)
            count++
        }
        // The cut can land right after a collapsed blank: the ends are trimmed.
        return out.toString().trimEnd()
    }
}

/**
 * The in-app banner of a message that arrives while the app is in front. It is fed by [MessageNotificationDriver]
 * from the conversation directory's change stream (a conversation whose unread count grew) and reads the newest row
 * of that conversation's timeline once, through the existing history repository. Never a system notification.
 */
@Singleton
class InAppBanners @Inject constructor(
    private val history: MessageHistoryRepository,
    private val routes: NotificationRoutes,
    private val session: ClientSessionRepository,
) {
    private val shown = MutableStateFlow<InAppBanner?>(null)
    private val serial = AtomicLong()
    private val openChat = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * The banner on screen; a newer one replaces it. Nothing shows while the app lock is on or unread: a banner names
     * the sender and its text, and the cover must not carry them.
     */
    val current: Flow<InAppBanner?> = combine(shown, session.status) { banner, status ->
        banner.takeIf { status.phase == ClientPhase.OPEN && status.appLock == AppLockPhase.OPEN }
    }

    private fun visibleToUser(): Boolean = session.status.value.let { it.phase == ClientPhase.OPEN && it.appLock == AppLockPhase.OPEN }

    /** The conversation the user is looking at, or null on any other screen. */
    fun chatOpened(account: String?, conversation: String?) {
        openChat.value = if (account != null && conversation != null) account to conversation else null
    }

    /** Shows a banner for [row] unless that conversation is muted or open, or the newest message is the user's own. */
    suspend fun announce(account: String, row: ConversationSummary) {
        if (!visibleToUser()) {
            clientDebugEvent("banner.skip.locked")
            return
        }
        if (row.muted || openChat.value == account to row.id) {
            clientDebugEvent("banner.skip." + (if (row.muted) "muted" else "open"))
            return
        }
        val newest = (history.page(account, row.id) as? ClientResult.Ready)?.value?.rows?.lastOrNull()
        val preview = when (newest) {
            null -> BannerPreview.Generic
            is CallSummary -> BannerPreview.Call
            is MessageSummary -> when {
                newest.own -> {
                    clientDebugEvent("banner.skip.own")
                    return
                }
                newest.service -> BannerPreview.Update
                newest.deleted || newest.placeholder -> BannerPreview.Generic
                else -> newest.text?.let(BannerText::line)?.takeIf { it.isNotEmpty() }?.let { BannerPreview.Text(it) }
                    ?: if (newest.attachments.isNotEmpty()) BannerPreview.Attachment else BannerPreview.Generic
            }
        }
        val author = (newest as? MessageSummary)?.senderName?.takeIf { row.group && it.isNotBlank() } ?: row.title
        // A conversation that became open while the newest row was read gets no banner.
        if (openChat.value == account to row.id || !visibleToUser()) return
        clientDebugEvent("banner.shown")
        shown.value = InAppBanner(serial.incrementAndGet(), account, row.id, row.groupId, BannerText.line(author), preview)
    }

    fun dismiss(banner: InAppBanner) {
        shown.compareAndSet(banner, null)
    }

    /** A tap: the conversation opens through the same route as a tapped notification. */
    fun open(banner: InAppBanner) {
        clientDebugEvent("banner.tap")
        routes.open(routes.issue(NotificationTarget(banner.account, banner.conversation, banner.group)))
        dismiss(banner)
    }
}

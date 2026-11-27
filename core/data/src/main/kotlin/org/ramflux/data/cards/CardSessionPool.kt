// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.put
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.ClientFeature
import org.ramflux.data.ClientOutcome
import org.ramflux.data.ClientProblem
import org.ramflux.data.ClientResult
import org.ramflux.data.DefaultSurfacesRepository
import org.ramflux.data.RepositoryContext
import org.ramflux.data.RepositoryTransport
import org.ramflux.data.SurfacesRepository
import org.ramflux.data.UnavailableReason
import org.ramflux.data.appProblem
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.CoreUpdate
import org.ramflux.sdk.SecretReply
import org.ramflux.sdk.SubscriptionFilter

/**
 * Where one card is in its life (spec 3.4). Draw the fallback text when the state is [UNAVAILABLE]; keep the last
 * tree read-only for [SUSPENDED] and [OUT_OF_SYNC].
 */
enum class CardLifecycle { READY, GENERATING, SUBMITTING, CONSUMED, CLOSED, SUPERSEDED, OUT_OF_SYNC, OVER_LIMIT, SUSPENDED, UNAVAILABLE }

/** What a host may show about one held card. [tree] is null until the card has rendered; [resumed] means it holds a runtime VM. */
data class CardState(
    val ref: String,
    val lifecycle: CardLifecycle,
    val fallback: String,
    val renderAllowed: Boolean,
    val tree: CardTree?,
    val resumed: Boolean,
    /** A host request waiting for its sheet: a link to confirm (`open_url`). Cleared by the next signed update. */
    val hostRequest: JsonObject?,
    /** An approval was confirmed; the verified outcome arrives with the next signed update. */
    val awaitingDecision: Boolean,
    /** The runtime discarded a draft edit that conflicted with a signed update (spec 4.20). */
    val draftConflict: Boolean,
) {
    override fun toString() = "CardState(<redacted>)"
}

/** One agent surface of a conversation (`surface.list`). [createdAt] places the card in the timeline. */
data class CardSurfaceRef(val ref: String, val version: Long, val hash: String, val closed: Boolean, val outOfSync: Boolean, val createdAt: Long?) {
    override fun toString() = "CardSurfaceRef(<redacted>)"
}

data class CardSurfacePage(val rows: List<CardSurfaceRef>, val next: String?) {
    override fun toString() = "CardSurfacePage(<redacted>)"
}

/**
 * The card runtime as the app sees it: discovery through `surface.list`, sessions through `card.session.*`.
 * Results are typed; nothing here parses a surface itself.
 */
interface CardSessionRepository {
    /** Cards are available when the core linked its card runtime (negotiate `card_runtime`). */
    suspend fun availability(): ClientResult<Unit>

    /** The agent surfaces of a conversation, newest page first by the core's cursor. */
    suspend fun page(account: String, conversation: String, cursor: String?): ClientResult<CardSurfacePage>

    /** The held state of one card, or null when none is held. Reads only; never waits for a call. */
    fun state(account: String, ref: String): CardState?

    /** Counts every change of any held card; collect it to redraw. */
    val revision: StateFlow<Long>

    /** Opens the card, or resumes it when it is held but suspended (the first tap on a suspended card). */
    suspend fun show(account: String, ref: String, locale: String = "en", dark: Boolean = false): ClientResult<Unit>

    /** Sends one host event for the control at [key] of the shown tree. Refused when the card is not resumed. */
    suspend fun event(account: String, ref: String, key: String, event: CardEvent): ClientResult<CardReply>

    /** Answers a host slot (link or approval) with [outcome]; [token], [slotId] and [requestId] come from the tree or the host request. */
    suspend fun slot(
        account: String, ref: String, slotId: String, requestId: String, token: JsonObject, outcome: CardSlotOutcome,
    ): ClientResult<CardReply>

    /** The one networked call: submits the shown action of a consumed Button. Never retried automatically. */
    suspend fun submit(account: String, ref: String): ClientResult<CardReply>

    /** Locale or dark change for every resumed card of the account. */
    suspend fun present(account: String, locale: String, dark: Boolean): ClientResult<Unit>

    /** Releases a card that left the timeline window. */
    suspend fun dispose(account: String, ref: String)
}

/** For hosts without the card runtime: every call is the typed unavailable state. */
class UnavailableCardSessionRepository : CardSessionRepository {
    private val unavailable get() = ClientResult.Unavailable(ClientFeature.CARDS, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun availability(): ClientResult<Unit> = unavailable
    override suspend fun page(account: String, conversation: String, cursor: String?): ClientResult<CardSurfacePage> = unavailable
    override fun state(account: String, ref: String): CardState? = null
    override val revision: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
    override suspend fun show(account: String, ref: String, locale: String, dark: Boolean): ClientResult<Unit> = unavailable
    override suspend fun event(account: String, ref: String, key: String, event: CardEvent): ClientResult<CardReply> = unavailable
    override suspend fun slot(
        account: String, ref: String, slotId: String, requestId: String, token: JsonObject, outcome: CardSlotOutcome,
    ): ClientResult<CardReply> = unavailable
    override suspend fun submit(account: String, ref: String): ClientResult<CardReply> = unavailable
    override suspend fun present(account: String, locale: String, dark: Boolean): ClientResult<Unit> = unavailable
    override suspend fun dispose(account: String, ref: String) = Unit
}

/** Lifecycle the client drives: suspend every resumed card before the client goes to the background, resume on return. */
internal interface CardLifecycleHooks {
    suspend fun background()
    suspend fun foreground()
}

/**
 * Per-account card sessions. At most [MAX_RESUMED] cards hold a runtime VM (the least recently used are suspended and keep
 * their last tree); at most [MAX_OPEN] cards are held (the least recently used are disposed). Every change of one account
 * runs under that account's [Mutex]; a surface event for an account reaches the same pool through [watch].
 */
internal class CardSessionPool(
    private val transport: RepositoryTransport,
    private val surfaces: SurfacesRepository = DefaultSurfacesRepository(transport),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CardSessionRepository, CardLifecycleHooks {
    private val client = CardSessionClient(transport)
    private val guard = Any()
    private val accounts = HashMap<String, Account>()
    private val counter = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = counter.asStateFlow()

    private class Account(val lock: Mutex) {
        /** Access order: iteration starts with the least recently used card. Only [touchSlot] reorders it. */
        val slots = LinkedHashMap<String, Slot>(16, 0.75f, true)
        var watch: Job? = null
    }

    private class Slot(val ref: String, var locale: String, var dark: Boolean, state: CardState) {
        @Volatile var state: CardState = state
        var session: Long? = null
        var resumeOnForeground = false
    }

    override suspend fun availability(): ClientResult<Unit> =
        if (transport.cardRuntimeAvailable()) ClientResult.Ready(Unit)
        else ClientResult.Unavailable(ClientFeature.CARDS, UnavailableReason.UNSUPPORTED_BY_CORE)

    override suspend fun page(account: String, conversation: String, cursor: String?): ClientResult<CardSurfacePage> {
        if (!usable(account) || !usable(conversation) || (cursor?.length ?: 0) > MAX_CURSOR) return invalid()
        val listed = surfaces.surfaceList(
            RepositoryContext(account),
            buildJsonObject {
                put("conversation_id", conversation)
                put("limit", PAGE)
                cursor?.let { put("cursor", it) }
            },
        )
        if (listed is CoreResult.Failure) return ClientResult.Failed(listed.error.appProblem())
        val root = readSecret(listed) ?: return failed(FAILED_CODE)
        val rows = (root["surfaces"] as? JsonArray).orEmpty().mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            if (item.flag("removed") == true) return@mapNotNull null
            CardSurfaceRef(
                ref = item.text("surface_ref") ?: return@mapNotNull null,
                version = item.long("version") ?: 0,
                hash = item.text("surface_hash").orEmpty(),
                closed = item.flag("closed") == true,
                outOfSync = item.flag("out_of_sync") == true,
                createdAt = item.long("created_at"),
            )
        }
        return ClientResult.Ready(CardSurfacePage(rows, root.text("next_cursor")?.takeIf { it.length <= MAX_CURSOR }))
    }

    /** A non-touching peek: reading the state of many cards must not change which card is least recently used. */
    override fun state(account: String, ref: String): CardState? = synchronized(guard) { accounts[account]?.let { peekSlot(it, ref) }?.state }

    override suspend fun show(account: String, ref: String, locale: String, dark: Boolean): ClientResult<Unit> {
        if (!usable(account) || !usable(ref)) return invalid()
        val acc = accountOf(account)
        return acc.lock.withLock {
            val held = touchSlot(acc, ref)
            when {
                held == null -> open(acc, account, ref, locale, dark)
                held.state.resumed -> ClientResult.Ready(Unit)
                else -> resume(acc, account, held)
            }
        }
    }

    override suspend fun event(account: String, ref: String, key: String, event: CardEvent): ClientResult<CardReply> {
        if (!usable(account) || !usable(ref)) return invalid()
        val acc = accountOf(account)
        return acc.lock.withLock {
            val slot = touchSlot(acc, ref)?.takeIf { it.state.resumed } ?: return@withLock invalid()
            val tree = slot.state.tree ?: return@withLock invalid()
            val session = slot.session ?: return@withLock invalid()
            when (val call = client.event(account, session, tree.tokenFor(key), event)) {
                is CardCall.Failed -> ClientResult.Failed(call.problem)
                is CardCall.Replied -> {
                    if (!absorb(slot, call.reply)) reread(acc, account, slot)
                    ClientResult.Ready(call.reply)
                }
            }
        }
    }

    override suspend fun slot(
        account: String, ref: String, slotId: String, requestId: String, token: JsonObject, outcome: CardSlotOutcome,
    ): ClientResult<CardReply> {
        if (!usable(account) || !usable(ref) || slotId.isEmpty() || requestId.isEmpty()) return invalid()
        val acc = accountOf(account)
        return acc.lock.withLock {
            val slot = touchSlot(acc, ref)?.takeIf { it.state.resumed } ?: return@withLock invalid()
            val session = slot.session ?: return@withLock invalid()
            when (val call = client.slot(account, session, token, slotId, requestId, outcome)) {
                is CardCall.Failed -> ClientResult.Failed(call.problem)
                is CardCall.Replied -> {
                    answered(slot, call.reply)
                    ClientResult.Ready(call.reply)
                }
            }
        }
    }

    override suspend fun submit(account: String, ref: String): ClientResult<CardReply> {
        if (!usable(account) || !usable(ref)) return invalid()
        val acc = accountOf(account)
        return acc.lock.withLock {
            val slot = touchSlot(acc, ref)?.takeIf { it.state.resumed } ?: return@withLock invalid()
            val session = slot.session ?: return@withLock invalid()
            publish(slot, slot.state.copy(lifecycle = CardLifecycle.SUBMITTING))
            when (val call = client.submit(account, session)) {
                is CardCall.Failed -> {
                    publish(slot, slot.state.copy(lifecycle = CardLifecycle.READY))
                    ClientResult.Failed(call.problem)
                }
                is CardCall.Replied -> {
                    if (!absorb(slot, call.reply)) reread(acc, account, slot)
                    // Any answer other than a consumed Button returns the card to ready.
                    if (slot.state.lifecycle == CardLifecycle.SUBMITTING) publish(slot, slot.state.copy(lifecycle = CardLifecycle.READY))
                    ClientResult.Ready(call.reply)
                }
            }
        }
    }

    override suspend fun present(account: String, locale: String, dark: Boolean): ClientResult<Unit> {
        if (!usable(account)) return invalid()
        val acc = accountOf(account)
        return acc.lock.withLock {
            val resumed = synchronized(guard) { acc.slots.values.filter { it.state.resumed } }
            for (slot in resumed) {
                val session = slot.session ?: continue
                slot.locale = locale
                slot.dark = dark
                when (val call = client.present(account, session, locale, dark)) {
                    is CardCall.Failed -> return@withLock ClientResult.Failed(call.problem)
                    is CardCall.Replied -> if (!absorb(slot, call.reply)) reread(acc, account, slot)
                }
            }
            ClientResult.Ready(Unit)
        }
    }

    override suspend fun dispose(account: String, ref: String) {
        if (!usable(account) || !usable(ref)) return
        val acc = accountOf(account)
        acc.lock.withLock { peekSlot(acc, ref)?.let { disposeSlot(acc, account, it) } }
    }

    /** Background: stop the surface feed, then suspend every resumed card. Foreground resumes the most recent ones again. */
    override suspend fun background() {
        val held = synchronized(guard) { accounts.entries.map { it.key to it.value } }
        for ((name, acc) in held) acc.lock.withLock {
            synchronized(guard) {
                acc.watch?.cancel()
                acc.watch = null
            }
            val resumed = synchronized(guard) { acc.slots.values.filter { it.state.resumed } }
            for (slot in resumed) {
                slot.resumeOnForeground = true
                suspendSlot(acc, name, slot)
            }
        }
    }

    override suspend fun foreground() {
        val held = synchronized(guard) { accounts.entries.map { it.key to it.value } }
        for ((name, acc) in held) acc.lock.withLock {
            val waiting = synchronized(guard) { acc.slots.values.filter { it.resumeOnForeground }.reversed() }
            if (synchronized(guard) { acc.slots.isNotEmpty() }) startWatch(name)
            for (slot in waiting) {
                slot.resumeOnForeground = false
            }
            for (slot in waiting.take(MAX_RESUMED)) {
                if (!slot.state.resumed) resume(acc, name, slot)
            }
        }
    }

    private fun accountOf(account: String): Account = synchronized(guard) { accounts.getOrPut(account) { Account(Mutex()) } }

    /** Reads the card and makes it most recently used. Used by show, event, slot and submit only. */
    private fun touchSlot(acc: Account, ref: String): Slot? = synchronized(guard) { acc.slots[ref] }

    /** Finds the card without changing the access order (iteration does not reorder a LinkedHashMap). */
    private fun peekSlot(acc: Account, ref: String): Slot? = acc.slots.values.firstOrNull { it.ref == ref }

    private suspend fun open(acc: Account, account: String, ref: String, locale: String, dark: Boolean): ClientResult<Unit> {
        makeRoom(acc, account, except = ref)
        var call = client.open(account, ref, locale, dark)
        if (isLimit(call)) {
            suspendLeastRecent(acc, account, except = null)
            call = client.open(account, ref, locale, dark)
        }
        return when (call) {
            is CardCall.Failed -> ClientResult.Failed(call.problem)
            is CardCall.Replied -> {
                // A refused open is held too: its state says why the card shows only the fallback text.
                val slot = Slot(ref, locale, dark, CardState(ref, CardLifecycle.UNAVAILABLE, "", false, null, false, null, false, false))
                synchronized(guard) { acc.slots[ref] = slot }
                absorb(slot, call.reply)
                enforceResumed(acc, account, except = ref)
                startWatch(account)
                ClientResult.Ready(Unit)
            }
        }
    }

    private suspend fun resume(acc: Account, account: String, slot: Slot): ClientResult<Unit> {
        val session = slot.session ?: return reopen(acc, account, slot)
        var call = client.resume(account, session)
        if (isLimit(call)) {
            suspendLeastRecent(acc, account, except = slot.ref)
            call = client.resume(account, session)
        }
        if (isInactive(call)) return reopen(acc, account, slot)
        return when (call) {
            is CardCall.Failed -> ClientResult.Failed(call.problem)
            is CardCall.Replied -> {
                absorb(slot, call.reply)
                enforceResumed(acc, account, except = slot.ref)
                ClientResult.Ready(Unit)
            }
        }
    }

    /**
     * The runtime handle is gone or the account closed: a fresh open replaces the held card. The held tree stays on
     * screen until the open answers, so a failed reopen keeps the last tree for the next attempt.
     */
    private suspend fun reopen(acc: Account, account: String, slot: Slot): ClientResult<Unit> {
        slot.session?.let { client.dispose(account, it) }
        slot.session = null
        return open(acc, account, slot.ref, slot.locale, slot.dark)
    }

    /** Full-tree re-read: the held tree no longer matches the runtime, so the card is suspended and resumed. */
    private suspend fun reread(acc: Account, account: String, slot: Slot) {
        if (slot.state.resumed) suspendSlot(acc, account, slot)
        if (slot.session != null) resume(acc, account, slot)
    }

    private suspend fun refresh(acc: Account, account: String, slot: Slot) {
        val session = slot.session ?: return
        if (!slot.state.resumed) return
        when (val call = client.update(account, session)) {
            is CardCall.Failed -> Unit
            is CardCall.Replied -> if (!absorb(slot, call.reply)) reread(acc, account, slot)
        }
    }

    /**
     * Suspends the card and keeps its last tree (and the draft shown with it) on screen. A failed suspend, such as the
     * account closed behind the app lock, releases the runtime handle best effort and keeps the tree: the next show or
     * the lock release re-opens the card. The tree is not dropped, so the host does not lose the user's input.
     */
    private suspend fun suspendSlot(acc: Account, account: String, slot: Slot): Boolean {
        val session = slot.session ?: return false
        return when (val call = client.suspendSession(account, session)) {
            is CardCall.Replied -> {
                publish(slot, slot.state.copy(resumed = false, lifecycle = CardLifecycle.SUSPENDED, hostRequest = null, awaitingDecision = false))
                true
            }
            is CardCall.Failed -> {
                client.dispose(account, session)
                slot.session = null
                publish(slot, slot.state.copy(resumed = false, lifecycle = CardLifecycle.SUSPENDED, hostRequest = null, awaitingDecision = false))
                true
            }
        }
    }

    private suspend fun suspendLeastRecent(acc: Account, account: String, except: String?): Boolean {
        val victim = synchronized(guard) { acc.slots.values.firstOrNull { it.state.resumed && it.ref != except } } ?: return false
        return suspendSlot(acc, account, victim)
    }

    private suspend fun enforceResumed(acc: Account, account: String, except: String) {
        while (synchronized(guard) { acc.slots.values.count { it.state.resumed } } > MAX_RESUMED) {
            if (!suspendLeastRecent(acc, account, except)) break
        }
    }

    private suspend fun makeRoom(acc: Account, account: String, except: String) {
        while (synchronized(guard) { acc.slots.size } >= MAX_OPEN) {
            val victim = synchronized(guard) { acc.slots.values.firstOrNull { it.ref != except } } ?: break
            disposeSlot(acc, account, victim)
        }
    }

    private suspend fun disposeSlot(acc: Account, account: String, slot: Slot) {
        synchronized(guard) { acc.slots.remove(slot.ref) }
        slot.session?.let { client.dispose(account, it) }
        counter.value = counter.value + 1
    }

    /** A signed update or a re-read of the same runtime session. Returns false when the batch does not fit the held tree. */
    private fun absorb(slot: Slot, reply: CardReply): Boolean {
        val current = slot.state
        val fallback = reply.fallback.ifEmpty { current.fallback }
        when (reply.result) {
            "opened", "resumed" -> {
                slot.session = reply.session
                val tree = if (reply.renderAllowed) reply.batch?.let { CardTree.EMPTY.apply(it) } else null
                publish(slot, current.copy(
                    fallback = fallback,
                    renderAllowed = reply.renderAllowed,
                    tree = tree,
                    resumed = tree != null,
                    lifecycle = if (tree == null) CardLifecycle.UNAVAILABLE else lifecycleOf(tree),
                    hostRequest = null,
                    awaitingDecision = false,
                    draftConflict = false,
                ))
            }
            "updated", "presented", "edited", "validation", "open_url" -> {
                if (!reply.renderAllowed) {
                    publish(slot, current.copy(fallback = fallback, renderAllowed = false, tree = null, resumed = false, lifecycle = CardLifecycle.UNAVAILABLE, hostRequest = null))
                    return true
                }
                val batch = reply.batch
                val tree = if (batch == null) current.tree else (current.tree?.apply(batch) ?: return false)
                val signed = reply.result == "updated"
                publish(slot, current.copy(
                    fallback = fallback,
                    tree = tree,
                    lifecycle = tree?.let(::lifecycleOf) ?: current.lifecycle,
                    hostRequest = if (reply.result == "open_url") reply.hostRequest else if (signed) null else current.hostRequest,
                    awaitingDecision = if (signed) false else current.awaitingDecision,
                    draftConflict = reply.draftConflict,
                ))
            }
            "submitted" -> publish(slot, current.copy(lifecycle = CardLifecycle.CONSUMED, draftConflict = reply.draftConflict))
            "approval_decision" -> publish(slot, current.copy(awaitingDecision = true, hostRequest = null))
            "suspended" -> publish(slot, current.copy(resumed = false, lifecycle = CardLifecycle.SUSPENDED, hostRequest = null))
            "superseded" -> publish(slot, current.copy(resumed = false, lifecycle = CardLifecycle.SUPERSEDED, hostRequest = null))
            "refused" -> publish(slot, current.copy(
                lifecycle = when (reply.reason) {
                    "card_session_limit" -> CardLifecycle.OVER_LIMIT
                    "card_session_inactive" -> CardLifecycle.CLOSED
                    "card_superseded" -> CardLifecycle.SUPERSEDED
                    else -> current.lifecycle
                },
                resumed = if (reply.reason == "card_session_inactive" || reply.reason == "card_superseded") false else current.resumed,
            ))
            // Pending or stale results change nothing the host shows; the caller reads the submit result.
            else -> Unit
        }
        return true
    }

    /** A host slot answer: a link answered with `opened`/`cancelled`/`unavailable`, or an approval decision. */
    private fun answered(slot: Slot, reply: CardReply) {
        val current = slot.state
        when (reply.result) {
            "approval_decision" -> publish(slot, current.copy(awaitingDecision = true, hostRequest = null))
            "opened", "cancelled", "unavailable" -> publish(slot, current.copy(hostRequest = null))
            "refused" -> publish(slot, current.copy(hostRequest = null))
            else -> Unit
        }
    }

    private fun publish(slot: Slot, next: CardState) {
        slot.state = next
        counter.value = counter.value + 1
    }

    private fun lifecycleOf(tree: CardTree): CardLifecycle = if (tree.generating) CardLifecycle.GENERATING else CardLifecycle.READY

    private fun isLimit(call: CardCall): Boolean = call is CardCall.Replied && call.reply.result == "refused" && call.reply.reason == "card_session_limit"

    /** The runtime no longer holds the session (closed account or superseded handle); a transport failure is treated the same. */
    private fun isInactive(call: CardCall): Boolean =
        call is CardCall.Failed || (call is CardCall.Replied && call.reply.result == "refused" && call.reply.reason == "card_session_inactive")

    private fun startWatch(account: String) {
        val acc = accountOf(account)
        synchronized(guard) {
            if (acc.watch?.isActive == true) return
            acc.watch = scope.launch { watch(account) }
        }
    }

    /** Surface events of one account: a changed surface is re-read, a rejected or resync-needed one is out of sync. */
    private suspend fun watch(account: String) {
        val filter = SubscriptionFilter(account, buildJsonObject { put("topics", buildJsonArray { WATCH_TOPICS.forEach { add(JsonPrimitive(it)) } }) })
        transport.updates(filter).collect { update ->
            when (update) {
                is CoreUpdate.Events -> onSurfaceEvent(account, update.body)
                is CoreUpdate.Terminal -> if (update.kind == "resync_required") onResync(account)
                is CoreUpdate.Failure -> Unit
            }
        }
    }

    private suspend fun onSurfaceEvent(account: String, body: SecretReply) {
        val envelope = readSecret(body) ?: return
        val topic = envelope.text("method") ?: return
        val meta = envelope["body"] as? JsonObject ?: return
        val ref = meta.text("surface_ref") ?: return
        val acc = accountOf(account)
        acc.lock.withLock {
            val slot = peekSlot(acc, ref) ?: return@withLock
            when {
                topic == "surface.rejected" || topic == "surface.resync" || meta.flag("out_of_sync") == true -> {
                    if (slot.state.resumed) suspendSlot(acc, account, slot)
                    publish(slot, slot.state.copy(lifecycle = CardLifecycle.OUT_OF_SYNC))
                }
                topic == "surface.changed" && meta.flag("removed") == true -> disposeSlot(acc, account, slot)
                topic == "surface.changed" || topic == "surface.rpc.conflict" -> refresh(acc, account, slot)
            }
        }
    }

    /** The feed reported a gap: every resumed card re-reads its update. */
    private suspend fun onResync(account: String) {
        val acc = accountOf(account)
        acc.lock.withLock {
            val resumed = synchronized(guard) { acc.slots.values.filter { it.state.resumed } }
            for (slot in resumed) refresh(acc, account, slot)
        }
    }

    private fun readSecret(reply: CoreResult<SecretReply>): JsonObject? = when (reply) {
        is CoreResult.Failure -> null
        is CoreResult.Success -> readSecret(reply.value)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readSecret(reply: SecretReply): JsonObject? = try {
        reply.useOnce { raw -> (json.decodeFromStream(JsonElement.serializer(), ByteArrayInputStream(raw)) as? JsonObject) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } finally {
        reply.close()
    }

    private fun usable(value: String) = value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= MAX_ID
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, CardSessionClient.INVALID_CODE, ClientOutcome.NONE))
    private fun failed(code: Int) = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, code, ClientOutcome.UNKNOWN))

    companion object {
        const val MAX_RESUMED = 2
        const val MAX_OPEN = 24
        private const val PAGE = 50
        private const val MAX_CURSOR = 8192
        private const val MAX_ID = 256
        private const val FAILED_CODE = 16
        private val WATCH_TOPICS = listOf("surface.changed", "surface.rejected", "surface.resync", "surface.rpc.conflict")

        private val json = Json { ignoreUnknownKeys = true }
    }
}

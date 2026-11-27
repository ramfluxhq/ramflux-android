// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import org.ramflux.data.isAppLocked
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

    /**
     * Sends one host event for the control at [key] of the shown tree. Events of one card are queued and applied one at a
     * time in the order they were made, so the runtime sees what the person did in that order. An event is dropped with the
     * result `stale_dropped` when the held tree is not the one [seen] (the token is built from the tree the person saw, not
     * a later one). Two kinds follow this card's own earlier events and are re-sent with the token of the current tree: an
     * `input` (it carries the whole value) and an `activate` whose only later steps came from `input` events queued before
     * the tap; both only while the control is still the same node kind and `input_pointer` as when the person saw it.
     * An `input` that finds an `input` for the same control at the tail (or consecutive ones at the head) replaces it, and
     * the replaced one answers `coalesced`. Any event that is not applied (stale, queue full, consumer cancelled, card gone)
     * answers `stale_dropped`, so the control reverts and the host asks for the tap again.
     */
    suspend fun event(account: String, ref: String, key: String, event: CardEvent, seen: CardStamp): ClientResult<CardReply>

    /**
     * Answers a host slot (link or approval) with [outcome]; [token], [slotId] and [requestId] come from the tree or the host
     * request. Queued with the card's events; an answer that is not applied answers `stale_dropped`.
     */
    suspend fun slot(
        account: String, ref: String, slotId: String, requestId: String, token: JsonObject, outcome: CardSlotOutcome,
    ): ClientResult<CardReply>

    /** The one networked call: submits the shown action of a consumed Button, queued with the card's events. Never retried automatically. */
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
    override suspend fun event(account: String, ref: String, key: String, event: CardEvent, seen: CardStamp): ClientResult<CardReply> = unavailable
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

    /** Accounts that signed out or were removed; no entry is created for them until [admit] (an explicit sign-in). */
    private val signedOut = HashSet<String>()
    private val counter = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = counter.asStateFlow()

    private class Account(val lock: Mutex) {
        /** Access order: iteration starts with the least recently used card. Only [touchSlot] reorders it. */
        val slots = LinkedHashMap<String, Slot>(16, 0.75f, true)
        var watch: Job? = null

        /** Set under [lock] by forget: a call that waited on the lock of a signed-out account must not touch the runtime. */
        @Volatile var retired = false

        /** Operations waiting to be applied, per card; each queue instance has at most one consumer ([drain]). */
        val queues = HashMap<String, CardQueue>()
        var eventSeq = 0L
    }

    /** What the person asked of one card: a control event, a host slot answer, or the submit of a pending action. */
    private sealed interface Op {
        class Event(val key: String, val event: CardEvent, val seen: CardStamp) : Op
        class Answer(val slotId: String, val requestId: String, val token: JsonObject, val outcome: CardSlotOutcome) : Op
        data object Submit : Op
    }

    /** One thing the person did, waiting for its turn. [seq] orders it against every other operation of the account. */
    private class Queued(val op: Op, val seq: Long, val done: CompletableDeferred<ClientResult<CardReply>>) {
        /** The control key when this is an `input` event, else null: only those are ever replaced by a newer one. */
        val inputKey: String? get() = (op as? Op.Event)?.takeIf { it.event is CardEvent.Input }?.key
    }

    /**
     * The operations of one card waiting to be applied. Each instance has at most one consumer ([drain]) and is mapped in
     * [Account.queues] from its creation until that consumer finds it empty or gives up; a later operation then creates a
     * fresh instance with its own consumer, so one consumer never touches another's queue.
     */
    private class CardQueue {
        val items = ArrayDeque<Queued>()
        var draining = false

        /** The operation the consumer took and has not answered yet; answered by [abandon] if the consumer dies. */
        var current: Queued? = null
    }

    /** What the held tree says about one node: enough to tell that a control is still the control the person saw. */
    private data class NodeSig(val kind: String, val pointer: String?)

    /** One stamp the held tree passed through; [producerSeq] is the own event whose reply made it (0 for the chain base). */
    private class Step(val stamp: CardStamp, val sigs: Map<String, NodeSig>, val producerSeq: Long, val byInput: Boolean)

    private class Slot(val ref: String, var locale: String, var dark: Boolean, state: CardState) {
        @Volatile var state: CardState = state
        var session: Long? = null
        var resumeOnForeground = false

        /**
         * The stamps the held tree passed through while absorbing replies to this card's own events, oldest first (the first
         * is the tree the chain started from), each with the node signatures of that tree. Any outside change (update,
         * re-read, resume, present, open) empties it.
         */
        val chain = ArrayList<Step>()

        fun resetChain() = chain.clear()
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
        val acc = accountOf(account) ?: return invalid()
        return acc.exclusive(account, invalid()) {
            val held = touchSlot(acc, ref)
            when {
                held == null -> open(acc, account, ref, locale, dark)
                held.state.resumed -> ClientResult.Ready(Unit)
                else -> resume(acc, account, held)
            }
        }
    }

    override suspend fun event(account: String, ref: String, key: String, event: CardEvent, seen: CardStamp): ClientResult<CardReply> {
        if (!usable(account) || !usable(ref)) return invalid()
        return enqueue(account, ref, Op.Event(key, event, seen))
    }

    /**
     * Queues [op] behind everything the person already did on this card and waits for its answer. An `input` that finds an
     * `input` for the same control at the tail takes its place (the older one answers `coalesced`), so typing never fills
     * the queue. Anything else that finds the queue full is answered as dropped (see [dropped]), never silently lost.
     */
    private suspend fun enqueue(account: String, ref: String, op: Op): ClientResult<CardReply> {
        val acc = accountOf(account) ?: return invalid()
        val done = CompletableDeferred<ClientResult<CardReply>>()
        var replaced: Queued? = null
        var start: CardQueue? = null
        val accepted = synchronized(guard) {
            val queue = acc.queues.getOrPut(ref) { CardQueue() }
            val key = (op as? Op.Event)?.takeIf { it.event is CardEvent.Input }?.key
            val tail = queue.items.lastOrNull()
            if (key != null && tail != null && tail.inputKey == key) {
                replaced = queue.items.removeLast()
            } else if (queue.items.size >= MAX_QUEUED) {
                return@synchronized false
            }
            acc.eventSeq += 1
            queue.items.addLast(Queued(op, acc.eventSeq, done))
            if (!queue.draining) {
                queue.draining = true
                start = queue
            }
            true
        }
        replaced?.done?.complete(ClientResult.Ready(coalesced()))
        if (!accepted) return dropped(op, started = false)
        start?.let { queue ->
            // A consumer that ends abnormally (cancelled, failed, or never started) answers everything it still holds.
            scope.launch { drain(acc, account, ref, queue) }.invokeOnCompletion { cause -> if (cause != null) abandon(acc, ref, queue) }
        }
        return done.await()
    }

    /** The one consumer of [queue]: applies the operations one by one, in the order they were made. */
    private suspend fun drain(acc: Account, account: String, ref: String, queue: CardQueue) {
        while (true) {
            val skipped = ArrayList<Queued>()
            val next = synchronized(guard) { takeNext(acc, ref, queue, skipped).also { queue.current = it } }
            skipped.forEach { it.done.complete(ClientResult.Ready(coalesced())) }
            val item = next ?: return
            val result = acc.exclusive(account, invalid()) { apply(acc, account, ref, item) }
            synchronized(guard) { queue.current = null }
            item.done.complete(result)
        }
    }

    /**
     * The consumer of [queue] ended abnormally: every operation it still holds is answered as dropped, and the queue is
     * unmapped only while it is still the card's queue (a later instance belongs to another consumer).
     */
    private fun abandon(acc: Account, ref: String, queue: CardQueue) {
        val (current, rest) = synchronized(guard) {
            if (acc.queues[ref] === queue) acc.queues.remove(ref)
            queue.draining = false
            val held = queue.current to queue.items.toList()
            queue.current = null
            queue.items.clear()
            held
        }
        current?.let { it.done.complete(dropped(it.op, started = true)) }
        rest.forEach { it.done.complete(dropped(it.op, started = false)) }
    }

    /**
     * The answer to an operation that was never applied. A control event or slot answer reads as `stale_dropped`, so the
     * control reverts and the host asks for the tap again. A submit is never retried: one that may have reached the runtime
     * ([started]) has an unknown outcome, one that never left the queue has none.
     */
    private fun dropped(op: Op, started: Boolean): ClientResult<CardReply> = when (op) {
        is Op.Event, is Op.Answer -> ClientResult.Ready(staleDropped())
        Op.Submit -> if (started) failed(FAILED_CODE) else invalid()
    }

    /**
     * The next operation of [queue], or null (and the queue is unmapped, if still mapped) when none is waiting. Only
     * consecutive `input` events for one control at the head are replaced by the newest; nothing else is jumped over.
     */
    private fun takeNext(acc: Account, ref: String, queue: CardQueue, skipped: MutableList<Queued>): Queued? {
        var head: Queued = queue.items.removeFirstOrNull() ?: run {
            if (acc.queues[ref] === queue) acc.queues.remove(ref)
            queue.draining = false
            return null
        }
        while (true) {
            val key = head.inputKey ?: break
            val after = queue.items.firstOrNull() ?: break
            if (after.inputKey != key) break
            skipped += head
            head = queue.items.removeFirst()
        }
        return head
    }

    private suspend fun apply(acc: Account, account: String, ref: String, item: Queued): ClientResult<CardReply> = when (val op = item.op) {
        is Op.Event -> applyEvent(acc, account, ref, item, op)
        is Op.Answer -> applyAnswer(acc, account, ref, item, op)
        Op.Submit -> applySubmit(acc, account, ref)
    }

    private suspend fun applyEvent(acc: Account, account: String, ref: String, item: Queued, op: Op.Event): ClientResult<CardReply> {
        val input = op.event is CardEvent.Input
        val slot = touchSlot(acc, ref)?.takeIf { it.state.resumed }
        val tree = slot?.state?.tree
        val session = slot?.session
        // The card is gone or suspended: the event is dropped visibly, so the control reverts and a tap is asked again.
        if (slot == null || tree == null || session == null) return ClientResult.Ready(staleDropped())
        // The tree moved on after the person acted: only an event that follows this card's own events is re-sent.
        if (tree.stamp != op.seen && !rebasable(slot, tree, item, op)) return ClientResult.Ready(staleDropped())
        return when (val call = client.event(account, session, tree.tokenFor(op.key), op.event)) {
            is CardCall.Failed -> ClientResult.Failed(call.problem)
            is CardCall.Replied -> {
                if (!absorbOwn(slot, call.reply, tree, item.seq, input)) reread(acc, account, slot)
                ClientResult.Ready(call.reply)
            }
        }
    }

    /**
     * True when the person's stamp is one the held tree passed through because of this card's own events, the card is ready,
     * and the control is still the same node (kind and `input_pointer`) as in the tree the person saw. An `input` carries the
     * whole value, so any later own step is fine. An `activate` is re-sent only when every later step came from an `input`
     * queued before the tap, so the tap still means what it meant; any other event gets the strict stamp check.
     */
    private fun rebasable(slot: Slot, tree: CardTree, item: Queued, op: Op.Event): Boolean {
        val event = op.event
        if (event !is CardEvent.Input && event !is CardEvent.Activate) return false
        if (slot.state.lifecycle != CardLifecycle.READY) return false
        val index = slot.chain.indexOfFirst { it.stamp == op.seen }
        if (index < 0) return false
        val seenSig = slot.chain[index].sigs[op.key] ?: return false
        val nowSig = tree.nodes[op.key]?.let { NodeSig(it.kind, it.attrs.text("input_pointer")) } ?: return false
        if (seenSig != nowSig) return false
        if (event is CardEvent.Input) return nowSig.pointer != null
        return slot.chain.drop(index + 1).all { it.byInput && it.producerSeq < item.seq }
    }

    /** Absorbs the reply to this card's own event and extends the stamp chain with the tree it produced. */
    private fun absorbOwn(slot: Slot, reply: CardReply, before: CardTree, seq: Long, byInput: Boolean): Boolean {
        val chain = ArrayList(slot.chain)
        if (!absorb(slot, reply)) return false
        val tree = slot.state.tree
        if (tree == null || !slot.state.resumed) return true
        slot.chain.addAll(chain)
        if (slot.chain.isEmpty()) slot.chain.add(Step(before.stamp, sigsOf(before), 0L, false))
        if (slot.chain.last().stamp != tree.stamp) slot.chain.add(Step(tree.stamp, sigsOf(tree), seq, byInput))
        while (slot.chain.size > MAX_CHAIN) slot.chain.removeAt(0)
        return true
    }

    private fun sigsOf(tree: CardTree): Map<String, NodeSig> = tree.nodes.mapValues { NodeSig(it.value.kind, it.value.attrs.text("input_pointer")) }

    override suspend fun slot(
        account: String, ref: String, slotId: String, requestId: String, token: JsonObject, outcome: CardSlotOutcome,
    ): ClientResult<CardReply> {
        if (!usable(account) || !usable(ref) || slotId.isEmpty() || requestId.isEmpty()) return invalid()
        // Queued with the card's events, so the runtime sees the answer exactly where the person gave it.
        return enqueue(account, ref, Op.Answer(slotId, requestId, token, outcome))
    }

    private suspend fun applyAnswer(acc: Account, account: String, ref: String, item: Queued, op: Op.Answer): ClientResult<CardReply> {
        val slot = touchSlot(acc, ref)?.takeIf { it.state.resumed } ?: return ClientResult.Ready(staleDropped())
        val session = slot.session ?: return ClientResult.Ready(staleDropped())
        return when (val call = client.slot(account, session, op.token, op.slotId, op.requestId, op.outcome)) {
            is CardCall.Failed -> ClientResult.Failed(call.problem)
            is CardCall.Replied -> {
                answered(acc, account, slot, call.reply, item.seq)
                ClientResult.Ready(call.reply)
            }
        }
    }

    override suspend fun submit(account: String, ref: String): ClientResult<CardReply> {
        if (!usable(account) || !usable(ref)) return invalid()
        // The pending action was signed when the person tapped; the send still waits for what they did before asking for it.
        return enqueue(account, ref, Op.Submit)
    }

    private suspend fun applySubmit(acc: Account, account: String, ref: String): ClientResult<CardReply> {
        val slot = touchSlot(acc, ref)?.takeIf { it.state.resumed } ?: return invalid()
        val session = slot.session ?: return invalid()
        publish(slot, slot.state.copy(lifecycle = CardLifecycle.SUBMITTING))
        return when (val call = client.submit(account, session)) {
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

    override suspend fun present(account: String, locale: String, dark: Boolean): ClientResult<Unit> {
        if (!usable(account)) return invalid()
        val acc = accountOf(account) ?: return invalid()
        return acc.exclusive(account, invalid()) {
            val resumed = synchronized(guard) { acc.slots.values.filter { it.state.resumed } }
            for (slot in resumed) {
                val session = slot.session ?: continue
                slot.locale = locale
                slot.dark = dark
                when (val call = client.present(account, session, locale, dark)) {
                    is CardCall.Failed -> return@exclusive ClientResult.Failed(call.problem)
                    is CardCall.Replied -> if (!absorb(slot, call.reply)) reread(acc, account, slot)
                }
            }
            ClientResult.Ready(Unit)
        }
    }

    override suspend fun dispose(account: String, ref: String) {
        if (!usable(account) || !usable(ref)) return
        val acc = accountOf(account) ?: return
        acc.exclusive(account, Unit) { peekSlot(acc, ref)?.let { disposeSlot(acc, account, it) } }
    }

    /**
     * The account left this device (sign-out or removal): its sessions are disposed and its slots dropped, so no draft or
     * tree outlives it.
     */
    suspend fun forget(account: String) {
        if (!usable(account)) return
        // Remembered even when no entry exists yet: a late show must not open a session for an account that left.
        val acc = synchronized(guard) {
            signedOut.add(account)
            accounts[account]
        } ?: return
        acc.lock.withLock {
            if (acc.retired) return
            // Anything queued on this entry now finds it retired and returns without opening a runtime session.
            acc.retired = true
            val held = synchronized(guard) {
                acc.watch?.cancel()
                acc.watch = null
                acc.slots.values.toList().also { acc.slots.clear() }
            }
            for (slot in held) slot.session?.let { client.dispose(account, it) }
            synchronized(guard) { if (accounts[account] === acc) accounts.remove(account) }
            counter.value = counter.value + 1
        }
    }

    /** Background: stop the surface feed, then suspend every resumed card. Foreground resumes the most recent ones again. */
    override suspend fun background() {
        val held = synchronized(guard) { accounts.entries.map { it.key to it.value } }
        for ((name, acc) in held) acc.exclusive(name, Unit) {
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
        for ((name, acc) in held) acc.exclusive(name, Unit) {
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

    /** The entry of [account], created on first use; null for an account that signed out and has not signed in again. */
    private fun accountOf(account: String): Account? = synchronized(guard) {
        if (account in signedOut) null else accounts.getOrPut(account) { Account(Mutex()) }
    }

    /** The account signed in again on purpose (unlock or switch): cards may open for it once more. */
    fun admit(account: String) {
        synchronized(guard) { signedOut.remove(account) }
    }

    /**
     * Runs [body] under the account's lock, unless the entry was retired (forget) or replaced while the caller waited:
     * then [gone] is returned and the runtime is not touched, so a signed-out account never gets a new session.
     */
    private suspend fun <T> Account.exclusive(account: String, gone: T, body: suspend () -> T): T = lock.withLock {
        if (retired || synchronized(guard) { accounts[account] } !== this) gone else body()
    }

    /** Reads the card and makes it most recently used. Used by show, event, slot and submit only. */
    private fun touchSlot(acc: Account, ref: String): Slot? = synchronized(guard) { acc.slots[ref] }

    /** Finds the card without changing the access order (iteration does not reorder a LinkedHashMap). */
    private fun peekSlot(acc: Account, ref: String): Slot? = synchronized(guard) { acc.slots.values.firstOrNull { it.ref == ref } }

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
        // Any other failure keeps the runtime session, and the draft with it, for the next attempt.
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
        slot.resetChain()
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
        // The card being (re)opened does not count: a re-open at the maximum must not evict an unrelated card.
        while (synchronized(guard) { acc.slots.values.count { it.ref != except } } >= MAX_OPEN) {
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
        // Any change that is not the answer to this card's own event (the caller restores the chain for those) ends the chain.
        slot.resetChain()
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

    /**
     * A host slot answer: a link answered with `opened`/`cancelled`/`unavailable` changes only the host request; any other
     * answer (an edited batch, an approval decision, a refusal) goes through the same absorb as an event's reply, so the
     * held tree and the stamp chain stay in step with the runtime.
     */
    private suspend fun answered(acc: Account, account: String, slot: Slot, reply: CardReply, seq: Long) {
        val before = slot.state.tree
        when (reply.result) {
            "opened", "resumed", "cancelled", "unavailable" -> publish(slot, slot.state.copy(hostRequest = null))
            else -> {
                if (before != null && !absorbOwn(slot, reply, before, seq, false)) reread(acc, account, slot)
                if (slot.state.hostRequest != null && reply.result != "open_url") publish(slot, slot.state.copy(hostRequest = null))
            }
        }
    }

    private fun publish(slot: Slot, next: CardState) {
        slot.state = next
        counter.value = counter.value + 1
    }

    private fun lifecycleOf(tree: CardTree): CardLifecycle = if (tree.generating) CardLifecycle.GENERATING else CardLifecycle.READY

    private fun isLimit(call: CardCall): Boolean = call is CardCall.Replied && call.reply.result == "refused" && call.reply.reason == "card_session_limit"

    /** The runtime no longer holds the session: the explicit inactive refusal, or a failure that names a closed (locked) account. */
    private fun isInactive(call: CardCall): Boolean =
        (call is CardCall.Failed && call.problem.isAppLocked()) || (call is CardCall.Replied && call.reply.result == "refused" && call.reply.reason == "card_session_inactive")

    private fun startWatch(account: String) {
        val acc = accountOf(account) ?: return
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
        val acc = synchronized(guard) { accounts[account] } ?: return
        acc.exclusive(account, Unit) {
            val slot = peekSlot(acc, ref) ?: return@exclusive
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
        val acc = synchronized(guard) { accounts[account] } ?: return
        acc.exclusive(account, Unit) {
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
    /** A dropped event the view reports as stale, so the control it came from reverts to the value the runtime holds. */
    private fun staleDropped() = CardReply("stale_dropped", null, true, "", null, null, null, false)

    /** An Input replaced by a newer one for the same control before it was sent; nothing changed. */
    private fun coalesced() = CardReply("coalesced", null, true, "", null, null, null, false)

    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, CardSessionClient.INVALID_CODE, ClientOutcome.NONE))
    private fun failed(code: Int) = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, code, ClientOutcome.UNKNOWN))

    companion object {
        const val MAX_RESUMED = 2
        const val MAX_OPEN = 24
        private const val MAX_CHAIN = 64
        private const val MAX_QUEUED = 64
        private const val PAGE = 50
        private const val MAX_CURSOR = 8192
        private const val MAX_ID = 256
        private const val FAILED_CODE = 16
        private val WATCH_TOPICS = listOf("surface.changed", "surface.rejected", "surface.resync", "surface.rpc.conflict")

        private val json = Json { ignoreUnknownKeys = true }
    }
}

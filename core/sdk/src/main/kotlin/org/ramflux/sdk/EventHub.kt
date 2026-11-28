// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The shared event fan-out. The C ABI has one event queue per core, so every collector in the app
 * (two panes, the shell, background work) shares one native subscription whose topics are the union
 * of what the collectors asked for.
 *
 * - Back-pressure: each collector has its own bounded buffer. The native pump never waits for a
 *   collector; a collector that falls [capacity] events behind loses its buffered events and gets a
 *   `resync_required` terminal of its own, while the other collectors continue unaffected.
 * - Automatic resync: on native overflow (status 27) or an in-band `stream.lagged` frame the hub
 *   resets the native queue itself, tells every collector `resync_required` (they reload their
 *   snapshots) and reopens the subscription. Collectors stay registered across the reopen, so no
 *   event after the reopen is lost; [CoreUpdate.Terminal] does not end a collector's flow.
 * - A collector whose topics are not yet covered reopens the shared subscription with the union;
 *   the existing collectors get `resync_required` because the reopen starts at the journal head.
 * - The subscription follows one account. A collector for another account replaces it; collectors
 *   of the previous account receive [CoreError.Closed] and end.
 * - The shared subscription is cancelled when its last collector leaves.
 *
 * Only the `topics` field of a filter body is honoured; any other field is refused, because a
 * per-collector cursor cannot be expressed on a shared subscription.
 */
class EventHub internal constructor(
    private val upstream: (SubscriptionFilter) -> Flow<CoreUpdate>,
    private val reset: suspend (ULong) -> CoreResult<JsonObject>,
    private val scope: CoroutineScope,
    private val capacity: Int = SUBSCRIBER_CAPACITY,
    private val detach: suspend () -> Unit = {},
) {
    private class Subscriber(val account: String?, val topics: Set<String>, val channel: Channel<CoreUpdate>)

    private val lock = Any()
    private val subscribers = LinkedHashSet<Subscriber>()
    private var pump: Job? = null
    private var pumpAccount: String? = null
    private var pumpTopics: Set<String> = emptySet()
    private var generation = 0uL
    private var paused = false
    private var detached = 0
    // Pumps that were cancelled but have not finished yet: their native subscription is still open or closing.
    private val stopping = LinkedHashSet<Job>()

    /** Number of collectors currently registered (for tests and diagnostics). */
    val collectorCount: Int get() = synchronized(lock) { subscribers.size }

    fun subscribe(filter: SubscriptionFilter): Flow<CoreUpdate> = flow {
        val topics = topicsOf(filter.body)
        if (topics == null) {
            emit(CoreUpdate.Failure(CoreError.InvalidArgument(Effect.NONE)))
            return@flow
        }
        val subscriber = Subscriber(filter.accountId, topics, Channel(capacity + 1))
        register(subscriber)
        try {
            for (update in subscriber.channel) emit(update)
        } finally {
            unregister(subscriber)
            drain(subscriber.channel)
        }
    }

    /**
     * The client is suspending: the pump stops without telling its collectors (they stay registered and keep their
     * flows open). [resume] restarts it and asks every collector to reload.
     */
    suspend fun pause() {
        val job = synchronized(lock) {
            paused = true
            retire(pump).also { pump = null }
        }
        job?.join()
    }

    /** The client resumed: the pump reopens from the journal head and every collector gets `resync_required`. */
    fun resume() = synchronized(lock) {
        paused = false
        if (detached == 0 && pump == null && subscribers.isNotEmpty()) {
            val account = pumpAccount
            start(account, union(subscribers.filter { it.account == account }))
            subscribers.forEach { offer(it, resync()) }
        }
    }

    /**
     * Runs [block] while the shared subscription is not attended for an account other than [account] (null: for any
     * account). Core refuses app-lock calls for another account on an attended connection (`refuse_attended`).
     */
    suspend fun <T> unattended(account: String?, block: suspend () -> T): T {
        // The lease always holds: while it runs no pump is started, and a collector that registers meanwhile detaches
        // a live pump. Before the block runs, every pump that is cancelled but not finished is awaited, whether or not
        // this call cancelled it, so core no longer sees an attended mark for another account.
        val pending = synchronized(lock) {
            val current = pumpAccount
            val needed = detached > 0 || (pump != null && current != null && (account == null || account != current))
            detached++
            if (needed) {
                retire(pump)
                pump = null
            }
            stopping.toList()
        }
        try {
            pending.forEach { it.join() }
            // A pump that ended on its own (a refused subscribe, a lock) can leave core's attended mark behind with no
            // subscription the hub knows of: with no pump running, the core subscription is closed explicitly.
            if (synchronized(lock) { pump == null }) detach()
            return block()
        } finally {
            withContext(NonCancellable) {
                synchronized(lock) {
                    if (detached > 0) detached--
                    if (detached == 0) {
                        if (!paused && pump == null && subscribers.isNotEmpty()) {
                            val current = pumpAccount
                            start(current, union(subscribers.filter { it.account == current }))
                            subscribers.forEach { offer(it, resync()) }
                        }
                    }
                }
            }
        }
    }

    private fun register(subscriber: Subscriber) = synchronized(lock) {
        if (paused || detached > 0) {
            // Collectors that join while the client is suspended or leased wait for [resume] or the lease end; a live
            // pump (lease that did not detach) stops so the lease end restarts it with the new topics or account.
            retire(pump)
            pump = null
            pumpTopics = emptySet()
            if (pumpAccount != subscriber.account) {
                subscribers.filter { it.account != subscriber.account }.forEach { other ->
                    subscribers.remove(other)
                    drain(other.channel)
                    other.channel.trySend(CoreUpdate.Failure(CoreError.Closed()))
                    other.channel.close()
                }
                pumpAccount = subscriber.account
            }
            subscribers.add(subscriber)
            return@synchronized
        }
        val running = pump?.isActive == true
        if (running && pumpAccount != subscriber.account) {
            subscribers.filter { it.account != subscriber.account }.forEach { other ->
                subscribers.remove(other)
                drain(other.channel)
                other.channel.trySend(CoreUpdate.Failure(CoreError.Closed()))
                other.channel.close()
            }
        }
        subscribers.add(subscriber)
        val wanted = union(subscribers.filter { it.account == subscriber.account })
        val covered = running && pumpAccount == subscriber.account && covers(pumpTopics, subscriber.topics)
        if (!covered) {
            val restarting = running && pumpAccount == subscriber.account
            retire(pump)
            if (restarting) {
                subscribers.filter { it !== subscriber }.forEach { offer(it, resync()) }
            }
            start(subscriber.account, wanted)
        }
    }

    private fun unregister(subscriber: Subscriber) = synchronized(lock) {
        subscribers.remove(subscriber)
        if (subscribers.isEmpty()) {
            retire(pump)
            pump = null
            pumpTopics = emptySet()
        }
    }

    /** Cancels [job] and tracks it until it has finished, so later pumps and leases can wait for its cleanup. */
    private fun retire(job: Job?): Job? {
        if (job == null) return null
        job.cancel()
        synchronized(lock) {
            if (stopping.add(job)) job.invokeOnCompletion { synchronized(lock) { stopping.remove(job) } }
        }
        return job
    }

    private fun start(account: String?, topics: Set<String>) {
        retire(pump)
        val previous = stopping.toList()
        pumpAccount = account
        pumpTopics = topics
        pump = scope.launch {
            // The native lane admits one subscription: every cancelled pump finishes its cleanup first.
            previous.forEach { it.join() }
            pumpLoop(account, topics, coroutineContext.job)
        }
    }

    private suspend fun CoroutineScope.pumpLoop(account: String?, topics: Set<String>, self: Job) {
        // The app is a human frontend that subscribes only while a screen is shown, so its subscription is
        // attended: the SDK admits backup jobs from an attended connection (`bus/backup_jobs/routing.rs`).
        val body = buildJsonObject {
            put("topics", JsonArray(topics.sorted().map(::JsonPrimitive)))
            put("attended_frontend", JsonPrimitive(true))
        }
        while (isActive) {
            var resyncToken: ULong? = null
            var reopen = false
            var ended = false
            upstream(SubscriptionFilter(account, body)).takeWhile { update ->
                when (update) {
                    is CoreUpdate.Events -> {
                        generation = update.subscriptionGeneration
                        if (dispatch(update, self)) true else { reopen = true; false }
                    }
                    is CoreUpdate.Terminal -> {
                        if (update.kind == RESYNC) resyncToken = update.subscriptionGeneration
                        else { broadcastAndClose(update, self); ended = true }
                        false
                    }
                    is CoreUpdate.Failure -> { broadcastAndClose(update, self); ended = true; false }
                }
            }.collect()
            if (ended || !isActive) return
            val token = resyncToken
            if (token != null) {
                val outcome = reset(token)
                if (outcome is CoreResult.Failure) {
                    broadcastAndClose(CoreUpdate.Failure(outcome.error), self)
                    return
                }
                synchronized(lock) { if (pump === self) subscribers.forEach { offer(it, CoreUpdate.Terminal(RESYNC, null, token)) } }
            } else if (reopen) {
                synchronized(lock) { if (pump === self) subscribers.forEach { offer(it, resync()) } }
            } else {
                // The native stream ended without a terminal (closing core): collectors end too.
                closeAll(self)
                return
            }
        }
    }

    /** Copies each event into an owned buffer per matching collector. False asks for a resync. */
    private fun dispatch(update: CoreUpdate.Events, self: Job): Boolean = update.body.useOnce { raw ->
        val topic = topicOf(raw)
        if (topic == LAGGED) return@useOnce false
        synchronized(lock) {
            if (pump !== self) return@useOnce true
            for (subscriber in subscribers) {
                if (subscriber.account != pumpAccount) continue
                if (WILDCARD !in subscriber.topics && (topic == null || topic !in subscriber.topics)) continue
                val copy = CoreUpdate.Events(SecretReply(SecretBytes.take(raw.copyOf())), update.subscriptionGeneration)
                if (!subscriber.channel.trySend(copy).isSuccess) {
                    copy.body.close()
                    offer(subscriber, resync())
                }
            }
        }
        true
    }

    /** A terminal or failure always fits: a full buffer is dropped first (the collector reloads anyway). */
    private fun offer(subscriber: Subscriber, update: CoreUpdate) {
        if (!subscriber.channel.trySend(update).isSuccess) {
            drain(subscriber.channel)
            subscriber.channel.trySend(update)
        }
    }

    /** Only the current pump may end the collectors; a superseded pump's late outcome is ignored. */
    private fun broadcastAndClose(update: CoreUpdate, self: Job) = synchronized(lock) {
        if (pump !== self) return@synchronized
        subscribers.forEach { offer(it, update); it.channel.close() }
        subscribers.clear()
        pump = null
        pumpTopics = emptySet()
    }

    private fun closeAll(self: Job) = synchronized(lock) {
        if (pump !== self) return@synchronized
        subscribers.forEach { it.channel.close() }
        subscribers.clear()
        pump = null
        pumpTopics = emptySet()
    }

    private fun union(of: Collection<Subscriber>): Set<String> =
        of.flatMapTo(sortedSetOf()) { it.topics }.let { if (WILDCARD in it) setOf(WILDCARD) else it }

    private fun resync() = CoreUpdate.Terminal(RESYNC, null, generation)

    private fun drain(channel: Channel<CoreUpdate>) {
        while (true) {
            val next = channel.tryReceive().getOrNull() ?: break
            if (next is CoreUpdate.Events) next.body.close()
        }
    }

    companion object {
        const val SUBSCRIBER_CAPACITY = 64
        private const val RESYNC = "resync_required"
        private const val LAGGED = "stream.lagged"
        private const val WILDCARD = "*"

        internal fun topicsOf(body: JsonObject): Set<String>? {
            if (body.keys.any { it != "topics" }) return null
            val array = body["topics"] ?: return emptySet()
            if (array !is JsonArray) return null
            return array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull ?: return null }.toSet()
        }

        internal fun covers(have: Set<String>, want: Set<String>) = WILDCARD in have || have.containsAll(want)


        /** The event's topic is the frame's `method`; it is metadata, never message content. */
        private fun topicOf(raw: ByteArray): String? = try {
            val parser = ByteJson(raw)
            val method = parser.field(parser.parse(), "method")
            if (method == null || method.kind != 34) null else {
                val chars = parser.chars(method)
                try { String(chars) } finally { chars.fill('\u0000') }
            }
        } catch (_: Exception) { null }
    }
}

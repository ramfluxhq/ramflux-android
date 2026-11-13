// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Serializes foreign handle access, while the single SDK supervisor executes all business logic. */
class CoreClient internal constructor(
    private val native: NativeCore,
    private val core: Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val capabilities: CoreCapabilities? = null,
) {
    private val guard = Mutex()
    private val shutdown = Mutex()
    private var closeResult: CoreResult<JsonObject>? = null
    private var eventJob: Job? = null
    private var closed = false
    private var eventCollector = false
    private val operations = mutableSetOf<Long>()

    suspend fun execute(request: CoreRequest): CoreResult<JsonObject> = withContext(io) {
        currentCoroutineContext().ensureActive()
        // A cancelled continuation must not lose a handle allocated by submit.
        var operation = 0L
        var completed = false
        val caller = currentCoroutineContext()
        val deadline = request.deadlineUnixMs.takeIf { it > 0 } ?: (System.currentTimeMillis() + REQUEST_TIMEOUT_MS)
        try {
            withContext(NonCancellable) {
                guard.withLock {
                    caller.ensureActive()
                    if (capabilities != null && request.method !in capabilities.methods) {
                        throw SubmitRefused(CoreResult.Failure(CoreError.UnsupportedVersion(Effect.NONE), request.operationId))
                    }
                    if (!BoundedJson.accepts(request.body) || deadline <= 0) {
                        throw SubmitRefused(CoreResult.Failure(CoreError.InvalidArgument(Effect.NONE), request.operationId))
                    }
                    if (closed) return@withLock
                    val bytes = encodeRequest(request, deadline)
                    if (bytes.size > (1 shl 20)) {
                        bytes.fill(0)
                        throw SubmitRefused(CoreResult.Failure(CoreError.InvalidArgument(Effect.NONE), request.operationId))
                    }
                    val reply = try { native.submit(core, bytes) } finally { bytes.fill(0) }
                    if (reply.code != 0 || reply.handle == 0L) {
                        val failure = ReplyCodec.decode(reply, request.operationId)
                        throw SubmitRefused(failure as? CoreResult.Failure
                            ?: CoreResult.Failure(CoreError.InvalidResponse(), request.operationId))
                    }
                    reply.body.fill(0)
                    operation = reply.handle
                    operations.add(operation)
                }
            }
            if (operation == 0L) return@withContext CoreResult.Failure(CoreError.Closed(), request.operationId)
            var terminal: CoreResult<JsonObject>? = null
            while (terminal == null) {
                currentCoroutineContext().ensureActive()
                val reply = guard.withLock {
                    if (closed) return@withContext CoreResult.Failure(CoreError.Closed(Effect.UNKNOWN), request.operationId)
                    native.operationPoll(core, operation)
                }
                if (reply.code != PENDING) {
                    completed = true
                    terminal = ReplyCodec.decodeCompletion(reply, request.operationId)
                    continue
                }
                if (System.currentTimeMillis() >= deadline) {
                    reply.body.fill(0)
                    return@withContext CoreResult.Failure(CoreError.Native(10, Effect.UNKNOWN), request.operationId)
                }
                reply.body.fill(0)
                delay(POLL_INTERVAL_MS)
            }
            terminal
        } catch (refused: SubmitRefused) {
            refused.result
        } catch (_: CancellationException) {
            throw CoreCancellation(request.operationId)
        } finally {
            withContext(NonCancellable) {
                guard.withLock {
                    if (!closed && operation != 0L && operations.remove(operation)) {
                        if (!completed) native.operationCancel(core, operation)
                        native.operationRelease(core, operation)
                    }
                }
            }
        }
    }

    /** One event consumer owns the ABI queue. Cancellation stops polling; the queue stays bounded. */
    fun updates(filter: SubscriptionFilter): Flow<CoreUpdate> = callbackFlow {
        var rejection: CoreError? = null
        val pump = guard.withLock {
            if (closed || eventCollector) {
                rejection = if (closed) CoreError.Closed() else CoreError.Native(18, Effect.NONE)
                null
            } else {
            eventCollector = true
            launch(io, start = CoroutineStart.LAZY) {
            var subscription = 0L
            try {
                val request = CoreRequest(
                    "subscription.open", "subscription", filter.accountId, null, filter.body,
                    System.currentTimeMillis() + REQUEST_TIMEOUT_MS,
                )
                val submitted = guard.withLock {
                    if (closed || !BoundedJson.accepts(filter.body)) null else {
                        val bytes = encodeRequest(request)
                        val reply = try { native.submit(core, bytes) } finally { bytes.fill(0) }
                        if (reply.code == 0 && reply.handle != 0L) {
                            subscription = reply.handle
                            operations.add(subscription)
                        }
                        reply
                    }
                }
                if (submitted == null || subscription == 0L) {
                    val failure = submitted?.let { ReplyCodec.decode(it) } as? CoreResult.Failure
                    send(CoreUpdate.Failure(failure?.error ?: CoreError.Closed()))
                    close(); return@launch
                }
                submitted.body.fill(0)
                var ready = false
                while (!ready && isActive) {
                    val reply = guard.withLock { if (closed) null else native.operationPoll(core, subscription) } ?: break
                    if (reply.code == PENDING) {
                        reply.body.fill(0)
                        if (System.currentTimeMillis() >= request.deadlineUnixMs) {
                            send(CoreUpdate.Failure(CoreError.Native(10, Effect.UNKNOWN))); return@launch
                        }
                        delay(POLL_INTERVAL_MS)
                    } else when (val result = ReplyCodec.decodeCompletion(reply, null)) {
                        is CoreResult.Success -> ready = true
                        is CoreResult.Failure -> { send(CoreUpdate.Failure(result.error)); return@launch }
                    }
                }
                while (isActive) {
                    val reply = guard.withLock {
                        if (closed) null else native.eventPoll(core)
                    } ?: break
                    when (reply.code) {
                        PENDING -> { reply.body.fill(0); delay(POLL_INTERVAL_MS) }
                        RESYNC_REQUIRED -> {
                            reply.body.fill(0)
                            send(CoreUpdate.Terminal("resync_required", null)); break
                        }
                        else -> when (val result = ReplyCodec.decode(reply)) {
                            is CoreResult.Success -> send(CoreUpdate.Events(result.value))
                            is CoreResult.Failure -> { send(CoreUpdate.Failure(result.error)); break }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    guard.withLock {
                        if (!closed && subscription != 0L && operations.remove(subscription)) {
                            native.operationCancel(core, subscription)
                            native.operationRelease(core, subscription)
                        }
                        eventCollector = false
                        eventJob = null
                    }
                }
                close()
            }
            }.also { eventJob = it; it.start() }
            }
        }
        if (pump == null) {
            send(CoreUpdate.Failure(rejection ?: CoreError.Closed()))
            close()
            return@callbackFlow
        }
        awaitClose { pump.cancel() }
    }.buffer(0)

    /** Call only after re-reading projections following a gap; then subscribe from that cursor. */
    suspend fun resetEventsAfterSnapshot(): CoreResult<JsonObject> = withContext(io) {
        guard.withLock {
            if (closed || eventCollector) CoreResult.Failure(CoreError.Closed())
            else ReplyCodec.decode(NativeReply(native.eventReset(core), byteArrayOf()))
        }
    }

    /** Blocks only the IO worker. Once begun, shutdown completes despite caller cancellation. */
    suspend fun close(): CoreResult<JsonObject> = withContext(io + NonCancellable) {
        shutdown.withLock {
            closeResult?.let { return@withLock it }
            val pump = guard.withLock { closed = true; eventJob.also { it?.cancel() } }
            pump?.join()
            val result = guard.withLock {
                operations.forEach {
                    native.operationCancel(core, it)
                    native.operationRelease(core, it)
                }
                operations.clear()
                ReplyCodec.decode(native.close(core))
            }
            closeResult = result
            result
        }
    }

    private class SubmitRefused(val result: CoreResult<JsonObject>) : Exception()

    companion object {
        const val PENDING = 24
        const val RESYNC_REQUIRED = 27
        private const val POLL_INTERVAL_MS = 10L
        private const val REQUEST_TIMEOUT_MS = 30_000L

        internal fun encodeRequest(request: CoreRequest, deadline: Long = request.deadlineUnixMs): ByteArray = buildJsonObject {
            request.accountId?.let { put("account_id", it) }
            request.operationId?.let { put("operation_id", it) }
            put("sdk_api", request.sdkApi)
            put("method", request.method)
            put("body", request.body)
            // Exact decimal u64 handling is enforced by the SDK I-JSON parser.
            put("deadline_ms", deadline)
        }.toString().toByteArray()

        suspend fun open(
            native: NativeCore,
            dataRoot: String,
            expectedDescriptorFingerprint: String,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): CoreResult<CoreClient> {
            currentCoroutineContext().ensureActive()
            var unreturned = 0L
            try {
                val result = withContext(io + NonCancellable) {
                    val negotiation = ReplyCodec.decode(native.negotiate())
                    if (negotiation is CoreResult.Failure) return@withContext negotiation
                    val version = (negotiation as CoreResult.Success).value
                    val capabilities = CoreCapabilities.parse(version, expectedDescriptorFingerprint)
                        ?: return@withContext CoreResult.Failure(CoreError.UnsupportedVersion(Effect.NONE))
                    val bytes = buildJsonObject { put("data_root", dataRoot) }.toString().toByteArray()
                    val reply = try { native.open(bytes) } finally { bytes.fill(0) }
                    if (reply.code == 0 && reply.handle != 0L) {
                        reply.body.fill(0)
                        unreturned = reply.handle
                        CoreResult.Success(CoreClient(native, reply.handle, io, capabilities))
                    } else {
                        ReplyCodec.decode(reply) as? CoreResult.Failure
                            ?: CoreResult.Failure(CoreError.InvalidResponse())
                    }
                }
                currentCoroutineContext().ensureActive()
                unreturned = 0L
                return result
            } finally {
                if (unreturned != 0L) withContext(io + NonCancellable) { native.close(unreturned).body.fill(0) }
            }
        }
    }
}

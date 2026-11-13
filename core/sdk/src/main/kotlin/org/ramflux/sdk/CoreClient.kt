// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** All native handle access is serialized; the shared supervisor owns business execution. */
class CoreClient internal constructor(
    private val native: NativeCore,
    private val core: Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val capabilities: CoreCapabilities? = null,
) {
    private val guard = Mutex()
    private val shutdown = Mutex()
    private val eventLane = Mutex()
    private var closeResult: CoreResult<JsonObject>? = null
    private var eventJob: Job? = null
    private var closing = false
    private var freed = false
    private val operations = OperationHandles()
    private val pendingRelease = OperationHandles()
    private val generationState = MutableStateFlow(0uL)
    val subscriptionGeneration: StateFlow<ULong> = generationState.asStateFlow()
    private val cleanupState = MutableStateFlow<CoreError?>(null)
    /** A bounded cleanup failure stays visible, and its handle stays owned until retry or close. */
    val cleanupFailure: StateFlow<CoreError?> = cleanupState.asStateFlow()

    suspend fun execute(request: CoreRequest): CoreResult<JsonObject> {
        val policy = SdkMethodPolicy.methods[request.method]
        if (request.method in SUBSCRIPTION_METHODS || policy?.confidential == true) {
            return CoreResult.Failure(CoreError.InvalidArgument(Effect.NONE), request.operationId)
        }
        if (policy == null) return CoreResult.Failure(CoreError.UnsupportedVersion(Effect.NONE), request.operationId)
        return perform(request.method, request.operationId, request.deadlineUnixMs,
            encode = { deadline ->
                require(BoundedJson.accepts(request.body))
                encodeRequest(request, deadline)
            }, decode = { ReplyCodec.decodeCompletion(it, request.operationId) })
    }

    suspend fun executeSecret(request: SecretRequest): CoreResult<SecretReply> = try {
        if (request.method in SUBSCRIPTION_METHODS) CoreResult.Failure(CoreError.InvalidArgument(Effect.NONE), request.operationId)
        else if (SdkMethodPolicy.methods[request.method] == null) CoreResult.Failure(CoreError.UnsupportedVersion(Effect.NONE), request.operationId)
        else perform(request.method, request.operationId, request.deadlineUnixMs,
            encode = { deadline -> request.body.storage.useOnce { body -> encodeSecret(request, body, deadline) } },
            decode = { ReplyCodec.decodeSecret(it, request.operationId) })
    } finally { request.body.close() }

    private suspend fun <T> perform(
        method: String, operationId: String?, deadlineUnixMs: Long,
        encode: (Long) -> ByteArray, decode: (NativeReply) -> CoreResult<T>,
    ): CoreResult<T> {
        var unreturned: java.io.Closeable? = null
        try {
            val result = withContext(io) {
                val caller = currentCoroutineContext()
                caller.ensureActive()
                var operation = 0L
                val deadline = deadlineUnixMs.takeIf { it > 0 } ?: (System.currentTimeMillis() + REQUEST_TIMEOUT_MS)
                try {
                    val admission = withContext(NonCancellable) {
                        guard.withLock {
                            caller.ensureActive()
                            when {
                                closing -> CoreResult.Failure(CoreError.Closed(), operationId)
                                capabilities != null && method !in capabilities.methods -> CoreResult.Failure(CoreError.UnsupportedVersion(Effect.NONE), operationId)
                                else -> {
                                    val bytes = encode(deadline)
                                    require(bytes.size <= MAX_FRAME)
                                    val ownership = LongArray(1)
                                    val reply = try { native.submit(core, bytes, ownership) } finally {
                                        bytes.fill(0)
                                        operation = ownership[0]
                                        if (operation != 0L) operations.add(operation)
                                    }
                                    if (reply.code != 0 || operation == 0L) ReplyCodec.submitRefusal(reply, operationId)
                                    else { reply.body.fill(0); null }
                                }
                            }
                        }
                    }
                    if (admission != null) return@withContext admission
                    var pause = 10L
                    var terminal: CoreResult<T>? = null
                    while (terminal == null) {
                        caller.ensureActive()
                        val reply = guard.withLock { if (closing) null else native.operationPoll(core, operation) }
                            ?: return@withContext CoreResult.Failure(CoreError.Closed(Effect.UNKNOWN), operationId)
                        if (reply.code != PENDING) {
                            terminal = decode(reply).also { decoded ->
                                if (decoded is CoreResult.Success && decoded.value is java.io.Closeable) unreturned = decoded.value
                            }
                            continue
                        }
                        reply.body.fill(0)
                        if (System.currentTimeMillis() >= deadline) return@withContext CoreResult.Failure(CoreError.Native(10, Effect.UNKNOWN), operationId)
                        delay(pause); pause = (pause * 2).coerceAtMost(100)
                    }
                    terminal
                } catch (_: IllegalArgumentException) {
                    CoreResult.Failure(CoreError.InvalidArgument(Effect.NONE), operationId)
                } catch (_: CancellationException) {
                    throw CoreCancellation(operationId)
                } finally {
                    if (operation != 0L) withContext(NonCancellable) { releaseOperation(operation) }
                }
            }
            currentCoroutineContext().ensureActive()
            unreturned = null
            return result
        } finally { unreturned?.close() }
    }

    /** Cancel + release are retried with bounded backoff. A failed handle is never forgotten. */
    private suspend fun releaseOperation(operation: Long): CoreError? {
        var last = 0
        for (attempt in 0 until CLEANUP_ATTEMPTS) {
            val code = guard.withLock {
                if (freed || operation !in operations) return null
                val cancelled = native.operationCancel(core, operation)
                val released = if (cancelled == 0) native.operationRelease(core, operation) else cancelled
                if (released == 0) { operations.remove(operation); pendingRelease.remove(operation) }
                else pendingRelease.add(operation)
                released
            }
            if (code == 0) {
                guard.withLock { if (pendingRelease.isEmpty()) cleanupState.value = null }
                return null
            }
            last = code
            if (attempt + 1 < CLEANUP_ATTEMPTS) delay((10L shl attempt).coerceAtMost(100))
        }
        return CoreError.CleanupPending(last).also { cleanupState.value = it }
    }

    private suspend fun reapCleanup(): CoreError? {
        val pending = guard.withLock { pendingRelease.toList() }
        for (operation in pending) releaseOperation(operation)?.let { return it }
        return null
    }

    /** The producer joins cancellation cleanup before yielding ownership to another collector. */
    fun updates(filter: SubscriptionFilter): Flow<CoreUpdate> = callbackFlow {
        eventLane.lock()
        var pump: Job? = null
        try {
            val previous = guard.withLock { eventJob }
            previous?.join()
            val unresolved = withContext(io + NonCancellable) { reapCleanup() }
            if (unresolved != null) { send(CoreUpdate.Failure(unresolved)); close(); return@callbackFlow }
            pump = launch(io, start = CoroutineStart.LAZY) {
                var subscription = 0L
                try {
                    val request = CoreRequest("subscription.open", "subscription", filter.accountId, null,
                        filter.body, System.currentTimeMillis() + REQUEST_TIMEOUT_MS)
                    val admission = guard.withLock {
                        if (closing) CoreError.Closed() else {
                                require(BoundedJson.accepts(filter.body))
                                val bytes = encodeRequest(request)
                                val ownership = LongArray(1)
                                val reply = try { native.submit(core, bytes, ownership) } finally {
                                    bytes.fill(0)
                                    subscription = ownership[0]
                                    if (subscription != 0L) operations.add(subscription)
                                }
                                if (reply.code != 0 || subscription == 0L) ReplyCodec.submitRefusal(reply).error
                                else { reply.body.fill(0); null }
                        }
                    }
                    if (admission != null) { send(CoreUpdate.Failure(admission)); return@launch }
                    var ready = false
                    var pause = 10L
                    while (!ready && isActive) {
                        val completion = guard.withLock { if (closing) null else native.operationPoll(core, subscription) } ?: return@launch
                        if (completion.code == PENDING) {
                            completion.body.fill(0)
                            // The SDK clears prior buffered frames on successful reopen. Keep
                            // current-generation frames queued until the open result is adopted.
                            if (System.currentTimeMillis() >= request.deadlineUnixMs) {
                                send(CoreUpdate.Failure(CoreError.Native(10, Effect.UNKNOWN))); return@launch
                            }
                            delay(pause); pause = (pause * 2).coerceAtMost(100)
                        } else {
                            val expectedGeneration = ReplyCodec.eventGeneration(completion.body)
                            when (val opened = ReplyCodec.decodeCompletion(completion, null)) {
                            is CoreResult.Success -> {
                                val tokenText = (opened.value["subscription_generation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                if (expectedGeneration == null || expectedGeneration == 0uL || tokenText != expectedGeneration.toString()) {
                                    send(CoreUpdate.Failure(CoreError.InvalidResponse())); return@launch
                                }
                                generationState.value = expectedGeneration
                                ready = true
                            }
                            is CoreResult.Failure -> { send(CoreUpdate.Failure(opened.error)); return@launch }
                            }
                        }
                    }
                    pause = 10
                    while (isActive) {
                        val reply = guard.withLock { if (closing) null else native.eventPoll(core) } ?: break
                        when (reply.code) {
                            PENDING -> { reply.body.fill(0); delay(pause); pause = (pause * 2).coerceAtMost(100) }
                            RESYNC_REQUIRED -> {
                                val token = try { ReplyCodec.eventGeneration(reply.body) } finally { reply.body.fill(0) }
                                if (token == generationState.value && token != 0uL) {
                                    send(CoreUpdate.Terminal("resync_required", null, token)); break
                                }
                                if (token == null) { send(CoreUpdate.Failure(CoreError.InvalidResponse())); break }
                                delay(pause); pause = (pause * 2).coerceAtMost(100)
                            }
                            else -> when (val row = ReplyCodec.decodeEvent(reply, filter.accountId, generationState.value)) {
                                is CoreResult.Failure -> { send(CoreUpdate.Failure(row.error)); return@launch }
                                is CoreResult.Success -> {
                                    pause = 10
                                    row.value?.let { owned ->
                                        try { send(CoreUpdate.Events(owned, generationState.value)) }
                                        catch (cancelled: CancellationException) { owned.close(); throw cancelled }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    send(CoreUpdate.Failure(CoreError.InvalidArgument(Effect.NONE)))
                } finally {
                    withContext(NonCancellable) {
                        if (subscription != 0L) releaseOperation(subscription)
                        guard.withLock { eventJob = null }
                    }
                    close()
                }
            }
            guard.withLock { eventJob = pump }
            pump.start()
            awaitClose { pump.cancel() }
        } finally {
            withContext(NonCancellable) { pump?.cancelAndJoin(); eventLane.unlock() }
        }
    }.buffer(0)

    suspend fun resetEventsAfterSnapshot(expectedGeneration: ULong): CoreResult<JsonObject> = withContext(io) {
        eventLane.withLock {
            reapCleanup()?.let { return@withLock CoreResult.Failure(it) }
            guard.withLock {
                if (closing) CoreResult.Failure(CoreError.Closed())
                else ReplyCodec.decode(NativeReply(native.eventReset(core, expectedGeneration.toLong()), byteArrayOf()))
            }
        }
    }

    suspend fun close(): CoreResult<JsonObject> = withContext(io + NonCancellable) {
        shutdown.withLock {
            closeResult?.let { return@withLock it }
            val pump = guard.withLock { closing = true; eventJob.also { it?.cancel() } }
            pump?.join()
            val ids = guard.withLock { operations.toList() }
            ids.forEach { releaseOperation(it) }
            val result = guard.withLock {
                val reply = native.close(core)
                ReplyCodec.decode(reply).also { decoded ->
                    if (decoded is CoreResult.Success) { freed = true; operations.clear(); pendingRelease.clear() }
                }
            }
            if (result is CoreResult.Success) cleanupState.value = null
            if (result is CoreResult.Success) closeResult = result
            result
        }
    }

    /** Release only a terminal failed close; Pending keeps ownership and never proves lease release. */
    suspend fun releaseFailedClose(): CoreResult<JsonObject> = withContext(io + NonCancellable) {
        shutdown.withLock {
            guard.withLock {
                if (!closing) return@withLock CoreResult.Failure(CoreError.InvalidArgument(Effect.NONE))
                val code = native.releaseClosed(core)
                if (code == 0) { freed = true; operations.clear(); pendingRelease.clear() }
                ReplyCodec.decode(NativeReply(code, byteArrayOf()))
            }
        }
    }

    companion object {
        const val PENDING = 24
        const val RESYNC_REQUIRED = 27
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val MAX_FRAME = 1 shl 20
        private const val CLEANUP_ATTEMPTS = 5
        private val SUBSCRIPTION_METHODS = setOf("subscription.open", "bus.subscribe", "subscription.close")

        internal fun encodeRequest(request: CoreRequest, deadline: Long = request.deadlineUnixMs): ByteArray = buildJsonObject {
            request.accountId?.let { put("account_id", it) }
            request.operationId?.let { put("operation_id", it) }
            put("sdk_api", request.sdkApi); put("method", request.method)
            put("body", request.body); put("deadline_ms", deadline)
        }.toString().toByteArray()

        private fun encodeSecret(request: SecretRequest, body: ByteArray, deadline: Long): ByteArray {
            require(ByteJson(body).parse().kind == 123)
            val metadata = buildJsonObject {
                request.accountId?.let { put("account_id", it) }
                request.operationId?.let { put("operation_id", it) }
                put("sdk_api", request.sdkApi); put("method", request.method); put("deadline_ms", deadline)
            }
            val prefix = (metadata.toString().dropLast(1) + ",\"body\":").toByteArray()
            require(prefix.size.toLong() + body.size + 1 <= MAX_FRAME)
            return ByteArray(prefix.size + body.size + 1).also {
                prefix.copyInto(it); body.copyInto(it, prefix.size); it[it.lastIndex] = 125
            }
        }

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
                    if (!CoreCapabilities.matchesPolicy(version)) return@withContext CoreResult.Failure(CoreError.MethodPolicyMismatch)
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
                if (unreturned != 0L) withContext(io + NonCancellable) {
                    // No caller received this handle: retain ownership until native shutdown confirms it.
                    var released = false
                    while (!released) {
                        val close = native.close(unreturned)
                        val code = close.code
                        close.body.fill(0)
                        released = code == 0 || (code !in setOf(10, 24) && native.releaseClosed(unreturned) == 0)
                        if (!released) delay(100)
                    }
                }
            }
        }
    }
}

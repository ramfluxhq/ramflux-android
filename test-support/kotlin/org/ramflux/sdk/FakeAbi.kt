// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.json.*

/** Stateful ABI oracle: ownership, bounded credit, cancellation, subscriptions and leases. */
class FakeAbi : NativeCore {
    data class Operation(val method: String, val account: String?, var completion: ByteArray?, var admitted: Boolean, var detached: Boolean = false, var generation: ULong? = null)
    data class Client(val root: String, val operations: MutableMap<Long, Operation> = mutableMapOf(),
        var subscription: Long? = null, val events: ArrayDeque<ByteArray> = ArrayDeque(), var bytes: Int = 0, var resync: Boolean = false, var waitingClose: Boolean = false, var carrierClosed: Boolean = false, var generation: ULong = 0uL)
    private var next = 1L
    private var nextGeneration = 1uL
    private val clients = mutableMapOf<Long, Client>()
    private val leases = mutableMapOf<String, Long>()
    val handles: List<Long> get() = clients.keys.toList()
    val liveWriters: Int get() = leases.size
    val submitted = mutableListOf<JsonObject>()
    val released = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()
    val returnedBuffers = mutableListOf<ByteArray>()
    var cancelFailures = 0
    var releaseFailures = 0
    var submitReplyOom = false
    var negotiatedPolicy: JsonObject? = null
    var pendingRequests = false
    var closeTimeout = false
    var closes = 0
    var opens = 0
    var onOpened: (() -> Unit)? = null
    var onSubscription: ((Long, Long) -> Unit)? = null
    var completionBody = buildJsonObject { put("result", "ok") }

    fun newClient(root: String = "/test/no_backup"): Long = open(buildJsonObject { put("data_root", root) }.toString().toByteArray()).handle
    fun client(core: Long) = clients.getValue(core)
    fun finish(core: Long, operation: Long, code: Int = 0) {
        val op = client(core).operations.getValue(operation)
        if (op.method in setOf("subscription.open", "bus.subscribe") && code == 0 && op.generation == null) activate(client(core), op)
        op.completion = completion(op.method, op.account, code, op.generation)
        if (op.detached) client(core).operations.remove(operation)?.completion?.fill(0)
    }
    fun admit(core: Long, operation: Long) { client(core).operations.getValue(operation).admitted = true }
    fun releaseTimedOutOwner() { leases.clear(); closeTimeout = false }
    fun event(core: Long, account: String, marker: String, generation: ULong = client(core).generation) = enqueue(core, buildJsonObject {
        put("subscription_generation", generation.toString())
        put("account_id", account); put("kind", "event"); put("marker", marker)
    }.toString().toByteArray())
    fun enqueue(core: Long, bytes: ByteArray) {
        val state = client(core)
        if (state.resync) { bytes.fill(0); return }
        if (state.events.size >= 64 || bytes.size > 4 * 1024 * 1024 - state.bytes) {
            if (runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject["subscription_generation"]?.jsonPrimitive?.content?.toULongOrNull() }.getOrNull() == state.generation) {
                state.events.forEach { it.fill(0) }; state.events.clear(); state.bytes = 0; state.resync = true
            }
            bytes.fill(0)
        } else { state.events.add(bytes); state.bytes += bytes.size }
    }
    private fun out(code: Int, bytes: ByteArray = byteArrayOf(), handle: Long = 0) =
        NativeReply(code, bytes, handle).also { returnedBuffers.add(bytes) }
    fun completeShutdown(core: Long) {
        val state = client(core)
        state.carrierClosed = true
        if (leases[state.root] == core) leases.remove(state.root)
        state.operations.toMap().forEach { (id, op) ->
            if (op.completion == null) finish(core, id, 26)
        }
    }
    fun policyManifest(): JsonObject = buildJsonObject {
        put("abi_major", 2); put("policy_version", 2)
        put("methods", JsonArray(SdkMethodPolicy.methods.map { (name, policy) -> buildJsonObject {
            put("name", name); put("capability", policy.capability); put("bridge_allowed", policy.bridgeAllowed)
            put("secret_input", policy.secretInput); put("secret_output", policy.secretOutput)
            put("show_once", policy.showOnce); put("key_export", policy.keyExport)
        } }))
    }
    override fun negotiate() = out(0, buildJsonObject {
        put("abi_major", 2); put("abi_minor", 0); put("bus_protocol", "ramflux.local_bus.v1")
        put("max_frame_bytes", 1048576); put("message_descriptor_blake3", "a".repeat(64))
        put("max_operations", 64); put("max_events", 64); put("max_event_bytes", 4 * 1024 * 1024)
        put("methods", JsonArray(SdkMethodPolicy.methods.filterValues { it.bridgeAllowed }.keys.map(::JsonPrimitive)))
        put("method_policy", negotiatedPolicy ?: policyManifest())
    }.toString().toByteArray())
    override fun open(config: ByteArray): NativeReply {
        val input = runCatching { Json.parseToJsonElement(config.decodeToString()).jsonObject }.getOrNull() ?: return out(1)
        val root = (input["data_root"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return out(1)
        val exports = input["allow_secret_exports"]
        if (!root.startsWith("/") || input.keys.any { it !in setOf("data_root", "builtin_nodes", "allow_secret_exports") } ||
            input["builtin_nodes"]?.let { it !is JsonArray } == true ||
            (exports != null && exports != JsonPrimitive(true) && exports != JsonPrimitive(false))) return out(1)
        opens++
        if (clients.size >= 16) return out(15)
        if (root in leases) return out(18)
        val id = next++; leases[root] = id; clients[id] = Client(root)
        onOpened?.invoke()
        return out(0, handle = id)
    }
    override fun close(core: Long): NativeReply {
        closes++
        val state = clients[core] ?: return out(0)
        if (closeTimeout) {
            val code = if (state.waitingClose) 24 else 10
            state.waitingClose = true
            return out(code)
        }
        clients.remove(core); if (leases[state.root] == core) leases.remove(state.root)
        state.operations.values.forEach { it.completion?.fill(0) }
        state.events.forEach { it.fill(0) }
        return out(0)
    }
    override fun releaseClosed(core: Long): Int {
        val state = clients[core] ?: return 0
        if (leases[state.root] == core) return 24
        clients.remove(core)
        return 0
    }
    override fun submit(core: Long, request: ByteArray, ownership: LongArray): NativeReply {
        require(ownership.size == 1 && ownership[0] == 0L)
        val state = clients[core] ?: return out(1)
        if (state.waitingClose || state.carrierClosed) return out(9)
        val frame = runCatching { Json.parseToJsonElement(request.decodeToString()).jsonObject }.getOrNull() ?: return out(1)
        val method = (frame["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return out(1)
        val api = (frame["sdk_api"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return out(1)
        val deadline = (frame["deadline_ms"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toULongOrNull() ?: return out(1)
        if (api.length > 128 || method.length !in 1..128 || method.contains("itest") ||
            frame.keys.any { it !in setOf("method", "sdk_api", "body", "deadline_ms", "account_id", "operation_id") } ||
            frame["body"] !is JsonObject) return out(1)
        for (key in listOf("account_id", "operation_id")) {
            val value = frame[key]
            if (value != null && value != JsonNull && (value !is JsonPrimitive || !value.isString)) return out(1)
        }
        if ((frame["account_id"] as? JsonPrimitive)?.contentOrNull?.length?.let { it > 128 } == true) return out(1)
        if (state.operations.size >= 64 || (method in setOf("subscription.open", "bus.subscribe") && state.subscription != null)) return out(15)
        val id = next++
        val account = frame["account_id"]?.jsonPrimitive?.contentOrNull
        submitted.add(frame)
        state.operations[id] = Operation(method, account,
            if (deadline <= System.currentTimeMillis().toULong()) completion(method, account, 10)
            else if (pendingRequests) null else completion(method, account, 0), !pendingRequests)
        if (method in setOf("subscription.open", "bus.subscribe")) {
            state.subscription = id
            if (!pendingRequests) {
                val operation = state.operations.getValue(id)
                activate(state, operation)
                operation.completion = completion(method, account, 0, operation.generation)
            }
            onSubscription?.invoke(core, id)
        }
        ownership[0] = id
        if (submitReplyOom) { submitReplyOom = false; throw OutOfMemoryError("injected JNI reply allocation failure") }
        return out(0, handle = id)
    }
    private fun activate(state: Client, operation: Operation) {
        operation.generation = nextGeneration++
        state.generation = operation.generation ?: 0uL
        state.events.forEach { it.fill(0) }; state.events.clear(); state.bytes = 0; state.resync = false
    }
    private fun completion(method: String, account: String?, code: Int, generation: ULong? = null): ByteArray = buildJsonObject {
        put("code", code)
        generation?.let { put("subscription_generation", it.toString()) }
        if (code != 0) put("effects", if (code == 10) "none" else "unknown")
        put("response", buildJsonObject {
            generation?.let { put("subscription_generation", it.toString()) }
            put("ok", code == 0); put("method", method); account?.let { put("account_id", it) }
            put("body", completionBody)
        })
    }.toString().toByteArray()
    override fun operationPoll(core: Long, operation: Long): NativeReply {
        val state = clients[core] ?: return out(1)
        val op = state.operations[operation] ?: return out(1)
        return op.completion?.let { out(0, it.copyOf()) } ?: out(24)
    }
    private fun cancel(state: Client, operation: Long): Int {
        val op = state.operations[operation] ?: return 1
        if (op.detached) return 1
        if (cancelFailures > 0) { cancelFailures--; return 15 }
        if (state.subscription == operation) state.subscription = null
        if (!op.admitted && op.completion == null) op.completion = """{"code":25,"effects":"none"}""".toByteArray()
        return 0
    }
    override fun operationCancel(core: Long, operation: Long): Int {
        cancelled.add(operation)
        return cancel(clients[core] ?: return 1, operation)
    }
    override fun operationRelease(core: Long, operation: Long): Int {
        val state = clients[core] ?: return 1
        if (releaseFailures > 0) { releaseFailures--; return 15 }
        val code = cancel(state, operation)
        if (code != 0) return code
        val op = state.operations.getValue(operation)
        op.detached = true
        if (op.completion != null) state.operations.remove(operation)?.completion?.fill(0)
        released.add(operation)
        return 0
    }
    override fun eventPoll(core: Long): NativeReply {
        val state = clients[core] ?: return out(1)
        if (state.resync) return out(27, buildJsonObject { put("kind", "stream.lagged"); put("subscription_generation", state.generation.toString()) }.toString().toByteArray())
        val bytes = state.events.removeFirstOrNull() ?: return out(if (state.carrierClosed) 26 else 24)
        state.bytes -= bytes.size
        return out(0, bytes)
    }
    override fun eventReset(core: Long, generation: Long): Int {
        val state = clients[core] ?: return 1
        if (state.generation.toLong() != generation) return 1
        state.events.forEach { it.fill(0) }; state.events.clear(); state.bytes = 0; state.resync = false
        return 0
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.json.JsonObject

/** Wire bodies belong to the SDK descriptor; this layer never reinterprets message content. */
class CoreRequest(
    val method: String,
    val sdkApi: String,
    val accountId: String?,
    val operationId: String?,
    val body: JsonObject,
    val deadlineUnixMs: Long = 0,
) {
    override fun toString(): String = "CoreRequest(method=$method, body=<redacted>)"
}

enum class Effect { NONE, REFUSED, UNKNOWN }
enum class UnavailableCapability { LOCAL_SEARCH, AGENT_CONFIGURATION, STRANGER_REPORTING }

sealed interface CoreError {
    val code: Int
    val effect: Effect
    data class Unavailable(val capability: UnavailableCapability) : CoreError {
        override val code = 4
        override val effect = Effect.NONE
    }
    data class InvalidArgument(override val effect: Effect) : CoreError { override val code = 1 }
    data class UnsupportedVersion(override val effect: Effect) : CoreError { override val code = 3 }
    data class PermissionDenied(override val effect: Effect) : CoreError { override val code = 12 }
    data class SchemaResetRequired(override val effect: Effect) : CoreError { override val code = 23 }
    data class VaultUnavailable(override val effect: Effect = Effect.NONE) : CoreError { override val code = 7 }
    data class Native(override val code: Int, override val effect: Effect) : CoreError {
        val status: SdkStatus? get() = SdkStatus.entries.firstOrNull { it.code == code }
    }
    data class Closed(override val effect: Effect = Effect.NONE) : CoreError { override val code = -1 }
    data class InvalidResponse(override val effect: Effect = Effect.UNKNOWN) : CoreError { override val code = -2 }
}


/** Stable ABI numbers. Unknown future values remain intact in CoreError.Native. */
enum class SdkStatus(val code: Int) {
    OK(0), INVALID_ARGUMENT(1), INVALID_JSON(2), UNSUPPORTED_VERSION(3),
    CAPABILITY_REJECTED(4), AUTH_FAILED(5), DEVICE_REVOKED(6), CRYPTO_FAILED(7),
    STORAGE_FAILED(8), TRANSPORT_UNAVAILABLE(9), TIMEOUT(10), REPLAY_REJECTED(11),
    PERMISSION_DENIED(12), MCP_GRANT_REJECTED(13), OBJECT_TRANSFER_FAILED(14),
    RATE_LIMITED(15), INTERNAL(16), PANIC_ABORTED(17), ACCOUNT_BUSY(18),
    OBJECT_ERASED(19), OBJECT_IDENTITY_CONFLICT(20), OBJECT_OWNERSHIP_UNVERIFIED(21),
    CUSTODY_FORK_FENCED(22), SCHEMA_RESET_REQUIRED(23), PENDING(24), CANCELLED(25),
    CLOSED(26), RESYNC_REQUIRED(27),
}

sealed interface CoreResult<out T> {
    class Success<T>(val value: T) : CoreResult<T> {
        override fun toString(): String = "Success(<redacted>)"
    }
    class Failure(val error: CoreError, val operationId: String? = null, val details: JsonObject? = null) : CoreResult<Nothing> {
        override fun toString(): String = "Failure(code=${error.code}, effect=${error.effect}, details=<redacted>)"
    }
}

/** JNI transfers buffers as bytes, never through modified UTF-8 or a loggable String. */
class NativeReply(val code: Int, val body: ByteArray, val handle: Long = 0) {
    override fun toString(): String = "NativeReply(code=$code, body=<redacted>)"
}

data class SubscriptionFilter(val accountId: String?, val body: JsonObject)

sealed interface CoreUpdate {
    class Events(val body: JsonObject) : CoreUpdate
    data class Terminal(val kind: String, val lastSequence: String?) : CoreUpdate
    data class Failure(val error: CoreError) : CoreUpdate
}

/** Cancellation is not evidence of rollback; reconcile the original operation identity. */
class CoreCancellation(val operationId: String?) :
    kotlinx.coroutines.CancellationException("SDK waiter cancelled; execution may require reconciliation")

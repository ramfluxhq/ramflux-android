// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.sdk

/**
 * Stable ABI numbers with the typed name the SDK reports for each (C header `RamfluxErrorCode`
 * and `RamfluxV2Status`). Unknown future values remain intact in [CoreError.Native].
 */
enum class SdkStatus(val code: Int, val wireName: String) {
    OK(0, "Ok"),
    INVALID_ARGUMENT(1, "InvalidArgument"),
    INVALID_CANONICAL_JSON(2, "InvalidCanonicalJson"),
    UNSUPPORTED_VERSION(3, "UnsupportedVersion"),
    CAPABILITY_REJECTED(4, "CapabilityRejected"),
    AUTH_FAILED(5, "AuthFailed"),
    DEVICE_REVOKED(6, "DeviceRevoked"),
    CRYPTO_FAILED(7, "CryptoFailed"),
    STORAGE_FAILED(8, "StorageFailed"),
    TRANSPORT_UNAVAILABLE(9, "TransportUnavailable"),
    TIMEOUT(10, "Timeout"),
    REPLAY_REJECTED(11, "ReplayRejected"),
    PERMISSION_DENIED(12, "PermissionDenied"),
    MCP_GRANT_REJECTED(13, "McpGrantRejected"),
    OBJECT_TRANSFER_FAILED(14, "ObjectTransferFailed"),
    RATE_LIMITED(15, "RateLimited"),
    INTERNAL(16, "Internal"),
    PANIC_ABORTED(17, "PanicAborted"),
    ACCOUNT_BUSY(18, "AccountBusy"),
    OBJECT_ERASED(19, "ObjectErased"),
    OBJECT_IDENTITY_CONFLICT(20, "ObjectIdentityConflict"),
    OBJECT_OWNERSHIP_UNVERIFIED(21, "ObjectOwnershipUnverified"),
    CUSTODY_FORK_FENCED(22, "CustodyForkFenced"),
    SCHEMA_RESET_REQUIRED(23, "SchemaResetRequired"),
    PENDING(24, "Pending"),
    CANCELLED(25, "Cancelled"),
    CLOSED(26, "Closed"),
    RESYNC_REQUIRED(27, "ResyncRequired"),
    PAIRING_REFUSED(28, "PairingRefused"),
    PAIRING_STALE(29, "PairingStale"),
    SUSPENDED(30, "Suspended"),
    VAULT_LOCKED(31, "VaultLocked");

    companion object {
        fun of(code: Int): SdkStatus? = entries.firstOrNull { it.code == code }
    }
}

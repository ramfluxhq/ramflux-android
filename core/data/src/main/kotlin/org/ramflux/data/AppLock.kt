// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.ramflux.sdk.CoreResult

/**
 * Whether the account's screens may show. [UNKNOWN] is the state until the SDK has answered after an open, a
 * resume or a background, and whenever the answer cannot be read: the cover stays up (fails closed).
 */
enum class AppLockPhase { UNKNOWN, OPEN, LOCKED }

/** The SDK's `account.app_lock.state` value, as far as this client reads it. */
enum class AppLockState {
    UNLOCKED, LOCKED, UNAVAILABLE;

    companion object {
        /** The SDK's state word; anything else is no state at all (null), which the caller treats as unknown. */
        fun parse(word: String?): AppLockState? = when (word) {
            "unlocked" -> UNLOCKED
            "locked" -> LOCKED
            "unavailable" -> UNAVAILABLE
            else -> null
        }
    }
}

/** One reading of `account.app_lock.state` for one account. [timeoutMs] is zero for "immediately". */
data class AppLockStatus(val enabled: Boolean, val timeoutMs: Long, val state: AppLockState) {
    companion object {
        /** Decodes the SDK reply; a missing field or an unknown state is no reading at all (null). */
        fun read(body: JsonObject): AppLockStatus? {
            val enabled = (body["enabled"] as? JsonPrimitive)?.booleanOrNull ?: return null
            val timeout = (body["timeout_ms"] as? JsonPrimitive)?.longOrNull ?: return null
            val state = AppLockState.parse((body["state"] as? JsonPrimitive)?.contentOrNull) ?: return null
            return AppLockStatus(enabled, timeout, state)
        }
    }
}

/** What the session needs after a read: the cover phase, and whether the lock is on (notifications stay generic). */
data class AppLockReading(val phase: AppLockPhase, val enabled: Boolean)

/** A read that failed or a state nobody can interpret: the cover stays up and the lock counts as on. */
internal val UNKNOWN_APP_LOCK = AppLockReading(AppLockPhase.UNKNOWN, enabled = true)

/** The reading for one decoded status. */
internal fun AppLockStatus.reading(): AppLockReading = AppLockReading(state.phase(enabled), enabled)

/**
 * The cover phase for one SDK state. An unavailable lock only blocks when it is switched on; no state at all (a failed
 * call, or a reply without a state) is unknown and keeps the cover up.
 */
internal fun AppLockState?.phase(enabled: Boolean): AppLockPhase = when (this) {
    AppLockState.LOCKED -> AppLockPhase.LOCKED
    AppLockState.UNLOCKED -> AppLockPhase.OPEN
    AppLockState.UNAVAILABLE -> if (enabled) AppLockPhase.UNKNOWN else AppLockPhase.OPEN
    null -> AppLockPhase.UNKNOWN
}

/** The refusal the SDK returns for any call while the app lock holds the account. */
const val APP_LOCKED_REASON = "app_locked"

/** Lifecycle states sent to `account.app_lock.lifecycle`. */
internal const val APP_LOCK_FOREGROUND = "foreground"
internal const val APP_LOCK_BACKGROUND = "background"

internal fun ClientProblem.isAppLocked(): Boolean = check == APP_LOCKED_REASON || statusName == APP_LOCKED_REASON

/** The unlocked active account, or null when no account is open on this phone. */
internal fun AccountCatalog.openAccount(): String? = activeId?.takeIf { id -> accounts.any { it.id == id && it.unlocked } }

/**
 * Reads the app lock of the open account for the session. A failed account list is an unknown state, not "no
 * account": only a listed catalog without an open account means there is nothing to lock.
 */
internal class AppLockReader(transport: RepositoryTransport) {
    private val accounts = DefaultAccountAccessRepository(transport)
    private val security = DefaultAccountSecurityRepository(transport)

    suspend fun read(): AppLockReading {
        // After a cold start the SDK reopens accounts by itself and publishes no event when it ends: wait for it, bounded.
        var catalog = listed() ?: return UNKNOWN_APP_LOCK
        var waited = 0L
        while (catalog.restoring() && waited < RESTORE_WAIT_MS) {
            delay(RESTORE_POLL_MS)
            waited += RESTORE_POLL_MS
            catalog = listed() ?: return UNKNOWN_APP_LOCK
        }
        // A restore that never ends is not "no account": the cover stays up.
        if (catalog.restoring()) return UNKNOWN_APP_LOCK
        val account = catalog.openAccount() ?: return AppLockReading(AppLockPhase.OPEN, enabled = false)
        return when (val status = security.appLockState(account)) {
            is ClientResult.Ready -> status.value.reading()
            else -> UNKNOWN_APP_LOCK
        }
    }

    private suspend fun listed(): AccountCatalog? = (accounts.list() as? ClientResult.Ready)?.value

    /**
     * Sends the lifecycle state and returns the state the reply carries. With no open account there is nothing to
     * lock, so the answer is unlocked; a failed call or a reply without a state returns null.
     */
    suspend fun lifecycle(state: String): AppLockState? {
        val catalog = (accounts.list() as? ClientResult.Ready)?.value ?: return null
        val account = catalog.openAccount() ?: return AppLockState.UNLOCKED
        return (security.appLockLifecycle(account, state) as? ClientResult.Ready)?.value
    }
}

/** The `account.list` state of an account the SDK is still reopening through its device key. */
private const val RESTORE_PENDING_STATE = "restore_pending"
private const val RESTORE_POLL_MS = 250L
private const val RESTORE_WAIT_MS = 30_000L

private fun AccountCatalog.restoring(): Boolean = accounts.any { it.state == RESTORE_PENDING_STATE }

/** Every SDK call passes here, so a refusal that names the app lock reaches the session once, whichever repository asked. */
internal class AppLockRefusalTransport(
    private val inner: RepositoryTransport,
    private val onRefused: () -> Unit,
) : RepositoryTransport {
    override suspend fun execute(request: org.ramflux.sdk.CoreRequest): CoreResult<JsonObject> =
        inner.execute(request).also { it.noteRefusal() }

    override suspend fun executeSecret(request: org.ramflux.sdk.SecretRequest): CoreResult<org.ramflux.sdk.SecretReply> =
        inner.executeSecret(request).also { it.noteRefusal() }

    override fun updates(filter: org.ramflux.sdk.SubscriptionFilter) = inner.updates(filter)

    override suspend fun resetEvents(generation: ULong): CoreResult<JsonObject> = inner.resetEvents(generation)

    private fun <T> CoreResult<T>.noteRefusal() {
        if (this is CoreResult.Failure && error.appProblem().isAppLocked()) onRefused()
    }
}

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

/**
 * The SDK's `account.app_lock.state` value, as far as this client reads it. [ARMED] is an unlocked account whose
 * timeout is running: the return from the background has not yet been confirmed by the foreground call.
 */
enum class AppLockState {
    UNLOCKED, LOCKED, ARMED, UNAVAILABLE;

    companion object {
        /** The SDK's state word; anything else is no state at all (null), which the caller treats as unknown. */
        fun parse(word: String?): AppLockState? = when (word) {
            "unlocked" -> UNLOCKED
            "locked" -> LOCKED
            "armed" -> ARMED
            "unavailable" -> UNAVAILABLE
            else -> null
        }
    }
}

/**
 * One reading of `account.app_lock.state` for one account. [timeoutMs] is zero for "immediately". [codeRetryAfterMs] is
 * the wait the account-code throttle holds now (null when none), and [codeAttemptsExhausted] is set once the code
 * attempts are used up. Both come from core, so a host shows the wait before it asks for a code.
 */
data class AppLockStatus(
    val enabled: Boolean,
    val timeoutMs: Long,
    val state: AppLockState,
    val codeRetryAfterMs: Long? = null,
    val codeAttemptsExhausted: Boolean = false,
) {
    companion object {
        /** Decodes the SDK reply; a missing field or an unknown state is no reading at all (null). */
        fun read(body: JsonObject): AppLockStatus? {
            val enabled = (body["enabled"] as? JsonPrimitive)?.booleanOrNull ?: return null
            val timeout = (body["timeout_ms"] as? JsonPrimitive)?.longOrNull ?: return null
            val state = AppLockState.parse((body["state"] as? JsonPrimitive)?.contentOrNull) ?: return null
            val retry = (body["code_retry_after_ms"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
            val exhausted = (body["code_attempts_exhausted"] as? JsonPrimitive)?.booleanOrNull ?: false
            return AppLockStatus(enabled, timeout, state, retry, exhausted)
        }
    }
}

/**
 * What the session needs after a read: the cover phase, whether the lock is on (notifications stay generic), and the
 * account the cover unlocks. [account] is set only while LOCKED: a locked account is not the active one, so the id
 * comes from the account list row or from the last read, never from the active account.
 */
data class AppLockReading(
    val phase: AppLockPhase,
    val enabled: Boolean,
    val account: String? = null,
    /** True only when an unlocked account was read: only then does the foreground lifecycle call have a target. */
    val foreground: Boolean = false,
)

/** A read that failed or a state nobody can interpret: the cover stays up and the lock counts as on. */
internal val UNKNOWN_APP_LOCK = AppLockReading(AppLockPhase.UNKNOWN, enabled = true)

/**
 * The cover phase for one SDK state. An unavailable lock only blocks when it is switched on; an armed timeout is an
 * open account; no state at all (a failed call, or a reply without a state) is unknown and keeps the cover up.
 */
internal fun AppLockState?.phase(enabled: Boolean): AppLockPhase = when (this) {
    AppLockState.LOCKED -> AppLockPhase.LOCKED
    AppLockState.UNLOCKED, AppLockState.ARMED -> AppLockPhase.OPEN
    AppLockState.UNAVAILABLE -> if (enabled) AppLockPhase.UNKNOWN else AppLockPhase.OPEN
    null -> AppLockPhase.UNKNOWN
}

/** The refusal the SDK returns for any call while the app lock holds the account. */
const val APP_LOCKED_REASON = "app_locked"

/** Lifecycle states sent to `account.app_lock.lifecycle`. */
internal const val APP_LOCK_FOREGROUND = "foreground"
internal const val APP_LOCK_BACKGROUND = "background"

internal fun ClientProblem.isAppLocked(): Boolean = check == APP_LOCKED_REASON || statusName == APP_LOCKED_REASON

/** `account.list` state words this client knows; anything else makes the lock state unknown. */
private const val LISTED_UNLOCKED = "unlocked"
private const val LISTED_LOCKED = "locked"

/** The `account.list` state of an account the SDK is still reopening through its device key. */
private const val RESTORE_PENDING_STATE = "restore_pending"
private const val RESTORE_POLL_MS = 250L
private const val RESTORE_WAIT_MS = 30_000L

private fun AccountCatalog.restoring(): Boolean = accounts.any { it.state == RESTORE_PENDING_STATE }

/**
 * Reads the app lock for the session. A failed account list is an unknown state, not "no account". An empty catalog is
 * the only "nothing to lock" answer. Every row is read for its own app lock: a closed or restoring row covers only when
 * its lock is on (or the call fails); a disabled lock leaves the device as before G05.
 */
internal class AppLockReader(transport: RepositoryTransport) {
    private val accounts = DefaultAccountAccessRepository(transport)
    private val security = DefaultAccountSecurityRepository(transport)

    /** The locked account the last read named, used only when the catalog names several locked accounts. Not persisted. */
    @Volatile private var remembered: String? = null

    suspend fun read(): AppLockReading {
        // After a cold start the SDK reopens accounts by itself and publishes no event when it ends: wait for it, bounded.
        var catalog = listed() ?: return UNKNOWN_APP_LOCK
        var waited = 0L
        while (catalog.restoring() && waited < RESTORE_WAIT_MS) {
            delay(RESTORE_POLL_MS)
            waited += RESTORE_POLL_MS
            catalog = listed() ?: return UNKNOWN_APP_LOCK
        }
        // Rows still restoring after the wait are read below like any other row: covered only when their lock is on.
        if (catalog.accounts.isEmpty()) return AppLockReading(AppLockPhase.OPEN, enabled = false)
        val locked = mutableListOf<String>()
        var enabled = false
        var unlockedRead = false
        for (row in catalog.accounts) {
            when (row.state) {
                // A closed account is covered only when its lock is on; a disabled lock keeps the normal account-locked route.
                LISTED_LOCKED -> when (val status = security.appLockState(row.id)) {
                    is ClientResult.Ready -> {
                        val reading = status.value.reading()
                        enabled = enabled || reading.enabled
                        if (reading.enabled) {
                            when (reading.phase) {
                                AppLockPhase.LOCKED -> locked += row.id
                                AppLockPhase.UNKNOWN -> return UNKNOWN_APP_LOCK
                                AppLockPhase.OPEN -> Unit
                            }
                        }
                    }
                    else -> return UNKNOWN_APP_LOCK
                }
                LISTED_UNLOCKED -> when (val status = security.appLockState(row.id)) {
                    is ClientResult.Ready -> {
                        unlockedRead = true
                        val reading = status.value.reading()
                        enabled = enabled || reading.enabled
                        when (reading.phase) {
                            AppLockPhase.LOCKED -> locked += row.id
                            AppLockPhase.UNKNOWN -> return UNKNOWN_APP_LOCK
                            AppLockPhase.OPEN -> Unit
                        }
                    }
                    else -> return UNKNOWN_APP_LOCK
                }
                // An account in another state covers only when its lock may be on: a disabled lock on it is as before G05.
                else -> when (val status = security.appLockState(row.id)) {
                    is ClientResult.Ready -> if (status.value.enabled) return UNKNOWN_APP_LOCK
                    else -> return UNKNOWN_APP_LOCK
                }
            }
        }
        if (locked.isEmpty()) return AppLockReading(AppLockPhase.OPEN, enabled, foreground = unlockedRead)
        // The cover needs one account to unlock. Several locked rows and no remembered one: fail closed.
        val target = locked.singleOrNull() ?: locked.firstOrNull { it == remembered } ?: return UNKNOWN_APP_LOCK
        remembered = target
        return AppLockReading(AppLockPhase.LOCKED, enabled = true, account = target)
    }

    private fun AppLockStatus.reading(): AppLockReading = AppLockReading(state.phase(enabled), enabled)

    private suspend fun listed(): AccountCatalog? = (accounts.list() as? ClientResult.Ready)?.value

    /**
     * Sends the lifecycle state to the unlocked account and returns the state its reply carries. With no account on
     * the phone there is nothing to lock, so the answer is unlocked; with accounts but none unlocked, it is unknown.
     */
    suspend fun lifecycle(state: String): AppLockState? {
        val catalog = listed() ?: return null
        if (catalog.accounts.isEmpty()) return AppLockState.UNLOCKED
        val account = catalog.accounts.firstOrNull { it.unlocked && it.id == catalog.activeId }?.id
            ?: catalog.accounts.firstOrNull { it.unlocked }?.id
            ?: return null
        return (security.appLockLifecycle(account, state) as? ClientResult.Ready)?.value
    }
}

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

    override suspend fun <T> withAttendedCode(answer: suspend () -> CharArray?, body: suspend () -> T): T =
        inner.withAttendedCode(answer, body)

    private fun <T> CoreResult<T>.noteRefusal() {
        if (this is CoreResult.Failure && error.appProblem().isAppLocked()) onRefused()
    }
}

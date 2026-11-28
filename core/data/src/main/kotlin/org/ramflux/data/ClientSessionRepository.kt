// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.StateFlow

/** App-owned values: no native reply, secret holder, or SDK error crosses this contract. */
enum class ClientPhase { CLOSED, OPENING, OPEN, CLOSING, FAILED }
enum class ClientFeature(val id: String) { CHAT("chat"), ONBOARDING("onboarding"), CONVERSATIONS("conversations"), CONTACTS("contacts"), SETTINGS("settings"), GROUPS("groups"), AGENTS("agents"), TASKS("tasks"), CARDS("cards"), CALLS("calls") }
enum class ClientFailureKind { INCOMPATIBLE, UNAVAILABLE, STORAGE, AUTHORIZATION, SHUTDOWN_PENDING, FAILED }
enum class ClientOutcome { NONE, REFUSED, UNKNOWN }
enum class ClientFailureReason { GROUP_CAPACITY, GROUP_INVALID }
data class ClientProblem(
    val kind: ClientFailureKind,
    val code: Int,
    val outcome: ClientOutcome,
    val reason: ClientFailureReason? = null,
    val statusName: String? = null,
    val check: String? = null,
    /** A wait the SDK reported before the same request may be tried again (seconds). */
    val retryAfterSecs: Long? = null,
    /** A number a typed refusal carries: `max_bytes` of `too_large`, `retry_at` (unix ms) of `paced`. */
    val detail: Long? = null,
)
/**
 * [suspended] is true while the app is in the background: the open client has released its files and network but
 * keeps its unlocked accounts, so [phase] stays OPEN and [generation] is unchanged; screens keep their state.
 */
data class ClientSnapshot(
    val phase: ClientPhase = ClientPhase.CLOSED,
    val generation: Long = 0,
    val problem: ClientProblem? = null,
    val suspended: Boolean = false,
    /** Read after every open and resume; UNKNOWN until then, and whenever the read fails. Only meaningful while OPEN. */
    val appLock: AppLockPhase = AppLockPhase.UNKNOWN,
    /** Whether the account has the app lock switched on; notifications stay generic while it is on. */
    val appLockEnabled: Boolean = false,
    /** The account the cover unlocks while LOCKED (never the active account: a locked account is not active). */
    val appLockAccount: String? = null,
    /** Set when an open session failed (resume or suspend) while the lock was on, engaged or unread: the cover stays up. */
    val lockHeld: Boolean = false,
)
/**
 * Whether the app-lock cover is up: the session is open and its lock is on or its state is unread, or the session failed
 * after the lock was on, engaged or unread (a failed resume or suspend must not drop the cover).
 */
fun ClientSnapshot.appLockCovers(): Boolean =
    (phase == ClientPhase.OPEN && appLock != AppLockPhase.OPEN) || ((phase == ClientPhase.FAILED || phase == ClientPhase.OPENING) && lockHeld)

/**
 * Whether the window must refuse capture right now, readable synchronously from a lifecycle callback: the lock was
 * read as on, or the session is open and its state is not read yet.
 */
fun ClientSnapshot.appLockGuarded(): Boolean = appLockEnabled || appLockCovers()

enum class UnavailableReason { FEATURE_NOT_INSTALLED, UNSUPPORTED_BY_CORE, CUSTODY_PROVIDER_REQUIRED }
sealed interface ClientResult<out T> {
    data class Ready<T>(val value: T) : ClientResult<T>
    data class Failed(val problem: ClientProblem) : ClientResult<Nothing>
    data class Unavailable(val feature: ClientFeature, val reason: UnavailableReason = UnavailableReason.FEATURE_NOT_INSTALLED) : ClientResult<Nothing>
}

/** Foreground/background are serialized. OPEN means the supervisor opened, not account authentication. */
interface ClientSessionRepository {
    val status: StateFlow<ClientSnapshot>
    suspend fun foreground()
    suspend fun background()
    suspend fun availability(feature: ClientFeature): ClientResult<Unit>

    /** Reads the app lock again, for example after the account code opened it. Serialized with foreground and background. */
    suspend fun recheckAppLock() {}
}

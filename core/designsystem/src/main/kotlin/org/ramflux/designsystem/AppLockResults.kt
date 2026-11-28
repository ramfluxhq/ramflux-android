// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The app-lock cover as read right now: down, up because the lock state is not read yet, or up because it is locked. */
enum class AppLockCoverState { DOWN, UNREAD, LOCKED }

/**
 * Reads the cover's state synchronously from the session, not from the composition. A result from another activity
 * (a picker) or a tap in a window outside the gate can arrive before the composition has seen the cover rise (on the
 * return from the background), so those paths ask [now]. [changes] starts with the current state.
 */
class AppLockCoverReader(val now: () -> AppLockCoverState, val changes: Flow<AppLockCoverState>)

/** Provided by the host; without one (previews, tests) the composition's [LocalAppLockCovered] is used. */
val LocalAppLockCoverReader = staticCompositionLocalOf<AppLockCoverReader?> { null }

/** A function that tells, when called, whether the cover is up now (read from the session where the host provides it). */
@Composable
fun rememberAppLockCoveredNow(): () -> Boolean {
    val reader = LocalAppLockCoverReader.current
    val covered by rememberUpdatedState(LocalAppLockCovered.current)
    return remember(reader) { { (reader?.now()?.let { it != AppLockCoverState.DOWN }) ?: covered } }
}

/**
 * Wraps an activity-result callback (a photo, document or save-location picker) so a result cannot land in a locked
 * account: one that arrives while the account is locked is refused ([onRefused]). One that arrives while the lock state
 * is not read yet (the session reads it again on every return from the background) is held until it is read: delivered
 * when the cover goes down without the lock, refused when it reads locked or the screen leaves the composition first.
 */
@Composable
fun <T> rememberAppLockCheckedResult(onRefused: (T) -> Unit = {}, onResult: (T) -> Unit): (T) -> Unit {
    val reader = LocalAppLockCoverReader.current
    val covered by rememberUpdatedState(LocalAppLockCovered.current)
    val deliver by rememberUpdatedState(onResult)
    val refuse by rememberUpdatedState(onRefused)
    val scope = rememberCoroutineScope()
    return remember(reader, scope) {
        { value: T ->
            when (reader?.now() ?: if (covered) AppLockCoverState.LOCKED else AppLockCoverState.DOWN) {
                AppLockCoverState.DOWN -> deliver(value)
                AppLockCoverState.LOCKED -> refuse(value)
                AppLockCoverState.UNREAD -> scope.launch {
                    var settled = false
                    try {
                        val state = reader?.changes?.first { it != AppLockCoverState.UNREAD } ?: AppLockCoverState.LOCKED
                        settled = true
                        if (state == AppLockCoverState.DOWN) deliver(value) else refuse(value)
                    } finally {
                        if (!settled) refuse(value)
                    }
                }
            }
        }
    }
}

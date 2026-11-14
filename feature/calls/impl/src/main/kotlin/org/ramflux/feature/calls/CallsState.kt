// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.designsystem.UiText
@Immutable data class CallItem(
    val id: String,
    val peer: String,
    val direction: UiText,
    val phase: UiText,
    val time: UiText,
    val sequence: ULong,
    val generation: UiText,
    val answered: UiText?,
    val ended: UiText?,
    val reason: UiText?,
    val canDecline: Boolean,
    val canHangup: Boolean,
    val incoming: Boolean,
    val hasAudioControls: Boolean
) {
    override fun toString() = "CallItem(<redacted>)"
}

@Immutable data class CallsState(
    val account: String? = null,
    val rows: ImmutableList<CallItem> = persistentListOf(),
    val selected: CallItem? = null,
    val expanded: Boolean = false,
    val fresh: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val accountRequired: Boolean = false,
    val problem: UiText? = null,
    val unavailable: UiText? = null,
    val pending: String? = null
) {
    override fun toString() = "CallsState(<redacted>)"
}

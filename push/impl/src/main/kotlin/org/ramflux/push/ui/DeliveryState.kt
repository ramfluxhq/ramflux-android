// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.ramflux.push.api.ProviderState
import org.ramflux.push.api.UnavailableReason
import org.ramflux.push.guidance.DeliveryEntry

@Immutable
data class DeliveryState(
    val loading: Boolean = true,
    val provider: ProviderState = ProviderState.Unavailable(UnavailableReason.NOT_CONFIGURED),
    val lastWakeMs: Long = 0L,
    @StringRes val brandLabel: Int? = null,
    @StringRes val skinHint: Int? = null,
    val osRelease: String = "",
    val entries: ImmutableList<DeliveryEntry> = persistentListOf(),
    /** Steps whose vendor screen could not be opened on this phone during this visit. */
    val cannotOpen: ImmutableSet<String> = persistentSetOf()
)

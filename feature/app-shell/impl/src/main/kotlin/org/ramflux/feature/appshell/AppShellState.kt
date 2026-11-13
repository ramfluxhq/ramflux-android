// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell

import androidx.compose.runtime.Immutable
import org.ramflux.data.Appearance
import org.ramflux.data.ClientFeature

@Immutable
data class ShellDestination(val feature: ClientFeature, val title: String)
enum class ShellNoticeKind { ERROR, UNAVAILABLE }

@Immutable
data class ShellNotice(val title: String, val description: String, val kind: ShellNoticeKind = ShellNoticeKind.ERROR)

@Immutable
data class AppShellState(
    val status: String = "Opening private data…",
    val ready: Boolean = false,
    val loading: Boolean = true,
    val canRetry: Boolean = false,
    val error: ShellNotice? = null,
    val notice: ShellNotice? = null,
    val appearance: Appearance = Appearance.SYSTEM,
    val savingAppearance: Boolean = false,
    val onboarding: ShellDestination = ShellDestination(ClientFeature.ONBOARDING, "Set up your account"),
    val conversations: ShellDestination = ShellDestination(ClientFeature.CONVERSATIONS, "Conversations"),
    val contacts: ShellDestination = ShellDestination(ClientFeature.CONTACTS, "People"),
    val settings: ShellDestination = ShellDestination(ClientFeature.SETTINGS, "Settings")
)

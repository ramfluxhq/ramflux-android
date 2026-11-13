// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
enum class OnboardingPage(val board: String, val title: String) {
    START("S01", "Welcome to Ramflux"),
    CONTINUE("S02", "Get started"),
    CONNECTION("S03", "Choose a connection"),
    ADDRESS("S04", "Add a node"),
    CHECKING("S05", "Checking the address"),
    IDENTITY("S06", "Check node identity"),
    CONNECTION_FAILED("S07", "Unable to connect"),
    RESUME("S08", "Continue setup"),
    OFFLINE("S09", "Connection unavailable"),
    DEFAULT_NODE("S10", "Use this node"),
    IDENTITY_CHANGED("S11", "Node identity changed"),
    REFUSED("S12", "Connection refused"),
    SAVE_FAILED("S13", "Unable to save"),
    REPAIR("S14", "Review your connection"),
    CONFIRM("S15", "Confirm node address"),
    SAVING("S16", "Saving"),
    SAVED("S17", "Node saved"),
    WELCOME("S18", "Your identity, your choice"),
    CREATE("S19", "Create an account"),
    PHRASE("S20", "Save your recovery phrase"),
    CHALLENGE("S21", "Check your phrase"),
    LOCAL_UNLOCK("S22", "Protect this device"),
    CREATED("S23", "Account status"),
    PAIR("S24", "Link a device"),
    RECOVER("S25", "Recover an account")
}

@Immutable data class AccountItem(val id: String, val label: String, val subtitle: String, val unlocked: Boolean)

@Immutable data class NodeItem(val id: String, val url: String, val keyChanged: Boolean, val isDefault: Boolean, val enabled: Boolean)

@Immutable data class IdentityFacts(val id: String, val url: String, val fingerprint: String, val keyChanged: Boolean)
enum class OnboardingNoticeKind { ERROR, UNAVAILABLE, UNKNOWN_OUTCOME }

@Immutable data class OnboardingNotice(val message: String, val kind: OnboardingNoticeKind = OnboardingNoticeKind.ERROR)

@Immutable data class OnboardingState(
    val page: OnboardingPage = OnboardingPage.START,
    val accounts: ImmutableList<AccountItem> = persistentListOf(),
    val nodes: ImmutableList<NodeItem> = persistentListOf(),
    val accountsLoaded: Boolean = false,
    val nodesLoaded: Boolean = false,
    val address: String = "",
    val normalizedOrigin: String? = null,
    val identity: IdentityFacts? = null,
    val selectedNode: NodeItem? = null,
    val busy: Boolean = false,
    val sessionReady: Boolean = false,
    val notice: OnboardingNotice? = null
)

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.onboarding.impl.R
enum class OnboardingPage(val board: String, val title: UiText) {
    START("S01", uiText(R.string.ui_welcome_to_ramflux)),
    CONTINUE("S02", uiText(R.string.ui_get_started)),
    CONNECTION("S03", uiText(R.string.ui_choose_a_connection)),
    ADDRESS("S04", uiText(R.string.ui_add_a_node)),
    CHECKING("S05", uiText(R.string.ui_checking_the_address)),
    IDENTITY("S06", uiText(R.string.ui_check_node_identity)),
    CONNECTION_FAILED("S07", uiText(R.string.ui_unable_to_connect)),
    RESUME("S08", uiText(R.string.ui_continue_setup)),
    OFFLINE("S09", uiText(R.string.ui_connection_unavailable)),
    DEFAULT_NODE("S10", uiText(R.string.ui_use_this_node)),
    IDENTITY_CHANGED("S11", uiText(R.string.ui_node_identity_changed)),
    REFUSED("S12", uiText(R.string.ui_connection_refused)),
    SAVE_FAILED("S13", uiText(R.string.ui_unable_to_save)),
    REPAIR("S14", uiText(R.string.ui_review_your_connection)),
    CONFIRM("S15", uiText(R.string.ui_confirm_node_address)),
    SAVING("S16", uiText(R.string.ui_saving)),
    SAVED("S17", uiText(R.string.ui_node_saved)),
    WELCOME("S18", uiText(R.string.ui_your_identity_your_choice)),
    CREATE("S19", uiText(R.string.ui_create_an_account)),
    PHRASE("S20", uiText(R.string.ui_save_your_recovery_phrase)),
    CHALLENGE("S21", uiText(R.string.ui_check_your_phrase)),
    LOCAL_UNLOCK("S22", uiText(R.string.ui_protect_this_device)),
    CREATED("S23", uiText(R.string.ui_account_status)),
    PAIR("S24", uiText(R.string.ui_link_a_device)),
    RECOVER("S25", uiText(R.string.ui_recover_an_account))
}

@Immutable data class AccountItem(val id: String, val label: String, val subtitle: UiText, val unlocked: Boolean)

@Immutable data class NodeItem(val id: String, val url: String, val keyChanged: Boolean, val isDefault: Boolean, val enabled: Boolean)

@Immutable data class IdentityFacts(val id: String, val url: String, val fingerprint: String, val keyChanged: Boolean)
enum class OperatorKeyIssue { REQUIRED, MISMATCH }
enum class OnboardingNoticeKind { ERROR, UNAVAILABLE, UNKNOWN_OUTCOME }

@Immutable data class OnboardingNotice(val message: UiText, val kind: OnboardingNoticeKind = OnboardingNoticeKind.ERROR)

@Immutable data class OnboardingState(
    val page: OnboardingPage = OnboardingPage.START,
    val accounts: ImmutableList<AccountItem> = persistentListOf(),
    val nodes: ImmutableList<NodeItem> = persistentListOf(),
    val accountsLoaded: Boolean = false,
    val nodesLoaded: Boolean = false,
    val address: String = "",
    val operatorKey: String = "",
    val operatorKeyIssue: OperatorKeyIssue? = null,
    val normalizedOrigin: String? = null,
    val identity: IdentityFacts? = null,
    val selectedNode: NodeItem? = null,
    val busy: Boolean = false,
    val sessionReady: Boolean = false,
    val notice: OnboardingNotice? = null,
    val displayName: String = "",
    val phraseSlots: Int = 0,
    val challengePositions: ImmutableList<Int> = persistentListOf(),
    val createdAccountId: String? = null
)

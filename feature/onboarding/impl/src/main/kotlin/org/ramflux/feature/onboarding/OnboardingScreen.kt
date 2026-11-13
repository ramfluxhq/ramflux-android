// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.render
import org.ramflux.feature.onboarding.impl.R
import org.ramflux.generated.*

/** Rendering only. State and all actions are supplied by the feature owner. */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing
) {
    val theme = LocalRFTheme.current
    Scaffold(modifier = Modifier.testTag("onboarding-window"), contentWindowInsets = contentWindowInsets) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                Box(Modifier.testTag("onboarding-header")) {
                    RFPageHeader(
                        state.page.title.render(),
                        stringResource(R.string.ui_your_identity_your_connections),
                        theme,
                        leading = { TextButton(onClick = { onAction(OnboardingAction.Back) }) { Text(stringResource(R.string.ui_back)) } },
                        trailing = {
                            TextButton(
                                enabled = !state.busy && state.sessionReady,
                                onClick = { onAction(OnboardingAction.Refresh) }
                            ) { Text(stringResource(R.string.ui_refresh)) }
                        }
                    )
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().testTag("onboarding-body")
                        .verticalScroll(rememberScrollState()).padding(theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.busy) {
                        RFStateBlock(
                            stringResource(
                                R.string.ui_please_wait
                            ),
                            stringResource(R.string.ui_checking_the_current_state),
                            RFStateKind.Loading,
                            theme,
                            action = {
                            }
                        )
                    }
                    state.notice?.let { notice ->
                        RFStateBlock(
                            if (notice.kind == OnboardingNoticeKind.UNAVAILABLE) {
                                stringResource(
                                    R.string.ui_not_available_yet
                                )
                            } else {
                                stringResource(R.string.ui_unable_to_continue)
                            },
                            notice.message.render(),
                            if (notice.kind == OnboardingNoticeKind.UNAVAILABLE) RFStateKind.Empty else RFStateKind.Error,
                            theme,
                            action = {}
                        )
                    }
                    if (state.sessionReady) {
                        when (state.page) {
                            OnboardingPage.START, OnboardingPage.CONTINUE, OnboardingPage.WELCOME, OnboardingPage.RESUME -> {
                                Text(
                                    stringResource(R.string.ui_private_conversations_an_identity_you_control),
                                    style = MaterialTheme.typography.headlineMedium
                                )
                                Text(stringResource(R.string.ui_connect_to_a_node_then_create_or_open))
                                RFActionButton(
                                    "connect",
                                    stringResource(R.string.ui_set_up_a_connection),
                                    RFButtonKind.Primary,
                                    theme,
                                    disabled = state.busy,
                                    onAction = { onAction(OnboardingAction.Page(OnboardingPage.CONNECTION)) }
                                )
                                if (state.accountsLoaded && state.accounts.isEmpty()) {
                                    Text(
                                        stringResource(R.string.ui_no_accounts_are_saved_on_this_device)
                                    )
                                }
                                for (account in state.accounts) RFIdentityRow(
                                    account.id,
                                    account.label,
                                    account.subtitle.render(),
                                    theme,
                                    avatar = { RFAvatar(account.label.take(1), RFAvatarKind.Person, theme) },
                                    badge = {},
                                    trailing = {
                                        TextButton(
                                            enabled = !state.busy,
                                            onClick = { onAction(OnboardingAction.SelectAccount(account.id)) }
                                        ) {
                                            Text(
                                                if (account.unlocked) {
                                                    stringResource(
                                                        R.string.ui_open
                                                    )
                                                } else {
                                                    stringResource(R.string.ui_unlock)
                                                }
                                            )
                                        }
                                    }
                                )
                                for ((target, label) in listOf(
                                    OnboardingPage.CREATE to stringResource(R.string.ui_create_account),
                                    OnboardingPage.PAIR to stringResource(R.string.ui_link_device),
                                    OnboardingPage.RECOVER to stringResource(R.string.ui_recover_account)
                                )) {
                                    RFActionButton(
                                        target.name,
                                        label,
                                        RFButtonKind.Secondary,
                                        theme,
                                        disabled = state.busy,
                                        onAction = { onAction(OnboardingAction.Page(target)) }
                                    )
                                }
                            }
                            OnboardingPage.CONNECTION, OnboardingPage.REPAIR -> {
                                RFSectionHeader(stringResource(R.string.ui_saved_nodes), theme, trailing = {})
                                if (state.nodesLoaded && state.nodes.isEmpty()) Text(stringResource(R.string.ui_no_saved_nodes))
                                for (node in state.nodes) RFListRow(
                                    node.id,
                                    node.id,
                                    node.url,
                                    theme,
                                    disabled = state.busy,
                                    leading = {},
                                    trailing = { if (node.isDefault) RFBadge(stringResource(R.string.ui_default), theme) },
                                    onAction = { onAction(OnboardingAction.ChooseNode(node.id)) }
                                )
                                RFActionButton(
                                    "add-node",
                                    stringResource(R.string.ui_add_a_node),
                                    RFButtonKind.Primary,
                                    theme,
                                    disabled = state.busy,
                                    onAction = { onAction(OnboardingAction.Page(OnboardingPage.ADDRESS)) }
                                )
                            }
                            OnboardingPage.DEFAULT_NODE -> state.selectedNode?.let { node ->
                                Text(node.id)
                                Text(node.url)
                                if (node.keyChanged || !node.enabled) {
                                    Text(
                                        stringResource(R.string.ui_this_node_cannot_be_selected_review_its_identity)
                                    )
                                }
                                RFActionButton(
                                    "use-node",
                                    stringResource(R.string.ui_use_this_node),
                                    RFButtonKind.Primary,
                                    theme,
                                    disabled = state.busy || node.keyChanged || !node.enabled,
                                    onAction = { onAction(OnboardingAction.UseNode) }
                                )
                            }
                            OnboardingPage.ADDRESS, OnboardingPage.CONNECTION_FAILED, OnboardingPage.OFFLINE, OnboardingPage.REFUSED -> {
                                Text(stringResource(R.string.ui_add_your_node), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.ui_enter_its_https_address_you_will_review_the))
                                state.operatorKeyIssue?.let { issue ->
                                    Text(
                                        stringResource(
                                            if (issue == OperatorKeyIssue.REQUIRED) {
                                                R.string.ui_operator_key_required
                                            } else {
                                                R.string.ui_operator_key_mismatch
                                            }
                                        )
                                    )
                                }
                                Text(stringResource(R.string.ui_operator_key_explanation))
                                RFTextField(
                                    "operator-key",
                                    stringResource(R.string.ui_operator_key),
                                    state.operatorKey,
                                    theme,
                                    disabled = state.busy,
                                    onValueChange = { onAction(OnboardingAction.OperatorKey(it)) }
                                )
                                RFTextField(
                                    "node-address",
                                    stringResource(R.string.ui_node_address),
                                    state.address,
                                    theme,
                                    disabled = state.busy,
                                    onValueChange = { onAction(OnboardingAction.Address(it)) }
                                )
                            }
                            OnboardingPage.IDENTITY, OnboardingPage.IDENTITY_CHANGED ->
                                state.identity?.let { identity ->
                                    RFTrustBanner(
                                        stringResource(R.string.ui_compare_the_node_fingerprint),
                                        stringResource(R.string.ui_compare_this_fingerprint_with_your_node_operator_a),
                                        identity.keyChanged,
                                        theme,
                                        icon = {},
                                        action = {}
                                    )
                                    Text(identity.id)
                                    Text(identity.url)
                                    Text(identity.fingerprint, style = MaterialTheme.typography.bodySmall)
                                }
                            OnboardingPage.CONFIRM -> {
                                Text(stringResource(R.string.ui_entered_address), style = MaterialTheme.typography.labelLarge)
                                Text(state.address)
                                Text(stringResource(R.string.ui_address_to_verify), style = MaterialTheme.typography.labelLarge)
                                Text(state.normalizedOrigin ?: stringResource(R.string.ui_return_to_edit_the_address))
                                Text(stringResource(R.string.ui_only_the_https_origin_is_sent_paths_query))
                            }
                            OnboardingPage.SAVED -> {
                                Text(stringResource(R.string.ui_node_saved_choose_it_from_your_saved_nodes))
                                RFActionButton(
                                    "choose-saved",
                                    stringResource(R.string.ui_choose_a_saved_node),
                                    RFButtonKind.Primary,
                                    theme,
                                    onAction = { onAction(OnboardingAction.Page(OnboardingPage.CONNECTION)) }
                                )
                            }
                            OnboardingPage.SAVE_FAILED -> RFActionButton(
                                "review-nodes",
                                stringResource(R.string.ui_review_saved_nodes),
                                RFButtonKind.Secondary,
                                theme,
                                disabled = state.busy,
                                onAction = { onAction(OnboardingAction.Page(OnboardingPage.CONNECTION)) }
                            )
                            OnboardingPage.CHECKING, OnboardingPage.SAVING -> Unit
                            OnboardingPage.CREATE, OnboardingPage.PHRASE, OnboardingPage.CHALLENGE, OnboardingPage.LOCAL_UNLOCK,
                            OnboardingPage.CREATED, OnboardingPage.PAIR, OnboardingPage.RECOVER -> {
                                Text(
                                    stringResource(R.string.ui_secure_setup_is_unavailable_no_account_phrase_or)
                                )
                                RFActionButton(
                                    "return-start",
                                    stringResource(R.string.ui_return_to_start),
                                    RFButtonKind.Secondary,
                                    theme,
                                    disabled = state.busy,
                                    onAction = { onAction(OnboardingAction.Page(OnboardingPage.START)) }
                                )
                            }
                        }
                    }
                }
                OnboardingFooter(state, onAction)
            }
        }
    }
}

@Composable
private fun OnboardingFooter(state: OnboardingState, onAction: (OnboardingAction) -> Unit) {
    val theme = LocalRFTheme.current
    if (!state.sessionReady) return
    Column(
        Modifier.fillMaxWidth().testTag("onboarding-footer").padding(horizontal = theme.gap.dp, vertical = (theme.gap * .5f).dp),
        verticalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp)
    ) {
        when (state.page) {
            OnboardingPage.ADDRESS, OnboardingPage.CONNECTION_FAILED, OnboardingPage.OFFLINE, OnboardingPage.REFUSED ->
                RFActionButton(
                    "review-address",
                    stringResource(R.string.ui_verify_node),
                    RFButtonKind.Primary,
                    theme,
                    disabled = state.busy || state.address.isBlank(),
                    onAction = { onAction(OnboardingAction.ReviewAddress) }
                )
            OnboardingPage.CONFIRM -> {
                RFActionButton(
                    "verify-origin",
                    stringResource(R.string.ui_verify_this_address),
                    RFButtonKind.Primary,
                    theme,
                    disabled = state.busy || state.normalizedOrigin == null,
                    onAction = { onAction(OnboardingAction.Discover) }
                )
                RFActionButton(
                    "edit-address",
                    stringResource(R.string.ui_return_to_edit),
                    RFButtonKind.Secondary,
                    theme,
                    disabled = state.busy,
                    onAction = { onAction(OnboardingAction.Page(OnboardingPage.ADDRESS)) }
                )
            }
            OnboardingPage.IDENTITY, OnboardingPage.IDENTITY_CHANGED ->
                RFActionButton(
                    "confirm-node",
                    stringResource(R.string.ui_save_this_node),
                    RFButtonKind.Primary,
                    theme,
                    disabled = state.busy || state.identity == null || state.identity.keyChanged,
                    onAction = { onAction(OnboardingAction.Confirm) }
                )
            else -> Unit
        }
    }
}

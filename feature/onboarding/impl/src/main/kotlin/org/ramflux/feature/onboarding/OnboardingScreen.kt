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
                        state.page.title,
                        "Your identity. Your connections.",
                        theme,
                        leading = { TextButton(onClick = { onAction(OnboardingAction.Back) }) { Text("Back") } },
                        trailing = {
                            TextButton(
                                enabled = !state.busy && state.sessionReady,
                                onClick = { onAction(OnboardingAction.Refresh) }
                            ) { Text("Refresh") }
                        }
                    )
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().testTag("onboarding-body")
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (state.busy) RFStateBlock("Please wait", "Checking the current state…", RFStateKind.Loading, theme, action = {})
                    state.notice?.let { notice ->
                        RFStateBlock(
                            if (notice.kind == OnboardingNoticeKind.UNAVAILABLE) "Not available yet" else "Unable to continue",
                            notice.message,
                            if (notice.kind == OnboardingNoticeKind.UNAVAILABLE) RFStateKind.Empty else RFStateKind.Error,
                            theme,
                            action = {}
                        )
                    }
                    if (state.sessionReady) {
                        when (state.page) {
                            OnboardingPage.START, OnboardingPage.CONTINUE, OnboardingPage.WELCOME, OnboardingPage.RESUME -> {
                                Text("Private conversations. An identity you control.", style = MaterialTheme.typography.headlineMedium)
                                Text("Connect to a node, then create or open your account.")
                                RFActionButton(
                                    "connect",
                                    "Set up a connection",
                                    RFButtonKind.Primary,
                                    theme,
                                    disabled = state.busy,
                                    onAction = { onAction(OnboardingAction.Page(OnboardingPage.CONNECTION)) }
                                )
                                if (state.accountsLoaded && state.accounts.isEmpty()) Text("No accounts are saved on this device.")
                                for (account in state.accounts) RFIdentityRow(
                                    account.id,
                                    account.label,
                                    account.subtitle,
                                    theme,
                                    avatar = { RFAvatar(account.label.take(1), RFAvatarKind.Person, theme) },
                                    badge = {},
                                    trailing = {
                                        TextButton(
                                            enabled = !state.busy,
                                            onClick = { onAction(OnboardingAction.SelectAccount(account.id)) }
                                        ) {
                                            Text(if (account.unlocked) "Open" else "Unlock")
                                        }
                                    }
                                )
                                for ((target, label) in listOf(
                                    OnboardingPage.CREATE to "Create account",
                                    OnboardingPage.PAIR to "Link device",
                                    OnboardingPage.RECOVER to "Recover account"
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
                                RFSectionHeader("Saved nodes", theme, trailing = {})
                                if (state.nodesLoaded && state.nodes.isEmpty()) Text("No saved nodes.")
                                for (node in state.nodes) RFListRow(
                                    node.id,
                                    node.id,
                                    node.url,
                                    theme,
                                    disabled = state.busy,
                                    leading = {},
                                    trailing = { if (node.isDefault) RFBadge("Default", theme) },
                                    onAction = { onAction(OnboardingAction.ChooseNode(node.id)) }
                                )
                                RFActionButton(
                                    "add-node",
                                    "Add a node",
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
                                        "This node cannot be selected. Review its identity and availability with your node operator."
                                    )
                                }
                                RFActionButton(
                                    "use-node",
                                    "Use this node",
                                    RFButtonKind.Primary,
                                    theme,
                                    disabled = state.busy || node.keyChanged || !node.enabled,
                                    onAction = { onAction(OnboardingAction.UseNode) }
                                )
                            }
                            OnboardingPage.ADDRESS, OnboardingPage.CONNECTION_FAILED, OnboardingPage.OFFLINE, OnboardingPage.REFUSED -> {
                                Text("Add your node", style = MaterialTheme.typography.titleLarge)
                                Text("Enter its HTTPS address. You will review the address before verification.")
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
                                    "Node address",
                                    state.address,
                                    theme,
                                    disabled = state.busy,
                                    onValueChange = { onAction(OnboardingAction.Address(it)) }
                                )
                            }
                            OnboardingPage.IDENTITY, OnboardingPage.IDENTITY_CHANGED ->
                                state.identity?.let { identity ->
                                    RFTrustBanner(
                                        "Compare the node fingerprint",
                                        "Compare this fingerprint with your node operator. A saved address alone does not prove identity.",
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
                                Text("Entered address", style = MaterialTheme.typography.labelLarge)
                                Text(state.address)
                                Text("Address to verify", style = MaterialTheme.typography.labelLarge)
                                Text(state.normalizedOrigin ?: "Return to edit the address.")
                                Text("Only the HTTPS origin is sent. Paths, query parameters and fragments are not sent.")
                            }
                            OnboardingPage.SAVED -> {
                                Text("Node saved. Choose it from your saved nodes to use it.")
                                RFActionButton(
                                    "choose-saved",
                                    "Choose a saved node",
                                    RFButtonKind.Primary,
                                    theme,
                                    onAction = { onAction(OnboardingAction.Page(OnboardingPage.CONNECTION)) }
                                )
                            }
                            OnboardingPage.SAVE_FAILED -> RFActionButton(
                                "review-nodes",
                                "Review saved nodes",
                                RFButtonKind.Secondary,
                                theme,
                                disabled = state.busy,
                                onAction = { onAction(OnboardingAction.Page(OnboardingPage.CONNECTION)) }
                            )
                            OnboardingPage.CHECKING, OnboardingPage.SAVING -> Unit
                            OnboardingPage.CREATE, OnboardingPage.PHRASE, OnboardingPage.CHALLENGE, OnboardingPage.LOCAL_UNLOCK,
                            OnboardingPage.CREATED, OnboardingPage.PAIR, OnboardingPage.RECOVER -> {
                                Text(
                                    "Secure setup is unavailable. No account, phrase or linked device has been created."
                                )
                                RFActionButton(
                                    "return-start",
                                    "Return to start",
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
        Modifier.fillMaxWidth().testTag("onboarding-footer").padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (state.page) {
            OnboardingPage.ADDRESS, OnboardingPage.CONNECTION_FAILED, OnboardingPage.OFFLINE, OnboardingPage.REFUSED ->
                RFActionButton(
                    "review-address",
                    "Verify node",
                    RFButtonKind.Primary,
                    theme,
                    disabled = state.busy || state.address.isBlank(),
                    onAction = { onAction(OnboardingAction.ReviewAddress) }
                )
            OnboardingPage.CONFIRM -> {
                RFActionButton(
                    "verify-origin",
                    "Verify this address",
                    RFButtonKind.Primary,
                    theme,
                    disabled = state.busy || state.normalizedOrigin == null,
                    onAction = { onAction(OnboardingAction.Discover) }
                )
                RFActionButton(
                    "edit-address",
                    "Return to edit",
                    RFButtonKind.Secondary,
                    theme,
                    disabled = state.busy,
                    onAction = { onAction(OnboardingAction.Page(OnboardingPage.ADDRESS)) }
                )
            }
            OnboardingPage.IDENTITY, OnboardingPage.IDENTITY_CHANGED ->
                RFActionButton(
                    "confirm-node",
                    "Save this node",
                    RFButtonKind.Primary,
                    theme,
                    disabled = state.busy || state.identity == null || state.identity.keyChanged,
                    onAction = { onAction(OnboardingAction.Confirm) }
                )
            else -> Unit
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.shortAccountId
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.*
import org.ramflux.designsystem.render
import org.ramflux.feature.onboarding.impl.R

/**
 * Rendering only. State and all actions are supplied by the feature owner. The caller owns window insets
 * (including the keyboard) and the width cap; the screen fills the space it is given.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
    modifier: Modifier = Modifier,
    drawPhrase: (Int, (CharArray) -> Unit) -> Unit = { _, _ -> }
) {
    val theme = LocalRFTheme.current
    val challenge = remember { ChallengeEntry() }
    val unlockCode = remember { ErasableBuffer() }
    val recovery = remember { RecoveryEntry() }
    Scaffold(modifier = modifier.testTag("onboarding-window"), contentWindowInsets = WindowInsets(0, 0, 0, 0)) { insets ->
        // The keyboard never covers the helper or the button: the body scrolls above it and the footer rides on it.
        val frame = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()
        if (state.page == OnboardingPage.CREATE) {
            CreateAccountPage(state, onAction, frame)
            return@Scaffold
        }
        Box(frame, contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                val subtitle = stringResource(R.string.ui_your_identity_your_connections)
                Box(Modifier.testTag("onboarding-header")) {
                    KitPageHeader(
                        state.page.title.render(),
                        subtitle = subtitle,
                        // The created account is reached by Continue only; going back into the finished ceremony is not offered.
                        // The welcome root and the lock page have no back control either (system back still works).
                        onBack = if (state.page.isRoot) null else ({ onAction(OnboardingAction.Back) })
                    )
                }
                // The subtitle scrolls with the body, so at large font scales in short windows the fixed header and
                // the actions keep their full height.
                Column(Modifier.weight(1f).fillMaxWidth().testTag("onboarding-body").verticalScroll(rememberScrollState())) {
                    Column(Modifier.fillMaxWidth().padding(theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                        // The tagline sits inside the padded column, so it keeps the shared gutter like every other line.
                        Text(subtitle, color = theme.secondary, modifier = Modifier.testTag("onboarding-tagline"))
                        if (state.busy) {
                            KitEmpty(
                                "loader",
                                stringResource(R.string.ui_please_wait),
                                stringResource(R.string.ui_checking_the_current_state)
                            )
                        }
                        state.notice?.let { notice ->
                            KitEmpty(
                                if (notice.kind == OnboardingNoticeKind.UNAVAILABLE) "inbox" else "circle-alert",
                                if (notice.kind == OnboardingNoticeKind.UNAVAILABLE) {
                                    stringResource(R.string.ui_not_available_yet)
                                } else {
                                    stringResource(R.string.ui_unable_to_continue)
                                },
                                notice.message.render()
                            )
                        }
                        if (state.sessionReady) {
                            when (state.page) {
                                OnboardingPage.START, OnboardingPage.CONTINUE, OnboardingPage.WELCOME, OnboardingPage.RESUME ->
                                    WelcomePage(state, onAction)
                                OnboardingPage.CONNECTION, OnboardingPage.REPAIR -> {
                                    Text(stringResource(R.string.ui_saved_nodes))
                                    if (state.nodesLoaded && state.nodes.isEmpty()) Text(stringResource(R.string.ui_no_saved_nodes))
                                    for (node in state.nodes) {
                                        KitRow(
                                            node.id,
                                            { onAction(OnboardingAction.ChooseNode(node.id)) },
                                            value = node.url,
                                            enabled = !state.busy,
                                            trailingContent = { if (node.isDefault) Text(stringResource(R.string.ui_default)) },
                                            testTag = node.id
                                        )
                                    }
                                    KitButton(
                                        stringResource(R.string.ui_add_a_node),
                                        { onAction(OnboardingAction.Page(OnboardingPage.ADDRESS)) },
                                        enabled = !state.busy,
                                        tag = "add-node"
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
                                    KitButton(
                                        stringResource(R.string.ui_use_this_node),
                                        { onAction(OnboardingAction.UseNode) },
                                        enabled = !state.busy && !node.keyChanged && node.enabled,
                                        tag = "use-node"
                                    )
                                }
                                OnboardingPage.ADDRESS,
                                OnboardingPage.CONNECTION_FAILED,
                                OnboardingPage.OFFLINE,
                                OnboardingPage.REFUSED -> {
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
                                    KitTextField(
                                        state.operatorKey,
                                        { onAction(OnboardingAction.OperatorKey(it)) },
                                        stringResource(R.string.ui_operator_key),
                                        enabled = !state.busy,
                                        tag = "operator-key"
                                    )
                                    KitTextField(
                                        state.address,
                                        { onAction(OnboardingAction.Address(it)) },
                                        stringResource(R.string.ui_node_address),
                                        enabled = !state.busy,
                                        tag = "node-address"
                                    )
                                }
                                OnboardingPage.IDENTITY, OnboardingPage.IDENTITY_CHANGED -> state.identity?.let { TrustNodePage(it) }
                                OnboardingPage.CONFIRM -> {
                                    Text(stringResource(R.string.ui_entered_address), style = MaterialTheme.typography.labelLarge)
                                    Text(state.address)
                                    Text(stringResource(R.string.ui_address_to_verify), style = MaterialTheme.typography.labelLarge)
                                    Text(state.normalizedOrigin ?: stringResource(R.string.ui_return_to_edit_the_address))
                                    Text(stringResource(R.string.ui_only_the_https_origin_is_sent_paths_query))
                                }
                                OnboardingPage.SAVED -> {
                                    Text(stringResource(R.string.ui_node_saved_choose_it_from_your_saved_nodes))
                                    KitButton(
                                        stringResource(R.string.ui_choose_a_saved_node),
                                        { onAction(OnboardingAction.Page(OnboardingPage.CONNECTION)) },
                                        tag = "choose-saved"
                                    )
                                }
                                OnboardingPage.SAVE_FAILED -> KitButton(
                                    stringResource(R.string.ui_review_saved_nodes),
                                    { onAction(OnboardingAction.Page(OnboardingPage.CONNECTION)) },
                                    kind = KitButtonKind.Secondary,
                                    enabled = !state.busy,
                                    tag = "review-nodes"
                                )
                                OnboardingPage.CHECKING, OnboardingPage.SAVING, OnboardingPage.CREATE -> Unit
                                OnboardingPage.PHRASE -> PhrasePage(state, drawPhrase)
                                OnboardingPage.CHALLENGE -> ChallengePage(state, challenge)
                                OnboardingPage.CREATED -> CreatedAccountPage(state)
                                OnboardingPage.LOCAL_UNLOCK -> {
                                    // The lock page anatomy (D-2): initial, display name, "Locked", then the instruction.
                                    AccountIdentity(
                                        state.accounts.firstOrNull { it.id == state.unlockAccountId }?.label,
                                        status = stringResource(R.string.ui_locked),
                                        nameTag = "unlock-account-name"
                                    )
                                    Text(stringResource(R.string.app_lock_body), style = MaterialTheme.typography.bodyLarge)
                                    AccountCodeField(
                                        unlockCode,
                                        stringResource(R.string.ui_account_code),
                                        "unlock-code",
                                        enabled = !state.busy,
                                        helper = stringResource(R.string.ui_account_code_explanation)
                                    )
                                    state.recoverAccountId?.let { id ->
                                        KitButton(
                                            stringResource(R.string.ui_recover_with_phrase),
                                            { onAction(OnboardingAction.BeginRecover(id)) },
                                            kind = KitButtonKind.Secondary,
                                            enabled = !state.busy,
                                            tag = "recover-locked"
                                        )
                                    }
                                    state.forgetAccountId?.let { id ->
                                        // One tap never removes an account: the button opens the confirmation.
                                        var confirming by remember(id) { mutableStateOf(false) }
                                        if (confirming) {
                                            RemoveAccountDialog(
                                                accountName = state.accounts.firstOrNull { it.id == id }?.label,
                                                accountId = id,
                                                busy = state.busy,
                                                failed = false,
                                                onConfirm = {
                                                    confirming = false
                                                    onAction(OnboardingAction.ForgetAccount(id))
                                                },
                                                onDismiss = { confirming = false }
                                            )
                                        }
                                        KitButton(
                                            stringResource(R.string.ui_forget_account_on_device),
                                            { confirming = true },
                                            kind = KitButtonKind.Secondary,
                                            enabled = !state.busy,
                                            tag = "forget-account"
                                        )
                                    }
                                }
                                OnboardingPage.RECOVER -> RecoverPage(state, recovery, onAction)
                                // One back control per step: the header's back returns to the start.
                                OnboardingPage.PAIR -> Text(
                                    stringResource(R.string.ui_secure_setup_is_unavailable_no_account_phrase_or)
                                )
                            }
                        }
                    }
                }
                OnboardingFooter(state, challenge, unlockCode, recovery, onAction)
            }
        }
    }
}

@Composable
private fun OnboardingFooter(
    state: OnboardingState,
    challenge: ChallengeEntry,
    unlockCode: ErasableBuffer,
    recovery: RecoveryEntry,
    onAction: (OnboardingAction) -> Unit
) {
    val theme = LocalRFTheme.current
    if (!state.sessionReady) return
    Column(
        Modifier.fillMaxWidth().testTag("onboarding-footer").padding(horizontal = theme.gap.dp, vertical = (theme.gap * .5f).dp),
        verticalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp)
    ) {
        when (state.page) {
            OnboardingPage.ADDRESS, OnboardingPage.CONNECTION_FAILED, OnboardingPage.OFFLINE, OnboardingPage.REFUSED ->
                KitButton(
                    stringResource(R.string.ui_verify_node),
                    { onAction(OnboardingAction.ReviewAddress) },
                    enabled = !state.busy && state.address.isNotBlank(),
                    tag = "review-address"
                )
            // One back control per step: the header's back returns to the address.
            OnboardingPage.CONFIRM -> KitButton(
                stringResource(R.string.ui_verify_this_address),
                { onAction(OnboardingAction.Discover) },
                enabled = !state.busy && state.normalizedOrigin != null,
                tag = "verify-origin"
            )
            OnboardingPage.PHRASE -> KitButton(
                stringResource(R.string.ui_i_have_saved_the_phrase),
                { onAction(OnboardingAction.PhraseSaved) },
                enabled = !state.busy && state.phraseSlots != 0,
                tag = "phrase-saved"
            )
            OnboardingPage.CHALLENGE -> KitButton(
                stringResource(R.string.ui_create_account),
                { onAction(OnboardingAction.ConfirmChallenge(challenge.words, challenge.code)) },
                enabled = !state.busy && challenge.ready(state.challengePositions.size),
                tag = "confirm-account"
            )
            OnboardingPage.LOCAL_UNLOCK -> KitButton(
                stringResource(R.string.ui_unlock),
                { onAction(OnboardingAction.SubmitUnlock(unlockCode)) },
                enabled = !state.busy,
                tag = "unlock-account"
            )
            OnboardingPage.RECOVER -> if (state.recovery != null) {
                KitButton(
                    stringResource(R.string.ui_recover_account_action),
                    { onAction(OnboardingAction.ConfirmRecover(recovery.phrase, recovery.code)) },
                    enabled = !state.busy && recovery.ready(),
                    tag = "confirm-recovery"
                )
            }
            OnboardingPage.CREATED -> KitButton(
                stringResource(R.string.ui_continue_into_the_account),
                { onAction(OnboardingAction.ContinueCreated) },
                enabled = !state.busy && state.createdAccountId != null,
                tag = "continue-created"
            )
            OnboardingPage.IDENTITY, OnboardingPage.IDENTITY_CHANGED -> {
                KitButton(
                    stringResource(R.string.ui_trust_this_node),
                    { onAction(OnboardingAction.Confirm) },
                    enabled = !state.busy && state.identity != null && !state.identity.keyChanged,
                    tag = "confirm-node"
                )
                KitButton(
                    stringResource(R.string.ui_cancel),
                    { onAction(OnboardingAction.Back) },
                    Modifier.fillMaxWidth(),
                    kind = KitButtonKind.Text,
                    tag = "cancel-node"
                )
            }
            else -> Unit
        }
    }
}

/** The pages that are roots of the flow or the lock page: they draw no back control in their header. */
private val OnboardingPage.isRoot: Boolean
    get() = when (this) {
        OnboardingPage.START, OnboardingPage.CONTINUE, OnboardingPage.WELCOME, OnboardingPage.RESUME,
        OnboardingPage.LOCAL_UNLOCK, OnboardingPage.CREATED -> true
        else -> false
    }

/**
 * The welcome page. With accounts on this device it opens with "Your accounts" (a locked row opens its lock page) and one
 * secondary "Add another account" that reveals the setup choices; the connection button is primary only without an account.
 */
@Composable
private fun WelcomePage(state: OnboardingState, onAction: (OnboardingAction) -> Unit) {
    val hasAccounts = state.accounts.isNotEmpty()
    var adding by remember { mutableStateOf(false) }
    if (hasAccounts) {
        Text(
            stringResource(R.string.ui_your_accounts),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("accounts-section")
        )
        for (account in state.accounts) {
            // The whole row opens the account, or its lock page when it is locked.
            KitRow(
                account.label,
                { if (!state.busy) onAction(OnboardingAction.SelectAccount(account.id)) },
                value = shortAccountId(account.id),
                leading = { KitAvatar(account.label.take(1), 36f, KitAvatarKind.Person) },
                trailingContent = { if (!account.unlocked) Text(stringResource(R.string.ui_locked)) },
                testTag = account.id
            )
        }
    } else {
        Text(
            stringResource(R.string.ui_private_conversations_an_identity_you_control),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(stringResource(R.string.ui_connect_to_a_node_then_create_or_open))
        if (state.accountsLoaded) Text(stringResource(R.string.ui_no_accounts_are_saved_on_this_device))
    }
    if (hasAccounts && !adding) {
        KitButton(
            stringResource(R.string.ui_add_another_account),
            { adding = true },
            kind = KitButtonKind.Secondary,
            enabled = !state.busy,
            tag = "add-account"
        )
    }
    if (!hasAccounts || adding) {
        KitButton(
            stringResource(R.string.ui_set_up_a_connection),
            { onAction(OnboardingAction.Page(OnboardingPage.CONNECTION)) },
            kind = if (hasAccounts) KitButtonKind.Secondary else KitButtonKind.Primary,
            enabled = !state.busy,
            tag = "connect"
        )
        for ((target, label) in listOf(
            OnboardingPage.CREATE to stringResource(R.string.ui_create_account),
            OnboardingPage.PAIR to stringResource(R.string.ui_link_device),
            OnboardingPage.RECOVER to stringResource(R.string.ui_recover_account)
        )) {
            KitButton(
                label,
                { onAction(OnboardingAction.Page(target)) },
                kind = KitButtonKind.Secondary,
                enabled = !state.busy,
                tag = target.name
            )
        }
        KitButton(
            stringResource(R.string.backup_restore_title),
            { onAction(OnboardingAction.RestoreBackup) },
            kind = KitButtonKind.Secondary,
            enabled = !state.busy,
            tag = "restore-backup"
        )
    }
}

/**
 * Trust this node (D-5): the node's name and address, a neutral status line and a collapsed "Details" disclosure with the
 * id, address and key fingerprint. Only a changed key uses the warning card.
 */
@Composable
private fun TrustNodePage(identity: IdentityFacts) {
    var details by remember { mutableStateOf(false) }
    val host = remember(identity.url) { runCatching { java.net.URI(identity.url).host }.getOrNull() ?: identity.url }
    Text(host, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("node-name"))
    Text(identity.url, modifier = Modifier.testTag("node-address-line"))
    if (identity.keyChanged) {
        KitSecurityWarning(
            stringResource(R.string.ui_node_identity_changed),
            stringResource(R.string.ui_the_saved_node_key_changed_confirmation_is_blocked),
            KitWarningTone.Danger
        )
    } else {
        Text(
            stringResource(R.string.ui_node_trust_first),
            color = LocalRFTheme.current.secondary,
            modifier = Modifier.testTag("node-trust-status")
        )
        Text(stringResource(R.string.ui_node_trust_confirmed), color = LocalRFTheme.current.secondary)
    }
    KitButton(stringResource(R.string.ui_details), { details = !details }, kind = KitButtonKind.Text, tag = "node-details-toggle")
    if (details) {
        Column(Modifier.testTag("node-details"), verticalArrangement = Arrangement.spacedBy((LocalRFTheme.current.gap * .5f).dp)) {
            Text(identity.id, style = MaterialTheme.typography.bodySmall)
            Text(identity.url, style = MaterialTheme.typography.bodySmall)
            Text(identity.fingerprint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

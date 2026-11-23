// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ramflux.data.AccountCode
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitAvatar
import org.ramflux.designsystem.kit.KitAvatarKind
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitSecurityWarning
import org.ramflux.designsystem.kit.KitTextField
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.KitWarningTone
import org.ramflux.designsystem.kit.LtrCode
import org.ramflux.designsystem.kit.LtrSecretWords
import org.ramflux.designsystem.render
import org.ramflux.feature.onboarding.impl.R

@Composable
internal fun CreateAccountPage(state: OnboardingState, onAction: (OnboardingAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val chosen = state.selectedNode
    val nodeLabel = if (chosen != null && !chosen.isDefault && chosen.url.isNotEmpty()) {
        chosen.url
    } else {
        stringResource(R.string.ui_n01_official_node)
    }
    // MOB-N01 sits on the grouped page tone, with the Kit header without its bottom rule.
    CompositionLocalProvider(LocalRFTheme provides theme.copy(bg = theme.nav)) {
        CreateAccountColumn(state, onAction, nodeLabel, modifier)
    }
}

@Composable
private fun CreateAccountColumn(state: OnboardingState, onAction: (OnboardingAction) -> Unit, nodeLabel: String, modifier: Modifier) {
    val theme = LocalRFTheme.current
    Column(modifier.fillMaxSize().background(theme.bg).testTag("n01")) {
        KitPageHeader(stringResource(R.string.ui_n01_header), onBack = { onAction(OnboardingAction.Back) }, divider = false, tag = "n01")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = theme.gap.dp, end = theme.gap.dp, top = theme.gap.dp, bottom = (theme.gap * .5f).dp),
            verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
        ) {
            state.notice?.let { notice -> Text(notice.message.render(), color = theme.text) }
            KitTextField(
                state.displayName,
                { onAction(OnboardingAction.DisplayName(it)) },
                stringResource(R.string.ui_display_name),
                helper = stringResource(R.string.ui_n01_name_helper),
                enabled = !state.busy,
                tag = "display-name"
            )
            Text(
                stringResource(R.string.ui_n01_nodes),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = theme.size_secondary.sp,
                    lineHeight = theme.size_secondary.sp * 1.45f,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.secondary
                )
            )
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(theme.radius_card.dp)).background(theme.surface_white)
            ) {
                KitRow(
                    nodeLabel,
                    {},
                    icon = "server",
                    value = stringResource(R.string.ui_n01_selected_node),
                    trailing = KitRowTrailing.None,
                    locked = true,
                    divider = false,
                    testTag = "n01-node"
                )
            }
            KitTipBar(stringResource(R.string.ui_n01_phrase_note), "pencil-line", tag = "n01-note")
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = (theme.gap * .5f).dp)) {
            KitButton(
                stringResource(R.string.ui_n01_button),
                { onAction(OnboardingAction.BeginCreate) },
                Modifier.fillMaxWidth(),
                enabled = state.sessionReady && !state.busy && state.displayName.isNotBlank(),
                tag = "begin-create"
            )
        }
    }
}

/**
 * The one-time recovery phrase. The words are drawn from the borrowed character arrays (never a String,
 * see [org.ramflux.data.RecoveryPhrase]), numbered and left to right in every locale, at the font scale.
 */
@Composable
internal fun PhrasePage(state: OnboardingState, drawPhrase: (Int, (CharArray) -> Unit) -> Unit) {
    Text(stringResource(R.string.ui_write_down_the_recovery_phrase))
    LtrSecretWords(state.phraseSlots, drawPhrase, Modifier.testTag("recovery-phrase"), tag = "phrase-word")
}

/** Challenge secrets stay in these buffers. Confirm reads them once and erases them after the call returns. */
internal class ChallengeEntry {
    val words: List<ErasableBuffer> = List(3) { ErasableBuffer() }
    val code = ErasableBuffer()
    var revision by mutableIntStateOf(0)
        private set

    fun edited() {
        revision++
    }

    fun ready(positionCount: Int): Boolean {
        val tick = revision
        return tick >= 0 && positionCount == words.size && words.none { it.isBlank() } && AccountCode.complete(code.owned())
    }
}

@Composable
internal fun ChallengePage(state: OnboardingState, entry: ChallengeEntry) {
    Text(stringResource(R.string.ui_check_your_phrase))
    state.challengePositions.forEachIndexed { index, position ->
        if (index < entry.words.size) {
            ErasableSecretField(
                entry.words[index],
                stringResource(R.string.ui_recovery_word_position, position),
                "challenge-$index",
                enabled = !state.busy,
                password = false,
                limit = 64,
                onEdited = entry::edited
            )
        }
    }
    AccountCodeField(
        entry.code,
        stringResource(R.string.ui_choose_account_code),
        "account-code",
        enabled = !state.busy,
        helper = stringResource(R.string.ui_account_code_explanation),
        onEdited = entry::edited
    )
}

/** Recovery secrets stay in these buffers. Confirm reads them once and erases them. */
internal class RecoveryEntry {
    val phrase = ErasableBuffer()
    val code = ErasableBuffer()
    var revision by mutableIntStateOf(0)
        private set

    fun edited() {
        revision++
    }

    fun ready(): Boolean {
        val tick = revision
        return tick >= 0 && !phrase.isBlank() && AccountCode.complete(code.owned())
    }
}

/**
 * Phrase recovery of an account whose data is on this phone. Without a started recovery it lists the
 * local accounts; the anchor (fingerprint) is shown left to right for comparison before phrase entry.
 */
@Composable
internal fun RecoverPage(state: OnboardingState, entry: RecoveryEntry, onAction: (OnboardingAction) -> Unit) {
    val recovery = state.recovery
    if (recovery == null) {
        if (state.accountsLoaded && state.accounts.isEmpty()) {
            Text(stringResource(R.string.ui_recover_no_local_account))
            return
        }
        Text(stringResource(R.string.ui_recover_choose_account))
        for (account in state.accounts) {
            KitRow(
                account.label,
                {},
                value = account.subtitle.render(),
                leading = { KitAvatar(account.label.take(1), 36f, KitAvatarKind.Person) },
                trailingContent = {
                    KitButton(
                        stringResource(R.string.ui_recover),
                        { onAction(OnboardingAction.BeginRecover(account.id)) },
                        kind = KitButtonKind.Secondary,
                        enabled = !state.busy,
                        tag = "recover-" + account.id
                    )
                },
                testTag = "recover-row-" + account.id
            )
        }
        return
    }
    KitSecurityWarning(
        stringResource(R.string.ui_account_fingerprint),
        stringResource(R.string.ui_recover_warning),
        KitWarningTone.Warning
    )
    LtrCode(recovery.anchor, groupSize = 4, tag = "recovery-anchor")
    ErasableSecretField(
        entry.phrase,
        stringResource(R.string.ui_recovery_phrase),
        "recovery-phrase-entry",
        enabled = !state.busy,
        password = false,
        limit = 400,
        onEdited = entry::edited
    )
    AccountCodeField(
        entry.code,
        stringResource(R.string.ui_account_code),
        "recovery-code",
        enabled = !state.busy,
        helper = stringResource(R.string.ui_account_code_explanation),
        onEdited = entry::edited
    )
}

@Composable
internal fun CreatedAccountPage(state: OnboardingState) {
    Text(stringResource(R.string.ui_account_created_detail))
    state.createdAccountId?.let { Text(it) }
    state.createdNetworking?.let { networking ->
        Text(
            stringResource(
                when (networking) {
                    CreatedNetworking.CONNECTING -> R.string.ui_created_network_connecting
                    CreatedNetworking.LIVE -> R.string.ui_created_network_connected
                    CreatedNetworking.NOT_CONFIRMED -> R.string.ui_created_network_not_confirmed
                }
            ),
            color = LocalRFTheme.current.secondary,
            modifier = Modifier.testTag("created-networking")
        )
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitTextField
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.render
import org.ramflux.feature.onboarding.impl.R
import org.ramflux.generated.RFTheme

@Composable
internal fun CreateAccountPage(state: OnboardingState, onAction: (OnboardingAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val chosen = state.selectedNode
    val nodeLabel = if (chosen != null && !chosen.isDefault && chosen.url.isNotEmpty()) {
        chosen.url
    } else {
        stringResource(R.string.ui_n01_official_node)
    }
    Column(modifier.fillMaxSize().background(theme.bg).testTag("n01")) {
        KitPageHeader(stringResource(R.string.ui_n01_header), onBack = { onAction(OnboardingAction.Back) }, tag = "n01")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = theme.gap.dp, vertical = (theme.gap * .5f).dp),
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

@Composable
internal fun PhrasePage(state: OnboardingState, drawPhrase: (Int, (CharArray) -> Unit) -> Unit) {
    val theme = LocalRFTheme.current
    Text(stringResource(R.string.ui_write_down_the_recovery_phrase))
    Column(Modifier.fillMaxWidth().testTag("recovery-phrase")) {
        for (index in 0 until state.phraseSlots) PhraseWord(index, theme, drawPhrase)
    }
}

@Composable
private fun PhraseWord(index: Int, theme: RFTheme, drawPhrase: (Int, (CharArray) -> Unit) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().heightIn(min = theme.row_conversation.dp).testTag("phrase-word")
    ) {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = theme.size_body * density
            color = theme.text.toArgb()
        }
        drawPhrase(index) { chars ->
            drawContext.canvas.nativeCanvas.drawText(chars, 0, chars.size, 0f, size.height * 0.75f, paint)
        }
    }
}

/** Challenge secrets stay in these buffers. Confirm reads them once and erases them after the call returns. */
internal class ChallengeEntry {
    val words: List<ErasableBuffer> = List(3) { ErasableBuffer() }
    val passphrase = ErasableBuffer()
    var revision by mutableIntStateOf(0)
        private set

    fun edited() {
        revision++
    }

    fun ready(positionCount: Int): Boolean {
        val tick = revision
        return tick >= 0 && positionCount == words.size && words.none { it.isBlank() } && !passphrase.isEmpty()
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
    Text(stringResource(R.string.ui_data_passphrase_explanation))
    ErasableSecretField(
        entry.passphrase,
        stringResource(R.string.ui_data_passphrase),
        "data-passphrase",
        enabled = !state.busy,
        password = true,
        limit = 128,
        onEdited = entry::edited
    )
}

@Composable
internal fun CreatedAccountPage(state: OnboardingState) {
    Text(stringResource(R.string.ui_account_created_detail))
    state.createdAccountId?.let { Text(it) }
}

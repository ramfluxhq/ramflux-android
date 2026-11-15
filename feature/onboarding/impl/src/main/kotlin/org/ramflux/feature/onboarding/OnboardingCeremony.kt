// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.feature.onboarding.impl.R
import org.ramflux.generated.RFTextField
import org.ramflux.generated.RFTheme

@Composable
internal fun CreateAccountPage(state: OnboardingState, onAction: (OnboardingAction) -> Unit) {
    val theme = LocalRFTheme.current
    Text(stringResource(R.string.ui_display_name_explanation))
    RFTextField(
        "display-name",
        stringResource(R.string.ui_display_name),
        state.displayName,
        theme,
        disabled = state.busy,
        onValueChange = { onAction(OnboardingAction.DisplayName(it)) }
    )
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

/** Challenge secrets stay in these buffers. The footer reads [ready] and [take] without copying them into screen state. */
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

    fun take(): Pair<Array<CharArray>, CharArray> {
        val owned = Array(words.size) { words[it].snapshot() }
        val secret = passphrase.snapshot()
        words.forEach { it.clear() }
        passphrase.clear()
        revision++
        return owned to secret
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

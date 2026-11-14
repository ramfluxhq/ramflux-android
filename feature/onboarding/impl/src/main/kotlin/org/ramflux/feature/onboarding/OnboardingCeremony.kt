// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.feature.onboarding.impl.R
import org.ramflux.generated.RFActionButton
import org.ramflux.generated.RFButtonKind
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

@Composable
internal fun ChallengePage(state: OnboardingState, onAction: (OnboardingAction) -> Unit) {
    val theme = LocalRFTheme.current
    val words = remember { mutableStateListOf("", "", "") }
    var passphrase by remember { mutableStateOf("") }
    Text(stringResource(R.string.ui_check_your_phrase))
    state.challengePositions.forEachIndexed { index, position ->
        RFTextField(
            "challenge-$index",
            stringResource(R.string.ui_recovery_word_position, position),
            words.getOrElse(index) { "" },
            theme,
            disabled = state.busy,
            onValueChange = { if (index in words.indices) words[index] = it.take(64) }
        )
    }
    Text(stringResource(R.string.ui_data_passphrase_explanation))
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it.take(128) },
        label = { Text(stringResource(R.string.ui_data_passphrase)) },
        enabled = !state.busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("data-passphrase")
    )
    RFActionButton(
        "confirm-account",
        stringResource(R.string.ui_confirm_account),
        RFButtonKind.Primary,
        theme,
        disabled = state.busy || state.challengePositions.size != 3 || words.any { it.isBlank() } || passphrase.isEmpty(),
        onAction = {
            val owned = Array(words.size) { words[it].toCharArray() }
            val secret = passphrase.toCharArray()
            words.indices.forEach { words[it] = "" }
            passphrase = ""
            onAction(OnboardingAction.ConfirmChallenge(owned, secret))
        }
    )
}

@Composable
internal fun CreatedAccountPage(state: OnboardingState) {
    Text(stringResource(R.string.ui_account_created_detail))
    state.createdAccountId?.let { Text(it) }
}

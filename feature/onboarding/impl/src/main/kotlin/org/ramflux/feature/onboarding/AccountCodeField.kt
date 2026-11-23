// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import org.ramflux.data.AccountCode
import org.ramflux.designsystem.kit.KitCodeField

/**
 * The six-digit account code. Keystrokes go to [buffer] through [ErasableSecretView] in digit mode
 * (no platform text editor, no undo history); the Kit slots only learn how many digits there are.
 * Leaving the composition wipes the buffer.
 */
@Composable
internal fun AccountCodeField(
    buffer: ErasableBuffer,
    label: String,
    fieldTag: String,
    enabled: Boolean,
    error: String? = null,
    helper: String? = null,
    onEdited: () -> Unit = {}
) {
    val edited by rememberUpdatedState(onEdited)
    var filled by remember(buffer) { mutableIntStateOf(buffer.owned().size) }
    val viewRef = remember { arrayOfNulls<ErasableSecretView>(1) }
    DisposableEffect(buffer) {
        buffer.onCleared = {
            viewRef[0]?.forgetImeState()
            filled = 0
            edited()
        }
        onDispose {
            buffer.onCleared = null
            viewRef[0]?.forgetImeState()
            buffer.clear()
        }
    }
    KitCodeField(
        label,
        filled,
        length = AccountCode.DIGITS,
        error = error,
        helper = helper,
        enabled = enabled,
        tag = fieldTag + "-slots"
    ) {
        AndroidView(
            factory = { context ->
                ErasableSecretView(context).apply {
                    tag = fieldTag
                    viewRef[0] = this
                }
            },
            update = { view ->
                view.isEnabled = enabled
                view.bind(buffer, maskInput = true, maxChars = AccountCode.DIGITS, description = label, digitsOnly = true) {
                    filled = buffer.owned().size
                    edited()
                }
            },
            modifier = Modifier.matchParentSize().testTag(fieldTag)
        )
    }
}

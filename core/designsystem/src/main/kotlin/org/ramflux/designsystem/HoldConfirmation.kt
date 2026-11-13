// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import org.ramflux.generated.RFHoldPresentation

/** Host gesture input only. The state holder owns the timer and authorizes the exact prepared intent. */
@Composable
fun HoldConfirmation(label: String, held: Boolean, onStart: () -> Unit, onEnd: (Boolean) -> Unit) {
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)
    var hadFocus by remember { mutableStateOf(false) }
    Box(
        Modifier.testTag("hold-confirmation").semantics {
            role = Role.Button
            contentDescription = label
        }
            .onFocusChanged {
                if (hadFocus && !it.isFocused) end(false)
                hadFocus = it.isFocused
            }
            .onKeyEvent { event ->
                if (event.key !in listOf(Key.Enter, Key.Spacebar, Key.DirectionCenter)) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyDown) start() else if (event.type == KeyEventType.KeyUp) end(true)
                    true
                }
            }.focusable().pointerInput(Unit) {
                detectTapGestures(onPress = {
                    start()
                    val released = tryAwaitRelease()
                    end(released)
                })
            }
    ) { RFHoldPresentation(label, if (held) 1f else 0f, LocalRFTheme.current) }
}

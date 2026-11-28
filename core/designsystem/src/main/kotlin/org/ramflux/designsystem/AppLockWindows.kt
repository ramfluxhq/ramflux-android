// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy

/*
 * The app-lock window rule. A dialog, popup, menu or sheet is its own window: it is drawn above the activity window,
 * so above the app-lock cover, and the cover's focus, pointer and accessibility guards do not reach it. Every such
 * window in the app is therefore composed through this file:
 * - [AppLockGatedWindow] composes a window only while the cover is down. When the cover rises it removes the window in
 *   the same frame and calls `onCovered` once, which must close the window's own state (or refuse the request it
 *   stands for), so the window does not come back on unlock.
 * - Windows that belong to the cover itself (composed under [LocalInAppLockCover]) stay shown.
 * - The window refuses capture (`FLAG_SECURE`) whenever the host says windows are secure ([LocalSecureWindows]).
 * [CoverAwareDialog], [CoverAwarePopup], [CoverAwareDropdownMenu] and [CoverAwareModalBottomSheet] are the gated forms
 * of the Compose windows; any other window (a Material date or time picker) is called inside an [AppLockGatedWindow]
 * lambda and passes on the policy it is given. `scripts/check-app-lock-windows.py` refuses a window call anywhere else.
 * The wrappers also mark the windows the cover itself opens, so the runtime backstop for library windows
 * (AppLockLibraryWindows.kt) leaves them shown; the cover therefore opens windows only through the wrappers.
 * A library view that opens windows on its own (androidx.emoji2 EmojiPickerView: its skin-tone PopupWindow) is created
 * with [appLockWindowContext], and its windows are closed by that backstop while the cover is up.
 */

/**
 * Whether windows composed through [AppLockGatedWindow] refuse capture (`FLAG_SECURE`). Defaults to true. The app
 * provides `false` only in debug builds, whose activity window is capturable while the cover is down; such windows
 * then inherit the activity window's policy.
 */
val LocalSecureWindows = staticCompositionLocalOf { true }

/**
 * Composes [window] only while the app-lock cover is down (or inside the cover). When the cover rises the window leaves
 * the composition and [onCovered] runs once: it closes the window's state, so the window is not shown again on unlock.
 * [window] receives the capture policy to set on the window it composes.
 */
@Composable
fun AppLockGatedWindow(onCovered: () -> Unit, window: @Composable (securePolicy: SecureFlagPolicy) -> Unit) {
    val gated = LocalAppLockCovered.current && !LocalInAppLockCover.current
    val close by rememberUpdatedState(onCovered)
    LaunchedEffect(gated) { if (gated) close() }
    if (gated) return
    window(if (LocalSecureWindows.current) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit)
}

/** A policy the caller set on purpose wins; otherwise the gate's. */
private fun SecureFlagPolicy.or(gate: SecureFlagPolicy): SecureFlagPolicy = if (this == SecureFlagPolicy.Inherit) gate else this

/** [Dialog] under the app-lock window rule. [onCovered] defaults to [onDismissRequest]. */
@Composable
fun CoverAwareDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    onCovered: () -> Unit = onDismissRequest,
    content: @Composable () -> Unit
) {
    AppLockGatedWindow(onCovered) { policy ->
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                dismissOnBackPress = properties.dismissOnBackPress,
                dismissOnClickOutside = properties.dismissOnClickOutside,
                securePolicy = properties.securePolicy.or(policy),
                usePlatformDefaultWidth = properties.usePlatformDefaultWidth,
                decorFitsSystemWindows = properties.decorFitsSystemWindows,
                windowTitle = properties.windowTitle
            )
        ) {
            CoverWindowMark()
            content()
        }
    }
}

/** [Popup] under the app-lock window rule. [onCovered] closes the state that shows the popup. */
@Composable
fun CoverAwarePopup(
    onCovered: () -> Unit,
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit
) {
    AppLockGatedWindow(onCovered) { policy ->
        Popup(
            alignment = alignment,
            offset = offset,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(
                focusable = properties.focusable,
                dismissOnBackPress = properties.dismissOnBackPress,
                dismissOnClickOutside = properties.dismissOnClickOutside,
                securePolicy = properties.securePolicy.or(policy),
                excludeFromSystemGesture = properties.excludeFromSystemGesture,
                clippingEnabled = properties.clippingEnabled,
                usePlatformDefaultWidth = properties.usePlatformDefaultWidth
            )
        ) {
            CoverWindowMark()
            content()
        }
    }
}

/** Material [DropdownMenu] under the app-lock window rule. A menu open when the cover rises is dismissed. */
@Composable
fun CoverAwareDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit
) {
    // The menu stays composed while it is closed, so the gate's own effect has run before `expanded` turns true: a menu
    // opened while covered is dismissed here, and so does not show after unlock either.
    val gated = LocalAppLockCovered.current && !LocalInAppLockCover.current
    val dismiss by rememberUpdatedState(onDismissRequest)
    LaunchedEffect(gated, expanded) { if (gated && expanded) dismiss() }
    AppLockGatedWindow(onCovered = {}) { policy ->
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
            properties = PopupProperties(focusable = true, securePolicy = policy)
        ) {
            CoverWindowMark()
            content()
        }
    }
}

/** Material [ModalBottomSheet] under the app-lock window rule. [onCovered] defaults to [onDismissRequest]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverAwareModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    onCovered: () -> Unit = onDismissRequest,
    content: @Composable ColumnScope.() -> Unit
) {
    AppLockGatedWindow(onCovered) { policy ->
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            containerColor = containerColor,
            properties = ModalBottomSheetProperties(securePolicy = policy)
        ) {
            CoverWindowMark()
            content()
        }
    }
}

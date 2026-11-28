// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * True while the app-lock cover is up (the lock is on or its state is unread). Windows and menus are not shown then,
 * and an open window is dismissed through its own dismiss callback, so none reopens when the cover goes.
 */
val LocalAppLockCovered = staticCompositionLocalOf { false }

/**
 * While [covered], the content under the app-lock cover is not part of the accessibility tree (screen readers, UI
 * automation): only what is drawn above it is exposed. Composition and state stay as they are.
 */
fun Modifier.hiddenWhileCovered(covered: Boolean): Modifier = if (covered) clearAndSetSemantics { } else this

/**
 * While [covered], keyboard and D-pad focus cannot enter the content under the app-lock cover, nested scrollables and
 * lazy lists included, and a programmatic `requestFocus()` on a control in it is refused. A key event that still
 * reaches it is consumed. `canFocus` alone is not enough: every scrollable and focus group adds its own focus target.
 */
fun Modifier.focusBlockedWhileCovered(covered: Boolean): Modifier = if (covered) {
    this.focusProperties {
        canFocus = false
        onEnter = { cancelFocusChange() }
    }.focusGroup().onPreviewKeyEvent { true }
} else {
    this
}

/** The app-lock cover's focus trap: focus cannot leave it, so Tab, arrow keys and the D-pad cycle inside it. */
fun Modifier.focusTrap(): Modifier = this.focusProperties { onExit = { cancelFocusChange() } }.focusGroup()

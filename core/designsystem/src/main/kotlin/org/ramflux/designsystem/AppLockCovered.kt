// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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

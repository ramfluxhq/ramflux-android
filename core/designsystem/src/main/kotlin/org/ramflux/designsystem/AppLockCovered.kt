// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True while the app-lock cover is up (the lock is on or its state is unread). Windows and menus are not shown then,
 * and an open window is dismissed through its own dismiss callback, so none reopens when the cover goes.
 */
val LocalAppLockCovered = staticCompositionLocalOf { false }

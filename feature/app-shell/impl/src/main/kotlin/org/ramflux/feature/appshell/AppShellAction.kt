// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell

import org.ramflux.data.Appearance
import org.ramflux.data.ClientFeature

sealed interface AppShellAction {
    data object Retry : AppShellAction
    data object DismissNotice : AppShellAction
    data class Open(val feature: ClientFeature) : AppShellAction
    data class ChangeAppearance(val value: Appearance) : AppShellAction
}

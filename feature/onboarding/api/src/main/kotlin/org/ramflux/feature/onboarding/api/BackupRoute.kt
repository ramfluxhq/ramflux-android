// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding.api
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Backup and restore (MOB-G09). [freshInstall] opens the restore-as-new-account path from onboarding,
 * with no account; otherwise the screen works on the active account from settings.
 */
@Serializable data class BackupRoute(val freshInstall: Boolean = false) : NavKey

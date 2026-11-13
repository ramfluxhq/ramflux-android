// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object ShellHome : NavKey
@Serializable data class FeatureRoute(val featureId: String) : NavKey

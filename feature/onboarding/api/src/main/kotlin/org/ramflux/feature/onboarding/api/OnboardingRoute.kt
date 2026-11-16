// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding.api
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
@Serializable data class OnboardingRoute(val openCreate: Boolean = false, val unlockId: String = "") : NavKey

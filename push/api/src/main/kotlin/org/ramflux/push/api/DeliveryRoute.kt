// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The message delivery page: reachable without an account, so it is not account scoped. */
@Serializable data object DeliveryRoute : NavKey

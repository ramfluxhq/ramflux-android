// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups.api
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.ramflux.navigation.AccountScoped

/** The details of one group opened from elsewhere (the group chat's details); back leaves the route. */
@Serializable data class GroupDetailRoute(val accountId: String, val groupId: String) : NavKey, AccountScoped

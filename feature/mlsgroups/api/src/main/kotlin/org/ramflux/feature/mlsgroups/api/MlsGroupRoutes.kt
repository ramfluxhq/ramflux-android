// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.ramflux.navigation.AccountScoped

// W: register these routes in ShellEntries when the groups module goes live; until then they are reachable from the gallery only.

/** The MLS groups list. */
@Serializable data object MlsGroupsRoute : NavKey

/** The info page of one MLS group. */
@Serializable data class MlsGroupInfoRoute(val accountId: String, val groupId: String) : NavKey, AccountScoped

/** The member list of one MLS group. */
@Serializable data class MlsGroupMembersRoute(val accountId: String, val groupId: String) : NavKey, AccountScoped

/** The join requests of one approval group (owner or admin). */
@Serializable data class MlsGroupRequestsRoute(val accountId: String, val groupId: String) : NavKey, AccountScoped

/** The settings (name and approval) of one MLS group (owner or admin). */
@Serializable data class MlsGroupSettingsRoute(val accountId: String, val groupId: String) : NavKey, AccountScoped

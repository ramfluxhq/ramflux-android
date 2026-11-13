// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
sealed interface GroupsAction {
    data object Refresh : GroupsAction
    data object Back : GroupsAction
    data object SetupAccount : GroupsAction
    data class Visible(val value: Boolean) : GroupsAction
    data class Page(val page: GroupsPage, val id: String? = null) : GroupsAction
    data class Query(val value: String) : GroupsAction
    data class Name(val value: String) : GroupsAction
    data object Create : GroupsAction
    data object Rename : GroupsAction
    data class Add(val id: String) : GroupsAction
    data class Role(val id: String, val admin: Boolean) : GroupsAction
    data class Remove(val id: String) : GroupsAction
    data class Invitation(val accept: Boolean) : GroupsAction
    data object OpenChat : GroupsAction
    data object Unavailable : GroupsAction
    data object Confirm : GroupsAction
    data object Deny : GroupsAction
}

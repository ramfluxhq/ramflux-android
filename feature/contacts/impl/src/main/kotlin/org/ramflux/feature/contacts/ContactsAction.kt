// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import org.ramflux.data.ContactOperation
sealed interface ContactsAction {
    data object Confirm : ContactsAction
    data object Deny : ContactsAction
    data object Refresh : ContactsAction
    data object Back : ContactsAction
    data object SetupAccount : ContactsAction
    data class Tab(val tab: ContactsTab) : ContactsAction
    data class Query(val text: String) : ContactsAction
    data class Address(val text: String) : ContactsAction
    data class Select(val id: String) : ContactsAction
    data class Toggle(val id: String) : ContactsAction
    data class Page(val page: ContactsPage) : ContactsAction
    data class Operate(val operation: ContactOperation) : ContactsAction
}

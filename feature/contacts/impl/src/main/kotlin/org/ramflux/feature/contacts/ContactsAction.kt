// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import org.ramflux.data.ContactOperation
sealed interface ContactsAction {
    data object Confirm : ContactsAction
    data object Deny : ContactsAction
    data object Refresh : ContactsAction
    data object Back : ContactsAction

    /** The end of the window is on screen: read the next page. */
    data object LoadMore : ContactsAction

    /** The start of the window is on screen and rows before it were dropped: read the previous page. */
    data object LoadPrevious : ContactsAction

    /** The list is on screen (subscribe to changes) or not (cancel the subscription). */
    data class Visible(val visible: Boolean) : ContactsAction
    data object SetupAccount : ContactsAction
    data class Tab(val tab: ContactsTab) : ContactsAction
    data class Query(val text: String) : ContactsAction
    data class Address(val text: String) : ContactsAction
    data class Select(val id: String) : ContactsAction
    data class Toggle(val id: String) : ContactsAction
    data class Page(val page: ContactsPage) : ContactsAction
    data class Operate(val operation: ContactOperation) : ContactsAction

    /** "Review" on a changed identity: opens the identity change sheet. */
    data object ReviewIdentity : ContactsAction

    /** The explicit "Accept new identity" tap of the sheet. */
    data object AcceptIdentity : ContactsAction

    /** "Not now" in the sheet. */
    data object LaterIdentity : ContactsAction

    /** Opens the direct conversation with the selected contact. */
    data object Message : ContactsAction
}

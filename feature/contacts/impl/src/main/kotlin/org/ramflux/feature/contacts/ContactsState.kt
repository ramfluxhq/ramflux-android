// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.data.ContactOperation
import org.ramflux.designsystem.UiText

enum class ContactsTab { CONTACTS, STRANGERS, SENT, BLOCKED }
enum class ContactsPage { LIST, DETAIL, ADD, SAFETY, DECLINE, BATCH }

/** One row: a contact link ([request] false; [id] is the link id) or a contact request ([id] is the request id). */
@Immutable data class PersonItem(
    val id: String,
    val principal: String,
    val label: String,
    val status: UiText,
    val request: Boolean,
    val inbound: Boolean = false,
    val note: String? = null,
    val count: UiText? = null,
    val expires: UiText? = null,
    val root: String? = null,
    val blocked: Boolean = false,
    val verified: Boolean = false,
    /** SDK-verified AI agent: shown with the AI marker, otherwise exactly like a person. */
    val ai: Boolean = false
) {
    override fun toString() = "PersonItem(<redacted>)"
}

/** [verifiable]: the SDK offered a stamp for this number, so "Numbers match" can be recorded. */
@Immutable data class SafetyItem(
    val number: String,
    val fingerprint: String,
    val state: UiText,
    val verified: Boolean,
    val verifiable: Boolean
)

@Immutable data class ContactApproval(
    val account: String,
    val operation: UiText,
    val targets: ImmutableList<String>,
    val explanation: UiText? = null,
    val kind: ContactOperation? = null
)

@Immutable data class ContactsState(
    val page: ContactsPage = ContactsPage.LIST,
    val tab: ContactsTab = ContactsTab.CONTACTS,
    val accountLabel: String? = null,
    val rows: ImmutableList<PersonItem> = persistentListOf(),
    val hasMore: Boolean = false,
    val hasBefore: Boolean = false,
    val loadingMore: Boolean = false,
    val selected: PersonItem? = null,
    val selectedIds: ImmutableSet<String> = persistentSetOf(),
    val query: String = "",
    val address: String = "",
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val accountRequired: Boolean = false,
    val problem: UiText? = null,
    val unavailable: UiText? = null,
    val approval: ContactApproval? = null,
    val safety: SafetyItem? = null,
    val operationNotice: UiText? = null,
    val uncertain: ImmutableList<UiText> = persistentListOf()
) {
    override fun toString() = "ContactsState(<redacted>)"
}

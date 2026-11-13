// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.designsystem.UiText

enum class ContactsTab { CONTACTS, STRANGERS, SENT }
enum class ContactsPage { LIST, DETAIL, ADD, SAFETY, DECLINE, BATCH }

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
    val root: String? = null
) {
    override fun toString() = "PersonItem(<redacted>)"
}

@Immutable data class SafetyItem(val number: String, val fingerprint: String)

@Immutable data class ContactApproval(
    val account: String,
    val operation: UiText,
    val targets: ImmutableList<String>,
    val held: Boolean = false
)

@Immutable data class ContactsState(
    val page: ContactsPage = ContactsPage.LIST,
    val tab: ContactsTab = ContactsTab.CONTACTS,
    val accountLabel: String? = null,
    val rows: ImmutableList<PersonItem> = persistentListOf(),
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

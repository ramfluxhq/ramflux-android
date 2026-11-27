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

/** The answer for an `@name` on the add page: what the directory published, or why it published nothing. */
@Immutable sealed interface UsernameLookup {
    /** [query] is the trimmed text that was looked up (for example `@bob`); it is what the found name answers for. */
    data class Found(val query: String, val name: String, val displayName: String?, val bio: String?) : UsernameLookup
    data class Failed(val message: UiText) : UsernameLookup
}

/** True while the typed text is an `@name` that the last lookup has not found: the add button looks it up first. */
internal fun ContactsState.usernameNeedsLookup(): Boolean {
    val typed = address.trim()
    if (!typed.startsWith(USERNAME_PREFIX)) return false
    val found = username as? UsernameLookup.Found
    return found == null || found.query != typed
}

/** The lookup answer to show now: a found name only while the field still holds the text that was looked up. */
internal fun ContactsState.visibleUsername(): UsernameLookup? = when (val lookup = username) {
    is UsernameLookup.Found -> lookup.takeIf { it.query == address.trim() }
    is UsernameLookup.Failed, null -> lookup
}

internal const val USERNAME_PREFIX = '@'

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
    val uncertain: ImmutableList<UiText> = persistentListOf(),
    /** The last `@name` lookup on the add page; a request to a name is sent only after it was found. */
    val username: UsernameLookup? = null
) {
    override fun toString() = "ContactsState(<redacted>)"
}

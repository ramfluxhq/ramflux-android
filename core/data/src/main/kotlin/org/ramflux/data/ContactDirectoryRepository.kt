// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** [ai] is the SDK-verified AI marker: an agent is shown exactly like a person plus this marker. */
data class ContactSummary(val id: String, val principal: String, val name: String?, val verified: Boolean, val root: String? = null, val ai: Boolean = false) {
    override fun toString() = "ContactSummary(<redacted>)"
}
enum class RequestDirection { INBOUND, OUTBOUND }
enum class ContactRequestStatus { PENDING, ACCEPTED, DECLINED, BLOCKED, EXPIRED, UNKNOWN }
data class ContactRequestSummary(val id: String, val principal: String, val name: String?, val direction: RequestDirection, val status: ContactRequestStatus, val note: String?, val messageCount: ULong, val expiresAt: Long, val root: String? = null, val ai: Boolean = false) {
    override fun toString() = "ContactRequestSummary(<redacted>)"
}
/**
 * ACCEPT, DECLINE and BLOCK decide an inbound request (target: request id). REMOVE, BLOCK_CONTACT and UNBLOCK
 * act on a contact link (target: link id). VERIFY marks a safety number verified (target: identity root) with the
 * stamp read by the last [ContactDirectoryRepository.safety] for that root.
 */
enum class ContactOperation { ADD, ACCEPT, DECLINE, BLOCK, REPORT, REPLY, VERIFY, SCAN, BATCH_DECLINE, REMOVE, BLOCK_CONTACT, UNBLOCK }

/** Which contact links a page lists. */
enum class ContactListScope { ACCEPTED, BLOCKED }

/** Which requests a page lists: pending inbound (the strangers area) or every outbound request (sent). */
enum class ContactRequestScope { STRANGERS, SENT }

/** One live change from the shared event subscription: reload the loaded window of that kind. */
enum class ContactChange { CONTACTS, REQUESTS, SAFETY, RELOAD }
interface ContactDirectoryRepository {
    /** Unpaged; for small pickers only. Lists read [contactPage]. */
    suspend fun contacts(account: String): ClientResult<List<ContactSummary>>
    suspend fun requests(account: String): ClientResult<List<ContactRequestSummary>>
    suspend fun safety(account: String, root: String): ClientResult<ContactSafety>

    /**
     * A window page of contacts filtered by [query] (name or principal, case-insensitive). A null cursor starts a
     * new read; a page's cursors continue it in either direction.
     */
    suspend fun contactPage(account: String, scope: ContactListScope, query: String = "", cursor: String? = null): ClientResult<WindowedPage<ContactSummary>> =
        ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)

    suspend fun requestPage(account: String, scope: ContactRequestScope, query: String = "", cursor: String? = null): ClientResult<WindowedPage<ContactRequestSummary>> =
        ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)

    /** The direct conversation with the contact whose identity root is [root] (see [DirectConversations]). */
    suspend fun conversation(account: String, root: String): ClientResult<String?> =
        ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)

    /**
     * This account's own signed contact card (`contact.card.export`): [OwnContactCard.text] for pasting, and
     * [OwnContactCard.link] for a QR code. It names the home node, so a peer that adds the phone by this card
     * pins that node. Public values only.
     */
    suspend fun ownCard(account: String): ClientResult<OwnContactCard> =
        ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)

    /** Live changes for [account]; collect while a contacts screen is visible. */
    fun changes(account: String): Flow<ClientResult<ContactChange>> = emptyFlow()
    suspend fun submit(command: ContactCommand, confirmed: Boolean): ClientResult<Unit>
    fun attempts(account: String): List<ContactAttempt>
    /** Reports SDK support; does not execute or grant consent. */
    suspend fun availability(operation: ContactOperation): ClientResult<Unit>
}

data class ContactCommand(val account: String, val operationId: String, val operation: ContactOperation, val target: String, val principal: String) {
    override fun toString() = "ContactCommand(<redacted>)"
}
enum class ContactAttemptPhase { CONFIRMED, REFUSED, FAILED, UNKNOWN }
data class ContactAttempt(val operationId: String, val operation: ContactOperation, val target: String, val phase: ContactAttemptPhase) {
    override fun toString() = "ContactAttempt(<redacted>)"
}

enum class SafetyState { UNVERIFIED, VERIFIED, CHANGED, BLOCKED_BY_POLICY, UNKNOWN }

/** [verifiable] is true when the SDK offered a compare-and-set stamp, so VERIFY can be submitted. */
/** A signed contact card: `ramflux-card:v3:...` text and the `ramflux://contact/v3/...` link (public). */
data class OwnContactCard(val text: String, val link: String) {
    override fun toString() = "OwnContactCard(<redacted>)"
}

data class ContactSafety(val number: String, val fingerprint: String, val state: SafetyState = SafetyState.UNVERIFIED, val verifiable: Boolean = false) {
    override fun toString() = "ContactSafety(<redacted>)"
}

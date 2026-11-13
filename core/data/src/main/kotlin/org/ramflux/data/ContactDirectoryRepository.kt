// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

data class ContactSummary(val id: String, val principal: String, val name: String?, val verified: Boolean, val root: String? = null) {
    override fun toString() = "ContactSummary(<redacted>)"
}
enum class RequestDirection { INBOUND, OUTBOUND }
enum class ContactRequestStatus { PENDING, ACCEPTED, DECLINED, BLOCKED, EXPIRED, UNKNOWN }
data class ContactRequestSummary(val id: String, val principal: String, val name: String?, val direction: RequestDirection, val status: ContactRequestStatus, val note: String?, val messageCount: ULong, val expiresAt: Long, val root: String? = null) {
    override fun toString() = "ContactRequestSummary(<redacted>)"
}
enum class ContactOperation { ADD, ACCEPT, DECLINE, BLOCK, REPORT, REPLY, VERIFY, SCAN, BATCH_DECLINE }
interface ContactDirectoryRepository {
    suspend fun contacts(account: String): ClientResult<List<ContactSummary>>
    suspend fun requests(account: String): ClientResult<List<ContactRequestSummary>>
    suspend fun safety(account: String, root: String): ClientResult<ContactSafety>
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

data class ContactSafety(val number: String, val fingerprint: String) {
    override fun toString() = "ContactSafety(<redacted>)"
}

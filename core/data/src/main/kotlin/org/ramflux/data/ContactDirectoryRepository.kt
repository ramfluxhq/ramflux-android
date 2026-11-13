// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

data class ContactSummary(val id: String, val principal: String, val name: String?, val verified: Boolean) {
    override fun toString() = "ContactSummary(<redacted>)"
}
enum class RequestDirection { INBOUND, OUTBOUND }
enum class ContactRequestStatus { PENDING, ACCEPTED, DECLINED, BLOCKED, EXPIRED, UNKNOWN }
data class ContactRequestSummary(val id: String, val principal: String, val name: String?, val direction: RequestDirection, val status: ContactRequestStatus, val note: String?, val messageCount: ULong, val expiresAt: Long) {
    override fun toString() = "ContactRequestSummary(<redacted>)"
}
enum class ContactOperation { ADD, ACCEPT, DECLINE, BLOCK, REPORT, REPLY, VERIFY, SCAN, BATCH_DECLINE }
interface ContactDirectoryRepository {
    suspend fun contacts(account: String): ClientResult<List<ContactSummary>>
    suspend fun requests(account: String): ClientResult<List<ContactRequestSummary>>
    /** Read-only preflight. No mutation or success is fabricated while host approval is not installed. */
    suspend fun availability(operation: ContactOperation): ClientResult<Unit>
}

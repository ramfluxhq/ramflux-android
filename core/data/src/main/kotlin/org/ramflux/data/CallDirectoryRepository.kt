// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.flow.Flow

enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }
enum class CallPhase { INCOMING_RINGING, OUTGOING_RINGING, CONNECTING, ACTIVE, ENDED, UNKNOWN }
data class CallRecord(val id: String, val conversation: String, val peer: String, val direction: CallDirection, val phase: CallPhase,
    val sequence: ULong, val mediaGeneration: ULong, val created: Long, val answered: Long?, val ended: Long?,
    val endReason: String?, val failure: String?, val peerReason: String?) {
    val canDecline get() = direction == CallDirection.INCOMING && phase == CallPhase.INCOMING_RINGING
    val canHangup get() = phase in setOf(CallPhase.INCOMING_RINGING, CallPhase.OUTGOING_RINGING, CallPhase.CONNECTING, CallPhase.ACTIVE)
    override fun toString() = "CallRecord(<redacted>)"
}
data class CallSignal(val operationId: String, val expected: CallRecord, val decline: Boolean)
interface CallDirectoryRepository {
    suspend fun list(account: String): ClientResult<List<CallRecord>>
    suspend fun signal(account: String, attempt: CallSignal): ClientResult<CallRecord>
    fun pending(account: String): CallSignal?
    fun updates(account: String): Flow<ClientResult<Unit>>
    suspend fun audioAvailability(): ClientResult<Unit>
}

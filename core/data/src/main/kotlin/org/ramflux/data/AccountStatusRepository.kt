// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

enum class SecureSessions { READY, REESTABLISHING, UNKNOWN }
enum class SyncPhase { RUNNING, IDLE, ERROR, NOT_RECORDED, UNKNOWN }
enum class NodeHealth { CONNECTED, UNAVAILABLE, NOT_RECORDED, UNKNOWN }
data class AccountStatus(val sessions: SecureSessions, val sync: SyncPhase, val node: NodeHealth, val lastRun: Long?)
interface AccountStatusRepository {
    suspend fun read(account: String): ClientResult<AccountStatus>
    suspend fun backupAvailability(): ClientResult<Unit>
}

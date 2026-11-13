// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

data class DeviceSummary(val id: String, val local: Boolean, val epoch: ULong)
interface DeviceDirectoryRepository {
    suspend fun list(account: String): ClientResult<List<DeviceSummary>>
    suspend fun revoke(account: String, device: String): ClientResult<Unit>
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/** Public identity facts; confirmation always carries the exact reviewed key. */
data class SavedNode(val id: String, val url: String, val publicKey: String, val isDefault: Boolean, val keyChanged: Boolean, val enabled: Boolean)
data class DiscoveredNode(val node: SavedNode, val fingerprint: String)
interface NodeSetupRepository {
    suspend fun list(): ClientResult<List<SavedNode>>
    suspend fun discover(address: String): ClientResult<DiscoveredNode>
    suspend fun confirm(candidate: DiscoveredNode): ClientResult<Unit>
    suspend fun select(id: String): ClientResult<Unit>
}

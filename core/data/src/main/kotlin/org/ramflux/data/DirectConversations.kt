// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.digests.Blake3Digest
import org.ramflux.sdk.ConversationDirectoryMetadataReader
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.GroupMetadataReader

/**
 * The SDK's ordinary direct conversation id of two root anchors (`ramflux-protocol` `message/dm_scope.rs`):
 * `"dm:" + base64url(BLAKE3(lp(DOMAIN) || lp(lower anchor) || lp(higher anchor)))`, where `lp` is a 4-byte
 * big-endian length prefix and the anchors are ordered by their encoded ASCII bytes. The id is public and grants
 * no authority; the SDK still checks the route when a message is sent.
 */
object DirectConversationId {
    const val DOMAIN = "ramflux.message.dm_conversation.v1"
    const val PREFIX = "dm:"

    /** The direct conversation of [first] and [second], or null when either is not a canonical anchor or both are equal. */
    fun derive(first: String, second: String): String? {
        if (!anchor(first) || !anchor(second) || first == second) return null
        val (low, high) = if (first < second) first to second else second to first
        val digest = Blake3Digest()
        for (part in listOf(DOMAIN, low, high)) {
            val bytes = part.toByteArray(Charsets.US_ASCII)
            val length = byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte())
            digest.update(length, 0, length.size)
            digest.update(bytes, 0, bytes.size)
        }
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out)
    }

    /** A canonical root anchor: 43 ASCII characters of unpadded base64url that decode to 32 bytes and re-encode unchanged. */
    fun anchor(value: String): Boolean {
        if (value.length != 43 || value.any { it.code > 127 }) return false
        val bytes = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value
    }
}

/**
 * Addresses the direct conversation of the open account with a peer or an agent by root anchor. The account's
 * own root comes from `account.status` (`principal_commitment`) and the id is derived ([DirectConversationId]);
 * only when that root cannot be read does it fall back to finding the directory row whose direct route names
 * the peer as the recipient commitment. Agents, contacts and anything else that opens a conversation by anchor
 * go through here.
 */
class DirectConversations(transport: RepositoryTransport) {
    private val accounts = DefaultAccountsRepository(transport)
    private val conversations = DefaultConversationsRepository(transport)

    /** The conversation id, or null when there is none (malformed anchor, own root, no directory row). */
    suspend fun resolve(account: String, peer: String): ClientResult<String?> {
        if (account.isEmpty() || account.length > 256 || peer.isEmpty() || peer.length > 128) return invalid()
        val root = when (val status = accounts.accountStatus(RepositoryContext(account), buildJsonObject {})) {
            is CoreResult.Failure -> null
            is CoreResult.Success -> when (val read = GroupMetadataReader.authority(status.value)) {
                is CoreResult.Failure -> null
                is CoreResult.Success -> read.value.root.takeIf { read.value.account == account }
            }
        }
        if (root != null && DirectConversationId.anchor(root)) {
            return ClientResult.Ready(DirectConversationId.derive(root, peer))
        }
        return directoryRow(account, peer)
    }

    /** The fallback: the visible direct row whose route names [peer], following the directory's cursor. */
    private suspend fun directoryRow(account: String, peer: String): ClientResult<String?> {
        var cursor: String? = null
        val seen = HashSet<String>()
        do {
            val body = buildJsonObject {
                put("limit", DIRECTORY_PAGE)
                cursor?.let { put("cursor", it) }
            }
            val page = when (val reply = conversations.conversationDirectory(RepositoryContext(account), body)) {
                is CoreResult.Failure -> return ClientResult.Failed(reply.error.appProblem())
                is CoreResult.Success -> when (val decoded = ConversationDirectoryMetadataReader.read(reply.value)) {
                    is CoreResult.Failure -> return ClientResult.Failed(decoded.error.appProblem())
                    is CoreResult.Success -> decoded.value
                }
            }
            page.rows.firstOrNull { !it.hidden && it.kind != "group" && it.peer == peer }?.let { return ClientResult.Ready(it.id) }
            cursor = page.nextCursor?.takeIf { it.isNotEmpty() && it.length <= 8192 && seen.add(it) }
        } while (cursor != null)
        return ClientResult.Ready(null)
    }

    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))

    private companion object {
        const val DIRECTORY_PAGE = 100
    }
}

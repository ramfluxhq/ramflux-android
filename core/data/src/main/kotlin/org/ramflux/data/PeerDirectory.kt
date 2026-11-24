// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A person as the contact list knows them: display name and the SDK-verified AI marker. */
data class Peer(val name: String?, val ai: Boolean) {
    override fun toString() = "Peer(<redacted>)"
}

/**
 * The people of the contact list keyed by root commitment and principal id, for places that show a person the SDK row
 * names only by key: the AI marker of a direct conversation row and its header, group members and the authors of
 * group messages (whose rows carry the author's root commitment and device). One contact read serves a whole page;
 * the map is reused for [freshForMillis] and read again earlier only when a page asks for a peer the map does not
 * know (a new contact) and the last read is older than [missRetryMillis]. The map is built off the caller's thread.
 */
internal class PeerDirectory(
    private val contacts: ContactDirectoryRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val freshForMillis: Long = 60_000,
    private val missRetryMillis: Long = 5_000
) {
    private class Entry(val peers: Map<String, Peer>, val readAt: Long)

    private val gate = Mutex()
    private val cache = HashMap<String, Entry>()

    suspend fun peers(account: String, wanted: Set<String>): Map<String, Peer> {
        if (wanted.isEmpty()) return emptyMap()
        return gate.withLock {
            val current = now()
            val entry = cache[account]
            if (entry != null) {
                val age = current - entry.readAt
                val missing = !entry.peers.keys.containsAll(wanted)
                if (age < freshForMillis && (!missing || age < missRetryMillis)) return@withLock entry.peers
            }
            when (val listed = contacts.contacts(account)) {
                is ClientResult.Ready -> {
                    val peers = withContext(Dispatchers.Default) {
                        HashMap<String, Peer>().apply {
                            for (row in listed.value) {
                                val peer = Peer(row.name?.takeIf { it.isNotBlank() }, row.ai)
                                put(row.principal, peer)
                                row.root?.takeIf { it.isNotBlank() }?.let { put(it, peer) }
                            }
                        }
                    }
                    // Only the active account's map is kept.
                    cache.clear()
                    cache[account] = Entry(peers, current)
                    peers
                }
                else -> entry?.peers ?: emptyMap()
            }
        }
    }

    companion object {
        /** The peer keys of a direct row: the route's root commitment, then the participants. */
        fun peers(peer: String?, participants: List<String>): List<String> =
            buildList {
                peer?.takeIf { it.isNotBlank() }?.let(::add)
                participants.asSequence().take(MAX_PARTICIPANTS).filter { it.isNotBlank() }.forEach(::add)
            }

        /**
         * A short form of an identifier for a direct row with no known name: the first characters of the peer's
         * root commitment, or of the conversation hash without its `dm:` prefix. Never the raw `dm:` id. The
         * screen shows it through the left-to-right identifier primitive.
         */
        fun shortIdentifier(peer: String?, conversationId: String): String {
            val body = peer?.takeIf { it.isNotBlank() } ?: conversationId.removePrefix("dm:")
            return if (body.length > SHORT_LENGTH + 2) body.take(SHORT_LENGTH) + "…" else body
        }

        private const val MAX_PARTICIPANTS = 8
        private const val SHORT_LENGTH = 10
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.ramflux.data.RepositoryTransport
import org.ramflux.sdk.CoreError
import org.ramflux.sdk.CoreRequest
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.CoreUpdate
import org.ramflux.sdk.SecretReply
import org.ramflux.sdk.SecretRequest
import org.ramflux.sdk.SubscriptionFilter

class CardSessionPoolTest {
    /** A bus that answers the card calls the pool makes. [suspendFails] makes suspend fail as it does once the account closed. */
    private class FakeBus(var suspendFails: Boolean = false, var resumeFails: CoreError? = null) : RepositoryTransport {
        var opens = 0
        val suspends = ArrayList<Long>()
        private var next = 1L

        override suspend fun execute(request: CoreRequest): CoreResult<JsonObject> {
            val session = (request.body["session"] as? JsonPrimitive)?.longOrNull ?: 0L
            return when (request.method) {
                "card.session.open" -> {
                    opens++
                    CoreResult.Success(reply("opened", next++))
                }
                "card.session.resume" -> resumeFails?.let { CoreResult.Failure(it) } ?: CoreResult.Success(reply("resumed", session))
                "card.session.suspend" -> {
                    suspends += session
                    if (suspendFails) CoreResult.Failure(CoreError.Closed())
                    else CoreResult.Success(JsonObject(mapOf("result" to JsonPrimitive("suspended"), "render_allowed" to JsonPrimitive(true))))
                }
                "card.session.dispose" -> CoreResult.Success(JsonObject(mapOf("result" to JsonPrimitive("disposed"))))
                else -> CoreResult.Failure(CoreError.Closed())
            }
        }

        override suspend fun executeSecret(request: SecretRequest): CoreResult<SecretReply> = CoreResult.Failure(CoreError.Closed())
        override fun updates(filter: SubscriptionFilter): Flow<CoreUpdate> = emptyFlow()
    }

    @Test fun peekingACardDoesNotMakeItMostRecentlyUsed() = runTest {
        val bus = FakeBus()
        val pool = CardSessionPool(bus, scope = this)
        pool.show(ACCOUNT, "a")
        pool.show(ACCOUNT, "b")
        // Showing a card is a use: a is now more recent than b.
        pool.show(ACCOUNT, "a")
        // Reading the held states, as the timeline does for every card, must not count as a use.
        repeat(3) { pool.state(ACCOUNT, "b") }
        pool.show(ACCOUNT, "c")
        assertEquals(listOf(2L), bus.suspends)
        assertEquals(false, pool.state(ACCOUNT, "b")?.resumed)
        assertEquals(true, pool.state(ACCOUNT, "a")?.resumed)
    }

    @Test fun aFailedSuspendKeepsTheHeldTreeAndReopensOnForeground() = runTest {
        val bus = FakeBus()
        val pool = CardSessionPool(bus, scope = this)
        pool.show(ACCOUNT, "a")
        bus.suspendFails = true
        pool.background()
        val held = pool.state(ACCOUNT, "a")
        assertNotNull(held?.tree)
        assertEquals(false, held?.resumed)
        assertEquals(CardLifecycle.SUSPENDED, held?.lifecycle)
        bus.suspendFails = false
        pool.foreground()
        assertEquals(2, bus.opens)
        assertEquals(true, pool.state(ACCOUNT, "a")?.resumed)
    }

    @Test fun aTransientResumeFailureKeepsTheSessionAndTheDraft() = runTest {
        val bus = FakeBus()
        val pool = CardSessionPool(bus, scope = this)
        pool.show(ACCOUNT, "a")
        pool.background()
        bus.resumeFails = CoreError.Closed()
        pool.foreground()
        assertEquals(1, bus.opens)
        assertNotNull(pool.state(ACCOUNT, "a")?.tree)
        bus.resumeFails = null
        pool.show(ACCOUNT, "a")
        assertEquals(1, bus.opens)
        assertEquals(true, pool.state(ACCOUNT, "a")?.resumed)
    }

    @Test fun reopeningAtTheMaximumDoesNotEvictAnUnrelatedCard() = runTest {
        val bus = FakeBus()
        val pool = CardSessionPool(bus, scope = this)
        repeat(CardSessionPool.MAX_OPEN) { pool.show(ACCOUNT, "c$it") }
        // The last card loses its runtime handle on a failed suspend; showing it re-opens it in place at the maximum.
        bus.suspendFails = true
        pool.background()
        bus.suspendFails = false
        val last = "c${CardSessionPool.MAX_OPEN - 1}"
        pool.show(ACCOUNT, last)
        assertEquals(CardSessionPool.MAX_OPEN + 1, bus.opens)
        assertNotNull(pool.state(ACCOUNT, "c0"))
        assertEquals(true, pool.state(ACCOUNT, last)?.resumed)
    }

    private companion object {
        const val ACCOUNT = "acct"

        fun reply(result: String, session: Long) = buildJsonObject {
            put("result", result)
            put("session", session)
            put("render_allowed", true)
            put("fallback_text", "fallback")
            put("batch", batch(session))
        }

        /** A one-node tree: a root upsert at version 1, applied to the empty tree. */
        fun batch(session: Long) = buildJsonObject {
            put("schema", CardTree.SCHEMA)
            put("session", session)
            put("previous_version", 0)
            put("version", 1)
            put("signed_version", 1)
            put("signed_hash", "hash")
            put("edit_seq", 0)
            put("mutations", buildJsonArray {
                add(buildJsonObject {
                    put("op", "upsert")
                    put("parent", JsonNull)
                    put("index", 0)
                    put("node", buildJsonObject {
                        put("key", "root")
                        put("component_id", "card")
                        put("kind", "Column")
                        put("scope", "card")
                        put("parent_key", JsonNull)
                        put("source_instance", JsonArray(emptyList()))
                        put("attrs", JsonObject(emptyMap()))
                    })
                })
            })
            put("host_slots", JsonArray(emptyList()))
            put("locale", "en")
            put("format_locale", "en-US")
            put("direction", "ltr")
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ramflux.data.ClientResult
import org.ramflux.data.RepositoryTransport
import org.ramflux.sdk.CoreError
import org.ramflux.sdk.CoreRequest
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.CoreUpdate
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretBytes
import org.ramflux.sdk.SecretReply
import org.ramflux.sdk.SecretRequest
import org.ramflux.sdk.SubscriptionFilter

/** Input events that queue behind each other on one card, and the lock of an account that signs out. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardSessionPoolInputTest {
    /**
     * A runtime in miniature: one card with a text field and a checkbox. Every event commits a new tree version, and an
     * event is stale unless its token names the current edit sequence and tree version (as the core's `token_matches`).
     */
    private class Runtime(private val replyDelay: Long = 0) : RepositoryTransport {
        var session = 0L
        var version = 0L
        var editSeq = 0L
        var text = ""
        var checked = false
        var pointer = "/tf"
        val inputs = ArrayList<String>()
        val activations = ArrayList<String>()
        val log = ArrayList<String>()
        var secretCalls = 0
        var opens = 0
        var openGate: CompletableDeferred<Unit>? = null

        private fun node(key: String, kind: String, parent: String?, pointer: String?, value: JsonElement) = buildJsonObject {
            put("key", key)
            put("component_id", key)
            put("kind", kind)
            put("scope", "card")
            put("parent_key", parent?.let { JsonPrimitive(it) } ?: JsonNull)
            put("source_instance", JsonArray(emptyList()))
            put("attrs", buildJsonObject {
                put("value", value)
                pointer?.let { put("input_pointer", it) }
            })
        }

        /** The whole tree as one batch from [previous]; every node is an upsert. */
        fun batch(previous: Long): JsonObject {
            version = if (previous == 0L) 1 else version + 1
            return buildJsonObject {
                put("schema", CardTree.SCHEMA)
                put("session", session)
                put("previous_version", previous)
                put("version", version)
                put("signed_version", 1)
                put("signed_hash", "hash")
                put("edit_seq", editSeq)
                put("mutations", buildJsonArray {
                    fun upsert(parent: String?, index: Int, node: JsonObject) = add(buildJsonObject {
                        put("op", "upsert")
                        put("parent", parent?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("index", index)
                        put("node", node)
                    })
                    upsert(null, 0, node("root", "Column", null, null, JsonNull))
                    upsert("root", 0, node("tf", "TextField", "root", pointer, JsonPrimitive(text)))
                    upsert("root", 1, node("cb", "CheckBox", "root", "/cb", JsonPrimitive(checked)))
                    upsert("root", 2, node("btn", "Button", "root", null, JsonNull))
                    upsert("root", 3, node("sw", "Button", "root", null, JsonNull))
                })
                put("host_slots", JsonArray(emptyList()))
                put("locale", "en")
                put("format_locale", "en-US")
                put("direction", "ltr")
            }
        }

        private fun reply(result: String, batch: JsonObject?) = buildJsonObject {
            put("result", result)
            put("session", session)
            put("render_allowed", true)
            put("fallback_text", "fallback")
            batch?.let { put("batch", it) }
        }

        override suspend fun execute(request: CoreRequest): CoreResult<JsonObject> = when (request.method) {
            "card.session.open" -> {
                opens++
                openGate?.await()
                session++
                CoreResult.Success(reply("opened", batch(0)))
            }
            "card.session.present", "card.session.update" -> CoreResult.Success(reply("updated", batch(version)))
            "card.session.dispose" -> CoreResult.Success(buildJsonObject { put("result", "disposed") })
            else -> CoreResult.Failure(CoreError.Closed())
        }

        override suspend fun executeSecret(request: SecretRequest): CoreResult<SecretReply> {
            secretCalls++
            if (replyDelay > 0) delay(replyDelay)
            val body = Json.parseToJsonElement(String(readBody(request.body))).jsonObject
            val token = body["token"]?.jsonObject ?: return CoreResult.Failure(CoreError.Closed())
            val event = body["event"]?.jsonObject ?: return CoreResult.Failure(CoreError.Closed())
            val fresh = token["editSeq"]?.let { (it as JsonPrimitive).longOrNull } == editSeq &&
                (token["treeVersion"] as? JsonPrimitive)?.longOrNull == version
            if (!fresh) return CoreResult.Success(secret(reply("stale_dropped", null)))
            val value = event["value"] as? JsonPrimitive
            val kind = (event["kind"] as? JsonPrimitive)?.contentOrNull
            val key = (token["key"] as? JsonPrimitive)?.contentOrNull
            log += "$kind:$key"
            if (kind == "activate") {
                when (key) {
                    "btn" -> activations += text
                    "sw" -> pointer = if (pointer == "/tf") "/tf2" else "/tf"
                }
            } else {
                when (key) {
                    "tf" -> {
                        text = value?.contentOrNull.orEmpty()
                        inputs += text
                    }
                    "cb" -> checked = value?.booleanOrNull == true
                }
            }
            editSeq++
            return CoreResult.Success(secret(reply("edited", batch(version))))
        }

        override fun updates(filter: SubscriptionFilter): Flow<CoreUpdate> = emptyFlow()

        private fun readBody(body: SecretBody): ByteArray {
            val field = SecretBody::class.java.getDeclaredField("storage").apply { isAccessible = true }
            return (field.get(body) as SecretBytes).useOnce { it.copyOf() }
        }

        private fun secret(row: JsonObject): SecretReply {
            val constructor = SecretReply::class.java.getDeclaredConstructor(SecretBytes::class.java).apply { isAccessible = true }
            return constructor.newInstance(SecretBytes.take(row.toString().toByteArray()))
        }
    }

    private fun CardSessionPool.tree(ref: String = "a"): CardTree = checkNotNull(state(ACCOUNT, ref)?.tree)

    private fun CardTree.text(key: String) = (nodes[key]?.attrs?.get("value") as? JsonPrimitive)?.contentOrNull
    private fun CardTree.flag(key: String) = (nodes[key]?.attrs?.get("value") as? JsonPrimitive)?.booleanOrNull

    @Test fun fastTypingEndsWithTheWholeValueInTheRuntime() = runTest {
        val runtime = Runtime(replyDelay = 10)
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val seen = pool.tree().stamp
        // Each keystroke is its own coroutine and every one carries the stamp of the tree the person saw before typing.
        val results = ArrayList<ClientResult<CardReply>>()
        for (typed in listOf("a", "ab", "abc")) {
            launch(start = CoroutineStart.UNDISPATCHED) { results += pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive(typed)), seen) }
        }
        advanceUntilIdle()
        assertEquals("abc", runtime.text)
        assertEquals("abc", pool.tree().text("tf"))
        // The consecutive keystrokes at the head of the queue were replaced by the newest value.
        assertEquals(listOf("abc"), runtime.inputs)
        assertEquals(listOf("coalesced", "coalesced", "edited"), results.map { (it as ClientResult.Ready).value.result })
    }

    @Test fun aToggleAfterAnotherControlsEventIsApplied() = runTest {
        val runtime = Runtime(replyDelay = 10)
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val seen = pool.tree().stamp
        launch(start = CoroutineStart.UNDISPATCHED) { pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive("x")), seen) }
        launch(start = CoroutineStart.UNDISPATCHED) { pool.event(ACCOUNT, "a", "cb", CardEvent.Input(JsonPrimitive(true)), seen) }
        advanceUntilIdle()
        assertEquals("x", runtime.text)
        assertTrue(runtime.checked)
        assertEquals(true, pool.tree().flag("cb"))
    }

    @Test fun anOutsideUpdateEndsTheChainAndAStaleInputRevertsVisibly() = runTest {
        val runtime = Runtime()
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val seen = pool.tree().stamp
        pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive("x")), seen)
        // A theme change commits a tree the person has not seen: the earlier stamps are no longer ours to rebase.
        pool.present(ACCOUNT, "fr", true)
        val calls = runtime.secretCalls
        val result = pool.event(ACCOUNT, "a", "cb", CardEvent.Input(JsonPrimitive(true)), seen)
        assertEquals("stale_dropped", (result as ClientResult.Ready).value.result)
        assertEquals(calls, runtime.secretCalls)
        assertEquals(false, runtime.checked)
    }

    @Test fun anActivateFromAnOlderTreeKeepsTheStrictCheck() = runTest {
        val runtime = Runtime()
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val seen = pool.tree().stamp
        // A tab-like step the person did not see: the tap on the old tree is not re-sent.
        pool.event(ACCOUNT, "a", "sw", CardEvent.Activate, seen)
        val calls = runtime.secretCalls
        val result = pool.event(ACCOUNT, "a", "btn", CardEvent.Activate, seen)
        assertEquals("stale_dropped", (result as ClientResult.Ready).value.result)
        assertEquals(calls, runtime.secretCalls)
        assertTrue(runtime.activations.isEmpty())
    }

    @Test fun anInputActivateInputSequenceIsAppliedInTheOrderTheyWereMade() = runTest {
        val runtime = Runtime(replyDelay = 10)
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val seen = pool.tree().stamp
        val results = ArrayList<String>()
        fun send(event: CardEvent, key: String) = launch(start = CoroutineStart.UNDISPATCHED) {
            results += (pool.event(ACCOUNT, "a", key, event, seen) as ClientResult.Ready).value.result
        }
        send(CardEvent.Input(JsonPrimitive("x")), "tf")
        send(CardEvent.Activate, "btn")
        send(CardEvent.Input(JsonPrimitive("y")), "tf")
        advanceUntilIdle()
        assertEquals(listOf("input:tf", "activate:btn", "input:tf"), runtime.log)
        // The tap carries the value typed before it, not the one typed after it.
        assertEquals(listOf("x"), runtime.activations)
        assertEquals("y", runtime.text)
        assertEquals(listOf("edited", "edited", "edited"), results)
    }

    @Test fun anActivateAfterQueuedInputsIsAppliedWithTheLatestValue() = runTest {
        val runtime = Runtime(replyDelay = 10)
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val seen = pool.tree().stamp
        var tap: ClientResult<CardReply>? = null
        launch(start = CoroutineStart.UNDISPATCHED) { pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive("a")), seen) }
        launch(start = CoroutineStart.UNDISPATCHED) { pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive("ab")), seen) }
        launch(start = CoroutineStart.UNDISPATCHED) { tap = pool.event(ACCOUNT, "a", "btn", CardEvent.Activate, seen) }
        advanceUntilIdle()
        assertEquals(listOf("ab"), runtime.activations)
        assertEquals("edited", (tap as ClientResult.Ready).value.result)
    }

    @Test fun anInputTypedAgainstAnotherPointerIsNotRebasedOntoTheCurrentOne() = runTest {
        val runtime = Runtime()
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val p = pool.tree().stamp
        // The field switches from pointer P to Q and back to P, each time on the tree the person saw.
        pool.event(ACCOUNT, "a", "sw", CardEvent.Activate, p)
        val q = pool.tree().stamp
        pool.event(ACCOUNT, "a", "sw", CardEvent.Activate, q)
        assertEquals("/tf", runtime.pointer)
        val calls = runtime.secretCalls
        val againstQ = pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive("typed on q")), q)
        assertEquals("stale_dropped", (againstQ as ClientResult.Ready).value.result)
        assertEquals(calls, runtime.secretCalls)
        assertEquals("", runtime.text)
        // Typed against the tree where the field was P, it still lands on P.
        val againstP = pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive("typed on p")), p)
        assertEquals("edited", (againstP as ClientResult.Ready).value.result)
        assertEquals("typed on p", runtime.text)
    }

    @Test fun showAfterSignOutCreatesNoEntryAndNoOpenCall() = runTest {
        val runtime = Runtime()
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        pool.forget(ACCOUNT)
        val late = pool.show(ACCOUNT, "b")
        assertTrue(late is ClientResult.Failed)
        assertEquals(1, runtime.opens)
        assertEquals(null, pool.state(ACCOUNT, "b"))
        // An account that never had an entry is refused too once it signed out.
        pool.forget("other")
        assertTrue(pool.show("other", "c") is ClientResult.Failed)
        assertEquals(1, runtime.opens)
        // An explicit sign-in lets cards open again.
        pool.admit(ACCOUNT)
        assertTrue(pool.show(ACCOUNT, "b") is ClientResult.Ready)
        assertEquals(2, runtime.opens)
    }

    @Test fun anInputForAControlThatLeftTheTreeIsDropped() = runTest {
        val runtime = Runtime()
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val seen = pool.tree().stamp
        pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive("x")), seen)
        val result = pool.event(ACCOUNT, "a", "gone", CardEvent.Input(JsonPrimitive("y")), seen)
        assertEquals("stale_dropped", (result as ClientResult.Ready).value.result)
    }

    @Test fun aCallThatWaitedOnASignedOutAccountDoesNotOpenASession() = runTest {
        val runtime = Runtime()
        val gate = CompletableDeferred<Unit>()
        runtime.openGate = gate
        val pool = CardSessionPool(runtime, scope = this)
        var late: ClientResult<Unit>? = null
        // a holds the lock inside open; forget and b queue behind it, in that order.
        launch(start = CoroutineStart.UNDISPATCHED) { pool.show(ACCOUNT, "a") }
        launch(start = CoroutineStart.UNDISPATCHED) { pool.forget(ACCOUNT) }
        launch(start = CoroutineStart.UNDISPATCHED) { late = pool.show(ACCOUNT, "b") }
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, runtime.opens)
        assertNotNull(late)
        assertTrue(late is ClientResult.Failed)
        assertEquals(null, pool.state(ACCOUNT, "a"))
        assertEquals(null, pool.state(ACCOUNT, "b"))
    }

    private companion object {
        const val ACCOUNT = "acct"
    }
}

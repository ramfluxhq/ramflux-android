// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ramflux.data.AccountScopedStores
import org.ramflux.data.ClientResult
import org.ramflux.data.DefaultAccountAccessRepository
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

/** Operations that queue behind each other on one card, and the lock and tombstone of an account that signs out. */
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

        /** When set, every secret call (event, slot, submit) waits for it, so later operations queue up behind. */
        var secretGate: CompletableDeferred<Unit>? = null

        /** A reopen starts the tree version and edit sequence again, as a fresh runtime session does. */
        var resetOnOpen = false

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
                if (resetOnOpen) editSeq = 0
                CoreResult.Success(reply("opened", batch(0)))
            }
            "card.session.present", "card.session.update" -> CoreResult.Success(reply("updated", batch(version)))
            "card.session.dispose" -> CoreResult.Success(buildJsonObject { put("result", "disposed") })
            else -> CoreResult.Failure(CoreError.Closed())
        }

        override suspend fun executeSecret(request: SecretRequest): CoreResult<SecretReply> {
            secretCalls++
            secretGate?.await()
            if (replyDelay > 0) delay(replyDelay)
            val body = Json.parseToJsonElement(String(readBody(request.body))).jsonObject
            if (request.method == "card.session.submit") {
                log += "submit"
                return CoreResult.Success(secret(reply("submitted", null)))
            }
            val token = body["token"]?.jsonObject ?: return CoreResult.Failure(CoreError.Closed())
            if (request.method == "card.session.slot") {
                log += "slot:" + (body["slot_id"] as? JsonPrimitive)?.contentOrNull
                editSeq++
                return CoreResult.Success(secret(reply("edited", batch(version))))
            }
            val event = body["event"]?.jsonObject ?: return CoreResult.Failure(CoreError.Closed())
            // The core's `token_matches`: session, signed version, edit sequence and tree version must all be current.
            fun long(name: String) = (token[name] as? JsonPrimitive)?.longOrNull
            val fresh = long("session") == session && long("signedVersion") == 1L && long("editSeq") == editSeq && long("treeVersion") == version
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

    @Test fun operationsMadeDuringADrainHandOffKeepOneConsumerAndThePersonsOrder() = runBlocking {
        val runtime = Runtime()
        val poolScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pool = CardSessionPool(runtime, scope = poolScope)
            assertTrue(pool.show(ACCOUNT, "a") is ClientResult.Ready)
            val results = ArrayList<Deferred<ClientResult<CardReply>>>()
            withTimeout(30_000) {
                for (round in 0 until HAND_OFF_ROUNDS) {
                    // Each call enters the queue before the next is made (undispatched), so this is the person's order.
                    results += async(start = CoroutineStart.UNDISPATCHED) {
                        pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive("v$round")), pool.tree().stamp)
                    }
                    results += async(start = CoroutineStart.UNDISPATCHED) {
                        pool.event(ACCOUNT, "a", "cb", CardEvent.Input(JsonPrimitive(round % 2 == 0)), pool.tree().stamp)
                    }
                    // Waiting for the answer lands the next call right when the consumer finds its queue empty.
                    if (round % 3 == 0) results.last().await()
                }
                results.awaitAll()
            }
            assertEquals(List(HAND_OFF_ROUNDS * 2) { "edited" }, results.map { (it.getCompleted() as ClientResult.Ready).value.result })
            assertEquals(List(HAND_OFF_ROUNDS) { "v$it" }, runtime.inputs)
            assertEquals(List(HAND_OFF_ROUNDS) { listOf("input:tf", "input:cb") }.flatten(), runtime.log)
        } finally {
            poolScope.cancel()
        }
    }

    @Test fun twoHundredFastKeystrokesEndWithTheFinalValueAndTheNextTapIsApplied() = runTest {
        val runtime = Runtime(replyDelay = 10)
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val results = ArrayList<String>()
        for (i in 1..200) {
            val seen = pool.tree().stamp
            launch(start = CoroutineStart.UNDISPATCHED) {
                results += (pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive("v$i")), seen) as ClientResult.Ready).value.result
            }
            if (i % 7 == 0) delay(3)
        }
        var tap: ClientResult<CardReply>? = null
        val seen = pool.tree().stamp
        launch(start = CoroutineStart.UNDISPATCHED) { tap = pool.event(ACCOUNT, "a", "btn", CardEvent.Activate, seen) }
        advanceUntilIdle()
        assertEquals("v200", runtime.text)
        assertEquals("v200", pool.tree().text("tf"))
        assertEquals(listOf("v200"), runtime.activations)
        assertEquals("edited", (tap as ClientResult.Ready).value.result)
        assertEquals(200, results.size)
        assertTrue(results.none { it == "stale_dropped" })
    }

    @Test fun aTapThatFindsTheQueueFullIsAnsweredAsDropped() = runTest {
        val runtime = Runtime()
        val gate = CompletableDeferred<Unit>()
        runtime.secretGate = gate
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val seen = pool.tree().stamp
        launch(start = CoroutineStart.UNDISPATCHED) { pool.event(ACCOUNT, "a", "btn", CardEvent.Activate, seen) }
        runCurrent()
        // Taps never replace each other: these fill the queue behind the one held at the runtime.
        repeat(64) { i ->
            launch(start = CoroutineStart.UNDISPATCHED) { pool.event(ACCOUNT, "a", if (i % 2 == 0) "sw" else "btn", CardEvent.Activate, seen) }
        }
        val overflow = pool.event(ACCOUNT, "a", "btn", CardEvent.Activate, seen)
        assertEquals("stale_dropped", (overflow as ClientResult.Ready).value.result)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test fun tapsHeldByACancelledConsumerAreAnsweredAsDropped() = runTest {
        val runtime = Runtime()
        val gate = CompletableDeferred<Unit>()
        runtime.secretGate = gate
        val poolScope = this + Job()
        val pool = CardSessionPool(runtime, scope = poolScope)
        pool.show(ACCOUNT, "a")
        val seen = pool.tree().stamp
        var held: ClientResult<CardReply>? = null
        var waiting: ClientResult<CardReply>? = null
        launch(start = CoroutineStart.UNDISPATCHED) { held = pool.event(ACCOUNT, "a", "btn", CardEvent.Activate, seen) }
        runCurrent()
        launch(start = CoroutineStart.UNDISPATCHED) { waiting = pool.event(ACCOUNT, "a", "sw", CardEvent.Activate, seen) }
        poolScope.cancel()
        advanceUntilIdle()
        assertEquals("stale_dropped", (held as ClientResult.Ready).value.result)
        assertEquals("stale_dropped", (waiting as ClientResult.Ready).value.result)
        gate.complete(Unit)
    }

    @Test fun aSlotAnswerAndASubmitQueuedAfterInputsAreAppliedAfterThem() = runTest {
        val runtime = Runtime(replyDelay = 10)
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val seen = pool.tree().stamp
        val token = pool.tree().tokenFor("btn")
        launch(start = CoroutineStart.UNDISPATCHED) { pool.event(ACCOUNT, "a", "tf", CardEvent.Input(JsonPrimitive("x")), seen) }
        launch(start = CoroutineStart.UNDISPATCHED) { pool.event(ACCOUNT, "a", "cb", CardEvent.Input(JsonPrimitive(true)), seen) }
        launch(start = CoroutineStart.UNDISPATCHED) { pool.slot(ACCOUNT, "a", "s1", "r1", token, CardSlotOutcome.CONFIRM) }
        launch(start = CoroutineStart.UNDISPATCHED) { pool.submit(ACCOUNT, "a") }
        advanceUntilIdle()
        assertEquals(listOf("input:tf", "input:cb", "slot:s1", "submit"), runtime.log)
        assertEquals("x", runtime.text)
        assertTrue(runtime.checked)
    }

    @Test fun aStampFromAPreviousSessionNeverMatches() = runTest {
        val runtime = Runtime().apply { resetOnOpen = true }
        val pool = CardSessionPool(runtime, scope = this)
        pool.show(ACCOUNT, "a")
        val old = pool.tree().stamp
        pool.dispose(ACCOUNT, "a")
        pool.show(ACCOUNT, "a")
        val now = pool.tree().stamp
        // The reopened tree repeats the version and edit sequence; only the session tells them apart.
        assertEquals(old.treeVersion, now.treeVersion)
        assertEquals(old.editSeq, now.editSeq)
        assertNotEquals(old, now)
        val calls = runtime.secretCalls
        val result = pool.event(ACCOUNT, "a", "btn", CardEvent.Activate, old)
        assertEquals("stale_dropped", (result as ClientResult.Ready).value.result)
        assertEquals(calls, runtime.secretCalls)
    }

    /** `account.list` and `account.sign_out` as the core answers them: sign-out locks the account; a locked one is refused. */
    private class AccountsBus : RepositoryTransport {
        var state = "unlocked"
        var refuseSignOut = false

        override suspend fun execute(request: CoreRequest): CoreResult<JsonObject> = CoreResult.Failure(CoreError.Closed())

        override suspend fun executeSecret(request: SecretRequest): CoreResult<SecretReply> = when (request.method) {
            "account.list" -> CoreResult.Success(secret(buildJsonObject {
                put("account_details", buildJsonArray {
                    add(buildJsonObject {
                        put("id", ACCOUNT)
                        put("display_name", "Work")
                        put("state", state)
                    })
                })
                put("active_account_id", JsonNull)
            }))
            "account.sign_out" -> if (state != "unlocked" || refuseSignOut) {
                CoreResult.Failure(CoreError.Closed())
            } else {
                state = "locked"
                CoreResult.Success(secret(buildJsonObject { put("signed_out", true) }))
            }
            else -> CoreResult.Failure(CoreError.Closed())
        }

        override fun updates(filter: SubscriptionFilter): Flow<CoreUpdate> = emptyFlow()

        private fun secret(row: JsonObject): SecretReply {
            val constructor = SecretReply::class.java.getDeclaredConstructor(SecretBytes::class.java).apply { isAccessible = true }
            return constructor.newInstance(SecretBytes.take(row.toString().toByteArray()))
        }
    }

    private fun wired(runtime: Runtime, bus: AccountsBus, scope: CoroutineScope): Pair<CardSessionPool, DefaultAccountAccessRepository> {
        val stores = AccountScopedStores()
        val pool = CardSessionPool(runtime, scope = scope)
        stores.register { account -> pool.forget(account) }
        stores.onAdmit(pool::admit)
        return pool to DefaultAccountAccessRepository(bus, stores)
    }

    @Test fun aSecondSignOutKeepsTheSignedOutAccountClosed() = runTest {
        val runtime = Runtime()
        val (pool, access) = wired(runtime, AccountsBus(), this)
        assertTrue(pool.show(ACCOUNT, "a") is ClientResult.Ready)
        assertTrue(access.signOut(ACCOUNT) is ClientResult.Ready)
        // The account already left: the second sign-out fails and must not re-admit it.
        assertTrue(access.signOut(ACCOUNT) is ClientResult.Failed)
        assertTrue(pool.show(ACCOUNT, "b") is ClientResult.Failed)
        assertEquals(1, runtime.opens)
    }

    @Test fun twoConcurrentSignOutsKeepTheSignedOutAccountClosed() = runTest {
        val runtime = Runtime()
        val (pool, access) = wired(runtime, AccountsBus(), this)
        assertTrue(pool.show(ACCOUNT, "a") is ClientResult.Ready)
        launch(start = CoroutineStart.UNDISPATCHED) { access.signOut(ACCOUNT) }
        launch(start = CoroutineStart.UNDISPATCHED) { access.signOut(ACCOUNT) }
        advanceUntilIdle()
        assertTrue(pool.show(ACCOUNT, "b") is ClientResult.Failed)
        assertEquals(1, runtime.opens)
    }

    @Test fun aSignOutTheCoreRefusedLeavesTheOpenAccountsCardsUsable() = runTest {
        val runtime = Runtime()
        val bus = AccountsBus().apply { refuseSignOut = true }
        val (pool, access) = wired(runtime, bus, this)
        assertTrue(pool.show(ACCOUNT, "a") is ClientResult.Ready)
        assertTrue(access.signOut(ACCOUNT) is ClientResult.Failed)
        // Still listed and unlocked: the account stays on this device, so its cards open again.
        assertTrue(pool.show(ACCOUNT, "b") is ClientResult.Ready)
    }

    private companion object {
        const val ACCOUNT = "acct"
        const val HAND_OFF_ROUNDS = 300
    }
}

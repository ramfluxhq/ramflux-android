// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class ByteJsonTest {
    private fun refused(bytes: ByteArray) = try {
        ByteJson(bytes).parse()
        false
    } catch (_: IllegalArgumentException) { true }

    @Test fun theValueCeilingFollowsTheFrameCeilingNotARowBudget() {
        assertEquals(1 shl 20, ByteJson.MAX_BYTES)
        assertEquals((ByteJson.MAX_BYTES + 1) / 2, ByteJson.MAX_VALUES)
        // The densest possible frame, `[0,0,...,0]` filling the 1 MiB, holds exactly MAX_VALUES values and is accepted.
        val count = (ByteJson.MAX_BYTES - 1) / 2
        val dense = ("[" + "0,".repeat(count - 1) + "0]").toByteArray()
        assertEquals(ByteJson.MAX_BYTES - 1, dense.size)
        val root = ByteJson(dense).parse()
        assertEquals(count, root.children.size)
        assertEquals(ByteJson.MAX_VALUES, count + 1)
        assertTrue(root.children.all { it.children.isEmpty() })
    }

    @Test fun frameAboveTheCeilingIsRefusedBeforeParsing() {
        val oversized = ByteArray(ByteJson.MAX_BYTES + 1) { ' '.code.toByte() }.also {
            it[0] = '{'.code.toByte(); it[1] = '}'.code.toByte()
        }
        assertTrue(refused(oversized))
    }

    @Test fun nestingBeyondTheDepthLimitIsRefused() {
        fun nested(depth: Int) = ("[".repeat(depth) + "]".repeat(depth)).toByteArray()
        assertFalse(refused(nested(ByteJson.MAX_DEPTH + 1)))
        assertTrue(refused(nested(ByteJson.MAX_DEPTH + 2)))
        assertTrue(refused(("{\"a\":".repeat(40) + "null" + "}".repeat(40)).toByteArray()))
        // A deep but in-frame attack never reaches the recursion limit of the thread.
        assertTrue(refused(("[".repeat(500_000) + "]".repeat(500_000)).toByteArray()))
    }

    @Test fun pathologicalInputsAreRefused() {
        val bad = listOf(
            "", "   ", "[", "]", "{", "{\"a\"}", "{\"a\":}", "{1:2}", "[1,]", "[,1]", "[1 2]", "\"open",
            "\"bad \\x escape\"", "\"\\u12G4\"", "tru", "nul", "falsey", "1x", "{}{}", "[]]",
            "\"control \u0001 byte\"",
        )
        for (text in bad) assertTrue(text, refused(text.toByteArray()))
        // A long unterminated string spanning the whole frame is a single bounded scan.
        val unterminated = ByteArray(ByteJson.MAX_BYTES) { 'a'.code.toByte() }.also { it[0] = '"'.code.toByte() }
        assertTrue(refused(unterminated))
    }

    @Test fun listRepliesDecodeOffTheCallerAndAreWipedOnCancellation() = runBlocking {
        val row = """{"link_id":"%s","principal_id":"p","state":"accepted","blocked":false,"verified":false}"""
        val raw = ("{\"contacts\":[" + (0 until 3_000).joinToString(",") { row.format("link-$it") } + "]}").toByteArray()
        val decoded = ContactDirectoryMetadataReader.contacts(SecretReply(SecretBytes.take(raw)))
        assertEquals(3_000, (decoded as CoreResult.Success).value.size)
        assertTrue(raw.all { it == 0.toByte() })

        val again = ("{\"contacts\":[" + (0 until 3_000).joinToString(",") { row.format("link-$it") } + "]}").toByteArray()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Started on the test's own loop: the decoder itself moves the work to its dispatcher.
        val job = async {
            ListReplyDecoder.decode(SecretReply(SecretBytes.take(again))) { fields, chunks ->
                val rows = fields.rows("contacts", 10_000)
                entered.complete(Unit)
                runBlocking { release.await() }
                assertFalse(Thread.currentThread().name.startsWith("Test worker"))
                chunks.map(rows) { it.start }
            }
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        try {
            job.await()
            fail("a cancelled decode must not complete")
        } catch (_: CancellationException) { }
        assertTrue(again.all { it == 0.toByte() })
    }
}

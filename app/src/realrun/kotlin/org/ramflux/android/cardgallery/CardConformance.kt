// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardNode
import org.ramflux.data.cards.CardTree
import org.ramflux.feature.cards.renderer.CARD_RENDERED_KINDS

/** One line of the conformance report: a check of one fixture. */
internal class ConformanceRecord(
    val id: String,
    val kind: String,
    val variant: String?,
    val check: String,
    val pass: Boolean,
    val detail: String
) {
    fun json(): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("variant", variant?.let(::JsonPrimitive) ?: JsonNull)
        put("check", check)
        put("pass", pass)
        put("detail", detail)
    }
}

/**
 * Conformance of the client's card model against the standalone runtime, for every fixture row (realrun gallery only).
 * A: the opened tree equals the core's expected tree (client copy and a fresh runtime read), and refused rows refuse.
 * B: every expected node kind is one the renderer draws (no placeholder) and every host slot kind has a draw path.
 * C: for each interactive kind, the event the conformance table sends is answered, the returned batch applies to the
 *    held tree, and the held tree then equals a fresh runtime read (self-consistency).
 */
internal class CardConformance(private val context: Context, private val trust: CardTrust, private val rows: List<FixtureRow>) {

    suspend fun run(): List<ConformanceRecord> {
        val records = mutableListOf<ConformanceRecord>()
        for (row in rows) records += checkRow(row)
        return records
    }

    private suspend fun checkRow(row: FixtureRow): List<ConformanceRecord> {
        val spec = CardFixtureCatalog.hostBytes(context, row.fixture)
        if (row.refused) {
            val refused = CardFixtureRuntime.open(trust, spec, LOCALE, false)
            if (refused.session != 0L) CardFixtureRuntime.dispose(refused.session)
            val pass = !refused.ok && refused.json == null
            return listOf(record(row, CHECK_A, pass, "refused by the runtime: status ${refused.status}"))
        }
        val opened = CardFixtureRuntime.open(trust, spec, LOCALE, false)
        val batch = opened.json
        if (!opened.ok || batch == null) return listOf(record(row, CHECK_A, false, "open status ${opened.status}"))
        val live = opened.session
        try {
            val held = CardTree.EMPTY.apply(batch)
                ?: return listOf(record(row, CHECK_A, false, "the open batch does not apply to an empty tree"))
            val expected = CardFixtureCatalog.hostJson(context, row.expected.orEmpty())
                ?: return listOf(record(row, CHECK_A, false, "expected tree missing: ${row.expected}"))
            val want = CardTreeJson.normal(expected)
            val heldJson = CardTreeJson.normal(CardTreeJson.rebase(CardTreeJson.of(held), live))
            val fresh = CardFixtureRuntime.tree(live)
            val freshJson = fresh.json?.let { CardTreeJson.normal(CardTreeJson.rebase(it, live)) }
            val heldEqual = heldJson == want
            val freshEqual = freshJson == want
            val records = mutableListOf(
                record(
                    row,
                    CHECK_A,
                    heldEqual && freshEqual,
                    "held tree equal: $heldEqual; runtime tree equal: $freshEqual" +
                        if (heldEqual && freshEqual) "" else "; first differing key: ${firstDifference(heldJson, want)}"
                ),
                checkB(row, expected)
            )
            if (row.kind in CardFixtureCatalog.INTERACTIVE) records += checkC(row, live, held)
            return records
        } finally {
            CardFixtureRuntime.dispose(live)
        }
    }

    private fun checkB(row: FixtureRow, expected: JsonObject): ConformanceRecord {
        val nodes = (expected["nodes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("kind") }
        val placeholders = nodes.filter { it !in CARD_RENDERED_KINDS }
        val slots = (expected["host_slots"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("kind") }
        val drawn = setOf("approval", "modal", "image", "link")
        val undrawn = slots.filter { it !in drawn }
        val pass = nodes.isNotEmpty() && placeholders.isEmpty() && undrawn.isEmpty()
        return record(
            row,
            CHECK_B,
            pass,
            "nodes ${nodes.size}; placeholders ${placeholders.size}; host slots ${slots.joinToString().ifEmpty { "none" }}"
        )
    }

    private suspend fun checkC(row: FixtureRow, live: Long, held: CardTree): ConformanceRecord {
        val node = held.nodes.values.firstOrNull { it.kind == row.kind }
            ?: return record(row, CHECK_C, false, "no ${row.kind} node in the opened tree")
        val event = eventFor(node)
        if (event == null) {
            // Approval answers are card.session.slot calls (SDK only); the standalone runtime has no slot path.
            val fresh = CardFixtureRuntime.tree(live).json?.let { CardTreeJson.normal(CardTreeJson.rebase(it, live)) }
            val heldEqual = fresh != null && CardTreeJson.normal(CardTreeJson.rebase(CardTreeJson.of(held), live)) == fresh
            return record(row, CHECK_C, heldEqual, "no standalone event: slot answers are SDK-only; runtime tree equal: $heldEqual")
        }
        val input = buildJsonObject {
            put("token", held.tokenFor(node.key))
            put("event", event.json())
        }
        val reply = CardFixtureRuntime.event(live, input)
        if (!reply.ok) return record(row, CHECK_C, false, "event status ${reply.status} for ${event.json()}")
        val answer = reply.json
        val result = (answer?.get("result") as? JsonPrimitive)?.content ?: "none"
        val batch = answer?.get("batch") as? JsonObject
        val next = if (batch == null) {
            held
        } else {
            held.apply(batch)
                ?: return record(row, CHECK_C, false, "batch from $result does not apply to the held tree")
        }
        val fresh = CardFixtureRuntime.tree(live).json?.let { CardTreeJson.normal(CardTreeJson.rebase(it, live)) }
        val equal = fresh != null && CardTreeJson.normal(CardTreeJson.rebase(CardTreeJson.of(next), live)) == fresh
        val action = answer?.get("action")?.toString()?.take(160) ?: "none"
        val digest = batch?.let { digest(it.toString()) } ?: "none"
        return record(
            row,
            CHECK_C,
            equal,
            "event ${event.json()}; result $result; batch $digest; action $action; runtime tree equal: $equal"
        )
    }

    /** The event the conformance table sends for [node]; null for kinds answered through a host slot instead. */
    private fun eventFor(node: CardNode): CardEvent? = when (node.kind) {
        "TextField" -> CardEvent.Input(JsonPrimitive("conformance"))
        "CheckBox" -> CardEvent.Input(JsonPrimitive(!((node.attrs["value"] as? JsonPrimitive)?.booleanOrNull ?: false)))
        "ChoicePicker" -> CardEvent.Input(JsonArray(listOfNotNull(firstOption(node))))
        "Slider" -> {
            val min = (node.attrs["min"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            val max = (node.attrs["max"] as? JsonPrimitive)?.doubleOrNull ?: 100.0
            CardEvent.Input(JsonPrimitive(min + (max - min) / 2))
        }
        "DateTimeInput" -> CardEvent.Input(
            JsonPrimitive(if ((node.attrs["enableDate"] as? JsonPrimitive)?.booleanOrNull == false) "09:30" else "2026-11-23")
        )
        "Button" -> CardEvent.Activate
        "Tabs" -> CardEvent.SelectTab(1)
        "Modal" -> CardEvent.ModalOpen
        else -> null
    }

    private fun firstOption(node: CardNode): JsonElement? =
        ((node.attrs["options"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("value")

    private fun record(row: FixtureRow, check: String, pass: Boolean, detail: String) =
        ConformanceRecord(row.id, row.kind, row.variant, check, pass, detail)

    private fun firstDifference(left: JsonObject, right: JsonObject): String =
        (left.keys + right.keys).sorted().firstOrNull { left[it] != right[it] } ?: "none"

    companion object {
        const val LOCALE = "en"
        const val CHECK_A = "A"
        const val CHECK_B = "B"
        const val CHECK_C = "C"
        const val REPORT = "card-conformance.json"
        private const val TAG = "RfCardConformance"

        /** Writes the report (a JSON array, one object per record) to the app's cache directory and logs each line. */
        fun report(context: Context, records: List<ConformanceRecord>): File {
            val lines = records.map { it.json() }
            val file = File(context.cacheDir, REPORT)
            file.writeText(JsonArray(lines).toString())
            lines.forEach { Log.i(TAG, it.toString()) }
            return file
        }

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

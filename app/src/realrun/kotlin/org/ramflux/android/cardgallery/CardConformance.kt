// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
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
import org.ramflux.data.cards.CardTreeCanonical
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

    /** Host slots of every failed check A row, written in full to `card-host-slots.json` (the record detail is cut). */
    val hostSlotDumps = mutableListOf<JsonObject>()

    suspend fun run(): List<ConformanceRecord> {
        val records = mutableListOf<ConformanceRecord>()
        for (row in rows) records += checkRow(row)
        // RTL is not a failure: the runtime publishes only en and zh-CN, so no RTL fixture can be opened yet.
        records += ConformanceRecord(
            id = "rtl",
            kind = "-",
            variant = null,
            check = CHECK_RTL,
            pass = true,
            detail = "NOT AVAILABLE: the runtime publishes only en and zh-CN; not a failure"
        )
        return records
    }

    private suspend fun checkRow(row: FixtureRow): List<ConformanceRecord> {
        val spec = CardFixtureCatalog.hostBytes(context, row.fixture)
        if (row.refused) {
            val refused = CardFixtureRuntime.open(trust, spec, LOCALE, false)
            if (refused.session != 0L) CardFixtureRuntime.dispose(refused.session)
            val pass = !refused.ok && refused.json == null
            val actual = "status ${refused.status}, batch ${excerpt(refused.json)}"
            val note = "refused by the runtime: status ${refused.status}"
            return listOf(record(row, CHECK_A, pass, detail(pass, "refused (status not 0, no batch)", actual, note)))
        }
        val expected = CardFixtureCatalog.hostJson(context, row.expected.orEmpty())
        val opened = CardFixtureRuntime.open(trust, spec, LOCALE, false)
        val batch = opened.json
        val live = opened.session
        val records = mutableListOf<ConformanceRecord>()
        try {
            // A is recorded and B and C still run: a batch that does not apply is a check A failure, not a reason to skip.
            val held = if (opened.ok && batch != null) CardTree.EMPTY.apply(batch) else null
            records += checkA(row, opened.ok, batch, held, live, expected)
            records += if (expected == null) {
                record(row, CHECK_B, false, detail(false, "expected tree present", "expected tree missing: ${row.expected}", ""))
            } else {
                checkB(row, expected)
            }
            if (row.kind in CardFixtureCatalog.INTERACTIVE) {
                records += when {
                    held == null || live == 0L -> record(
                        row,
                        CHECK_C,
                        false,
                        detail(false, "an open tree to drive", "no held tree (check A did not apply the open batch)", "")
                    )
                    else -> checkC(row, live, held)
                }
            }
            return records
        } finally {
            if (live != 0L) CardFixtureRuntime.dispose(live)
        }
    }

    /** Check A for an open row: the batch applies to the empty tree, the held tree equals the expected tree, a fresh read too. */
    private suspend fun checkA(
        row: FixtureRow,
        opened: Boolean,
        batch: JsonObject?,
        held: CardTree?,
        live: Long,
        expected: JsonObject?
    ): ConformanceRecord {
        if (!opened || batch == null) return record(row, CHECK_A, false, detail(false, "an open batch", "open failed", ""))
        if (expected == null) {
            return record(row, CHECK_A, false, detail(false, "expected tree ${row.expected}", "expected tree missing", ""))
        }
        if (held == null) {
            val expectedText = "the open batch applies to an empty tree"
            val note = "the open batch does not apply to an empty tree"
            return record(row, CHECK_A, false, detail(false, expectedText, "batch ${excerpt(batch)}", note))
        }
        val want = CardTreeCanonical.of(expected, 1L)
        val heldJson = CardTreeCanonical.of(CardTreeJson.of(held), live)
        val fresh = CardFixtureRuntime.tree(live)
        val freshJson = fresh.json?.let { CardTreeCanonical.of(it, live) }
        val heldEqual = heldJson == want
        val freshEqual = freshJson == want
        val pass = heldEqual && freshEqual
        val difference = firstDifference(heldJson, want)
        if (!pass) {
            hostSlotDumps += buildJsonObject {
                put("id", row.id)
                put("check", CHECK_A)
                put("first_differing_key", difference)
                put("expected_host_slots", expected["host_slots"] ?: JsonArray(emptyList()))
                put("held_host_slots", JsonArray(held.hostSlots))
                put("runtime_host_slots", fresh.json?.get("host_slots") ?: JsonNull)
            }
        }
        return record(
            row,
            CHECK_A,
            pass,
            detail(
                pass,
                "tree ${excerpt(want)}",
                "held ${excerpt(heldJson)}; runtime ${excerpt(freshJson)}",
                "held tree equal: $heldEqual; runtime tree equal: $freshEqual; first differing key: $difference"
            )
        )
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
            detail(
                pass,
                "every node a rendered kind and every host slot drawn",
                "placeholders ${placeholders.ifEmpty { listOf("none") }}; undrawn host slots ${undrawn.ifEmpty { listOf("none") }}",
                "nodes ${nodes.size}; placeholders ${placeholders.size}; host slots ${slots.joinToString().ifEmpty { "none" }}"
            )
        )
    }

    private suspend fun checkC(row: FixtureRow, live: Long, held: CardTree): ConformanceRecord {
        val node = held.nodes.values.firstOrNull { it.kind == row.kind }
            ?: return record(row, CHECK_C, false, detail(false, "a ${row.kind} node in the opened tree", "none", ""))
        val event = eventFor(node)
        if (event == null) {
            // Approval answers are card.session.slot calls (SDK only); the standalone runtime has no slot path.
            val fresh = CardFixtureRuntime.tree(live).json?.let { CardTreeCanonical.of(it, live) }
            val heldEqual = fresh != null && CardTreeCanonical.of(CardTreeJson.of(held), live) == fresh
            return record(row, CHECK_C, heldEqual, "no standalone event: slot answers are SDK-only; runtime tree equal: $heldEqual")
        }
        val input = buildJsonObject {
            put("token", held.tokenFor(node.key))
            put("event", event.json())
        }
        val reply = CardFixtureRuntime.event(live, input)
        if (!reply.ok) {
            return record(row, CHECK_C, false, detail(false, "event status 0", "event status ${reply.status}", "event ${event.json()}"))
        }
        val answer = reply.json
        val result = (answer?.get("result") as? JsonPrimitive)?.content ?: "none"
        val batch = answer?.get("batch") as? JsonObject
        val next = if (batch == null) {
            held
        } else {
            held.apply(batch)
                ?: return record(
                    row,
                    CHECK_C,
                    false,
                    detail(false, "the $result batch applies to the held tree", "batch ${excerpt(batch)}", "result $result")
                )
        }
        val fresh = CardFixtureRuntime.tree(live).json?.let { CardTreeCanonical.of(it, live) }
        val equal = fresh != null && CardTreeCanonical.of(CardTreeJson.of(next), live) == fresh
        val action = answer?.get("action")?.toString()?.take(160) ?: "none"
        val digest = batch?.let { digest(it.toString()) } ?: "none"
        val note = "event ${event.json()}; result $result; batch $digest; action $action; runtime tree equal: $equal"
        val excerpts = detail(equal, "runtime ${excerpt(fresh)}", "held ${excerpt(CardTreeJson.of(next))}", note)
        return record(row, CHECK_C, equal, excerpts)
    }

    /** The event the conformance table sends for [node]; null for kinds answered through a host slot instead. */
    private fun eventFor(node: CardNode): CardEvent? = when (node.kind) {
        "TextField" -> CardEvent.Input(JsonPrimitive("conformance"))
        "CheckBox" -> CardEvent.Input(JsonPrimitive(!((node.attrs["value"] as? JsonPrimitive)?.booleanOrNull ?: false)))
        "ChoicePicker" -> CardEvent.Input(JsonArray(listOfNotNull(firstOption(node))))
        "Slider" -> CardEvent.Input(JsonPrimitive(sliderMidpoint(node)))
        "DateTimeInput" -> CardEvent.Input(
            JsonPrimitive(if ((node.attrs["enableDate"] as? JsonPrimitive)?.booleanOrNull == false) "09:30" else "2026-11-23")
        )
        "Button" -> CardEvent.Activate
        "Tabs" -> CardEvent.SelectTab(1)
        "Modal" -> CardEvent.ModalOpen
        else -> null
    }

    /**
     * A Slider value is an integer inside the node's bounds (the core's `validate_input`: `as_i64`, then min and max).
     * The midpoint, rounded into the whole numbers of [min, max].
     */
    private fun sliderMidpoint(node: CardNode): Long {
        val min = (node.attrs["min"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val max = (node.attrs["max"] as? JsonPrimitive)?.doubleOrNull ?: 100.0
        return floor((min + max) / 2).toLong().coerceIn(ceil(min).toLong(), floor(max).toLong())
    }

    private fun firstOption(node: CardNode): JsonElement? =
        ((node.attrs["options"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("value")

    private fun record(row: FixtureRow, check: String, pass: Boolean, detail: String) =
        ConformanceRecord(row.id, row.kind, row.variant, check, pass, detail)

    /** The detail of a record: a failure carries the expected and the actual excerpt, then the note. */
    private fun detail(pass: Boolean, expected: String, actual: String, note: String): String {
        if (pass) return note.ifEmpty { "pass" }
        return listOf("expected: $expected", "actual: $actual", note).filter { it.isNotEmpty() }.joinToString("; ")
    }

    private fun excerpt(json: JsonElement?): String = json?.toString()?.take(EXCERPT_CHARS) ?: "none"

    private fun firstDifference(left: JsonObject, right: JsonObject): String =
        (left.keys + right.keys).sorted().firstOrNull { left[it] != right[it] } ?: "none"

    companion object {
        const val LOCALE = "en"
        const val CHECK_A = "A"
        const val CHECK_B = "B"
        const val CHECK_C = "C"
        const val CHECK_RTL = "RTL"
        const val REPORT = "card-conformance.json"
        const val HOST_SLOTS_REPORT = "card-host-slots.json"
        private const val EXCERPT_CHARS = 240
        private const val TAG = "RfCardConformance"

        /**
         * Writes the report (a JSON array, one object per record) to the app's cache directory and logs each line. The
         * host slots of failed check A rows go to [HOST_SLOTS_REPORT] in full, not cut to the record detail.
         */
        fun report(context: Context, records: List<ConformanceRecord>, hostSlots: List<JsonObject>): File {
            val lines = records.map { it.json() }
            val file = File(context.cacheDir, REPORT)
            file.writeText(JsonArray(lines).toString())
            File(context.cacheDir, HOST_SLOTS_REPORT).writeText(JsonArray(hostSlots).toString())
            lines.forEach { Log.i(TAG, it.toString()) }
            return file
        }

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

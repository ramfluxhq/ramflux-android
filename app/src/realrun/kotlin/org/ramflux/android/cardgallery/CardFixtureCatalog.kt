// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One fixture of `card-fixtures/host-fixtures/index.json`. [expected] is null for refused rows. */
internal data class FixtureRow(
    val id: String,
    val kind: String,
    val variant: String?,
    val fixture: String,
    val expected: String?,
    val refused: Boolean
)

/** The scenario batches of `card-fixtures/fixtures/` that the gallery also renders (the envelope is `<name>.json`). */
internal data class ScenarioRow(val name: String, val envelope: String, val expected: String)

/** Reads the card fixtures packaged for the realrun build (assets `card-fixtures/`). */
internal object CardFixtureCatalog {
    const val ROOT = "card-fixtures"
    const val HOST = "host-fixtures"
    val SCENARIOS = listOf("approval", "chart", "form", "complete_form", "task")

    fun rows(context: Context): List<FixtureRow> {
        val index = json(context, "$ROOT/$HOST/index.json") ?: return emptyList()
        val cases = index["cases"] as? JsonArray ?: return emptyList()
        return cases.mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val kind = row.text("kind") ?: return@mapNotNull null
            val variant = row.text("variant")
            val refused = row.text("outcome") == "refused"
            FixtureRow(
                id = "$kind/${variant ?: "unset"}",
                kind = kind,
                variant = variant,
                fixture = row.text("fixture") ?: return@mapNotNull null,
                expected = if (refused) null else row.text("expected") ?: return@mapNotNull null,
                refused = refused
            )
        }
    }

    fun scenarios(): List<ScenarioRow> = SCENARIOS.map { name ->
        ScenarioRow(name, "fixtures/$name.json", "fixtures/$name.tree.json")
    }

    /** The asset bytes of an index row file, e.g. `text/caption.input.json`. */
    fun hostBytes(context: Context, path: String): ByteArray = context.assets.open("$ROOT/$HOST/$path").use { it.readBytes() }

    fun bytes(context: Context, path: String): ByteArray = context.assets.open("$ROOT/$path").use { it.readBytes() }

    /** The expected or input document of an index row, parsed (null when it is missing or not an object). */
    fun hostJson(context: Context, path: String): JsonObject? = json(context, "$ROOT/$HOST/$path")

    fun json(context: Context, assetPath: String): JsonObject? = runCatching {
        Json.parseToJsonElement(context.assets.open(assetPath).use { it.readBytes() }.decodeToString()) as? JsonObject
    }.getOrNull()

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The interactive kinds Check C drives. */
    val INTERACTIVE = setOf("TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput", "Button", "Tabs", "Modal", "Approval")
}

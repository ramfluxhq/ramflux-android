// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardState

/** The gallery's state and its calls, kept for the life of the gallery activity. Test build only. */
internal class CardGalleryModel(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cards: GalleryCards? = null
    private var trust: CardTrust? = null

    /** The fixture rows and scenarios, by id; the renderer draws [states]. */
    val rows: List<FixtureRow> = CardFixtureCatalog.rows(context)
    val scenarios: List<ScenarioRow> = CardFixtureCatalog.scenarios()
    val states = mutableStateMapOf<String, CardState>()

    var runtimeReady by mutableStateOf(false)
        private set
    var dark by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var summary by mutableStateOf("")
        private set

    init {
        scope.launch {
            val loaded = if (CardFixtureRuntime.available()) CardFixtureRuntime.trust().getOrNull() else null
            trust = loaded
            if (loaded != null) cards = GalleryCards(loaded)
            runtimeReady = loaded != null
        }
    }

    /** Opens the envelope at [assetPath] (relative to the fixture root) under [ref], once per gallery visit. */
    fun open(ref: String, assetPath: String, locale: String = CardConformance.LOCALE) {
        val pool = cards ?: return
        scope.launch {
            val spec = withContext(Dispatchers.IO) { CardFixtureCatalog.bytes(context, assetPath) }
            states[ref] = pool.open(ref, spec, locale, dark)
        }
    }

    fun show(ref: String) {
        val pool = cards ?: return
        scope.launch { states[ref] = pool.show(ref) }
    }

    fun event(ref: String, key: String, event: CardEvent) {
        val pool = cards ?: return
        scope.launch { states[ref] = pool.event(ref, key, event) }
    }

    /** Dark or light for every card; the renderer reads it through the theme. */
    fun applyDark(value: Boolean) {
        dark = value
        val pool = cards ?: return
        scope.launch { pool.present(CardConformance.LOCALE, value) }
    }

    /** Runs checks A to C over every row, writes the report and shows the count. */
    fun runConformance() {
        val pool = cards ?: return
        val material = trust ?: return
        if (running) return
        running = true
        scope.launch {
            pool.suspendAll()
            states.putAll(pool.snapshot())
            val conformance = CardConformance(context, material, rows)
            val records = withContext(Dispatchers.IO) { conformance.run() }
            val file = withContext(Dispatchers.IO) { CardConformance.report(context, records, conformance.hostSlotDumps) }
            val pass = records.count { it.pass }
            summary = "PASS $pass, FAIL ${records.size - pass} of ${records.size} checks; report ${file.name}"
            running = false
        }
    }

    /** Closes the gallery: every card is disposed and the scope stops. */
    fun close() {
        val pool = cards
        scope.launch {
            pool?.disposeAll()
            scope.cancel()
        }
    }
}

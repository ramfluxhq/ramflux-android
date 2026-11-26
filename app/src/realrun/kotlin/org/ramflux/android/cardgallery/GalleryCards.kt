// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardLifecycle
import org.ramflux.data.cards.CardState
import org.ramflux.data.cards.CardTree

/**
 * The gallery's sessions on the standalone runtime. The runtime holds at most two resident VMs per process, so the
 * gallery keeps at most [MAX_RESIDENT] cards resumed: opening or resuming one more suspends the least recently used
 * (its last tree stays drawn, read-only, and a tap resumes it, as the production pool does). One mutex orders the calls.
 */
internal class GalleryCards(private val trust: CardTrust) {
    private val lock = Mutex()
    private val slots = HashMap<String, Slot>()
    private var clock = 0L

    private class Slot(val ref: String, val fallback: String) {
        var session = 0L
        var resumed = false
        var tree: CardTree? = null
        var failure: Int? = null
        var used = 0L

        fun state(): CardState {
            val held = tree
            val lifecycle = when {
                failure != null || held == null -> CardLifecycle.UNAVAILABLE
                held.generating -> CardLifecycle.GENERATING
                !resumed -> CardLifecycle.SUSPENDED
                else -> CardLifecycle.READY
            }
            return CardState(
                ref = ref,
                lifecycle = lifecycle,
                fallback = fallback,
                renderAllowed = failure == null,
                tree = held,
                resumed = resumed,
                hostRequest = null,
                awaitingDecision = false,
                draftConflict = false
            )
        }
    }

    /** Opens the envelope [spec] under [ref] (once) or resumes it; returns what the renderer draws. */
    suspend fun open(ref: String, spec: ByteArray, locale: String, dark: Boolean): CardState = lock.withLock {
        val slot = slots.getOrPut(ref) { Slot(ref, fallbackOf(spec)) }
        if (slot.session == 0L && slot.failure == null) {
            makeRoom(ref)
            val call = CardFixtureRuntime.open(trust, spec, locale, dark)
            val batch = call.json
            if (call.ok && batch != null) {
                slot.session = call.session
                slot.resumed = true
                slot.tree = CardTree.EMPTY.apply(batch)
                if (slot.tree == null) {
                    slot.failure = STATUS_UNAPPLIED
                    CardFixtureRuntime.dispose(call.session)
                    slot.session = 0L
                }
            } else {
                slot.failure = call.status
            }
        } else if (slot.session != 0L && !slot.resumed) {
            resume(slot)
        }
        slot.used = ++clock
        slot.state()
    }

    /** First tap on a suspended card: resume it. */
    suspend fun show(ref: String): CardState = lock.withLock {
        val slot = slots[ref] ?: return@withLock CardState(ref, CardLifecycle.UNAVAILABLE, "", false, null, false, null, false, false)
        if (slot.session != 0L && !slot.resumed) resume(slot)
        slot.used = ++clock
        slot.state()
    }

    /** A host event for the control at [key]; the returned batch applies to the held tree. */
    suspend fun event(ref: String, key: String, event: CardEvent): CardState = lock.withLock {
        val slot = slots[ref] ?: return@withLock CardState(ref, CardLifecycle.UNAVAILABLE, "", false, null, false, null, false, false)
        val held = slot.tree
        if (held == null || !slot.resumed) return@withLock slot.state()
        val input = buildJsonObject {
            put("token", held.tokenFor(key))
            put("event", event.json())
        }
        val call = CardFixtureRuntime.event(slot.session, input)
        val batch = call.json?.get("batch") as? JsonObject
        if (call.ok && batch != null) slot.tree = held.apply(batch) ?: held
        slot.used = ++clock
        slot.state()
    }

    /** Switches locale and theme of every held card (no peer update); the cards opened later use the same values. */
    suspend fun present(locale: String, dark: Boolean) = lock.withLock {
        for (slot in slots.values) {
            if (slot.session == 0L || !slot.resumed) continue
            val call = CardFixtureRuntime.present(slot.session, trust, locale, dark)
            val batch = call.json
            if (call.ok && batch != null) slot.tree = slot.tree?.apply(batch) ?: slot.tree
        }
    }

    /** Suspends every resumed card so that a conformance run starts with no resident VM from the gallery. */
    suspend fun suspendAll() = lock.withLock {
        for (slot in slots.values.toList()) if (slot.resumed) suspendSlot(slot)
    }

    /** What the renderer draws for every card now (after a suspend or a resume). */
    suspend fun snapshot(): Map<String, CardState> = lock.withLock { slots.mapValues { (_, slot) -> slot.state() } }

    /** Disposes every session (the gallery is closed). */
    suspend fun disposeAll() = lock.withLock {
        for (slot in slots.values) {
            if (slot.session != 0L) CardFixtureRuntime.dispose(slot.session)
            slot.session = 0L
            slot.resumed = false
        }
        slots.clear()
    }

    private suspend fun resume(slot: Slot) {
        makeRoom(slot.ref)
        val call = CardFixtureRuntime.resume(slot.session)
        val batch = call.json
        if (call.ok && batch != null) {
            slot.resumed = true
            slot.tree = CardTree.EMPTY.apply(batch) ?: slot.tree
        }
    }

    /** Suspends least recently used cards until fewer than [MAX_RESIDENT] others are resumed. */
    private suspend fun makeRoom(except: String) {
        while (true) {
            val resident = slots.values.filter { it.resumed && it.ref != except }
            if (resident.size < MAX_RESIDENT) return
            val oldest = resident.minByOrNull { it.used } ?: return
            if (!suspendSlot(oldest)) return
        }
    }

    private suspend fun suspendSlot(slot: Slot): Boolean {
        val call = CardFixtureRuntime.suspendCard(slot.session)
        if (call.ok) slot.resumed = false
        return call.ok
    }

    private fun fallbackOf(spec: ByteArray): String = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(spec.decodeToString()) as? JsonObject
        ((root?.get("snapshot") as? JsonObject)?.get("fallbackText") as? JsonPrimitive)?.content.orEmpty()
    }.getOrDefault("")

    companion object {
        const val MAX_RESIDENT = 2
        const val STATUS_UNAPPLIED = -1
    }
}

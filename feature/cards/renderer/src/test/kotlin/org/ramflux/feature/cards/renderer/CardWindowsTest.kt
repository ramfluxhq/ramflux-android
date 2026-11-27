// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards.renderer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardLifecycle
import org.ramflux.data.cards.CardSlotOutcome
import org.ramflux.data.cards.CardStamp
import org.ramflux.data.cards.CardTree
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardWindowsTest {
    @get:Rule val compose = createComposeRule()

    private class Recorder : CardActions {
        val events = ArrayList<String>()
        val inputs = ArrayList<JsonElement>()
        override fun event(key: String, event: CardEvent, seen: CardStamp) {
            events += key + ":" + (event.json()["kind"] as? JsonPrimitive)?.content
            (event as? CardEvent.Input)?.let { inputs += it.value }
        }
        override fun slot(slotId: String, requestId: String, token: JsonObject, outcome: CardSlotOutcome, url: String?) = Unit
        override fun show() = Unit
        override fun resync() = Unit
    }

    private fun node(
        key: String,
        kind: String,
        parent: String?,
        index: Int = 0,
        attrs: JsonObject = JsonObject(
            emptyMap()
        )
    ) = buildJsonObject {
        put("op", "upsert")
        put("parent", parent?.let { JsonPrimitive(it) } ?: JsonNull)
        put("index", index)
        put(
            "node",
            buildJsonObject {
                put("key", key)
                put("component_id", key)
                put("kind", kind)
                put("scope", "card")
                put("parent_key", parent?.let { JsonPrimitive(it) } ?: JsonNull)
                put("source_instance", JsonArray(emptyList()))
                put("attrs", attrs)
            }
        )
    }

    private fun tree(vararg nodes: JsonObject): CardTree = requireNotNull(
        CardTree.EMPTY.apply(
            buildJsonObject {
                put("schema", CardTree.SCHEMA)
                put("session", 1)
                put("previous_version", 0)
                put("version", 1)
                put("signed_version", 1)
                put("signed_hash", "hash")
                put("edit_seq", 0)
                put("mutations", JsonArray(nodes.toList()))
                put("host_slots", JsonArray(emptyList()))
                put("locale", "en")
                put("format_locale", "en-US")
                put("direction", "ltr")
            }
        )
    )

    private fun input(tree: CardTree, hostRequest: JsonObject? = null, lifecycle: CardLifecycle = CardLifecycle.READY) = CardRenderInput(
        ref = "r",
        tree = tree,
        lifecycle = lifecycle,
        fallback = "",
        renderAllowed = true,
        hostRequest = hostRequest,
        awaitingDecision = false,
        draftConflict = false
    )

    private fun modalTree() = tree(
        node("root", "Column", null),
        node("modal", "Modal", "root"),
        node("trigger", "Text", "modal", 0, buildJsonObject { put("text", "Details") }),
        node("content", "Text", "modal", 1, buildJsonObject { put("text", "Sheet body") })
    )

    /** The tree while the runtime holds the modal closed: the content is not mounted. */
    private fun closedModalTree() = tree(
        node("root", "Column", null),
        node("modal", "Modal", "root"),
        node("trigger", "Text", "modal", 0, buildJsonObject { put("text", "Details") })
    )

    @Test fun theModalSheetCarriesTheAgentHeaderAndClosesUnderTheCover() {
        var covered by mutableStateOf(false)
        var runtimeOpen by mutableStateOf(false)
        val actions = Recorder()
        compose.setContent {
            RamfluxTheme {
                CompositionLocalProvider(LocalAppLockCovered provides covered) {
                    CardRenderer(input(if (runtimeOpen) modalTree() else closedModalTree()), actions, 360.dp, Modifier)
                }
            }
        }
        compose.onNodeWithText("Details").performClick()
        // The runtime answers modal_open with the tree that mounts the content.
        compose.runOnIdle { runtimeOpen = true }
        compose.waitForIdle()
        compose.onNodeWithTag("card-modal-sheet").assertExists()
        compose.onNodeWithTag("card-modal-header").assertExists()
        compose.onNodeWithText("From the agent").assertExists()
        compose.runOnIdle { covered = true }
        compose.waitForIdle()
        compose.onNodeWithTag("card-modal-sheet").assertDoesNotExist()
        // The sheet stays closed after the cover goes down: it must not reappear by itself. The ModalClose sent under the
        // cover did not reach the runtime (the tree still holds the content), so the card closes it again once usable.
        compose.runOnIdle { covered = false }
        compose.waitForIdle()
        compose.onNodeWithTag("card-modal-sheet").assertDoesNotExist()
        assertEquals(listOf("modal:modal_open", "modal:modal_close", "modal:modal_close"), actions.events)
        // Once the runtime agrees (content unmounted) nothing more is sent.
        compose.runOnIdle { runtimeOpen = false }
        compose.waitForIdle()
        assertEquals(3, actions.events.size)
    }

    @Test fun aSuspendedCardClosesItsModalSheet() {
        var lifecycle by mutableStateOf(CardLifecycle.READY)
        val tree = modalTree()
        compose.setContent {
            RamfluxTheme { CardRenderer(input(tree, lifecycle = lifecycle), Recorder(), 360.dp, Modifier) }
        }
        compose.onNodeWithText("Details").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("card-modal-sheet").assertExists()
        compose.runOnIdle { lifecycle = CardLifecycle.SUSPENDED }
        compose.waitForIdle()
        compose.onNodeWithTag("card-modal-sheet").assertDoesNotExist()
    }

    @Test fun theLinkSheetIsNotDrawnUnderTheCover() {
        var covered by mutableStateOf(true)
        val request = buildJsonObject {
            put("kind", "open_url")
            put("url", "https://example.com/path")
            put("slot_id", "s")
            put("request_id", "q")
            put("token", buildJsonObject { put("session", 1) })
        }
        compose.setContent {
            RamfluxTheme {
                CompositionLocalProvider(LocalAppLockCovered provides covered) {
                    CardRenderer(input(tree(node("root", "Column", null)), request), Recorder(), 360.dp, Modifier)
                }
            }
        }
        compose.onNodeWithTag("card-link-sheet").assertDoesNotExist()
        compose.runOnIdle { covered = false }
        compose.waitForIdle()
        compose.onNodeWithTag("card-link-sheet").assertExists()
        compose.onNodeWithText("example.com").assertExists()
    }

    private fun sliderCase(low: Double, high: Double) {
        val tree = tree(
            node("root", "Column", null),
            node(
                "slider",
                "Slider",
                "root",
                0,
                buildJsonObject {
                    put("label", "Level")
                    put("min", low)
                    put("max", high)
                    put("value", 5.0)
                }
            )
        )
        val actions = Recorder()
        compose.setContent { RamfluxTheme { CardRenderer(input(tree), actions, 360.dp, Modifier) } }
        compose.onNodeWithText("Level").assertExists()
        assertEquals(emptyList<String>(), actions.events)
    }

    /** A checkbox the runtime did not take reverts to the tree value when the host reports the input stale. */
    @Test fun aCheckBoxRevertsWhenItsInputCameBackStale() {
        val tree = tree(
            node("root", "Column", null),
            node("cb", "CheckBox", "root", 0, buildJsonObject { put("label", "Agree"); put("value", false) })
        )
        var staleSeq by mutableStateOf(0L)
        val actions = Recorder()
        compose.setContent {
            RamfluxTheme {
                val base = input(tree)
                val shown = CardRenderInput(
                    base.ref, base.tree, base.lifecycle, base.fallback, true, null, false, false,
                    staleKey = if (staleSeq > 0) "cb" else null, staleSeq = staleSeq
                )
                CardRenderer(shown, actions, 360.dp, Modifier)
            }
        }
        compose.onNodeWithContentDescription("Agree").performClick()
        compose.waitForIdle()
        // The control now shows checked; a second tap would send false.
        compose.runOnIdle { staleSeq = 1 }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Agree").performClick()
        compose.waitForIdle()
        // Reverted to the tree value (false), the next tap sends true again.
        assertEquals(listOf<JsonElement>(JsonPrimitive(true), JsonPrimitive(true)), actions.inputs)
    }

    @Test fun aSliderWithAMinBeyondFloatRangeDrawsWithoutCrashing() = sliderCase(-1e39, 100.0)

    @Test fun aSliderWithAMaxBeyondFloatRangeDrawsWithoutCrashing() = sliderCase(0.0, 1e39)
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.time.Instant
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

class CardSurfacesTest {
    private val hashed = mutableListOf<String>()
    private val digest = {
        object : PlaintextDigest {
            private val seen = StringBuilder()
            override fun update(bytes: ByteArray, offset: Int, length: Int) {
                seen.append(String(bytes, offset, length, Charsets.UTF_8))
            }
            override fun hex(): String {
                hashed += seen.toString()
                return "ab".repeat(32)
            }
        }
    }

    private fun view(renderAllowed: Boolean = true, closed: Boolean = false, outOfSync: Boolean = false) = Json.parseToJsonElement(
        """{"surface_ref":"ref1","surface_id":"s1","conversation_id":"c","author_principal_id":"agent","version":3,""" +
            """"surface_hash":"H3","closed":$closed,"out_of_sync":$outOfSync,"render_allowed":$renderAllowed,""" +
            """"fallback_text":"Pick a day","created_at":100,"snapshot":{"surfaceId":"s1","catalogId":"urn:ramflux:catalog:rf-card:1.0",""" +
            """"fallbackText":"Pick a day","components":[""" +
            """{"id":"root","component":"Column","children":["title","note","agree","day","go","web","pic"]},""" +
            """{"id":"title","component":"Text","text":{"@path":"/title"}},""" +
            """{"id":"note","component":"TextField","label":"Note","value":{"@path":"/note"}},""" +
            """{"id":"agree","component":"CheckBox","label":"Agree","value":{"@path":"/agree"}},""" +
            """{"id":"day","component":"ChoicePicker","label":"Day","value":{"@path":"/day"},""" +
            """"options":[{"label":"Mon","value":"mon"},{"label":"Tue","value":"tue"}]},""" +
            """{"id":"go_label","component":"Text","text":"Send"},""" +
            """{"id":"go","component":"Button","child":"go_label","variant":"primary","action":{"event":{"name":"submit",""" +
            """"context":{"note":{"@path":"/note"},"kind":"plan"}}}},""" +
            """{"id":"web_label","component":"Text","text":"Open"},""" +
            """{"id":"web","component":"Button","child":"web_label","action":{"functionCall":{"call":"openUrl","args":{"url":"https://x.example"}}}},""" +
            """{"id":"pic","component":"Image","attachment":"a"}],""" +
            """"dataModel":{"title":"Plan","note":"","agree":false,"day":["mon"]},"final":[],"closed":$closed}}""",
    ).jsonObject

    @Test fun theParserResolvesBindingsAndMarksUnsupportedPartsAndLinks() {
        val surface = CardParser.surface(view())!!
        assertTrue(surface.actionable)
        val root = surface.root as CardGroup
        assertEquals(listOf("title", "note", "agree", "day", "go", "web", "pic"), root.children.map { it.id })
        assertEquals("Plan", (root.children[0] as CardText).text)
        assertEquals("/note", (root.children[1] as CardTextInput).pointer)
        assertEquals(listOf("mon"), (root.children[3] as CardChoice).selected)
        assertFalse((root.children[4] as CardButton).link)
        assertTrue((root.children[5] as CardButton).link)
        assertTrue(root.children[6] is CardUnsupported)
        assertEquals(setOf("go"), surface.buttons.keys)
        assertEquals(mapOf("note" to "/note"), surface.buttons.getValue("go").bound)
        assertFalse(CardParser.surface(view(closed = true))!!.actionable)
        assertFalse(CardParser.surface(view(outOfSync = true))!!.actionable)
        // Another author's surface shows only its fallback text.
        val plain = CardParser.surface(view(renderAllowed = false))!!
        assertNull(plain.root)
        assertFalse(plain.actionable)
        assertEquals("Pick a day", plain.fallback)
    }

    @Test fun canonicalJsonSortsKeysAndEscapesMinimally() {
        val value = Json.parseToJsonElement("""{"b":1,"a":"x\"\n\u0001é","c":[true,null,{"z":0,"y":-2}]}""")
        assertEquals("""{"a":"x\"\n\u0001é","b":1,"c":[true,null,{"y":-2,"z":0}]}""", String(Canonical.bytes(value), Charsets.UTF_8))
    }

    @Test fun anActionBindsTheShownVersionHashAndInputValues() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = Json.parseToJsonElement("""{"accepted":true}""").jsonObject }
        val client = (CoreClient.open(abi, "/test/cards", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultCardSurfaceRepository(SdkRepositoryTransport { client }, digest, clock = { Instant.parse("2026-11-23T10:00:00.5Z") })
            val surface = CardParser.surface(view())!!
            val press = CardPress(surface, "go", mapOf("/note" to JsonPrimitive("hi")), "msg-1")
            val expected = """{"a2ui":{"action":{"context":{"kind":"plan","note":"hi"},"name":"submit","sourceComponentId":"go",""" +
                """"surfaceId":"s1","timestamp":"2026-11-23T10:00:00Z"},"version":"1.0"},"argsHash":"""" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0xab.toByte() }) +
                """","conversationId":"c","schema":"ramflux.surface.action.v3","sourceInstance":[],"surfaceHash":"H3","surfaceVersion":3}"""
            assertEquals(expected, String(repo.actionBytes(press)!!, Charsets.UTF_8))
            assertEquals(
                """ramflux.a2ui.action_args.v2{"context":{"kind":"plan","note":"hi"},"name":"submit","sourceInstance":[]}""",
                hashed.last(),
            )
            assertTrue(repo.act("a", press) is ClientResult.Ready)
            val sent = abi.submitted.last()
            assertEquals("surface.action", sent["method"]?.jsonPrimitive?.content)
            val body = sent["body"]!!.jsonObject
            assertEquals("agent", body["author_principal_id"]?.jsonPrimitive?.content)
            assertEquals("msg-1", body["message_id"]?.jsonPrimitive?.content)
            val wire = Base64.getUrlDecoder().decode(body["wire_base64"]!!.jsonPrimitive.content)
            assertEquals(expected, String(wire, Charsets.UTF_8))
            // A link, a closed surface and an unknown button never produce action bytes.
            assertNull(repo.actionBytes(press.copy(button = "web")))
            assertTrue(repo.act("a", press.copy(surface = CardParser.surface(view(closed = true))!!)) is ClientResult.Failed)
            assertEquals(22, repo.newMessageId().length)
        } finally {
            client.close()
        }
    }
}

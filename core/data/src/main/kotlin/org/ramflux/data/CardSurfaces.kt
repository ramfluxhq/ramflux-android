// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

/** One node of an agent card (catalog `urn:ramflux:catalog:rf-card:1.0`), resolved for display. */
sealed interface CardNode {
    val id: String
}

/** [text] is author-controlled; it may carry simple Markdown, shown as plain text. */
data class CardText(override val id: String, val text: String, val caption: Boolean) : CardNode
data class CardGroup(override val id: String, val horizontal: Boolean, val framed: Boolean, val children: List<CardNode>) : CardNode
data class CardButton(override val id: String, val label: String, val primary: Boolean, val link: Boolean) : CardNode
data class CardTextInput(
    override val id: String,
    val label: String,
    val placeholder: String,
    val pointer: String,
    val value: String,
    val multiline: Boolean,
    val obscured: Boolean,
) : CardNode
data class CardCheck(override val id: String, val label: String, val pointer: String, val value: Boolean) : CardNode
data class CardChoice(
    override val id: String,
    val label: String,
    val pointer: String,
    val options: List<Pair<String, String>>,
    val multiple: Boolean,
    val selected: List<String>,
) : CardNode
data class CardApproval(override val id: String, val explanation: String, val task: Boolean) : CardNode
data class CardDivider(override val id: String) : CardNode

/** A component this client does not render (template, modal, tabs, image, chart, table, task, slider, date). */
data class CardUnsupported(override val id: String, val kind: String) : CardNode

/** What a Button declares: the event name, an optional literal user message and its context entries. */
data class CardButtonSpec(val name: String, val userMessage: String?, val literals: Map<String, JsonElement>, val bound: Map<String, String>)

/** One surface as the SDK reads it. Only own agents' surfaces carry content ([renderAllowed]). */
data class CardSurface(
    val ref: String,
    val surfaceId: String,
    val conversation: String,
    val author: String,
    val version: Long,
    val hash: String,
    val closed: Boolean,
    val outOfSync: Boolean,
    val renderAllowed: Boolean,
    /** The author's plain fallback, as the SDK renders it; peer-controlled. */
    val fallback: String,
    val createdAt: Long?,
    val root: CardNode?,
    val buttons: Map<String, CardButtonSpec>,
    /** Current values of the inputs, keyed by their owned model pointer. */
    val inputs: Map<String, JsonElement>,
) {
    /** Controls work only on a current, open, renderable surface; anything else shows no active control. */
    val actionable: Boolean get() = renderAllowed && !closed && !outOfSync && root != null && version > 0 && hash.isNotEmpty()
    override fun toString() = "CardSurface(<redacted>)"
}

/** `surface.list` metadata: enough to tell whether a held surface changed. */
data class CardSurfaceRef(val ref: String, val version: Long, val hash: String, val closed: Boolean, val outOfSync: Boolean) {
    override fun toString() = "CardSurfaceRef(<redacted>)"
}

data class CardSurfacePage(val rows: List<CardSurfaceRef>, val next: String?) {
    override fun toString() = "CardSurfacePage(<redacted>)"
}

/** A pressed button with the person's input values; [messageId] is reused for a retry of the same press. */
data class CardPress(val surface: CardSurface, val button: String, val values: Map<String, JsonElement>, val messageId: String) {
    override fun toString() = "CardPress(<redacted>)"
}

/** Agent surfaces of a conversation (`surface.list`, `surface.get`) and their actions (`surface.action`). */
interface CardSurfaceRepository {
    suspend fun page(account: String, conversation: String, cursor: String? = null): ClientResult<CardSurfacePage>

    /** The full surface; content is present only when the SDK allows rendering it. */
    suspend fun get(account: String, ref: String): ClientResult<CardSurface?>
    suspend fun act(account: String, press: CardPress): ClientResult<Unit>
    fun newMessageId(): String
}

class UnavailableCardSurfaceRepository : CardSurfaceRepository {
    override suspend fun page(account: String, conversation: String, cursor: String?): ClientResult<CardSurfacePage> =
        ClientResult.Unavailable(ClientFeature.CARDS, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun act(account: String, press: CardPress): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.CARDS, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun get(account: String, ref: String): ClientResult<CardSurface?> =
        ClientResult.Unavailable(ClientFeature.CARDS, UnavailableReason.UNSUPPORTED_BY_CORE)
    override fun newMessageId(): String = ""
}

/**
 * [digest] is the unkeyed BLAKE3 the protocol uses for `argsHash` (domain tag then canonical bytes).
 * Action bytes are RFC 8785 canonical JSON of `ramflux.surface.action.v3`, bound to the shown version
 * and hash; the author re-validates every value against the inputs the button may send.
 */
class DefaultCardSurfaceRepository(
    transport: RepositoryTransport,
    private val digest: () -> PlaintextDigest,
    private val clock: () -> Instant = { Instant.now() },
) : CardSurfaceRepository {
    private val source = DefaultSurfacesRepository(transport)
    private val random = SecureRandom()

    override fun newMessageId(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override suspend fun page(account: String, conversation: String, cursor: String?): ClientResult<CardSurfacePage> {
        if (!usable(account) || !usable(conversation) || (cursor?.length ?: 0) > 8192) return invalid()
        val listed = read(
            source.surfaceList(
                RepositoryContext(account),
                buildJsonObject {
                    put("conversation_id", conversation)
                    put("limit", PAGE)
                    cursor?.let { put("cursor", it) }
                },
            ),
        ) ?: return failed()
        if (listed is Failure) return ClientResult.Failed(listed.problem)
        val root = (listed as Body).json
        val rows = (root["surfaces"] as? JsonArray).orEmpty().mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            if (item.flag("removed") == true) return@mapNotNull null
            CardSurfaceRef(
                item.text("surface_ref") ?: return@mapNotNull null,
                item.number("version") ?: 0,
                item.text("surface_hash").orEmpty(),
                item.flag("closed") == true,
                item.flag("out_of_sync") == true,
            )
        }
        return ClientResult.Ready(CardSurfacePage(rows, root.text("next_cursor")?.takeIf { it.length <= 8192 }))
    }

    override suspend fun get(account: String, ref: String): ClientResult<CardSurface?> {
        if (!usable(account) || ref.isEmpty() || ref.length > 512) return invalid()
        return when (val got = read(source.surfaceGet(RepositoryContext(account), buildJsonObject { put("surface_ref", ref) }))) {
            null -> ClientResult.Ready(null)
            is Failure -> ClientResult.Failed(got.problem)
            is Body -> ClientResult.Ready(CardParser.surface(got.json))
        }
    }

    override suspend fun act(account: String, press: CardPress): ClientResult<Unit> {
        val surface = press.surface
        if (!usable(account) || !surface.actionable || press.messageId.isEmpty()) return invalid()
        val wire = actionBytes(press) ?: return invalid()
        val encoded = Base64.getUrlEncoder().withoutPadding().encode(wire)
        wire.fill(0)
        val chars = CharArray(encoded.size) { encoded[it].toInt().toChar() }
        encoded.fill(0)
        val body = SecretBody.fields {
            publicText("conversation_id", surface.conversation)
            publicText("message_id", press.messageId)
            publicText("author_principal_id", surface.author)
            chars("wire_base64", chars)
        }
        return when (val result = source.surfaceAction(RepositoryContext(account, press.messageId), body)) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> {
                result.value.close()
                ClientResult.Ready(Unit)
            }
        }
    }

    /** The canonical action frame, or null when the button or a value does not match the shown surface. */
    fun actionBytes(press: CardPress): ByteArray? {
        val surface = press.surface
        val spec = surface.buttons[press.button] ?: return null
        val context = LinkedHashMap<String, JsonElement>()
        spec.literals.forEach { (key, value) -> context[key] = value }
        for ((key, pointer) in spec.bound) {
            context[key] = press.values[pointer] ?: surface.inputs[pointer] ?: return null
        }
        val contextJson = JsonObject(context)
        val args = buildJsonObject {
            put("sourceInstance", buildJsonArray { })
            put("name", spec.name)
            put("context", contextJson)
            spec.userMessage?.let { put("userMessage", it) }
        }
        val hasher = digest()
        val domain = ARGS_DOMAIN.toByteArray(Charsets.UTF_8)
        val canonicalArgs = Canonical.bytes(args)
        hasher.update(domain, 0, domain.size)
        hasher.update(canonicalArgs, 0, canonicalArgs.size)
        val argsHash = Base64.getUrlEncoder().withoutPadding().encodeToString(hex(hasher.hex()))
        val frame = buildJsonObject {
            put("schema", ACTION_SCHEMA)
            put("conversationId", surface.conversation)
            put("surfaceVersion", surface.version)
            put("surfaceHash", surface.hash)
            put("sourceInstance", buildJsonArray { })
            put("argsHash", argsHash)
            put(
                "a2ui",
                buildJsonObject {
                    put("version", "1.0")
                    put(
                        "action",
                        buildJsonObject {
                            put("name", spec.name)
                            put("surfaceId", surface.surfaceId)
                            put("sourceComponentId", press.button)
                            put("timestamp", clock().truncatedTo(ChronoUnit.SECONDS).toString())
                            put("context", contextJson)
                            spec.userMessage?.let { put("userMessage", it) }
                        },
                    )
                },
            )
        }
        return Canonical.bytes(frame)
    }

    private sealed interface Reply
    private data class Body(val json: JsonObject) : Reply
    private data class Failure(val problem: ClientProblem) : Reply

    @OptIn(ExperimentalSerializationApi::class)
    private fun read(result: CoreResult<SecretReply>): Reply? = when (result) {
        is CoreResult.Failure -> Failure(result.error.appProblem())
        is CoreResult.Success -> try {
            result.value.useOnce { raw ->
                (json.decodeFromStream(JsonElement.serializer(), ByteArrayInputStream(raw)) as? JsonObject)?.let { Body(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            result.value.close()
        }
    }

    private fun usable(value: String) = value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 256
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
    private fun failed() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN))

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val PAGE = 50
        const val ACTION_SCHEMA = "ramflux.surface.action.v3"
        const val ARGS_DOMAIN = "ramflux.a2ui.action_args.v2"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Reads a `surface.get` view into app-owned card values. Only explicit renderable content is parsed. */
internal object CardParser {
    private const val MAX_DEPTH = 16

    fun surface(view: JsonObject): CardSurface? {
        val ref = view.text("surface_ref") ?: view.text("id") ?: return null
        val renderAllowed = view.flag("render_allowed") == true
        val snapshot = view["snapshot"] as? JsonObject
        val components = (snapshot?.get("components") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .associateBy { it.text("id").orEmpty() }
        val model = snapshot?.get("dataModel") ?: JsonNull
        val buttons = HashMap<String, CardButtonSpec>()
        val inputs = HashMap<String, JsonElement>()
        val root = if (renderAllowed && snapshot != null) node("root", components, model, buttons, inputs, 0) else null
        return CardSurface(
            ref = ref,
            surfaceId = view.text("surface_id").orEmpty(),
            conversation = view.text("conversation_id").orEmpty(),
            author = view.text("author_principal_id").orEmpty(),
            version = view.number("version") ?: 0,
            hash = view.text("surface_hash").orEmpty(),
            closed = view.flag("closed") == true || snapshot?.flag("closed") == true,
            outOfSync = view.flag("out_of_sync") == true,
            renderAllowed = renderAllowed,
            fallback = view.text("fallback_text").orEmpty(),
            createdAt = view.number("created_at"),
            root = root,
            buttons = buttons,
            inputs = inputs,
        )
    }

    private fun node(
        id: String,
        components: Map<String, JsonObject>,
        model: JsonElement,
        buttons: MutableMap<String, CardButtonSpec>,
        inputs: MutableMap<String, JsonElement>,
        depth: Int,
    ): CardNode {
        val component = components[id] ?: return CardUnsupported(id, "missing")
        if (depth >= MAX_DEPTH) return CardUnsupported(id, "depth")
        fun child(name: String) = component.text(name)?.let { node(it, components, model, buttons, inputs, depth + 1) }
        fun children(): List<CardNode>? = when (val list = component["children"]) {
            is JsonArray -> list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }.map { node(it, components, model, buttons, inputs, depth + 1) }
            else -> null
        }
        return when (val kind = component.text("component").orEmpty()) {
            "Text" -> CardText(id, string(component["text"], model) ?: return CardUnsupported(id, kind), component.text("variant") == "caption")
            "Row", "Column", "List" -> children()?.let { CardGroup(id, kind == "Row", false, it) } ?: CardUnsupported(id, "template")
            "Card" -> child("child")?.let { CardGroup(id, false, true, listOf(it)) } ?: CardUnsupported(id, kind)
            "Divider" -> CardDivider(id)
            "Button" -> button(id, component, components, model, buttons)
            "TextField" -> {
                val pointer = path(component["value"]) ?: return CardUnsupported(id, kind)
                val value = pointerValue(model, pointer)
                inputs[pointer] = value ?: JsonPrimitive("")
                CardTextInput(
                    id,
                    string(component["label"], model).orEmpty(),
                    string(component["placeholder"], model).orEmpty(),
                    pointer,
                    (value as? JsonPrimitive)?.contentOrNull().orEmpty(),
                    component.text("variant") == "longText",
                    component.text("variant") == "obscured",
                )
            }
            "CheckBox" -> {
                val pointer = path(component["value"]) ?: return CardUnsupported(id, kind)
                val value = (pointerValue(model, pointer) as? JsonPrimitive)?.booleanOrNull ?: false
                inputs[pointer] = JsonPrimitive(value)
                CardCheck(id, string(component["label"], model).orEmpty(), pointer, value)
            }
            "ChoicePicker" -> {
                val pointer = path(component["value"]) ?: return CardUnsupported(id, kind)
                val options = (component["options"] as? JsonArray).orEmpty().mapNotNull { option ->
                    val item = option as? JsonObject ?: return@mapNotNull null
                    val value = item.text("value") ?: return@mapNotNull null
                    value to (string(item["label"], model) ?: value)
                }
                val current = pointerValue(model, pointer)
                inputs[pointer] = current ?: JsonArray(emptyList())
                val selected = (current as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }.orEmpty()
                CardChoice(id, string(component["label"], model).orEmpty(), pointer, options, component.text("variant") == "multipleSelection", selected)
            }
            "Approval" -> CardApproval(id, component.text("explanation").orEmpty(), component["taskBinding"] is JsonObject)
            else -> CardUnsupported(id, kind)
        }
    }

    private fun button(
        id: String,
        component: JsonObject,
        components: Map<String, JsonObject>,
        model: JsonElement,
        buttons: MutableMap<String, CardButtonSpec>,
    ): CardNode {
        val label = component.text("child")?.let { components[it] }?.let { string(it["text"], model) }.orEmpty()
        val action = component["action"] as? JsonObject
        val event = action?.get("event") as? JsonObject
        // A link (functionCall openUrl) needs a host-owned confirmation sheet this client does not have yet.
        if (event == null) return CardButton(id, label, component.text("variant") == "primary", link = true)
        val name = event.text("name") ?: return CardUnsupported(id, "Button")
        val message = event["userMessage"]
        if (message != null && !(message is JsonPrimitive && message.isString)) return CardUnsupported(id, "Button")
        val literals = LinkedHashMap<String, JsonElement>()
        val bound = LinkedHashMap<String, String>()
        (event["context"] as? JsonObject)?.forEach { (key, value) ->
            val pointer = path(value)
            if (pointer != null) bound[key] = pointer else literals[key] = value
        }
        buttons[id] = CardButtonSpec(name, (message as? JsonPrimitive)?.content, literals, bound)
        return CardButton(id, label, component.text("variant") == "primary", link = false)
    }

    private fun path(value: JsonElement?): String? = (value as? JsonObject)?.takeIf { it.size == 1 }?.text("@path")

    private fun string(value: JsonElement?, model: JsonElement): String? = when (value) {
        is JsonPrimitive -> value.takeIf { it.isString }?.content
        is JsonObject -> path(value)?.let { (pointerValue(model, it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        else -> null
    }

    /** Object-only JSON Pointer resolution, as the protocol's text pointers are. */
    fun pointerValue(model: JsonElement, pointer: String): JsonElement? {
        if (pointer.isEmpty()) return model
        if (!pointer.startsWith("/")) return null
        var current: JsonElement = model
        for (raw in pointer.substring(1).split('/')) {
            val token = raw.replace("~1", "/").replace("~0", "~")
            current = (current as? JsonObject)?.get(token) ?: return null
        }
        return current
    }
}

/** RFC 8785 canonical JSON for the values cards carry: sorted keys, minimal escapes, integers only. */
internal object Canonical {
    fun bytes(value: JsonElement): ByteArray = StringBuilder().also { write(value, it) }.toString().toByteArray(Charsets.UTF_8)

    private fun write(value: JsonElement, out: StringBuilder) {
        when (value) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                value.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    string(key, out)
                    out.append(':')
                    write(value.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                value.isString -> string(value.content, out)
                value.booleanOrNull != null -> out.append(value.content)
                value.longOrNull != null -> out.append(value.longOrNull)
                else -> throw IllegalArgumentException("number")
            }
        }
    }

    private fun string(text: String, out: StringBuilder) {
        out.append('"')
        for (char in text) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\b' -> out.append("\\b")
                char == '\u000C' -> out.append("\\f")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' -> out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> out.append(char)
            }
        }
        out.append('"')
    }
}

private fun JsonPrimitive.contentOrNull(): String? = takeIf { it.isString }?.content

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.number(name: String): Long? {
    val value = this[name] as? JsonPrimitive ?: return null
    return if (value.isString) value.content.toLongOrNull() else value.longOrNull
}

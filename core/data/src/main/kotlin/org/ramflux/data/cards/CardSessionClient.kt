// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.ClientOutcome
import org.ramflux.data.ClientProblem
import org.ramflux.data.RepositoryTransport
import org.ramflux.data.appProblem
import org.ramflux.sdk.CoreRequest
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply
import org.ramflux.sdk.SecretRequest

/** One host event the renderer builds for a mounted control (`card.session.event`, secret body). */
sealed interface CardEvent {
    fun json(): JsonObject

    class Input(val value: JsonElement) : CardEvent {
        override fun json(): JsonObject = buildJsonObject {
            put("kind", "input")
            put("value", value)
        }
        override fun toString() = "CardEvent.Input(<redacted>)"
    }

    data object Activate : CardEvent {
        override fun json(): JsonObject = buildJsonObject { put("kind", "activate") }
    }

    class SelectTab(val index: Int) : CardEvent {
        override fun json(): JsonObject = buildJsonObject {
            put("kind", "select_tab")
            put("index", index)
        }
    }

    data object ModalOpen : CardEvent {
        override fun json(): JsonObject = buildJsonObject { put("kind", "modal_open") }
    }

    data object ModalClose : CardEvent {
        override fun json(): JsonObject = buildJsonObject { put("kind", "modal_close") }
    }
}

/** The answer a host gives to a link or approval slot (`card.session.slot`). */
enum class CardSlotOutcome(val wire: String) { CONFIRM("confirm"), DENY("deny"), CANCEL("cancel"), UNAVAILABLE("unavailable") }

/** A bus answer. [result] is the SDK's word (`opened`, `updated`, `refused`, ...); draw only the fallback when [renderAllowed] is false. */
class CardReply internal constructor(
    val result: String,
    val session: Long?,
    val renderAllowed: Boolean,
    val fallback: String,
    val reason: String?,
    val batch: JsonObject?,
    val hostRequest: JsonObject?,
    val draftConflict: Boolean,
) {
    override fun toString() = "CardReply(result=$result, <redacted>)"

    companion object {
        /**
         * The [reason] of a host-made `stale_dropped`: the account signed out or was removed before the operation started.
         * The control reverts; nothing is asked again.
         */
        const val ACCOUNT_RETIRED = "host_account_retired"

        internal fun parse(row: JsonObject): CardReply? {
            val result = row.text("result") ?: return null
            return CardReply(
                result = result,
                session = row["session"]?.let { (it as? JsonPrimitive)?.longOrNull },
                renderAllowed = (row["render_allowed"] as? JsonPrimitive)?.booleanOrNull == true,
                fallback = row.text("fallback_text").orEmpty(),
                reason = row.text("reason"),
                batch = row["batch"] as? JsonObject,
                hostRequest = row["host_request"] as? JsonObject,
                draftConflict = (row["draft_conflict"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }
}

internal sealed interface CardCall {
    class Replied(val reply: CardReply) : CardCall
    class Failed(val problem: ClientProblem) : CardCall
}

/**
 * Typed `card.session.*` calls over the bus. Open, update, present, suspend, resume and dispose travel as plain
 * bodies; event, slot and submit are secret bodies, so their replies are read once and wiped. Only [submit]
 * reaches the network inside the SDK. Bodies above the bus limit are refused here, before they are sent.
 */
internal class CardSessionClient(private val transport: RepositoryTransport) {

    suspend fun open(account: String, surfaceRef: String, locale: String, dark: Boolean): CardCall =
        plain(account, "card.session.open", buildJsonObject {
            put("surface_ref", surfaceRef)
            put("locale", locale)
            put("dark", dark)
        })

    suspend fun update(account: String, session: Long): CardCall = plain(account, "card.session.update", sessionBody(session))

    suspend fun present(account: String, session: Long, locale: String, dark: Boolean): CardCall =
        plain(account, "card.session.present", buildJsonObject {
            put("session", session)
            put("locale", locale)
            put("dark", dark)
        })

    suspend fun suspendSession(account: String, session: Long): CardCall = plain(account, "card.session.suspend", sessionBody(session))

    suspend fun resume(account: String, session: Long): CardCall = plain(account, "card.session.resume", sessionBody(session))

    suspend fun dispose(account: String, session: Long): CardCall = plain(account, "card.session.dispose", sessionBody(session))

    suspend fun event(account: String, session: Long, token: JsonObject, event: CardEvent): CardCall =
        secret(account, "card.session.event", buildJsonObject {
            put("session", session)
            put("token", token)
            put("event", event.json())
        })

    suspend fun slot(
        account: String, session: Long, token: JsonObject, slotId: String, requestId: String, outcome: CardSlotOutcome,
    ): CardCall = secret(account, "card.session.slot", buildJsonObject {
        put("session", session)
        put("token", token)
        put("slot_id", slotId)
        put("request_id", requestId)
        put("outcome", outcome.wire)
    })

    suspend fun submit(account: String, session: Long): CardCall = secret(account, "card.session.submit", sessionBody(session))

    private fun sessionBody(session: Long): JsonObject = buildJsonObject { put("session", session) }

    private suspend fun plain(account: String, method: String, body: JsonObject): CardCall {
        if (body.toString().toByteArray(Charsets.UTF_8).size > MAX_BODY) return failed(LIMIT_CODE)
        return when (val result = transport.execute(CoreRequest(method, SDK_API, account, null, body, 0))) {
            is CoreResult.Failure -> CardCall.Failed(result.error.appProblem())
            is CoreResult.Success -> CardReply.parse(result.value)?.let { CardCall.Replied(it) } ?: failed(INVALID_CODE)
        }
    }

    private suspend fun secret(account: String, method: String, body: JsonObject): CardCall {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BODY) return failed(LIMIT_CODE)
        val request = SecretRequest(method, SDK_API, account, null, SecretBody.takeJson(bytes), 0)
        return when (val result = transport.executeSecret(request)) {
            is CoreResult.Failure -> CardCall.Failed(result.error.appProblem())
            is CoreResult.Success -> readSecret(result.value)?.let { CardCall.Replied(it) } ?: failed(INVALID_CODE)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readSecret(reply: SecretReply): CardReply? = try {
        reply.useOnce { raw -> (json.decodeFromStream(JsonElement.serializer(), ByteArrayInputStream(raw)) as? JsonObject)?.let(CardReply::parse) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } finally {
        reply.close()
    }

    private fun failed(code: Int) = CardCall.Failed(ClientProblem(ClientFailureKind.FAILED, code, ClientOutcome.NONE))

    companion object {
        const val SDK_API = "card"
        /** The bus bodies are at most 16 KiB (the open body at most 4 KiB; the SDK refuses larger ones). */
        const val MAX_BODY = 16 * 1024
        const val INVALID_CODE = 1
        const val LIMIT_CODE = 2

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** The typed card refusal token of a failure: the bus `reason_code`, else the status name; null when neither is set. */
internal fun ClientProblem.cardToken(): String? = check ?: statusName

internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

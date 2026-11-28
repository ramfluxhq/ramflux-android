// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ramflux.sdk.*

/**
 * Reads identity and security through the SDK by method name. `contact.trust.accept_change` and `conversation.security` are
 * not in the pinned method policy yet, so the transport refuses them with `UnsupportedVersion`; that is mapped to the typed
 * unavailable state and never retried.
 */
internal class DefaultContactIdentityRepository(
    private val transport: RepositoryTransport,
    private val decoding: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : ContactIdentityRepository {
    private val source = DefaultContactsRepository(transport)
    private val known = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** The AI marker and username the last event of a contact gave; a status read carries neither and must not drop them. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, Boolean>>()
    private val calls = object : SdkRepository(transport) {
        suspend fun conversationSecurity(context: RepositoryContext, body: SecretBody) = executeSecret("conversation.security", "conversation", context, body)
        suspend fun acceptChange(context: RepositoryContext, body: SecretBody) = executeSecret("contact.trust.accept_change", "contact", context, body)
    }

    override suspend fun identity(account: String, contact: String): ClientResult<Pair<IdentityState, IdentityChange?>> {
        if (!valid(account) || !valid(contact)) return invalid()
        val body = buildJsonObject { put("contact_identity_commitment", JsonPrimitive(contact)) }
        return when (val reply = source.verificationStatus(RepositoryContext(account), body)) {
            is CoreResult.Failure -> failed(reply.error)
            is CoreResult.Success -> when (val read = withContext(decoding) { ContactIdentityMetadataReader.status(reply.value) }) {
                is CoreResult.Failure -> ClientResult.Failed(read.error.appProblem())
                is CoreResult.Success -> {
                    val state = state(read.value.state)
                    if (state == IdentityState.CHANGED) mark(account, contact, true)
                    else if (state != IdentityState.UNKNOWN) mark(account, contact, false)
                    val change = pendingChange(contact, state, read.value, seen[account + "\u0000" + contact], clock())
                    ClientResult.Ready(state to change)
                }
            }
        }
    }

    override suspend fun security(account: String, conversation: String): ClientResult<ConversationSecurity> {
        if (!valid(account) || !valid(conversation)) return invalid()
        val body = SecretBody.fields { publicText("conversation_id", conversation) }
        return when (val reply = calls.conversationSecurity(RepositoryContext(account), body)) {
            is CoreResult.Failure -> failed(reply.error)
            is CoreResult.Success -> when (val read = withContext(decoding) { ContactIdentityMetadataReader.security(reply.value) }) {
                is CoreResult.Failure -> ClientResult.Failed(read.error.appProblem())
                is CoreResult.Success -> ClientResult.Ready(
                    ConversationSecurity(
                        when (read.value.state) {
                            "ok" -> SecurityState.OK
                            "pending" -> SecurityState.PENDING
                            "re_established" -> SecurityState.RE_ESTABLISHED
                            else -> SecurityState.UNKNOWN
                        },
                        read.value.epoch,
                        read.value.devices.size,
                        read.value.devices.any { it.aiFlag },
                        read.value.warning?.let { if (it == "fork_recovered") SecurityWarning.FORK_RECOVERED else SecurityWarning.UNKNOWN },
                    )
                )
            }
        }
    }

    override suspend fun accept(account: String, contact: String, newAnchor: String): AcceptOutcome {
        if (!valid(account) || !valid(contact) || !valid(newAnchor)) return AcceptOutcome.Failed(null)
        val body = SecretBody.fields {
            publicText("contact", contact)
            publicText("new_anchor", newAnchor)
        }
        return when (val reply = calls.acceptChange(RepositoryContext(account, java.util.UUID.randomUUID().toString()), body)) {
            is CoreResult.Failure -> when (reply.error.statusName ?: reply.error.reasonCode) {
                "no_pending_change" -> {
                    mark(account, contact, false)
                    AcceptOutcome.AlreadyHandled
                }
                "anchor_mismatch" -> AcceptOutcome.ChangedAgain
                else -> AcceptOutcome.Failed(reply.error.statusName ?: reply.error.reasonCode)
            }
            is CoreResult.Success -> if (withContext(decoding) { ContactIdentityMetadataReader.accepted(reply.value) }) {
                mark(account, contact, false)
                AcceptOutcome.Accepted
            } else {
                AcceptOutcome.Failed(null)
            }
        }
    }

    override fun changes(account: String): Flow<IdentitySignal> = flow {
        if (!valid(account)) return@flow
        val filter = SubscriptionFilter(account, buildJsonObject { put("topics", buildJsonArray { TOPICS.forEach { add(it) } }) })
        transport.updates(filter).collect { update ->
            when (update) {
                // The payload is parsed once and dropped; it is never logged.
                is CoreUpdate.Events -> when (val parsed = ContactIdentityMetadataReader.signal(update.body)) {
                    is ContactIdentitySignalMetadata.Changed -> {
                        mark(account, parsed.event.contact, true)
                        seen[account + "\u0000" + parsed.event.contact] = parsed.event.username to parsed.event.aiFlag
                        emit(
                            IdentitySignal.Changed(
                                parsed.event.let { IdentityChange(it.contact, it.username, it.previousAnchor, it.newAnchor, it.aiFlag, clock()) }
                            )
                        )
                    }
                    is ContactIdentitySignalMetadata.Devices -> emit(IdentitySignal.DevicesChanged(parsed.conversation))
                    null -> Unit
                }
                is CoreUpdate.Failure -> Unit
                is CoreUpdate.Terminal -> if (update.kind == "resync_required") emit(IdentitySignal.Resync)
            }
        }
    }

    override fun changed(account: String): Flow<Set<String>> = known.map { it[account].orEmpty() }

    private fun mark(account: String, contact: String, changed: Boolean) {
        if (!changed) seen.remove(account + "\u0000" + contact)
        known.update { all ->
            val now = all[account].orEmpty()
            val next = if (changed) now + contact else now - contact
            if (next == now) all else if (next.isEmpty()) all - account else all + (account to next)
        }
    }

    private fun state(raw: String?) = when (raw) {
        "verified" -> IdentityState.VERIFIED
        "tofu" -> IdentityState.TOFU
        "changed" -> IdentityState.CHANGED
        else -> IdentityState.UNKNOWN
    }

    /** `UnsupportedVersion` (the method is not in the pinned policy) is the typed unavailable state; anything else is a failure. */
    private fun failed(error: CoreError): ClientResult<Nothing> =
        if (error is CoreError.UnsupportedVersion) ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)
        else ClientResult.Failed(error.appProblem())

    private fun valid(value: String) = value.isNotBlank() && value.length <= 4096
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))

    companion object {
        private const val CHANGED = "contact.identity_changed"
        private const val DEVICES = "conversation.devices_changed"

        /** The topics of the live subscription. */
        val TOPICS = listOf(CHANGED, DEVICES)

        /**
         * The change a status read describes. The read has no username; its own `ai_flag` wins, else the flag the last event of
         * the contact gave ([remembered]) is kept, so a re-read never drops the AI marker.
         */
        internal fun pendingChange(
            contact: String,
            state: IdentityState,
            status: ContactIdentityStatus,
            remembered: Pair<String?, Boolean>?,
            now: Long,
        ): IdentityChange? = status.pendingAnchor?.takeIf { state == IdentityState.CHANGED }?.let {
            IdentityChange(contact, remembered?.first, status.previousAnchor, it, status.aiFlag ?: remembered?.second, now)
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** The SDK's `identity_state` of a contact. [UNKNOWN] is any other value or none; it is shown like [TOFU] with no badge. */
enum class IdentityState { VERIFIED, TOFU, CHANGED, UNKNOWN }

/**
 * A contact identity the person has not accepted yet. [newAnchor] is the anchor the sheet sends back on accept. [username] and
 * [aiFlag] come from the event; a change rebuilt from a status read has no username and a false flag. Anchors are public
 * identity values and never reach a log.
 */
data class IdentityChange(
    val contact: String,
    val username: String?,
    val previousAnchor: String?,
    val newAnchor: String,
    val aiFlag: Boolean,
    val seenAt: Long,
) {
    override fun toString() = "IdentityChange(<redacted>)"
}

enum class SecurityState { OK, PENDING, RE_ESTABLISHED, UNKNOWN }

enum class SecurityWarning { FORK_RECOVERED, UNKNOWN }

/** `conversation.security`: [peerDevices] is the number of devices listed and [anyAi] is true when any carries the SDK's AI flag. */
data class ConversationSecurity(
    val state: SecurityState,
    val epoch: Long,
    val peerDevices: Int,
    val anyAi: Boolean,
    val warning: SecurityWarning? = null,
) {
    override fun toString() = "ConversationSecurity(<redacted>)"
}

/** What `contact.trust.accept_change` came back with. Nothing is retried after any of these. */
sealed interface AcceptOutcome {
    data object Accepted : AcceptOutcome

    /** `no_pending_change`: the change was handled before. */
    data object AlreadyHandled : AcceptOutcome

    /** `anchor_mismatch`: a newer change exists. */
    data object ChangedAgain : AcceptOutcome

    /** Any other refusal or failure; [code] is the SDK's typed code, or null. */
    data class Failed(val code: String?) : AcceptOutcome
}

/** A send refused until a changed identity is reviewed (`identity_change_pending`). [contact] is null when the refusal named none. */
sealed interface SendBlock {
    data class IdentityChangePending(val contact: String?) : SendBlock

    companion object {
        const val IDENTITY_CHANGE_PENDING = "identity_change_pending"

        /** The block a refused send reports, or null for any other failure. The draft text is never touched here. */
        fun of(problem: ClientProblem, contact: String? = null): SendBlock? =
            if (problem.statusName == IDENTITY_CHANGE_PENDING || problem.check == IDENTITY_CHANGE_PENDING) IdentityChangePending(contact) else null
    }
}

/** One live signal of the shared event subscription. */
sealed interface IdentitySignal {
    /** `contact.identity_changed`: replaces any older pending change of that contact. */
    data class Changed(val change: IdentityChange) : IdentitySignal {
        override fun toString() = "Changed(<redacted>)"
    }

    /** `conversation.devices_changed`: re-read [ContactIdentityRepository.security] silently. [conversation] is null when the event named none. */
    data class DevicesChanged(val conversation: String?) : IdentitySignal {
        override fun toString() = "DevicesChanged(<redacted>)"
    }

    /** The subscription lost events: re-read what is on screen. */
    data object Resync : IdentitySignal
}

/**
 * Contact identity and conversation security on the MLS surface. The only call that accepts a changed identity is [accept];
 * it is reached from the explicit "Accept new identity" action of [IdentityReview] and nowhere else.
 */
interface ContactIdentityRepository {
    /**
     * `contact.verification.status`: the state and, when core reports a pending anchor, the change it describes. The current core
     * returns no `identity_state`, so the state is [IdentityState.UNKNOWN].
     */
    suspend fun identity(account: String, contact: String): ClientResult<Pair<IdentityState, IdentityChange?>>

    /** `conversation.security`. Fails typed-unavailable while the method is not in the pinned policy. */
    suspend fun security(account: String, conversation: String): ClientResult<ConversationSecurity>

    /** `contact.trust.accept_change` with exactly [newAnchor]. Never retried. */
    suspend fun accept(account: String, contact: String, newAnchor: String): AcceptOutcome

    /** Live signals for [account]; collect while an identity surface is visible. */
    fun changes(account: String): Flow<IdentitySignal> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * The contacts of [account] known to be in the changed state, from events and status reads seen so far (a list badge cannot
     * read every contact). A contact leaves the set when its change is accepted or a status read says it is not changed.
     */
    fun changed(account: String): Flow<Set<String>> = flowOf(emptySet())
}

/** For hosts with no identity surface (tests and previews that do not drive it): everything is typed unavailable. */
object UnavailableContactIdentity : ContactIdentityRepository {
    override suspend fun identity(account: String, contact: String): ClientResult<Pair<IdentityState, IdentityChange?>> = unavailable()
    override suspend fun security(account: String, conversation: String): ClientResult<ConversationSecurity> = unavailable()
    override suspend fun accept(account: String, contact: String, newAnchor: String): AcceptOutcome = AcceptOutcome.Failed(null)
    private fun unavailable() = ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)
}

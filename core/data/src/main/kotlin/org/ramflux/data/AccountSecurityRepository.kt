// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.io.Closeable
import org.ramflux.sdk.SecretBytes

/** Who may start a conversation by contact request (`contact.requests.policy.*`). */
enum class ContactRequestPolicy(val wire: String) { MANUAL("manual"), AUTO_ACCEPT("auto_accept") }

/** Pairing phases in the SDK's order (`ramflux-protocol` `custody/pairing/local_dto.rs` `PHASES`). */
enum class PairingPhase(val wire: String, val terminal: Boolean = false) {
    INVITED("invited"), COMMITMENTS("commitments"), REVEALED("revealed"), SAS_PENDING("sas_pending"),
    MUTUALLY_CONFIRMED("mutually_confirmed"), PROPOSAL_PENDING("proposal_pending"), PREPARED("prepared"),
    SUBMITTED("submitted"), ACKNOWLEDGED("acknowledged"), LOCALLY_INSTALLED("locally_installed"),
    COMPLETED("completed", true), CANCELLED("cancelled", true), EXPIRED("expired", true), REJECTED("rejected", true),
}

/**
 * The show-once invitation this phone (the account's primary) hands to the joining device. [token] is the
 * SDK's invitation token as erasable UTF-8 bytes; [close] erases it. It is never logged or persisted.
 */
class PairingInvitation(val operationId: String, val generation: ULong, val expiresAt: Long, val token: SecretBytes) : Closeable {
    override fun close() = token.close()
    override fun toString() = "PairingInvitation(<redacted>)"
}

/** One pairing state as the SDK reports it; [peerDevice] and [peerName] describe the joining device. */
data class PairingState(
    val operationId: String,
    val generation: ULong,
    val phase: PairingPhase,
    val expiresAt: Long,
    val transcriptHash: String?,
    val emojiIndices: List<Int>?,
    val peerDevice: String?,
    val peerName: String?,
    val capabilities: List<String>,
    val leaseExpiresAt: Long,
    val proposalHash: String?,
    /** The SDK's pairing result code (terminal phases, or `PublicationPending`). */
    val result: String?,
)

/**
 * Account and security actions of the settings screen: the inviting side of device pairing, the contact
 * request policy and the account-code change. The joining side (`device.pair.join`) is not here: it needs
 * the SDK credential channel the in-process C ABI lacks (request R32, docs/bridge.md).
 */
interface AccountSecurityRepository {
    /** `device.pair.begin` with every linked-device capability for [leaseSeconds]; [displayName] names this phone. */
    suspend fun beginPairing(account: String, displayName: String, leaseSeconds: Long = DEFAULT_LEASE_SECONDS): ClientResult<PairingInvitation>
    suspend fun pairingStatus(account: String, operationId: String, generation: ULong): ClientResult<PairingState>

    /** Short-authentication-string comparison: [match] false aborts the ceremony. */
    suspend fun confirmPairing(account: String, state: PairingState, match: Boolean): ClientResult<PairingState>

    /** The host's final decision on the joining device's proposal. */
    suspend fun approvePairing(account: String, state: PairingState, approved: Boolean): ClientResult<PairingState>
    suspend fun cancelPairing(account: String, operationId: String, generation: ULong): ClientResult<PairingState>

    suspend fun requestPolicy(account: String): ClientResult<ContactRequestPolicy>
    suspend fun setRequestPolicy(account: String, policy: ContactRequestPolicy): ClientResult<ContactRequestPolicy>

    /**
     * `account.passphrase.rotate`: the SDK verifies [current] before it rekeys to [next]. Both arrays are
     * wiped before return; refusals carry the SDK's names as in [AccountAccessRepository.unlock].
     */
    suspend fun changeAccountCode(account: String, current: CharArray, next: CharArray): ClientResult<Unit>

    companion object {
        /** Seven days; the SDK admits at most thirty (`MAX_BRANCH_LEASE_SECONDS`). */
        const val DEFAULT_LEASE_SECONDS = 7L * 24 * 3600
    }
}

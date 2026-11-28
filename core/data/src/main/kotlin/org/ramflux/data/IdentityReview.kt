// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the identity change sheet says about its last tap. */
enum class IdentityReviewError { ALREADY_HANDLED, CHANGED_AGAIN, FAILED }

/**
 * The identity change sheet. [newAnchor] is what an accept sends; it is null while no new anchor is known (the sheet waits).
 * [username] and [aiFlag] come from the SDK only.
 */
data class IdentityReviewState(
    val account: String,
    val contact: String,
    val username: String?,
    val aiFlag: Boolean,
    val newAnchor: String?,
    val accepting: Boolean = false,
    val error: IdentityReviewError? = null,
) {
    override fun toString() = "IdentityReviewState(<redacted>)"
}

/**
 * The state machine behind the identity change sheet, shared by the chat and the contact detail.
 *
 * [accept] is the only caller of [ContactIdentityRepository.accept], and it runs only from the sheet's explicit accept action,
 * with the anchor of the change the sheet displays. A newer change replaces the displayed one and needs a new tap. Nothing here
 * retries, resends a message or accepts on a timer.
 */
class IdentityReview(
    private val identity: ContactIdentityRepository,
    private val scope: CoroutineScope,
    /** Called after [accept] has an outcome, so the owner can re-read the status and write its timeline line. */
    private val onOutcome: (account: String, contact: String, outcome: AcceptOutcome, anchor: String) -> Unit = { _, _, _, _ -> },
) {
    private val local = MutableStateFlow<IdentityReviewState?>(null)
    val state: StateFlow<IdentityReviewState?> = local.asStateFlow()
    private var job: Job? = null

    /** The newest anchor the contact is known to have pending: the one an accept must still match. */
    private var latest: String? = null

    /** Shows the sheet for [change], or in the waiting state when [change] is null. */
    fun open(account: String, contact: String, change: IdentityChange?, username: String? = null, aiFlag: Boolean = false) {
        job?.cancel()
        latest = change?.newAnchor
        local.value = IdentityReviewState(
            account, contact, change?.username ?: username, change?.aiFlag ?: aiFlag, change?.newAnchor,
        )
    }

    /**
     * A change of the contact on display. One with a different anchor never replaces the one under review silently: the sheet
     * switches to "changed again" and shows the new anchor, and the person must tap again.
     */
    fun update(change: IdentityChange) {
        local.update {
            if (it == null || it.contact != change.contact) {
                it
            } else {
                latest = change.newAnchor
                val differs = it.newAnchor != null && it.newAnchor != change.newAnchor
                it.copy(
                    username = change.username ?: it.username,
                    aiFlag = change.aiFlag,
                    newAnchor = change.newAnchor,
                    error = if (differs) IdentityReviewError.CHANGED_AGAIN else it.error
                )
            }
        }
    }

    /** "Not now". */
    fun later() {
        if (local.value?.accepting == true) return
        local.value = null
    }

    fun close() {
        job?.cancel()
        local.value = null
    }

    /** The explicit accept tap. Sends the anchor on display; ignored while waiting or already accepting. */
    fun accept() {
        val shown = local.value ?: return
        val anchor = shown.newAnchor ?: return
        if (shown.accepting || shown.error == IdentityReviewError.ALREADY_HANDLED) return
        // The anchor on display must still be the pending one; otherwise nothing is sent and the sheet shows the newer one.
        val newest = latest
        if (newest != null && newest != anchor) {
            local.value = shown.copy(newAnchor = newest, error = IdentityReviewError.CHANGED_AGAIN)
            return
        }
        local.value = shown.copy(accepting = true, error = null)
        job = scope.launch {
            val outcome = identity.accept(shown.account, shown.contact, anchor)
            when (outcome) {
                AcceptOutcome.Accepted ->
                    // A newer change may have arrived during the call: keep the sheet open on it, needing a new tap.
                    if (newer(anchor) == null) {
                        local.value = null
                    } else {
                        local.update { it?.copy(accepting = false, newAnchor = newer(anchor), error = IdentityReviewError.CHANGED_AGAIN) }
                    }
                AcceptOutcome.AlreadyHandled -> {
                    // Only the accepted anchor is handled. A newer pending change is shown as changed again and the sheet stays open.
                    val next = newer(anchor)
                    if (next != null) {
                        local.update { it?.copy(accepting = false, newAnchor = next, error = IdentityReviewError.CHANGED_AGAIN) }
                    } else {
                        local.update { it?.copy(accepting = false, error = IdentityReviewError.ALREADY_HANDLED) }
                        onOutcome(shown.account, shown.contact, outcome, anchor)
                        delay(CLOSE_AFTER_MS)
                        // A newer change that arrived while "handled" showed turns the error into "changed again": it stays.
                        local.update { if (it?.error == IdentityReviewError.ALREADY_HANDLED && newer(anchor) == null) null else it }
                        return@launch
                    }
                }
                AcceptOutcome.ChangedAgain -> {
                    // The status read only supplies the newest anchor to show; it is never sent without a new tap.
                    val fresh = ((identity.identity(shown.account, shown.contact) as? ClientResult.Ready)?.value?.second)?.newAnchor
                    if (fresh != null && fresh != anchor) latest = fresh
                    val next = newer(anchor)
                    local.update { it?.copy(accepting = false, error = IdentityReviewError.CHANGED_AGAIN, newAnchor = next) }
                }
                // A failure never hides a newer change: the retry tap is a new decision on the anchor then shown.
                is AcceptOutcome.Failed -> {
                    val next = newer(anchor)
                    local.update {
                        it?.copy(
                            accepting = false,
                            newAnchor = next ?: it.newAnchor,
                            error = if (next != null) IdentityReviewError.CHANGED_AGAIN else IdentityReviewError.FAILED
                        )
                    }
                }
            }
            onOutcome(shown.account, shown.contact, outcome, anchor)
        }
    }

    /** The newest known pending anchor when it is not [anchor] (the one that was just sent), else null. */
    private fun newer(anchor: String): String? = latest?.takeIf { it != anchor }

    private companion object {
        const val CLOSE_AFTER_MS = 1_500L
    }
}

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
    private val onOutcome: (account: String, contact: String, outcome: AcceptOutcome) -> Unit = { _, _, _ -> },
) {
    private val local = MutableStateFlow<IdentityReviewState?>(null)
    val state: StateFlow<IdentityReviewState?> = local.asStateFlow()
    private var job: Job? = null

    /** Shows the sheet for [change], or in the waiting state when [change] is null. */
    fun open(account: String, contact: String, change: IdentityChange?, username: String? = null, aiFlag: Boolean = false) {
        job?.cancel()
        local.value = IdentityReviewState(
            account, contact, change?.username ?: username, change?.aiFlag ?: aiFlag, change?.newAnchor,
        )
    }

    /** A newer change of the contact on display replaces the displayed one; the person must tap again. */
    fun update(change: IdentityChange) {
        local.update {
            if (it == null || it.contact != change.contact || it.accepting) it
            else it.copy(username = change.username ?: it.username, aiFlag = change.aiFlag, newAnchor = change.newAnchor)
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
        local.value = shown.copy(accepting = true, error = null)
        job = scope.launch {
            val outcome = identity.accept(shown.account, shown.contact, anchor)
            when (outcome) {
                AcceptOutcome.Accepted -> local.value = null
                AcceptOutcome.AlreadyHandled -> {
                    local.update { it?.copy(accepting = false, error = IdentityReviewError.ALREADY_HANDLED) }
                    onOutcome(shown.account, shown.contact, outcome)
                    delay(CLOSE_AFTER_MS)
                    local.update { if (it?.error == IdentityReviewError.ALREADY_HANDLED) null else it }
                    return@launch
                }
                AcceptOutcome.ChangedAgain -> {
                    // The status read only supplies the latest anchor to show; it is never sent without a new tap.
                    val latest = (identity.identity(shown.account, shown.contact) as? ClientResult.Ready)?.value?.second
                    local.update {
                        it?.copy(accepting = false, error = IdentityReviewError.CHANGED_AGAIN, newAnchor = latest?.newAnchor?.takeIf { next -> next != anchor })
                    }
                }
                is AcceptOutcome.Failed -> local.update { it?.copy(accepting = false, error = IdentityReviewError.FAILED) }
            }
            onOutcome(shown.account, shown.contact, outcome)
        }
    }

    private companion object {
        const val CLOSE_AFTER_MS = 1_500L
    }
}

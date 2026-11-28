// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ramflux.data.AcceptOutcome
import org.ramflux.data.ClientResult
import org.ramflux.data.ContactIdentityRepository
import org.ramflux.data.ConversationSecurity
import org.ramflux.data.IdentityChange
import org.ramflux.data.IdentitySignal
import org.ramflux.data.IdentityState

/**
 * The scripted identity repository behind the MLS gallery (realrun builds only). It answers the same interface as the
 * native repository from [IdentityScript]; it counts what the screens ask so the runner can see them on screen.
 */
internal class FixtureContactIdentityRepository(
    private val person: MlsPerson,
    private val script: IdentityScript,
    private val scope: CoroutineScope
) : ContactIdentityRepository {
    private var status = script.status
    private var pending: IdentityChange? = script.pending
    private var acceptIndex = 0
    private var settled = false
    private var securityIndex = 0
    private val live = MutableSharedFlow<IdentitySignal>(extraBufferCapacity = 16)
    private val changedSet = MutableStateFlow(if (script.status == IdentityState.CHANGED) setOf(person.root) else emptySet())
    private val counts = MutableStateFlow(Counts())

    /** What the screens asked so far; shown by the gallery. */
    data class Counts(val identityReads: Int = 0, val securityReads: Int = 0, val accepts: Int = 0, val lastAnchor: String = "-")

    val asked: StateFlow<Counts> = counts.asStateFlow()

    override suspend fun identity(account: String, contact: String): ClientResult<Pair<IdentityState, IdentityChange?>> {
        counts.update { it.copy(identityReads = it.identityReads + 1) }
        return ClientResult.Ready(status to pending)
    }

    override suspend fun security(account: String, conversation: String): ClientResult<ConversationSecurity> {
        counts.update { it.copy(securityReads = it.securityReads + 1) }
        return ClientResult.Ready(script.security[minOf(securityIndex++, script.security.lastIndex)])
    }

    override suspend fun accept(account: String, contact: String, newAnchor: String): AcceptOutcome {
        counts.update { it.copy(accepts = it.accepts + 1, lastAnchor = newAnchor) }
        val outcome = script.accepts[minOf(acceptIndex++, script.accepts.lastIndex)]
        // The anchor must be the pending one: an accept of any other anchor is the mismatch the core would refuse.
        val current = pending?.newAnchor
        if (outcome == AcceptOutcome.Accepted && current != null && current != newAnchor) return AcceptOutcome.ChangedAgain
        when (outcome) {
            AcceptOutcome.Accepted, AcceptOutcome.AlreadyHandled -> settle()
            AcceptOutcome.ChangedAgain -> {
                // A newer change exists; its anchor is delivered by an event, not by the status read.
                pending = null
                script.mismatchEvent?.let { next ->
                    scope.launch {
                        delay(MISMATCH_EVENT_MS)
                        arrive(next)
                    }
                }
            }
            is AcceptOutcome.Failed -> Unit
        }
        return outcome
    }

    override fun changes(account: String): Flow<IdentitySignal> = flow {
        coroutineScope {
            launch {
                script.events.forEach { (after, signal) ->
                    delay(after)
                    if (signal is IdentitySignal.Changed) arrive(signal.change) else live.emit(signal)
                }
            }
            launch {
                script.devicesChangedAfterMs.forEach { after ->
                    delay(after)
                    live.emit(IdentitySignal.DevicesChanged(person.conversation))
                }
            }
            live.collect { emit(it) }
        }
    }

    override fun changed(account: String): Flow<Set<String>> = changedSet.map { it }

    /** A send is refused until the change is accepted; the refusal makes the change pending with its anchor. */
    fun sendBlocked(): Boolean {
        if (!script.blocksSend || settled) return false
        if (status != IdentityState.CHANGED) {
            status = IdentityState.CHANGED
            pending = change(person, "A2")
            changedSet.value = setOf(person.root)
        }
        return true
    }

    private suspend fun arrive(change: IdentityChange) {
        status = IdentityState.CHANGED
        pending = change
        changedSet.value = setOf(person.root)
        live.emit(IdentitySignal.Changed(change))
    }

    private fun settle() {
        settled = true
        status = IdentityState.TOFU
        pending = null
        changedSet.value = emptySet()
    }

    private companion object {
        const val MISMATCH_EVENT_MS = 1_000L
    }
}

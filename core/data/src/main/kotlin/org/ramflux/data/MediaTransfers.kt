// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Downloads that outlive the screen that started them (media design 5.6, 5.10). Imports run over the
 * SDK's live gateway session inside this process, so they last exactly as long as the unlocked session:
 * leaving a chat keeps them running, locking or closing the session cancels every one of them. Nothing
 * here survives process death; the SDK keeps import handles in memory too (request R4).
 *
 * At most [PER_ACCOUNT] imports run at once per account; the rest wait in order.
 */
class MediaTransfers(
    private val media: MediaRepository,
    session: ClientSessionRepository,
    private val scope: CoroutineScope,
) {
    private val states = MutableStateFlow<Map<String, TransferState>>(emptyMap())
    private val jobs = HashMap<String, Job>()
    private val permits = HashMap<String, Semaphore>()

    /** Transfer states by [key]; a settled entry stays until [forget] or the session closes. */
    val current: StateFlow<Map<String, TransferState>> = states.asStateFlow()

    /** True while at least one import runs or waits; the foreground-service controller follows it. */
    val active: StateFlow<Boolean> = MutableStateFlow(false).also { flag ->
        scope.launch { states.map { map -> map.values.any { it.inFlight } }.distinctUntilChanged().collect { flag.value = it } }
    }

    init {
        scope.launch {
            session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
                if (phase != ClientPhase.OPEN) cancelAll()
            }
        }
    }

    /** Starts or joins the import of [attachment] for [account]. */
    fun download(account: String, attachment: MediaAttachment) {
        val key = key(account, attachment)
        synchronized(jobs) {
            if (jobs[key]?.isActive == true) return
            states.update { it + (key to TransferState.Queued) }
            val gate = permits.getOrPut(account) { Semaphore(PER_ACCOUNT) }
            jobs[key] = scope.launch {
                val result = gate.withPermit {
                    media.download(account, attachment) { progress -> states.update { it + (key to progress) } }
                }
                if (result == TransferState.Revoked) media.purge(account, attachment.messageId)
                states.update { it + (key to result) }
                synchronized(jobs) { jobs.remove(key) }
            }
        }
    }

    fun cancel(account: String, attachment: MediaAttachment) {
        val key = key(account, attachment)
        synchronized(jobs) { jobs.remove(key)?.cancel() }
        states.update { it - key }
    }

    /** Drops a settled state once its owner has taken the result. */
    fun forget(key: String) {
        if (synchronized(jobs) { jobs[key]?.isActive == true }) return
        states.update { it - key }
    }

    private fun cancelAll() {
        synchronized(jobs) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
        states.value = emptyMap()
    }

    companion object {
        const val PER_ACCOUNT = 3

        fun prefix(account: String) = "$account|"

        fun key(account: String, attachment: MediaAttachment) = prefix(account) + attachment.messageId + "/" + attachment.index
    }
}

private val TransferState.inFlight: Boolean
    get() = this == TransferState.Queued || this is TransferState.Downloading

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Downloads and uploads that outlive the screen that started them (media design 5.6, 5.10). Both run
 * over the SDK's live gateway session inside this process, so they last exactly as long as the unlocked
 * session: leaving a chat keeps them running, locking or closing the session cancels every one of them.
 * Nothing here survives process death; the SDK keeps import and prepare handles in memory too (R4).
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
    private val uploads = HashSet<Job>()
    private val uploading = MutableStateFlow(0)

    /** Transfer states by [key]; a settled entry stays until [forget] or the session closes. */
    val current: StateFlow<Map<String, TransferState>> = states.asStateFlow()

    /** True while at least one import or upload runs or waits; the foreground-service controller follows it. */
    val active: StateFlow<Boolean> = MutableStateFlow(false).also { flag ->
        scope.launch {
            combine(states, uploading) { map, sends -> sends > 0 || map.values.any { it.inFlight } }
                .distinctUntilChanged()
                .collect { flag.value = it }
        }
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

    /**
     * Sends [items] as one message in this queue's scope and waits for the result. A caller that goes
     * away (the chat closes) stops waiting, but the upload goes on until it completes or the session
     * closes; a retry under the same [operationId] lets the SDK reconcile it.
     */
    suspend fun upload(
        account: String,
        destination: MediaDestination,
        caption: String,
        items: List<StagedMedia>,
        operationId: String,
    ): ClientResult<SendReceipt> {
        val send = scope.async(start = CoroutineStart.LAZY) {
            uploading.update { it + 1 }
            try {
                media.send(account, destination, caption, items, operationId)
            } finally {
                uploading.update { it - 1 }
            }
        }
        synchronized(jobs) { uploads.add(send) }
        send.invokeOnCompletion { synchronized(jobs) { uploads.remove(send) } }
        send.start()
        return send.await()
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
            uploads.toList().forEach { it.cancel() }
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

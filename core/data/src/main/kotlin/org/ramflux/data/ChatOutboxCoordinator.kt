// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one process-wide owner of the chat outbox store and of the outcome of every outbox send. A send runs in [scope], not in
 * a screen's scope, so it and the bookkeeping of its refusal go on when the chat closes, the account locks or the screen is
 * recreated. A send refused with a pending identity change is marked blocked in the store before its result is returned to
 * anyone, and its operation is never sent again by this process: only an explicit user resend, under a new operation id,
 * sends it after the change was accepted.
 *
 * The [Mutex] serialises every store access of the process; it is never held across the send call itself.
 */
class ChatOutboxCoordinator(private val store: ChatOutboxRepository, private val scope: CoroutineScope) {
    private data class Key(val account: String, val conversation: String, val operation: String)

    private val guard = Mutex()
    private val refused = LinkedHashSet<Key>()
    private val inflight = HashMap<Key, Deferred<ClientResult<SendReceipt>>>()

    /**
     * The rows of the conversation under the lock. A row is blocked when it is stored blocked or its operation was refused in
     * this process. Blocked rows seed the refused set.
     */
    suspend fun load(account: String, conversation: String): List<QueuedSend> = guard.withLock {
        store.load(account, conversation).map { row ->
            val key = Key(account, conversation, row.operation)
            if (row.blocked) remember(key)
            if (!row.blocked && key in refused) row.copy(blocked = true) else row
        }
    }

    /**
     * Writes the screen's snapshot under the lock. The write is launched undispatched, so the order of the lock equals the
     * order of the calls. A row stays blocked when the stored row with the same id and operation is blocked, or its operation
     * was refused in this process: a stale unblocked snapshot never undoes a refusal.
     */
    fun persist(account: String, conversation: String, sends: List<QueuedSend>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                guard.withLock {
                    val stored = store.load(account, conversation)
                    val merged = sends.map { send ->
                        val blocked = send.blocked ||
                            stored.any { it.id == send.id && it.operation == send.operation && it.blocked } ||
                            Key(account, conversation, send.operation) in refused
                        if (blocked == send.blocked) send else send.copy(blocked = blocked)
                    }
                    store.save(account, conversation, merged)
                }
            }
        }
    }

    /**
     * Runs [call] in [scope] for the operation. A refused operation returns its refusal without calling; an operation in flight
     * joins the running call. A refusal result is recorded (refused set and stored flag) before this returns, for every caller
     * and even when the caller went away.
     */
    suspend fun send(
        account: String,
        conversation: String,
        id: String,
        operation: String,
        call: suspend () -> ClientResult<SendReceipt>
    ): ClientResult<SendReceipt> {
        val key = Key(account, conversation, operation)
        var fresh = false
        val running = guard.withLock {
            if (key in refused) return refusal()
            inflight[key] ?: scope.async(start = CoroutineStart.LAZY) { run(key, id, call) }.also {
                inflight[key] = it
                fresh = true
            }
        }
        if (fresh) running.start()
        return running.await()
    }

    /** Removes the stored sends of [account] and forgets its refused operations. */
    suspend fun clearAccount(account: String) {
        guard.withLock {
            store.clearAccount(account)
            refused.removeAll { it.account == account }
        }
    }

    private suspend fun run(key: Key, id: String, call: suspend () -> ClientResult<SendReceipt>): ClientResult<SendReceipt> {
        try {
            val result = call()
            if (result is ClientResult.Failed && SendBlock.of(result.problem) != null) {
                withContext(NonCancellable) {
                    guard.withLock {
                        remember(key)
                        val stored = store.load(key.account, key.conversation)
                        if (stored.any { it.id == id && it.operation == key.operation }) {
                            store.save(
                                key.account,
                                key.conversation,
                                stored.map { if (it.id == id && it.operation == key.operation) it.copy(blocked = true) else it }
                            )
                        }
                    }
                }
            }
            return result
        } finally {
            withContext(NonCancellable) { guard.withLock { inflight.remove(key) } }
        }
    }

    private fun remember(key: Key) {
        refused.remove(key)
        refused.add(key)
        while (refused.size > REFUSED_LIMIT) refused.remove(refused.first())
    }

    private fun refusal(): ClientResult<SendReceipt> = ClientResult.Failed(
        ClientProblem(ClientFailureKind.FAILED, REFUSAL_CODE, ClientOutcome.REFUSED, statusName = SendBlock.IDENTITY_CHANGE_PENDING)
    )

    private companion object {
        const val REFUSED_LIMIT = 512
        const val REFUSAL_CODE = 4
    }
}

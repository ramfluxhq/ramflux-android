// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The one-shot events an MLS screen's view model sends to its screen. A Back for a lock ([lockBack]) goes only to a screen that
 * is collecting at that moment: a view model whose screen is not shown queues nothing, so the screen is not closed by an old lock
 * when it is opened again after the unlock (its state was already dropped with the lock). [reopened] drops whatever an earlier
 * display left queued, so a new open starts with no stale event.
 */
class MlsScreenEvents<E> {
    private val channel = Channel<E>(Channel.BUFFERED)
    private val collecting = AtomicInteger()

    val flow: Flow<E> = channel.receiveAsFlow()
        .onStart { collecting.incrementAndGet() }
        .onCompletion { collecting.decrementAndGet() }

    /** Whether a screen is collecting the events now, that is, whether the screen is shown. */
    val attached: Boolean get() = collecting.get() > 0

    fun send(event: E) {
        channel.trySend(event)
    }

    /** Sends [back] for a lock only while the screen collects; otherwise nothing is kept. */
    fun lockBack(back: E) {
        if (attached) channel.trySend(back)
    }

    /** A new open of the screen: events an earlier display did not take are dropped. */
    fun reopened() {
        while (channel.tryReceive().isSuccess) Unit
    }
}

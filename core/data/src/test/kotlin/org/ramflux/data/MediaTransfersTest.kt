// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaTransfersTest {
    private class GatedMedia : MediaRepository by UnavailableMediaRepository() {
        val gates = HashMap<String, CompletableDeferred<TransferState>>()
        var running = 0
        var peak = 0
        override suspend fun download(account: String, attachment: MediaAttachment, progress: (TransferState) -> Unit): TransferState {
            running++
            peak = maxOf(peak, running)
            progress(TransferState.Downloading(null))
            try {
                return gates.getOrPut(attachment.messageId) { CompletableDeferred() }.await()
            } finally {
                running--
            }
        }
    }

    private class Session : ClientSessionRepository {
        override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
        override suspend fun foreground() = Unit
        override suspend fun background() = Unit
        override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
    }

    private fun item(id: String) = MediaAttachment(id, 0, MediaKind.IMAGE, "image/jpeg", "x.jpg", 1)

    @Test fun atMostThreeImportsRunPerAccountAndTheRestWaitInOrder() = runTest(timeout = 5.seconds) {
        val media = GatedMedia()
        val queue = MediaTransfers(media, Session(), backgroundScope)
        (1..5).forEach { queue.download("a", item("m$it")) }
        runCurrent()
        assertEquals(3, media.running)
        assertTrue(queue.active.value)
        media.gates.getValue("m1").complete(TransferState.Ready(File("/tmp/m1")))
        runCurrent()
        assertEquals(3, media.running)
        assertEquals(3, media.peak)
        val ready = queue.current.value[MediaTransfers.key("a", item("m1"))]
        assertTrue(ready is TransferState.Ready)
    }

    @Test fun closingTheSessionCancelsEveryTransfer() = runTest(timeout = 5.seconds) {
        val media = GatedMedia()
        val session = Session()
        val queue = MediaTransfers(media, session, backgroundScope)
        queue.download("a", item("m1"))
        queue.download("b", item("m2"))
        runCurrent()
        assertEquals(2, media.running)
        session.status.value = ClientSnapshot(ClientPhase.CLOSED, 1)
        runCurrent()
        assertEquals(0, media.running)
        assertTrue(queue.current.value.isEmpty())
        assertFalse(queue.active.value)
    }
}

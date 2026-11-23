// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decodes an unpaged list reply (a whole snapshot in one frame) off the caller's thread.
 *
 * The parse is bounded by [ByteJson] (frame bytes, depth, values). Rows are then mapped in chunks of
 * [CHUNK] with a cancellation check between chunks, so a superseded load stops early instead of
 * finishing a several-thousand-row snapshot. The reply bytes are borrowed once and wiped afterwards,
 * including on failure and cancellation.
 */
internal object ListReplyDecoder {
    const val CHUNK = 256

    /** Mapping helper handed to a reader; [map] is the only way rows are turned into display values. */
    class Rows internal constructor(private val job: Job) {
        fun <T> map(rows: List<ByteJson.Node>, transform: (ByteJson.Node) -> T): List<T> {
            val out = ArrayList<T>(rows.size)
            for (index in rows.indices) {
                if (index % CHUNK == 0) job.ensureActive()
                out.add(transform(rows[index]))
            }
            return out
        }
    }

    suspend fun <T> decode(
        reply: SecretReply,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        block: (DisplayFields, Rows) -> T,
    ): CoreResult<T> = try {
        withContext(dispatcher) {
            val rows = Rows(this.coroutineContext.job)
            reply.useOnce { CoreResult.Success(block(DisplayFields(it), rows)) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CoreResult.Failure(CoreError.InvalidResponse())
    } finally { reply.close() }
}

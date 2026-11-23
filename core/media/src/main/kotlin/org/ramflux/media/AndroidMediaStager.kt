// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ramflux.data.MediaRepository
import org.ramflux.data.MediaStager
import org.ramflux.data.StageRefusal
import org.ramflux.data.StageResult
import org.ramflux.data.StagedMedia

/** Copies picker results into the account outbox at once, because content URIs are not durable. */
class AndroidMediaStager(context: Context, private val media: MediaRepository) : MediaStager {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    override suspend fun visual(account: String, uri: String, progress: (Float) -> Unit): StageResult {
        val outbox = media.outbox(account) ?: return StageResult.Refused(StageRefusal.UNREADABLE)
        val parsed = Uri.parse(uri)
        val mime = withContext(Dispatchers.IO) { resolver.getType(parsed) }.orEmpty()
        val staged = when {
            mime.startsWith("video/") -> return when (val outcome = MediaVideo.transcode(app, parsed, outbox, progress)) {
                is VideoOutcome.Staged -> StageResult.Staged(outcome.media)
                VideoOutcome.TooLong -> StageResult.Refused(StageRefusal.VIDEO_TOO_LONG)
                VideoOutcome.TooLarge -> StageResult.Refused(StageRefusal.VIDEO_TOO_LARGE)
                VideoOutcome.Failed -> StageResult.Refused(StageRefusal.UNREADABLE)
            }
            // An animation over the cap is sent as a file, byte for byte, as the design asks.
            MediaNormalizer.animated(resolver, parsed) ->
                MediaNormalizer.animatedCopy(resolver, parsed, outbox) ?: MediaNormalizer.file(resolver, parsed, outbox)
            else -> MediaNormalizer.image(resolver, parsed, outbox)
        }
        return staged?.let { StageResult.Staged(it) } ?: StageResult.Refused(StageRefusal.UNREADABLE)
    }

    override suspend fun image(account: String, uri: String): StagedMedia? {
        val outbox = media.outbox(account) ?: return null
        return MediaNormalizer.image(resolver, Uri.parse(uri), outbox)
    }

    override suspend fun file(account: String, uri: String): StagedMedia? {
        val outbox = media.outbox(account) ?: return null
        return MediaNormalizer.file(resolver, Uri.parse(uri), outbox)
    }

    /** The camera wrote EXIF (possibly with location); only the re-encoded pixels leave, the capture is removed. */
    override suspend fun camera(account: String, uri: String): StagedMedia? {
        val parsed = Uri.parse(uri)
        return try {
            image(account, uri)
        } finally {
            withContext(Dispatchers.IO) { runCatching { resolver.delete(parsed, null, null) } }
        }
    }

    override fun dropMemory() = MediaImages.clear()
}

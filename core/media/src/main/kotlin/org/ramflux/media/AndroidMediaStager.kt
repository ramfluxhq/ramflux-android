// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ramflux.data.MediaRepository
import org.ramflux.data.MediaStager
import org.ramflux.data.StagedMedia

/** Copies picker results into the account outbox at once, because content URIs are not durable. */
class AndroidMediaStager(context: Context, private val media: MediaRepository) : MediaStager {
    private val resolver = context.applicationContext.contentResolver

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

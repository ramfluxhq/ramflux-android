// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ramflux.data.StagedMedia

/** Size, rotation and duration read from a video container, never from the declared type. */
data class VideoProbe(val width: Int, val height: Int, val rotation: Int, val durationMs: Long) {
    /** The long edge of the picture as shown (rotation applied). */
    val longEdge: Int get() = max(width, height)
}

/** What happened to one picked video (owner decision 9: 1,280 px, 10 minutes, 200 MB). */
sealed interface VideoOutcome {
    data class Staged(val media: StagedMedia) : VideoOutcome
    data object TooLong : VideoOutcome
    data object TooLarge : VideoOutcome
    data object Failed : VideoOutcome
}

/**
 * On-device video (media design 4.3, 5.4): Media3 Transformer writes MP4 with H.264 and AAC, the long
 * edge at most [MAX_EDGE] and about [BITRATE] bit/s, with every container metadata entry dropped
 * (location, creation tags). One transcode at a time. Poster frames and durations are derived from the
 * file on this device; the message cannot carry them yet (requests R1, R2).
 */
@OptIn(UnstableApi::class)
object MediaVideo {
    const val MAX_EDGE = 1_280
    const val MAX_DURATION_MS = 10 * 60 * 1_000L
    const val MAX_BYTES = 200L * 1024 * 1024
    const val BITRATE = 2_500_000
    private const val PROGRESS_MS = 250L

    private val transcodes = Mutex()

    fun probe(context: Context, uri: Uri): VideoProbe? = retrieve { it.setDataSource(context, uri) }

    fun probe(file: File): VideoProbe? = retrieve { it.setDataSource(file.path) }

    /** The target picture size: the source size scaled so its long edge fits [MAX_EDGE], never upscaled. */
    fun targetSize(probe: VideoProbe): Pair<Int, Int>? {
        if (probe.longEdge <= MAX_EDGE) return null
        val scale = MAX_EDGE.toFloat() / probe.longEdge
        val shown = if (probe.rotation % 180 == 0) probe.width to probe.height else probe.height to probe.width
        return even(shown.first * scale) to even(shown.second * scale)
    }

    suspend fun transcode(context: Context, uri: Uri, outbox: File, progress: (Float) -> Unit, now: Date = Date()): VideoOutcome =
        transcodes.withLock {
            val probe = withContext(Dispatchers.IO) { probe(context, uri) } ?: return@withLock VideoOutcome.Failed
            if (probe.durationMs > MAX_DURATION_MS) return@withLock VideoOutcome.TooLong
            outbox.mkdirs()
            val output = File.createTempFile("stage", ".mp4", outbox)
            val done = try {
                export(context, uri, probe, output, progress)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                output.delete()
                throw cancelled
            }
            when {
                !done -> {
                    output.delete()
                    VideoOutcome.Failed
                }
                output.length() > MAX_BYTES -> {
                    output.delete()
                    VideoOutcome.TooLarge
                }
                else -> {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(now)
                    VideoOutcome.Staged(StagedMedia(output, "VID_$stamp.mp4", MimeTypes.VIDEO_MP4))
                }
            }
        }

    private suspend fun export(context: Context, uri: Uri, probe: VideoProbe, output: File, progress: (Float) -> Unit): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val effects = targetSize(probe)?.let { (width, height) ->
                    Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)))
                } ?: Effects.EMPTY
                val encoders = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(BITRATE).build())
                    .build()
                // No container metadata survives: location, XMP and creation tags of the source are removed.
                val muxer = InAppMp4Muxer.Factory { entries: MutableSet<Metadata.Entry> -> entries.clear() }
                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoders)
                    .setMuxerFactory(muxer)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    })
                    .build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
                transformer.start(item, output.path)
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (!continuation.isActive) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f)
                        handler.postDelayed(this, PROGRESS_MS)
                    }
                }
                handler.post(poll)
                continuation.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(poll)
                        transformer.cancel()
                    }
                }
            }
        }

    /** A poster frame of a decrypted local video at [targetPx] on the long edge, cached with the images. */
    suspend fun poster(file: File, targetPx: Int): ImageBitmap? {
        val key = "poster:" + file.path + "#" + file.lastModified() + "@" + targetPx
        MediaImages.cached(key)?.let { return it.asImageBitmap() }
        val bitmap = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val width = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (width <= 0 || height <= 0) return@withContext null
                val scale = (targetPx.toFloat() / max(width, height)).coerceAtMost(1f)
                retriever.getScaledFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    max(1, (width * scale).roundToInt()),
                    max(1, (height * scale).roundToInt())
                )?.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        } ?: return null
        MediaImages.store(key, bitmap)
        return bitmap.asImageBitmap()
    }

    private fun retrieve(source: (MediaMetadataRetriever) -> Unit): VideoProbe? {
        val retriever = MediaMetadataRetriever()
        return try {
            source(retriever)
            val width = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            if (width <= 0 || height <= 0 || duration < 0) {
                null
            } else {
                VideoProbe(width, height, retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), duration)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.number(key: Int) = extractMetadata(key)?.toIntOrNull() ?: 0

    private fun even(value: Float) = max(2, (value / 2).roundToInt() * 2)
}

/** The poster of a local video; null until decoded or when the file is not a playable video. */
@Composable
fun rememberVideoPoster(path: String?, targetPx: Int): ImageBitmap? = produceState<ImageBitmap?>(null, path, targetPx) {
    value = path?.let { MediaVideo.poster(File(it), targetPx) }
}.value

/** The duration of a local video in milliseconds, read off the main thread; null until known. */
@Composable
fun rememberVideoDuration(path: String?): Long? = produceState<Long?>(null, path) {
    value = path?.let { withContext(Dispatchers.IO) { MediaVideo.probe(File(it))?.durationMs } }
}.value

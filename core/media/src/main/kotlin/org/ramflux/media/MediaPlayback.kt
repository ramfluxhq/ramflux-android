// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Whether animations may start by themselves (media design 4.2, 8). */
object MediaMotion {
    /** Removed animations (animator scale 0) or battery saver keep animated images on their first frame. */
    fun autoplay(animatorScale: Float, powerSave: Boolean): Boolean = animatorScale > 0f && !powerSave

    /** Animated images playing at once in the timeline. */
    const val MAX_PLAYING = 2

    /** The share of a bubble that must be on screen before it plays. */
    const val VISIBLE_SHARE = .5f
}

/** Reads the system animator scale and battery saver, and follows battery saver changes while shown. */
@Composable
fun rememberAutoplayAllowed(): Boolean {
    val context = LocalContext.current
    var powerSave by remember { mutableStateOf(context.powerSave()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                powerSave = context.powerSave()
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    val scale = remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
    return MediaMotion.autoplay(scale, powerSave)
}

private fun Context.powerSave() = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

/** At most [MediaMotion.MAX_PLAYING] animated images hold a slot; the rest show their first frame. */
@Stable
class AnimationSlots(private val limit: Int = MediaMotion.MAX_PLAYING) {
    private val active = mutableStateListOf<String>()

    fun holds(key: String, wanted: Boolean): Boolean {
        if (!wanted) {
            active.remove(key)
            return false
        }
        if (key in active) return true
        if (active.size >= limit) return false
        active.add(key)
        return true
    }

    fun release(key: String) {
        active.remove(key)
    }
}

val LocalAnimationSlots = staticCompositionLocalOf { AnimationSlots() }

/**
 * A decrypted GIF or animated WebP drawn through `AnimatedImageDrawable`, decoded to [targetPx] off the
 * main thread with the same header checks as still images. It runs only while [play] is true and a slot
 * is free; a still image or a decode failure draws the first frame or nothing.
 */
@Composable
fun MediaAnimatedImage(path: String?, key: String, targetPx: Int, play: Boolean, modifier: Modifier = Modifier) {
    val drawable = produceState<Drawable?>(null, path, targetPx) { value = path?.let { MediaAnimations.decode(File(it), targetPx) } }.value
    val slots = LocalAnimationSlots.current
    val running = slots.holds(key, play && drawable is AnimatedImageDrawable)
    var frame by remember { mutableIntStateOf(0) }
    DisposableEffect(drawable, running) {
        val animated = drawable as? AnimatedImageDrawable
        animated?.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                frame++
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        if (running) animated?.start() else animated?.stop()
        onDispose {
            animated?.stop()
            animated?.callback = null
            slots.release(key)
        }
    }
    Box(
        modifier.clipToBounds().drawBehind {
            // Reading the frame counter redraws the box on every animation frame.
            if (frame < 0) return@drawBehind
            val current = drawable ?: return@drawBehind
            val width = current.intrinsicWidth.coerceAtLeast(1)
            val height = current.intrinsicHeight.coerceAtLeast(1)
            // Fill the box from the centre, as the still images do.
            val scale = max(size.width / width, size.height / height)
            val drawnWidth = (width * scale).toInt()
            val drawnHeight = (height * scale).toInt()
            val left = ((size.width - drawnWidth) / 2).toInt()
            val top = ((size.height - drawnHeight) / 2).toInt()
            current.setBounds(left, top, left + drawnWidth, top + drawnHeight)
            drawIntoCanvas { current.draw(it.nativeCanvas) }
        }
    )
}

internal object MediaAnimations {
    private val decoders = Semaphore(1)

    suspend fun decode(file: File, targetPx: Int): Drawable? = decoders.withPermit {
        withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    require(width > 0 && height > 0 && width.toLong() * height <= MediaImages.MAX_PIXELS)
                    val scale = targetPx.toFloat() / max(width, height)
                    if (scale < 1f) decoder.setTargetSize(max(1, (width * scale).toInt()), max(1, (height * scale).toInt()))
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** One local video player; the app shows at most one, in the viewer (media design 5.8). */
@Stable
class LocalVideoPlayer internal constructor(internal val player: ExoPlayer) {
    var playing by mutableStateOf(false)
        internal set
    var muted by mutableStateOf(false)
        internal set
    var positionMs by mutableLongStateOf(0L)
        internal set
    var durationMs by mutableLongStateOf(0L)
        internal set
    var failed by mutableStateOf(false)
        internal set

    fun toggle() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceIn(0L, max(0L, durationMs)))
        this.positionMs = player.currentPosition
    }
}

/**
 * A player for a decrypted app-private file. Nothing streams: the SDK has no range reads, so the file is
 * complete before it plays. It never starts by itself and is released when it leaves the composition.
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberLocalVideoPlayer(path: String, muted: Boolean = false): LocalVideoPlayer {
    val context = LocalContext.current
    val holder = remember(path) {
        val player = ExoPlayer.Builder(context.applicationContext).build()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        player.playWhenReady = false
        player.volume = if (muted) 0f else 1f
        player.prepare()
        LocalVideoPlayer(player).also { it.muted = muted }
    }
    DisposableEffect(holder) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                holder.playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) holder.durationMs = holder.player.duration.coerceAtLeast(0L)
            }

            override fun onPlayerError(error: PlaybackException) {
                holder.failed = true
            }
        }
        holder.player.addListener(listener)
        onDispose {
            holder.player.removeListener(listener)
            holder.player.release()
        }
    }
    LaunchedEffect(holder, holder.playing) {
        while (holder.playing) {
            holder.positionMs = holder.player.currentPosition
            delay(POSITION_MS)
        }
        holder.positionMs = holder.player.currentPosition
    }
    return holder
}

private const val POSITION_MS = 500L

/** The picture of [player]; a SurfaceView inherits the window's secure flag. */
@OptIn(UnstableApi::class)
@Composable
fun MediaVideoSurface(player: LocalVideoPlayer, modifier: Modifier = Modifier) {
    PlayerSurface(player.player, modifier)
}

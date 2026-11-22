// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import org.ramflux.data.CallRecord

/** Why the media engine cannot carry a call. */
enum class CallMediaRefusal {
    /** The SDK has no audio or video engine yet (request R28): `call.media.session` returns only relay credentials and SFrame keys. */
    NO_ENGINE
}

sealed interface CallMediaResult {
    data object Started : CallMediaResult
    data class Refused(val reason: CallMediaRefusal) : CallMediaResult
}

/**
 * The audio and video engine boundary. An engine receives the live call and would fetch
 * `call.media.session` itself, so relay credentials and SFrame keys never pass through UI state.
 */
interface CallMediaEngine {
    suspend fun start(account: String, call: CallRecord, video: Boolean): CallMediaResult
    fun setMuted(muted: Boolean)
    fun setSpeaker(on: Boolean)
    fun switchCamera()
    fun stop()
}

/**
 * The engine on this build: it refuses before any media session is requested, so no keys are fetched
 * that nothing could use, and the call screen says honestly that audio and video are unavailable.
 */
class RefusingCallMediaEngine : CallMediaEngine {
    override suspend fun start(account: String, call: CallRecord, video: Boolean): CallMediaResult =
        CallMediaResult.Refused(CallMediaRefusal.NO_ENGINE)
    override fun setMuted(muted: Boolean) = Unit
    override fun setSpeaker(on: Boolean) = Unit
    override fun switchCamera() = Unit
    override fun stop() = Unit
}

/**
 * Audio focus and routing for a call, for an engine to use once it plays audio: voice-communication
 * focus, communication mode, and speaker or earpiece through the communication-device API (Android 12+)
 * or the speakerphone flag before it.
 */
class CallAudio(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()

    fun acquire(): Boolean {
        val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) audio.mode = AudioManager.MODE_IN_COMMUNICATION
        return granted
    }

    fun speaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            audio.availableCommunicationDevices.firstOrNull { it.type == wanted }?.let { audio.setCommunicationDevice(it) }
        } else {
            // The communication-device API exists from Android 12; earlier versions only have this flag.
            audio.isSpeakerphoneOn = on
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
        audio.abandonAudioFocusRequest(focus)
    }
}

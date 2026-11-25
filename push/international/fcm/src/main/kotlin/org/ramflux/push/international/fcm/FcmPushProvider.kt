// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.fcm

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.ramflux.push.api.DeliveryChannel
import org.ramflux.push.api.ProviderState
import org.ramflux.push.api.PushProvider
import org.ramflux.push.api.PushToken
import org.ramflux.push.api.UnavailableReason

/**
 * Firebase Cloud Messaging. Firebase is started here, by hand, and only when a configuration for this application id
 * is compiled in; otherwise the state is NOT_CONFIGURED and nothing from Firebase runs. The token is returned to the
 * caller in memory only: it is never logged or stored, and only the count and time of failed attempts are kept.
 */
@Singleton
class FcmPushProvider @Inject constructor(@ApplicationContext private val context: Context) : PushProvider {
    override val channel: DeliveryChannel = DeliveryChannel.FCM
    private val client = FcmConfig.find(context.packageName)

    // Declared before the state, which reads it while it is computed.
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val local = MutableStateFlow(evaluate())
    override val state: StateFlow<ProviderState> = local.asStateFlow()

    override fun refreshState() {
        local.value = evaluate()
    }

    override suspend fun token(): PushToken? {
        refreshState()
        if (state.value != ProviderState.Ready) return null
        val messaging = messaging() ?: return null
        return try {
            val value = messaging.token.await()
            recordSuccess()
            if (value.isNullOrEmpty()) null else PushToken(value).also { traceObtained(value.length) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            null
        }
    }

    override suspend fun rotate(): PushToken? {
        delete()
        return token()
    }

    override suspend fun delete() {
        val messaging = messaging() ?: return
        try {
            messaging.deleteToken().await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The token dies at the provider anyway when it is replaced; nothing to report.
        }
    }

    private fun evaluate(): ProviderState {
        if (client == null) return ProviderState.Unavailable(UnavailableReason.NOT_CONFIGURED)
        PlayServicesStatus.check(context)?.let { return ProviderState.Unavailable(it) }
        return if (tokenKeepsFailing()) ProviderState.Unavailable(UnavailableReason.TOKEN_FAILED) else ProviderState.Ready
    }

    private fun messaging(): FirebaseMessaging? {
        val config = client ?: return null
        return try {
            val app = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(config.appId)
                    .setApiKey(config.apiKey)
                    .setProjectId(config.projectId)
                    .setGcmSenderId(config.senderId)
                    .build(),
                APP_NAME
            )
            app.get(FirebaseMessaging::class.java)
        } catch (_: IllegalStateException) {
            null
        }
    }

    /** Debug builds only: that a token was obtained and how long it is, never the token itself. */
    private fun traceObtained(length: Int) {
        if (!BuildConfig.DEBUG) return
        val line = buildString {
            append("push.token.obtained")
            append(' ')
            append("len")
            append('=')
            append(length)
        }
        Log.d(TRACE_TAG, line)
    }

    private fun tokenKeepsFailing(): Boolean {
        val count = preferences.getInt(KEY_FAIL_COUNT, 0)
        val first = preferences.getLong(KEY_FAIL_FIRST, 0L)
        return count >= FAILURES_BEFORE_REPORT && first > 0L && System.currentTimeMillis() - first >= FAILURE_WINDOW_MS
    }

    private fun recordFailure() {
        val first = preferences.getLong(KEY_FAIL_FIRST, 0L)
        preferences.edit {
            putInt(KEY_FAIL_COUNT, preferences.getInt(KEY_FAIL_COUNT, 0) + 1)
            putLong(KEY_FAIL_FIRST, if (first > 0L) first else System.currentTimeMillis())
        }
        refreshState()
    }

    private fun recordSuccess() {
        preferences.edit {
            remove(KEY_FAIL_COUNT)
            remove(KEY_FAIL_FIRST)
        }
    }

    private companion object {
        const val APP_NAME = "ramflux"
        const val TRACE_TAG = "RamfluxPush"
        const val PREFERENCES = "push-fcm"
        const val KEY_FAIL_COUNT = "token.fail.count"
        const val KEY_FAIL_FIRST = "token.fail.first"
        const val FAILURES_BEFORE_REPORT = 3
        const val FAILURE_WINDOW_MS = 24L * 60 * 60 * 1000
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException())
        }
    }
}

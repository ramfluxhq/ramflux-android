// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.*
import org.ramflux.feature.calls.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1400dp-mdpi")
class CallsScreenshotTest(private val kind: String, private val dark: Boolean, private val locale: String, private val width: Int) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}-{3}")
        fun cases(): List<Array<Any>> = buildList {
            val kinds =
                listOf(
                    "history", "incoming", "outgoing", "connecting", "active", "ended", "error",
                    "loading", "account", "unavailable", "pending"
                )
            for (kind in kinds) for (dark in listOf(
                false,
                true
            )) for (locale in listOf("en", "zh-CN", "en-XA")) for (width in listOf(400, 900)) add(arrayOf(kind, dark, locale, width))
        }
    }

    @Test fun screen() {
        val row = callItem().copy(
            incoming = kind == "incoming",
            hasAudioControls = kind in setOf("connecting", "active"),
            canDecline = kind == "incoming",
            canHangup = kind != "ended",
            phase = uiText(
                when (kind) {
                    "outgoing" -> R.string.calls_outgoing_ringing
                    "connecting" -> R.string.calls_connecting
                    "active" -> R.string.calls_active
                    "ended" -> R.string.calls_ended
                    else -> R.string.calls_incoming_ringing
                }
            )
        )
        val base = CallsState(account = "Work", rows = persistentListOf(row), selected = row, loaded = true, fresh = true)
        val state = when (kind) {
            "history" -> base.copy(selected = null)
            "error" -> CallsState(problem = uiText(R.string.calls_error_code, UiText.Number(8)))
            "loading" -> CallsState(loading = true)
            "account" -> CallsState(accountRequired = true)
            "unavailable" -> base.copy(unavailable = uiText(R.string.calls_audio_unavailable))
            "pending" -> base.copy(pending = "operation-id", fresh = false)
            else -> base
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, if (width == 900) 1000.dp else 600.dp))
                ) {
                    RamfluxTheme(dark) { CallsScreen(state, {}) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/calls-$kind-$dark-$locale-$width.png")
    }
}

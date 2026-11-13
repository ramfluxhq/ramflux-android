// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.*
import org.ramflux.feature.settings.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
class SettingsScreenshotTest(
    private val page: SettingsPage,
    private val dark: Boolean,
    private val locale: String,
    private val width: Int,
    private val kind: String
) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}-{2}-{3}-{4}")
        fun cases(): List<Array<Any>> = buildList {
            for (page in SettingsPage.entries) for (dark in listOf(false, true))
                for (locale in listOf("en", "zh-CN", "en-XA")) for (width in listOf(400, 900)) {
                    add(arrayOf(page, dark, locale, width, "populated"))
                }
            for (kind in listOf("loading", "error", "account")) for (dark in listOf(false, true))
                for (locale in listOf("en", "zh-CN", "en-XA")) for (width in listOf(400, 900)) {
                    add(arrayOf(SettingsPage.GENERAL, dark, locale, width, kind))
                }
        }
    }

    @Test fun screen() {
        val populated = SettingsState(
            page = page,
            accountLabel = "Work",
            selectedTheme = if (dark) "dark" else "light",
            devices = persistentListOf(SettingsDevice("local-device", true), SettingsDevice("other-device", false)),
            devicesLoaded = true,
            status = SettingsStatus(
                uiText(R.string.settings_session_ready),
                uiText(R.string.settings_not_recorded),
                uiText(R.string.settings_not_recorded),
                uiText(R.string.settings_not_recorded)
            ),
            unavailable = if (page == SettingsPage.BACKUP) uiText(R.string.settings_custody_missing) else null
        )
        val state = when (kind) {
            "loading" -> SettingsState(loading = true)
            "error" -> SettingsState(problem = uiText(R.string.settings_read_failed, UiText.Number(8)))
            "account" -> SettingsState(accountRequired = true)
            else -> populated
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 500.dp))) {
                    RamfluxTheme(dark) { SettingsScreen(state, {}) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings-${page.name}-$dark-$locale-$width-$kind.png")
    }
}

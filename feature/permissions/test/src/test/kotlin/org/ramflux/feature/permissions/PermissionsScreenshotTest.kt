// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.toPersistentList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.ramflux.designsystem.*
import org.ramflux.feature.permissions.api.PermissionEntry
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
class PermissionsScreenshotTest(private val kind: String, private val dark: Boolean, private val locale: String, private val width: Int) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}-{3}")
        fun cases(): List<Array<Any>> = buildList {
            for (kind in listOf(
                "settings", "notification", "camera", "battery", "denied",
                "media", "attachments", "selected", "error", "loading"
            )) {
                for (dark in listOf(false, true)) for (locale in listOf("en", "zh-CN", "en-XA")) for (width in listOf(400, 900)) {
                    add(arrayOf(kind, dark, locale, width))
                }
            }
        }
    }

    @Test fun screen() {
        val state = PermissionsState(
            loading = kind == "loading",
            failed = kind == "error",
            needed = kind == "denied",
            unavailable = kind == "media",
            selected = kind == "selected",
            entry = if (kind in setOf("attachments", "selected")) PermissionEntry.ATTACHMENTS else PermissionEntry.SETTINGS,
            explanation = when (kind) {
                "notification" -> OsPermission.NOTIFICATIONS
                "camera" -> OsPermission.CAMERA
                "battery" -> OsPermission.BATTERY
                else -> null
            },
            rows = OsPermission.entries.map {
                OsPermissionRow(
                    it,
                    when (it) {
                        OsPermission.NOTIFICATIONS -> OsPermissionStatus.DENIED
                        OsPermission.MICROPHONE -> OsPermissionStatus.NOT_ASKED
                        OsPermission.SAVE_PHOTO, OsPermission.PHOTOS_FILES -> OsPermissionStatus.NOT_REQUIRED
                        else -> OsPermissionStatus.ALLOWED
                    }
                )
            }.toPersistentList()
        )
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 600.dp))) {
                AppLanguageProvider(locale) { RamfluxTheme(dark) { PermissionsScreen(state, {}) } }
            }
        }
        val roots = compose.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().lastIndex].captureRoboImage("build/outputs/roborazzi/permissions-$kind-$dark-$locale-$width.png")
    }
}

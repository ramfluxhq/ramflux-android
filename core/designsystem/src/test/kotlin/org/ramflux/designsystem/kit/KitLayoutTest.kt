// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
class KitLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun primaryButtonMatchesTheMasterFrame() = frame("button", 361.dp) {
        KitButton("\u6309\u94ae", {}, tag = "button")
    }.also {
        compose.onNodeWithTag("button").assertWidthIsEqualTo(361.dp).assertHeightIsEqualTo(52.dp)
    }

    @Test fun textButtonIsTheCompactHeight() = frame("text-button", 361.dp) {
        KitButton("\u6587\u5b57\u6309\u94ae", {}, kind = KitButtonKind.Text, tag = "text-button")
    }.also {
        compose.onNodeWithTag("text-button").assertHeightIsEqualTo(44.dp)
    }

    @Test fun disabledPrimaryUsesTheDesignedFrame() = frame("disabled", 361.dp) {
        KitButton("\u6309\u94ae", {}, kind = KitButtonKind.Disabled, tag = "disabled")
    }.also {
        compose.onNodeWithTag("disabled").assertHeightIsEqualTo(52.dp)
    }

    @Test fun avatarScaleKeepsTheDrawnSize() {
        val sizes = listOf(18, 28, 36, 44, 52, 72)
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column {
                    sizes.forEach { size -> KitAvatar("A", size.toFloat(), KitAvatarKind.Person, tag = "avatar-$size") }
                }
            }
        }
        sizes.forEach { size ->
            compose.onNodeWithTag("avatar-$size").assertWidthIsEqualTo(size.dp).assertHeightIsEqualTo(size.dp)
        }
    }

    @Test fun switchAndCheckboxKeepTheirDrawnSize() {
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column {
                    KitSwitch(true, {}, tag = "switch")
                    KitCheckbox(true, {}, tag = "check")
                }
            }
        }
        compose.onNodeWithTag("switch", useUnmergedTree = true).assertWidthIsEqualTo(51.dp).assertHeightIsEqualTo(31.dp)
        compose.onNodeWithTag("check", useUnmergedTree = true).assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
    }

    @Test fun chromeFramesMatchTheMasters() {
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column {
                    KitStatusBar("9:41", tag = "status")
                    KitPageHeader("\u6807\u9898", onBack = {}, tag = "header")
                    KitTabBar(
                        listOf(
                            KitTab("messages-square", "\u6d88\u606f", badge = "1"),
                            KitTab("users-round", "\u7fa4\u7ec4"),
                            KitTab("bot", "Agents", latin = true),
                            KitTab("ellipsis", "\u66f4\u591a")
                        ),
                        0,
                        {},
                        tag = "tabs"
                    )
                    KitHomeIndicator(tag = "home")
                }
            }
        }
        compose.onNodeWithTag("status").assertWidthIsEqualTo(393.dp).assertHeightIsEqualTo(59.dp)
        compose.onNodeWithTag("header").assertHeightIsEqualTo(60.dp)
        compose.onNodeWithTag("tabs").assertHeightIsEqualTo(83.dp)
        compose.onNodeWithTag("home").assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(4.dp)
    }

    @Test fun searchRowAndSegmentedMatchTheMasters() = frame("search", 361.dp) {
        KitSearchField("", {}, "\u641c\u7d22", tag = "search")
        KitRow("\u6807\u7b7e", {}, icon = "user-round", testTag = "row")
        KitSegmented(listOf("A", "B"), 0, {}, tag = "segments")
    }.also {
        compose.onNodeWithTag("search").assertHeightIsEqualTo(36.dp)
        compose.onNodeWithTag("row").assertHeightIsEqualTo(58.dp)
        compose.onNodeWithTag("segments").assertHeightIsEqualTo(36.dp)
    }

    @Test fun qrTileStaysTheDesignSquare() {
        compose.setContent { RamfluxTheme(dark = true) { KitQrPlaceholder(tag = "qr") } }
        compose.onNodeWithTag("qr").assertWidthIsEqualTo(200.dp).assertHeightIsEqualTo(200.dp)
    }

    private fun frame(tag: String, width: androidx.compose.ui.unit.Dp, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            RamfluxTheme(dark = false) { Box(Modifier.width(width)) { content() } }
        }
    }
}

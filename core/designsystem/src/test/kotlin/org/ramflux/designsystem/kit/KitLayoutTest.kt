// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
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

    @get:Rule val deadline = KitDeadline(30)

    @Test fun primaryButtonMatchesTheMasterFrame() = frame("button", 361.dp) {
        KitButton("\u6309\u94ae", {}, tag = "button")
    }.also {
        compose.onNodeWithTag("button").assertWidthIsEqualTo(361.dp).assertHeightIsEqualTo(52.dp)
    }

    @Test fun textButtonIsTheCompactHeight() = frame("text-button", 361.dp) {
        KitButton("\u6587\u5b57\u6309\u94ae", {}, kind = KitButtonKind.Text, tag = "text-button")
    }.also {
        compose.onNodeWithTag("text-button", useUnmergedTree = true).assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag("text-button-hit", useUnmergedTree = true).assertHeightIsEqualTo(48.dp)
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
                        bottomInset = KIT_FRAME_INSET,
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

    @Test fun hitFramesStayFortyEightWithoutChangingTheDrawnControl() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column {
                    KitInfoButton({}, tag = "info")
                    KitPageHeader("Title", onBack = {}, tag = "header")
                    KitChatHeader("Title", null, { KitAvatar("A", 36f, KitAvatarKind.Person) }, {}, tag = "chat")
                    KitTabBar(listOf(KitTab("messages-square", "Messages")), 0, {}, bottomInset = KIT_FRAME_INSET, tag = "tabs")
                    KitContextMenu(listOf(KitMenuEntry("Reply", "reply")), {}, tag = "menu")
                }
            }
        }
        compose.onNodeWithTag("info", useUnmergedTree = true).assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag("info-hit", useUnmergedTree = true).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("header", useUnmergedTree = true).assertHeightIsEqualTo(60.dp)
        compose.onNodeWithTag("header-back", useUnmergedTree = true).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("chat", useUnmergedTree = true).assertHeightIsEqualTo(60.dp)
        compose.onNodeWithTag("chat-back", useUnmergedTree = true).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("tabs", useUnmergedTree = true).assertHeightIsEqualTo(83.dp)
        compose.onNodeWithTag("tabs-0", useUnmergedTree = true).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("menu-hit-0-0", useUnmergedTree = true).assertHeightIsEqualTo(48.dp)
    }

    @Test fun disabledControlsDoNotHit() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column {
                    KitSwitch(true, {}, enabled = false, tag = "switch")
                    KitButton("Button", {}, kind = KitButtonKind.Disabled, tag = "disabled")
                    KitRow("Label", {}, enabled = false, testTag = "row")
                }
            }
        }
        compose.onNodeWithTag("switch-hit", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("switch", useUnmergedTree = true).assertWidthIsEqualTo(51.dp).assertHeightIsEqualTo(31.dp)
        compose.onNodeWithTag("disabled", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("row", useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test fun composerBandReachesOverTheHomeIndicatorAndKeepsItsControlsAbove() {
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column {
                    KitComposer("x", {}, "\u6d88\u606f", {}, {}, sendActive = true, tag = "plain")
                    KitComposer(
                        "x",
                        {},
                        "\u6d88\u606f",
                        {},
                        {},
                        sendActive = true,
                        tag = "banded",
                        bottomInset = WindowInsets(bottom = 34.dp)
                    )
                }
            }
        }
        compose.onNodeWithTag("plain").assertHeightIsEqualTo(60.dp)
        compose.onNodeWithTag("banded").assertHeightIsEqualTo(94.dp)
        compose.onNodeWithTag("banded-send", useUnmergedTree = true).assertHeightIsEqualTo(48.dp)
    }

    @Test fun albumHeightsDependOnlyOnTheCountAndTheCap() {
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column {
                    for (count in listOf(2, 3, 5)) {
                        KitAlbumGrid(count, own = false, maxWidth = 300.dp, more = "+1", tag = "album-$count") { _, modifier ->
                            Box(modifier)
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("album-2").assertWidthIsEqualTo(300.dp).assertHeightIsEqualTo(149.dp)
        compose.onNodeWithTag("album-3").assertHeightIsEqualTo(200.dp)
        compose.onNodeWithTag("album-5").assertHeightIsEqualTo(300.dp)
    }

    @Test fun nestedHitsStayFortyEightInsideTheMasterFrame() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column {
                    KitSearchField("x", {}, "\u641c\u7d22", tag = "search")
                    KitSegmented(listOf("A", "B"), 0, {}, tag = "segments")
                    KitComposer("x", {}, "\u6d88\u606f", {}, {}, sendActive = true, tag = "composer")
                    KitReactionBar(listOf(KitReaction("\uD83D\uDC4D")), {}, {}, tag = "reactions")
                }
            }
        }
        compose.onNodeWithTag("search").assertHeightIsEqualTo(36.dp)
        compose.onNodeWithTag("search-clear", useUnmergedTree = true).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("search-clear-icon", useUnmergedTree = true).assertHeightIsEqualTo(17.dp)
        compose.onNodeWithTag("segments").assertHeightIsEqualTo(36.dp)
        compose.onNodeWithTag("segments-0", useUnmergedTree = true).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("composer").assertHeightIsEqualTo(60.dp)
        compose.onNodeWithTag("composer-send", useUnmergedTree = true).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("composer-send-icon", useUnmergedTree = true).assertHeightIsEqualTo(32.dp)
        compose.onNodeWithTag("reactions").assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag("reactions-0", useUnmergedTree = true).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("reactions-cell-0", useUnmergedTree = true).assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag("reactions-cell-0", useUnmergedTree = true).assertWidthIsEqualTo(40.dp)
    }

    @Test fun pressedFilledButtonUsesTheRuledAlpha() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RamfluxTheme(dark = false) {
                Box(Modifier.width(361.dp)) { KitButton("\u6309\u94ae", {}, tag = "button") }
            }
        }
        compose.onNodeWithTag("button").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        val pixel = colorAt("button", 12, 26)
        val red = android.graphics.Color.red(pixel) / 255f
        // 0.88 of #222224 over the light surface lands near rgb 60 (0x3C).
        check(red in 0.18f..0.32f) { "pressed red $red pixel ${Integer.toHexString(pixel)}" }
    }

    @Test fun focusedPrimaryRingUsesTheDarkFocusToken() {
        compose.setContent {
            RamfluxTheme(dark = false) {
                Box(Modifier.width(361.dp)) { KitButton("\u6309\u94ae", {}, focused = true, tag = "button") }
            }
        }
        val image = capture("button")
        val ring = channel(image, 1, 26)
        val fill = channel(image, 12, 26)
        check(ring > 0.7f) { "ring red $ring pixel ${hex(image, 1, 26)}" }
        check(fill < 0.25f) { "fill red $fill pixel ${hex(image, 12, 26)}" }
    }

    @Test fun focusedPrimaryRingStaysVisibleInDarkTheme() {
        compose.setContent {
            RamfluxTheme(dark = true) {
                Box(Modifier.width(361.dp)) { KitButton("\u6309\u94ae", {}, focused = true, tag = "button") }
            }
        }
        val image = capture("button")
        val ring = channel(image, 1, 26)
        val fill = channel(image, 12, 26)
        check(ring + 0.04f < fill) { "dark ring $ring fill $fill ringPx ${hex(image, 1, 26)} fillPx ${hex(image, 12, 26)}" }
    }

    @Test fun pressedRowUsesTheSelectedFill() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RamfluxTheme(dark = false) {
                Box(Modifier.width(361.dp)) { KitRow("\u6807\u9898", {}, icon = "user-round", testTag = "row") }
            }
        }
        compose.onNodeWithTag("row").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        val pixel = colorAt("row", 180, 4)
        val red = android.graphics.Color.red(pixel) / 255f
        check(red in 0.85f..0.95f) { "pressed row red $red pixel ${Integer.toHexString(pixel)}" }
    }

    @Test fun loadingButtonKeepsItsWidthAndShowsASpinner() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RamfluxTheme(dark = false) {
                Box(Modifier.width(361.dp)) { KitButton("\u6309\u94ae", {}, loading = true, tag = "loading") }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val width = compose.onNodeWithTag("loading").fetchSemanticsNode().size.width
        check(width == 361) { "loading width $width" }
        compose.onNodeWithTag("loading-spinner", useUnmergedTree = true).assertExists()
    }

    @Test fun headerBackAndMenuIconsSitAtTheStartInRightToLeft() {
        compose.setContent {
            RamfluxTheme(dark = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(Modifier.width(393.dp)) {
                        KitPageHeader("\u0639\u0646\u0648\u0627\u0646", onBack = {}, tag = "header")
                        KitContextMenu(listOf(KitMenuEntry("\u0631\u062f", "reply")), {}, tag = "menu")
                    }
                }
            }
        }
        val back = compose.onNodeWithTag("header-back", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        check(back.left > 300f) { "back at ${back.left}" }
        val hit = compose.onNodeWithTag("menu-hit-0-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        check(hit.right == 393f) { "menu row ends at ${hit.right}" }
    }

    @Test fun textFramesGrowAtFontScaleTwo() {
        compose.setContent {
            RamfluxTheme(dark = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Column(Modifier.width(360.dp)) {
                        KitChatHeader("Title", "Subtitle", { KitAvatar("A", 36f, KitAvatarKind.Person) }, {}, tag = "header")
                        KitRow("Label", {}, icon = "user-round", value = "Value", testTag = "row")
                        KitButton("Continue with the existing identity on this device", {}, tag = "button")
                        KitButton("Enter the pairing code shown on the other device", {}, kind = KitButtonKind.Text, tag = "text")
                        KitTabBar(listOf(KitTab("messages-square", "Messages")), 0, {}, bottomInset = KIT_FRAME_INSET, tag = "tabs")
                        KitComposer("", {}, "Message", {}, {}, tag = "composer")
                        KitSegmented(listOf("Scan", "Compare the safety number"), 0, {}, tag = "segments")
                    }
                }
            }
        }
        fun height(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().size.height
        check(height("header") > 60) { "header ${height("header")}" }
        check(height("row") > 58) { "row ${height("row")}" }
        check(height("button") > 52) { "button ${height("button")}" }
        check(height("text") > 44) { "text button ${height("text")}" }
        check(height("tabs") > 83) { "tabs ${height("tabs")}" }
        check(height("composer") > 60) { "composer ${height("composer")}" }
        check(height("segments") > 36) { "segments ${height("segments")}" }
        compose.onNodeWithTag("segments-0", useUnmergedTree = true).assertHeightIsEqualTo(
            compose.onNodeWithTag(
                "segments-1",
                useUnmergedTree = true
            ).fetchSemanticsNode().let { with(Density(1f)) { it.size.height.toDp() } }
        )
    }

    @Test fun wideKitPartsFitANarrowPane() {
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column(Modifier.width(200.dp)) {
                    KitContextMenu(listOf(KitMenuEntry("Reply", "reply")), {}, tag = "menu")
                    KitApprovalCard("Ada", "Pending", "Title", "Scope", "Detail", "Decline", "Approve", {}, {}, tag = "card")
                }
            }
        }
        compose.onNodeWithTag("menu").assertWidthIsEqualTo(200.dp)
        compose.onNodeWithTag("card").assertWidthIsEqualTo(200.dp)
    }

    private fun colorAt(tag: String, x: Int, y: Int): Int = capture(tag).getPixel(x, y)

    private fun channel(image: android.graphics.Bitmap, x: Int, y: Int): Float = android.graphics.Color.red(image.getPixel(x, y)) / 255f

    private fun hex(image: android.graphics.Bitmap, x: Int, y: Int): String = Integer.toHexString(image.getPixel(x, y))

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(tag: String): android.graphics.Bitmap {
        val file = java.io.File.createTempFile("kit-pixel-", ".png")
        try {
            compose.onNodeWithTag(tag).captureRoboImage(
                file,
                RoborazziOptions(taskType = RoborazziTaskType.Record)
            )
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            check(bitmap != null) { "unreadable ${file.absolutePath} bytes=${file.length()}" }
            return bitmap
        } finally {
            file.delete()
        }
    }

    @Test fun tabBarBandFollowsTheBottomInset() {
        val tabs = listOf(KitTab("messages-square", "Messages"), KitTab("ellipsis", "More"))
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column(Modifier.width(393.dp)) {
                    KitTabBar(tabs, 0, {}, bottomInset = WindowInsets(0, 0, 0, 0), tag = "none")
                    KitTabBar(tabs, 0, {}, bottomInset = KIT_FRAME_INSET, tag = "frame")
                    KitTabBar(tabs, 0, {}, bottomInset = WindowInsets(bottom = 48.dp), tag = "tall")
                }
            }
        }
        // Tab row 8 + 48 dp; the band is the inset less the design frame's 3 dp overlap and the 4 dp hit overhang.
        compose.onNodeWithTag("none", useUnmergedTree = true).assertHeightIsEqualTo(56.dp)
        compose.onNodeWithTag("frame", useUnmergedTree = true).assertHeightIsEqualTo(83.dp)
        compose.onNodeWithTag("tall", useUnmergedTree = true).assertHeightIsEqualTo(97.dp)
    }

    @Test fun railStacksTheDestinationsWithHitFramesAndPadsTheInset() {
        val tabs = listOf(KitTab("messages-square", "Messages", badge = "3"), KitTab("users-round", "Groups"), KitTab("ellipsis", "More"))
        var picked = -1
        compose.setContent {
            RamfluxTheme(dark = false) {
                Box(Modifier.height(600.dp)) {
                    KitNavigationRail(tabs, 0, { picked = it }, bottomInset = KIT_FRAME_INSET, tag = "rail")
                }
            }
        }
        compose.onNodeWithTag("rail", useUnmergedTree = true).assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(600.dp)
        (0..2).forEach { compose.onNodeWithTag("rail-$it", useUnmergedTree = true).assertHeightIsAtLeast(48.dp) }
        val first = compose.onNodeWithTag("rail-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("rail-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        check(second.top >= first.bottom) { "rail cells overlap: $first $second" }
        compose.onNodeWithTag("rail-2", useUnmergedTree = true).performClick()
        compose.runOnIdle { check(picked == 2) { "picked $picked" } }
    }

    @Test fun railLabelsWrapAtLargeFontScale() {
        compose.setContent {
            RamfluxTheme(dark = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    KitNavigationRail(listOf(KitTab("users-round", "Communities and channels")), 0, {}, tag = "rail")
                }
            }
        }
        val cell = compose.onNodeWithTag("rail-0", useUnmergedTree = true).fetchSemanticsNode().size.height
        check(cell > 48 * compose.density.density) { "rail label did not wrap: $cell" }
    }

    @Test fun secretWordsStayLeftToRightNumberedAndOutOfTheSemanticsTree() {
        val words = listOf("apple", "river", "stone", "cloud").map { it.toCharArray() }
        compose.setContent {
            RamfluxTheme(dark = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(360.dp)) {
                        LtrSecretWords(words.size, { index, block -> block(words[index]) }, tag = "words")
                    }
                }
            }
        }
        val first = compose.onNodeWithTag("words-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("words-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        check(first.top < second.top || first.left < second.left) { "word 1 is not after word 0 left to right: $first $second" }
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("4").assertExists()
        words.forEach { compose.onAllNodesWithText(String(it), substring = true, useUnmergedTree = true).assertCountEquals(0) }
    }

    @Test fun secretWordsGrowWithTheFontScale() {
        val words = listOf("apple".toCharArray())
        compose.setContent {
            RamfluxTheme(dark = false) {
                Column(Modifier.width(360.dp)) {
                    LtrSecretWords(1, { _, block -> block(words[0]) }, tag = "normal")
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                        LtrSecretWords(1, { _, block -> block(words[0]) }, tag = "large")
                    }
                }
            }
        }
        val normal = compose.onNodeWithTag("normal-0", useUnmergedTree = true).fetchSemanticsNode().size.height
        val large = compose.onNodeWithTag("large-0", useUnmergedTree = true).fetchSemanticsNode().size.height
        // Platform font scaling is non-linear above 1.3 on recent API levels, so 2.0 grows the line by less than 2x.
        check(large > normal * 3 / 2) { "secret words ignore the font scale: $normal -> $large" }
    }

    private fun frame(tag: String, width: androidx.compose.ui.unit.Dp, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            RamfluxTheme(dark = false) { Box(Modifier.width(width)) { content() } }
        }
    }
}

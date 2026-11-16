// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h1200dp-mdpi")
class KitScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val deadline = KitDeadline(300)

    @Test fun buttonPrimary() = shot("button--primary", 361.dp) { KitButton("\u6309\u94ae", {}, tag = "shot") }

    @Test fun buttonSecondary() = shot("button--secondary", 361.dp) {
        KitButton("\u6309\u94ae", {}, kind = KitButtonKind.Secondary, tag = "shot")
    }

    @Test fun buttonDestructive() = shot("button--destructive", 361.dp) {
        KitButton("\u6309\u94ae", {}, kind = KitButtonKind.Destructive, tag = "shot")
    }

    @Test fun buttonDisabled() = shot(
        "button--disabled",
        361.dp
    ) { KitButton("\u6309\u94ae", {}, kind = KitButtonKind.Disabled, tag = "shot") }

    @Test fun buttonText() = shot(
        "button--text",
        361.dp
    ) { KitButton("\u6587\u5b57\u6309\u94ae", {}, kind = KitButtonKind.Text, tag = "shot") }

    @Test fun buttonPrimaryDark() = shot("button--primary--dark", 361.dp, dark = true) { KitButton("\u6309\u94ae", {}, tag = "shot") }

    @Test fun avatarPerson() = shot("avatar--person") { KitAvatar("A", 28f, KitAvatarKind.Person, tag = "shot") }

    @Test fun avatarAgent() = shot("avatar--agent") { KitAvatar("", 28f, KitAvatarKind.Agent, tag = "shot") }

    @Test fun avatarPerson18() = shot("avatar--person-small-18") { KitAvatar("A", 18f, KitAvatarKind.Person, tag = "shot") }

    @Test fun switchOn() = shot("switch--on") { KitSwitch(true, {}, tag = "shot") }

    @Test fun switchOff() = shot("switch--off") { KitSwitch(false, {}, tag = "shot") }

    @Test fun checkboxOn() = shot("checkbox--on") { KitCheckbox(true, {}, tag = "shot") }

    @Test fun checkboxOff() = shot("checkbox--off") { KitCheckbox(false, {}, tag = "shot") }

    @Test fun segmented() = shot("segmented--default", 361.dp) {
        KitSegmented(listOf("\u626b\u63cf\u4e8c\u7ef4\u7801", "\u5bf9\u6bd4\u53f7\u7801"), 0, {}, tag = "shot")
    }

    @Test fun statusBar() = shot("status-bar--default", 393.dp) { KitStatusBar("9:41", tag = "shot") }

    @Test fun homeIndicator() = shot("home-indicator--default") { KitHomeIndicator(tag = "shot") }

    @Test fun pageHeader() = shot("page-header--default", 393.dp) {
        KitPageHeader("\u9875\u9762\u6807\u9898", onBack = {}, onInfo = {}, tag = "shot")
    }

    @Test fun pageHeaderLarge() = shot("page-header--large-title", 393.dp) {
        KitPageHeader(
            "\u6d88\u606f",
            large = true,
            subtitle = "\u526f\u6807\u9898",
            actions = listOf(
                KitHeaderAction("square-pen") {
                },
                KitHeaderAction("ellipsis") {}
            ),
            tag = "shot"
        )
    }

    @Test fun chatHeader() = shot("chat-header--default", 393.dp) {
        KitChatHeader("\u6807\u9898", "\u526f\u6807\u9898", {
            KitAvatar("A", 36f, KitAvatarKind.Person)
        }, {}, onCall = {}, onMore = {}, tag = "shot")
    }

    @Test fun tabBar() = shot("tab-bar--messages-selected-badge", 393.dp) {
        KitTabBar(
            listOf(
                KitTab("messages-square", "\u6d88\u606f", badge = "1"),
                KitTab("users-round", "\u7fa4\u7ec4"),
                KitTab("bot", "Agents", latin = true),
                KitTab("ellipsis", "\u66f4\u591a")
            ),
            0,
            {},
            tag = "shot"
        )
    }

    @Test fun search() = shot("search-field--default", 361.dp) { KitSearchField("", {}, "\u641c\u7d22", tag = "shot") }

    @Test fun textField() = shot("text-field--default", 361.dp) { KitTextField("\u503c", {}, "\u6807\u7b7e", tag = "shot") }

    @Test fun textFieldError() = shot("text-field--error", 361.dp) {
        KitTextField("", {}, "\u6807\u7b7e", error = "\u9519\u8bef", tag = "shot")
    }

    @Test fun row() = shot("row--default", 361.dp) {
        KitRow("\u6807\u9898", {}, icon = "user-round", value = "\u8bf4\u660e", testTag = "shot")
    }

    @Test fun rowTag() = shot("row--tag", 361.dp) {
        KitRow("\u6807\u7b7e", {}, icon = "user-round", tagLabel = "\u6807\u7b7e", testTag = "shot")
    }

    @Test fun iconTile() = shot("icon-tile--default") { KitIconTile("user-round", tag = "shot") }

    @Test fun infoButton() = shot("info-button--default") { KitInfoButton({}, tag = "shot") }

    @Test fun conversation() = shot("list-row-conversation--default", 393.dp) {
        KitConversationRow("\u6807\u9898", "\u9884\u89c8", "9:41", { KitAvatar("A", 52f, KitAvatarKind.Person) }, {}, tag = "shot")
    }

    @Test fun bubbleOwn() = shot("bubble--own", 393.dp) {
        KitBubble(
            "\u6536\u5230\uff0c\u6211\u4f1a\u628a\u95ee\u9898\u9010\u9879\u56de\u590d\u3002",
            time = "10:23",
            maxWidth = 260.dp,
            tag = "shot"
        )
    }

    @Test fun bubbleIncoming() = shot("bubble--incoming", 393.dp) {
        KitBubble(
            "\u65b0\u7248\u8d44\u6599\u6211\u6574\u7406\u597d\u4e86\uff0c\u4f60\u6709\u7a7a\u65f6\u770b\u4e00\u4e0b\u3002",
            kind = KitBubbleKind.Incoming,
            time = "10:21",
            maxWidth = 260.dp,
            tag = "shot"
        )
    }

    @Test fun bubbleFile() = shot("bubble--file") {
        KitBubble(
            "",
            kind = KitBubbleKind.File,
            time = "10:24",
            fileName = "\u7248\u672c\u8bf4\u660e.pdf",
            fileMeta = "PDF",
            tag = "shot"
        )
    }

    @Test fun composer() = shot("composer--default", 393.dp) { KitComposer("", {}, "\u53d1\u6d88\u606f\u2026", {}, {}, tag = "shot") }

    @Test fun dateChip() = shot("date-chip--default", 361.dp) { KitDateChip("\u4eca\u5929", tag = "shot") }

    @Test fun unread() = shot("unread-divider--default", 361.dp) { KitUnreadDivider("\u672a\u8bfb\u6d88\u606f", tag = "shot") }

    @Test fun reaction() = shot("reaction-bar--default") {
        KitReactionBar(
            listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F"),
            {},
            {},
            tag = "shot"
        )
    }

    @Test fun menu() = shot("context-menu--default") {
        KitContextMenu(
            listOf(
                KitMenuEntry("\u56de\u590d", "reply"),
                KitMenuEntry("\u590d\u5236", "copy"),
                KitMenuEntry("\u7f16\u8f91", "pencil"),
                KitMenuEntry("\u8f6c\u53d1", "forward"),
                KitMenuEntry("\u7f6e\u9876", "pin"),
                KitMenuEntry("\u9009\u62e9", "list-checks"),
                KitMenuEntry("\u5220\u9664", "trash-2", danger = true, groupBefore = true)
            ),
            {},
            tag = "shot"
        )
    }

    @Test fun sheet() = shot("sheet--default", 393.dp) { KitSheet("\u6807\u9898", "\u6b63\u6587", "\u5173\u95ed", {}, tag = "shot") }

    @Test fun tip() = shot("tip-bar--default", 361.dp) {
        KitTipBar("\u63d0\u793a", "wifi-off", action = "\u91cd\u8bd5", onAction = {}, tag = "shot")
    }

    @Test fun warning() = shot("security-warning--warning", 361.dp) {
        KitSecurityWarning(
            "\u7f51\u9875\u4f1a\u8bdd\u4e0d\u53d7\u8282\u70b9\u8fd0\u8425\u65b9\u7684\u9632\u62a4",
            "\u8be5\u8282\u70b9\u7684\u8fd0\u8425\u65b9\u53ef\u4ee5\u8bfb\u53d6" +
                "\u8fd9\u53f0\u7f51\u9875\u8bbe\u5907\u4e0a\u7684\u5bc6\u94a5\u548c\u6d88\u606f\u660e\u6587\u3002",
            KitWarningTone.Warning,
            tag = "shot"
        )
    }

    @Test fun danger() = shot("security-warning--danger", 361.dp) {
        KitSecurityWarning(
            "\u7f51\u9875\u4f1a\u8bdd\u4e0d\u53d7\u8282\u70b9\u8fd0\u8425\u65b9\u7684\u9632\u62a4",
            "\u8be5\u8282\u70b9\u7684\u8fd0\u8425\u65b9\u53ef\u4ee5\u8bfb\u53d6" +
                "\u8fd9\u53f0\u7f51\u9875\u8bbe\u5907\u4e0a\u7684\u5bc6\u94a5\u548c\u6d88\u606f\u660e\u6587\u3002",
            KitWarningTone.Danger,
            tag = "shot"
        )
    }

    @Test fun hero() = shot("status-hero--default") { KitStatusHero("loader-circle", tag = "shot") }

    @Test fun approval() = shot("approval-card--default") {
        KitApprovalCard("Ada", "\u5f85\u5904\u7406", "\u6807\u9898", "\u8303\u56f4", "\u8be6\u60c5", "\u62d2\u7edd", "\u540c\u610f", {
        }, {}, tag = "shot")
    }

    @Test fun mono() = shot("mono-block--default", 361.dp) { KitMonoBlock("abc123", {}, tag = "shot") }

    @Test fun pairing() = shot("pairing-code--default", 361.dp) { KitPairingCode("ABCD EF", tag = "shot") }

    @Test fun qr() = shot("qr-placeholder--default") { KitQrPlaceholder(tag = "shot") }

    @Test fun qrDark() = shot("qr-placeholder--default--dark", dark = true) { KitQrPlaceholder(tag = "shot") }

    @Test fun darkMasters() = batch {
        shot("avatar--person--dark", dark = true) { KitAvatar("A", 28f, KitAvatarKind.Person, tag = "shot") }
        shot("avatar--agent--dark", dark = true) { KitAvatar("", 28f, KitAvatarKind.Agent, tag = "shot") }
        shot("bubble--own--dark", 393.dp, dark = true) {
            KitBubble(
                "\u6536\u5230\uff0c\u6211\u4f1a\u628a\u95ee\u9898\u9010\u9879\u56de\u590d\u3002",
                time = "10:23",
                maxWidth = 260.dp,
                tag = "shot"
            )
        }
        shot("bubble--incoming--dark", 393.dp, dark = true) {
            KitBubble(
                "\u65b0\u7248\u8d44\u6599\u6211\u6574\u7406\u597d\u4e86\uff0c\u4f60\u6709\u7a7a\u65f6\u770b\u4e00\u4e0b\u3002",
                kind = KitBubbleKind.Incoming,
                time = "10:21",
                maxWidth = 260.dp,
                tag = "shot"
            )
        }
        shot("bubble--file--dark", dark = true) {
            KitBubble(
                "",
                kind = KitBubbleKind.File,
                time = "10:24",
                fileName = "\u7248\u672c\u8bf4\u660e.pdf",
                fileMeta = "PDF",
                tag = "shot"
            )
        }
        shot("button--secondary--dark", 361.dp, dark = true) { KitButton("\u6309\u94ae", {}, kind = KitButtonKind.Secondary, tag = "shot") }
        shot("button--text--dark", 361.dp, dark = true) {
            KitButton("\u6587\u5b57\u6309\u94ae", {}, kind = KitButtonKind.Text, tag = "shot")
        }
        shot("chat-header--default--dark", 393.dp, dark = true) {
            KitChatHeader(
                "\u6807\u9898",
                "\u526f\u6807\u9898",
                { KitAvatar("A", 36f, KitAvatarKind.Person) },
                {},
                onCall = {},
                onMore = {},
                tag = "shot"
            )
        }
        shot("composer--default--dark", 393.dp, dark = true) { KitComposer("", {}, "\u53d1\u6d88\u606f\u2026", {}, {}, tag = "shot") }
        shot("context-menu--default--dark", dark = true) {
            KitContextMenu(menuEntries(), {}, tag = "shot")
        }
        shot("date-chip--default--dark", 361.dp, dark = true) { KitDateChip("\u4eca\u5929", tag = "shot") }
        shot("home-indicator--default--dark", dark = true) { KitHomeIndicator(tag = "shot") }
        shot("list-row-conversation--default--dark", 393.dp, dark = true) {
            KitConversationRow("\u6807\u9898", "\u9884\u89c8", "9:41", { KitAvatar("A", 52f, KitAvatarKind.Person) }, {}, tag = "shot")
        }
        shot("mono-block--default--dark", 361.dp, dark = true) { KitMonoBlock("abc123", {}, tag = "shot") }
        shot("page-header--default--dark", 393.dp, dark = true) {
            KitPageHeader("Luma \u6863\u6848", onBack = {}, onInfo = {}, tag = "shot")
        }
        shot("page-header--large-title--dark", 393.dp, dark = true) {
            KitPageHeader("\u6d88\u606f", large = true, subtitle = "\u526f\u6807\u9898", tag = "shot")
        }
        shot("reaction-bar--default--dark", dark = true) {
            KitReactionBar(
                listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F"),
                {},
                {},
                tag = "shot"
            )
        }
        shot("row--default--dark", 361.dp, dark = true) {
            KitRow(
                "\u6807\u9898",
                {},
                icon = "user-round",
                value = "\u8bf4\u660e",
                testTag = "shot"
            )
        }
        shot("search-field--default--dark", 361.dp, dark = true) { KitSearchField("", {}, "\u641c\u7d22", tag = "shot") }
        shot("segmented--default--dark", 361.dp, dark = true) {
            KitSegmented(listOf("\u626b\u63cf\u4e8c\u7ef4\u7801", "\u5bf9\u6bd4\u53f7\u7801"), 0, {}, tag = "shot")
        }
        shot("sheet--default--dark", 393.dp, dark = true) { KitSheet("\u6807\u9898", "\u6b63\u6587", "\u5173\u95ed", {}, tag = "shot") }
        shot("status-bar--default--dark", 393.dp, dark = true) { KitStatusBar("9:41", tag = "shot") }
        shot("status-hero--default--dark", dark = true) { KitStatusHero("loader-circle", tag = "shot") }
        shot("tab-bar--default--dark", 393.dp, dark = true) { MessagesTabBar() }
        shot("text-field--default--dark", 361.dp, dark = true) { KitTextField("\u503c", {}, "\u6807\u7b7e", tag = "shot") }
        shot("tip-bar--default--dark", 361.dp, dark = true) {
            KitTipBar("\u63d0\u793a", "wifi-off", action = "\u91cd\u8bd5", onAction = {}, tag = "shot")
        }
        shot("unread-divider--default--dark", 361.dp, dark = true) { KitUnreadDivider("\u672a\u8bfb\u6d88\u606f", tag = "shot") }
    }

    @Test fun englishAndChineseSamples() = batch {
        shot("button--primary--en", 361.dp) { KitButton("\u6309\u94ae", {}, tag = "shot") }
        shot("button--primary--zh", 361.dp) { KitButton("\u6309\u94ae", {}, tag = "shot") }
        shot("page-header--default--en", 393.dp) {
            KitPageHeader("\u9875\u9762\u6807\u9898", onBack = {}, onInfo = {}, tag = "shot")
        }
        shot("page-header--default--zh", 393.dp) {
            KitPageHeader("\u9875\u9762\u6807\u9898", onBack = {}, onInfo = {}, tag = "shot")
        }
        shot("row--default--en", 361.dp) {
            KitRow(
                "\u6807\u9898",
                {},
                icon = "user-round",
                value = "\u8bf4\u660e",
                testTag = "shot"
            )
        }
        shot("row--default--zh", 361.dp) {
            KitRow(
                "\u6807\u9898",
                {},
                icon = "user-round",
                value = "\u8bf4\u660e",
                testTag = "shot"
            )
        }
        shot("bubble--own--en", 393.dp) {
            KitBubble(
                "\u6536\u5230\uff0c\u6211\u4f1a\u628a\u95ee\u9898\u9010\u9879\u56de\u590d\u3002",
                time = "10:23",
                maxWidth = 260.dp,
                tag = "shot"
            )
        }
        shot("tab-bar--messages-selected-badge--en", 393.dp) { MessagesTabBar() }
    }

    @Test fun ruledStates() = batch {
        shot("text-field--focused", 361.dp) {
            KitTextField("\u00b7 ".repeat(16).trim(), {}, "\u518d\u6b21\u8f93\u5165\u6570\u636e\u53e3\u4ee4", focused = true, tag = "shot")
        }
        shot("text-field--disabled", 361.dp) { KitTextField("\u503c", {}, "\u6807\u7b7e", enabled = false, tag = "shot") }
        shot("search-field--focused", 361.dp) { KitSearchField("", {}, "\u641c\u7d22", focused = true, tag = "shot") }
        shot("button--primary--loading", 361.dp) { KitButton("\u6309\u94ae", {}, loading = true, tag = "shot") }
        shot("button--primary--focused", 361.dp) { KitButton("\u6309\u94ae", {}, focused = true, tag = "shot") }
        shot("button--primary--pressed", 361.dp, press = true) { KitButton("\u6309\u94ae", {}, tag = "shot") }
        shot("row--default--pressed", 361.dp, press = true) {
            KitRow(
                "\u6807\u9898",
                {},
                icon = "user-round",
                value = "\u8bf4\u660e",
                testTag = "shot"
            )
        }
    }

    @Composable
    private fun MessagesTabBar() {
        KitTabBar(
            listOf(
                KitTab("messages-square", "\u6d88\u606f", badge = "1"),
                KitTab("users-round", "\u7fa4\u7ec4"),
                KitTab("bot", "Agents", latin = true),
                KitTab("ellipsis", "\u66f4\u591a")
            ),
            0,
            {},
            tag = "shot"
        )
    }

    private fun menuEntries() = listOf(
        KitMenuEntry("\u56de\u590d", "reply"),
        KitMenuEntry("\u590d\u5236", "copy"),
        KitMenuEntry("\u7f16\u8f91", "pencil"),
        KitMenuEntry("\u8f6c\u53d1", "forward"),
        KitMenuEntry("\u7f6e\u9876", "pin"),
        KitMenuEntry("\u9009\u62e9", "list-checks"),
        KitMenuEntry("\u5220\u9664", "trash-2", danger = true, groupBefore = true)
    )

    private class Frame(
        val name: String,
        val width: Dp? = null,
        val dark: Boolean = false,
        val press: Boolean = false,
        val body: @Composable () -> Unit
    )

    private var batch: MutableList<Frame>? = null

    private fun batch(build: () -> Unit) {
        val items = mutableListOf<Frame>()
        batch = items
        try {
            build()
        } finally {
            batch = null
        }
        frames(items)
    }

    private fun frames(items: List<Frame>) {
        val step = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val item = items[step.intValue]
            RamfluxTheme(item.dark) {
                val frame = if (item.width == null) Modifier else Modifier.width(item.width)
                Box(frame) { item.body() }
            }
        }
        items.forEachIndexed { index, item ->
            step.intValue = index
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            if (item.press) {
                compose.onNodeWithTag("shot", useUnmergedTree = true).performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(32)
            }
            compose.onNodeWithTag("shot", useUnmergedTree = true)
                .captureRoboImage("build/outputs/roborazzi/${item.name}.png")
            if (item.press) compose.onNodeWithTag("shot", useUnmergedTree = true).performTouchInput { up() }
        }
    }

    private fun shot(name: String, width: Dp? = null, dark: Boolean = false, press: Boolean = false, body: @Composable () -> Unit) {
        val pending = batch
        val item = Frame(name, width, dark, press, body)
        if (pending != null) {
            pending.add(item)
        } else {
            frames(listOf(item))
        }
    }
}

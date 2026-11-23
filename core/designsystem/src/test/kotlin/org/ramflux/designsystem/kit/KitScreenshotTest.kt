// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.LocalRFTheme
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
            bottomInset = KIT_FRAME_INSET,
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
            emojiReactions(),
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
        shot("avatar--person--dark", dark = true) { KitAvatar("\u6797", 28f, KitAvatarKind.Person, tag = "shot") }
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
            KitButton("\u8f93\u5165\u914d\u5bf9\u7801", {}, kind = KitButtonKind.Text, tag = "shot")
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
            KitConversationRow(
                "\u6797\u60a6",
                "\u597d\u7684\uff0c\u9644\u4ef6\u91cc\u7684\u7248\u672c\u8bf4\u660e\u4e5f\u8bf7\u4e00\u8d77\u770b\u3002",
                "10:24",
                { KitAvatar("\u6797", 52f, KitAvatarKind.Person) },
                {},
                tag = "shot"
            )
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
                emojiReactions(),
                {},
                {},
                tag = "shot"
            )
        }
        shot("row--default--dark", 361.dp, dark = true) {
            KitRow(
                "\u540d\u79f0\u3001\u5934\u50cf\u4e0e\u804c\u8d23",
                {},
                icon = "pencil",
                value = "\u53ef\u5728\u624b\u673a\u4e0a\u7f16\u8f91",
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
        shot("text-field--default--dark", 361.dp, dark = true) { KitTextField("Luma", {}, "\u540d\u79f0", tag = "shot") }
        shot("tip-bar--default--dark", 361.dp, dark = true) {
            KitTipBar("\u63d0\u793a", "wifi-off", action = "\u91cd\u8bd5", onAction = {}, tag = "shot")
        }
        shot("unread-divider--default--dark", 361.dp, dark = true) {
            KitUnreadDivider("\u4ee5\u4e0b\u4e3a\u672a\u8bfb\u6d88\u606f", tag = "shot")
        }
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

    /**
     * Right-to-left (ar-XB) and expanded (en-XA) samples at font scale 1.0 and 2.0 in 360, 393 and 700 dp
     * containers. ar-XB samples use Arabic peer text; en-XA samples use expanded accented text.
     */
    @Config(qualifiers = "w800dp-h1600dp-mdpi")
    @Test
    fun localeScaleAndWidthVariants() = batch {
        listOf("ar-XB", "en-XA").forEach { locale ->
            val rtl = locale == "ar-XB"
            val text = if (rtl) arabicSamples else expandedSamples
            listOf(1f, 2f).forEach { scale ->
                listOf(360, 393, 700).forEach { width ->
                    fun variant(component: String, body: @Composable () -> Unit) = batch?.add(
                        Frame(
                            "$component--$locale--font$scale--w$width",
                            width.dp,
                            rtl = rtl,
                            fontScale = scale,
                            variant = true,
                            body = body
                        )
                    )
                    variant("page-header") { KitPageHeader(text.title, onBack = {}, onInfo = {}, tag = "shot") }
                    variant("chat-header") {
                        KitChatHeader(text.name, text.subtitle, {
                            KitAvatar(text.name.take(1), 36f, KitAvatarKind.Person)
                        }, {}, onCall = {}, onMore = {}, tag = "shot")
                    }
                    variant("tab-bar") {
                        KitTabBar(
                            listOf(
                                KitTab("messages-square", text.tab, badge = "3"),
                                KitTab("users-round", text.tab),
                                KitTab("bot", "Agents", latin = true),
                                KitTab("ellipsis", text.tab)
                            ),
                            0,
                            {},
                            bottomInset = KIT_FRAME_INSET,
                            tag = "shot"
                        )
                    }
                    variant("row") { KitRow(text.title, {}, icon = "user-round", value = text.body, testTag = "shot") }
                    variant("conversation") {
                        KitConversationRow(text.name, text.body, "10:24", {
                            KitAvatar(text.name.take(1), 52f, KitAvatarKind.Person)
                        }, {}, badge = "2", tag = "shot")
                    }
                    variant("bubbles") {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = VARIANT_PAD.dp)) {
                            KitBubble(
                                text.body,
                                Modifier.align(androidx.compose.ui.Alignment.Start),
                                kind = KitBubbleKind.Incoming,
                                time = "10:21",
                                sender = text.name,
                                quote = text.title,
                                tag = "shot"
                            )
                            KitBubble(text.other, Modifier.align(androidx.compose.ui.Alignment.End), time = "10:23", read = true)
                            KitBubble(
                                "",
                                Modifier.align(androidx.compose.ui.Alignment.Start),
                                kind = KitBubbleKind.File,
                                time = "10:24",
                                fileName = "invoice\u202Efdp.exe",
                                fileMeta = "PDF"
                            )
                        }
                    }
                    variant("composer") {
                        KitComposer(
                            "",
                            {},
                            text.placeholder,
                            {},
                            {},
                            replyAuthor = text.name,
                            replyExcerpt = text.body,
                            onCloseReply = {},
                            tag = "shot"
                        )
                    }
                    variant("menu") { KitContextMenu(menuEntries(), {}, tag = "shot") }
                    variant(
                        "reactions"
                    ) { KitReactionBar(listOf(KitReaction(text.title, icon = "check")) + emojiReactions().take(2), {}, null, tag = "shot") }
                    variant("date-unread") {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                            KitDateChip(text.tab, tag = "shot")
                            KitUnreadDivider(text.title)
                        }
                    }
                    variant("button") { KitButton(text.subtitle, {}, tag = "shot") }
                    variant("tip-warning") {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                            KitTipBar(text.body, "wifi-off", action = text.tab, onAction = {}, tag = "shot")
                            KitSecurityWarning(text.title, text.warning, KitWarningTone.Danger)
                        }
                    }
                    variant("codes") {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = VARIANT_PAD.dp)) {
                            KitPairingCode("4821 7730", tag = "shot")
                            KitMonoBlock("\u0660\u0661\u0662\u0663 4567 8901 2345 6789", {}, groupSize = 4)
                            LtrCode("apple river stone cloud", numbered = true)
                        }
                    }
                    variant("secret-words") {
                        LtrSecretWords(
                            secretWords.size,
                            { index, block -> block(secretWords[index]) },
                            Modifier.padding(horizontal = VARIANT_PAD.dp),
                            tag = "shot"
                        )
                    }
                    variant("rail") {
                        androidx.compose.foundation.layout.Box(Modifier.height(RAIL_SAMPLE_HEIGHT.dp)) {
                            KitNavigationRail(
                                listOf(
                                    KitTab("messages-square", text.tab, badge = "3"),
                                    KitTab("users-round", text.title),
                                    KitTab("bot", "Agents", latin = true),
                                    KitTab("ellipsis", text.tab)
                                ),
                                0,
                                {},
                                bottomInset = KIT_FRAME_INSET,
                                tag = "shot"
                            )
                        }
                    }
                    variant("untrusted") {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = VARIANT_PAD.dp)) {
                            UntrustedText("Ada\u202Egnp.exe", Modifier.kitTag("shot"))
                            UntrustedText("report\u202Efdp.exe", kind = org.ramflux.designsystem.UntrustedKind.FileName)
                            UntrustedText(text.name)
                        }
                    }
                }
            }
        }
    }

    private class Samples(
        val title: String,
        val name: String,
        val subtitle: String,
        val tab: String,
        val body: String,
        val other: String,
        val placeholder: String,
        val warning: String
    )

    private val arabicSamples = Samples(
        title = "\u0627\u0644\u0625\u0639\u062F\u0627\u062F\u0627\u062A",
        name = "\u0645\u0631\u064A\u0645 \u0627\u0644\u0639\u0644\u064A",
        subtitle = "\u0645\u062A\u0635\u0644 \u0627\u0644\u0622\u0646",
        tab = "\u0627\u0644\u0631\u0633\u0627\u0626\u0644",
        body = "\u0647\u0644 \u0631\u0627\u062C\u0639\u062A \u0627\u0644\u0645\u0644\u0641 v2.pdf \u0627\u0644\u064A\u0648\u0645\u061F",
        other = "Yes, I reviewed \u0627\u0644\u0645\u0644\u0641 today.",
        placeholder = "\u0627\u0643\u062A\u0628 \u0631\u0633\u0627\u0644\u0629\u2026",
        warning = "\u062A\u063A\u064A\u0651\u0631 \u0631\u0642\u0645 \u0627\u0644\u0623\u0645\u0627\u0646. \u0642\u0627\u0631\u0646" +
            " \u0627\u0644\u0631\u0642\u0645 \u0642\u0628\u0644 \u0627\u0644\u0645\u062A\u0627\u0628\u0639\u0629."
    )

    private val expandedSamples = Samples(
        title = "[\u015C\u00E9\u0163\u0163\u00EE\u00F1\u011D\u0161 \u00E5\u00F1\u00F0 \u03C1\u0157\u00EE\u1E7D\u00E5\u00E7\u00FD one two]",
        name = "[\u00C5\u00F0\u00E5 \u0141\u00F6\u1E7D\u00E9\u013C\u00E5\u00E7\u00E9 one two]",
        subtitle = "[\u00D6\u00F1\u013C\u00EE\u00F1\u00E9 \u00F1\u00F6\u0175 one two three]",
        tab = "[\u1E3E\u00E9\u0161\u0161\u00E5\u011D\u00E9\u0161 one]",
        body = "[\u0124\u00E5\u1E7D\u00E9 \u00FD\u00F6\u00FB \u0157\u00E9\u1E7D\u00EE\u00E9\u0175\u00E9\u00F0" +
            " \u0163\u0125\u00E9 \u0192\u00EE\u013C\u00E9 one two three four]",
        other = "[\u00DD\u00E9\u0161, \u00CE \u0157\u00E9\u1E7D\u00EE\u00E9\u0175\u00E9\u00F0 \u00EE\u0163 one two]",
        placeholder = "[\u1E3E\u00E9\u0161\u0161\u00E5\u011D\u00E9\u2026 one]",
        warning = "[\u0162\u0125\u00E9 \u0161\u00E5\u0192\u00E9\u0163\u00FD \u00F1\u00FB\u0271\u0180\u00E9\u0157" +
            " \u00E7\u0125\u00E5\u00F1\u011D\u00E9\u00F0." +
            " \u00C7\u00F6\u0271\u03C1\u00E5\u0157\u00E9 \u00EE\u0163 one two three four five]"
    )

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
            bottomInset = KIT_FRAME_INSET,
            tag = "shot"
        )
    }

    private fun emojiReactions() = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F")
        .map { KitReaction(it) }

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
        val rtl: Boolean = false,
        val fontScale: Float = 1f,
        val variant: Boolean = false,
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
                val theme = LocalRFTheme.current
                val frame = if (item.width == null) Modifier else Modifier.width(item.width)
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalKitBackdrop provides theme.bg,
                    LocalLayoutDirection provides if (item.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(density.density, item.fontScale)
                ) {
                    if (item.variant) {
                        Box(frame.background(theme.bg).padding(vertical = VARIANT_PAD.dp).testTag("variant")) { item.body() }
                    } else {
                        Box(frame) { item.body() }
                    }
                }
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
            // Variants have no design master; they go beside the master captures, not into the comparison.
            if (item.variant) {
                compose.onNodeWithTag("variant", useUnmergedTree = true)
                    .captureRoboImage("build/outputs/roborazzi-variants/${item.name}.png")
            } else {
                compose.onNodeWithTag("shot", useUnmergedTree = true)
                    .captureRoboImage("build/outputs/roborazzi/${item.name}.png")
            }
            if (item.press) compose.onNodeWithTag("shot", useUnmergedTree = true).performTouchInput { up() }
        }
    }

    private companion object {
        const val VARIANT_PAD = 16
    }

    private fun shot(name: String, width: Dp? = null, dark: Boolean = false, press: Boolean = false, body: @Composable () -> Unit) {
        val pending = batch
        val item = Frame(name, width, dark, press, body = body)
        if (pending != null) {
            pending.add(item)
        } else {
            frames(listOf(item))
        }
    }
}

private const val RAIL_SAMPLE_HEIGHT = 420

/** Recovery-style words as character arrays, the only form LtrSecretWords accepts. */
private val secretWords = listOf(
    "apple", "river", "stone", "cloud", "amber", "orbit", "maple", "quartz", "harbor", "tiger", "violet", "zebra"
).map { it.toCharArray() }

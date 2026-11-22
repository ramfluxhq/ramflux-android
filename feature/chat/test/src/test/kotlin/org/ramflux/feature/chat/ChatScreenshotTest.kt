// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.kit.KitHomeIndicator
import org.ramflux.designsystem.kit.KitStatusBar
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One parameter matrix for the chat screen: width x theme x state x locale x font scale. The board state
 * reproduces MOB-B02 (fX3lF) at the 393 x 852 design frame with the system bars drawn by the kit; the
 * actions state reproduces MOB-B03 (FfJ3N). ar-XB is the right-to-left pseudo-locale and en-XA the expanded one.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
class ChatScreenshotTest(
    private val width: Int,
    private val dark: Boolean,
    private val kind: String,
    private val localeTag: String,
    private val fontScale: Float
) {
    @get:Rule val compose = createComposeRule()
    companion object {
        private val locales = listOf("en", "zh-CN", "en-XA", "ar-XB")
        private val kinds = listOf("loading", "error", "empty", "rows", "group", "bidi", "board", "actions")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}-{2}-locale{3}-font{4}")
        fun cases(): List<Array<Any>> {
            val light = listOf(360, 393, 700).flatMap { width -> kinds.map { arrayOf<Any>(width, false, it, 1f) } }
            val dark = kinds.map { arrayOf<Any>(393, true, it, 1f) }
            val large = listOf("rows", "bidi", "board", "actions").map { arrayOf<Any>(360, false, it, 2f) }
            return (light + dark + large).flatMap { row -> locales.map { tag -> arrayOf(row[0], row[1], row[2], tag, row[3]) } }
        }

        private val zone = ZoneId.of("Asia/Shanghai")
        private val clock = Clock.fixed(LocalDateTime.of(2026, 11, 20, 10, 30).atZone(zone).toInstant(), zone)
        private fun at(hour: Int, minute: Int, daysAgo: Long = 0) =
            LocalDateTime.of(2026, 11, 20, hour, minute).minusDays(daysAgo).atZone(zone).toEpochSecond()
    }

    @Test fun screen() {
        val state = when (kind) {
            "loading" -> ChatState(loading = true)
            "error" -> ChatState(problem = uiText(R.string.ui_unable_to_continue))
            "empty" -> ChatState(loaded = true, title = UiText.Data("Project"))
            "board", "actions" -> board(kind == "actions")
            "bidi" -> ChatState(
                conversationId = "conversation-id",
                groupId = "group-id",
                title = UiText.Data("\u0641\u0631\u064A\u0642 Ramflux"),
                loaded = true,
                rows = persistentListOf(
                    MessageItem(
                        "a",
                        "\u0645\u0631\u064A\u0645",
                        false,
                        false,
                        UiText.Data("\u0647\u0644 \u0631\u0627\u062C\u0639\u062A \u0627\u0644\u0645\u0644\u0641 v2.pdf\u061F"),
                        at(9, 2, 1)
                    ),
                    MessageItem("b", "Ada\u202Egnp.exe", false, false, UiText.Data("Report attached: invoice\u202Efdp.exe"), at(9, 5)),
                    MessageItem(
                        "c",
                        "Self",
                        true,
                        false,
                        UiText.Data("Yes, 2 \u0645\u0644\u0641\u0627\u062A reviewed."),
                        at(9, 7),
                        receipt = Receipt.READ
                    )
                ),
                firstUnreadId = "b",
                peerTyping = true
            )
            else -> ChatState(
                conversationId = "conversation-id",
                groupId = if (kind == "group") "group-id" else null,
                title = UiText.Data("Project"),
                loaded = true,
                rows = persistentListOf(
                    MessageItem("one", "Peer", false, false, UiText.Data("Approved message content."), at(9, 20, 1)),
                    CallItem("call", at(9, 21, 1)),
                    MessageItem(
                        "two",
                        "Self",
                        true,
                        false,
                        UiText.Data("Own group message"),
                        at(9, 22),
                        replyPreview = UiText.Data("Approved message content."),
                        receipt = Receipt.DELIVERED,
                        edited = true
                    )
                ),
                unavailable = uiText(R.string.ui_sending_unavailable_value, uiText(R.string.ui_the_sdk_operation_is_not_available_in_this))
            )
        }
        val direction = if (android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.forLanguageTag(localeTag)) ==
            android.view.View.LAYOUT_DIRECTION_RTL
        ) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
        val frame = framed(kind)
        // The boards use the 24-hour clock; the screen follows the device setting.
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        android.provider.Settings.System.putString(context.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(localeTag))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.LayoutDirection(direction)) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, HEIGHT.dp))) {
                            RamfluxTheme(dark) {
                                if (frame) {
                                    Column(Modifier.fillMaxSize()) {
                                        KitStatusBar("9:41")
                                        ChatScreen(state, {}, Modifier.weight(1f), clock = clock)
                                        Box(
                                            Modifier.fillMaxWidth().height(HOME.dp),
                                            contentAlignment = Alignment.Center
                                        ) { KitHomeIndicator() }
                                    }
                                } else {
                                    ChatScreen(state, {}, clock = clock)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat-$width-$dark-$kind-$localeTag-font$fontScale.png")
    }

    private fun framed(kind: String) = kind == "board" || kind == "actions"

    private fun board(selected: Boolean) = ChatState(
        conversationId = "conversation-id",
        title = UiText.Data("\u6797\u60A6"),
        loaded = true,
        canSend = true,
        rows = persistentListOf(
            MessageItem(
                "b1",
                "\u6797\u60A6",
                false,
                false,
                UiText.Data("\u65B0\u7248\u8D44\u6599\u6211\u6574\u7406\u597D\u4E86\uFF0C\u4F60\u6709\u7A7A\u65F6\u770B\u4E00\u4E0B\u3002"),
                at(10, 21)
            ),
            MessageItem(
                "b2",
                "Self",
                true,
                false,
                UiText.Data("\u6536\u5230\uFF0C\u6211\u4F1A\u628A\u95EE\u9898\u9010\u9879\u56DE\u590D\u3002"),
                at(10, 23),
                receipt = Receipt.READ
            ),
            MessageItem(
                "b3",
                "\u6797\u60A6",
                false,
                false,
                UiText.Data("\u597D\u7684\uFF0C\u9644\u4EF6\u91CC\u7684\u7248\u672C\u8BF4\u660E\u4E5F\u8BF7\u4E00\u8D77\u770B\u3002"),
                at(10, 24)
            )
        ),
        firstUnreadId = "b3",
        selectedId = if (selected) "b2" else null
    )
}

private const val HEIGHT = 852
private const val HOME = 34

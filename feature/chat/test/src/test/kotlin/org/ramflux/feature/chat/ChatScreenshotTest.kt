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
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.cards.CardLifecycle
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.kit.KitHomeIndicator
import org.ramflux.designsystem.kit.KitStatusBar
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R
import org.ramflux.testing.fixtures.ChatBoards
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One parameter matrix for the chat screen: width x theme x state x locale x font scale. The board state
 * reproduces MOB-B02 (fX3lF) from the shared board fixture at the 393 x 852 design frame with the system bars drawn by the kit; the
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
        private val kinds =
            listOf("loading", "error", "empty", "rows", "group", "bidi", "board", "actions", "outbox", "media", "album", "gallery", "cards")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}-{2}-locale{3}-font{4}")
        fun cases(): List<Array<Any>> {
            val light = listOf(360, 393, 700).flatMap { width -> kinds.map { arrayOf<Any>(width, false, it, 1f) } }
            val dark = kinds.map { arrayOf<Any>(393, true, it, 1f) }
            val large = listOf("rows", "bidi", "board", "actions", "outbox").map { arrayOf<Any>(360, false, it, 2f) }
            return (light + dark + large).flatMap { row -> locales.map { tag -> arrayOf(row[0], row[1], row[2], tag, row[3]) } }
        }

        private val clock = ChatBoards.clock
        private fun at(hour: Int, minute: Int, daysAgo: Long = 0) = ChatBoards.at(hour, minute, daysAgo)
    }

    @Test fun screen() {
        val state = when (kind) {
            "loading" -> ChatState(loading = true)
            "error" -> ChatState(problem = uiText(R.string.ui_unable_to_continue))
            "empty" -> ChatState(loaded = true, title = UiText.Data("Project"))
            "board", "actions" -> ChatBoards.conversation(selected = kind == "actions")
            // Media M1 states; there is no media board yet, so this follows the file bubble of MOB-B02.
            "media" -> ChatState(
                conversationId = "conversation-id",
                title = UiText.Data("Project"),
                loaded = true,
                rows = persistentListOf(
                    MessageItem(
                        "p",
                        "Peer",
                        false,
                        false,
                        UiText.Data(""),
                        at(10, 20),
                        attachments = persistentListOf(
                            AttachmentItem("p", 0, AttachmentKind.IMAGE, "photo.jpg", 2_400_000, 4f / 3f, AttachmentStatus.AVAILABLE),
                            AttachmentItem(
                                "p",
                                1,
                                AttachmentKind.FILE,
                                "Quarterly report\u202Efdp.exe",
                                48_000,
                                4f / 3f,
                                AttachmentStatus.EXPIRED
                            )
                        )
                    ),
                    MessageItem(
                        "q",
                        "Self",
                        true,
                        false,
                        UiText.Data("Slides attached."),
                        at(10, 22),
                        receipt = Receipt.DELIVERED,
                        attachments = persistentListOf(
                            AttachmentItem("q", 0, AttachmentKind.FILE, "slides.pdf", 1_200_000, 4f / 3f, AttachmentStatus.LOADING)
                        )
                    )
                )
            )
            // Media M2: an album of four pictures and a video (no board yet; follows the media bubble).
            "album" -> ChatState(
                conversationId = "conversation-id",
                title = UiText.Data("Project"),
                loaded = true,
                rows = persistentListOf(
                    MessageItem(
                        "album",
                        "Peer",
                        false,
                        false,
                        UiText.Data("Photos from the offsite."),
                        at(10, 20),
                        attachments = (0 until 4).map { index ->
                            AttachmentItem(
                                "album",
                                index,
                                if (index == 3) AttachmentKind.VIDEO else AttachmentKind.IMAGE,
                                "photo-$index.jpg",
                                1_800_000L + index,
                                if (index % 2 == 0) 4f / 3f else 3f / 4f,
                                if (index == 1) AttachmentStatus.LOADING else AttachmentStatus.AVAILABLE
                            )
                        }.let { persistentListOf<AttachmentItem>().addAll(it) }
                    )
                )
            )
            // Media M3: the conversation gallery (board B29 to be drawn), media tab.
            "gallery" -> ChatState(
                conversationId = "conversation-id",
                title = UiText.Data("Project"),
                loaded = true,
                gallery = GallerySheet(
                    items = (0 until 7).map { index ->
                        AttachmentItem(
                            "m$index",
                            0,
                            if (index == 2) AttachmentKind.VIDEO else AttachmentKind.IMAGE,
                            "item-$index.jpg",
                            900_000L,
                            1f,
                            if (index == 5) AttachmentStatus.EXPIRED else AttachmentStatus.AVAILABLE
                        )
                    }.let { persistentListOf<AttachmentItem>().addAll(it) },
                    loading = false,
                    hasMore = true
                )
            )
            // Beta flow 13: an agent card with an approval, in the timeline.
            "cards" -> ChatState(
                conversationId = "conversation-id",
                title = UiText.Data("Planner"),
                loaded = true,
                rows = persistentListOf(
                    MessageItem("m", "Planner", false, false, UiText.Data("The release notes are ready."), at(10, 20)),
                    CardItem(
                        "card:plan",
                        at(10, 21),
                        "plan",
                        CardLifecycle.READY,
                        "Publish the release notes?",
                        renderAllowed = false,
                        tree = null,
                    )
                )
            )
            // Boards MOB-B05 (send failed) and MOB-B08 (offline, queued).
            "outbox" -> ChatState(
                conversationId = "conversation-id",
                title = UiText.Data("Project"),
                loaded = true,
                queued = true,
                rows = persistentListOf(
                    MessageItem("one", "Peer", false, false, UiText.Data("Approved message content."), at(10, 21)),
                    MessageItem(
                        "local:a",
                        "",
                        true,
                        false,
                        UiText.Data("I'll send the revisions this afternoon."),
                        at(10, 22),
                        delivery = LocalDelivery.QUEUED
                    ),
                    MessageItem(
                        "local:b",
                        "",
                        true,
                        false,
                        UiText.Data("Seen, more comments later."),
                        at(10, 23),
                        delivery = LocalDelivery.FAILED
                    )
                )
            )
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
                    MessageItem(
                        "one",
                        "Peer",
                        false,
                        false,
                        UiText.Data("Approved message content."),
                        at(9, 20, 1),
                        reactions = persistentListOf(ReactionCount("+", 1), ReactionCount(String(Character.toChars(0x1F44D)), 2))
                    ),
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
                                        ChatScreen(state, {}, Modifier.weight(1f), clock = clock, showDetails = true, showCall = true)
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
}

private const val HEIGHT = 852
private const val HOME = 30

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    private val stamp = 1_700_000_000L
    private val clock = Clock.fixed(Instant.ofEpochSecond(stamp + 600), ZoneOffset.UTC)

    @Test fun draftIsAnActionAndBlockedSendCannotFire() {
        val actions = mutableListOf<ChatAction>()
        compose.setContent {
            RamfluxTheme {
                ChatScreen(ChatState(loaded = true, draft = "Draft", unavailable = UiText.Data("Sending unavailable")), { actions.add(it) })
            }
        }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithText("Draft").performTextInput("!")
        compose.runOnIdle { assertTrue(actions.single() is ChatAction.Draft) }
    }

    @Test fun deletedRowAndReadErrorRenderWithoutEmptyHistoryClaim() {
        compose.setContent {
            RamfluxTheme {
                ChatScreen(
                    ChatState(
                        problem = UiText.Data("Read failed"),
                        rows = persistentListOf(MessageItem("m", "", false, true, UiText.Data("Message deleted")))
                    ),
                    {}
                )
            }
        }
        compose.onNodeWithText("Read failed").assertExists()
        compose.onNodeWithText("No messages").assertDoesNotExist()
        compose.onNodeWithText("Message deleted").assertExists()
    }

    @Test fun headerShowsTheTitleNeverTheConversationIdAndHasNoRefreshButton() {
        compose.setContent {
            RamfluxTheme {
                ChatScreen(
                    ChatState(
                        conversationId = "conversation-id",
                        title = UiText.Data("Project"),
                        loaded = true,
                        rows = persistentListOf(MessageItem("one", "Peer", false, false, UiText.Data("Hello"), stamp))
                    ),
                    {},
                    clock = clock
                )
            }
        }
        compose.onNodeWithText("Project").assertExists()
        compose.onNodeWithText("conversation-id", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertExists()
    }

    @Test fun aDetailPaneHidesTheBackControl() {
        compose.setContent { RamfluxTheme { ChatScreen(ChatState(loaded = true), {}, rooted = true) } }
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test fun stampsAreTimeOnlyWithADateChipAndTheUnreadDivider() {
        compose.setContent {
            RamfluxTheme {
                ChatScreen(
                    ChatState(
                        loaded = true,
                        firstUnreadId = "two",
                        rows = persistentListOf(
                            MessageItem("one", "Peer", false, false, UiText.Data("First"), stamp),
                            MessageItem("two", "Peer", false, false, UiText.Data("Second"), stamp + 60)
                        )
                    ),
                    {},
                    clock = clock
                )
            }
        }
        val time = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.US, "hm"), Locale.US)
            .format(Instant.ofEpochSecond(stamp).atZone(ZoneOffset.UTC))
        val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.US)
            .format(Instant.ofEpochSecond(stamp).atZone(ZoneOffset.UTC))
        compose.onNodeWithText(time).assertExists()
        compose.onNodeWithText(dateTime).assertDoesNotExist()
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Unread messages").assertExists()
    }

    @Test fun longPressOpensKitActionsAndTheyEmitActions() {
        val actions = mutableListOf<ChatAction>()
        val row = MessageItem("one", "Peer", false, false, UiText.Data("Hello"), stamp)
        val state = androidx.compose.runtime.mutableStateOf(ChatState(loaded = true, rows = persistentListOf(row)))
        compose.setContent { RamfluxTheme { ChatScreen(state.value, { actions.add(it) }, clock = clock) } }
        compose.onNodeWithTag("one", useUnmergedTree = true).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(ChatAction.Select("one"), actions.last()) }
        compose.runOnIdle { state.value = state.value.copy(selectedId = "one") }
        compose.onNodeWithTag("message-menu").assertExists()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Delete for everyone").assertExists()
        compose.onNodeWithContentDescription("Reply").performClick()
        compose.runOnIdle { assertEquals(ChatAction.Reply, actions.last()) }
        compose.onNodeWithContentDescription("Acknowledge").performClick()
        compose.runOnIdle { assertEquals(ChatAction.React, actions.last()) }
        compose.onNodeWithTag("message-actions-scrim").performTouchInput { click(topCenter) }
        compose.runOnIdle { assertEquals(ChatAction.Dismiss, actions.last()) }
    }

    @Test fun ownMessagesOfferEditAndDeletedOnesOfferNothing() {
        val actions = mutableListOf<ChatAction>()
        val rows = persistentListOf(
            MessageItem("own", "Self", true, false, UiText.Data("Mine"), stamp),
            MessageItem("gone", "", false, true, UiText.Data("Message deleted"), stamp)
        )
        val state = androidx.compose.runtime.mutableStateOf(ChatState(loaded = true, rows = rows, selectedId = "own"))
        compose.setContent { RamfluxTheme { ChatScreen(state.value, { actions.add(it) }, clock = clock) } }
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.runOnIdle { assertEquals(ChatAction.Edit, actions.last()) }
        compose.runOnIdle { state.value = state.value.copy(selectedId = "gone") }
        compose.onNodeWithTag("message-menu").assertDoesNotExist()
    }

    @Test fun anArabicMessageInAnEnglishInterfaceIsRightToLeft() {
        val arabic = "\u0645\u0631\u062D\u0628\u0627 \u0628\u0643"
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("en"))) {
                RamfluxTheme {
                    ChatScreen(ChatState(loaded = true, rows = persistentListOf(message("a", arabic))), {}, clock = clock)
                }
            }
        }
        assertEquals(ResolvedTextDirection.Rtl, paragraph(arabic))
    }

    @Test fun anEnglishMessageInARightToLeftInterfaceIsLeftToRight() {
        val english = "Hello there"
        compose.setContent {
            RamfluxTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ChatScreen(ChatState(loaded = true, rows = persistentListOf(message("e", english))), {}, clock = clock)
                }
            }
        }
        assertEquals(ResolvedTextDirection.Ltr, paragraph(english))
    }

    private fun message(id: String, text: String) = MessageItem(id, "Peer", false, false, UiText.Data(text), stamp)

    private fun paragraph(text: String): ResolvedTextDirection {
        val results = mutableListOf<TextLayoutResult>()
        val action = compose.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)
        checkNotNull(action).action?.invoke(results)
        return results.single().getParagraphDirection(0)
    }
}

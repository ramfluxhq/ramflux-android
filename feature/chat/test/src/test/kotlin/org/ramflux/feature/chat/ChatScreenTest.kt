// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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

    @Test fun ownGroupMessagesSitOnTheEndSideAndOnlyIncomingOnesShowTheSender() {
        compose.setContent {
            RamfluxTheme {
                Box(Modifier.width(300.dp).testTag("frame")) {
                    ChatScreen(
                        ChatState(
                            conversationId = "conversation-id",
                            groupId = "group-id",
                            title = UiText.Data("Team"),
                            loaded = true,
                            rows = persistentListOf(
                                MessageItem("in", "Peer name", false, false, UiText.Data("Incoming text"), stamp),
                                MessageItem("mine", "Self name", true, false, UiText.Data("Own text"), stamp)
                            )
                        ),
                        {},
                        clock = clock
                    )
                }
            }
        }
        val incoming = compose.onNodeWithTag("in", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val own = compose.onNodeWithTag("mine", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        val gutter = frame.width / 10f
        assertTrue("incoming starts at the start side: $incoming in $frame", incoming.left - frame.left < gutter)
        assertTrue("own ends at the end side: $own in $frame", frame.right - own.right < gutter)
        assertTrue("own is not on the start side: $own", own.left - frame.left > gutter)
        compose.onNodeWithText("Peer name", substring = true).assertExists()
        compose.onNodeWithText("Self name", substring = true).assertDoesNotExist()
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
        // Erase for everyone is the author's action only.
        compose.onNodeWithText("Delete for everyone").assertDoesNotExist()
        compose.onNodeWithText("Delete for me").assertExists()
        compose.onNodeWithContentDescription("Reply").performClick()
        compose.runOnIdle { assertEquals(ChatAction.Reply, actions.last()) }
        compose.onNodeWithContentDescription("Copy text").performClick()
        compose.runOnIdle { assertEquals(ChatAction.Copy, actions.last()) }
        compose.onNodeWithContentDescription("React with \uD83D\uDC4D").performClick()
        compose.runOnIdle { assertEquals(ChatAction.React("\uD83D\uDC4D"), actions.last()) }
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
        compose.onNodeWithText("Delete for everyone").assertExists()
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.runOnIdle { assertEquals(ChatAction.Edit, actions.last()) }
        compose.runOnIdle { state.value = state.value.copy(selectedId = "gone") }
        compose.onNodeWithTag("message-menu").assertDoesNotExist()
    }

    @Test fun failedLocalMessagesOfferRetryAndQueuedOnesShowTheOfflineNotice() {
        val actions = mutableListOf<ChatAction>()
        val rows = persistentListOf(
            MessageItem("local:1", "", true, false, UiText.Data("Unsent"), stamp, delivery = LocalDelivery.FAILED),
            MessageItem("local:2", "", true, false, UiText.Data("Waiting"), stamp, delivery = LocalDelivery.QUEUED)
        )
        compose.setContent {
            RamfluxTheme { ChatScreen(ChatState(loaded = true, rows = rows, queued = true), { actions.add(it) }, clock = clock) }
        }
        compose.onNodeWithText("Not sent").assertExists()
        compose.onNodeWithText("Queued").assertExists()
        compose.onNodeWithTag("chat-offline", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("chat-retry", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(ChatAction.Retry("local:1"), actions.last()) }
    }

    @Test fun headerActionsAppearOnlyWhenTheHostWiresThem() {
        val actions = mutableListOf<ChatAction>()
        val state = ChatState(loaded = true, rows = persistentListOf(MessageItem("m", "Peer", false, false, UiText.Data("Hi"), stamp)))
        compose.setContent {
            RamfluxTheme { ChatScreen(state, { actions.add(it) }, clock = clock, showDetails = true, showCall = true) }
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Media and files").assertExists()
        compose.onNodeWithText("Conversation details").performClick()
        compose.runOnIdle { assertEquals(ChatAction.Details, actions.last()) }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Media and files").performClick()
        compose.runOnIdle { assertEquals(ChatAction.OpenGallery, actions.last()) }
        compose.onNodeWithContentDescription("Call").performClick()
        compose.runOnIdle { assertEquals(ChatAction.Call, actions.last()) }
        compose.runOnIdle { assertTrue(actions.any { it is ChatAction.Shown && it.id == "m" }) }
    }

    @Test fun cardsShowPlaceholdersForUnsupportedPartsAndActOnlyWhenActionable() {
        val actions = mutableListOf<ChatAction>()
        val root = org.ramflux.data.CardGroup(
            "root",
            false,
            false,
            listOf(
                org.ramflux.data.CardText("t", "Pick a day", false),
                org.ramflux.data.CardButton("go", "Go", true, false),
                org.ramflux.data.CardButton("web", "Open", false, true),
                org.ramflux.data.CardUnsupported("pic", "Image")
            )
        )
        val live = CardItem("card:a", stamp, "a", root, "Fallback words", actionable = true, closed = false, outOfSync = false)
        val stale = live.copy(id = "card:b", ref = "b", actionable = false, outOfSync = true)
        compose.setContent {
            RamfluxTheme { ChatScreen(ChatState(loaded = true, rows = persistentListOf(live, stale)), { actions.add(it) }, clock = clock) }
        }
        compose.onAllNodesWithText("Not supported on this phone").assertCountEquals(2)
        compose.onAllNodesWithText("Fallback words").assertCountEquals(2)
        compose.onNodeWithText("Out of date. Waiting for the agent.").assertExists()
        compose.onAllNodesWithTag("card-link-web", useUnmergedTree = true).onFirst().assertIsNotEnabled()
        val buttons = compose.onAllNodesWithTag("card-button-go")
        buttons.assertCountEquals(2)
        // The out-of-date card's button is off; the current one acts (semantics, as it may be scrolled away).
        buttons.filter(isEnabled()).assertCountEquals(1)
        buttons.filter(isEnabled()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf(ChatAction.CardPress("a", "go")), actions.filterIsInstance<ChatAction.CardPress>()) }
    }

    @Test fun theReactionPlusOpensThePlatformEmojiPicker() {
        val state = ChatState(
            loaded = true,
            canSend = true,
            selectedId = "m",
            rows = persistentListOf(MessageItem("m", "Peer", false, false, UiText.Data("Hi"), stamp))
        )
        compose.setContent { RamfluxTheme { ChatScreen(state, {}, clock = clock) } }
        compose.onNodeWithContentDescription("More reactions").performClick()
        compose.onNodeWithTag("emoji-picker").assertExists()
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

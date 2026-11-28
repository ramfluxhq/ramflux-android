// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.*
import java.net.URI
import java.util.jar.JarFile
import kotlin.time.Duration.Companion.seconds
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.ClientOutcome
import org.ramflux.data.ClientProblem
import org.ramflux.data.ClientResult
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.ramflux.feature.onboarding.impl.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalTestApi::class)
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun screenEmitsAddressAndDiscoveryActionsWithoutStartingWork() {
        val actions = mutableListOf<OnboardingAction>()
        compose.setContent {
            RamfluxTheme {
                OnboardingScreen(
                    OnboardingState(page = OnboardingPage.ADDRESS, sessionReady = true, address = "node.example"),
                    { actions.add(it) }
                )
            }
        }
        compose.onNodeWithTag("address-details-toggle", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("operator-key").performScrollTo()
        compose.onNodeWithTag("operator-key").onChildren().filterToOne(hasSetTextAction()).performTextInput("trusted-operator-key")
        compose.runOnIdle {
            assertEquals(listOf(OnboardingAction.OperatorKey("trusted-operator-key")), actions)
            actions.clear()
        }
        compose.onNodeWithText("Verify node").performClick()
        compose.runOnIdle { assertEquals(listOf(OnboardingAction.ReviewAddress), actions) }
    }

    @Test fun tappingALockedAccountRowSelectsItLikeItsUnlockButton() {
        val actions = mutableListOf<OnboardingAction>()
        val account = AccountItem("account-1", "Work", UiText.Data("Locked"), unlocked = false)
        compose.setContent {
            RamfluxTheme {
                OnboardingScreen(
                    OnboardingState(
                        page = OnboardingPage.START,
                        sessionReady = true,
                        accountsLoaded = true,
                        accounts = persistentListOf(account)
                    ),
                    { actions.add(it) }
                )
            }
        }
        compose.onNodeWithTag("account-1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(OnboardingAction.SelectAccount("account-1")), actions) }
    }

    @Test fun failedReadDoesNotRenderEmptyAccountClaim() {
        compose.setContent {
            RamfluxTheme {
                OnboardingScreen(
                    OnboardingState(sessionReady = true, notice = OnboardingNotice(UiText.Data("Read failed"))),
                    {}
                )
            }
        }
        compose.onNodeWithText("Read failed").assertExists()
        compose.onNodeWithText("No accounts are saved on this device.").assertDoesNotExist()
    }

    @Test fun compactLargeTextWithImeInsetKeepsPinnedActionAndAddressVisible() {
        var imePixels = 0f
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 650.dp))) {
                val density = LocalDensity.current
                imePixels = with(density) { 180.dp.toPx() }
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    RamfluxTheme {
                        // The caller owns the insets, as the app's root page does.
                        Box(Modifier.fillMaxSize().testTag("window").windowInsetsPadding(WindowInsets(bottom = 180.dp))) {
                            OnboardingScreen(
                                OnboardingState(
                                    page = OnboardingPage.ADDRESS,
                                    sessionReady = true,
                                    address = "node.example"
                                ),
                                {}
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("node-address").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Verify node").assertIsDisplayed()
        val window = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("onboarding-footer").fetchSemanticsNode().boundsInRoot
        assertEquals("IME padding is applied exactly once", window.bottom - imePixels, footer.bottom, 1f)
        val header = compose.onNodeWithTag("onboarding-header").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("onboarding-body").performTouchInput { swipeUp() }
        assertEquals(header, compose.onNodeWithTag("onboarding-header").getUnclippedBoundsInRoot())
        compose.onNodeWithText("Verify node").assertIsDisplayed()
    }

    @Test fun secretFieldWipesThePreviousArrayOnEditAndOnDispose() {
        val buffer = ErasableBuffer()
        var shown by mutableStateOf(true)
        var root: View? = null
        compose.setContent {
            root = LocalView.current
            if (shown) {
                RamfluxTheme {
                    ErasableSecretField(
                        buffer,
                        stringResource(R.string.ui_account_code),
                        "data-passphrase",
                        enabled = true,
                        password = true,
                        limit = 128,
                        onEdited = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        val field = root!!.findTagged("data-passphrase")
        compose.runOnIdle { assertTrue(field.openConnection().second.commitText("first-secret", 1)) }
        compose.waitForIdle()
        val previous = buffer.owned()
        assertChars(previous, "first-secret")
        compose.runOnIdle {
            val connection = field.openConnection().second
            connection.setSelection(0, field.secretLength())
            assertTrue(connection.commitText("second-secret", 1))
        }
        compose.waitForIdle()
        assertTrue(previous.all { it == '\u0000' })
        val current = buffer.owned()
        assertChars(current, "second-secret")
        shown = false
        compose.waitForIdle()
        assertTrue(current.all { it == '\u0000' })
        assertTrue(buffer.isEmpty())
        assertTrue(field.isSecretEmpty())
    }

    @Test fun secretFieldCommitsDeletesAndClearZeroesTheBuffer() {
        val buffer = ErasableBuffer()
        var root: View? = null
        compose.setContent {
            root = LocalView.current
            RamfluxTheme {
                ErasableSecretField(
                    buffer,
                    stringResource(R.string.ui_account_code),
                    "data-passphrase",
                    enabled = true,
                    password = true,
                    limit = 128,
                    onEdited = {}
                )
            }
        }
        compose.waitForIdle()
        val field = root!!.findTagged("data-passphrase")
        val (info, connection) = field.openConnection()
        assertSecretInput(info, field)
        assertTrue(field.masksInput())
        assertTrue(field.onCheckIsTextEditor())
        assertNull(connection.getEditable())
        assertNoImeContext(connection)
        compose.runOnIdle { assertTrue(connection.commitText("first-secret", 1)) }
        compose.waitForIdle()
        assertChars(buffer.owned(), "first-secret")
        assertNoImeContext(connection)
        assertNull(connection.getEditable())
        val previous = buffer.owned()
        compose.runOnIdle { assertTrue(connection.deleteSurroundingText(6, 0)) }
        compose.waitForIdle()
        assertTrue(previous.all { it == '\u0000' })
        assertChars(buffer.owned(), "first-")
        assertNoImeContext(connection)
        val current = buffer.owned()
        compose.runOnIdle { buffer.clear() }
        compose.waitForIdle()
        assertTrue(current.all { it == '\u0000' })
        assertTrue(buffer.isEmpty())
        assertTrue(field.isSecretEmpty())
        assertNull(connection.getEditable())
        assertNoImeContext(connection)
    }

    @Test fun secretFieldTypesFromKeyEventsAndComposingText() {
        val buffer = ErasableBuffer()
        var root: View? = null
        compose.setContent {
            root = LocalView.current
            RamfluxTheme {
                ErasableSecretField(
                    buffer,
                    stringResource(R.string.ui_account_code),
                    "data-passphrase",
                    enabled = true,
                    password = true,
                    limit = 128,
                    onEdited = {}
                )
            }
        }
        compose.waitForIdle()
        val field = root!!.findTagged("data-passphrase")
        val connection = field.openConnection().second
        compose.runOnIdle { assertTrue(connection.setComposingText("wor", 1)) }
        compose.waitForIdle()
        val composed = buffer.owned()
        assertChars(composed, "wor")
        compose.runOnIdle { assertTrue(connection.setComposingText("word", 1)) }
        compose.waitForIdle()
        assertTrue(composed.all { it == '\u0000' })
        assertChars(buffer.owned(), "word")
        val composing = buffer.owned()
        compose.runOnIdle { assertTrue(connection.commitText("W", 1)) }
        compose.waitForIdle()
        assertTrue(composing.all { it == '\u0000' })
        assertChars(buffer.owned(), "W")
        val downA = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        val downB = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B)
        val charA = downA.unicodeChar
        val charB = downB.unicodeChar
        assertTrue(charA != 0)
        assertTrue(charB != 0)
        compose.runOnIdle {
            assertTrue(connection.sendKeyEvent(downA))
            assertTrue(connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)))
            assertTrue(connection.sendKeyEvent(downB))
            assertTrue(connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B)))
        }
        compose.waitForIdle()
        assertEquals(3, buffer.owned().size)
        assertEquals('W', buffer.owned()[0])
        assertEquals(charA.toChar(), buffer.owned()[1])
        assertEquals(charB.toChar(), buffer.owned()[2])
        val typed = buffer.owned()
        compose.runOnIdle {
            assertTrue(connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
            assertTrue(connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)))
        }
        compose.waitForIdle()
        assertTrue(typed.all { it == '\u0000' })
        assertEquals(2, buffer.owned().size)
        assertEquals('W', buffer.owned()[0])
        assertEquals(charA.toChar(), buffer.owned()[1])
        val downC = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C)
        val charC = downC.unicodeChar
        assertTrue(charC != 0)
        compose.runOnIdle {
            assertTrue(field.onKeyDown(KeyEvent.KEYCODE_C, downC))
            assertTrue(field.onKeyUp(KeyEvent.KEYCODE_C, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C)))
        }
        compose.waitForIdle()
        assertEquals(3, buffer.owned().size)
        assertEquals(charC.toChar(), buffer.owned()[2])
        compose.runOnIdle {
            val multiple = KeyEvent(0L, "de", 0, 0)
            assertTrue(field.onKeyMultiple(multiple.keyCode, multiple.repeatCount, multiple))
        }
        compose.waitForIdle()
        assertEquals(5, buffer.owned().size)
        assertEquals('d', buffer.owned()[3])
        assertEquals('e', buffer.owned()[4])
        compose.runOnIdle {
            connection.setSelection(0, 0)
            assertTrue(connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL)))
            assertTrue(connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL)))
        }
        compose.waitForIdle()
        assertEquals(4, buffer.owned().size)
        assertEquals(charA.toChar(), buffer.owned()[0])
        val selected = buffer.owned()
        compose.runOnIdle {
            connection.setSelection(0, field.secretLength())
            assertTrue(connection.sendKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
        }
        compose.waitForIdle()
        assertTrue(selected.all { it == '\u0000' })
        assertTrue(buffer.isEmpty())
        assertNoImeContext(connection)
        assertFalse(field.onKeyDown(KeyEvent.KEYCODE_ENTER, keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
    }

    @Test fun secretFieldPresentsAnEditFieldToAccessibilityWithoutItsText() {
        val buffer = ErasableBuffer()
        var root: View? = null
        compose.setContent {
            root = LocalView.current
            RamfluxTheme {
                ErasableSecretField(buffer, "Word 7", "challenge-0", enabled = true, password = true, limit = 64, onEdited = {})
            }
        }
        compose.waitForIdle()
        val field = root!!.findTagged("challenge-0")
        compose.runOnIdle { assertTrue(field.openConnection().second.commitText("hidden", 1)) }
        compose.waitForIdle()
        val info = android.view.accessibility.AccessibilityNodeInfo()
        compose.runOnIdle { field.onInitializeAccessibilityNodeInfo(info) }
        assertEquals("android.widget.EditText", info.className?.toString())
        assertTrue(info.isEditable)
        assertTrue(info.isPassword)
        assertEquals("Word 7", info.hintText?.toString())
        assertFalse(info.text?.toString().orEmpty().contains("hidden"))
    }

    @Test fun secretFieldDoesNotReflectOnFrameworkTypes() {
        val files = erasableClassFiles()
        val names = files.map { it.first }
        assertTrue(names.toString(), names.contains("ErasableSecretView.class"))
        assertTrue(names.toString(), names.contains("ErasableBuffer.class"))
        assertTrue(names.toString(), names.contains("ErasableSecretFieldKt.class"))
        val forbidden = listOf(
            "java/lang/reflect",
            "android/widget/EditText",
            "android/widget/TextView",
            "android/widget/Editor",
            "mAllowUndo",
            "forgetUndoRedo",
            "UndoInputFilter"
        )
        for ((name, bytes) in files) {
            for (needle in forbidden) {
                assertFalse(name + " " + needle, containsAscii(bytes, needle))
            }
        }
    }

    @Test fun challengePageConfirmsFromTheErasableFieldsAndClearsThem() {
        val actions = mutableListOf<OnboardingAction>()
        var root: View? = null
        compose.setContent {
            root = LocalView.current
            RamfluxTheme {
                OnboardingScreen(
                    OnboardingState(
                        page = OnboardingPage.CHALLENGE,
                        sessionReady = true,
                        challengePositions = persistentListOf(2, 15, 24)
                    ),
                    { actions.add(it) }
                )
            }
        }
        compose.waitForIdle()
        val view = root!!
        compose.runOnIdle {
            view.findTagged("challenge-0").commit("alpha")
            view.findTagged("challenge-1").commit("bravo")
            view.findTagged("challenge-2").commit("charlie")
            view.findTagged("account-code").commit("482915")
        }
        val word = view.findTagged("challenge-0")
        val secret = view.findTagged("account-code")
        val wordInfo = word.openConnection().first
        val secretInfo = secret.openConnection().first
        assertTrue(wordInfo.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0)
        assertEquals(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, secretInfo.inputType)
        // The words are masked until the per-field Show toggle reveals them.
        assertTrue(word.masksInput())
        compose.onNodeWithTag("challenge-0-show", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertFalse(view.findTagged("challenge-0").masksInput())
        assertTrue(secret.masksInput())
        assertTrue(wordInfo.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0)
        assertEquals(View.LAYOUT_DIRECTION_LTR, word.layoutDirection)
        assertEquals(View.LAYOUT_DIRECTION_LTR, secret.layoutDirection)
        compose.onNodeWithTag("confirm-account").performClick()
        compose.waitForIdle()
        val action = actions.single() as OnboardingAction.ConfirmChallenge
        assertBuffer(action.words[0], "alpha")
        assertBuffer(action.words[1], "bravo")
        assertBuffer(action.words[2], "charlie")
        assertBuffer(action.code, "482915")
        assertFalse(view.findTagged("challenge-0").isSecretEmpty())
        assertFalse(view.findTagged("account-code").isSecretEmpty())
    }

    @Test fun losingFocusDoesNotClearTheSecretBuffer() {
        var root: View? = null
        compose.setContent {
            root = LocalView.current
            RamfluxTheme {
                OnboardingScreen(
                    OnboardingState(
                        page = OnboardingPage.CHALLENGE,
                        sessionReady = true,
                        challengePositions = persistentListOf(2, 15, 24)
                    ),
                    {}
                )
            }
        }
        compose.waitForIdle()
        val field = root!!.findTagged("account-code")
        compose.runOnIdle {
            field.commit("482915")
            assertTrue(field.requestFocus())
            field.clearFocus()
            assertFalse(field.isFocused)
            assertFalse(field.isSecretEmpty())
        }
    }

    @Test fun leavingTheSecretFieldClearsItsBuffer() {
        val buffer = ErasableBuffer()
        buffer.replace("seal".toCharArray())
        var show by mutableStateOf(true)
        compose.setContent {
            if (show) {
                RamfluxTheme {
                    ErasableSecretField(buffer, "Data passphrase", "data-passphrase", true, true, 128) {}
                }
            }
        }
        compose.waitForIdle()
        assertFalse(buffer.isEmpty())
        show = false
        compose.waitForIdle()
        assertTrue(buffer.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmClickReadsLiveBuffersAndTheHandlerShowsTheCreatedAccount() = runTest(timeout = 5.seconds) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<ClientResult<String>>()
        val ceremony = FakeAccountCeremony()
        var stillEntered = false
        ceremony.confirmResult = { typed, secret ->
            stillEntered = typed.size == 3 && typed.all { it.isNotEmpty() } && secret.isNotEmpty()
            gate.await()
        }
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), FakeOnboardingSession(), ceremony)
        var root: View? = null
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            compose.setContent {
                root = LocalView.current
                RamfluxTheme {
                    OnboardingScreen(
                        OnboardingState(
                            page = OnboardingPage.CHALLENGE,
                            sessionReady = true,
                            challengePositions = persistentListOf(2, 15, 24)
                        ),
                        vm::onAction
                    )
                }
            }
            compose.waitForIdle()
            val view = checkNotNull(root)
            compose.runOnIdle {
                view.findTagged("challenge-0").commit("alpha")
                view.findTagged("challenge-1").commit("bravo")
                view.findTagged("challenge-2").commit("charlie")
                view.findTagged("account-code").commit("482915")
            }
            compose.onNodeWithTag("confirm-account").performClick()
            assertFalse(view.findTagged("challenge-0").isSecretEmpty())
            assertFalse(view.findTagged("account-code").isSecretEmpty())
            runCurrent()
            assertTrue(stillEntered)
            assertTrue(vm.state.value.busy)
            assertFalse(view.findTagged("challenge-0").isSecretEmpty())
            gate.complete(ClientResult.Ready("account-created"))
            runCurrent()
            assertEquals(1, ceremony.confirmations)
            assertEquals(OnboardingPage.CREATED, vm.state.value.page)
            assertEquals("account-created", vm.state.value.createdAccountId)
            assertTrue(view.findTagged("challenge-0").isSecretEmpty())
            assertTrue(view.findTagged("account-code").isSecretEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmFailureClearsTheFieldsAndDisablesTheButton() = runTest(timeout = 5.seconds) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ceremony = FakeAccountCeremony().apply {
            confirmResult = { _, _ ->
                ClientResult.Failed(
                    ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = "ValidationFailed")
                )
            }
        }
        val vm = OnboardingViewModel(FakeAccountAccess(), FakeNodeSetup(), FakeOnboardingSession(), ceremony)
        var shown by mutableStateOf(
            OnboardingState(
                page = OnboardingPage.CHALLENGE,
                sessionReady = true,
                challengePositions = persistentListOf(2, 15, 24)
            )
        )
        var root: View? = null
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.state.collect { incoming -> shown = shown.copy(busy = incoming.busy, notice = incoming.notice) }
            }
            runCurrent()
            compose.setContent {
                root = LocalView.current
                RamfluxTheme { OnboardingScreen(shown, vm::onAction) }
            }
            compose.waitForIdle()
            val view = checkNotNull(root)
            compose.runOnIdle {
                view.findTagged("challenge-0").commit("alpha")
                view.findTagged("challenge-1").commit("bravo")
                view.findTagged("challenge-2").commit("charlie")
                view.findTagged("account-code").commit("482915")
            }
            compose.onNodeWithTag("confirm-account").assertIsEnabled()
            compose.onNodeWithTag("confirm-account").performClick()
            runCurrent()
            compose.waitForIdle()
            assertEquals(1, ceremony.confirmations)
            assertEquals(OnboardingNoticeKind.ERROR, vm.state.value.notice?.kind)
            assertFalse(vm.state.value.busy)
            assertTrue(view.findTagged("challenge-0").isSecretEmpty())
            assertTrue(view.findTagged("account-code").isSecretEmpty())
            compose.onNodeWithTag("confirm-account").assertIsNotEnabled()
            compose.onNodeWithText("Unable to continue").assertIsDisplayed()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun challengeConfirmStaysInTheFooterAboveTheImeInset() {
        var imePixels = 0f
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 700.dp))) {
                val density = LocalDensity.current
                imePixels = with(density) { 220.dp.toPx() }
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    RamfluxTheme {
                        // The caller owns the insets, as the app's root page does.
                        Box(Modifier.fillMaxSize().testTag("window").windowInsetsPadding(WindowInsets(bottom = 220.dp))) {
                            OnboardingScreen(
                                OnboardingState(
                                    page = OnboardingPage.CHALLENGE,
                                    sessionReady = true,
                                    challengePositions = persistentListOf(2, 15, 24)
                                ),
                                {}
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("confirm-account").assertIsDisplayed()
        val window = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("onboarding-footer").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("confirm-account").fetchSemanticsNode().boundsInRoot
        assertEquals("IME padding is applied exactly once", window.bottom - imePixels, footer.bottom, 1f)
        assertTrue(button.top >= footer.top - 1f)
        assertTrue(button.bottom <= footer.bottom + 1f)
        val header = compose.onNodeWithTag("onboarding-header").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("onboarding-body").performTouchInput { swipeUp() }
        assertEquals(header, compose.onNodeWithTag("onboarding-header").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("confirm-account").assertIsDisplayed()
    }

    @Test fun createAccountStepShowsTheDisplayNameBoard() {
        val actions = mutableListOf<OnboardingAction>()
        var state by mutableStateOf(OnboardingState(page = OnboardingPage.CREATE, sessionReady = true))
        compose.setContent {
            RamfluxTheme { OnboardingScreen(state, { actions.add(it) }) }
        }
        compose.onAllNodesWithText("Create account").assertCountEquals(2)
        compose.onNodeWithText("Create an account").assertDoesNotExist()
        compose.onNodeWithText("Create the account").assertDoesNotExist()
        compose.onNodeWithText("Display name").assertExists()
        compose.onNodeWithText("This name is used for the new account. You can change it later.").assertExists()
        compose.onNodeWithText("Nodes").assertExists()
        compose.onNodeWithText("Ramflux official node").assertExists()
        compose.onNodeWithText("Selected node").assertExists()
        compose.onNodeWithText("Your recovery phrase is generated on this phone and shown only once. Get paper and a pen ready.")
            .assertExists()
        compose.onNodeWithText("\u6797\u6f88").assertDoesNotExist()
        compose.onNodeWithTag("begin-create").assertIsNotEnabled()
        compose.onNodeWithTag("n01-node").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Navigate up").performClick()
        compose.runOnIdle { assertEquals(OnboardingAction.Back, actions.single()) }
        compose.onNode(hasSetTextAction()).performTextInput("Ada")
        compose.runOnIdle {
            assertTrue(actions.filterIsInstance<OnboardingAction.DisplayName>().any { it.value.trim() == "Ada" })
            state = state.copy(displayName = "Ada")
        }
        compose.onNodeWithTag("begin-create").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(actions.contains(OnboardingAction.BeginCreate)) }
    }

    @Test fun createAccountStepShowsTheChosenNodeAddress() {
        compose.setContent {
            RamfluxTheme {
                OnboardingScreen(
                    OnboardingState(
                        page = OnboardingPage.CREATE,
                        sessionReady = true,
                        selectedNode = NodeItem("node-1", "https://node.example", false, false, true)
                    ),
                    {}
                )
            }
        }
        compose.onNodeWithText("https://node.example").assertExists()
        compose.onNodeWithText("Ramflux official node").assertDoesNotExist()
    }
}

private fun ErasableSecretView.commit(text: String) {
    assertTrue(openConnection().second.commitText(text, 1))
}

private fun ErasableSecretView.openConnection(): Pair<EditorInfo, BaseInputConnection> {
    val info = EditorInfo()
    val created = onCreateInputConnection(info)
    check(created is BaseInputConnection) { "secret field did not return its input connection" }
    return info to created
}

private fun assertSecretInput(info: EditorInfo, view: ErasableSecretView) {
    val type = info.inputType
    assertTrue(type and InputType.TYPE_CLASS_TEXT != 0)
    assertTrue(type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0)
    assertTrue(type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
    assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
    assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, view.importantForAutofill)
    assertFalse(view.isSaveEnabled)
    assertTrue(view.autofillHints.isNullOrEmpty())
}

private fun assertChars(actual: CharArray, expected: String) {
    assertEquals(expected.length, actual.size)
    for (index in expected.indices) assertEquals(expected[index], actual[index])
}

private fun assertBuffer(buffer: ErasableBuffer, expected: String) {
    val owned = buffer.snapshot()
    try {
        assertChars(owned, expected)
    } finally {
        owned.fill('\u0000')
    }
}

private fun assertNoImeContext(connection: BaseInputConnection) {
    assertNull(connection.getTextBeforeCursor(32, 0))
    assertNull(connection.getTextAfterCursor(32, 0))
    assertNull(connection.getSelectedText(0))
    assertNull(connection.getSurroundingText(32, 32, 0))
}

private fun keyEvent(action: Int, code: Int): KeyEvent = KeyEvent(
    0L,
    0L,
    action,
    code,
    0,
    0,
    KeyCharacterMap.VIRTUAL_KEYBOARD,
    0,
    0,
    0
)

private fun erasableClassFiles(): List<Pair<String, ByteArray>> {
    val marker = ErasableSecretView::class.java.getResource("ErasableSecretView.class")
    checkNotNull(marker) { "ErasableSecretView class resource missing" }
    check(marker.protocol == "jar") { marker.toString() }
    val path = marker.path
    val bang = path.indexOf('!')
    check(bang > 0) { path }
    val jarPath = URI.create(path.substring(0, bang)).path
    checkNotNull(jarPath) { path }
    val prefix = "org/ramflux/feature/onboarding/Erasable"
    return JarFile(jarPath).use { jar ->
        jar.entries().asSequence().filter { entry ->
            !entry.isDirectory && entry.name.startsWith(prefix) && entry.name.endsWith(".class")
        }.map { entry ->
            entry.name.substringAfterLast('/') to jar.getInputStream(entry).use { it.readBytes() }
        }.sortedBy { it.first }.toList()
    }
}

private fun containsAscii(bytes: ByteArray, needle: String): Boolean {
    val target = needle.toByteArray(Charsets.US_ASCII)
    if (target.isEmpty() || bytes.size < target.size) return false
    var index = 0
    while (index <= bytes.size - target.size) {
        var offset = 0
        while (offset < target.size && bytes[index + offset] == target[offset]) offset++
        if (offset == target.size) return true
        index++
    }
    return false
}

private fun View.findTagged(tag: String): ErasableSecretView {
    val found = findTaggedOrNull(tag)
    check(found != null) { "missing secret field $tag" }
    return found
}

private fun View.findTaggedOrNull(tag: String): ErasableSecretView? {
    if (this is ErasableSecretView && this.tag == tag) return this
    if (this !is ViewGroup) return null
    for (index in 0 until childCount) {
        getChildAt(index).findTaggedOrNull(tag)?.let { return it }
    }
    return null
}

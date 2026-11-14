// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.*
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
        compose.onNodeWithTag("operator-key").performScrollTo().performTextInput("trusted-operator-key")
        compose.runOnIdle {
            assertEquals(listOf(OnboardingAction.OperatorKey("trusted-operator-key")), actions)
            actions.clear()
        }
        compose.onNodeWithText("Verify node").performClick()
        compose.runOnIdle { assertEquals(listOf(OnboardingAction.ReviewAddress), actions) }
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
                        OnboardingScreen(
                            OnboardingState(
                                page = OnboardingPage.ADDRESS,
                                sessionReady = true,
                                address = "node.example"
                            ),
                            {},
                            contentWindowInsets = WindowInsets(bottom = 180.dp)
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("node-address").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Verify node").assertIsDisplayed()
        val window = compose.onNodeWithTag("onboarding-window").fetchSemanticsNode().boundsInRoot
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
                        stringResource(R.string.ui_data_passphrase),
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
        compose.runOnIdle { field.setText("first-secret") }
        compose.waitForIdle()
        val previous = buffer.owned()
        assertEquals("first-secret", String(previous))
        compose.runOnIdle { field.setText("second-secret") }
        compose.waitForIdle()
        assertTrue(previous.all { it == '\u0000' })
        val current = buffer.owned()
        assertEquals("second-secret", String(current))
        shown = false
        compose.waitForIdle()
        assertTrue(current.all { it == '\u0000' })
        assertTrue(buffer.isEmpty())
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
            view.findTagged("challenge-0").setText("alpha")
            view.findTagged("challenge-1").setText("bravo")
            view.findTagged("challenge-2").setText("charlie")
            view.findTagged("data-passphrase").setText("seal")
        }
        compose.onNodeWithTag("confirm-account").performScrollTo().performClick()
        compose.waitForIdle()
        val action = actions.single() as OnboardingAction.ConfirmChallenge
        assertEquals("alpha", String(action.words[0]))
        assertEquals("bravo", String(action.words[1]))
        assertEquals("charlie", String(action.words[2]))
        assertEquals("seal", String(action.passphrase))
        assertTrue(view.findTagged("challenge-0").text.isEmpty())
        assertTrue(view.findTagged("data-passphrase").text.isEmpty())
    }
}

private fun View.findTagged(tag: String): EditText {
    val found = findTaggedOrNull(tag)
    check(found != null) { "missing secret field $tag" }
    return found
}

private fun View.findTaggedOrNull(tag: String): EditText? {
    if (this is EditText && this.tag == tag) return this
    if (this !is ViewGroup) return null
    for (index in 0 until childCount) {
        getChildAt(index).findTaggedOrNull(tag)?.let { return it }
    }
    return null
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.textclassifier.TextClassifier
import android.widget.EditText
import android.widget.TextView
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

    @Test fun secretFieldDisablesUndoHistoryAndDropsThePreviousSecret() {
        val buffer = ErasableBuffer()
        var root: View? = null
        compose.setContent {
            root = LocalView.current
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
        compose.waitForIdle()
        val field = root!!.findTagged("data-passphrase")
        assertSealed(field, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue(field.transformationMethod is PasswordTransformationMethod)
        compose.runOnIdle {
            field.text.replace(0, field.text.length, "first-secret")
        }
        compose.waitForIdle()
        assertEquals("first-secret", field.text.toString())
        compose.runOnIdle { field.onTextContextMenuItem(android.R.id.undo) }
        assertEquals("first-secret", field.text.toString())
        compose.runOnIdle {
            field.text.replace(0, field.text.length, "second-secret")
        }
        compose.waitForIdle()
        compose.runOnIdle { field.onTextContextMenuItem(android.R.id.undo) }
        assertEquals("second-secret", field.text.toString())
        assertNoSecret(field, "first-secret")
        compose.runOnIdle { buffer.clear() }
        compose.waitForIdle()
        assertTrue(field.text.isEmpty())
        assertTrue(buffer.isEmpty())
        assertNoSecret(field, "first-secret")
        assertNoSecret(field, "second-secret")
        assertTrue(field.text.filters.none { it.javaClass.simpleName == "UndoInputFilter" })
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
        val word = view.findTagged("challenge-0")
        val secret = view.findTagged("data-passphrase")
        assertSealed(word, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        assertSealed(secret, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse(word.transformationMethod is PasswordTransformationMethod)
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

private fun assertNoSecret(field: EditText, secret: String) {
    val leaked = editorStrings(field).filter { it.contains(secret) }
    assertTrue(leaked.joinToString("|"), leaked.isEmpty())
}

private fun assertSealed(field: EditText, variation: Int) {
    assertFalse(field.isSuggestionsEnabled)
    assertFalse(field.isSaveEnabled)
    assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, field.importantForAutofill)
    assertEquals(TextClassifier.NO_OP, field.textClassifier)
    assertTrue(field.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
    assertTrue(field.inputType and variation != 0)
    assertTrue(field.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    assertTrue(field.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
    val editor = editorOf(field)
    val allow = editor.javaClass.getDeclaredField("mAllowUndo")
    allow.isAccessible = true
    assertFalse(allow.getBoolean(editor))
    assertTrue(field.text.filters.none { it.javaClass.simpleName == "UndoInputFilter" })
}

private fun editorOf(field: EditText): Any {
    val editor = TextView::class.java.getDeclaredField("mEditor")
    editor.isAccessible = true
    return checkNotNull(editor.get(field))
}

private fun editorStrings(field: EditText): List<String> {
    val found = ArrayList<String>()
    val seen = HashSet<Int>()
    fun walk(value: Any?, depth: Int) {
        if (value == null || depth > 8) return
        if (value is String) {
            found.add(value)
            return
        }
        if (value is View || value is android.content.Context || value is Class<*>) return
        if (value is Boolean || value is Number || value is Char || value is Enum<*>) return
        val typeName = value.javaClass.name
        val skipped = typeName.startsWith("java.") || typeName.startsWith("jdk.") ||
            typeName.startsWith("sun.") || typeName.startsWith("kotlin.")
        if (skipped) return
        if (!seen.add(System.identityHashCode(value))) return
        if (value is Array<*>) {
            value.forEach { walk(it, depth + 1) }
            return
        }
        var type: Class<*>? = value.javaClass
        var levels = 0
        while (type != null && type != Any::class.java && levels < 8) {
            for (member in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(member.modifiers)) continue
                val child = try {
                    member.isAccessible = true
                    member.get(value)
                } catch (_: Exception) {
                    null
                }
                if (child is String) found.add(child) else walk(child, depth + 1)
            }
            type = type.superclass
            levels++
        }
    }
    walk(editorOf(field), 0)
    return found
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

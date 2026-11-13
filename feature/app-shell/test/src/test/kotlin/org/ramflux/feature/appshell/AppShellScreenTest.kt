// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.navigation3.runtime.rememberNavBackStack
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.render
import org.ramflux.feature.app_shell.impl.R
import org.ramflux.feature.appshell.api.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppShellScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun screenOnlyEmitsTypedActionsAndDisplaysUnavailableState() {
        var selected: AppShellAction? = null
        compose.setContent {
            RamfluxTheme {
                AppShellScreen(
                    AppShellState(
                        ready = true,
                        loading = false,
                        notice = ShellNotice(UiText.Data("Not available yet"), UiText.Data("Feature unavailable"))
                    ),
                    {
                        selected = it
                    }
                )
            }
        }
        compose.onNodeWithText("Set up your account").performClick()
        compose.runOnIdle { assertEquals(AppShellAction.Open(org.ramflux.data.ClientFeature.ONBOARDING), selected) }
        compose.onNodeWithText("Feature unavailable").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun everyThemeHasOneLocalizedSelectableRadioControl() {
        var locale by mutableStateOf("en")
        val state = AppShellState(loading = false)
        var labels = listOf<String>()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                RamfluxTheme {
                    val current = listOf(stringResource(R.string.ui_use_device_setting)) + state.themes.map { it.label.render() }
                    SideEffect { labels = current }
                    AppShellScreen(state, {})
                }
            }
        }
        for (language in listOf("en", "zh-CN")) {
            compose.runOnIdle { locale = language }
            compose.waitForIdle()
            for ((index, label) in labels.withIndex()) {
                val node = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                node.performScrollTo().assertHasClickAction()
                if (index == 0) node.assertIsSelected() else node.assertIsNotSelected()
            }
        }
    }

    @Test fun navigationKeysSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var size = 0
        var push: (() -> Unit)? = null
        restoration.setContent {
            val stack = rememberNavBackStack(ShellHome)
            val currentSize = stack.size
            SideEffect {
                size = currentSize
                push = { stack.add(FeatureRoute("onboarding")) }
            }
        }
        compose.runOnIdle { push?.invoke() }
        compose.waitForIdle()
        assertEquals(2, size)
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(2, size) }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.feature.appshell.api.MoreRoute
import org.ramflux.feature.appshell.api.NavigationEditorRoute
import org.ramflux.feature.appshell.api.NoticesRoute
import org.ramflux.navigation.AccountScoped
import org.ramflux.navigation.RoutePolicy
import org.ramflux.navigation.TopLevelNavigator
import org.ramflux.navigation.rememberTopLevelNavigator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A route key that declares itself account scoped, as feature route keys do. */
private data class AccountRoute(val name: String) : NavKey, AccountScoped

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockedTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lockingDropsAccountRoutesFromTheTabStacksAndUnlockingDoesNotRestoreThem() {
        var ready by mutableStateOf(true)
        lateinit var tabs: TopLevelNavigator
        // Keys of the API module stand in for tab roots; AccountRoute plays an account-scoped route.
        val policy = RoutePolicy()
        compose.setContent {
            tabs = rememberTopLevelNavigator(listOf(MoreRoute, NavigationEditorRoute))
            AppShellNavigation(
                vm = AppShellViewModel(FakeSession(), FakeAppearance()),
                home = { BasicText("home") },
                shellReady = ready,
                policy = policy,
                tabs = tabs
            )
        }
        compose.runOnIdle {
            tabs.openDetail(AccountRoute("thread"))
            tabs.select(1)
            tabs.push(NoticesRoute)
            tabs.push(AccountRoute("contacts"))
        }
        ready = false
        compose.waitForIdle()
        ready = true
        compose.runOnIdle {
            assertEquals(listOf<NavKey>(MoreRoute), tabs.stacks[0].toList())
            assertEquals(listOf<NavKey>(NavigationEditorRoute, NoticesRoute), tabs.stacks[1].toList())
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.ramflux.designsystem.*
import org.ramflux.feature.contacts.impl.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingRealRowEmitsOnlyTypedIdentityAction() {
        val actions = mutableListOf<ContactsAction>()
        val row = PersonItem("row", "peer-id", "Actual name", uiText(R.string.contacts_unverified), false)
        compose.setContent { RamfluxTheme { ContactsScreen(ContactsState(loaded = true, rows = persistentListOf(row)), actions::add) } }
        compose.onNodeWithText("Actual name").performClick()
        compose.runOnIdle { assertEquals(listOf(ContactsAction.Select("row")), actions) }
    }

    @Test fun failedChineseReadHasNoEmptyListClaimAndUsesLocalizedRetry() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("zh-CN"))) {
                RamfluxTheme { ContactsScreen(ContactsState(problem = uiText(R.string.contacts_read_failed, UiText.Number(8))), {}) }
            }
        }
        compose.onNodeWithText("无法读取联系人").assertExists()
        compose.onNodeWithText("暂无联系人").assertDoesNotExist()
        compose.onNodeWithText("刷新").assertHasClickAction()
    }

    @Test fun actualViewModelLocaleSwitchDoesNotRepeatNativeReadOrTreatPeerTextAsKey() {
        var locale by mutableStateOf("en")
        val repo = FakeContactDirectory().apply {
            reading = { ClientResult.Ready(listOf(ContactSummary("row", "peer-id", "contacts_verified", false))) }
        }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                val state by vm.state.collectAsState()
                RamfluxTheme { ContactsScreen(state, vm::onAction) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.loaded }
        val reads = repo.reads
        compose.onNodeWithText("Safety number not verified").assertExists()
        compose.runOnIdle { locale = "zh-CN" }
        compose.onNodeWithText("安全号码尚未核对").assertExists()
        compose.onNodeWithText("contacts_verified").assertExists()
        compose.runOnIdle { assertEquals(reads, repo.reads) }
    }
}

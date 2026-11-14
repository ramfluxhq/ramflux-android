// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.*
import org.ramflux.feature.contacts.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
class ContactsScreenshotTest(private val kind: String, private val dark: Boolean, private val locale: String, private val width: Int) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}-{2}-{3}")
        fun cases(): List<Array<Any>> = buildList {
            val kinds =
                listOf(
                    "contacts", "strangers", "sent", "detail", "add", "safety", "decline", "batch",
                    "empty", "loading", "error", "account", "confirmation", "unknown"
                )
            for (kind in kinds) {
                for (dark in listOf(false, true)) for (locale in listOf("en", "zh-CN", "en-XA")) for (width in listOf(400, 900)) {
                    add(arrayOf(kind, dark, locale, width))
                }
            }
        }
    }

    @Test fun screen() {
        val person = PersonItem(
            "request", "peer-id", "Actual peer name", uiText(R.string.contacts_pending), true, true,
            "Peer supplied note", UiText.Quantity(R.plurals.contacts_messages, 3u), UiText.DateTime(1700000000)
        )
        val populated = ContactsState(accountLabel = "Work", loaded = true, rows = persistentListOf(person), selected = person)
        val state = when (kind) {
            "contacts" -> populated.copy(
                rows = persistentListOf(person.copy(request = false, inbound = false, status = uiText(R.string.contacts_unverified)))
            )
            "strangers" -> populated.copy(tab = ContactsTab.STRANGERS)
            "sent" -> populated.copy(
                tab = ContactsTab.SENT,
                rows = persistentListOf(person.copy(inbound = false, status = uiText(R.string.contacts_accepted)))
            )
            "detail" -> populated.copy(page = ContactsPage.DETAIL)
            "add" -> populated.copy(page = ContactsPage.ADD, address = "Peer identity draft")
            "safety" -> populated.copy(page = ContactsPage.SAFETY, unavailable = uiText(R.string.contacts_action_unavailable))
            "decline" -> populated.copy(page = ContactsPage.DECLINE)
            "batch" -> populated.copy(page = ContactsPage.BATCH, tab = ContactsTab.STRANGERS, selectedIds = persistentSetOf("request"))
            "confirmation" -> populated.copy(
                approval = ContactApproval("account-id", uiText(R.string.contacts_accept), persistentListOf("request-id"))
            )
            "unknown" -> populated.copy(
                uncertain = persistentListOf(uiText(R.string.contacts_unknown_operation, UiText.Data("operation-id")))
            )
            "loading" -> ContactsState(loading = true)
            "error" -> ContactsState(problem = uiText(R.string.contacts_read_failed, UiText.Number(8)))
            "account" -> ContactsState(accountRequired = true)
            else -> ContactsState(loaded = true)
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 600.dp))) {
                    RamfluxTheme(dark) { ContactsScreen(state, {}) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/contacts-$kind-$dark-$locale-$width.png")
    }
}

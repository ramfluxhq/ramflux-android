// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
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
import org.ramflux.feature.groups.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
class GroupsScreenshotTest(
    private val page: GroupsPage,
    private val dark: Boolean,
    private val locale: String,
    private val width: Int,
    private val kind: String
) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}-{3}-{4}")
        fun cases(): List<Array<Any>> = buildList {
            for (page in GroupsPage.entries) for (dark in listOf(
                false,
                true
            )) for (locale in listOf(
                "en",
                "zh-CN",
                "en-XA"
            )) for (width in listOf(400, 900)) add(arrayOf(page, dark, locale, width, "rows"))
            for (kind in listOf(
                "loading",
                "error",
                "account",
                "waiting",
                "confirmation"
            )) for (dark in listOf(
                false,
                true
            )) for (locale in listOf(
                "en",
                "zh-CN",
                "en-XA"
            )) for (width in listOf(400, 900)) add(arrayOf(GroupsPage.LIST, dark, locale, width, kind))
        }
    }

    @Test fun screen() {
        val invite = InviteRow("g", "Actual invitation", "creator", "inviter", UiText.DateTime(1800000000))
        val base =
            GroupsState(
                page = page, loaded = true, account = "Work",
                groups = persistentListOf(
                    GroupRow("g", "Actual group", UiText.Quantity(R.plurals.groups_member_count, 2u))
                ),
                selected = "g", title = "Actual group", epoch = UiText.Unsigned(7u), authorityFresh = true, canManage = true,
                invitations = persistentListOf(invite), selectedInvitation = invite, name = "New group",
                members = persistentListOf(MemberRow("peer", "Peer", "root", "device", uiText(R.string.groups_member), true, true)),
                contacts = persistentListOf(GroupRow("contact", "Accepted contact", UiText.Empty)),
                requests = persistentListOf(RequestRow("request", uiText(R.string.groups_waiting), UiText.DateTime(1800000000)))
            )
        val state = when (kind) {
            "loading" -> GroupsState(loading = true)
            "error" -> GroupsState(problem = uiText(R.string.groups_error_code, UiText.Number(8)))
            "account" -> GroupsState(accountRequired = true)
            "waiting" -> base.copy(notice = uiText(R.string.groups_waiting))
            "confirmation" -> base.copy(
                confirmation = GroupFacts(uiText(R.string.groups_create), persistentListOf(UiText.Data("account"), UiText.Data("group")))
            )
            else -> base
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 600.dp))) {
                    RamfluxTheme(dark) { GroupsScreen(state, {}) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/groups-$page-$dark-$locale-$width-$kind.png")
    }
}

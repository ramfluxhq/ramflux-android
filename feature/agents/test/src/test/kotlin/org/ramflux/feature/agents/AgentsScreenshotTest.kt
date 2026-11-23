// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.*
import org.ramflux.feature.agents.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1400dp-mdpi")
class AgentsScreenshotTest(private val kind: String, private val dark: Boolean, private val locale: String, private val width: Int) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}-{3}")
        fun cases(): List<Array<Any>> = buildList {
            val kinds =
                listOf("list", "pending", "empty", "no-match", "loading", "error", "account", "profile", "edit", "invalid", "review")
            for (kind in kinds)
                for (dark in listOf(false, true)) for (locale in listOf("en", "zh-CN", "en-XA")) for (width in listOf(400, 900)) {
                    add(arrayOf(kind, dark, locale, width))
                }
        }
    }

    @Test fun screen() {
        val rows = persistentListOf(
            AgentRow("nova", "Nova", "Research partner", "orbit", uiText(R.string.agents_status_active), true),
            AgentRow("atlas", "Atlas", "Planning assistant", "gem", uiText(R.string.agents_status_active), true),
            AgentRow("mira", "Mira", "Writing partner", "bot", uiText(R.string.agents_status_deleted), false)
        )
        val profile = AgentProfile(
            rows.first(),
            "orbit",
            uiText(R.string.agents_role_named),
            uiText(R.string.agents_access_owner),
            uiText(R.string.agents_access_contacts)
        )
        val list = AgentsState(loaded = true, account = "Work", rows = rows, loadedCount = rows.size)
        val state = when (kind) {
            "pending" -> list.copy(
                pending = PendingRow("request-1", "Planner", uiText(R.string.agents_pending_waiting), UiText.DateTime(1_800_000_000))
            )
            "empty" -> AgentsState(loaded = true)
            "no-match" -> list.copy(rows = persistentListOf(), query = "zzz", hasMore = true)
            "loading" -> AgentsState(loading = true)
            "error" -> AgentsState(problem = uiText(R.string.agents_read_failure, UiText.Number(8)))
            "account" -> AgentsState(accountRequired = true)
            "profile" -> list.copy(page = AgentsPage.PROFILE, selected = profile)
            "edit" -> list.copy(page = AgentsPage.EDIT, selected = profile, name = "Nova", responsibility = "Research partner")
            "invalid" -> list.copy(
                page = AgentsPage.EDIT,
                selected = profile,
                name = "",
                responsibility = "",
                nameIssue = uiText(R.string.agents_name_required),
                responsibilityIssue = uiText(R.string.agents_responsibility_required)
            )
            "review" -> list.copy(
                page = AgentsPage.PROFILE,
                selected = profile,
                review = AgentReview(uiText(R.string.agents_delete), "Nova", uiText(R.string.agents_delete_explanation), "account")
            )
            else -> list
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, if (width == 900) 1000.dp else 700.dp))
                ) {
                    RamfluxTheme(dark) { AgentsScreen(state, {}, rooted = kind != "edit") }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/agents-$kind-$dark-$locale-$width.png")
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
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
import org.ramflux.feature.tasks.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1400dp-mdpi")
class TasksScreenshotTest(private val kind: String, private val dark: Boolean, private val locale: String, private val width: Int) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}-{3}")
        fun cases(): List<Array<Any>> = buildList {
            for (kind in listOf(
                "contexts", "list", "detail", "detail-large", "permissions", "permission", "receipt",
                "empty", "loading", "error", "account", "pending", "confirmation", "unavailable", "running", "needs_me", "done",
                "failed", "cancelled", "resolution", "stalled"
            ))
                for (dark in listOf(
                    false,
                    true
                )) for (locale in listOf("en", "zh-CN", "en-XA")) for (width in listOf(400, 900)) add(arrayOf(kind, dark, locale, width))
        }
    }

    @Test fun screen() {
        val permission =
            TaskPermissionRow(
                "grant:g",
                "Tool",
                uiText(R.string.tasks_grant),
                persistentListOf(uiText(R.string.tasks_readonly), uiText(R.string.tasks_limited))
            )
        val receipt =
            TaskPermissionRow(
                "receipt:r",
                "Task",
                uiText(R.string.tasks_receipt),
                persistentListOf(uiText(R.string.tasks_version, UiText.Unsigned(ULong.MAX_VALUE)))
            )
        val status = when (kind) {
            "running" -> R.string.tasks_running
            "needs_me" -> R.string.tasks_needs_me
            "done" -> R.string.tasks_done
            "failed" -> R.string.tasks_failed
            "cancelled" -> R.string.tasks_cancelled
            else -> R.string.tasks_proposed
        }
        val terminal = kind in setOf("done", "failed", "cancelled")
        val row = taskItem().copy(
            status = uiText(status),
            canDecide = !terminal && kind !in setOf("running", "needs_me"),
            canCancel = !terminal,
            result = if (kind == "done") "Actual result" else null,
            pendingResolution = kind == "resolution",
            stalled = kind == "stalled"
        )
        val detail = TasksState(page = TasksPage.DETAIL, conversation = "conversation", selected = row, fresh = true, loaded = true)
        val state = when (kind) {
            "contexts" -> TasksState(contexts = persistentListOf(TaskContextRow("c", "Conversation")), fresh = true, loaded = true)
            "list" -> TasksState(page = TasksPage.LIST, rows = persistentListOf(taskItem()), loaded = true, fresh = true)
            "permissions" -> TasksState(
                page = TasksPage.PERMISSIONS,
                permissions = persistentListOf(permission, receipt),
                loaded = true,
                fresh = true
            )
            "permission", "receipt" -> TasksState(
                page = TasksPage.PERMISSION,
                selectedPermission = if (kind == "receipt") receipt else permission,
                loaded = true,
                fresh = true
            )
            "empty" -> TasksState(page = TasksPage.LIST, loaded = true, fresh = true)
            "loading" -> TasksState(loading = true)
            "error" -> TasksState(problem = uiText(R.string.tasks_error_code, UiText.Number(8)))
            "account" -> TasksState(accountRequired = true)
            "pending" -> detail.copy(pendingId = "original-operation", pendingFacts = persistentListOf(UiText.Data("Original facts")))
            "confirmation" -> detail.copy(
                confirmation = TaskConfirmation(
                    "original-operation",
                    uiText(R.string.tasks_accept),
                    persistentListOf(UiText.Data("Original facts"))
                )
            )
            "unavailable" -> detail.copy(unavailable = true)
            else -> detail
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, if (width == 900) 1000.dp else 700.dp))
                ) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (kind == "detail-large") 2f else 1f)) {
                        RamfluxTheme(dark) { TasksScreen(state, {}) }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/tasks-$kind-$dark-$locale-$width.png")
    }
}

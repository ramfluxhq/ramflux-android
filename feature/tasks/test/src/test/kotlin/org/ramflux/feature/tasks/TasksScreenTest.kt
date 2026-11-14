// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.TaskDecision
import org.ramflux.designsystem.*
import org.ramflux.feature.tasks.impl.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
fun taskItem() = TaskRow(
    "task", "Actual task", uiText(R.string.tasks_proposed), ULong.MAX_VALUE,
    persistentListOf(uiText(R.string.tasks_version, UiText.Unsigned(ULong.MAX_VALUE))),
    (1..20).map { TaskStepRow("step-$it", "Actual step $it", uiText(R.string.tasks_needs_me), true) }.toPersistentList(),
    null, true, true, false, false
)

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TasksScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fixedDecisionFooterStaysVisibleOutsideLongStepsAndEmitsOnlyTypedIntent() {
        val actions = mutableListOf<TasksAction>()
        compose.setContent {
            RamfluxTheme {
                TasksScreen(
                    TasksState(page = TasksPage.DETAIL, selected = taskItem(), fresh = true),
                    actions::add
                )
            }
        }
        compose.onNodeWithText("Accept task").assertIsDisplayed().performClick()
        compose.onNodeWithText("Decline task").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(TasksAction.Prepare(TaskDecision.ACCEPT), TasksAction.Prepare(TaskDecision.DECLINE)),
                actions
            )
        }
    }

    @Test fun hostFactsAndSingleTapConfirmationBindExactOperationWhileDenyOnlyDismisses() {
        val actions = mutableListOf<TasksAction>()
        val facts =
            TaskConfirmation(
                "operation-A",
                uiText(R.string.tasks_accept),
                persistentListOf(UiText.Data("account-A"), UiText.Data("task-A"))
            )
        compose.setContent { RamfluxTheme { TasksScreen(TasksState(fresh = true, confirmation = facts), actions::add) } }
        compose.onNodeWithText("account-A").assertExists()
        compose.onNodeWithText("task-A").assertExists()
        compose.onNodeWithTag("host-confirmation-confirm").performTouchInput { click() }
        compose.onNodeWithTag("host-confirmation-deny").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(TasksAction.Confirm("operation-A"), TasksAction.Deny), actions) }
    }

    @Test fun destructiveDialogEnterDismissesBeforeExplicitConfirmPreservesOriginalOperation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeTasks()
        val session = FakeTaskSession()
        val vm = TasksViewModel(repo, FakeTaskContexts(), FakeTaskAccounts(), session)
        var rendered by mutableStateOf(TasksState())
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { rendered = it } }
            runCurrent()
            vm.onAction(TasksAction.Visible(true))
            runCurrent()
            vm.onAction(TasksAction.Conversation("c"))
            runCurrent()
            vm.onAction(TasksAction.Select("task"))
            runCurrent()
            compose.setContent { RamfluxTheme { TasksScreen(rendered, vm::onAction) } }
            for (decision in listOf(TaskDecision.DECLINE, TaskDecision.CANCEL)) {
                val count = repo.mutations.size
                vm.onAction(TasksAction.Prepare(decision))
                runCurrent()
                compose.waitForIdle()
                compose.runOnIdle {
                    val root = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).window?.decorView
                    val deny = requireNotNull(root?.findViewWithTag<android.widget.Button>("host-confirmation-deny"))
                    assertTrue(deny.isFocused)
                    val time = android.os.SystemClock.uptimeMillis() + 1
                    deny.dispatchKeyEvent(
                        android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0)
                    )
                    deny.dispatchKeyEvent(
                        android.view.KeyEvent(time, time + 1, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0)
                    )
                }
                runCurrent()
                assertEquals(count, repo.mutations.size)
                assertNull(rendered.confirmation)
                vm.onAction(TasksAction.Prepare(decision))
                runCurrent()
                val id = requireNotNull(rendered.confirmation).id
                compose.runOnIdle {
                    val root = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).window?.decorView
                    requireNotNull(root?.findViewWithTag<android.widget.Button>("host-confirmation-confirm")).performClick()
                }
                runCurrent()
                assertEquals(count + 1, repo.mutations.size)
                assertEquals(id, repo.mutations.last().operationId)
                assertEquals(decision, repo.mutations.last().decision)
            }
        } finally {
            vm.onAction(TasksAction.Visible(false))
            session.background()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun chineseConfirmationKeepsNativeDenyFocusAndExactActionSummary() {
        val facts = TaskConfirmation("op", uiText(R.string.tasks_cancel), persistentListOf(UiText.Data("task")))
        compose.setContent {
            AppLanguageProvider(
                "zh-CN"
            ) { RamfluxTheme { TasksScreen(TasksState(confirmation = facts, fresh = true), {}) } }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).window?.decorView
            val deny = requireNotNull(root?.findViewWithTag<android.widget.Button>("host-confirmation-deny"))
            val confirm = requireNotNull(root?.findViewWithTag<android.widget.Button>("host-confirmation-confirm"))
            assertTrue(deny.isFocused)
            assertEquals("确认：取消任务", confirm.contentDescription.toString())
        }
    }

    @Test fun chineseReadFailureNeverClaimsEmptyTasks() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("zh-CN"))) {
                RamfluxTheme {
                    TasksScreen(
                        TasksState(page = TasksPage.LIST, problem = uiText(R.string.tasks_error_code, UiText.Number(8))),
                        {}
                    )
                }
            }
        }
        compose.onNodeWithText("无法读取任务").assertExists()
        compose.onNodeWithText("此会话中暂无任务").assertDoesNotExist()
    }

    @Test fun actualReducerLocaleChangeDoesNotRefetchAndPeerTitlesAreData() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var locale by mutableStateOf("en")
        val repo = FakeTasks().apply {
            paging = {
                org.ramflux.data.ClientResult.Ready(
                    org.ramflux.data.TaskPage(listOf(taskRecord().copy(title = "tasks_title")), null)
                )
            }
        }
        val session = FakeTaskSession()
        val vm = TasksViewModel(repo, FakeTaskContexts(), FakeTaskAccounts(), session)
        var rendered by mutableStateOf(TasksState())
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { rendered = it } }
            runCurrent()
            vm.onAction(TasksAction.Visible(true))
            runCurrent()
            compose.setContent {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                    RamfluxTheme { TasksScreen(rendered, vm::onAction) }
                }
            }
            compose.onNodeWithText("Actual conversation").performClick()
            runCurrent()
            assertEquals(listOf("task"), rendered.rows.map { it.id })
            val reads = repo.reads
            compose.runOnIdle { locale = "zh-CN" }
            compose.onNodeWithText("任务").assertExists()
            compose.onNodeWithText("tasks_title").assertExists()
            assertEquals(reads, repo.reads)
        } finally {
            vm.onAction(TasksAction.Visible(false))
            session.background()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}

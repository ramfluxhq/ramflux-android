// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.*
import org.ramflux.feature.contacts.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [28, 35])
class HostConfirmationInputTest(private val locale: String) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("zh-CN"))
    }

    @get:Rule val compose = createComposeRule()
    private fun open(actions: MutableList<ContactsAction>) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(AccessibilityManager::class.java)
        Shadows.shadowOf(manager).setEnabled(true)
        Shadows.shadowOf(manager).setTouchExplorationEnabled(true)
        val approval = ContactApproval("account", uiText(R.string.contacts_accept), persistentListOf("request"))
        compose.setContent {
            AppLanguageProvider(locale) {
                RamfluxTheme { ContactsScreen(ContactsState(approval = approval), actions::add) }
            }
        }
        compose.waitForIdle()
    }
    private fun button(tag: String): Button = requireNotNull(ShadowDialog.getLatestDialog())
        .window?.decorView?.findViewWithTag<Button>(tag) ?: error("native host action missing")

    @Test fun denyStartsWithKeyboardAndAccessibilityFocusAndConfirmNamesLocalizedAction() {
        val actions = mutableListOf<ContactsAction>()
        open(actions)
        compose.runOnIdle {
            val deny = button("host-confirmation-deny")
            val confirm = button("host-confirmation-confirm")
            assertTrue("Deny keyboard focus", deny.isFocused)
            assertTrue("Deny accessibility focus", deny.isAccessibilityFocused)
            assertFalse(confirm.isFocused)
            assertFalse(confirm.isAccessibilityFocused)
            assertEquals(if (locale == "zh-CN") "确认：通过" else "Confirm: Accept", confirm.contentDescription.toString())
            assertTrue(actions.isEmpty())
            deny.performClick()
            assertEquals(listOf(ContactsAction.Deny), actions)
        }
    }

    @Test fun repeatedHeldOrOrphanActivationCannotConfirmButFreshPressAndAccessibilityCan() {
        val actions = mutableListOf<ContactsAction>()
        open(actions)
        compose.runOnIdle {
            val confirm = button("host-confirmation-confirm")
            confirm.requestFocusFromTouch()
            var time = SystemClock.uptimeMillis() + 1
            fun send(action: Int, repeat: Int, down: Long, at: Long) {
                confirm.dispatchKeyEvent(KeyEvent(down, at, action, KeyEvent.KEYCODE_ENTER, repeat))
            }
            send(KeyEvent.ACTION_UP, 0, time - 1, time)
            assertTrue("Orphan release must not confirm", actions.isEmpty())
            send(KeyEvent.ACTION_DOWN, 0, time, time)
            send(KeyEvent.ACTION_DOWN, 1, time, time + 1)
            send(KeyEvent.ACTION_UP, 0, time, time + 2)
            assertTrue("Repeating key must not confirm on release", actions.isEmpty())
            time += ViewConfiguration.getLongPressTimeout() + 10
            send(KeyEvent.ACTION_DOWN, 0, time, time)
            send(KeyEvent.ACTION_UP, 0, time, time + ViewConfiguration.getLongPressTimeout())
            assertTrue("Held key without repeats must not confirm", actions.isEmpty())
            time += ViewConfiguration.getLongPressTimeout() + 10
            send(KeyEvent.ACTION_DOWN, 0, time, time)
            send(KeyEvent.ACTION_UP, 0, time, time + 1)
            assertEquals(listOf(ContactsAction.Confirm), actions)
            assertTrue(confirm.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf(ContactsAction.Confirm, ContactsAction.Confirm), actions)
        }
    }
}

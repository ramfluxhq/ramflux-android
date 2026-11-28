// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import android.app.Dialog
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.PopupWindow
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.android.coverState
import org.ramflux.data.AppLockPhase
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientSnapshot
import org.ramflux.designsystem.AppLockCoverFocus
import org.ramflux.designsystem.AppLockCoverReader
import org.ramflux.designsystem.AppLockCoverState
import org.ramflux.designsystem.AppLockFocusGuard
import org.ramflux.designsystem.CoverAwareDialog
import org.ramflux.designsystem.CoverAwareDropdownMenu
import org.ramflux.designsystem.LocalAppLockCoverFocus
import org.ramflux.designsystem.LocalAppLockCoverReader
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalInAppLockCover
import org.ramflux.designsystem.appLockWindowContext
import org.ramflux.designsystem.rememberAppLockCheckedResult
import org.ramflux.designsystem.rememberAppLockCoveredNow
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app-lock backstop for windows outside the gate (AppLockLibraryWindows.kt), the emoji picker's skin-tone popup
 * (round-4 finding M4), picker results in a locked account (F10) and a menu expanded while covered (F9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class AppLockLibraryWindowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var covered by mutableStateOf(false)
    private var coverDialog by mutableStateOf(false)
    private var rawPopup by mutableStateOf(false)
    private var menuExpanded by mutableStateOf(false)
    private var menuDismissals = 0
    private var rawPopupView: View? = null
    private var shellView: View? = null
    private var emoji: EmojiPickerView? = null
    private val picks = mutableListOf<String>()
    private val state = MutableStateFlow(AppLockCoverState.DOWN)
    private val reader = AppLockCoverReader({ state.value }, state)
    private var result: ((String) -> Unit)? = null
    private val delivered = mutableListOf<String>()
    private val refused = mutableListOf<String>()

    @Composable
    private fun Harness(withEmoji: Boolean = false) {
        val coverFocus = remember { AppLockCoverFocus() }
        CompositionLocalProvider(
            LocalAppLockCovered provides covered,
            LocalAppLockCoverFocus provides coverFocus,
            LocalAppLockCoverReader provides reader
        ) {
            Box(Modifier.fillMaxSize()) {
                AppLockFocusGuard(covered)
                result = rememberAppLockCheckedResult(onRefused = { refused += it }) { delivered += it }
                val coveredNow = rememberAppLockCoveredNow()
                Column {
                    AndroidView(factory = { c -> Button(c).also { shellView = it } }, modifier = Modifier.fillMaxWidth().height(48.dp))
                    if (withEmoji) {
                        val context = LocalContext.current
                        AndroidView(
                            factory = {
                                EmojiPickerView(context.appLockWindowContext()).apply {
                                    setOnEmojiPickedListener { if (!coveredNow()) picks += it.emoji }
                                }.also { emoji = it }
                            },
                            modifier = Modifier.fillMaxWidth().height(400.dp)
                        )
                    }
                    CoverAwareDropdownMenu(expanded = menuExpanded, onDismissRequest = {
                        menuDismissals++
                        menuExpanded = false
                    }) { Text("menu item") }
                }
                if (rawPopup) {
                    // Not reachable in production (the window guard refuses it); a window the gate does not know.
                    androidx.compose.ui.window.Popup {
                        rawPopupView = androidx.compose.ui.platform.LocalView.current
                        Text("ungated")
                    }
                }
                if (covered && coverDialog) {
                    CompositionLocalProvider(LocalInAppLockCover provides true) {
                        CoverAwareDialog(onDismissRequest = { coverDialog = false }) { Text("cover dialog") }
                    }
                }
            }
        }
    }

    private fun rise() {
        compose.runOnIdle {
            covered = true
            state.value = AppLockCoverState.LOCKED
        }
        compose.waitForIdle()
    }

    private fun unlock() {
        compose.runOnIdle {
            covered = false
            state.value = AppLockCoverState.DOWN
        }
        compose.waitForIdle()
    }

    /** Several sweep intervals of the guard's loop on the paused main clock. */
    private fun sweepTicks() {
        repeat(5) {
            compose.mainClock.advanceTimeBy(150)
            compose.waitForIdle()
        }
    }

    /** Drawn and touchable: attached, visible, opaque and without FLAG_NOT_TOUCHABLE. */
    private fun shown(root: View): Boolean {
        val params = root.layoutParams as? WindowManager.LayoutParams
        return root.parent != null && root.visibility == View.VISIBLE &&
            (params == null || (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 && params.alpha != 0f))
    }

    /** The M4 mechanism: a platform PopupWindow opened from a shell view (tracked context on API 28, any on 29+). */
    @Test(timeout = 60_000)
    fun aShellPopupWindowWorksUncoveredAndIsClosedWhenTheCoverRises() {
        compose.setContent { Harness() }
        var clicks = 0
        lateinit var popup: PopupWindow
        compose.runOnIdle {
            val content = Button(compose.activity.appLockWindowContext()).apply { setOnClickListener { clicks++ } }
            popup = PopupWindow(content, 200, 100, false)
            popup.isTouchable = true
            popup.isOutsideTouchable = true
            popup.showAtLocation(shellView!!, Gravity.CENTER, 0, 0)
        }
        sweepTicks()
        assertTrue("the popup shows while the cover is down", popup.isShowing)
        compose.runOnIdle { popup.contentView.performClick() }
        assertEquals(1, clicks)
        rise()
        assertFalse("closed in the frame the cover rises", popup.isShowing)
        unlock()
        assertFalse("not back after unlock", popup.isShowing)
    }

    @Test(timeout = 60_000)
    fun aPopupWindowOpenedWhileCoveredIsClosed() {
        compose.setContent { Harness() }
        rise()
        lateinit var popup: PopupWindow
        compose.runOnIdle {
            popup = PopupWindow(Button(compose.activity.appLockWindowContext()), 200, 100, false)
            popup.showAtLocation(shellView!!, Gravity.CENTER, 0, 0)
        }
        sweepTicks()
        assertFalse(popup.isShowing)
        unlock()
    }

    /** A window that Back does not close is hidden while covered (no surface, no touch, no focus) and given back on unlock. */
    @Test(timeout = 60_000)
    @Config(sdk = [35])
    fun aNonCancellableLibraryDialogIsHiddenThenRestored() {
        compose.setContent { Harness() }
        lateinit var dialog: Dialog
        compose.runOnIdle {
            dialog = Dialog(compose.activity).apply {
                setCancelable(false)
                setContentView(Button(context))
                show()
            }
        }
        sweepTicks()
        val root = dialog.window!!.decorView
        assertTrue(shown(root))
        rise()
        sweepTicks()
        assertFalse("hidden under the cover", shown(root))
        val flags = (root.layoutParams as WindowManager.LayoutParams).flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        unlock()
        assertTrue("given back on unlock", shown(root))
        compose.runOnIdle { dialog.dismiss() }
    }

    @Test(timeout = 60_000)
    @Config(sdk = [35])
    fun anUngatedComposePopupIsHiddenUnderTheCover() {
        compose.setContent { Harness() }
        compose.runOnIdle { rawPopup = true }
        compose.waitForIdle()
        val root = rawPopupView!!.rootView
        assertTrue(shown(root))
        rise()
        sweepTicks()
        assertFalse(shown(root))
        val params = root.layoutParams as WindowManager.LayoutParams
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        unlock()
        compose.runOnIdle { rawPopup = false }
    }

    @Test(timeout = 60_000)
    @Config(sdk = [35])
    fun theCoversOwnDialogIsNotSwept() {
        compose.setContent { Harness() }
        rise()
        compose.runOnIdle { coverDialog = true }
        compose.waitForIdle()
        sweepTicks()
        assertTrue(coverDialog)
        assertEquals(1, compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        compose.runOnIdle { coverDialog = false }
        unlock()
    }

    /** F9: a menu expanded while the cover is up is dismissed and does not show after unlock. */
    @Test(timeout = 60_000)
    @Config(sdk = [35])
    fun aMenuExpandedWhileCoveredIsDismissed() {
        compose.setContent { Harness() }
        rise()
        compose.runOnIdle { menuExpanded = true }
        compose.waitForIdle()
        assertFalse(menuExpanded)
        assertEquals(1, menuDismissals)
        unlock()
        assertEquals("no menu window after unlock", 0, compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("menu item")).fetchSemanticsNodes().isEmpty())
    }

    /** M4 with the real androidx.emoji2 picker: its skin-tone popup closes when the cover rises; a covered pick is dropped. */
    @Test(timeout = 60_000)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theEmojiPickersVariantPopupClosesAndCoveredPicksAreDropped() {
        // Robolectric never finishes the EmojiCompat font load; without EmojiCompat the picker loads its data at once.
        val emojiCompat = Class.forName("androidx.emoji2.text.EmojiCompat")
        emojiCompat.getMethod("reset", emojiCompat).invoke(null, null)
        compose.setContent { Harness(withEmoji = true) }
        val popup = openVariantPopup()
        assertNotNull("the library opened its variant popup", popup)
        val root = popup!!
        assertTrue(shown(root))
        rise()
        assertFalse("the variant popup is closed when the cover rises", root.parent != null)
        // A pick that still reaches the picker while covered is dropped.
        compose.runOnIdle { firstEmojiView(emoji!!)?.performClick() }
        assertEquals(0, picks.size)
        unlock()
        compose.runOnIdle { firstEmojiView(emoji!!)?.performClick() }
        assertEquals("picks work again after unlock", 1, picks.size)
    }

    /** F10: a picker result is delivered with the cover down, refused when locked, held while the lock is unread. */
    @Test(timeout = 60_000)
    @Config(sdk = [35])
    fun pickerResultsAreRefusedInALockedAccountAndHeldWhileUnread() {
        compose.setContent { Harness() }
        compose.runOnIdle { result!!("down") }
        compose.runOnIdle {
            state.value = AppLockCoverState.LOCKED
            result!!("locked")
        }
        compose.runOnIdle {
            state.value = AppLockCoverState.UNREAD
            result!!("unread-then-open")
        }
        compose.waitForIdle()
        assertEquals("held until the lock is read", listOf("down"), delivered.toList())
        compose.runOnIdle { state.value = AppLockCoverState.DOWN }
        compose.waitForIdle()
        compose.runOnIdle {
            state.value = AppLockCoverState.UNREAD
            result!!("unread-then-locked")
        }
        compose.waitForIdle()
        compose.runOnIdle { state.value = AppLockCoverState.LOCKED }
        compose.waitForIdle()
        assertEquals(listOf("down", "unread-then-open"), delivered)
        assertEquals(listOf("locked", "unread-then-locked"), refused)
        compose.runOnIdle { state.value = AppLockCoverState.DOWN }
    }

    @Test
    @Config(sdk = [35])
    fun theSessionCoverStateSplitsUnreadFromLocked() {
        val open = ClientSnapshot(phase = ClientPhase.OPEN)
        assertEquals(AppLockCoverState.DOWN, open.copy(appLock = AppLockPhase.OPEN).coverState())
        assertEquals(AppLockCoverState.UNREAD, open.copy(appLock = AppLockPhase.UNKNOWN).coverState())
        assertEquals(AppLockCoverState.LOCKED, open.copy(appLock = AppLockPhase.LOCKED).coverState())
        assertEquals(AppLockCoverState.LOCKED, ClientSnapshot(phase = ClientPhase.FAILED, lockHeld = true).coverState())
        assertEquals(AppLockCoverState.DOWN, ClientSnapshot(phase = ClientPhase.FAILED).coverState())
    }

    private fun allViews(view: View): List<View> {
        val group = view as? ViewGroup ?: return listOf(view)
        return listOf(view) + (0 until group.childCount).flatMap { allViews(group.getChildAt(it)) }
    }

    private fun firstEmojiView(root: View): View? = allViews(root).firstOrNull { it.javaClass.simpleName == "EmojiView" && it.isClickable }

    /** Every window root of the process (WindowManagerGlobal on API 28, which has no WindowInspector). */
    private fun windowRoots(): List<View> = if (Build.VERSION.SDK_INT >= 29) {
        WindowInspector.getGlobalWindowViews()
    } else {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>).toList()
    }

    private fun isRecycler(view: View): Boolean {
        val classes = generateSequence<Class<*>>(view.javaClass) { it.superclass }
        return classes.any { it.simpleName == "RecyclerView" }
    }

    private fun isUntriedEmoji(view: View, tried: Set<View>): Boolean =
        view.javaClass.simpleName == "EmojiView" && view.isLongClickable && view !in tried

    /** Long-presses emoji until the library opens its variant popup; the picker loads its data off the main thread. */
    private fun openVariantPopup(): View? {
        val before = windowRoots().toSet()
        val tried = HashSet<View>()
        repeat(50) {
            compose.mainClock.advanceTimeBy(50)
            compose.waitForIdle()
            val body = allViews(emoji!!).filter(::isRecycler).maxByOrNull { it.height }
            if (body != null) {
                repeat(20) {
                    val candidates = allViews(body).filter { isUntriedEmoji(it, tried) }
                    for (candidate in candidates) {
                        tried += candidate
                        compose.runOnIdle { candidate.performLongClick() }
                        compose.waitForIdle()
                        windowRoots().firstOrNull { it !in before }?.let { return it }
                    }
                    compose.runOnIdle { body.scrollBy(0, body.height / 2) }
                    compose.waitForIdle()
                }
                compose.runOnIdle { body.scrollToPosition(0) }
            }
            Thread.sleep(200)
        }
        return null
    }

    private fun View.scrollToPosition(position: Int) {
        javaClass.getMethod("scrollToPosition", Int::class.javaPrimitiveType).invoke(this, position)
    }
}

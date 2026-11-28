// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.delay

/**
 * True while the app-lock cover is up (the lock is on or its state is unread). Windows and menus are not shown then,
 * and an open window is closed through its own callback, so none reopens when the cover goes: every window is composed
 * through [AppLockGatedWindow] (see AppLockWindows.kt), which `scripts/check-app-lock-windows.py` enforces.
 */
val LocalAppLockCovered = staticCompositionLocalOf { false }

/**
 * True inside the cover's own surface: native views composed there may hold focus while the shell is covered, and windows
 * composed there stay shown. Only the cover provides it (`scripts/check-app-lock-windows.py`).
 */
val LocalInAppLockCover = compositionLocalOf { false }

/**
 * The cover's focus anchor: a non-text focus target at the top of the cover. It takes focus when the cover rises and
 * when focus has to be moved out of the shell, so no keyboard is shown and no text field holds the focus.
 */
class AppLockCoverFocus {
    internal val anchor = FocusRequester()
    internal var attached = false

    /** Makes the receiver the cover's focus anchor: a non-text focus target that [take] moves focus to. */
    @Composable
    fun Modifier.coverAnchor(): Modifier {
        DisposableEffect(this@AppLockCoverFocus) {
            attached = true
            onDispose { attached = false }
        }
        return this.focusRequester(anchor).focusable()
    }

    /** Moves focus to the anchor. False when the cover is not composed (nothing to take focus). */
    fun take(): Boolean = attached && runCatching { anchor.requestFocus() }.getOrDefault(false)
}

/** The host supplies one so the shell guard and the cover share the anchor; without one the cover makes its own. */
val LocalAppLockCoverFocus = staticCompositionLocalOf<AppLockCoverFocus?> { null }

/**
 * While [covered], the content under the app-lock cover is not part of the accessibility tree (screen readers, UI
 * automation): only what is drawn above it is exposed. Composition and state stay as they are. Native views inside the
 * content are hidden by [AppLockFocusGuard].
 */
fun Modifier.hiddenWhileCovered(covered: Boolean): Modifier = if (covered) clearAndSetSemantics { } else this

/**
 * While [covered], Compose focus cannot enter the content under the app-lock cover: a focus group whose `onEnter`
 * cancels the change (nested scrollables and lazy lists included, which `canFocus` alone does not reach), a backstop
 * that pushes out any focus that still arrives inside it and moves focus to the cover, and key events that are consumed.
 * Native views and the keyboard are handled by [AppLockFocusGuard], not here.
 */
@Composable
fun Modifier.focusBlockedWhileCovered(covered: Boolean): Modifier {
    if (!covered) return this
    val focus = LocalFocusManager.current
    val cover = LocalAppLockCoverFocus.current
    return this.focusProperties {
        canFocus = false
        onEnter = { cancelFocusChange() }
    }.onFocusChanged {
        if (it.hasFocus) {
            focus.clearFocus(force = true)
            cover?.take()
        }
    }.focusGroup().onPreviewKeyEvent { true }
}

/** The app-lock cover's focus trap: focus cannot leave it, so Tab, arrow keys and the D-pad cycle inside it. */
fun Modifier.focusTrap(): Modifier = this.focusProperties { onExit = { cancelFocusChange() } }.focusGroup()

/** Native views that belong to the cover (the code field). Main thread only. */
private val coverViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

/** Marks [this] as part of the cover, so [AppLockFocusGuard] lets it hold focus while covered. */
fun View.markAsAppLockCoverView() {
    coverViews += this
}

private fun View.inCover(): Boolean {
    var v: View? = this
    while (v != null) {
        if (v in coverViews) return true
        v = v.parent as? View
    }
    return false
}

private fun View.containsCoverView(): Boolean = inCover() || (
    this is ViewGroup && (0 until childCount).any {
        getChildAt(it).containsCoverView()
    }
    )

private fun View.isBelow(root: View): Boolean {
    var v: View? = this
    while (v != null) {
        if (v === root) return true
        v = v.parent as? View
    }
    return false
}

/**
 * While [covered], native views (`AndroidView`) of the shell cannot keep focus, and the keyboard cannot reach them.
 * Compose focus is blocked by [focusBlockedWhileCovered]; this closes the View-system routes:
 * - Any focus that lands on a view of this window that is not part of the cover (marked with [markAsAppLockCoverView])
 *   is moved at once, in the focus-change callback, to the cover's [AppLockCoverFocus] anchor (to the Compose host first
 *   when the cover is not composed yet), and the keyboard is hidden through the window token, a keyboard a native view
 *   raised included, so no input connection stays open to a shell view. The keyboard is also hidden when the cover rises.
 *   The focus part covers the activity window only; other windows follow [AppLockGatedWindow] or the sweep below. The focus-change listener sees every
 *   View-system route (`View.requestFocus()`, a view's own auto-focus), which is why it is used instead of making the
 *   host view non-focusable: the cover's own code field is a native view in the same window, so blocking the whole host
 *   would block the cover too.
 * - The holders of the shell's native views are also hidden from accessibility services (`importantForAccessibility`)
 *   and refuse focus (`descendantFocusability`), so they cannot be reached before the listener acts. This is checked in
 *   Robolectric, not with TalkBack on a device. The previous values are restored when the cover goes.
 * - Windows that a library view opens by itself, outside the gate, are closed or hidden by [LibraryWindowSweeper]
 *   (AppLockLibraryWindows.kt): when the cover rises, right after a window is added through an [appLockWindowContext]
 *   (API 28), when this window loses window focus (a focusable window came up), and every [SWEEP_INTERVAL_MS] while the
 *   activity is started. A window hidden this way is given back when the cover goes.
 */
@Composable
fun AppLockFocusGuard(covered: Boolean) {
    val host = LocalView.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val cover = LocalAppLockCoverFocus.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sweeper = remember(host) { LibraryWindowSweeper(host) }
    LaunchedEffect(sweeper, covered, lifecycle) {
        if (!covered) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                sweeper.sweep()
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }
    DisposableEffect(host, covered) {
        if (!covered) return@DisposableEffect onDispose { }
        var active = true
        fun sweepSoon() {
            if (active) sweeper.sweep()
        }
        // A tracked root is swept once it is attached (View.post runs after the attach); a lost window focus means a
        // focusable window came up above this one.
        val stopTracking = TrackedWindowRoots.listen { root -> root.post { sweepSoon() } }
        val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (!hasFocus) host.post { sweepSoon() } }
        val root = host.rootView
        val blocker = NativeShellBlocker(host)
        val imm = host.context.getSystemService(InputMethodManager::class.java)
        fun hideKeyboard() {
            // Compose's controller reaches only Compose's own input session; a keyboard a native view raised is hidden
            // through the window token.
            keyboard?.hide()
            imm?.hideSoftInputFromWindow(host.windowToken, 0)
        }
        var evicting = false
        fun evict(view: View) {
            if (evicting || view === host || !view.isBelow(host) || view.inCover()) return
            evicting = true
            try {
                // The anchor first, so focus does not end on the cover's code field (a text editor can raise the
                // keyboard when the window regains focus). Only when the anchor cannot take it (the cover is not composed
                // yet) does the host take focus, to be moved to the anchor once the cover composes.
                if (cover?.take() != true || view.hasFocus()) {
                    if (!host.requestFocus()) view.clearFocus()
                    focus.clearFocus(force = true)
                    cover?.take()
                }
                hideKeyboard()
            } finally {
                evicting = false
            }
        }
        val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus -> newFocus?.let(::evict) }
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { blocker.sweep() }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        root.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
        blocker.sweep()
        sweeper.sweep()
        root.findFocus()?.let(::evict)
        hideKeyboard()
        onDispose {
            active = false
            stopTracking()
            root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
            root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            root.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
            blocker.release()
            sweeper.restore()
        }
    }
}

/** How often [AppLockFocusGuard] looks for library windows while the cover is up and the activity is started. */
private const val SWEEP_INTERVAL_MS = 100L

/** Blocks focus and accessibility on the holders of the shell's native views while the cover is up, and restores them. */
private class NativeShellBlocker(private val host: View) {
    private class Saved(val focusability: Int, val accessibility: Int)

    private val seen = WeakHashMap<ViewGroup, Saved?>()

    fun sweep() {
        val parent = host as? ViewGroup ?: return
        for (i in 0 until parent.childCount) {
            val handler = parent.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until handler.childCount) {
                val holder = handler.getChildAt(j) as? ViewGroup ?: continue
                if (holder in seen) continue
                if (holder.containsCoverView()) {
                    seen[holder] = null
                    continue
                }
                seen[holder] = Saved(holder.descendantFocusability, holder.importantForAccessibility)
                holder.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                holder.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
    }

    fun release() {
        for ((holder, saved) in seen) {
            if (saved == null) continue
            holder.descendantFocusability = saved.focusability
            holder.importantForAccessibility = saved.accessibility
        }
        seen.clear()
    }
}

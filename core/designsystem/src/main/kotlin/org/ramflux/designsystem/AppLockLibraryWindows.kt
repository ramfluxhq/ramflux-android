// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.Collections
import java.util.WeakHashMap

/*
 * The runtime backstop of the app-lock window rule, for windows the app's own code does not compose: a library view in
 * the shell that opens a window by itself (androidx.emoji2 EmojiPickerView opens its skin-tone popup as a platform
 * PopupWindow). The gate (AppLockWindows.kt) and `scripts/check-app-lock-windows.py` cannot see those.
 *
 * While the cover is up, [LibraryWindowSweeper] goes over every window root of the process and closes, or else hides,
 * each one that is neither the activity's main window nor a window of the cover. It finds the roots through
 * - `WindowInspector.getGlobalWindowViews()` on API 29+ (public API, every root of the process), and
 * - on API 28 (minSdk, no WindowInspector), the roots added through a context made by [appLockWindowContext]: the shell
 *   creates each library view that opens windows in such a context, so its windows are still known there.
 * The cover's own windows are composed through the gated wrappers, which mark them ([CoverWindowMark]); a root that
 * holds a marked view is never touched, nor is a window of another activity.
 */

/** Views inside the cover's own windows (the content of a gated wrapper composed under [LocalInAppLockCover]). */
private val coverWindowViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

/**
 * Marks the window this is composed in as a window of the cover, so [LibraryWindowSweeper] leaves it alone. The gated
 * wrappers call it first in their content; it does nothing outside the cover. A Compose window composes its content in
 * the same main-thread call that attaches its root (`onAttachedToWindow`), and the sweeper skips roots that are not
 * attached yet, so no sweep runs between the attach and the mark.
 */
@Composable
internal fun CoverWindowMark() {
    if (!LocalInAppLockCover.current) return
    val view = LocalView.current
    // Set during composition, not in an effect: an effect runs a frame later, and a sweep may run before it.
    coverWindowViews += view
    DisposableEffect(view) { onDispose { coverWindowViews -= view } }
}

/** Window roots added through an [appLockWindowContext]. Main thread only. */
internal object TrackedWindowRoots {
    private val roots: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val listeners = mutableListOf<(View) -> Unit>()

    fun added(root: View) {
        roots += root
        listeners.toList().forEach { it(root) }
    }

    fun removed(root: View) {
        roots -= root
    }

    /** The tracked roots that are still in a window (a root's parent is its window's ViewRootImpl). */
    fun inWindows(): List<View> = roots.filter { it.parent != null }

    fun listen(listener: (View) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
}

/**
 * A window manager that reports the roots added through it to [TrackedWindowRoots]. Used on API 28 only: Kotlin
 * delegation forwards the interface's abstract methods, and API 28's WindowManager has no default methods that a later
 * level added (window metrics, blur, ...), which a wrapper would otherwise answer with the interface's own default.
 */
private class TrackingWindowManager(private val base: WindowManager) : WindowManager by base {
    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        base.addView(view, params)
        TrackedWindowRoots.added(view)
    }

    override fun removeView(view: View) {
        TrackedWindowRoots.removed(view)
        base.removeView(view)
    }

    override fun removeViewImmediate(view: View) {
        TrackedWindowRoots.removed(view)
        base.removeViewImmediate(view)
    }
}

/**
 * A context for a library view that opens its own windows (a `PopupWindow` takes its window manager from its content
 * view's context). On API 28 the windows it opens are reported to the app-lock backstop through this context's window
 * manager; the inflater (cloned into this context) and the theme follow the wrapped context.
 */
private class AppLockWindowContext(base: Context) : ContextThemeWrapper(base, base.theme) {
    private val windows by lazy(LazyThreadSafetyMode.NONE) {
        (baseContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.let(::TrackingWindowManager)
    }

    override fun getSystemService(name: String): Any? = if (name == Context.WINDOW_SERVICE) windows else super.getSystemService(name)
}

/**
 * The context to create a library view that opens its own windows with, so the app-lock backstop knows its windows.
 * On API 29+ the backstop lists every window of the process, so this is the context itself; on API 28 it is wrapped.
 * `scripts/check-app-lock-windows.py` requires it for the library views it names.
 */
fun Context.appLockWindowContext(): Context =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || this is AppLockWindowContext) this else AppLockWindowContext(this)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.activity()
    else -> null
}

/**
 * Closes or hides every window root of the process that is above the cover without belonging to it. Main thread only.
 * - A root is skipped when it is the activity's main window ([host]'s root), when it holds a view marked by
 *   [CoverWindowMark], when it is the main window of any activity, when its context belongs to another activity, and
 *   while it is not attached yet (it draws and takes input only once attached; it is seen on the next sweep).
 * - Every other root is asked to close once, with a Back key (a PopupWindow, a cancellable Dialog and the Compose
 *   windows close on it). One that is still there is hidden: its root view is made invisible (the window has no
 *   surface, so it shows nothing and takes no input) and its window is made not touchable, not focusable, transparent
 *   and secure. A root is never removed from the window manager: its owner's own dismiss would then fail.
 * - [restore] gives a hidden window back its visibility, flags and alpha when the cover goes down: it was opened before
 *   the lock by whoever unlocked, and its owner still holds it as open.
 */
internal class LibraryWindowSweeper(private val host: View) {
    private class Saved(val visibility: Int, val flags: Int, val alpha: Float)

    private val hidden = WeakHashMap<View, Saved>()
    private val asked: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val windowManager = host.context.getSystemService(WindowManager::class.java)
    private val activity = host.context.activity()

    fun sweep() {
        val main = host.rootView
        val cover = coverWindowViews.filter { it.parent != null || it.isAttachedToWindow }.mapTo(HashSet()) { it.rootView }
        for (root in roots()) {
            if (root === main || root in cover || !root.isAttachedToWindow || !ours(root)) continue
            neutralize(root)
        }
    }

    fun restore() {
        for ((root, saved) in hidden) {
            if (root.parent == null) continue
            root.visibility = saved.visibility
            val params = root.layoutParams as? WindowManager.LayoutParams ?: continue
            params.flags = (params.flags and HIDDEN_FLAGS.inv()) or (saved.flags and HIDDEN_FLAGS)
            params.alpha = saved.alpha
            runCatching { windowManager?.updateViewLayout(root, params) }
        }
        hidden.clear()
        asked.clear()
    }

    private fun roots(): Set<View> {
        val all = LinkedHashSet<View>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) all += WindowInspector.getGlobalWindowViews()
        all += TrackedWindowRoots.inWindows()
        return all
    }

    private fun ours(root: View): Boolean {
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return false
        if (params.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) return false
        val owner = root.context.activity()
        return owner == null || owner === activity
    }

    private fun neutralize(root: View) {
        if (asked.add(root)) {
            back(root)
            if (root.parent == null) return
        }
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val blank = root.visibility != View.VISIBLE && params.flags and HIDDEN_FLAGS == HIDDEN_FLAGS && params.alpha == 0f
        if (blank) return
        if (root !in hidden) hidden[root] = Saved(root.visibility, params.flags, params.alpha)
        root.visibility = View.INVISIBLE
        params.flags = params.flags or HIDDEN_FLAGS
        params.alpha = 0f
        runCatching { windowManager?.updateViewLayout(root, params) }
    }

    private fun back(root: View) {
        val now = SystemClock.uptimeMillis()
        runCatching {
            root.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            root.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        }
    }

    private companion object {
        const val HIDDEN_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SECURE
    }
}

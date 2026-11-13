// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.ramflux.generated.RFTheme

/** Host-owned actions use real platform accessibility focus, separate from keyboard focus. */
@Composable
fun HostConfirmationActions(
    identity: Any,
    denyLabel: String,
    confirmLabel: String,
    confirmDescription: String,
    enabled: Boolean = true,
    onDeny: () -> Unit,
    onConfirm: () -> Unit
) {
    val theme = LocalRFTheme.current
    val deny by rememberUpdatedState(onDeny)
    val confirm by rememberUpdatedState(onConfirm)
    key(identity) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)) {
            AndroidView(
                factory = { HostActionButton(it, initialDeny = true).apply { tag = "host-confirmation-deny" } },
                modifier = Modifier.testTag("host-confirmation-deny").heightIn(min = (theme.gap * 3.25f).dp),
                onReset = null,
                onRelease = { it.release() },
                update = { button -> button.bind(denyLabel, denyLabel, true, false, theme) { deny() } }
            )
            AndroidView(
                factory = { HostActionButton(it, initialDeny = false).apply { tag = "host-confirmation-confirm" } },
                modifier = Modifier.testTag("host-confirmation-confirm").heightIn(min = (theme.gap * 3.25f).dp),
                onReset = null,
                onRelease = { it.release() },
                update = { button -> button.bind(confirmLabel, confirmDescription, enabled, true, theme) { confirm() } }
            )
        }
    }
}
private class HostActionButton(context: Context, private val initialDeny: Boolean) : Button(context) {
    private var armedKey: Int? = null
    private var repeated = false
    private var pressTime = 0L
    private var attachedAt = 0L
    private val focusDeny = Runnable {
        if (isAttachedToWindow) {
            requestFocusFromTouch()
            performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedAt = SystemClock.uptimeMillis()
        if (initialDeny) post(focusDeny)
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(focusDeny)
        resetKey()
        super.onDetachedFromWindow()
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        resetKey()
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }
    private fun activation(key: Int) =
        key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!activation(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0 || armedKey != null || event.downTime < attachedAt) {
            repeated = true
        } else {
            armedKey = keyCode
            pressTime = event.downTime
            repeated = false
        }
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!activation(keyCode)) return super.onKeyUp(keyCode, event)
        val duration = event.eventTime - pressTime
        val allow = armedKey == keyCode && !repeated && !event.isCanceled && event.repeatCount == 0 &&
            duration >= 0 && duration < ViewConfiguration.getLongPressTimeout()
        resetKey()
        if (allow && isEnabled && hasFocus()) performClick()
        return true
    }
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (activation(keyCode)) {
            resetKey()
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }
    private fun resetKey() {
        armedKey = null
        repeated = false
        pressTime = 0
    }
    fun release() {
        removeCallbacks(focusDeny)
        resetKey()
        setOnClickListener(null)
    }
    fun bind(label: String, description: String, enabled: Boolean, primary: Boolean, theme: RFTheme, click: () -> Unit) {
        text = label
        contentDescription = description
        isEnabled = enabled
        isAllCaps = false
        isFocusable = true
        isFocusableInTouchMode = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isScreenReaderFocusable = true
        val density = resources.displayMetrics.density
        val gap = theme.gap * density
        minimumHeight = (gap * 3.25f).toInt()
        minimumWidth = (gap * 3).toInt()
        setPadding(gap.toInt(), (gap / 2).toInt(), gap.toInt(), (gap / 2).toInt())
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, theme.size_mobile_body)
        typeface = Typeface.create(typeface, Typeface.NORMAL)
        setTextColor((if (primary) theme.on_primary else theme.text).toArgb())
        fun shape(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = theme.radius_button * density
            setColor((if (primary) theme.primary else theme.surface).toArgb())
            setStroke(
                (gap / if (focused) 8 else 16).toInt(),
                (
                    if (focused) {
                        if (primary) theme.on_primary else theme.text
                    } else {
                        theme.line
                    }
                    ).toArgb()
            )
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(true))
            addState(intArrayOf(), shape(false))
        }
        setOnClickListener { if (isEnabled) click() }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.util.TypedValue
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import org.ramflux.designsystem.LocalRFTheme

/** Owned secret text. [replace], [splice], [removeOutside], and [clear] wipe the previous array. */
class ErasableBuffer {
    private var chars = CharArray(0)

    /** Invoked after the array is already zero. The callback must not call [clear]. */
    internal var onCleared: (() -> Unit)? = null

    fun owned(): CharArray = chars

    fun isEmpty(): Boolean = chars.isEmpty()

    fun isBlank(): Boolean = chars.isEmpty() || chars.all { it.isWhitespace() }

    fun snapshot(): CharArray = chars.copyOf()

    fun replace(next: CharArray) {
        val previous = chars
        chars = next.copyOf()
        previous.fill('\u0000')
        next.fill('\u0000')
    }

    /** Inserts [incoming] at [at], dropping [drop] following chars. Wipes [incoming]. Returns how many chars were kept. */
    internal fun splice(at: Int, drop: Int, incoming: CharArray, limit: Int): Int {
        val size = chars.size
        val safeAt = at.coerceIn(0, size)
        val safeDrop = drop.coerceIn(0, size - safeAt)
        val room = (limit - (size - safeDrop)).coerceAtLeast(0)
        val take = incoming.size.coerceAtMost(room)
        if (take == 0 && safeDrop == 0) {
            incoming.fill('\u0000')
            return 0
        }
        val next = CharArray(size - safeDrop + take)
        chars.copyInto(next, 0, 0, safeAt)
        if (take > 0) incoming.copyInto(next, safeAt, 0, take)
        chars.copyInto(next, safeAt + take, safeAt + safeDrop, size)
        val previous = chars
        chars = next
        previous.fill('\u0000')
        incoming.fill('\u0000')
        return take
    }

    /** Drops `[start, keepStart)` and `[keepEnd, end)`. Returns whether the array changed. */
    internal fun removeOutside(start: Int, keepStart: Int, keepEnd: Int, end: Int): Boolean {
        val size = chars.size
        val head = start.coerceIn(0, size)
        val keptStart = keepStart.coerceIn(head, size)
        val keptEnd = keepEnd.coerceIn(keptStart, size)
        val tail = end.coerceIn(keptEnd, size)
        val removed = (keptStart - head) + (tail - keptEnd)
        if (removed == 0) return false
        val next = CharArray(size - removed)
        chars.copyInto(next, 0, 0, head)
        chars.copyInto(next, head, keptStart, keptEnd)
        chars.copyInto(next, head + (keptEnd - keptStart), tail, size)
        val previous = chars
        chars = next
        previous.fill('\u0000')
        return true
    }

    fun clear() {
        val previous = chars
        chars = CharArray(0)
        previous.fill('\u0000')
        onCleared?.invoke()
    }
}

/** Secret field. Keystrokes live in [buffer], and leaving the composition wipes that buffer. */
@Composable
fun ErasableSecretField(
    buffer: ErasableBuffer,
    label: String,
    fieldTag: String,
    enabled: Boolean,
    password: Boolean,
    limit: Int,
    onEdited: () -> Unit
) {
    val theme = LocalRFTheme.current
    val edited by rememberUpdatedState(onEdited)
    val viewRef = remember { arrayOfNulls<ErasableSecretView>(1) }
    DisposableEffect(buffer) {
        buffer.onCleared = { viewRef[0]?.forgetImeState() }
        onDispose {
            buffer.onCleared = null
            viewRef[0]?.forgetImeState()
            buffer.clear()
        }
    }
    Text(label)
    AndroidView(
        factory = { context ->
            ErasableSecretView(context).apply {
                tag = fieldTag
                viewRef[0] = this
            }
        },
        update = { view ->
            view.isEnabled = enabled
            view.bind(buffer, password, limit, label) { edited() }
            view.applyStyle(theme.text.toArgb(), theme.line.toArgb(), theme.size_body, theme.gap)
        },
        modifier = Modifier.fillMaxWidth().testTag(fieldTag)
    )
}

/**
 * Secret entry that does not use [android.widget.TextView]. The platform text editor keeps an undo
 * history, and no public API turns that history off, so this view owns the characters and draws them.
 */
class ErasableSecretView(context: Context) : View(context) {
    private var secret: ErasableBuffer? = null
    private var mask = true
    private var limit = 0
    private var onEdited: (() -> Unit)? = null
    private var selStart = 0
    private var selEnd = 0
    private var compStart = -1
    private var compEnd = -1
    private var pendingAccent = 0
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = false
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setAutofillHints()
        linePaint.style = Paint.Style.STROKE
    }

    fun masksInput(): Boolean = mask

    fun secretLength(): Int = secret?.owned()?.size ?: 0

    fun isSecretEmpty(): Boolean = secretLength() == 0

    fun bind(next: ErasableBuffer, maskInput: Boolean, maxChars: Int, description: String, edited: () -> Unit) {
        if (secret !== next) {
            secret = next
            selStart = 0
            selEnd = 0
            compStart = -1
            compEnd = -1
            pendingAccent = 0
        }
        mask = maskInput
        limit = maxChars.coerceAtLeast(0)
        contentDescription = description
        onEdited = edited
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setAutofillHints()
    }

    fun applyStyle(textColor: Int, lineColor: Int, sizeSp: Float, gapDp: Float) {
        textPaint.color = textColor
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        linePaint.color = lineColor
        linePaint.strokeWidth = resources.displayMetrics.density
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, gapDp, resources.displayMetrics)
        val padX = pad.toInt()
        val padY = (pad / 2f).toInt()
        setPadding(padX, padY, padX, padY)
        minimumHeight = (textPaint.textSize + pad).toInt()
    }

    /** Drops the cursor and composing range after the buffer has been wiped. */
    internal fun forgetImeState() {
        selStart = 0
        selEnd = 0
        compStart = -1
        compEnd = -1
        pendingAccent = 0
        invalidate()
    }

    override fun getAutofillType(): Int = AUTOFILL_TYPE_NONE

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!isEnabled) return null
        outAttrs.inputType = SECRET_INPUT_TYPE
        outAttrs.imeOptions = SECRET_IME_OPTIONS
        outAttrs.initialSelStart = selStart
        outAttrs.initialSelEnd = selEnd
        outAttrs.initialCapsMode = 0
        return openConnection()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = minimumHeight.coerceAtLeast(suggestedMinimumHeight)
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> desired.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else -> desired
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val owned = secret?.owned() ?: return
        val baseline = textBaseline()
        if (owned.isNotEmpty()) {
            if (mask) {
                val glyphs = CharArray(owned.size) { MASK_GLYPH }
                canvas.drawText(glyphs, 0, glyphs.size, paddingLeft.toFloat(), baseline, textPaint)
                glyphs.fill('\u0000')
            } else {
                canvas.drawText(owned, 0, owned.size, paddingLeft.toFloat(), baseline, textPaint)
            }
        }
        val rule = (height - paddingBottom).toFloat()
        canvas.drawLine(paddingLeft.toFloat(), rule, (width - paddingRight).toFloat(), rule, linePaint)
        if (isFocused) {
            val caret = caretX(owned)
            canvas.drawLine(caret, paddingTop.toFloat(), caret, rule, textPaint)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return false
        return applyKeyEvent(event, 1)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return false
        return applyKeyEvent(event, repeatCount.coerceAtLeast(1))
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = isEnabled && isTextKey(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.action == MotionEvent.ACTION_UP) {
            requestFocus()
            val manager = context.getSystemService(InputMethodManager::class.java)
            manager?.showSoftInput(this, 0)
        }
        return true
    }

    private fun openConnection(): InputConnection {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> Connection34()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Connection31()
            else -> SecretInputConnection()
        }
    }

    private fun commit(text: CharSequence, newCursorPosition: Int) {
        val (start, end) = activeRange()
        replace(start, end, text, newCursorPosition, composing = false)
    }

    private fun compose(text: CharSequence, newCursorPosition: Int) {
        val (start, end) = activeRange()
        replace(start, end, text, newCursorPosition, composing = true)
    }

    private fun activeRange(): Pair<Int, Int> {
        val size = secretLength()
        if (compStart >= 0 && compEnd >= 0) {
            val start = minOf(compStart, compEnd).coerceIn(0, size)
            val end = maxOf(compStart, compEnd).coerceIn(start, size)
            return start to end
        }
        val start = minOf(selStart, selEnd).coerceIn(0, size)
        val end = maxOf(selStart, selEnd).coerceIn(start, size)
        return start to end
    }

    private fun replace(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, composing: Boolean) {
        val buffer = secret ?: return
        val size = buffer.owned().size
        val from = start.coerceIn(0, size)
        val to = end.coerceIn(from, size)
        val incoming = copyChars(text)
        val taken = buffer.splice(from, to - from, incoming, limit)
        val newSize = buffer.owned().size
        val insertedEnd = from + taken
        var cursor = if (newCursorPosition > 0) insertedEnd + newCursorPosition - 1 else from + newCursorPosition
        if (cursor < 0) cursor = 0
        if (cursor > newSize) cursor = newSize
        selStart = cursor
        selEnd = cursor
        if (composing && taken > 0) {
            compStart = from
            compEnd = insertedEnd
        } else {
            compStart = -1
            compEnd = -1
        }
        if (taken > 0 || to > from) onEdited?.invoke()
        invalidate()
    }

    private fun deleteAround(beforeLength: Int, afterLength: Int, codePoints: Boolean) {
        val buffer = secret ?: return
        val owned = buffer.owned()
        val size = owned.size
        val from = minOf(selStart, selEnd).coerceIn(0, size)
        val to = maxOf(selStart, selEnd).coerceIn(0, size)
        val before = beforeLength.coerceAtLeast(0)
        val after = afterLength.coerceAtLeast(0)
        val start = if (codePoints) offsetCodePoints(owned, from, -before) else (from - before).coerceAtLeast(0)
        val end = if (codePoints) offsetCodePoints(owned, to, after) else (to + after).coerceAtMost(size)
        val changed = buffer.removeOutside(start, from, to, end)
        val removedBefore = from - start
        val newSize = buffer.owned().size
        selStart = (from - removedBefore).coerceIn(0, newSize)
        selEnd = (to - removedBefore).coerceIn(selStart, newSize)
        compStart = -1
        compEnd = -1
        if (changed) onEdited?.invoke()
        invalidate()
    }

    private fun moveSelection(start: Int, end: Int) {
        val size = secretLength()
        selStart = start.coerceIn(0, size)
        selEnd = end.coerceIn(0, size)
        invalidate()
    }

    private fun moveComposing(start: Int, end: Int) {
        val size = secretLength()
        val from = start.coerceIn(0, size)
        val to = end.coerceIn(0, size)
        if (from == to) {
            compStart = -1
            compEnd = -1
        } else {
            compStart = minOf(from, to)
            compEnd = maxOf(from, to)
        }
    }

    private fun finishComposing() {
        compStart = -1
        compEnd = -1
    }

    private fun applyKeyEvent(event: KeyEvent, repeats: Int): Boolean {
        if (!isEnabled || secret == null) return false
        if (event.action == KeyEvent.ACTION_UP) return isTextKey(event)
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_MULTIPLE) return false
        val code = event.keyCode
        val times = repeats.coerceAtLeast(1)
        if (code == KeyEvent.KEYCODE_DEL) {
            deleteSelectionOr(times, 0)
            return true
        }
        if (code == KeyEvent.KEYCODE_FORWARD_DEL) {
            deleteSelectionOr(0, times)
            return true
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE && code == KeyEvent.KEYCODE_UNKNOWN) {
            val text = event.characters
            if (text.isNullOrEmpty()) return false
            pendingAccent = 0
            finishComposing()
            insertOwned(copyChars(text))
            return true
        }
        val value = event.unicodeChar
        if (value == 0 || Character.isISOControl(value)) return false
        if ((value and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            pendingAccent = value and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        val unit = charsFromCodePoints(codePointsFor(value))
        val count = times.coerceAtMost(limit.coerceAtLeast(1))
        finishComposing()
        insertOwned(if (count == 1) unit else repeatChars(unit, count))
        return true
    }

    private fun isTextKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL) return true
        if (code == KeyEvent.KEYCODE_UNKNOWN && !event.characters.isNullOrEmpty()) return true
        val value = event.unicodeChar
        return value != 0 && !Character.isISOControl(value)
    }

    private fun codePointsFor(value: Int): IntArray {
        val accent = pendingAccent
        pendingAccent = 0
        if (accent == 0) return intArrayOf(value)
        val combined = KeyCharacterMap.getDeadChar(accent, value)
        if (combined != 0) return intArrayOf(combined)
        return intArrayOf(accent, value)
    }

    private fun charsFromCodePoints(points: IntArray): CharArray {
        var count = 0
        for (point in points) {
            if (Character.isValidCodePoint(point)) count += Character.charCount(point)
        }
        val out = CharArray(count)
        var offset = 0
        for (point in points) {
            if (!Character.isValidCodePoint(point)) continue
            offset += Character.toChars(point, out, offset)
        }
        points.fill(0)
        return out
    }

    private fun repeatChars(unit: CharArray, times: Int): CharArray {
        val incoming = CharArray(unit.size * times)
        var offset = 0
        var left = times
        while (left > 0) {
            unit.copyInto(incoming, offset)
            offset += unit.size
            left--
        }
        unit.fill('\u0000')
        return incoming
    }

    private fun insertOwned(incoming: CharArray) {
        val buffer = secret
        if (buffer == null) {
            incoming.fill('\u0000')
            return
        }
        val size = buffer.owned().size
        val from = minOf(selStart, selEnd).coerceIn(0, size)
        val to = maxOf(selStart, selEnd).coerceIn(from, size)
        val taken = buffer.splice(from, to - from, incoming, limit)
        val cursor = (from + taken).coerceIn(0, buffer.owned().size)
        selStart = cursor
        selEnd = cursor
        compStart = -1
        compEnd = -1
        if (taken > 0 || to > from) onEdited?.invoke()
        invalidate()
    }

    private fun deleteSelectionOr(before: Int, after: Int) {
        pendingAccent = 0
        finishComposing()
        val size = secretLength()
        val from = minOf(selStart, selEnd).coerceIn(0, size)
        val to = maxOf(selStart, selEnd).coerceIn(from, size)
        if (from == to) {
            deleteAround(before, after, codePoints = true)
            return
        }
        val buffer = secret ?: return
        val changed = buffer.removeOutside(from, from, from, to)
        val newSize = buffer.owned().size
        selStart = from.coerceIn(0, newSize)
        selEnd = selStart
        if (changed) onEdited?.invoke()
        invalidate()
        val moreBefore = (before - 1).coerceAtLeast(0)
        val moreAfter = (after - 1).coerceAtLeast(0)
        if (moreBefore > 0 || moreAfter > 0) deleteAround(moreBefore, moreAfter, codePoints = true)
    }

    private fun textBaseline(): Float {
        val metrics = textPaint.fontMetrics
        val content = (height - paddingTop - paddingBottom).toFloat()
        return paddingTop + (content - metrics.bottom - metrics.top) / 2f
    }

    private fun caretX(owned: CharArray): Float {
        val origin = paddingLeft.toFloat()
        val count = selStart.coerceIn(0, owned.size)
        if (count == 0) return origin
        if (mask) {
            val glyph = charArrayOf(MASK_GLYPH)
            val step = textPaint.measureText(glyph, 0, 1)
            glyph[0] = '\u0000'
            return origin + step * count
        }
        return origin + textPaint.measureText(owned, 0, count)
    }

    private fun copyChars(text: CharSequence): CharArray {
        val out = CharArray(text.length)
        for (index in text.indices) out[index] = text[index]
        return out
    }

    private fun offsetCodePoints(chars: CharArray, index: Int, delta: Int): Int {
        var cursor = index.coerceIn(0, chars.size)
        if (delta > 0) {
            var left = delta
            while (left > 0 && cursor < chars.size) {
                if (chars[cursor].isHighSurrogate() && cursor + 1 < chars.size && chars[cursor + 1].isLowSurrogate()) {
                    cursor += 2
                } else {
                    cursor += 1
                }
                left--
            }
        } else if (delta < 0) {
            var left = -delta
            while (left > 0 && cursor > 0) {
                cursor--
                if (chars[cursor].isLowSurrogate() && cursor > 0 && chars[cursor - 1].isHighSurrogate()) cursor--
                left--
            }
        }
        return cursor
    }

    /**
     * IME bridge over the view's own buffer. Text methods do not call through to
     * [BaseInputConnection]: its default buffer is a copy this view does not own.
     * [getEditable] stays null so that copy is never created.
     */
    private open inner class SecretInputConnection : BaseInputConnection(this@ErasableSecretView, false) {
        override fun getEditable(): Editable? = null

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text == null || !isEnabled) return false
            commit(text, newCursorPosition)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text == null || !isEnabled) return false
            compose(text, newCursorPosition)
            return true
        }

        override fun finishComposingText(): Boolean {
            finishComposing()
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            moveComposing(start, end)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (!isEnabled) return false
            deleteAround(beforeLength, afterLength, codePoints = false)
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (!isEnabled) return false
            deleteAround(beforeLength, afterLength, codePoints = true)
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            moveSelection(start, end)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (!isEnabled) return false
            return applyKeyEvent(event, 1)
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun getCursorCapsMode(reqModes: Int): Int = 0

        override fun performContextMenuAction(id: Int): Boolean = false
    }

    /**
     * API 31 surrounding text is refused. The platform parcels a character sequence into a
     * string copy, which this field cannot wipe. The cursor queries above stay null for the
     * same reason.
     */
    private open inner class Connection31 : SecretInputConnection() {
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? = null
    }

    /** API 34 replace goes through the same buffer path. Styling attributes are ignored. */
    private inner class Connection34 : Connection31() {
        override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
            if (!isEnabled) return false
            finishComposing()
            moveSelection(start, end)
            commit(text, newCursorPosition)
            return true
        }
    }

    private companion object {
        const val MASK_GLYPH = '\u2022'
        const val SECRET_INPUT_TYPE =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        const val SECRET_IME_OPTIONS = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }
}

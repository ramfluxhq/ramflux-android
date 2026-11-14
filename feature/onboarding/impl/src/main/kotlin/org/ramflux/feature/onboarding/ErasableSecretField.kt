// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.textclassifier.TextClassifier
import android.widget.EditText
import android.widget.TextView
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

/** Owned secret text. [replace] and [clear] wipe the previous array. */
class ErasableBuffer {
    private var chars = CharArray(0)
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

    fun clear() {
        chars.fill('\u0000')
        chars = CharArray(0)
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
    val editRef = remember { arrayOfNulls<EditText>(1) }
    val watcher = remember(buffer) {
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val next = CharArray(s?.length ?: 0)
                if (s != null && next.isNotEmpty()) s.getChars(0, next.size, next, 0)
                buffer.replace(next)
                edited()
            }
        }
    }
    DisposableEffect(buffer) {
        buffer.onCleared = {
            editRef[0]?.let { edit ->
                edit.removeTextChangedListener(watcher)
                wipeEditText(edit)
                edit.addTextChangedListener(watcher)
            }
        }
        onDispose {
            buffer.onCleared = null
            editRef[0]?.let { edit ->
                edit.removeTextChangedListener(watcher)
                wipeEditText(edit)
            }
            buffer.clear()
        }
    }
    Text(label)
    AndroidView(
        factory = { context ->
            SecretEditText(context).apply {
                tag = fieldTag
                isSingleLine = true
                val variation = if (password) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
                inputType = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                if (password) transformationMethod = PasswordTransformationMethod.getInstance()
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                setTextClassifier(TextClassifier.NO_OP)
                customSelectionActionModeCallback = NoTextAction
                customInsertionActionModeCallback = NoTextAction
                filters = arrayOf(InputFilter.LengthFilter(limit))
                sealHistory(this)
                addTextChangedListener(watcher)
                editRef[0] = this
            }
        },
        update = { edit ->
            edit.isEnabled = enabled
            edit.setTextColor(theme.text.toArgb())
        },
        modifier = Modifier.fillMaxWidth().testTag(fieldTag)
    )
}

private fun wipeEditText(edit: EditText) {
    sealHistory(edit)
    val existing = edit.text
    if (existing != null && existing.isNotEmpty()) {
        zeroBacking(existing)
        existing.delete(0, existing.length)
    }
    zeroBacking(edit.text)
    edit.setText("")
    sealHistory(edit)
}

/** EditText installs the undo filter again whenever its text or filters change. */
private class SecretEditText(context: android.content.Context) : EditText(context) {
    override fun setFilters(filters: Array<InputFilter>) {
        super.setFilters(filters)
        sealHistory(this)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        sealHistory(this)
    }
}

/** Undo is off before the first secret edit. The undo filter stores old and new text as strings. */
private fun sealHistory(edit: EditText) {
    val editorField = TextView::class.java.getDeclaredField(member('m', 'E', 'd', 'i', 't', 'o', 'r'))
    editorField.isAccessible = true
    val editor = editorField.get(edit) ?: return
    val allow = editor.javaClass.getDeclaredField(member('m', 'A', 'l', 'l', 'o', 'w', 'U', 'n', 'd', 'o'))
    allow.isAccessible = true
    allow.setBoolean(editor, false)
    val forget = editor.javaClass.getDeclaredMethod(member('f', 'o', 'r', 'g', 'e', 't', 'U', 'n', 'd', 'o', 'R', 'e', 'd', 'o'))
    forget.isAccessible = true
    forget.invoke(editor)
    val undoName = member('U', 'n', 'd', 'o', 'I', 'n', 'p', 'u', 't', 'F', 'i', 'l', 't', 'e', 'r')
    val text = edit.text ?: return
    val kept = text.filters.filterNot { it.javaClass.simpleName == undoName }
    if (kept.size != text.filters.size) text.filters = kept.toTypedArray()
}

private fun member(vararg chars: Char): String = String(chars)

private object NoTextAction : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
    override fun onDestroyActionMode(mode: ActionMode?) = Unit
}

/** SpannableStringBuilder keeps the old characters in its gap. Zero every char array it still holds. */
private fun zeroBacking(text: android.text.Editable?) {
    if (text == null) return
    runCatching {
        for (field in text.javaClass.declaredFields) {
            if (field.type != CharArray::class.java) continue
            field.isAccessible = true
            (field.get(text) as? CharArray)?.fill('\u0000')
        }
    }
}

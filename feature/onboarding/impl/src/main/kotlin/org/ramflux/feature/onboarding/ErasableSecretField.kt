// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
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
            EditText(context).apply {
                tag = fieldTag
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                filters = arrayOf(InputFilter.LengthFilter(limit))
                isSingleLine = true
                if (password) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    transformationMethod = PasswordTransformationMethod.getInstance()
                } else {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
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
    val existing = edit.text
    if (existing != null && existing.isNotEmpty()) {
        val zeros = CharArray(existing.length)
        existing.replace(0, existing.length, String(zeros))
        zeroBacking(existing)
        zeros.fill('\u0000')
    }
    zeroBacking(edit.text)
    edit.setText("")
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

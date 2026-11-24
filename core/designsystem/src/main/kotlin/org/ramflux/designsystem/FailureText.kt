// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import androidx.annotation.StringRes
import org.ramflux.core.designsystem.R

/**
 * The one place an SDK refusal becomes user-visible text. A reason the app has words for is shown in words; anything
 * else keeps the typed status name and the reason token next to the numeric code, never a bare number.
 */
fun failureText(@StringRes generic: Int, code: Int, statusName: String?, reason: String?): UiText =
    failureWords(reason) ?: uiText(generic, failureDetail(code, statusName, reason))

/** The words for a reason token the app knows, or null. */
fun failureWords(reason: String?): UiText? = when (reason) {
    "directory_unavailable" -> uiText(R.string.failure_directory_unavailable)
    else -> null
}

/** The parenthesised part of a generic failure sentence: the code, then the status name and reason token the SDK gave. */
fun failureDetail(code: Int, statusName: String?, reason: String?): UiText {
    val number = UiText.Number(code.toLong())
    val name = statusName?.takeIf { it.isNotEmpty() }
    val token = reason?.takeIf { it.isNotEmpty() }
    return when {
        name != null && token != null -> uiText(R.string.failure_detail_name_reason, number, UiText.Code(name), UiText.Code(token))
        name != null || token != null -> uiText(R.string.failure_detail_name, number, UiText.Code(name ?: token.orEmpty()))
        else -> uiText(R.string.failure_detail_code, number)
    }
}

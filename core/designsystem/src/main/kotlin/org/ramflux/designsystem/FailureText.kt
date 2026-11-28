// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import android.util.Log
import androidx.annotation.StringRes
import org.ramflux.core.designsystem.R

/**
 * The sentence a person reads for an SDK refusal (D-1b): a known reason in words, otherwise the network, the "not offered
 * by this build" or the generic line. The numeric code, status name and reason token never reach the screen; they go to
 * the log with the method-level diagnostics.
 */
fun failureSentence(code: Int, statusName: String?, reason: String?): UiText {
    try {
        Log.d(LOG_TAG, listOf(code.toString(), statusName.orEmpty(), reason.orEmpty()).joinToString("/"))
    } catch (_: RuntimeException) {
        // The platform logger is absent in a plain JVM test; the sentence does not depend on it.
    }
    failureWords(reason)?.let { return it }
    val token = listOfNotNull(statusName, reason).joinToString("/").lowercase()
    return when {
        NOT_OFFERED.any { token.contains(it) } -> uiText(R.string.failure_unsupported)
        UNREACHABLE.any { token.contains(it) } -> uiText(R.string.failure_network)
        else -> uiText(R.string.failure_generic)
    }
}

private const val LOG_TAG = "ramflux.failure"
private val NOT_OFFERED = listOf("unsupported", "not_supported", "notsupported", "not_offered", "has_not_provided")
private val UNREACHABLE = listOf("network", "unreachable", "offline", "timeout", "timedout", "connect", "transport")

/**
 * Legacy wording that keeps the numeric code in the sentence. Only the frozen group module still calls it; every other
 * screen uses [failureSentence].
 *
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

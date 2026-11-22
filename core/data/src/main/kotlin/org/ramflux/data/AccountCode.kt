// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/**
 * The six-digit account code. The SDK is the authority: it admits a new code (exactly six ASCII
 * digits, not on its closed trivial list), counts wrong codes and imposes waits. This mirror only
 * lets the screen say what is wrong before a round trip; the SDK refusal is still mapped below.
 */
object AccountCode {
    const val DIGITS = 6

    /** Exactly [DIGITS] ASCII digits. */
    fun complete(code: CharArray): Boolean = code.size == DIGITS && code.all { it in '0'..'9' }

    /** All digits equal, or a straight ascending or descending run (the SDK's trivial list). */
    fun trivial(code: CharArray): Boolean {
        if (!complete(code)) return false
        val steps = (1 until code.size).map { code[it] - code[it - 1] }
        return listOf(0, 1, -1).any { step -> steps.all { it == step } }
    }

    /** A refusal of a new code before it is sent, or null when the SDK should decide. */
    fun newCodeRefusal(code: CharArray): AccountCodeRefusal? = when {
        !complete(code) -> AccountCodeRefusal.NOT_SIX_DIGITS
        trivial(code) -> AccountCodeRefusal.TRIVIAL_CODE
        else -> null
    }
}

/** Typed account-code outcomes the SDK reports by name. */
enum class AccountCodeRefusal {
    /** `not_six_digits`. */
    NOT_SIX_DIGITS,

    /** `trivial_code`: repeated digits or a straight run. */
    TRIVIAL_CODE,

    /** The code did not open the data; the attempt was counted. */
    WRONG_CODE,

    /** `UnlockRetryLater`: wait [ClientProblem.retryAfterSecs] before the next try. */
    RETRY_LATER,

    /** `UnlockAttemptsExhausted`: only recovery from the phrase opens the account now. */
    ATTEMPTS_EXHAUSTED,

    /** `DeviceUnlockUnavailable`: this device's vault holds no unlock secret for the account. */
    DEVICE_UNLOCK_UNAVAILABLE,

    /** `AccountCodeRequired`: the account was locked; it opens with its code. */
    CODE_REQUIRED
}

/**
 * The typed account-code refusal of a failure, or null for other failures. [unlock] marks an
 * `account.unlock` reply, where the SDK reports a code that does not open the data key as
 * `ValidationFailed` ("wrong passphrase or damaged local key").
 */
fun ClientProblem.accountCodeRefusal(unlock: Boolean = false): AccountCodeRefusal? = when (statusName) {
    "not_six_digits" -> AccountCodeRefusal.NOT_SIX_DIGITS
    "trivial_code" -> AccountCodeRefusal.TRIVIAL_CODE
    "UnlockRetryLater" -> AccountCodeRefusal.RETRY_LATER
    "UnlockAttemptsExhausted" -> AccountCodeRefusal.ATTEMPTS_EXHAUSTED
    "DeviceUnlockUnavailable" -> AccountCodeRefusal.DEVICE_UNLOCK_UNAVAILABLE
    "AccountCodeRequired" -> AccountCodeRefusal.CODE_REQUIRED
    "ValidationFailed" -> if (unlock) AccountCodeRefusal.WRONG_CODE else null
    else -> when (check) {
        "unlock_retry_later" -> AccountCodeRefusal.RETRY_LATER
        "unlock_attempts_exhausted" -> AccountCodeRefusal.ATTEMPTS_EXHAUSTED
        "device_unlock_unavailable" -> AccountCodeRefusal.DEVICE_UNLOCK_UNAVAILABLE
        "account_code_required" -> AccountCodeRefusal.CODE_REQUIRED
        else -> null
    }
}

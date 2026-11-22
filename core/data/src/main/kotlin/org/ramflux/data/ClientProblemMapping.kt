// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.ramflux.sdk.CoreError
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.Effect

internal fun CoreError.appProblem(): ClientProblem = ClientProblem(
    kind = when {
        code == 3 || this is CoreError.MethodPolicyMismatch -> ClientFailureKind.INCOMPATIBLE
        code == 4 -> ClientFailureKind.UNAVAILABLE
        code == 8 -> ClientFailureKind.STORAGE
        code in setOf(5, 6, 12) -> ClientFailureKind.AUTHORIZATION
        code in setOf(10, 24, 18) -> ClientFailureKind.SHUTDOWN_PENDING
        else -> ClientFailureKind.FAILED
    },
    code = code,
    outcome = when (effect) { Effect.NONE -> ClientOutcome.NONE; Effect.REFUSED -> ClientOutcome.REFUSED; Effect.UNKNOWN -> ClientOutcome.UNKNOWN },
    statusName = statusName,
    check = reasonCode,
)

/** As [CoreError.appProblem], plus the public retry wait the SDK attached to the refusal. */
internal fun CoreResult.Failure.appProblem(): ClientProblem =
    error.appProblem().copy(retryAfterSecs = (details?.get("retry_after_secs") as? JsonPrimitive)?.longOrNull)

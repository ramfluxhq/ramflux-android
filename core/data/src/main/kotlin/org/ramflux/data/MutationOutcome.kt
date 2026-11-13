// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.Effect

/** Classifies an outcome under its original identity; the intent owner retains frozen arguments. */
class PendingMutation(val context: RepositoryContext, val method: String) {
    fun disposition(result: CoreResult<*>): MutationDisposition = when (result) {
        is CoreResult.Success -> MutationDisposition.Completed
        is CoreResult.Failure -> when (result.error.effect) {
            Effect.NONE, Effect.REFUSED -> MutationDisposition.Failed(result.error)
            Effect.UNKNOWN -> MutationDisposition.Reconcile(context.operationId)
        }
    }
    override fun toString(): String = "PendingMutation(method=$method, arguments=<redacted>)"
}

sealed interface MutationDisposition {
    data object Completed : MutationDisposition
    data class Failed(val error: org.ramflux.sdk.CoreError) : MutationDisposition
    data class Reconcile(val operationId: String?) : MutationDisposition
}

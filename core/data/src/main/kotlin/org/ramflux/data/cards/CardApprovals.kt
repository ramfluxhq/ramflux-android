// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import kotlinx.serialization.json.JsonObject
import org.ramflux.data.ClientProblem
import org.ramflux.data.DefaultPermissionsRepository
import org.ramflux.data.DefaultTasksRepository
import org.ramflux.data.RepositoryContext
import org.ramflux.data.RepositoryTransport
import org.ramflux.data.appProblem
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody

/**
 * Finishes an approval the card runtime handed to the host (`approval_decision` with `host_request.task_resolve`): the
 * decision is signed with `grant.approve` or `grant.deny` for `request_ref`, then the task decision goes out with
 * `task.resolve`. The `task_resolve` object is the core's; it is sent exactly as received and never built or edited here.
 * Bodies stay off every log: only the method and the typed result are ever reported.
 */
internal class CardApprovals(transport: RepositoryTransport) {
    private val permissions = DefaultPermissionsRepository(transport)
    private val tasks = DefaultTasksRepository(transport)

    /** What came of the hand-off. */
    sealed interface Result {
        /** Both calls succeeded; the card keeps waiting for the signed update or the task change. */
        data object Done : Result

        /** The host request carries nothing to finish (cancel, unavailable, or no `task_resolve`). */
        data object NotApplicable : Result

        /** The request changed or was already decided (a typed refusal); nothing is retried. */
        data object Changed : Result

        /** Any other failure; its [problem] says whether the outcome is unknown. */
        class Failed(val problem: ClientProblem) : Result
    }

    suspend fun finish(account: String, hostRequest: JsonObject): Result {
        val resolve = hostRequest["task_resolve"] as? JsonObject ?: return Result.NotApplicable
        val reference = hostRequest.text("request_ref")?.takeIf { it.isNotEmpty() } ?: return Result.NotApplicable
        val approve = when (hostRequest.text("outcome")) {
            "confirm" -> true
            "deny" -> false
            else -> return Result.NotApplicable
        }
        val context = RepositoryContext(account)
        val decided = SecretBody.fields { publicText("approval_id", reference) }
        val signed = if (approve) permissions.grantApprove(context, decided) else permissions.grantDeny(context, decided)
        when (signed) {
            is CoreResult.Failure -> return refused(signed.error.appProblem())
            is CoreResult.Success -> signed.value.close()
        }
        val resolved = tasks.resolve(context, SecretBody.takeJson(resolve.toString().toByteArray(Charsets.UTF_8)))
        return when (resolved) {
            is CoreResult.Failure -> refused(resolved.error.appProblem())
            is CoreResult.Success -> {
                resolved.value.close()
                Result.Done
            }
        }
    }

    private fun refused(problem: ClientProblem): Result =
        if (problem.cardToken() in CHANGED) Result.Changed else Result.Failed(problem)

    private companion object {
        /** The typed refusals that mean the request moved on since the card drew it. */
        val CHANGED = setOf("stale_revision", "decision_consumed", "decision_mismatch", "terminal", "not_found")
    }
}

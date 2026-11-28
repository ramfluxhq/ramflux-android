// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import kotlinx.coroutines.flow.MutableStateFlow
import org.ramflux.data.AccountAccessRepository
import org.ramflux.data.AccountCatalog
import org.ramflux.data.AccountSetupAction
import org.ramflux.data.AccountSummary
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.ClientFeature
import org.ramflux.data.ClientOutcome
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientProblem
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.ClientSnapshot
import org.ramflux.data.ContactAttempt
import org.ramflux.data.ContactCommand
import org.ramflux.data.ContactDirectoryRepository
import org.ramflux.data.ContactListScope
import org.ramflux.data.ContactOperation
import org.ramflux.data.ContactRequestScope
import org.ramflux.data.ContactRequestSummary
import org.ramflux.data.ContactSummary
import org.ramflux.data.ConversationHeader
import org.ramflux.data.MessageHistoryRepository
import org.ramflux.data.MessageSummary
import org.ramflux.data.SendBlock
import org.ramflux.data.SendReceipt
import org.ramflux.data.TimelineSummary
import org.ramflux.data.UnavailableReason
import org.ramflux.data.WindowedPage

/** One unlocked account and an open session: the minimum the real view models need to run. */
internal class FixtureAccounts : AccountAccessRepository {
    override suspend fun list() = ClientResult.Ready(
        AccountCatalog(listOf(AccountSummary(GALLERY_ACCOUNT, "Gallery", true, "unlocked")), GALLERY_ACCOUNT)
    )
    override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
}

internal class FixtureSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() = Unit
    override suspend fun background() = Unit
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}

/** A two-message direct chat with the fixture person. A send is refused with `identity_change_pending` while the script says so. */
internal class FixtureMessageHistory(
    private val person: MlsPerson,
    private val identity: FixtureContactIdentityRepository
) : MessageHistoryRepository {
    private var sent = 0

    override suspend fun read(account: String, conversation: String): ClientResult<List<TimelineSummary>> = ClientResult.Ready(
        listOf(
            MessageSummary("m1", person.root, own = false, deleted = false, text = "Hello", createdAt = NOW - 120),
            MessageSummary("m2", null, own = true, deleted = false, text = "Hi", createdAt = NOW - 60)
        )
    )

    override suspend fun header(account: String, conversation: String): ClientResult<ConversationHeader> =
        ClientResult.Ready(ConversationHeader(person.name, peer = person.root, ai = person.ai))

    override suspend fun sendAvailability(): ClientResult<Unit> = ClientResult.Ready(Unit)

    override suspend fun send(account: String, conversation: String, text: String, operationId: String): ClientResult<SendReceipt> =
        if (identity.sendBlocked()) {
            ClientResult.Failed(
                ClientProblem(ClientFailureKind.FAILED, 12, ClientOutcome.REFUSED, statusName = SendBlock.IDENTITY_CHANGE_PENDING)
            )
        } else {
            ClientResult.Ready(SendReceipt("sent-" + sent++))
        }

    private companion object {
        val NOW = System.currentTimeMillis() / 1000
    }
}

/** The one contact of a fixture, as an accepted link. */
internal class FixtureContactDirectory(private val person: MlsPerson) : ContactDirectoryRepository {
    private val row = ContactSummary(person.link, "principal-" + person.key, person.name, false, person.root, person.ai)
    override suspend fun contacts(account: String): ClientResult<List<ContactSummary>> = ClientResult.Ready(listOf(row))
    override suspend fun requests(account: String): ClientResult<List<ContactRequestSummary>> = ClientResult.Ready(emptyList())
    override suspend fun contactPage(
        account: String,
        scope: ContactListScope,
        query: String,
        cursor: String?
    ): ClientResult<WindowedPage<ContactSummary>> =
        ClientResult.Ready(WindowedPage(if (scope == ContactListScope.ACCEPTED) listOf(row) else emptyList(), null, null))

    override suspend fun requestPage(
        account: String,
        scope: ContactRequestScope,
        query: String,
        cursor: String?
    ): ClientResult<WindowedPage<ContactRequestSummary>> = ClientResult.Ready(WindowedPage(emptyList(), null, null))

    override suspend fun submit(command: ContactCommand, confirmed: Boolean): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)

    override fun attempts(account: String): List<ContactAttempt> = emptyList()
    override suspend fun availability(operation: ContactOperation): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.notify

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ramflux.data.AccountAccessRepository
import org.ramflux.data.AccountCatalog
import org.ramflux.data.AccountSetupAction
import org.ramflux.data.AccountSummary
import org.ramflux.data.ClientFeature
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.ClientSnapshot
import org.ramflux.data.ConversationChange
import org.ramflux.data.ConversationDirectoryRepository
import org.ramflux.data.ConversationPage
import org.ramflux.data.ConversationSummary

@OptIn(ExperimentalCoroutinesApi::class)
class MessageNotificationsTest {
    private class Session : ClientSessionRepository {
        override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
        override suspend fun foreground() {}
        override suspend fun background() {}
        override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
    }
    private class Accounts : AccountAccessRepository {
        var catalog = AccountCatalog(
            listOf(AccountSummary("a", "Work", true, "unlocked"), AccountSummary("b", "Home", true, "unlocked")),
            "a"
        )
        override val revisions = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        override suspend fun list(): ClientResult<AccountCatalog> = ClientResult.Ready(catalog)
        override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Ready(Unit)
        override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Ready(Unit)
    }
    private class Directory : ConversationDirectoryRepository {
        val rows = mutableMapOf<String, List<ConversationSummary>>()
        val events = MutableSharedFlow<ClientResult<ConversationChange>>(extraBufferCapacity = 8)
        val subscribed = mutableListOf<String>()
        override suspend fun page(account: String, cursor: String?) = ClientResult.Ready(ConversationPage(rows[account].orEmpty(), null))
        override suspend fun searchAvailability(): ClientResult<Unit> = ClientResult.Ready(Unit)
        override suspend fun openAvailability(account: String, conversation: String): ClientResult<Unit> = ClientResult.Ready(Unit)
        override fun changes(account: String) = events.also { subscribed += account }
    }
    private class Notifier : MessageNotifier {
        var allowed = true
        val posted = mutableListOf<String>()
        override fun allowed() = allowed
        override fun post(token: String) {
            posted += token
        }
    }
    private fun row(id: String, unread: ULong, muted: Boolean = false) =
        ConversationSummary(id, "Title $id", unread, false, unread > 0u, muted = muted)

    private class Rig(scope: TestScope) {
        val session = Session()
        val accounts = Accounts()
        val directory = Directory()
        val notifier = Notifier()
        val routes = NotificationRoutes(accounts)
        var foreground = false
        val job = MessageNotificationDriver(session, accounts, directory, notifier, routes) { foreground }
            .start(scope.backgroundScope)
        suspend fun change() = directory.events.emit(ClientResult.Ready(ConversationChange.Reload))
    }

    @Test fun firstReadIsTheBaselineThenGrownUnreadNotifiesWithAnOpaqueToken() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val rig = Rig(this)
        rig.directory.rows["a"] = listOf(row("talk-x", 3u), row("c2", 0u))
        runCurrent()
        assertEquals("existing unread messages are the baseline", emptyList<String>(), rig.notifier.posted)
        assertEquals(listOf("a"), rig.directory.subscribed)
        rig.directory.rows["a"] = listOf(row("talk-x", 4u), row("c2", 0u))
        rig.change()
        runCurrent()
        val token = rig.notifier.posted.single()
        assertTrue("the token carries no conversation id", !token.contains("talk-x") && !token.contains("a:"))
        rig.directory.rows["a"] = listOf(row("talk-x", 5u), row("c2", 0u))
        rig.change()
        runCurrent()
        assertEquals("one token per conversation: the notification is replaced", listOf(token, token), rig.notifier.posted)
        rig.routes.open(token)
        assertEquals(NotificationTarget("a", "talk-x"), rig.routes.claim())
        assertNull("a claim is taken once", rig.routes.claim())
    }

    @Test fun aNotifiedGroupConversationOpensWithItsGroupId() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val rig = Rig(this)
        val group = ConversationSummary("thread", "Team", 0u, false, false, group = true, groupId = "g")
        rig.directory.rows["a"] = listOf(group)
        runCurrent()
        rig.directory.rows["a"] = listOf(group.copy(unread = 1u, isUnread = true))
        rig.change()
        runCurrent()
        rig.routes.open(rig.notifier.posted.single())
        assertEquals(NotificationTarget("a", "thread", "g"), rig.routes.claim())
    }

    @Test fun mutedForegroundAndDisallowedPostNothing() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val rig = Rig(this)
        rig.directory.rows["a"] = listOf(row("muted", 0u, muted = true), row("c", 0u))
        runCurrent()
        rig.directory.rows["a"] = listOf(row("muted", 2u, muted = true), row("c", 0u))
        rig.change()
        runCurrent()
        assertTrue("muted conversations are skipped", rig.notifier.posted.isEmpty())
        rig.foreground = true
        rig.directory.rows["a"] = listOf(row("muted", 2u, muted = true), row("c", 1u))
        rig.change()
        runCurrent()
        assertTrue("nothing while the app is in front", rig.notifier.posted.isEmpty())
        rig.foreground = false
        rig.notifier.allowed = false
        rig.directory.rows["a"] = listOf(row("muted", 2u, muted = true), row("c", 2u))
        rig.change()
        runCurrent()
        assertTrue("nothing unless notifications are already allowed", rig.notifier.posted.isEmpty())
        rig.notifier.allowed = true
        rig.directory.rows["a"] = listOf(row("muted", 2u, muted = true), row("c", 3u), row("new", 1u))
        rig.change()
        runCurrent()
        assertEquals("a grown and a new conversation", 2, rig.notifier.posted.size)
    }

    @Test fun aClosedSessionStopsAndAnAccountSwitchStartsANewBaseline() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val rig = Rig(this)
        rig.directory.rows["a"] = listOf(row("c", 1u))
        rig.directory.rows["b"] = listOf(row("d", 7u))
        runCurrent()
        rig.accounts.catalog = rig.accounts.catalog.copy(activeId = "b")
        rig.accounts.revisions.emit(1)
        runCurrent()
        assertEquals(listOf("a", "b"), rig.directory.subscribed)
        assertTrue("the other account's unread messages are its baseline", rig.notifier.posted.isEmpty())
        rig.session.status.value = ClientSnapshot(ClientPhase.CLOSED, 2)
        runCurrent()
        rig.directory.rows["b"] = listOf(row("d", 9u))
        rig.change()
        runCurrent()
        assertTrue("no reads or posts with the session closed", rig.notifier.posted.isEmpty())
    }

    @Test fun aTappedNotificationOfAnotherAccountOrAForgedTokenOpensNothing() = runTest(timeout = 5.seconds) {
        val accounts = Accounts()
        val routes = NotificationRoutes(accounts)
        routes.open("forged")
        assertNull(routes.pendingOpen.value)
        val token = routes.issue(NotificationTarget("b", "d"))
        routes.open(token)
        assertEquals("the route waits for the shell (behind the unlock)", NotificationTarget("b", "d"), routes.pendingOpen.value)
        assertNull("a different active account drops the route", routes.claim())
        assertNull(routes.pendingOpen.value)
        accounts.catalog = AccountCatalog(listOf(AccountSummary("b", "Home", false, "locked")), "b")
        routes.open(token)
        assertNull("a locked account opens nothing", routes.claim())
    }
}

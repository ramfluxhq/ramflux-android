// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.ramflux.data.cards.CardSessionPool
import org.ramflux.data.cards.CardSessionRepository

/**
 * One composition-root instance owns the one native writer and the app preference store. [languages] are
 * the host languages the app offers (see [appLanguages]).
 */
class AndroidClientData(context: Context, expectedDescriptor: String, languages: List<AppLanguage>, openConfig: ClientOpenConfig = ClientOpenConfig()) {
    private val driver = AndroidClientLifecycleDriver(context.applicationContext, expectedDescriptor, openConfig.toCore())

    /** Account-scoped host stores (drafts, media) register here; see [AccountScopedStores]. */
    val accountStores = AccountScopedStores()
    private val cardPool = CardSessionPool(driver.transport).also { pool ->
        // A card draft or tree must not outlive its account: sign-out and removal dispose the account's sessions.
        accountStores.register { account -> pool.forget(account) }
        accountStores.onAdmit(pool::admit)
    }
    /** The card runtime: sessions shared by every chat screen, suspended and resumed with the client. */
    val cardSessions: CardSessionRepository = cardPool
    /**
     * The MLS groups session, one per process. It makes no call until a screen asks (none is linked yet); it is attached here so
     * the app lock, an account lock, a switch, sign-out and removal clear what it holds.
     */
    val mlsGroups = MlsGroupSession(DefaultMlsGroupRepository(driver.transport), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        .also { it.attach(accountStores) }
    val session: ClientSessionRepository = DefaultClientSessionRepository(driver, cards = cardPool, stores = accountStores)

    val nodes: NodeSetupRepository = DefaultNodeSetupRepository(driver.transport)
    val accounts: AccountAccessRepository = DefaultAccountAccessRepository(driver.transport, accountStores)
    val ceremony: AccountCeremonyRepository = DefaultAccountCeremonyRepository(driver.transport)
    val conversations: ConversationDirectoryRepository = DefaultConversationDirectoryRepository(driver.transport)
    val history: MessageHistoryRepository = DefaultMessageHistoryRepository(driver.transport)
    val calls: CallDirectoryRepository = DefaultCallDirectoryRepository(driver.transport)
    val taskDirectory: TaskDirectoryRepository = DefaultTaskDirectoryRepository(driver.transport)
    val agents: AgentDirectoryRepository = DefaultAgentDirectoryRepository(driver.transport)
    val groups: GroupDirectoryRepository = DefaultGroupDirectoryRepository(driver.transport)
    val contacts: ContactDirectoryRepository = DefaultContactDirectoryRepository(driver.transport)
    val identity: ContactIdentityRepository = DefaultContactIdentityRepository(driver.transport)
    val devices: DeviceDirectoryRepository = DefaultDeviceDirectoryRepository(driver.transport)
    val security: AccountSecurityRepository = DefaultAccountSecurityRepository(driver.transport)
    val backups: BackupRepository = DefaultBackupRepository(driver.transport, AndroidBackupFileStore(context.applicationContext))
    val accountStatus: AccountStatusRepository = DefaultAccountStatusRepository(driver.transport)
    val language: LanguageRepository = AndroidLanguageRepository(context.applicationContext, languages)
    val appearance: AppearanceRepository = AndroidAppearanceRepository(context.applicationContext)
}

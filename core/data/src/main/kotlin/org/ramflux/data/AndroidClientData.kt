// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context

/**
 * One composition-root instance owns the one native writer and the app preference store. [languages] are
 * the host languages the app offers (see [appLanguages]).
 */
class AndroidClientData(context: Context, expectedDescriptor: String, languages: List<AppLanguage>, openConfig: ClientOpenConfig = ClientOpenConfig()) {
    private val driver = AndroidClientLifecycleDriver(context.applicationContext, expectedDescriptor, openConfig.toCore())

    /** Account-scoped host stores (drafts, media) register here; see [AccountScopedStores]. */
    val accountStores = AccountScopedStores()
    val session: ClientSessionRepository = DefaultClientSessionRepository(driver)

    val nodes: NodeSetupRepository = DefaultNodeSetupRepository(driver.transport)
    val accounts: AccountAccessRepository = DefaultAccountAccessRepository(driver.transport, accountStores)
    val ceremony: AccountCeremonyRepository = DefaultAccountCeremonyRepository(driver.transport)
    val conversations: ConversationDirectoryRepository = DefaultConversationDirectoryRepository(driver.transport)
    val history: MessageHistoryRepository = DefaultMessageHistoryRepository(driver.transport)
    val calls: CallDirectoryRepository = DefaultCallDirectoryRepository(driver.transport)
    val cards: CardHostAvailabilityRepository = SurfaceCardHostAvailabilityRepository()
    val taskDirectory: TaskDirectoryRepository = DefaultTaskDirectoryRepository(driver.transport)
    val agents: AgentDirectoryRepository = DefaultAgentDirectoryRepository(driver.transport)
    val groups: GroupDirectoryRepository = DefaultGroupDirectoryRepository(driver.transport)
    val contacts: ContactDirectoryRepository = DefaultContactDirectoryRepository(driver.transport)
    val devices: DeviceDirectoryRepository = DefaultDeviceDirectoryRepository(driver.transport)
    val security: AccountSecurityRepository = DefaultAccountSecurityRepository(driver.transport)
    val backups: BackupRepository = DefaultBackupRepository(driver.transport, AndroidBackupFileStore(context.applicationContext))
    val accountStatus: AccountStatusRepository = DefaultAccountStatusRepository(driver.transport)
    val language: LanguageRepository = AndroidLanguageRepository(context.applicationContext, languages)
    val appearance: AppearanceRepository = AndroidAppearanceRepository(context.applicationContext)
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context

/**
 * One composition-root instance owns the one native writer and the app preference store. [languages] are
 * the host languages the app offers (see [appLanguages]).
 */
class AndroidClientData(context: Context, expectedDescriptor: String, languages: List<AppLanguage>) {
    private val driver = AndroidClientLifecycleDriver(context.applicationContext, expectedDescriptor)
    val session: ClientSessionRepository = DefaultClientSessionRepository(driver)
    val accounts: AccountAccessRepository = DefaultAccountAccessRepository(driver.transport)
    val ceremony: AccountCeremonyRepository = DefaultAccountCeremonyRepository(driver.transport)
    val nodes: NodeSetupRepository = DefaultNodeSetupRepository(driver.transport)
    val conversations: ConversationDirectoryRepository = DefaultConversationDirectoryRepository(driver.transport)
    val history: MessageHistoryRepository = DefaultMessageHistoryRepository(driver.transport)
    val calls: CallDirectoryRepository = DefaultCallDirectoryRepository(driver.transport)
    val cards: CardHostAvailabilityRepository = UnavailableCardHostAvailabilityRepository()
    val taskDirectory: TaskDirectoryRepository = DefaultTaskDirectoryRepository(driver.transport)
    val agents: AgentDirectoryRepository = UnavailableAgentDirectoryRepository()
    val groups: GroupDirectoryRepository = DefaultGroupDirectoryRepository(driver.transport)
    val contacts: ContactDirectoryRepository = DefaultContactDirectoryRepository(driver.transport)
    val devices: DeviceDirectoryRepository = DefaultDeviceDirectoryRepository(driver.transport)
    val accountStatus: AccountStatusRepository = DefaultAccountStatusRepository(driver.transport)
    val language: LanguageRepository = AndroidLanguageRepository(context.applicationContext, languages)
    val appearance: AppearanceRepository = AndroidAppearanceRepository(context.applicationContext)
}

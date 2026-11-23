// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.ramflux.data.*
import org.ramflux.feature.onboarding.ConfirmTrace

@Module
@InstallIn(SingletonComponent::class)
object ClientModule {
    @Provides @Singleton
    fun data(@ApplicationContext context: Context) =
        AndroidClientData(context, BuildConfig.MESSAGE_DESCRIPTOR, appLanguages(context.resources, R.xml._generated_res_locale_config))

    @Provides fun session(data: AndroidClientData): ClientSessionRepository = data.session

    @Provides fun accounts(data: AndroidClientData): AccountAccessRepository = data.accounts

    @Provides fun ceremony(data: AndroidClientData): AccountCeremonyRepository = data.ceremony

    @Provides fun nodes(data: AndroidClientData): NodeSetupRepository = data.nodes

    @Provides fun conversations(data: AndroidClientData): ConversationDirectoryRepository = data.conversations

    @Provides fun history(data: AndroidClientData): MessageHistoryRepository = data.history

    @Provides fun calls(data: AndroidClientData): CallDirectoryRepository = data.calls

    @Provides fun cards(data: AndroidClientData): CardHostAvailabilityRepository = data.cards

    @Provides fun tasks(data: AndroidClientData): TaskDirectoryRepository = data.taskDirectory

    @Provides fun agents(data: AndroidClientData): AgentDirectoryRepository = data.agents

    @Provides fun groups(data: AndroidClientData): GroupDirectoryRepository = data.groups

    @Provides fun contacts(data: AndroidClientData): ContactDirectoryRepository = data.contacts

    @Provides fun devices(data: AndroidClientData): DeviceDirectoryRepository = data.devices
    @Provides fun security(data: AndroidClientData): AccountSecurityRepository = data.security
    @Provides fun backups(data: AndroidClientData): BackupRepository = data.backups

    @Provides fun accountStores(data: AndroidClientData): AccountScopedStores = data.accountStores

    @Provides fun accountStatus(data: AndroidClientData): AccountStatusRepository = data.accountStatus

    /** The platform per-app language on API 33+, the device-local store before that. */
    @Provides @Singleton
    fun language(@ApplicationContext context: Context, data: AndroidClientData): LanguageRepository =
        appLanguageRepository(context, data.language)

    @Provides @Singleton
    fun osPermissions(@ApplicationContext context: Context): OsPermissionsRepository = AndroidOsPermissionsRepository(context)

    @Provides fun appearance(data: AndroidClientData): AppearanceRepository = data.appearance

    @Provides fun confirmTrace(): ConfirmTrace = if (BuildConfig.DEBUG) {
        ConfirmTrace { message -> Log.d("ramflux.abi", message) }
    } else {
        ConfirmTrace {}
    }
}

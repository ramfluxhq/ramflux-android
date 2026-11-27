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
import dagger.multibindings.Multibinds
import javax.inject.Singleton
import org.ramflux.data.*
import org.ramflux.data.cards.CardSessionRepository
import org.ramflux.feature.onboarding.ConfirmTrace

/** Declares the store set so the app builds even when no feature contributes a store. */
@Module
@InstallIn(SingletonComponent::class)
interface AccountStoreBindings {
    @Multibinds fun accountStores(): Set<AccountScopedStore>
}

@Module
@InstallIn(SingletonComponent::class)
object ClientModule {
    @Provides @Singleton
    fun data(@ApplicationContext context: Context) = AndroidClientData(
        context,
        BuildConfig.MESSAGE_DESCRIPTOR,
        appLanguages(context.resources, R.xml._generated_res_locale_config),
        openConfig()
    )

    /**
     * The open-config fields the host provisions: the username directory pin (build properties; absent leaves
     * usernames refused) and the SDK diagnostic log. Debug builds ask for `debug`, the real-run build also names
     * itself so the SDK may keep free text; the SDK lowers both unless the library carries its real-run feature.
     * Release builds keep the SDK defaults (`info`, `release`).
     */
    internal fun openConfig(): ClientOpenConfig = ClientOpenConfig(
        usernameDirectory = BuildConfig.USERNAME_DIRECTORY_URL.takeIf { it.isNotEmpty() }?.let {
            ClientDirectoryPin(
                it,
                BuildConfig.USERNAME_DIRECTORY_ID,
                BuildConfig.USERNAME_DIRECTORY_KEY,
                BuildConfig.USERNAME_DIRECTORY_GENERATION
            )
        },
        diagnostics = when (BuildConfig.BUILD_TYPE) {
            "realrun" -> ClientDiagnostics(level = "debug", buildClass = "realrun")
            "debug" -> ClientDiagnostics(level = "debug", buildClass = "beta")
            else -> null
        }
    )

    @Provides fun session(data: AndroidClientData): ClientSessionRepository = data.session

    /** Sign-out and local removal erase the host stores, so the hook is registered before the repository is handed out. */
    @Provides fun accounts(data: AndroidClientData, stores: Set<@JvmSuppressWildcards AccountScopedStore>): AccountAccessRepository {
        stores.forEach(data.accountStores::register)
        return data.accounts
    }

    @Provides fun ceremony(data: AndroidClientData): AccountCeremonyRepository = data.ceremony

    @Provides fun nodes(data: AndroidClientData): NodeSetupRepository = data.nodes

    @Provides fun conversations(data: AndroidClientData): ConversationDirectoryRepository = data.conversations

    @Provides fun history(data: AndroidClientData): MessageHistoryRepository = data.history

    @Provides fun calls(data: AndroidClientData): CallDirectoryRepository = data.calls

    @Provides fun cardSessions(data: AndroidClientData): CardSessionRepository = data.cardSessions

    @Provides fun tasks(data: AndroidClientData): TaskDirectoryRepository = data.taskDirectory

    @Provides fun agents(data: AndroidClientData): AgentDirectoryRepository = data.agents

    @Provides fun groups(data: AndroidClientData): GroupDirectoryRepository = data.groups

    @Provides fun contacts(data: AndroidClientData): ContactDirectoryRepository = data.contacts

    @Provides fun devices(data: AndroidClientData): DeviceDirectoryRepository = data.devices

    @Provides fun security(data: AndroidClientData): AccountSecurityRepository = data.security

    @Provides fun backups(data: AndroidClientData): BackupRepository = data.backups

    /** Every host store a feature contributes (drafts, media) is registered before the hook is used. */
    @Provides fun accountStores(data: AndroidClientData, stores: Set<@JvmSuppressWildcards AccountScopedStore>): AccountScopedStores =
        data.accountStores.also { hook -> stores.forEach(hook::register) }

    @Provides fun accountStatus(data: AndroidClientData): AccountStatusRepository = data.accountStatus

    /** The platform per-app language on API 33+, the device-local store before that. */
    @Provides @Singleton
    fun language(@ApplicationContext context: Context, data: AndroidClientData): LanguageRepository =
        appLanguageRepository(context, data.language)

    @Provides @Singleton
    fun osPermissions(@ApplicationContext context: Context): OsPermissionsRepository = AndroidOsPermissionsRepository(context)

    @Provides fun appearance(data: AndroidClientData): AppearanceRepository = data.appearance

    @Provides fun confirmTrace(): ConfirmTrace = if (BuildConfig.DEBUG) {
        ConfirmTrace { message -> Log.w(DEBUG_TAG, message) }
    } else {
        ConfirmTrace {}
    }
}

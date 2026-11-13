// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.ramflux.data.*

@Module
@InstallIn(SingletonComponent::class)
object ClientModule {
    @Provides @Singleton
    fun data(@ApplicationContext context: Context) = AndroidClientData(context, BuildConfig.MESSAGE_DESCRIPTOR)

    @Provides fun session(data: AndroidClientData): ClientSessionRepository = data.session

    @Provides fun accounts(data: AndroidClientData): AccountAccessRepository = data.accounts

    @Provides fun nodes(data: AndroidClientData): NodeSetupRepository = data.nodes

    @Provides fun conversations(data: AndroidClientData): ConversationDirectoryRepository = data.conversations

    @Provides fun history(data: AndroidClientData): MessageHistoryRepository = data.history

    @Provides fun devices(data: AndroidClientData): DeviceDirectoryRepository = data.devices

    @Provides fun accountStatus(data: AndroidClientData): AccountStatusRepository = data.accountStatus

    @Provides fun language(data: AndroidClientData): LanguageRepository = data.language

    @Provides fun appearance(data: AndroidClientData): AppearanceRepository = data.appearance
}

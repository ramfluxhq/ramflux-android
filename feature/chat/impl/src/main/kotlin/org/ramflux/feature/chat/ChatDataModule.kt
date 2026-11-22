// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.ramflux.data.ChatDraftRepository
import org.ramflux.data.EncryptedChatDraftRepository

/**
 * Chat-owned bindings that need only the application context. Bindings that need the shared SDK
 * transport stay in the app's client module.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatDataModule {
    @Provides @Singleton
    fun drafts(@ApplicationContext context: Context): ChatDraftRepository = EncryptedChatDraftRepository(context)
}

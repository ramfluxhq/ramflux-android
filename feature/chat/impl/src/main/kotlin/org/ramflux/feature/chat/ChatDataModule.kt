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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.ramflux.data.AttachmentHost
import org.ramflux.data.ChatDraftRepository
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.DefaultMediaRepository
import org.ramflux.data.EncryptedChatDraftRepository
import org.ramflux.data.MediaRepository
import org.ramflux.data.MediaStager
import org.ramflux.data.MediaTransfers
import org.ramflux.data.MessageHistoryRepository
import org.ramflux.data.UnavailableMediaRepository
import org.ramflux.media.AndroidMediaStager
import org.ramflux.media.Blake3PlaintextDigest

/**
 * Chat-owned bindings. Drafts need only the application context; media reuses the transport of the
 * message repository the app's client module provides.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatDataModule {
    @Provides @Singleton
    fun drafts(@ApplicationContext context: Context): ChatDraftRepository = EncryptedChatDraftRepository(context)

    /** Media shares the message repository's SDK transport; without it media is the typed unavailable state. */
    @Provides @Singleton
    fun media(@ApplicationContext context: Context, history: MessageHistoryRepository): MediaRepository =
        (history as? AttachmentHost)?.let {
            DefaultMediaRepository(it.repositoryTransport(), context.applicationContext.noBackupFilesDir, { Blake3PlaintextDigest() })
        } ?: UnavailableMediaRepository()

    /** One queue per process; its downloads end with the unlocked session (see [MediaTransfers]). */
    @Provides @Singleton
    fun transfers(@ApplicationContext context: Context, media: MediaRepository, session: ClientSessionRepository): MediaTransfers {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        return MediaTransfers(media, session, scope).also { MediaTransferServiceController(context.applicationContext, it).start(scope) }
    }

    @Provides @Singleton
    fun stager(@ApplicationContext context: Context, media: MediaRepository): MediaStager = AndroidMediaStager(context, media)
}

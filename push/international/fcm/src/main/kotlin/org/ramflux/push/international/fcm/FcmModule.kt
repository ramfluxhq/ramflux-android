// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.fcm

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.ramflux.push.api.PushProvider

@Module
@InstallIn(SingletonComponent::class)
internal abstract class FcmModule {
    @Binds
    abstract fun bindPushProvider(provider: FcmPushProvider): PushProvider
}

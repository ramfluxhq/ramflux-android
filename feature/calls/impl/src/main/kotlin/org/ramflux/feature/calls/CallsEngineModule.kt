// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The media engine of this build refuses: the SDK has no audio or video engine yet (request R28). */
@Module
@InstallIn(SingletonComponent::class)
object CallsEngineModule {
    @Provides fun engine(): CallMediaEngine = RefusingCallMediaEngine()
}

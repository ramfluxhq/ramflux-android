// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import org.ramflux.data.installProcessDebugTrace

@HiltAndroidApp
class RamfluxApplication : Application() {
    @Inject lateinit var sessionLifecycle: SessionLifecycle
    override fun onCreate() {
        if (BuildConfig.DEBUG) installProcessDebugTrace { message -> Log.d("ramflux.abi", message) }
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(sessionLifecycle)
    }
}

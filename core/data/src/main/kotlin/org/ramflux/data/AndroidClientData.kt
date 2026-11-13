// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context

/** One composition-root instance owns the one native writer and the app preference store. */
class AndroidClientData(context: Context, expectedDescriptor: String) {
    val session: ClientSessionRepository = DefaultClientSessionRepository(AndroidClientLifecycleDriver(context.applicationContext, expectedDescriptor))
    val appearance: AppearanceRepository = AndroidAppearanceRepository(context.applicationContext)
}

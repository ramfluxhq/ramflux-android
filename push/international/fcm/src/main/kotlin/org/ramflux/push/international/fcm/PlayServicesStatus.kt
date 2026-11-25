// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.fcm

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import org.ramflux.push.api.UnavailableReason

internal object PlayServicesStatus {
    /** Why Google Play services cannot be used for notifications on this phone, or null when it can. */
    fun check(context: Context): UnavailableReason? {
        val code = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context)
        return when (code) {
            ConnectionResult.SUCCESS -> null
            ConnectionResult.SERVICE_MISSING -> UnavailableReason.PLAY_SERVICES_MISSING
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
            ConnectionResult.SERVICE_UPDATING -> UnavailableReason.PLAY_SERVICES_UPDATE_REQUIRED
            ConnectionResult.SERVICE_DISABLED -> UnavailableReason.PLAY_SERVICES_DISABLED
            else -> UnavailableReason.PLAY_SERVICES_INVALID
        }
    }
}

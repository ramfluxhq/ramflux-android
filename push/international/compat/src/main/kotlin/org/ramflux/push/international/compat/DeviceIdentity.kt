// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.compat

import android.os.Build
import java.util.Locale
import org.ramflux.push.api.DeviceIdentity

/** The identity of this phone from the public build constants, trimmed and lower-cased for matching. */
fun currentDeviceIdentity(): DeviceIdentity = DeviceIdentity(
    manufacturer = normalise(Build.MANUFACTURER),
    brand = normalise(Build.BRAND),
    model = normalise(Build.MODEL),
    sdkInt = Build.VERSION.SDK_INT
)

internal fun normalise(value: String?): String = value.orEmpty().trim().lowercase(Locale.ROOT)

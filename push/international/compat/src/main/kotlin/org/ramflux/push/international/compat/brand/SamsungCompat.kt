// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.compat.brand

import javax.inject.Inject
import org.ramflux.push.api.BrandCompat
import org.ramflux.push.api.BrandFamily
import org.ramflux.push.api.Detection
import org.ramflux.push.api.DeviceIdentity
import org.ramflux.push.api.GuidanceStep
import org.ramflux.push.api.StepWeight
import org.ramflux.push.international.compat.R
import org.ramflux.push.international.compat.appDetails
import org.ramflux.push.international.compat.component

class SamsungCompat @Inject constructor() : BrandCompat {
    override val family = BrandFamily.SAMSUNG

    override fun matches(id: DeviceIdentity): Boolean = id.manufacturer == "samsung"

    override fun brandLabel(id: DeviceIdentity): Int = R.string.compat_brand_samsung

    override fun skinHint(id: DeviceIdentity): Int? = when (id.sdkInt) {
        34 -> R.string.compat_skin_one_ui_6
        35 -> R.string.compat_skin_one_ui_7
        36 -> R.string.compat_skin_one_ui_8
        else -> null
    }

    override fun steps(id: DeviceIdentity): List<GuidanceStep> = listOf(
        GuidanceStep(
            id = "samsung.never_sleeping",
            weight = StepWeight.RECOMMENDED,
            title = R.string.compat_samsung_sleeping_title,
            body = R.string.compat_samsung_sleeping_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                appDetails()
            )
        )
    )
}

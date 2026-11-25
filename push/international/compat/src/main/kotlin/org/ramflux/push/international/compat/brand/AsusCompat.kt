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
import org.ramflux.push.international.compat.isAny

class AsusCompat @Inject constructor() : BrandCompat {
    override val family = BrandFamily.ASUS

    override fun matches(id: DeviceIdentity): Boolean = id.isAny("asus")

    override fun brandLabel(id: DeviceIdentity): Int = R.string.compat_brand_asus

    override fun skinHint(id: DeviceIdentity): Int? = null

    override fun steps(id: DeviceIdentity): List<GuidanceStep> = if (id.sdkInt <= LAST_ZENUI_SDK) {
        listOf(
            GuidanceStep(
                id = "asus.autostart",
                weight = StepWeight.REQUIRED,
                title = R.string.compat_asus_autostart_title,
                body = R.string.compat_asus_autostart_body,
                detection = Detection.UNDETECTABLE,
                candidates = listOf(
                    component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                    component("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
                    appDetails()
                )
            )
        )
    } else {
        emptyList()
    }
}

/** ZenUI shipped up to Android 10 (API 29); later Zenfone and ROG builds are close to stock. */
private const val LAST_ZENUI_SDK = 29

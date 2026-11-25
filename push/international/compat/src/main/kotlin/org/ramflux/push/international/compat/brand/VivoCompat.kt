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

class VivoCompat @Inject constructor() : BrandCompat {
    override val family = BrandFamily.VIVO

    override fun matches(id: DeviceIdentity): Boolean = id.isAny("vivo", "iqoo")

    override fun brandLabel(id: DeviceIdentity): Int = if (id.isAny("iqoo")) R.string.compat_brand_iqoo else R.string.compat_brand_vivo

    override fun skinHint(id: DeviceIdentity): Int? = null

    override fun steps(id: DeviceIdentity): List<GuidanceStep> = listOf(
        GuidanceStep(
            id = "vivo.background_start",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_vivo_background_start_title,
            body = R.string.compat_vivo_background_start_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                appDetails()
            )
        ),
        GuidanceStep(
            id = "vivo.high_power",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_vivo_high_power_title,
            body = R.string.compat_vivo_high_power_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                appDetails()
            )
        ),
        GuidanceStep(
            id = "vivo.lock_recents",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_lock_recents_title,
            body = R.string.compat_lock_recents_body,
            detection = Detection.UNDETECTABLE,
            candidates = emptyList()
        )
    )
}

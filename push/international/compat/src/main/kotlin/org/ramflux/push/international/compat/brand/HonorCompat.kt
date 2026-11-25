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

class HonorCompat @Inject constructor() : BrandCompat {
    override val family = BrandFamily.HONOR

    override fun matches(id: DeviceIdentity): Boolean = id.manufacturer == "honor" || (id.brand == "honor" && id.manufacturer != "huawei")

    override fun brandLabel(id: DeviceIdentity): Int = R.string.compat_brand_honor

    override fun skinHint(id: DeviceIdentity): Int? = null

    override fun steps(id: DeviceIdentity): List<GuidanceStep> = listOf(
        GuidanceStep(
            id = "honor.app_launch",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_honor_app_launch_title,
            body = R.string.compat_honor_app_launch_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                appDetails()
            )
        )
    )
}

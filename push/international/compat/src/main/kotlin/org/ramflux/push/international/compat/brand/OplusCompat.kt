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

class OplusCompat @Inject constructor() : BrandCompat {
    override val family = BrandFamily.OPLUS

    override fun matches(id: DeviceIdentity): Boolean = id.isAny("oppo", "realme", "oneplus")

    override fun brandLabel(id: DeviceIdentity): Int = when {
        id.isAny("realme") -> R.string.compat_brand_realme
        id.isAny("oneplus") -> R.string.compat_brand_oneplus
        else -> R.string.compat_brand_oppo
    }

    override fun skinHint(id: DeviceIdentity): Int? = null

    override fun steps(id: DeviceIdentity): List<GuidanceStep> = listOf(
        GuidanceStep(
            id = "oplus.auto_launch",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_oplus_auto_launch_title,
            body = R.string.compat_oplus_auto_launch_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                appDetails()
            )
        ),
        GuidanceStep(
            id = "oplus.background",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_oplus_background_title,
            body = R.string.compat_oplus_background_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                appDetails()
            )
        ),
        GuidanceStep(
            id = "oplus.lock_recents",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_lock_recents_title,
            body = R.string.compat_lock_recents_body,
            detection = Detection.UNDETECTABLE,
            candidates = emptyList()
        )
    )
}

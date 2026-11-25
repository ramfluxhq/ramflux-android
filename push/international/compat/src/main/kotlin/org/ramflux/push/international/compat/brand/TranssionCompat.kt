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

class TranssionCompat @Inject constructor() : BrandCompat {
    override val family = BrandFamily.TRANSSION

    override fun matches(id: DeviceIdentity): Boolean =
        TRANSSION_NAMES.any { name -> id.manufacturer.contains(name) || id.brand.contains(name) }

    override fun brandLabel(id: DeviceIdentity): Int = when {
        id.manufacturer.contains("infinix") || id.brand.contains("infinix") -> R.string.compat_brand_infinix
        id.manufacturer.contains("itel") || id.brand.contains("itel") -> R.string.compat_brand_itel
        else -> R.string.compat_brand_tecno
    }

    override fun skinHint(id: DeviceIdentity): Int? = null

    override fun steps(id: DeviceIdentity): List<GuidanceStep> = listOf(
        GuidanceStep(
            id = "transsion.autostart",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_transsion_autostart_title,
            body = R.string.compat_transsion_autostart_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                component("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
                appDetails()
            )
        ),
        GuidanceStep(
            id = "transsion.power",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_transsion_power_title,
            body = R.string.compat_transsion_power_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                appDetails()
            )
        ),
        GuidanceStep(
            id = "transsion.lock_recents",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_lock_recents_title,
            body = R.string.compat_lock_recents_body,
            detection = Detection.UNDETECTABLE,
            candidates = emptyList()
        )
    )
}

/** Strings that appear in the manufacturer or brand of Tecno, Infinix and itel phones. */
private val TRANSSION_NAMES = listOf("tecno", "infinix", "itel", "transsion")

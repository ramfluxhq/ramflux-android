// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.compat.brand

import android.provider.Settings
import javax.inject.Inject
import org.ramflux.push.api.BrandCompat
import org.ramflux.push.api.BrandFamily
import org.ramflux.push.api.Detection
import org.ramflux.push.api.DeviceIdentity
import org.ramflux.push.api.GuidanceStep
import org.ramflux.push.api.IntentCandidate
import org.ramflux.push.api.StepWeight
import org.ramflux.push.international.compat.R
import org.ramflux.push.international.compat.appDetails

class SonyCompat @Inject constructor() : BrandCompat {
    override val family = BrandFamily.SONY

    override fun matches(id: DeviceIdentity): Boolean = id.manufacturer == "sony"

    override fun brandLabel(id: DeviceIdentity): Int = R.string.compat_brand_sony

    override fun skinHint(id: DeviceIdentity): Int? = null

    override fun steps(id: DeviceIdentity): List<GuidanceStep> = listOf(
        GuidanceStep(
            id = "sony.stamina",
            weight = StepWeight.RECOMMENDED,
            title = R.string.compat_sony_stamina_title,
            body = R.string.compat_sony_stamina_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                IntentCandidate.Action(Settings.ACTION_BATTERY_SAVER_SETTINGS, packageUri = false),
                appDetails()
            )
        )
    )
}

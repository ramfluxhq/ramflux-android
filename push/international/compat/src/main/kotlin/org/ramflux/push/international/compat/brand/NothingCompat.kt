// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.compat.brand

import javax.inject.Inject
import org.ramflux.push.api.BrandCompat
import org.ramflux.push.api.BrandFamily
import org.ramflux.push.api.DeviceIdentity
import org.ramflux.push.api.GuidanceStep
import org.ramflux.push.international.compat.R
import org.ramflux.push.international.compat.isAny

class NothingCompat @Inject constructor() : BrandCompat {
    override val family = BrandFamily.NOTHING

    override fun matches(id: DeviceIdentity): Boolean = id.isAny("nothing")

    override fun brandLabel(id: DeviceIdentity): Int = R.string.compat_brand_nothing

    override fun skinHint(id: DeviceIdentity): Int? = null

    override fun steps(id: DeviceIdentity): List<GuidanceStep> = emptyList()
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.compat

import android.provider.Settings
import javax.inject.Inject
import javax.inject.Singleton
import org.ramflux.push.api.BrandCompat
import org.ramflux.push.api.BrandFamily
import org.ramflux.push.api.DeviceGuidance
import org.ramflux.push.api.DeviceIdentity
import org.ramflux.push.api.IntentCandidate

/**
 * Picks the guidance for this phone from the bound brand set.
 *
 * Every vendor package, component, action and extra named under `brand/` is unverified, check on device: components
 * move between OS versions and vendors rename them. A candidate is only ever tried by resolving it first
 * (`SafeSettingsLauncher`), so a wrong entry costs a skipped screen, never a crash. The table of candidates with the
 * confidence of each is in the design document, section 3.3.
 */
@Singleton
class BrandRegistry @Inject constructor(compats: Set<@JvmSuppressWildcards BrandCompat>) : DeviceGuidance {
    private val fallback = compats.firstOrNull { it.family == BrandFamily.AOSP }
    private val ordered = compats.filter { it.family != BrandFamily.AOSP }.sortedBy { PRIORITY.indexOf(it.family) }

    init {
        require(fallback != null)
    }

    /** The first specific brand that matches, else the fallback for stock and unknown phones. */
    fun compatFor(id: DeviceIdentity): BrandCompat = ordered.firstOrNull { it.matches(id) } ?: checkNotNull(fallback)

    override fun identity(): DeviceIdentity = currentDeviceIdentity()

    override fun compat(): BrandCompat = compatFor(identity())

    private companion object {
        /** HONOR before HUAWEI, every specific brand before the fallback. */
        val PRIORITY = listOf(
            BrandFamily.HONOR,
            BrandFamily.HUAWEI,
            BrandFamily.XIAOMI,
            BrandFamily.OPLUS,
            BrandFamily.VIVO,
            BrandFamily.SAMSUNG,
            BrandFamily.SONY,
            BrandFamily.MOTOROLA,
            BrandFamily.NOTHING,
            BrandFamily.TRANSSION,
            BrandFamily.ASUS
        )
    }
}

internal fun component(pkg: String, cls: String, vararg extras: Pair<String, String>, action: String? = null): IntentCandidate =
    IntentCandidate.Component(pkg, cls, extras.toMap(), action)

internal fun appDetails(): IntentCandidate = IntentCandidate.Action(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri = true)

internal fun DeviceIdentity.isAny(vararg names: String): Boolean = manufacturer in names || brand in names

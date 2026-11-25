// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.api

import androidx.annotation.StringRes

enum class BrandFamily { AOSP, SAMSUNG, XIAOMI, HUAWEI, HONOR, OPLUS, VIVO, MOTOROLA, SONY, NOTHING, TRANSSION, ASUS }

/** Normalised (trimmed, lower-case) build identity of this phone; see the edition's compat module for how it is read. */
data class DeviceIdentity(val manufacturer: String, val brand: String, val model: String, val sdkInt: Int)

data class DeviceProfile(val family: BrandFamily, @StringRes val brandLabel: Int, val sdkInt: Int, @StringRes val skinHint: Int?)

enum class Detection { OK, PROBLEM, UNDETECTABLE }

enum class StepWeight { REQUIRED, RECOMMENDED }

data class GuidanceStep(
    val id: String,
    val weight: StepWeight,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val detection: Detection,
    val candidates: List<IntentCandidate>
)

/**
 * A settings screen that may open the page the step describes. Extra values may be [APP_PACKAGE] or [APP_LABEL]; the
 * launcher replaces them with this app's package name and launcher label.
 */
sealed interface IntentCandidate {
    data class Component(val pkg: String, val cls: String, val extras: Map<String, String> = emptyMap()) : IntentCandidate

    data class Action(val action: String, val packageUri: Boolean, val extras: Map<String, String> = emptyMap()) : IntentCandidate

    companion object {
        const val APP_PACKAGE = "app:package"
        const val APP_LABEL = "app:label"
    }
}

interface BrandCompat {
    val family: BrandFamily
    val brandLabel: Int

    fun matches(id: DeviceIdentity): Boolean

    /** Vendor steps only; the standard Android steps come from the edition-neutral module. */
    fun steps(id: DeviceIdentity): List<GuidanceStep>

    fun skinHint(id: DeviceIdentity): Int?
}

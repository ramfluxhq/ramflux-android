// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.compat.brand

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
import org.ramflux.push.international.compat.component
import org.ramflux.push.international.compat.isAny

class XiaomiCompat @Inject constructor() : BrandCompat {
    override val family = BrandFamily.XIAOMI

    override fun matches(id: DeviceIdentity): Boolean = id.isAny("xiaomi", "redmi", "poco")

    override fun brandLabel(id: DeviceIdentity): Int = when {
        id.isAny("redmi") -> R.string.compat_brand_redmi
        id.isAny("poco") -> R.string.compat_brand_poco
        else -> R.string.compat_brand_xiaomi
    }

    override fun skinHint(id: DeviceIdentity): Int? = null

    override fun steps(id: DeviceIdentity): List<GuidanceStep> = listOf(
        GuidanceStep(
            id = "xiaomi.autostart",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_xiaomi_autostart_title,
            body = R.string.compat_xiaomi_autostart_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
        ),
        GuidanceStep(
            id = "xiaomi.permissions",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_xiaomi_permissions_title,
            body = R.string.compat_xiaomi_permissions_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                component(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    "extra_pkgname" to IntentCandidate.APP_PACKAGE,
                    action = "miui.intent.action.APP_PERM_EDITOR"
                ),
                component(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                    "extra_pkgname" to IntentCandidate.APP_PACKAGE,
                    action = "miui.intent.action.APP_PERM_EDITOR"
                ),
                appDetails()
            )
        ),
        GuidanceStep(
            id = "xiaomi.battery",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_xiaomi_battery_title,
            body = R.string.compat_xiaomi_battery_body,
            detection = Detection.UNDETECTABLE,
            candidates = listOf(
                component(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                    "package_name" to IntentCandidate.APP_PACKAGE,
                    "package_label" to IntentCandidate.APP_LABEL
                )
            )
        ),
        GuidanceStep(
            id = "xiaomi.lock_recents",
            weight = StepWeight.REQUIRED,
            title = R.string.compat_lock_recents_title,
            body = R.string.compat_lock_recents_body,
            detection = Detection.UNDETECTABLE,
            candidates = emptyList()
        )
    )
}

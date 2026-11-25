// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import javax.inject.Inject
import javax.inject.Singleton
import org.ramflux.push.api.IntentCandidate

/** Which screen a launch reached. */
enum class SettingsTier { VENDOR, STANDARD, APP_DETAILS, NONE }

/**
 * The only way a vendor settings screen is opened. Every candidate is built, resolved and then started inside a
 * guard: a candidate that does not resolve, is not exported, or refuses to start is skipped, and when none opens
 * the app-details page is tried. It never throws, never loops, and never claims the setting is now on; the caller
 * starts it only from a person's tap.
 */
@Singleton
class SafeSettingsLauncher @Inject constructor() {
    fun launch(context: Context, candidates: List<IntentCandidate>): SettingsTier {
        for (candidate in candidates) {
            val intent = build(context, candidate) ?: continue
            if (!resolves(context, intent, explicit = candidate is IntentCandidate.Component)) continue
            if (start(context, intent)) {
                return if (candidate is IntentCandidate.Component) SettingsTier.VENDOR else SettingsTier.STANDARD
            }
        }
        val details = build(context, IntentCandidate.Action(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri = true))
        return if (details != null && resolves(context, details, explicit = false) && start(context, details)) {
            SettingsTier.APP_DETAILS
        } else {
            SettingsTier.NONE
        }
    }

    private fun build(context: Context, candidate: IntentCandidate): Intent? = try {
        val intent = when (candidate) {
            is IntentCandidate.Component -> Intent(candidate.action).setComponent(ComponentName(candidate.pkg, candidate.cls))
            is IntentCandidate.Action -> Intent(candidate.action).also {
                if (candidate.packageUri) it.data = Uri.fromParts("package", context.packageName, null)
            }
        }
        val extras = when (candidate) {
            is IntentCandidate.Component -> candidate.extras
            is IntentCandidate.Action -> candidate.extras
        }
        extras.forEach { (key, value) -> intent.putExtra(key, substitute(context, value)) }
        // A non-Activity context cannot start an activity without a task of its own.
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun substitute(context: Context, value: String): String = when (value) {
        IntentCandidate.APP_PACKAGE -> context.packageName
        IntentCandidate.APP_LABEL -> context.applicationInfo.loadLabel(context.packageManager).toString()
        else -> value
    }

    /** Resolves like `resolveActivity(MATCH_DEFAULT_ONLY)`; an explicit component is looked up by name. */
    private fun resolves(context: Context, intent: Intent, explicit: Boolean): Boolean = try {
        val info = intent.resolveActivityInfo(context.packageManager, PackageManager.MATCH_DEFAULT_ONLY)
        // A vendor screen that is not exported cannot be started from another app.
        info != null && (!explicit || info.exported)
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

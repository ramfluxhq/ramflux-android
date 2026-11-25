// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.fcm

/** The Firebase values of one Android app, as compiled in from the configuration file outside the repository. */
internal class FcmClient(
    val packageName: String,
    val appId: String,
    val apiKey: String,
    val projectId: String,
    val senderId: String
) {
    override fun toString(): String = "FcmClient(<redacted>)"
}

internal object FcmConfig {
    private const val FIELDS = 5

    /** The entry whose package name is [packageName], or null when there is none (the build is then not configured). */
    fun find(packageName: String, encoded: String = BuildConfig.FIREBASE_CLIENTS): FcmClient? = encoded.split(';')
        .map { it.split(',') }
        .firstOrNull { it.size == FIELDS && it[0] == packageName }
        ?.let { FcmClient(it[0], it[1], it[2], it[3], it[4]) }
}

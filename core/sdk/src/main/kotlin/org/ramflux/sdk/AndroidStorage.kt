// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import android.content.Context
import java.io.File

object AndroidStorage {
    /** The SDK owns layout, locks, sentinels and orphan recovery beneath this directory. */
    fun noBackupRoot(context: Context): CoreResult<File> {
        val app = context.applicationContext
        if (app.isDeviceProtectedStorage) return CoreResult.Failure(CoreError.VaultUnavailable())
        return try {
            val root = app.noBackupFilesDir.canonicalFile
            val data = File(app.applicationInfo.dataDir).canonicalFile
            if (!root.toPath().startsWith(data.toPath()) || root.name != "no_backup") {
                CoreResult.Failure(CoreError.VaultUnavailable())
            } else CoreResult.Success(root)
        } catch (_: java.io.IOException) { CoreResult.Failure(CoreError.VaultUnavailable()) }
    }
}

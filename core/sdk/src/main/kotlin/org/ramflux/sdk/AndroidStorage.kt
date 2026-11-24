// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import android.content.Context
import android.os.UserManager
import android.system.ErrnoException
import android.system.Os
import java.io.File

object AndroidStorage {
    private const val ROOT = "ramflux"
    private const val PRIVATE_MODE = 448 // 0700

    /**
     * The SDK's data root: a dedicated directory below credential-encrypted no-backup storage, owned by the app and
     * private to it (mode 0700), as the SDK's shared-root contract requires; the SDK owns layout, locks and recovery
     * beneath it. Before the first device unlock after a restart that storage is unavailable: custody is locked.
     */
    fun noBackupRoot(context: Context): CoreResult<File> {
        val app = context.applicationContext
        if (app.isDeviceProtectedStorage) return CoreResult.Failure(CoreError.VaultUnavailable())
        if (app.getSystemService(UserManager::class.java)?.isUserUnlocked == false) {
            return CoreResult.Failure(CoreError.Native(SdkStatus.VAULT_LOCKED.code, Effect.NONE))
        }
        return try {
            val parent = app.noBackupFilesDir.canonicalFile
            val data = File(app.applicationInfo.dataDir).canonicalFile
            if (!parent.toPath().startsWith(data.toPath()) || parent.name != "no_backup") return CoreResult.Failure(CoreError.VaultUnavailable())
            val root = File(parent, ROOT)
            if (!root.isDirectory) Os.mkdir(root.path, PRIVATE_MODE)
            Os.chmod(root.path, PRIVATE_MODE)
            CoreResult.Success(root)
        } catch (_: java.io.IOException) {
            CoreResult.Failure(CoreError.VaultUnavailable())
        } catch (_: ErrnoException) {
            CoreResult.Failure(CoreError.VaultUnavailable())
        }
    }
}

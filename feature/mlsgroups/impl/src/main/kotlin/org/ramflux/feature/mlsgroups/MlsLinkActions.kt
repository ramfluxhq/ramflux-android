// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * What the screens do with an invite link, each only on an explicit tap (S1). Copy marks the clipboard entry sensitive. Paste
 * reads the clipboard on the Paste tap only. The platform has no expiry for a clipboard entry, so none is set; this is
 * documented in the report.
 */
interface MlsLinkActions {
    fun copy(link: String)
    fun share(link: String)
    fun paste(): String?
}

@Composable
fun rememberMlsLinkActions(): MlsLinkActions {
    val context = LocalContext.current
    return remember(context) { AndroidMlsLinkActions(context) }
}

internal class AndroidMlsLinkActions(private val context: Context) : MlsLinkActions {
    override fun copy(link: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        // The label is empty: no link, no name in it. The key is the value of ClipDescription.EXTRA_IS_SENSITIVE (API 33), which is
        // written as text because the minimum API level is 28.
        val clip = ClipData.newPlainText("", link)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
    }

    override fun share(link: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun paste(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}

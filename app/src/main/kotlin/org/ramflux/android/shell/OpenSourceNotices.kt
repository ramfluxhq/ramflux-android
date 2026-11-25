// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.ramflux.android.R
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitPageHeader

private const val NOTICE_CAP = 16384

/** Licence texts that must travel with the app: the bundled fonts, the Kit icons, Bouncy Castle (BLAKE3) and the push libraries. */
private val NOTICES = listOf(
    "notices/hanken-grotesk-ofl.txt",
    "notices/jetbrains-mono-ofl.txt",
    "notices/lucide-isc.txt",
    "notices/bouncycastle-mit.txt",
    "notices/firebase-apache-2.0.txt",
    "notices/google-play-services.txt"
)

@Composable
internal fun OpenSourceNotices(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val context = LocalContext.current
    val body = remember(context) {
        NOTICES.joinToString(Char(10).toString().repeat(2)) { readNotice(context, it) }
    }
    Column(modifier.fillMaxSize().background(theme.bg)) {
        KitPageHeader(stringResource(R.string.shell_notices_title), onBack = onBack, tag = "shell-notices")
        Text(
            body,
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(theme.gap.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = theme.text)
        )
    }
}

private fun readNotice(context: Context, name: String): String {
    context.assets.open(name).use { stream ->
        val bytes = ByteArray(NOTICE_CAP)
        var offset = 0
        while (offset < NOTICE_CAP) {
            val count = stream.read(bytes, offset, NOTICE_CAP - offset)
            if (count < 0) break
            offset += count
        }
        return String(bytes, 0, offset, Charsets.UTF_8)
    }
}

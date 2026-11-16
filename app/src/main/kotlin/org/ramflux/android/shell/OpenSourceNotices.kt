// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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

@Composable
internal fun OpenSourceNotices(onBack: () -> Unit) {
    val theme = LocalRFTheme.current
    val context = LocalContext.current
    val body = remember(context) {
        readNotice(context, "notices/hanken-grotesk-ofl.txt") +
            Char(10).toString().repeat(2) +
            readNotice(context, "notices/jetbrains-mono-ofl.txt")
    }
    Column(Modifier.fillMaxSize().background(theme.bg).statusBarsPadding()) {
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

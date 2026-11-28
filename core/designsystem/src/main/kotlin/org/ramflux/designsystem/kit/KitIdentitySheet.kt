// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import org.ramflux.core.designsystem.R
import org.ramflux.designsystem.Bidi
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind

/** What the identity change sheet reports about the last tap; the text is the sheet's own. */
enum class KitIdentityNotice { AlreadyHandled, ChangedAgain, Failed }

/**
 * The identity change sheet (derived design D-M1-1): the contact's name and @username, the AI marker when the SDK says so,
 * "Accept new identity" and "Not now". It shows no code, number or QR. [waiting] is a change whose new identity is not known
 * yet: the primary action is disabled. [onAccept] is the only way to accept; closing the sheet never accepts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KitIdentityChangeSheet(
    name: String,
    username: String?,
    ai: Boolean,
    accepting: Boolean,
    waiting: Boolean,
    notice: KitIdentityNotice?,
    onAccept: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Not shown under the app-lock cover.
    if (LocalAppLockCovered.current) return
    val theme = LocalRFTheme.current
    val shape = RoundedCornerShape(topStart = dp(theme.radius_sheet), topEnd = dp(theme.radius_sheet))
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shown = Bidi.isolate(name, UntrustedKind.Name)
    ModalBottomSheet(
        onDismissRequest = { if (!accepting) onLater() },
        modifier = modifier.kitTag("identity-sheet"),
        sheetState = state,
        shape = shape,
        containerColor = theme.overlay,
        contentColor = theme.text,
        dragHandle = {
            Box(
                Modifier.padding(top = dp(RfMetrics.sheetPadTop)).size(dp(RfMetrics.grabberWidth), dp(RfMetrics.grabberHeight))
                    .clip(shapeOf(RfMetrics.grabberRadius)).background(theme.line_strong)
            )
        }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(
                start = dp(RfMetrics.sheetPadH),
                top = dp(RfMetrics.sheetGap),
                end = dp(RfMetrics.sheetPadH),
                bottom = dp(RfMetrics.sheetPadBottom)
            ),
            verticalArrangement = Arrangement.spacedBy(dp(RfMetrics.sheetGap))
        ) {
            KitText(
                stringResource(R.string.identity_sheet_title, shown),
                style = kitStyle(theme.size_mobile_body, FontWeight.SemiBold, theme.text, RfMetrics.lineTitle)
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dp(RfMetrics.headerGap))) {
                username?.takeIf { it.isNotBlank() }?.let {
                    UntrustedText(
                        USERNAME_SIGIL + it,
                        Modifier.kitTag("identity-sheet-username"),
                        UntrustedKind.Name,
                        kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, RfMetrics.lineSecondary),
                        maxLines = 1
                    )
                }
                if (ai) KitAiMarker()
            }
            KitText(
                stringResource(R.string.identity_sheet_body, shown),
                style = kitStyle(theme.size_body, FontWeight.Normal, theme.text, RfMetrics.lineBody)
            )
            val line = when {
                notice == KitIdentityNotice.AlreadyHandled -> stringResource(R.string.identity_accept_already)
                notice == KitIdentityNotice.ChangedAgain -> stringResource(R.string.identity_accept_again)
                notice == KitIdentityNotice.Failed -> stringResource(R.string.identity_accept_failed)
                waiting -> stringResource(R.string.identity_sheet_waiting)
                else -> null
            }
            if (line != null) {
                KitText(
                    line,
                    Modifier.kitTag("identity-sheet-notice"),
                    style = kitStyle(
                        theme.size_secondary,
                        FontWeight.Normal,
                        if (waiting && notice == null) theme.secondary else theme.warning,
                        RfMetrics.lineSecondary
                    )
                )
            }
            KitButton(
                stringResource(R.string.identity_sheet_accept),
                onAccept,
                enabled = !waiting && !accepting && notice != KitIdentityNotice.AlreadyHandled,
                loading = accepting,
                tag = "identity-accept"
            )
            KitButton(
                stringResource(R.string.identity_sheet_later),
                onLater,
                kind = KitButtonKind.Secondary,
                enabled = !accepting,
                tag = "identity-later"
            )
        }
    }
}

private const val USERNAME_SIGIL = '@'

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.feature.onboarding.impl.R

/**
 * Whether the remove dialog's window is secure (`FLAG_SECURE`). The app provides `false` only in the realrun build
 * (test-only, never shipped) because UI automation cannot read a secure window; every other build keeps it secure.
 */
val LocalRemoveDialogSecure = staticCompositionLocalOf { true }

/**
 * The confirmation in front of "remove from this device". One tap never removes: the destructive button enables only
 * after the person ticks "I understand". [accountName] is shown when it is known. The dialog closes when the app stops;
 * [failed] shows the generic failure line and keeps everything as it was.
 */
@Composable
internal fun RemoveAccountDialog(accountName: String?, busy: Boolean, failed: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val theme = LocalRFTheme.current
    val secure = LocalRemoveDialogSecure.current
    var understood by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onDismiss() }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.SecureOff
        )
    ) {
        Column(
            // A Dialog is its own window: test tags reach the accessibility tree as ids only when set here too.
            Modifier.background(theme.bg).semantics { testTagsAsResourceId = true }.testTag("remove-dialog").padding(theme.gap.dp),
            verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
        ) {
            Text(stringResource(R.string.ui_remove_dialog_title), style = MaterialTheme.typography.titleMedium)
            accountName?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("remove-dialog-name")) }
            Text(stringResource(R.string.ui_remove_dialog_body), style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = understood, enabled = !busy, role = Role.Checkbox, onValueChange = { understood = it })
                    .testTag("remove-understand"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = understood, onCheckedChange = null, enabled = !busy)
                Text(
                    stringResource(R.string.ui_remove_dialog_understand),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = theme.gap.dp)
                )
            }
            if (failed) {
                Text(
                    stringResource(R.string.app_lock_remove_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("remove-failed")
                )
            }
            KitButton(
                stringResource(R.string.ui_remove_dialog_confirm),
                onConfirm,
                Modifier.fillMaxWidth(),
                kind = KitButtonKind.Destructive,
                enabled = understood && !busy,
                tag = "remove-confirm"
            )
            KitButton(
                stringResource(R.string.ui_remove_dialog_cancel),
                onDismiss,
                Modifier.fillMaxWidth(),
                kind = KitButtonKind.Secondary,
                enabled = !busy,
                tag = "remove-cancel"
            )
        }
    }
}

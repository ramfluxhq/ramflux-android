// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.window.Dialog

/**
 * A separate dialog window inherits the host's current resource locale and Activity-reachable context. The dialog
 * is its own composition, so debuggable builds expose its test tags as view ids here too, as the activity does for
 * its content (UI automation across processes); release builds do not.
 */
@Composable
fun HostConfirmationDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val direction = LocalLayoutDirection.current
    Dialog(onDismissRequest = onDismissRequest) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides resources,
            LocalLayoutDirection provides direction
        ) {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                Box(Modifier.semantics { testTagsAsResourceId = true }, propagateMinConstraints = true) { content() }
            } else {
                content()
            }
        }
    }
}

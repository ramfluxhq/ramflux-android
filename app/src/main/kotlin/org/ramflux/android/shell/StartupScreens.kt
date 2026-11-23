// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ramflux.android.R
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitLoaderMark
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitSummaryCard

@Composable
internal fun SplashScreen(modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    StartupFrame("startup-splash", modifier = modifier, header = { KitPageHeader("") }, footer = {}, scroll = false) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.startup_mark),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = theme.size_mobile_large_title.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.text
                )
            )
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = theme.size_mobile_large_title.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.text
                )
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(theme.gap.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KitLoaderMark(theme.size_title, theme.secondary)
                Text(
                    stringResource(R.string.startup_starting),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = theme.size_body.sp,
                        color = theme.secondary
                    )
                )
            }
        }
    }
}

@Composable
internal fun GatewayScreen(onContinue: () -> Unit, onChangeNode: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    StartupFrame("startup-gateway", modifier = modifier, header = { KitPageHeader("", tag = "startup-gateway-header") }, footer = {
        KitButton(stringResource(R.string.startup_continue), onContinue, Modifier.fillMaxWidth(), tag = "startup-continue")
    }) {
        Wordmark()
        Text(
            stringResource(R.string.startup_headline),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = theme.size_title.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.text
            )
        )
        Text(
            stringResource(R.string.startup_gateway_purpose),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = theme.size_body.sp, color = theme.secondary)
        )
        KitSummaryCard(
            stringResource(R.string.startup_official_node),
            stringResource(R.string.startup_official_detail),
            tag = "startup-official"
        )
        KitRow(
            stringResource(R.string.startup_change_node),
            onChangeNode,
            icon = "network",
            value = stringResource(R.string.startup_change_node_detail),
            testTag = "startup-change-node"
        )
    }
}

@Composable
internal fun WelcomeScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onLink: () -> Unit,
    onRecover: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalRFTheme.current
    StartupFrame(
        "startup-welcome",
        modifier = modifier,
        header = { KitPageHeader("", onBack = onBack, tag = "startup-welcome-header") },
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                KitButton(stringResource(R.string.startup_create), onCreate, Modifier.fillMaxWidth(), tag = "startup-create")
                KitButton(
                    stringResource(R.string.startup_link),
                    onLink,
                    Modifier.fillMaxWidth(),
                    kind = KitButtonKind.Secondary,
                    tag = "startup-link"
                )
                KitButton(
                    stringResource(R.string.startup_recover),
                    onRecover,
                    Modifier.fillMaxWidth(),
                    kind = KitButtonKind.Text,
                    tag = "startup-recover"
                )
            }
        }
    ) {
        Wordmark()
        Text(
            stringResource(R.string.startup_headline),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = theme.size_title.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.text
            )
        )
        Text(
            stringResource(R.string.startup_welcome_purpose),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = theme.size_body.sp, color = theme.secondary)
        )
    }
}

@Composable
internal fun UnlockEntry(
    label: String,
    nodeUrl: String,
    onUnlock: () -> Unit,
    onChangeNode: () -> Unit,
    onUnauthorized: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalRFTheme.current
    val account = label.ifEmpty { stringResource(R.string.startup_account_on_device) }
    val nodeTitle = if (nodeUrl.isEmpty()) {
        stringResource(
            R.string.startup_official_node
        )
    } else {
        stringResource(R.string.startup_selected_node)
    }
    val nodeBody = if (nodeUrl.isEmpty()) stringResource(R.string.startup_official_detail) else nodeUrl
    StartupFrame(
        "startup-unlock",
        modifier = modifier,
        header = { KitPageHeader(stringResource(R.string.startup_identity_title), tag = "startup-unlock-header") },
        footer = {
            KitButton(stringResource(R.string.startup_unlock), onUnlock, Modifier.fillMaxWidth(), tag = "startup-unlock-continue")
        }
    ) {
        Text(
            stringResource(R.string.startup_identity_body),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = theme.size_body.sp, color = theme.secondary)
        )
        KitSummaryCard(nodeTitle, nodeBody, tag = "startup-node")
        KitRow(
            stringResource(R.string.startup_change_node),
            onChangeNode,
            icon = "network",
            value = stringResource(R.string.startup_change_node_note),
            testTag = "startup-change-node"
        )
        KitRow(
            account,
            onUnlock,
            icon = "user-round",
            value = stringResource(R.string.startup_account_on_device),
            testTag = "startup-account"
        )
        KitRow(
            stringResource(R.string.startup_device_unauthorized),
            onUnauthorized,
            icon = "shield",
            value = stringResource(R.string.startup_device_unauthorized_detail),
            testTag = "startup-unauthorized"
        )
        Text(
            stringResource(R.string.startup_no_account_note),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = theme.size_secondary.sp, color = theme.secondary)
        )
    }
}

@Composable
internal fun UnlockDetails(label: String, onBack: () -> Unit, onViewInfo: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val account = label.ifEmpty { stringResource(R.string.startup_account_on_device) }
    StartupFrame(
        "startup-unlock-details",
        modifier = modifier,
        header = { KitPageHeader(stringResource(R.string.startup_unlock), onBack = onBack, tag = "startup-details-header") },
        footer = {
            KitButton(
                stringResource(R.string.startup_view_unlock),
                onViewInfo,
                Modifier.fillMaxWidth(),
                kind = KitButtonKind.Secondary,
                tag = "startup-view-unlock"
            )
        }
    ) {
        KitSummaryCard(stringResource(R.string.startup_identity), account, selected = false, tag = "startup-identity")
        KitSummaryCard(
            stringResource(R.string.startup_this_device),
            stringResource(R.string.startup_primary_device),
            selected = false,
            tag = "startup-device"
        )
        Text(
            stringResource(R.string.startup_unlock_local_title),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = theme.size_title.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.text
            )
        )
        Text(
            stringResource(R.string.startup_unlock_local_body),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = theme.size_body.sp, color = theme.secondary)
        )
        Text(
            stringResource(R.string.startup_unlock_local_note),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = theme.size_secondary.sp, color = theme.secondary)
        )
    }
}

@Composable
private fun Wordmark() {
    val theme = LocalRFTheme.current
    Text(
        stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineLarge.copy(
            fontSize = theme.size_mobile_large_title.sp,
            fontWeight = FontWeight.Bold,
            color = theme.text
        )
    )
}

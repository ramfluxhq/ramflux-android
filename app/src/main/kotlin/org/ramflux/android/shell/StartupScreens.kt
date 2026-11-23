// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ramflux.android.R
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitFluxMark
import org.ramflux.designsystem.kit.KitLoaderMark
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitSummaryCard

/** MOB-S01: the app tile, the wordmark, the flux line and the starting indicator, a little above the centre. */
@Composable
internal fun SplashScreen(modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val density = LocalDensity.current
    StartupFrame("startup-splash", modifier = modifier, header = {}, footer = {}, scroll = false, contentTop = Dp(0f)) {
        Spacer(Modifier.weight(SPLASH_ABOVE))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(dimensionResource(R.dimen.startup_tile))
                    .clip(RoundedCornerShape(dimensionResource(R.dimen.startup_tile_radius)))
                    .background(theme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.startup_mark),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = with(density) { dimensionResource(R.dimen.startup_tile_letter).toSp() },
                        fontWeight = FontWeight.Bold,
                        color = theme.on_primary
                    )
                )
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_tile_gap)))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = with(density) { dimensionResource(R.dimen.startup_splash_wordmark).toSp() },
                    lineHeight = with(density) { dimensionResource(R.dimen.startup_splash_wordmark).toSp() } * LINE_TITLE,
                    fontWeight = FontWeight.Bold,
                    color = theme.text
                )
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_splash_mark_gap)))
            // Absolute: the board's offset is to the right in every layout direction, as the line is drawn.
            KitFluxMark(Modifier.absolutePadding(left = dimensionResource(R.dimen.startup_splash_mark_shift)))
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_splash_loader_gap)))
            Row(horizontalArrangement = Arrangement.spacedBy((theme.gap / 2).dp), verticalAlignment = Alignment.CenterVertically) {
                KitLoaderMark(theme.size_title, theme.secondary)
                Text(
                    stringResource(R.string.startup_starting),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = theme.size_secondary.sp,
                        lineHeight = (theme.size_secondary * LINE_BODY).sp,
                        color = theme.secondary
                    )
                )
            }
        }
        Spacer(Modifier.weight(1f - SPLASH_ABOVE))
    }
}

/** MOB-S02 (2026-11-16 revision): the brand intro, the official node card and the node-change card. */
@Composable
internal fun GatewayScreen(onContinue: () -> Unit, onChangeNode: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    StartupFrame(
        "startup-gateway",
        modifier = modifier,
        header = {},
        contentTop = dimensionResource(R.dimen.startup_intro_top),
        footer = {
            KitButton(stringResource(R.string.startup_continue), onContinue, Modifier.fillMaxWidth(), tag = "startup-continue")
        }
    ) {
        BrandIntro(
            stringResource(R.string.startup_gateway_purpose),
            dimensionResource(R.dimen.startup_tagline_gap),
            dimensionResource(R.dimen.startup_purpose_gap)
        )
        Spacer(Modifier.height(theme.gap.dp))
        KitSummaryCard(
            stringResource(R.string.startup_official_node),
            stringResource(R.string.startup_official_detail),
            Modifier.heightIn(min = dimensionResource(R.dimen.startup_card_min)),
            tag = "startup-official"
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.startup_card_gap)))
        // The board's filled card: the Kit row on the surface tone (its icon tile shares the tone).
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(theme.radius_card.dp)).background(theme.surface)) {
            KitRow(
                stringResource(R.string.startup_change_node),
                onChangeNode,
                icon = "network",
                value = stringResource(R.string.startup_change_node_detail),
                divider = false,
                testTag = "startup-change-node"
            )
        }
    }
}

/** MOB-S18: the brand intro and the create, link and recover stack. */
@Composable
internal fun WelcomeScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onLink: () -> Unit,
    onRecover: () -> Unit,
    modifier: Modifier = Modifier
) {
    StartupFrame(
        "startup-welcome",
        modifier = modifier,
        header = { StartupHeader("", onBack = onBack, tag = "startup-welcome-header") },
        contentTop = dimensionResource(R.dimen.startup_welcome_top),
        footer = {
            KitButton(stringResource(R.string.startup_create), onCreate, Modifier.fillMaxWidth(), tag = "startup-create")
            Spacer(Modifier.height((LocalRFTheme.current.gap / 2).dp))
            KitButton(
                stringResource(R.string.startup_link),
                onLink,
                Modifier.fillMaxWidth(),
                kind = KitButtonKind.Secondary,
                tag = "startup-link"
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_recover_gap)))
            KitButton(
                stringResource(R.string.startup_recover),
                onRecover,
                Modifier.fillMaxWidth(),
                kind = KitButtonKind.Text,
                tag = "startup-recover"
            )
        }
    ) {
        BrandIntro(
            stringResource(R.string.startup_welcome_purpose),
            dimensionResource(R.dimen.startup_welcome_tagline_gap),
            LocalRFTheme.current.gap.dp
        )
    }
}

/** MOB-A01: the node card with its change row, the existing-account section and the authorization row. */
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
    val nodeTitle = if (nodeUrl.isEmpty()) {
        stringResource(
            R.string.startup_official_node
        )
    } else {
        stringResource(R.string.startup_selected_node)
    }
    val nodeBody = if (nodeUrl.isEmpty()) stringResource(R.string.startup_official_detail) else nodeUrl
    GroupedTone {
        StartupFrame(
            "startup-unlock",
            modifier = modifier,
            header = { StartupHeader(stringResource(R.string.startup_identity_title), tag = "startup-unlock-header") },
            contentTop = dimensionResource(R.dimen.startup_grouped_top),
            footer = {
                KitButton(stringResource(R.string.startup_unlock), onUnlock, Modifier.fillMaxWidth(), tag = "startup-unlock-continue")
            }
        ) {
            StartupTitle(stringResource(R.string.startup_identity_body))
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_title_gap)))
            GroupedCard {
                Column(
                    Modifier.fillMaxWidth().padding(
                        start = theme.gap.dp,
                        end = theme.gap.dp,
                        top = dimensionResource(R.dimen.startup_node_top),
                        bottom = dimensionResource(R.dimen.startup_node_bottom)
                    ).testTag("startup-node")
                ) {
                    Text(nodeTitle, style = rowTitle())
                    Text(nodeBody, style = rowValue())
                }
                GroupedDivider()
                KitRow(
                    stringResource(R.string.startup_change_node),
                    onChangeNode,
                    icon = "network",
                    value = stringResource(R.string.startup_change_node_note),
                    divider = false,
                    testTag = "startup-change-node"
                )
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_section_gap)))
            // The section's info opens the locked account's unlock details (MOB-A02).
            StartupSection(
                stringResource(R.string.startup_account_on_device),
                onUnlock,
                infoLabel = stringResource(
                    R.string.startup_account_details,
                    label.ifEmpty { stringResource(R.string.startup_account_on_device) }
                ),
                tag = "startup-account"
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_section_gap)))
            GroupedCard {
                KitRow(
                    stringResource(R.string.startup_device_unauthorized),
                    onUnauthorized,
                    icon = "shield-check",
                    value = stringResource(R.string.startup_device_unauthorized_detail),
                    divider = false,
                    testTag = "startup-unauthorized"
                )
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_footnote_gap)))
            Text(
                stringResource(R.string.startup_no_account_note),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = theme.size_meta.sp,
                    lineHeight = (theme.size_meta * LINE_SECONDARY).sp,
                    color = theme.secondary
                )
            )
        }
    }
}

/** MOB-A02: the account, its identity and device rows, and the local-unlock note. */
@Composable
internal fun UnlockDetails(label: String, onBack: () -> Unit, onViewInfo: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val account = label.ifEmpty { stringResource(R.string.startup_account_on_device) }
    GroupedTone {
        StartupFrame(
            "startup-unlock-details",
            modifier = modifier,
            header = { StartupHeader(stringResource(R.string.startup_unlock), onBack = onBack, tag = "startup-details-header") },
            contentTop = dimensionResource(R.dimen.startup_grouped_top),
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
            StartupTitle(account, tag = "startup-identity")
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_title_gap)))
            GroupedCard {
                KeyValueRow(stringResource(R.string.startup_identity), stringResource(R.string.startup_identity_human))
                GroupedDivider()
                KeyValueRow(stringResource(R.string.startup_this_device), stringResource(R.string.startup_primary_device), "startup-device")
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_note_gap)))
            StartupSection(stringResource(R.string.startup_unlock_local_title), onViewInfo, tag = "startup-unlock-info")
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_note_gap)))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(theme.radius_card.dp)).background(theme.surface).padding(theme.gap.dp),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.startup_section_gap))
            ) {
                Text(stringResource(R.string.startup_unlock_local_body), style = rowTitle(FontWeight.SemiBold))
                Text(stringResource(R.string.startup_unlock_local_note), style = rowValue())
            }
        }
    }
}

/**
 * MOB-A07: how unlocking works on this phone, adapted to the six-digit account code (the board describes a data
 * passphrase and biometric unlock, neither of which this client offers). The button opens the code entry.
 */
@Composable
internal fun UnlockInfo(onBack: () -> Unit, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    GroupedTone {
        StartupFrame(
            "startup-unlock-info",
            modifier = modifier,
            header = { StartupHeader(stringResource(R.string.startup_view_unlock_title), onBack = onBack, tag = "startup-info-header") },
            contentTop = dimensionResource(R.dimen.startup_info_top),
            footer = {
                KitButton(stringResource(R.string.startup_unlock_with_code), onUnlock, Modifier.fillMaxWidth(), tag = "startup-unlock-code")
            }
        ) {
            Text(stringResource(R.string.startup_unlock_info_body), style = rowValue())
            Spacer(Modifier.height(dimensionResource(R.dimen.startup_info_gap)))
            GroupedCard {
                InfoRow("key", R.string.startup_unlock_with_code, R.string.startup_unlock_with_code_detail, true)
                InfoRow("help-circle", R.string.startup_no_biometric, R.string.startup_no_biometric_detail, true)
                InfoRow("smartphone", R.string.startup_no_app_pin, R.string.startup_no_app_pin_detail, true)
                InfoRow("life-buoy", R.string.startup_forgot_code, R.string.startup_forgot_code_detail, false)
            }
            Spacer(Modifier.height(theme.gap.dp))
        }
    }
}

@Composable
private fun InfoRow(icon: String, title: Int, detail: Int, divider: Boolean) {
    KitRow(
        stringResource(title),
        {},
        Modifier.heightIn(min = dimensionResource(R.dimen.startup_info_row)),
        icon = icon,
        value = stringResource(detail),
        trailing = KitRowTrailing.None,
        locked = true,
        divider = divider
    )
}

@Composable
private fun KeyValueRow(key: String, value: String, tag: String? = null) {
    val theme = LocalRFTheme.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.startup_key_row)).padding(horizontal = theme.gap.dp)
            .then(if (tag == null) Modifier else Modifier.testTag(tag)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)
    ) {
        Text(key, Modifier.weight(1f), style = rowTitle())
        Text(value, style = rowTitle().copy(color = theme.secondary, textAlign = TextAlign.End))
    }
}

/** The wordmark, the flux line, the tagline and the purpose line of S02 and S18. */
@Composable
private fun ColumnScope.BrandIntro(purpose: String, taglineGap: Dp, purposeGap: Dp) {
    val theme = LocalRFTheme.current
    Text(
        stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineLarge.copy(
            fontSize = theme.size_mobile_large_title.sp,
            fontWeight = FontWeight.Bold,
            color = theme.text
        )
    )
    Spacer(Modifier.height(dimensionResource(R.dimen.startup_mark_gap)))
    KitFluxMark()
    Spacer(Modifier.height(taglineGap))
    Text(
        stringResource(R.string.startup_headline),
        style = MaterialTheme.typography.titleLarge.copy(
            fontSize = theme.size_title.sp,
            lineHeight = (theme.size_title * LINE_TAGLINE).sp,
            fontWeight = FontWeight.SemiBold,
            color = theme.text
        )
    )
    Spacer(Modifier.height(purposeGap))
    Text(purpose, style = rowValue())
}

@Composable
private fun rowTitle(weight: FontWeight = FontWeight.Normal) = MaterialTheme.typography.bodyLarge.copy(
    fontSize = LocalRFTheme.current.size_mobile_body.sp,
    lineHeight = (LocalRFTheme.current.size_mobile_body * LINE_TITLE).sp,
    fontWeight = weight,
    color = LocalRFTheme.current.text
)

@Composable
private fun rowValue() = MaterialTheme.typography.bodyLarge.copy(
    fontSize = LocalRFTheme.current.size_body.sp,
    lineHeight = (LocalRFTheme.current.size_body * LINE_BODY).sp,
    color = LocalRFTheme.current.secondary
)

/** Share of the free height above the splash block (MOB-S01 places it a little above the centre). */
private const val SPLASH_ABOVE = 0.452f

/** Tagline line height on S02 and S18 (27 dp lines at 20 sp). */
private const val LINE_TAGLINE = 1.35f

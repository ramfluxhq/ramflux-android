// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.shortAccountId
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitAvatar
import org.ramflux.designsystem.kit.KitAvatarKind
import org.ramflux.feature.onboarding.impl.R

/**
 * The account a lock or remove screen acts on (D-2): the avatar initial, the display name as the title line, and one
 * secondary line under it. [status] is "Locked" on the lock page; the remove dialog passes the short id line [accountId]
 * instead. A name that could not be read is "Unknown", never an id.
 */
@Composable
internal fun AccountIdentity(
    name: String?,
    modifier: Modifier = Modifier,
    status: String? = null,
    accountId: String? = null,
    nameTag: String = "account-identity-name",
    statusTag: String? = null
) {
    val theme = LocalRFTheme.current
    val shown = name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ui_unknown_name)
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(theme.gap.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KitAvatar(shown.take(1), 48f, KitAvatarKind.Person)
        Column(Modifier.weight(1f)) {
            Text(shown, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(nameTag))
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.secondary,
                    modifier = if (statusTag != null) Modifier.testTag(statusTag) else Modifier
                )
            }
            accountId?.let {
                Text(
                    shortAccountId(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.secondary,
                    modifier = Modifier.testTag(nameTag + "-short-id")
                )
            }
        }
    }
}

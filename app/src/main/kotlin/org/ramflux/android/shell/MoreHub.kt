// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.ramflux.android.R
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow

@Composable
internal fun MoreHub(order: String, hidden: String, onOpen: (String) -> Unit, onEditNavigation: () -> Unit, onNotices: () -> Unit) {
    val theme = LocalRFTheme.current
    Column(Modifier.fillMaxSize().background(theme.bg).statusBarsPadding()) {
        KitPageHeader(stringResource(R.string.shell_tab_more), large = true, tag = "shell-more-header")
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            moreShortcutIds(order, hidden).forEach { id ->
                item(id) { MoreRow(id, onOpen) }
            }
            item("permissions") { MoreRow("permissions", onOpen) }
            item("settings") { MoreRow("settings", onOpen) }
            item("navigation") {
                KitRow(
                    stringResource(R.string.shell_more_navigation),
                    onEditNavigation,
                    icon = "sliders-horizontal",
                    value = stringResource(R.string.shell_more_navigation_detail),
                    testTag = "shell-more-navigation"
                )
            }
            item("notices") {
                KitRow(
                    stringResource(R.string.shell_more_notices),
                    onNotices,
                    icon = "file-text",
                    value = stringResource(R.string.shell_more_notices_detail),
                    testTag = "shell-more-notices"
                )
            }
        }
    }
}

@Composable
private fun MoreRow(id: String, onOpen: (String) -> Unit) {
    val copy = moreCopy(id)
    KitRow(
        stringResource(copy.title),
        { onOpen(id) },
        icon = copy.icon,
        value = stringResource(copy.detail),
        testTag = "shell-more-" + id
    )
}

private data class MoreCopy(val title: Int, val detail: Int, val icon: String)

private fun moreCopy(id: String): MoreCopy = when (id) {
    "contacts" -> MoreCopy(R.string.shell_more_contacts, R.string.shell_more_contacts_detail, "contact")
    "tasks" -> MoreCopy(R.string.shell_more_tasks, R.string.shell_more_tasks_detail, "clipboard-list")
    "permissions" -> MoreCopy(R.string.shell_more_permissions, R.string.shell_more_permissions_detail, "shield")
    else -> MoreCopy(R.string.shell_more_settings, R.string.shell_more_settings_detail, "settings")
}

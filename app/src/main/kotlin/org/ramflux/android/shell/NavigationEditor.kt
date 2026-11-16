// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.android.R
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitIconButton
import org.ramflux.designsystem.kit.KitPageHeader

@Composable
internal fun NavigationEditor(order: String, hidden: String, onOrder: (String) -> Unit, onHidden: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val theme = LocalRFTheme.current
    val gone = shellIds(hidden).toSet()
    val visible = shellIds(order).filter { it !in gone }
    val last = visible.lastIndex
    Column(Modifier.fillMaxSize().background(theme.bg).statusBarsPadding().navigationBarsPadding()) {
        KitPageHeader(stringResource(R.string.shell_nav_title), onBack = onBack, tag = "shell-nav-header")
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item("note") { EditorNote(stringResource(R.string.shell_nav_note)) }
            itemsIndexed(visible, key = { _, id -> id }) { index, id ->
                ShortcutEditorRow(
                    id,
                    index,
                    last,
                    onUp = { onOrder(moveVisibleShortcut(order, hidden, index, -1)) },
                    onDown = { onOrder(moveVisibleShortcut(order, hidden, index, 1)) },
                    onRemove = { onHidden(hideShortcut(hidden, id)) }
                )
            }
            item("add") {
                KitButton(
                    stringResource(R.string.shell_nav_add),
                    { onHidden(revealShortcut(hidden)) },
                    Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp),
                    kind = KitButtonKind.Secondary,
                    enabled = hidden.isNotEmpty(),
                    tag = "shell-nav-add"
                )
            }
            item("core") { EditorNote(stringResource(R.string.shell_nav_core_note)) }
        }
        Row(
            Modifier.fillMaxWidth().padding(theme.gap.dp),
            horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)
        ) {
            KitButton(
                stringResource(R.string.shell_nav_restore),
                {
                    onOrder(DEFAULT_SHELL_ORDER)
                    onHidden("")
                },
                Modifier.weight(1f),
                kind = KitButtonKind.Secondary,
                tag = "shell-nav-restore"
            )
            KitButton(stringResource(R.string.shell_nav_done), onBack, Modifier.weight(1f), tag = "shell-nav-done")
        }
    }
}

@Composable
private fun EditorNote(text: String) {
    val theme = LocalRFTheme.current
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = theme.gap.dp),
        color = theme.secondary,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun ShortcutEditorRow(id: String, index: Int, last: Int, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    val theme = LocalRFTheme.current
    val core = shellCore(id)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(shortcutLabel(id), Modifier.weight(1f), color = theme.text, style = MaterialTheme.typography.bodyLarge)
        KitIconButton(
            "arrow-up",
            stringResource(R.string.shell_nav_up),
            onUp,
            enabled = index > 0,
            tag = "shell-nav-up-" + index.toString()
        )
        KitIconButton(
            "arrow-down",
            stringResource(R.string.shell_nav_down),
            onDown,
            enabled = index < last,
            tag = "shell-nav-down-" + index.toString()
        )
        if (core) {
            KitIconButton(
                "lock-keyhole",
                stringResource(R.string.shell_nav_lock),
                {},
                enabled = false,
                tag = "shell-nav-lock-" + index.toString()
            )
        } else {
            KitIconButton(
                "x",
                stringResource(R.string.shell_nav_remove),
                onRemove,
                tag = "shell-nav-remove-" + index.toString()
            )
        }
    }
}

@Composable
private fun shortcutLabel(id: String): String = when (id) {
    "contacts" -> stringResource(R.string.shell_more_contacts)
    "tasks" -> stringResource(R.string.shell_more_tasks)
    "groups" -> stringResource(R.string.shell_tab_groups)
    "agents" -> stringResource(R.string.shell_tab_agents)
    else -> stringResource(R.string.shell_tab_messages)
}

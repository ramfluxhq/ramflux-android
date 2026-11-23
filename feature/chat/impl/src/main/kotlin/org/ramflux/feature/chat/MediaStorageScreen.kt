// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitConfirmDialog
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.feature.chat.impl.R

/** The media storage destination; the shell or the chat shows it full pane. */
@Composable
fun MediaStorageNavigation(
    accountId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: MediaStorageViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm, accountId) { vm.onAction(MediaStorageAction.Load(accountId)) }
    BackHandler(onBack = onBack)
    MediaStorageScreen(state, vm::onAction, onBack, modifier)
}

@Composable
fun MediaStorageScreen(
    state: MediaStorageState,
    onAction: (MediaStorageAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalRFTheme.current
    val context = LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    Column(modifier.fillMaxSize().background(theme.bg).testTag("media-storage")) {
        KitPageHeader(stringResource(R.string.storage_title), onBack = onBack, tag = "storage-header")
        if (state.closed) {
            KitEmpty("lock", stringResource(R.string.ui_private_data_is_closed), "")
            return@Column
        }
        KitRow(
            stringResource(R.string.storage_total),
            {},
            value = size(state.total),
            trailing = KitRowTrailing.None,
            testTag = "storage-total"
        )
        if (state.outbox > 0) {
            KitRow(stringResource(R.string.storage_outbox), {}, value = size(state.outbox), trailing = KitRowTrailing.None)
        }
        KitTipBar(stringResource(R.string.storage_policy), "info", Modifier.padding(theme.gap.dp))
        if (state.loading && state.rows.isEmpty()) {
            KitMeta(stringResource(R.string.storage_loading), Modifier.fillMaxWidth().padding(theme.gap.dp), centered = true)
        } else if (state.rows.isEmpty()) {
            KitEmpty("hard-drive", stringResource(R.string.storage_empty), "")
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.rows, key = { it.conversation }, contentType = { "conversation" }) { row ->
                    KitRow(
                        row.title.ifBlank { stringResource(R.string.ui_conversation) },
                        { onAction(MediaStorageAction.AskClear(row.conversation)) },
                        value = size(row.bytes),
                        trailing = KitRowTrailing.None,
                        peer = row.title.isNotBlank(),
                        testTag = "storage-" + row.conversation
                    )
                }
            }
            KitButton(
                stringResource(R.string.storage_clear_all),
                { onAction(MediaStorageAction.AskClear(null)) },
                Modifier.fillMaxWidth().padding(theme.gap.dp),
                kind = KitButtonKind.Destructive,
                tag = "storage-clear-all"
            )
        }
    }
    state.confirm?.let { target ->
        KitConfirmDialog(
            stringResource(if (target.isEmpty()) R.string.storage_clear_all_title else R.string.storage_clear_title),
            stringResource(R.string.storage_clear_body),
            stringResource(R.string.storage_clear),
            stringResource(R.string.ui_cancel),
            onConfirm = { onAction(MediaStorageAction.ConfirmClear) },
            onDismiss = { onAction(MediaStorageAction.Dismiss) }
        )
    }
}

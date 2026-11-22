// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.kit.KitConfirmDialog
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.render
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R
/**
 * The chat destination. The shell owns window insets (including the keyboard), the width cap and system
 * back: back pops the chat from the shell's stack, so predictive back animates through the shell. Only
 * the open message actions intercept back, to close themselves. [rooted] is true when the shell shows the
 * chat as the detail beside its list, where it has no back control.
 */
@Composable
fun ChatNavigation(
    accountId: String,
    conversationId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ChatViewModel = hiltViewModel(),
    groupId: String? = null,
    onAttachments: () -> Unit = {},
    rooted: Boolean = false,
    onDetails: (() -> Unit)? = null,
    onCall: (() -> Unit)? = null
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val details by rememberUpdatedState(onDetails)
    val call by rememberUpdatedState(onCall)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<ChatEvent.File?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    // The system photo picker needs no storage permission; documents come through the document picker.
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onAction(ChatAction.Picked(uri.toString(), image = true))
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onAction(ChatAction.Picked(uri.toString(), image = false))
    }
    fun hand(file: ChatEvent.File) {
        scope.launch {
            val done = when (file.use) {
                FileUse.OPEN -> ChatFileHandover.open(context, file.path, file.mime, file.name)
                FileUse.SHARE -> ChatFileHandover.share(context, file.path, file.mime, file.name)
                FileUse.SAVE -> ChatFileHandover.save(context, file.path, file.mime, file.name)
            }
            notice = when {
                !done -> R.string.chat_media_no_app
                file.use == FileUse.SAVE && file.mime.startsWith("image/") -> R.string.chat_media_saved
                else -> null
            }
        }
    }
    BackHandler(enabled = state.selectedId != null) { vm.onAction(ChatAction.Dismiss) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(ChatAction.Refresh) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.onAction(ChatAction.Pause) }
    LaunchedEffect(vm, accountId, conversationId, groupId) { vm.onAction(ChatAction.Load(accountId, conversationId, groupId)) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ChatEvent.Back -> back()
                is ChatEvent.Pick -> if (event.image) {
                    photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    documents.launch(ANY_DOCUMENT)
                }
                is ChatEvent.File -> if (event.use == FileUse.OPEN) confirm = event else hand(event)
                ChatEvent.Sent -> vm.onAction(ChatAction.Refresh)
                ChatEvent.Details -> details?.invoke()
                ChatEvent.Call -> call?.invoke()
                is ChatEvent.Copy -> clipboard.setText(AnnotatedString(event.text))
            }
        }
    }
    Box(modifier) {
        ChatScreen(state, vm::onAction, Modifier, rooted, showDetails = onDetails != null, showCall = onCall != null)
        notice?.let { text ->
            KitTipBar(stringResource(text), "info", Modifier.align(Alignment.TopCenter).padding(LocalRFTheme.current.gap.dp))
            LaunchedEffect(text) {
                delay(NOTICE_MS)
                notice = null
            }
        }
    }
    confirm?.let { file ->
        // Opening hands a copy to another app; the person confirms first (media design 4.4).
        KitConfirmDialog(
            stringResource(R.string.chat_media_open_title),
            uiText(R.string.chat_media_open_body, UiText.Untrusted(file.name)).render(),
            stringResource(R.string.chat_media_open),
            stringResource(R.string.ui_cancel),
            onConfirm = {
                confirm = null
                hand(file)
            },
            onDismiss = { confirm = null }
        )
    }
}

private const val NOTICE_MS = 3_000L

/** Every document type; the picker itself is the user's choice. */
private val ANY_DOCUMENT = arrayOf(charArrayOf('*', '/', '*').concatToString())

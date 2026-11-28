// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ramflux.data.MediaAttachment
import org.ramflux.designsystem.LocalRFDark
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
    detailsVm: DirectDetailsViewModel = hiltViewModel(),
    groupId: String? = null,
    title: String? = null,
    rooted: Boolean = false,
    onDetails: (() -> Unit)? = null,
    onCall: (() -> Unit)? = null,
    onCameraPermission: (() -> Unit)? = null,
    onStorage: (() -> Unit)? = null,
    bottomInset: WindowInsets = WindowInsets(0)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val details by rememberUpdatedState(onDetails)
    val call by rememberUpdatedState(onCall)
    val openStorage by rememberUpdatedState(onStorage)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<ChatEvent.File?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    // A direct chat shows its own details over the chat; a group chat opens the host's group details.
    var directDetails by rememberSaveable { mutableStateOf(false) }
    val detailsState by detailsVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(directDetails, accountId, conversationId) {
        if (directDetails && groupId == null) detailsVm.open(accountId, conversationId, title)
    }
    // The system photo picker needs no storage permission; documents come through the document picker.
    // Up to 16 photos and videos become one message (the SDK's attachment limit per message).
    val pickMany = ActivityResultContracts.PickMultipleVisualMedia(MediaAttachment.MAX_PER_MESSAGE)
    val photos = rememberLauncherForActivityResult(pickMany) { uris ->
        if (uris.isNotEmpty()) vm.onAction(ChatAction.Picked(uris.map { it.toString() }, PickSource.PHOTO))
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onAction(ChatAction.Picked(listOf(uri.toString()), PickSource.FILE))
    }
    // The capture target survives the camera activity and process death; it is a cache path, not content.
    var capture by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val target = capture
        capture = null
        if (saved && target != null) {
            vm.onAction(ChatAction.Picked(listOf(target), PickSource.CAMERA))
        } else {
            scope.launch { ChatFileHandover.discardCapture(context) }
        }
    }
    val cameraPermission by rememberUpdatedState(onCameraPermission)
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    fun cameraGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun takePhoto() {
        // The camera permission is asked only through the permissions feature, which explains it first.
        if (!cameraGranted()) {
            cameraPermission?.invoke()
            return
        }
        scope.launch {
            val target = ChatFileHandover.captureTarget(context) ?: return@launch
            capture = target.toString()
            camera.launch(target)
        }
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
    LaunchedEffect(vm, accountId, conversationId, groupId) { vm.onAction(ChatAction.Load(accountId, conversationId, groupId, title)) }
    // Agent cards draw in the app's locale and theme; a change is sent to the card runtime (present).
    val cardLocale = LocalConfiguration.current.locales[0].toLanguageTag()
    val cardDark = LocalRFDark.current
    LaunchedEffect(vm, cardLocale, cardDark) { vm.onAction(ChatAction.CardAppearance(cardLocale, cardDark)) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ChatEvent.Back -> back()
                is ChatEvent.Pick -> when (event.source) {
                    PickSource.PHOTO -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    PickSource.CAMERA -> takePhoto()
                    PickSource.FILE -> documents.launch(ANY_DOCUMENT)
                }
                is ChatEvent.File -> if (event.use == FileUse.OPEN) confirm = event else hand(event)
                ChatEvent.Sent -> vm.onAction(ChatAction.Refresh)
                ChatEvent.Details -> details?.invoke() ?: run { if (groupId == null) directDetails = true }
                ChatEvent.Call -> call?.invoke()
                ChatEvent.Storage -> openStorage?.invoke()
                // W: the MLS group chat opens the invite panel and group info through the groups routes; the direct chat never sends these.
                ChatEvent.MlsInvite, ChatEvent.MlsAlerts -> Unit
                is ChatEvent.Copy -> clipboard.setText(AnnotatedString(event.text))
                is ChatEvent.OpenUrl -> runCatching {
                    // Only web links leave the app; any other scheme in a card link is dropped.
                    val uri = event.url.toUri()
                    if (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                    }
                }
            }
        }
    }
    Box(modifier) {
        ChatScreen(
            state,
            vm::onAction,
            Modifier,
            rooted,
            showDetails = onDetails != null || groupId == null,
            showCall = onCall != null,
            showCamera = hasCamera && (onCameraPermission != null || cameraGranted()),
            bottomInset = bottomInset,
            showStorage = onStorage != null
        )
        if (directDetails && groupId == null) {
            DirectDetails(
                detailsState,
                detailsVm::onAction,
                onGallery = {
                    directDetails = false
                    detailsVm.close()
                    vm.onAction(ChatAction.OpenGallery)
                },
                onReview = {
                    directDetails = false
                    detailsVm.close()
                    vm.onAction(ChatAction.ReviewIdentity)
                },
                onClose = {
                    directDetails = false
                    detailsVm.close()
                }
            )
        }
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

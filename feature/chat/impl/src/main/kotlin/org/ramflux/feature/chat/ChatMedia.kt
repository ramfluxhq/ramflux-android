// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.ALBUM_TILES
import org.ramflux.designsystem.kit.KitAlbumGrid
import org.ramflux.designsystem.kit.KitBubble
import org.ramflux.designsystem.kit.KitBubbleKind
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitIconButton
import org.ramflux.designsystem.kit.KitMediaBubble
import org.ramflux.designsystem.kit.KitMediaGlyph
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitSegmented
import org.ramflux.designsystem.kit.KitTextField
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.KitZoomableImage
import org.ramflux.designsystem.render
import org.ramflux.feature.chat.impl.R
import org.ramflux.media.MediaAnimatedImage
import org.ramflux.media.MediaMotion
import org.ramflux.media.MediaVideoSurface
import org.ramflux.media.rememberAutoplayAllowed
import org.ramflux.media.rememberLocalVideoPlayer
import org.ramflux.media.rememberMediaImage
import org.ramflux.media.rememberVideoDuration
import org.ramflux.media.rememberVideoPoster

/** Pictures and videos of one message: one box, or an album grid from two items on; files as rows. */
@Composable
internal fun Attachments(items: List<AttachmentItem>, own: Boolean, time: String?, cap: Dp, onAction: (ChatAction) -> Unit) {
    val visual = items.filter { it.kind != AttachmentKind.FILE }
    if (visual.size >= 2) {
        Album(visual, own, time, cap, onAction)
    } else {
        visual.firstOrNull()?.let { MediaBox(it, own, time, cap, onAction) }
    }
    items.filter { it.kind == AttachmentKind.FILE }.forEach { FileAttachment(it, time, cap, onAction) }
}

@Composable
private fun statusText(item: AttachmentItem): String? = when (item.status) {
    AttachmentStatus.HIDDEN -> stringResource(R.string.chat_media_hidden)
    AttachmentStatus.AVAILABLE -> stringResource(R.string.chat_media_download)
    AttachmentStatus.LOADING -> stringResource(R.string.chat_media_loading)
    AttachmentStatus.READY -> null
    AttachmentStatus.FAILED -> stringResource(R.string.chat_media_failed)
    AttachmentStatus.EXPIRED -> stringResource(R.string.chat_media_expired)
    AttachmentStatus.REVOKED -> stringResource(R.string.chat_media_revoked)
    AttachmentStatus.NOT_ON_DEVICE -> stringResource(R.string.chat_media_not_on_device)
    AttachmentStatus.SENDING -> stringResource(R.string.chat_media_sending)
}

private fun statusIcon(item: AttachmentItem): String? = when (item.status) {
    AttachmentStatus.HIDDEN -> "eye"
    AttachmentStatus.AVAILABLE, AttachmentStatus.FAILED -> if (item.kind == AttachmentKind.FILE) "download" else "image-down"
    // The SDK reports no byte progress (R4, R5): an indeterminate mark, never a percentage.
    AttachmentStatus.LOADING, AttachmentStatus.SENDING -> "loader-circle"
    AttachmentStatus.EXPIRED -> "timer-off"
    AttachmentStatus.REVOKED, AttachmentStatus.NOT_ON_DEVICE -> "ban"
    AttachmentStatus.READY -> null
}

/** The decrypted or staged file may be decoded only when it is this device's own copy. */
private val AttachmentItem.decodable: String?
    get() = localPath?.takeIf { status == AttachmentStatus.READY || status == AttachmentStatus.SENDING }

private val AttachmentItem.animated: Boolean
    get() = kind == AttachmentKind.ANIMATED || mime == "image/webp"

@Composable
private fun description(item: AttachmentItem) = stringResource(
    when {
        item.kind == AttachmentKind.VIDEO -> R.string.chat_media_video
        item.kind == AttachmentKind.ANIMATED -> R.string.chat_media_animated
        else -> R.string.chat_media_image
    }
)

@Composable
private fun FileAttachment(item: AttachmentItem, time: String?, cap: Dp, onAction: (ChatAction) -> Unit) {
    val status = statusText(item)
    val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, item.size)
    KitBubble(
        "",
        Modifier.clickable { onAction(ChatAction.OpenAttachment(item.key)) }.testTag("attachment-" + item.key),
        kind = KitBubbleKind.File,
        time = time,
        fileName = item.fileName.ifEmpty { stringResource(R.string.chat_attach_file) },
        fileMeta = if (status == null) size else stringResource(R.string.chat_file_meta, size, status),
        fileIcon = statusIcon(item) ?: "file-text",
        maxWidth = cap
    )
}

/** One picture, animation or video sized from message data; videos never play in the timeline. */
@Composable
private fun MediaBox(item: AttachmentItem, own: Boolean, time: String?, cap: Dp, onAction: (ChatAction) -> Unit) {
    val target = with(LocalDensity.current) { cap.roundToPx() }
    val path = item.decodable
    val video = item.kind == AttachmentKind.VIDEO
    val duration = if (video) rememberVideoDuration(path) else null
    var share by remember { mutableFloatStateOf(0f) }
    val autoplay = rememberAutoplayAllowed()
    KitMediaBubble(
        when {
            path == null -> null
            video -> rememberVideoPoster(path, target)
            item.animated -> null
            else -> rememberMediaImage(path, target)
        },
        item.aspect,
        own,
        Modifier.onGloballyPositioned { share = visibleShare(it) },
        icon = statusIcon(item),
        status = statusText(item),
        time = time,
        badge = duration?.let(::mediaClock),
        description = description(item),
        maxWidth = cap,
        onClick = { onAction(ChatAction.OpenAttachment(item.key)) },
        tag = "attachment-" + item.key,
        glyph = if (video) "play" else null,
        content = if (path != null && item.animated && !video) {
            { MediaAnimatedImage(path, item.key, target, autoplay && share >= MediaMotion.VISIBLE_SHARE, Modifier.fillMaxSize()) }
        } else {
            null
        }
    )
}

@Composable
private fun Album(items: List<AttachmentItem>, own: Boolean, time: String?, cap: Dp, onAction: (ChatAction) -> Unit) {
    val label = pluralStringResource(R.plurals.chat_media_album, items.size, items.size)
    val extra = items.size - ALBUM_TILES
    var share by remember { mutableFloatStateOf(0f) }
    val autoplay = rememberAutoplayAllowed()
    KitAlbumGrid(
        items.size,
        own,
        Modifier.semantics { contentDescription = label }.onGloballyPositioned { share = visibleShare(it) },
        maxWidth = cap,
        more = if (extra > 0) stringResource(R.string.chat_media_more, extra) else null,
        time = time,
        tag = "album-" + items.first().messageId
    ) { index, modifier ->
        val item = items[index]
        AlbumTile(item, modifier, cap / 2, autoplay && share >= MediaMotion.VISIBLE_SHARE, onAction)
    }
}

@Composable
private fun AlbumTile(item: AttachmentItem, modifier: Modifier, edge: Dp, play: Boolean, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    val target = with(LocalDensity.current) { edge.roundToPx() }
    val path = item.decodable
    val video = item.kind == AttachmentKind.VIDEO
    val label = description(item)
    Box(
        modifier
            .clickable { onAction(ChatAction.OpenAttachment(item.key)) }
            .semantics { contentDescription = label }
            .testTag("attachment-" + item.key),
        contentAlignment = Alignment.Center
    ) {
        when {
            path == null -> statusIcon(item)?.let { KitMediaGlyph(it) }
            item.animated && !video -> MediaAnimatedImage(path, item.key, target, play, Modifier.fillMaxSize())
            else -> {
                val image = if (video) rememberVideoPoster(path, target) else rememberMediaImage(path, target)
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (video) KitMediaGlyph("play")
            }
        }
        if (path == null && item.status == AttachmentStatus.LOADING) {
            Box(Modifier.matchParentSize().background(theme.scrim))
        }
    }
}

/** The share of a laid-out bubble that is inside the window, after every clipping parent. */
private fun visibleShare(coordinates: androidx.compose.ui.layout.LayoutCoordinates): Float {
    val height = coordinates.size.height
    return if (height <= 0) 0f else coordinates.boundsInWindow().height / height
}

/** Media time is not text: Western digits, left to right, minutes and seconds (design 8). */
fun mediaClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return buildString {
        if (hours > 0) {
            append(hours)
            append(':')
            append(minutes.toString().padStart(2, '0'))
        } else {
            append(minutes)
        }
        append(':')
        append(seconds.toString().padStart(2, '0'))
    }
}

/**
 * Full-screen picture, animation or video. Pictures zoom; animations loop with a pause control; videos
 * wait for play, with sound on and a mute control. Save, forward and share go through the system; the
 * window keeps its secure flag.
 */
@Composable
internal fun BoxScope.Viewer(item: AttachmentItem, position: ViewerPosition?, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    BackHandler { onAction(ChatAction.CloseViewer) }
    BoxWithConstraints(Modifier.matchParentSize().background(theme.bg).testTag("media-viewer")) {
        val target = with(LocalDensity.current) { (maxOf(maxWidth, maxHeight) * 2).roundToPx() }
        val path = item.localPath
        when {
            path == null -> Unit
            item.kind == AttachmentKind.VIDEO -> VideoPane(path, Modifier.fillMaxSize())
            item.animated -> {
                var paused by remember(item.key) { mutableStateOf(false) }
                MediaAnimatedImage(path, "viewer:" + item.key, target, !paused, Modifier.fillMaxSize())
                KitIconButton(
                    if (paused) "play" else "pause",
                    stringResource(if (paused) R.string.chat_viewer_play else R.string.chat_viewer_pause),
                    { paused = !paused },
                    Modifier.align(Alignment.BottomCenter).padding(theme.gap.dp),
                    tag = "viewer-pause"
                )
            }
            else -> rememberMediaImage(path, target)?.let { KitZoomableImage(it, description(item)) }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(theme.gap.dp),
            horizontalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp)
        ) {
            KitIconButton("download", stringResource(R.string.chat_media_save), { onAction(ChatAction.SaveAttachment(item.key)) })
            KitIconButton(
                "forward",
                stringResource(R.string.chat_action_forward),
                { onAction(ChatAction.Forward(item.messageId, item.index)) }
            )
            KitIconButton("share-2", stringResource(R.string.chat_media_share), { onAction(ChatAction.ShareAttachment(item.key)) })
            KitIconButton("x", stringResource(R.string.chat_media_close), { onAction(ChatAction.CloseViewer) }, tag = "viewer-close")
        }
        position?.let {
            Row(
                Modifier.align(Alignment.TopStart).padding(theme.gap.dp),
                horizontalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KitIconButton("chevron-left", stringResource(R.string.chat_viewer_previous), { onAction(ChatAction.ViewerStep(-1)) })
                KitMeta(stringResource(R.string.chat_viewer_position, it.index, it.count))
                KitIconButton("chevron-right", stringResource(R.string.chat_viewer_next), { onAction(ChatAction.ViewerStep(1)) })
            }
        }
    }
}

@Composable
private fun VideoPane(path: String, modifier: Modifier) {
    val theme = LocalRFTheme.current
    val player = rememberLocalVideoPlayer(path)
    Box(modifier) {
        if (player.failed) {
            KitTipBar(
                stringResource(R.string.chat_viewer_video_failed),
                "circle-alert",
                Modifier.align(Alignment.Center).padding(theme.gap.dp)
            )
            return@Box
        }
        MediaVideoSurface(player, Modifier.fillMaxSize().testTag("viewer-video"))
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(theme.gap.dp),
            horizontalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KitIconButton(
                if (player.playing) "pause" else "play",
                stringResource(if (player.playing) R.string.chat_viewer_pause else R.string.chat_viewer_play),
                player::toggle,
                tag = "viewer-play"
            )
            val elapsed = mediaClock(player.positionMs)
            KitMeta(stringResource(R.string.chat_viewer_time, elapsed, mediaClock(player.durationMs)), Modifier.weight(1f))
            KitIconButton(
                if (player.muted) "volume-x" else "volume-2",
                stringResource(if (player.muted) R.string.chat_viewer_unmute else R.string.chat_viewer_mute),
                player::toggleMute,
                tag = "viewer-mute"
            )
        }
    }
}

/**
 * The conversation gallery (board B29 to be drawn): pictures and videos in a lazy grid, files in a lazy
 * list, both keyed by attachment; the next history pages load near the end of the window.
 */
@Composable
internal fun BoxScope.Gallery(sheet: GallerySheet, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    BackHandler { onAction(ChatAction.CloseGallery) }
    val more = { if (sheet.hasMore && !sheet.loading) onAction(ChatAction.GalleryMore) }
    Column(Modifier.matchParentSize().background(theme.bg).testTag("gallery")) {
        KitPageHeader(stringResource(R.string.gallery_title), onBack = { onAction(ChatAction.CloseGallery) }, tag = "gallery-header")
        KitSegmented(
            listOf(stringResource(R.string.gallery_media), stringResource(R.string.gallery_files)),
            sheet.tab.ordinal,
            { onAction(ChatAction.GalleryTab(GalleryTab.entries[it])) },
            Modifier.fillMaxWidth().padding(theme.gap.dp),
            tag = "gallery-tabs"
        )
        sheet.problem?.let { KitTipBar(it.render(), "circle-alert", Modifier.padding(theme.gap.dp)) }
        when {
            sheet.items.isEmpty() && sheet.loading ->
                KitMeta(stringResource(R.string.gallery_loading), Modifier.fillMaxWidth().padding(theme.gap.dp), centered = true)
            sheet.items.isEmpty() -> KitEmpty("images", stringResource(R.string.gallery_empty), "")
            sheet.tab == GalleryTab.MEDIA -> {
                val grid = rememberLazyGridState()
                val nearEnd by remember {
                    derivedStateOf { nearEnd(grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index, grid.layoutInfo.totalItemsCount) }
                }
                LaunchedEffect(nearEnd, sheet.items.size) { if (nearEnd) more() }
                LazyVerticalGrid(
                    GridCells.Adaptive((theme.gap * 6).dp),
                    Modifier.weight(1f).fillMaxWidth().testTag("gallery-grid"),
                    state = grid
                ) {
                    items(sheet.items, key = { it.key }, contentType = { it.kind }) { item ->
                        GalleryTile(item, onAction)
                    }
                }
            }
            else -> {
                val list = rememberLazyListState()
                val nearEnd by remember {
                    derivedStateOf { nearEnd(list.layoutInfo.visibleItemsInfo.lastOrNull()?.index, list.layoutInfo.totalItemsCount) }
                }
                LaunchedEffect(nearEnd, sheet.items.size) { if (nearEnd) more() }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("gallery-files"), state = list) {
                    items(sheet.items, key = { it.key }, contentType = { it.kind }) { item ->
                        val status = statusText(item)
                        val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, item.size)
                        KitRow(
                            item.fileName.ifEmpty { stringResource(R.string.chat_attach_file) },
                            { onAction(ChatAction.OpenAttachment(item.key)) },
                            value = if (status == null) size else stringResource(R.string.chat_file_meta, size, status),
                            trailing = KitRowTrailing.None,
                            peer = true,
                            testTag = "gallery-" + item.key
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryTile(item: AttachmentItem, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    BoxWithConstraints(Modifier.aspectRatio(1f).padding((theme.gap * .125f).dp).background(theme.surface)) {
        AlbumTile(item, Modifier.fillMaxSize(), maxWidth, play = false, onAction)
    }
}

private const val GALLERY_PREFETCH = 12

private fun nearEnd(last: Int?, total: Int) = (last ?: 0) >= total - GALLERY_PREFETCH

/**
 * The send preview of picked photos and videos (board B28 to be drawn): staging progress from this
 * device's own work, then the items in album order with move-earlier and remove, a caption and send.
 */
@Composable
internal fun BoxScope.SendPreviewSheet(preview: SendPreview, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    BackHandler { onAction(ChatAction.PreviewCancel) }
    Column(Modifier.matchParentSize().background(theme.bg).testTag("send-preview")) {
        KitPageHeader(
            stringResource(R.string.chat_preview_title),
            actionLabel = stringResource(R.string.chat_preview_cancel),
            onAction = { onAction(ChatAction.PreviewCancel) },
            tag = "preview-header"
        )
        preview.problem?.let { KitTipBar(it.render(), "circle-alert", Modifier.padding(theme.gap.dp)) }
        if (preview.preparing) {
            val percent = ((preview.progress ?: 0f) * 100).toInt().coerceIn(0, 100)
            KitMeta(
                stringResource(R.string.chat_preview_preparing, percent),
                Modifier.fillMaxWidth().padding(theme.gap.dp),
                centered = true
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(preview.items, key = { _, item -> item.path }, contentType = { _, item -> item.kind }) { index, item ->
                PreviewRow(item, index, !preview.preparing, onAction)
            }
        }
        KitTextField(
            preview.caption,
            { onAction(ChatAction.PreviewCaption(it)) },
            "",
            Modifier.padding(horizontal = theme.gap.dp),
            placeholder = stringResource(R.string.chat_preview_caption),
            enabled = !preview.preparing,
            tag = "preview-caption"
        )
        KitButton(
            stringResource(R.string.chat_preview_send),
            { onAction(ChatAction.PreviewSend) },
            Modifier.fillMaxWidth().padding(theme.gap.dp),
            enabled = !preview.preparing && preview.items.isNotEmpty(),
            tag = "preview-send"
        )
    }
}

@Composable
private fun PreviewRow(item: PreviewItem, index: Int, editable: Boolean, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    val thumb = (theme.gap * 4).dp
    val target = with(LocalDensity.current) { thumb.roundToPx() }
    val image = if (item.kind == AttachmentKind.VIDEO) rememberVideoPoster(item.path, target) else rememberMediaImage(item.path, target)
    val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, item.size)
    KitRow(
        size,
        {},
        Modifier.testTag("preview-item-$index"),
        trailing = KitRowTrailing.None,
        enabled = editable,
        leading = {
            Box(Modifier.size(thumb).background(theme.surface), contentAlignment = Alignment.Center) {
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (item.kind == AttachmentKind.VIDEO) KitMediaGlyph("play")
            }
        },
        trailingContent = {
            Row {
                if (index > 0) {
                    KitIconButton("arrow-up", stringResource(R.string.chat_preview_move), { onAction(ChatAction.PreviewMove(index)) })
                }
                KitIconButton("x", stringResource(R.string.chat_preview_remove), { onAction(ChatAction.PreviewRemove(index)) })
            }
        }
    )
}

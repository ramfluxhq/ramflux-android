// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards.renderer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.JsonObject
import org.ramflux.data.cards.CardImageSource
import org.ramflux.data.cards.CardNode
import org.ramflux.data.cards.CardTree
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.UntrustedText
import org.ramflux.designsystem.kit.kitStyle

/** The image bytes of the card being drawn. Hosts without the object API (the gallery) leave the default: every image is unavailable. */
internal val LocalCardImages = staticCompositionLocalOf<CardImageSource> { CardImageSource.None }

/** Where one Image node is: still loading, drawn, or unavailable (any refusal, failure, mismatch or decode limit). */
internal sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Unavailable : ImageLoad
    class Ready(val bitmap: ImageBitmap) : ImageLoad
}

/**
 * The bytes of an Image node (spec 4.15): read through the object API for the `attachment` core filled on the tree's host
 * slot of kind `image` for this node's key, when it says `retrieval: "object"`. The node's own `attachment` is the author's
 * bare selector and is never read. No slot, anything else, or any failure draws [Unavailable]. Nothing is ever read from a
 * URL, path or data URI of the tree.
 */
@Composable
internal fun rememberCardImage(ref: String, node: CardNode, tree: CardTree): State<ImageLoad> {
    val attachment = tree.hostSlotFor(node.key, "image")?.get("attachment") as? JsonObject
    val images = LocalCardImages.current
    return produceState<ImageLoad>(ImageLoad.Loading, ref, node.key, attachment) {
        value = if (attachment == null || attachment.str("retrieval") != "object") {
            ImageLoad.Unavailable
        } else {
            images.load(ref, attachment)?.let { ImageLoad.Ready(it.bitmap.asImageBitmap()) } ?: ImageLoad.Unavailable
        }
    }
}

/** The box content of an Image node: a placeholder while loading, the image with its `fit`, or "Image unavailable". */
@Composable
internal fun ImageBox(load: ImageLoad, fit: String?, description: String, showLabel: Boolean) {
    var full by remember { mutableStateOf(false) }
    when (load) {
        is ImageLoad.Ready -> {
            Image(
                load.bitmap,
                contentDescription = description.ifEmpty { null },
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(role = Role.Button) { full = true }
                    .testTag("card-image"),
                contentScale = scaleOf(fit)
            )
            if (full) ImageFullView(load.bitmap, description) { full = false }
        }
        ImageLoad.Loading -> Unit
        ImageLoad.Unavailable -> if (showLabel) KitMeta(stringResource(R.string.card_image_unavailable), centered = true)
    }
}

/** `fit` is object-fit (spec 4.15, default fill). */
private fun scaleOf(fit: String?): ContentScale = when (fit) {
    "contain" -> ContentScale.Fit
    "cover" -> ContentScale.Crop
    "none" -> ContentScale.None
    "scale-down" -> ContentScale.Inside
    else -> ContentScale.FillBounds
}

/** The full-screen view (spec 4.15): the image fitted with `contain` on the background, the description below. */
@Composable
private fun ImageFullView(bitmap: ImageBitmap, description: String, onClose: () -> Unit) {
    val theme = LocalRFTheme.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(theme.bg).padding(16.dp).testTag("card-image-full"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap,
                    contentDescription = description.ifEmpty { null },
                    modifier = Modifier.fillMaxSize().clickable(role = Role.Button) { onClose() },
                    contentScale = ContentScale.Fit
                )
            }
            if (description.isNotEmpty()) {
                UntrustedText(
                    description,
                    kind = UntrustedKind.Excerpt,
                    style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, 1.448f)
                )
            }
            KitButton(
                stringResource(R.string.card_close),
                onClose,
                Modifier.fillMaxWidth(),
                kind = KitButtonKind.Secondary,
                tag = "card-image-close"
            )
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.serialization.json.JsonObject
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardLifecycle
import org.ramflux.data.cards.CardSlotOutcome
import org.ramflux.data.cards.CardStamp
import org.ramflux.data.cards.CardTree
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.kit.KitAiMarker
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitButtonSize
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.UntrustedText
import org.ramflux.designsystem.kit.kitStyle
import org.ramflux.designsystem.kit.shapeOf

/**
 * What the renderer draws for one card. The host builds it from the card runtime state (CardSessionRepository); the
 * renderer holds no session, no token and no network path.
 */
class CardRenderInput(
    val ref: String,
    val tree: CardTree?,
    val lifecycle: CardLifecycle,
    val fallback: String,
    val renderAllowed: Boolean,
    /** A host request waiting for its sheet: `open_url` (a link to confirm). Null otherwise. */
    val hostRequest: JsonObject?,
    /** An approval was confirmed; the verified outcome arrives with the next signed update. */
    val awaitingDecision: Boolean,
    /** The runtime discarded a draft edit that conflicted with a signed update (spec 4.20). */
    val draftConflict: Boolean,
    /** The key of the control whose last event came back `stale_dropped`; it reverts to the shown value. */
    val staleKey: String? = null,
    /** Changes every time a stale event arrives, so the same stale answer reverts the field again. */
    val staleSeq: Long = 0
) {
    override fun toString() = "CardRenderInput(<redacted>)"
}

/**
 * What the person did on a card. Calls return at once; the host sends them to the card runtime and reports results back
 * through the card state. The renderer never builds a token: the host passes the one from the tree or the host request.
 */
interface CardActions {
    /** A host event for the control at [key] (`input`, `activate`, `select_tab`, `modal_open`, `modal_close`). */
    fun event(key: String, event: CardEvent, seen: CardStamp)

    /** The answer to a link or approval slot. [url] is the link the sheet showed (null for approvals). */
    fun slot(slotId: String, requestId: String, token: JsonObject, outcome: CardSlotOutcome, url: String?)

    /** First tap on a suspended card: resume it. */
    fun show()

    /** Out of sync: re-read the card from the latest surface. */
    fun resync()
}

/** Everything a node needs while the card is drawn. */
internal class CardContext(
    val input: CardRenderInput,
    val actions: CardActions,
    val tree: CardTree,
    /** Controls take input only while the card is open (READY). Navigation (tabs, modal) works while resumed. */
    val live: Boolean,
    val navigable: Boolean,
    /** The key of the Button last pressed; it shows "Submitting..." while the submit is in flight. */
    val pressed: MutableState<String?>
) {
    val format: java.util.Locale get() = formatLocale(tree.formatLocale)
    val submitting: Boolean get() = input.lifecycle == CardLifecycle.SUBMITTING

    fun event(key: String, event: CardEvent) = actions.event(key, event, tree.stamp)

    fun inert(): CardContext = CardContext(input, actions, tree, live = false, navigable = false, pressed = pressed)
}

/**
 * Draws one agent card (spec 3.2 to 3.4 and 4.1 to 4.20). Used by the timeline and by the realrun gallery. The card is
 * drawn from [input.tree] only; with no tree or `renderAllowed == false` the author's fallback text is the content.
 */
@Composable
fun CardRenderer(input: CardRenderInput, actions: CardActions, cap: Dp, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val shape = RoundedCornerShape(theme.radius_card.dp)
    val tree = input.tree?.takeIf { input.renderAllowed && input.lifecycle != CardLifecycle.UNAVAILABLE }
    val pressed = remember(input.ref) { mutableStateOf<String?>(null) }
    val suspended = input.lifecycle == CardLifecycle.SUSPENDED
    val live = tree != null && input.lifecycle == CardLifecycle.READY
    val navigable = tree != null && !suspended
    val ctx = tree?.let { CardContext(input, actions, it, live, navigable, pressed) }
    val direction = if (tree?.direction == "rtl") LayoutDirection.Rtl else LayoutDirection.Ltr
    val pad = if (cap < 400.dp) 12.dp else 16.dp
    val label = stringResource(R.string.card_resume_label)
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Column(
            modifier
                .fillMaxWidth()
                .widthIn(max = cap)
                .alpha(if (input.lifecycle == CardLifecycle.SUBMITTING) 0.6f else 1f)
                .border(1.dp, theme.line, shape)
                .clip(shape)
                .background(theme.surface_white)
                .then(
                    if (suspended) Modifier.clickable(role = Role.Button, onClickLabel = label) { actions.show() } else Modifier
                )
                .padding(pad)
                .testTag("card-" + input.ref),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CardHeader(input)
            if (ctx == null) {
                val text = input.fallback.ifEmpty { stringResource(R.string.card_fallback_empty) }
                UntrustedText(
                    text,
                    kind = UntrustedKind.Excerpt,
                    style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.secondary, 1.448f)
                )
            } else {
                CardLifecycleLine(input.lifecycle)
                if (input.draftConflict) KitTipBar(stringResource(R.string.card_draft_replaced), icon = "info")
                CardBody(ctx)
                CardFooter(ctx)
            }
        }
        if (input.hostRequest?.str("kind") == "open_url") LinkSheet(input, actions)
    }
}

/** Header row: the agent marker (spec 3.2) and the lifecycle chip. The author name is not in the surface feed yet. */
@Composable
private fun CardHeader(input: CardRenderInput) {
    val chip = when (input.lifecycle) {
        CardLifecycle.GENERATING -> R.string.card_status_generating
        CardLifecycle.CONSUMED, CardLifecycle.SUBMITTING -> R.string.card_status_waiting
        CardLifecycle.CLOSED -> R.string.card_status_closed
        CardLifecycle.SUPERSEDED -> R.string.card_status_updated
        CardLifecycle.OUT_OF_SYNC -> R.string.card_status_out_of_sync
        CardLifecycle.OVER_LIMIT -> R.string.card_status_over_limit
        else -> null
    }
    val theme = LocalRFTheme.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        KitAiMarker()
        Spacer(Modifier.width(8.dp))
        Spacer(Modifier.weight(1f))
        if (chip != null) {
            Box(Modifier.background(theme.surface, shapeOf(theme.radius_chip)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                KitMeta(stringResource(chip))
            }
        }
    }
}

/** Lifecycle line under the header (spec 3.4): what the person must know before reading the body. */
@Composable
private fun CardLifecycleLine(lifecycle: CardLifecycle) {
    val text = when (lifecycle) {
        CardLifecycle.OVER_LIMIT -> R.string.card_over_limit_body
        CardLifecycle.SUPERSEDED -> R.string.card_superseded_body
        CardLifecycle.OUT_OF_SYNC -> R.string.card_out_of_sync_body
        else -> return
    }
    KitMeta(stringResource(text))
}

/** The root node: a root Card draws its children directly (the container is the card); any other root is one node. */
@Composable
private fun CardBody(ctx: CardContext) {
    val root = ctx.tree.root() ?: return
    if (root.kind == "Card") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { root.children.forEach { RenderNode(it, ctx) } }
    } else {
        RenderNode(root.key, ctx)
    }
}

/** Actions and recovery that sit under the body (spec 5.2 error row, 3.4 Resync). */
@Composable
private fun CardFooter(ctx: CardContext) {
    if (ctx.input.lifecycle != CardLifecycle.OUT_OF_SYNC) return
    KitButton(
        label = stringResource(R.string.card_resync),
        onClick = { ctx.actions.resync() },
        kind = KitButtonKind.Secondary,
        size = KitButtonSize.Regular
    )
}

/**
 * The 20 kinds [RenderNode] draws with their own view. Any other kind draws the neutral line of [RenderNode]; the realrun
 * card gallery counts such nodes as placeholders (conformance check B). Keep this list in step with the `when` below.
 */
val CARD_RENDERED_KINDS: Set<String> = setOf(
    "Text", "Icon", "Row", "Column", "List", "Card", "Tabs", "Modal", "Divider", "Button", "TextField", "CheckBox",
    "ChoicePicker", "Slider", "DateTimeInput", "Image", "Approval", "Task", "Chart", "Table"
)

/** Dispatches one node of the tree by kind. Unknown kinds draw a neutral line, never nothing. */
@Composable
internal fun RenderNode(key: String, ctx: CardContext) {
    val node = ctx.tree.nodes[key] ?: return
    androidx.compose.runtime.key(key) {
        when (node.kind) {
            "Text" -> CardText(node, ctx)
            "Icon" -> CardIcon(node, ctx)
            "Row" -> CardRow(node, ctx)
            "Column" -> CardColumn(node, ctx)
            "List" -> CardList(node, ctx)
            "Card" -> CardInset(node, ctx)
            "Tabs" -> CardTabs(node, ctx)
            "Modal" -> CardModal(node, ctx)
            "Divider" -> CardDivider(node)
            "Button" -> CardButton(node, ctx)
            "TextField" -> CardTextField(node, ctx)
            "CheckBox" -> CardCheckBox(node, ctx)
            "ChoicePicker" -> CardChoicePicker(node, ctx)
            "Slider" -> CardSlider(node, ctx)
            "DateTimeInput" -> CardDateTime(node, ctx)
            "Image" -> CardImage(node, ctx)
            "Approval" -> CardApproval(node, ctx)
            "Task" -> CardTask(node, ctx)
            "Chart" -> CardChart(node, ctx)
            "Table" -> CardTable(node, ctx)
            else -> KitMeta(stringResource(R.string.card_component_unsupported))
        }
    }
}

/** The link sheet (spec 5.4 and README "Link activation"): the full URL, the author and the privacy note; Open after confirm. */
@Composable
private fun LinkSheet(input: CardRenderInput, actions: CardActions) {
    val request = input.hostRequest ?: return
    val url = request.str("url") ?: return
    val slotId = request.str("slot_id") ?: return
    val requestId = request.str("request_id") ?: return
    val token = request.obj("token") ?: return
    if (LocalAppLockCovered.current) return
    val theme = LocalRFTheme.current
    val host = remember(url) { runCatching { java.net.URI(url).host }.getOrNull().orEmpty() }
    val shape = RoundedCornerShape(theme.radius_dialog.dp)
    Dialog(onDismissRequest = { actions.slot(slotId, requestId, token, CardSlotOutcome.CANCEL, url) }) {
        Column(
            Modifier
                .background(theme.overlay, shape)
                .border(1.dp, theme.overlay_border, shape)
                .padding(24.dp)
                .testTag("card-link-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            KitMeta(stringResource(R.string.card_link_title))
            // The body scrolls so Open stays reachable for the longest URL; the buttons sit below it.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (host.isNotEmpty()) {
                    UntrustedText(
                        host,
                        kind = UntrustedKind.Name,
                        style = kitStyle(theme.size_subheading, FontWeight.SemiBold, theme.text, 1.448f)
                    )
                }
                UntrustedText(
                    url,
                    kind = UntrustedKind.FileName,
                    style = kitStyle(theme.size_secondary, FontWeight.Normal, theme.text, 1.448f, FontFamily.Monospace)
                )
                KitMeta(stringResource(R.string.card_link_author_note))
                KitMeta(stringResource(R.string.card_link_privacy))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                KitButton(
                    label = stringResource(R.string.card_cancel),
                    onClick = { actions.slot(slotId, requestId, token, CardSlotOutcome.CANCEL, url) },
                    kind = KitButtonKind.Secondary
                )
                Spacer(Modifier.width(8.dp))
                KitButton(
                    label = stringResource(R.string.card_link_open),
                    onClick = { actions.slot(slotId, requestId, token, CardSlotOutcome.CONFIRM, url) },
                    kind = KitButtonKind.Primary
                )
            }
        }
    }
}

/** Reads the host slot the tree carries for [key] (approval, modal, image). Only the SDK writes these. */
internal fun CardTree.hostSlotFor(key: String, kind: String): JsonObject? =
    hostSlots.firstOrNull { it.str("key") == key && it.str("kind") == kind }

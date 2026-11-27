// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards.renderer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.ramflux.data.cards.CardNode
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitIcon
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitText
import org.ramflux.designsystem.kit.kitStyle
import org.ramflux.designsystem.kit.shapeOf

/** Text (spec 4.1): `body` or `caption`, simple Markdown, nothing for an empty string. */
@Composable
internal fun CardText(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    val text = node.attrs.str("text").orEmpty()
    if (text.isEmpty()) return
    val caption = node.attrs.str("variant") == "caption"
    val color = if (caption) theme.secondary else theme.text
    val style = kitStyle(if (caption) theme.size_secondary else theme.size_body, FontWeight.Normal, color, 1.448f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        markdownBlocks(text).forEach { (bullet, line) ->
            Row(verticalAlignment = Alignment.Top) {
                if (bullet) {
                    Box(Modifier.padding(top = 8.dp).size(4.dp).background(color, CircleShape))
                    Spacer(Modifier.width(12.dp))
                }
                Text(line, style = style, color = color)
            }
        }
    }
}

/** Icon (spec 4.2): a named icon from the design kit, or bounded path data drawn in a 24 viewBox. Never an SVG document. */
@Composable
internal fun CardIcon(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    val name = node.attrs.str("name")
    val path = node.attrs.str("path") ?: node.attrs.str("svgPath")
    val label = node.attrs.str("label")
    when {
        name != null -> KitIcon(name, ICON_SIZE, theme.text, description = label)
        path != null -> {
            // Untrusted path data: a malformed path draws the empty box, never a crash.
            val parsed = remember(path) { runCatching { PathParser().parsePathString(path).toNodes().toPath() }.getOrNull() }
            if (parsed == null) {
                Box(Modifier.size(ICON_SIZE.dp))
            } else {
                Canvas(Modifier.size(ICON_SIZE.dp)) {
                    val factor = size.width / PATH_VIEWBOX
                    scale(factor, factor, pivot = Offset.Zero) { drawPath(parsed, theme.text) }
                }
            }
        }
        else -> Box(Modifier.size(ICON_SIZE.dp))
    }
}

/** Image (spec 4.15): v0.2.9 has no byte delivery, so every image draws its box and the unavailable state; no URL is ever loaded. */
@Composable
internal fun CardImage(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    val variant = node.attrs.str("variant") ?: "mediumFeature"
    val description = node.attrs.str("description").orEmpty()
    val shape = shapeOf(theme.radius_md)
    val box = when (variant) {
        "icon" -> Modifier.size(24.dp)
        "avatar" -> Modifier.size(40.dp).background(theme.surface, CircleShape)
        "smallFeature" -> Modifier.size(96.dp)
        "largeFeature" -> Modifier.fillMaxWidth().aspectRatio(4f / 3f).heightIn(max = 420.dp)
        "header" -> Modifier.fillMaxWidth().aspectRatio(2f).heightIn(max = 240.dp)
        else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(max = 280.dp)
    }
    val semantic = if (description.isEmpty()) Modifier else Modifier.semantics { contentDescription = description }
    Box(
        box
            .background(theme.surface, if (variant == "avatar") CircleShape else shape)
            .then(semantic),
        contentAlignment = Alignment.Center
    ) {
        if (variant != "icon" && variant != "avatar") KitMeta(stringResource(R.string.card_image_unavailable), centered = true)
    }
}

/** Chart (spec 4.18): line or bar, one or two series, a legend, and the table alternative toggled in place. */
@Composable
internal fun CardChart(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    val kind = node.attrs.str("kind") ?: "line"
    val scale = node.attrs.num("scale") ?: 1.0
    val unit = node.attrs.str("unit")?.let { trusted(it) }
    val series = node.attrs.arr("series")?.mapNotNull { it as? JsonObject }.orEmpty()
    val data = series.map { s -> s.arr("points")?.mapNotNull { it as? JsonObject }.orEmpty() }
    var table by rememberSaveable(node.key) { mutableStateOf(false) }
    val locale = ctx.format
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                KitText(
                    trusted(node.attrs.str("title").orEmpty()),
                    style = kitStyle(theme.size_subheading, FontWeight.SemiBold, theme.text, 1.448f)
                )
                unit?.let { KitMeta(it) }
            }
            KitButton(
                label = stringResource(if (table) R.string.card_chart_show_chart else R.string.card_chart_show_table),
                onClick = { table = !table },
                kind = KitButtonKind.Text
            )
        }
        val hasData = data.any { it.isNotEmpty() }
        if (!hasData) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                KitMeta(stringResource(R.string.card_no_data), centered = true)
            }
        } else if (table) {
            ChartTable(node, series, data, scale, unit, locale)
        } else {
            ChartLegend(series, data, scale, unit, locale)
            ChartPlot(kind, data, theme.chart_1, theme.chart_2, theme.line, theme.line_strong)
            XLabels(data.firstOrNull().orEmpty())
        }
    }
}

@Composable
private fun ChartLegend(series: List<JsonObject>, data: List<List<JsonObject>>, scale: Double, unit: String?, locale: Locale) {
    val theme = LocalRFTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        series.forEachIndexed { index, s ->
            val color = if (index == 0) theme.chart_1 else theme.chart_2
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(7.dp))
                KitMeta(trusted(s.str("label").orEmpty()))
                val last = data.getOrNull(index)?.lastOrNull()?.num("value")
                if (last != null) {
                    Spacer(Modifier.width(6.dp))
                    KitMeta(formatNumber(last, scale, locale, unit))
                }
            }
        }
    }
}

@Composable
private fun ChartPlot(kind: String, data: List<List<JsonObject>>, first: Color, second: Color, grid: Color, base: Color) {
    val values = data.flatten().mapNotNull { it.num("value") }
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
    val min = (values.minOrNull() ?: 0.0).coerceAtMost(0.0)
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val lines = 4
        for (i in 0..lines) {
            val y = size.height * i / lines
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val zeroY = size.height * (1f - ((0.0 - min) / span).toFloat())
        drawLine(base, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1f)
        data.forEachIndexed { index, points ->
            val color = if (index == 0) first else second
            val count = points.size
            if (count == 0) return@forEachIndexed
            val pitch = size.width / count
            if (kind == "bar") {
                val groups = data.size
                val barWidth = 11.dp.toPx()
                points.forEachIndexed { i, p ->
                    val v = p.num("value") ?: 0.0
                    val y = size.height * (1f - ((v - min) / span).toFloat())
                    val x = i * pitch + pitch / 2 + (index - (groups - 1) / 2f) * (barWidth + 2.dp.toPx())
                    val top = minOf(y, zeroY)
                    drawRect(color, Offset(x - barWidth / 2, top), androidx.compose.ui.geometry.Size(barWidth, kotlin.math.abs(zeroY - y)))
                }
            } else {
                val path = androidx.compose.ui.graphics.Path()
                points.forEachIndexed { i, p ->
                    val v = p.num("value") ?: 0.0
                    val x = i * pitch + pitch / 2
                    val y = size.height * (1f - ((v - min) / span).toFloat())
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val effect = if (index == 1) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
                drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = effect))
            }
        }
    }
}

@Composable
private fun XLabels(points: List<JsonObject>) {
    if (points.isEmpty()) return
    val picks = if (points.size <= 4) points.indices.toList() else listOf(0, points.size / 3, 2 * points.size / 3, points.lastIndex)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        picks.forEach { KitMeta(trusted(points[it].str("label").orEmpty())) }
    }
}

@Composable
private fun ChartTable(
    node: CardNode,
    series: List<JsonObject>,
    data: List<List<JsonObject>>,
    scale: Double,
    unit: String?,
    locale: Locale
) {
    val theme = LocalRFTheme.current
    val labels = data.firstOrNull().orEmpty().map { trusted(it.str("label").orEmpty()) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            KitMeta(stringResource(R.string.card_chart_column_label), Modifier.weight(1f))
            series.forEach { s -> KitMeta(trusted(s.str("label").orEmpty()), Modifier.weight(1f), centered = false) }
        }
        labels.forEachIndexed { row, label ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                KitText(label, Modifier.weight(1f), style = kitStyle(theme.size_meta, FontWeight.Normal, theme.text, 1.448f))
                data.forEach { points ->
                    val v = points.getOrNull(row)?.num("value")
                    KitText(
                        v?.let { formatNumber(it, scale, locale, unit) } ?: "",
                        Modifier.weight(1f),
                        style = kitStyle(theme.size_meta, FontWeight.Normal, theme.text, 1.448f)
                    )
                }
            }
        }
    }
}

/** Table (spec 4.19): header, rows and rules; integers end-aligned, booleans as Yes/No, strings wrap. */
@Composable
internal fun CardTable(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    val columns = node.attrs.arr("columns")?.mapNotNull { (it as? JsonObject)?.str("label")?.let(::trusted) }.orEmpty()
    val rows = node.attrs.arr("rows")?.mapNotNull { it as? JsonObject }.orEmpty()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            columns.forEach { KitMeta(it, Modifier.weight(1f)) }
        }
        HorizontalRule(theme.line)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                KitMeta(stringResource(R.string.card_no_rows), centered = true)
            }
        }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                row.arr("cells")?.forEach { cell ->
                    val primitive = cell as? JsonPrimitive
                    val integer = primitive?.let { !it.isString && it.content.toLongOrNull() != null } == true
                    KitText(
                        cellText(primitive, ctx.format),
                        Modifier.weight(1f),
                        style = kitStyle(theme.size_meta, FontWeight.Normal, theme.text, 1.448f),
                        textAlign = if (integer) TextAlign.End else null
                    )
                }
            }
            HorizontalRule(theme.line_soft)
        }
    }
}

private fun cellText(cell: JsonPrimitive?, locale: Locale): String {
    if (cell == null) return ""
    if (cell.isString) return trusted(cell.content)
    cell.content.toLongOrNull()?.let { return java.text.NumberFormat.getIntegerInstance(locale).format(it) }
    return when (cell.content) {
        "true" -> "Yes"
        "false" -> "No"
        else -> cell.content
    }
}

@Composable
private fun HorizontalRule(color: Color) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Task (spec 4.17): reported status (untrusted), progress, steps and the outcome line. No actions: the bus has no task event yet. */
@Composable
internal fun CardTask(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    val status = node.attrs.str("status") ?: "pending"
    val owner = trusted(node.attrs.str("owner").orEmpty())
    val title = trusted(node.attrs.str("title").orEmpty())
    val requester = node.attrs.str("requester").orEmpty()
    val progress = node.attrs.long("progress")?.coerceIn(0L, 100L)?.toInt()
    val steps = node.attrs.arr("steps")?.mapNotNull { it as? JsonObject }.orEmpty()
    var showSteps by rememberSaveable(node.key) { mutableStateOf(false) }
    val tone = when (status) {
        "completed" -> theme.success
        "refused" -> theme.danger
        else -> null
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.background(theme.surface, shapeOf(theme.radius_chip)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            KitMeta(stringResource(taskStatusLabel(status)), tone = tone)
        }
        KitText(
            listOf(owner, title).filter { it.isNotEmpty() }.joinToString(" . ").let(::trusted),
            style = kitStyle(theme.size_subheading, FontWeight.SemiBold, theme.text, 1.448f)
        )
        if (requester.isNotEmpty()) KitMeta(stringResource(R.string.card_task_requested, trusted(requester)))
        if (progress != null) {
            LinearProgressIndicator(progress = {
                progress / 100f
            }, modifier = Modifier.fillMaxWidth().height(4.dp), color = theme.primary, trackColor = theme.surface)
            KitMeta("$progress %")
        } else if (status == "running") {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = theme.primary, trackColor = theme.surface)
        }
        if (steps.isEmpty() && status == "pending" && progress == null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 0.7f, 0.4f).forEach { fraction ->
                    Box(Modifier.fillMaxWidth(fraction).height(12.dp).background(theme.surface, shapeOf(theme.radius_sm)))
                }
            }
        }
        val visible = if (steps.size <= INLINE_STEPS || showSteps) {
            steps
        } else {
            steps.filter {
                it.str("status") == "running" || it.str("status") == "pending"
            }.take(2)
        }
        if (steps.size > INLINE_STEPS) {
            KitButton(
                label = stringResource(R.string.card_task_steps, steps.count { it.str("status") == "completed" }, steps.size),
                onClick = { showSteps = !showSteps },
                kind = KitButtonKind.Text
            )
        }
        visible.forEach { step ->
            val current = step.str("status") == "running"
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                KitIcon(
                    stepGlyph(step.str("status")),
                    14f,
                    stepTint(step.str("status"), theme.text, theme.success, theme.danger, theme.control, theme.secondary)
                )
                Spacer(Modifier.width(8.dp))
                KitText(
                    trusted(step.str("text").orEmpty()),
                    style = kitStyle(
                        theme.size_secondary,
                        if (current) FontWeight.SemiBold else FontWeight.Normal,
                        if (current) theme.text else theme.secondary,
                        1.448f
                    )
                )
            }
        }
        if (status == "refused") KitMeta(stringResource(R.string.card_task_refused_body))
    }
}

private const val ICON_SIZE = 20f
private const val PATH_VIEWBOX = 24f
private const val INLINE_STEPS = 3

private fun taskStatusLabel(status: String): Int = when (status) {
    "delivered" -> R.string.card_task_delivered
    "running" -> R.string.card_task_running
    "completed" -> R.string.card_task_completed
    "refused" -> R.string.card_task_refused
    "cancelled" -> R.string.card_task_cancelled
    else -> R.string.card_task_pending
}

private fun stepGlyph(status: String?): String = when (status) {
    "completed" -> "circle-check"
    "running" -> "loader-circle"
    "refused" -> "circle-x"
    else -> "circle"
}

private fun stepTint(status: String?, text: Color, success: Color, danger: Color, control: Color, secondary: Color): Color = when (status) {
    "completed" -> success
    "running" -> text
    "refused" -> danger
    "delivered" -> secondary
    else -> control
}

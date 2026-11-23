// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ramflux.android.R
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitInfoButton
import org.ramflux.designsystem.kit.KitPageHeader

/**
 * Header, content and footer of a startup screen. The content scrolls, so security text wraps and stays
 * readable in short windows and at large font scales instead of being squeezed; [scroll] is false only
 * for content that centres itself in the remaining height (the splash). [contentTop] is the space above the
 * content, measured from the boards.
 */
@Composable
internal fun StartupFrame(
    tag: String,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    scroll: Boolean = true,
    contentTop: androidx.compose.ui.unit.Dp = LocalRFTheme.current.gap.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalRFTheme.current
    Column(modifier.fillMaxSize().background(theme.bg).testTag(tag)) {
        header()
        val scrolling = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            Modifier.weight(1f).fillMaxWidth().then(scrolling)
                .padding(start = theme.gap.dp, end = theme.gap.dp, top = contentTop, bottom = theme.gap.dp),
            content = content
        )
        Column(
            Modifier.fillMaxWidth().padding(
                start = theme.gap.dp,
                end = theme.gap.dp,
                top = theme.gap.dp,
                bottom = dimensionResource(R.dimen.startup_footer_bottom)
            )
        ) { footer() }
    }
}

/**
 * The startup pages on the grouped page tone (MOB-A01, A02, A07): the theme's page colour becomes the grouped
 * background, so the Kit header and the frame take it while cards stay white.
 */
@Composable
internal fun GroupedTone(content: @Composable () -> Unit) {
    val theme = LocalRFTheme.current
    CompositionLocalProvider(LocalRFTheme provides theme.copy(bg = theme.nav), content = content)
}

/** The Kit page header without its bottom rule, which the startup boards do not draw. */
@Composable
internal fun StartupHeader(title: String, onBack: (() -> Unit)? = null, tag: String? = null) {
    KitPageHeader(title, onBack = onBack, divider = false, tag = tag)
}

/** A white card of grouped rows (radius of the Kit card). */
@Composable
internal fun GroupedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalRFTheme.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(theme.radius_card.dp)).background(theme.surface_white),
        content = content
    )
}

/** The Kit's hairline between grouped rows. */
@Composable
internal fun GroupedDivider() {
    val theme = LocalRFTheme.current
    Spacer(Modifier.fillMaxWidth().height(dimensionResource(R.dimen.startup_hairline)).background(theme.line))
}

/** A section title above a grouped card, with the Kit info button when it explains something. */
@Composable
internal fun StartupSection(text: String, onInfo: (() -> Unit)? = null, infoLabel: String? = null, tag: String? = null) {
    val theme = LocalRFTheme.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.startup_section_height)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = theme.size_body.sp,
                lineHeight = (theme.size_body * LINE_BODY).sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.text
            )
        )
        if (onInfo != null) KitInfoButton(onInfo, label = infoLabel ?: stringResource(R.string.startup_info), tag = tag)
    }
}

/** The page title of the grouped startup pages (24 sp, bold). */
@Composable
internal fun StartupTitle(text: String, tag: String? = null) {
    val theme = LocalRFTheme.current
    Text(
        text,
        if (tag == null) Modifier else Modifier.testTag(tag),
        style = MaterialTheme.typography.headlineSmall.copy(
            fontSize = theme.size_page.sp,
            lineHeight = (theme.size_page * LINE_TITLE).sp,
            fontWeight = FontWeight.Bold,
            color = theme.text
        )
    )
}

/** Body line height of the Kit (RfMetrics.lineBody) and title line height (RfMetrics.lineTitle). */
internal const val LINE_BODY = 1.45f
internal const val LINE_TITLE = 1.3f
internal const val LINE_SECONDARY = 1.4f

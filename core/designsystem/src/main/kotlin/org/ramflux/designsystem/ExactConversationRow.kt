// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ramflux.core.designsystem.R

/** RFConversationRow geometry with a host-owned exact count/manual-unread trailing presentation.
 * The generated row accepts UInt, so it cannot represent the SDK's complete unsigned64 domain.
 */
@Composable
fun ExactConversationRow(
    id: String,
    title: String,
    summary: String,
    timestamp: String,
    unread: String,
    isUnread: Boolean,
    onSelect: (String) -> Unit
) {
    val theme = LocalRFTheme.current
    val layout = LocalRFLayout.current
    val count = localizedUnsigned(unread)
    val markedUnread = stringResource(R.string.marked_unread)
    val unreadDescription = UiText.Quantity(R.plurals.unread_messages, unread.toULong()).render()
    Row(
        Modifier.fillMaxWidth().heightIn(min = layout.rowHeight.dp).clickable(role = Role.Button) { onSelect(id) }
            .semantics(mergeDescendants = true) { testTag = "conversation:$id" }.padding(start = theme.gap.dp),
        horizontalArrangement = Arrangement.spacedBy((theme.gap * .75f).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(
                layout.avatarSize.dp
            ).background(theme.avatar_person, RoundedCornerShape(theme.radius_full.dp)).clearAndSetSemantics {},
            contentAlignment = Alignment.Center
        ) { Text(title.take(1), fontSize = layout.avatarTextSize.sp, color = theme.text) }
        Column(
            Modifier.weight(1f).heightIn(min = layout.rowHeight.dp).padding(end = theme.gap.dp)
                .drawBehind { drawLine(theme.line, Offset(0f, size.height), Offset(size.width, size.height), layout.hairline.dp.toPx()) },
            verticalArrangement = Arrangement.spacedBy((theme.gap * .125f).dp, Alignment.CenterVertically)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp)) {
                Text(
                    title,
                    Modifier.weight(1f),
                    color = theme.text,
                    fontSize = theme.size_mobile_body.sp,
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(timestamp, color = theme.secondary, fontSize = theme.size_mobile_caption.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp)) {
                Text(
                    summary,
                    Modifier.weight(1f),
                    color = theme.secondary,
                    fontSize = theme.size_body.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isUnread) {
                    if (unread == "0") {
                        Box(
                            Modifier.size((theme.gap * .5f).dp).background(theme.badge, RoundedCornerShape(theme.radius_full.dp))
                                .semantics { contentDescription = markedUnread }
                        )
                    } else {
                        Text(
                            count,
                            Modifier.background(theme.badge, RoundedCornerShape(theme.radius_chip.dp))
                                .padding(horizontal = (theme.gap * .5f).dp).semantics { contentDescription = unreadDescription },
                            color = theme.on_badge,
                            fontSize = theme.size_meta.sp
                        )
                    }
                }
            }
        }
    }
}

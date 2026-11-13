// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button) { onSelect(id) }
            .semantics(mergeDescendants = true) { testTag = "conversation:$id" }.padding(start = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).background(theme.avatar_person, CircleShape).clearAndSetSemantics {},
            contentAlignment = Alignment.Center
        ) { Text(title.take(1), fontSize = 22.sp, color = theme.text) }
        Column(
            Modifier.weight(1f).heightIn(min = 72.dp).padding(end = 16.dp)
                .drawBehind { drawLine(theme.line, Offset(0f, size.height), Offset(size.width, size.height), .5.dp.toPx()) },
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            Modifier.size(8.dp).background(theme.badge, CircleShape)
                                .semantics { contentDescription = "Marked unread" }
                        )
                    } else {
                        Text(
                            unread,
                            Modifier.background(theme.badge, RoundedCornerShape(theme.radius_chip.dp))
                                .padding(horizontal = 8.dp).semantics { contentDescription = "$unread unread" },
                            color = theme.on_badge,
                            fontSize = theme.size_meta.sp
                        )
                    }
                }
            }
        }
    }
}

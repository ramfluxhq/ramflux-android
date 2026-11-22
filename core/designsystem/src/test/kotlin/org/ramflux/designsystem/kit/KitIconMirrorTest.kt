// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mirroring table for the kit icons. Icons whose meaning follows reading order (navigation chevrons,
 * reply, forward, lists with leading bullets) mirror in right-to-left layouts through the drawable's
 * autoMirrored flag. Media controls, ticks, clocks, vertical arrows, locks, shields, logos and the QR glyph
 * never mirror. Screens draw icons only through the kit, so no screen can flip an icon by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KitIconMirrorTest {
    private val mirrored = setOf("chevron-left", "chevron-right", "reply", "forward", "list-checks", "clipboard-list")

    private val neverMirrored = setOf(
        "play", "pause", "check", "check-check", "clock-3", "timer", "history", "arrow-up", "arrow-down",
        "chevron-down", "lock", "lock-keyhole", "shield", "shield-check", "shield-alert", "qr-code",
        "loader-circle", "refresh-cw", "phone", "search"
    )

    @Test fun everyKitIconFollowsTheMirroringTable() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val actual = KitIcons.ids.filter { (_, id) -> ImageVector.vectorResource(null, resources, id).autoMirror }.keys
        assertEquals(mirrored, actual)
        check(neverMirrored.none { it in actual }) { "non-directional icon mirrored: ${neverMirrored.intersect(actual)}" }
        check(KitIcons.ids.keys.containsAll(mirrored + neverMirrored)) { "table names a missing icon" }
    }
}

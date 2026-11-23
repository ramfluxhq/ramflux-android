// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the kit drawables to the mirroring table in `kit-icons.json`, the table scripts/sync-lucide-drawables.py
 * writes the flags from. Icons whose meaning follows reading order (navigation chevrons, reply, forward, lists
 * with leading bullets) mirror in right-to-left layouts through the drawable's autoMirrored flag. Media
 * controls, ticks, clocks, vertical arrows, locks, shields, logos and the QR glyph never mirror. Screens draw
 * icons only through the kit, so no screen can flip an icon by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KitIconMirrorTest {
    private val table = JSONObject(File("kit-icons.json").readText())

    private fun names(key: String): Set<String> {
        val array = table.getJSONArray(key)
        return (0 until array.length()).map(array::getString).toSet()
    }

    private val mirrored = names("mirror")

    private val neverMirrored = names("neverMirror")

    @Test fun everyKitIconFollowsTheMirroringTable() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val actual = KitIcons.ids.filter { (_, id) -> ImageVector.vectorResource(null, resources, id).autoMirror }.keys
        assertEquals(mirrored, actual)
        check(neverMirrored.none { it in actual }) { "non-directional icon mirrored: ${neverMirrored.intersect(actual)}" }
        check(KitIcons.ids.keys.containsAll(mirrored + neverMirrored)) { "table names a missing icon" }
        assertEquals("KitIcons.kt and the table list the same icons", names("icons"), KitIcons.ids.keys)
        check(mirrored.isNotEmpty() && neverMirrored.isNotEmpty()) { "empty mirroring table" }
    }
}

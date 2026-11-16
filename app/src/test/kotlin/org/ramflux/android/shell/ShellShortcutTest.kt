// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellShortcutTest {
    @Test fun coreRowsStayAndRemovableRowsHide() {
        assertEquals("", hideShortcut("", "messages"))
        assertEquals("contacts", hideShortcut("", "contacts"))
        assertEquals("contacts-tasks", hideShortcut("tasks", "contacts"))
        assertEquals("tasks", revealShortcut("contacts-tasks"))
        assertEquals("", revealShortcut(""))
    }

    @Test fun visibleMoveSkipsHiddenSlots() {
        val hidden = hideShortcut("", "contacts")
        val moved = moveVisibleShortcut(DEFAULT_SHELL_ORDER, hidden, 3, -1)
        assertEquals("messages-groups-tasks-contacts-agents", moved)
        assertEquals(listOf("tasks"), moreShortcutIds(moved, hidden))
    }

    @Test fun endsDoNotMovePastTheList() {
        assertEquals(DEFAULT_SHELL_ORDER, moveVisibleShortcut(DEFAULT_SHELL_ORDER, "", 0, -1))
        assertEquals(DEFAULT_SHELL_ORDER, moveVisibleShortcut(DEFAULT_SHELL_ORDER, "", 4, 1))
    }
}

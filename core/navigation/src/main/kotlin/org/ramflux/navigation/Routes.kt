// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.navigation

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.contains
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata

/**
 * A route key that shows data of the unlocked account. While the session is locked every
 * account-scoped key is dropped from every stack, so unlocking never reopens restored account data.
 */
interface AccountScoped

/** Where a route renders when the window has room for two panes. Keys without a role use one full pane. */
enum class PaneRole {
    /** A list whose selection opens a detail beside it, for example the conversation list. */
    LIST,

    /** The selected item of the list below it on the same stack, for example a chat. */
    DETAIL
}

/** Entry metadata key for [PaneRole]. */
object PaneRoleKey : NavMetadataKey<PaneRole>

/** Entry metadata key: the entry lays out the bottom window inset itself (see [LocalShellBottomInset]). */
object OwnsBottomInsetKey : NavMetadataKey<Boolean>

/**
 * Metadata for `entry<K>(metadata = pane(PaneRole.LIST))`. With [ownsBottomInset] the shell does not pad
 * the entry above the home indicator while it fills the window; it hands the inset over through
 * [LocalShellBottomInset] so the entry's bottom band (a composer) can reach the screen edge.
 */
fun pane(role: PaneRole, ownsBottomInset: Boolean = false): Map<String, Any> = metadata {
    put(PaneRoleKey, role)
    if (ownsBottomInset) put(OwnsBottomInsetKey, true)
}

internal fun NavEntry<*>.paneRole(): PaneRole? = if (metadata.contains(PaneRoleKey)) metadata[PaneRoleKey] else null

internal fun NavEntry<*>.ownsBottomInset(): Boolean = metadata.contains(OwnsBottomInsetKey) && metadata[OwnsBottomInsetKey] == true

/**
 * The application's routing policy. Which keys are account scoped is declared by the keys themselves
 * through [AccountScoped]; the policy only adds which keys open outside the tab stacks.
 */
class RoutePolicy(
    /** Keys outside the tab stacks, shown over the whole shell (for example account setup). */
    val isGlobal: (NavKey) -> Boolean = { false }
) {
    fun isAccountScoped(key: NavKey): Boolean = key is AccountScoped
}

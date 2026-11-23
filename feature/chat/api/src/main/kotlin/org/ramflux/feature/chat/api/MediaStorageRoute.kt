// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat.api
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.ramflux.navigation.AccountScoped

/** Media storage of one account: decrypted files by conversation, with clearing (media design B30). */
@Serializable data class MediaStorageRoute(val accountId: String) : NavKey, AccountScoped

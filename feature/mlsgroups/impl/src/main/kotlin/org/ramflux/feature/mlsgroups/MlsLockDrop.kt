// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.ramflux.data.MlsGroupSession
import org.ramflux.data.epoch

/**
 * Runs [onLock] each time the lock epoch of [account] moves after the first read: the app lock engaged, the account was
 * switched or removed. The caller closes every sheet, dialog and confirmation it holds and drops their state there, so nothing
 * of an admin action, a typed link or an invite survives a lock and nothing comes back after the unlock.
 */
internal fun ViewModel.dropOnLock(session: MlsGroupSession, account: String, onLock: () -> Unit): Job =
    viewModelScope.launch { session.epoch(account).drop(1).collect { onLock() } }

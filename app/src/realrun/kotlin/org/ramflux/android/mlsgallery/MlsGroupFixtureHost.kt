// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.ramflux.data.InMemoryMlsGroupNotices
import org.ramflux.data.MlsGroupSession
import org.ramflux.feature.chat.MlsGroupChatViewModel
import org.ramflux.feature.mlsgroups.MlsCreateViewModel
import org.ramflux.feature.mlsgroups.MlsGroupsViewModel
import org.ramflux.feature.mlsgroups.MlsInfoViewModel
import org.ramflux.feature.mlsgroups.MlsInviteViewModel
import org.ramflux.feature.mlsgroups.MlsMembersViewModel

/**
 * The session, the scripted repository and the real MLS group view models of one opened fixture. The view models are the product
 * ones; only the repository under them is scripted. Closing the host clears the view models and stops the scripted events.
 */
internal class MlsGroupFixtureHost(val fixture: MlsGroupFixture) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val repository = FixtureMlsGroupRepository(fixture, scope)
    val session = MlsGroupSession(repository, scope)
    private val notices = InMemoryMlsGroupNotices()
    private val store = ViewModelStore()

    private inline fun <reified T : ViewModel> model(key: String, crossinline make: () -> T): T = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
        }
    )[key, T::class.java]

    fun groups() = model("groups") { MlsGroupsViewModel(session) }
    fun create() = model("create") { MlsCreateViewModel(session) }
    fun invites() = model("invites") { MlsInviteViewModel(session, notices) }
    fun info() = model("info") { MlsInfoViewModel(session) }
    fun members() = model("members") { MlsMembersViewModel(session) }
    fun chat(key: String) = model("chat-$key") { MlsGroupChatViewModel(session, notices) }

    fun close() {
        store.clear()
        scope.cancel()
    }
}

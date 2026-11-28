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
import org.ramflux.data.UnavailableMediaRepository
import org.ramflux.feature.chat.ChatViewModel
import org.ramflux.feature.chat.DirectDetailsViewModel
import org.ramflux.feature.contacts.ContactsViewModel

/**
 * The repositories and real view models of one opened fixture. The view models are the product ones; only the repositories
 * under them are scripted. Closing the host clears the view models and stops the scripted events.
 */
internal class FixtureHost(val fixture: MlsFixture) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val identity = FixtureContactIdentityRepository(fixture.person, fixture.script, scope)
    private val history = FixtureMessageHistory(fixture.person, identity)
    private val directory = FixtureContactDirectory(fixture.person)
    private val accounts = FixtureAccounts()
    private val session = FixtureSession()
    private val store = ViewModelStore()

    private inline fun <reified T : ViewModel> model(key: String, crossinline make: () -> T): T = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
        }
    )[key, T::class.java]

    fun chatViewModel() = model("chat") { ChatViewModel(history, accounts, session, identity = identity) }
    fun contactsViewModel() = model("contacts") { ContactsViewModel(directory, accounts, session, identity) }
    fun detailsViewModel() = model(
        "details"
    ) { DirectDetailsViewModel(history, directory, session, UnavailableMediaRepository(), identity) }

    fun close() {
        store.clear()
        scope.cancel()
    }
}

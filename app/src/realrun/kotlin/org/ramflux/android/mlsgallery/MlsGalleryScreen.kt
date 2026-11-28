// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.ramflux.android.R
import org.ramflux.data.IdentityState
import org.ramflux.designsystem.UiText
import org.ramflux.feature.chat.ChatAction
import org.ramflux.feature.chat.ChatEvent
import org.ramflux.feature.chat.ChatScreen
import org.ramflux.feature.chat.DirectDetails
import org.ramflux.feature.contacts.ContactsAction
import org.ramflux.feature.contacts.ContactsEvent
import org.ramflux.feature.contacts.ContactsScreen
import org.ramflux.feature.conversations.ConversationItem
import org.ramflux.feature.conversations.ConversationsScreen
import org.ramflux.feature.conversations.ConversationsState

private const val VIEW_CHAT = "chat"
private const val VIEW_CONTACT = "contact"
private const val VIEW_DETAILS = "details"
private const val VIEW_ROW = "row"
private const val REVIEW_WAIT_MS = 5_000L

/** The fixture list; a fixture opens the real chat, contact detail, direct details or conversation row. */
@Composable
internal fun MlsGalleryScreen(dark: Boolean, onDark: (Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var view by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewRequested by remember { mutableStateOf(false) }
    var groupOpen by rememberSaveable { mutableStateOf<String?>(null) }
    val fixture = FIXTURES.firstOrNull { it.id == open }
    if (groupOpen != null) {
        MlsGroupGallery(groupOpen) { groupOpen = it }
        return
    }
    BackHandler(enabled = open != null) {
        if (view != null) view = null else open = null
    }
    if (fixture == null) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).testTag("mls-gallery"),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text(stringResource(R.string.mls_gallery_title), style = MaterialTheme.typography.titleLarge) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = dark, onCheckedChange = onDark, modifier = Modifier.testTag("mls-gallery-dark"))
                    Text(stringResource(R.string.mls_gallery_dark), Modifier.padding(start = 8.dp))
                }
            }
            items(FIXTURES, key = { it.id }) { item ->
                Button({
                    open = item.id
                    view = null
                }, Modifier.fillMaxWidth().testTag("mls-fixture-" + item.id)) {
                    Text(item.id + " " + stringResource(item.title))
                }
            }
            item { Text(stringResource(R.string.mls_group_section), style = MaterialTheme.typography.titleMedium) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GROUP_FIXTURES.forEach { item ->
                        Button({ groupOpen = item.id }, Modifier.fillMaxWidth().testTag("mls-group-fixture-" + item.id)) {
                            Text(item.id + " " + stringResource(item.title))
                        }
                    }
                }
            }
        }
        return
    }
    val host = remember(fixture.id) { FixtureHost(fixture) }
    DisposableEffect(host) { onDispose { host.close() } }
    val shown = view
    if (shown == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(fixture.id + " " + stringResource(fixture.title), style = MaterialTheme.typography.titleMedium)
            Counters(host)
            Button({ view = VIEW_CHAT }, Modifier.fillMaxWidth().testTag("mls-view-chat")) { Text(stringResource(R.string.mls_view_chat)) }
            Button(
                { view = VIEW_CONTACT },
                Modifier.fillMaxWidth().testTag("mls-view-contact")
            ) { Text(stringResource(R.string.mls_view_contact)) }
            Button(
                { view = VIEW_DETAILS },
                Modifier.fillMaxWidth().testTag("mls-view-details")
            ) { Text(stringResource(R.string.mls_view_details)) }
            Button({ view = VIEW_ROW }, Modifier.fillMaxWidth().testTag("mls-view-row")) { Text(stringResource(R.string.mls_view_row)) }
            OutlinedButton({ open = null }, Modifier.fillMaxWidth().testTag("mls-back")) { Text(stringResource(R.string.mls_back)) }
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        when (shown) {
            VIEW_CHAT -> ChatView(host, reviewRequested, { reviewRequested = false }, { view = VIEW_DETAILS }) { view = null }
            VIEW_CONTACT -> ContactView(host) { view = null }
            VIEW_DETAILS -> DetailsView(host, {
                reviewRequested = true
                view = VIEW_CHAT
            }) { view = null }
            else -> RowView(host)
        }
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) { Counters(host) }
    }
}

/** What the screens asked of the fixture repository: the runner reads these to check silent re-reads and accepted anchors. */
@Composable
private fun Counters(host: FixtureHost) {
    val asked by host.identity.asked.collectAsState()
    Text(
        stringResource(R.string.mls_counters, asked.identityReads, asked.securityReads, asked.accepts, asked.lastAnchor),
        Modifier.testTag("mls-fixture-counter"),
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun ChatView(host: FixtureHost, review: Boolean, reviewDone: () -> Unit, onDetails: () -> Unit, onBack: () -> Unit) {
    val vm = remember(host) { host.chatViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.onAction(ChatAction.Load(GALLERY_ACCOUNT, host.fixture.person.conversation, null, host.fixture.person.name))
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ChatEvent.Details -> onDetails()
                ChatEvent.Back -> onBack()
                else -> Unit
            }
        }
    }
    // "Review" of the direct details comes back here: the sheet opens once the chat knows the identity changed.
    LaunchedEffect(review) {
        if (!review) return@LaunchedEffect
        withTimeoutOrNull(REVIEW_WAIT_MS) { vm.state.first { it.identityState == IdentityState.CHANGED } }?.let {
            vm.onAction(ChatAction.ReviewIdentity)
        }
        reviewDone()
    }
    ChatScreen(state, vm::onAction, Modifier.statusBarsPadding(), showDetails = true)
}

@Composable
private fun ContactView(host: FixtureHost, onBack: () -> Unit) {
    val vm = remember(host) { host.contactsViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.onAction(ContactsAction.Visible(true))
        vm.state.first { it.loaded }
        vm.onAction(ContactsAction.Select(host.fixture.person.link))
    }
    LaunchedEffect(vm) { vm.events.collect { if (it == ContactsEvent.Back) onBack() } }
    ContactsScreen(state, vm::onAction, Modifier.statusBarsPadding())
}

@Composable
private fun DetailsView(host: FixtureHost, onReview: () -> Unit, onClose: () -> Unit) {
    val vm = remember(host) { host.detailsViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.open(GALLERY_ACCOUNT, host.fixture.person.conversation, host.fixture.person.name) }
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        DirectDetails(state, vm::onAction, onGallery = {}, onReview = onReview, onClose = onClose)
    }
}

/** The conversation row of the fixture person, with the warning glyph while the identity is changed. */
@Composable
private fun RowView(host: FixtureHost) {
    val person = host.fixture.person
    val changed by host.identity.changed(GALLERY_ACCOUNT).collectAsState(emptySet())
    // The row follows the same signals the conversation list follows while it is visible.
    LaunchedEffect(host) { host.identity.changes(GALLERY_ACCOUNT).collect { } }
    val state = ConversationsState(
        accountId = GALLERY_ACCOUNT,
        accountLabel = "Gallery",
        rows = persistentListOf(
            ConversationItem(person.conversation, person.name, UiText.Data("Hello"), "0", false, ai = person.ai, peer = person.root)
        ),
        loaded = true,
        changedPeers = changed.fold(persistentSetOf()) { all, peer -> all.add(peer) }
    )
    Box(Modifier.fillMaxSize().statusBarsPadding().testTag("mls-row")) {
        ConversationsScreen(state, {}, rooted = true)
    }
}

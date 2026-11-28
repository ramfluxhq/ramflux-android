// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ramflux.android.R
import org.ramflux.feature.chat.ChatAction
import org.ramflux.feature.chat.ChatEvent
import org.ramflux.feature.chat.ChatScreen
import org.ramflux.feature.mlsgroups.MlsCreateAction
import org.ramflux.feature.mlsgroups.MlsCreateEvent
import org.ramflux.feature.mlsgroups.MlsCreateScreen
import org.ramflux.feature.mlsgroups.MlsGroupsAction
import org.ramflux.feature.mlsgroups.MlsGroupsEvent
import org.ramflux.feature.mlsgroups.MlsGroupsScreen
import org.ramflux.feature.mlsgroups.MlsInfoAction
import org.ramflux.feature.mlsgroups.MlsInfoEvent
import org.ramflux.feature.mlsgroups.MlsInfoScreen
import org.ramflux.feature.mlsgroups.MlsInviteAction
import org.ramflux.feature.mlsgroups.MlsInviteEvent
import org.ramflux.feature.mlsgroups.MlsInviteScreen
import org.ramflux.feature.mlsgroups.MlsMembersAction
import org.ramflux.feature.mlsgroups.MlsMembersEvent
import org.ramflux.feature.mlsgroups.MlsMembersScreen
import org.ramflux.feature.mlsgroups.rememberMlsLinkActions

private const val V_LIST = "list"
private const val V_CREATE = "create"
private const val V_INVITES = "invites"
private const val V_INFO = "info"
private const val V_MEMBERS = "members"
private const val V_CHAT = "chat"

/** The wire methods whose call counters the gallery shows (tag `mls-calls-<method>`). */
private val METHODS = listOf(
    "group.list", "group.info", "group.create", "group.invite.create", "group.invite.list", "group.invite.revoke", "group.join",
    "group.join.pending", "group.members", "group.history", "group.send", "group.refresh", "group.mark_read", "group.message.delete",
    "group.leave"
)

/** One opened "Groups (M2)" fixture: the real MLS group screens over a scripted repository. */
@Composable
internal fun MlsGroupGallery(open: String?, onOpen: (String?) -> Unit) {
    val fixture = GROUP_FIXTURES.firstOrNull { it.id == open }
    if (fixture == null) {
        onOpen(null)
        return
    }
    BackHandler { onOpen(null) }
    val host = remember(fixture.id) { MlsGroupFixtureHost(fixture) }
    DisposableEffect(host) { onDispose { host.close() } }
    MlsGroupFixtureScreen(host) { onOpen(null) }
}

@Composable
private fun MlsGroupFixtureScreen(host: MlsGroupFixtureHost, onExit: () -> Unit) {
    var view by rememberSaveable { mutableStateOf<String?>(null) }
    var group by rememberSaveable(host.fixture.id) { mutableStateOf(host.fixture.group) }
    var fromCreate by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = view != null) { view = null }
    val links = rememberMlsLinkActions()
    Box(Modifier.fillMaxSize()) {
        when (view) {
            null -> Menu(host, onExit) { view = it }
            V_LIST -> {
                val vm = remember(host) { host.groups() }
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(vm) { vm.onAction(MlsGroupsAction.Open(GALLERY_ACCOUNT)) }
                LaunchedEffect(vm) {
                    vm.events.collect {
                        when (it) {
                            is MlsGroupsEvent.OpenGroup -> {
                                group = it.groupId
                                view = V_CHAT
                            }
                            MlsGroupsEvent.NewGroup -> view = V_CREATE
                            MlsGroupsEvent.Back -> view = null
                        }
                    }
                }
                MlsGroupsScreen(state, vm::onAction, links, Modifier.statusBarsPadding())
            }
            V_CREATE -> {
                val vm = remember(host) { host.create() }
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(vm) { vm.onAction(MlsCreateAction.Open(GALLERY_ACCOUNT)) }
                LaunchedEffect(vm) {
                    vm.events.collect {
                        when (it) {
                            is MlsCreateEvent.Created -> {
                                group = it.groupId
                                fromCreate = true
                                view = V_INVITES
                            }
                            MlsCreateEvent.Back -> view = V_LIST
                        }
                    }
                }
                MlsCreateScreen(state, vm::onAction, Modifier.statusBarsPadding())
            }
            V_INVITES -> {
                val vm = remember(host) { host.invites() }
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(vm, group) { vm.onAction(MlsInviteAction.Open(GALLERY_ACCOUNT, group, fromCreate)) }
                LaunchedEffect(vm) { vm.events.collect { if (it == MlsInviteEvent.Back) view = null } }
                MlsInviteScreen(state, vm::onAction, links, vm::drawQr, Modifier.statusBarsPadding())
            }
            V_INFO -> {
                val vm = remember(host) { host.info() }
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(vm, group) { vm.onAction(MlsInfoAction.Open(GALLERY_ACCOUNT, group)) }
                LaunchedEffect(vm) {
                    vm.events.collect {
                        when (it) {
                            is MlsInfoEvent.Invites -> view = V_INVITES
                            is MlsInfoEvent.Members -> view = V_MEMBERS
                            MlsInfoEvent.Back -> view = null
                        }
                    }
                }
                MlsInfoScreen(state, vm::onAction, Modifier.statusBarsPadding())
            }
            V_MEMBERS -> {
                val vm = remember(host) { host.members() }
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(vm, group) { vm.onAction(MlsMembersAction.Open(GALLERY_ACCOUNT, group)) }
                LaunchedEffect(vm) { vm.events.collect { if (it == MlsMembersEvent.Back) view = null } }
                MlsMembersScreen(state, vm::onAction, Modifier.statusBarsPadding())
            }
            else -> {
                val vm = remember(host, group) { host.chat(group) }
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(vm) { vm.onAction(ChatAction.Load(GALLERY_ACCOUNT, group, group, null)) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onForeground() }
                LaunchedEffect(vm) {
                    vm.events.collect {
                        when (it) {
                            ChatEvent.Back -> view = null
                            ChatEvent.Details -> view = V_INFO
                            else -> Unit
                        }
                    }
                }
                ChatScreen(state, vm::onAction, Modifier.statusBarsPadding(), showDetails = true)
            }
        }
        if (view != null) Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) { Calls(host) }
    }
}

@Composable
private fun Menu(host: MlsGroupFixtureHost, onExit: () -> Unit, open: (String) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(host.fixture.id + " " + stringResource(host.fixture.title), style = MaterialTheme.typography.titleMedium)
        Calls(host)
        listOf(
            V_LIST to R.string.mls_group_view_list,
            V_CREATE to R.string.mls_group_view_create,
            V_INVITES to R.string.mls_group_view_invites,
            V_INFO to R.string.mls_group_view_info,
            V_MEMBERS to R.string.mls_group_view_members,
            V_CHAT to R.string.mls_group_view_chat
        ).forEach { (id, label) ->
            Button({ open(id) }, Modifier.fillMaxWidth().testTag("mls-group-view-$id")) { Text(stringResource(label)) }
        }
        OutlinedButton(onExit, Modifier.fillMaxWidth().testTag("mls-back")) { Text(stringResource(R.string.mls_back)) }
    }
}

/** What the screens asked of the fixture repository, by wire method: the runner checks that nothing was sent without a tap. */
@Composable
private fun Calls(host: MlsGroupFixtureHost) {
    val asked by host.repository.asked.collectAsState()
    Column(Modifier.testTag("mls-calls")) {
        METHODS.forEach { method ->
            Text(
                stringResource(R.string.mls_group_calls, method, asked[method] ?: 0),
                Modifier.testTag("mls-calls-$method"),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

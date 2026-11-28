// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ramflux.data.MlsAdminKind
import org.ramflux.data.MlsAdminRules
import org.ramflux.data.MlsAdminState
import org.ramflux.data.MlsFailure
import org.ramflux.data.MlsGroupCatchUp
import org.ramflux.data.MlsGroupNotices
import org.ramflux.data.MlsGroupSession
import org.ramflux.data.MlsGroupWarning
import org.ramflux.data.MlsInfoState
import org.ramflux.data.MlsMembersState
import org.ramflux.data.MlsReadOnly
import org.ramflux.data.MlsTimelineState
import org.ramflux.data.admin
import org.ramflux.data.epoch
import org.ramflux.data.hideWarning
import org.ramflux.data.skipCatchUp
import org.ramflux.data.voidCommit
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R

/**
 * An MLS group on the shared chat screen. It is a plain view model over [MlsGroupSession] (no outbox, no Hilt, no direct-chat
 * code): the session owns every call and every outcome, so a send, a delete or a refusal is recorded when this chat is gone.
 * It handles the actions an MLS group supports: send, retry, discard, load older, delete for everyone (confirmed), refresh,
 * mentions, reply and copy. Attachments and cards are not sent from a group (L2). W: a `@HiltViewModel` constructor and the
 * navigation entry.
 */
class MlsGroupChatViewModel(
    private val session: MlsGroupSession,
    private val notices: MlsGroupNotices,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private data class Ids(val account: String, val group: String, val title: String?)

    private data class Local(
        val draft: String = "",
        val composer: ComposerMode = ComposerMode.SEND,
        val actionTarget: String? = null,
        val selectedId: String? = null,
        val deleteTarget: String? = null,
        val mentionOpen: Boolean = false,
        /** anchor to the name put into the draft; only names still in the draft are sent as mentions. */
        val picked: Map<String, String> = emptyMap(),
        val noticePending: Boolean = false,
        /** The skip dialog is open. */
        val skipOpen: Boolean = false,
        /** The log position the void confirmation is for. */
        val voidTarget: Long? = null,
        /** "Show" was tapped and the placeholder row is not loaded yet: older pages load (M2 path) until it is, at most [SHOW_PAGES]. */
        val showPending: Boolean = false,
        val showPages: Int = 0,
        val scrollTo: MlsScrollTarget? = null
    ) {
        override fun toString() = "Local(<redacted>)"
    }

    private val ids = MutableStateFlow<Ids?>(null)
    private var lockWatch: Job? = null
    private val local = MutableStateFlow(Local())
    private val channel = Channel<ChatEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    private data class Remote(
        val timeline: MlsTimelineState,
        val members: MlsMembersState,
        val info: MlsInfoState,
        val admin: MlsAdminState = MlsAdminState()
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val remote = ids.flatMapLatest { id ->
        if (id == null) {
            flowOf(Remote(MlsTimelineState(), MlsMembersState(), MlsInfoState()))
        } else {
            combine(
                session.timeline(id.account, id.group),
                session.members(id.account, id.group),
                session.info(id.account, id.group),
                session.admin(id.account, id.group)
            ) { t, m, i, a ->
                Remote(t, m, i, a)
            }
        }
    }

    val state: StateFlow<ChatState> = combine(ids, remote, local) { ids, remote, local -> build(ids, remote, local) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), ChatState())

    private fun build(ids: Ids?, remote: Remote, local: Local): ChatState {
        if (ids == null) return ChatState()
        val t = remote.timeline
        val surfaces = surfaces(remote)
        val rows = surfaces.rows.toImmutableList()
        val now = clock()
        val limited = t.limitedUntil?.takeIf { it > now }
        val readOnly = when (t.readOnly) {
            MlsReadOnly.LEFT -> GroupReadOnly.LEFT
            MlsReadOnly.REMOVED -> GroupReadOnly.REMOVED
            MlsReadOnly.BANNED -> GroupReadOnly.BANNED
            null -> null
        }
        val failure = t.failure
        return ChatState(
            conversationId = ids.group,
            groupId = ids.group,
            title = ids.title?.let { UiText.Data(it) } ?: remote.info.group?.name?.let { UiText.Data(it) },
            rows = rows,
            loading = t.loading,
            loaded = t.loaded,
            draft = local.draft,
            canSend = readOnly == null && limited == null,
            problem = if (failure != null && !failure.unavailable) {
                UiText.Resource(R.string.group_chat_error_generic)
            } else {
                null
            },
            unavailable = if (failure?.unavailable == true) UiText.Resource(R.string.group_chat_error_unavailable) else null,
            composer = local.composer,
            actionTarget = local.actionTarget,
            selectedId = local.selectedId,
            deliveryNotice = if (local.noticePending && remote.info.group != null) {
                uiText(
                    R.string.group_chat_notice_host_ip,
                    remote.info.group?.hostNode?.let { UiText.Data(it) } ?: UiText.Resource(R.string.group_chat_host_unknown)
                )
            } else {
                null
            },
            hasOlder = t.window.hasOlder,
            loadingOlder = t.loadingOlder,
            mls = MlsChatUi(
                readOnly = readOnly,
                limitedUntil = limited,
                refreshing = false,
                trimmed = t.window.trimmedNewest,
                confirmDelete = local.deleteTarget != null,
                mention = if (local.mentionOpen) {
                    MentionSheet(
                        remote.members.rows.filter { !it.isMe }.map { MentionRow(it.anchor, it.display, it.aiFlag) }.toImmutableList(),
                        remote.members.loading,
                        remote.members.cursor != null && remote.members.failure == null
                    )
                } else {
                    null
                },
                notice = listOfNotNull(
                    t.deleteFailure,
                    remote.admin.lastFailure?.takeIf { it.kind == MlsAdminKind.VOID }?.failure,
                    remote.admin.catchUp.skipFailure
                ).firstOrNull()?.let { noticeOf(it) },
                catchUp = surfaces.catchUp,
                banners = surfaces.banners.toImmutableList(),
                bannersMore = surfaces.more,
                admissionActions = surfaces.readOnly == null && remote.info.group != null,
                quotaLine = remote.info.group?.quotaPost?.takeIf { surfaces.readOnly == null && MlsAdminRules.quotaLineVisible(it) }
                    ?.let { MlsQuotaUi(it.remaining, it.limit, it.firstDay, it.resetsAt) },
                privacyEta = remote.info.group?.privacySendAfter?.takeIf { surfaces.readOnly == null && it > now },
                voidableRowId = surfaces.voidableRowId,
                voiding = (remote.admin.slot ?: remote.admin.pending?.action)?.kind == MlsAdminKind.VOID,
                confirmSkip = local.skipOpen && surfaces.catchUp is MlsCatchUpUi.Running,
                confirmVoid = local.voidTarget != null,
                scrollTo = local.scrollTo
            )
        )
    }

    /** What the session state gives the chat besides the plain timeline: rows, the catch-up banner, the warning banners, the head commit. */
    private class Surfaces(
        val rows: List<TimelineItem>,
        val catchUp: MlsCatchUpUi?,
        val banners: List<MlsBannerUi>,
        val more: Int,
        val voidableRowId: String?,
        val readOnly: MlsReadOnly?,
        val placeholderId: String?
    )

    private fun surfaces(remote: Remote): Surfaces {
        val t = remote.timeline
        val admin = remote.admin
        val group = remote.info.group
        val readOnly = t.readOnly
        // A group that is read-only shows no banner, no catch-up, no hint action and no void entry (A16).
        val warnings = if (readOnly != null) emptyList() else MlsAdminRules.visibleWarnings(admin.warnings, group?.myRole)
        val undecryptable = warnings.filterIsInstance<MlsGroupWarning.Undecryptable>().firstOrNull()
        val rows = MlsGroupChatMapping.rows(t, admin.voided, undecryptable)
        val rejoin = warnings.filterIsInstance<MlsGroupWarning.RejoinNeeded>().firstOrNull()
        val record = admin.catchUp
        val catchUp = when {
            readOnly != null -> null
            record.state == MlsGroupCatchUp.REJOINING || rejoin != null -> MlsCatchUpUi.Rejoining(rejoin?.reason)
            record.skipping -> MlsCatchUpUi.Skipping
            record.state == MlsGroupCatchUp.RUNNING -> MlsCatchUpUi.Running(record.done, record.total)
            else -> null
        }
        // `rejoin_needed` is shown by the catch-up banner; the others are banners, most severe first, hidden ones left out.
        val shown = warnings.filterNot { it is MlsGroupWarning.RejoinNeeded || (MlsAdminRules.hideable(it) && it.key in admin.hidden) }
        val canVoid = group != null && readOnly == null && MlsAdminRules.isAdmin(group.myRole) && !admin.busy
        val head = if (canVoid) MlsAdminRules.headCommit(t.window, admin.voided) else null
        return Surfaces(
            rows,
            catchUp,
            shown.take(MAX_BANNERS).map { MlsBannerUi(it, MlsAdminRules.hideable(it)) },
            (shown.size - MAX_BANNERS).coerceAtLeast(0),
            head?.let { MlsGroupChatMapping.rowIdAt(rows, it) },
            readOnly,
            undecryptable?.fromLogSeq?.let { MlsGroupChatMapping.PLACEHOLDER + it }
        )
    }

    private fun noticeOf(failure: MlsFailure) = GroupDelivery(
        GroupDeliveryKind.NOT_SENT,
        if (failure.unavailable) GroupReason.UNAVAILABLE else MlsGroupChatMapping.reasonOf(failure.code),
        failure.detail
    )

    /** The app returned to the foreground with this chat open: fill the gap and pull from the host. */
    fun onForeground() {
        ids.value?.let { session.onForeground(it.account, it.group) }
    }

    fun onAction(action: ChatAction) {
        val target = ids.value
        when (action) {
            is ChatAction.Load -> load(action)
            is ChatAction.Draft -> local.value = local.value.copy(draft = action.text.take(MAX_DRAFT))
            ChatAction.Send -> send(target)
            // A service row cannot be selected, except the head commit's row (its one action is "Undo this change").
            is ChatAction.Select -> if (rowOf(action.id)?.line == null || action.id == state.value.mls?.voidableRowId) {
                local.value = local.value.copy(selectedId = action.id)
            }
            ChatAction.Dismiss -> local.value = local.value.copy(selectedId = null)
            ChatAction.Reply -> local.value = local.value.let {
                it.copy(composer = ComposerMode.REPLY, actionTarget = it.selectedId, selectedId = null)
            }
            ChatAction.CancelComposer -> local.value = local.value.copy(composer = ComposerMode.SEND, actionTarget = null)
            ChatAction.Copy -> {
                val text = (rowOf(local.value.selectedId)?.content as? UiText.Data)?.value
                local.value = local.value.copy(selectedId = null)
                if (text != null) channel.trySend(ChatEvent.Copy(text))
            }
            ChatAction.DeleteForEveryone -> {
                val id = local.value.selectedId
                if (rowOf(id)?.deletable == true) local.value = local.value.copy(deleteTarget = id, selectedId = null)
            }
            ChatAction.ConfirmDelete -> confirmDelete(target)
            ChatAction.CancelDelete -> local.value = local.value.copy(deleteTarget = null)
            is ChatAction.Retry -> if (target != null && action.id.startsWith(MlsGroupChatMapping.LOCAL)) {
                session.retry(target.account, target.group, action.id.removePrefix(MlsGroupChatMapping.LOCAL))
            }
            is ChatAction.Discard -> if (target != null && action.id.startsWith(MlsGroupChatMapping.LOCAL)) {
                local.value = local.value.copy(selectedId = null)
                session.discard(target.account, target.group, action.id.removePrefix(MlsGroupChatMapping.LOCAL))
            }
            ChatAction.JumpToNewest -> target?.let { session.refreshGroup(it.account, it.group) }
            ChatAction.LoadOlder -> target?.let { session.loadOlder(it.account, it.group) }
            ChatAction.Refresh -> target?.let {
                // A refresh reloads a window that failed to load; the session reloads the newest page of a trimmed one, then pulls from the host (once per 5 s).
                if (!state.value.loaded || state.value.problem != null) session.reloadGroup(it.account, it.group)
                session.refreshGroup(it.account, it.group)
            }
            is ChatAction.Shown -> if (target != null && action.id.startsWith(MlsGroupChatMapping.POSTED)) {
                action.id.removePrefix(
                    MlsGroupChatMapping.POSTED
                ).toLongOrNull()?.let { session.markRead(target.account, target.group, it) }
            }
            ChatAction.MlsSkip -> if (state.value.mls?.catchUp is MlsCatchUpUi.Running) local.value = local.value.copy(skipOpen = true)
            ChatAction.MlsCancelSkip -> local.value = local.value.copy(skipOpen = false)
            // The skip dialog's confirm is the only caller of `group.catch_up{skip: true}` (A3).
            ChatAction.MlsConfirmSkip -> {
                local.value = local.value.copy(skipOpen = false)
                if (target != null) session.skipCatchUp(target.account, target.group)
            }
            is ChatAction.MlsHideBanner -> if (target != null) session.hideWarning(target.account, target.group, action.code)
            ChatAction.MlsShowUndecryptable -> showUndecryptable(target)
            is ChatAction.MlsUndo -> askVoid(action.rowId)
            ChatAction.MlsCancelUndo -> local.value = local.value.copy(voidTarget = null)
            // The void confirmation's confirm is the only caller of `group.void` (A1). The session checks the head commit again.
            ChatAction.MlsConfirmUndo -> {
                val slot = local.value.voidTarget
                local.value = local.value.copy(voidTarget = null)
                if (target != null && slot != null) session.voidCommit(target.account, target.group, slot)
            }
            ChatAction.MlsOpenInvite -> channel.trySend(ChatEvent.MlsInvite)
            ChatAction.MlsOpenAlerts -> channel.trySend(ChatEvent.MlsAlerts)
            ChatAction.MentionOpen -> if (target != null) {
                if (!state.value.loaded || state.value.mls?.mention == null) session.loadMembers(target.account, target.group)
                local.value = local.value.copy(mentionOpen = true)
            }
            ChatAction.MentionMore -> target?.let { session.loadMoreMembers(it.account, it.group) }
            ChatAction.MentionClose -> local.value = local.value.copy(mentionOpen = false)
            is ChatAction.MentionPick -> pick(action.anchor)
            ChatAction.Back -> channel.trySend(ChatEvent.Back)
            ChatAction.Details -> channel.trySend(ChatEvent.Details)
            // Attachments, cards, forwarding, editing, reactions and the gallery are not part of M2 groups (L2).
            else -> Unit
        }
    }

    /** "Undo this change": the head commit's row, or the log position `head_commit_rejected` names (`h<log_seq>`). */
    private fun askVoid(rowId: String) {
        val slot = MlsGroupChatMapping.slotOf(rowId) ?: return
        val now = state.value
        val rejected = latestAdmin.warnings.items.values.any { it is MlsGroupWarning.HeadCommitRejected && it.logSeq == slot }
        val head = now.mls?.voidableRowId?.let { MlsGroupChatMapping.slotOf(it) }
        val allowed = latestInfoAdmin && now.mls?.readOnly == null && (slot == head || rejected)
        local.value = local.value.copy(selectedId = null, voidTarget = if (allowed) slot else null)
    }

    /** "Show": scrolls to the placeholder row; while older rows are missing they load by the M2 paging path, one page at a time. */
    private fun showUndecryptable(target: Ids?) {
        if (target == null) return
        local.value = local.value.copy(showPending = true, showPages = 0)
        followShow(target)
    }

    private fun followShow(target: Ids) {
        val current = local.value
        if (!current.showPending) return
        val placeholder = surfaces(Remote(latest, MlsMembersState(), latestInfo, latestAdmin)).placeholderId
        val loaded = placeholder != null && state.value.rows.any { it.id == placeholder }
        when {
            loaded -> local.value = current.copy(
                showPending = false,
                scrollTo = MlsScrollTarget(placeholder.orEmpty(), (current.scrollTo?.nonce ?: 0) + 1)
            )
            latest.window.hasOlder && !latest.loadingOlder && current.showPages < SHOW_PAGES -> {
                local.value = current.copy(showPages = current.showPages + 1)
                session.loadOlder(target.account, target.group)
            }
            !latest.window.hasOlder || current.showPages >= SHOW_PAGES -> local.value = current.copy(showPending = false)
        }
    }

    private fun load(action: ChatAction.Load) {
        val group = action.groupId ?: action.conversationId
        val next = Ids(action.accountId, group, action.title)
        if (ids.value == next) return
        ids.value = next
        local.value = Local()
        lockWatch?.cancel()
        // The app lock engaged or the account went away: every dialog, sheet and draft of this chat is dropped and the chat closes.
        lockWatch = viewModelScope.launch {
            session.epoch(action.accountId).drop(1).collect {
                local.value = Local()
                ids.value = null
                channel.trySend(ChatEvent.Back)
            }
        }
        session.watch(action.accountId)
        session.openGroup(action.accountId, group)
        // The one-time host notice: once per account and group, shown as a note above the composer.
        if (!notices.hostIpSeen(action.accountId, group)) {
            notices.markHostIpSeen(action.accountId, group)
            local.value = local.value.copy(noticePending = true)
        }
    }

    private fun rowOf(id: String?): MessageItem? = state.value.rows.firstOrNull { it.id == id } as? MessageItem

    private fun send(target: Ids?) {
        val current = local.value
        val text = current.draft.trim()
        val chat = state.value
        if (target == null || text.isEmpty() || !chat.canSend) return
        val replyTo = if (current.composer == ComposerMode.REPLY) postIdOf(current.actionTarget) else null
        val mentions = current.picked.filter { (_, name) -> text.contains(AT + name) }.keys.toList()
        session.send(target.account, target.group, text, replyTo, mentions)
        local.value = Local(noticePending = current.noticePending)
    }

    private fun postIdOf(rowId: String?): String? = rowId?.removePrefix(MlsGroupChatMapping.POSTED)?.toLongOrNull()?.let { slot ->
        remoteWindowPostId(slot)
    }

    private var latest: MlsTimelineState = MlsTimelineState()
    private var latestInfo: MlsInfoState = MlsInfoState()
    private var latestAdmin: MlsAdminState = MlsAdminState()
    private val latestInfoAdmin get() = latestInfo.group?.let { MlsAdminRules.isAdmin(it.myRole) } == true

    private fun remoteWindowPostId(slot: Long): String? = latest.window.posts.firstOrNull {
        it.logSeq == slot
    }?.postId?.takeIf { it.isNotEmpty() }

    private fun confirmDelete(target: Ids?) {
        val id = local.value.deleteTarget
        local.value = local.value.copy(deleteTarget = null)
        val slot = id?.removePrefix(MlsGroupChatMapping.POSTED)?.toLongOrNull() ?: return
        val postId = remoteWindowPostId(slot) ?: return
        if (target != null) {
            session.deleteFailureShown(target.account, target.group)
            session.deletePost(target.account, target.group, postId)
        }
    }

    private fun pick(anchor: String) {
        val target = ids.value ?: return
        val member = membersNow(target)?.firstOrNull { it.anchor == anchor }
        val name = member?.display
        val current = local.value
        val tag = name ?: return run { local.value = current.copy(mentionOpen = false) }
        val draft = buildString {
            append(current.draft)
            if (isNotEmpty() && !endsWith(SPACE)) append(SPACE)
            append(AT).append(tag).append(SPACE)
        }
        local.value = current.copy(draft = draft.take(MAX_DRAFT), picked = current.picked + (anchor to tag), mentionOpen = false)
    }

    private var loadedMembers: List<org.ramflux.data.MlsGroupMember> = emptyList()

    private fun membersNow(target: Ids): List<org.ramflux.data.MlsGroupMember>? = loadedMembers.takeIf { ids.value == target }

    init {
        // Keep the latest session values for the lookups of an action (post ids and member names); the screen state is separate.
        viewModelScope.launch {
            remote.collect {
                latest = it.timeline
                latestInfo = it.info
                latestAdmin = it.admin
                loadedMembers = it.members.rows
                ids.value?.let { target -> followShow(target) }
            }
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
        const val MAX_DRAFT = 65_536
        const val MAX_BANNERS = 2
        const val SHOW_PAGES = 12
        const val SPACE = ' '
        const val AT = '@'
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val history: MessageHistoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository,
    private val drafts: ChatDraftRepository,
    private val media: MediaRepository,
    private val stager: MediaStager
) : ViewModel() {
    /** For hosts that keep no drafts and no media (tests and previews). */
    constructor(
        history: MessageHistoryRepository,
        accounts: AccountAccessRepository,
        session: ClientSessionRepository,
        drafts: ChatDraftRepository = NoChatDrafts
    ) : this(history, accounts, session, drafts, UnavailableMediaRepository(), NoMediaStager)

    private data class Target(val account: String, val conversation: String, val group: String?)
    private var target: Target? = null

    /** The loaded window in reading order (oldest first), mapped once per page, bounded by [WINDOW]. */
    private val window = ArrayList<TimelineItem>()
    private var older: String? = null

    /** Rows were trimmed from the newest end; live pages are not merged until the window is reset. */
    private var detached = false
    private var olderJob: Job? = null
    private val outbox = LinkedHashMap<String, Outgoing>()
    private var lastMarked: String? = null
    private var markRefused = false
    private var unreadAtOpen = 0L
    private var dividerPlaced = false
    private var draftJob: Job? = null

    /** Attachment references of the window by key, the files this device holds, and transfers in flight. */
    private val references = HashMap<String, MediaAttachment>()
    private val located = HashMap<String, String>()
    private val transfers = HashMap<String, TransferState>()
    private val downloads = HashMap<String, Job>()
    private val local = MutableStateFlow(ChatState())
    private val channel = Channel<ChatEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { screen, status ->
        if (status.phase == ClientPhase.OPEN) screen else ChatState(problem = uiText(R.string.ui_private_data_is_closed))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ChatState())
    private var request: Job? = null
    private var watch: Job? = null
    private var watchTarget: Target? = null
    private var typingJob: Job? = null
    private var typingExpiry: Job? = null
    private var typingActive = false
    private var typingRefused = false
    private var needsRefresh = false
    private var ticket = 0L
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            // Lock or session loss: seal the draft before the in-memory state is dropped.
            sealDraft()
            stager.dropMemory()
            request?.cancel()
            watch?.cancel()
            watchTarget = null
            clearPeerTypingClock()
            stopTyping()
            typingRefused = false
            ticket++
            resetTimeline()
            local.value = ChatState()
            val openTarget = target
            if (phase == ClientPhase.OPEN && openTarget != null) {
                local.value = ChatState(conversationId = openTarget.conversation)
                ensureWatch(openTarget)
                restoreDraft(openTarget)
                onAction(ChatAction.Refresh)
            }
        }
    }
    fun onAction(action: ChatAction) {
        when (action) {
            is ChatAction.Load -> {
                val next = Target(action.accountId, action.conversationId, action.groupId)
                if (target != next) {
                    sealDraft()
                    request?.cancel()
                    watch?.cancel()
                    watchTarget = null
                    clearPeerTypingClock()
                    stopTyping()
                    typingRefused = false
                    ticket++
                    resetTimeline()
                    target = next
                    local.value = ChatState(conversationId = next.conversation)
                    ensureWatch(next)
                    restoreDraft(next)
                    read()
                }
            }
            ChatAction.Refresh -> if (request?.isActive != true) read()
            is ChatAction.Draft -> if (!local.value.sending && target != null && session.status.value.phase == ClientPhase.OPEN) {
                val text = action.text.take(65536)
                local.update { it.copy(draft = text) }
                if (local.value.composer == ComposerMode.SEND) syncTyping(text.isNotBlank())
                if (local.value.composer != ComposerMode.EDIT) scheduleDraftSave()
            }
            ChatAction.Send -> if (local.value.draft.isNotBlank()) {
                if (local.value.composer == ComposerMode.SEND) {
                    enqueue()
                } else if (request?.isActive != true) {
                    compose(local.value.composer)
                }
            }
            ChatAction.Back -> {
                sealDraft()
                request?.cancel()
                watch?.cancel()
                watchTarget = null
                clearPeerTypingClock()
                stopTyping()
                ticket++
                resetTimeline()
                local.value = ChatState()
                target = null
                viewModelScope.launch { channel.send(ChatEvent.Back) }
            }
            is ChatAction.Select -> if (target != null && session.status.value.phase == ClientPhase.OPEN) {
                local.update { it.copy(selectedId = action.id) }
            }
            ChatAction.Dismiss -> local.update { it.copy(selectedId = null) }
            ChatAction.Reply -> selected()?.let { row ->
                if (!row.deleted && !row.placeholder) {
                    local.update { it.copy(composer = ComposerMode.REPLY, actionTarget = row.id, expectedRevision = 0, selectedId = null) }
                }
            }
            ChatAction.Edit -> selected()?.let { row ->
                val text = (row.content as? UiText.Data)?.value ?: return@let
                if (row.own && !row.deleted && !row.placeholder && !row.service) {
                    local.update {
                        it.copy(
                            composer = ComposerMode.EDIT,
                            actionTarget = row.id,
                            expectedRevision = row.revision,
                            draft = text,
                            selectedId = null
                        )
                    }
                }
            }
            is ChatAction.React -> if (ChatReaction.accepts(action.token)) {
                mutate(local.value.selectedId) { account, conversation, id, operation ->
                    history.react(account, conversation, id, action.token, false, operation)
                }
            }
            ChatAction.Copy -> selected()?.let { row ->
                val text = (row.content as? UiText.Data)?.value
                local.update { it.copy(selectedId = null) }
                if (text != null && !row.deleted && !row.placeholder) viewModelScope.launch { channel.send(ChatEvent.Copy(text)) }
            }
            ChatAction.DeleteForMe -> mutate(local.value.selectedId) { account, conversation, id, operation ->
                history.delete(account, conversation, id, MessageDeleteScope.LOCAL, operation).also {
                    if (it is ClientResult.Ready) media.purge(account, id)
                }
            }
            ChatAction.DeleteForEveryone -> mutate(local.value.selectedId) { account, conversation, id, operation ->
                val hasMedia = references.values.any { it.messageId == id }
                history.delete(account, conversation, id, MessageDeleteScope.EVERYONE, operation).also {
                    if (it is ClientResult.Ready && hasMedia) {
                        // The SDK does not retire relay grants on erase (request R7); the author does it here.
                        media.revoke(account, id)
                        media.purge(account, id)
                    }
                }
            }
            ChatAction.CancelComposer -> {
                val editing = local.value.composer == ComposerMode.EDIT
                local.update {
                    it.copy(
                        composer = ComposerMode.SEND,
                        actionTarget = null,
                        expectedRevision = 0,
                        draft = if (editing) "" else it.draft
                    )
                }
                if (editing) target?.let { restoreDraft(it) }
            }
            ChatAction.LoadOlder -> loadOlder()
            is ChatAction.Shown -> shown(action.id)
            is ChatAction.Retry -> retry(action.id)
            is ChatAction.Discard -> if (outbox.remove(action.id) != null) publish()
            ChatAction.Details -> if (target != null) viewModelScope.launch { channel.send(ChatEvent.Details) }
            ChatAction.Call -> if (target != null && local.value.groupId == null) viewModelScope.launch { channel.send(ChatEvent.Call) }
            ChatAction.Pause -> sealDraft()
            is ChatAction.OpenAttachment -> openAttachment(action.key)
            ChatAction.CloseViewer -> local.update { it.copy(viewer = null) }
            is ChatAction.SaveAttachment -> handOver(action.key, FileUse.SAVE)
            is ChatAction.ShareAttachment -> handOver(action.key, FileUse.SHARE)
            is ChatAction.Pick -> if (target != null && local.value.canSend) {
                viewModelScope.launch {
                    channel.send(
                        ChatEvent.Pick(action.image)
                    )
                }
            }
            is ChatAction.Picked -> stage(action.uri, action.image)
        }
    }
    private fun selected(): MessageItem? {
        val id = local.value.selectedId ?: return null
        return local.value.rows.filterIsInstance<MessageItem>().firstOrNull { it.id == id }
    }
    private suspend fun validate(expected: Long, generation: Long, expectedTarget: Target): Boolean {
        current(expected, generation, expectedTarget)
        val catalog = accounts.list()
        current(expected, generation, expectedTarget)
        val value = when (catalog) {
            is ClientResult.Ready -> catalog.value
            is ClientResult.Failed -> {
                local.value = ChatState(
                    problem = uiText(
                        R.string.ui_account_state_could_not_be_read_code_value,
                        UiText.Number(catalog.problem.code.toLong())
                    )
                )
                return false
            }
            is ClientResult.Unavailable -> {
                local.value = ChatState(unavailable = uiText(R.string.ui_account_access_is_unavailable_value, catalog.reason.description()))
                return false
            }
        }
        val selected = value.accounts.find { it.id == expectedTarget.account }
        val valid = value.activeId == expectedTarget.account && selected?.unlocked == true
        if (!valid) {
            val onlyLockedAccount = selected != null && !selected.unlocked && value.activeId == null
            val notice = if (onlyLockedAccount) {
                R.string.ui_private_data_is_closed
            } else {
                R.string.ui_the_active_account_changed_return_to_conversations
            }
            local.value = ChatState(problem = uiText(notice))
        }
        return valid
    }
    private fun read() {
        val currentTarget = target ?: return
        if (session.status.value.phase != ClientPhase.OPEN) return
        val expected = ++ticket
        val generation = session.status.value.generation
        // A refresh keeps the rows on screen and merges the newest page into them.
        local.update { it.copy(conversationId = currentTarget.conversation, loading = true, problem = null) }
        request = viewModelScope.launch {
            try {
                if (!validate(expected, generation, currentTarget)) return@launch
                val groupHeader = currentTarget.group?.let {
                    history.groupHeader(currentTarget.account, it, currentTarget.conversation)
                }
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                if (currentTarget.group != null &&
                    (groupHeader !is ClientResult.Ready || groupHeader.value.groupId != currentTarget.group)
                ) {
                    local.value = ChatState(problem = uiText(R.string.ui_conversation_details_unavailable))
                    return@launch
                }
                val result = history.page(currentTarget.account, currentTarget.conversation, null)
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                val header = groupHeader ?: history.header(currentTarget.account, currentTarget.conversation)
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                if (!dividerPlaced) unreadAtOpen = (header as? ClientResult.Ready)?.value?.unread ?: 0L
                local.update {
                    it.copy(
                        groupId = (groupHeader as? ClientResult.Ready)?.value?.groupId,
                        title = (header as? ClientResult.Ready)?.value?.title?.takeIf { title -> title.isNotBlank() }?.let { title ->
                            UiText.Data(title)
                        },
                        headerProblem = when (header) {
                            is ClientResult.Ready -> null
                            is ClientResult.Failed, is ClientResult.Unavailable -> uiText(R.string.ui_conversation_details_unavailable)
                        }
                    )
                }
                val capability = history.sendAvailability()
                current(expected, generation, currentTarget)
                when (result) {
                    is ClientResult.Ready -> {
                        merge(result.value)
                        local.update {
                            it.copy(
                                loaded = true,
                                canSend = capability is ClientResult.Ready,
                                unavailable = when (capability) {
                                    is ClientResult.Unavailable -> uiText(
                                        R.string.ui_sending_unavailable_value,
                                        capability.reason.description()
                                    )
                                    is ClientResult.Ready, is ClientResult.Failed -> null
                                },
                                problem = when (capability) {
                                    is ClientResult.Failed -> uiText(
                                        R.string.ui_send_availability_failed_code_value,
                                        UiText.Number(capability.problem.code.toLong())
                                    )
                                    is ClientResult.Ready, is ClientResult.Unavailable -> null
                                }
                            )
                        }
                        publish()
                        if (capability is ClientResult.Ready) resendQueued()
                    }
                    is ClientResult.Failed -> local.update {
                        it.copy(problem = uiText(R.string.ui_read_failed_code_value, UiText.Number(result.problem.code.toLong())))
                    }
                    is ClientResult.Unavailable -> local.update {
                        it.copy(unavailable = uiText(R.string.ui_message_history_is_unavailable))
                    }
                }
            } finally {
                finish(expected)
            }
        }
    }

    /** Reply and edit; plain sends go through [enqueue]. */
    private fun compose(mode: ComposerMode) {
        val currentTarget = target ?: return
        if (session.status.value.phase != ClientPhase.OPEN || mode == ComposerMode.SEND) return
        val text = local.value.draft
        val messageId = local.value.actionTarget ?: return
        val revision = local.value.expectedRevision
        val operation = UUID.randomUUID().toString()
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(sending = true, problem = null) }
        request = viewModelScope.launch {
            try {
                if (!validate(expected, generation, currentTarget)) return@launch
                val changed = if (mode == ComposerMode.REPLY) {
                    history.reply(currentTarget.account, currentTarget.conversation, messageId, text, operation)
                } else {
                    history.edit(currentTarget.account, currentTarget.conversation, messageId, text, revision, operation)
                }
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                applyUnit(changed, clearDraft = true)
                if (changed is ClientResult.Ready && mode == ComposerMode.REPLY) {
                    drafts.clear(currentTarget.account, currentTarget.conversation)
                }
            } finally {
                finish(expected)
            }
        }
    }

    /** Moves the draft into the outbox at once (boards MOB-B05, MOB-B08) and hands it to the SDK. */
    private fun enqueue() {
        val currentTarget = target ?: return
        if (session.status.value.phase != ClientPhase.OPEN) return
        val text = local.value.draft
        if (outbox.size >= OUTBOX_LIMIT) return
        val operation = UUID.randomUUID().toString()
        val id = LOCAL_PREFIX + operation
        outbox[id] = Outgoing(operation, text, System.currentTimeMillis() / 1000, LocalDelivery.SENDING)
        draftJob?.cancel()
        local.update { it.copy(draft = "", problem = null) }
        syncTyping(false)
        viewModelScope.launch { drafts.clear(currentTarget.account, currentTarget.conversation) }
        publish()
        deliver(currentTarget, id)
    }

    private fun deliver(currentTarget: Target, id: String) {
        val outgoing = outbox[id] ?: return
        val generation = session.status.value.generation
        viewModelScope.launch {
            if (!accountStillOpen(currentTarget, generation)) return@launch
            val staged = outgoing.staged
            val sent = if (staged == null) {
                history.send(currentTarget.account, currentTarget.conversation, outgoing.text, outgoing.operation)
            } else {
                media.send(
                    currentTarget.account,
                    MediaDestination(currentTarget.conversation, currentTarget.group),
                    outgoing.text,
                    listOf(staged),
                    outgoing.operation
                )
            }
            if (target != currentTarget || generation != session.status.value.generation) return@launch
            when (sent) {
                is ClientResult.Ready -> {
                    outbox.remove(id)
                    local.update { it.copy(deliveryNotice = uiText(R.string.ui_delivery_submitted)) }
                    publish()
                    channel.send(ChatEvent.Sent)
                    if (request?.isActive == true) needsRefresh = true else read()
                }
                is ClientResult.Unavailable -> {
                    // Sending is not available here at all: give the words back to the composer.
                    outbox.remove(id)
                    local.update {
                        it.copy(
                            draft = it.draft.ifEmpty { outgoing.text },
                            unavailable = uiText(R.string.ui_sending_unavailable_draft_kept)
                        )
                    }
                    publish()
                }
                is ClientResult.Failed -> {
                    val offline = sent.problem.kind == ClientFailureKind.UNAVAILABLE || sent.problem.statusName in OFFLINE
                    outgoing.delivery = if (offline) LocalDelivery.QUEUED else LocalDelivery.FAILED
                    outgoing.definite = sent.problem.outcome != ClientOutcome.UNKNOWN
                    local.update {
                        it.copy(
                            problem = if (offline) null else sendFailure(sent.problem),
                            deliveryNotice = if (offline) null else failedNotice(sent.problem)
                        )
                    }
                    publish()
                }
            }
        }
    }

    private fun retry(id: String) {
        val currentTarget = target ?: return
        val outgoing = outbox[id] ?: return
        if (outgoing.delivery == LocalDelivery.SENDING || session.status.value.phase != ClientPhase.OPEN) return
        // An uncertain outcome is retried under the same operation id so the SDK can reconcile it.
        val again = if (outgoing.definite) outgoing.copy(operation = UUID.randomUUID().toString()) else outgoing
        again.delivery = LocalDelivery.SENDING
        outbox[id] = again
        publish()
        deliver(currentTarget, id)
    }

    private fun resendQueued() {
        outbox.entries.filter { it.value.delivery == LocalDelivery.QUEUED }.forEach { retry(it.key) }
    }

    private suspend fun accountStillOpen(expectedTarget: Target, generation: Long): Boolean {
        if (target != expectedTarget || session.status.value.phase != ClientPhase.OPEN ||
            generation != session.status.value.generation
        ) {
            return false
        }
        val catalog = accounts.list() as? ClientResult.Ready ?: return false
        return catalog.value.activeId == expectedTarget.account &&
            catalog.value.accounts.any { it.id == expectedTarget.account && it.unlocked }
    }

    /** Merges the newest page into the window: rows from the first overlap onwards are replaced. */
    private fun merge(page: TimelinePage) {
        remember(page.rows)
        val mapped = page.rows.map(::itemOf)
        if (window.isEmpty()) {
            window.addAll(mapped)
            older = page.older
            return
        }
        if (detached) return
        val ids = mapped.mapTo(HashSet(mapped.size)) { it.id }
        val overlap = window.indexOfFirst { it.id in ids }
        if (overlap < 0) {
            // More new rows than one page: the gap cannot be filled forwards, so the window restarts.
            window.clear()
            window.addAll(mapped)
            older = page.older
        } else {
            window.subList(overlap, window.size).clear()
            window.addAll(mapped)
        }
    }

    private fun loadOlder() {
        val currentTarget = target ?: return
        val cursor = older ?: return
        if (olderJob?.isActive == true || session.status.value.phase != ClientPhase.OPEN) return
        local.update { it.copy(loadingOlder = true) }
        val generation = session.status.value.generation
        olderJob = viewModelScope.launch {
            try {
                val result = history.page(currentTarget.account, currentTarget.conversation, cursor)
                if (target != currentTarget || generation != session.status.value.generation) return@launch
                when (result) {
                    is ClientResult.Ready -> {
                        val known = window.mapTo(HashSet(window.size)) { it.id }
                        remember(result.value.rows)
                        window.addAll(0, result.value.rows.map(::itemOf).filter { it.id !in known })
                        older = result.value.older
                        if (window.size > WINDOW) {
                            window.subList(WINDOW, window.size).clear()
                            detached = true
                        }
                        publish()
                    }
                    is ClientResult.Failed -> local.update {
                        it.copy(problem = uiText(R.string.ui_read_failed_code_value, UiText.Number(result.problem.code.toLong())))
                    }
                    is ClientResult.Unavailable -> older = null
                }
            } finally {
                if (target == currentTarget) local.update { it.copy(loadingOlder = false) }
            }
        }
    }

    /** Marks the newest incoming message at or before [id] as read, once, through the SDK. */
    private fun shown(id: String) {
        val currentTarget = target ?: return
        val index = window.indexOfFirst { it.id == id }
        if (index < 0) return
        if (detached && index == window.lastIndex) {
            // Back at the newest loaded row of a trimmed window: start again from the newest page.
            detached = false
            window.clear()
            older = null
            read()
            return
        }
        if (markRefused || session.status.value.phase != ClientPhase.OPEN) return
        val incoming = (index downTo 0).asSequence().map { window[it] }
            .firstOrNull { it is MessageItem && !it.own && !it.deleted } ?: return
        val marked = lastMarked?.let { last -> window.indexOfFirst { it.id == last } } ?: -1
        if (marked >= window.indexOf(incoming)) return
        lastMarked = incoming.id
        viewModelScope.launch {
            val result = history.markRead(currentTarget.account, currentTarget.conversation, incoming.id)
            if (result is ClientResult.Unavailable) markRefused = true
        }
    }

    /** Publishes the window plus the outbox as the timeline, and places the unread divider once. */
    private fun publish() {
        val pending = outbox.map { (id, outgoing) ->
            val staged = outgoing.staged
            val attachments = if (staged == null) {
                persistentListOf()
            } else {
                persistentListOf(
                    AttachmentItem(
                        id, 0, kindOf(staged.mime), staged.fileName, staged.file.length(), MediaAttachment.DEFAULT_RATIO,
                        if (outgoing.delivery == LocalDelivery.SENDING) AttachmentStatus.SENDING else AttachmentStatus.FAILED,
                        staged.file.path, staged.mime
                    )
                )
            }
            MessageItem(
                id,
                "",
                true,
                false,
                UiText.Data(outgoing.text),
                outgoing.sentAt,
                delivery = outgoing.delivery,
                attachments = attachments
            )
        }
        if (!dividerPlaced && window.isNotEmpty()) {
            dividerPlaced = true
            local.update { it.copy(firstUnreadId = firstUnread()) }
        }
        local.update {
            it.copy(
                rows = (window + pending).toPersistentList(),
                hasOlder = older != null,
                queued = outbox.values.any { item -> item.delivery == LocalDelivery.QUEUED }
            )
        }
    }

    /** The read-model page has no read position; the reader-scoped unread count places the divider. */
    private fun firstUnread(): String? {
        if (unreadAtOpen <= 0) return null
        var remaining = unreadAtOpen
        for (index in window.indices.reversed()) {
            val row = window[index] as? MessageItem ?: continue
            if (row.own || row.deleted) continue
            remaining--
            if (remaining == 0L) return row.id
        }
        return null
    }

    private fun resetTimeline() {
        olderJob?.cancel()
        downloads.values.forEach { it.cancel() }
        downloads.clear()
        references.clear()
        located.clear()
        transfers.clear()
        window.clear()
        older = null
        detached = false
        outbox.clear()
        lastMarked = null
        markRefused = false
        unreadAtOpen = 0
        dividerPlaced = false
    }

    private fun scheduleDraftSave() {
        val currentTarget = target ?: return
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            drafts.save(currentTarget.account, currentTarget.conversation, local.value.draft)
        }
    }

    /** Seals the current draft now (pause, lock, leaving the conversation). Edit text is not a draft. */
    private fun sealDraft() {
        val currentTarget = target ?: return
        draftJob?.cancel()
        val state = local.value
        if (state.composer == ComposerMode.EDIT || state.conversationId != currentTarget.conversation) return
        val text = state.draft
        viewModelScope.launch(NonCancellable) { drafts.save(currentTarget.account, currentTarget.conversation, text) }
    }

    private fun restoreDraft(next: Target) {
        viewModelScope.launch {
            val text = drafts.load(next.account, next.conversation) ?: return@launch
            if (target == next && session.status.value.phase == ClientPhase.OPEN && local.value.draft.isEmpty()) {
                local.update { it.copy(draft = text) }
            }
        }
    }

    private fun mutate(id: String?, call: suspend (String, String, String, String) -> ClientResult<Unit>) {
        val currentTarget = target ?: return
        val messageId = id ?: return
        if (session.status.value.phase != ClientPhase.OPEN || request?.isActive == true) return
        val operation = UUID.randomUUID().toString()
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(sending = true, problem = null, selectedId = null) }
        request = viewModelScope.launch {
            try {
                if (!validate(expected, generation, currentTarget)) return@launch
                val result = call(currentTarget.account, currentTarget.conversation, messageId, operation)
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                applyUnit(result, clearDraft = false)
            } finally {
                finish(expected)
            }
        }
    }
    private fun applyUnit(result: ClientResult<Unit>, clearDraft: Boolean) {
        when (result) {
            is ClientResult.Ready -> {
                local.update {
                    it.copy(
                        draft = if (clearDraft) "" else it.draft,
                        composer = ComposerMode.SEND,
                        actionTarget = null,
                        expectedRevision = 0,
                        selectedId = null,
                        deliveryNotice = uiText(R.string.ui_delivery_submitted)
                    )
                }
                needsRefresh = true
            }
            is ClientResult.Unavailable -> local.update {
                it.copy(unavailable = uiText(R.string.ui_sending_unavailable_draft_kept))
            }
            is ClientResult.Failed -> local.update {
                it.copy(
                    problem = actionFailure(result.problem),
                    deliveryNotice = failedNotice(result.problem)
                )
            }
        }
    }
    private fun finish(expected: Long) {
        if (expected != ticket) return
        local.update { it.copy(loading = false, sending = false) }
        if (needsRefresh) {
            needsRefresh = false
            read()
        }
    }
    private fun ensureWatch(current: Target) {
        if (watchTarget == current && watch?.isActive == true) return
        watch?.cancel()
        watchTarget = current
        watch = viewModelScope.launch {
            var again = true
            while (again && currentCoroutineContext().isActive) {
                again = false
                history.updates(current.account).collect { result ->
                    if (target != current || session.status.value.phase != ClientPhase.OPEN) return@collect
                    if (result is ClientResult.Ready) {
                        result.value.peerTyping?.let { typing -> applyPeerTyping(current, typing) }
                        if (result.value.refresh) {
                            if (request?.isActive == true) needsRefresh = true else read()
                        }
                        if (result.value.resubscribe) again = true
                    }
                }
            }
        }
    }
    private fun applyPeerTyping(current: Target, typing: PeerTyping) {
        if (typing.conversationId != current.conversation) return
        val now = System.currentTimeMillis()
        val visible = typing.active && now < typing.expiresAtMs
        local.update { it.copy(peerTyping = visible) }
        typingExpiry?.cancel()
        if (!visible) {
            typingExpiry = null
            return
        }
        val wait = typing.expiresAtMs - now
        typingExpiry = viewModelScope.launch {
            delay(wait)
            if (target == current) local.update { it.copy(peerTyping = false) }
        }
    }

    private fun clearPeerTypingClock() {
        typingExpiry?.cancel()
        typingExpiry = null
    }

    private fun syncTyping(active: Boolean) {
        val current = target ?: return
        if (typingRefused || typingActive == active) return
        typingActive = active
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            val result = history.setTyping(current.account, current.conversation, active)
            if (target != current || session.status.value.phase != ClientPhase.OPEN) return@launch
            val denied = result is ClientResult.Failed &&
                result.problem.kind == ClientFailureKind.AUTHORIZATION
            if (result !is ClientResult.Ready && !denied) {
                typingRefused = true
                typingActive = false
            } else if (denied) {
                typingActive = false
            }
            local.update { it.copy(typingProblem = typingNotice(result)) }
        }
    }
    private fun stopTyping() {
        val current = target
        val wasActive = typingActive
        typingActive = false
        typingJob?.cancel()
        typingJob = null
        if (wasActive && current != null && !typingRefused) {
            val account = current.account
            val conversation = current.conversation
            viewModelScope.launch { history.setTyping(account, conversation, false) }
        }
    }
    private fun itemOf(row: TimelineSummary): TimelineItem = when (row) {
        is CallSummary -> CallItem(row.id, row.createdAt)
        is MessageSummary -> messageItem(row)
    }

    private fun messageItem(row: MessageSummary): MessageItem {
        val content = when {
            row.deleted -> uiText(R.string.ui_message_deleted)
            row.placeholder -> uiText(R.string.ui_message_placeholder)
            row.service && row.text == null -> uiText(R.string.ui_service_message)
            else -> row.text?.let { UiText.Data(it) } ?: uiText(R.string.ui_message_content_is_unavailable)
        }
        return MessageItem(
            row.id,
            if (row.deleted) "" else row.sender ?: "",
            row.own,
            row.deleted,
            content,
            row.createdAt,
            row.revision,
            row.edited,
            row.replyPreview?.let { UiText.Data(it) },
            row.placeholder,
            row.service,
            receiptLabel(row.receipts),
            row.reactions.map { ReactionCount(it.token, it.count) }.toPersistentList(),
            attachments = if (row.deleted) persistentListOf() else row.attachments.map { attachmentItem(it, row.own) }.toPersistentList()
        )
    }

    /** Records the attachment references of new rows, purges erased ones and looks up held files. */
    private fun remember(rows: List<TimelineSummary>) {
        val currentTarget = target ?: return
        val fresh = ArrayList<MediaAttachment>()
        rows.forEach { row ->
            val message = row as? MessageSummary ?: return@forEach
            if (message.deleted) {
                if (references.values.any { it.messageId == message.id }) {
                    references.keys.removeAll { it.startsWith(message.id + "/") }
                    viewModelScope.launch { media.purge(currentTarget.account, message.id) }
                }
                return@forEach
            }
            message.attachments.forEach { attachment ->
                val key = keyOf(attachment)
                if (references.put(key, attachment) == null) fresh += attachment
            }
        }
        if (fresh.isEmpty()) return
        viewModelScope.launch {
            val files = media.locate(currentTarget.account, fresh)
            if (target != currentTarget) return@launch
            var changed = false
            fresh.forEachIndexed { index, attachment ->
                files.getOrNull(index)?.let {
                    located[keyOf(attachment)] = it.path
                    changed = true
                }
            }
            if (changed) refreshAttachments(fresh.mapTo(HashSet()) { it.messageId })
        }
    }

    private fun attachmentItem(attachment: MediaAttachment, own: Boolean): AttachmentItem {
        val key = keyOf(attachment)
        val path = located[key]
        val status = when (val transfer = transfers[key]) {
            null -> when {
                path != null -> AttachmentStatus.READY
                !own && attachment.availableUntil?.let { System.currentTimeMillis() / 1000 > it } == true -> AttachmentStatus.EXPIRED
                else -> AttachmentStatus.AVAILABLE
            }
            is TransferState.Ready -> AttachmentStatus.READY
            is TransferState.Downloading, TransferState.Queued -> AttachmentStatus.LOADING
            TransferState.Expired -> AttachmentStatus.EXPIRED
            TransferState.Revoked -> AttachmentStatus.REVOKED
            TransferState.NotGrantee -> AttachmentStatus.NOT_ON_DEVICE
            TransferState.BlockedByPolicy -> AttachmentStatus.HIDDEN
            else -> AttachmentStatus.FAILED
        }
        return AttachmentItem(
            attachment.messageId, attachment.index, kindOf(attachment.declaredMime), attachment.fileName, attachment.size,
            attachment.aspectRatio, status, path, attachment.declaredMime
        )
    }

    /** Re-maps only the rows whose attachments changed. */
    private fun refreshAttachments(messages: Set<String>) {
        for (index in window.indices) {
            val row = window[index] as? MessageItem ?: continue
            if (row.id !in messages) continue
            val items = references.values.filter { it.messageId == row.id }.sortedBy { it.index }
            window[index] = row.copy(attachments = items.map { attachmentItem(it, row.own) }.toPersistentList())
        }
        val viewer = local.value.viewer
        if (viewer != null) {
            val key = viewer.key
            references[key]?.let { reference ->
                local.update { it.copy(viewer = attachmentItem(reference, false).takeIf { item -> item.status == AttachmentStatus.READY }) }
            }
        }
        publish()
    }

    private fun openAttachment(key: String) {
        val currentTarget = target ?: return
        val attachment = references[key] ?: return
        val item = attachmentItem(attachment, false)
        when (item.status) {
            AttachmentStatus.READY -> {
                val path = item.localPath ?: return
                if (item.kind == AttachmentKind.IMAGE || item.kind == AttachmentKind.ANIMATED) {
                    local.update { it.copy(viewer = item) }
                } else {
                    viewModelScope.launch { channel.send(ChatEvent.File(path, item.mime, item.fileName, FileUse.OPEN)) }
                }
            }
            AttachmentStatus.AVAILABLE, AttachmentStatus.FAILED, AttachmentStatus.HIDDEN -> download(currentTarget, attachment)
            else -> Unit
        }
    }

    private fun download(currentTarget: Target, attachment: MediaAttachment) {
        val key = keyOf(attachment)
        if (downloads[key]?.isActive == true || session.status.value.phase != ClientPhase.OPEN) return
        transfers[key] = TransferState.Queued
        refreshAttachments(setOf(attachment.messageId))
        downloads[key] = viewModelScope.launch {
            val result = media.download(currentTarget.account, attachment)
            if (target != currentTarget) return@launch
            if (result is TransferState.Ready) {
                located[key] = result.file.path
                transfers.remove(key)
            } else {
                transfers[key] = result
            }
            if (result == TransferState.Revoked) media.purge(currentTarget.account, attachment.messageId)
            refreshAttachments(setOf(attachment.messageId))
        }
    }

    private fun handOver(key: String, use: FileUse) {
        val item = references[key]?.let { attachmentItem(it, false) } ?: return
        val path = item.localPath ?: return
        if (item.status != AttachmentStatus.READY) return
        viewModelScope.launch { channel.send(ChatEvent.File(path, item.mime, item.fileName, use)) }
    }

    private fun stage(uri: String, image: Boolean) {
        val currentTarget = target ?: return
        if (session.status.value.phase != ClientPhase.OPEN || outbox.size >= OUTBOX_LIMIT) return
        viewModelScope.launch {
            val staged = if (image) stager.image(currentTarget.account, uri) else stager.file(currentTarget.account, uri)
            if (target != currentTarget) {
                staged?.file?.delete()
                return@launch
            }
            if (staged == null) {
                local.update { it.copy(problem = uiText(R.string.chat_media_not_staged)) }
                return@launch
            }
            val operation = UUID.randomUUID().toString()
            val id = LOCAL_PREFIX + operation
            outbox[id] = Outgoing(operation, "", System.currentTimeMillis() / 1000, LocalDelivery.SENDING, staged = staged)
            publish()
            deliver(currentTarget, id)
        }
    }

    private suspend fun current(expected: Long, generation: Long, expectedTarget: Target) {
        currentCoroutineContext().ensureActive()
        if (expected != ticket || generation != session.status.value.generation ||
            session.status.value.phase != ClientPhase.OPEN || target != expectedTarget
        ) {
            throw CancellationException("retired chat")
        }
    }
}

private data class Outgoing(
    val operation: String,
    val text: String,
    val sentAt: Long,
    var delivery: LocalDelivery,
    var definite: Boolean = false,
    val staged: StagedMedia? = null
) {
    override fun toString() = "Outgoing(<redacted>)"
}

/** Status names of failures that mean "no live session"; such sends wait in the queue. */
private val OFFLINE = setOf("authority_unavailable", "unavailable", "offline", "closed")
private const val LOCAL_PREFIX = "local:"

private fun keyOf(attachment: MediaAttachment) = attachment.messageId + "/" + attachment.index

private fun kindOf(mime: String) = when (MediaAttachment.kindOf(mime)) {
    MediaKind.IMAGE -> AttachmentKind.IMAGE
    MediaKind.ANIMATED -> AttachmentKind.ANIMATED
    MediaKind.VIDEO -> AttachmentKind.VIDEO
    MediaKind.FILE -> AttachmentKind.FILE
}
private const val OUTBOX_LIMIT = 64
private const val WINDOW = 1_000
private const val DRAFT_DEBOUNCE_MS = 400L

private object NoChatDrafts : ChatDraftRepository {
    override suspend fun load(account: String, conversation: String): String? = null
    override suspend fun save(account: String, conversation: String, text: String): ClientResult<Unit> = ClientResult.Ready(Unit)
    override suspend fun clear(account: String, conversation: String) = Unit
    override suspend fun clearAccount(account: String) = Unit
}

private fun receiptLabel(receipts: List<MessageReceipt>): Receipt? {
    val state = receipts.maxByOrNull { rank(it.state) }?.state ?: return null
    return when (state) {
        "read" -> Receipt.READ
        "delivered" -> Receipt.DELIVERED
        "sent" -> Receipt.SENT
        else -> null
    }
}

private fun rank(state: String) = when (state) {
    "read" -> 3
    "delivered" -> 2
    "sent" -> 1
    else -> 0
}

private fun sendFailure(problem: ClientProblem): UiText = when (problem.outcome) {
    ClientOutcome.UNKNOWN -> uiText(R.string.ui_send_outcome_is_unknown_refresh_before_making_another)
    ClientOutcome.NONE, ClientOutcome.REFUSED -> coded(
        problem,
        R.string.ui_the_message_was_not_sent_code_value,
        R.string.ui_the_message_was_not_sent_status_value
    )
}

private fun actionFailure(problem: ClientProblem): UiText = when (problem.outcome) {
    ClientOutcome.UNKNOWN -> uiText(R.string.ui_send_outcome_is_unknown_refresh_before_making_another)
    ClientOutcome.NONE, ClientOutcome.REFUSED -> coded(
        problem,
        R.string.ui_action_failed_code_value,
        R.string.ui_action_failed_status_value
    )
}

private fun failedNotice(problem: ClientProblem): UiText? =
    if (problem.outcome == ClientOutcome.UNKNOWN) null else uiText(R.string.ui_delivery_failed)

private fun typingNotice(result: ClientResult<Unit>): UiText? = when (result) {
    is ClientResult.Ready -> null
    is ClientResult.Unavailable -> uiText(R.string.ui_typing_unavailable)
    is ClientResult.Failed -> if (result.problem.kind == ClientFailureKind.AUTHORIZATION) {
        null
    } else {
        uiText(R.string.ui_typing_unavailable)
    }
}

private fun coded(problem: ClientProblem, codeOnly: Int, withStatus: Int): UiText {
    val code = UiText.Number(problem.code.toLong())
    val status = problem.statusName
    return if (status.isNullOrEmpty()) uiText(codeOnly, code) else uiText(withStatus, UiText.Data(status), code)
}

private fun UnavailableReason.description(): UiText = when (this) {
    UnavailableReason.FEATURE_NOT_INSTALLED -> uiText(R.string.ui_the_feature_is_not_installed)
    UnavailableReason.UNSUPPORTED_BY_CORE -> uiText(R.string.ui_the_sdk_operation_is_not_available_in_this)
    UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> uiText(R.string.ui_secure_account_access_is_required_on_this_device)
}

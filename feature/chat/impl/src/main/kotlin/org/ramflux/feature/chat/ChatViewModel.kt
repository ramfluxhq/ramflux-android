// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive
import org.ramflux.data.*
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardReply
import org.ramflux.data.cards.CardSessionRepository
import org.ramflux.data.cards.CardSlotOutcome
import org.ramflux.data.cards.CardState
import org.ramflux.data.cards.CardSurfaceRef
import org.ramflux.data.cards.UnavailableCardSessionRepository
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.failureText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val history: MessageHistoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository,
    private val drafts: ChatDraftRepository,
    private val media: MediaRepository,
    private val stager: MediaStager,
    private val queue: MediaTransfers,
    private val cards: CardSessionRepository,
    private val coordinator: ChatOutboxCoordinator = noOutbox(),
    private val identity: ContactIdentityRepository = UnavailableContactIdentity
) : ViewModel() {
    /** For hosts without an application-wide transfer queue: downloads then end with this chat. */
    constructor(
        history: MessageHistoryRepository,
        accounts: AccountAccessRepository,
        session: ClientSessionRepository,
        drafts: ChatDraftRepository,
        media: MediaRepository,
        stager: MediaStager
    ) : this(history, accounts, session, drafts, media, stager, localQueue(media, session), UnavailableCardSessionRepository())

    /** For hosts that keep no drafts and no media (tests and previews). */
    constructor(
        history: MessageHistoryRepository,
        accounts: AccountAccessRepository,
        session: ClientSessionRepository,
        drafts: ChatDraftRepository = NoChatDrafts,
        identity: ContactIdentityRepository = UnavailableContactIdentity
    ) : this(
        history, accounts, session, drafts, UnavailableMediaRepository(), NoMediaStager, localQueue(UnavailableMediaRepository(), session),
        UnavailableCardSessionRepository(), noOutbox(), identity
    )

    private data class Target(val account: String, val conversation: String, val group: String?)
    private var target: Target? = null

    /** The loaded window in reading order (oldest first), mapped once per page, bounded by [WINDOW]. */
    private val window = ArrayList<TimelineItem>()
    private var older: String? = null

    /** Rows were trimmed from the newest end; live pages are not merged until the window is reset. */
    private var detached = false
    private var olderJob: Job? = null
    private val outbox = LinkedHashMap<String, Outgoing>()

    /** The target whose sealed outbox was read back; until then nothing is written over it. */
    private var outboxRestored: Target? = null
    private var persistedOutbox: List<QueuedSend>? = null

    /** The SDK reported sending available for the open conversation (queued sends may go). */
    private var sendReady = false
    private var lastMarked: String? = null
    private var markRefused = false
    private var unreadAtOpen = 0L
    private var dividerPlaced = false
    private var draftJob: Job? = null

    /**
     * The target whose sealed draft is known: restored from the store, or typed since the state was reset. Until then the
     * composer is empty only because the draft has not been read yet, and sealing it would erase the stored draft (a blank
     * save deletes it): a session transition while the restore is in flight lost the draft across background and lock.
     */
    private var draftKnown: Target? = null

    /** Attachment references of the window by key, the files this device holds, and transfers in flight. */
    private val references = HashMap<String, MediaAttachment>()
    private val located = HashMap<String, String>()
    private val transfers = HashMap<String, TransferState>()
    private var transferWatch: Job? = null

    /** Attachments the gallery loaded beyond the timeline window, by key, in gallery order. */
    private val galleryRefs = LinkedHashMap<String, MediaAttachment>()
    private val galleryMedia = ArrayList<String>()
    private val galleryFiles = ArrayList<String>()
    private var galleryCursor: String? = null
    private var galleryJob: Job? = null

    /** Agent surfaces of the conversation by reference, in page order; each card's state is held by the card runtime. */
    private val cardRefs = LinkedHashMap<String, CardSurfaceRef>()
    private var cardJob: Job? = null

    /** The last control per card whose event the runtime dropped as stale; the renderer reverts that field. */
    private val cardStale = HashMap<String, Pair<String, Long>>()

    /** When each card last said an input was not applied (monotonic nanoseconds); the notice is shown at most once per interval. */
    private val inputNoticeAt = HashMap<String, Long>()

    /** The locale and theme the cards draw with, as the screen reports them. */
    private var cardLook = CardLook("en", false)

    init {
        // A card that changed (signed update, lifecycle, sync) redraws the timeline.
        viewModelScope.launch { cards.revision.drop(1).collect { if (target != null) publish() } }
    }

    /** Staged items of the open send preview, in the order they will be sent. */
    private val previewItems = ArrayList<StagedMedia>()
    private var previewJob: Job? = null

    /** What the open forward chooser re-sends: one message, or one item of it. */
    private data class Forwarding(val target: Target, val messageId: String, val index: Int?, val cursor: String?)
    private var forwarding: Forwarding? = null
    private var forwardJob: Job? = null
    private val local = MutableStateFlow(ChatState())
    private val channel = Channel<ChatEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    /** The identity change sheet; its accept runs only from [ChatAction.AcceptIdentity]. */
    private val review =
        IdentityReview(identity, viewModelScope) { account, contact, outcome, anchor ->
            reviewOutcome(account, contact, outcome, anchor)
        }

    /** The direct peer's contact identifier of the open conversation, as the header reported it. */
    private var peerRoot: String? = null

    /** A blocked direct send tapped before the header named the peer: it is retried once the header read has run. */
    private var pendingRetry: String? = null

    /** The change the peer's last `contact.identity_changed` event (or status read) delivered and the person has not accepted. */
    private var pendingChange: IdentityChange? = null

    /** Local service lines (identity changed, accepted, fork recovered), in arrival order; never message rows. */
    private val serviceLines = ArrayList<MessageItem>()
    private val lineKeys = HashSet<Any>()
    val state = combine(local, session.status, review.state) { screen, status, sheet ->
        if (status.phase == ClientPhase.OPEN) {
            screen.copy(
                review = sheet
            )
        } else {
            ChatState(problem = uiText(R.string.ui_private_data_is_closed))
        }
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

    /**
     * The peer's header title as last read for [Target]. A session phase change resets [local], and the re-read
     * after it keeps this title until the header names the conversation again, so the generic title never flashes.
     */
    private var namedHeader: Pair<Target, UiText>? = null
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            // Lock or session loss: seal the draft before the in-memory state is dropped.
            sealDraft()
            review.close()
            stager.dropMemory()
            request?.cancel()
            watch?.cancel()
            watchTarget = null
            identityWatch?.cancel()
            identityTarget = null
            clearPeerTypingClock()
            stopTyping()
            typingRefused = false
            ticket++
            resetTimeline()
            local.value = ChatState()
            val openTarget = target
            if (phase == ClientPhase.OPEN && openTarget != null) {
                local.value = ChatState(
                    conversationId = openTarget.conversation,
                    title = namedHeader?.takeIf { it.first == openTarget }?.second
                )
                ensureWatch(openTarget)
                restoreDraft(openTarget)
                restoreOutbox(openTarget)
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
                    review.close()
                    request?.cancel()
                    watch?.cancel()
                    watchTarget = null
                    identityWatch?.cancel()
                    identityTarget = null
                    clearPeerTypingClock()
                    stopTyping()
                    typingRefused = false
                    ticket++
                    resetTimeline()
                    target = next
                    local.value = ChatState(
                        conversationId = next.conversation,
                        title = action.title?.takeIf { it.isNotBlank() }?.let { UiText.Data(it) }
                    )
                    ensureWatch(next)
                    restoreDraft(next)
                    restoreOutbox(next)
                    read()
                }
            }
            ChatAction.Refresh -> if (request?.isActive != true) read()
            is ChatAction.Draft -> if (!local.value.sending && target != null && session.status.value.phase == ClientPhase.OPEN) {
                val text = action.text.take(65536)
                local.update { it.copy(draft = text) }
                if (local.value.composer != ComposerMode.EDIT) draftKnown = target
                if (local.value.composer == ComposerMode.SEND) syncTyping(text.isNotBlank())
                if (local.value.composer != ComposerMode.EDIT) scheduleDraftSave()
            }
            ChatAction.Send -> if (local.value.draft.isNotBlank()) {
                if (local.value.composer == ComposerMode.SEND) {
                    enqueue()
                } else if (!local.value.sending) {
                    // A timeline refresh still running (events arrive often) must not swallow a reply or an edit: it
                    // is cancelled and read again after the change.
                    if (request?.isActive == true) needsRefresh = true
                    request?.cancel()
                    compose(local.value.composer)
                }
            }
            ChatAction.Back -> {
                sealDraft()
                request?.cancel()
                watch?.cancel()
                watchTarget = null
                identityWatch?.cancel()
                identityTarget = null
                review.close()
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
            is ChatAction.CardInteract -> cardInteract(action)
            is ChatAction.CardAnswer -> cardAnswer(action)
            is ChatAction.CardShow -> cardShow(action.ref)
            is ChatAction.CardResync -> cardResync(action.ref)
            is ChatAction.CardAppearance -> cardLookChanged(action.locale, action.dark)
            ChatAction.Pause -> sealDraft()
            ChatAction.ReviewIdentity -> openReview()
            ChatAction.AcceptIdentity -> review.accept()
            ChatAction.LaterIdentity -> review.later()
            is ChatAction.OpenAttachment -> openAttachment(action.key)
            ChatAction.CloseViewer -> local.update { it.copy(viewer = null, viewerPosition = null) }
            is ChatAction.SaveAttachment -> handOver(action.key, FileUse.SAVE)
            is ChatAction.ShareAttachment -> handOver(action.key, FileUse.SHARE)
            is ChatAction.Pick -> if (target != null && local.value.canSend) {
                viewModelScope.launch { channel.send(ChatEvent.Pick(action.source)) }
            }
            is ChatAction.Picked -> if (action.source == PickSource.PHOTO) {
                preview(action.uris)
            } else {
                action.uris.firstOrNull()?.let { stage(it, action.source) }
            }
            is ChatAction.PreviewRemove -> previewRemove(action.index)
            is ChatAction.PreviewMove -> previewMove(action.index)
            is ChatAction.PreviewCaption -> local.update { it.copy(preview = it.preview?.copy(caption = action.text.take(65536))) }
            ChatAction.PreviewSend -> previewSend()
            ChatAction.PreviewCancel -> previewCancel()
            is ChatAction.ViewerStep -> viewerStep(action.delta)
            is ChatAction.Forward -> openForward(action.messageId ?: local.value.selectedId, action.index)
            ChatAction.ForwardMore -> loadForwardTargets(more = true)
            is ChatAction.ForwardTo -> forwardTo(action.conversation)
            ChatAction.CloseForward -> closeForward()
            ChatAction.OpenGallery -> openGallery()
            ChatAction.GalleryMore -> loadGallery()
            is ChatAction.GalleryTab -> local.update { it.copy(gallery = it.gallery?.copy(tab = action.tab)) }.also { publishGallery() }
            ChatAction.CloseGallery -> closeGallery()
            ChatAction.Storage -> if (target != null) viewModelScope.launch { channel.send(ChatEvent.Storage) }
            // The MLS group chat's actions: a direct chat has no confirmation or mention picker of this kind.
            ChatAction.ConfirmDelete, ChatAction.CancelDelete, ChatAction.MentionOpen, ChatAction.MentionMore, ChatAction.MentionClose,
            is ChatAction.MentionPick -> Unit
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
                    problem = failureText(
                        R.string.ui_account_state_could_not_be_read_code_value,
                        catalog.problem.code, catalog.problem.statusName, catalog.problem.check
                    ),
                    draft = local.value.draft
                )
                return false
            }
            is ClientResult.Unavailable -> {
                local.value = ChatState(
                    unavailable = uiText(R.string.ui_account_access_is_unavailable_value, catalog.reason.description()),
                    draft = local.value.draft
                )
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
            // Right after an unlock the same account can read as not yet open for a moment; its draft is kept so the next
            // successful read restores the timeline around the same composer text. Another active account drops it.
            val sameAccount = selected != null && (value.activeId == null || value.activeId == expectedTarget.account)
            local.value = ChatState(problem = uiText(notice), draft = if (sameAccount) local.value.draft else "")
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
                    local.value = ChatState(problem = uiText(R.string.ui_conversation_details_unavailable), draft = local.value.draft)
                    return@launch
                }
                val result = history.page(currentTarget.account, currentTarget.conversation, null)
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                val header = groupHeader ?: history.header(currentTarget.account, currentTarget.conversation)
                current(expected, generation, currentTarget)
                if (!validate(expected, generation, currentTarget)) return@launch
                if (!dividerPlaced) unreadAtOpen = (header as? ClientResult.Ready)?.value?.unread ?: 0L
                val named = (header as? ClientResult.Ready)?.value?.title?.takeIf { title -> title.isNotBlank() }?.let { title ->
                    UiText.Data(title)
                }
                named?.let { namedHeader = currentTarget to it }
                val facts = (header as? ClientResult.Ready)?.value
                if (currentTarget.group == null) {
                    peerRoot = facts?.peer
                    pendingRetry?.let { waiting ->
                        pendingRetry = null
                        if (peerRoot != null) retry(waiting)
                    }
                    facts?.peer?.let { watchIdentity(currentTarget, it) }
                }
                local.update {
                    it.copy(
                        peerName = named?.value ?: it.peerName,
                        groupId = (groupHeader as? ClientResult.Ready)?.value?.groupId,
                        title = named ?: it.title,
                        titleAi = (header as? ClientResult.Ready)?.value?.ai == true,
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
                                    is ClientResult.Failed -> failureText(
                                        R.string.ui_send_availability_failed_code_value,
                                        capability.problem.code, capability.problem.statusName, capability.problem.check
                                    )
                                    is ClientResult.Ready, is ClientResult.Unavailable -> null
                                }
                            )
                        }
                        publish()
                        loadCards(currentTarget)
                        sendReady = capability is ClientResult.Ready
                        if (sendReady) resendQueued()
                    }
                    is ClientResult.Failed -> local.update {
                        it.copy(
                            problem = failureText(
                                R.string.ui_read_failed_code_value, result.problem.code, result.problem.statusName, result.problem.check
                            )
                        )
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
            // The coordinator runs the send in the application scope and records a refusal in the store before it returns, so
            // nothing below is needed for the refusal to survive this screen.
            val sent = if (staged.isEmpty()) {
                coordinator.send(currentTarget.account, currentTarget.conversation, id, outgoing.operation) {
                    history.send(currentTarget.account, currentTarget.conversation, outgoing.text, outgoing.operation)
                }
            } else {
                // The application queue owns the upload, so it goes on when this chat closes.
                coordinator.send(currentTarget.account, currentTarget.conversation, id, outgoing.operation) {
                    queue.upload(
                        currentTarget.account,
                        MediaDestination(currentTarget.conversation, currentTarget.group),
                        outgoing.text,
                        staged,
                        outgoing.operation
                    )
                }
            }
            if (target != currentTarget || generation != session.status.value.generation) {
                if (target == currentTarget && sent is ClientResult.Failed && SendBlock.of(sent.problem) != null) {
                    markBlocked(
                        outgoing.operation
                    )
                }
                return@launch
            }
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
                    val problem = sent.problem
                    clientDebugEvent(
                        listOf(
                            "chat.send",
                            if (offline) "queued" else "failed",
                            "media:" + staged.size,
                            "kind:" + problem.kind,
                            "code:" + problem.code,
                            "status:" + problem.statusName,
                            "check:" + problem.check,
                            "outcome:" + problem.outcome
                        ).joinToString("/")
                    )
                    // A send refused until the identity is reviewed stays as a failed bubble with its text, however the refusal
                    // was classified, and the block is persisted with it: nothing is sent again by itself, before or after the
                    // review, after a reopen, unlock or relaunch. Only an explicit user resend sends it.
                    val block = SendBlock.of(sent.problem, peerRoot)
                    outgoing.blocked = block != null
                    outgoing.delivery = if (offline && block == null) LocalDelivery.QUEUED else LocalDelivery.FAILED
                    outgoing.definite = sent.problem.outcome != ClientOutcome.UNKNOWN
                    if (block != null) blockedByIdentity(currentTarget, block)
                    local.update {
                        it.copy(
                            problem = when {
                                block != null -> null
                                offline -> null
                                staged.isNotEmpty() -> mediaFailure(sent.problem) ?: sendFailure(sent.problem)
                                else -> sendFailure(sent.problem)
                            },
                            deliveryNotice = if (offline || block != null) null else failedNotice(sent.problem)
                        )
                    }
                    publish()
                }
            }
        }
    }

    /**
     * A refusal that reached this screen after its generation changed (lock and unlock) blocks every row of that operation,
     * including the copy restored after the unlock. The store was already written by the coordinator.
     */
    private fun markBlocked(operation: String) {
        var changed = false
        for (outgoing in outbox.values) {
            if (outgoing.operation != operation) continue
            outgoing.blocked = true
            outgoing.delivery = LocalDelivery.FAILED
            changed = true
        }
        if (changed) publish()
    }

    private fun retry(id: String) {
        val currentTarget = target ?: return
        val outgoing = outbox[id] ?: return
        if (outgoing.delivery == LocalDelivery.SENDING || session.status.value.phase != ClientPhase.OPEN) return
        if (outgoing.blocked) {
            // A blocked send is resent only after the identity change was accepted. While the state is changed, or not read yet,
            // the tap sends nothing: it shows the composer prompt and opens the review, or reads the status first and sends
            // only if that read is not changed. A read that fails keeps the send held.
            val root = peerRoot
            when (local.value.identityState) {
                IdentityState.CHANGED -> holdBlocked()
                IdentityState.UNKNOWN -> if (root == null) {
                    if (currentTarget.group != null) {
                        // No peer identity to read (not a direct chat): the explicit tap sends, and the SDK stays the gate.
                        resend(currentTarget, id)
                    } else {
                        // A direct chat whose header has not named the peer yet: nothing is sent before the identity can be read.
                        pendingRetry = id
                        if (request?.isActive == true) needsRefresh = true else read()
                    }
                } else {
                    readIdentity(currentTarget, root, afterAccept = false) { read ->
                        if (read == null || read == IdentityState.CHANGED) holdBlocked() else resend(currentTarget, id)
                    }
                }
                else -> resend(currentTarget, id)
            }
            return
        }
        resend(currentTarget, id)
    }

    /** A blocked send that may not go yet: the composer prompt shows, and the review opens once a change is known. */
    private fun holdBlocked() {
        local.update { it.copy(identityState = IdentityState.CHANGED, sendBlocked = true) }
        openReview()
    }

    private fun resend(currentTarget: Target, id: String) {
        val outgoing = outbox[id] ?: return
        if (outgoing.delivery == LocalDelivery.SENDING || session.status.value.phase != ClientPhase.OPEN) return
        // An uncertain outcome is retried under the same operation id so the SDK can reconcile it.
        // A refusal is a definite non-send and its operation is never sent again, so a blocked row always gets a new one.
        val again = if (outgoing.definite || outgoing.blocked) outgoing.copy(operation = UUID.randomUUID().toString()) else outgoing
        again.delivery = LocalDelivery.SENDING
        again.blocked = false
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
                        it.copy(
                            problem = failureText(
                                R.string.ui_read_failed_code_value, result.problem.code, result.problem.statusName, result.problem.check
                            )
                        )
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
            // A local send is never a failed download: it is uploading, waiting for the connection or refused.
            val status = when (outgoing.delivery) {
                LocalDelivery.SENDING -> AttachmentStatus.SENDING
                LocalDelivery.QUEUED -> AttachmentStatus.QUEUED
                LocalDelivery.FAILED -> AttachmentStatus.UNSENT
            }
            val attachments = outgoing.staged.mapIndexed { index, staged ->
                AttachmentItem(
                    id, index, kindOf(staged.mime), staged.fileName, staged.size, MediaAttachment.DEFAULT_RATIO,
                    status, staged.file.path, staged.mime
                )
            }.toPersistentList()
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
                rows = withCards(window + serviceLines + pending).toPersistentList(),
                hasOlder = older != null,
                queued = outbox.values.any { item -> item.delivery == LocalDelivery.QUEUED }
            )
        }
        persistOutbox()
    }

    /**
     * Reads the sealed outbox of [next] back after open, unlock or relaunch: each send not admitted before comes back
     * queued under its original operation id and goes again once sending is available, so the SDK reconciles a send
     * that was in flight. Staged files that are gone are dropped.
     */
    private fun restoreOutbox(next: Target) {
        viewModelScope.launch {
            val stored = coordinator.load(next.account, next.conversation)
            if (target != next || session.status.value.phase != ClientPhase.OPEN) return@launch
            for (send in stored) {
                if (send.id in outbox || outbox.size >= OUTBOX_LIMIT) continue
                val staged = send.media.map {
                    File(it.path) to it
                }.filter { it.first.isFile }.map { (file, item) -> StagedMedia(file, item.fileName, item.mime) }
                if (staged.isEmpty() && send.media.isNotEmpty() && send.text.isBlank()) continue
                // A send the SDK refused for a pending identity change comes back blocked and failed, never queued.
                val delivery = if (send.blocked) LocalDelivery.FAILED else LocalDelivery.QUEUED
                outbox[send.id] = Outgoing(send.operation, send.text, send.sentAt, delivery, send.definite, staged, send.blocked)
            }
            outboxRestored = next
            publish()
            if (sendReady) resendQueued()
        }
    }

    /** Writes the outbox of the open conversation, sealed, whenever it changed (every change ends in [publish]). */
    private fun persistOutbox() {
        val current = target ?: return
        if (outboxRestored != current) return
        val snapshot = outbox.map { (id, outgoing) ->
            QueuedSend(
                id,
                outgoing.operation,
                outgoing.text,
                outgoing.sentAt,
                outgoing.definite,
                outgoing.staged.map {
                    QueuedMedia(it.file.path, it.fileName, it.mime)
                },
                outgoing.blocked
            )
        }
        if (snapshot == persistedOutbox) return
        persistedOutbox = snapshot
        coordinator.persist(current.account, current.conversation, snapshot)
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
        previewCancel()
        cardJob?.cancel()
        // The held cards of the leaving timeline are released; the runtime keeps no card for a screen that left.
        val leaving = target?.account
        val held = cardRefs.keys.toList()
        cardRefs.clear()
        if (leaving != null && held.isNotEmpty()) viewModelScope.launch { held.forEach { cards.dispose(leaving, it) } }
        cardStale.clear()
        inputNoticeAt.clear()
        olderJob?.cancel()
        forwardJob?.cancel()
        forwarding = null
        closeGallery()
        references.clear()
        located.clear()
        transfers.clear()
        window.clear()
        older = null
        detached = false
        outbox.clear()
        outboxRestored = null
        draftKnown = null
        persistedOutbox = null
        sendReady = false
        lastMarked = null
        markRefused = false
        unreadAtOpen = 0
        dividerPlaced = false
        peerRoot = null
        pendingRetry = null
        pendingChange = null
        serviceLines.clear()
        lineKeys.clear()
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
        if (state.composer == ComposerMode.EDIT || state.conversationId != currentTarget.conversation) {
            clientDebugEvent("chat.draft.seal.skipped")
            return
        }
        if (draftKnown != currentTarget) {
            clientDebugEvent("chat.draft.seal.unknown")
            return
        }
        val text = state.draft
        viewModelScope.launch(NonCancellable) {
            val saved = drafts.save(currentTarget.account, currentTarget.conversation, text)
            clientDebugEvent("chat.draft.seal.empty:" + text.isEmpty() + ".saved:" + (saved is ClientResult.Ready))
        }
    }

    private fun restoreDraft(next: Target) {
        viewModelScope.launch {
            val text = drafts.load(next.account, next.conversation)
            val current = target == next && session.status.value.phase == ClientPhase.OPEN
            clientDebugEvent("chat.draft.restore.found:" + (text != null) + ".current:" + current)
            if (!current) return@launch
            if (text != null && local.value.draft.isEmpty()) local.update { it.copy(draft = text) }
            if (local.value.composer != ComposerMode.EDIT) draftKnown = next
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

    /** One shared subscription for the identity topics, tied to the message watch of the same target. */
    private var identityWatch: Job? = null
    private var identityTarget: Pair<Target, String>? = null

    /** Starts the identity subscription of a direct chat and reads its identity and security once the peer is known. */
    private fun watchIdentity(current: Target, root: String) {
        if (identityTarget == current to root && identityWatch?.isActive == true) return
        identityWatch?.cancel()
        identityTarget = current to root
        val generation = session.status.value.generation
        readIdentity(current, root, afterAccept = false)
        readSecurity(current)
        identityWatch = viewModelScope.launch {
            identity.changes(current.account).collect { signal ->
                if (target != current || session.status.value.generation != generation) return@collect
                when (signal) {
                    is IdentitySignal.Changed -> if (signal.change.contact == root) applyChange(current, signal.change)
                    // Silent: no timeline line, only a fresh read of the encryption state.
                    is IdentitySignal.DevicesChanged ->
                        if (signal.conversation == null || signal.conversation == current.conversation) readSecurity(current)
                    IdentitySignal.Resync -> {
                        readIdentity(current, root, afterAccept = false)
                        readSecurity(current)
                    }
                }
            }
        }
    }

    /**
     * Reads the peer's status. A status that reports no state does not clear a change an event delivered; only an accept
     * outcome ([afterAccept]) lets an unknown state replace it.
     */
    private fun readIdentity(current: Target, root: String, afterAccept: Boolean, then: ((IdentityState?) -> Unit)? = null) {
        val generation = session.status.value.generation
        viewModelScope.launch {
            val result = identity.identity(current.account, root)
            if (target != current || session.status.value.generation != generation) return@launch
            val read = (result as? ClientResult.Ready)?.value
            if (read == null) {
                // No identity feature at all is not a change; a failed read is unknown, and a held send stays held.
                then?.invoke(if (result is ClientResult.Unavailable) IdentityState.UNKNOWN else null)
                return@launch
            }
            val (readState, change) = read
            val next = if (readState == IdentityState.UNKNOWN && !afterAccept && local.value.identityState == IdentityState.CHANGED) {
                IdentityState.CHANGED
            } else {
                readState
            }
            if (change != null) pendingChange = change else if (next != IdentityState.CHANGED) pendingChange = null
            local.update { it.copy(identityState = next, sendBlocked = it.sendBlocked && next == IdentityState.CHANGED) }
            pendingChange?.let(review::update)
            then?.invoke(next)
        }
    }

    private fun readSecurity(current: Target) {
        val generation = session.status.value.generation
        viewModelScope.launch {
            val result = identity.security(current.account, current.conversation)
            if (target != current || session.status.value.generation != generation) return@launch
            val security = (result as? ClientResult.Ready)?.value ?: return@launch
            // One line per recovery, whatever number of reads report it.
            if (security.warning == SecurityWarning.FORK_RECOVERED && lineKeys.add("fork:" + security.epoch)) {
                addServiceLine(uiText(R.string.security_warning_fork_recovered))
            }
        }
    }

    /** A `contact.identity_changed` event: the banner, the sheet and a timeline line; nothing is accepted. */
    private fun applyChange(current: Target, change: IdentityChange) {
        pendingChange = change
        review.update(change)
        local.update { it.copy(identityState = IdentityState.CHANGED) }
        if (lineKeys.add("changed:" + change.newAnchor.hashCode())) {
            addServiceLine(uiText(R.string.identity_timeline_changed, UiText.Untrusted(local.value.peerName.orEmpty())))
        } else {
            publish()
        }
    }

    private fun addServiceLine(text: UiText) {
        val at = System.currentTimeMillis() / 1000
        serviceLines += MessageItem(IDENTITY_LINE + serviceLines.size + ":" + at, "", false, false, text, at, service = true)
        publish()
    }

    /** `identity_change_pending`: the composer prompt, and a status read for the anchor the sheet needs. */
    private fun blockedByIdentity(current: Target, block: SendBlock) {
        if (block !is SendBlock.IdentityChangePending) return
        local.update { it.copy(identityState = IdentityState.CHANGED, sendBlocked = true) }
        peerRoot?.let { readIdentity(current, it, afterAccept = false) }
    }

    private fun openReview() {
        val current = target ?: return
        val root = peerRoot ?: return
        if (local.value.identityState != IdentityState.CHANGED) return
        val change = pendingChange
        review.open(current.account, root, change, change?.username, change?.aiFlag ?: local.value.titleAi)
    }

    /** After an accept attempt the status is read again; an accepted change also gets its timeline line. */
    private fun reviewOutcome(account: String, contact: String, outcome: AcceptOutcome, anchor: String) {
        val current = target ?: return
        if (current.account != account || peerRoot != contact) return
        when (outcome) {
            AcceptOutcome.Accepted -> {
                // The banner clears only if the pending change is still the one that was accepted.
                if (pendingChange != null && pendingChange?.newAnchor != anchor) {
                    readIdentity(current, contact, afterAccept = false)
                    return
                }
                pendingChange = null
                local.update { it.copy(identityState = IdentityState.UNKNOWN, sendBlocked = false) }
                addServiceLine(uiText(R.string.identity_timeline_accepted, UiText.Untrusted(local.value.peerName.orEmpty())))
                readIdentity(current, contact, afterAccept = true)
            }
            AcceptOutcome.AlreadyHandled -> {
                // Same rule as an accept: only the handled anchor clears; a newer pending change stays and is read again.
                if (pendingChange != null && pendingChange?.newAnchor != anchor) {
                    readIdentity(current, contact, afterAccept = false)
                    return
                }
                pendingChange = null
                local.update { it.copy(identityState = IdentityState.UNKNOWN, sendBlocked = false) }
                readIdentity(current, contact, afterAccept = true)
            }
            AcceptOutcome.ChangedAgain -> readIdentity(current, contact, afterAccept = false)
            is AcceptOutcome.Failed -> Unit
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
        // Group typing is outside the beta scope: the SDK refuses message.focus for a group (unsupported_for_group).
        if (current.group != null || typingRefused || typingActive == active) return
        typingActive = active
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            val result = history.setTyping(current.account, current.conversation, active)
            if (target != current || session.status.value.phase != ClientPhase.OPEN) return@launch
            val denied = result is ClientResult.Failed &&
                result.problem.kind == ClientFailureKind.AUTHORIZATION
            // A refusal for a group scope is tolerated silently: no notice, and typing stays off for this chat.
            if (result is ClientResult.Failed && result.problem.check == UNSUPPORTED_FOR_GROUP) {
                typingRefused = true
                typingActive = false
                return@launch
            }
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
        if (wasActive && current != null && current.group == null && !typingRefused) {
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
        // A service row, and a row this client cannot display, are centred lines (never an own bubble). The device id
        // of the sender is never a name: a peer without a resolved name is shown as a neutral member.
        val centred = row.service || (row.placeholder && !row.deleted)
        val content = when {
            row.deleted -> uiText(R.string.ui_message_deleted)
            row.service && row.text == null -> uiText(serviceLine(row.serviceAction))
            row.placeholder -> uiText(R.string.ui_message_placeholder)
            else -> row.text?.let { UiText.Data(it) } ?: uiText(R.string.ui_message_content_is_unavailable)
        }
        return MessageItem(
            row.id,
            if (row.deleted) "" else row.senderName.orEmpty(),
            row.own && !centred,
            row.deleted,
            content,
            row.createdAt,
            row.revision,
            row.edited,
            row.replyPreview?.let { UiText.Data(it) },
            row.placeholder,
            centred,
            receiptLabel(row.receipts),
            row.reactions.map { ReactionCount(it.token, it.count) }.toPersistentList(),
            attachments = if (row.deleted) persistentListOf() else row.attachments.map { attachmentItem(it, row.own) }.toPersistentList(),
            senderAi = row.senderAi
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
        refreshGallery(messages)
        val viewer = local.value.viewer
        if (viewer != null) {
            val key = viewer.key
            refOf(key)?.let { reference ->
                local.update { it.copy(viewer = attachmentItem(reference, false).takeIf { item -> item.status == AttachmentStatus.READY }) }
            }
        }
        publish()
    }

    private fun refOf(key: String): MediaAttachment? = references[key] ?: galleryRefs[key]

    private fun openAttachment(key: String) {
        val currentTarget = target ?: return
        val attachment = refOf(key) ?: return
        val item = attachmentItem(attachment, false)
        when (item.status) {
            AttachmentStatus.READY -> {
                val path = item.localPath ?: return
                if (item.kind != AttachmentKind.FILE) {
                    local.update { it.copy(viewer = item, viewerPosition = viewerPosition(item)) }
                } else {
                    viewModelScope.launch { channel.send(ChatEvent.File(path, item.mime, item.fileName, FileUse.OPEN)) }
                }
            }
            AttachmentStatus.AVAILABLE, AttachmentStatus.FAILED, AttachmentStatus.HIDDEN -> download(currentTarget, attachment)
            else -> Unit
        }
    }

    /** Downloads go to the application queue, so they continue after this chat closes (session lifetime). */
    private fun download(currentTarget: Target, attachment: MediaAttachment) {
        if (session.status.value.phase != ClientPhase.OPEN) return
        watchTransfers(currentTarget)
        queue.download(currentTarget.account, attachment)
    }

    private fun watchTransfers(currentTarget: Target) {
        if (transferWatch?.isActive == true) return
        val prefix = MediaTransfers.prefix(currentTarget.account)
        transferWatch = viewModelScope.launch {
            queue.current.collect { all ->
                if (target != currentTarget) return@collect
                val changed = HashSet<String>()
                val seen = HashSet<String>()
                all.forEach { (full, state) ->
                    if (!full.startsWith(prefix)) return@forEach
                    val key = full.removePrefix(prefix)
                    seen += key
                    val reference = refOf(key) ?: return@forEach
                    if (state is TransferState.Ready) {
                        located[key] = state.file.path
                        transfers.remove(key)
                        queue.forget(full)
                        changed += reference.messageId
                    } else if (transfers.put(key, state) != state) {
                        changed += reference.messageId
                    }
                }
                // Cancelled by a lock or session close: the item is downloadable again.
                transfers.keys.filter { it !in seen }.forEach { key ->
                    transfers.remove(key)
                    refOf(key)?.let { changed += it.messageId }
                }
                if (changed.isNotEmpty()) refreshAttachments(changed)
            }
        }
    }

    private fun handOver(key: String, use: FileUse) {
        val item = refOf(key)?.let { attachmentItem(it, false) } ?: return
        val path = item.localPath ?: return
        if (item.status != AttachmentStatus.READY) return
        viewModelScope.launch { channel.send(ChatEvent.File(path, item.mime, item.fileName, use)) }
    }

    private fun stage(uri: String, source: PickSource) {
        val currentTarget = target ?: return
        if (session.status.value.phase != ClientPhase.OPEN || outbox.size >= OUTBOX_LIMIT) return
        viewModelScope.launch {
            val staged = when (source) {
                PickSource.PHOTO -> (stager.visual(currentTarget.account, uri) as? StageResult.Staged)?.media
                PickSource.CAMERA -> stager.camera(currentTarget.account, uri)
                PickSource.FILE -> stager.file(currentTarget.account, uri)
            }
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
            outbox[id] = Outgoing(operation, "", System.currentTimeMillis() / 1000, LocalDelivery.SENDING, staged = listOf(staged))
            publish()
            deliver(currentTarget, id)
        }
    }

    /**
     * Stages up to 16 picked items one after another (transcoding is serial), then opens the send preview
     * (album board B28 to be drawn). Local work reports real progress; nothing is sent yet.
     */
    private fun preview(uris: List<String>) {
        val currentTarget = target ?: return
        if (uris.isEmpty() || session.status.value.phase != ClientPhase.OPEN || outbox.size >= OUTBOX_LIMIT) return
        val picked = uris.take(MediaAttachment.MAX_PER_MESSAGE)
        previewCancel()
        local.update { it.copy(preview = SendPreview(preparing = true, progress = 0f)) }
        previewJob = viewModelScope.launch {
            val refusals = HashSet<StageRefusal>()
            picked.forEachIndexed { index, uri ->
                val result = stager.visual(currentTarget.account, uri) { fraction ->
                    local.update { it.copy(preview = it.preview?.copy(progress = (index + fraction.coerceIn(0f, 1f)) / picked.size)) }
                }
                if (target != currentTarget) {
                    (result as? StageResult.Staged)?.media?.file?.delete()
                    return@launch
                }
                when (result) {
                    is StageResult.Staged -> previewItems += result.media
                    is StageResult.Refused -> refusals += result.reason
                }
                publishPreview(preparing = index < picked.lastIndex, progress = (index + 1f) / picked.size, refusals = refusals)
            }
            if (previewItems.isEmpty() && refusals.isNotEmpty()) {
                local.update { it.copy(preview = null, problem = refusalText(refusals)) }
            }
        }
    }

    private fun publishPreview(preparing: Boolean, progress: Float?, refusals: Set<StageRefusal> = emptySet()) {
        val rows = previewItems.mapIndexed { index, staged ->
            PreviewItem(index, kindOf(staged.mime), staged.file.path, staged.fileName, staged.size)
        }.toPersistentList()
        local.update { state ->
            state.copy(
                preview = (state.preview ?: SendPreview()).copy(
                    items = rows,
                    preparing = preparing,
                    progress = progress,
                    problem = if (refusals.isEmpty()) state.preview?.problem else refusalText(refusals)
                )
            )
        }
    }

    private fun previewRemove(index: Int) {
        if (local.value.preview?.preparing != false || index !in previewItems.indices) return
        previewItems.removeAt(index).file.delete()
        if (previewItems.isEmpty()) previewCancel() else publishPreview(preparing = false, progress = null)
    }

    /** Moves item [index] one place towards the start of the album. */
    private fun previewMove(index: Int) {
        if (local.value.preview?.preparing != false || index !in 1 until previewItems.size) return
        previewItems.add(index - 1, previewItems.removeAt(index))
        publishPreview(preparing = false, progress = null)
    }

    private fun previewSend() {
        val currentTarget = target ?: return
        val sheet = local.value.preview ?: return
        if (sheet.preparing || previewItems.isEmpty() || outbox.size >= OUTBOX_LIMIT) return
        val operation = UUID.randomUUID().toString()
        val id = LOCAL_PREFIX + operation
        val now = System.currentTimeMillis() / 1000
        outbox[id] = Outgoing(operation, sheet.caption, now, LocalDelivery.SENDING, staged = previewItems.toList())
        previewItems.clear()
        local.update { it.copy(preview = null) }
        publish()
        deliver(currentTarget, id)
    }

    private fun previewCancel() {
        previewJob?.cancel()
        previewJob = null
        previewItems.forEach { it.file.delete() }
        previewItems.clear()
        if (local.value.preview != null) local.update { it.copy(preview = null) }
    }

    /**
     * The conversation gallery (board B29 to be drawn): a client-side filter over the paged message
     * source until the SDK can filter by media (request R10). It follows the `next_before` cursor,
     * reads a few pages per request until it has something to show, and keeps a bounded window.
     */
    private fun openGallery() {
        if (target == null || session.status.value.phase != ClientPhase.OPEN) return
        closeGallery()
        local.update { it.copy(gallery = GallerySheet(), selectedId = null) }
        galleryCursor = null
        loadGallery(first = true)
    }

    private fun loadGallery(first: Boolean = false) {
        val currentTarget = target ?: return
        val sheet = local.value.gallery ?: return
        if (galleryJob?.isActive == true || (!first && galleryCursor == null) || galleryRefs.size >= GALLERY_WINDOW) return
        local.update { it.copy(gallery = sheet.copy(loading = true, problem = null)) }
        galleryJob = viewModelScope.launch {
            var cursor = galleryCursor
            var pages = 0
            var added = 0
            while (pages < GALLERY_PAGES_PER_LOAD && added < GALLERY_BATCH) {
                val result = history.page(currentTarget.account, currentTarget.conversation, cursor)
                if (target != currentTarget || local.value.gallery == null) return@launch
                val page = when (result) {
                    is ClientResult.Ready -> result.value
                    is ClientResult.Failed -> {
                        local.update {
                            it.copy(
                                gallery = it.gallery?.copy(
                                    loading = false,
                                    problem = failureText(
                                        R.string.ui_read_failed_code_value,
                                        result.problem.code,
                                        result.problem.statusName,
                                        result.problem.check
                                    )
                                )
                            )
                        }
                        return@launch
                    }
                    is ClientResult.Unavailable -> {
                        galleryCursor = null
                        break
                    }
                }
                pages++
                val fresh = page.rows.asReversed().filterIsInstance<MessageSummary>().filter { !it.deleted }
                    .flatMap { it.attachments }.filter { keyOf(it) !in galleryRefs }
                val files = media.locate(currentTarget.account, fresh)
                if (target != currentTarget) return@launch
                fresh.forEachIndexed { index, attachment ->
                    if (galleryRefs.size >= GALLERY_WINDOW) return@forEachIndexed
                    val key = keyOf(attachment)
                    galleryRefs[key] = attachment
                    files.getOrNull(index)?.let { located[key] = it.path }
                    if (attachment.kind == MediaKind.FILE) galleryFiles += key else galleryMedia += key
                    added++
                }
                cursor = page.older
                galleryCursor = cursor
                if (cursor == null || galleryRefs.size >= GALLERY_WINDOW) break
            }
            val more = galleryCursor != null && galleryRefs.size < GALLERY_WINDOW
            local.update { it.copy(gallery = it.gallery?.copy(loading = false, hasMore = more)) }
            publishGallery()
        }
    }

    /** Re-maps the shown tab from the window; mapping is per item, never a filter over the whole window. */
    private fun publishGallery() {
        val sheet = local.value.gallery ?: return
        val keys = if (sheet.tab == GalleryTab.FILES) galleryFiles else galleryMedia
        val items = keys.mapNotNull { key -> galleryRefs[key]?.let { attachmentItem(it, false) } }.toPersistentList()
        local.update { it.copy(gallery = it.gallery?.copy(items = items)) }
    }

    private fun refreshGallery(messages: Set<String>) {
        val sheet = local.value.gallery ?: return
        if (sheet.items.none { it.messageId in messages }) return
        publishGallery()
    }

    private fun closeGallery() {
        galleryJob?.cancel()
        galleryJob = null
        galleryRefs.clear()
        galleryMedia.clear()
        galleryFiles.clear()
        galleryCursor = null
        if (local.value.gallery != null) local.update { it.copy(gallery = null) }
    }

    /**
     * Reads the conversation's agent surfaces (`surface.list`, following its cursor) and shows each card the runtime
     * does not hold yet. The runtime keeps every held card current and reports changes through [CardSessionRepository.revision].
     */
    private fun loadCards(currentTarget: Target) {
        if (cardJob?.isActive == true) return
        cardJob = viewModelScope.launch {
            var cursor: String? = null
            var changed = false
            repeat(CARD_PAGES) {
                val listed = cards.page(currentTarget.account, currentTarget.conversation, cursor)
                val page = (listed as? ClientResult.Ready)?.value ?: return@repeat
                for (meta in page.rows) {
                    if (cardRefs[meta.ref] != meta) {
                        cardRefs[meta.ref] = meta
                        changed = true
                    }
                    if (cards.state(currentTarget.account, meta.ref) == null) {
                        if (target != currentTarget) return@launch
                        cards.show(currentTarget.account, meta.ref, cardLook.locale, cardLook.dark)
                    }
                }
                cursor = page.next ?: return@repeat
            }
            // The window is by creation time, not by the directory order of the pages.
            while (cardRefs.size > CARD_WINDOW) {
                val oldest = cardRefs.values.minByOrNull { it.createdAt ?: Long.MAX_VALUE } ?: break
                cardRefs.remove(oldest.ref)
                cards.dispose(currentTarget.account, oldest.ref)
            }
            if (changed && target == currentTarget) publish()
        }
    }

    /** Places each card by its creation time; the window is already in reading order. */
    private fun withCards(rows: List<TimelineItem>): List<TimelineItem> {
        if (cardRefs.isEmpty()) return rows
        val account = target?.account ?: return rows
        val out = ArrayList<TimelineItem>(rows.size + cardRefs.size)
        out.addAll(rows)
        cardRefs.values.sortedBy { it.createdAt ?: Long.MAX_VALUE }.forEach { meta ->
            val item = cards.state(account, meta.ref)?.let { cardItem(meta, it) } ?: return@forEach
            val at = meta.createdAt
            val later = if (at == null) -1 else out.indexOfFirst { (it.sentAt ?: Long.MAX_VALUE) > at }
            val index = if (later < 0) out.size else later
            out.add(index, item)
        }
        return out
    }

    private fun cardItem(meta: CardSurfaceRef, state: CardState): CardItem {
        val stale = cardStale[meta.ref]
        val images = cards.images(target?.account.orEmpty())
        return CardItem("card:" + meta.ref, meta.createdAt, meta.ref, state, stale?.first, stale?.second ?: 0, images)
    }

    /** A control event from the timeline. A `pending_submit` is submitted once; a stale answer reverts the field. */
    private fun cardInteract(action: ChatAction.CardInteract) {
        val account = target?.account ?: return
        viewModelScope.launch {
            val sent = cards.event(account, action.ref, action.key, action.event, action.seen)
            val reply = (sent as? ClientResult.Ready)?.value
            if (reply == null) {
                // An event that was not applied must not leave the control showing a value the runtime does not hold. A
                // call that may have reached the runtime is never asked for again: a second tap could repeat it.
                revertCard(action)
                if ((sent as? ClientResult.Failed)?.problem?.outcome == ClientOutcome.UNKNOWN) {
                    local.update { it.copy(deliveryNotice = uiText(R.string.chat_card_result_unknown)) }
                } else {
                    cardNotApplied(action)
                }
                return@launch
            }
            when (reply.result) {
                "pending_submit" -> {
                    val submitted = cards.submit(account, action.ref)
                    if (submitted is ClientResult.Failed) local.update { it.copy(deliveryNotice = cardSubmitFailure(submitted.problem)) }
                }
                "stale_dropped" -> {
                    revertCard(action)
                    // The account signed out: nothing to ask again. Otherwise the drop is said, never silent.
                    if (reply.reason != CardReply.ACCOUNT_RETIRED) cardNotApplied(action)
                }
                else -> Unit
            }
        }
    }

    /**
     * A control event the card did not take: a tap or a tab change is asked for again; an input says once per interval per
     * card that it was not applied (typing would otherwise repeat the notice on every keystroke).
     */
    private fun cardNotApplied(action: ChatAction.CardInteract) {
        if (action.event !is CardEvent.Input) {
            local.update { it.copy(deliveryNotice = uiText(R.string.chat_card_tap_again)) }
            return
        }
        val now = System.nanoTime()
        val last = inputNoticeAt[action.ref]
        if (last != null && now - last < INPUT_NOTICE_INTERVAL_NANOS) return
        inputNoticeAt[action.ref] = now
        local.update { it.copy(deliveryNotice = uiText(R.string.chat_card_input_not_applied)) }
    }

    /** Tells the card the control at the action's key shows a value the runtime does not hold, so it redraws the tree value. */
    private fun revertCard(action: ChatAction.CardInteract) {
        cardStale[action.ref] = action.key to ((cardStale[action.ref]?.second ?: 0L) + 1)
        publish()
    }

    /** A link or approval answer. A link opens only after the runtime answers `opened`, and only if it is the link the sheet showed. */
    private fun cardAnswer(action: ChatAction.CardAnswer) {
        val account = target?.account ?: return
        viewModelScope.launch {
            val url = action.url
            if (url != null && (cards.state(account, action.ref)?.hostRequest?.get("url") as? JsonPrimitive)?.content != url) return@launch
            val answered = cards.slot(account, action.ref, action.slotId, action.requestId, action.token, action.outcome)
            val reply = (answered as? ClientResult.Ready)?.value
            // An answer that may have reached the runtime is never asked for again: a second answer could repeat it.
            if ((answered as? ClientResult.Failed)?.problem?.outcome == ClientOutcome.UNKNOWN) {
                local.update { it.copy(deliveryNotice = uiText(R.string.chat_card_result_unknown)) }
                return@launch
            }
            // The request changed or was decided already (typed refusal): say so; the card was read again, nothing is retried.
            if (reply?.result == "approval_changed") {
                local.update { it.copy(deliveryNotice = uiText(R.string.chat_card_request_changed)) }
                return@launch
            }
            // An answer the card did not take (dropped, or never reached the runtime) is asked for again, never lost silently.
            if (reply == null || reply.result == "stale_dropped") {
                // Dismissing a sheet needs no second try, and an account that signed out has nothing to ask again.
                val retired = reply?.reason == CardReply.ACCOUNT_RETIRED
                if (action.outcome != CardSlotOutcome.CANCEL && !retired) {
                    local.update {
                        it.copy(
                            deliveryNotice = uiText(R.string.chat_card_tap_again)
                        )
                    }
                }
                return@launch
            }
            if (url != null && reply.result == "opened") channel.send(ChatEvent.OpenUrl(url))
        }
    }

    private fun cardShow(ref: String) {
        val account = target?.account ?: return
        viewModelScope.launch { cards.show(account, ref, cardLook.locale, cardLook.dark) }
    }

    /** Out of sync: the held card is released and opened again from the latest surface. */
    private fun cardResync(ref: String) {
        val account = target?.account ?: return
        viewModelScope.launch {
            cards.dispose(account, ref)
            cards.show(account, ref, cardLook.locale, cardLook.dark)
        }
    }

    private fun cardLookChanged(locale: String, dark: Boolean) {
        if (cardLook.locale == locale && cardLook.dark == dark) return
        cardLook = CardLook(locale, dark)
        val account = target?.account ?: return
        viewModelScope.launch { cards.present(account, locale, dark) }
    }

    /** Steps the viewer through the ready pictures and videos of the same message. */
    private fun viewerStep(delta: Int) {
        val open = local.value.viewer ?: return
        val siblings = references.values.filter { it.messageId == open.messageId }.sortedBy { it.index }
            .map { attachmentItem(it, false) }
            .filter { it.status == AttachmentStatus.READY && it.kind != AttachmentKind.FILE }
        val at = siblings.indexOfFirst { it.key == open.key }
        val next = siblings.getOrNull(at + delta) ?: return
        local.update { it.copy(viewer = next, viewerPosition = viewerPosition(next)) }
    }

    private fun viewerPosition(item: AttachmentItem): ViewerPosition? {
        val siblings = references.values.filter { it.messageId == item.messageId }.sortedBy { it.index }
            .map { attachmentItem(it, false) }
            .filter { it.status == AttachmentStatus.READY && it.kind != AttachmentKind.FILE }
        if (siblings.size < 2) return null
        return ViewerPosition(siblings.indexOfFirst { it.key == item.key } + 1, siblings.size)
    }

    private fun openForward(messageId: String?, index: Int?) {
        val currentTarget = target ?: return
        val row = local.value.rows.firstOrNull { it.id == messageId } as? MessageItem ?: return
        if (!forwardable(row) || session.status.value.phase != ClientPhase.OPEN) return
        forwardJob?.cancel()
        forwarding = Forwarding(currentTarget, row.id, index, null)
        local.update { it.copy(selectedId = null, viewer = null, forward = ForwardSheet()) }
        loadForwardTargets(more = false)
    }

    private fun loadForwardTargets(more: Boolean) {
        val job = forwarding ?: return
        val sheet = local.value.forward ?: return
        if (forwardJob?.isActive == true || (more && (!sheet.hasMore || sheet.sending))) return
        local.update { it.copy(forward = sheet.copy(loading = true, problem = null)) }
        forwardJob = viewModelScope.launch {
            val result = history.forwardTargets(job.target.account, if (more) job.cursor else null)
            if (forwarding?.messageId != job.messageId || target != job.target) return@launch
            val open = local.value.forward ?: return@launch
            when (result) {
                is ClientResult.Ready -> {
                    forwarding = job.copy(cursor = result.value.next)
                    val known = if (more) open.rows else persistentListOf()
                    val ids = known.mapTo(HashSet()) { it.conversation }
                    val fresh = result.value.rows.filter { it.conversation != job.target.conversation && ids.add(it.conversation) }
                    // A bounded window: the chooser keeps at most FORWARD_WINDOW conversations.
                    val rows = (known + fresh.map { ForwardRow(it.conversation, it.title, it.group) }).take(FORWARD_WINDOW)
                    local.update {
                        it.copy(
                            forward = open.copy(
                                rows = rows.toPersistentList(),
                                loading = false,
                                hasMore = result.value.next != null && rows.size < FORWARD_WINDOW
                            )
                        )
                    }
                }
                is ClientResult.Failed -> local.update {
                    it.copy(
                        forward = open.copy(
                            loading = false,
                            problem = failureText(
                                R.string.ui_read_failed_code_value, result.problem.code, result.problem.statusName, result.problem.check
                            )
                        )
                    )
                }
                is ClientResult.Unavailable -> local.update {
                    it.copy(forward = open.copy(loading = false, problem = uiText(R.string.chat_forward_unavailable)))
                }
            }
        }
    }

    /**
     * Forwarding re-encrypts (media design 6 rule 8): each item is imported if needed, copied into the
     * outbox and prepared again for the new conversation; keys and grants are never reused.
     */
    private fun forwardTo(conversation: String) {
        val job = forwarding ?: return
        val sheet = local.value.forward ?: return
        val destination = sheet.rows.firstOrNull { it.conversation == conversation } ?: return
        if (sheet.sending) return
        val row = window.firstOrNull { it.id == job.messageId } as? MessageItem ?: return
        val items = references.values.filter { it.messageId == job.messageId && (job.index == null || it.index == job.index) }
            .sortedBy { it.index }
        val caption = if (job.index == null) (row.content as? UiText.Data)?.value.orEmpty() else ""
        val generation = session.status.value.generation
        local.update { it.copy(forward = sheet.copy(sending = true, problem = null)) }
        forwardJob?.cancel()
        forwardJob = viewModelScope.launch {
            val account = job.target.account
            val staged = ArrayList<StagedMedia>(items.size)
            var failure: ClientProblem? = null
            for (item in items) {
                val held = media.localFile(account, item) ?: (media.download(account, item) as? TransferState.Ready)?.file
                val copy = held?.let {
                    media.copyToOutbox(account, it, item.fileName.ifEmpty { "file" }, item.declaredMime.ifEmpty { OCTET })
                }
                if (copy == null) {
                    failure = ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE)
                    break
                }
                staged += copy
            }
            val operation = UUID.randomUUID().toString()
            val sent = when {
                failure != null -> ClientResult.Failed(failure)
                staged.isEmpty() -> history.send(account, conversation, caption, operation)
                else -> queue.upload(account, MediaDestination(conversation, destination.group), caption, staged, operation)
            }
            if (sent !is ClientResult.Ready) staged.forEach { it.file.delete() }
            val stale = forwarding?.messageId != job.messageId || target != job.target
            if (stale || generation != session.status.value.generation) return@launch
            when (sent) {
                is ClientResult.Ready -> {
                    forwarding = null
                    local.update { it.copy(forward = null, deliveryNotice = uiText(R.string.chat_forward_sent)) }
                }
                is ClientResult.Failed -> local.update { state ->
                    state.copy(forward = state.forward?.copy(sending = false, problem = sendFailure(sent.problem)))
                }
                is ClientResult.Unavailable -> local.update { state ->
                    state.copy(forward = state.forward?.copy(sending = false, problem = uiText(R.string.chat_forward_unavailable)))
                }
            }
        }
    }

    private fun closeForward() {
        // A re-send that the SDK may already have admitted is not cancelled; only the chooser closes.
        if (local.value.forward?.sending != true) forwardJob?.cancel()
        forwarding = null
        local.update { it.copy(forward = null) }
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
    val staged: List<StagedMedia> = emptyList(),
    /** Refused until a changed identity is reviewed; never resent by itself. */
    var blocked: Boolean = false
) {
    override fun toString() = "Outgoing(<redacted>)"
}

/** Status names of failures that mean "no live session"; such sends wait in the queue. */
// authority_unavailable is not offline: the SDK has no home node for the contact until its card is imported (it carries
// no reason code yet), so the send ends as not sent with a retry and the card hint.
private val OFFLINE = setOf("unavailable", "offline", "closed")
private const val LOCAL_PREFIX = "local:"

/** Ids of the local identity and encryption service lines; they are never message ids. */
internal const val IDENTITY_LINE = "identity-line:"

private fun keyOf(attachment: MediaAttachment) = attachment.messageId + "/" + attachment.index

private fun kindOf(mime: String) = when (MediaAttachment.kindOf(mime)) {
    MediaKind.IMAGE -> AttachmentKind.IMAGE
    MediaKind.ANIMATED -> AttachmentKind.ANIMATED
    MediaKind.VIDEO -> AttachmentKind.VIDEO
    MediaKind.FILE -> AttachmentKind.FILE
}
private const val OUTBOX_LIMIT = 64
private const val CARD_PAGES = 4
private const val CARD_WINDOW = 200

/** At most one "input not applied" notice per card in this interval. */
private const val INPUT_NOTICE_INTERVAL_NANOS = 5_000_000_000L

private data class CardLook(val locale: String, val dark: Boolean)

private fun localQueue(media: MediaRepository, session: ClientSessionRepository) =
    MediaTransfers(media, session, CoroutineScope(SupervisorJob() + Dispatchers.Main))
private const val GALLERY_WINDOW = 2_000
private const val GALLERY_BATCH = 30
private const val GALLERY_PAGES_PER_LOAD = 5

/** Media refusals the person can act on get their own words (design B22); others use the generic text. */
private fun mediaFailure(problem: ClientProblem): UiText? = when (problem.statusName ?: problem.check) {
    AUTHORITY_UNAVAILABLE -> uiText(R.string.chat_send_needs_card)
    "recipient_limit" -> uiText(R.string.chat_media_recipient_limit)
    "peer_upgrade_required" -> uiText(R.string.chat_media_peer_upgrade)
    "file_too_large", "quota_exceeded", "disk_full" -> uiText(R.string.chat_media_too_large)
    else -> null
}

private fun refusalText(refusals: Set<StageRefusal>): UiText = when {
    StageRefusal.VIDEO_TOO_LONG in refusals -> uiText(R.string.chat_media_video_too_long)
    StageRefusal.VIDEO_TOO_LARGE in refusals -> uiText(R.string.chat_media_video_too_large)
    else -> uiText(R.string.chat_media_not_staged)
}
private const val FORWARD_WINDOW = 500
private const val OCTET = "application/octet-stream"

/** A message can be forwarded when the SDK accepted it and it still has text or attachments. */
internal fun forwardable(row: MessageItem) = row.delivery == null && !row.deleted && !row.placeholder && !row.service &&
    (row.attachments.isNotEmpty() || (row.content as? UiText.Data)?.value?.isNotBlank() == true) &&
    row.attachments.none { it.status in UNFORWARDABLE }

private val UNFORWARDABLE = setOf(AttachmentStatus.REVOKED, AttachmentStatus.EXPIRED, AttachmentStatus.NOT_ON_DEVICE)
private const val WINDOW = 1_000
private const val DRAFT_DEBOUNCE_MS = 400L

private fun noOutbox() = ChatOutboxCoordinator(NoChatOutbox, CoroutineScope(SupervisorJob() + Dispatchers.Main))

private object NoChatOutbox : ChatOutboxRepository {
    override suspend fun load(account: String, conversation: String): List<QueuedSend> = emptyList()
    override suspend fun save(account: String, conversation: String, sends: List<QueuedSend>): ClientResult<Unit> = ClientResult.Ready(Unit)
    override suspend fun clearAccount(account: String) = Unit
}

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

/** A card answer that failed: an unknown outcome is said plainly, so the user checks the conversation before resending. */
private fun cardSubmitFailure(problem: ClientProblem): UiText = when (problem.outcome) {
    ClientOutcome.UNKNOWN -> uiText(R.string.chat_card_submit_unknown)
    else -> sendFailure(problem)
}

private fun sendFailure(problem: ClientProblem): UiText = when (problem.outcome) {
    ClientOutcome.UNKNOWN -> uiText(R.string.ui_send_outcome_is_unknown_refresh_before_making_another)
    ClientOutcome.NONE, ClientOutcome.REFUSED -> failureText(
        R.string.ui_the_message_was_not_sent_code_value,
        problem.code,
        problem.statusName,
        problem.check
    )
}

private fun actionFailure(problem: ClientProblem): UiText = when (problem.outcome) {
    ClientOutcome.UNKNOWN -> uiText(R.string.ui_send_outcome_is_unknown_refresh_before_making_another)
    ClientOutcome.NONE, ClientOutcome.REFUSED -> failureText(
        R.string.ui_action_failed_code_value,
        problem.code,
        problem.statusName,
        problem.check
    )
}

private fun failedNotice(problem: ClientProblem): UiText? = when {
    problem.outcome == ClientOutcome.UNKNOWN -> null
    problem.statusName == AUTHORITY_UNAVAILABLE -> uiText(R.string.chat_send_needs_card)
    else -> uiText(R.string.ui_delivery_failed)
}

private const val AUTHORITY_UNAVAILABLE = "authority_unavailable"
private const val UNSUPPORTED_FOR_GROUP = "unsupported_for_group"

private fun typingNotice(result: ClientResult<Unit>): UiText? = when (result) {
    is ClientResult.Ready -> null
    is ClientResult.Unavailable -> uiText(R.string.ui_typing_unavailable)
    is ClientResult.Failed -> if (result.problem.kind == ClientFailureKind.AUTHORIZATION) {
        null
    } else {
        uiText(R.string.ui_typing_unavailable)
    }
}

private fun UnavailableReason.description(): UiText = when (this) {
    UnavailableReason.FEATURE_NOT_INSTALLED -> uiText(R.string.ui_the_feature_is_not_installed)
    UnavailableReason.UNSUPPORTED_BY_CORE -> uiText(R.string.ui_the_sdk_operation_is_not_available_in_this)
    UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> uiText(R.string.ui_secure_account_access_is_required_on_this_device)
}

/** The wording of a group service row from its action; the row itself carries no text. */
private fun serviceLine(action: String?): Int = when (action) {
    "member_added" -> R.string.chat_service_member_added
    "member_removed" -> R.string.chat_service_member_removed
    "name_changed" -> R.string.chat_service_name_changed
    "key_changed" -> R.string.chat_service_key_changed
    "pin_added" -> R.string.chat_service_pin_added
    "pin_removed" -> R.string.chat_service_pin_removed
    else -> R.string.ui_service_message
}

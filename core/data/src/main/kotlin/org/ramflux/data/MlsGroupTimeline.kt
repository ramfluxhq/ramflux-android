// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/*
 * The pure parts of the group timeline (common 3.3): the bounded window of posted rows, the AI marker merge (S8), the local
 * sends and the collapse of membership rows into lines. Nothing here is thread-safe or logs; the session confines every
 * instance to its mutex.
 */

/** Why a group is read-only for this member: no composer, no `group.send`, no `group.refresh` (S13). */
enum class MlsReadOnly { LEFT, REMOVED, BANNED }

/** The phase of an own post that is not in the log yet. */
enum class MlsLocalPhase { SUBMITTING, QUEUED, DELAYED, NOT_SENT }

/** Why a send is NOT_SENT: an error code, a rate limit with its quota, or the typed unavailable result. */
data class MlsSendFailure(
    val code: String?,
    val rateLimited: Boolean = false,
    val quota: MlsGroupQuota? = null,
    val detail: Long? = null,
    val unavailable: Boolean = false,
)

/**
 * An own post the SDK has not put in the group log yet. The SDK owns queued and delayed posts; the app keeps no outbox. [text] is
 * held in memory only, for the session; a NOT_SENT item is the only one the person can retry (by an explicit tap).
 */
data class MlsLocalSend(
    val localId: String,
    val text: String,
    val replyTo: String?,
    val mentions: List<String>,
    val postId: String?,
    val phase: MlsLocalPhase,
    val queuedReason: MlsGroupQueuedReason? = null,
    val sendAfter: Long? = null,
    val failure: MlsSendFailure? = null,
    val createdAt: Long = 0,
) {
    override fun toString() = "MlsLocalSend(<redacted>)"
}

/** The AI marker merge of S8: once an SDK row said `ai_flag: true` for an anchor, the anchor keeps the marker. */
internal object MlsAiFlags {
    fun learn(known: Set<String>, post: MlsGroupPost): Set<String> {
        var next = known
        fun add(anchor: String, flag: Boolean) {
            if (flag && anchor.isNotEmpty() && anchor !in next) next = next + anchor
        }
        post.author?.let { add(it.anchor, it.aiFlag) }
        post.by?.let { add(it.anchor, it.aiFlag) }
        post.changes.forEach { add(it.anchor, it.aiFlag) }
        return next
    }

    fun learnMembers(known: Set<String>, rows: List<MlsGroupMember>): Set<String> =
        known + rows.filter { it.aiFlag && it.anchor.isNotEmpty() }.map { it.anchor }

    fun learnRequests(known: Set<String>, rows: List<MlsJoinRequest>): Set<String> =
        known + rows.filter { it.aiFlag && it.anchor.isNotEmpty() }.map { it.anchor }

    fun apply(known: Set<String>, request: MlsJoinRequest) = if (!request.aiFlag && request.anchor in known) request.copy(aiFlag = true) else request

    fun apply(known: Set<String>, author: MlsGroupAuthor?) =
        if (author != null && !author.aiFlag && author.anchor in known) author.copy(aiFlag = true) else author

    fun apply(known: Set<String>, post: MlsGroupPost): MlsGroupPost {
        if (known.isEmpty()) return post
        return post.copy(
            author = apply(known, post.author),
            by = apply(known, post.by),
            changes = post.changes.map { if (!it.aiFlag && it.anchor in known) it.copy(aiFlag = true) else it },
        )
    }

    fun apply(known: Set<String>, member: MlsGroupMember) = if (!member.aiFlag && member.anchor in known) member.copy(aiFlag = true) else member
}

/**
 * The posted rows of one group, ordered by `log_seq`, at most [LIMIT] long. Rows are keyed by `log_seq`, so an event and a
 * history row for the same slot are one item. A deleted post keeps its slot as a tombstone and the slot is never reused.
 */
data class MlsTimelineWindow(
    val posts: List<MlsGroupPost> = emptyList(),
    val deleted: Set<String> = emptySet(),
    val floorLogSeq: Long = 0,
    val reachedFloor: Boolean = false,
    val hasOlder: Boolean = false,
    val newest: Long = 0,
    /** Rows at the newest end were dropped by the bound: events are held out until the window is reloaded. */
    val trimmedNewest: Boolean = false,
    /** The log position after which rows are missing (a gap fill starts there). */
    val gapAfter: Long? = null,
    /**
     * The generation of this window: bumped by every reset, reload, restart and eviction. A page that was requested for an
     * older generation is dropped, so it can never leave a hole between itself and the rows that replaced the window.
     */
    val gen: Long = 0,
) {
    /** An empty window of the next generation. */
    fun reset() = MlsTimelineWindow(gen = gen + 1)

    val oldest: Long? get() = posts.firstOrNull()?.logSeq

    /**
     * Merges [rows] (all with a `log_seq`). A row for a loaded slot replaces it (a tombstone stays a tombstone). Past [LIMIT] the
     * far end is dropped: the newest rows when the window grew towards older rows, else the oldest.
     */
    fun merge(rows: List<MlsGroupPost>): MlsTimelineWindow {
        val bySlot = LinkedHashMap<Long, MlsGroupPost>()
        posts.forEach { post -> post.logSeq?.let { bySlot[it] = post } }
        var grewOlder = false
        val before = oldest
        rows.forEach { row ->
            val slot = row.logSeq ?: return@forEach
            val stamped = if (row.postId in deleted || bySlot[slot]?.state == MlsGroupPostState.Deleted) {
                row.copy(state = MlsGroupPostState.Deleted, text = null)
            } else {
                row
            }
            if (before != null && slot < before) grewOlder = true
            bySlot[slot] = stamped
        }
        var sorted = bySlot.values.sortedBy { it.logSeq }
        var trimmed = trimmedNewest
        var droppedOldest = false
        if (sorted.size > LIMIT) {
            val drop = sorted.size - LIMIT
            if (grewOlder) {
                sorted = sorted.dropLast(drop)
                trimmed = true
            } else {
                sorted = sorted.drop(drop)
                droppedOldest = true
            }
        }
        return copy(
            posts = sorted,
            newest = sorted.lastOrNull()?.logSeq ?: newest,
            trimmedNewest = trimmed,
            hasOlder = hasOlder || droppedOldest,
            reachedFloor = reachedFloor && !droppedOldest,
        )
    }

    /** Applies a history page: [older] pages grow the window towards older rows and carry `more`. */
    fun page(history: MlsGroupHistory, older: Boolean, newestPage: Boolean): MlsTimelineWindow {
        val merged = merge(history.posts.filter { it.logSeq != null })
        val floor = history.floorLogSeq
        val towardsOlder = older || newestPage
        return merged.copy(
            floorLogSeq = floor,
            hasOlder = if (towardsOlder) history.more else merged.hasOlder,
            reachedFloor = if (towardsOlder) !history.more || (merged.oldest ?: Long.MAX_VALUE) <= floor else merged.reachedFloor,
        )
    }

    /**
     * Restarts the window at [row], the newest row the person is certain of: older rows load by scrolling up and newer rows by
     * the gap fill after it. Used when a trimmed window must show an own post again.
     */
    fun restartAt(row: MlsGroupPost): MlsTimelineWindow {
        val slot = row.logSeq ?: return this
        return copy(posts = listOf(row), newest = slot, trimmedNewest = false, hasOlder = true, reachedFloor = false, gapAfter = slot, gen = gen + 1)
    }

    /** Applies one event row; held out while newest rows were trimmed. A jump past the newest slot opens a gap. */
    fun event(row: MlsGroupPost): MlsTimelineWindow {
        val slot = row.logSeq ?: return this
        if (trimmedNewest) return this
        val gap = if (newest > 0 && slot > newest + 1 && gapAfter == null) newest else gapAfter
        return merge(listOf(row)).copy(gapAfter = gap)
    }

    /** A post became `deleted`: its row stays as a tombstone. [postLogSeq] is the post's own position. */
    fun tombstone(postId: String, postLogSeq: Long?): MlsTimelineWindow {
        val marked = posts.map {
            if (it.postId == postId || (postLogSeq != null && it.logSeq == postLogSeq)) it.copy(state = MlsGroupPostState.Deleted, text = null) else it
        }
        return copy(posts = marked, deleted = deleted + postId)
    }

    /** A `post_state` for a loaded row: only `sent` carries a slot. */
    fun withState(postId: String, state: MlsGroupPostState): MlsTimelineWindow =
        copy(posts = posts.map { if (it.postId == postId && it.state != MlsGroupPostState.Deleted) it.copy(state = state) else it })

    fun contains(postId: String) = postId.isNotEmpty() && posts.any { it.postId == postId }

    companion object {
        const val LIMIT = 600
    }
}

/** One line of the timeline after collapse. */
sealed interface MlsLine {
    /** A text, attachment, card, void or unknown row; the row is kept whole. */
    data class Row(val post: MlsGroupPost) : MlsLine {
        override fun toString() = "Row(<redacted>)"
    }

    /** Joined or left, collapsed: [names] holds at most [MlsLines.NAMES] entries; [count] is their number. */
    data class Group(val kind: MlsGroupChangeKind, val names: List<MlsLineName>, val count: Int, val logSeq: Long?) : MlsLine {
        override fun toString() = "Group(kind=$kind, count=$count)"
    }

    /** One removed, banned or role change line. [actor] is `by`, or null for the passive text. */
    data class Change(val change: MlsGroupChange, val actor: MlsGroupAuthor?, val logSeq: Long?) : MlsLine {
        override fun toString() = "Change(change=${change.change})"
    }

    /** A settings row; absent fields are not shown. A row with no known field is "Group updated". */
    data class Settings(val name: String?, val approval: Boolean?, val actor: MlsGroupAuthor?, val logSeq: Long?) : MlsLine {
        override fun toString() = "Settings(<redacted>)"
    }
}

data class MlsLineName(val display: String?, val isMe: Boolean, val aiFlag: Boolean) {
    override fun toString() = "MlsLineName(<redacted>)"
}

object MlsLines {
    const val NAMES = 500

    /**
     * Collapses [posts] (in log order). Inside one membership row the `joined` changes make one line, and so do the `left`
     * changes; consecutive joined (or left) rows with no other row between them merge into one line of up to [NAMES] names.
     * `removed`, `banned` and `role_changed` get one line each; device changes are never shown and do not break a run.
     */
    fun collapse(posts: List<MlsGroupPost>): List<MlsLine> {
        val out = ArrayList<MlsLine>(posts.size)
        for (post in posts) {
            when (post.kind) {
                MlsGroupPostKind.MEMBERSHIP -> membership(post, out)
                MlsGroupPostKind.SETTINGS -> out += MlsLine.Settings(post.settingsName, post.settingsApproval, post.by, post.logSeq)
                else -> out += MlsLine.Row(post)
            }
        }
        return out
    }

    private fun membership(post: MlsGroupPost, out: MutableList<MlsLine>) {
        for (kind in listOf(MlsGroupChangeKind.JOINED, MlsGroupChangeKind.LEFT)) {
            val names = post.changes.filter { it.change == kind }.map { MlsLineName(it.display, it.isMe, it.aiFlag) }
            if (names.isEmpty()) continue
            val last = out.lastOrNull()
            if (last is MlsLine.Group && last.kind == kind) {
                val all = last.names + names
                out[out.lastIndex] = last.copy(names = all.take(NAMES), count = minOf(last.count + names.size, NAMES), logSeq = post.logSeq)
            } else {
                out += MlsLine.Group(kind, names.take(NAMES), minOf(names.size, NAMES), post.logSeq)
            }
        }
        post.changes.filter {
            it.change == MlsGroupChangeKind.REMOVED || it.change == MlsGroupChangeKind.BANNED || it.change == MlsGroupChangeKind.ROLE_CHANGED
        }.forEach { out += MlsLine.Change(it, post.by, post.logSeq) }
        val shown = post.changes.any {
            it.change in setOf(
                MlsGroupChangeKind.JOINED, MlsGroupChangeKind.LEFT, MlsGroupChangeKind.REMOVED, MlsGroupChangeKind.BANNED, MlsGroupChangeKind.ROLE_CHANGED
            )
        }
        // A row of unknown changes only is "Group updated"; device-only rows are not shown at all.
        if (!shown && post.changes.any { it.change == MlsGroupChangeKind.UNKNOWN }) out += MlsLine.Settings(null, null, post.by, post.logSeq)
    }
}

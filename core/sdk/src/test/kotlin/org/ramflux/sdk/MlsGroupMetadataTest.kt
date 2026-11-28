// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MlsGroupMetadataTest {
    private fun reply(json: String) = SecretReply(SecretBytes.take(json.toByteArray()))
    private fun <T> ok(result: CoreResult<T>): T = (result as CoreResult.Success).value

    private val author = """{"anchor":"a1","display":"Ana","ai_flag":false,"is_me":false}"""

    @Test fun listReadsRowsCursorAndUnreadState() {
        val page = ok(
            MlsGroupMetadataReader.list(
                reply(
                    """{"groups":[{"group_id":"g1","name":"Design","tier":1000,"members":12,"leaves":12,"agents":1,"approval":true,
                    "my_role":"owner","host_node":"node.example","push":"mentions_replies","catch_up":{"state":"running","done":40,"total":120},
                    "privacy_delay":{"send_after":null},"quota":{"post":{"limit":20,"remaining":3,"first_day":true,"resets_at":99},
                    "commit":{"limit":5,"remaining":5,"first_day":false}},"last_log_seq":9,"last_activity_at":1700,"unread":2,"unread_mentions":1,
                    "read_log_seq":7,"last_post":{"log_seq":9,"kind":"text","author":$author,"snippet":"hi","appended_at":1700},
                    "warnings":[{"code":"host_ip_visible","params":{"host_node":"node.example"}}]}],"cursor":"l2"}"""
                )
            )
        )
        val row = page.groups.single()
        assertEquals("l2", page.cursor)
        assertEquals(MlsRole.OWNER, row.myRole)
        assertEquals(MlsCatchUpState.RUNNING, row.catchUp)
        assertEquals(MlsPrivacyDelay.Armed, row.privacyDelay)
        assertEquals(3L, row.quotaPost?.remaining)
        assertEquals(2L, row.unread)
        assertEquals("hi", row.lastPost?.snippet)
        assertEquals("host_ip_visible", row.warnings.single().code)
        assertFalse(row.toString().contains("Design"))
    }

    @Test fun listWithoutCursorIsTheLastPage() {
        assertNull(ok(MlsGroupMetadataReader.list(reply("""{"groups":[]}"""))).cursor)
    }

    @Test fun createAndInviteRowsKeepTheLinkOutOfToString() {
        val created = ok(
            MlsGroupMetadataReader.created(
                reply("""{"group_id":"g","invite":{"invite_id":"i","link":"rfinv-secret-0001","max_uses":100,"uses_left":100,"expires_at":5}}""")
            )
        )
        assertEquals("g", created.groupId)
        assertEquals(100L, created.invite.usesLeft)
        assertFalse(created.invite.toString().contains("rfinv"))
        val list = ok(
            MlsGroupMetadataReader.invites(
                reply("""{"invites":[{"invite_id":"i","link":"rfinv-a","max_uses":10,"uses_left":null,"expires_at":5,"revoked":true}]}""")
            )
        )
        assertTrue(list.single().revoked)
        assertNull(list.single().usesLeft)
    }

    @Test fun joinResultsAndEveryRefusalReasonMap() {
        assertEquals(MlsJoinMetadata.Joined("g"), ok(MlsGroupMetadataReader.join(reply("""{"state":"joined","group_id":"g"}"""))))
        val pending = ok(MlsGroupMetadataReader.join(reply("""{"state":"pending","request_id":"r","reason":"awaiting_member_online","expires_at":9}"""))) as MlsJoinMetadata.Pending
        assertEquals(MlsPendingReason.AWAITING_MEMBER_ONLINE, pending.reason)
        assertEquals(9L, pending.expiresAt)
        val expected = mapOf(
            "link_invalid" to MlsJoinRefusal.LINK_INVALID,
            "invite_expired" to MlsJoinRefusal.INVITE_EXPIRED,
            "invite_invalid" to MlsJoinRefusal.INVITE_INVALID,
            "group_full" to MlsJoinRefusal.GROUP_FULL,
            "queue_full" to MlsJoinRefusal.QUEUE_FULL,
            "request_expired" to MlsJoinRefusal.REQUEST_EXPIRED,
            "already_member" to MlsJoinRefusal.ALREADY_MEMBER,
            "rate_limited" to MlsJoinRefusal.RATE_LIMITED,
            "banana" to MlsJoinRefusal.UNKNOWN,
            // Removed by r4: they are unknown reasons now and show the generic text.
            "invite_unknown" to MlsJoinRefusal.UNKNOWN,
            "invite_used_up" to MlsJoinRefusal.UNKNOWN,
            "invite_revoked" to MlsJoinRefusal.UNKNOWN,
        )
        expected.forEach { (code, reason) ->
            val refused = ok(MlsGroupMetadataReader.join(reply("""{"state":"refused","reason":"$code","params":{}}"""))) as MlsJoinMetadata.Refused
            assertEquals(code, reason, refused.reason)
        }
        val typed = ok(
            MlsGroupMetadataReader.join(reply("""{"state":"refused","reason":"rate_limited","params":{"retry_after_s":10}}"""))
        ) as MlsJoinMetadata.Refused
        assertEquals(10L, typed.retryAfterSecs)
        val member = ok(
            MlsGroupMetadataReader.join(reply("""{"state":"refused","reason":"already_member","params":{"group_id":"g9"}}"""))
        ) as MlsJoinMetadata.Refused
        assertEquals("g9", member.groupId)
        assertEquals("banana", (ok(MlsGroupMetadataReader.join(reply("""{"state":"refused","reason":"banana"}"""))) as MlsJoinMetadata.Refused).rawReason)
        assertTrue(MlsGroupMetadataReader.join(reply("""{"state":"mystery"}""")) is CoreResult.Failure)
    }

    @Test fun pendingJoinRequestsRead() {
        val rows = ok(
            MlsGroupMetadataReader.pending(
                reply("""{"requests":[{"request_id":"r","group_id":"g","host_node":"node.example","reason":"awaiting_admin","requested_at":1,"expires_at":2}]}""")
            )
        )
        assertEquals("g", rows.single().groupId)
        assertEquals(MlsPendingReason.AWAITING_ADMIN, rows.single().reason)
    }

    @Test fun membersReadRolesNullDisplayAndDevices() {
        val page = ok(
            MlsGroupMetadataReader.members(
                reply(
                    """{"rows":[{"anchor":"a","display":null,"role":"admin","ai_flag":true,"devices":3,"is_me":false},
                    {"anchor":"b","display":"Ben","role":"zzz","ai_flag":false,"devices":1,"is_me":true}],"cursor":"p2"}"""
                )
            )
        )
        assertNull(page.rows[0].display)
        assertEquals(MlsRole.ADMIN, page.rows[0].role)
        assertEquals(3L, page.rows[0].devices)
        assertEquals(MlsRole.UNKNOWN, page.rows[1].role)
        assertTrue(page.rows[1].isMe)
        assertEquals("p2", page.cursor)
    }

    @Test fun historyReadsEveryKindAndAnOwnUnsentRow() {
        val history = ok(
            MlsGroupMetadataReader.history(
                reply(
                    """{"posts":[
                    {"log_seq":1,"post_id":"p1","author":$author,"appended_at":10,"kind":"text","body":"hello","reply_to":"p0","mentions":["a1"],"state":"sent"},
                    {"log_seq":2,"post_id":"p2","author":$author,"kind":"text","body":"","state":"deleted"},
                    {"log_seq":3,"post_id":"p3","kind":"membership","state":"sent","body":{"changes":[
                      {"change":"joined","anchor":"x","display":null,"ai_flag":true,"is_me":false},
                      {"change":"removed","anchor":"y","display":"Chen","ai_flag":false,"is_me":true},
                      {"change":"role_changed","anchor":"z","display":"Dana","ai_flag":false,"role":"admin"}],"by":$author}},
                    {"log_seq":4,"post_id":"p4","kind":"settings","state":"sent","body":{"name":"New","approval":false,"by":$author}},
                    {"log_seq":5,"post_id":"p5","kind":"void","state":"sent","body":{"voided_log_seq":3,"epoch":2,"by":$author}},
                    {"log_seq":6,"post_id":"p6","kind":"poll","author":$author,"body":{"q":1},"state":"sent"},
                    {"log_seq":null,"post_id":"p7","author":$author,"appended_at":null,"kind":"text","body":"queued text","state":"pending",
                     "send":{"state":"delayed_privacy","params":{"send_after":55,"delay_s":9}}}],
                    "floor_log_seq":1,"last_log_seq":6,"more":true}"""
                )
            )
        )
        assertTrue(history.more)
        assertEquals(1L, history.floorLogSeq)
        val posts = history.posts
        assertEquals("hello", posts[0].text)
        assertEquals(listOf("a1"), posts[0].mentions)
        assertEquals(MlsPostState.Deleted, posts[1].state)
        assertEquals(MlsPostKind.MEMBERSHIP, posts[2].kind)
        assertNull(posts[2].author)
        assertEquals("a1", posts[2].by?.anchor)
        assertEquals(listOf(MlsChangeKind.JOINED, MlsChangeKind.REMOVED, MlsChangeKind.ROLE_CHANGED), posts[2].changes.map { it.change })
        assertTrue(posts[2].changes[0].aiFlag)
        assertTrue(posts[2].changes[1].isMe)
        assertEquals(MlsRole.ADMIN, posts[2].changes[2].role)
        assertEquals("New", posts[3].settings?.name)
        assertEquals(false, posts[3].settings?.approval)
        assertEquals(MlsPostKind.VOID, posts[4].kind)
        assertEquals(MlsPostKind.UNKNOWN, posts[5].kind)
        assertEquals("poll", posts[5].rawKind)
        assertNull(posts[6].logSeq)
        assertEquals(MlsPostState.Delayed(55, 9), posts[6].state)
    }

    @Test fun sendStatesAndTheDeleteResultParse() {
        fun sent(json: String) = ok(MlsGroupMetadataReader.sent(reply(json))).state
        assertEquals(MlsPostState.Sent(7), sent("""{"post_id":"p","state":"sent","params":{"log_seq":7}}"""))
        assertEquals(MlsPostState.Queued(MlsQueuedReason.CATCHING_UP), sent("""{"post_id":"p","state":"queued","params":{"reason":"catching_up"}}"""))
        assertEquals(MlsPostState.Queued(MlsQueuedReason.HOST_UNREACHABLE), sent("""{"post_id":"p","state":"queued","params":{"reason":"host_unreachable"}}"""))
        assertEquals(MlsPostState.Queued(MlsQueuedReason.FETCHING_TOKENS), sent("""{"post_id":"p","state":"queued","params":{"reason":"fetching_tokens"}}"""))
        assertEquals(MlsPostState.Queued(MlsQueuedReason.UNKNOWN), sent("""{"post_id":"p","state":"queued","params":{"reason":"x"}}"""))
        assertEquals(MlsPostState.Delayed(9, 90), sent("""{"post_id":"p","state":"delayed_privacy","params":{"send_after":9,"delay_s":90}}"""))
        val limited = sent("""{"post_id":"p","state":"rate_limited","params":{"bucket":"post","limit":20,"remaining":0,"first_day":true,"resets_at":8}}""")
        assertEquals(MlsPostState.RateLimited(MlsQuota(20, 0, true, 8), "post"), limited)
        assertEquals(MlsPostState.Failed("x"), sent("""{"post_id":"p","state":"failed","params":{"reason":"x"}}"""))
        assertEquals(MlsPostState.Unknown("parked"), sent("""{"post_id":"p","state":"parked"}"""))
        assertEquals(MlsPostState.Deleted, ok(MlsGroupMetadataReader.deleted(reply("""{"post_id":"p","state":"deleted","log_seq":4}"""))).state)
        assertEquals(
            MlsPostState.Delayed(5, null),
            ok(MlsGroupMetadataReader.deleted(reply("""{"post_id":"p","state":"delayed_privacy","params":{"send_after":5}}"""))).state,
        )
    }

    @Test fun refreshMarkReadAndCommitResultsParse() {
        val refreshed = ok(MlsGroupMetadataReader.refreshed(reply("""{"groups":[{"group_id":"g","last_log_seq":4,"new_posts":2}]}"""))).single()
        assertEquals(2L, refreshed.newPosts)
        val read = ok(MlsGroupMetadataReader.marked(reply("""{"read_log_seq":4,"unread":0,"unread_mentions":0}""")))
        assertEquals(4L, read.readLogSeq)
        assertTrue(ok(MlsGroupMetadataReader.committed(reply("""{"state":"committed","log_seq":8}"""))).committed)
        assertFalse(ok(MlsGroupMetadataReader.committed(reply("""{"state":"nope"}"""))).committed)
        assertEquals("revoked", ok(MlsGroupMetadataReader.state(reply("""{"state":"revoked"}"""))))
    }

    private fun event(json: String) = MlsGroupMetadataReader.event(reply(json))

    @Test fun everyEventKindParses() {
        val post = event("""{"method":"group.event","body":{"seq":5,"at":1,"group_id":"g","kind":"post","log_seq":3,
            "post":{"log_seq":3,"post_id":"p","author":$author,"appended_at":4,"kind":"text","body":"x","state":"sent"}}}""") as MlsEventMetadata.Post
        assertEquals(3L, post.post.logSeq)
        assertEquals("g", post.groupId)
        val state = event("""{"body":{"group_id":"g","kind":"post_state","post_id":"p","state":"sent","params":{"log_seq":3},"log_seq":3}}""") as MlsEventMetadata.PostState
        assertEquals(MlsPostState.Sent(3), state.state)
        val deleted = event("""{"body":{"group_id":"g","kind":"post_deleted","post_id":"p","post_log_seq":3,"log_seq":9}}""") as MlsEventMetadata.PostDeleted
        assertEquals(3L, deleted.postLogSeq)
        assertEquals(9L, deleted.logSeq)
        val membership = event("""{"body":{"group_id":"g","kind":"membership","log_seq":4,"body":{"changes":[{"change":"left","anchor":"y","display":null,"ai_flag":false,"is_me":true}]}}}""") as MlsEventMetadata.Service
        assertTrue(membership.post.changes.single().isMe)
        assertEquals(4L, membership.post.logSeq)
        val settings = event("""{"body":{"group_id":"g","kind":"settings","log_seq":5,"body":{"approval":true,"by":$author}}}""") as MlsEventMetadata.Service
        assertEquals(true, settings.post.settings?.approval)
        val joinedResult = event("""{"body":{"group_id":"g","kind":"join_result","request_id":"r","state":"joined"}}""") as MlsEventMetadata.JoinResult
        assertTrue(joinedResult.joined)
        val refusedResult = event("""{"body":{"group_id":"g","kind":"join_result","request_id":"r","state":"refused","reason":"request_expired","params":{"expired_at":5}}}""") as MlsEventMetadata.JoinResult
        assertEquals(MlsJoinRefusal.REQUEST_EXPIRED, refusedResult.refusal?.reason)
        val read = event("""{"body":{"group_id":"g","kind":"read","read_log_seq":3,"unread":1,"unread_mentions":0}}""") as MlsEventMetadata.Read
        assertEquals(1L, read.read.unread)
        listOf("pending_join", "catch_up", "warning", "void", "somethingnew").forEach {
            assertEquals(it, (event("""{"body":{"group_id":"g","kind":"$it"}}""") as MlsEventMetadata.Skipped).kind)
        }
        assertNull(event("""{"method":"contact.identity_changed","body":{"group_id":"g","kind":"post"}}"""))
        assertNull(event("not json"))
    }

    @Test fun unknownEnumValuesAreKeptAsUnknown() {
        val row = ok(MlsGroupMetadataReader.info(reply("""{"group_id":"g","name":"n","my_role":"boss","push":"loud","catch_up":{"state":"weird"}}""")))
        assertEquals(MlsRole.UNKNOWN, row.myRole)
        assertEquals(MlsPush.UNKNOWN, row.push)
        assertEquals(MlsCatchUpState.IDLE, row.catchUp)
    }
}

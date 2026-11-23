// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

class AccountSecurityDataTest {
    private fun result(outcome: String) = Json.parseToJsonElement("""{"schema":"ramflux.pairing.LocalResult.v2","version":2,"outcome":$outcome}""").jsonObject
    private fun update(phase: String, extra: String = "") =
        """{"tag":"update","value":{"schema":"ramflux.pairing.Update.v2","version":2,"operation_id":"op","generation":3,"phase":"$phase","expires_at":2000000000,"transcript_hash":"th","emoji_indices":[1,2,3,4,5,6,63],"peer_ref":{"account_anchor":"anchor","device_id":"laptop","device_epoch":1},"peer_name":"Laptop","capabilities":["call","message"],"lease_not_before":1,"lease_expires_at":1700000000,"proposal_hash":$extra,"record_hash":null,"result":null,"cleanup":"none"}}"""

    @Test fun beginSendsTheSdkBeginShapeAndKeepsTheInvitationAsErasableBytes() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply {
            completionBody = result("""{"tag":"begin","value":{"schema":"ramflux.pairing.BeginResult.v2","version":2,"operation_id":"op","generation":1,"expires_at":2000000000,"invite_token":{"schema":"ramflux.pairing.InviteToken.v2","recipient_token":"secret-token"}}}""")
        }
        val client = (CoreClient.open(abi, "/test/pair-begin", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultAccountSecurityRepository(SdkRepositoryTransport { client }) { 1_000L }
            val invitation = (repo.beginPairing("account", "Pixel") as ClientResult.Ready).value
            assertEquals("op", invitation.operationId)
            assertEquals(1uL, invitation.generation)
            assertFalse(invitation.toString().contains("secret-token"))
            val shown = invitation.token.useOnce { String(it, Charsets.UTF_8) }
            assertTrue(shown.contains("secret-token"))
            invitation.close()
            val sent = abi.submitted.single()
            assertEquals("device.pair.begin", sent["method"]?.jsonPrimitive?.content)
            val body = sent["body"]!!.jsonObject
            assertEquals("ramflux.pairing.Begin.v2", body["schema"]?.jsonPrimitive?.content)
            assertEquals(listOf("call", "group", "message", "register", "sync"), body["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(1_000L, body["lease_not_before"]?.jsonPrimitive?.long)
            assertEquals(1_000L + AccountSecurityRepository.DEFAULT_LEASE_SECONDS, body["lease_expires_at"]?.jsonPrimitive?.long)
            assertTrue(repo.beginPairing("account", "") is ClientResult.Failed)
            assertEquals(1, abi.submitted.size)
        } finally { client.close() }
    }

    @Test fun statusConfirmApproveAndRefusalFollowTheSdkPhases() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = result(update("sas_pending", "null")) }
        val client = (CoreClient.open(abi, "/test/pair-status", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultAccountSecurityRepository(SdkRepositoryTransport { client })
            val state = (repo.pairingStatus("account", "op", 3u) as ClientResult.Ready).value
            assertEquals(PairingPhase.SAS_PENDING, state.phase)
            assertEquals("laptop", state.peerDevice)
            assertEquals("Laptop", state.peerName)
            assertEquals(listOf(1, 2, 3, 4, 5, 6, 63), state.emojiIndices)
            assertTrue("approval needs the proposal phase", repo.approvePairing("account", state, true) is ClientResult.Failed)
            abi.completionBody = result(update("proposal_pending", "\"ph\"").replace("\"emoji_indices\":[1,2,3,4,5,6,63]", "\"emoji_indices\":null"))
            val proposed = (repo.confirmPairing("account", state, true) as ClientResult.Ready).value
            val confirm = abi.submitted.last()
            assertEquals("device.pair.confirm", confirm["method"]?.jsonPrimitive?.content)
            assertEquals("th", confirm["body"]!!.jsonObject["transcript_hash"]?.jsonPrimitive?.content)
            assertEquals(true, confirm["body"]!!.jsonObject["match"]?.jsonPrimitive?.boolean)
            assertEquals(PairingPhase.PROPOSAL_PENDING, proposed.phase)
            abi.completionBody = result("""{"tag":"error","error":{"schema":"ramflux.pairing.Failure.v2","version":2,"code":"Expired","retry_after_ms":null}}""")
            val refused = repo.approvePairing("account", proposed, true) as ClientResult.Failed
            assertEquals("Expired", refused.problem.statusName)
            assertEquals(ClientOutcome.REFUSED, refused.problem.outcome)
            assertEquals("ph", abi.submitted.last()["body"]!!.jsonObject["proposal_hash"]?.jsonPrimitive?.content)
            abi.completionBody = result(update("cancelled", "null").replace("\"result\":null", "\"result\":\"Cancelled\""))
            assertEquals(PairingPhase.CANCELLED, (repo.cancelPairing("account", "op", 3u) as ClientResult.Ready).value.phase)
            abi.completionBody = result(update("unknown_phase", "null"))
            assertTrue(repo.pairingStatus("account", "op", 3u) is ClientResult.Failed)
        } finally { client.close() }
    }

    @Test fun requestPolicyAndAccountCodeChangeUseTheSdkFieldsAndWipeCodes() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = Json.parseToJsonElement("""{"contact_requests":"manual","authority_floors":{}}""").jsonObject }
        val client = (CoreClient.open(abi, "/test/security", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultAccountSecurityRepository(SdkRepositoryTransport { client })
            assertEquals(ContactRequestPolicy.MANUAL, (repo.requestPolicy("account") as ClientResult.Ready).value)
            abi.completionBody = Json.parseToJsonElement("""{"contact_requests":"auto_accept","cleared_authority_floors":0}""").jsonObject
            assertEquals(ContactRequestPolicy.AUTO_ACCEPT, (repo.setRequestPolicy("account", ContactRequestPolicy.AUTO_ACCEPT) as ClientResult.Ready).value)
            assertEquals("auto_accept", abi.submitted.last()["body"]!!.jsonObject["contact_requests"]?.jsonPrimitive?.content)
            abi.completionBody = Json.parseToJsonElement("""{"contact_requests":"everyone"}""").jsonObject
            assertTrue(repo.requestPolicy("account") is ClientResult.Failed)

            val count = abi.submitted.size
            val trivial = "123456".toCharArray()
            val current = "258036".toCharArray()
            assertEquals("trivial_code", (repo.changeAccountCode("account", current, trivial) as ClientResult.Failed).problem.statusName)
            assertTrue(trivial.all { it == '\u0000' } && current.all { it == '\u0000' })
            assertEquals(SAME_CODE, (repo.changeAccountCode("account", "258036".toCharArray(), "258036".toCharArray()) as ClientResult.Failed).problem.statusName)
            assertEquals(count, abi.submitted.size)
            abi.completionBody = Json.parseToJsonElement("""{"local_account_id":"account","rotated":true}""").jsonObject
            val next = "740395".toCharArray()
            assertTrue(repo.changeAccountCode("account", "258036".toCharArray(), next) is ClientResult.Ready)
            assertTrue(next.all { it == '\u0000' })
            val rotate = abi.submitted.last()
            assertEquals("account.passphrase.rotate", rotate["method"]?.jsonPrimitive?.content)
            assertEquals("258036", rotate["body"]!!.jsonObject["old_passphrase"]?.jsonPrimitive?.content)
            assertEquals("740395", rotate["body"]!!.jsonObject["new_passphrase"]?.jsonPrimitive?.content)
        } finally { client.close() }
    }
}

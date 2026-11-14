// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class GroupMetadataTest {
    @Test fun exactEpochAndRootQualifiedRosterNeverGrantControlFromDeviceNameAlone() {
        val raw = """{"group_id":"g","name":"Actual name","group_epoch":18446744073709551615,"members":["same"],"roles":{"same":"owner"},"member_roster":[{"identity":{"principal_id":"own","root_commitment":"own-root","device_id":"same"},"role":"admin"},{"identity":{"principal_id":"peer","root_commitment":"peer-root","device_id":"same"},"role":"owner"}],"excluded_devices":[],"sender_key":"must-not-copy"}""".toByteArray()
        val result = (GroupMetadataReader.detail(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value
        assertEquals(ULong.MAX_VALUE, result.group.epoch)
        assertFalse(result.roster.first().active)
        assertTrue(result.roster.last().active)
        assertTrue(raw.all { it == 0.toByte() })
    }
    @Test fun pendingOwnerAndMalformedMutationAcknowledgementRemainDistinct() {
        val raw = """{"group_id":"g","accepted":true,"pending":true,"request_id":"request"}""".toByteArray()
        val result = (GroupMetadataReader.outcome(SecretReply(SecretBytes.take(raw)), "g", null) as CoreResult.Success).value
        assertTrue(result.waiting)
        val wrong = """{"group_id":"other","accepted":true}""".toByteArray()
        assertTrue(GroupMetadataReader.outcome(SecretReply(SecretBytes.take(wrong)), "g", null) is CoreResult.Failure)
        assertTrue(wrong.all { it == 0.toByte() })
    }
}

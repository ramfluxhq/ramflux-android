// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class TaskMetadataTest {
    @Test fun exactRevisionStepsAndApprovedDisplayFieldsConsumeAndWipeReply() {
        val raw = """{"tasks":[{"task_id":"t","conversation_id":"c","version":18446744073709551615,"title":"Actual","state":"needs_me","requester":"human","assignee":"agent","steps":[{"id":"s","title":"Review","state":"needs_me","needs_me":{"kind":"approval","credential":"never-copy"}}],"result_summary":null,"pending_resolution":null,"stalled_by_assignee":true,"private_key":"never-copy"}],"next_cursor":"opaque"}""".toByteArray()
        val page = (TaskMetadataReader.page(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value
        assertEquals(ULong.MAX_VALUE, page.rows.single().version)
        assertEquals("approval", page.rows.single().steps.single().intervention)
        assertEquals("opaque", page.cursor); assertTrue(page.rows.single().stalled)
        assertFalse(page.rows.single().toString().contains("never-copy")); assertTrue(raw.all { it == 0.toByte() })
    }
    @Test fun permissionsKeepGrantApprovalAndCompactReceiptAsDifferentShapes() {
        val raw = permissionFixture().toByteArray()
        val rows = (PermissionMetadataReader.read(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value
        assertEquals(listOf(PermissionDisplayKind.GRANT, PermissionDisplayKind.APPROVAL, PermissionDisplayKind.RECEIPT), rows.map { it.kind })
        assertEquals(ULong.MAX_VALUE, rows.last().openingRevision); assertNull(rows.last().tool)
        assertEquals("task", rows.last().task); assertEquals("executor", rows.last().executor)
        assertEquals(listOf("read"), rows.first().capabilities)
        assertFalse(rows.toString().contains("never-copy")); assertTrue(raw.all { it == 0.toByte() })
    }
    @Test fun malformedMetadataIsNotAnEmptySuccess() {
        val raw = """{"tasks":[{"version":-1}],"next_cursor":null}""".toByteArray()
        assertTrue(TaskMetadataReader.page(SecretReply(SecretBytes.take(raw))) is CoreResult.Failure)
        assertTrue(raw.all { it == 0.toByte() })
        val invalid = permissionFixture().replace("2000000000", "-1").toByteArray()
        assertTrue(PermissionMetadataReader.read(SecretReply(SecretBytes.take(invalid))) is CoreResult.Failure)
    }
    private fun permissionFixture() = """{"grants":[{"grant_id":"g","state":{"server_id":"server","tool_name":"tool","tool_scope":"scope","registry_hash":"registry","tool_manifest_set_hash":"manifest","full_delegation":false,"allowed_capabilities":["read"],"revoked":false,"expires_at":2000000000},"signing_body":{"arguments_hash":"hash","credential":"never-copy"}}],"approvals":[{"approval_id":"a","server_id":"server","tool_name":"tool","capability":"send","status":"pending","expires_at":2000000000,"details":{"arguments_hash":"hash"}},{"approval_id":"r","decision_id":"d","grant_id":"g","status":"approved","delivery_state":"submitted","reason_code":null,"expires_at":2000000000,"task_binding":{"conversation_id":"c","task_id":"task","step_id":"step","opening_revision":18446744073709551615,"requester":"human","assignee":"agent","executor_device_id":"executor"}}]}"""
}

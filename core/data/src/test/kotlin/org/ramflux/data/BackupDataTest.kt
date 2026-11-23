// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

class BackupDataTest {
    private class Files : BackupFileStore {
        val deleted = mutableListOf<String>()
        var next = 0
        override fun newArchivePath() = "/private/backups/${next++}.rfbackup"
        override suspend fun copyOut(path: String, destination: Uri) = true
        override suspend fun copyIn(source: Uri, limit: Long): ImportCopy = ImportCopy.Failed
        override fun delete(path: String) { deleted += path }
        override fun owns(path: String) = path.startsWith("/private/backups/")
    }
    private fun job(id: String, state: String, kind: String = "backup", path: String = "/private/backups/0.rfbackup", extra: String = "") = Json.parseToJsonElement(
        """{"job":{"job_id":"$id","kind":"$kind","state":"$state","seq":3,"path":"$path","created_at":1700000000,"progress":{"category":"messages","items_done":4,"items_total":10,"bytes_done":400,"bytes_total":null}$extra}}"""
    ).jsonObject

    @Test fun createWritesToPrivateStorageWithTheArchivePassphraseAndFailuresLeaveNothing() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = job("j1", "preparing") }
        val client = (CoreClient.open(abi, "/test/backup-create", "a".repeat(64)) as CoreResult.Success).value
        try {
            val files = Files()
            val repo = DefaultBackupRepository(SdkRepositoryTransport { client }, files)
            val short = "too short".toCharArray()
            assertEquals(WEAK_PASSPHRASE, (repo.create("account", short) as ClientResult.Failed).problem.statusName)
            assertTrue(short.all { it == '\u0000' })
            assertTrue(abi.submitted.isEmpty())
            val secret = "a long archive passphrase".toCharArray()
            val created = (repo.create("account", secret) as ClientResult.Ready).value
            assertTrue(secret.all { it == '\u0000' })
            assertEquals(BackupJobState.PREPARING, created.state)
            assertEquals(4L, created.itemsDone)
            val sent = abi.submitted.single()
            assertEquals("account.backup.create", sent["method"]?.jsonPrimitive?.content)
            assertEquals("/private/backups/0.rfbackup", sent["body"]!!.jsonObject["output_path"]?.jsonPrimitive?.content)
            assertEquals("a long archive passphrase", sent["body"]!!.jsonObject["passphrase"]?.jsonPrimitive?.content)
            abi.completionBody = buildJsonObject { put("unexpected", true) }
            assertTrue(repo.create("account", "another long passphrase".toCharArray()) is ClientResult.Failed)
            assertEquals(listOf("/private/backups/1.rfbackup"), files.deleted)
        } finally { client.close() }
    }

    @Test fun jobsVerifyInstallAndCancelFollowTheSdkWithAndWithoutAnAccount() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply {
            completionBody = Json.parseToJsonElement("""{"jobs":[${job("old", "done")["job"]},${job("new", "writing").let { it["job"]!!.jsonObject + ("created_at" to JsonPrimitive(1800000000)) }.let(::JsonObject)}]}""").jsonObject
        }
        val client = (CoreClient.open(abi, "/test/backup-jobs", "a".repeat(64)) as CoreResult.Success).value
        try {
            val files = Files()
            val repo = DefaultBackupRepository(SdkRepositoryTransport { client }, files)
            assertEquals(listOf("new", "old"), (repo.jobs(null) as ClientResult.Ready).value.map { it.id })
            assertNull("a fresh install has no account", abi.submitted.last()["account_id"]?.takeIf { it !is JsonNull })

            abi.completionBody = job("r1", "verified", kind = "restore", path = "/private/backups/9.rfbackup",
                extra = ""","archive":{"local_account_id":"acct","account_label":"Lin","created_at":1700000000,"merge":false}""")
            val verified = (repo.verify(null, "/private/backups/9.rfbackup", "a long archive passphrase".toCharArray()) as ClientResult.Ready).value
            assertEquals(BackupArchive("acct", "Lin", 1700000000, false), verified.archive)
            assertEquals("/private/backups/9.rfbackup", abi.submitted.last()["body"]!!.jsonObject["input_path"]?.jsonPrimitive?.content)

            val trivial = "123456".toCharArray()
            assertEquals("trivial_code", (repo.install(null, "r1", trivial) as ClientResult.Failed).problem.statusName)
            assertTrue(trivial.all { it == '\u0000' })
            abi.completionBody = job("r1", "installing", kind = "restore", path = "/private/backups/9.rfbackup")
            assertEquals(BackupJobState.INSTALLING, (repo.install(null, "r1", "258036".toCharArray()) as ClientResult.Ready).value.state)
            assertEquals("258036", abi.submitted.last()["body"]!!.jsonObject["passphrase"]?.jsonPrimitive?.content)
            assertTrue((repo.install("acct", "r1", null) as ClientResult.Ready).value.state == BackupJobState.INSTALLING)
            assertFalse("a merge sends no code", abi.submitted.last()["body"]!!.jsonObject.containsKey("passphrase"))

            abi.completionBody = job("r1", "cancelled", kind = "restore", path = "/private/backups/9.rfbackup")
            assertEquals(BackupJobState.CANCELLED, (repo.cancel(null, "r1") as ClientResult.Ready).value.state)
            repo.discard("r1")
            assertEquals(listOf("/private/backups/9.rfbackup"), files.deleted)
            repo.discard("/etc/hosts")
            assertEquals("never deletes outside the private store", 1, files.deleted.size)
        } finally { client.close() }
    }

    @Test fun boundedCopyRefusesMoreThanTheLimitWithoutWritingPastIt() {
        val out = ByteArrayOutputStream()
        assertEquals(10L, copyBounded(ByteArrayInputStream(ByteArray(10)), out, 10))
        val over = ByteArrayOutputStream()
        assertNull(copyBounded(ByteArrayInputStream(ByteArray(200_000)), over, 100_000))
        assertTrue(over.size() <= 100_000)
        assertEquals(256L * 1024 * 1024, BackupRepository.MAX_IMPORT_BYTES)
    }
}

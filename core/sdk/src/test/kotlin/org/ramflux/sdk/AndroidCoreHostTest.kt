// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AndroidCoreHostTest {
    @Test fun foregroundSharesOneCoreAndBackgroundClosesIt() = runTest {
        val native = HostNative()
        val host = AndroidCoreHost(native, "/private/no_backup", "a".repeat(64))
        val first = host.foreground() as CoreResult.Success
        val second = host.foreground() as CoreResult.Success
        assertSame(first.value, second.value)
        assertEquals(1, native.opens)
        assertTrue(host.background() is CoreResult.Success)
        assertEquals(1, native.closes)
        assertEquals(HostState.Stopped, host.state.value)
    }

    @Test fun failedShutdownNeverAuthorizesReplacementWriter() = runTest {
        val native = HostNative().apply { closeCode = 10 }
        val host = AndroidCoreHost(native, "/private/no_backup", "a".repeat(64))
        assertTrue(host.foreground() is CoreResult.Success)
        assertTrue(host.background() is CoreResult.Failure)
        assertTrue(host.foreground() is CoreResult.Failure)
        assertEquals("failed shutdown cannot open a replacement", 1, native.opens)
    }

    private class HostNative : NativeCore {
        var opens = 0
        var closes = 0
        var closeCode = 0
        override fun negotiate() = NativeReply(0, """{
            "abi_major":2,"abi_minor":0,"bus_protocol":"ramflux.local_bus.v1",
            "max_frame_bytes":1048576,"message_descriptor_blake3":"${"a".repeat(64)}",
            "methods":["account.list"]
        }""".toByteArray())
        override fun open(config: ByteArray): NativeReply { opens++; return NativeReply(0, byteArrayOf(), 1) }
        override fun close(core: Long): NativeReply { closes++; return NativeReply(closeCode, byteArrayOf()) }
        override fun submit(core: Long, request: ByteArray) = NativeReply(1, byteArrayOf())
        override fun operationPoll(core: Long, operation: Long) = NativeReply(24, byteArrayOf())
        override fun operationCancel(core: Long, operation: Long) = 0
        override fun operationRelease(core: Long, operation: Long) = 0
        override fun eventPoll(core: Long) = NativeReply(24, byteArrayOf())
        override fun eventReset(core: Long) = 0
    }
}

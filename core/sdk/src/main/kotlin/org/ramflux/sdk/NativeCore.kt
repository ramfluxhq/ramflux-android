// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/** Nonblocking C ABI v2 operations, except open/close which run on the IO dispatcher. */
interface NativeCore {
    fun negotiate(): NativeReply
    fun open(config: ByteArray): NativeReply
    fun releaseClosed(core: Long): Int
    fun close(core: Long): NativeReply
    /** Caller retains a zeroed one-slot array; an admitted token survives even reply-allocation OOM. */
    fun submit(core: Long, request: ByteArray, ownership: LongArray = LongArray(1)): NativeReply
    fun operationPoll(core: Long, operation: Long): NativeReply
    fun operationCancel(core: Long, operation: Long): Int
    fun operationRelease(core: Long, operation: Long): Int
    fun eventReset(core: Long, generation: Long): Int
    fun eventPoll(core: Long): NativeReply
}

/** One JNI translation unit calls the shared C ABI; no business code crosses this seam. */
class JniCore : NativeCore {
    override external fun negotiate(): NativeReply
    override external fun open(config: ByteArray): NativeReply
    override external fun releaseClosed(core: Long): Int
    override external fun close(core: Long): NativeReply
    override fun submit(core: Long, request: ByteArray, ownership: LongArray): NativeReply {
        require(ownership.size == 1 && ownership[0] == 0L)
        val code = submitInto(core, request, ownership)
        // Allocation may fail here; the caller already owns the token in ownership[0].
        return NativeReply(code, byteArrayOf(), ownership[0])
    }
    private external fun submitInto(core: Long, request: ByteArray, ownership: LongArray): Int
    override external fun operationPoll(core: Long, operation: Long): NativeReply
    override external fun operationCancel(core: Long, operation: Long): Int
    override external fun operationRelease(core: Long, operation: Long): Int
    override external fun eventReset(core: Long, generation: Long): Int
    override external fun eventPoll(core: Long): NativeReply

    companion object {
        fun load(): JniCore {
            System.loadLibrary("ramflux_sdk")
            System.loadLibrary("ramflux_android")
            return JniCore()
        }
    }
}

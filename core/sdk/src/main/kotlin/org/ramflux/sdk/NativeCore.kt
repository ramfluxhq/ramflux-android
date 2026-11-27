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
    /** Stops networking and closes files; unlocked accounts stay in memory. Blocking: call on the IO dispatcher. */
    fun suspendClient(core: Long): Int = UNSUPPORTED
    /** Reopens files and reconnects without the account code. Blocking: call on the IO dispatcher. */
    fun resumeClient(core: Long): Int = UNSUPPORTED
    /**
     * `ramflux_v2_username_authorize`: [publicRequest] is the public JSON, [phrase] the recovery phrase as UTF-8. Code 0
     * carries the signed JSON, any other code the error JSON. Blocking: the caller erases both arrays.
     */
    fun usernameAuthorize(core: Long, publicRequest: ByteArray, phrase: ByteArray): NativeReply = NativeReply(UNSUPPORTED, byteArrayOf())

    companion object {
        /** Status of a core without suspend support (InvalidArgument); only test doubles lack it. */
        const val UNSUPPORTED = 1
    }
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
    override external fun suspendClient(core: Long): Int
    override external fun resumeClient(core: Long): Int
    override external fun usernameAuthorize(core: Long, publicRequest: ByteArray, phrase: ByteArray): NativeReply

    companion object {
        fun load(): JniCore {
            System.loadLibrary("ramflux_sdk")
            System.loadLibrary("ramflux_android")
            return JniCore()
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/** Nonblocking C ABI v2 operations, except open/close which run on the IO dispatcher. */
interface NativeCore {
    fun negotiate(): NativeReply
    fun open(config: ByteArray): NativeReply
    fun releaseClosed(core: Long): Int
    fun close(core: Long): NativeReply
    fun submit(core: Long, request: ByteArray): NativeReply
    fun operationPoll(core: Long, operation: Long): NativeReply
    fun operationCancel(core: Long, operation: Long): Int
    fun operationRelease(core: Long, operation: Long): Int
    fun eventReset(core: Long): Int
    fun eventPoll(core: Long): NativeReply
}

/** One JNI translation unit calls the shared C ABI; no business code crosses this seam. */
class JniCore : NativeCore {
    override external fun negotiate(): NativeReply
    override external fun open(config: ByteArray): NativeReply
    override external fun releaseClosed(core: Long): Int
    override external fun close(core: Long): NativeReply
    override external fun submit(core: Long, request: ByteArray): NativeReply
    override external fun operationPoll(core: Long, operation: Long): NativeReply
    override external fun operationCancel(core: Long, operation: Long): Int
    override external fun operationRelease(core: Long, operation: Long): Int
    override external fun eventReset(core: Long): Int
    override external fun eventPoll(core: Long): NativeReply

    companion object {
        fun load(): JniCore {
            System.loadLibrary("ramflux_sdk")
            System.loadLibrary("ramflux_android")
            return JniCore()
        }
    }
}

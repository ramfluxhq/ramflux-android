// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

/**
 * One native answer of the standalone card runtime. [status] is the runtime status (0 OK; see ramflux_card.h), [session]
 * the handle, [json] the single callback output (null when the call emitted none).
 */
internal class CardFixtureReply(val status: Int, val session: Long, val json: ByteArray?)

/**
 * The realrun-only JNI binding (libramflux_card_fixture.so). Every call must run on the renderer thread of
 * [CardFixtureRuntime]: the runtime keeps its handles on the thread that created them.
 */
internal object CardFixtureNative {
    /** False when the test library is not packaged; the gallery then reports the runtime as unavailable. */
    val loaded: Boolean = runCatching {
        System.loadLibrary("ramflux_sdk")
        System.loadLibrary("ramflux_card_fixture")
    }.isSuccess

    external fun abiVersion(): Int

    external fun trustPackage(): CardFixtureReply

    external fun open(
        spec: ByteArray,
        bytecode: ByteArray,
        signature: ByteArray,
        trustedKey: ByteArray,
        descriptor: ByteArray,
        policy: ByteArray,
        presentation: ByteArray
    ): CardFixtureReply

    external fun tree(session: Long): CardFixtureReply

    external fun present(session: Long, input: ByteArray): CardFixtureReply

    external fun event(session: Long, input: ByteArray): CardFixtureReply

    external fun suspendCard(session: Long): CardFixtureReply

    external fun resume(session: Long): CardFixtureReply

    external fun dispose(session: Long): Int
}

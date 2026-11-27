// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import java.io.Closeable

/**
 * The prepare step of a username change. [NoChange] carries the directory's reply when the change is a no-op; [Signed]
 * carries the draft the phone authorized with the recovery phrase, ready for the submit phase. [operationId] is the
 * signed operation's id, or null when the signed draft does not name one.
 */
sealed class UsernamePrepared : Closeable {
    class NoChange(val status: SecretReply) : UsernamePrepared() {
        override fun close() = status.close()
    }

    class Signed(val operationId: String?, private val signed: SecretReply) : UsernamePrepared() {
        /** The `username.mutate` submit body, `{"phase":"submit","signed":<signed>}`; consumes the signed draft. */
        fun submitBody(): SecretBody = signed.useOnce { raw ->
            val head = "{\"phase\":\"submit\",\"signed\":".toByteArray()
            val tail = "}".toByteArray()
            val body = ByteArray(head.size + raw.size + tail.size)
            head.copyInto(body)
            raw.copyInto(body, head.size)
            tail.copyInto(body, head.size + raw.size)
            head.fill(0)
            tail.fill(0)
            SecretBody.takeJson(body)
        }

        override fun close() = signed.close()
    }
}

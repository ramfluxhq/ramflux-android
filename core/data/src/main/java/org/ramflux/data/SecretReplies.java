// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data;

import org.ramflux.sdk.SecretBytes;
import org.ramflux.sdk.SecretReply;

/**
 * Re-wraps a copy of one SDK reply so two readers can each consume it once: the SDK's display reader for
 * the timeline rows and the data layer's reader for the paging cursor and attachment references. The
 * caller hands over ownership of the copy; the reader that consumes it wipes it. This exists only until
 * the SDK message reader returns the cursor and attachment references itself.
 */
final class SecretReplies {
    private SecretReplies() {}

    static SecretReply take(byte[] owned) {
        return new SecretReply(SecretBytes.Companion.take(owned));
    }
}

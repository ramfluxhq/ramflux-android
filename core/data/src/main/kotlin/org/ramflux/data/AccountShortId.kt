// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/**
 * The short id line shown as a second line where two accounts can share a name (the account picker row and the remove
 * dialog): the first eight characters after the type prefix, grouped `xxxx xxxx`. An id too short to group is returned as
 * it is, and a blank id gives an empty line.
 */
fun shortAccountId(id: String): String {
    val body = id.substringAfter('-', id).filter { !it.isWhitespace() }
    val head = body.take(SHORT_ID_CHARS)
    return if (head.length > SHORT_ID_GROUP) head.take(SHORT_ID_GROUP) + " " + head.drop(SHORT_ID_GROUP) else head
}

private const val SHORT_ID_CHARS = 8
private const val SHORT_ID_GROUP = 4

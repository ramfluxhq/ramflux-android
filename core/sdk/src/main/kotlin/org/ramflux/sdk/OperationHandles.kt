// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/** Fixed ABI credit: registering an admitted token must not allocate after a JNI OOM. */
internal class OperationHandles {
    private val handles = LongArray(64)
    operator fun contains(handle: Long): Boolean = handles.any { it == handle }
    fun add(handle: Long) {
        if (handle in this) return
        val index = handles.indexOf(0L)
        check(handle != 0L && index >= 0) { "native operation credit exceeded" }
        handles[index] = handle
    }
    fun remove(handle: Long) {
        val index = handles.indexOf(handle)
        if (index >= 0) handles[index] = 0
    }
    fun isEmpty(): Boolean = handles.all { it == 0L }
    fun clear() = handles.fill(0)
    fun toList(): List<Long> = handles.filter { it != 0L }
}

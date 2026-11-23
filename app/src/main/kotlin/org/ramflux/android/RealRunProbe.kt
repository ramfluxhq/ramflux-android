// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import org.ramflux.designsystem.kit.SecretWordsProbe

/**
 * The recovery words as drawn, for the opt-in real-run suite only (`realrun` build type, scripts/realrun.sh):
 * its instrumentation runs in this process and must answer the word challenge of account creation. Every
 * other build type leaves the probe disabled and the Kit never calls it. Words are kept as erasable
 * characters, never as Strings, and erased by the suite after use; nothing is logged.
 */
object RealRunProbe : SecretWordsProbe {
    val enabled: Boolean = BuildConfig.BUILD_TYPE == "realrun"
    private val words = arrayOfNulls<CharArray>(MAX_WORDS)

    override fun seen(index: Int, chars: CharArray) {
        if (!enabled || index !in 0 until MAX_WORDS) return
        synchronized(words) {
            words[index]?.fill('\u0000')
            words[index] = chars.copyOf()
        }
    }

    /** Lends word [index] (0-based) to [block]; false when it was not drawn. */
    fun borrow(index: Int, block: (CharArray) -> Unit): Boolean = synchronized(words) {
        val word = words.getOrNull(index) ?: return false
        block(word)
        true
    }

    fun erase() = synchronized(words) {
        words.forEach { it?.fill('\u0000') }
        words.fill(null)
    }

    private const val MAX_WORDS = 24
}

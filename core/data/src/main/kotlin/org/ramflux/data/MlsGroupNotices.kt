// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context
import java.security.MessageDigest

/**
 * The one-time `host_ip_visible` notice: shown once per account and group, then remembered on this device. The key is a hash
 * of the account and group ids, so the stored text names no group, no link and no person.
 */
interface MlsGroupNotices {
    fun hostIpSeen(account: String, group: String): Boolean
    fun markHostIpSeen(account: String, group: String)

    /** Removes every flag of [account] (an account removal). // W: call it from the account-removal path */
    fun clearAccount(account: String)
}

/** For hosts without preferences (previews, the gallery): remembered for the process only. */
class InMemoryMlsGroupNotices : MlsGroupNotices {
    private val seen = HashSet<String>()
    override fun hostIpSeen(account: String, group: String) = key(account, group) in seen
    override fun markHostIpSeen(account: String, group: String) {
        seen.add(key(account, group))
    }

    override fun clearAccount(account: String) {
        seen.removeAll { it.startsWith(prefix(account)) }
    }
}

/** The app's UI preferences file; only the hashed key and a flag are written. // W: provide through Hilt */
class AndroidMlsGroupNotices(context: Context) : MlsGroupNotices {
    private val preferences = context.getSharedPreferences("mls-group-notices", Context.MODE_PRIVATE)
    override fun hostIpSeen(account: String, group: String) = preferences.getBoolean(key(account, group), false)
    override fun markHostIpSeen(account: String, group: String) {
        preferences.edit().putBoolean(key(account, group), true).apply()
    }

    override fun clearAccount(account: String) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix(account)) }.forEach { editor.remove(it) }
        editor.apply()
    }
}

private fun hash(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(KEY_HEX)

/** The keys of one account share a prefix, so removing the account removes its flags. */
private fun prefix(account: String) = "host_ip." + hash(account) + "."

private fun key(account: String, group: String) = prefix(account) + hash(account + "\u0000" + group)

private const val KEY_HEX = 32

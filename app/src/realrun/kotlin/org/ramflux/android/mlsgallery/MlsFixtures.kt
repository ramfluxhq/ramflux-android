// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import androidx.annotation.StringRes
import org.ramflux.android.R
import org.ramflux.data.AcceptOutcome
import org.ramflux.data.ConversationSecurity
import org.ramflux.data.IdentityChange
import org.ramflux.data.IdentitySignal
import org.ramflux.data.IdentityState
import org.ramflux.data.SecurityState
import org.ramflux.data.SecurityWarning

/** The account every fixture runs under. */
internal const val GALLERY_ACCOUNT = "gallery"

/** A neutral person of the fixtures; [root] is the contact identifier the identity methods take. */
internal data class MlsPerson(val key: String, val name: String, val username: String, val ai: Boolean = false) {
    val root: String get() = "root-$key"
    val conversation: String get() = "dm:$key"
    val link: String get() = "link-$key"
}

internal val ANA = MlsPerson("ana", "Ana", "ana")
internal val BEN = MlsPerson("ben", "Ben", "ben")
internal val CHEN = MlsPerson("chen", "Chen", "chen")
internal val SCOUT = MlsPerson("scout", "Scout", "scout", ai = true)

internal fun change(person: MlsPerson, next: String, previous: String = "A1") =
    IdentityChange(person.root, person.username, previous, next, person.ai, 0L)

private fun security(state: SecurityState, warning: SecurityWarning? = null, epoch: Long = 3, ai: Boolean = false) =
    ConversationSecurity(state, epoch, 2, ai, warning)

/**
 * What the fixture repository answers for one fixture (common design 5.1). [events] are injected after the identity
 * subscription starts, each after its delay in milliseconds. [accepts] are the outcomes of successive accepts (the last
 * repeats). [mismatchEvent] is injected one second after an accept answers [AcceptOutcome.ChangedAgain].
 */
internal class IdentityScript(
    val status: IdentityState,
    val pending: IdentityChange? = null,
    val events: List<Pair<Long, IdentitySignal>> = emptyList(),
    val accepts: List<AcceptOutcome> = listOf(AcceptOutcome.Accepted),
    val mismatchEvent: IdentityChange? = null,
    /** A send is refused with `identity_change_pending` until the change is accepted; the refusal makes the change pending. */
    val blocksSend: Boolean = false,
    /** Successive `conversation.security` reads; the last one repeats. */
    val security: List<ConversationSecurity> = listOf(security(SecurityState.OK)),
    /** Injected as `conversation.devices_changed` after the delay in milliseconds. */
    val devicesChangedAfterMs: List<Long> = emptyList()
)

internal class MlsFixture(val id: String, @param:StringRes val title: Int, val person: MlsPerson, val script: IdentityScript)

internal val FIXTURES: List<MlsFixture> = listOf(
    MlsFixture("F1-1", R.string.mls_fixture_f1_1, ANA, IdentityScript(IdentityState.TOFU)),
    MlsFixture("F1-2", R.string.mls_fixture_f1_2, BEN, IdentityScript(IdentityState.VERIFIED)),
    MlsFixture(
        "F1-3",
        R.string.mls_fixture_f1_3,
        CHEN,
        IdentityScript(
            IdentityState.CHANGED,
            events = listOf(1_000L to IdentitySignal.Changed(change(CHEN, "A2")))
        )
    ),
    MlsFixture("F1-4", R.string.mls_fixture_f1_4, CHEN, IdentityScript(IdentityState.TOFU, blocksSend = true)),
    MlsFixture(
        "F1-5",
        R.string.mls_fixture_f1_5,
        CHEN,
        IdentityScript(
            IdentityState.CHANGED,
            events = listOf(1_000L to IdentitySignal.Changed(change(CHEN, "A2"))),
            accepts = listOf(AcceptOutcome.ChangedAgain, AcceptOutcome.Accepted),
            mismatchEvent = change(CHEN, "A3", previous = "A2")
        )
    ),
    MlsFixture(
        "F1-6",
        R.string.mls_fixture_f1_6,
        CHEN,
        IdentityScript(
            IdentityState.CHANGED,
            events = listOf(1_000L to IdentitySignal.Changed(change(CHEN, "A2"))),
            accepts = listOf(AcceptOutcome.AlreadyHandled)
        )
    ),
    MlsFixture(
        "F1-7",
        R.string.mls_fixture_f1_7,
        SCOUT,
        IdentityScript(
            IdentityState.CHANGED,
            events = listOf(1_000L to IdentitySignal.Changed(change(SCOUT, "A2")))
        )
    ),
    MlsFixture(
        "F1-8",
        R.string.mls_fixture_f1_8,
        ANA,
        IdentityScript(
            IdentityState.TOFU,
            security = listOf(
                security(SecurityState.PENDING),
                security(SecurityState.RE_ESTABLISHED, SecurityWarning.FORK_RECOVERED, epoch = 4)
            ),
            devicesChangedAfterMs = listOf(2_000L)
        )
    ),
    MlsFixture(
        "F1-9",
        R.string.mls_fixture_f1_9,
        ANA,
        IdentityScript(IdentityState.TOFU, devicesChangedAfterMs = listOf(1_000L, 2_000L))
    ),
    MlsFixture("F1-10", R.string.mls_fixture_f1_10, CHEN, IdentityScript(IdentityState.CHANGED))
)

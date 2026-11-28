// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.realrun

import android.content.Intent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import java.util.regex.Pattern

/** Which half of the M3 walk runs: the admin screens (W3-1 to W3-6) or the chat, warning and edge states (W3-7 to W3-15). */
enum class MlsWalkPart { ADMIN, EDGE }

/**
 * The phone walk of the MLS M3 states (common walk list W3-1 to W3-15), driven through the realrun MLS gallery: it opens each
 * fixture, reaches every state that is a screen and keeps one screenshot per state, named
 * `mls-m3-<walk>-<state>-<language>-<theme>` (the control server stores it under `screenshots/`). The gallery is started with
 * a language tag and the theme in its intent, so one run covers a language and a theme without touching the phone's settings.
 *
 * A step whose target is not on screen fails the walk with the walk id and the screen; nothing is skipped. After each fixture the
 * call counters of the gallery menu go to the control log (`mls-m3 <fixture> calls ...`), the evidence of "one call per tap".
 * States a fixture cannot show are listed in docs/realrun.md.
 */
internal class MlsGroupWalk(private val ui: Ui, private val control: PeerControl, private val locale: String, private val dark: Boolean) {
    private val device get() = ui.device
    private val theme = if (dark) "dark" else "light"
    private val lang = if (locale.startsWith("zh")) "zh" else "en"

    fun run(part: MlsWalkPart): Int {
        launch()
        var states = 0
        val walks = when (part) {
            MlsWalkPart.ADMIN -> listOf(
                "W3-1" to ::w31,
                "W3-2" to ::w32,
                "W3-3" to ::w33,
                "W3-4" to ::w34,
                "W3-5" to ::w35,
                "W3-6" to ::w36
            )
            MlsWalkPart.EDGE -> listOf(
                "W3-7" to ::w37, "W3-8" to ::w38, "W3-9" to ::w39, "W3-10" to ::w310, "W3-11" to ::w311, "W3-12" to ::w312,
                "W3-13" to ::w313, "W3-14" to ::w314, "W3-15" to ::w315
            )
        }
        for ((id, walk) in walks) {
            val before = shots
            try {
                walk()
            } catch (stop: FlowStop) {
                throw FlowStop(stop.status, "$id ($locale, $theme) after $shots states: ${stop.message}")
            }
            states += shots - before
            control.log("mls-m3 $id ($locale, $theme): ${shots - before} states")
        }
        return states
    }

    private var shots = 0

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private fun fail(message: String): Nothing = throw FlowStop("FAIL", message)

    private fun launch() {
        val context = ui.targetContext
        context.startActivity(
            Intent().setClassName(context.packageName, GALLERY)
                .putExtra(EXTRA_LOCALE, locale).putExtra(EXTRA_DARK, dark)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        if (ui.find("mls-gallery", LAUNCH_WAIT) == null) fail("the MLS gallery did not open: ${ui.screen()}")
        ui.portrait()
    }

    /** One screenshot of a state; the name carries the walk, the state, the language and the theme. */
    private fun shot(walk: String, state: String) {
        Thread.sleep(SETTLE)
        ui.shot("mls-m3-$walk-$state-$lang-$theme")
        shots += 1
    }

    private fun tap(tag: String) = ui.tap(tag, STEP_WAIT)

    private fun need(tag: String, wait: Long = STEP_WAIT) = ui.need(tag, wait)

    private fun appears(tag: String, wait: Long = STEP_WAIT) {
        if (ui.find(tag, wait) == null) fail("'$tag' did not appear within ${wait / 1000} s; ${ui.screen()}")
    }

    private fun absent(tag: String, wait: Long = 1_500) {
        if (!ui.eventually(wait, POLL) { ui.find(tag, 0) == null }) fail("'$tag' is on screen but must not be; ${ui.screen()}")
    }

    private fun gone(tag: String, wait: Long = STEP_WAIT) {
        if (!ui.eventually(wait, POLL) { ui.find(tag, 0) == null }) fail("'$tag' did not go within ${wait / 1000} s; ${ui.screen()}")
    }

    private fun back() {
        device.pressBack()
        device.waitForIdle()
    }

    /** Back to the gallery list, never past it (a back on the list closes the activity). */
    private fun home() {
        repeat(HOME_BACKS) {
            if (ui.find("mls-gallery", SHORT_FIND) != null) return
            back()
        }
        fail("the gallery list did not come back: ${ui.screen()}")
    }

    /** Scrolls the list that holds [tag] down, then up, until it is on screen. */
    private fun findInList(tag: String) {
        if (ui.find(tag, SHORT_FIND) != null) return
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            repeat(LIST_SCROLLS) {
                val list = device.findObject(By.pkg(ui.packageName).scrollable(true))
                    ?: fail("no list to scroll for '$tag': ${ui.screen()}")
                runCatching { list.scroll(direction, SCROLL_FRACTION) }
                if (ui.find(tag, SHORT_FIND) != null) return
            }
        }
        fail("'$tag' is not in the list: ${ui.screen()}")
    }

    /** Swipes the content up until [tag] is on screen (the end of a long paged list). */
    private fun swipeUntil(tag: String) {
        val x = device.displayWidth / 2
        repeat(MAX_SWIPES) {
            if (ui.find(tag, SHORT_FIND) != null) return
            device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 4, SWIPE_STEPS)
        }
        fail("'$tag' did not come into view after $MAX_SWIPES swipes: ${ui.screen()}")
    }

    private fun openFixture(id: String) {
        home()
        findInList("mls-group-fixture-$id")
        tap("mls-group-fixture-$id")
        need("mls-group-view-chat")
    }

    private fun openView(view: String) {
        need("mls-group-view-chat")
        tap("mls-group-view-$view")
    }

    /** Opens [groupId] from the groups list of the open fixture; the fixture menu then works on that group. */
    private fun openFromList(groupId: String) {
        openView("list")
        findInList("mls-group-row-$groupId")
        tap("mls-group-row-$groupId")
    }

    /** Leaves the open view for the fixture menu, which shows the call counters, and logs the non-zero ones. */
    private fun counters(fixture: String) {
        var atMenu = false
        repeat(HOME_BACKS) {
            if (!atMenu && ui.find("mls-group-view-chat", SHORT_FIND) != null) atMenu = true
            if (!atMenu) back()
        }
        if (!atMenu) fail("the fixture menu did not come back for the counters: ${ui.screen()}")
        val nodes = device.findObjects(By.res(Pattern.compile("mls-calls-group\\..*")))
        val asked = nodes.mapNotNull { node -> runCatching { node.text }.getOrNull()?.takeIf { !it.endsWith(": 0") && !it.endsWith("：0") } }
        control.log("mls-m3 $fixture ($locale, $theme) calls: ${asked.joinToString(", ").ifEmpty { "none" }}")
    }

    // ---- W3-1 to W3-6: the admin screens -----------------------------------------------------------------------------

    /** Requests: approve, decline, `request_not_found`, `paced`, the busy slot, the agent and the unnamed requester. */
    private fun w31() {
        openFixture("F3-1")
        openView("info")
        need("mls-requests-entry")
        shot("w3-01", "a-info-requests-entry")
        tap("mls-requests-entry")
        need("mls-request-row-0")
        shot("w3-01", "b-requests-list")
        // Ana: confirmation, in flight, gone.
        tap("mls-request-approve-0")
        need("mls-confirm-primary")
        shot("w3-01", "c-approve-confirm-named")
        tap("mls-confirm-primary")
        appears("mls-request-phase-0", FLIGHT_WAIT)
        shot("w3-01", "d-approve-in-flight")
        gone("mls-request-phase-0", EVENT_WAIT)
        shot("w3-01", "e-approved-row-gone")
        // Ben: the request is no longer pending.
        tap("mls-request-approve-0")
        tap("mls-confirm-primary")
        appears("mls-requests-gone", FLIGHT_WAIT)
        shot("w3-01", "f-request-not-found")
        // Chen: decline.
        tap("mls-request-decline-0")
        need("mls-confirm-primary")
        shot("w3-01", "g-decline-confirm")
        tap("mls-confirm-primary")
        appears("mls-request-phase-0", FLIGHT_WAIT)
        shot("w3-01", "h-declining")
        gone("mls-request-phase-0", EVENT_WAIT)
        absent("mls-requests-gone")
        shot("w3-01", "i-declined-notice-cleared")
        // Dana: paced.
        tap("mls-request-approve-0")
        tap("mls-confirm-primary")
        appears("mls-requests-paced", FLIGHT_WAIT)
        shot("w3-01", "j-paced")
        gone("mls-requests-paced", EVENT_WAIT)
        shot("w3-01", "k-paced-over")
        // Eve: the reply takes three seconds, every other Approve is disabled meanwhile.
        tap("mls-request-approve-1")
        tap("mls-confirm-primary")
        appears("mls-requests-busy", FLIGHT_WAIT)
        shot("w3-01", "l-busy-other-rows-disabled")
        gone("mls-requests-busy", EVENT_WAIT)
        // Scout is an agent, the next row has no name.
        tap("mls-request-approve-1")
        need("mls-confirm-primary")
        appears("mls-requests-confirm-agent-note")
        shot("w3-01", "m-approve-confirm-agent")
        tap("mls-confirm-cancel")
        gone("mls-requests-confirm")
        tap("mls-request-approve-2")
        need("mls-confirm-primary")
        shot("w3-01", "n-approve-confirm-unnamed")
        tap("mls-confirm-cancel")
        gone("mls-requests-confirm")
        shot("w3-01", "o-requests-fay-and-expiry")
        counters("F3-1")
    }

    /** Paging: the entry says "more than 200", the page error is the last row, Try again loads page 2. */
    private fun w32() {
        openFixture("F3-2")
        openView("info")
        need("mls-requests-entry")
        shot("w3-02", "a-info-more-than-200")
        tap("mls-requests-entry")
        need("mls-request-row-0")
        shot("w3-02", "b-first-page")
        swipeUntil("mls-requests-retry")
        shot("w3-02", "c-page-error-last-row")
        tap("mls-requests-retry")
        gone("mls-requests-retry", EVENT_WAIT)
        swipeUntil("mls-request-row-229")
        shot("w3-02", "d-second-page-end")
        counters("F3-2")
    }

    /** Owner member actions: menus by role, confirmations that name the member, event-first, remove and ban. */
    private fun w33() {
        openFixture("F3-3")
        openView("members")
        need("mls-member-menu-1")
        shot("w3-03", "a-members-owner")
        // Ben: make admin (the event arrives before the reply).
        tap("mls-member-menu-2")
        need("mls-member-action-make_admin")
        shot("w3-03", "b-menu-member-row")
        tap("mls-member-action-make_admin")
        need("mls-confirm-primary")
        shot("w3-03", "c-make-admin-confirm")
        tap("mls-confirm-primary")
        appears("mls-member-menu-2", EVENT_WAIT)
        shot("w3-03", "d-role-done-event-first")
        // Chen: make regular member, cancelled.
        tap("mls-member-menu-1")
        need("mls-member-action-make_member")
        shot("w3-03", "e-menu-admin-row")
        tap("mls-member-action-make_member")
        need("mls-confirm-primary")
        shot("w3-03", "f-demote-confirm")
        tap("mls-confirm-cancel")
        gone("mls-member-confirm")
        // Dana: remove.
        tap("mls-member-menu-3")
        tap("mls-member-action-remove")
        need("mls-confirm-primary")
        shot("w3-03", "g-remove-confirm")
        tap("mls-confirm-primary")
        gone("mls-member-menu-3", FLIGHT_WAIT)
        shot("w3-03", "h-remove-updating")
        appears("mls-member-menu-3", EVENT_WAIT)
        shot("w3-03", "i-remove-done")
        // Eve (now in Dana's place): ban.
        tap("mls-member-menu-3")
        tap("mls-member-action-ban")
        need("mls-confirm-primary")
        shot("w3-03", "j-ban-confirm")
        tap("mls-confirm-primary")
        gone("mls-member-menu-3", FLIGHT_WAIT)
        appears("mls-member-menu-3", EVENT_WAIT)
        shot("w3-03", "k-ban-done")
        counters("F3-3")
    }

    /** Admin member actions: Remove and Ban only on member rows, then the stale role refusal at the list. */
    private fun w34() {
        openFixture("F3-4")
        openView("members")
        need("mls-member-menu-3")
        shot("w3-04", "a-members-admin")
        tap("mls-member-menu-3")
        need("mls-member-action-remove")
        absent("mls-member-action-make_admin")
        shot("w3-04", "b-menu-remove-ban-only")
        tap("mls-member-action-remove")
        need("mls-confirm-primary")
        shot("w3-04", "c-remove-confirm")
        tap("mls-confirm-primary")
        appears("mls-member-action-error", EVENT_WAIT)
        shot("w3-04", "d-not-admin-text")
        gone("mls-member-menu-3", EVENT_WAIT)
        shot("w3-04", "e-after-reread-no-menus")
        counters("F3-4")
    }

    /** The busy slot on the member menu: the entries are disabled and say why. */
    private fun w35() {
        openFixture("F3-5")
        openView("members")
        need("mls-member-menu-3")
        tap("mls-member-menu-3")
        tap("mls-member-action-remove")
        need("mls-confirm-primary")
        tap("mls-confirm-primary")
        tap("mls-member-menu-4")
        appears("mls-member-menu-busy", FLIGHT_WAIT)
        shot("w3-05", "a-busy-member-menu")
        back()
        gone("mls-member-menu")
        shot("w3-05", "b-busy-list-updating-row")
        counters("F3-5")
    }

    /** Settings (name, approval) and the per-device push picker, including its failure. */
    private fun w36() {
        openFixture("F3-6")
        openView("settings")
        need("mls-settings-name")
        shot("w3-06", "a-settings")
        ui.fill("mls-settings-name", "x".repeat(NAME_TOO_LONG))
        shot("w3-06", "b-name-too-long")
        ui.fill("mls-settings-name", "Design team")
        tap("mls-settings-save")
        Thread.sleep(FLIGHT)
        shot("w3-06", "c-name-saving-old-name-stays")
        Thread.sleep(EVENT_SETTLE)
        shot("w3-06", "d-renamed")
        tap("mls-settings-approval")
        need("mls-settings-confirm")
        shot("w3-06", "e-approval-off-confirm")
        tap("mls-confirm-primary")
        Thread.sleep(FLIGHT)
        shot("w3-06", "f-approval-saving")
        Thread.sleep(EVENT_SETTLE)
        shot("w3-06", "g-approval-off-done")
        tap("mls-settings-approval")
        Thread.sleep(FLIGHT)
        shot("w3-06", "h-approval-on-saving")
        Thread.sleep(EVENT_SETTLE)
        shot("w3-06", "i-approval-on-done")
        back()
        openView("info")
        findInList("mls-push-all")
        shot("w3-06", "j-push-picker")
        tap("mls-push-all")
        Thread.sleep(FLIGHT)
        shot("w3-06", "k-push-saving-current-looks-selected")
        Thread.sleep(PUSH_REPLY)
        shot("w3-06", "l-push-all-done")
        findInList("mls-push-none")
        tap("mls-push-none")
        appears("mls-push-failed", EVENT_WAIT)
        shot("w3-06", "m-push-failed-old-value-kept")
        counters("F3-6")
    }

    // ---- W3-7 to W3-15: the chat, the warnings and the edge states ---------------------------------------------------

    /** Catch-up: running, the skip dialog, keep catching up, skipping, rejoining, the late variant, the unknown total. */
    private fun w37() {
        openFixture("F3-7")
        openView("chat")
        appears("mls-catchup-banner", LONG_WAIT)
        shot("w3-07", "a-running")
        tap("mls-catchup-skip")
        need("mls-skip-dialog")
        shot("w3-07", "b-skip-dialog")
        tap("mls-skip-cancel")
        gone("mls-skip-dialog")
        shot("w3-07", "c-keep-catching-up")
        tap("mls-catchup-skip")
        need("mls-skip-confirm")
        tap("mls-skip-confirm")
        Thread.sleep(FLIGHT)
        shot("w3-07", "d-skipping")
        Thread.sleep(SETTLE)
        shot("w3-07", "e-rejoining-after-skip")
        gone("mls-catchup-banner", LONG_WAIT)
        shot("w3-07", "f-idle-after-skip")
        back()
        // The late variant: the idle event comes first, the reply after it changes nothing.
        openFromList("g-latecatch")
        appears("mls-catchup-banner", LONG_WAIT)
        shot("w3-07", "g-late-running")
        tap("mls-catchup-skip")
        need("mls-skip-confirm")
        tap("mls-skip-confirm")
        gone("mls-catchup-banner", EVENT_WAIT)
        shot("w3-07", "h-late-idle-event-first")
        Thread.sleep(EVENT_SETTLE)
        absent("mls-catchup-banner")
        shot("w3-07", "i-late-reply-changes-nothing")
        back()
        // The unknown total: no numbers.
        openFromList("g-catch0")
        appears("mls-catchup-banner", LONG_WAIT)
        shot("w3-07", "j-indeterminate")
        counters("F3-7")
    }

    /** Every warning, the three-active case with the "more" row and group info, the stale info read. */
    private fun w38() {
        openFixture("F3-8")
        openView("chat")
        need("mls-f38-next", LONG_WAIT)
        val steps = listOf(
            "fork_alarm", "rejoin_pruned", "rejoin_gate_rejected", "rejoin_apply_failed", "rejoin_welcome_expired",
            "rejoin_state_lost", "rejoin_unknown_reason", "undecryptable", "admission_needs_online", "host_unusual",
            "head_commit_rejected", "unknown_code"
        )
        steps.forEachIndexed { index, name ->
            tap("mls-f38-next")
            Thread.sleep(WARNING_SETTLE)
            shot("w3-08", "%02d-%s".format(index + 1, name))
            if (name == "head_commit_rejected") {
                tap("mls-warning-action-head_commit_rejected")
                need("mls-void-dialog")
                shot("w3-08", "%02d-head-rejected-undo-confirm".format(index + 1))
                tap("mls-void-cancel")
                gone("mls-void-dialog")
            }
            tap("mls-f38-clear")
            Thread.sleep(SETTLE)
        }
        // Fork alarm, a forced rejoin, undecryptable and the admission hint at once: two banners and the "more" row.
        repeat(ACTIVE_STEPS) {
            tap("mls-f38-next")
            Thread.sleep(SETTLE)
        }
        appears("mls-warning-more", EVENT_WAIT)
        shot("w3-08", "13-three-active-two-shown-plus-more")
        tap("mls-warning-more")
        need("mls-warning-fork_alarm")
        shot("w3-08", "14-info-every-warning-rows")
        back()
        openView("chat")
        need("mls-warning-hide-undecryptable")
        tap("mls-warning-hide-undecryptable")
        shot("w3-08", "15-undecryptable-hidden-admission-shown")
        // The stale info read must not bring a cleared warning back.
        home()
        openFixture("F3-8")
        openView("chat")
        need("mls-f38-stale", LONG_WAIT)
        tap("mls-f38-stale")
        appears("mls-warning-fork_alarm", FLIGHT_WAIT)
        shot("w3-08", "16-stale-step-fork-set")
        gone("mls-warning-fork_alarm", EVENT_WAIT)
        Thread.sleep(STALE_REPLY)
        absent("mls-warning-fork_alarm")
        shot("w3-08", "17-after-stale-read-fork-not-back")
        counters("F3-8")
    }

    /** Quota and privacy delay: the post line, the commit line, the first-day line, the privacy row, the ETA without a quota line. */
    private fun w39() {
        openFixture("F3-9")
        openView("chat")
        appears("mls-quota-line", LONG_WAIT)
        shot("w3-09", "a-design-post-quota-line")
        back()
        openView("info")
        need("mls-info-commit-quota", LONG_WAIT)
        shot("w3-09", "b-design-commit-quota-info")
        back()
        openFromList("g-neighbours")
        appears("mls-quota-line", LONG_WAIT)
        shot("w3-09", "c-neighbours-first-day-line")
        back()
        openView("info")
        need("mls-info-privacy-delay", LONG_WAIT)
        shot("w3-09", "d-neighbours-privacy-row")
        back()
        openFromList("g-large")
        appears("mls-privacy-eta", LONG_WAIT)
        absent("mls-quota-line")
        shot("w3-09", "e-scale-eta-time-of-day-no-quota-line")
        counters("F3-9")
    }

    /** Void: the entry on the head commit, the confirmation, "Undoing…", the "Undone" mark, the not-head refusal. */
    private fun w310() {
        openFixture("F3-10")
        openView("chat")
        appears("mls-void-250", LONG_WAIT)
        shot("w3-10", "a-head-commit-row")
        tap("mls-void-250")
        need("mls-void-action")
        shot("w3-10", "b-undo-entry")
        tap("mls-void-action")
        need("mls-void-dialog")
        shot("w3-10", "c-undo-confirm")
        tap("mls-void-confirm")
        appears("mls-void-undoing", FLIGHT_WAIT)
        shot("w3-10", "d-undoing")
        appears("mls-undone-250", EVENT_WAIT)
        shot("w3-10", "e-undone-mark-and-void-line")
        back()
        // The head moved: the SDK refuses with void_not_head and the text shows next to the chat.
        openFromList("g-stale")
        appears("mls-void-250", LONG_WAIT)
        tap("mls-void-250")
        need("mls-void-action")
        tap("mls-void-action")
        need("mls-void-dialog")
        tap("mls-void-confirm")
        appears("mls-notice", EVENT_WAIT)
        shot("w3-10", "f-not-head-text")
        counters("F3-10")
    }

    /** Removed during an action: the group turns read-only, the late reply changes nothing. */
    private fun w311() {
        openFixture("F3-11")
        openView("members")
        need("mls-member-menu-3")
        tap("mls-member-menu-3")
        tap("mls-member-action-remove")
        need("mls-confirm-primary")
        tap("mls-confirm-primary")
        Thread.sleep(FLIGHT)
        shot("w3-11", "a-removing-in-flight")
        gone("mls-member-menu-4", EVENT_WAIT)
        shot("w3-11", "b-removed-read-only-members")
        Thread.sleep(EVENT_SETTLE)
        shot("w3-11", "c-late-reply-changes-nothing")
        back()
        openView("chat")
        appears("mls-readonly", EVENT_WAIT)
        shot("w3-11", "d-chat-read-only-footer")
        counters("F3-11")
    }

    /**
     * Lock during actions: every confirmation and screen closes and nothing comes back after the unlock. The lock is the session's
     * app-lock entry (`appLocked`, `appUnlocked`), on the requests, invite, settings and member screens and the join sheet.
     */
    private fun w312() {
        openFixture("F3-12")
        openView("requests")
        need("mls-request-row-0")
        tap("mls-request-approve-0")
        need("mls-requests-confirm")
        shot("w3-12", "a-confirm-open-before-lock")
        tap("mls-f312-lock")
        need("mls-group-view-chat", EVENT_WAIT)
        absent("mls-requests-confirm")
        shot("w3-12", "b-locked-everything-closed")
        tap("mls-f312-unlock")
        openView("requests")
        need("mls-request-row-0", EVENT_WAIT)
        absent("mls-requests-confirm")
        absent("mls-requests-approval-off")
        shot("w3-12", "c-reopened-nothing-survives")
        // A reply still on its way when the lock lands is dropped.
        tap("mls-request-approve-0")
        tap("mls-confirm-primary")
        Thread.sleep(FLIGHT)
        tap("mls-f312-lock")
        need("mls-group-view-chat", EVENT_WAIT)
        shot("w3-12", "d-locked-during-approve")
        tap("mls-f312-unlock")
        openView("requests")
        need("mls-request-row-0", EVENT_WAIT)
        Thread.sleep(EVENT_SETTLE)
        absent("mls-request-phase-0")
        shot("w3-12", "e-late-reply-dropped")
        // The invite panel: the link and its QR go with the lock.
        menu()
        openView("invites")
        tap("mls-invite-new")
        appears("mls-invite-link", EVENT_WAIT)
        appears("mls-invite-qr")
        shot("w3-12", "f-invite-link-before-lock")
        tap("mls-f312-lock")
        need("mls-group-view-chat", EVENT_WAIT)
        absent("mls-invite-link")
        absent("mls-invite-qr")
        shot("w3-12", "g-invite-closed-by-lock")
        tap("mls-f312-unlock")
        openView("invites")
        need("mls-invite-new", EVENT_WAIT)
        absent("mls-invite-revoke-confirm")
        shot("w3-12", "h-invite-reopened")
        // Settings: the typed name and the approval-off confirmation go with the lock.
        menu()
        openView("settings")
        need("mls-settings-name")
        ui.fill("mls-settings-name", DRAFT_NAME)
        tap("mls-settings-approval")
        need("mls-settings-confirm")
        shot("w3-12", "i-settings-draft-and-confirm-before-lock")
        tap("mls-f312-lock")
        need("mls-group-view-chat", EVENT_WAIT)
        absent("mls-settings-confirm")
        shot("w3-12", "j-settings-closed-by-lock")
        tap("mls-f312-unlock")
        openView("settings")
        need("mls-settings-name", EVENT_WAIT)
        absent("mls-settings-confirm")
        if (ui.field("mls-settings-name").text == DRAFT_NAME) fail("the typed name survived the lock: ${ui.screen()}")
        shot("w3-12", "k-settings-reopened-no-draft")
        // Members: the action menu and its confirmation go with the lock.
        menu()
        openView("members")
        need("mls-member-menu-2")
        tap("mls-member-menu-2")
        tap("mls-member-action-remove")
        need("mls-member-confirm")
        shot("w3-12", "l-member-confirm-before-lock")
        tap("mls-f312-lock")
        need("mls-group-view-chat", EVENT_WAIT)
        absent("mls-member-confirm")
        shot("w3-12", "m-members-closed-by-lock")
        tap("mls-f312-unlock")
        openView("members")
        need("mls-member-menu-2", EVENT_WAIT)
        absent("mls-member-confirm")
        absent("mls-member-menu")
        shot("w3-12", "n-members-reopened")
        // The join sheet is its own window above the controls: the lock is armed first and lands while a link is typed.
        menu()
        openView("list")
        need("mls-join-open")
        tap("mls-f312-lock-later")
        tap("mls-join-open")
        need("mls-join-field")
        ui.fill("mls-join-field", TYPED_LINK)
        shot("w3-12", "o-join-link-typed-before-lock")
        gone("mls-join-sheet", LOCK_LATER_WAIT)
        shot("w3-12", "p-join-closed-by-lock")
        tap("mls-f312-unlock")
        tap("mls-join-open")
        need("mls-join-field")
        if (ui.field("mls-join-field").text == TYPED_LINK) fail("the typed link survived the lock: ${ui.screen()}")
        shot("w3-12", "q-join-reopened-empty")
        tap("mls-join-close")
        gone("mls-join-sheet")
        counters("F3-12")
    }

    /** Back from the open view to the fixture menu, never past it. */
    private fun menu() {
        repeat(HOME_BACKS) {
            if (ui.find("mls-group-view-chat", SHORT_FIND) != null) return
            back()
        }
        fail("the fixture menu did not come back: ${ui.screen()}")
    }

    /** The admission hint: the chat banner, the group info row with "Get invite link", the invite panel's approval-off note. */
    private fun w313() {
        openFixture("F3-13")
        openView("chat")
        appears("mls-warning-admission_needs_online", LONG_WAIT)
        appears("mls-hint-invite")
        shot("w3-13", "a-chat-admission-banner")
        back()
        openView("info")
        findInList("mls-hint-invite")
        shot("w3-13", "b-info-admission-row")
        tap("mls-hint-invite")
        need("mls-invite-note", EVENT_WAIT)
        shot("w3-13", "c-invite-panel-approval-off-note")
        counters("F3-13")
    }

    /** Every M3 method answers unavailable: each screen says so and keeps its old state. */
    private fun w314() {
        openFixture("F3-14")
        openView("requests")
        appears("mls-requests-retry", LONG_WAIT)
        shot("w3-14", "a-requests-unavailable")
        back()
        openView("settings")
        need("mls-settings-name")
        ui.fill("mls-settings-name", "Design team")
        tap("mls-settings-save")
        appears("mls-settings-error", EVENT_WAIT)
        shot("w3-14", "b-settings-unavailable")
        back()
        openView("info")
        findInList("mls-push-all")
        tap("mls-push-all")
        appears("mls-push-failed", EVENT_WAIT)
        shot("w3-14", "c-push-unavailable")
        back()
        openView("members")
        need("mls-member-menu-2")
        tap("mls-member-menu-2")
        tap("mls-member-action-make_admin")
        need("mls-confirm-primary")
        tap("mls-confirm-primary")
        appears("mls-member-action-error", EVENT_WAIT)
        shot("w3-14", "d-member-action-unavailable")
        counters("F3-14")
    }

    /** The invite presets (1 and 7 days, no 30) with the approval-on note. */
    private fun w315() {
        openFixture("F3-1")
        openView("invites")
        need("mls-invite-days")
        need("mls-invite-note")
        shot("w3-15", "a-invite-presets-approval-on-note")
        counters("F3-1")
    }

    private companion object {
        const val GALLERY = "org.ramflux.android.mlsgallery.MlsGalleryActivity"
        const val EXTRA_LOCALE = "mls_locale"
        const val EXTRA_DARK = "mls_dark"
        const val LAUNCH_WAIT = 30_000L
        const val STEP_WAIT = 15_000L
        const val LONG_WAIT = 30_000L
        const val FLIGHT_WAIT = 6_000L
        const val EVENT_WAIT = 15_000L
        const val SHORT_FIND = 400L
        const val SETTLE = 600L
        const val FLIGHT = 400L
        const val DRAFT_NAME = "Typed before the lock"
        const val TYPED_LINK = "rfinv-typed-before-lock"
        const val LOCK_LATER_WAIT = 20_000L
        const val EVENT_SETTLE = 2_500L
        const val PUSH_REPLY = 1_500L
        const val WARNING_SETTLE = 900L
        const val STALE_REPLY = 4_500L
        const val POLL = 300L
        const val HOME_BACKS = 6
        const val LIST_SCROLLS = 14
        const val SCROLL_FRACTION = 0.8f
        const val MAX_SWIPES = 60
        const val SWIPE_STEPS = 20
        const val NAME_TOO_LONG = 65

        /** Steps from the first warning to the admission hint: fork alarm, six rejoin reasons, undecryptable, admission. */
        const val ACTIVE_STEPS = 9
    }
}

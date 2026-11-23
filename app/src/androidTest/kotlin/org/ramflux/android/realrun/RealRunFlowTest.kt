// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.realrun

import java.security.SecureRandom
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.ramflux.android.RealRunProbe

/**
 * The opt-in real run of the beta flows against the local node (scripts/realrun.sh). Skipped unless the
 * instrumentation argument `rf_realrun=1` is given, and in no CI task's class list. Each flow records PASS,
 * FAIL, BLOCKED or NOT RUN with the control server, which writes the flow table; a flow never fails the JUnit
 * run by itself, so later flows still report. The account code is generated here per run and the recovery
 * words are read from the realrun-only probe; neither is sent to the control server or logged.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealRunFlowTest {
    private val args = RealRunArgs.current()
    private lateinit var control: PeerControl
    private lateinit var ui: Ui

    @Before fun setUp() {
        assumeTrue("real run not requested (rf_realrun=1)", args.enabled)
        control = PeerControl(args.control)
        ui = Ui(control)
    }

    private fun flow(id: String, needsAccount: Boolean = true, block: () -> String) {
        if (needsAccount && !accountReady) {
            control.result(id, "BLOCKED", "no account: flow 01 did not complete")
            return
        }
        try {
            val detail = block()
            control.result(id, "PASS", detail)
        } catch (stop: FlowStop) {
            ui.shot("$id-stop")
            control.result(id, stop.status, stop.message.orEmpty())
        } catch (error: Throwable) {
            ui.shot("$id-error")
            control.result(id, "FAIL", "${error.javaClass.simpleName}: ${error.message.orEmpty().take(600)}")
        }
    }

    private fun notScripted(id: String, reason: String) = control.result(id, "NOT RUN", reason)

    @Test fun f01_connectAndCreateAccount() = flow("01-connect-create", needsAccount = false) {
        if (!control.health()) throw FlowStop("BLOCKED", "peer control at ${args.control} is not reachable from the phone (adb reverse)")
        ui.launch()
        ui.shot("01-start")
        when (ui.first("startup-gateway", "startup-welcome", "startup-unlock", "shell-tabs", timeout = Ui.LONG)) {
            "startup-gateway" -> ui.tap("startup-change-node")
            "startup-welcome" -> ui.tap("startup-link")
            null -> throw FlowStop("FAIL", "the app showed no startup screen: ${ui.texts().take(400)}")
            else -> throw FlowStop("FAIL", "the app already holds an account; the run needs a fresh install")
        }
        ui.tap("connect")
        ui.tap("add-node")
        ui.type("node-address", args.nodeOrigin)
        ui.type("operator-key", args.operatorKey)
        ui.shot("01-address")
        ui.tap("review-address")
        ui.tap("verify-origin")
        // The first node step: discovery over HTTP from the phone.
        val reached = ui.first("confirm-node", "review-address", timeout = Ui.LONG)
        if (reached != "confirm-node") {
            ui.shot("01-node-unreachable")
            throw FlowStop("BLOCKED", "node discovery at ${args.nodeOrigin} failed from the phone: ${ui.texts().take(500)}")
        }
        val compact = ui.texts().filterNot { it.isWhitespace() || it == '|' }
        if (args.fingerprint.isNotEmpty() && !compact.contains(args.fingerprint.take(16), ignoreCase = true)) {
            throw FlowStop("FAIL", "the reviewed node fingerprint does not match node-facts.json")
        }
        ui.shot("01-identity")
        ui.tap("confirm-node")
        if (ui.first("choose-saved", "CREATE", "display-name", timeout = Ui.LONG) == "choose-saved") {
            ui.tap("choose-saved")
            ui.tap(args.nodeId)
            ui.tap("use-node")
        }
        when (ui.first("display-name", "CREATE", "startup-create", timeout = Ui.LONG)) {
            "CREATE" -> ui.tap("CREATE")
            "startup-create" -> ui.tap("startup-create")
            null -> throw FlowStop("FAIL", "no create entry after saving the node: ${ui.texts().take(400)}")
        }
        ui.type("display-name", DISPLAY_NAME)
        ui.tap("begin-create")
        ui.need("phrase-saved", Ui.LONG, "recovery phrase")
        ui.need("phrase-word-23")
        ui.shot("01-phrase")
        ui.tap("phrase-saved")
        ui.need("challenge-0", Ui.LONG, "word challenge")
        for (index in 0..2) {
            val position = challengePosition(index)
            var drawn = false
            RealRunProbe.borrow(position - 1) { word ->
                ui.keys("challenge-$index", word)
                drawn = true
            }
            if (!drawn) throw FlowStop("FAIL", "recovery word $position was not drawn (realrun probe)")
        }
        ui.keys("account-code", code)
        ui.tap("confirm-account")
        ui.need("continue-created", Ui.LONG, "account created")
        RealRunProbe.erase()
        ui.shot("01-created")
        val created = ui.texts()
        ui.tap("continue-created")
        leavePermissionFlow()
        ui.need("shell-tabs", Ui.LONG, "shell after creation")
        ui.shot("01-shell")
        accountReady = true
        "account created through the node; created screen said: ${created.take(300)}"
    }

    @Test fun f02_lockAndUnlock() = flow("02-lock-unlock") {
        // Lock through the app's background close, then unlock with the code (known-accounts registry).
        ui.device.pressHome()
        Thread.sleep(LOCK_WAIT)
        ui.launch()
        when (ui.first("shell-tabs", "startup-unlock", "unlock-code", timeout = Ui.LONG)) {
            "shell-tabs" -> return@flow "the session stayed open across a short background (no unlock was needed)"
            "startup-unlock" -> {
                ui.tap("startup-unlock-continue")
                ui.tap("startup-view-unlock")
                ui.tap("startup-unlock-code")
            }
        }
        ui.need("unlock-account-name", Ui.SHORT, "unlock names the account")
        ui.keys("unlock-code", code)
        ui.tap("unlock-account")
        ui.need("shell-tabs", Ui.LONG, "shell after unlock")
        ui.shot("02-unlocked")
        "unlocked by name with the six-digit code"
    }

    @Test fun f03_contactsWithAlice() = notScripted(
        "03-contacts-alice",
        "not scripted yet: add-contact handles need a device run to confirm"
    )

    @Test fun f04_directChat() = notScripted("04-direct-chat", "not scripted yet (needs flow 03)")

    @Test fun f05_mediaM1() = notScripted(
        "05-media-m1",
        "not scripted yet; on the adb-reverse path the QUIC-only relay is unreachable from the phone, so media is expected BLOCKED"
    )

    @Test fun f06_groups() = notScripted("06-groups", "not scripted yet (needs flow 03)")

    @Test fun f07_settingsCodeBackup() = notScripted("07-settings-code-backup", "not scripted yet")

    @Test fun f08_honestStates() = notScripted("08-agents-tasks-calls-privacy", "not scripted yet")

    private fun challengePosition(index: Int): Int {
        val field = ui.need("challenge-$index")
        val label = (listOf(field) + field.children).flatMap { listOf(it.text, it.contentDescription) }.filterNotNull()
            .joinToString(" ") + " " + (field.parent?.children?.mapNotNull { it.text }?.joinToString(" ") ?: "")
        return Regex("\\b([1-9]|1[0-9]|2[0-4])\\b").find(label)?.value?.toInt()
            ?: throw FlowStop("FAIL", "could not read the position of challenge word ${index + 1}")
    }

    /** The post-creation permission explanations: leave them without granting anything. */
    private fun leavePermissionFlow() {
        repeat(6) {
            if (ui.first("shell-tabs", timeout = 3_000) != null) return
            ui.device.pressBack()
        }
    }

    companion object {
        private const val DISPLAY_NAME = "Phone RealRun"
        private const val LOCK_WAIT = 4_000L
        private var accountReady = false

        /** The run's account code: six random digits that are not all equal and not a straight run. */
        private val code: CharArray = generateCode()

        private fun generateCode(): CharArray {
            val random = SecureRandom()
            while (true) {
                val digits = CharArray(6) { ('0' + random.nextInt(10)) }
                val same = digits.all { it == digits[0] }
                val up = (1 until 6).all { digits[it] - digits[it - 1] == 1 }
                val down = (1 until 6).all { digits[it - 1] - digits[it] == 1 }
                if (!same && !up && !down) return digits
            }
        }

        @JvmStatic @AfterClass
        fun erase() {
            code.fill('\u0000')
            RealRunProbe.erase()
        }
    }
}

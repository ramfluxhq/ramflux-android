// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.realrun

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.ramflux.android.RealRunProbe

/**
 * The opt-in real run of the beta flows against the local node (scripts/realrun.sh). Skipped unless the
 * instrumentation argument `rf_realrun=1` is given, and in no CI task's class list. Each flow records RUNNING when it
 * starts and then PASS, FAIL, BLOCKED or NOT RUN with the control server, which writes the flow table. The JUnit
 * result agrees with the table: a FAIL fails the test, BLOCKED and NOT RUN mark it skipped, PASS passes; later flows
 * of the invocation still run. A flow left RUNNING (the process died) is turned into FAIL by the script.
 *
 * The script runs the flows in several invocations (each under ten minutes); every invocation starts the app
 * process afresh, so the first flow of a later invocation also proves a cold start. The account code is generated
 * by flow 01 and kept encrypted on the phone ([RunCode]); the recovery words are read from the realrun-only probe.
 * Neither is sent to the control server or logged, and no screenshot is taken while recovery words are shown.
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
        // Marks each flow under the app's debug trace tag, so app-logcat.txt lines can be placed. Logged directly: the
        // suite's classes may not share the app's installed trace sink.
        android.util.Log.w("ramflux.abi", "realrun.flow.start")
    }

    private fun flow(id: String, needsAccount: Boolean = true, block: () -> String) {
        if (needsAccount && !accountReady) {
            control.result(id, "BLOCKED", "no account: flow 01 did not complete on this install")
            throw AssumptionViolatedException("flow $id: BLOCKED: no account on this install")
        }
        val first = flowsInProcess++ == 0
        coldProcess = first
        control.result(id, "RUNNING", "started")
        val (status, detail) = try {
            "PASS" to block()
        } catch (stop: FlowStop) {
            evidence("$id-stop")
            stop.status to stop.message.orEmpty()
        } catch (error: Throwable) {
            evidence("$id-error")
            "FAIL" to "${error.javaClass.simpleName}: ${error.message.orEmpty().take(600)}; ${runCatching { ui.screen() }.getOrDefault("")}"
        }
        control.result(id, status, detail)
        when (status) {
            "PASS" -> Unit
            "FAIL" -> throw AssertionError("flow $id: FAIL: ${detail.take(1500)}")
            else -> throw AssumptionViolatedException("flow $id: $status: ${detail.take(1500)}")
        }
    }

    /** A screenshot and a UI dump of the moment a flow stopped (neither while recovery words are shown). */
    private fun evidence(name: String) {
        if (secretScreen) return
        ui.shot(name)
        ui.dump(name)
    }

    private fun fail(message: String): Nothing = throw FlowStop("FAIL", message)

    private fun blocked(message: String): Nothing = throw FlowStop("BLOCKED", message)

    // Flow 1: custom node, account, recovery phrase, word check, account code, completion networking.

    @Test fun f01_connectAndCreateAccount() = flow("01-connect-create", needsAccount = false) {
        if (!control.health()) blocked("peer control at ${args.control} is not reachable from the phone (adb reverse)")
        ui.launch()
        ui.shot("01-start")
        when (ui.first("startup-gateway", "startup-welcome", "startup-unlock", "shell-tabs", timeout = Ui.LONG)) {
            "startup-gateway" -> ui.tap("startup-change-node")
            "startup-welcome" -> ui.tap("startup-link")
            null -> fail("the app showed no startup screen: ${ui.screen()}")
            else -> fail("the app already holds an account; the run needs a fresh install")
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
            blocked("node discovery at ${args.nodeOrigin} failed from the phone: ${ui.screen()}")
        }
        val compact = ui.texts().filterNot { it.isWhitespace() || it == '|' }
        if (args.fingerprint.isEmpty() || !compact.contains(args.fingerprint, ignoreCase = true)) {
            fail("the reviewed node fingerprint does not show the full fingerprint of node-facts.json ${args.fingerprint}")
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
            null -> fail("no create entry after saving the node: ${ui.screen()}")
        }
        val name = "Phone RealRun " + UUID.randomUUID().toString().take(4)
        RunState["name"] = name
        ui.type("display-name", name)
        ui.tap("begin-create")
        ui.need("phrase-saved", Ui.LONG, "recovery phrase")
        ui.need("phrase-word-23")
        // No screenshot here or on the word check: both show recovery words.
        secretScreen = true
        ui.tap("phrase-saved")
        ui.need("challenge-0", Ui.LONG, "word challenge")
        for (index in 0..2) {
            val position = challengePosition(index)
            var drawn = false
            RealRunProbe.borrow(position - 1) { word ->
                ui.keys("challenge-$index", word)
                drawn = true
            }
            if (!drawn) fail("recovery word $position was not drawn (realrun probe)")
        }
        val code = RunCode.create()
        try {
            ui.keys("account-code", code)
        } finally {
            code.fill('\u0000')
        }
        ui.tap("confirm-account")
        ui.need("continue-created", Ui.LONG, "account created")
        secretScreen = false
        RealRunProbe.erase()
        // The completion step follows account.status until the SDK reports networking live.
        val connected = ui.str("ui_created_network_connected")
        val firstShown = ui.find("created-networking")?.text ?: "nothing"
        val live = ui.eventually(90_000) { ui.find("created-networking", 1_000)?.text == connected }
        ui.shot("01-created")
        val networking = if (live) {
            "completion showed '$firstShown', then '$connected'"
        } else {
            "completion showed '$firstShown' and never '$connected' within 90 s (now '${ui.find("created-networking")?.text}')"
        }
        ui.tap("continue-created")
        leavePermissionFlow()
        ui.need("shell-tabs", Ui.LONG, "shell after creation")
        val header = ui.hasText(name)
        ui.shot("01-shell")
        if (!live) fail("account created, but $networking")
        "node ${args.nodeOrigin} accepted as typed; full fingerprint matched node-facts.json; 24 words shown, three checked; " +
            "code set; $networking; shell reached" +
            (if (header) "; Messages names the account '$name'" else "; the display name is not shown on Messages")
    }

    // Flow 1 (continued): lock from settings, the locked state offers the account by name, unlock with the code.

    @Test fun f02_lockAndUnlock() = flow("02-lock-unlock") {
        ensureShell()
        openSettings()
        ui.tap("account-security")
        ui.tap("lock-account", Ui.SHORT)
        confirmIfAsked()
        val locked = ui.first(
            "startup-unlock-header",
            "startup-details-header",
            "startup-info-header",
            "unlock-account-name",
            "shell-locked",
            "onboarding-header",
            timeout = Ui.LONG
        )
            ?: fail("no locked state after lock: ${ui.screen()}")
        ui.shot("02-locked")
        val named = ui.hasText(RunState["name"].orEmpty())
        unlock()
        val reopened = ui.find("account-security", 3_000) != null || ui.find("lock-account", 1_000) != null
        ui.shot("02-unlocked")
        ensureShell()
        "lock from Account and security showed '$locked'" + (if (named) " naming the account" else "") +
            "; unlocked with the six-digit code" + (if (reopened) "; the settings screen open before the lock came back" else "")
    }

    // Flow 1 (continued): every invocation restarts the app process, so this first flow of invocation 2 is a cold start.

    @Test fun f03_coldStartUnlock() = flow("03-cold-start") {
        if (!coldProcess) {
            throw FlowStop(
                "NOT RUN",
                "not the first flow of its invocation, so not a cold start; run it first (scripts/realrun.sh --tests \"f03 ...\")"
            )
        }
        ui.launch()
        val first = ui.first(
            "startup-unlock-header",
            "startup-details-header",
            "startup-info-header",
            "unlock-account-name",
            "shell-tabs",
            "startup-welcome",
            timeout = Ui.LONG
        )
            ?: fail("no screen after a cold start: ${ui.screen()}")
        if (first == "startup-welcome") fail("after a cold start the app offers account creation instead of the existing account")
        ui.shot("03-cold-start")
        if (first != "shell-tabs") unlock()
        ui.need("shell-tabs", Ui.LONG, "shell after the cold-start unlock")
        "cold start (new app process) showed '$first' and the account opened with its code"
    }

    // Flow 6: add alice by principal id, alice accepts, safety number, verify.

    @Test fun f04_contactsWithAlice() = flow("04-contacts-alice") {
        ensureShell()
        val request = addContact("alice")
        val commitment = request.optString("root_commitment")
        ui.shot("04-contact-listed")
        openContactRow("alice")
        ui.tap("contacts-open-safety")
        val number = ui.need("contacts-safety-number", Ui.LONG, "safety number")
        val phoneDigits = (number.text ?: number.contentDescription ?: ui.texts()).filter(Char::isDigit)
        val peer = control.rf("alice", "contact", "safety-number", "--contact", commitment)
        val groups = peer.optJSONObject("json")?.optJSONArray("safety_number")
        val peerDigits = (0 until (groups?.length() ?: 0)).joinToString("") { groups!!.optString(it) }
        ui.shot("04-safety-number")
        if (phoneDigits.length < 12) fail("the safety number shows no digits (${phoneDigits.length})")
        // The phone's text also carries the fingerprint; the twelve groups of five must appear in order.
        val matches = peer.optInt("exit") == 0 && peerDigits.length == 60 && phoneDigits.contains(peerDigits)
        val verified = ui.str("contacts_safety_state_verified")
        // A contact verified in an earlier invocation offers no verify action any more.
        if (ui.find("contacts-verify", 3_000) != null) {
            ui.tap("contacts-verify")
            confirmIfAsked()
        }
        if (!ui.eventually(30_000) { ui.hasText(verified) }) fail("not shown as verified after confirming: ${ui.screen()}")
        if (!matches) {
            fail(
                "safety number on the phone does not match alice's (exit ${peer.optInt(
                    "exit"
                )}): phone $phoneDigits peer ${peerDigits.take(80)}"
            )
        }
        "request by principal id reached alice; alice accepted with rf; alice listed; safety number equals alice's; marked verified"
    }

    // Flow 3: the conversation list shows the direct conversation with alice under the derived id.

    @Test fun f05_conversationList() = flow("05-conversations") {
        val conversation = aliceConversation()
        ensureShell()
        ui.tap("shell-tabs-0")
        if (ui.find("conversation-$conversation", 10_000) == null) {
            val sent = control.rf("alice", "conversation", "send", "--conversation", conversation, "--text", "realrun hello from alice")
            control.log("alice conversation send exit ${sent.optInt("exit")} ${sent.optString("stderr").takeLast(300)}")
        }
        ui.need("conversation-$conversation", 60_000, "conversation row")
        ui.shot("05-conversation-row")
        val alice = peerName("alice")
        if (!ui.eventually(30_000) { ui.find("conversation-$conversation", 1_000)?.let { row -> rowText(row).contains(alice) } == true }) {
            fail("the direct conversation row is not titled with alice's name $alice: ${ui.screen()}")
        }
        "Messages lists the direct conversation with alice under the client-derived id $conversation, titled with her contact name"
    }

    // Flow 4: direct chat with alice both ways.

    @Test fun f06_directChat() = flow("06-direct-chat") {
        val conversation = aliceConversation()
        ensureShell()
        openConversation(conversation)
        val notes = mutableListOf<String>()
        if (!ui.hasText(peerName("alice"))) fail("the chat header does not show alice's name: ${ui.screen()}")
        // Send from the phone; alice reads it in the same conversation id.
        val first = "rr-phone-" + token()
        send(first)
        ui.text(first, 30_000) ?: fail("own message bubble did not appear")
        if (!ui.eventually(90_000, 3_000) {
                aliceMessage(conversation, first) != null
            }
        ) {
            fail("alice did not receive the phone's message in $conversation")
        }
        ui.shot("06-sent")
        // Live receive from alice without a manual refresh.
        val incoming = "rr-alice-" + token()
        val sent = control.rf("alice", "conversation", "send", "--conversation", conversation, "--text", incoming)
        if (sent.optInt("exit") != 0) {
            blocked(
                "alice: rf conversation send failed (exit ${sent.optInt("exit")}): ${sent.optString("stderr").takeLast(300)}"
            )
        }
        var liveFailure: String? = null
        if (ui.text(incoming, 60_000) == null) {
            ui.device.pressBack()
            openConversation(conversation)
            liveFailure = if (ui.text(incoming, 20_000) != null) {
                "alice's message was not shown live in the open chat within 60 s; it is there after reopening"
            } else {
                "alice's message never appeared on the phone, even after reopening"
            }
            if (!ui.hasText(incoming)) fail(liveFailure)
        }
        ui.shot("06-received")
        // Receipts as alice sees them on her message once the phone showed it.
        var aliceReceipts = "none"
        ui.eventually(45_000, 4_000) {
            val states = receipts(aliceMessage(conversation, incoming))
            aliceReceipts = states.ifEmpty { listOf("none") }.joinToString(",")
            "read" in states
        }
        notes += "receipts alice sees on her message after the phone displayed it: $aliceReceipts"
        // Typing from alice.
        val typing = control.rf("alice", "conversation", "typing", "start", "--conversation", conversation)
        notes += if (typing.optInt("exit") != 0) {
            "typing start refused on alice (exit ${typing.optInt("exit")})"
        } else if (ui.text(ui.str("ui_peer_typing"), 15_000) != null) "typing shown" else "typing not shown within 15 s"
        // Reply to alice's message.
        val reply = "rr-reply-" + token()
        openActions(incoming)
        ui.tapText(ui.str("ui_reply"))
        send(reply)
        if (!ui.eventually(90_000, 3_000) { aliceMessage(conversation, reply) != null }) fail("alice did not receive the reply")
        val replyRow = aliceMessage(conversation, reply)
        notes += if (replyRow.toString().contains("reply", ignoreCase = true)) {
            "reply reference present on alice"
        } else {
            "reply arrived without a reply reference on alice"
        }
        val ownSide = ownSide(reply)
        notes += "own reply drawn on the ${if (ownSide) "own" else "incoming"} side"
        // Edit the first message.
        val edited = "$first-edited"
        openActions(first)
        ui.tapText(ui.str("ui_edit"))
        ui.fill("composer", edited)
        ui.tap("composer-send")
        if (!ui.eventually(90_000, 3_000) { aliceMessage(conversation, edited)?.optBoolean("edited") == true }) {
            fail("alice did not see the edit (text and edited flag); ${notes.joinToString("; ")}")
        }
        ui.text(edited, 30_000) ?: fail("edited bubble not shown on the phone")
        ui.shot("06-edited")
        // Erase the reply for everyone.
        openActions(reply)
        ui.tapText(ui.str("ui_delete_for_everyone"))
        confirmIfAsked()
        if (!ui.eventually(90_000, 3_000) {
                val row = aliceMessage(conversation, reply)
                row == null || (row.has("erasure") && !row.isNull("erasure"))
            }
        ) {
            fail("alice still shows the reply after delete for everyone; ${notes.joinToString("; ")}")
        }
        ui.shot("06-erased")
        aliceMessage(conversation, edited)?.let {
            notes += "receipts on alice for the phone's message: ${receipts(it).ifEmpty { listOf("none") }.joinToString(",")}"
        }
        val own = ui.desc(ui.str("ui_receipt_delivered"), 2_000) != null || ui.desc(ui.str("ui_receipt_read"), 1_000) != null ||
            ui.desc(ui.str("ui_receipt_sent"), 1_000) != null
        notes += if (own) "own bubbles state their receipt" else "no receipt label found on own bubbles"
        if (!ownSide) fail("the phone's own reply is drawn on the incoming side; ${notes.joinToString("; ")}")
        liveFailure?.let { fail("$it; otherwise: ${notes.joinToString("; ")}") }
        "send, live receive, reply, edit and erase for everyone all reached alice in $conversation; ${notes.joinToString("; ")}"
    }

    // Flow 4 (continued): background and return keeps the session and the open chat.

    @Test fun f07_backgroundAndReturn() = flow("07-background") {
        val conversation = aliceConversation()
        ensureShell()
        openConversation(conversation)
        val marker = "rr-bg-" + token()
        ui.fill("composer", marker)
        ui.device.pressHome()
        Thread.sleep(BACKGROUND_WAIT)
        val incoming = "rr-alice-bg-" + token()
        control.rf("alice", "conversation", "send", "--conversation", conversation, "--text", incoming)
        ui.launch()
        val back = ui.first(
            "composer",
            "shell-tabs",
            "startup-unlock-header",
            "startup-details-header",
            "startup-info-header",
            "unlock-account-name",
            timeout = Ui.LONG
        )
            ?: fail("nothing shown after returning: ${ui.screen()}")
        val unlocked = back != "composer" && back != "shell-tabs"
        if (unlocked) unlock()
        val inChat = ui.find("composer", 20_000) != null
        if (!inChat) fail("after returning${if (unlocked) " and unlocking" else ""} the app did not reopen the chat: ${ui.screen()}")
        if (ui.text(incoming, 60_000) == null) {
            fail(
                "alice's message sent while the app was in the background did not appear after returning"
            )
        }
        val draft = ui.eventually(10_000) { ui.hasText(marker) }
        ui.shot("07-returned")
        if (draft) {
            ui.tap("composer-send")
            if (!ui.eventually(
                    60_000,
                    3_000
                ) { aliceMessage(conversation, marker) != null }
            ) {
                fail("the kept draft was not delivered after sending")
            }
        }
        "after ${BACKGROUND_WAIT / 1000} s in the background the app returned to the open chat" +
            (if (unlocked) " after the unlock step" else " without an unlock") +
            "; the message alice sent meanwhile is shown" + (if (draft) "; the draft was kept and sent" else "; the draft was not kept")
    }

    // Flow 7, media M1: receive an image and a file from alice, view them; send an image to alice.

    @Test fun f08_mediaM1() = flow("08-media") {
        val conversation = aliceConversation()
        ensureShell()
        val commitment = RunState["commitment"] ?: blocked("needs flow 04 (contact with alice)")
        val notes = mutableListOf<String>()
        notes += exchangeCards("alice", "08-my-card")
        // This device's id from My devices; alice's attachment send addresses it directly.
        ensureShell()
        openSettings()
        ui.tap("devices")
        val current = ui.str("settings_current_device")
        var device: String? = null
        ui.eventually(30_000) {
            device = ui.device.findObjects(By.pkg(ui.packageName).res(java.util.regex.Pattern.compile("device-.+")))
                .firstOrNull { row -> rowText(row).contains(current) }?.resourceName
            device != null
        }
        val phoneDevice = device ?: fail("My devices lists no current device: ${ui.screen()}")
        val targetJson = JSONArray(listOf(commitment, phoneDevice)).toString().toByteArray()
        val target = "root~" + Base64.getUrlEncoder().withoutPadding().encodeToString(targetJson)
        val peers = control.config().getJSONObject("peers")
        val alice = peers.getJSONObject("alice")
        val captions = mutableListOf<String>()
        var receiveBlocked: String? = null
        for ((file, kind, size) in listOf(Triple("rr-image.png", "png", 60_000), Triple("rr-file.txt", "text", 3_000))) {
            val made = control.makeFile(file, kind, size)
            val caption = "rr-media-$file-" + token()
            val id = "rr-msg-" + token()
            val result = control.rf(
                "alice", "dm", "send", "--conversation", conversation, "--message", id, "--envelope", id,
                "--source-principal", alice.getString("principal_id"), "--sender", alice.getString("device_id"),
                "--recipient-device", phoneDevice, "--recipient-principal-commitment", commitment, "--target", target,
                "--body", caption, "--attach", made.getString("path"), timeoutSecs = 120
            )
            if (result.optInt("exit") != 0) {
                val stderr = result.optString("stderr").takeLast(300)
                receiveBlocked = "alice: rf dm send --attach $file failed (exit ${result.optInt("exit")}): $stderr"
                break
            }
            notes += "alice sent $file"
            captions += caption
        }
        ensureShell()
        openConversation(conversation)
        if (receiveBlocked == null) {
            for (caption in captions) {
                ui.text(caption, 90_000) ?: fail("message $caption from alice did not appear; ${notes.joinToString("; ")}")
            }
            // rf dm send --attach declares the sniffed media type: the PNG arrives as an image tile (no file name drawn),
            // the text file as a file card named by its stem.
            val pending = listOf(ui.str("chat_media_download"), ui.str("chat_media_loading"))
            val incoming = java.util.regex.Pattern.compile("attachment-(?!local:).+")
            for (file in listOf("rr-image.png", "rr-file.txt")) {
                // The file name is drawn with bidi isolates around its extension: match the stem on the attachment card.
                val stem = file.substringBefore('.')
                fun visible(): UiObject2? = if (file.endsWith(".png")) {
                    ui.device.findObjects(By.res(incoming)).lastOrNull { it.findObject(By.textContains("rr-file")) == null }
                } else {
                    ui.device.findObject(By.res(incoming).hasDescendant(By.textContains(stem)))
                }

                // The card may sit above the visible part of the timeline (new rows scroll it away): look upwards too.
                fun card(): UiObject2? {
                    visible()?.let { return it }
                    val timeline = ui.device.findObject(By.res("chat-timeline")) ?: return null
                    repeat(3) {
                        runCatching { timeline.scroll(androidx.test.uiautomator.Direction.UP, 0.5f) }
                        visible()?.let { return it }
                    }
                    return null
                }
                if (!ui.eventually(30_000) { card() != null }) fail("the attachment card of $file is not shown; ${ui.screen()}")
                card()?.click()
                val downloaded = ui.eventually(90_000, 2_000) {
                    ui.hasText(ui.str("chat_media_open_title")) || ui.find("media-viewer", 300) != null ||
                        card()?.let { label -> pending.none { rowText(label).contains(it) } } == true
                }
                val state = card()?.let { rowText(it) } ?: "opened"
                notes += if (downloaded) {
                    "phone downloaded $file ($state)"
                } else {
                    "phone did not finish downloading $file within 90 s ($state)"
                }
                if (!downloaded) fail(notes.joinToString("; "))
                returnToChat(conversation)
            }
            ui.shot("08-received")
        }
        // Send an image from the phone. The system photo picker is answered with a test image (the shared phone's
        // own photos are never offered); the app's copy, prepare, upload and send are the real path.
        val image = File(ui.targetContext.cacheDir, "media-capture").apply { mkdirs() }.resolve("rr-phone.png")
        image.writeBytes(testPng())
        val expected = sha256(image.readBytes())
        val uri = Uri.parse("content://${ui.packageName}.chatmedia/capture/rr-phone.png")
        val monitor = ui.stubPicker(setOf(MediaStore.ACTION_PICK_IMAGES, Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT), uri)
        try {
            (ui.desc(ui.str("ui_attach"), Ui.SHORT) ?: fail("no attach control in the composer")).click()
            ui.need("attach-menu")
            ui.tapText(ui.str("chat_attach_photo"))
            if (!ui.eventually(10_000, 500) { monitor.hits > 0 }) fail("the app did not request the photo picker")
            ui.tap("preview-send", 20_000)
        } finally {
            ui.removeMonitor(monitor)
        }
        var imported: JSONObject? = null
        val delivered = ui.eventually(150_000, 5_000) {
            val row = aliceMessages(conversation).lastOrNull { row ->
                !row.optBoolean("is_own", true) && messageText(row) !in captions && row.toString().contains("media")
            } ?: return@eventually false
            val out = control.freshPath("alice-import.bin")
            val result = control.rf(
                "alice", "attachment", "import", "--message",
                row.optString(
                    "id"
                ),
                "--index", "0", "--output", out, timeoutSecs = 120
            )
            if (result.optInt("exit") != 0) return@eventually false
            imported = control.fileInfo(out)
            true
        }
        if (!delivered) {
            val detail = (listOfNotNull(receiveBlocked) + notes).joinToString("; ")
            fail("alice never received an importable image from the phone; ${ui.screen()}; $detail")
        }
        val same = imported?.optString("sha256") == expected
        notes += if (same) {
            "alice imported identical bytes of the phone's image"
        } else {
            "alice imported ${imported?.optLong("size")} bytes with a different sha256"
        }
        if (!same) fail(notes.joinToString("; "))
        receiveBlocked?.let { blocked("receive: $it; send: ${notes.joinToString("; ")}") }
        notes.joinToString("; ")
    }

    // Flow 5: groups with alice and bob.

    @Test fun f09_groups() = flow("09-groups") {
        ensureShell()
        val notes = mutableListOf<String>()
        addContact("bob")
        notes += "bob added as a contact"
        // Group media goes to every member's home node: the phone and both peers exchange contact cards (flow 08 did
        // it for alice already; doing it again here keeps this flow's precondition to flow 04).
        notes += exchangeCards("alice")
        notes += exchangeCards("bob")
        ensureShell()
        val name = "rr-group-" + token()
        ui.tap("shell-tabs-1")
        // The Groups tab offers creation in exactly one place: the empty state's action when there is no group, the
        // list's create button otherwise (also while the first page loads). Wait for whichever is laid out; the header
        // alone says nothing about which one will be there.
        val entry = ui.first("groups-empty-action", "groups-create", "groups-error", timeout = Ui.LONG)
            ?: fail("the Groups tab offered no create action: ${ui.screen()}")
        if (entry == "groups-error") fail("the Groups tab showed an error: ${ui.screen()}")
        notes += "created from '$entry'"
        ui.tap(entry)
        ui.fill(null, name)
        ui.tap("groups-review-create")
        ui.tap("host-confirmation-confirm", Ui.SHORT)
        // After creation the app opens the new group's conversation or its detail.
        val after = ui.first("composer", "groups-open-chat", "groups-error", timeout = 90_000)
            ?: fail("group creation was not acknowledged: ${ui.screen()}")
        if (after == "groups-error") fail("group creation ended in an error: ${ui.screen()}")
        ui.shot("09-group-created")
        notes += "group created; the app opened its ${if (after == "composer") "conversation" else "detail"}"
        if (after == "composer") ui.device.pressBack()
        if (ui.find("groups-open-members", 5_000) == null) {
            ui.tap("shell-tabs-1")
            ui.tapText(name, 30_000)
        }
        ui.tap("groups-open-members", Ui.LONG)
        for (peer in listOf("alice", "bob")) {
            ui.tap("groups-open-invite", Ui.LONG)
            ui.text(peerName(peer), 30_000)?.click() ?: fail("$peer is not offered in the invite picker: ${ui.screen()}")
            ui.tap("host-confirmation-confirm", Ui.SHORT)
            // The app returns to the member list once the SDK answered the add; the picker also shows the principal.
            ui.find("groups-open-invite", 90_000) ?: fail("the app did not return to the member list after adding $peer: ${ui.screen()}")
            val principal = peerArg(peer, "principal")
            if (!ui.eventually(90_000, 2_000) {
                    ui.hasText(principal) || ui.hasText(principal.removePrefix("principal-")) || ui.hasText(peerName(peer))
                }
            ) {
                fail("$peer is not listed as a member after the add: ${ui.screen()}; ${notes.joinToString("; ")}")
            }
        }
        ui.shot("09-group-members")
        notes += "members list shows alice and bob"
        var group: JSONObject? = null
        if (!ui.eventually(120_000, 4_000) {
                group = jsonRows(control.rf("alice", "group", "list"), "groups").firstOrNull {
                    it.optString("name") == name || it.optString("title") == name
                }
                group != null
            }
        ) {
            fail("alice never listed the group $name; ${notes.joinToString("; ")}")
        }
        val groupId = group!!.optString("group_id")
        val groupConversation = group!!.optString("conversation_id").ifEmpty { groupId }
        notes += "alice lists the group"
        // Phone sends; alice and bob read it. The members page's own back control returns to the group's detail.
        ui.find("groups-header-back", 5_000)?.click() ?: ui.device.pressBack()
        ui.tap("groups-open-chat", Ui.LONG)
        val fromPhone = "rr-gphone-" + token()
        send(fromPhone)
        for (peer in listOf("alice", "bob")) {
            if (!ui.eventually(120_000, 4_000) { groupTexts(peer, groupId, groupConversation).any { it.contains(fromPhone) } }) {
                fail("$peer did not receive the phone's group message; ${notes.joinToString("; ")}")
            }
        }
        notes += "alice and bob received the phone's group message"
        val fromBob = "rr-gbob-" + token()
        val bob = control.config().getJSONObject("peers").getJSONObject("bob")
        val sent = control.rf(
            "bob", "group", "send", "--group", groupId, "--conversation", groupConversation, "--message", "rr-gmsg-" + token(),
            "--sender", bob.getString("device_id"), "--body", fromBob, timeoutSecs = 120
        )
        if (sent.optInt("exit") != 0) {
            blocked(
                "bob: rf group send failed (exit ${sent.optInt(
                    "exit"
                )}): ${sent.optString("stderr").takeLast(300)}; ${notes.joinToString("; ")}"
            )
        }
        ui.text(fromBob, 90_000) ?: fail("bob's group message did not appear on the phone; ${notes.joinToString("; ")}")
        ui.shot("09-group-chat")
        notes += "bob's group message appeared on the phone"
        // The phone sends an image in the group through the same test-image interception as the direct chat (the
        // shared phone's own photos are never offered); alice imports it, and the bytes must be the phone's PNG.
        val groupImage = File(ui.targetContext.cacheDir, "media-capture").apply { mkdirs() }.resolve("rr-group.png")
        groupImage.writeBytes(testPng())
        val groupExpected = sha256(groupImage.readBytes())
        val groupUri = Uri.parse("content://${ui.packageName}.chatmedia/capture/rr-group.png")
        val pick = ui.stubPicker(setOf(MediaStore.ACTION_PICK_IMAGES, Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT), groupUri)
        try {
            (ui.desc(ui.str("ui_attach"), Ui.SHORT) ?: fail("no attach control in the group composer")).click()
            ui.need("attach-menu")
            ui.tapText(ui.str("chat_attach_photo"))
            if (!ui.eventually(10_000, 500) { pick.hits > 0 }) fail("the app did not request the photo picker in the group")
            ui.tap("preview-send", 20_000)
        } finally {
            ui.removeMonitor(pick)
        }
        var groupImported: JSONObject? = null
        val groupDelivered = ui.eventually(150_000, 5_000) {
            val read = control.rf("alice", "group", "read", "--group", groupId, "--conversation", groupConversation)
            val rows = jsonRows(read, "messages")
            val row = rows.lastOrNull { !it.optBoolean("is_own", true) && it.toString().contains("media") } ?: return@eventually false
            val out = control.freshPath("alice-group-import.bin")
            val result = control.rf(
                "alice", "attachment", "import", "--message", row.optString("id"), "--index", "0", "--output", out, timeoutSecs = 120
            )
            if (result.optInt("exit") != 0) return@eventually false
            groupImported = control.fileInfo(out)
            true
        }
        ui.shot("09-group-image")
        if (!groupDelivered) fail("alice never received an importable image in the group; ${ui.screen()}; ${notes.joinToString("; ")}")
        if (groupImported?.optString("sha256") != groupExpected) {
            val size = groupImported?.optLong("size")
            fail("alice imported $size bytes of the group image with a different sha256; ${notes.joinToString("; ")}")
        }
        notes += "alice imported identical bytes of the phone's group image (64 by 64 PNG)"
        notes.joinToString("; ")
    }

    // Flow 10: settings (devices, language), change the account code, lock and unlock with the new code.

    @Test fun f10_settingsAndCode() = flow("10-settings-code") {
        ensureShell()
        val notes = mutableListOf<String>()
        openSettings()
        ui.tap("devices")
        val current = ui.str("settings_current_device")
        if (ui.text(current, Ui.LONG) == null) fail("My devices does not list this device: ${ui.screen()}")
        notes += "My devices lists this device"
        ui.device.pressBack()
        ui.tap("appearance")
        ui.tap("language")
        val languages = ui.eventually(10_000) { ui.ids().contains("language:") }
        notes += if (languages) "language screen lists languages" else "language screen shows no language rows"
        ui.shot("10-language")
        ensureShell()
        openSettings()
        ui.tap("account-security")
        ui.tap("change-code")
        ui.need("code-form", Ui.LONG, "change code form")
        val old = RunCode.load() ?: blocked("no saved run code")
        val next = RunCode.next(old)
        try {
            old.forEach { ui.tap("code-key-$it") }
            next.forEach { ui.tap("code-key-$it") }
            if (!ui.scrollTo("code-submit")) fail("the code change has no submit action on screen: ${ui.screen()}")
            ui.tap("code-submit")
            ui.find("code-changed", Ui.LONG) ?: fail("the code change was not confirmed: ${ui.screen()}")
            RunCode.save(next)
        } finally {
            old.fill('\u0000')
            next.fill('\u0000')
        }
        ui.shot("10-code-changed")
        notes += "account.passphrase.rotate accepted"
        ensureShell()
        openSettings()
        ui.tap("account-security")
        ui.tap("lock-account")
        confirmIfAsked()
        unlock()
        ensureShell()
        notes += "lock and unlock with the new code work"
        notes.joinToString("; ")
    }

    // Flow 14: backup create and verify.

    @Test fun f11_backup() = flow("11-backup") {
        ensureShell()
        openSettings()
        ui.tap("backup")
        ui.tap("backup-create", Ui.LONG)
        val passphrase = UUID.randomUUID().toString().replace("-", "").take(20).toCharArray()
        try {
            ui.need("backup-create-confirm", Ui.LONG, "backup passphrase form")
            ui.keys("backup-passphrase", passphrase)
            ui.tap("backup-create-confirm")
            confirmIfAsked()
        } finally {
            passphrase.fill('\u0000')
        }
        val failed = ui.str("backup_state_failed")
        val done = ui.eventually(180_000, 3_000) { ui.ids().contains("job-save") || ui.hasText(failed) }
        if (ui.hasText(failed)) fail("the backup job failed: ${ui.screen()}")
        ui.shot("11-backup")
        if (!done) fail("the backup job did not finish within 180 s: ${ui.screen()}")
        "backup job created and finished (${ui.texts().take(160)})"
    }

    // Flows 8, 9, 12, 15 and the pairing entry: the screens load and show honest states.

    @Test fun f12_honestStates() = flow("12-honest-states") {
        ensureShell()
        val notes = mutableListOf<String>()
        ui.tap("shell-tabs-2")
        val agents = ui.first("agents-empty", "agents-account", "agents-error", "agents-header", timeout = Ui.LONG)
        ui.shot("12-agents")
        notes += "Agents tab: ${agents ?: "nothing"}"
        ensureShell()
        ui.tap("shell-tabs-3")
        ui.tap("shell-more-tasks", Ui.SHORT)
        Thread.sleep(3_000)
        ui.shot("12-tasks")
        notes += "Tasks: ${ui.texts().take(120)}"
        ensureShell()
        ui.tap("shell-tabs-3")
        ui.tap("shell-more-calls", Ui.SHORT)
        Thread.sleep(3_000)
        ui.shot("12-calls")
        notes += "Calls: ${ui.texts().take(120)}"
        ensureShell()
        openSettings()
        ui.tap("account-security")
        ui.tap("privacy")
        Thread.sleep(3_000)
        ui.shot("12-privacy")
        val policy = ui.ids().contains("policy:")
        notes += if (policy) {
            "privacy shows the request policy from the SDK"
        } else {
            "privacy shows no request policy rows: ${ui.texts().take(120)}"
        }
        ui.find("open-account", 3_000)?.let { row ->
            val label = rowText(row)
            if (row.isEnabled) {
                row.click()
                Thread.sleep(2_000)
                ui.shot("12-open-account")
                notes += "open account: ${ui.texts().take(160)}"
            } else {
                notes += "open account row disabled with its typed unavailable state: $label"
            }
        }
        ensureShell()
        openSettings()
        ui.tap("devices")
        val link = ui.need("link-device", Ui.SHORT, "link device row")
        if (link.isEnabled) {
            link.click()
            Thread.sleep(3_000)
            ui.shot("12-pairing")
            notes += "link device: ${ui.texts().take(160)}"
        } else {
            notes += "link device row disabled: ${rowText(link)}"
        }
        if (agents == null) fail(notes.joinToString("; "))
        notes.joinToString("; ")
    }

    // Helpers.

    private fun challengePosition(index: Int): Int {
        val field = ui.need("challenge-$index")
        val label = listOfNotNull(field.contentDescription, field.text).joinToString(" ") +
            " " + (field.parent?.children?.mapNotNull { it.text }?.joinToString(" ") ?: "")
        return Regex("\\b([1-9]|1[0-9]|2[0-4])\\b").find(label)?.value?.toInt()
            ?: fail("could not read the position of challenge word ${index + 1}")
    }

    /** After creation the app shows the permission explanations; leave them without granting anything. */
    private fun leavePermissionFlow() {
        repeat(4) {
            if (ui.first("shell-tabs", timeout = 4_000) != null) return
            if (!ui.foreground()) {
                ui.launch()
                return@repeat
            }
            ui.device.pressBack()
        }
    }

    // Flow 5 (invitations): the Groups tab reads group.invitations.list together with the group list; it must load
    // without an error. Both peers are contacts by now, so the SDK joins their groups directly and no invitation is held.

    @Test fun f13_invitationList() = flow("13-invitations") {
        ensureShell()
        ui.tap("shell-tabs-1")
        val shown = ui.first("groups-header", "groups-empty", "groups-error", timeout = Ui.LONG)
            ?: fail("the Groups tab did not load: ${ui.screen()}")
        ui.device.waitForIdle()
        val codeFailure = ui.str("groups_error_code", "\u0000").substringBefore('\u0000')
        val failed = shown == "groups-error" || ui.find("groups-error", 3_000) != null
        if (failed || (codeFailure.isNotBlank() && ui.hasText(codeFailure))) {
            fail("the group and invitation lists did not load: ${ui.screen()}")
        }
        val pending = ui.hasText(ui.str("groups_invitations"))
        ui.shot("13-invitations")
        "group.invitations.list loaded with the group list and no error; " +
            if (pending) "pending invitations are listed" else "no pending invitation (both peers are contacts; their groups join directly)"
    }

    // Flow 10 (account actions): sign out on this phone, then sign back in with the run's code.

    @Test fun f14_signOutAndBack() = flow("14-sign-out") {
        ensureShell()
        openSettings()
        ui.tap("account-security")
        if (!reach("sign-out")) fail("Account and security has no Sign out row: ${ui.screen()}")
        ui.tap("sign-out")
        if (!reach("confirm-exit")) fail("Sign out asked for no confirmation: ${ui.screen()}")
        ui.shot("14-sign-out-confirm")
        ui.tap("confirm-exit")
        val locked = ui.first("onboarding-header", "startup-unlock-header", "unlock-account-name", "shell-locked", timeout = Ui.LONG)
            ?: fail("no signed-out state after Sign out: ${ui.screen()}")
        ui.shot("14-signed-out")
        unlock()
        // After an unlock the app reopens the screens that were open: here Account and security.
        val back = ui.first("lock-account", "sign-out", "shell-tabs", timeout = Ui.LONG)
            ?: fail("signing back in opened neither the account screen nor the shell: ${ui.screen()}")
        ensureShell()
        "account.sign_out accepted; the signed-out state showed '$locked' and offered the account; signed back in with the " +
            "six-digit code and the app reopened '$back'"
    }

    // Flow 10 (account actions), the last step of a run: remove the account from this phone. It erases the account, so
    // nothing may run after it on this install.

    @Test fun f15_removeFromPhone() = flow("15-remove-local") {
        ensureShell()
        val name = RunState["name"] ?: blocked("needs flow 01 (display name)")
        openSettings()
        ui.tap("account-security")
        if (!reach("remove-account")) fail("Account and security has no Remove from this phone row: ${ui.screen()}")
        ui.tap("remove-account")
        if (!reach("confirm-exit")) fail("Remove from this phone asked for no confirmation: ${ui.screen()}")
        ui.shot("15-remove-confirm")
        ui.tap("confirm-exit")
        val after = ui.first("startup-gateway", "startup-welcome", "onboarding-header", "startup-unlock-header", timeout = Ui.LONG)
            ?: fail("no start state after the removal: ${ui.screen()}")
        val stillListed = ui.eventually(5_000) { ui.hasText(name) }
        ui.shot("15-removed")
        if (stillListed) fail("the removed account '$name' is still offered: ${ui.screen()}")
        "account.remove_local accepted; the app returned to '$after' and no longer offers '$name'"
    }

    /** Brings the app to its unlocked shell with the tab bar shown. */
    private fun ensureShell() {
        if (!ui.foreground()) ui.launch() else ui.portrait()
        repeat(6) {
            when (
                ui.first(
                    "shell-tabs",
                    "startup-unlock-header",
                    "startup-details-header",
                    "startup-info-header",
                    "unlock-account-name",
                    timeout = 3_000
                )
            ) {
                "shell-tabs" -> return
                null -> {
                    if (ui.find("host-confirmation-deny", 500) != null) ui.tap("host-confirmation-deny") else ui.device.pressBack()
                    if (!ui.foreground()) ui.launch()
                }
                else -> unlock()
            }
        }
        ui.need("shell-tabs", Ui.SHORT, "unlocked shell")
    }

    /** Walks the locked screens to the code page and unlocks with the run's code. */
    private fun unlock() {
        val end = System.currentTimeMillis() + Ui.LONG
        while (System.currentTimeMillis() < end) {
            // The locked screens by their headers: A01 (account offered), its details, the unlock information.
            when (
                ui.first(
                    "shell-tabs",
                    "composer",
                    "unlock-account-name",
                    "startup-info-header",
                    "startup-details-header",
                    "startup-unlock-header",
                    "shell-locked",
                    "onboarding-header",
                    timeout = Ui.SHORT
                )
            ) {
                "shell-tabs", "composer" -> return
                "startup-unlock-header" -> ui.tap("startup-unlock-continue")
                "shell-locked" -> ui.tap("shell-locked-unlock")
                "startup-details-header" -> ui.tap("startup-view-unlock")
                "startup-info-header" -> ui.tap("startup-unlock-code")
                // After a lock from settings the account chooser offers the locked account by name.
                "onboarding-header" -> lockedAccountRow()?.click() ?: fail("the account chooser offers no account: ${ui.screen()}")
                "unlock-account-name" -> {
                    val code = RunCode.load() ?: blocked("no saved run code on this install")
                    try {
                        ui.keys("unlock-code", code)
                    } finally {
                        code.fill('\u0000')
                    }
                    ui.tap("unlock-account")
                    // The shell, or the screen that was open before the lock (it comes back after the unlock): done once
                    // no locked screen is left.
                    val locked =
                        listOf(
                            "unlock-account-name",
                            "onboarding-header",
                            "startup-unlock-header",
                            "startup-details-header",
                            "startup-info-header"
                        )
                    if (ui.eventually(
                            Ui.LONG
                        ) { ui.foreground() && locked.none { tag -> ui.find(tag, 300) != null } && ui.ids().isNotEmpty() }
                    ) {
                        ui.shot("unlocked")
                        return
                    }
                    fail("unlock with the run's code did not open the shell: ${ui.screen()}")
                }
                null -> fail("no unlock step on screen: ${ui.screen()}")
            }
            Thread.sleep(500)
        }
        fail("unlock did not finish within ${Ui.LONG / 1000} s: ${ui.screen()}")
    }

    private fun lockedAccountRow(): UiObject2? =
        ui.device.findObjects(By.pkg(ui.packageName).res(java.util.regex.Pattern.compile("account-.+"))).firstOrNull()

    private fun openSettings() {
        ui.tap("shell-tabs-3")
        ui.tap("shell-more-settings", Ui.SHORT)
    }

    /**
     * The phone pins [peer]'s home node from the peer's card: the SDK refuses media to a contact known only by a lookup
     * (typed authority_unavailable) until the sender has imported that contact's card (contact.add with the card).
     */
    private fun importCard(peer: String): String {
        val card = control.rf(peer, "contact", "card").optJSONObject("json")?.optString("card").orEmpty()
        if (!card.startsWith("ramflux-card:")) return "$peer's contact card could not be read"
        ensureShell()
        ui.tap("shell-tabs-3")
        ui.tap("shell-more-contacts", Ui.SHORT)
        ui.tap("contacts-add")
        ui.fill(null, card)
        ui.tap("contacts-send")
        confirmIfAsked()
        ui.shot("card-$peer")
        return "the phone imported $peer's contact card"
    }

    /**
     * The phone's own contact card goes to [peer] (so the peer's record of the phone names its home node) and the
     * phone imports the peer's card: media in either direction needs both.
     */
    private fun exchangeCards(peer: String, shot: String? = null): String {
        ensureShell()
        openSettings()
        ui.tap("account-security")
        ui.tap("my-card")
        ui.need("my-card-text", Ui.LONG, "contact card text")
        shot?.let(ui::shot)
        // The card is drawn in short runs; Share hands over the whole text, read here without opening the sheet.
        if (!ui.scrollTo("my-card-share")) fail("My contact card has no Share action: ${ui.screen()}")
        val shared = ui.captureShare { ui.tap("my-card-share") }
        val card = Regex("ramflux-card:v[0-9]+:[A-Za-z0-9_=.-]+").find(shared.orEmpty())?.value
            ?: fail("Share on My contact card handed over no card text: ${ui.screen()}")
        val added = control.rf(peer, "contact", "add", "--card", card)
        val sent = if (added.optInt("exit") == 0) {
            "$peer added the phone's contact card"
        } else {
            "$peer: rf contact add --card exit ${added.optInt("exit")}: ${added.optString("stderr").takeLast(200)}"
        }
        return sent + "; " + importCard(peer)
    }

    /** Scrolls [tag] into view, again when the list recomposed under the scroll (a stale node). */
    private fun reach(tag: String): Boolean {
        repeat(3) {
            try {
                return ui.scrollTo(tag)
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                ui.device.waitForIdle()
            }
        }
        return false
    }

    private fun confirmIfAsked() {
        ui.find("host-confirmation-confirm", 3_000)?.click()
        ui.device.waitForIdle()
    }

    /** Adds [peer] by principal id unless listed already; the peer accepts with rf. Returns the peer's row for the phone. */
    private fun addContact(peer: String): JSONObject {
        val name = RunState["name"] ?: blocked("needs flow 01 (display name)")
        ui.tap("shell-tabs-3")
        ui.tap("shell-more-contacts", Ui.SHORT)
        val listed = ui.text(peerName(peer), 5_000) != null
        if (!listed) {
            ui.tap("contacts-add")
            ui.fill(null, peerArg(peer, "principal"))
            ui.tap("contacts-send")
            confirmIfAsked()
            ui.shot("contact-request-$peer")
        }
        var request: JSONObject? = null
        if (!ui.eventually(90_000, 3_000) {
                request = jsonRows(control.rf(peer, "contact", "list"), "contacts").firstOrNull { it.optString("display_name") == name }
                request != null
            }
        ) {
            fail("$peer never saw a contact request from $name; phone: ${ui.screen()}")
        }
        val row = request!!
        RunState["principal"] = row.optString("principal_id")
        RunState["commitment"] = row.optString("root_commitment")
        if (row.optString("state") != "accepted") {
            val accepted = control.rf(peer, "contact", "accept", "--principal", row.optString("principal_id"))
            if (accepted.optInt("exit") != 0) {
                blocked(
                    "$peer: rf contact accept failed (exit ${accepted.optInt("exit")}): ${accepted.optString("stderr").takeLast(300)}"
                )
            }
        }
        // The add page stays open after the request; the list is one back away.
        // Back may first close the keyboard, so press until the add page is gone.
        repeat(3) { if (ui.find("contacts-send", 1_000) != null) ui.device.pressBack() }
        if (!ui.eventually(90_000, 3_000) {
                if (ui.hasText(peerName(peer))) {
                    true
                } else {
                    ui.device.findObject(By.pkg(ui.packageName).text(ui.str("contacts_refresh")))?.click()
                    false
                }
            }
        ) {
            fail("$peer did not appear in the phone's contacts after accepting: ${ui.screen()}")
        }
        return row
    }

    private fun openContactRow(peer: String) {
        ui.text(peerName(peer), Ui.SHORT)?.click() ?: fail("no contact row for $peer")
        ui.need("contacts-open-safety", Ui.SHORT, "contact detail")
    }

    private fun aliceConversation(): String {
        RunState["conversation"]?.let { return it }
        val commitment = RunState["commitment"] ?: blocked("needs flow 04 (contact with alice)")
        val derived = control.dmConversation(commitment, peerArg("alice", "commitment"))
        if (!derived.startsWith("dm:")) fail("could not derive the dm conversation id")
        RunState["conversation"] = derived
        return derived
    }

    private fun openConversation(id: String) {
        if (ui.find("composer", 1_000) != null && ui.find("conversation-$id", 500) == null && ui.hasText(peerName("alice"))) return
        ensureShell()
        ui.tap("shell-tabs-0")
        ui.tap("conversation-$id", 30_000)
        ui.need("composer", 20_000, "chat composer")
    }

    private fun returnToChat(id: String) {
        repeat(3) {
            if (ui.find("composer", 2_000) != null) return
            if (ui.find("viewer-close", 500) != null) ui.tap("viewer-close") else ui.device.pressBack()
            if (!ui.foreground()) ui.launch()
        }
        openConversation(id)
    }

    private fun send(text: String) {
        ui.fill("composer", text)
        ui.tap("composer-send")
    }

    private fun openActions(fragment: String) {
        repeat(2) {
            val bubble = ui.text(fragment, 20_000) ?: fail("no bubble with '$fragment'")
            ui.longPress(bubble)
            if (ui.find("message-menu", Ui.SHORT) != null) return
        }
        fail("a long press on the bubble '$fragment' did not open the message actions; ${ui.screen()}")
    }

    /** Own bubbles sit on the trailing (end) half of the timeline. */
    private fun ownSide(fragment: String): Boolean {
        val bubble = ui.text(fragment, 10_000) ?: return false
        val width = ui.device.displayWidth
        val bounds = bubble.visibleBounds
        return bounds.right > width * 3 / 4 && bounds.left > width / 8
    }

    private fun rowText(row: UiObject2): String = (
        listOf(row) + row.children + row.children.flatMap {
            it.children
        }
        ).flatMap { listOfNotNull(it.text, it.contentDescription) }.joinToString(" ")

    private fun peerName(peer: String): String = control.config().getJSONObject("peers").getJSONObject(peer).getString("display_name")

    private fun peerArg(peer: String, field: String): String = args.peer(peer, field).ifEmpty { fail("missing peer argument $peer $field") }

    private fun jsonRows(result: JSONObject, key: String): List<JSONObject> {
        val rows = result.optJSONObject("json")?.optJSONArray(key) ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    private fun aliceMessages(conversation: String): List<JSONObject> =
        jsonRows(control.rf("alice", "dm", "read", "--conversation", conversation, "--limit", "100"), "messages")

    private fun messageText(row: JSONObject): String =
        row.optJSONObject("content")?.optJSONObject("message")?.optString("text")?.takeIf { it.isNotEmpty() } ?: row.optString("text")

    private fun aliceMessage(conversation: String, fragment: String): JSONObject? = aliceMessages(conversation).firstOrNull {
        messageText(it).contains(fragment)
    }

    private fun receipts(row: JSONObject?): List<String> {
        val list = row?.optJSONArray("receipts") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("state") }
    }

    private fun groupTexts(peer: String, group: String, conversation: String): List<String> {
        val value = control.rf(peer, "group", "read", "--group", group, "--conversation", conversation)
        return jsonRows(value, "messages").map(::messageText) + value.optString("stdout")
    }

    private fun token() = UUID.randomUUID().toString().take(8)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A small deterministic RGB PNG (64 by 64 gradient). */
    private fun testPng(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 64) bitmap.setPixel(x, y, android.graphics.Color.rgb(x * 4, y * 4, (x + y) * 2))
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    companion object {
        private const val BACKGROUND_WAIT = 8_000L

        /** An account exists on this install when flow 01 saved its code (flows run in separate processes). */
        private val accountReady get() = RunCode.exists()

        /** Flows run in this process so far; am instrument starts a new app process per invocation. */
        private var flowsInProcess = 0
        private var coldProcess = false

        /** True while a screen with recovery words is shown: no screenshot is taken, not even of a failure. */
        private var secretScreen = false

        @JvmStatic @AfterClass
        fun erase() {
            RealRunProbe.erase()
        }
    }
}

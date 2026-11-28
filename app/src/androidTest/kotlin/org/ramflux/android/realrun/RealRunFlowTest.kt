// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.realrun

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
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

    /**
     * The onboarding path shared by f01 and f22 on a fresh install: launch, node review and confirm, to the display-name
     * form. [existing] is called when the app already holds an account.
     */
    private fun onboardToDisplayName(existing: () -> Nothing) {
        if (!control.health()) blocked("peer control at ${args.control} is not reachable from the phone (adb reverse)")
        ui.launch()
        ui.shot("01-start")
        when (ui.first("startup-gateway", "startup-welcome", "startup-unlock", "shell-tabs", timeout = Ui.LONG)) {
            "startup-gateway" -> ui.tap("startup-change-node")
            "startup-welcome" -> ui.tap("startup-link")
            null -> fail("the app showed no startup screen: ${ui.screen()}")
            else -> existing()
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
    }

    @Test fun f01_connectAndCreateAccount() = flow("01-connect-create", needsAccount = false) {
        onboardToDisplayName { fail("the app already holds an account; the run needs a fresh install") }
        val name = "Phone RealRun " + UUID.randomUUID().toString().take(4)
        // A new account: facts kept from an earlier run (its conversation id, device id) would point at the old one.
        RunState.clear()
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

    // Flow 1 (continued): every invocation starts a new app process (the script stops the previous one first), so this
    // first flow of an invocation is a terminate-and-relaunch: the account opens through the device-bound key, no code.

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
        if (first != "shell-tabs") fail("the relaunched app asked for the account code ('$first'): ${ui.screen()}")
        "terminate and relaunch (new app process) opened the shell with no prompt"
    }

    // Flow 6: add alice by principal id, alice accepts, alice is listed and her profile opens.

    @Test fun f04_contactsWithAlice() = flow("04-contacts-alice") {
        ensureShell()
        addContact("alice")
        ui.shot("04-contact-listed")
        openContactRow("alice")
        ui.shot("04-contact-profile")
        "request by principal id reached alice; alice accepted with rf; alice listed; her profile opened"
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
        if (back != "composer" && back != "shell-tabs") {
            fail(
                "returning from the background asked for the account code ('$back'): ${ui.screen()}"
            )
        }
        val inChat = ui.find("composer", 20_000) != null
        if (!inChat) fail("after returning the app did not reopen the chat: ${ui.screen()}")
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
        if (!draft) fail("the draft typed before going to the background was not kept: ${ui.screen()}")
        "after ${BACKGROUND_WAIT / 1000} s in the background the app returned to the open chat with no prompt" +
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
                card()?.press()
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
            (ui.desc(ui.str("ui_attach"), Ui.SHORT) ?: fail("no attach control in the composer")).press()
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
            ui.text(peerName(peer), 30_000)?.press() ?: fail("$peer is not offered in the invite picker: ${ui.screen()}")
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
        ui.find("groups-header-back", 5_000)?.press() ?: ui.device.pressBack()
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
            (ui.desc(ui.str("ui_attach"), Ui.SHORT) ?: fail("no attach control in the group composer")).press()
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
        // The id list is cut at 700 characters, so a row far down the screen never showed in it: ask for the row itself.
        val languages = ui.eventually(10_000) { ui.find("language:system", 500) != null }
        notes += if (languages) {
            "language screen lists the system-language row"
        } else {
            "language screen did not show the system-language row within 10 s"
        }
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
        ui.open("backup", "backup-create")
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
        ui.more("shell-more-tasks")
        Thread.sleep(3_000)
        ui.shot("12-tasks")
        notes += "Tasks: ${ui.texts().take(120)}"
        ensureShell()
        ui.more("shell-more-calls")
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
                row.press()
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
            link.press()
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

    // Flow 4 (receipts): the phone reads alice's message and alice's copy shows it read (CHAT-ACTIONS-01).

    @Test fun f16_readReceiptSeenByPeer() = flow("16-read-receipt") {
        val conversation = aliceConversation()
        ensureShell()
        ui.tap("shell-tabs-0")
        val text = "rr-read-" + token()
        val sent = control.rf("alice", "conversation", "send", "--conversation", conversation, "--text", text)
        if (sent.optInt("exit") != 0) {
            blocked(
                "alice: rf conversation send failed (exit ${sent.optInt("exit")}): ${sent.optString("stderr").takeLast(300)}"
            )
        }
        openConversation(conversation)
        ui.text(text, 60_000) ?: fail("alice's message did not appear on the phone: ${ui.screen()}")
        ui.shot("16-displayed")
        var states = "none"
        val read = ui.eventually(60_000, 3_000) {
            val now = receipts(aliceMessage(conversation, text))
            states = now.ifEmpty { listOf("none") }.joinToString(",")
            "read" in now
        }
        if (!read) fail("alice's copy shows no read receipt within 60 s after the phone displayed it (receipts: $states)")
        "the phone displayed alice's message and alice's copy shows it read (receipts: $states)"
    }

    // Flow 5 (invitation and leave): bob, not yet a contact of the phone, creates a group with the phone in it; the phone
    // gets an invitation, accepts it, then leaves the group; bob's roster no longer lists the phone. Run it before flow
    // 09, which makes bob a contact (a contact's group joins directly, without an invitation).

    @Test fun f17_invitationAcceptAndLeave() = flow("17-invitation-leave") {
        ensureShell()
        val notes = mutableListOf<String>()
        val commitment = RunState["commitment"] ?: blocked("needs flow 04 (the phone's identity commitment)")
        val device = phoneDevice()
        val card = ownCard()
        val bob = control.config().getJSONObject("peers").getJSONObject("bob")
        // A contact's group joins directly and no invitation exists, so the flow only means something while bob is no
        // contact of this account yet (flow 09 makes him one).
        if (jsonRows(control.rf("bob", "contact", "list"), "contacts").any { it.optString("root_commitment") == commitment }) {
            blocked("bob is already a contact of this account, so his group joins directly; run this flow before flow 09 (groups)")
        }
        val carded = control.rf("bob", "contact", "add", "--card", card)
        notes += "bob took the phone's contact card (exit ${carded.optInt("exit")})"
        val group = UUID.randomUUID().toString()
        val created = control.rf("bob", "group", "create", "--group", group, "--creator", bob.getString("device_id"), timeoutSecs = 120)
        if (created.optInt("exit") != 0) {
            blocked(
                "bob: rf group create failed (exit ${created.optInt("exit")}): ${created.optString("stderr").takeLast(300)}"
            )
        }
        val added = control.rf(
            "bob", "group", "member", "add", "--group", group, "--member-device", device, "--member-principal-commitment", commitment,
            timeoutSecs = 120
        )
        if (added.optInt("exit") != 0) {
            blocked(
                "bob: rf group member add failed (exit ${added.optInt("exit")}): ${added.optString("stderr").takeLast(300)}"
            )
        }
        notes += "bob created a group and added the phone"
        ensureShell()
        ui.tap("shell-tabs-1")
        if (!ui.eventually(120_000, 3_000) { ui.find(group, 1_000) != null }) {
            fail("no invitation to bob's group reached the Groups tab within 120 s: ${ui.screen()}; ${notes.joinToString("; ")}")
        }
        val pending = ui.hasText(ui.str("groups_invitations"))
        ui.shot("17-invitation")
        notes += if (pending) "the invitation is listed under Invitations" else "the group is listed without an Invitations section"
        ui.tap(group)
        ui.tap("groups-invitation-accept", Ui.LONG)
        ui.tap("host-confirmation-confirm", Ui.SHORT)
        if (!ui.eventually(120_000, 4_000) { bobMembers(group).contains(device) }) {
            fail("bob's roster never listed the phone after it accepted: ${ui.screen()}; ${notes.joinToString("; ")}")
        }
        notes += "accepted; bob's roster lists the phone"
        ensureShell()
        ui.tap("shell-tabs-1")
        ui.tap(group, 30_000)
        if (!ui.scrollToTag("groups-leave")) fail("the group detail offers no Leave group: ${ui.screen()}")
        ui.tap("groups-leave", Ui.LONG)
        ui.shot("17-leave-confirm")
        ui.tap("host-confirmation-confirm", Ui.SHORT)
        if (!ui.eventually(120_000, 4_000) { !bobMembers(group).contains(device) }) {
            fail("bob's roster still lists the phone 120 s after it left: ${ui.screen()}; ${notes.joinToString("; ")}")
        }
        ui.shot("17-left")
        notes += "left; bob's roster no longer lists the phone"
        notes += acceptRequestFrom("bob")
        notes.joinToString("; ")
    }

    // Flow 15 (usernames): this account's username in settings and adding a contact by @name (OPEN-USERNAME-ABI-01).

    @Test fun f18_usernames() = flow("18-usernames") {
        ensureShell()
        openSettings()
        ui.tap("account-security")
        ui.tap("privacy")
        if (!reach("username")) fail("Privacy has no username row: ${ui.screen()}")
        val row = ui.need("username", Ui.LONG, "username row")
        val status = ui.eventually(30_000) { rowText(ui.find("username", 1_000) ?: row).contains("@") }
        val shown = rowText(ui.find("username") ?: row)
        ui.shot("18-username")
        ensureShell()
        ui.more("shell-more-contacts")
        ui.tap("contacts-add")
        val alice = peerName("alice")
        ui.fill(null, "@" + alice.lowercase().replace(Regex("[^a-z0-9_]"), ""))
        ui.tap("contacts-send")
        confirmIfAsked()
        Thread.sleep(3_000)
        val answer = ui.texts().take(300)
        ui.shot("18-add-by-name")
        val unavailable = ui.str("settings_username_unavailable", "\u0000").substringBefore('\u0000')
        if (shown.contains(unavailable)) {
            blocked("username directory not available to this account: settings shows '$shown'; adding @name answered: $answer")
        }
        if (!status) fail("the username row shows no name or state: '$shown'")
        "settings shows '$shown'; adding @name answered: $answer"
    }

    // Flow 20: the app lock (G05) on its cover. Steps 1 to 6 run in this process with the lock switched on. Steps 7 and 8
    // start with a process that was killed: am force-stop cannot run inside the suite (the suite runs in the app's own
    // process), so the script stops the app before each invocation and these steps run as the first flow of the next
    // invocations. The phase is kept in RunState: none, then APP_LOCK_LOCKED (step 7 next), then APP_LOCK_OFF (step 8
    // next), then none again when the flow has passed. Between the invocations the lock stays on: run f20 to the end
    // before the flows that expect no lock.

    @Test fun f20_appLock() = flow("20-app-lock") {
        val phase = RunState[APP_LOCK_PHASE]
        if (phase != null && !coldProcess) {
            throw FlowStop(
                "NOT RUN",
                "the app lock steps resume in a new app process (phase '$phase'): run f20 alone in its own invocation " +
                    "(scripts/realrun.sh --skip-build --no-install --tests \"f20\"); ${RunState[APP_LOCK_NOTES].orEmpty()}"
            )
        }
        when (phase) {
            null -> leaveAppLockOffOnFailure { appLockOnSteps() }
            APP_LOCK_LOCKED -> leaveAppLockOffOnFailure { appLockAfterRestart() }
            APP_LOCK_OFF -> appLockOffAfterRestart()
            else -> fail("unknown app lock phase '$phase'")
        }
    }

    /**
     * Runs [steps]; when they fail (a stop that is not the planned hand-over to the next invocation), the app lock is turned
     * off with the run's code before the failure is reported, so a failed phase never leaves a lock on for the flows after it.
     * The state of the screen at the failure is kept as evidence first, and the phase starts over.
     */
    private fun leaveAppLockOffOnFailure(steps: () -> Nothing): Nothing {
        try {
            steps()
        } catch (stop: FlowStop) {
            if (stop.status != "NOT RUN") appLockOffAfterFailure("20-app-lock-failed")
            throw stop
        } catch (error: Throwable) {
            appLockOffAfterFailure("20-app-lock-failed")
            throw error
        }
    }

    private fun appLockOffAfterFailure(evidenceName: String) {
        evidence(evidenceName)
        runCatching { turnAppLockOffWithRunCode() }
        RunState[APP_LOCK_PHASE] = ""
        RunState[APP_LOCK_NOTES] = ""
    }

    /**
     * The lock may be on from an earlier phase that failed: when the launched app shows the lock form, opens it with the run's
     * code and turns the lock off. Returns the note for the flow detail, or null when the app opened with no cover.
     */
    private fun clearStaleAppLock(): String? {
        ui.launch()
        if (!ui.eventually(6_000, 500) { ui.find("app-lock-code", 300) != null || ui.find("app-lock-retry", 300) != null }) return null
        lockForm()
        enterAppLockCode()
        turnAppLockOffWithRunCode()
        return "a stale app lock from an earlier phase was opened with the run's code and turned off"
    }

    /**
     * Settings > Privacy > App lock: turns the lock off. When the account-code sheet asks, the run's code is typed into it.
     * Fails unless the lock reads off afterwards (the switch is unchecked and the timeouts are disabled). Returns whether the
     * sheet asked; with [requireOn] a lock that is already off fails.
     */
    private fun turnAppLockOffWithRunCode(requireOn: Boolean = false): Boolean {
        if (ui.find("app-lock-code", 1_000) != null) enterAppLockCode()
        openAppLockPage()
        val switch = ui.need("app-lock-switch", Ui.LONG, "app lock switch")
        if (!switch.isChecked) {
            if (requireOn) fail("the app lock was already off before it was turned off: ${ui.screen()}")
            ensureShell()
            return false
        }
        ui.tap("app-lock-switch", Ui.LONG)
        val asked = ui.eventually(4_000, 300) { ui.windowNode("app-lock-reauth") != null }
        if (asked) enterReauthRunCode()
        if (!ui.eventually(Ui.LONG, 300) { ui.find("app-lock-switch", 300)?.isChecked == false }) {
            fail("the app lock is still on after the turn-off (the code was ${if (asked) "asked" else "not asked"}): ${ui.screen()}")
        }
        if (ui.find("app-lock-timeout:0", 300)?.isEnabled != false) {
            fail("the app lock timeouts stay enabled after the lock was turned off: ${ui.screen()}")
        }
        ensureShell()
        return asked
    }

    /** Settings > Privacy > App lock, from the settings root. */
    private fun openAppLockPage() {
        ensureShell()
        openSettings()
        ui.tap("account-security")
        ui.tap("privacy")
        ui.tap("app-lock")
    }

    /**
     * G05f, with the lock on and the timeout Immediately: a longer timeout asks for the account code. Cancel changes nothing
     * and shows nothing; a wrong code is refused with the wrong-code text and the timeout stays; the right code applies 15
     * minutes. Then Immediately again (a shorter timeout asks for nothing), so the steps after it run as before.
     */
    private fun longerTimeoutWithCode(): String {
        openAppLockPage()
        ui.need("app-lock-timeout:0", Ui.LONG, "app lock timeouts")
        // Cancel: the sheet closes, the timeout stays Immediately and no error text shows.
        ui.tap("app-lock-timeout:900000", Ui.LONG)
        if (!ui.eventually(Ui.LONG, 300) { ui.windowNode("app-lock-reauth") != null }) {
            fail("the longer timeout did not ask for the account code: ${ui.screen()}")
        }
        if (!ui.tapWindowNode("app-lock-cancel")) fail("the account-code sheet has no cancel button: ${ui.screen()}")
        if (!ui.eventually(Ui.LONG, 300) { ui.windowNode("app-lock-reauth") == null }) {
            fail("the account-code sheet stayed open after cancel: ${ui.screen()}")
        }
        ui.need("app-lock-timeout:0", Ui.LONG, "app lock timeouts after cancel")
        if (ui.find("app-lock-timeout:0", 300)?.let { it.isSelected || it.isChecked } != true) {
            fail(
                "cancel changed the timeout: ${ui.screen()}"
            )
        }
        if (ui.find("app-lock-wrong-code", 300) != null || ui.find("app-lock-not-changed", 300) != null) {
            fail("cancel showed an error text: ${ui.screen()}")
        }
        // A wrong code: the sheet closes with the wrong-code text, and the timeout stays Immediately.
        ui.tap("app-lock-timeout:900000", Ui.LONG)
        if (!ui.eventually(Ui.LONG, 300) { ui.windowNode("app-lock-reauth") != null }) {
            fail("the longer timeout did not ask for the account code again: ${ui.screen()}")
        }
        val old = RunCode.load() ?: blocked("no saved run code on this install")
        val wrong = RunCode.next(old)
        old.fill('\u0000')
        try {
            typeReauthCode(wrong)
        } finally {
            wrong.fill('\u0000')
        }
        if (!ui.eventually(Ui.LONG, 300) { ui.find("app-lock-wrong-code", 300) != null }) {
            fail("a wrong code was not refused with the wrong-code text: ${ui.screen()}")
        }
        if (ui.find("app-lock-timeout:0", 300)?.let {
                it.isSelected || it.isChecked
            } != true
        ) {
            fail("a wrong code changed the timeout: ${ui.screen()}")
        }
        // The right code: the timeout becomes 15 minutes.
        ui.tap("app-lock-timeout:900000", Ui.LONG)
        if (!ui.eventually(Ui.LONG, 300) { ui.windowNode("app-lock-reauth") != null }) {
            fail("the longer timeout did not ask for the account code a third time: ${ui.screen()}")
        }
        enterReauthRunCode()
        if (!ui.eventually(Ui.LONG, 300) { ui.find("app-lock-timeout:900000", 300)?.let { it.isSelected || it.isChecked } == true }) {
            fail("the right code did not apply the 15 minute timeout: ${ui.screen()}")
        }
        if (ui.find("app-lock-wrong-code", 300) != null) fail("the right code still shows the wrong-code text: ${ui.screen()}")
        // Back to Immediately: a shorter timeout asks for nothing.
        ui.tap("app-lock-timeout:0", Ui.LONG)
        if (ui.eventually(1_500, 300) { ui.windowNode("app-lock-reauth") != null }) {
            fail("a shorter timeout asked for the account code: ${ui.screen()}")
        }
        if (!ui.eventually(Ui.LONG, 300) { ui.find("app-lock-timeout:0", 300)?.let { it.isSelected || it.isChecked } == true }) {
            fail("the shorter timeout did not apply: ${ui.screen()}")
        }
        ensureShell()
        return "G05f: a longer timeout asked for the code; cancel changed nothing and showed nothing; a wrong code was refused " +
            "with the wrong-code text; the right code applied 15 minutes; Immediately again asked for no code"
    }

    /**
     * Types the run's code on the account-code sheet (a dialog window: its keys are found in any window) and confirms it.
     * The digits are never logged or put in a message; the caller wipes its array.
     */
    private fun typeReauthCode(code: CharArray) {
        for (digit in code) {
            if (!ui.tapWindowNode("app-lock-code-key-$digit", Ui.SHORT)) {
                fail("the account-code sheet has no key to type on: ${ui.screen()}")
            }
        }
        if (!ui.tapWindowNode("app-lock-confirm", Ui.SHORT)) fail("the account-code sheet did not enable confirm: ${ui.screen()}")
    }

    /** Types the run's code on the account-code sheet and confirms it; the loaded code is wiped after use. */
    private fun enterReauthRunCode() {
        val code = RunCode.load() ?: blocked("no saved run code on this install")
        try {
            typeReauthCode(code)
        } finally {
            code.fill('\u0000')
        }
    }

    /** Steps 1 to 6: the lock is off by default; then on with timeout Immediately; the lock form; back; relocks; a menu. */
    private fun appLockOnSteps(): Nothing {
        val conversation = aliceConversation()
        val alice = peerName("alice")
        val name = RunState["name"].orEmpty()
        val notes = mutableListOf<String>()
        clearStaleAppLock()?.let { notes += it }
        ensureShell()
        openConversation(conversation)
        // 1. Lock off by default: background, relaunch, no lock form, the same chat.
        // The header names the peer before HOME, so a failure after the relaunch means the name was lost on resume.
        if (!ui.hasText(alice)) fail("before HOME the chat header does not show $alice (never named): ${ui.screen()}")
        ui.device.pressHome()
        Thread.sleep(BACKGROUND_WAIT)
        ui.launch()
        ui.need("composer", Ui.LONG, "chat after relaunch")
        Thread.sleep(2_000)
        if (ui.find("app-lock-code", 1_000) != null) fail("the app lock is on before it was switched on: ${ui.screen()}")
        if (!ui.hasText(alice)) fail("after relaunch the chat header does not show $alice (lost on resume): ${ui.screen()}")
        notes += "lock off by default: relaunch opened the chat with no lock form"
        // 2. Settings > Privacy > App lock: on, timeout Immediately; the biometric row is disabled.
        ensureShell()
        openSettings()
        ui.tap("account-security")
        ui.tap("privacy")
        ui.tap("app-lock")
        ui.tap("app-lock-switch", Ui.LONG)
        ui.tap("app-lock-timeout:0", Ui.LONG)
        val biometric = ui.need("app-lock-biometric", Ui.LONG, "biometric row")
        if (biometric.isEnabled) fail("the biometric unlock row is enabled; it must be disabled until the core offers it")
        notes += "app lock on with timeout Immediately; biometric row disabled"
        notes += longerTimeoutWithCode()
        ensureShell()
        openConversation(conversation)
        // 3. Background, return: the lock form only, no account or chat text; a wrong code is refused.
        ui.device.pressHome()
        Thread.sleep(BACKGROUND_WAIT)
        ui.launch()
        lockForm()
        ui.shot("20-locked")
        val leaked = listOf(alice, name).filter { it.isNotEmpty() && ui.hasText(it) }
        if (leaked.isNotEmpty()) fail("the lock form shows account text: ${leaked.size} item(s) of the account or peer name")
        val old = RunCode.load() ?: blocked("no saved run code on this install")
        val wrong = RunCode.next(old)
        old.fill('\u0000')
        try {
            ui.keys("app-lock-code", wrong)
        } finally {
            wrong.fill('\u0000')
        }
        ui.tap("app-lock-unlock")
        if (!ui.eventually(Ui.LONG) { ui.find("app-lock-notice", 300)?.text == ui.str("ui_code_wrong") }) {
            fail("a wrong code was not refused with the wrong-code text: ${ui.screen()}")
        }
        notes += "lock form shows no account text; a wrong code is refused"
        // 4. BACK with the keyboard up: the form keeps its content and stays; the run's code then opens the same chat.
        val right = RunCode.load() ?: blocked("no saved run code on this install")
        try {
            ui.keys("app-lock-code", right)
        } finally {
            right.fill('\u0000')
        }
        ui.device.pressBack()
        Thread.sleep(1_000)
        if (ui.find("app-lock-code", 2_000) == null) fail("BACK on the lock form left it: ${ui.screen()}")
        ui.tap("app-lock-unlock")
        appLockOpened()
        sameChat(alice)
        notes += "BACK kept the lock form and its code; the run's code opened the same chat"
        // 5. Lock and unlock twice more, each time the same chat.
        repeat(2) { round ->
            relockAndUnlock(alice)
            notes += "relock ${round + 2}: the same chat"
        }
        // 6. A message menu open when the app is backgrounded: only the lock form, and the menu is gone after the unlock.
        openConversation(conversation)
        val marker = "rr-lock-" + token()
        send(marker)
        openActions(marker)
        ui.device.pressHome()
        Thread.sleep(BACKGROUND_WAIT)
        ui.launch()
        lockForm()
        if (ui.find("message-menu", 1_000) != null) fail("the message menu shows on the lock form: ${ui.screen()}")
        if (ui.hasText(marker)) fail("a message text shows on the lock form: ${ui.screen()}")
        enterAppLockCode()
        if (ui.find("message-menu", 2_000) != null) fail("the message menu is still open after the unlock: ${ui.screen()}")
        sameChat(alice)
        notes += "menu open on background: only the lock form shown; the menu is gone after the unlock"
        RunState[APP_LOCK_NOTES] = notes.joinToString("; ")
        RunState[APP_LOCK_PHASE] = APP_LOCK_LOCKED
        throw FlowStop(
            "NOT RUN",
            "steps 1 to 6 PASS (${notes.joinToString("; ")}); steps 7 and 8 need a new app process: run f20 alone in its own " +
                "invocation (scripts/realrun.sh --skip-build --no-install --tests \"f20\")"
        )
    }

    /** Step 7: the killed app opens on the lock form; the run's code opens the shell with the account's data. */
    private fun appLockAfterRestart(): Nothing {
        val conversation = aliceConversation()
        val alice = peerName("alice")
        ui.launch()
        lockForm()
        ui.shot("20-restart-locked")
        if (ui.hasText(alice)) fail("after a force-stop the lock form shows $alice: ${ui.screen()}")
        enterAppLockCode()
        ensureShell()
        ui.tap("shell-tabs-0")
        ui.need("conversation-$conversation", Ui.LONG, "conversation row after the unlock")
        val notes = RunState[APP_LOCK_NOTES].orEmpty() +
            "; after a force-stop the lock form came first; the run's code opened the shell with the account's conversation"
        // Step 8, part 1: turn the lock off with the run's code when the sheet asks for it; both outcomes are recorded.
        val asked = turnAppLockOffWithRunCode(requireOn = true)
        RunState[APP_LOCK_NOTES] = notes + "; turned off: the code was ${if (asked) "asked" else "not asked"}"
        RunState[APP_LOCK_PHASE] = APP_LOCK_OFF
        throw FlowStop(
            "NOT RUN",
            "step 7 PASS and the lock is off (${RunState[APP_LOCK_NOTES]}); step 8 needs a new app process: run f20 alone " +
                "in its own invocation (scripts/realrun.sh --skip-build --no-install --tests \"f20\")"
        )
    }

    /** Step 8, part 2: after the force-stop the app opens with no cover. */
    private fun appLockOffAfterRestart(): String {
        // A lock that is on here was not turned off by the phase before (it failed): open it, turn it off, note it, go on.
        val stale = clearStaleAppLock()
        ensureShell()
        val notes = RunState[APP_LOCK_NOTES].orEmpty() +
            (stale?.let { "; $it" } ?: "; after a force-stop and relaunch the app opened with no cover")
        RunState[APP_LOCK_PHASE] = ""
        RunState[APP_LOCK_NOTES] = ""
        return notes
    }

    /** The lock form once it is up; the cover may first show its retry action while the lock state is read. */
    private fun lockForm(): UiObject2 {
        val end = System.currentTimeMillis() + Ui.LONG
        while (System.currentTimeMillis() < end) {
            ui.find("app-lock-code", 300)?.let { return it }
            if (ui.find("app-lock-retry", 300) != null) ui.tap("app-lock-retry")
            Thread.sleep(300)
        }
        fail("the app lock form did not show: ${ui.screen()}")
    }

    /** Types the run's code into the lock form and taps unlock. The caller checks the result. */
    private fun enterAppLockCode() {
        val code = RunCode.load() ?: blocked("no saved run code on this install")
        try {
            ui.keys("app-lock-code", code)
        } finally {
            code.fill('\u0000')
        }
        ui.tap("app-lock-unlock")
        appLockOpened()
    }

    /** The lock form is gone: the cover released the app. */
    private fun appLockOpened() {
        if (!ui.eventually(Ui.LONG) { ui.find("app-lock-code", 300) == null }) {
            fail("the app lock did not open with the run's code: ${ui.screen()}")
        }
    }

    /** The chat with [alice] is open again after an unlock (the composer and the chat header). */
    private fun sameChat(alice: String) {
        if (ui.find("composer", Ui.LONG) == null || !ui.hasText(alice)) {
            fail("after the unlock the app did not show the chat with $alice: ${ui.screen()}")
        }
    }

    /** Background for the timeout, return to the lock form, and unlock with the run's code. */
    private fun relockAndUnlock(alice: String) {
        ui.device.pressHome()
        Thread.sleep(BACKGROUND_WAIT)
        ui.launch()
        lockForm()
        enterAppLockCode()
        sameChat(alice)
    }

    /** This phone's device id, from My devices (public). */
    private fun phoneDevice(): String {
        RunState["device"]?.let { return it }
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
        val found = device ?: fail("My devices lists no current device: ${ui.screen()}")
        RunState["device"] = found
        return found
    }

    /** The phone's own contact card text, read through Share on My contact card (the sheet never opens). */
    private fun ownCard(): String {
        ensureShell()
        openSettings()
        ui.tap("account-security")
        ui.tap("my-card")
        ui.need("my-card-text", Ui.LONG, "contact card text")
        if (!ui.scrollTo("my-card-share")) fail("My contact card has no Share action: ${ui.screen()}")
        val shared = ui.captureShare { ui.tap("my-card-share") }
        return Regex("ramflux-card:v[0-9]+:[A-Za-z0-9_=.-]+").find(shared.orEmpty())?.value
            ?: fail("Share on My contact card handed over no card text: ${ui.screen()}")
    }

    /** The device ids in bob's roster of [group] (`member_roster`; excluded devices are not members). */
    private fun bobMembers(group: String): List<String> {
        val roster = control.rf("bob", "group", "members", "--group", group).optJSONObject("json")?.optJSONArray("member_roster")
            ?: return emptyList()
        return (0 until roster.length()).mapNotNull { roster.optJSONObject(it)?.optJSONObject("identity")?.optString("device_id") }
    }

    /** Accepts [peer]'s pending contact request on the phone, when there is one (Contacts, Strangers). */
    private fun acceptRequestFrom(peer: String): String {
        val name = peerName(peer)
        ensureShell()
        ui.more("shell-more-contacts")
        ui.find("tab:STRANGERS", 5_000)?.press() ?: return "no Strangers tab"
        val row = ui.text(name, 20_000) ?: return "no pending request from $peer on the phone"
        row.press()
        ui.tap("contacts-accept", Ui.LONG)
        confirmIfAsked()
        return "the phone accepted $peer's contact request"
    }

    /** Brings the app to its unlocked shell with the tab bar shown. */
    private fun ensureShell() {
        if (!ui.foreground()) ui.launch() else ui.portrait()
        repeat(6) {
            when (
                ui.first(
                    "app-lock-code",
                    "app-lock-retry",
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
                    // The app lock cover first: the shell stays composed under it.
                    "app-lock-code",
                    "app-lock-retry",
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
                "app-lock-code" -> enterAppLockCode()
                "app-lock-retry" -> ui.tap("app-lock-retry")
                "shell-tabs", "composer" -> return
                "startup-unlock-header" -> ui.tap("startup-unlock-continue")
                "shell-locked" -> ui.tap("shell-locked-unlock")
                "startup-details-header" -> ui.tap("startup-view-unlock")
                "startup-info-header" -> ui.tap("startup-unlock-code")
                // After a lock from settings the account chooser offers the locked account by name.
                "onboarding-header" -> lockedAccountRow()?.press() ?: fail("the account chooser offers no account: ${ui.screen()}")
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
                            "app-lock-code",
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
        ui.more("shell-more-settings")
    }

    /**
     * The phone pins [peer]'s home node from the peer's card: the SDK refuses media to a contact known only by a lookup
     * (typed authority_unavailable) until the sender has imported that contact's card (contact.add with the card).
     */
    private fun importCard(peer: String): String {
        val card = control.rf(peer, "contact", "card").optJSONObject("json")?.optString("card").orEmpty()
        if (!card.startsWith("ramflux-card:")) return "$peer's contact card could not be read"
        ensureShell()
        ui.more("shell-more-contacts")
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
        ui.find("host-confirmation-confirm", 3_000)?.press()
        ui.device.waitForIdle()
    }

    /** Adds [peer] by principal id unless listed already; the peer accepts with rf. Returns the peer's row for the phone. */
    private fun addContact(peer: String): JSONObject {
        val name = RunState["name"] ?: blocked("needs flow 01 (display name)")
        ui.more("shell-more-contacts")
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
        // The add page stays open after the request; the list is one back away. Back may first close the keyboard, so
        // back is pressed while the add page is the screen; once the list's add button shows (also while the add page
        // is still leaving) no further back is pressed, which would leave Contacts.
        repeat(3) {
            if (ui.first("contacts-add", "contacts-send", timeout = 3_000) != "contacts-send") return@repeat
            ui.device.pressBack()
            ui.device.waitForIdle()
        }
        if (!ui.eventually(90_000, 3_000) {
                if (ui.hasText(peerName(peer))) {
                    true
                } else {
                    ui.device.findObject(By.pkg(ui.packageName).text(ui.str("contacts_refresh")))?.press()
                    false
                }
            }
        ) {
            fail("$peer did not appear in the phone's contacts after accepting: ${ui.screen()}")
        }
        return row
    }

    private fun openContactRow(peer: String) {
        ui.text(peerName(peer), Ui.SHORT)?.press() ?: fail("no contact row for $peer")
        ui.need("security-section", Ui.SHORT, "contact detail")
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

    // Flow 21: the card gallery (test and realrun builds only; the release build has no such activity). It lists every
    // fixture id and scenario, each drawn by the card renderer from the standalone card runtime. Run conformance runs
    // checks A to C over the fixtures and writes card-conformance.json on the phone; the flow sends that report to the
    // control server (artifacts/card-conformance.json) and keeps screenshots of the gallery. Any FAIL fails the flow.
    @Test fun f21_cardGallery() = flow("21-card-gallery", needsAccount = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent().setClassName(context.packageName, CARD_GALLERY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (ui.find("card-gallery", 20_000) == null) fail("the card gallery did not open: ${ui.screen()}")
        if (ui.texts().contains(ui.str("card_gallery_runtime_missing"))) {
            fail("this build has no card runtime fixture library (build with RAMFLUX_CARD_FIXTURE=1 before the realrun build)")
        }
        ui.shot("21-gallery-open")
        ui.tap("card-gallery-run", 10_000)
        // A tap on Run is sometimes lost (or the activity is recreated under it): when the summary is still empty and the
        // button is enabled after a short wait, tap once more and say so.
        val summaryEmpty = { ui.find("card-gallery-summary", 1_000)?.text.orEmpty().isEmpty() }
        var retapped = false
        Thread.sleep(RUN_RETAP_WAIT)
        if (summaryEmpty() && ui.find("card-gallery-run", 1_000)?.isEnabled == true) {
            retapped = true
            control.log("f21: the summary was still empty ${RUN_RETAP_WAIT / 1000} s after Run with the button enabled: tapped Run again")
            ui.tap("card-gallery-run", 10_000)
        }
        val finished = ui.eventually(300_000, every = 2_000) { ui.find("card-gallery-summary")?.text.orEmpty().contains("checks") }
        if (!finished) fail("conformance did not finish within 300 s: ${ui.screen()}")
        val summary = ui.find("card-gallery-summary")?.text.orEmpty()
        val report = File(context.cacheDir, "card-conformance.json").readBytes()
        control.artifact("card-conformance.json", report)
        File(context.cacheDir, "card-host-slots.json").takeIf { it.isFile }?.let {
            control.artifact("card-host-slots.json", it.readBytes())
        }
        val records = JSONArray(report.decodeToString())
        val failed = (0 until records.length()).map { records.getJSONObject(it) }.filter { !it.getBoolean("pass") }
        ui.shot("21-gallery-conformance")
        val device = ui.device
        val x = device.displayWidth / 2
        repeat(GALLERY_PAGES) { page ->
            device.swipe(x, device.displayHeight * 4 / 5, x, device.displayHeight / 5, 25)
            Thread.sleep(600)
            ui.shot("21-gallery-${page + 1}")
        }
        // Dark: the same gallery with the Dark switch on; the runtime presents the dark theme to every held card. The
        // list is left on the Image cards by the pages above, so it goes back to the top before the switch is looked for.
        if (ui.find("card-gallery", 10_000) == null) fail("the card gallery is not in front after the conformance pages: ${ui.screen()}")
        ui.scrollToTop()
        if (!ui.scrollToTag("card-gallery-dark")) fail("the card gallery has no Dark switch: ${ui.screen()}")
        ui.tap("card-gallery-dark", 10_000)
        Thread.sleep(600)
        repeat(DARK_PAGES) { page ->
            ui.shot("21-gallery-dark-${page + 1}")
            device.swipe(x, device.displayHeight * 4 / 5, x, device.displayHeight / 5, 25)
            Thread.sleep(600)
        }
        device.pressBack()
        if (failed.isNotEmpty()) {
            fail(
                "${failed.size} of ${records.length()} checks FAIL ($summary): " +
                    failed.take(20).joinToString("; ") { "${it.getString("id")} ${it.getString("check")} ${it.getString("detail")}" }
            )
        }
        "${records.length()} checks, all PASS ($summary); report kept as artifacts/card-conformance.json; screenshots 21-gallery-*" +
            if (retapped) "; Run was tapped a second time (first tap had no effect)" else ""
    }

    // Flow 22: an app-locked account whose code attempts are exhausted (G10b, RECOVER-LOCKED-01), on the lock cover. The
    // recovery phrase and the account code exist only in this process (secure fields, nothing in the accessibility tree),
    // so the flow creates its own throwaway account, keeps the phrase and the code in memory only (never logged, saved or
    // shown on a screenshot) and runs the whole path in one process: 10 wrong codes with the escalating waits (30, 60, 120,
    // 240, 480 s) and the exhausted page, also after a relaunch; recovery with the phrase and a wrong code (still locked);
    // recovery with the phrase and the right code (unlocked, the lock still on); exhaust again; remove from this device
    // (the confirmation dialog, cancel, confirm). It runs about 50 minutes: the waits alone are two rounds of 15.5 minutes
    // plus up to 8 minutes each after the two recoveries. Run it alone, in its own invocation, as a background command: it
    // is longer than a ten-minute command. It needs a fresh install with no account (it never creates a second account:
    // a second account on a connection still attended for the first is refused by core, G05e): it creates the one
    // throwaway account itself through the onboarding path of flow 01 and removes it at the end.

    @Test fun f22_exhaustRecoverRemove() = flow("22-exhaust-recover-remove", needsAccount = false) {
        val name = "Phone Throwaway " + token()
        val notes = mutableListOf<String>()
        val secrets = createThrowawayAccount(name) {
            blocked("run f22 alone on a fresh install (scripts/realrun.sh … --tests f22 without --no-install, after uninstall)")
        }
        try {
            appLockOnForThrowaway()
            notes += "throwaway account created; app lock on with timeout Immediately"
            // Step 1: lock, ten wrong codes, the exhausted page, also after a relaunch.
            relaunch()
            lockForm()
            ui.shot("22-lock-form")
            var lastFailureAt = exhaustWithWrongCodes(secrets.code, detailed = true, notes = notes)
            assertExhaustedPage(name)
            ui.shot("22-exhausted")
            relaunch()
            assertExhaustedPage(name)
            ui.shot("22-exhausted-relaunched")
            notes += "exhausted page after the 10th wrong code and after a relaunch: restore and remove offered, no code field"
            // Step 2: the right phrase and a WRONG code: refused, still locked.
            val wrong = RunCode.next(secrets.code)
            try {
                when (recoverOnExhaustedPage(secrets.phrase, wrong)) {
                    RecoveryOutcome.WRONG_CODE -> notes += "recovery with a wrong code: wrong-code notice on the exhausted page"
                    RecoveryOutcome.RETRY_LATER -> {
                        notes += "recovery with a wrong code: the phrase was accepted and the wait of the 10th failure still ran"
                        waitOutPhraseAcceptedWait()
                        ui.keys("app-lock-code", wrong)
                        ui.tap("app-lock-unlock")
                        val refused = ui.eventually(Ui.LONG) { ui.find("app-lock-notice", 300)?.text == ui.str("ui_code_wrong") }
                        if (!refused) {
                            fail(
                                "the granted code try with a wrong code was not refused with the wrong-code text: ${ui.screen()}"
                            )
                        }
                        notes += "the one granted try with a wrong code was refused"
                    }
                    RecoveryOutcome.UNLOCKED -> fail("recovery with a wrong account code opened the account")
                }
            } finally {
                wrong.fill('\u0000')
            }
            val notice = ui.find("app-lock-notice", Ui.SHORT)?.text.orEmpty()
            if (notice != ui.str("app_lock_code_wrong") && notice != ui.str("ui_code_wrong")) {
                fail("the wrong code was not refused with a wrong-code notice: ${ui.screen()}")
            }
            assertExhaustedPage(name)
            ui.shot("22-recover-wrong-code")
            lastFailureAt = System.currentTimeMillis()
            // Step 3: the right phrase and the right code, after the wait of the last failure: unlocked, the lock still on.
            waitUntilAfter(lastFailureAt, EXHAUST_WAITS_SECS.last() + 10)
            when (recoverOnExhaustedPage(secrets.phrase, secrets.code)) {
                RecoveryOutcome.UNLOCKED -> notes += "recovery with the phrase and the right code unlocked the account"
                RecoveryOutcome.RETRY_LATER -> {
                    notes += "recovery with the right code: the phrase was accepted, the wait still ran (waited it out)"
                    waitOutPhraseAcceptedWait()
                    ui.keys("app-lock-code", secrets.code)
                    ui.tap("app-lock-unlock")
                }
                RecoveryOutcome.WRONG_CODE -> fail("recovery with the right phrase and the right code refused the code: ${ui.screen()}")
            }
            if (!ui.eventually(Ui.LONG, 500) { ui.find("app-lock-code", 300) == null && ui.find("app-lock-exhausted", 300) == null }) {
                fail("the cover is still up after a recovery with the right phrase and code: ${ui.screen()}")
            }
            openAppLockPageWithoutUnlock()
            val lockSwitch = ui.need("app-lock-switch", Ui.LONG, "app lock switch")
            if (!lockSwitch.isChecked) fail("the app lock setting is off after the recovery: ${ui.screen()}")
            ui.shot("22-recovered-lock-still-on")
            notes += "the app lock setting is still on after the recovery"
            // Step 4: exhaust again, then remove from this device.
            relaunch()
            lockForm()
            exhaustWithWrongCodes(secrets.code, detailed = false, notes = notes)
            assertExhaustedPage(name)
            ui.shot("22-exhausted-again")
            notes += removeFromExhaustedPage(name)
            // Step 5: the onboarding "forget-account" button appears only after a non-code unlock failure of a locked
            // account (OnboardingViewModel.submitUnlock), which this flow cannot cause; recorded, not exercised.
            notes += "forget-account (onboarding) not reachable here: it shows only after an unlock refused for a reason that " +
                "is not a code refusal; the remove-dialog was exercised from the lock cover"
        } finally {
            secretScreen = false
            secrets.phrase.fill('\u0000')
            secrets.code.fill('\u0000')
        }
        notes.joinToString("; ")
    }

    private class Throwaway(val phrase: CharArray, val code: CharArray)

    private enum class RecoveryOutcome { UNLOCKED, RETRY_LATER, WRONG_CODE }

    /**
     * Creates the run's only account on a fresh install through the onboarding path of flow 01 (node confirm, create) and
     * keeps its recovery phrase (24 words joined by spaces) and its code in memory. The code is not saved as the run's
     * code. [existing] is called when the app already holds an account.
     */
    private fun createThrowawayAccount(name: String, existing: () -> Nothing): Throwaway {
        onboardToDisplayName(existing)
        ui.type("display-name", name)
        ui.tap("begin-create")
        ui.need("phrase-saved", Ui.LONG, "recovery phrase")
        ui.need("phrase-word-23")
        // No screenshot from here until the account exists: the words and later the code are on screen.
        secretScreen = true
        val words = ArrayList<CharArray>()
        var phrase: CharArray? = null
        var code: CharArray? = null
        try {
            for (index in 0 until PHRASE_WORDS) {
                if (!RealRunProbe.borrow(index) { words += it.copyOf() }) fail("recovery word ${index + 1} was not drawn (realrun probe)")
            }
            phrase = joinWords(words)
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
            code = RunCode.next(CharArray(CODE_DIGITS) { '0' })
            ui.keys("account-code", code)
            ui.tap("confirm-account")
            ui.need("continue-created", Ui.LONG, "account created")
            secretScreen = false
            RealRunProbe.erase()
            ui.tap("continue-created")
            leavePermissionFlow()
            ui.need("shell-tabs", Ui.LONG, "shell after creation")
            return Throwaway(phrase, code)
        } catch (error: Throwable) {
            phrase?.fill('\u0000')
            code?.fill('\u0000')
            throw error
        } finally {
            words.forEach { it.fill('\u0000') }
            RealRunProbe.erase()
        }
    }

    private fun joinWords(words: List<CharArray>): CharArray {
        val out = CharArray(words.sumOf { it.size } + words.size - 1)
        var at = 0
        words.forEachIndexed { index, word ->
            if (index > 0) out[at++] = ' '
            word.copyInto(out, at)
            at += word.size
        }
        return out
    }

    /** Settings > Privacy > App lock of the active throwaway account, with no unlock helper (the run's code is another account's). */
    private fun openAppLockPageWithoutUnlock() {
        repeat(6) {
            if (ui.find("shell-tabs", 1_500) != null) return@repeat
            ui.device.pressBack()
        }
        openSettings()
        ui.tap("account-security")
        ui.tap("privacy")
        ui.tap("app-lock")
    }

    private fun appLockOnForThrowaway() {
        openAppLockPageWithoutUnlock()
        ui.tap("app-lock-switch", Ui.LONG)
        ui.tap("app-lock-timeout:0", Ui.LONG)
        if (ui.find("app-lock-switch", Ui.SHORT)?.isChecked != true) fail("the app lock is not on: ${ui.screen()}")
    }

    private fun relaunch() {
        ui.device.pressHome()
        Thread.sleep(BACKGROUND_WAIT)
        ui.launch()
    }

    /** The countdown the cover shows, in seconds, or null when none is shown. */
    private fun shownWaitSecs(): Long? {
        val text = ui.find("app-lock-wait", 1_000)?.text ?: return null
        val match = Regex("(\\d{2}):(\\d{2})").find(text) ?: return null
        return match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()
    }

    /** True once the code form takes input again: no countdown and the unlock button enabled. */
    private fun codeFormReady(): Boolean = ui.find("app-lock-wait", 300) == null && ui.find("app-lock-unlock", 300)?.isEnabled == true

    /**
     * Types wrong codes until core answers with the exhausted page; returns when the 10th was refused. After the 5th to the
     * 9th the countdown (30, 60, 120, 240, 480 s) must show with the unlock button disabled, and the wait must last as long
     * as core said. Nothing is sent while a wait runs. With [detailed] there are screenshots and a relaunch inside the first wait.
     */
    private fun exhaustWithWrongCodes(code: CharArray, detailed: Boolean, notes: MutableList<String>): Long {
        val waits = mutableListOf<String>()
        var failedAt = 0L
        for (attempt in 1..EXHAUST_ATTEMPTS) {
            if (!ui.eventually(
                    Ui.LONG,
                    500
                ) { codeFormReady() }
            ) {
                fail("the code form did not take input for attempt $attempt: ${ui.screen()}")
            }
            val wrong = RunCode.next(code)
            try {
                ui.keys("app-lock-code", wrong)
            } finally {
                wrong.fill('\u0000')
            }
            ui.tap("app-lock-unlock")
            failedAt = System.currentTimeMillis()
            Thread.sleep(1_500)
            when {
                attempt < WAIT_FROM_ATTEMPT -> {
                    val refused = ui.eventually(Ui.LONG, 300) {
                        ui.find("app-lock-notice", 300)?.text == ui.str("ui_code_wrong") &&
                            ui.find("app-lock-unlock", 300)?.isEnabled == true
                    }
                    if (!refused) fail("attempt $attempt was not refused with the wrong-code text and an enabled form: ${ui.screen()}")
                }
                attempt < EXHAUST_ATTEMPTS -> {
                    val expected = EXHAUST_WAITS_SECS[attempt - WAIT_FROM_ATTEMPT]
                    if (!ui.eventually(
                            Ui.LONG,
                            300
                        ) { shownWaitSecs() != null }
                    ) {
                        fail("no countdown after failure $attempt: ${ui.screen()}")
                    }
                    val shown = shownWaitSecs() ?: fail("the countdown vanished after failure $attempt")
                    if (shown > expected || shown < expected - WAIT_READ_SLACK_SECS) {
                        fail("after failure $attempt the countdown shows $shown s, expected $expected s")
                    }
                    if (ui.find("app-lock-unlock", 1_000)?.isEnabled != false) {
                        fail(
                            "the unlock button is enabled during the wait: ${ui.screen()}"
                        )
                    }
                    val fieldEnabled = ui.find("app-lock-code", 1_000)?.isEnabled
                    if (detailed && (attempt == WAIT_FROM_ATTEMPT || attempt == EXHAUST_ATTEMPTS - 1)) ui.shot("22-wait-$attempt")
                    if (detailed && attempt == WAIT_FROM_ATTEMPT) {
                        // The wait survives a relaunch: the cover reads core's state again.
                        relaunch()
                        lockForm()
                        val again = shownWaitSecs() ?: fail("no countdown after the relaunch inside the first wait: ${ui.screen()}")
                        if (again > expected || ui.find("app-lock-unlock", 1_000)?.isEnabled != false) {
                            fail("after the relaunch the wait is not kept (shows $again s): ${ui.screen()}")
                        }
                        ui.shot("22-wait-relaunched")
                    }
                    if (!ui.eventually((expected + WAIT_END_SLACK_SECS) * 1_000, 1_000) { codeFormReady() }) {
                        fail("the wait of $expected s after failure $attempt did not end: ${ui.screen()}")
                    }
                    val lasted = (System.currentTimeMillis() - failedAt) / 1_000
                    if (lasted < expected - WAIT_EARLY_SLACK_SECS) {
                        fail(
                            "the wait after failure $attempt ended after $lasted s, expected $expected s"
                        )
                    }
                    waits += "$attempt: ${expected}s (shown $shown, lasted $lasted, field enabled during wait: $fieldEnabled)"
                }
                else -> if (!ui.eventually(Ui.LONG, 300) { ui.find("app-lock-exhausted", 300) != null }) {
                    fail("the 10th wrong code did not lead to the exhausted page: ${ui.screen()}")
                }
            }
        }
        notes += "10 wrong codes: refused with the wrong-code text up to the 4th; waits after failures 5 to 9 ${waits.joinToString(", ")}"
        return failedAt
    }

    /** The exhausted page: its marker, restore and remove offered, no code field, no account name. */
    private fun assertExhaustedPage(name: String) {
        ui.need("app-lock-exhausted", Ui.LONG, "exhausted page")
        ui.need("app-lock-restore")
        ui.need("app-lock-remove")
        if (ui.find("app-lock-code", 1_000) != null) fail("the exhausted page still has the code field: ${ui.screen()}")
        if (ui.find("app-lock-unlock", 500) != null) fail("the exhausted page still has the unlock button: ${ui.screen()}")
        if (ui.hasText(name)) fail("the exhausted page shows the account name")
    }

    /** Recovery from the exhausted page with [phrase] and [code]; the screens with the secret fields are never captured. */
    private fun recoverOnExhaustedPage(phrase: CharArray, code: CharArray): RecoveryOutcome {
        ui.tap("app-lock-restore", Ui.LONG)
        ui.need("recovery-phrase-entry", Ui.LONG, "recovery form")
        ui.need("recovery-anchor")
        secretScreen = true
        try {
            ui.keys("recovery-phrase-entry", phrase)
            ui.keys("recovery-code", code)
            ui.tap("confirm-recovery", Ui.LONG)
            if (!ui.eventually(Ui.LONG, 300) { ui.find("recovery-phrase-entry", 200) == null }) {
                fail("the recovery stayed on its form: notice '${ui.find("app-lock-notice", 500)?.text.orEmpty()}'")
            }
        } finally {
            secretScreen = false
        }
        var outcome: RecoveryOutcome? = null
        val decided = ui.eventually(Ui.LONG, 500) {
            outcome = when {
                ui.find("app-lock-recovery", 200) != null -> null
                ui.find("app-lock-wait", 200) != null -> RecoveryOutcome.RETRY_LATER
                ui.find("app-lock-exhausted", 200) != null -> RecoveryOutcome.WRONG_CODE
                ui.find("app-lock-code", 200) == null && ui.find("app-lock-retry", 200) == null -> RecoveryOutcome.UNLOCKED
                else -> null
            }
            outcome != null
        }
        if (!decided) fail("the recovery reached no result: ${ui.screen()}")
        if (outcome == RecoveryOutcome.UNLOCKED) {
            Thread.sleep(1_000)
            if (ui.find("app-lock-code", 500) != null || ui.find("app-lock-exhausted", 500) != null) {
                fail(
                    "the cover came back: ${ui.screen()}"
                )
            }
        }
        return outcome ?: fail("the recovery reached no result")
    }

    /** After a recovery that was answered with the wait still running: the phrase-accepted line, then the wait until the field returns. */
    private fun waitOutPhraseAcceptedWait() {
        val accepted = ui.str("app_lock_phrase_accepted", "\u0000").substringBefore('\u0000')
        if (ui.find("app-lock-wait", Ui.SHORT)?.text?.contains(accepted) != true) {
            fail(
                "the wait does not read 'phrase accepted': ${ui.screen()}"
            )
        }
        if (ui.find("app-lock-unlock", 1_000)?.isEnabled != false) fail("the unlock button is enabled during the wait: ${ui.screen()}")
        ui.shot("22-phrase-accepted-wait")
        if (!ui.eventually((EXHAUST_WAITS_SECS.last() + WAIT_END_SLACK_SECS) * 1_000, 1_000) { codeFormReady() }) {
            fail("the wait after the accepted phrase did not end: ${ui.screen()}")
        }
    }

    /** Sleeps (with a look at the screen every 30 s, which keeps the phone awake) until [secs] after [since]. */
    private fun waitUntilAfter(since: Long, secs: Long) {
        val end = since + secs * 1_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(minOf(30_000L, maxOf(end - System.currentTimeMillis(), 0L)))
            ui.find("app-lock-exhausted", 500)
        }
    }

    /** The remove dialog from the exhausted page: no name, confirm disabled until "I understand", cancel keeps, confirm removes. */
    private fun removeFromExhaustedPage(name: String): String {
        ui.tap("app-lock-remove", Ui.LONG)
        ui.need("remove-dialog", Ui.LONG, "remove dialog")
        if (ui.find("remove-dialog-name", 500) != null || ui.hasText(name)) fail("the remove dialog names the account")
        if (ui.find("remove-confirm", Ui.SHORT)?.isEnabled != false) fail("remove-confirm is enabled before 'I understand' is ticked")
        ui.shot("22-remove-dialog")
        ui.tap("remove-cancel")
        if (!ui.eventually(Ui.SHORT, 300) { ui.find("remove-dialog", 300) == null }) fail("Cancel did not close the remove dialog")
        assertExhaustedPage(name)
        ui.shot("22-remove-cancelled")
        ui.tap("app-lock-remove", Ui.LONG)
        ui.need("remove-dialog", Ui.LONG, "remove dialog again")
        if (ui.find("remove-confirm", Ui.SHORT)?.isEnabled != false) fail("remove-confirm is enabled when the dialog is opened again")
        ui.tap("remove-understand")
        if (!ui.eventually(Ui.SHORT, 300) { ui.find("remove-confirm", 300)?.isEnabled == true }) {
            fail("remove-confirm did not enable after 'I understand': ${ui.screen()}")
        }
        ui.tap("remove-confirm")
        val removedText = ui.str("ui_account_removed")
        val toast = ui.text(removedText, Ui.SHORT) != null
        val after = ui.first(
            "onboarding-header", "startup-gateway", "startup-welcome", "startup-unlock-header", "unlock-account-name", "shell-locked",
            timeout = Ui.LONG
        ) ?: fail("no start state after the removal: ${ui.screen()}")
        if (ui.find("app-lock-code", 1_000) != null || ui.find("app-lock-exhausted", 500) != null) {
            fail(
                "the cover is still up after the removal"
            )
        }
        if (ui.eventually(5_000) { ui.hasText(name) }) fail("the removed throwaway account is still offered: ${ui.screen()}")
        ui.shot("22-removed")
        if (!toast) fail("the removed notice '$removedText' was not shown (the app went to '$after')")
        return "remove dialog named no account, confirm needed 'I understand', Cancel kept the account, confirm removed it: toast shown, " +
            "the app went to '$after' and no longer offers the account"
    }

    companion object {
        private const val BACKGROUND_WAIT = 8_000L

        /** The card gallery activity (realrun source set; not in any other build type). */
        private const val CARD_GALLERY = "org.ramflux.android.cardgallery.CardGalleryActivity"
        private const val GALLERY_PAGES = 14
        private const val DARK_PAGES = 5
        private const val RUN_RETAP_WAIT = 4_000L

        /** Flow 22: the code form's escalating waits after failures 5 to 9 (core's throttle), the attempts and the reading slack. */
        private val EXHAUST_WAITS_SECS = longArrayOf(30, 60, 120, 240, 480)
        private const val EXHAUST_ATTEMPTS = 10
        private const val WAIT_FROM_ATTEMPT = 5
        private const val WAIT_READ_SLACK_SECS = 25L
        private const val WAIT_END_SLACK_SECS = 90L
        private const val WAIT_EARLY_SLACK_SECS = 5L
        private const val PHRASE_WORDS = 24
        private const val CODE_DIGITS = 6

        /** Flow 20's phase in [RunState]: steps 7 and 8 wait for a new app process; the notes of the steps passed so far. */
        private const val APP_LOCK_PHASE = "app-lock-phase"
        private const val APP_LOCK_NOTES = "app-lock-notes"
        private const val APP_LOCK_LOCKED = "locked"
        private const val APP_LOCK_OFF = "off"

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

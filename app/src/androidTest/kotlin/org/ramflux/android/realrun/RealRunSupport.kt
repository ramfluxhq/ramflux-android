// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.realrun

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.CharBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Properties
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Arguments of the opt-in real run (scripts/realrun.sh passes them with `am instrument -e`). Only public values:
 * the node's origin, operator key and fingerprint, the peers' public identifiers and the control address.
 */
class RealRunArgs(private val bundle: Bundle) {
    val enabled get() = bundle.getString("rf_realrun") == "1"
    val control get() = bundle.getString("rf_control").orEmpty()
    val nodeOrigin get() = bundle.getString("rf_node_origin").orEmpty()
    val operatorKey get() = bundle.getString("rf_operator_key").orEmpty()
    val fingerprint get() = bundle.getString("rf_fingerprint").orEmpty()
    val nodeId get() = bundle.getString("rf_node_id").orEmpty()
    fun peer(name: String, field: String) = bundle.getString("rf_${name}_$field").orEmpty()

    companion object {
        fun current() = RealRunArgs(InstrumentationRegistry.getArguments())
    }
}

/** Client of scripts/realrun/peer_control.py on the Mac, reached through `adb reverse`. Never sent a secret. */
class PeerControl(private val base: String) {
    fun health(): Boolean = runCatching { get("/health").optBoolean("ok") }.getOrDefault(false)

    fun rf(peer: String, vararg args: String, timeoutSecs: Int = 60): JSONObject =
        post("/rf", JSONObject().put("peer", peer).put("args", JSONArray(args.toList())).put("timeout", timeoutSecs), timeoutSecs + 15)

    fun result(flow: String, status: String, detail: String) {
        runCatching { post("/result", JSONObject().put("flow", flow).put("status", status).put("detail", detail.take(1800))) }
    }

    fun log(line: String) {
        runCatching { post("/log", JSONObject().put("line", line.take(2000))) }
    }

    fun config(): JSONObject = get("/config")

    fun dmConversation(first: String, second: String): String =
        post("/dm-conversation", JSONObject().put("first", first).put("second", second)).optString("conversation")

    fun makeFile(name: String, kind: String, size: Int): JSONObject =
        post("/make-file", JSONObject().put("name", name).put("kind", kind).put("size", size))

    fun freshPath(name: String): String = post("/fresh-path", JSONObject().put("name", name)).optString("path")

    fun fileInfo(path: String): JSONObject = post("/file-info", JSONObject().put("path", path))

    fun screenshot(name: String, png: ByteArray) {
        runCatching { raw("/screenshot/$name", png, "image/png", 30) }
    }

    /** A UI hierarchy dump (XML) of the moment a flow stopped; never taken while recovery words are shown. */
    fun dump(name: String, xml: ByteArray) {
        runCatching { raw("/dump/$name", xml, "application/xml", 30) }
    }

    private fun get(path: String): JSONObject {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        return connection.inputStream.use { JSONObject(it.readBytes().decodeToString()) }
    }

    private fun post(path: String, body: JSONObject, timeoutSecs: Int = 30): JSONObject =
        JSONObject(raw(path, body.toString().encodeToByteArray(), "application/json", timeoutSecs).decodeToString())

    private fun raw(path: String, body: ByteArray, type: String, timeoutSecs: Int): ByteArray {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = timeoutSecs * 1_000
        connection.setRequestProperty("Content-Type", type)
        connection.outputStream.use { it.write(body) }
        return connection.inputStream.use { it.readBytes() }
    }
}

/**
 * A tap (or a held press) at the centre of this node, injected synchronously by the instrumentation's UiAutomation into
 * the app's window. UiAutomator's own click is injected without waiting for the result; after a cold launch the test
 * phone refused injected input for about a minute ("Input event injection ... failed" in the input dispatcher log while
 * the app's main thread kept answering), and those clicks were lost without a trace. A touch is therefore repeated
 * until the dispatcher reports both its down and its up delivered (bounded).
 */
fun UiObject2.press(holdMillis: Long = 0) {
    val point = visibleCenter
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    fun inject(action: Int, downTime: Long): Boolean {
        val event = android.view.MotionEvent.obtain(
            downTime,
            android.os.SystemClock.uptimeMillis(),
            action,
            point.x.toFloat(),
            point.y.toFloat(),
            0
        )
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        return try {
            automation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }
    // A whole touch (down and up) must be delivered: while the phone refuses injected input the attempt is repeated.
    val deadline = android.os.SystemClock.uptimeMillis() + PRESS_DELIVERY_MILLIS
    while (true) {
        val downTime = android.os.SystemClock.uptimeMillis()
        if (inject(android.view.MotionEvent.ACTION_DOWN, downTime)) {
            if (holdMillis > 0) Thread.sleep(holdMillis)
            if (inject(android.view.MotionEvent.ACTION_UP, downTime)) return
            inject(android.view.MotionEvent.ACTION_CANCEL, downTime)
        }
        if (android.os.SystemClock.uptimeMillis() > deadline) {
            throw FlowStop("FAIL", "the phone refused injected touches for ${PRESS_DELIVERY_MILLIS / 1000} s")
        }
        Thread.sleep(PRESS_RETRY_MILLIS)
    }
}

private const val PRESS_DELIVERY_MILLIS = 90_000L
private const val PRESS_RETRY_MILLIS = 300L

/** A flow could not continue; [status] is what the table records (FAIL or BLOCKED). */
class FlowStop(val status: String, message: String) : Exception(message)

/** UiAutomator over the app's Compose test tags (exposed as view ids in debuggable builds). */
class Ui(private val control: PeerControl) {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val packageName: String = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val targetContext: android.content.Context = instrumentation.targetContext

    fun launch() {
        val context = instrumentation.context
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw FlowStop("FAIL", "no launch intent for $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), LONG)
        portrait()
    }

    /**
     * The shared phone may lie sideways with auto-rotate on; the suite never changes its settings. Instead it asks
     * for portrait on the app's own activity, which ends with the activity.
     */
    fun portrait() {
        instrumentation.runOnMainSync {
            resumed()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // A sideways activity is recreated for portrait: until the resumed activity is a portrait one that stays the
        // same between two reads, a tap can land in the window being torn down and be lost.
        var last: Activity? = null
        eventually(LONG, POLL) {
            var now: Activity? = null
            instrumentation.runOnMainSync { now = resumed() }
            val current = now
            val stable = current != null && current === last &&
                current.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            last = current
            stable
        }
        device.waitForIdle()
    }

    fun resumed(): Activity? = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()

    fun foreground(): Boolean = device.currentPackageName == packageName

    /** A string of the app in the phone's language, by resource name (the suite runs in any locale). */
    fun str(name: String, vararg args: Any): String {
        val id = targetContext.resources.getIdentifier(name, "string", packageName)
        if (id == 0) throw FlowStop("FAIL", "the app has no string resource $name")
        return targetContext.getString(id, *args)
    }

    fun text(fragment: String, timeout: Long = SHORT): UiObject2? =
        device.wait(Until.findObject(By.pkg(packageName).textContains(fragment)), timeout)

    fun hasText(fragment: String): Boolean = device.hasObject(By.pkg(packageName).textContains(fragment))

    fun desc(fragment: String, timeout: Long = SHORT): UiObject2? =
        device.wait(Until.findObject(By.pkg(packageName).descContains(fragment)), timeout)

    fun tapText(text: String, timeout: Long = SHORT, step: String = text) {
        repeat(TEXT_TAPS) {
            val byText = By.pkg(packageName).text(text)
            val selector = if (device.wait(Until.findObject(byText), timeout) != null) {
                byText
            } else if (device.wait(Until.findObject(By.pkg(packageName).desc(text)), 2_000) != null) {
                By.pkg(packageName).desc(text)
            } else {
                throw FlowStop("FAIL", "step '$step': no '$text' on screen; ${screen()}")
            }
            try {
                val target = settled(selector) ?: run {
                    Thread.sleep(POLL)
                    return@repeat
                }
                target.press()
                device.waitForIdle()
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                Thread.sleep(POLL)
            }
        }
        throw FlowStop("FAIL", "step '$step': '$text' kept changing under the tap")
    }

    /** The visible test tags (resource ids) of the app, for failure details. */
    // Reading a node that the app replaced meanwhile throws: such a node is skipped (also in texts()).
    fun ids(): String = device.findObjects(By.pkg(packageName).res(Pattern.compile(".+"))).mapNotNull { runCatching { it.resourceName }.getOrNull() }
        .filterNot { it.startsWith("android:") }.distinct().joinToString(",").take(700)

    fun screen(): String = "screen: ${texts().take(500)}; tags: ${ids()}"

    /** Polls [condition] until it holds or [timeout] passes. */
    fun eventually(timeout: Long, every: Long = 1_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeout
        while (true) {
            if (runCatching(condition).getOrDefault(false)) return true
            if (System.currentTimeMillis() >= end) return false
            Thread.sleep(every)
        }
    }

    /** Scrolls the first scrollable view of the app until [tag] is on screen; returns whether it is. */
    fun scrollTo(tag: String): Boolean {
        if (find(tag, 1_000) != null) return true
        val list = device.findObject(By.pkg(packageName).scrollable(true)) ?: return false
        repeat(12) {
            runCatching { list.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f) }
            if (find(tag, 500) != null) return true
        }
        return false
    }

    /** [scrollTo] that survives a list recomposing under the scroll (a stale node). */
    fun scrollToTag(tag: String): Boolean {
        repeat(3) {
            try {
                return scrollTo(tag)
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                device.waitForIdle()
            }
        }
        return false
    }

    /** The first edit field under [tag] (or anywhere on screen without a tag). */
    fun field(tag: String? = null, timeout: Long = SHORT): UiObject2 {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            val scope = tag?.let { device.findObject(By.res(it)) }
            val found = if (tag == null) {
                device.findObject(By.pkg(packageName).clazz(EDIT_TEXT))
            } else {
                scope?.let { if (it.className == EDIT_TEXT) it else it.findObject(By.clazz(EDIT_TEXT)) }
            }
            if (found != null) return found
            Thread.sleep(POLL)
        }
        throw FlowStop("FAIL", "no text field${tag?.let { " under '$it'" }.orEmpty()}; ${screen()}")
    }

    /** Public text into the first text field under [tag] (accessibility set-text), looked up again if it recomposes. */
    fun fill(tag: String?, value: String) {
        // A field on a screen that is still coming in is replaced several times; keep trying for a bounded time.
        val end = System.currentTimeMillis() + SHORT
        while (System.currentTimeMillis() < end) {
            try {
                val target = field(tag)
                target.press()
                device.waitForIdle()
                field(tag).text = value
                device.waitForIdle()
                if (field(tag).text == value) return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                Thread.sleep(POLL)
            }
        }
        throw FlowStop("FAIL", "could not enter text into the field${tag?.let { " under '$it'" }.orEmpty()}; ${screen()}")
    }

    /** A held press of about 1.5 s at the centre of [target] (longer than any long-press timeout setting). */
    fun longPress(target: UiObject2) {
        target.press(holdMillis = LONG_PRESS_MILLIS)
    }

    /**
     * Answers the next activity start that matches [action] with [uri] instead of opening the system picker: the
     * picker UI belongs to the platform, and the shared phone's own photos and files must never be offered. What the
     * app does with the returned document (copy, prepare, upload, send) is the real path.
     */
    fun stubPicker(actions: Set<String>, uri: Uri): Instrumentation.ActivityMonitor {
        val result = Instrumentation.ActivityResult(
            Activity.RESULT_OK,
            Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? = if (intent.action in actions) result else null
        }
        instrumentation.addMonitor(monitor)
        return monitor
    }

    /**
     * Runs [action] while the system share sheet is intercepted, and returns the shared text (the sheet never opens,
     * so nothing reaches another app on the shared phone).
     */
    fun captureShare(action: () -> Unit): String? {
        var shared: String? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                val inner = if (intent.action == Intent.ACTION_CHOOSER) {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                } else {
                    intent
                }
                if (inner?.action != Intent.ACTION_SEND) return null
                shared = inner.getStringExtra(Intent.EXTRA_TEXT)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            action()
            eventually(10_000, POLL) { shared != null }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        return shared
    }

    fun removeMonitor(monitor: Instrumentation.ActivityMonitor) = instrumentation.removeMonitor(monitor)

    fun find(tag: String, timeout: Long = SHORT): UiObject2? = device.wait(Until.findObject(By.res(tag)), timeout)

    fun need(tag: String, timeout: Long = SHORT, step: String = tag): UiObject2 = find(tag, timeout) ?: throw FlowStop(
        "FAIL",
        "step '$step': '$tag' did not appear within ${timeout / 1000} s; screen: ${texts().take(400)}"
    )

    /**
     * Taps [tag], which opens a screen showing [next], and taps it again (bounded) while [tag] is still shown without
     * [next]. A delivered tap is sometimes lost on the test phone: right after a cold launch the input manager accepts
     * an injected touch without delivering it to the window, and a tap on a screen that is still fading in reaches the
     * window but does nothing. Repeating is safe because [tag] is only tapped again while its screen is still shown.
     */
    fun open(tag: String, next: String, timeout: Long = SHORT) {
        tap(tag, timeout)
        repeat(OPEN_ATTEMPTS - 1) {
            if (find(next, OPEN_WAIT) != null || find(tag, 0) == null) return
            tap(tag, timeout)
        }
    }

    /** Opens the More tab and taps its [entry] (see [open]). */
    fun more(entry: String) {
        open("shell-tabs-3", entry)
        tap(entry, SHORT)
    }

    /** Taps [tag] once it is shown and enabled (a row disabled while its screen loads ignores taps). */
    fun tap(tag: String, timeout: Long = SHORT) {
        need(tag, timeout)
        val end = System.currentTimeMillis() + timeout
        while (true) {
            // Compose may replace the node while it recomposes: look it up again on every pass. The click goes to the
            // node's centre, so it waits until the node is laid out where it stays (a screen sliding in moves it).
            val clicked = try {
                settled(By.res(tag))?.let {
                    it.press()
                    true
                } ?: false
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                false
            }
            if (clicked) break
            if (System.currentTimeMillis() >= end) {
                throw FlowStop(
                    "FAIL",
                    "step '$tag': not shown enabled within ${timeout / 1000} s; ${screen()}"
                )
            }
            Thread.sleep(POLL)
        }
        device.waitForIdle()
    }

    /**
     * The enabled node matching [selector] once its bounds are the same in two reads [SETTLE] ms apart, or null while it
     * is missing, disabled or still moving (a page transition or a list settling).
     */
    fun settled(selector: androidx.test.uiautomator.BySelector): UiObject2? {
        val first = device.findObject(selector)?.takeIf { it.isEnabled } ?: return null
        val bounds = first.visibleBounds
        Thread.sleep(SETTLE)
        val again = device.findObject(selector)?.takeIf { it.isEnabled } ?: return null
        return again.takeIf { it.visibleBounds == bounds }
    }

    /** The first of [tags] on screen within [timeout], or null. */
    fun first(vararg tags: String, timeout: Long = SHORT): String? {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            tags.firstOrNull { device.hasObject(By.res(it)) }?.let { return it }
            Thread.sleep(POLL)
        }
        return null
    }

    /** Types public text into a field through its editable node (accessibility set-text). */
    fun type(tag: String, text: String) = enter(tag, text)

    /**
     * Codes and recovery words go straight to the app's erasable secret field (the instrumentation shares the app
     * process): its input connection takes the characters as a [CharBuffer] over [chars], so no String copy, no
     * shell command line and no key injection that could drop characters. Never logged.
     */
    fun keys(tag: String, chars: CharArray) {
        need(tag)
        var taken = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
            val view = activity?.window?.decorView?.findViewWithTag<View>(tag) ?: return@runOnMainSync
            view.requestFocus()
            val connection = view.onCreateInputConnection(EditorInfo()) ?: return@runOnMainSync
            connection.deleteSurroundingText(MAX_SECRET, MAX_SECRET)
            taken = connection.commitText(CharBuffer.wrap(chars), 1)
        }
        device.waitForIdle()
        if (!taken) throw FlowStop("FAIL", "the secret field '$tag' did not take input")
    }

    private fun enter(tag: String, text: String) {
        val field = need(tag)
        val editable = if (field.className == EDIT_TEXT) field else field.findObject(By.clazz(EDIT_TEXT)) ?: field
        editable.press()
        editable.text = text
        device.waitForIdle()
        val now = find(tag)?.let { if (it.className == EDIT_TEXT) it else it.findObject(By.clazz(EDIT_TEXT)) }
        if (now?.text != text && text.all { it.isLetterOrDigit() }) {
            // Fallback for fields without set-text: clear, then the device's key input (letters and digits only).
            now?.clear()
            inputText(text)
        }
    }

    private fun inputText(text: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input text $text").close()
        device.waitForIdle()
    }

    /** Every visible text of the app, joined (for checks and failure details; no secret is drawn as text). */
    fun texts(): String = device.findObjects(By.pkg(packageName))
        .mapNotNull { node -> runCatching { node.text }.getOrNull()?.takeIf(String::isNotBlank) }.distinct().joinToString(" | ")

    fun shot(name: String) {
        // Only the app's own screens: never the shared phone's launcher or another app.
        if (!foreground()) return
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        control.screenshot(name, out.toByteArray())
    }

    /** The window hierarchy as UiAutomator sees it (texts and test tags), only while the app is in front. */
    fun dump(name: String) {
        if (!foreground()) return
        val out = ByteArrayOutputStream()
        runCatching { device.dumpWindowHierarchy(out) }.onSuccess { control.dump(name, out.toByteArray()) }
        control.log("$name windows: " + windows())
        control.dump("$name-input", inputDispatcherState())
    }

    /** The input dispatcher's focus and queue state lines (no content), read through the instrumentation's shell. */
    fun inputDispatcherState(): ByteArray = runCatching {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys input")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
        val wanted = Regex("Focus|focus|Pending|pending|Inject|inject|Paused|paused|Waiting|ANR|Anr|Connection|realrun|TouchState|Window #")
        text.lineSequence().filter { wanted.containsMatchIn(it) }.take(400).joinToString("\n").encodeToByteArray()
    }.getOrDefault("unavailable".encodeToByteArray())

    /** The windows on screen as accessibility reports them (type, layer, display, bounds, package), for failures. */
    fun windows(): String = runCatching {
        instrumentation.uiAutomation.windows.joinToString(" ; ") { window ->
            val bounds = android.graphics.Rect().also(window::getBoundsInScreen)
            "type=${window.type} layer=${window.layer} display=${window.displayId} focused=${window.isFocused} " +
                "active=${window.isActive} bounds=$bounds pkg=${window.root?.packageName}"
        }
    }.getOrDefault("unavailable")

    companion object {
        const val SHORT = 15_000L
        const val LONG = 60_000L
        const val POLL = 300L
        private const val OPEN_ATTEMPTS = 3
        private const val OPEN_WAIT = 5_000L
        private const val SETTLE = 200L
        private const val TEXT_TAPS = 10
        private const val EDIT_TEXT = "android.widget.EditText"
        private const val MAX_SECRET = 4096
        private const val LONG_PRESS_MILLIS = 1_500L
    }
}

/**
 * The run's six-digit account code. scripts/realrun.sh runs the flows in several instrumentation invocations (each
 * under ten minutes), so the code generated by flow 01 must outlive one process: it is kept on the phone only,
 * in the app's no-backup storage, encrypted with an AES-GCM key in the Android Keystore of the app's uid. A fresh
 * install removes both. It is never sent to the Mac, passed as an argument or logged.
 */
object RunCode {
    private const val ALIAS = "org.ramflux.realrun.run-code"
    private const val FILE = "realrun-run-code.bin"
    private val file get() = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, FILE)

    fun exists(): Boolean = file.exists()

    /** A new code for a new account; replaces any saved one. */
    fun create(): CharArray = generate().also(::save)

    /** A code that differs from [current], for the code change (saved by the caller once the SDK accepted it). */
    fun next(current: CharArray): CharArray {
        while (true) {
            val digits = generate()
            if (!digits.contentEquals(current)) return digits
            digits.fill('\u0000')
        }
    }

    /** Six random digits, not all equal and not a straight run. */
    private fun generate(): CharArray {
        val random = SecureRandom()
        while (true) {
            val digits = CharArray(6) { '0' + random.nextInt(10) }
            val same = digits.all { it == digits[0] }
            val up = (1 until 6).all { digits[it] - digits[it - 1] == 1 }
            val down = (1 until 6).all { digits[it - 1] - digits[it] == 1 }
            if (!same && !up && !down) return digits
            digits.fill('\u0000')
        }
    }

    fun save(digits: CharArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = ByteArray(digits.size) { digits[it].code.toByte() }
        val sealed = try {
            cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        file.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + sealed)
    }

    /** The saved code (the caller wipes it), or null when this install has none. */
    fun load(): CharArray? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val ivSize = bytes[0].toInt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivSize))
        val plain = cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize)
        return try {
            CharArray(plain.size) { plain[it].toInt().toChar() }
        } finally {
            plain.fill(0)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}

/**
 * Public run facts that later invocations need (display name, the phone's principal and commitment as alice sees
 * them, conversation and group ids), kept in the app's no-backup storage on the phone. Nothing secret.
 */
object RunState {
    private const val FILE = "realrun-run-state.properties"
    private val file get() = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, FILE)
    private fun load() = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    operator fun get(key: String): String? = load().getProperty(key)?.takeIf { it.isNotEmpty() }

    operator fun set(key: String, value: String) {
        val values = load()
        values.setProperty(key, value)
        file.outputStream().use { values.store(it, null) }
    }
}

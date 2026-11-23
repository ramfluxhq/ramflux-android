// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.realrun

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
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

    fun screenshot(name: String, png: ByteArray) {
        runCatching { raw("/screenshot/$name", png, "image/png", 30) }
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

/** A flow could not continue; [status] is what the table records (FAIL or BLOCKED). */
class FlowStop(val status: String, message: String) : Exception(message)

/** UiAutomator over the app's Compose test tags (exposed as view ids in debuggable builds). */
class Ui(private val control: PeerControl) {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val packageName: String = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw FlowStop("FAIL", "no launch intent for $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), LONG)
    }

    fun find(tag: String, timeout: Long = SHORT): UiObject2? = device.wait(Until.findObject(By.res(tag)), timeout)

    fun need(tag: String, timeout: Long = SHORT, step: String = tag): UiObject2 = find(tag, timeout) ?: throw FlowStop(
        "FAIL",
        "step '$step': '$tag' did not appear within ${timeout / 1000} s; screen: ${texts().take(400)}"
    )

    fun tap(tag: String, timeout: Long = SHORT) = need(tag, timeout).click()

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

    /** Codes and recovery words: same path, never logged; the String copy lives only in this process. */
    fun keys(tag: String, chars: CharArray) = enter(tag, String(chars))

    private fun enter(tag: String, text: String) {
        val field = need(tag)
        val editable = if (field.className == EDIT_TEXT) field else field.findObject(By.clazz(EDIT_TEXT)) ?: field
        editable.click()
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
        .mapNotNull { it.text?.takeIf(String::isNotBlank) }.distinct().joinToString(" | ")

    fun shot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        control.screenshot(name, out.toByteArray())
    }

    companion object {
        const val SHORT = 15_000L
        const val LONG = 60_000L
        const val POLL = 300L
        private const val EDIT_TEXT = "android.widget.EditText"
    }
}

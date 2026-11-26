// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One standalone-runtime answer: the status, the handle and the parsed callback object (null when none). */
internal class CardCall(val status: Int, val session: Long, val json: JsonObject?) {
    val ok: Boolean get() = status == CardFixtureRuntime.STATUS_OK
}

/**
 * The standalone card runtime for the realrun gallery (`ramflux_card_*`, ABI 5). Every call runs on one dedicated thread:
 * the runtime keeps its handles on the thread that created them. The runtime admits at most two resident VMs per process
 * and 128 admissions a second; a budget answer is retried after a short wait, a limit answer is for the caller to resolve.
 */
internal object CardFixtureRuntime {
    const val STATUS_OK = 0
    const val STATUS_LIMIT = 2
    const val STATUS_INACTIVE = 6
    const val STATUS_BUDGET = 5
    const val STATUS_INVALID = 1

    private const val BUDGET_ATTEMPTS = 4
    private const val BUDGET_WAIT_MS = 1_100L

    private val renderer = Executors.newSingleThreadExecutor { task -> Thread(task, "card-fixture-renderer").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    /** True when the test library is packaged and the runtime reports the ABI this client was built for. */
    suspend fun available(): Boolean = withContext(renderer) {
        CardFixtureNative.loaded && CardFixtureNative.abiVersion() == CardTrust.ABI_MAJOR
    }

    /** The bundled trust package, decoded and checked (format, ABI, descriptor digest, key and signature sizes). */
    suspend fun trust(): Result<CardTrust> = withContext(renderer) {
        if (!CardFixtureNative.loaded) return@withContext Result.failure(IllegalStateException("card fixture library not packaged"))
        val reply = CardFixtureNative.trustPackage()
        val bytes = reply.json
        if (reply.status != STATUS_OK || bytes == null) {
            Result.failure(IllegalStateException("trust package unavailable (status ${reply.status})"))
        } else {
            CardTrust.parse(bytes)
        }
    }

    /** Opens one envelope ([spec], the fixture's input file) for [locale] and [dark]. */
    suspend fun open(trust: CardTrust, spec: ByteArray, locale: String, dark: Boolean): CardCall = withContext(renderer) {
        val presentation = trust.presentation(locale, dark) ?: return@withContext CardCall(STATUS_INVALID, 0, null)
        attempt {
            CardFixtureNative.open(
                spec,
                trust.bytecode,
                trust.signature,
                trust.trustedKey,
                trust.descriptor,
                trust.policy,
                presentation
            )
        }
    }

    suspend fun tree(session: Long): CardCall = withContext(renderer) { attempt { CardFixtureNative.tree(session) } }

    suspend fun present(session: Long, trust: CardTrust, locale: String, dark: Boolean): CardCall = withContext(renderer) {
        val presentation = trust.presentation(locale, dark) ?: return@withContext CardCall(STATUS_INVALID, session, null)
        attempt { CardFixtureNative.present(session, presentation) }
    }

    /** One host event: `{token, event}` as the runtime's HostEvent (the token comes from the held tree). */
    suspend fun event(session: Long, input: JsonObject): CardCall = withContext(renderer) {
        attempt { CardFixtureNative.event(session, input.toString().toByteArray(Charsets.UTF_8)) }
    }

    suspend fun suspendCard(session: Long): CardCall = withContext(renderer) { attempt { CardFixtureNative.suspendCard(session) } }

    suspend fun resume(session: Long): CardCall = withContext(renderer) { attempt { CardFixtureNative.resume(session) } }

    suspend fun dispose(session: Long): Int = withContext(renderer) { CardFixtureNative.dispose(session) }

    private fun attempt(call: () -> CardFixtureReply): CardCall {
        var reply = call()
        var tries = 1
        while (reply.status == STATUS_BUDGET && tries < BUDGET_ATTEMPTS) {
            Thread.sleep(BUDGET_WAIT_MS)
            reply = call()
            tries++
        }
        return CardCall(reply.status, reply.session, reply.json?.let(::parseObject))
    }

    private fun parseObject(bytes: ByteArray): JsonObject? =
        runCatching { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
}

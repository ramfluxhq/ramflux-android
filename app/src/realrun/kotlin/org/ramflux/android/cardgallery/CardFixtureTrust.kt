// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The bundled card trust package (`ramflux_v2_card_trust_package`) decoded for the standalone runtime: the signed
 * bytecode, its signature and the trusted key, the exact descriptor bytes (the signature covers them), the acceptance
 * policy and the host presentations. Only the realrun gallery decodes it; the production path never does.
 */
internal class CardTrust(
    val bytecode: ByteArray,
    val signature: ByteArray,
    val trustedKey: ByteArray,
    val descriptor: ByteArray,
    val policy: ByteArray,
    private val presentations: Map<String, JsonObject>
) {
    /** The presentation for [locale] and [dark], as the JSON bytes ramflux_card_open reads; null when the package has none. */
    fun presentation(locale: String, dark: Boolean): ByteArray? = presentations[key(locale, dark)]?.toString()?.toByteArray(Charsets.UTF_8)

    companion object {
        const val FORMAT = "ramflux.sdk.card.trust.v1"
        const val ABI_MAJOR = 5
        const val LOCALE_EN = "en"
        const val LOCALE_ZH = "zh-CN"

        fun key(locale: String, dark: Boolean): String = "${locale}_${if (dark) "dark" else "light"}"

        /** Decodes the package; refuses (with the reason) when the format, the ABI, the descriptor digest or any field is off. */
        fun parse(bytes: ByteArray): Result<CardTrust> = runCatching {
            val root = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                ?: throw IllegalArgumentException("trust package is not a JSON object")
            require(root.text("format") == FORMAT) { "unknown trust package format" }
            require(root.long("card_abi_major") == ABI_MAJOR.toLong()) { "trust package is for another card ABI" }
            val descriptor = root.text("descriptor").orEmpty().toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(descriptor).joinToString("") { "%02x".format(it) }
            require(digest == root.text("descriptor_sha256")) { "descriptor digest does not match the package" }
            val signature = decode(root, "signature_base64url")
            val trustedKey = decode(root, "trusted_key_base64url")
            require(signature.size == 64 && trustedKey.size == 32) { "signature or trusted key has the wrong length" }
            val policy = (root["policy"] as? JsonObject ?: throw IllegalArgumentException("policy missing")).toString()
            val presentations = (root["presentations"] as? JsonObject ?: throw IllegalArgumentException("presentations missing"))
                .mapValues { (_, value) -> value as? JsonObject ?: throw IllegalArgumentException("presentation is not an object") }
            CardTrust(
                bytecode = decode(root, "bytecode_base64url"),
                signature = signature,
                trustedKey = trustedKey,
                descriptor = descriptor,
                policy = policy.toByteArray(Charsets.UTF_8),
                presentations = presentations
            )
        }

        private fun decode(root: JsonObject, name: String): ByteArray =
            Base64.getUrlDecoder().decode(root.text(name) ?: throw IllegalArgumentException("$name missing"))

        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(name: String): Long? =
            (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
    }
}

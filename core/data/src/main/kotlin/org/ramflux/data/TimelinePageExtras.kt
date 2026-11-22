// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.io.ByteArrayInputStream
import java.util.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.longOrNull

/**
 * The parts of a `message.read` read-model page the SDK display reader does not return: the backwards
 * paging cursor `next_before` (an event identity passed back unchanged as `before_order_key`) and the
 * `content.message.media[]` references of each row. Key slots are stripped by the SDK before the page is
 * built; this reader never sees key material.
 */
internal data class TimelinePageExtras(val nextBefore: String?, val media: Map<String, List<MediaAttachment>>) {
    override fun toString() = "TimelinePageExtras(<redacted>)"

    companion object {
        private const val MAX_CURSOR = 8192
        private val json = Json { ignoreUnknownKeys = true }

        @OptIn(ExperimentalSerializationApi::class)
        fun parse(raw: ByteArray): TimelinePageExtras {
            val root = json.decodeFromStream(JsonElement.serializer(), ByteArrayInputStream(raw)) as? JsonObject
                ?: throw IllegalArgumentException("page")
            val cursor = when (val next = root["next_before"]) {
                null, JsonNull -> null
                is JsonObject -> next.toString().takeIf { it.length <= MAX_CURSOR } ?: throw IllegalArgumentException("cursor")
                else -> throw IllegalArgumentException("cursor")
            }
            val media = LinkedHashMap<String, List<MediaAttachment>>()
            (root["messages"] as? JsonArray)?.forEach { row ->
                val obj = row as? JsonObject ?: return@forEach
                val id = obj.text("id") ?: return@forEach
                val items = mediaOf(id, obj)
                if (items.isNotEmpty()) media[id] = items
            }
            return TimelinePageExtras(cursor, media)
        }

        private fun mediaOf(id: String, row: JsonObject): List<MediaAttachment> {
            if (row.text("state") == "tombstone" || row["erasure"] is JsonObject) return emptyList()
            val content = row["content"] as? JsonObject ?: return emptyList()
            val message = content["message"] as? JsonObject ?: return emptyList()
            val list = message["media"] as? JsonArray ?: return emptyList()
            return list.take(MediaAttachment.MAX_PER_MESSAGE).mapIndexedNotNull { index, element ->
                val item = element as? JsonObject ?: return@mapIndexedNotNull null
                val mime = item.text("mimeType", "mime_type")?.takeIf { it.length in 1..256 } ?: "application/octet-stream"
                val size = item.number("size")?.takeIf { it >= 0 } ?: 0L
                val name = item.text("fileName", "file_name")?.takeIf { it.isNotEmpty() && it.length <= 256 }.orEmpty()
                val manifest = (item["largeManifest"] ?: item["large_manifest"]) as? JsonObject
                val expires = manifest?.number("expiresAt", "expires_at")?.takeIf { it > 0 }
                MediaAttachment(
                    id, index, MediaAttachment.kindOf(mime), mime, name, size,
                    thumbnailOf((item["inlineThumbnail"] ?: item["inline_thumbnail"]) as? JsonObject), expires,
                )
            }
        }

        private fun thumbnailOf(node: JsonObject?): MediaThumbnail? {
            node ?: return null
            val mime = node.text("mimeType", "mime_type") ?: return null
            val width = node.number("width")?.toInt() ?: return null
            val height = node.number("height")?.toInt() ?: return null
            val encoded = node.text("encodedImage", "encoded_image") ?: return null
            val bytes = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (bytes.isEmpty() || bytes.size > 64 * 1024 || width !in 1..4096 || height !in 1..4096) return null
            return MediaThumbnail(mime, width, height, bytes)
        }

        private fun JsonObject.text(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

        /** pbjson writes 64-bit integers as strings; both forms are read. */
        private fun JsonObject.number(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
            val value = this[name] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
            if (value.isString) value.content.toLongOrNull() else value.longOrNull
        }
    }
}

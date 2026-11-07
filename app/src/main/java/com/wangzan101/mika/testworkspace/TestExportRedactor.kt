package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.record.Redactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Removes credential fields and known credential values from text leaving the device. */
internal object TestExportRedactor {
    private val json = Json
    private val credentialName = Regex("(?i)(password|passwd|pwd|secret|token|credential|authorization|api.?key|access.?key|private.?key|session.?id)")
    private val assignment = Regex("(?i)([\\w.-]*(?:password|passwd|pwd|secret|token|credential|authorization|api.?key|access.?key|private.?key|session.?id)[\\w.-]*\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^,;\\s}]+)")

    fun bytes(name: String, input: ByteArray): ByteArray {
        val lowerName = name.lowercase()
        if (lowerName.startsWith("screens/") || lowerName.endsWith(".jpg") || lowerName.endsWith(".png")) return input
        val value = input.toString(Charsets.UTF_8)
        val sanitized = if (lowerName.endsWith(".jsonl")) {
            value.lineSequence().joinToString("\n") { line -> if (line.isBlank()) line else sanitizeText(line) }
        } else sanitizeText(value)
        return sanitized.toByteArray(Charsets.UTF_8)
    }

    fun text(value: String): String = sanitizeText(value)

    private fun sanitizeText(value: String): String {
        val structured = runCatching { json.parseToJsonElement(value) }.getOrNull()
        val sanitized = structured?.let { sanitize(it).toString() } ?: value
        val assignmentSafe = assignment.replace(sanitized) { "${it.groupValues[1]}\"***\"" }
        return Redactor.network(body = assignmentSafe).body?.let(Redactor::text) ?: Redactor.text(assignmentSafe)
    }

    private fun sanitize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, element) ->
            if (credentialName.containsMatchIn(key)) JsonPrimitive("***") else sanitize(element)
        })
        is JsonArray -> JsonArray(value.map(::sanitize))
        is JsonPrimitive -> if (value.isString) {
            val content = value.content
            val nested = runCatching { json.parseToJsonElement(content) }.getOrNull()
            JsonPrimitive(nested?.let { sanitize(it).toString() } ?: Redactor.text(content))
        } else value
    }
}

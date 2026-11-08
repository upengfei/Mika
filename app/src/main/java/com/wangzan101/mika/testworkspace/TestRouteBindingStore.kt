package com.wangzan101.mika.testworkspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A route is reusable only by the exact case revision that produced it. */
class TestRouteBindingStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun routeId(caseId: String, version: Int): String? = read().bindings
        .firstOrNull { it.caseId == caseId && it.version == version }?.routeId

    @Synchronized
    fun bind(caseId: String, version: Int, routeId: String) {
        require(caseId.isNotBlank() && version > 0 && routeId.isNotBlank())
        val next = read().bindings.filterNot { it.caseId == caseId && it.version == version } + Binding(caseId, version, routeId)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(Database(next)))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun read(): Database = if (!file.isFile) Database() else runCatching {
        json.decodeFromString<Database>(file.readText())
    }.getOrDefault(Database())

    @Serializable private data class Database(val bindings: List<Binding> = emptyList())
    @Serializable private data class Binding(val caseId: String, val version: Int, val routeId: String)
}

package com.wangzan101.mika.testworkspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A route is reusable only by the exact case revision that produced it. */
class TestRouteBindingStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun routeId(caseId: String, version: Int): String? = synchronized(lock) {
        read().bindings.firstOrNull { it.caseId == caseId && it.version == version }?.routeId
    }

    fun routeIds(): Map<Pair<String, Int>, String> = synchronized(lock) {
        read().bindings.associate { (it.caseId to it.version) to it.routeId }
    }

    fun bind(caseId: String, version: Int, routeId: String) = synchronized(lock) {
        require(caseId.isNotBlank() && version > 0 && routeId.isNotBlank())
        val db = read()
        val next = db.bindings.filterNot { it.caseId == caseId && it.version == version } + Binding(caseId, version, routeId)
        write(db.copy(bindings = next, captures = db.captures.filterNot { it.caseId == caseId && it.version == version }))
    }

    /** Persist the source before outcome judging finishes; either side can then complete the binding. */
    fun requestCapture(caseId: String, version: Int, sourceRunId: String) = synchronized(lock) {
        require(caseId.isNotBlank() && version > 0 && sourceRunId.isNotBlank())
        val db = read()
        if (db.bindings.any { it.caseId == caseId && it.version == version }) return@synchronized
        write(db.copy(captures = db.captures.filterNot { it.caseId == caseId && it.version == version } + Capture(caseId, version, sourceRunId)))
    }

    fun captures(sourceRunId: String? = null): List<Capture> = synchronized(lock) {
        read().captures.filter { sourceRunId == null || it.sourceRunId == sourceRunId }
    }

    private fun write(database: Database) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(database))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun read(): Database = if (!file.isFile) Database() else runCatching {
        json.decodeFromString<Database>(file.readText())
    }.getOrDefault(Database())

    // Capture UI and task completion use different store instances in the same app process.
    private companion object { val lock = Any() }
    @Serializable data class Capture(val caseId: String, val version: Int, val sourceRunId: String)
    @Serializable private data class Database(val bindings: List<Binding> = emptyList(), val captures: List<Capture> = emptyList())
    @Serializable private data class Binding(val caseId: String, val version: Int, val routeId: String)
}

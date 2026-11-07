package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.automation.TestTaskContract
import com.wangzan101.mika.core.automation.TestTaskContracts
import com.wangzan101.mika.core.automation.TestTaskFormat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Local, versioned task-contract storage used by the test workspace. */
class TestTaskStore(private val root: File) {
    private val json = Json { prettyPrint = true; explicitNulls = false }

    init { root.mkdirs() }

    fun save(contract: TestTaskContract): File {
        TestTaskContracts.requireValid(contract)
        val file = file(contract.id)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(TestTaskContract.serializer(), contract))
        return file
    }

    fun import(text: String, format: TestTaskFormat? = null): TestTaskContract =
        TestTaskContracts.parse(text, format).also(::save)

    fun load(id: String): TestTaskContract? = file(id).takeIf { it.isFile }
        ?.let { runCatching { json.decodeFromString(TestTaskContract.serializer(), it.readText()) }.getOrNull() }

    fun list(): List<TestTaskContract> = root.listFiles { f -> f.isFile && f.extension == "json" }
        .orEmpty().sortedBy { it.name }.mapNotNull { file -> runCatching { json.decodeFromString(TestTaskContract.serializer(), file.readText()) }.getOrNull() }

    fun delete(id: String): Boolean = file(id).delete()

    fun file(id: String): File {
        require(id.matches(Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$"))) { "invalid task id" }
        return File(root, "$id.json")
    }
}

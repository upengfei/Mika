package com.wangzan101.mika.core.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TestTaskContract(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String = id,
    val command: String,
    val targetApp: String? = null,
    val steps: List<TestStepSpec> = emptyList(),
    val assertions: List<TestAssertionSpec> = emptyList(),
    val modelPolicy: TestModelPolicy = TestModelPolicy(),
    val sandbox: TestSandboxSpec? = null,
    val schedule: TestScheduleSpec? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class TestStepSpec(
    val id: String,
    val instruction: String,
)

@Serializable
data class TestAssertionSpec(
    val id: String,
    val kind: TestAssertionKind,
    val description: String,
    val required: Boolean = true,
)

@Serializable
enum class TestAssertionKind {
    @SerialName("hard") HARD,
    @SerialName("soft") SOFT,
}

@Serializable
data class TestModelPolicy(
    val model: String = "",
    val promptVersion: String = "",
)

@Serializable
data class TestSandboxSpec(
    val name: String = "",
    val cleanup: String = "retain",
)

@Serializable
data class TestScheduleSpec(
    val intervalMinutes: Int? = null,
)

enum class TestTaskFormat { JSON, YAML }

object TestTaskContracts {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }
    private val idPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun parse(text: String, format: TestTaskFormat? = null): TestTaskContract {
        val actual = format ?: detectFormat(text)
        return when (actual) {
            TestTaskFormat.JSON -> json.decodeFromString(TestTaskContract.serializer(), text)
            TestTaskFormat.YAML -> YamlTestTaskParser.parse(text)
        }
    }

    fun validate(contract: TestTaskContract): List<String> = buildList {
        if (contract.schemaVersion != TestTaskContract.CURRENT_SCHEMA_VERSION) {
            add("schemaVersion ${contract.schemaVersion} is unsupported")
        }
        if (!idPattern.matches(contract.id)) add("id must match ${idPattern.pattern}")
        if (contract.command.isBlank()) add("command must not be blank")
        if (contract.steps.any { it.id.isBlank() || it.instruction.isBlank() }) add("steps must have non-blank id and instruction")
        if (contract.steps.map { it.id }.distinct().size != contract.steps.size) add("step ids must be unique")
        if (contract.assertions.any { it.id.isBlank() || it.description.isBlank() }) add("assertions must have non-blank id and description")
        contract.assertions.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { add("duplicate assertion id: $it") }
        contract.schedule?.intervalMinutes?.let { if (it <= 0) add("schedule.intervalMinutes must be positive") }
        if (contract.modelPolicy.model.isBlank()) add("modelPolicy.model must not be blank")
        if (contract.modelPolicy.promptVersion.isBlank()) add("modelPolicy.promptVersion must not be blank")
    }

    fun requireValid(contract: TestTaskContract): TestTaskContract = validate(contract).takeIf { it.isEmpty() }?.let { contract }
        ?: error(validate(contract).joinToString("; "))

    private fun detectFormat(text: String): TestTaskFormat = text.trimStart().let { if (it.startsWith("{") || it.startsWith("[")) TestTaskFormat.JSON else TestTaskFormat.YAML }
}

package com.wangzan101.mika.core.automation

import kotlinx.serialization.json.Json

/** A deliberately small YAML reader for the task contract; it accepts mappings, sequences and scalar values. */
internal object YamlTestTaskParser {
    private data class Item(val indent: Int, val text: String)

    fun parse(input: String): TestTaskContract {
        val items = input.lineSequence()
            .map { it.replace("\t", "  ") }
            .mapNotNull { raw ->
                val withoutComment = stripComment(raw).trimEnd()
                if (withoutComment.isBlank() || withoutComment.trimStart().startsWith("#") || withoutComment.trim() == "---") null
                else Item(withoutComment.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0), withoutComment.trim())
            }.toList()
        val root = linkedMapOf<String, String>()
        val steps = mutableListOf<MutableMap<String, String>>()
        val assertions = mutableListOf<MutableMap<String, String>>()
        val model = linkedMapOf<String, String>()
        val sandbox = linkedMapOf<String, String>()
        val schedule = linkedMapOf<String, String>()
        var section = ""
        var current: MutableMap<String, String>? = null
        for (item in items) {
            val line = item.text
            if (item.indent == 0 && !line.startsWith("- ")) {
                val (key, value) = split(line)
                require(key in setOf("schemaVersion", "id", "name", "command", "targetApp", "steps", "assertions", "modelPolicy", "sandbox", "schedule")) {
                    "Unknown YAML field '$key'"
                }
                section = key
                current = null
                if (value.isNotEmpty()) root[key] = value
                continue
            }
            if (section == "steps" || section == "assertions") {
                if (line.startsWith("- ")) {
                    current = linkedMapOf()
                    (if (section == "steps") steps else assertions) += current
                    val rest = line.removePrefix("- ").trim()
                    if (rest.isNotEmpty()) {
                        val (key, value) = split(rest)
                        require(key in if (section == "steps") setOf("id", "instruction") else setOf("id", "kind", "description", "required", "expectedText")) {
                            "Unknown YAML field '$key' in $section"
                        }
                        current[key] = value
                    }
                } else {
                    val (key, value) = split(line)
                    require(key in if (section == "steps") setOf("id", "instruction") else setOf("id", "kind", "description", "required", "expectedText")) {
                        "Unknown YAML field '$key' in $section"
                    }
                    current?.set(key, value)
                }
            } else {
                val (key, value) = split(line)
                when (section) {
                    "modelPolicy" -> { require(key in setOf("model", "promptVersion")) { "Unknown YAML field '$key' in modelPolicy" }; model[key] = value }
                    "sandbox" -> { require(key in setOf("name", "cleanup")) { "Unknown YAML field '$key' in sandbox" }; sandbox[key] = value }
                    "schedule" -> { require(key == "intervalMinutes") { "Unknown YAML field '$key' in schedule" }; schedule[key] = value }
                }
            }
        }
        return TestTaskContract(
            schemaVersion = root["schemaVersion"]?.let { scalar(it).toIntOrNull() ?: error("schemaVersion must be an integer") } ?: TestTaskContract.CURRENT_SCHEMA_VERSION,
            id = scalar(root["id"].orEmpty()),
            name = scalar(root["name"] ?: root["id"].orEmpty()),
            command = scalar(root["command"].orEmpty()),
            targetApp = root["targetApp"]?.let(::scalar),
            steps = steps.map { TestStepSpec(scalar(it["id"].orEmpty()), scalar(it["instruction"].orEmpty())) },
            assertions = assertions.map {
                TestAssertionSpec(
                    id = scalar(it["id"].orEmpty()),
                    kind = when (scalar(it["kind"].orEmpty()).lowercase()) {
                        "soft" -> TestAssertionKind.SOFT
                        "hard" -> TestAssertionKind.HARD
                        else -> error("assertion kind must be 'hard' or 'soft'")
                    },
                    description = scalar(it["description"].orEmpty()),
                    required = it["required"]?.let(::scalar)?.toBooleanStrictOrNull() ?: true,
                    expectedText = it["expectedText"]?.let(::scalar),
                )
            },
            modelPolicy = TestModelPolicy(scalar(model["model"].orEmpty()), scalar(model["promptVersion"].orEmpty())),
            sandbox = if (sandbox.isEmpty()) null else TestSandboxSpec(scalar(sandbox["name"].orEmpty()), scalar(sandbox["cleanup"] ?: "retain")),
            schedule = if (schedule.isEmpty()) null else TestScheduleSpec(schedule["intervalMinutes"]?.let { scalar(it).toIntOrNull() ?: error("schedule.intervalMinutes must be an integer") }),
        )
    }

    private fun split(line: String): Pair<String, String> {
        val i = line.indexOf(':')
        require(i > 0) { "Invalid YAML mapping: $line" }
        return line.substring(0, i).trim() to line.substring(i + 1).trim()
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            if (escaped) escaped = false
            else if (quote == '"' && char == '\\') escaped = true
            else if (quote != null && char == quote) {
                if (quote == '\'' && line.getOrNull(index + 1) == '\'') index++ else quote = null
            } else if (quote == null && (char == '"' || char == '\'')) quote = char
            else if (quote == null && char == '#' && (index == 0 || line[index - 1].isWhitespace())) {
                return line.substring(0, index)
            }
            index++
        }
        return line
    }

    private fun scalar(value: String): String {
        val v = value.trim()
        if (v.length < 2) return v
        return when {
            v.first() == '"' && v.last() == '"' -> Json.decodeFromString<String>(v)
            v.first() == '\'' && v.last() == '\'' -> v.substring(1, v.length - 1).replace("''", "'")
            else -> v
        }
    }
}

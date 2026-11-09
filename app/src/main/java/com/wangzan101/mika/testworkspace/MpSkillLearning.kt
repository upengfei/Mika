package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.skills.*
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.*

/** MP-only admission and exact local deduplication. No inference or task text is stored in metadata. */
object MpSkillLearning : SkillLearningPolicy {
    override val minimumActions = 1
    override val skipExtractionOnReplay = true
    private const val SIGNATURE = "mp_learning_signature"

    override fun shouldDraft(input: DraftInput, existing: PersonalSkills): Boolean {
        val actions = ActionDigest.of(input.events, input.tools)
        // Unlike the old count, require positive evidence of an effect or a successful open.
        val effective = input.events.filterIsInstance<RunEvent.ToolResult>().any { e ->
            val result = runCatching { Json.parseToJsonElement(e.resultJson).jsonObject }.getOrNull()
            result?.get("ok") == JsonPrimitive(true) &&
                (e.changed == true || result["changed"] == JsonPrimitive(true) ||
                    result["verification"] == JsonPrimitive("match") ||
                    (e.changed != false && result["changed"] != JsonPrimitive(false) &&
                        input.tools.verb(e.tool) in setOf(Verb.LaunchApp, Verb.Navigate))) &&
                actions.any { it.step == e.step && it.tool == e.tool }
        }
        if (!effective) return false
        val signature = signature(input)
        return (existing.list().filter { it.status in setOf(SkillStatus.active, SkillStatus.trial) } + existing.pending()).none { s ->
            s.learnedFrom == input.runId || FrontMatter.parse(s.markdown).first[SIGNATURE] == signature
        }
    }

    override fun decorate(input: DraftInput, skill: PersonalSkill): PersonalSkill =
        skill.copy(markdown = PersonalSkills.withField(skill.markdown, SIGNATURE, signature(input)))

    private fun signature(input: DraftInput): String {
        val actions = ActionDigest.of(input.events, input.tools)
        val goal = SkillRedactor.scrubNumbers(SkillRedactor.redact(input.goal, SkillRedactor.literals(input.goal, emptyList())), emptySet())
            .replace(Regex("(?m)^Test (?:task|case|execution|route).*$(?:\n)?"), "").trim()
        // Targets and places distinguish different workflows; transient run/step numbers do not.
        return PersonalSkills.fingerprint(goal + "\n" + actions.joinToString("\n") { "${it.tool}|${it.where.orEmpty()}|${it.target.orEmpty()}|${it.typed}" })
    }
}

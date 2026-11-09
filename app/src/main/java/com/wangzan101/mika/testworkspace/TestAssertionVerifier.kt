package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.automation.*
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.verify.Criteria
import com.wangzan101.mika.core.verify.Evidence
import com.wangzan101.mika.core.verify.Verification
import com.wangzan101.mika.core.verify.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** MP verification seam: local checks for hard facts, one optional model request for soft criteria. */
class TestAssertionVerifier(
    private val semanticCheck: suspend (String, Criteria, String, Evidence) -> Verification,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(val verdict: TestVerdict, val event: RunEvent.Verifier) {
        fun applyTo(end: TaskState.Succeeded): TaskState.Succeeded = end.copy(
            verified = verdict.passed && !end.doubtful,
            reviewed = true,
            note = if (verdict.passed) end.note else "测试断言未全部通过：${verdict.reasons.joinToString("；")}",
            unconfirmed = (end.unconfirmed + verdict.reasons).distinct(),
        )
    }

    suspend fun verify(
        contract: TestTaskContract, caseId: String, version: Int, goal: String, answer: String, evidence: Evidence,
    ): Result {
        val hard = mutableMapOf<String, Boolean>()
        val soft = mutableMapOf<String, Boolean>()
        val explanations = mutableMapOf<String, String>()
        val quotes = mutableMapOf<String, String>()
        // Only a fresh read of the intended target counts; historic success screens and user/model text do not.
        val reads = evidence.fresh.filter { o ->
            o.kind in setOf(ObsKind.Read, ObsKind.Output) &&
                (contract.targetApp.isNullOrBlank() || o.place?.let { it.kind == PlaceKind.App && it.name == contract.targetApp } == true)
        }.map { o ->
            val inputs = o.inputs?.names.orEmpty().filter(String::isNotBlank)
            o.text.lines().filterNot { line ->
                inputs.any { it in line } || o.authored?.takeIf(String::isNotBlank)?.contains(line.trim().takeIf(String::isNotBlank) ?: "\u0000") == true
            }.joinToString("\n")
        }.filter(String::isNotBlank).takeUnless { evidence.protected }.orEmpty()

        contract.assertions.filter { it.kind == TestAssertionKind.HARD }.forEach { assertion ->
            val expected = assertion.expectedText
            when {
                expected.isNullOrBlank() -> explanations[assertion.id] = "硬断言需配置 expectedText，不能用模型的成功结论代替客观校验"
                reads.isEmpty() -> explanations[assertion.id] = "没有可读取的当前目标页面证据"
                else -> {
                    // Literal text comparison, including numeric token boundaries; no semantic model override.
                    val met = TestLiteralEvidence.contains(expected, reads)
                    hard[assertion.id] = met
                    explanations[assertion.id] = if (met) "当前目标页面包含预期文字" else "当前目标页面未找到预期文字：$expected"
                    if (met) quotes[assertion.id] = expected
                }
            }
        }

        val semantic = contract.assertions.filter { it.kind == TestAssertionKind.SOFT }
        if (semantic.isNotEmpty()) {
            val checked = try {
                semanticCheck(goal, Criteria(semantic.map(::criterion), "read", fallback = false), answer, evidence)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Verification(Verdict.CannotVerify, emptyList(), "语义核查失败，结果待验证")
            }
            semantic.forEach { assertion ->
                val check = checked.checks.singleOrNull { it.criterion == criterion(assertion) }
                when {
                    check?.status == "unmet" -> soft[assertion.id] = false
                    checked.verdict == Verdict.Pass && check?.status == "met" -> soft[assertion.id] = true
                }
                explanations[assertion.id] = checked.reason
                check?.quote?.let { quotes[assertion.id] = it }
            }
        }
        val verdict = TestVerdictEvaluator.evaluate(contract, TestEvidence(hard, soft, explanations))
        val wire = when {
            verdict.passed -> "pass"
            verdict.assertions.any { row -> row.status == TestAssertionStatus.FAILED && contract.assertions.any { it.id == row.id && it.required } } -> "fail"
            else -> "cannot_verify"
        }
        val detail = buildJsonObject {
            put("source", SOURCE)
            put("caseId", caseId)
            put("caseVersion", version)
            put("reason", verdict.reasons.joinToString("；"))
            put("checks", JsonArray(verdict.assertions.map { result -> buildJsonObject {
                put("assertionId", result.id)
                put("kind", result.kind.name.lowercase())
                put("criterion", contract.assertions.first { it.id == result.id }.description)
                put("status", when (result.status) { TestAssertionStatus.PASSED -> "met"; TestAssertionStatus.FAILED -> "unmet"; TestAssertionStatus.UNRESOLVED -> "unknown" })
                result.explanation?.let { put("reason", it) }
                quotes[result.id]?.let { put("quote", it) }
            } }))
        }
        // Do not clip a JSON document: clipped verification rows cannot be read back reliably.
        return Result(verdict, RunEvent.Verifier(clock(), wire, Redactor.text(detail.toString())))
    }

    companion object {
        const val SOURCE = "mp-test-assertions-v1"
        private fun criterion(assertion: TestAssertionSpec) = "[${assertion.id}] ${assertion.description}"

        /** Use one complete, latest verification for this revision, never combine passes from different attempts. */
        fun permitsRoute(contract: TestTaskContract, caseId: String, version: Int, events: List<RunEvent>): Boolean {
            if (contract.assertions.none { it.required }) return true
            return runCatching {
                val event = events.filterIsInstance<RunEvent.Verifier>().lastOrNull() ?: return@runCatching false
                val detail = Json.parseToJsonElement(event.detailJson).jsonObject
                if (event.verdict != "pass" || detail["source"]?.jsonPrimitive?.content != SOURCE ||
                    detail["caseId"]?.jsonPrimitive?.content != caseId || detail["caseVersion"]?.jsonPrimitive?.intOrNull != version) return@runCatching false
                val checks = detail["checks"] as? JsonArray ?: return@runCatching false
                contract.assertions.filter { it.required }.all { spec ->
                    val row = checks.mapNotNull { it as? JsonObject }.singleOrNull { it["assertionId"]?.jsonPrimitive?.content == spec.id }
                    row?.get("status")?.jsonPrimitive?.content == "met" && row["kind"]?.jsonPrimitive?.content == spec.kind.name.lowercase() &&
                        row["criterion"]?.jsonPrimitive?.content == spec.description &&
                        (spec.kind != TestAssertionKind.HARD || (!spec.expectedText.isNullOrBlank() && row["quote"]?.jsonPrimitive?.content == spec.expectedText))
                }
            }.getOrDefault(false)
        }
    }
}

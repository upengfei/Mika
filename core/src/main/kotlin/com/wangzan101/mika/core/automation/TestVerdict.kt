package com.wangzan101.mika.core.automation

data class TestEvidence(
    val hard: Map<String, Boolean> = emptyMap(),
    val soft: Map<String, Boolean> = emptyMap(),
    val explanations: Map<String, String> = emptyMap(),
    val screenshots: Map<String, List<String>> = emptyMap(),
)

enum class TestAssertionStatus { PASSED, FAILED, UNRESOLVED }

data class TestAssertionResult(
    val id: String,
    val kind: TestAssertionKind,
    val status: TestAssertionStatus,
    val explanation: String? = null,
    val screenshots: List<String> = emptyList(),
)

data class TestVerdict(
    val passed: Boolean,
    val assertions: List<TestAssertionResult>,
    val reasons: List<String>,
)

object TestVerdictEvaluator {
    fun evaluate(contract: TestTaskContract, evidence: TestEvidence): TestVerdict {
        val results = contract.assertions.map { assertion ->
            val value = when (assertion.kind) {
                TestAssertionKind.HARD -> evidence.hard[assertion.id]
                TestAssertionKind.SOFT -> evidence.soft[assertion.id]
            }
            TestAssertionResult(
                id = assertion.id,
                kind = assertion.kind,
                status = when (value) {
                    true -> TestAssertionStatus.PASSED
                    false -> TestAssertionStatus.FAILED
                    null -> TestAssertionStatus.UNRESOLVED
                },
                explanation = evidence.explanations[assertion.id],
                screenshots = evidence.screenshots[assertion.id].orEmpty(),
            )
        }
        val reasons = results.filterIndexed { index, result ->
            val spec = contract.assertions[index]
            spec.required && result.status != TestAssertionStatus.PASSED
        }.map { "${it.id}: ${it.status.name.lowercase()}" }
        return TestVerdict(reasons.isEmpty(), results, reasons)
    }
}

package com.wangzan101.mika.core.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestTaskContractTest {
    @Test fun parsesJsonAndValidatesVersionedContract() {
        val contract = TestTaskContracts.parse(
            """
            {
              "schemaVersion": 1,
              "id": "checkout-sandbox",
              "name": "沙箱支付",
              "command": "在支付测试环境完成一笔订单",
              "targetApp": "com.example.shop",
              "steps": [{"id":"open","instruction":"打开商品详情"}],
              "assertions": [
                {"id":"payment","kind":"hard","description":"支付状态为成功"},
                {"id":"copy","kind":"soft","description":"页面显示成功提示"}
              ],
              "modelPolicy": {"model":"fixed-model","promptVersion":"prompt-7"}
            }
            """.trimIndent(), TestTaskFormat.JSON,
        )

        assertEquals("checkout-sandbox", contract.id)
        assertEquals(1, contract.schemaVersion)
        assertEquals(1, contract.steps.size)
        assertEquals(TestAssertionKind.HARD, contract.assertions.first().kind)
        assertTrue(TestTaskContracts.validate(contract).isEmpty())
    }

    @Test fun parsesYamlWithStepsAndAssertions() {
        val contract = TestTaskContracts.parse(
            """
            schemaVersion: 1
            id: checkout-sandbox
            command: 在支付测试环境完成一笔订单
            targetApp: com.example.shop
            modelPolicy:
              model: fixed-model
              promptVersion: prompt-7
            steps:
              - id: open
                instruction: 打开商品详情
            assertions:
              - id: payment
                kind: hard
                description: 支付状态为成功
              - id: copy
                kind: soft
                description: 页面显示成功提示
            """.trimIndent(), TestTaskFormat.YAML,
        )

        assertEquals("checkout-sandbox", contract.id)
        assertEquals("fixed-model", contract.modelPolicy.model)
        assertEquals(1, contract.steps.size)
        assertEquals(2, contract.assertions.size)
        assertTrue(TestTaskContracts.validate(contract).isEmpty())
    }

    @Test fun rejectsDuplicateAssertionIdsAndMissingCommand() {
        val contract = TestTaskContract(
            id = "bad",
            command = "",
            assertions = listOf(
                TestAssertionSpec("same", TestAssertionKind.HARD, "a"),
                TestAssertionSpec("same", TestAssertionKind.SOFT, "b"),
            ),
        )
        val errors = TestTaskContracts.validate(contract)
        assertTrue(errors.any { "command" in it })
        assertTrue(errors.any { "same" in it && "duplicate" in it })
    }

    @Test fun rejectsUnknownYamlFieldsAndInvalidAssertionKinds() {
        val base = """
            id: invalid
            command: inspect page
            modelPolicy:
              model: fixed-model
              promptVersion: prompt-1
            assertions:
              - id: result
                kind: typo
                description: result is visible
        """.trimIndent()

        assertFalse(runCatching { TestTaskContracts.parse(base, TestTaskFormat.YAML) }.isSuccess)
        assertFalse(runCatching { TestTaskContracts.parse(base.replace("kind: typo", "kind: hard").replace("command: inspect page", "unexpected: value"), TestTaskFormat.YAML) }.isSuccess)
        assertFalse(runCatching { TestTaskContracts.parse(base.replace("kind: typo", "kind: hard").replace("command: inspect page", "command: inspect page\nschedule:\n  intervalMinutes: often"), TestTaskFormat.YAML) }.isSuccess)
    }

    @Test fun verdictCannotOverrideRequiredHardFailure() {
        val contract = TestTaskContract(
            id = "checkout",
            command = "完成支付",
            assertions = listOf(
                TestAssertionSpec("payment", TestAssertionKind.HARD, "交易成功"),
                TestAssertionSpec("copy", TestAssertionKind.SOFT, "页面有成功文案"),
            ),
        )
        val result = TestVerdictEvaluator.evaluate(
            contract,
            TestEvidence(
                hard = mapOf("payment" to false),
                soft = mapOf("copy" to true),
                explanations = mapOf("copy" to "LLM 看到成功文案"),
            ),
        )

        assertFalse(result.passed)
        assertEquals(TestAssertionStatus.FAILED, result.assertions.first().status)
        assertTrue(result.reasons.any { "payment" in it })
    }

    @Test fun optionalSoftFailureDoesNotFailRun() {
        val contract = TestTaskContract(
            id = "read-only",
            command = "检查页面",
            assertions = listOf(
                TestAssertionSpec("page", TestAssertionKind.HARD, "页面可访问"),
                TestAssertionSpec("copy", TestAssertionKind.SOFT, "文案友好", required = false),
            ),
        )
        val result = TestVerdictEvaluator.evaluate(contract, TestEvidence(hard = mapOf("page" to true), soft = mapOf("copy" to false)))

        assertTrue(result.passed)
        assertEquals(TestAssertionStatus.FAILED, result.assertions[1].status)
    }
}

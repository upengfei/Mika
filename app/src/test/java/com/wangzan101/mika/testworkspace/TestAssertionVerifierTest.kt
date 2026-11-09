package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.automation.*
import com.wangzan101.mika.core.observation.*
import com.wangzan101.mika.core.verify.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TestAssertionVerifierTest {
    private val hard = TestAssertionSpec("paid", TestAssertionKind.HARD, "订单支付状态正确", expectedText = "支付成功")
    private val contract = TestTaskContract(id = "checkout", command = "检查支付", targetApp = "com.shop", assertions = listOf(hard), modelPolicy = TestModelPolicy("m", "p"))
    private var modelCalls = 0
    private val verifier = TestAssertionVerifier({ _, criteria, _, _ ->
        modelCalls++
        Verification(Verdict.Pass, criteria.items.map { CriterionCheck(it, "met", null) }, "模型认为成功")
    }) { 10L }
    private fun evidence(text: String = "订单状态：支付成功", pkg: String = "com.shop", protected: Boolean = false, history: String? = null, input: String? = null): Evidence {
        val ledger = EvidenceLedger()
        history?.let { ledger.add(Observation(ObsKind.Read, it, place = Place.app("com.shop"))) }
        return Evidence(listOf(Observation(ObsKind.Read, text, place = Place.app(pkg), inputs = input?.let { InputHints(listOf(it)) })), null, ledger, emptyList(), protected)
    }

    @Test fun freshHardEvidencePassesWithoutAnyModelCallAndAllowsBinding() = runTest {
        val result = verifier.verify(contract, "checkout", 2, "检查支付", "已完成", evidence())
        assertTrue(result.verdict.passed)
        assertEquals(0, modelCalls)
        assertTrue(TestAssertionVerifier.permitsRoute(contract, "checkout", 2, listOf(result.event)))
        assertFalse(TestAssertionVerifier.permitsRoute(contract, "checkout", 3, listOf(result.event)))
        assertFalse(TestAssertionVerifier.permitsRoute(contract, "other", 2, listOf(result.event)))
    }

    @Test fun aSemanticPassCannotOverrideAFailedHardCheck() = runTest {
        val both = contract.copy(assertions = listOf(hard, TestAssertionSpec("copy", TestAssertionKind.SOFT, "提示语清楚")))
        val result = verifier.verify(both, "checkout", 1, "检查支付", "成功", evidence("支付失败"))
        assertEquals(1, modelCalls)
        assertFalse(result.verdict.passed)
        assertEquals(TestAssertionStatus.FAILED, result.verdict.assertions.first().status)
        assertEquals("fail", result.event.verdict)
        val end = result.applyTo(TaskState.Succeeded("成功", reviewed = false))
        assertFalse(LearnGate.done(end, RunOutcome.Done))
        assertFalse(TestAssertionVerifier.permitsRoute(both, "checkout", 1, listOf(result.event)))
    }

    @Test fun oldSuccessWrongAppProtectedPageAndInputEchoCannotProveAHardFact() = runTest {
        val samples = listOf(
            evidence("支付失败", history = "支付成功"),
            evidence(pkg = "com.other"),
            evidence(protected = true),
            evidence(text = "输入框：支付成功", input = "支付成功"),
        )
        samples.forEach { e -> assertFalse(verifier.verify(contract, "checkout", 1, "检查支付", "成功", e).verdict.passed) }
        assertEquals(0, modelCalls)
    }

    @Test fun legacyDescriptionOnlyHardAssertionIsUnresolvedRatherThanModelApproved() = runTest {
        val result = verifier.verify(contract.copy(assertions = listOf(hard.copy(expectedText = null))), "checkout", 1, "检查", "成功", evidence())
        assertEquals("cannot_verify", result.event.verdict)
        assertEquals(TestAssertionStatus.UNRESOLVED, result.verdict.assertions.single().status)
        assertEquals(0, modelCalls)
    }

    @Test fun numericExpectedTextCannotMatchPartOfAnotherNumber() = runTest {
        val amount = contract.copy(assertions = listOf(hard.copy(expectedText = "100")))
        assertFalse(verifier.verify(amount, "checkout", 1, "金额", "成功", evidence("金额：1100元")).verdict.passed)
        assertTrue(verifier.verify(amount, "checkout", 1, "金额", "成功", evidence("金额：100元")).verdict.passed)
    }

    @Test fun latestFailedVerificationRevokesEarlierPassInsteadOfCombiningEvidence() = runTest {
        val pass = verifier.verify(contract, "checkout", 1, "检查", "成功", evidence()).event
        val fail = verifier.verify(contract, "checkout", 1, "检查", "成功", evidence("支付失败")).event
        assertFalse(TestAssertionVerifier.permitsRoute(contract, "checkout", 1, listOf(pass, fail)))
        assertFalse(TestAssertionVerifier.permitsRoute(contract, "checkout", 1, listOf(pass.copy(detailJson = "{broken"))))
        assertFalse(TestAssertionVerifier.permitsRoute(contract, "checkout", 1, listOf(pass, pass.copy(detailJson = "{broken"))))
        val generic = com.wangzan101.mika.core.record.RunEvent.Verifier(1, "pass", """{"checks":[{"criterion":"订单支付状态正确","status":"met"}]}""")
        assertFalse(TestAssertionVerifier.permitsRoute(contract, "checkout", 1, listOf(generic)))
    }

    @Test fun semanticFailureAndMissingChecksRemainUnresolvedWithoutRetryingActions() = runTest {
        val soft = contract.copy(assertions = listOf(TestAssertionSpec("copy", TestAssertionKind.SOFT, "提示清楚")))
        val missing = TestAssertionVerifier({ _, _, _, _ -> Verification(Verdict.Pass, emptyList(), "总体成功") })
        assertFalse(missing.verify(soft, "checkout", 1, "检查", "成功", evidence()).verdict.passed)
        val broken = TestAssertionVerifier({ _, _, _, _ -> error("offline") })
        assertEquals("cannot_verify", broken.verify(soft, "checkout", 1, "检查", "成功", evidence()).event.verdict)
    }
}

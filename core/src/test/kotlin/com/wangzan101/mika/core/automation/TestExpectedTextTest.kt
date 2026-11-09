package com.wangzan101.mika.core.automation

import org.junit.Assert.*
import org.junit.Test

class TestExpectedTextTest {
    @Test fun expectedTextSurvivesJsonAndYamlExportImport() {
        val contract = TestTaskContract(id = "paid", command = "检查订单", modelPolicy = TestModelPolicy("m", "p"),
            assertions = listOf(TestAssertionSpec("status", TestAssertionKind.HARD, "状态正确", expectedText = "订单状态：支付成功")))
        TestTaskFormat.entries.forEach { format ->
            val encoded = TestTaskContracts.encode(contract, format)
            assertEquals(contract, TestTaskContracts.parse(encoded, format))
        }
    }

    @Test fun legacyContractsRemainReadableAndBlankExpectedTextIsRejected() {
        val legacy = TestTaskContracts.parse("""{"id":"old","command":"检查","modelPolicy":{"model":"m","promptVersion":"p"},"assertions":[{"id":"status","kind":"hard","description":"已完成"}]}""")
        assertNull(legacy.assertions.single().expectedText)
        assertTrue(TestTaskContracts.validate(legacy).isEmpty())
        assertFalse(TestTaskContracts.validate(legacy.copy(assertions = listOf(legacy.assertions.single().copy(expectedText = " ")))).isEmpty())
    }

    @Test fun literalChecksRequireTheWholeExpectedTextAndPreserveNumberBoundaries() {
        assertFalse(TestLiteralEvidence.contains("订单正确\n支付成功", listOf("支付成功")))
        assertFalse(TestLiteralEvidence.contains("100", listOf("金额：1100元")))
        assertFalse(TestLiteralEvidence.contains("100", listOf("金额：100.50元")))
        assertFalse(TestLiteralEvidence.contains("100", listOf("金额：-100元")))
        assertTrue(TestLiteralEvidence.contains("100", listOf("金额：１００元")))
        assertTrue(TestLiteralEvidence.contains("支付成功", listOf("支付\u200B成功")))
    }
}

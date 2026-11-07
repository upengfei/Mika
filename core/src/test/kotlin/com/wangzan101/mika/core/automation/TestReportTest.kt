package com.wangzan101.mika.core.automation

import java.util.zip.ZipInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestReportTest {
    @Test fun htmlIncludesFailureScreenshotAndEscapesUserText() {
        val contract = TestTaskContract(
            id = "checkout",
            name = "<沙箱>",
            command = "完成支付",
            modelPolicy = TestModelPolicy("fixed", "p1"),
            assertions = listOf(TestAssertionSpec("payment", TestAssertionKind.HARD, "支付成功")),
        )
        val verdict = TestVerdictEvaluator.evaluate(
            contract,
            TestEvidence(hard = mapOf("payment" to false), screenshots = mapOf("payment" to listOf("screens/0001.jpg"))),
        )

        val html = TestReportRenderer.html(contract, "run-1", verdict, TestRunMetadata(appVersion = "1.2", mikaVersion = "upstream-9"))

        assertTrue(html.contains("screens/0001.jpg"))
        assertTrue(html.contains("&lt;沙箱&gt;"))
        assertTrue(html.contains("FAIL"))
    }

    @Test fun zipContainsHtmlAndMetadataButNeverSecrets() {
        val contract = TestTaskContract(
            id = "checkout",
            command = "完成支付",
            modelPolicy = TestModelPolicy("fixed", "p1"),
            sandbox = TestSandboxSpec("pay-sandbox"),
        )
        val verdict = TestVerdictEvaluator.evaluate(contract, TestEvidence())
        val zip = TestReportRenderer.zip(
            contract,
            "run-1",
            verdict,
            TestRunMetadata(appVersion = "1.2", mikaVersion = "upstream-9"),
            files = mapOf("screens/0001.jpg" to byteArrayOf(1, 2, 3), "credentials.txt" to "secret".toByteArray()),
        )
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes()
            }
        }

        assertNotNull(entries["report.html"])
        assertNotNull(entries["metadata.json"])
        assertNotNull(entries["screens/0001.jpg"])
        assertFalse(entries.containsKey("credentials.txt"))
        assertFalse(entries.values.any { String(it).contains("secret") })
    }

    @Test fun pdfExportProducesPortablePdfDocument() {
        val contract = TestTaskContract(id = "smoke", command = "检查首页", modelPolicy = TestModelPolicy("fixed", "p1"))
        val pdf = TestReportRenderer.pdf(contract, "run-1", TestVerdictEvaluator.evaluate(contract, TestEvidence()), TestRunMetadata("1", "mika"))

        assertTrue(String(pdf, Charsets.US_ASCII).startsWith("%PDF-1.4"))
        assertTrue(String(pdf, Charsets.US_ASCII).contains("%%EOF"))
    }
}

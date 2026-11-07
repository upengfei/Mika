package com.wangzan101.mika.core.automation

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TestRunMetadata(
    val appVersion: String,
    val mikaVersion: String,
    val startedAtMs: Long = 0,
)

object TestReportRenderer {
    private val json = Json { prettyPrint = true }

    fun html(
        contract: TestTaskContract,
        runId: String,
        verdict: TestVerdict,
        metadata: TestRunMetadata,
    ): String = buildString {
        appendLine("<!doctype html><html><head><meta charset=\"utf-8\"><title>${escape(contract.name)}</title>")
        appendLine("<style>body{font-family:sans-serif;max-width:960px;margin:2rem auto;padding:0 1rem} .pass{color:#167c3a}.fail{color:#b42318} li{margin:.5rem 0} img{max-width:720px;border:1px solid #ddd}</style></head><body>")
        appendLine("<h1>${escape(contract.name)}</h1>")
        appendLine("<p><strong>Run:</strong> ${escape(runId)} <strong>Result:</strong> <span class=\"${if (verdict.passed) "pass" else "fail"}\">${if (verdict.passed) "PASS" else "FAIL"}</span></p>")
        appendLine("<p>${escape(contract.command)}</p>")
        appendLine("<p>Test App ${escape(metadata.appVersion)} · Mika ${escape(metadata.mikaVersion)}</p>")
        appendLine("<h2>Assertions</h2><ul>")
        verdict.assertions.forEach { assertion ->
            val css = if (assertion.status == TestAssertionStatus.PASSED) "pass" else "fail"
            append("<li class=\"$css\"><strong>${escape(assertion.id)}</strong>: ${assertion.status.name} (${assertion.kind.name})")
            assertion.explanation?.let { append("<div>${escape(it)}</div>") }
            assertion.screenshots.forEach { screenshot -> append("<div><img src=\"${escape(screenshot)}\" alt=\"${escape(assertion.id)}\"></div>") }
            appendLine("</li>")
        }
        appendLine("</ul></body></html>")
    }

    fun zip(
        contract: TestTaskContract,
        runId: String,
        verdict: TestVerdict,
        metadata: TestRunMetadata,
        files: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val metadataJson = buildJsonObject {
            put("runId", runId)
            put("taskId", contract.id)
            put("schemaVersion", contract.schemaVersion)
            put("appVersion", metadata.appVersion)
            put("mikaVersion", metadata.mikaVersion)
            put("passed", verdict.passed)
        }.toString()
        return ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { out ->
                entry(out, "report.html", html(contract, runId, verdict, metadata).toByteArray())
                entry(out, "metadata.json", metadataJson.toByteArray())
                files.filterKeys(::isExportable).forEach { (name, bytes) -> entry(out, name, bytes) }
            }
            bytes.toByteArray()
        }
    }

    /** Minimal one-page PDF export; screenshots remain in the HTML/ZIP evidence bundle. */
    fun pdf(
        contract: TestTaskContract,
        runId: String,
        verdict: TestVerdict,
        metadata: TestRunMetadata,
    ): ByteArray {
        val lines = buildList {
            add("Mika Test Report")
            add("Task: ${contract.id}")
            add("Run: $runId")
            add("Result: ${if (verdict.passed) "PASS" else "FAIL"}")
            add("Mika: ${metadata.mikaVersion}  App: ${metadata.appVersion}")
            verdict.assertions.forEach { add("${it.id}: ${it.status.name}") }
        }.map(::pdfSafe)
        val stream = buildString {
            append("BT /F1 12 Tf 50 760 Td ")
            lines.forEachIndexed { index, line ->
                if (index > 0) append(" 0 -18 Td ")
                append("(").append(pdfEscape(line)).append(") Tj ")
            }
            append("ET")
        }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${stream.toByteArray(Charsets.US_ASCII).size} >>\nstream\n$stream\nendstream",
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        val offsets = mutableListOf(0)
        objects.forEachIndexed { index, objectText ->
            offsets += out.size()
            out.write("${index + 1} 0 obj\n$objectText\nendobj\n".toByteArray(Charsets.US_ASCII))
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray(Charsets.US_ASCII))
        offsets.drop(1).forEach { offset -> out.write("%010d 00000 n \n".format(offset).toByteArray(Charsets.US_ASCII)) }
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun isExportable(name: String): Boolean {
        val lower = name.lowercase()
        return !lower.contains("credential") && !lower.contains("secret") && !lower.contains("password") && !lower.contains("token") && !lower.contains("api-key")
    }

    private fun entry(out: ZipOutputStream, name: String, bytes: ByteArray) {
        out.putNextEntry(ZipEntry(name))
        out.write(bytes)
        out.closeEntry()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun pdfSafe(value: String): String = value.map { if (it.code in 32..126) it else '?' }.joinToString("")

    private fun pdfEscape(value: String): String = value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
}

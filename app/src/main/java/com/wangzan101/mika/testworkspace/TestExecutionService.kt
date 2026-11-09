package com.wangzan101.mika.testworkspace

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.content.Context
import com.wangzan101.mika.core.automation.TestTaskContract
import com.wangzan101.mika.core.automation.TestTaskContracts
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TriggerRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSummary
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.UUID

data class TestExecutionReceipt(
    val executionId: String,
    val taskId: String,
    val taskSchemaVersion: Int,
    val startedAtMs: Long,
)

/** Test workspace entry point; the actual phone operation remains Mika's TaskService. */
class TestExecutionService private constructor(
    private val startTask: (goal: String, conversationId: String?) -> Unit,
    private val clock: () -> Long,
    private val scheduleTask: (id: String, conversationId: String, goal: String, atMs: Long, intervalMs: Long) -> Unit,
    private val routeLookup: (caseId: String, version: Int) -> String? = { _, _ -> null },
) {
    constructor(startTask: (goal: String, conversationId: String?) -> Unit) : this(startTask, System::currentTimeMillis, { _, _, _, _, _ -> })

    constructor(
        startTask: (goal: String, conversationId: String?) -> Unit,
        scheduleTask: (id: String, conversationId: String, goal: String, atMs: Long, intervalMs: Long) -> Unit,
    ) : this(startTask, System::currentTimeMillis, scheduleTask)

    constructor(context: Context) : this(
        startTask = { goal, conversationId -> TaskService.start(context, goal, conversationId) },
        clock = System::currentTimeMillis,
        scheduleTask = { id, conversationId, goal, atMs, intervalMs ->
            TriggerRuntime.schedule(context, TriggerDefinition(id, conversationId, goal, atMs, intervalMs))
        },
        routeLookup = { caseId, version -> TestRouteBindingStore(File(context.filesDir, "test-route-bindings.json")).routeId(caseId, version) },
    )

    fun start(
        contract: TestTaskContract,
        conversationId: String? = null,
        caseId: String = contract.id,
        caseVersion: Int = 1,
    ): TestExecutionReceipt {
        TestTaskContracts.requireValid(contract)
        require(caseVersion > 0) { "caseVersion must be positive" }
        val executionId = UUID.randomUUID().toString()
        val startedAtMs = clock()
        startTask(goal(contract, caseId, caseVersion, executionId, routeLookup(caseId, caseVersion)), conversationId)
        return TestExecutionReceipt(executionId, contract.id, caseVersion, startedAtMs)
    }

    fun schedule(contract: TestTaskContract, conversationId: String, firstAtMs: Long): TestScheduleReceipt {
        TestTaskContracts.requireValid(contract)
        val intervalMs = requireNotNull(contract.schedule?.intervalMinutes) { "task has no schedule" }.toLong() * 60_000L
        val scheduleId = "test-${contract.id}"
        scheduleTask(scheduleId, conversationId, goal(contract, contract.id, contract.schemaVersion, "schedule-$scheduleId", routeLookup(contract.id, contract.schemaVersion)), firstAtMs, intervalMs)
        return TestScheduleReceipt(scheduleId, firstAtMs, intervalMs)
    }

    fun goal(contract: TestTaskContract, caseId: String = contract.id, caseVersion: Int = contract.schemaVersion, executionId: String = UUID.randomUUID().toString(), routeId: String? = null): String = buildString {
        append(contract.command)
        if (contract.steps.isNotEmpty()) {
            append("\n\nRequired steps:\n")
            contract.steps.forEachIndexed { index, step -> append("${index + 1}. ${step.instruction}\n") }
        }
        append("\nTest task id: ").append(contract.id)
        append("\nTest case id: ").append(caseId)
        append("\nTest case version: ").append(caseVersion)
        append("\nTest execution id: ").append(executionId)
        routeId?.let { append("\nTest route id: ").append(it) }
        append("\nAssertions to verify:\n")
        contract.assertions.forEach { append("- ${it.id}: ${it.description}\n") }
    }

}

data class TestScheduleReceipt(val scheduleId: String, val firstAtMs: Long, val intervalMs: Long)

/** A verification failure outranks a model's success; passing assertions cannot hide a failed task. */
fun testRunResult(summary: RunSummary): String = when {
    summary.verdict in setOf("fail", "cannot_verify") -> requireNotNull(summary.verdict)
    summary.outcome != null && summary.outcome != "succeeded" -> requireNotNull(summary.outcome)
    summary.judged != null && summary.judged != "done" -> requireNotNull(summary.judged)
    summary.verdict == "pass" -> "pass"
    else -> summary.judged ?: summary.outcome ?: "unknown"
}

/** Read-only access to Mika's persisted run evidence for the test workspace. */
class TestRunRepository(private val store: RunStore, private val archiveRoot: File? = null) {
    fun list(): List<RunSummary> {
        val archived = archiveRoot?.listFiles { file -> file.isDirectory && RUN_ID.matches(file.name) }
            .orEmpty().mapNotNull(::readSummary)
        return (store.list() + archived).distinctBy { it.id }.sortedByDescending { it.startedAtMs }
    }
    fun testRuns() = list().filter { it.goal.contains("Test case id:") || it.goal.contains("Test task id:") }
    fun identity(goal: String): TestRunIdentity? = parseTestRunIdentity(goal)
    fun events(runId: String) = store.events(runId)
    fun screenshot(runId: String, screenshotId: Int) = store.screen(runId, screenshotId)

    /** Copies a run out of Mika's bounded rolling store so an archived report survives later pruning. */
    fun archive(runId: String): File {
        val root = requireNotNull(archiveRoot) { "report archive directory is not configured" }
        val target = File(root, runId)
        val currentSummary = store.summary(runId)
        if (currentSummary != null) check(store.copyTo(runId, target)) { "Could not archive run evidence" }
        val summary = currentSummary ?: readSummary(target) ?: throw FileNotFoundException("Run is unavailable")
        val meta = ReportArchiveManifest(runId, identity(summary.goal), summary.startedAtMs)
        File(target, "manifest.json").writeText(REPORT_JSON.encodeToString(meta))
        val timeline = if (currentSummary != null) events(runId) else readEvents(target)
        File(target, "report.html").writeText(renderHtml(summary, timeline, target))
        return target
    }

    fun exportHtml(runId: String, destination: File): File {
        val directory = refreshArchive(runId)
        val html = File(directory, "report.html")
        if (!html.isFile) {
            val summary = readSummary(directory) ?: throw FileNotFoundException("Archived report is unavailable")
            html.writeText(renderHtml(summary, readEvents(directory), directory))
        }
        destination.parentFile?.mkdirs()
        html.copyTo(destination, overwrite = true)
        return destination
    }

    fun exportZip(runId: String, destination: File): File {
        val directory = refreshArchive(runId)
        if (!File(directory, "report.html").isFile) archive(runId)
        destination.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            directory.walkTopDown().filter { it.isFile && it.canonicalFile != destination.canonicalFile }.forEach { file ->
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                if (relative.split('/').any { it.contains("credential", true) || it.contains("secret", true) || it.contains("password", true) || it.contains("token", true) }) return@forEach
                zip.putNextEntry(ZipEntry(relative))
                zip.write(TestExportRedactor.bytes(relative, file.readBytes()))
                zip.closeEntry()
            }
        }
        return destination
    }

    /** A compact paginated PDF that keeps failed actions and their available screenshots in the document. */
    fun exportPdf(runId: String, destination: File): File {
        val directory = refreshArchive(runId)
        val summary = readSummary(directory) ?: throw FileNotFoundException("Archived report is unavailable")
        val timeline = readEvents(directory)
        destination.parentFile?.mkdirs()
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; isFakeBoldText = true }
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun nextPage() {
            page?.let(document::finishPage)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            y = margin
        }
        fun line(text: String, style: Paint = paint) {
            val canvas = page?.canvas ?: return
            val value = text.ifBlank { " " }
            var offset = 0
            while (offset < value.length) {
                if (y > pageHeight - margin) nextPage()
                val count = style.breakText(value, offset, value.length, true, pageWidth - margin * 2, null).coerceAtLeast(1)
                page?.canvas?.drawText(value.substring(offset, offset + count), margin, y, style)
                y += if (style === heading) 24f else 16f
                offset += count
            }
        }
        fun screenshot(id: Int) {
            val file = File(directory, "screens/%04d.jpg".format(id))
            val bitmap = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
            if (bitmap == null) { line("Screenshot unavailable: $id"); return }
            val maxWidth = pageWidth - margin.toInt() * 2
            val maxHeight = 260
            val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height, 1f)
            val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
            if (y + height > pageHeight - margin) nextPage()
            page?.canvas?.drawBitmap(bitmap, null, android.graphics.RectF(margin, y, margin + width, y + height), paint)
            y += height + 18f
            bitmap.recycle()
        }
        try {
            nextPage()
            line("Test run ${summary.id}", heading)
            line("Result: ${testRunResult(summary)} · Model: ${summary.model} · Steps: ${summary.steps}")
            line("Command: ${summary.goal}")
            line("Assertions: ${summary.criteria.joinToString("; ")}")
            line("执行与后台成本", heading)
            MpRunCosts.report(timeline).forEach { line(it) }
            timeline.forEach { event ->
                when (event) {
                    is RunEvent.Activity -> {
                        line("${event.atMs} · ${event.label}${event.target?.let { " · $it" }.orEmpty()}${if (event.failed) " · FAILED" else ""}")
                        if (event.failed) {
                            val id = timeline.filterIsInstance<RunEvent.ToolResult>().firstOrNull { it.step == event.step && it.screenshotId != null }?.screenshotId
                            if (id == null) line("Screenshot unavailable for failed step ${event.step}") else screenshot(id)
                        }
                    }
                    is RunEvent.ToolResult -> if (event.changed == false) {
                        line("${event.atMs} · ${event.tool} · ${event.resultJson} · FAILED")
                        event.screenshotId?.let(::screenshot) ?: line("Screenshot unavailable for failed step ${event.step}")
                    }
                    is RunEvent.TaskEnded -> line("${event.atMs} · ${event.outcome}: ${event.answerOrReason.orEmpty()}")
                    is RunEvent.Replay -> line("${event.atMs} · Replay ${event.step}/${event.total}: ${event.detail}")
                    is RunEvent.Verifier -> {
                        line("断言核查：${event.verdict} · ${event.detailJson}")
                        if (event.verdict != "pass") verificationScreenshot(event)?.let(::screenshot)
                    }
                    else -> Unit
                }
            }
            page?.let(document::finishPage)
            page = null
            FileOutputStream(destination).use(document::writeTo)
        } finally {
            page?.let(document::finishPage)
            document.close()
        }
        return destination
    }

    private fun refreshArchive(runId: String): File {
        // Outcome/learning events arrive after the task result. Refresh exports while the live run exists.
        if (store.summary(runId) != null) return archive(runId)
        val directory = archiveDirectory(runId)
        val summary = readSummary(directory) ?: throw FileNotFoundException("Archived report is unavailable")
        File(directory, "report.html").writeText(renderHtml(summary, readEvents(directory), directory))
        return directory
    }

    private fun archiveDirectory(runId: String): File {
        require(Regex("\\d{8}-\\d{6}-[a-z0-9]{6}").matches(runId)) { "invalid run id" }
        return File(requireNotNull(archiveRoot) { "report archive directory is not configured" }, runId)
            .takeIf { it.isDirectory } ?: throw FileNotFoundException("Archived report is unavailable")
    }

    private fun readSummary(directory: File): RunSummary? = runCatching {
        REPORT_JSON.decodeFromString<RunSummary>(File(directory, "run.json").readText())
    }.getOrNull()

    private fun readEvents(directory: File): List<RunEvent> = File(directory, "events.jsonl").takeIf { it.isFile }
        ?.readLines()?.filter(String::isNotBlank)?.mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }.orEmpty()

    private fun renderHtml(summary: RunSummary, events: List<RunEvent>, directory: File): String {
        val rows = events.joinToString("\n") { event ->
            val detail = when (event) {
                is RunEvent.Activity -> "${event.label}${event.target?.let { " · $it" }.orEmpty()}${if (event.failed) " · FAILED" else ""}"
                is RunEvent.Replay -> "Replay ${event.step}/${event.total}: ${event.detail}"
                is RunEvent.ModelReply -> "Model ${event.model.orEmpty()} · step ${event.step} · tokens ${event.inputTokens ?: "?"}/${event.outputTokens ?: "?"}"
                is RunEvent.ToolResult -> "${event.tool}: ${event.resultJson}"
                is RunEvent.TaskEnded -> "${event.outcome}: ${event.answerOrReason.orEmpty()}"
                is RunEvent.Criteria -> "Assertions: ${event.criteria.joinToString("; ")}"
                is RunEvent.Verifier -> "断言核查：${event.verdict} · ${event.detailJson}"
                is RunEvent.State -> "${event.name} · ${event.detail}"
                else -> event::class.simpleName.orEmpty()
            }
            val failure = event is RunEvent.Activity && event.failed || event is RunEvent.ToolResult && event.changed == false ||
                event is RunEvent.Verifier && event.verdict != "pass"
            val screenshotId = when (event) {
                is RunEvent.ToolResult -> event.screenshotId
                is RunEvent.Activity -> events.filterIsInstance<RunEvent.ToolResult>().firstOrNull { it.step == event.step && it.screenshotId != null }?.screenshotId
                is RunEvent.Verifier -> verificationScreenshot(event)
                else -> null
            }
            val evidence = if (!failure) "" else {
                val screenshot = screenshotId?.let { File(directory, "screens/%04d.jpg".format(it)) }
                if (screenshot?.isFile == true) {
                    val id = requireNotNull(screenshotId)
                    val base64 = Base64.getEncoder().encodeToString(screenshot.readBytes())
                    "<a href=\"screens/%04d.jpg\"><img alt=\"Failure screenshot\" src=\"data:image/jpeg;base64,%s\"></a>".format(id, base64)
                } else "Screenshot unavailable in run evidence"
            }
            "<tr class=\"${if (failure) "failure" else ""}\"><td>${event.atMs}</td><td>${event::class.simpleName.orEmpty()}</td><td>${TestExportRedactor.text(detail).html()}</td><td>$evidence</td></tr>"
        }
        val verdict = testRunResult(summary)
        return """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Test run ${summary.id}</title>
<style>body{font:14px system-ui,sans-serif;margin:24px;color:#202124}table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:8px;border-bottom:1px solid #ddd;vertical-align:top}.failure{background:#fff0ee}img{max-width:480px}</style>
<h1>Test run ${summary.id.html()}</h1><p>Result: ${TestExportRedactor.text(verdict).html()} · Model: ${TestExportRedactor.text(summary.model).html()} · Steps: ${summary.steps}</p>
<h2>Command</h2><pre>${TestExportRedactor.text(summary.goal).html()}</pre><h2>Assertions</h2><ul>${summary.criteria.joinToString("") { "<li>${TestExportRedactor.text(it).html()}</li>" }}</ul>
<h2>执行与后台成本</h2><ul>${MpRunCosts.report(events).joinToString("") { "<li>${it.html()}</li>" }}</ul>
<h2>Timeline</h2><table><thead><tr><th>Time</th><th>Event</th><th>Details</th><th>Evidence</th></tr></thead><tbody>$rows</tbody></table></html>"""
    }

    private fun String.html(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun verificationScreenshot(event: RunEvent.Verifier): Int? = runCatching {
        ((Json.parseToJsonElement(event.detailJson) as? JsonObject)?.get("screenshotId") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    }.getOrNull()

    private companion object {
        val RUN_ID = Regex("\\d{8}-\\d{6}-[a-z0-9]{6}")
        val REPORT_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; explicitNulls = false }
    }
}

@Serializable
private data class ReportArchiveManifest(val runId: String, val identity: TestRunIdentity?, val startedAtMs: Long)

@Serializable
data class TestRunIdentity(val caseId: String, val caseVersion: Int?, val executionId: String?)

internal fun parseTestRunIdentity(goal: String): TestRunIdentity? {
    val caseId = Regex("Test case id: ([a-z0-9-]+)").find(goal)?.groupValues?.get(1)
        ?: Regex("Test task id: ([a-z0-9-]+)").find(goal)?.groupValues?.get(1)
        ?: return null
    return TestRunIdentity(
        caseId = caseId,
        caseVersion = Regex("Test case version: (\\d+)").find(goal)?.groupValues?.get(1)?.toIntOrNull(),
        executionId = Regex("(?m)^Test execution id: ([A-Za-z0-9-]+)$").find(goal)?.groupValues?.get(1),
    )
}

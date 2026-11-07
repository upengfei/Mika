package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.automation.TestAssertionSpec
import com.wangzan101.mika.core.automation.TestAssertionKind
import com.wangzan101.mika.core.automation.TestModelPolicy
import com.wangzan101.mika.core.automation.TestStepSpec
import com.wangzan101.mika.core.automation.TestTaskContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class TestExecutionServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun startsTaskServiceWithContractCommandAndSteps() {
        var receivedGoal = ""
        var receivedConversation: String? = null
        val service = TestExecutionService { goal, conversation ->
            receivedGoal = goal
            receivedConversation = conversation
        }
        val contract = TestTaskContract(
            id = "checkout",
            command = "完成支付测试",
            steps = listOf(TestStepSpec("open", "打开商品详情")),
            assertions = listOf(TestAssertionSpec("payment", TestAssertionKind.HARD, "交易成功")),
            modelPolicy = TestModelPolicy("fixed", "p1"),
        )

        val receipt = service.start(contract, "conversation-1")

        assertEquals("conversation-1", receivedConversation)
        assertTrue(receivedGoal.contains("完成支付测试"))
        assertTrue(receivedGoal.contains("打开商品详情"))
        assertTrue(receivedGoal.contains("Test case id: checkout"))
        assertTrue(receivedGoal.contains("Test case version: 1"))
        assertTrue(receivedGoal.contains("Test execution id: ${receipt.executionId}"))
        assertEquals("checkout", receipt.taskId)
        assertTrue(receipt.executionId.isNotBlank())
    }

    @Test fun schedulesContractUsingConfiguredInterval() {
        var scheduled: List<Any?> = emptyList()
        val service = TestExecutionService(
            startTask = { _, _ -> },
            scheduleTask = { id, conversation, goal, at, interval -> scheduled = listOf(id, conversation, goal, at, interval) },
        )
        val contract = TestTaskContract(
            id = "smoke",
            command = "检查首页",
            modelPolicy = TestModelPolicy("fixed", "p1"),
            schedule = com.wangzan101.mika.core.automation.TestScheduleSpec(15),
        )

        val receipt = service.schedule(contract, "conversation-1", 1_000L)

        assertEquals("test-smoke", scheduled[0])
        assertEquals("conversation-1", scheduled[1])
        assertEquals(1_000L, scheduled[3])
        assertEquals(900_000L, scheduled[4])
        assertTrue((scheduled[2] as String).contains("Test case id: smoke"))
        assertEquals("test-smoke", receipt.scheduleId)
    }

    @Test fun extractsCaseIdentityFromPersistedRunGoal() {
        val identity = parseTestRunIdentity("Open shop\nTest case id: checkout\nTest case version: 4\nTest execution id: 6c0b7c00-2e1a-4c16-9f0f-402568ec7a71")
        assertEquals("checkout", identity?.caseId)
        assertEquals(4, identity?.caseVersion)
        assertEquals("6c0b7c00-2e1a-4c16-9f0f-402568ec7a71", identity?.executionId)
    }

    @Test fun archivedReportAndZipKeepEvidenceAfterRunStoreRotation() {
        var clock = 1_760_000_000_000L
        val store = com.wangzan101.mika.record.RunStore(tmp.root.resolve("runs"), { null }, 1, { clock }, { it })
        val handle = store.open("Test case id: checkout\nTest case version: 3")
        handle.event(com.wangzan101.mika.core.record.RunEvent.TaskStarted(clock, "Test case id: checkout", "model"))
        handle.event(com.wangzan101.mika.core.record.RunEvent.Activity(clock + 1, 1, "act", "Tap <Buy>", failed = true))
        handle.event(com.wangzan101.mika.core.record.RunEvent.ToolResult(clock + 2, 1, "phone_tap", "{\"error\":\"not found\",\"sandboxPassword\":\"sandbox-secret-123\"}", false, 7))
        handle.media(7, byteArrayOf(1, 2, 3))
        handle.event(com.wangzan101.mika.core.record.RunEvent.TaskEnded(clock + 3, "failed", "Could not find <Buy>"))
        handle.close()
        val repository = TestRunRepository(store, File(tmp.root, "reports"))

        val archived = repository.archive(handle.runId)
        val html = File(archived, "report.html").readText()
        clock += 1_000
        store.open("later").close()
        val zip = repository.exportZip(handle.runId, File(tmp.root, "checkout.zip"))
        val entries = ZipInputStream(zip.inputStream()).use { input ->
            buildMap { while (true) { val entry = input.nextEntry ?: break; put(entry.name, input.readBytes()) } }
        }

        assertTrue(html.contains("&lt;Buy&gt;"))
        assertFalse(html.contains("<Buy>"))
        assertTrue(html.contains("screens/0007.jpg"))
        assertTrue(html.contains("data:image/jpeg;base64,AQID"))
        assertTrue(entries.containsKey("run.json"))
        assertTrue(entries.containsKey("events.jsonl"))
        assertArrayEquals(byteArrayOf(1, 2, 3), entries["screens/0007.jpg"])
        val exportedText = entries.filterKeys { it !in setOf("screens/0007.jpg") }.values
            .joinToString("\n") { it.toString(Charsets.UTF_8) }
        assertFalse("sandbox secrets must not leave in report exports", exportedText.contains("sandbox-secret-123"))
        assertTrue("report archive survives RunStore pruning", archived.resolve("events.jsonl").isFile)
    }
}

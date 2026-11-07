package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.automation.TestAssertionSpec
import com.wangzan101.mika.core.automation.TestAssertionKind
import com.wangzan101.mika.core.automation.TestModelPolicy
import com.wangzan101.mika.core.automation.TestStepSpec
import com.wangzan101.mika.core.automation.TestTaskContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestExecutionServiceTest {
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
        assertEquals("test-smoke", receipt.scheduleId)
    }
}

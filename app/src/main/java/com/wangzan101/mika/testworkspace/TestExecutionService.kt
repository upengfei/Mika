package com.wangzan101.mika.testworkspace

import android.content.Context
import com.wangzan101.mika.core.automation.TestTaskContract
import com.wangzan101.mika.core.automation.TestTaskContracts
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TriggerRuntime
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
    )

    fun start(contract: TestTaskContract, conversationId: String? = null): TestExecutionReceipt {
        TestTaskContracts.requireValid(contract)
        startTask(contract.toGoal(), conversationId)
        return TestExecutionReceipt(UUID.randomUUID().toString(), contract.id, contract.schemaVersion, clock())
    }

    fun schedule(contract: TestTaskContract, conversationId: String, firstAtMs: Long): TestScheduleReceipt {
        TestTaskContracts.requireValid(contract)
        val intervalMs = requireNotNull(contract.schedule?.intervalMinutes) { "task has no schedule" }.toLong() * 60_000L
        val scheduleId = "test-${contract.id}"
        scheduleTask(scheduleId, conversationId, contract.toGoal(), firstAtMs, intervalMs)
        return TestScheduleReceipt(scheduleId, firstAtMs, intervalMs)
    }

    private fun TestTaskContract.toGoal(): String = buildString {
        append(command)
        if (steps.isNotEmpty()) {
            append("\n\nRequired steps:\n")
            steps.forEachIndexed { index, step -> append("${index + 1}. ${step.instruction}\n") }
        }
        append("\nTest task id: ").append(id)
        append("\nAssertions to verify:\n")
        assertions.forEach { append("- ${it.id}: ${it.description}\n") }
    }
}

data class TestScheduleReceipt(val scheduleId: String, val firstAtMs: Long, val intervalMs: Long)

/** Read-only access to Mika's persisted run evidence for the test workspace. */
class TestRunRepository(private val store: RunStore) {
    fun list() = store.list()
    fun events(runId: String) = store.events(runId)
    fun screenshot(runId: String, screenshotId: Int) = store.screen(runId, screenshotId)
}

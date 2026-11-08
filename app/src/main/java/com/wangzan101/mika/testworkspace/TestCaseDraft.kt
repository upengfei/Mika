package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.automation.TestModelPolicy
import com.wangzan101.mika.core.automation.TestStepSpec
import com.wangzan101.mika.core.automation.TestTaskContract
import com.wangzan101.mika.core.record.RunEvent

/** Builds a replayable case from successful UI actions without copying screen contents or typed values. */
object TestCaseDraft {
    fun fromRun(goal: String, activities: List<RunEvent.Activity>, id: String): TestTaskContract? {
        val actions = activities.filter { it.kind == "act" && !it.failed }
        if (goal.isBlank() || actions.isEmpty()) return null
        val safeId = id.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)
            .ifBlank { "captured-run" }
        return TestTaskContract(
            id = safeId,
            name = goal.trim().take(80),
            command = goal.trim(),
            steps = actions.mapIndexed { index, action ->
                val target = action.target?.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
                TestStepSpec("step-${index + 1}", "${action.label}$target")
            },
            modelPolicy = TestModelPolicy("mika-current", "captured-run-v1"),
        )
    }
}

package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestCaseDraftTest {
    @Test
    fun capturesOnlySuccessfulUiActionsAndUsesSafeCaseId() {
        val activities = listOf(
            RunEvent.Activity(1, 1, "observe", "read_screen"),
            RunEvent.Activity(2, 2, "act", "tap", "Sign in"),
            RunEvent.Activity(3, 3, "act", "type_text", failed = true),
            RunEvent.Activity(4, 4, "act", "tap", "Continue"),
        )

        val contract = TestCaseDraft.fromRun("Sign in to the app", activities, "Captured Run 123")!!

        assertEquals("captured-run-123", contract.id)
        assertEquals("Sign in to the app", contract.command)
        assertEquals(listOf("tap Sign in", "tap Continue"), contract.steps.map { it.instruction })
        assertEquals(listOf("step-1", "step-2"), contract.steps.map { it.id })
    }

    @Test
    fun doesNotCreateCaseWithoutSuccessfulUiActions() {
        assertNull(TestCaseDraft.fromRun("What time is it?", emptyList(), "captured-run"))
        assertNull(TestCaseDraft.fromRun("Open the app", listOf(RunEvent.Activity(1, 1, "act", "tap", failed = true)), "captured-run"))
    }
}

package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.automation.TestAssertionKind
import com.wangzan101.mika.core.automation.TestAssertionSpec
import com.wangzan101.mika.core.automation.TestModelPolicy
import com.wangzan101.mika.core.automation.TestStepSpec
import com.wangzan101.mika.core.automation.TestTaskContract
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.observation.*
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSummary
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.verify.Evidence
import com.wangzan101.mika.core.verify.EvidenceLedger
import com.wangzan101.mika.replay.FileRouteStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TestRouteLearningTest {
    @get:Rule val tmp = TemporaryFolder()
    private val sourceGoal = "在备忘录里写今天天气不错"
    private val sourceId = "20261109-120000-abcdef"
    private val timeline = mutableListOf<RunEvent>()
    private val root get() = File(tmp.root, "cases")
    private val bindings get() = TestRouteBindingStore(File(tmp.root, "bindings.json"))
    private val routes get() = FileRouteStore(File(tmp.root, "routes"))
    private fun repository() = TestCaseRepository(root)
    private fun captured() = repository().createCase(TestTaskContract(
        id = "captured", command = sourceGoal, steps = listOf(TestStepSpec("write", "写今天天气不错")), modelPolicy = TestModelPolicy("m", "p"),
    ))
    private fun sourceEvents(goal: String = sourceGoal): List<RunEvent> = listOf(
        RunEvent.TaskStarted(1, goal, "m"),
        RunEvent.ActionTarget(2, 1, "phone_launch_app", """{"package":"com.notes"}""", null, Anchors(null, emptyList()), Anchors("com.notes", listOf("备忘录")), true),
        RunEvent.ActionTarget(3, 2, "phone_type_text", """{"text":"今天天气不错"}""", null, Anchors("com.notes", listOf("新建")), Anchors("com.notes", listOf("完成")), true, "typed"),
        RunEvent.ToolResult(3, 2, "phone_type_text", """{"ok":true,"secret":false,"verification":"match"}""", true, null, callId = "typed"),
        RunEvent.TaskEnded(4, "succeeded", "已写好"),
        RunEvent.OutcomeJudged(5, "done", "完成"),
    )
    private fun learning() = TestRouteLearning(::repository, bindings, routes,
        summary = { RunSummary(sourceId, sourceGoal, startedAtMs = 1, outcome = "succeeded") },
        events = { timeline.toList() }, append = { _, event -> timeline += event }, clock = { 10L },
    )
    private val noModel = object : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 10000, reasoning = false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("Pinned case replay must not call route-selection model")
    }

    @Test fun savingBeforeOutcomeJudgingBindsLaterAndReplaysWithNoRouteSelectionCall() = runTest {
        val record = captured()
        timeline += sourceEvents().filterNot { it is RunEvent.OutcomeJudged }
        learning().capture(record.id, 1, sourceId)
        assertNull(bindings.routeId(record.id, 1))
        timeline += RunEvent.OutcomeJudged(5, "done", "完成")
        learning().completed(sourceGoal, sourceId, timeline.toList())
        val routeId = requireNotNull(bindings.routeId(record.id, 1))
        val goal = TestExecutionService { _, _ -> }.goal(record.current.contract, record.id, 1, routeId = routeId)
        val choice = RouteMatcher(noModel, routes, null).choose(goal)
        assertNotNull(choice)
        assertEquals(listOf("今天天气不错"), choice!!.slots)
        assertEquals("{1}", kotlinx.serialization.json.Json.parseToJsonElement(choice.route.steps.last().argsJson).let { (it as kotlinx.serialization.json.JsonObject)["text"] }.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(TestCaseReplayState.READY, testCaseReplayState(record, bindings, routes))
        assertTrue(bindings.captures().isEmpty())
    }

    @Test fun savingAfterOutcomeJudgingBindsImmediatelyAndDoesNotChangeSourceRoute() {
        timeline += sourceEvents()
        learning().completed(sourceGoal, sourceId, timeline.toList())
        val original = routes.all().single()
        val record = captured()
        learning().capture(record.id, 1, sourceId)
        assertNotNull(bindings.routeId(record.id, 1))
        assertTrue(routes.all().any { it == original })
        assertEquals(2, routes.count())
        learning().reconcile(sourceId)
        assertEquals(2, routes.count())
    }

    @Test fun aNewRevisionDoesNotInheritCapturedRouteAndDisabledRoutesAreNotReady() {
        val record = captured()
        timeline += sourceEvents()
        learning().capture(record.id, 1, sourceId)
        learning().completed(sourceGoal, sourceId, timeline.toList())
        val routeId = requireNotNull(bindings.routeId(record.id, 1))
        routes.update(routeId) { it.copy(disabled = true) }
        assertEquals(TestCaseReplayState.PENDING, testCaseReplayState(record, bindings, routes))
        val revised = repository().revise(record.id, record.current.contract.copy(command = "打开设置"))
        assertNull(bindings.routeId(record.id, revised.currentVersion))
        assertEquals(TestCaseReplayState.SAVED, testCaseReplayState(revised, bindings, routes))
    }

    @Test fun unsafePartialAndEvidenceMissingRunsStaySavedWithoutBinding() {
        val record = captured()
        timeline += sourceEvents() + RunEvent.Barrier(4, "payment")
        learning().capture(record.id, 1, sourceId)
        learning().completed(sourceGoal, sourceId, timeline.toList())
        assertNull(bindings.routeId(record.id, 1))
        timeline.clear()
        timeline += sourceEvents().filterNot { it is RunEvent.OutcomeJudged }
        timeline += RunEvent.OutcomeJudged(5, "partial", "未完成")
        timeline += RunEvent.State(6, TestRouteLearning.SOURCE_READY, "")
        learning().reconcile(sourceId)
        assertNull(bindings.routeId(record.id, 1))
        assertNotNull(repository().case(record.id))
    }

    @Test fun newHardAssertionsRequireTheirOwnVerificationBeforeRouteBinding() = runTest {
        val contract = TestTaskContract(id = "checked", command = sourceGoal, targetApp = "com.notes",
            assertions = listOf(TestAssertionSpec("saved", TestAssertionKind.HARD, "保存成功", expectedText = "保存成功")), modelPolicy = TestModelPolicy("m", "p"))
        repository().createCase(contract)
        val goal = TestExecutionService { _, _ -> }.goal(contract, "checked", 1)
        val events = sourceEvents(goal)
        learning().completed(goal, sourceId, events)
        assertNull(bindings.routeId("checked", 1))
        val evidence = Evidence(listOf(Observation(ObsKind.Read, "保存成功", place = Place.app("com.notes"))), null, EvidenceLedger(), emptyList(), false)
        val checked = TestAssertionVerifier({ _, _, _, _ -> error("Hard assertions use no model") }).verify(contract, "checked", 1, goal, "完成", evidence)
        learning().completed(goal, sourceId, events + checked.event)
        assertNotNull(bindings.routeId("checked", 1))
    }

    @Test fun persistedCaptureRequestSurvivesStoreRecreation() {
        val record = captured()
        learning().capture(record.id, 1, sourceId)
        assertEquals(sourceId, bindings.captures().single().sourceRunId)
        timeline += sourceEvents()
        learning().completed(sourceGoal, sourceId, timeline.toList())
        assertNotNull(bindings.routeId(record.id, 1))
    }

    @Test fun savingACaseCannotResurrectAnAlreadyDisabledSourceRoute() {
        timeline += sourceEvents()
        learning().completed(sourceGoal, sourceId, timeline.toList())
        val original = routes.all().single()
        routes.update(original.id) { it.copy(disabled = true) }
        val record = captured()
        learning().capture(record.id, 1, sourceId)
        assertNull(bindings.routeId(record.id, 1))
        assertEquals(1, routes.count())
    }
}

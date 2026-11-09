package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.agent.*
import com.wangzan101.mika.core.automation.*
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.knowledge.*
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.*
import com.wangzan101.mika.core.skills.*
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.usage.*
import com.wangzan101.mika.core.memory.MemoryExtractor
import com.wangzan101.mika.task.MapSkills
import com.wangzan101.mika.task.RunAftermath
import com.wangzan101.mika.task.RunLearning
import com.wangzan101.mika.task.TextModel
import com.wangzan101.mika.task.memoryStore
import com.wangzan101.mika.settings.MemorySwitches
import com.wangzan101.mika.i18n.ChineseTexts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MpEfficientExecutionTest {
    @get:Rule val texts = ChineseTexts()
    private val tools = knownTools()
    private val assertion = TestAssertionSpec("visible", TestAssertionKind.HARD, "当前页面状态", expectedText = "状态：成功")
    private val contract = TestTaskContract(id = "test", command = "打开测试", targetApp = "com.test", assertions = listOf(assertion))
    private val goal = "打开测试\nTest case id: test\nTest case version: 1\nTest route id: r1"
    private val route = Route("r1", "打开测试", 0, listOf("com.test"), mapOf("com.test" to "1#1"),
        listOf(RouteStep("phone_launch_app", "{}", null, Anchors("com.test", emptyList()), Anchors("com.test", emptyList()))), lastUsedMs = 1)

    private fun policy(spec: TestTaskContract? = contract, r: Route = route, version: String? = "1#1",
        verify: suspend (TaskState.Succeeded) -> TaskState.Succeeded = { it.copy(verified = true, reviewed = true) }) =
        TestFastReplay(goal, spec, "r1", r, { version }, verify)

    @Test fun onlyFreshExplicitHardAssertionCasesCanFinishLocally() = runBlocking {
        assertTrue(policy().directReplay(goal))
        assertFalse(policy(contract.copy(assertions = emptyList())).directReplay(goal))
        assertFalse(policy(contract.copy(assertions = listOf(assertion.copy(required = false)))).directReplay(goal))
        assertFalse(policy(contract.copy(assertions = listOf(assertion.copy(expectedText = null)))).directReplay(goal))
        assertFalse(policy(contract.copy(assertions = listOf(assertion.copy(kind = TestAssertionKind.SOFT)))).directReplay(goal))
        assertFalse(policy(contract.copy(targetApp = null)).directReplay(goal))
        assertFalse(policy(version = null).directReplay(goal))
        assertFalse(policy(version = "1#2").directReplay(goal))
        assertFalse(policy(r = route.copy(appVersions = emptyMap())).directReplay(goal))
        assertFalse(policy(r = route.copy(disabled = true)).directReplay(goal))
        assertFalse(policy().directReplay(goal + "额外要求"))
        assertTrue(policy(version = "2").allowReplay("普通 Mika 任务", route))
        assertFalse(policy(version = "2").allowReplay(goal, route))
    }

    @Test fun completionUsesFreshVerificationAndOutcomeOnlyAfterAcceptance() = runBlocking {
        var checks = 0
        val p = policy(verify = { checks++; it.copy(verified = true, reviewed = true) })
        val end = p.completeReplay(goal, route)!!
        assertEquals(1, checks)
        assertTrue(end.verified)
        assertNull(p.localOutcome(end))
        p.acceptedCompletion(end)
        assertEquals(RunOutcome.Done, p.localOutcome(end)?.outcome)
        assertNull(p.localOutcome(end.copy(answer = "其他回答")))
    }

    @Test fun failedAndUnavailableEvidenceNeverBecomeSuccessOrTriggerAnotherWrite() = runBlocking {
        for (unavailable in listOf(false, true)) {
            var checks = 0
            val p = policy(verify = { checks++; if (unavailable) error("unavailable") else it.copy(verified = false, reviewed = true, unconfirmed = listOf("visible failed")) })
            val end = p.completeReplay(goal, route)!!
            p.acceptedCompletion(end)
            assertEquals(1, checks)
            assertFalse(end.verified)
            assertEquals(RunOutcome.Partial, p.localOutcome(end)?.outcome)
        }
    }

    private fun skill(name: String, display: String = name, description: String = display, extra: String = "", status: SkillStatus = SkillStatus.active) =
        PersonalSkill(name, "---\nname: $name\ndisplay: $display\ndescription: $description\n$extra---\n## 入口\n打开 App", status, "old-run", null, 1, emptyList())

    @Test fun personalIndexIsBoundedAndFullSearchStillFindsUnrecommendedSkills() {
        val skills = MapSkills(*(1..30).map { skill("search-$it", "搜索商品", "搜索商品" + "描述".repeat(100)) }.toTypedArray(), skill("notes", "记录笔记"))
        val catalog = GuideCatalog.withPersonal(GuideCatalog.loadBundled(), skills)
        val index = MpGuideIndex.index(catalog, "搜索商品")
        val rows = index.lines().filter { it.startsWith("- personal:") }
        assertEquals(3, rows.size)
        assertTrue(rows.sumOf { it.length + 1 } <= MpGuideIndex.PERSONAL_BUDGET)
        assertFalse(index.contains("personal:notes"))
        assertTrue(index.contains("skill_search"))
        assertEquals("notes", catalog.find("记录笔记", among = setOf(PERSONAL_KIND)).single().name)
        assertTrue(catalog.items.filter { it.kind == KnowledgeIndex.SKILL }.all { "skill:${it.name}" in index })
    }

    @Test fun appPackageAssociationsRecommendSkillsWithoutMatchingTheirNames() {
        val catalog = GuideCatalog.withPersonal(GuideCatalog.loadBundled(), MapSkills(skill("known-flow", "已验证路线", extra = "apps: [com.example.shop]\n")))
        assertTrue(MpGuideIndex.index(catalog, "在示例商城检查页面", listOf("com.example.shop" to "示例商城")).contains("personal:known-flow"))
        assertFalse(MpGuideIndex.index(catalog, "打开别的应用", listOf("com.example.shop" to "示例商城")).contains("personal:known-flow"))
    }

    private fun input(run: String = "run-1", changed: Boolean = true, action: String = "phone_tap"): DraftInput {
        val events = listOf(RunEvent.TaskStarted(1, "打开页面", "m"),
            RunEvent.ToolResult(2, 1, action, "{\"ok\":true,\"changed\":$changed}", changed, null), RunEvent.TaskEnded(3, "succeeded", "完成"))
        return DraftInput(run, "打开页面", "完成", emptyList(), events, setOf(action), tools)
    }

    @Test fun oneEffectiveActionIsEligibleButZeroEffectsAndErrorsAreNot() {
        val one = input()
        assertNull(Eligibility.check(one.events, null, tools, done = true, minimumActions = MpSkillLearning.minimumActions))
        assertEquals("too_few_actions", Eligibility.check(one.events, null, tools, done = true))
        assertTrue(MpSkillLearning.shouldDraft(one, MapSkills()))
        assertFalse(MpSkillLearning.shouldDraft(input(changed = false), MapSkills()))
        assertFalse(MpSkillLearning.shouldDraft(input(changed = false, action = "phone_observe"), MapSkills()))
        val bad = one.copy(events = listOf(RunEvent.ToolResult(2, 1, "phone_tap", "{\"ok\":false}", true, null)))
        assertFalse(MpSkillLearning.shouldDraft(bad, MapSkills()))
    }

    @Test fun localDedupSkipsRepeatedWorkButAllowsAChangedWorkflow() {
        val one = input()
        val saved = MpSkillLearning.decorate(one, skill("open-page"))
        val skills = MapSkills(saved)
        assertFalse(MpSkillLearning.shouldDraft(input(run = "run-2"), skills))
        assertTrue(MpSkillLearning.shouldDraft(input(run = "run-2").copy(goal = "提交订单"), skills))
        skills.remove(saved.name)
        skills.putPending("draft", saved)
        assertFalse(MpSkillLearning.shouldDraft(input(run = "run-3"), skills))
        skills.reject("draft")
        assertTrue(MpSkillLearning.shouldDraft(input(run = "run-3"), skills))
    }

    @Test fun shortRunProducesAUserConfirmableDraftOnceThroughTheRealLearningPipeline() = runBlocking {
        val skills = MapSkills()
        val md = "---\nname: open-test\ndisplay: 打开页面\ndescription: 打开测试页面并核查\n---\n" +
            "## 入口\n打开 App\n## 步骤\n1. 点入口\n## 容易出错的地方\n页面变化\n## 怎样算办成\n结果可见\n## 什么时候停下交给用户\n需要登录"
        val model = TextModel(md)
        val learning = RunLearning(MemorySwitches(learnSkills = true), null, skills,
            GuideCatalog.withPersonal(GuideCatalog.loadBundled(), skills), null, SkillDrafter(model), { "draft-1" }, skillPolicy = MpSkillLearning)
        fun after(id: String) = input(id).let { i -> RunAftermath("chat", id, i.goal, TaskState.Succeeded(i.answer), emptyList(), emptyList(), emptyList(),
            i.toolNames, i.tools, events = { i.events }, outcome = RunOutcome.Done) }
        val notes = learning.learn(after("run-1"))
        assertEquals(1, model.requests.size)
        assertEquals(SkillStatus.pending, skills.drafts["draft-1"]?.status)
        assertEquals("draft-1", notes.skillNotes.single().draftId)
        assertTrue(skills.list().isEmpty())
        learning.learn(after("run-2"))
        assertEquals(1, model.requests.size)
    }

    @Test fun phaseAccountingIncludesMeteredBackgroundCallsAndMarksMissingUsage() = runBlocking {
        val events = mutableListOf<RunEvent>()
        val source = UsageSource("p", "p", "model", UsagePurpose.Main)
        val model = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 1000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, Usage(100, 20, 30))
        }.metered(source, UsageSink { _, _ -> })
        MpRunCosts.measure("learning", write = { events += it }) { model.complete("s", emptyList(), emptyList()) }
        val row = MpRunCosts.rows(events).single()
        assertEquals(1, row.calls)
        assertEquals(100L, row.inputTokens)
        assertEquals(20L, row.outputTokens)
        assertEquals(30L, row.cachedTokens)
        assertEquals(0, row.unreported)
        assertTrue(MpRunCosts.report(events).any { "执行与断言核查" in it && "未知" in it })
        try { MpRunCosts.measure("outcome", write = { events += it }) { throw IllegalStateException("fail") } } catch (_: IllegalStateException) { }
        assertEquals("interrupted", MpRunCosts.rows(events).first { it.phase == "outcome" }.status)
        val noUsage = object : ChatModel by model {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = ModelReply(emptyList(), StopReason.EndTurn, null)
        }.metered(source, UsageSink { _, _ -> })
        MpRunCosts.measure("execution", write = { events += it }) { noUsage.complete("s", emptyList(), emptyList()) }
        assertEquals(1, MpRunCosts.rows(events).first { it.phase == "execution" }.unreported)
        assertTrue(MpRunCosts.report(events).any { "总 token 未知" in it })
    }

    @Test fun onlyCompleteUnchangedSuccessfulReplaySkipsExtraction() = runBlocking {
        val replay = (1..2).map { RunEvent.Replay(it.toLong(), "r1", it, 2, true, "") }
        val steer = RunEvent.State(3, "steer_received", "s1")
        val newAction = RunEvent.ToolResult(4, 3, "phone_tap", "{\"ok\":true}", true, null)
        val samples = listOf(
            Triple(replay, RunOutcome.Done, false),
            Triple(replay.take(1), RunOutcome.Done, true),
            Triple(replay + RunEvent.Replay(3, "r1", 2, 2, false, "diverged"), RunOutcome.Done, true),
            Triple(replay, RunOutcome.Partial, true),
            Triple(replay + steer, RunOutcome.Done, true),
            Triple(replay + newAction, RunOutcome.Done, true),
        )
        for ((events, outcome, extracts) in samples) {
            val model = TextModel("{\"ops\":[]}")
            val learning = RunLearning(MemorySwitches(), memoryStore(), MapSkills(), GuideCatalog.loadBundled(),
                MemoryExtractor(model), null, { "id" }, skillPolicy = MpSkillLearning)
            learning.learn(RunAftermath("chat", "run", "打开页面", TaskState.Succeeded("完成"), listOf("打开页面"),
                emptyList(), emptyList(), emptySet(), tools, events = { events }, outcome = outcome))
            assertEquals("$events / $outcome", if (extracts) 1 else 0, model.requests.size)
        }
    }
}

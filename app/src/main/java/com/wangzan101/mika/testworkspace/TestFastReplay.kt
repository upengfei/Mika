package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.agent.*
import com.wangzan101.mika.core.automation.*
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.replay.Route
import kotlinx.coroutines.CancellationException

/** One instance per run: immutable admission, fresh version checks, and evidence-based completion. */
class TestFastReplay(
    private val goal: String,
    private val contract: TestTaskContract?,
    private val boundRouteId: String?,
    private val boundRoute: Route?,
    private val currentVersion: (String) -> String?,
    private val verify: suspend (TaskState.Succeeded) -> TaskState.Succeeded,
) : RunExtensions {
    var accepted: TaskState.Succeeded? = null
        private set
    private var checked: TaskState.Succeeded? = null

    override fun guideIndex(catalog: GuideCatalog, task: String, installed: List<Pair<String, String>>) = MpGuideIndex.index(catalog, task, installed)

    private fun versionsMatch(route: Route): Boolean = route.packages.isNotEmpty() && route.packages.all { p ->
        val old = route.appVersions[p]
        !old.isNullOrBlank() && runCatching { currentVersion(p) }.getOrNull() == old
    }

    override suspend fun allowReplay(task: String, route: Route): Boolean =
        parseTestRunIdentity(task) == null || versionsMatch(route)

    private fun eligible(task: String, route: Route?): Boolean {
        val spec = contract ?: return false
        if (task != goal || route == null || route.disabled || route.id != boundRouteId || route.steps.isEmpty()) return false
        if (spec.targetApp.isNullOrBlank() || spec.targetApp !in route.packages) return false
        if (spec.assertions.none { it.required && it.kind == TestAssertionKind.HARD }) return false
        if (spec.assertions.any { it.kind != TestAssertionKind.HARD || it.expectedText.isNullOrBlank() }) return false
        return versionsMatch(route)
    }

    override suspend fun directReplay(task: String): Boolean = eligible(task, boundRoute)

    override suspend fun completeReplay(task: String, route: Route): TaskState.Succeeded? {
        if (!eligible(task, route)) return null
        val end = try {
            verify(TaskState.Succeeded("已按保存路线完成操作，正在核查测试结果。", verified = false, reviewed = false))
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            TaskState.Succeeded("操作已完成，断言证据暂不可用。", verified = false, reviewed = true,
                note = "核查不可用，结果待验证", unconfirmed = listOf("断言核查不可用"))
        }
        // A failed/unknown assertion ends the run with evidence; do not repeat already performed writes.
        return end.copy(answer = if (end.verified && end.reviewed && end.unconfirmed.isEmpty())
            "测试通过：已按保存路线完成操作，当前页面的全部必需断言通过。" else
            "操作已执行，测试断言未全部通过或证据不足，请查看报告与截图。",
            note = end.note ?: "由当前页面证据核查；页面文字不代表后端交易状态验证").also { checked = it }
    }

    override fun acceptedCompletion(end: TaskState.Succeeded) { if (end === checked) accepted = end }

    fun localOutcome(end: TaskState): JudgedOutcome? = accepted?.takeIf { it === end }?.let {
        if (it.verified && it.reviewed && it.unconfirmed.isEmpty()) JudgedOutcome(RunOutcome.Done, "保存路线执行完成，当前页面必需硬断言通过")
        else JudgedOutcome(RunOutcome.Partial, it.note ?: "测试断言未通过或证据不足")
    }
}

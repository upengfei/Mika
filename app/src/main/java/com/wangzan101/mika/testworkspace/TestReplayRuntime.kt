package com.wangzan101.mika.testworkspace

import android.content.Context
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.verify.Verifier
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.vision.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Thin Android adapters keep test-specific learning and checks out of Mika's execution loop. */
object TestReplayRuntime {
    suspend fun fastPolicy(
        context: Context, goal: String, tools: ToolDispatcher, sink: RunSink, model: ChatModel, vision: ChatModel?,
    ): TestFastReplay {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val identity = parseTestRunIdentity(goal) ?: return@runCatching null
                val version = identity.caseVersion ?: return@runCatching null
                val revision = TestCaseRepository(File(context.filesDir, "test-case-repository")).case(identity.caseId)
                    ?.revisions?.firstOrNull { it.version == version } ?: return@runCatching null
                val bound = TestRouteBindingStore(File(context.filesDir, "test-route-bindings.json")).routeId(identity.caseId, version)
                    ?: return@runCatching null
                val executionId = Regex("(?m)^Test execution id: ([A-Za-z0-9-]+)$").find(goal)?.groupValues?.get(1) ?: return@runCatching null
                val expected = TestExecutionService { _, _ -> }.goal(revision.contract, identity.caseId, version, executionId, bound)
                if (goal.trimEnd() != expected.trimEnd()) return@runCatching null
                val route = FileRouteStore.shared(context).all().firstOrNull { it.id == bound && !it.disabled } ?: return@runCatching null
                revision.contract to route
            }.getOrNull()
        }
        return TestFastReplay(goal, prepared?.first, prepared?.second?.id, prepared?.second,
            currentVersion = { pkg -> installedVersion(context, pkg) },
            verify = { end -> verify(context, goal, end, tools, sink, model, vision) as TaskState.Succeeded },
        )
    }

    fun learning(context: Context, tools: ToolIndex = knownTools()): TestRouteLearning {
        val runs = RunStore(context)
        return TestRouteLearning(
            repository = { TestCaseRepository(File(context.filesDir, "test-case-repository")) },
            bindings = TestRouteBindingStore(File(context.filesDir, "test-route-bindings.json")),
            routes = FileRouteStore.shared(context),
            summary = runs::summary, events = runs::events, append = runs::append,
            appVersion = { p -> installedVersion(context, p) },
            tools = tools,
        )
    }

    /** Must run while the task still owns its screen, before TaskRun releases resources. */
    suspend fun verify(
        context: Context, goal: String, end: TaskState, tools: ToolDispatcher, sink: RunSink,
        model: ChatModel, vision: ChatModel?,
    ): TaskState {
        if (BuildConfig.BUILD_TYPE != "workspace" || end !is TaskState.Succeeded) return end
        val identity = parseTestRunIdentity(goal) ?: return end
        return try {
            val revision = withContext(Dispatchers.IO) {
                TestCaseRepository(File(context.filesDir, "test-case-repository")).case(identity.caseId)
                    ?.revisions?.firstOrNull { it.version == identity.caseVersion }
            } ?: return end.copy(verified = false, reviewed = true, note = "用例版本不可用，无法验证断言", unconfirmed = end.unconfirmed + "用例版本不可用")
            if (revision.contract.assertions.isEmpty()) return end
            val verifier = Verifier(model, vision, BitmapImageOps)
            val evidence = tools.evidence(withImage = true)
            val result = TestAssertionVerifier({ g, c, a, e -> verifier.verify(g, c, a, e) })
                .verify(revision.contract, identity.caseId, revision.version, goal, end.answer, evidence)
            val shotId = if (evidence.freshImage != null && !evidence.protected) VERIFICATION_SCREENSHOT else null
            shotId?.let { sink.media(it, requireNotNull(evidence.freshImage)) }
            val details = Json.parseToJsonElement(result.event.detailJson).jsonObject
            val event = if (shotId == null) result.event else result.event.copy(
                detailJson = JsonObject(details + ("screenshotId" to JsonPrimitive(shotId))).toString(),
            )
            sink.event(event)
            result.applyTo(end)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TestReplayRuntime", "assertion verification unavailable", e)
            // A read or verification failure never reruns a transaction or upgrades the run to PASS.
            sink.event(RunEvent.Verifier(System.currentTimeMillis(), "cannot_verify", """{"source":"mp-test-assertions-v1","reason":"断言核查不可用"}"""))
            end.copy(verified = false, reviewed = true, note = "断言核查不可用，结果待验证", unconfirmed = end.unconfirmed + "断言核查不可用")
        }
    }

    private fun installedVersion(context: Context, pkg: String): String? = runCatching {
        context.packageManager.getPackageInfo(pkg, 0).let { info ->
            info.versionName?.takeIf { it.isNotBlank() }?.let { "$it#${info.longVersionCode}" }
        }
    }.getOrNull()

    private const val VERIFICATION_SCREENSHOT = 999999
}

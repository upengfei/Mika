package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSummary
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteRecorder
import com.wangzan101.mika.core.replay.RouteStore
import com.wangzan101.mika.core.tool.ToolIndex
import java.util.UUID

/** MP owns the link between a case revision and Mika's guarded replay route. */
class TestRouteLearning(
    private val repository: () -> TestCaseRepository,
    private val bindings: TestRouteBindingStore,
    private val routes: RouteStore,
    private val summary: (String) -> RunSummary?,
    private val events: (String) -> List<RunEvent>,
    private val append: (String, RunEvent) -> Unit,
    private val appVersion: (String) -> String? = { null },
    private val tools: ToolIndex = knownTools(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Called only after LearnGate allows this successful run to be learned. */
    fun completed(goal: String, runId: String, timeline: List<RunEvent>) = synchronized(lock) {
        completedLocked(goal, runId, timeline)
    }

    private fun completedLocked(goal: String, runId: String, timeline: List<RunEvent>) {
        if (RunOutcome.recorded(timeline) != RunOutcome.Done) return
        val routeable = goal.replace(ROUTE_ID, "").trimEnd()
        val route = RouteRecorder.record(routeable, timeline, clock(), tools) ?: return
        val saved = withVersions(route)
        val identity = parseTestRunIdentity(goal)
        if (identity == null) {
            routes.putReplacing(saved)
            if (routes.all().none { it.id == saved.id && !it.disabled }) return
            append(runId, RunEvent.State(clock(), SOURCE_READY, saved.id))
            reconcile(runId)
            return
        }
        val revision = repository().case(identity.caseId)?.revisions?.firstOrNull { it.version == identity.caseVersion } ?: return
        if (!targetMatches(revision, saved) || !TestAssertionVerifier.permitsRoute(revision.contract, identity.caseId, revision.version, timeline)) return
        routes.put(saved)
        bindings.bind(identity.caseId, revision.version, saved.id)
    }

    /** Safe regardless of whether background outcome judging or the save dialog finishes first. */
    fun capture(caseId: String, version: Int, sourceRunId: String) = synchronized(lock) {
        bindings.requestCapture(caseId, version, sourceRunId)
        reconcile(sourceRunId)
    }

    fun reconcile(sourceRunId: String) = synchronized(lock) { reconcileLocked(sourceRunId) }

    private fun reconcileLocked(sourceRunId: String) {
        val source = summary(sourceRunId) ?: return
        val timeline = events(sourceRunId)
        if (source.outcome != "succeeded" || RunOutcome.recorded(timeline) != RunOutcome.Done) return
        val ready = timeline.filterIsInstance<RunEvent.State>().lastOrNull { it.name == SOURCE_READY } ?: return
        if (routes.all().none { it.id == ready.detail && !it.disabled }) return
        if (parseTestRunIdentity(source.goal) != null) return
        // Re-record from the source goal before adding repeated step text: slot safety is checked against the actual user command.
        val route = RouteRecorder.record(source.goal, timeline, clock(), tools) ?: return
        bindings.captures(sourceRunId).forEach { capture ->
            val revision = repository().case(capture.caseId)?.revisions?.firstOrNull { it.version == capture.version } ?: return@forEach
            if (revision.contract.command != source.goal || !targetMatches(revision, route)) return@forEach
            // A newly added assertion has never been checked by this exploratory run.
            if (!TestAssertionVerifier.permitsRoute(revision.contract, capture.caseId, capture.version, timeline)) return@forEach
            val caseGoal = TestExecutionService { _, _ -> }.goal(revision.contract, capture.caseId, capture.version, CAPTURE_EXECUTION_ID)
            val rebased = withVersions(route.copy(
                id = UUID.randomUUID().toString(),
                template = route.template + caseGoal.removePrefix(revision.contract.command),
            ))
            routes.put(rebased)
            bindings.bind(capture.caseId, capture.version, rebased.id)
        }
    }

    private fun targetMatches(revision: TestCaseRevision, route: Route) =
        revision.contract.targetApp.isNullOrBlank() || revision.contract.targetApp in route.packages

    private fun withVersions(route: Route) = route.copy(appVersions = route.packages.mapNotNull { p -> appVersion(p)?.let { p to it } }.toMap())

    companion object {
        private val lock = Any()
        const val SOURCE_READY = "mp_replay_source_ready"
        private const val CAPTURE_EXECUTION_ID = "00000000-0000-0000-0000-000000000000"
        private val ROUTE_ID = Regex("(?m)^Test route id: [A-Za-z0-9_-]+\\s*$")
    }
}

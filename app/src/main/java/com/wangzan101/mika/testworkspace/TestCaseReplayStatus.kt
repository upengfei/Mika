package com.wangzan101.mika.testworkspace

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.core.replay.RouteStore
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

enum class TestCaseReplayState(val label: String) {
    SAVED("已保存 · 尚未关联成功路线"),
    PENDING("待验证 · 暂无可用的安全路线"),
    READY("可回放 · 运行时仍会校验界面和断言"),
}

fun testCaseReplayState(record: ManagedTestCase, bindings: TestRouteBindingStore, routes: RouteStore): TestCaseReplayState {
    return testCaseReplayStates(listOf(record), bindings, routes).getValue(record.id)
}

private fun testCaseReplayStates(records: List<ManagedTestCase>, bindings: TestRouteBindingStore, routes: RouteStore): Map<String, TestCaseReplayState> {
    val routeIds = bindings.routeIds()
    val pending = bindings.captures().map { it.caseId to it.version }.toSet()
    val available = routes.all().filterNot { it.disabled }.map { it.id }.toSet()
    return records.associate { record ->
        val key = record.id to record.currentVersion
        val routeId = routeIds[key]
        record.id to when {
            routeId in available -> TestCaseReplayState.READY
            routeId != null || key in pending -> TestCaseReplayState.PENDING
            else -> TestCaseReplayState.SAVED
        }
    }
}

/** Observe late outcome judging without introducing another dependency into ChatScreen. */
@Composable
fun rememberTestCaseReplayStates(records: List<ManagedTestCase>): Map<String, TestCaseReplayState> {
    val context = LocalContext.current
    var states by remember { mutableStateOf(emptyMap<String, TestCaseReplayState>()) }
    LaunchedEffect(records) {
        if (records.isEmpty()) {
            states = emptyMap()
            return@LaunchedEffect
        }
        val bindings = TestRouteBindingStore(File(context.filesDir, "test-route-bindings.json"))
        val routes = FileRouteStore.shared(context)
        // Resume an interrupted save after app restart, provided outcome evidence is already persisted.
        withContext(Dispatchers.IO) {
            bindings.captures().filter { capture -> records.any { it.id == capture.caseId && it.currentVersion == capture.version } }.forEach { capture ->
                runCatching { TestReplayRuntime.learning(context).reconcile(capture.sourceRunId) }
            }
        }
        while (true) {
            states = withContext(Dispatchers.IO) { testCaseReplayStates(records, bindings, routes) }
            delay(2_000)
        }
    }
    return states
}

@Composable
fun TestCaseReplayStatus(state: TestCaseReplayState, modifier: Modifier = Modifier) {
    Text(state.label, modifier, style = MikaType.Hint, color = if (state == TestCaseReplayState.READY) MikaTheme.colors.ok else MikaTheme.colors.ink3)
}

package com.wangzan101.mika.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.automation.TestModelPolicy
import com.wangzan101.mika.core.automation.TestTaskContract
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.testworkspace.TestExecutionService
import com.wangzan101.mika.testworkspace.TestRunRepository
import com.wangzan101.mika.testworkspace.TestTaskStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TestWorkspaceScreen(context: Context, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val store = remember { TestTaskStore(File(context.filesDir, "test-tasks")) }
    val runRepository = remember { TestRunRepository(RunStore(context)) }
    val executor = remember { TestExecutionService(context) }
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(store.list()) }
    var runs by remember { mutableStateOf(runRepository.testRuns()) }
    var command by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var expandedTaskId by remember { mutableStateOf<String?>(null) }
    var startedTask by remember { mutableStateOf<String?>(null) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { store.import(it.readText()) }
                ?: error("empty file")
        }.onSuccess {
            tasks = store.list()
            error = null
        }.onFailure { error = it.message ?: context.getString(R.string.test_workspace_import_failed) }
    }

    SubPage(stringResource(R.string.drawer_test_workspace), onBack) {
        Text(stringResource(R.string.test_workspace_intro), style = MikaType.Body, color = MikaTheme.colors.ink2)
        SegmentedControl(
            options = listOf(stringResource(R.string.test_workspace_tasks), stringResource(R.string.test_workspace_history)),
            selected = selectedTab,
            onSelect = { selectedTab = it; error = null },
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )

        if (selectedTab == 0) {
            StateCard(
                MikaIcons.Apps,
                stringResource(R.string.test_workspace_saved),
                "${tasks.size} · ${stringResource(R.string.test_workspace_local)}",
                tone = TileTone.Accent,
            )
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                label = { Text(stringResource(R.string.test_workspace_command)) },
                minLines = 3,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaButton(
                    stringResource(R.string.test_workspace_save),
                    onClick = {
                        val text = command.trim()
                        if (text.isEmpty()) error = context.getString(R.string.test_workspace_command_required)
                        else runCatching {
                            val id = "task-${System.currentTimeMillis()}"
                            store.save(TestTaskContract(id = id, name = text.take(48), command = text, modelPolicy = TestModelPolicy("configured", "workspace-v1")))
                            tasks = store.list()
                            command = ""
                            error = null
                        }.onFailure { error = it.message }
                    },
                    modifier = Modifier.weight(1f),
                    size = ButtonSize.Medium,
                )
                MikaButton(
                    stringResource(R.string.test_workspace_import),
                    onClick = { importFile.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    kind = ButtonKind.Secondary,
                    size = ButtonSize.Medium,
                )
            }
            error?.let { Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.bad) }
            startedTask?.let {
                StateCard(MikaIcons.Clock, stringResource(R.string.test_workspace_started), it, tone = TileTone.Neutral)
            }
            GroupLabel(stringResource(R.string.test_workspace_saved))
            if (tasks.isEmpty()) EmptyNote(stringResource(R.string.test_workspace_empty))
            else tasks.forEach { task ->
                GroupCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(task.name, style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
                        Text(task.command, style = MikaType.Caption, color = MikaTheme.colors.ink3, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { expandedTaskId = if (expandedTaskId == task.id) null else task.id }) {
                                Text(if (expandedTaskId == task.id) stringResource(R.string.test_workspace_hide_details) else stringResource(R.string.test_workspace_details))
                            }
                            TextButton(onClick = {
                                runCatching {
                                    executor.start(task)
                                    startedTask = task.name
                                    error = null
                                    selectedTab = 1
                                    scope.launch {
                                        delay(1_000)
                                        runs = runRepository.testRuns()
                                    }
                                }.onFailure { error = it.message }
                            }) { Text(stringResource(R.string.test_workspace_run)) }
                            TextButton(onClick = { store.delete(task.id); tasks = store.list(); expandedTaskId = null }) {
                                Text(stringResource(R.string.test_workspace_delete))
                            }
                        }
                        if (expandedTaskId == task.id) {
                            GroupLabel(stringResource(R.string.test_workspace_steps))
                            if (task.steps.isEmpty()) Text("—", style = MikaType.Caption, color = MikaTheme.colors.ink3)
                            else task.steps.forEachIndexed { index, step ->
                                Text("${index + 1}. ${step.instruction}", style = MikaType.Caption, color = MikaTheme.colors.ink2, modifier = Modifier.padding(vertical = 3.dp))
                            }
                            GroupLabel(stringResource(R.string.test_workspace_assertions))
                            if (task.assertions.isEmpty()) Text("—", style = MikaType.Caption, color = MikaTheme.colors.ink3)
                            else task.assertions.forEach { assertion ->
                                Text("${assertion.kind.name} · ${assertion.description}", style = MikaType.Caption, color = MikaTheme.colors.ink2, modifier = Modifier.padding(vertical = 3.dp))
                            }
                            GroupLabel(stringResource(R.string.test_workspace_target_app))
                            Text(task.targetApp ?: stringResource(R.string.test_workspace_target_unspecified), style = MikaType.Caption, color = MikaTheme.colors.ink2)
                        }
                    }
                }
            }
        } else {
            GroupLabel(stringResource(R.string.test_workspace_history))
            if (runs.isEmpty()) EmptyNote(stringResource(R.string.test_workspace_no_runs))
            else runs.forEach { run ->
                val taskId = Regex("Test task id: ([a-z0-9-]+)").find(run.goal)?.groupValues?.getOrNull(1)
                val status = when {
                    run.endedAtMs == null -> stringResource(R.string.test_workspace_running)
                    run.judged != null -> run.judged
                    run.outcome != null -> run.outcome
                    else -> stringResource(R.string.test_workspace_result_unknown)
                }
                GroupCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(run.goal.substringBefore("\n\nRequired steps:").substringBefore("\nTest task id:").take(88), style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
                        Text(
                            "${stringResource(R.string.test_workspace_run_id)} ${run.id}",
                            style = MikaType.Caption,
                            color = MikaTheme.colors.ink3,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.startedAtMs))} · $status · ${run.steps} ${stringResource(R.string.test_workspace_steps)}",
                            style = MikaType.Caption,
                            color = if (run.endedAtMs == null) MikaTheme.colors.warn else MikaTheme.colors.ink2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (taskId != null) Text("${stringResource(R.string.test_workspace_task_id)} $taskId", style = MikaType.Hint, color = MikaTheme.colors.ink3, modifier = Modifier.padding(top = 3.dp))
                        run.answerOrReason?.let { Text(it, style = MikaType.Caption, color = MikaTheme.colors.ink2, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
            MikaButton(
                stringResource(R.string.test_workspace_refresh),
                onClick = { runs = runRepository.testRuns() },
                modifier = Modifier.fillMaxWidth(),
                kind = ButtonKind.Secondary,
                size = ButtonSize.Medium,
            )
        }
    }
}

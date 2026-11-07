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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.automation.TestModelPolicy
import com.wangzan101.mika.core.automation.TestTaskContract
import com.wangzan101.mika.testworkspace.TestExecutionService
import com.wangzan101.mika.testworkspace.TestTaskStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.io.File

@Composable
fun TestWorkspaceScreen(context: Context, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val store = remember { TestTaskStore(File(context.filesDir, "test-tasks")) }
    val executor = remember { TestExecutionService(context) }
    var tasks by remember { mutableStateOf(store.list()) }
    var command by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var lastRun by remember { mutableStateOf<String?>(null) }
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
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
        lastRun?.let { StateCard(MikaIcons.Check, stringResource(R.string.test_workspace_started), it, tone = TileTone.Ok) }
        GroupLabel(stringResource(R.string.test_workspace_saved))
        if (tasks.isEmpty()) EmptyNote(stringResource(R.string.test_workspace_empty))
        else GroupCard {
            tasks.forEach { task ->
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(task.name, style = MikaType.Body, color = MikaTheme.colors.ink)
                    Text(task.command, style = MikaType.Caption, color = MikaTheme.colors.ink3, modifier = Modifier.padding(top = 3.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            runCatching { lastRun = executor.start(task).executionId; error = null }
                                .onFailure { error = it.message }
                        }) { Text(stringResource(R.string.test_workspace_run)) }
                        TextButton(onClick = { store.delete(task.id); tasks = store.list() }) { Text(stringResource(R.string.test_workspace_delete)) }
                    }
                }
            }
        }
    }
}

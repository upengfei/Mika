package com.wangzan101.mika.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.wangzan101.mika.core.automation.TestTaskContracts
import com.wangzan101.mika.core.automation.TestTaskFormat
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.testworkspace.ManagedTestCase
import com.wangzan101.mika.testworkspace.TestCaseImportPolicy
import com.wangzan101.mika.testworkspace.TestCaseProject
import com.wangzan101.mika.testworkspace.TestCaseRepository
import com.wangzan101.mika.testworkspace.TestCaseSuite
import com.wangzan101.mika.testworkspace.TestExecutionService
import com.wangzan101.mika.testworkspace.TestRunRepository
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
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestWorkspaceScreen(context: Context, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val repository = remember {
        TestCaseRepository(File(context.filesDir, "test-case-repository"), File(context.filesDir, "test-tasks"))
    }
    val runRepository = remember { TestRunRepository(RunStore(context), File(context.filesDir, "test-reports")) }
    val executor = remember { TestExecutionService(context) }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(repository.projects()) }
    var selectedProjectId by remember { mutableStateOf(projects.firstOrNull()?.id ?: TestCaseRepository.DEFAULT_PROJECT) }
    var suites by remember { mutableStateOf(repository.suites(selectedProjectId)) }
    var selectedSuiteId by remember { mutableStateOf<String?>(null) }
    var cases by remember { mutableStateOf(repository.search(projectId = selectedProjectId)) }
    var runs by remember { mutableStateOf(runRepository.testRuns()) }
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    var showArchived by remember { mutableStateOf(false) }
    var expandedCaseId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var projectMenu by remember { mutableStateOf(false) }
    var suiteMenu by remember { mutableStateOf(false) }
    var namingKind by remember { mutableStateOf<String?>(null) }
    var namingValue by remember { mutableStateOf("") }
    var editorId by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorName by remember { mutableStateOf("") }
    var editorCommand by remember { mutableStateOf("") }
    var editorTargetApp by remember { mutableStateOf("") }
    var editorSuiteName by remember { mutableStateOf("") }
    var editorLabels by remember { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf<TestTaskContract?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf("") }
    var pendingExportName by remember { mutableStateOf("") }
    var pendingReportFile by remember { mutableStateOf<File?>(null) }

    fun refreshCases() {
        projects = repository.projects()
        suites = repository.suites(selectedProjectId)
        cases = repository.search(projectId = selectedProjectId, suiteId = selectedSuiteId, query = query)
            .filter { it.archived == showArchived }
    }

    fun beginEdit(record: ManagedTestCase?) {
        editorOpen = true
        editorId = record?.id
        editorName = record?.current?.contract?.name.orEmpty()
        editorCommand = record?.current?.contract?.command.orEmpty()
        editorTargetApp = record?.current?.contract?.targetApp.orEmpty()
        editorSuiteName = record?.let { repository.suites(it.projectId).firstOrNull { suite -> suite.id == it.suiteId }?.name }.orEmpty()
        editorLabels = record?.labels?.joinToString(", ").orEmpty()
        error = null
    }

    fun importSuiteId(): String = selectedSuiteId ?: suites.firstOrNull()?.id ?: repository.createSuite(selectedProjectId, "General").id

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { TestTaskContracts.parse(it.readText()) }
                ?: error("empty file")
        }.onSuccess { contract ->
            error = null
            if (repository.case(contract.id) == null) {
                repository.importCase(contract, TestCaseImportPolicy.REQUIRE_CHOICE, selectedProjectId, importSuiteId())
                refreshCases()
            } else pendingImport = contract
        }.onFailure { error = it.message ?: context.getString(R.string.test_workspace_import_failed) }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer -> writer.write(pendingExport) } }
    }
    val exportYaml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/yaml")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer -> writer.write(pendingExport) } }
    }
    val exportHtmlReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val source = pendingReportFile
        if (uri != null && source != null) context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
        pendingReportFile = null
    }
    val exportZipReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = pendingReportFile
        if (uri != null && source != null) context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
        pendingReportFile = null
    }
    val exportPdfReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = pendingReportFile
        if (uri != null && source != null) context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
        pendingReportFile = null
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    TextButton(onClick = { projectMenu = true }) {
                        Text("${stringResource(R.string.test_workspace_project)}: ${projects.firstOrNull { it.id == selectedProjectId }?.name.orEmpty()} ▾")
                    }
                    DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                        projects.forEach { project -> DropdownMenuItem(text = { Text(project.name) }, onClick = {
                            selectedProjectId = project.id
                            selectedSuiteId = null
                            projectMenu = false
                            refreshCases()
                        }) }
                    }
                }
                Column(Modifier.weight(1f)) {
                    TextButton(onClick = { suiteMenu = true }) {
                        Text("${stringResource(R.string.test_workspace_suite)}: ${suites.firstOrNull { it.id == selectedSuiteId }?.name ?: stringResource(R.string.test_workspace_all)} ▾")
                    }
                    DropdownMenu(expanded = suiteMenu, onDismissRequest = { suiteMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.test_workspace_all)) }, onClick = {
                            selectedSuiteId = null; suiteMenu = false; refreshCases()
                        })
                        suites.forEach { suite -> DropdownMenuItem(text = { Text(suite.name) }, onClick = {
                            selectedSuiteId = suite.id; suiteMenu = false; refreshCases()
                        }) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { namingKind = "project"; namingValue = "" }) { Text(stringResource(R.string.test_workspace_new_project)) }
                TextButton(onClick = { namingKind = "suite"; namingValue = "" }) { Text(stringResource(R.string.test_workspace_new_suite)) }
            }
            StateCard(
                MikaIcons.Apps,
                stringResource(R.string.test_workspace_saved),
                "${cases.size} · ${stringResource(R.string.test_workspace_local)}",
                tone = TileTone.Accent,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; refreshCases() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                label = { Text(stringResource(R.string.test_workspace_search)) },
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaButton(stringResource(R.string.test_workspace_create), onClick = { beginEdit(null) }, modifier = Modifier.weight(1f), size = ButtonSize.Medium)
                MikaButton(stringResource(R.string.test_workspace_import), onClick = { importFile.launch("*/*") }, modifier = Modifier.weight(1f), kind = ButtonKind.Secondary, size = ButtonSize.Medium)
            }
            TextButton(onClick = { showArchived = !showArchived; refreshCases() }) {
                Text(if (showArchived) stringResource(R.string.test_workspace_show_active) else stringResource(R.string.test_workspace_show_archived))
            }
            error?.let { Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.bad) }
            GroupLabel(if (showArchived) stringResource(R.string.test_workspace_archived) else stringResource(R.string.test_workspace_cases))
            if (cases.isEmpty()) EmptyNote(stringResource(if (showArchived) R.string.test_workspace_no_archived else R.string.test_workspace_no_cases))
            else cases.forEach { record ->
                val contract = record.current.contract
                GroupCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(contract.name, style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
                        Text(contract.command, style = MikaType.Caption, color = MikaTheme.colors.ink3, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            "${stringResource(R.string.test_workspace_revision)} ${record.currentVersion} · ${record.labels.joinToString(", ").ifBlank { "—" }}",
                            style = MikaType.Hint,
                            color = MikaTheme.colors.ink3,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            TextButton(onClick = { expandedCaseId = if (expandedCaseId == record.id) null else record.id }) {
                                Text(if (expandedCaseId == record.id) stringResource(R.string.test_workspace_hide_details) else stringResource(R.string.test_workspace_details))
                            }
                            if (!record.archived) {
                                TextButton(onClick = { beginEdit(record) }) { Text(stringResource(R.string.test_workspace_edit)) }
                                TextButton(onClick = {
                                    runCatching {
                                        val revision = record.current
                                        val receipt = executor.start(revision.contract, caseId = record.id, caseVersion = revision.version)
                                        repository.recordExecution(record.id, revision.version, receipt.executionId, receipt.startedAtMs)
                                        selectedTab = 1
                                        scope.launch { delay(1_000); runs = runRepository.testRuns() }
                                        error = null
                                    }.onFailure { error = it.message }
                                }) { Text(stringResource(R.string.test_workspace_run)) }
                            }
                            TextButton(onClick = { repository.duplicate(record.id); refreshCases() }) { Text(stringResource(R.string.test_workspace_duplicate)) }
                            TextButton(onClick = { repository.archive(record.id, !record.archived); refreshCases() }) {
                                Text(stringResource(if (record.archived) R.string.test_workspace_restore else R.string.test_workspace_archive))
                            }
                            if (!record.archived) TextButton(onClick = { pendingDelete = record.id }) { Text(stringResource(R.string.test_workspace_delete)) }
                            TextButton(onClick = {
                                pendingExport = repository.export(record.id, format = TestTaskFormat.JSON)
                                pendingExportName = "${contract.id}.json"
                                exportJson.launch(pendingExportName)
                            }) { Text(stringResource(R.string.test_workspace_export_json)) }
                            TextButton(onClick = {
                                pendingExport = repository.export(record.id, format = TestTaskFormat.YAML)
                                pendingExportName = "${contract.id}.yaml"
                                exportYaml.launch(pendingExportName)
                            }) { Text(stringResource(R.string.test_workspace_export_yaml)) }
                        }
                        if (expandedCaseId == record.id) {
                            GroupLabel(stringResource(R.string.test_workspace_suite))
                            Text(suites.firstOrNull { it.id == record.suiteId }?.name.orEmpty(), style = MikaType.Caption, color = MikaTheme.colors.ink2)
                            GroupLabel(stringResource(R.string.test_workspace_steps))
                            if (contract.steps.isEmpty()) Text("—", style = MikaType.Caption, color = MikaTheme.colors.ink3)
                            else contract.steps.forEachIndexed { index, step ->
                                Text("${index + 1}. ${step.instruction}", style = MikaType.Caption, color = MikaTheme.colors.ink2, modifier = Modifier.padding(vertical = 3.dp))
                            }
                            GroupLabel(stringResource(R.string.test_workspace_assertions))
                            if (contract.assertions.isEmpty()) Text("—", style = MikaType.Caption, color = MikaTheme.colors.ink3)
                            else contract.assertions.forEach { assertion ->
                                Text("${assertion.kind.name} · ${assertion.description}", style = MikaType.Caption, color = MikaTheme.colors.ink2, modifier = Modifier.padding(vertical = 3.dp))
                            }
                            GroupLabel(stringResource(R.string.test_workspace_target_app))
                            Text(contract.targetApp ?: stringResource(R.string.test_workspace_target_unspecified), style = MikaType.Caption, color = MikaTheme.colors.ink2)
                            GroupLabel(stringResource(R.string.test_workspace_revisions))
                            record.revisions.asReversed().forEach { revision ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("v${revision.version} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(revision.savedAtMs))}", style = MikaType.Hint, color = MikaTheme.colors.ink3)
                                    TextButton(onClick = {
                                        runCatching {
                                            val receipt = executor.start(revision.contract, caseId = record.id, caseVersion = revision.version)
                                            repository.recordExecution(record.id, revision.version, receipt.executionId, receipt.startedAtMs)
                                            selectedTab = 1
                                            scope.launch { delay(1_000); runs = runRepository.testRuns() }
                                        }.onFailure { error = it.message }
                                    }) { Text(stringResource(R.string.test_workspace_run)) }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            GroupLabel(stringResource(R.string.test_workspace_history))
            if (runs.isEmpty()) EmptyNote(stringResource(R.string.test_workspace_no_runs))
            else runs.forEach { run ->
                val identity = runRepository.identity(run.goal)
                val snapshot = identity?.executionId?.let(repository::execution)
                val caseRecord = identity?.caseId?.let(repository::case)
                val status = when {
                    run.endedAtMs == null -> stringResource(R.string.test_workspace_running)
                    run.judged != null -> run.judged
                    run.outcome != null -> run.outcome
                    else -> stringResource(R.string.test_workspace_result_unknown)
                }
                GroupCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(snapshot?.contract?.name ?: caseRecord?.current?.contract?.name ?: run.goal.substringBefore("\n\nRequired steps:").take(88), style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
                        Text("${stringResource(R.string.test_workspace_run_id)} ${run.id}", style = MikaType.Caption, color = MikaTheme.colors.ink3, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.startedAtMs))} · $status · ${run.steps} ${stringResource(R.string.test_workspace_steps)}",
                            style = MikaType.Caption,
                            color = if (run.endedAtMs == null) MikaTheme.colors.warn else MikaTheme.colors.ink2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (identity != null) Text(
                            "${stringResource(R.string.test_workspace_case_id)} ${identity.caseId} · v${identity.caseVersion ?: "?"}",
                            style = MikaType.Hint,
                            color = MikaTheme.colors.ink3,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        (run.answerOrReason ?: run.verdict)?.let { Text(it, style = MikaType.Caption, color = MikaTheme.colors.ink2, modifier = Modifier.padding(top = 8.dp)) }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = {
                                runCatching {
                                    val archived = runRepository.archive(run.id)
                                    pendingReportFile = File(archived, "report.html")
                                    exportHtmlReport.launch("test-${run.id}.html")
                                }.onFailure { error = it.message ?: "Report export failed" }
                            }) { Text("HTML") }
                            TextButton(onClick = {
                                runCatching {
                                    val output = File(context.cacheDir, "test-${run.id}.zip")
                                    runRepository.archive(run.id)
                                    pendingReportFile = runRepository.exportZip(run.id, output)
                                    exportZipReport.launch("test-${run.id}.zip")
                                }.onFailure { error = it.message ?: "Report export failed" }
                            }) { Text("ZIP") }
                            TextButton(onClick = {
                                runCatching {
                                    runRepository.archive(run.id)
                                    val output = File(context.cacheDir, "test-${run.id}.pdf")
                                    pendingReportFile = runRepository.exportPdf(run.id, output)
                                    exportPdfReport.launch("test-${run.id}.pdf")
                                }.onFailure { error = it.message ?: "Report export failed" }
                            }) { Text("PDF") }
                        }
                    }
                }
            }
            error?.let { Text(it, style = MikaType.Caption, color = MikaTheme.colors.bad) }
            MikaButton(stringResource(R.string.test_workspace_refresh), onClick = { runs = runRepository.testRuns() }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, size = ButtonSize.Medium)
        }
    }

    if (editorOpen) {
        AlertDialog(
            onDismissRequest = { editorOpen = false; editorId = null; editorName = ""; editorCommand = "" },
            title = { Text(stringResource(if (editorId == null) R.string.test_workspace_create else R.string.test_workspace_edit)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(editorName, { editorName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.test_workspace_name)) }, singleLine = true)
                    OutlinedTextField(editorCommand, { editorCommand = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.test_workspace_command)) }, minLines = 3)
                    OutlinedTextField(editorTargetApp, { editorTargetApp = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.test_workspace_target_app)) }, singleLine = true)
                    OutlinedTextField(editorSuiteName, { editorSuiteName = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.test_workspace_suite)) }, singleLine = true)
                    OutlinedTextField(editorLabels, { editorLabels = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.test_workspace_labels)) }, singleLine = true)
                    error?.let { Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.bad) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val existing = editorId?.let(repository::case)
                        val suiteName = editorSuiteName.trim().ifBlank { "General" }
                        val suite = repository.suites(selectedProjectId).firstOrNull { it.name.equals(suiteName, true) }
                            ?: repository.createSuite(selectedProjectId, suiteName)
                        val labels = editorLabels.split(',').map(String::trim).filter(String::isNotEmpty)
                        if (existing == null) {
                            val id = "case-${UUID.randomUUID().toString().replace("-", "").take(12)}"
                            repository.createCase(
                                TestTaskContract(id = id, name = editorName.trim(), command = editorCommand.trim(), targetApp = editorTargetApp.trim().ifBlank { null }, modelPolicy = TestModelPolicy("configured", "workspace-v1")),
                                selectedProjectId,
                                suite.id,
                                labels,
                            )
                        } else {
                            repository.revise(
                                existing.id,
                                existing.current.contract.copy(name = editorName.trim(), command = editorCommand.trim(), targetApp = editorTargetApp.trim().ifBlank { null }),
                                selectedProjectId,
                                suite.id,
                                labels,
                            )
                        }
                        selectedSuiteId = suite.id
                        refreshCases()
                        editorOpen = false; editorId = null; editorName = ""; editorCommand = ""; error = null
                    }.onFailure { error = it.message }
                }) { Text(stringResource(R.string.test_workspace_save)) }
            },
            dismissButton = { TextButton(onClick = { editorOpen = false; editorId = null; editorName = ""; editorCommand = "" }) { Text(stringResource(R.string.test_workspace_cancel)) } },
        )
    }

    namingKind?.let { kind ->
        AlertDialog(
            onDismissRequest = { namingKind = null },
            title = { Text(stringResource(if (kind == "project") R.string.test_workspace_new_project else R.string.test_workspace_new_suite)) },
            text = { OutlinedTextField(namingValue, { namingValue = it }, label = { Text(stringResource(if (kind == "project") R.string.test_workspace_project_name else R.string.test_workspace_suite_name)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                runCatching {
                    if (kind == "project") {
                        val project = repository.createProject(namingValue)
                        repository.createSuite(project.id, "General")
                        selectedProjectId = project.id
                        selectedSuiteId = null
                    } else {
                        val suite = repository.createSuite(selectedProjectId, namingValue)
                        selectedSuiteId = suite.id
                    }
                    namingKind = null
                    refreshCases()
                }.onFailure { error = it.message }
            }) { Text(stringResource(R.string.test_workspace_create)) } },
            dismissButton = { TextButton(onClick = { namingKind = null }) { Text(stringResource(R.string.test_workspace_cancel)) } },
        )
    }

    pendingImport?.let { contract ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.test_workspace_import_conflict)) },
            text = { Text("${contract.name} · ${contract.id}") },
            confirmButton = { TextButton(onClick = {
                runCatching { repository.importCase(contract, TestCaseImportPolicy.OVERWRITE_AS_REVISION, selectedProjectId, importSuiteId()) }
                    .onSuccess { pendingImport = null; refreshCases() }.onFailure { error = it.message }
            }) { Text(stringResource(R.string.test_workspace_import_overwrite)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        runCatching { repository.importCase(contract, TestCaseImportPolicy.COPY, selectedProjectId, importSuiteId()) }
                            .onSuccess { pendingImport = null; refreshCases() }.onFailure { error = it.message }
                    }) { Text(stringResource(R.string.test_workspace_import_copy)) }
                    TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.test_workspace_import_skip)) }
                }
            },
        )
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.test_workspace_delete)) },
            text = { Text(stringResource(R.string.test_workspace_delete_note)) },
            confirmButton = { TextButton(onClick = { repository.deleteCase(id); pendingDelete = null; refreshCases() }) { Text(stringResource(R.string.test_workspace_delete)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.test_workspace_cancel)) } },
        )
    }
}

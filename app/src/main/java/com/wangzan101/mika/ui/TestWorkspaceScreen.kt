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
import com.wangzan101.mika.testworkspace.TestCaseReplayStatus
import com.wangzan101.mika.testworkspace.TestCaseReplayState
import com.wangzan101.mika.testworkspace.rememberTestCaseReplayStates
import com.wangzan101.mika.testworkspace.testRunResult
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
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RowChevron
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
import com.wangzan101.mika.task.TriggerRuntime

private enum class TestWorkspaceSection { Projects, Suites, Cases, History }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestWorkspaceScreen(context: Context, onBack: () -> Unit) {
    var section by remember { mutableStateOf<TestWorkspaceSection?>(null) }
    BackHandler(onBack = { if (section == null) onBack() else section = null })
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
    val replayStates = rememberTestCaseReplayStates(if (section == TestWorkspaceSection.Cases) cases else emptyList())
    var runs by remember { mutableStateOf(runRepository.testRuns()) }
    var query by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var showArchivedProjects by remember { mutableStateOf(false) }
    var expandedCaseId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var suiteMenu by remember { mutableStateOf(false) }
    var projectEditorOpen by remember { mutableStateOf(false) }
    var projectEditorId by remember { mutableStateOf<String?>(null) }
    var projectEditorName by remember { mutableStateOf("") }
    var projectEditorDescription by remember { mutableStateOf("") }
    var projectEditorTargetApp by remember { mutableStateOf("") }
    var pendingProjectDelete by remember { mutableStateOf<String?>(null) }
    var suiteEditorId by remember { mutableStateOf<String?>(null) }
    var suiteEditorName by remember { mutableStateOf("") }
    var pendingSuiteDelete by remember { mutableStateOf<String?>(null) }
    var editorId by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorName by remember { mutableStateOf("") }
    var editorCommand by remember { mutableStateOf("") }
    var editorTargetApp by remember { mutableStateOf("") }
    var editorSuiteName by remember { mutableStateOf("") }
    var editorLabels by remember { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf<TestTaskContract?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var pendingMove by remember { mutableStateOf<ManagedTestCase?>(null) }
    var moveProjectId by remember { mutableStateOf("") }
    var moveSuiteId by remember { mutableStateOf("") }
    var pendingSuiteRun by remember { mutableStateOf<List<ManagedTestCase>?>(null) }
    var historyProjectFilter by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf("") }
    var pendingExportName by remember { mutableStateOf("") }
    var pendingReportFile by remember { mutableStateOf<File?>(null) }
    var chatRecommendations by remember { mutableStateOf(TestWorkspacePreferences.chatRecommendations(context)) }

    fun refreshCases() {
        projects = repository.projects()
        suites = repository.suites(selectedProjectId)
        val found = repository.search(projectId = selectedProjectId, suiteId = selectedSuiteId, query = query)
            .filter { it.archived == showArchived }
        cases = if (selectedSuiteId != null && !showArchived) {
            val order = repository.orderedCases(selectedProjectId, selectedSuiteId!!).mapIndexed { index, case -> case.id to index }.toMap()
            found.sortedBy { order[it.id] ?: Int.MAX_VALUE }
        } else found
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
        }.onSuccess { importedContract ->
            val contract = importedContract.copy(targetApp = importedContract.targetApp ?: repository.project(selectedProjectId)?.targetApp)
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

    val pageTitle = when (section) {
        TestWorkspaceSection.Projects -> stringResource(R.string.test_workspace_project)
        TestWorkspaceSection.Suites -> stringResource(R.string.test_workspace_suite)
        TestWorkspaceSection.Cases -> stringResource(R.string.test_workspace_cases)
        TestWorkspaceSection.History -> stringResource(R.string.test_workspace_history)
        null -> stringResource(R.string.drawer_test_workspace)
    }
    SubPage(pageTitle, onBack = { if (section == null) onBack() else section = null }) {
        when (section) {
        null -> {
            Text(stringResource(R.string.test_workspace_intro), style = MikaType.Body, color = MikaTheme.colors.ink2)
            GroupCard {
                ListRow(MikaIcons.Apps, stringResource(R.string.test_workspace_project), first = true, onClick = { section = TestWorkspaceSection.Projects }) { RowChevron() }
                ListRow(MikaIcons.Lines, stringResource(R.string.test_workspace_suite), onClick = { section = TestWorkspaceSection.Suites }) { RowChevron() }
                ListRow(MikaIcons.Check, stringResource(R.string.test_workspace_cases), onClick = { section = TestWorkspaceSection.Cases }) { RowChevron() }
                ListRow(MikaIcons.Clock, stringResource(R.string.test_workspace_history), onClick = { section = TestWorkspaceSection.History; runs = runRepository.testRuns() }) { RowChevron() }
            }
            GroupCard {
                ListRow(
                    null,
                    stringResource(R.string.test_workspace_chat_recommendations),
                    stringResource(R.string.test_workspace_chat_recommendations_subtitle),
                    first = true,
                ) {
                    MikaSwitch(chatRecommendations, { enabled ->
                        chatRecommendations = enabled
                        TestWorkspacePreferences.setChatRecommendations(context, enabled)
                    })
                }
            }
        }
        TestWorkspaceSection.Projects -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaButton(stringResource(R.string.test_workspace_new_project), onClick = {
                    projectEditorOpen = true; projectEditorId = null; projectEditorName = ""; projectEditorDescription = ""; projectEditorTargetApp = ""; error = null
                }, modifier = Modifier.weight(1f), size = ButtonSize.Medium)
                TextButton(onClick = { showArchivedProjects = !showArchivedProjects }) {
                    Text(if (showArchivedProjects) stringResource(R.string.test_workspace_show_active) else stringResource(R.string.test_workspace_show_archived))
                }
            }
            val visibleProjects = repository.projects(includeArchived = showArchivedProjects).filter { it.archived == showArchivedProjects }
            val knownTestRuns = runRepository.testRuns()
            GroupLabel(if (showArchivedProjects) stringResource(R.string.test_workspace_archived) else stringResource(R.string.test_workspace_project))
            if (visibleProjects.isEmpty()) EmptyNote(stringResource(R.string.test_workspace_no_projects))
            visibleProjects.forEach { project ->
                val projectCases = repository.search(projectId = project.id, includeArchived = true)
                val projectSuites = repository.suites(project.id)
                val projectRuns = projectCases.flatMap { repository.executions(it.id) }
                    .filter { it.projectId == project.id }
                    .mapNotNull { snapshot -> knownTestRuns.firstOrNull { runRepository.identity(it.goal)?.executionId == snapshot.executionId } }
                    .distinctBy { it.id }.sortedByDescending { it.startedAtMs }.take(5)
                GroupCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(project.name, style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
                        project.description.takeIf(String::isNotBlank)?.let { Text(it, Modifier.padding(top = 4.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3) }
                        Text("${projectSuites.size} 个套件 · ${projectCases.count { !it.archived }} 个有效用例${project.targetApp?.let { " · $it" }.orEmpty()}", Modifier.padding(top = 6.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                        if (projectRuns.isNotEmpty()) {
                            GroupLabel("最近运行")
                            projectRuns.forEach { run ->
                                val result = run.judged ?: run.outcome ?: if (run.endedAtMs == null) "运行中" else "结果未知"
                                TextButton(onClick = { historyProjectFilter = project.id; runs = projectRuns; section = TestWorkspaceSection.History }) {
                                    Text("${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.startedAtMs))} · $result · 报告")
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!project.archived) TextButton(onClick = {
                                projectEditorOpen = true; projectEditorId = project.id; projectEditorName = project.name
                                projectEditorDescription = project.description; projectEditorTargetApp = project.targetApp.orEmpty(); error = null
                            }) { Text(stringResource(R.string.test_workspace_edit)) }
                            TextButton(enabled = !project.archived, onClick = { selectedProjectId = project.id; section = TestWorkspaceSection.Suites }) { Text("打开项目") }
                            TextButton(enabled = !project.archived && projectCases.any { !it.archived }, onClick = {
                                pendingSuiteRun = projectSuites.flatMap { repository.orderedCases(project.id, it.id) }
                            }) { Text("运行项目") }
                            if (project.id != TestCaseRepository.DEFAULT_PROJECT) {
                        TextButton(onClick = {
                                    runCatching {
                                        if (!project.archived) {
                                            val linked = repository.search(projectId = project.id, includeArchived = true)
                                            val activeScheduleIds = TriggerRuntime.list(context).filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
                                            linked.map { "test-${it.id}" }.filter { it in activeScheduleIds }.forEach { id ->
                                                check(TriggerRuntime.setEnabled(context, id, false)) { "Could not pause test schedule $id" }
                                            }
                                        }
                                        repository.archiveProject(project.id, !project.archived)
                                        if (selectedProjectId == project.id && !project.archived) selectedProjectId = repository.projects().firstOrNull()?.id ?: TestCaseRepository.DEFAULT_PROJECT
                                        refreshCases()
                                    }.onFailure { error = it.message }
                                }) { Text(stringResource(if (project.archived) R.string.test_workspace_restore else R.string.test_workspace_archive)) }
                            if (project.archived) TextButton(onClick = { pendingProjectDelete = project.id }) { Text(stringResource(R.string.test_workspace_delete)) }
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.bad) }
        }
        TestWorkspaceSection.Suites -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectSelector(
                    projects = projects,
                    selectedProjectId = selectedProjectId,
                    onSelect = { id -> selectedProjectId = id; selectedSuiteId = null; refreshCases() },
                )
            MikaButton(stringResource(R.string.test_workspace_new_suite), onClick = { suiteEditorId = ""; suiteEditorName = ""; error = null }, size = ButtonSize.Small)
            }
            GroupLabel(stringResource(R.string.test_workspace_suite))
            val visibleSuites = repository.suites(selectedProjectId)
            if (visibleSuites.isEmpty()) EmptyNote(stringResource(R.string.test_workspace_no_suites))
            visibleSuites.forEach { suite ->
                val suiteCases = repository.orderedCases(selectedProjectId, suite.id)
                val suiteCaseCount = repository.search(projectId = selectedProjectId, suiteId = suite.id, includeArchived = true).size
                GroupCard {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(suite.name, style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
                            Text(stringResource(R.string.test_workspace_suite_case_count, suiteCaseCount), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                        }
                        TextButton(onClick = { suiteEditorId = suite.id; suiteEditorName = suite.name; error = null }) { Text(stringResource(R.string.test_workspace_edit)) }
                        TextButton(enabled = suiteCases.isNotEmpty(), onClick = { pendingSuiteRun = suiteCases }) { Text("运行整套") }
                        if (suite.id != TestCaseRepository.DEFAULT_SUITE) TextButton(enabled = suiteCaseCount == 0, onClick = { pendingSuiteDelete = suite.id }) { Text(stringResource(R.string.test_workspace_delete)) }
                    }
                }
            }
            error?.let { Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.bad) }
        }
        TestWorkspaceSection.Cases -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    ProjectSelector(
                        projects = projects,
                        selectedProjectId = selectedProjectId,
                        onSelect = { id -> selectedProjectId = id; selectedSuiteId = null; refreshCases() },
                    )
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
                        TestCaseReplayStatus(replayStates[record.id] ?: TestCaseReplayState.SAVED, Modifier.padding(top = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            TextButton(onClick = { expandedCaseId = if (expandedCaseId == record.id) null else record.id }) {
                                Text(if (expandedCaseId == record.id) stringResource(R.string.test_workspace_hide_details) else stringResource(R.string.test_workspace_details))
                            }
                            if (!record.archived) {
                                TextButton(onClick = { beginEdit(record) }) { Text(stringResource(R.string.test_workspace_edit)) }
                                TextButton(onClick = { pendingMove = record; moveProjectId = record.projectId; moveSuiteId = record.suiteId }) { Text("移动") }
                                TextButton(onClick = {
                                    runCatching {
                                        val revision = record.current
                                        val receipt = executor.start(revision.contract, caseId = record.id, caseVersion = revision.version)
                                        repository.recordExecution(record.id, revision.version, receipt.executionId, receipt.startedAtMs)
                                        section = TestWorkspaceSection.History
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
                            val ordered = repository.orderedCases(record.projectId, record.suiteId)
                            val orderIndex = ordered.indexOfFirst { it.id == record.id }
                            Row {
                                TextButton(enabled = orderIndex > 0, onClick = {
                                    val ids = ordered.map { it.id }.toMutableList(); java.util.Collections.swap(ids, orderIndex, orderIndex - 1)
                                    repository.reorderSuiteCases(record.suiteId, ids); refreshCases()
                                }) { Text("上移") }
                                TextButton(enabled = orderIndex >= 0 && orderIndex < ordered.lastIndex, onClick = {
                                    val ids = ordered.map { it.id }.toMutableList(); java.util.Collections.swap(ids, orderIndex, orderIndex + 1)
                                    repository.reorderSuiteCases(record.suiteId, ids); refreshCases()
                                }) { Text("下移") }
                            }
                            GroupLabel(stringResource(R.string.test_workspace_steps))
                            if (contract.steps.isEmpty()) Text("—", style = MikaType.Caption, color = MikaTheme.colors.ink3)
                            else contract.steps.forEachIndexed { index, step ->
                                Text("${index + 1}. ${step.instruction}", style = MikaType.Caption, color = MikaTheme.colors.ink2, modifier = Modifier.padding(vertical = 3.dp))
                            }
                            GroupLabel(stringResource(R.string.test_workspace_assertions))
                            if (contract.assertions.isEmpty()) Text("—", style = MikaType.Caption, color = MikaTheme.colors.ink3)
                            else contract.assertions.forEach { assertion ->
                                Text("${assertion.kind.name} · ${assertion.description}", style = MikaType.Caption, color = MikaTheme.colors.ink2, modifier = Modifier.padding(vertical = 3.dp))
                                if (assertion.kind == com.wangzan101.mika.core.automation.TestAssertionKind.HARD) {
                                    Text(assertion.expectedText?.let { "预期文字：$it" } ?: "待配置客观校验条件", style = MikaType.Hint, color = MikaTheme.colors.ink3)
                                }
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
                                            section = TestWorkspaceSection.History
                                            scope.launch { delay(1_000); runs = runRepository.testRuns() }
                                        }.onFailure { error = it.message }
                                    }) { Text(stringResource(R.string.test_workspace_run)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        TestWorkspaceSection.History -> {
            GroupLabel(stringResource(R.string.test_workspace_history))
            historyProjectFilter?.let { id -> Text("项目：${repository.project(id)?.name ?: "已删除项目"}", style = MikaType.Caption, color = MikaTheme.colors.ink3) }
            if (runs.isEmpty()) EmptyNote(stringResource(R.string.test_workspace_no_runs))
            else runs.forEach { run ->
                val identity = runRepository.identity(run.goal)
                val snapshot = identity?.executionId?.let(repository::execution)
                val caseRecord = identity?.caseId?.let(repository::case)
                val status = when {
                    run.endedAtMs == null -> stringResource(R.string.test_workspace_running)
                    run.verdict != null -> when (testRunResult(run)) {
                        "pass" -> "断言通过"
                        "fail" -> "断言失败"
                        "cannot_verify" -> "待验证"
                        else -> testRunResult(run)
                    }
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
                        snapshot?.let { Text("项目：${it.projectName}", style = MikaType.Hint, color = MikaTheme.colors.ink3, modifier = Modifier.padding(top = 3.dp)) }
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
                                TestTaskContract(id = id, name = editorName.trim(), command = editorCommand.trim(), targetApp = editorTargetApp.trim().ifBlank { repository.project(selectedProjectId)?.targetApp }, modelPolicy = TestModelPolicy("configured", "workspace-v1")),
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

    if (projectEditorOpen) {
        AlertDialog(
            onDismissRequest = { projectEditorOpen = false },
            title = { Text(stringResource(if (projectEditorId == null) R.string.test_workspace_new_project else R.string.test_workspace_edit_project)) },
            text = {
                Column {
                    OutlinedTextField(projectEditorName, { projectEditorName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.test_workspace_project_name)) }, singleLine = true)
                    OutlinedTextField(projectEditorDescription, { projectEditorDescription = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.test_workspace_project_description)) }, minLines = 2)
                    OutlinedTextField(projectEditorTargetApp, { projectEditorTargetApp = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.test_workspace_target_app) + "（可选，包名）") }, singleLine = true)
                    error?.let { Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.bad) }
                }
            },
            confirmButton = { TextButton(onClick = {
                runCatching {
                    val id = projectEditorId
                    if (id == null) {
                        val project = repository.createProject(projectEditorName, projectEditorDescription, projectEditorTargetApp)
                        repository.createSuite(project.id, "General")
                        selectedProjectId = project.id
                        selectedSuiteId = null
                    } else {
                        repository.updateProject(id, projectEditorName, projectEditorDescription, projectEditorTargetApp)
                    }
                    projectEditorOpen = false
                    error = null
                    refreshCases()
                }.onFailure { error = it.message }
            }) { Text(stringResource(R.string.test_workspace_save)) } },
            dismissButton = { TextButton(onClick = { projectEditorOpen = false }) { Text(stringResource(R.string.test_workspace_cancel)) } },
        )
    }

    suiteEditorId?.let { id ->
        AlertDialog(
            onDismissRequest = { suiteEditorId = null },
            title = { Text(stringResource(if (id.isEmpty()) R.string.test_workspace_new_suite else R.string.test_workspace_edit_suite)) },
            text = {
                Column {
                    OutlinedTextField(suiteEditorName, { suiteEditorName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.test_workspace_suite_name)) }, singleLine = true)
                    error?.let { Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.bad) }
                }
            },
            confirmButton = { TextButton(onClick = {
                runCatching {
                    if (id.isEmpty()) repository.createSuite(selectedProjectId, suiteEditorName)
                    else repository.renameSuite(id, suiteEditorName)
                    suiteEditorId = null
                    error = null
                    refreshCases()
                }.onFailure { error = it.message }
            }) { Text(stringResource(R.string.test_workspace_save)) } },
            dismissButton = { TextButton(onClick = { suiteEditorId = null }) { Text(stringResource(R.string.test_workspace_cancel)) } },
        )
    }

    pendingProjectDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingProjectDelete = null },
            title = { Text(stringResource(R.string.test_workspace_delete_project)) },
            text = { Text(stringResource(R.string.test_workspace_delete_project_note)) },
            confirmButton = { TextButton(onClick = {
                runCatching { repository.deleteProject(id) }
                    .onSuccess { pendingProjectDelete = null; refreshCases(); error = null }
                    .onFailure { pendingProjectDelete = null; error = it.message }
            }) { Text(stringResource(R.string.test_workspace_delete)) } },
            dismissButton = { TextButton(onClick = { pendingProjectDelete = null }) { Text(stringResource(R.string.test_workspace_cancel)) } },
        )
    }

    pendingMove?.let { record ->
        val targetSuites = repository.suites(moveProjectId)
        AlertDialog(
            onDismissRequest = { pendingMove = null },
            title = { Text("移动用例") },
            text = {
                Column {
                    var projectMenuOpen by remember { mutableStateOf(false) }
                    var targetSuiteMenuOpen by remember { mutableStateOf(false) }
                    TextButton(onClick = { projectMenuOpen = true }) { Text("项目：${repository.project(moveProjectId)?.name ?: "选择项目"} ▾") }
                    DropdownMenu(expanded = projectMenuOpen, onDismissRequest = { projectMenuOpen = false }) {
                        repository.projects().forEach { project ->
                            DropdownMenuItem(text = { Text(project.name) }, onClick = {
                                moveProjectId = project.id
                                moveSuiteId = repository.suites(project.id).firstOrNull { it.name.equals("General", true) }?.id
                                    ?: repository.suites(project.id).firstOrNull()?.id.orEmpty()
                                projectMenuOpen = false
                            })
                        }
                    }
                    TextButton(onClick = { targetSuiteMenuOpen = true }, enabled = targetSuites.isNotEmpty()) {
                        Text("套件：${targetSuites.firstOrNull { it.id == moveSuiteId }?.name ?: "选择套件"} ▾")
                    }
                    DropdownMenu(expanded = targetSuiteMenuOpen, onDismissRequest = { targetSuiteMenuOpen = false }) {
                        targetSuites.forEach { suite -> DropdownMenuItem(text = { Text(suite.name) }, onClick = { moveSuiteId = suite.id; targetSuiteMenuOpen = false }) }
                    }
                    val targetProject = repository.project(moveProjectId)
                    if (record.current.contract.targetApp != null && targetProject?.targetApp != null && record.current.contract.targetApp != targetProject.targetApp) {
                        Text("目标 App 与项目默认值不同；移动会保留用例中的目标 App。", style = MikaType.Caption, color = MikaTheme.colors.warn)
                    }
                    Text("移动会保留用例版本和历史。", style = MikaType.Caption, color = MikaTheme.colors.ink3)
                }
            },
            confirmButton = { TextButton(enabled = targetSuites.any { it.id == moveSuiteId }, onClick = {
                runCatching { repository.move(record.id, moveProjectId, moveSuiteId) }
                    .onSuccess { pendingMove = null; selectedProjectId = moveProjectId; selectedSuiteId = moveSuiteId; refreshCases() }
                    .onFailure { error = it.message }
            }) { Text("移动") } },
            dismissButton = { TextButton(onClick = { pendingMove = null }) { Text(stringResource(R.string.test_workspace_cancel)) } },
        )
    }

    pendingSuiteRun?.let { suiteCases ->
        AlertDialog(
            onDismissRequest = { pendingSuiteRun = null },
            title = { Text("运行整套用例") },
            text = { Text("将按保存顺序运行 ${suiteCases.size} 个用例。单项明确通过或失败后继续；状态未知或等待超时则停止。真实交易仍会按现有安全机制确认。") },
            confirmButton = { TextButton(onClick = {
                val casesToRun = suiteCases.toList()
                pendingSuiteRun = null
                section = TestWorkspaceSection.History
                scope.launch {
                    for (case in casesToRun) {
                        val project = repository.project(case.projectId) ?: break
                        if (project.archived) break
                        val revision = case.current
                        val receipt = try {
                            executor.start(revision.contract, caseId = case.id, caseVersion = revision.version)
                        } catch (failure: Exception) {
                            error = failure.message
                            break
                        }
                        repository.recordExecution(case.id, revision.version, receipt.executionId, receipt.startedAtMs)
                        var completed: com.wangzan101.mika.core.record.RunSummary? = null
                        val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
                        while (completed == null && System.currentTimeMillis() < deadline) {
                            delay(500)
                            completed = runRepository.testRuns().firstOrNull { runRepository.identity(it.goal)?.executionId == receipt.executionId && it.endedAtMs != null }
                        }
                        val result = completed?.outcome.orEmpty().lowercase()
                        val known = result == "succeeded" || result == "failed"
                        if (!known) {
                            error = "整套运行已停止：用例 ${case.current.contract.name} 状态未知或等待超时。"
                            break
                        }
                    }
                    runs = runRepository.testRuns()
                }
            }) { Text("确认运行") } },
            dismissButton = { TextButton(onClick = { pendingSuiteRun = null }) { Text(stringResource(R.string.test_workspace_cancel)) } },
        )
    }

    pendingSuiteDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingSuiteDelete = null },
            title = { Text(stringResource(R.string.test_workspace_delete_suite)) },
            text = { Text(stringResource(R.string.test_workspace_delete_suite_note)) },
            confirmButton = { TextButton(onClick = {
                runCatching { repository.deleteSuite(id) }
                    .onSuccess { pendingSuiteDelete = null; refreshCases(); error = null }
                    .onFailure { pendingSuiteDelete = null; error = it.message }
            }) { Text(stringResource(R.string.test_workspace_delete)) } },
            dismissButton = { TextButton(onClick = { pendingSuiteDelete = null }) { Text(stringResource(R.string.test_workspace_cancel)) } },
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

object TestWorkspacePreferences {
    private const val FILE = "test-workspace-preferences"
    private const val CHAT_RECOMMENDATIONS = "chat-recommendations"

    fun chatRecommendations(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(CHAT_RECOMMENDATIONS, true)

    fun setChatRecommendations(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(CHAT_RECOMMENDATIONS, enabled).apply()
    }
}

@Composable
private fun ProjectSelector(
    projects: List<TestCaseProject>,
    selectedProjectId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text("${stringResource(R.string.test_workspace_project)}: ${projects.firstOrNull { it.id == selectedProjectId }?.name.orEmpty()} ▾")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        projects.forEach { project ->
            DropdownMenuItem(text = { Text(project.name) }, onClick = {
                expanded = false
                onSelect(project.id)
            })
        }
    }
}

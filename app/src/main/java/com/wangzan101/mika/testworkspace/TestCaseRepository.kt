package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.automation.TestTaskContract
import com.wangzan101.mika.core.automation.TestTaskContracts
import com.wangzan101.mika.core.automation.TestTaskFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class TestCaseProject(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val description: String = "",
    val archived: Boolean = false,
    val targetApp: String? = null,
)

@Serializable
data class TestCaseSuite(val id: String, val projectId: String, val name: String, val createdAtMs: Long, val caseOrder: List<String> = emptyList())

@Serializable
data class TestCaseRevision(val version: Int, val contract: TestTaskContract, val savedAtMs: Long)

@Serializable
data class ManagedTestCase(
    val id: String,
    val projectId: String,
    val suiteId: String,
    val labels: List<String> = emptyList(),
    val archived: Boolean = false,
    val revisions: List<TestCaseRevision>,
) {
    val current: TestCaseRevision get() = revisions.last()
    val currentVersion: Int get() = current.version
}

@Serializable
data class TestCaseExecutionSnapshot(
    val executionId: String,
    val caseId: String,
    val caseVersion: Int,
    val contract: TestTaskContract,
    val startedAtMs: Long,
    val projectId: String = "default",
    val projectName: String = "Default project",
)

@Serializable
private data class TestCaseDatabase(
    val schemaVersion: Int = 1,
    val projects: List<TestCaseProject> = emptyList(),
    val suites: List<TestCaseSuite> = emptyList(),
    val cases: List<ManagedTestCase> = emptyList(),
    val executions: List<TestCaseExecutionSnapshot> = emptyList(),
)

enum class TestCaseImportPolicy { REQUIRE_CHOICE, OVERWRITE_AS_REVISION, COPY, SKIP }
enum class TestCaseImportOutcome { CREATED, REVISED, COPIED, SKIPPED }
data class TestCaseImportResult(val case: ManagedTestCase?, val outcome: TestCaseImportOutcome)

/** Local case library. Revisions and execution snapshots are append-only; deleting a case never deletes Mika runs. */
class TestCaseRepository(
    private val root: File,
    private val legacyTasksDir: File = File(root.parentFile, "test-tasks"),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
) {
    private val file = File(root, "repository.json")
    private val json = Json { prettyPrint = true; explicitNulls = false }
    private var database: TestCaseDatabase

    init {
        root.mkdirs()
        database = if (file.isFile) {
            try {
                json.decodeFromString<TestCaseDatabase>(file.readText())
            } catch (error: Exception) {
                throw IllegalStateException("Could not read test case repository; original data was preserved at ${file.path}", error)
            }
        } else TestCaseDatabase()
        ensureDefaults()
        if (!file.isFile) migrateLegacyTasks()
        persist()
    }

    @Synchronized fun projects(includeArchived: Boolean = false): List<TestCaseProject> =
        database.projects.filter { includeArchived || !it.archived }
    @Synchronized fun project(id: String): TestCaseProject? = database.projects.firstOrNull { it.id == id }
    @Synchronized fun suites(projectId: String): List<TestCaseSuite> = database.suites.filter { it.projectId == projectId }
    @Synchronized fun orderedCases(projectId: String, suiteId: String): List<ManagedTestCase> {
        val suite = database.suites.firstOrNull { it.id == suiteId && it.projectId == projectId } ?: return emptyList()
        val cases = database.cases.filter { it.projectId == projectId && it.suiteId == suiteId && !it.archived }
        val byId = cases.associateBy { it.id }
        return suite.caseOrder.mapNotNull(byId::get) + cases.filter { it.id !in suite.caseOrder }
    }
    @Synchronized fun case(id: String): ManagedTestCase? = database.cases.firstOrNull { it.id == id }
    @Synchronized fun executions(caseId: String? = null): List<TestCaseExecutionSnapshot> =
        database.executions.filter { caseId == null || it.caseId == caseId }.sortedByDescending { it.startedAtMs }
    @Synchronized fun execution(executionId: String): TestCaseExecutionSnapshot? = database.executions.firstOrNull { it.executionId == executionId }

    @Synchronized
    fun search(
        projectId: String? = null,
        suiteId: String? = null,
        query: String = "",
        label: String? = null,
        includeArchived: Boolean = false,
    ): List<ManagedTestCase> = database.cases.asSequence()
        .filter { projectId == null || it.projectId == projectId }
        .filter { suiteId == null || it.suiteId == suiteId }
        .filter { includeArchived || database.projects.none { project -> project.id == it.projectId && project.archived } }
        .filter { includeArchived || !it.archived }
        .filter { label.isNullOrBlank() || label in it.labels }
        .filter { query.isBlank() || it.current.contract.name.contains(query, true) || it.current.contract.command.contains(query, true) || it.labels.any { tag -> tag.contains(query, true) } }
        .sortedBy { it.current.contract.name.lowercase() }
        .toList()

    @Synchronized
    fun createProject(name: String, description: String = "", targetApp: String? = null): TestCaseProject {
        require(name.isNotBlank()) { "project name must not be blank" }
        require(database.projects.none { it.name.equals(name.trim(), true) }) { "project already exists" }
        val project = TestCaseProject(uniqueId("project"), name.trim(), clock(), description.trim(), targetApp = targetApp?.trim()?.ifBlank { null })
        database = database.copy(projects = database.projects + project)
        persist()
        return project
    }

    @Synchronized
    fun renameProject(projectId: String, name: String, description: String? = null): TestCaseProject {
        require(name.isNotBlank()) { "project name must not be blank" }
        require(database.projects.none { it.id != projectId && it.name.equals(name.trim(), true) }) { "project already exists" }
        val old = requireNotNull(database.projects.firstOrNull { it.id == projectId }) { "project does not exist" }
        val updated = old.copy(name = name.trim(), description = description?.trim() ?: old.description)
        database = database.copy(projects = database.projects.map { if (it.id == projectId) updated else it })
        persist()
        return updated
    }

    @Synchronized
    fun updateProject(projectId: String, name: String, description: String, targetApp: String?): TestCaseProject {
        val renamed = renameProject(projectId, name, description)
        val updated = renamed.copy(targetApp = targetApp?.trim()?.ifBlank { null })
        database = database.copy(projects = database.projects.map { if (it.id == projectId) updated else it })
        persist()
        return updated
    }

    @Synchronized
    fun archiveProject(projectId: String, archived: Boolean): TestCaseProject {
        require(projectId != DEFAULT_PROJECT) { "the default project cannot be archived" }
        val old = requireNotNull(database.projects.firstOrNull { it.id == projectId }) { "project does not exist" }
        val updated = old.copy(archived = archived)
        database = database.copy(projects = database.projects.map { if (it.id == projectId) updated else it })
        persist()
        return updated
    }

    @Synchronized
    fun deleteProject(projectId: String): Boolean {
        require(projectId != DEFAULT_PROJECT) { "the default project cannot be deleted" }
        if (database.cases.any { it.projectId == projectId } || database.suites.any { it.projectId == projectId }) {
            error("move or delete project suites and cases first")
        }
        if (database.projects.none { it.id == projectId }) return false
        database = database.copy(projects = database.projects.filterNot { it.id == projectId })
        persist()
        return true
    }

    @Synchronized
    fun createSuite(projectId: String, name: String): TestCaseSuite {
        require(database.projects.any { it.id == projectId }) { "project does not exist" }
        require(name.isNotBlank()) { "suite name must not be blank" }
        require(database.suites.none { it.projectId == projectId && it.name.equals(name.trim(), true) }) { "suite already exists" }
        val suite = TestCaseSuite(uniqueId("suite"), projectId, name.trim(), clock())
        database = database.copy(suites = database.suites + suite)
        persist()
        return suite
    }

    @Synchronized
    fun renameSuite(suiteId: String, name: String): TestCaseSuite {
        require(name.isNotBlank()) { "suite name must not be blank" }
        val old = requireNotNull(database.suites.firstOrNull { it.id == suiteId }) { "suite does not exist" }
        require(database.suites.none { it.id != suiteId && it.projectId == old.projectId && it.name.equals(name.trim(), true) }) { "suite already exists" }
        val updated = old.copy(name = name.trim())
        database = database.copy(suites = database.suites.map { if (it.id == suiteId) updated else it })
        persist()
        return updated
    }

    @Synchronized
    fun deleteSuite(suiteId: String): Boolean {
        require(suiteId != DEFAULT_SUITE) { "the general suite cannot be deleted" }
        if (database.cases.any { it.suiteId == suiteId }) error("move or delete suite cases first")
        if (database.suites.none { it.id == suiteId }) return false
        database = database.copy(suites = database.suites.filterNot { it.id == suiteId })
        persist()
        return true
    }

    @Synchronized
    fun createCase(contract: TestTaskContract, projectId: String = DEFAULT_PROJECT, suiteId: String = DEFAULT_SUITE, labels: List<String> = emptyList()): ManagedTestCase {
        TestTaskContracts.requireValid(contract)
        require(database.cases.none { it.id == contract.id }) { "case id already exists" }
        require(database.projects.any { it.id == projectId }) { "project does not exist" }
        require(database.suites.any { it.id == suiteId && it.projectId == projectId }) { "suite does not exist in project" }
        val record = ManagedTestCase(contract.id, projectId, suiteId, normalizeLabels(labels), revisions = listOf(TestCaseRevision(1, contract, clock())))
        database = database.copy(cases = database.cases + record)
        persist()
        return record
    }

    @Synchronized
    fun revise(caseId: String, contract: TestTaskContract, projectId: String? = null, suiteId: String? = null, labels: List<String>? = null): ManagedTestCase {
        TestTaskContracts.requireValid(contract)
        require(contract.id == caseId) { "case id cannot change when revising" }
        val old = requireNotNull(case(caseId)) { "case does not exist" }
        val nextProject = projectId ?: old.projectId
        val nextSuite = suiteId ?: old.suiteId
        require(database.suites.any { it.id == nextSuite && it.projectId == nextProject }) { "suite does not exist in project" }
        val revised = old.copy(
            projectId = nextProject,
            suiteId = nextSuite,
            labels = labels?.let(::normalizeLabels) ?: old.labels,
            archived = false,
            revisions = old.revisions + TestCaseRevision(old.currentVersion + 1, contract, clock()),
        )
        replaceCase(revised)
        persist()
        return revised
    }

    @Synchronized
    fun importCase(contract: TestTaskContract, policy: TestCaseImportPolicy, projectId: String = DEFAULT_PROJECT, suiteId: String = DEFAULT_SUITE, labels: List<String> = emptyList()): TestCaseImportResult {
        TestTaskContracts.requireValid(contract)
        val existing = case(contract.id)
        if (existing == null) return TestCaseImportResult(createCase(contract, projectId, suiteId, labels), TestCaseImportOutcome.CREATED)
        return when (policy) {
            TestCaseImportPolicy.REQUIRE_CHOICE -> error("case id already exists; choose overwrite, copy, or skip")
            TestCaseImportPolicy.SKIP -> TestCaseImportResult(existing, TestCaseImportOutcome.SKIPPED)
            TestCaseImportPolicy.OVERWRITE_AS_REVISION -> TestCaseImportResult(revise(existing.id, contract, projectId, suiteId, labels), TestCaseImportOutcome.REVISED)
            TestCaseImportPolicy.COPY -> {
                val copyId = uniqueId("case")
                TestCaseImportResult(createCase(contract.copy(id = copyId), projectId, suiteId, labels), TestCaseImportOutcome.COPIED)
            }
        }
    }

    @Synchronized
    fun duplicate(caseId: String, newName: String? = null): ManagedTestCase {
        val source = requireNotNull(case(caseId)) { "case does not exist" }
        val id = uniqueId("case")
        val contract = source.current.contract.copy(id = id, name = newName?.takeIf { it.isNotBlank() } ?: "${source.current.contract.name} copy")
        return createCase(contract, source.projectId, source.suiteId, source.labels)
    }

    @Synchronized
    fun move(caseId: String, projectId: String, suiteId: String): ManagedTestCase {
        val old = requireNotNull(case(caseId)) { "case does not exist" }
        require(database.projects.any { it.id == projectId && !it.archived }) { "target project is archived or missing" }
        require(database.suites.any { it.id == suiteId && it.projectId == projectId }) { "suite does not exist in project" }
        if (old.projectId == projectId && old.suiteId == suiteId) return old
        database = database.copy(suites = database.suites.map { suite ->
            when (suite.id) {
                old.suiteId -> suite.copy(caseOrder = suite.caseOrder.filterNot { it == caseId })
                suiteId -> suite.copy(caseOrder = (suite.caseOrder.filterNot { it == caseId } + caseId))
                else -> suite
            }
        })
        val moved = old.copy(projectId = projectId, suiteId = suiteId)
        replaceCase(moved)
        persist()
        return moved
    }

    @Synchronized
    fun reorderSuiteCases(suiteId: String, orderedCaseIds: List<String>) {
        val suite = requireNotNull(database.suites.firstOrNull { it.id == suiteId }) { "suite does not exist" }
        val activeIds = database.cases.filter { it.suiteId == suiteId && !it.archived }.map { it.id }.toSet()
        require(orderedCaseIds.toSet() == activeIds && orderedCaseIds.size == activeIds.size) { "order must contain each active suite case once" }
        database = database.copy(suites = database.suites.map { if (it.id == suiteId) it.copy(caseOrder = orderedCaseIds) else it })
        persist()
    }

    @Synchronized
    fun archive(caseId: String, archived: Boolean): ManagedTestCase {
        val updated = requireNotNull(case(caseId)) { "case does not exist" }.copy(archived = archived)
        replaceCase(updated)
        persist()
        return updated
    }

    @Synchronized
    fun deleteCase(caseId: String): Boolean {
        if (case(caseId) == null) return false
        database = database.copy(cases = database.cases.filterNot { it.id == caseId })
        persist()
        return true
    }

    @Synchronized
    fun recordExecution(caseId: String, version: Int, executionId: String, startedAtMs: Long = clock()): TestCaseExecutionSnapshot {
        require(database.executions.none { it.executionId == executionId }) { "execution id already exists" }
        val managedCase = requireNotNull(case(caseId)) { "case does not exist" }
        val revision = managedCase.revisions.firstOrNull { it.version == version }
            ?: error("case revision does not exist")
        val project = requireNotNull(project(managedCase.projectId)) { "case project does not exist" }
        val snapshot = TestCaseExecutionSnapshot(executionId, caseId, revision.version, revision.contract, startedAtMs, project.id, project.name)
        database = database.copy(executions = database.executions + snapshot)
        persist()
        return snapshot
    }

    @Synchronized
    fun export(caseId: String, version: Int? = null, format: TestTaskFormat = TestTaskFormat.JSON): String {
        val record = requireNotNull(case(caseId)) { "case does not exist" }
        val revision = version?.let { v -> record.revisions.firstOrNull { it.version == v } ?: error("case revision does not exist") } ?: record.current
        return TestTaskContracts.encode(revision.contract, format)
    }

    private fun ensureDefaults() {
        val projects = if (database.projects.any { it.id == DEFAULT_PROJECT }) database.projects else database.projects + TestCaseProject(DEFAULT_PROJECT, "Default project", clock())
        val suites = if (database.suites.any { it.id == DEFAULT_SUITE }) database.suites else database.suites + TestCaseSuite(DEFAULT_SUITE, DEFAULT_PROJECT, "General", clock())
        database = database.copy(projects = projects, suites = suites)
    }

    private fun migrateLegacyTasks() {
        val migrated = legacyTasksDir.listFiles { candidate -> candidate.isFile && candidate.extension == "json" }.orEmpty().mapNotNull { oldFile ->
            runCatching { json.decodeFromString<TestTaskContract>(oldFile.readText()) }.getOrNull()?.let { contract ->
                runCatching { TestTaskContracts.requireValid(contract) }.getOrNull()?.let {
                    ManagedTestCase(it.id, DEFAULT_PROJECT, DEFAULT_SUITE, revisions = listOf(TestCaseRevision(1, it, oldFile.lastModified())))
                }
            }
        }
        database = database.copy(cases = (database.cases + migrated).distinctBy { it.id })
    }

    private fun uniqueId(prefix: String): String {
        var candidate: String
        do candidate = "$prefix-${newId()}" while (database.projects.any { it.id == candidate } || database.suites.any { it.id == candidate } || database.cases.any { it.id == candidate })
        return candidate
    }

    private fun normalizeLabels(labels: List<String>): List<String> = labels.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
    private fun replaceCase(updated: ManagedTestCase) { database = database.copy(cases = database.cases.map { if (it.id == updated.id) updated else it }) }

    private fun persist() {
        val temporary = File(root, "repository.json.tmp")
        temporary.writeText(json.encodeToString(database))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    companion object {
        const val DEFAULT_PROJECT = "default"
        const val DEFAULT_SUITE = "general"
    }
}

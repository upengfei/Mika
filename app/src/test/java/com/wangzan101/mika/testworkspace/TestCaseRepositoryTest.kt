package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.automation.TestModelPolicy
import com.wangzan101.mika.core.automation.TestTaskContract
import com.wangzan101.mika.core.automation.TestTaskFormat
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TestCaseRepositoryTest {
    private fun contract(id: String = "checkout", command: String = "Open checkout") = TestTaskContract(
        id = id,
        name = "Checkout",
        command = command,
        modelPolicy = TestModelPolicy("fixed-model", "prompt-1"),
    )

    @Test fun createsHierarchyRevisionsAndKeepsOldRunSnapshot() {
        val root = Files.createTempDirectory("case-repository").toFile()
        val repository = TestCaseRepository(root, legacyTasksDir = FileAbsent)
        val project = repository.createProject("Shop")
        val suite = repository.createSuite(project.id, "Checkout")
        val created = repository.createCase(contract(), project.id, suite.id, listOf("smoke", "payment"))
        val snapshot = repository.recordExecution(created.id, 1, "exec-1", 100L)
        val edited = repository.revise(created.id, contract(command = "Open revised checkout"))

        assertEquals(2, edited.currentVersion)
        assertEquals("Open checkout", repository.execution("exec-1")?.contract?.command)
        assertEquals("Open revised checkout", repository.case(created.id)?.current?.contract?.command)
        assertEquals(listOf("smoke", "payment"), edited.labels)
        assertEquals(1, snapshot.caseVersion)
    }

    @Test fun importConflictRequiresAnExplicitPolicy() {
        val repository = TestCaseRepository(Files.createTempDirectory("case-import").toFile(), legacyTasksDir = FileAbsent)
        repository.createCase(contract())

        assertTrue(runCatching { repository.importCase(contract(command = "Changed"), TestCaseImportPolicy.REQUIRE_CHOICE) }.isFailure)
        val copy = repository.importCase(contract(), TestCaseImportPolicy.COPY)
        assertTrue(copy.case?.id != "checkout")
        val overwrite = repository.importCase(contract(command = "Imported revision"), TestCaseImportPolicy.OVERWRITE_AS_REVISION)
        assertEquals(2, overwrite.case?.currentVersion)
        val skipped = repository.importCase(contract(), TestCaseImportPolicy.SKIP)
        assertEquals(TestCaseImportOutcome.SKIPPED, skipped.outcome)
        assertEquals(2, repository.case("checkout")?.revisions?.size)
    }

    @Test fun archiveSearchAndExportUseSelectedRevision() {
        val repository = TestCaseRepository(Files.createTempDirectory("case-export").toFile(), legacyTasksDir = FileAbsent)
        val saved = repository.createCase(contract(), labels = listOf("smoke"))
        repository.revise(saved.id, contract(command = "Second version"))

        assertEquals("Open checkout", repository.export(saved.id, version = 1, TestTaskFormat.JSON).let { com.wangzan101.mika.core.automation.TestTaskContracts.parse(it).command })
        assertTrue(repository.export(saved.id, version = 2, TestTaskFormat.YAML).contains("Second version"))
        assertEquals(listOf(saved.id), repository.search(query = "checkout", label = "smoke").map { it.id })
        repository.archive(saved.id, true)
        assertTrue(repository.search().isEmpty())
        assertEquals(1, repository.search(includeArchived = true).size)
        assertFalse(repository.deleteCase("missing"))
    }

    @Test fun migratesExistingTaskFilesIntoDefaultProjectAndSuite() {
        val root = Files.createTempDirectory("case-migration").toFile()
        val legacy = root.resolve("test-tasks").apply { mkdirs() }
        legacy.resolve("checkout.json").writeText(
            """{"id":"checkout","name":"Checkout","command":"Open checkout","modelPolicy":{"model":"fixed-model","promptVersion":"prompt-1"}}""",
        )
        val repository = TestCaseRepository(root.resolve("case-repository"), legacyTasksDir = legacy)

        val imported = repository.case("checkout")
        assertEquals("default", imported?.projectId)
        assertEquals("general", imported?.suiteId)
        assertEquals(1, imported?.currentVersion)
        assertTrue(legacy.resolve("checkout.json").isFile)
    }

    @Test fun damagedRepositoryIsPreservedAndReportedInsteadOfReplacedWithDefaults() {
        val root = Files.createTempDirectory("damaged-case-repository").toFile()
        root.mkdirs()
        val data = root.resolve("repository.json")
        val damaged = "{ recoverable user data"
        data.writeText(damaged)

        try {
            TestCaseRepository(root, legacyTasksDir = FileAbsent)
            fail("repository corruption should be surfaced")
        } catch (_: IllegalStateException) {
            assertEquals(damaged, data.readText())
        }
    }

    @Test fun archivedProjectHidesItsAssetsAndPermanentDeletionRequiresEmptyProject() {
        val repository = TestCaseRepository(Files.createTempDirectory("project-lifecycle").toFile(), legacyTasksDir = FileAbsent)
        val project = repository.createProject("Shop", "Checkout scenarios")
        val suite = repository.createSuite(project.id, "Payments")
        val testCase = repository.createCase(contract(), project.id, suite.id)

        repository.archiveProject(project.id, true)

        assertTrue(repository.projects().none { it.id == project.id })
        assertEquals("Checkout scenarios", repository.project(project.id)?.description)
        assertTrue(repository.search(projectId = project.id).isEmpty())
        assertEquals(testCase.id, repository.search(projectId = project.id, includeArchived = true).single().id)
        assertTrue(runCatching { repository.deleteProject(project.id) }.isFailure)

        repository.archiveProject(project.id, false)
        assertFalse(repository.project(project.id)?.archived == true)
        repository.deleteCase(testCase.id)
        repository.deleteSuite(suite.id)
        assertTrue(repository.deleteProject(project.id))
    }

    @Test fun projectDefaultsAndMoveKeepRunOwnershipAndSuiteOrder() {
        val repository = TestCaseRepository(Files.createTempDirectory("case-move").toFile(), legacyTasksDir = FileAbsent)
        val shop = repository.createProject("Shop")
        val billing = repository.createProject("Billing")
        repository.updateProject(billing.id, billing.name, billing.description, "com.example.billing")
        val source = repository.createSuite(shop.id, "Checkout")
        val target = repository.createSuite(billing.id, "Payments")
        val first = repository.createCase(contract("first"), shop.id, source.id)
        val second = repository.createCase(contract("second"), shop.id, source.id)
        repository.reorderSuiteCases(source.id, listOf(second.id, first.id))
        assertEquals(listOf(second.id, first.id), repository.orderedCases(shop.id, source.id).map { it.id })

        val snapshot = repository.recordExecution(first.id, 1, "move-exec", 100L)
        repository.move(first.id, billing.id, target.id)

        assertEquals(billing.id, repository.case(first.id)?.projectId)
        assertEquals(1, repository.case(first.id)?.currentVersion)
        assertEquals(shop.id, repository.execution("move-exec")?.projectId)
        assertEquals("Shop", snapshot.projectName)
        assertEquals(listOf(second.id), repository.orderedCases(shop.id, source.id).map { it.id })
        assertEquals(listOf(first.id), repository.orderedCases(billing.id, target.id).map { it.id })
        repository.archiveProject(billing.id, true)
        assertTrue(runCatching { repository.move(second.id, billing.id, target.id) }.isFailure)
    }

    private companion object {
        val FileAbsent = Files.createTempDirectory("no-legacy").resolve("absent").toFile()
    }
}

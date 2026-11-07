package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.automation.TestAssertionKind
import com.wangzan101.mika.core.automation.TestAssertionSpec
import com.wangzan101.mika.core.automation.TestModelPolicy
import com.wangzan101.mika.core.automation.TestTaskContract
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestTaskStoreTest {
    @Test fun savesListsLoadsAndImportsVersionedTasks() {
        val store = TestTaskStore(Files.createTempDirectory("mika-test-tasks").toFile())
        val contract = TestTaskContract(
            id = "checkout",
            command = "完成支付测试",
            assertions = listOf(TestAssertionSpec("payment", TestAssertionKind.HARD, "支付成功")),
            modelPolicy = TestModelPolicy("fixed", "p1"),
        )

        store.save(contract)
        val imported = store.import(
            """
            id: search
            command: 搜索商品
            modelPolicy:
              model: fixed
              promptVersion: p1
            """.trimIndent(),
        )

        assertEquals(contract, store.load("checkout"))
        assertEquals("search", imported.id)
        assertEquals(listOf("checkout", "search"), store.list().map { it.id })
        assertTrue(store.file("checkout").isFile)
    }
}

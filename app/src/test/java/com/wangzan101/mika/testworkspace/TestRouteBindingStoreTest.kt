package com.wangzan101.mika.testworkspace

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestRouteBindingStoreTest {
    @Test fun bindingsAreScopedToAnExactCaseRevisionAndSurviveReload() {
        val file = Files.createTempDirectory("test-route-bindings").resolve("bindings.json").toFile()
        val store = TestRouteBindingStore(file)

        store.bind("checkout", 1, "route-v1")
        store.bind("checkout", 2, "route-v2")

        assertEquals("route-v1", TestRouteBindingStore(file).routeId("checkout", 1))
        assertEquals("route-v2", store.routeId("checkout", 2))
        assertNull(store.routeId("checkout", 3))
    }
}

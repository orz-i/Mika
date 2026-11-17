package com.wangzan101.mika.atlas

import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.replay.FileRouteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRouteStorePackageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun route(
        id: String,
        packages: List<String>,
        used: Long,
        disabled: Boolean = false,
        steps: List<RouteStep> = emptyList(),
    ) = Route(
        id = id,
        template = "模板$id",
        slots = 0,
        packages = packages,
        appVersions = emptyMap(),
        steps = steps,
        disabled = disabled,
        lastUsedMs = used,
    )

    @Test fun routesForPackageFiltersIncludesStepPackagesAndSortsByLastUsedDesc() {
        val store = FileRouteStore(tmp.newFolder("routes"))
        store.put(route("r1", listOf("com.a"), 10L))
        store.put(route("r2", listOf("com.b", "com.a"), 30L))
        store.put(route("r3", listOf("com.b"), 50L))
        store.put(
            route(
                id = "r4",
                packages = listOf("com.b"),
                used = 40L,
                steps = listOf(
                    RouteStep("phone_tap", "{}", null, Anchors("com.c", listOf("设置")), Anchors("com.b", listOf("主页"))),
                ),
            ),
        )

        assertEquals(listOf("r2", "r1"), store.routesForPackage("com.a").map { it.id })
        assertEquals(listOf("r2", "r1"), store.forPackage("com.a").map { it.id })
        assertEquals(listOf("r3", "r4", "r2"), store.routesForPackage("com.b").map { it.id })
        assertEquals(listOf("r4"), store.routesForPackage("com.c").map { it.id })
        assertTrue(store.routesForPackage("com.missing").isEmpty())
        assertTrue(store.routesForPackage(" ").isEmpty())
    }

    @Test fun toggleDisabledAndSetDisabledUpdateStateAndChangesFlow() {
        val store = FileRouteStore(tmp.newFolder("routes"))
        val initialRev = store.changes.value
        store.put(route("r1", listOf("com.a"), 10L, disabled = false))
        assertTrue(store.changes.value > initialRev)

        assertNull(store.toggleDisabled("missing", true))
        val disabled = store.toggleDisabled("r1", true)
        assertEquals(true, disabled?.disabled)
        assertTrue(store.routesForPackage("com.a").single().disabled)

        // 幂等再设一次 true 不重复递增修订号
        val revAfterDisable = store.changes.value
        val stillDisabled = store.toggleDisabled("r1", true)
        assertEquals(true, stillDisabled?.disabled)
        assertEquals(revAfterDisable, store.changes.value)

        store.setDisabled("r1", false)
        assertFalse(store.routesForPackage("com.a").single().disabled)
    }

    @Test fun clearPackageAndRemovePackageDropMatchingRoutesOnly() {
        val store = FileRouteStore(tmp.newFolder("routes"))
        store.put(route("r1", listOf("com.a"), 10L))
        store.put(route("r2", listOf("com.a"), 20L))
        store.put(route("r3", listOf("com.b"), 30L))

        assertEquals(2, store.clearPackage("com.a"))
        assertTrue(store.routesForPackage("com.a").isEmpty())
        assertEquals(listOf("r3"), store.all().map { it.id })
        assertEquals(0, store.clearPackage(" "))
        assertEquals(1, store.removePackage("com.b"))
        assertTrue(store.all().isEmpty())
    }

    @Test fun packageSummariesAggregateCountsAndLastUsed() {
        val store = FileRouteStore(tmp.newFolder("routes"))
        store.put(route("r1", listOf("com.a"), 10L, disabled = false))
        store.put(route("r2", listOf("com.a"), 40L, disabled = true))
        store.put(route("r3", listOf("com.b", "com.a"), 25L, disabled = false))

        assertEquals(setOf("com.a", "com.b"), store.packages())
        val byPkg = store.routesByPackage()
        assertEquals(listOf("r2", "r3", "r1"), byPkg.getValue("com.a").map { it.id })
        assertEquals(listOf("r3"), byPkg.getValue("com.b").map { it.id })

        val summaries = store.packageSummaries()
        assertEquals(
            listOf(
                FileRouteStore.PackageSummary("com.a", routeCount = 3, enabledCount = 2, lastUsedMs = 40L),
                FileRouteStore.PackageSummary("com.b", routeCount = 1, enabledCount = 1, lastUsedMs = 25L),
            ),
            summaries,
        )
    }
}

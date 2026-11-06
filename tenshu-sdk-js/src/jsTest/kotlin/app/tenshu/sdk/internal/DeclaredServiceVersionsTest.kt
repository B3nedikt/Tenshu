@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk.internal

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.schema.migration.InMemoryStoreMigration
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import org.tenshu.sdk.ios.PinnedServiceVersions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ **F7 (`web-app-suite.md` §3): the browser runtime declares exactly the pinned service set** —
 * the web twin of every iOS app's `<App>DeclaredServiceVersionsTest`, against the SAME committed
 * list (`PinnedServiceVersions`, compiled into this test source set by `tenshu-sdk-js/build.gradle.kts`).
 *
 * A service the runtime does not declare has its blocks dropped at ingest while the lane cursor moves
 * past them (`SchemaPullFence`), so a missing plugin is silent, permanent data loss on every browser.
 * And a service bumped without updating the list fails here exactly as it fails on iOS.
 *
 * ⚠ The comparison is against the committed copy, never against the runtime's own plugin list: that
 * would be a test that cannot fail. [aRuntimeMissingAPluginFailsTheGuard] proves this one can.
 */
class DeclaredServiceVersionsTest {

    @Test
    fun theAssembledRuntimeDeclaresExactlyThePinnedServiceVersions() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val problems = pinnedServiceVersionProblems(declaredBy(JsRuntime.schemaRegistry))
        if (problems.isNotEmpty()) {
            fail(
                "The browser runtime does not declare the pinned service versions " +
                    "(web-app-suite.md F7, ios-per-app-frameworks.md §6.2). A missing service loses " +
                    "its rows at ingest. If a service was bumped on purpose, update " +
                    "PinnedServiceVersions in the same PR.\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
    }

    /** The control: the guard above is only worth something if a wrong runtime fails it. */
    @Test
    fun aRuntimeMissingAPluginFailsTheGuard() {
        val withoutCalendar = declaredBy(registryOf(JsRuntimeBuilder.orderedPlugins.filter { it.id != "calendar" }))
        assertEquals(
            listOf("tenshu/calendar/service: pinned v${PinnedServiceVersions.SERVICES.getValue("tenshu/calendar/service")}, NOT DECLARED"),
            pinnedServiceVersionProblems(withoutCalendar),
            "dropping one plugin must fail the guard, naming exactly that service",
        )

        val bumped = declaredBy(JsRuntime.schemaRegistry).toMutableMap()
        bumped["tenshu/file/service"] = bumped.getValue("tenshu/file/service") + 1
        assertTrue(
            pinnedServiceVersionProblems(bumped).single().startsWith("tenshu/file/service: declared v"),
            "a version that moved without the list moving must fail the guard",
        )
    }

    /**
     * ⚠⚠ The no-wipe proof, through the real migrator: a browser store recorded by the pre-F7 runtime
     * (the same plugins minus F7's) opened by this one stamps the new services at current and keeps
     * every row. `IndexedDbObjectStore.open` runs exactly this (`InMemoryStoreMigration`), and wipes on
     * ANY throw from it — so a chain that failed `verify` with the new services registered would also
     * show up here.
     */
    @Test
    fun aStoreRecordedByThePreF7RuntimeIsStampedNotWiped() {
        // ⚠ + "photo": F7 registered it sync-only; Photos run A moved it to `PhotoPlugin`.
        val f7Ids = SyncedServicePlugins.map { it.id }.toSet() + PhotoPlugin.id
        val preF7 = declaredBy(registryOf(JsRuntimeBuilder.orderedPlugins.filter { it.id !in f7Ids }))
        assertTrue(preF7.size < PinnedServiceVersions.SERVICES.size, "the pre-F7 runtime declared a strict subset: $preF7")

        val row = ObjectStore.DbEntry(type = "tenshu/test/opaque", dataIdHash = 42L, fields = emptyList())
        val result = InMemoryStoreMigration(
            StoreSchemaMigrations(context = JsRuntime.tContext, schemas = JsRuntime.schemaRegistry, deviceId = ""),
        ).run(rows = mapOf(row.dataIdHash to row), recordedVersions = preF7)

        assertFalse(result.wiped, "registering a service the store never recorded must not wipe it")
        assertEquals(0, result.changedRows, "and must touch no rows")
        assertEquals(mapOf(row.dataIdHash to row), result.rows)
        assertEquals(PinnedServiceVersions.SERVICES, result.serviceVersions, "the new services are stamped at current")
    }

    private fun registryOf(plugins: List<JsRuntimePlugin>): SchemaRegistry =
        createDefaultSchemaRegistry(
            tContextProvider = { JsRuntime.tContext },
            extraConfigs = plugins.flatMap { it.schemaConfigs },
        )

    // The same computation the store-open gate makes (`IndexedDbObjectStore.open`).
    private fun declaredBy(registry: SchemaRegistry): Map<String, Int> =
        StoreSchemaMigrations(context = JsRuntime.tContext, schemas = registry, deviceId = "")
            .declaredServiceVersions()

    /** Every difference from the pinned list, in the shape the iOS guard prints. Empty = in step. */
    private fun pinnedServiceVersionProblems(declared: Map<String, Int>): List<String> {
        val pinned = PinnedServiceVersions.SERVICES
        return buildList {
            if (TENSHU_STORAGE_FORMAT_VERSION != PinnedServiceVersions.CORE_FORMAT_VERSION) {
                add("tenshu/core: format version is $TENSHU_STORAGE_FORMAT_VERSION, pinned ${PinnedServiceVersions.CORE_FORMAT_VERSION}")
            }
            (pinned.keys - declared.keys).sorted().forEach { add("$it: pinned v${pinned[it]}, NOT DECLARED") }
            (declared.keys - pinned.keys).sorted().forEach { add("$it: declared v${declared[it]}, not pinned") }
            (declared.keys intersect pinned.keys).sorted()
                .filter { declared[it] != pinned[it] }
                .forEach { add("$it: declared v${declared[it]}, pinned v${pinned[it]}") }
        }
    }
}

package org.tenshu.sdk.ios

import org.koin.core.Koin
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.schema.migration.CORE_SERVICE_ID
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import kotlin.test.fail

/**
 * ⚠⚠ The service versions EVERY iOS app's in-process runtime must declare
 * (`docs/proposals/ios-per-app-frameworks.md` D4, §6.2).
 *
 * All route-1 apps open ONE store in the shared App Group. The store-open migrator walks the
 * services a runtime *declares*: one slot behind the store wipes every row of it, and a service a
 * runtime does not declare has its blocks dropped at ingest while the lane cursor moves past them.
 * So an app framework that dropped or changed a service is data loss on a phone, not a build error.
 *
 * This list is compiled into the iOS test source set of every app framework module (by
 * the root `build.gradle.kts`, "iOS app frameworks"), and each module's `DeclaredServiceVersionsTest` asserts
 * that its app's graph — assembled the way the app assembles it — declares exactly this.
 *
 * ⚠ It is a **committed copy on purpose**. Comparing an app's graph with
 * `coreSchemaConfigurations()` instead would be a test that cannot fail: the base runtime's
 * declared versions are that list by construction.
 *
 * **When a service version is bumped** (House Rule 9), update the line here in the same PR. Every
 * app's guard goes red until you do — that is the point: the bump is then visible as an iOS change.
 * ⚠ The guards run in `./gradlew iosTestCheck` (macOS), not in `scripts/run-gate.sh`.
 */
object PinnedServiceVersions {

    /** `:core`'s slot — the store's format version (`TENSHU_STORAGE_FORMAT_VERSION`). */
    const val CORE_FORMAT_VERSION: Int = 1

    /**
     * Service id → declared version, sorted by id. Every service at its v1 release baseline
     * (re-cut 2026-11-05, core-schema-evolution.md Appendix A.6); a failing guard prints the
     * declared list in this shape.
     */
    val SERVICES: Map<String, Int> = mapOf(
        "tenshu/app/service" to 1,
        "tenshu/calendar/service" to 1,
        "tenshu/contact/service" to 1,
        "tenshu/file/service" to 1,
        "tenshu/file/system" to 1,
        "tenshu/health/service" to 1,
        "tenshu/home/device/service" to 1,
        "tenshu/hub/management/service" to 1,
        "tenshu/identity" to 1,
        "tenshu/inference/service" to 1,
        "tenshu/ingest/service" to 1,
        "tenshu/logging/service" to 1,
        "tenshu/mapRegion/service" to 1,
        "tenshu/mia/service" to 1,
        "tenshu/notification/service" to 1,
        // v2: ED-3 `PhotoEdit` (`photos-editing.md` §12).
        "tenshu/photo/service" to 2,
        "tenshu/place/service" to 1,
        "tenshu/preference/service" to 1,
        "tenshu/realmData/service" to 1,
        "tenshu/routine/service" to 1,
        "tenshu/storage/service" to 1,
        "tenshu/sync/scheduleService" to 1,
        "tenshu/sync/service" to 1,
        "tenshu/task/service" to 1,
        "tenshu/timeline/service" to 1,
    )
}

/**
 * The per-service vector [koin]'s store would be opened with — the same computation the shared
 * replica's open makes (`replicaBuildOf`: `StoreSchemaMigrations.declaredServiceVersions`, over the
 * graph's own `SchemaRegistry` and `TContext`).
 */
fun declaredServiceVersionsOf(koin: Koin): Map<String, Int> =
    StoreSchemaMigrations(context = koin.get(), schemas = koin.get(), deviceId = "")
        .declaredServiceVersions()

/** Fails, naming every difference, unless [app]'s graph declares exactly the pinned versions. */
fun assertDeclaresPinnedServiceVersions(app: String, koin: Koin) {
    val declared = declaredServiceVersionsOf(koin)
    val pinned = PinnedServiceVersions.SERVICES
    val problems = buildList {
        if (TENSHU_STORAGE_FORMAT_VERSION != PinnedServiceVersions.CORE_FORMAT_VERSION) {
            add("$CORE_SERVICE_ID: format version is $TENSHU_STORAGE_FORMAT_VERSION, pinned ${PinnedServiceVersions.CORE_FORMAT_VERSION}")
        }
        (pinned.keys - declared.keys).sorted().forEach { add("$it: pinned v${pinned[it]}, NOT DECLARED by $app") }
        (declared.keys - pinned.keys).sorted().forEach { add("$it: declared v${declared[it]} by $app, not pinned") }
        (declared.keys intersect pinned.keys).sorted()
            .filter { declared[it] != pinned[it] }
            .forEach { add("$it: declared v${declared[it]} by $app, pinned v${pinned[it]}") }
    }
    if (problems.isNotEmpty()) {
        fail(
            "$app's route-1 runtime does not declare the pinned service versions " +
                "(ios-per-app-frameworks.md §6.2). Every app shares one store: a slot behind wipes " +
                "it, a missing service loses rows at ingest. If a service was bumped on purpose, " +
                "update PinnedServiceVersions in the same PR.\n" +
                problems.joinToString("\n") { "  - $it" } +
                "\nDeclared by $app:\n" +
                declared.entries.sortedBy { it.key }.joinToString("\n") { "        \"${it.key}\" to ${it.value}," },
        )
    }
}

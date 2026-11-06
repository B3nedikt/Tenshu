package app.tenshu.sdk.internal

import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.home.device.client.homeDeviceServiceClientModule
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.hub.management.client.hubManagementServiceClientModule
import org.tenshu.hub.management.common.hubManagementServiceCommonModule
import org.tenshu.routine.client.routineServiceClientModule
import org.tenshu.routine.common.routineServiceCommonModule

/**
 * The three services behind `Home` on web (`web-app-suite.md` Wave 4, Home run A):
 * `:home-device-service` (devices + their state), `:hub-management-service` (the storage tile) and
 * `:routine-service` (automations + the activity feed). Until this run each was an F7 sync-only
 * entry in `SyncedServicePlugins.kt`; each keeps that schema and gains exactly one thing, the
 * relation maintainer its reads need.
 *
 * ⚠⚠ **Home on web is READ-ONLY** (`web-app-suite.md` §8 items 2 and 9, the iOS route-1 precedent in
 * `home-and-mia-apps.md` §8.1). It is enforced here, below any UI: **no plugin maps an operation**.
 * So every write a native Home makes — `IssueDeviceCommand` (power / level / permit-join),
 * `UpdateHomeDevice` (rename / re-room), `IssueHubCommand` (backup now / restart), `SaveRoutine`,
 * `DeleteRoutine`, `SetRoutineEnabled`, `RunRoutineNow` — fails on web as "no implementation", and so
 * does the executor `EvaluateDueRoutines`. `HomeWebContractTest` dispatches a write and the executor
 * through the real client and requires that failure.
 *
 * ⚠⚠ **No routine evaluation, no background work.** `initRoutineService()` natively is one line,
 * `installRelationMaintainer()` — the engine (`RoutineEngine`, its schedule armer, the device-event
 * host) is wired separately by each platform's `PlatformProxyModule`, and none of it is reachable
 * from here: `:routine-service-proxy` is not even on this module's classpath. The same holds for
 * `initHomeDeviceService()` / `initHubManagementService()`, which are also just the maintainer. This
 * file calls `installRelationMaintainer()` directly rather than adding three `-proxy` modules to the
 * bundle for three one-line functions. `NoBackgroundWorkInATabTest` holds the observer set exact.
 *
 * ⚠ No mergers, as before: the three native `PlatformProxyModule`s register none for these
 * services (every row is last-writer-wins), so the F7 entries carried none either.
 */
private class HomeReadPlugin(
    override val id: String,
    override val schemaConfigs: List<ModuleConfiguration>,
) : JsRuntimePlugin {

    // ⚠ Deliberately no `operationFactory` (the interface default, `null`): see the file KDoc.

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        // Idempotent (one generic maintainer per context): the relation reads, nothing else.
        executionContext.installRelationMaintainer()
    }
}

/**
 * The three Home plugins, built once: [JsRuntimeBuilder.register] is idempotent on identity. Their
 * ids are the F7 entries' ids, so `DeclaredServiceVersionsTest` sees the same set.
 */
internal val HomePlugins: List<JsRuntimePlugin> = listOf(
    HomeReadPlugin(
        id = "routine",
        schemaConfigs = listOf(routineServiceCommonModule, routineServiceClientModule),
    ),
    HomeReadPlugin(
        id = "homeDevice",
        schemaConfigs = listOf(homeDeviceServiceCommonModule, homeDeviceServiceClientModule),
    ),
    HomeReadPlugin(
        id = "hubManagement",
        schemaConfigs = listOf(hubManagementServiceCommonModule, hubManagementServiceClientModule),
    ),
)

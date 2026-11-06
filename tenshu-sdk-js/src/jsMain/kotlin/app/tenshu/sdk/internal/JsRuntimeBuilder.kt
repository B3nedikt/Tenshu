package app.tenshu.sdk.internal

// Registration surface for [JsRuntimePlugin]s (proposal §4.2). In Phase 1 the
// four first-party plugins are seeded eagerly here; Phase 2 moves these seed
// calls into per-service-module top-level `register(...)` statements that fire
// on dynamic import.
//
// [register] is idempotent and ordering-tolerant: re-registering the same
// plugin id is a no-op (so repeated test setup / HMR / re-imports are safe),
// and a conflicting plugin for an already-claimed id fails fast. Cross-plugin
// ordering is resolved by [dependsOn] topological sort at read time.
internal object JsRuntimeBuilder {

    private val plugins = mutableListOf<JsRuntimePlugin>()

    // Set by [JsRuntime] once its graph is live, so registrations that arrive
    // after the runtime is built are folded into the running runtime
    // (proposal §4.3). Null during the eager first-party seed below — those
    // plugins are picked up when the runtime first materializes, not augmented.
    private var augmentHook: ((JsRuntimePlugin) -> Unit)? = null

    init {
        // Phase 1: first-party services. The order is the legacy wiring order —
        // schema-config order matches the monolith exactly, and the operation
        // factory chain keeps sync last so file/notification ops short-circuit
        // before the relay's lazy fires (see [JsRuntime] notes). Storage slots
        // in before sync for the same reason (web-storage-status.md Bucket 1):
        // its factory + init never dial the relay.
        // ContactPlugin declares `dependsOn = {"sync"}`, so the topological sort
        // orders it after SyncPlugin regardless of this list position.
        // TimelinePlugin sits before sync for two reasons — its `initialize`
        // registers Moment/Trip mergers into the registry `SyncPlugin.initialize`
        // hands to `initSyncService`, and its factory never dials the relay. It
        // carries no `dependsOn`, so *this* list position is what orders it (see
        // its KDoc in FirstPartyPlugins.kt).
        //
        // ⚠⚠ F7 (`web-app-suite.md` §3): the sync-only plugins for every other pinned service sit
        // before sync too — schema + mergers, no factory, no init (see SyncedServicePlugins.kt).
        // Without them a browser drops those services' rows at ingest. `DeclaredServiceVersionsTest`
        // holds the assembled set to `PinnedServiceVersions`.
        (
            listOf(
                FilesPlugin, NotificationsPlugin, IdentityPlugin, StoragePlugin,
                PreferencePlugin, TimelinePlugin, PlacePlugin, HealthPlugin,
                CalendarPlugin,
                PhotoPlugin, // Photos run A: ops + relation maintainer, no background work (PhotoPlugin.kt)
                MapRegionPlugin, // W-MAP: catalog + streamed ranges, nothing at init (MapRegionPlugin.kt)
                MiaPlugin, // Mia run A: the planner's `askMia` only, no action, nothing at init (MiaPlugin.kt)
            ) + HomePlugins + // Home run A: schema + relation maintainer, NO ops — read-only (HomePlugin.kt)
                SyncedServicePlugins + listOf(SyncPlugin, ContactPlugin)
        ).forEach(::register)
    }

    fun register(plugin: JsRuntimePlugin) {
        val existing = plugins.firstOrNull { it.id == plugin.id }
        if (existing != null) {
            check(existing === plugin) {
                "Conflicting JsRuntimePlugin registered for id='${plugin.id}'"
            }
            return // idempotent
        }
        plugins += plugin
        augmentHook?.invoke(plugin)
    }

    /** Plugins in [dependsOn]-resolved order; registration order breaks ties. */
    val orderedPlugins: List<JsRuntimePlugin> get() = topologicalSort(plugins)

    /** Called once by [JsRuntime] to receive post-build registrations. */
    fun installAugmentHook(hook: (JsRuntimePlugin) -> Unit) {
        augmentHook = hook
    }

    private fun topologicalSort(input: List<JsRuntimePlugin>): List<JsRuntimePlugin> {
        val byId = input.associateBy { it.id }
        val ordered = mutableListOf<JsRuntimePlugin>()
        val done = mutableSetOf<String>()
        val onStack = mutableSetOf<String>()

        fun visit(plugin: JsRuntimePlugin) {
            if (plugin.id in done) return
            check(plugin.id !in onStack) {
                "Cycle detected in JsRuntimePlugin dependsOn involving '${plugin.id}'"
            }
            onStack += plugin.id
            for (depId in plugin.dependsOn) {
                val dep = byId[depId]
                    ?: error("Plugin '${plugin.id}' dependsOn missing plugin '$depId'")
                visit(dep)
            }
            onStack -= plugin.id
            done += plugin.id
            ordered += plugin
        }

        input.forEach(::visit)
        return ordered
    }
}

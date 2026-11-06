package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.JsRuntimeBuilder
import app.tenshu.sdk.internal.JsRuntimePlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Phase 1 of `docs/proposals/web-sdk-bundle-splitting.md`: unit coverage for
// the plugin-registration seam ([JsRuntimeBuilder] / [JsRuntimePlugin]). These
// are pure builder tests — they don't materialize the runtime graph. The
// behavioural guarantee that the plugin-driven wiring matches the old
// hard-coded wiring is carried by the existing integration suites (FileSystem,
// Notifications, Pairing, Realm, Session, Devices), which dispatch real ops
// through the composed runtime.
//
// Note: [JsRuntimeBuilder] is a process-global singleton already seeded with
// the four first-party plugins. Tests register only *inert* plugins (empty
// schemaConfigs / null-returning factories / no-op init) so the shared runtime
// other suites use is unaffected, and assert against the first-party set by
// filtering rather than by exact list identity.
class JsRuntimePluginTest {

    /** Inert plugin: contributes nothing to the runtime. */
    private class InertPlugin(
        override val id: String,
        override val dependsOn: Set<String> = emptySet(),
    ) : JsRuntimePlugin

    private val firstPartyIds =
        setOf("files", "notifications", "identity", "storage", "timeline", "sync")

    @Test
    fun firstParty_plugins_present_in_legacy_wiring_order() {
        val ordered = JsRuntimeBuilder.orderedPlugins.map { it.id }
        // Filtering ignores any inert plugins other tests may have registered.
        val firstParty = ordered.filter { it in firstPartyIds }
        assertEquals(
            // Storage slots in before sync (web-storage-status.md Bucket 1) so
            // the relay's lazy stays unfired through the storage arm.
            //
            // ⚠⚠ Timeline before sync is a *correctness* constraint, not just a
            // cost one: `initTimelineService` registers MomentMerger/TripMerger
            // into the SemanticMergerRegistry that `SyncPlugin.initialize` hands
            // to `initSyncService`. It carries no `dependsOn` (it needs nothing
            // sync publishes), so this list position is the only thing enforcing
            // it — which is exactly why it is pinned here.
            listOf("files", "notifications", "identity", "storage", "timeline", "sync"),
            firstParty,
            "first-party plugin order must reproduce the monolith's wiring order",
        )
    }

    @Test
    fun register_is_idempotent_for_same_instance() {
        // The four first-party objects are already registered; re-registering
        // the same instance must be a no-op, not a duplicate or a throw.
        val filesPlugin = JsRuntimeBuilder.orderedPlugins.first { it.id == "files" }
        JsRuntimeBuilder.register(filesPlugin)
        JsRuntimeBuilder.register(filesPlugin)
        val filesCount = JsRuntimeBuilder.orderedPlugins.count { it.id == "files" }
        assertEquals(1, filesCount, "re-registering the same plugin must not duplicate it")
    }

    @Test
    fun register_conflicting_id_fails_fast() {
        // A different instance claiming an already-registered id is a wiring
        // bug and must throw rather than silently shadow.
        assertFailsWith<IllegalStateException> {
            JsRuntimeBuilder.register(InertPlugin(id = "files"))
        }
    }

    @Test
    fun dependsOn_reorders_dependency_before_dependent() {
        // Register the dependent first, then its dependency. Topological sort
        // must still place the dependency ahead of the dependent.
        val dependentId = "plugin-test-dependent"
        val dependencyId = "plugin-test-dependency"
        JsRuntimeBuilder.register(InertPlugin(id = dependentId, dependsOn = setOf(dependencyId)))
        JsRuntimeBuilder.register(InertPlugin(id = dependencyId))

        val ordered = JsRuntimeBuilder.orderedPlugins.map { it.id }
        assertTrue(
            ordered.indexOf(dependencyId) < ordered.indexOf(dependentId),
            "dependency must be ordered before the dependent (got $ordered)",
        )
    }

    @Test
    fun post_build_registration_runs_the_augment_hook_without_error() {
        // Force JsRuntime init so its augment hook is installed, then register
        // a plugin afterwards. The hook fires (augment runs); with no facets
        // forced it no-ops, but the path must not throw and the plugin must be
        // visible to subsequent runtime builds.
        JsRuntime.isReady() // triggers JsRuntime object init -> installs hook
        val lateId = "plugin-test-late"
        JsRuntimeBuilder.register(InertPlugin(id = lateId))
        assertTrue(
            JsRuntimeBuilder.orderedPlugins.any { it.id == lateId },
            "a plugin registered after build must be visible in orderedPlugins",
        )
    }
}

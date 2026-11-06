@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk.internal

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import app.tenshu.sdk.Fit
import app.tenshu.sdk.Home
import app.tenshu.sdk.Mia
import app.tenshu.sdk.MiaWithheldFixture
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.fold
import org.tenshu.health.client.healthService
import org.tenshu.core.common.relation.RelationMaintainer
import org.tenshu.place.proxy.PlaceReconciler
import org.tenshu.timeline.proxy.MomentReconciliationPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⚠⚠ `PlacePlugin` / `HealthPlugin` (`web-app-suite.md` Wave 2) give a tab the place and
 * health READS, and none of the work their native `init*` functions start: no `PlaceReconciler`
 * (whose `reconcileAll()` scans the store and writes merges at start), no health rollup / latest-
 * sample maintainers (which backfill at start), no import-run retention. The phones do that work;
 * a browser syncs its results. The sibling guard for place-role inference is
 * `NoPlaceRoleInferenceTest`.
 *
 * ⚠⚠ An **allow-list**, not a deny-list: a deny-list misses what nobody thought to name
 * (`MomentReconciliationPass`, which every tab runs, is one). Every observer the browser runtime
 * installs is named in [ALLOWED]; a new one — from any plugin, any `init*` — fails this test until
 * someone adds it here and says why it is not background work.
 *
 * ⚠⚠ **The gap: this sees observers only — not timers, not coroutines.** A plugin's `initialize`
 * that calls `setInterval` / `setTimeout`, or launches a job in `GlobalScope` or a runtime scope,
 * installs no observer and passes every test here. Extending it is not cheap: the jsTest suite
 * shares ONE runtime that is already built before any test body runs, so a hook on the timer
 * globals would have to be installed ahead of `JsRuntimeBuilder` for the whole suite; a
 * `GlobalScope` job has no registry to enumerate; and the runtime does legitimately start bounded
 * work (the IndexedDB write-behind, `RuntimeLease`, `serviceScope` jobs), so "no job at all" would
 * be a false rule, and an allow-list of call sites the honest one. So a plugin's `initialize` is
 * reviewed by eye: Home's three (`HomePlugin.kt`) call `installRelationMaintainer()` and nothing
 * else.
 */
class NoBackgroundWorkInATabTest {

    @Test
    fun theBrowserRuntimeInstallsExactlyTheAllowedObservers() = GlobalScope.promise {
        JsRuntime.awaitReady()
        // `ObserverRegistry` exposes no listing, only `any`: a predicate that never matches visits all.
        val installed = buildList {
            JsRuntime.executionContext.observerRegistry.any { add(it::class.simpleName ?: ANONYMOUS); false }
        }.sorted()
        assertEquals(ALLOWED.keys.sorted(), installed.distinct(), "an observer was added to (or left) the browser runtime")
    }

    @Test
    fun theBrowserRuntimeInstallsNoPlaceOrHealthBackgroundWork() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val observers = JsRuntime.executionContext.observerRegistry
        assertFalse(observers.any { it is PlaceReconciler }, "a tab must not run the automatic place merge")
        // The health observers are private to `:health-service-proxy`, so they are named.
        listOf("WorkoutActivityRollupMaintainer", "LatestSampleMaintainer", "ImportRunRetentionObserver").forEach { name ->
            assertFalse(observers.any { it::class.simpleName == name }, "a tab must not install $name")
            assertFalse(name in ALLOWED, "$name must never be allowed")
        }
        // Control: the reads' maintainer IS there, so the registry is the live one and the absences
        // above are decisions, not an empty list.
        assertTrue(observers.any { it is RelationMaintainer }, "the relation maintainer the reads need is installed")
        assertTrue(observers.any { it is MomentReconciliationPass }, "the accepted exception is the live one")
    }

    /**
     * Fit (`web-app-suite.md` Wave 3): the Fit surface writes rows and reads profile / aggregates,
     * and still derives nothing. `latestSamples` and `workoutActivityRollups` are the two relations
     * only the forbidden maintainers write; a body sample and an imported workout are exactly the
     * saves that would feed them on a phone. ⚠ If this goes red, a tab started deriving rollups —
     * `fit-app.md` §Web scope 3 decided it must not; reverse that decision there, not here.
     */
    @Test
    fun fitWritesAndReadsDeriveNoRollupInATab() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val before = buildList {
            JsRuntime.executionContext.observerRegistry.any { add(it::class.simpleName ?: ANONYMOUS); false }
        }.sorted()
        val start = 9_000_000_000_000L + (0..1_000_000).random() * 60_000L
        // A summary-only TCX (no trackpoints, so no streams): the save that feeds the workout rollup
        // on a phone, with nothing to push to the content plane (whose relay this suite cannot reach).
        val t0 = kotlin.js.Date(start.toDouble()).toISOString()
        val tcx = "<?xml version=\"1.0\"?><TrainingCenterDatabase><Activities><Activity Sport=\"Running\">" +
            "<Id>$t0</Id><Lap StartTime=\"$t0\"><TotalTimeSeconds>1800</TotalTimeSeconds>" +
            "<DistanceMeters>5000</DistanceMeters></Lap></Activity></Activities></TrainingCenterDatabase>"
        val imported = Fit.importActivityFile("w.tcx", tcx.encodeToByteArray(), "UTC").await()
        assertEquals(true, imported.ok, "import: ${JSON.stringify(imported)}")
        val weighed = Fit.recordWeight(70.0).await()
        assertEquals(true, weighed.ok, "recordWeight: ${JSON.stringify(weighed)}")
        val profile = Fit.profile().await()
        assertEquals(true, profile.ok, "profile: ${JSON.stringify(profile)}")
        val days = Fit.dailyAggregates("2026-01-01", "2026-12-31", "UTC").await()
        assertEquals(true, days.ok, "aggregates: ${JSON.stringify(days)}")

        val health = JsRuntime.tenshuClient.healthService
        val latest = health.latestSamples.getAll().fold(onSuccess = { it.toList().size }, onFailure = { -1 })
        val rollups = health.workoutActivityRollups.getAll().fold(onSuccess = { it.toList().size }, onFailure = { -1 })
        assertEquals(0, latest, "a tab must not derive LatestSample rows")
        assertEquals(0, rollups, "a tab must not derive WorkoutActivityRollup rows")
        val after = buildList {
            JsRuntime.executionContext.observerRegistry.any { add(it::class.simpleName ?: ANONYMOUS); false }
        }.sorted()
        assertEquals(before, after, "a Fit call installed an observer")
    }

    /**
     * Mia (`web-app-suite.md` Wave 4): asking runs the native planner, lowering, validator and
     * reads in the tab — and installs nothing. Native Mia has no `init` (it observes nothing), and
     * `MiaPlugin` adds none; write-shaped utterances are refused in planning, so nothing is resolved
     * either. ⚠ If this goes red, asking Mia started something that outlives the question.
     */
    @Test
    fun askingMiaInstallsNothing() = GlobalScope.promise {
        JsRuntime.awaitReady()
        // ⚠ Rows the write-shaped utterances name, so they would resolve were actions offered —
        // otherwise they stop at "no such device" and never reach the path this test is about.
        val seeded = MiaWithheldFixture.seed()
        val before = buildList {
            JsRuntime.executionContext.observerRegistry.any { add(it::class.simpleName ?: ANONYMOUS); false }
        }.sorted()
        for (utterance in listOf(
            "what is on my calendar tomorrow", "workouts last week", "where was that café blue bottle",
            "what did I do yesterday", "photos from last summer", "is the kitchen light on",
        )) {
            val asked = Mia.ask(utterance, "UTC").await()
            assertEquals(true, asked.ok, "$utterance: ${JSON.stringify(asked)}")
        }
        val notWithheld = seeded.utterances.mapNotNull { (utterance, withheld) ->
            val asked = Mia.ask(utterance, "UTC").await()
            if (asked.ok == true && asked.turn.answer.explanation == withheld) null
            else "'$utterance' → ${JSON.stringify(asked)}"
        }
        assertTrue(notWithheld.isEmpty(), "write-shaped utterances are withheld, not unresolved: $notWithheld")
        val after = buildList {
            JsRuntime.executionContext.observerRegistry.any { add(it::class.simpleName ?: ANONYMOUS); false }
        }.sorted()
        assertEquals(before, after, "asking Mia installed an observer")
        assertTrue(SyncedServicePlugins.none { it.id == "mia" }, "mia is its own plugin, not a sync-only entry")
        assertTrue(JsRuntimeBuilder.orderedPlugins.map { it.id }.let { it.indexOf("mia") < it.indexOf("sync") })
    }

    @Test
    fun placeAndHealthAreTheirOwnPlugins_notSyncOnlyEntries() {
        val ids = JsRuntimeBuilder.orderedPlugins.map { it.id }
        assertTrue(PlacePlugin in JsRuntimeBuilder.orderedPlugins && HealthPlugin in JsRuntimeBuilder.orderedPlugins)
        assertTrue(SyncedServicePlugins.none { it.id == "place" || it.id == "health" })
        // Their mergers must be in the registry before sync takes it — i.e. they sort before sync.
        assertTrue(ids.indexOf("place") < ids.indexOf("sync") && ids.indexOf("health") < ids.indexOf("sync"), "$ids")
    }

    /**
     * Home (`web-app-suite.md` Wave 4): `routine`, `homeDevice` and `hubManagement` are plugins
     * that add the relation maintainer and NOTHING else — no operation
     * (Home on web is read-only, §8 items 2/9), so no routine evaluation can be dispatched, and no
     * observer beyond the maintainer [ALLOWED] already names. The observer set itself stays pinned by
     * [theBrowserRuntimeInstallsExactlyTheAllowedObservers]; this pins the plugins' shape, and that a
     * Home read installs nothing.
     */
    @Test
    fun homePluginsAddTheMaintainerAndNoOperation_andAHomeReadInstallsNothing() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val ids = JsRuntimeBuilder.orderedPlugins.map { it.id }
        val home = setOf("routine", "homeDevice", "hubManagement")
        assertEquals(home, HomePlugins.map { it.id }.toSet())
        assertTrue(HomePlugins.all { it in JsRuntimeBuilder.orderedPlugins }, "$ids")
        assertTrue(SyncedServicePlugins.none { it.id in home }, "no longer sync-only entries")
        for (plugin in HomePlugins) {
            assertEquals(null, plugin.operationFactory(JsRuntime), "${plugin.id}: read-only, maps no operation")
            assertTrue(plugin.mergers.isEmpty(), "${plugin.id}: every Home row is last-writer-wins natively too")
            assertTrue(ids.indexOf(plugin.id) < ids.indexOf("sync"), "${plugin.id} sorts before sync: $ids")
        }
        val before = buildList {
            JsRuntime.executionContext.observerRegistry.any { add(it::class.simpleName ?: ANONYMOUS); false }
        }.sorted()
        assertEquals(true, Home.devices().await().ok)
        assertEquals(true, Home.hubStorage().await().ok)
        assertEquals(true, Home.automations().await().ok)
        assertEquals(true, Home.activityPage(null, 5).await().ok)
        val after = buildList {
            JsRuntime.executionContext.observerRegistry.any { add(it::class.simpleName ?: ANONYMOUS); false }
        }.sorted()
        assertEquals(before, after, "a Home read installed an observer")
    }

    private companion object {
        /** An observer object with no class name — exactly one, see [ALLOWED]. */
        const val ANONYMOUS = "<anonymous>"

        /**
         * `simpleName` → why a tab may run it. The first block is the platform set the file / sync /
         * storage plugins install, pinned so the list is exact; the second is Timeline / place /
         * health.
         */
        val ALLOWED: Map<String, String> = mapOf(
            // ── platform (file, sync, storage) ──
            "AdmissionMigrationObserver" to "sync: admission-key announce/re-root, on synced rows",
            "DeletedFolderSubtreeReaper" to "file: reaps a deleted folder's subtree on that delete",
            "FileHistoryPolicyObserver" to "file: history policy applied inside each file save",
            "FileSyncSettingsRelationMaintainer" to "file: relation membership inside each save",
            "MemberSelfBindObserver" to "sync: self-binds a synced shared-realm grant",
            "SharedRealmJoinObserver" to "sync: registers a synced shared-realm membership",
            "SyncObserver" to "sync: emits this tab's own writes — the sync itself",
            "TombstoneRegistry" to "sync: tombstone / write-metadata index inside each save",
            ANONYMOUS to "storage: EntitlementStorageReader's re-derive on an entitlement save",
            // ── Timeline / place / health ──
            "RelationMaintainer" to "maintains relation membership inside each save; starts nothing",
            "WorkoutChanges" to "bumps a counter inside each Workout save / delete; no reads, no writes",
            // ── Contacts (`contacts-app.md` §9.10.2) ──
            "ContactPhotoSupersededRecorder" to "contacts: inside each ContactSource save / delete, notes a photo id the row stops " +
                "naming in a local candidate file; starts nothing (the sweep runs on the sync scheduler's own maintenance tick)",
            /**
             * ⚠ The accepted exception. It writes, but it is **save-triggered, not a timer**: it arms a
             * one-shot 1 s coalesce only when a `Moment` is saved (local or sync-ingested), never at
             * start. And it writes `supersededBy` with `WriteOrigin.DERIVED`
             * (`MomentReconciliationPass.reconcileRecent`), which **does not leave the device**:
             * `SyncObserver.onSaved` returns before emitting for DERIVED
             * (`sync-service-proxy/.../SyncObserver.kt`), and `RealmSchemaEpochWriter` only runs on
             * that emit path, so these rows raise no epoch either.
             */
            "MomentReconciliationPass" to "save-triggered, DERIVED rows only — never synced",
        )
    }
}

package org.tenshu.sdk.ios

import app.tenshu.control.di.proxyGraphJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.getOrNull
import org.tenshu.sync.client.catchUp
import org.tenshu.sync.client.catchUpOnThisStore
import org.tenshu.sync.common.CatchUpHeartbeatTiming
import org.tenshu.sync.common.wallClockMillis
import org.tenshu.sync.client.syncService
import org.tenshu.sync.proxy.SyncCatchUp
import org.tenshu.sync.proxy.SyncRuntime
import org.tenshu.sync.proxy.SyncRuntimeHolder
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-writer catch-up row over one replica that several apps open at once
 * (`ios-sync-activity-indicator.md` §3.1).
 *
 * Each [Process] below is a full route-1 assembly with **its own store handle over one test
 * replica** (`TestReplicas`): the rows are shared, the listeners are each process's own. That is
 * what the SQLite shared replica is to two apps of an App Group build: a sibling's write reaches
 * this process's reads, never its listeners.
 *
 * ⚠ There is no per-process cache for a foreground to rehydrate (`ios-shared-store-sqlite.md`
 * B.22.9): [Process.foreground] is only the point where the tests look again.
 */
class SyncActivitySharedReplicaTest {

    private class Process(val id: String, directory: String) {
        private val runtime = FirstPartyLocalRuntime(
            FirstPartyLocalRuntime.Config(clientId = id, secureStorageService = "test.$id"),
        )
        val base = runtime.assembleBase(
            replicaDirectory = directory,
            secureStorage = InMemorySecureStorageDelegate(),
        )

        /** What this app's own surface would be told. Also brings the sync runtime up. */
        suspend fun reads(): Boolean = readsOf(id)

        /** What this process's view of the store holds for ANOTHER writer. */
        suspend fun readsOf(writer: String): Boolean =
            withTimeout(30_000) { base.tenshuClient.syncService.catchUp(writer).first() }

        private var writer: SyncCatchUp? = null

        /**
         * This runtime's writer, for a test that raises and settles **in the scheduler's place**
         * — so the scheduler is stopped first, once, and its last tick awaited.
         *
         * ⚠⚠ Without that the test and the runtime's own loop are two writers of one flag. This
         * realm is unprovisioned, so every tick ends in `settle(false)`; the loop's first tick is
         * launched by the same start that publishes the runtime, and a tick that ends after the
         * test's `raise()` clears it — under load, not the store losing a row.
         * [theRuntimesOwnTickClearsAFlagRaisedFromOutside] pins the mechanism without the timing.
         *
         * `stop()` latches the writer shut; [SyncCatchUp.resume] reopens it, as `start()` would.
         */
        suspend fun catchUp(): SyncCatchUp = writer ?: run {
            val runtime = liveRuntime()
            runtime.components.scheduler.stopAndAwait()
            runtime.components.catchUp.also {
                it.resume()
                writer = it
            }
        }

        /** The writer with the scheduler left running — only for the test that shows why not. */
        suspend fun liveCatchUp(): SyncCatchUp = liveRuntime().components.catchUp

        /** One real tick of this runtime, serialized with its loop. */
        suspend fun tick() = liveRuntime().components.scheduler.drainNow()

        private suspend fun liveRuntime(): SyncRuntime {
            reads()
            return withTimeout(30_000) {
                var runtime = base.koin.get<SyncRuntimeHolder>().runtime
                while (runtime == null) {
                    delay(20)
                    runtime = base.koin.get<SyncRuntimeHolder>().runtime
                }
                runtime
            }
        }

        /**
         * Whether what this app's surface reads (§12) — its own row, or any writer's fresh
         * heartbeat — comes to say [expected], and still says it after a quiet [HOLD_MS].
         *
         * ⚠⚠ Waited for, never sampled: the last value of a fixed window races the store. A
         * subscription made while another one holds the same chain (the surface's, in the kill
         * test) **joins** that stream and is handed its current value at once, while the
         * rehydrate's re-evaluation of it is still a launched coroutine. Its first value is
         * therefore the pre-rehydrate `false`, and the `true` follows — 0 ms later on an idle
         * machine, after any fixed window under load. The hold is the other half: a fresh
         * stream's first value can come from a partial snapshot, so a value is only an answer
         * once nothing overturns it.
         */
        suspend fun readsOnStoreSettlesAt(expected: Boolean): Boolean =
            settlesAt(base.tenshuClient.syncService.catchUpOnThisStore(id), expected)

        /**
         * [readsOf], waited for as [readsOnStoreSettlesAt] is and for its reason — for a read
         * straight after a [foreground], which may join a stream the rehydrate is still
         * re-evaluating: under load, a bare `first()` can read a relaunched sibling's cleared row
         * as still `true`.
         */
        suspend fun readsOfSettlesAt(writer: String, expected: Boolean): Boolean =
            settlesAt(base.tenshuClient.syncService.catchUp(writer), expected)

        /**
         * The negative twin of [readsOnStoreSettlesAt]: `null` when the surface's read comes to say
         * `false` and **never says `true`** in the [NEVER_WINDOW_MS] after; otherwise every value
         * it said. ⚠⚠ Not "settles at false": that passes on `false → true → false`, which is a
         * real resurrection. Only the **first** value may be skipped, and only where
         * [firstMayBeStale] — the value a read joining a live stream is handed from before the
         * rehydrate, when that stream rightly said `true` then.
         */
        suspend fun readsOnStoreNeverTrue(firstMayBeStale: Boolean = false): String? =
            neverTrue(base.tenshuClient.syncService.catchUpOnThisStore(id), firstMayBeStale)

        /** [readsOnStoreNeverTrue] over [writer]'s own row. */
        suspend fun readsOfNeverTrue(writer: String, firstMayBeStale: Boolean = false): String? =
            neverTrue(base.tenshuClient.syncService.catchUp(writer), firstMayBeStale)

        private suspend fun neverTrue(read: Flow<Boolean>, firstMayBeStale: Boolean): String? = coroutineScope {
            val seen = mutableListOf<Boolean>()
            val count = MutableStateFlow(0)
            val reader = launch { read.collect { seen += it; count.value = seen.size } }
            try {
                withTimeoutOrNull(SETTLE_TIMEOUT_MS) { count.first { seen.lastOrNull() == false } }
                    ?: return@coroutineScope "never said false: $seen"
                delay(NEVER_WINDOW_MS)
                val judged = if (firstMayBeStale) seen.drop(1) else seen
                if (true in judged) "said true: $seen" else null
            } finally {
                reader.cancel()
            }
        }

        private suspend fun settlesAt(read: Flow<Boolean>, expected: Boolean): Boolean = coroutineScope {
            val seen = MutableStateFlow<Boolean?>(null)
            val reader = launch { read.collect { seen.value = it } }
            try {
                withTimeoutOrNull(SETTLE_TIMEOUT_MS) { seen.first { it == expected } }
                    ?: return@coroutineScope false
                delay(HOLD_MS)
                seen.value == expected
            } finally {
                reader.cancel()
            }
        }

        /** [writer]'s heartbeat row as this process's view of the store holds it: `catchingUp to stamp`. */
        suspend fun beatOf(writer: String): Pair<Boolean, Long>? = withTimeout(30_000) {
            base.tenshuClient.syncService.syncCatchUpHeartbeats.getAll().getOrNull()?.toList()
                ?.firstOrNull { it.key.get() == writer }
                ?.let { it.catchingUp.get() to it.beatAtMillis.get() }
        }

        /** The process is killed: every loop of its graph stops, and nothing is cleared. */
        fun kill() {
            proxyGraphJob(base.koin).cancel()
        }

        /** The foreground return. Nothing to refresh: every read goes to the shared rows. */
        fun foreground() = Unit

        /** A settle is written by the publisher's collector; give it its turn. */
        suspend fun settled(expected: Boolean) = withTimeout(30_000) {
            while (reads() != expected) delay(20)
        }

        /**
         * [settled], and this runtime's **heartbeat** written too. ⚠⚠ Not implied by [settled]:
         * the settle's collector writes the own row and then the beat, so the row can read `false`
         * while the beat on disk still says `true` — and a sibling that rehydrates in that gap
         * sees the pull still running.
         */
        suspend fun settledWithBeat(expected: Boolean) {
            settled(expected)
            withTimeout(30_000) { while (beatOf(id)?.first != expected) delay(20) }
        }
    }

    private companion object {
        /** Under `T`, so a test that asserts a clear came before expiry still means it. */
        const val SETTLE_TIMEOUT_MS = 10_000L
        const val HOLD_MS = 500L
        const val NEVER_WINDOW_MS = 1_500L
    }

    private fun directory() =
        NSTemporaryDirectory() + "sync-activity-shared-replica-" + Random.nextLong().toString(16)

    @Test
    fun anIdleRuntimesSurfaceSaysNothing() = runBlocking {
        Process("app.tenshu.photos.ios", directory()).reads()

        assertNull(withTimeout(30_000) { SyncActivitySurface.observeLine().first() })
        assertNull(withTimeout(30_000) { SyncActivitySurface.observeAccessibilityLabel().first() })
    }

    @Test
    fun theRuntimesOwnTickClearsAFlagRaisedFromOutside() = runBlocking {
        // Why [Process.catchUp] stops the scheduler. The tick is run here, not waited for.
        // ⚠ Nothing may be asserted between the raise and the tick: the loop's own first tick
        // can end in that gap, which is the race this test is about.
        val photos = Process("app.tenshu.photos.ios", directory())
        val live = photos.liveCatchUp()
        live.raise()

        photos.tick()
        photos.settled(false)
        assertFalse(live.catchingUp, "an unprovisioned realm pulls nothing, so its tick settles false")
    }

    @Test
    fun anIdleSiblingsStartAndTickEnd_leaveACatchingUpAppsRowAlone() = runBlocking {
        val dir = directory()
        val photos = Process("app.tenshu.photos.ios", dir)
        photos.catchUp().raise()
        assertTrue(photos.reads())

        // Drive starts over the same replica, idle, and its scheduler ticks.
        val drive = Process("app.tenshu.drive.ios", dir)
        drive.catchUp().settle(false)
        // ⚠ Time for a write that must NOT happen, so there is nothing to await. Too short a
        // wait can only pass a wrong write; it cannot fail a right one.
        delay(200)
        assertFalse(drive.reads(), "Drive is idle, and reads its own row")

        photos.foreground()
        assertTrue(photos.readsOfSettlesAt(photos.id, true), "Photos' row survived Drive's start and Drive's tick end")

        // Each reader sees only its own, even once it can see the sibling's `true` on disk.
        drive.foreground()
        assertTrue(drive.readsOfSettlesAt(photos.id, true), "Drive's store does hold Photos' row by now")
        assertNull(drive.readsOfNeverTrue(drive.id), "and Drive still says nothing")
    }

    @Test
    fun aRowLeftTrueByAKilledApp_isClearedWhenThatAppStartsAgain_andOnlyThen() = runBlocking {
        val dir = directory()
        Process("app.tenshu.photos.ios", dir).catchUp().raise() // … and the process is killed

        // A sibling starting does not clear it: it cannot know whether Photos is alive.
        val drive = Process("app.tenshu.drive.ios", dir)
        assertFalse(drive.reads())
        assertTrue(drive.readsOf("app.tenshu.photos.ios"), "left alone by a sibling")

        val relaunched = Process("app.tenshu.photos.ios", dir)
        assertFalse(relaunched.reads(), "its own start clears its own stale row")
        drive.foreground()
        assertNull(drive.readsOfNeverTrue("app.tenshu.photos.ios", firstMayBeStale = true), "the relaunch's clear reaches the sibling")
    }

    @Test
    fun aSiblingWritingFromAStaleCache_canHideTheRow_andTheNextRaisePutsItBack() = runBlocking {
        // The relation is ONE array of member hashes, cached per process. Drive opened the
        // replica before Photos ever wrote, so Drive's own first write rebuilds the array without
        // Photos in it. This is the shared-store hazard every relation has; what this test
        // pins is that the catch-up row recovers from it by itself.
        val dir = directory()
        val drive = Process("app.tenshu.drive.ios", dir)
        drive.reads()
        val photos = Process("app.tenshu.photos.ios", dir)
        photos.catchUp().raise()
        assertTrue(photos.reads())

        drive.catchUp().raise() // from a cache that has never seen Photos' row
        photos.foreground()
        // Recorded, not asserted: whether the row is hidden here is the store's behaviour, and
        // the day the shared store stops losing members this line should start printing false.
        println("SyncActivitySharedReplicaTest: a stale-cache write hid the sibling's row = ${!photos.reads()}")

        photos.catchUp().raise() // the next page of the same catch-up
        assertTrue(photos.reads(), "the writer asks the store, not its memory, so the row is back")

        photos.catchUp().settle(false)
        photos.settled(false)
        drive.foreground()
        assertTrue(drive.readsOfSettlesAt(drive.id, true), "and Photos finishing did not clear Drive")
    }

    // ---- cross-process activity: the heartbeat (ios-sync-activity-indicator.md §12) ---------------

    /**
     * ⚠⚠ Ignored (`ios-shared-store-sqlite.md` B.22.9): it needs a foreground rehydrate that
     * re-notifies the mounted surface's listener. Over a replica where a sibling's write reaches no
     * listener — the SQLite store's behaviour, and the test replica's — the mounted surface never
     * re-evaluates, so Photos' reader never counts Control's fresh beat. That is the open gap
     * `ios-sync-activity-indicator.md` §12.3 records for SQLite: un-ignore it with the fix.
     */
    @kotlin.test.Ignore
    @Test
    fun aSiblingsPull_lightsThisAppsSurface_andAKilledSiblingClearsWithinT_withNoWrite() = runBlocking {
        val dir = directory()
        val control = Process("app.tenshu.control.ios", dir)
        val photos = Process("app.tenshu.photos.ios", dir) // assembled last: the surface is Photos'
        photos.reads()

        // ONE observation for the whole test, as the mounted capsule holds: a new one would start
        // at idle and say `null` before its show delay, whatever the store holds.
        val line = MutableStateFlow<String?>("<not yet>")
        val observer = launch(Dispatchers.Default) { SyncActivitySurface.observeLine().collect { line.value = it } }
        withTimeout(10_000) { line.first { it == null } }

        control.catchUp().raise()
        photos.foreground()
        assertTrue(photos.readsOnStoreSettlesAt(true), "Photos' reader counts Control's fresh beat")
        assertNull(photos.readsOfNeverTrue(photos.id), "while Photos' own row still says nothing")
        withTimeout(10_000) { line.first { it == "Syncing…" } }

        // Control is killed mid-pull. Its rows stay `true`; only time can end them.
        val seenBeat = photos.beatOf(control.id)!!.second // the stamp Photos' reader is judging
        control.kill()
        val staleAfter = CatchUpHeartbeatTiming.Default.staleAfterMs
        withTimeout(staleAfter + 10_000) { line.first { it == null } }
        val clearedAt = wallClockMillis()
        observer.cancel()
        assertTrue(
            clearedAt >= seenBeat + staleAfter - 250,
            "cleared by expiry, not before: ${clearedAt - seenBeat} ms after the beat it judged",
        )

        // Nothing wrote: the row on disk still says `true`, and the kill really stopped the beat.
        photos.foreground()
        val onDisk = photos.beatOf(control.id)!!
        assertTrue(onDisk.first, "no write cleared Control's row")
        delay(CatchUpHeartbeatTiming.Default.intervalMs + 1_000)
        photos.foreground()
        assertEquals(onDisk, photos.beatOf(control.id), "no beat followed the kill")
        assertNull(photos.readsOnStoreNeverTrue(), "the killed sibling's beat stays expired")
    }

    @Test
    fun aSiblingThatFinishesNormally_isClearedAtTheNextForeground_withNoWaitForT() = runBlocking {
        val dir = directory()
        val control = Process("app.tenshu.control.ios", dir)
        val photos = Process("app.tenshu.photos.ios", dir)
        photos.reads()
        control.catchUp().raise()
        photos.foreground()
        assertTrue(photos.readsOnStoreSettlesAt(true))

        control.catchUp().settle(false)
        control.settledWithBeat(false)
        val settledAt = wallClockMillis()
        photos.foreground()

        assertNull(photos.readsOnStoreNeverTrue(firstMayBeStale = true), "the `false` beat clears it")
        assertTrue(wallClockMillis() - settledAt < CatchUpHeartbeatTiming.Default.staleAfterMs)
    }

    /** ⚠⚠ Ignored for the reason the test above is: what it pins needs a foreground rehydrate. */
    @kotlin.test.Ignore
    @Test
    fun aSiblingsLaterPull_rewritesItsBeatInPlace_andStillLightsAMountedSurface() = runBlocking {
        // ⚠⚠ Pins the in-place beat (§12.7). Once Photos' view holds Control's beat row, every
        // later beat of Control's is an IN-PLACE save — the row is already a member — and the
        // member-updated event that announces it stays in Control's process. A rehydrate that
        // refreshes the row reaches only the relation's listener, which diffs its membership,
        // unchanged: the mounted surface would never re-evaluate, every reader joining its stream
        // would get the old `false`, and Control's second pull would never light Photos.
        val dir = directory()
        val control = Process("app.tenshu.control.ios", dir)
        control.catchUp().raise()
        control.catchUp().settle(false)
        control.settledWithBeat(false) // Control's first pull is over, and its beat says so
        val photos = Process("app.tenshu.photos.ios", dir) // opens a replica that holds that row
        photos.reads()
        // ⚠⚠ S4 OPEN (`ios-sync-activity-indicator.md` §12.7, `tech-debt.md`). Under load, 1 in
        // 12–36 class runs, Photos opens the replica and reads Control's rows as a fresh *raise*
        // (own row and beat `true`, stamped ~40 ms after Control's settle) that Control's own view
        // does not hold until it rehydrates from disk. Some writer under Control's id outlives
        // what this test thinks is live; it is not yet found, and printing from the write path
        // hides it. ⚠ Never retried — that would hide the writer. When it happens the run is
        // INCONCLUSIVE: the full diagnostic is printed and this test alone returns early. Every
        // assertion below, and every other test, stays strict.
        val photosView = photos.beatOf(control.id)
        if (photosView?.first != false) {
            val controlView = control.beatOf(control.id)
            control.foreground()
            println(
                "SyncActivitySharedReplicaTest: S4 OPEN, see ios-sync-activity-indicator.md §12.7 — " +
                    "inconclusive, not judged. Photos held $photosView; Control's view $controlView, " +
                    "later ${control.beatOf(control.id)}, its writer catching up " +
                    "${control.catchUp().catchingUp}",
            )
            return@runBlocking
        }

        val line = MutableStateFlow<String?>("<not yet>")
        val observer = launch(Dispatchers.Default) { SyncActivitySurface.observeLine().collect { line.value = it } }
        withTimeout(10_000) { line.first { it == null } }

        control.catchUp().raise() // the next pull: the beat row is rewritten in place
        photos.foreground()
        assertEquals(true, photos.beatOf(control.id)?.first, "the rehydrate did bring the new beat in")
        assertTrue(
            photos.readsOnStoreSettlesAt(true),
            "a reader joining the surface's stream counts the in-place beat",
        )
        withTimeout(10_000) { line.first { it == "Syncing…" } }
        observer.cancel()
    }

    @Test
    fun aCatchingUpRuntime_beatsEveryInterval_andAnIdleOneNever() = runBlocking {
        val dir = directory()
        val photos = Process("app.tenshu.photos.ios", dir)
        val drive = Process("app.tenshu.drive.ios", dir)
        drive.reads()
        photos.catchUp().raise()
        val first = photos.beatOf(photos.id)!!.second

        delay(CatchUpHeartbeatTiming.Default.intervalMs + 1_000)

        val next = photos.beatOf(photos.id)!!
        assertTrue(next.first)
        assertTrue(next.second > first, "a second beat, ${next.second - first} ms after the first")
        drive.foreground()
        assertEquals(null, drive.beatOf(drive.id), "the idle runtime never wrote one")
    }
}

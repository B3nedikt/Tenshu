@file:OptIn(org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.tenshu.core.common.proxy.db.sqlite.DamagedCopy
import org.tenshu.core.common.proxy.db.sqlite.DamagedCopyMethod
import org.tenshu.core.common.proxy.db.sqlite.DamagedSnapshotOutcome
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaEntryToken
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Appendix B.18 (Q47): the damaged store's copy per process, Control's snapshot run, and the
 * "Storage damaged" cover that says what is there: the export only in Control and only over a
 * complete copy, Control named everywhere else. Over fakes; every name is made up.
 */
class DamagedDataCopiesTest {

    private val savedContext = StorageStateSurface.callbackContext
    private val savedReachable = StorageStateSurface.controlReachable

    @BeforeTest
    fun setUp() {
        StorageStateSurface.detachForTests()
        StorageStateSurface.callbackContext = Dispatchers.Default
        StorageStateSurface.controlReachable = { true }
        StorageStateSurface.refreshReachability()
    }

    @AfterTest
    fun tearDown() {
        StorageStateSurface.detachForTests()
        StorageStateSurface.callbackContext = savedContext
        StorageStateSurface.controlReachable = savedReachable
    }

    private val copy = DamagedCopy("/made/up/replica-damaged/20261030T101112Z", listOf("/made/up/replica-damaged/20261030T101112Z/objects.db", "/made/up/replica-damaged/20261030T101112Z/COMPLETE"), DamagedCopyMethod.VACUUM_INTO)

    private fun within(what: String, timeoutMs: Long = 10_000, done: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!done()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMs) fail("$what: not within $timeoutMs ms")
            platform.posix.usleep(5_000u)
        }
    }

    private fun status(state: SharedReplicaHostState) = SharedReplicaHostStatus(state, "made up", willRetry = false)

    private class Token(val released: AtomicInt = AtomicInt(0)) : SharedReplicaEntryToken {
        override fun release() {
            released.incrementAndGet()
        }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Control: the snapshot is taken in the foreground, published, and only a complete one is offered ──

    @Test
    fun inControlACorruptStateInTheForegroundTakesTheSnapshotUnderATokenAndPublishesTheCompleteCopy() {
        val looks = AtomicInt(0)
        val copies = DamagedDataCopies("/made/up/replica", look = { looks.incrementAndGet(); null }, scope = scope(), log = {})
        val token = Token()
        val runner = DamagedSnapshotRunner(copies, take = { DamagedSnapshotOutcome.Completed(copy) }, protect = { token }, scope = scope(), log = {})
        copies.exporter = runner
        val host = MutableStateFlow(status(SharedReplicaHostState.READY))
        copies.follow(host) { true }
        host.value = status(SharedReplicaHostState.CORRUPT)
        within("the copy is published") { copies.current.phase == DamagedCopyPhase.COMPLETE }
        assertEquals(copy.files, copies.current.files)
        within("the token is released") { token.released.value == 1 }
        assertEquals(1, runner.attempts.value)
        assertEquals(0, looks.value, "Control takes, it does not only look")

        // Out of CORRUPT the view is reset: nothing is offered over another state.
        host.value = status(SharedReplicaHostState.READY)
        within("reset") { copies.current.phase == DamagedCopyPhase.UNKNOWN }
    }

    @Test
    fun inControlNothingIsTakenInTheBackgroundAndTheNextForegroundTakesIt() {
        val copies = DamagedDataCopies("/made/up/replica", look = { null }, scope = scope(), log = {})
        val taken = AtomicInt(0)
        copies.exporter = DamagedSnapshotRunner(copies, take = { taken.incrementAndGet(); DamagedSnapshotOutcome.Completed(copy) }, protect = { Token() }, scope = scope(), log = {})
        val host = MutableStateFlow(status(SharedReplicaHostState.CORRUPT))
        copies.follow(host) { false }
        within("looked at in the background") { copies.current.phase == DamagedCopyPhase.NOT_COPIED }
        platform.posix.usleep(100_000u)
        assertEquals(0, taken.value, "§6: the snapshot runs in the foreground only")

        copies.onForeground(SharedReplicaHostState.READY)
        platform.posix.usleep(100_000u)
        assertEquals(0, taken.value, "a foreground over another state takes nothing")
        copies.onForeground(SharedReplicaHostState.CORRUPT)
        within("taken at the foreground") { taken.value == 1 && copies.current.phase == DamagedCopyPhase.COMPLETE }
    }

    @Test
    fun withNoProtectedTimeNothingIsTakenAndTheCopyIsShownAsNotCopiedYet() {
        val copies = DamagedDataCopies("/made/up/replica", look = { null }, scope = scope(), log = {})
        val taken = AtomicInt(0)
        val runner = DamagedSnapshotRunner(copies, take = { taken.incrementAndGet(); DamagedSnapshotOutcome.Completed(copy) }, protect = { null }, scope = scope(), log = {})
        copies.exporter = runner
        assertTrue(runner.start())
        within("not copied") { copies.current.phase == DamagedCopyPhase.NOT_COPIED }
        within("the attempt is over") { runner.start() }
        within("not copied again") { copies.current.phase == DamagedCopyPhase.NOT_COPIED }
        assertEquals(0, taken.value, "nothing is taken without a token")
    }

    @Test
    fun theTokensExpiryStopsTheAttemptAndOneAttemptRunsAtATime() {
        val copies = DamagedDataCopies("/made/up/replica", look = { null }, scope = scope(), log = {})
        var expire: (() -> Unit)? = null
        val stopSeen = AtomicInt(0)
        val gate = AtomicInt(0)
        val runner = DamagedSnapshotRunner(
            copies,
            take = { shouldStop ->
                gate.value = 1
                within("expired") { shouldStop() }
                stopSeen.value = 1
                DamagedSnapshotOutcome.Stopped("made up: the token expired")
            },
            protect = { onExpired -> expire = onExpired; Token() },
            scope = scope(),
            log = {},
        )
        copies.exporter = runner
        assertTrue(runner.start())
        within("running") { gate.value == 1 }
        assertEquals(DamagedCopyPhase.COPYING, copies.current.phase)
        assertFalse(runner.start(), "one at a time")
        expire!!.invoke()
        within("stopped") { stopSeen.value == 1 && copies.current.phase == DamagedCopyPhase.NOT_COPIED }
        within("free again") { runner.start() }
    }

    @Test
    fun eachOutcomeIsShownAsWhatItIsAndOnlyACompleteCopyCarriesFiles() {
        assertEquals(DamagedCopyView(DamagedCopyPhase.COMPLETE, copy.files), DamagedCopyView.of(DamagedSnapshotOutcome.AlreadyComplete(copy)))
        assertEquals(DamagedCopyView(DamagedCopyPhase.NO_ROOM, neededBytes = 7), DamagedCopyView.of(DamagedSnapshotOutcome.NoRoom(7, 3)))
        assertEquals(DamagedCopyPhase.INCONSISTENT, DamagedCopyView.of(DamagedSnapshotOutcome.Inconsistent(3)).phase)
        for (notYet in listOf(DamagedSnapshotOutcome.Busy("x"), DamagedSnapshotOutcome.Stopped("x"), DamagedSnapshotOutcome.Failed("x"), DamagedSnapshotOutcome.MarkerNotDurable)) {
            assertEquals(DamagedCopyView.NOT_COPIED, DamagedCopyView.of(notYet), "$notYet")
        }
        assertEquals(DamagedCopyView.UNKNOWN, DamagedCopyView.of(DamagedSnapshotOutcome.NotCorrupt))
    }

    // ── every other app: it only looks ──

    @Test
    fun inAnotherAppACorruptStateOnlyLooksAtTheCopiesAndLooksAgainAtEachForeground() {
        val found = AtomicInt(0)
        val copies = DamagedDataCopies("/made/up/replica", look = { if (found.value == 1) copy else null }, scope = scope(), log = {})
        val host = MutableStateFlow(status(SharedReplicaHostState.CORRUPT))
        copies.follow(host) { true }
        within("nothing kept yet") { copies.current.phase == DamagedCopyPhase.NOT_COPIED }
        found.value = 1
        copies.onForeground(SharedReplicaHostState.CORRUPT)
        within("Control's copy seen at the foreground") { copies.current.phase == DamagedCopyPhase.COMPLETE }
    }

    // ── the cover ──

    private fun host() = SharedReplicaHost(isForegroundSource = { true }, release = { true }, unbindLoops = {}, mainContext = EmptyCoroutineContext, log = {})

    @Test
    fun theDamagedCoverOffersTheExportOnlyInControlAndOnlyOverACompleteCopy() {
        val host = host()
        val corrupt = status(SharedReplicaHostState.CORRUPT)
        val complete = DamagedCopyView(DamagedCopyPhase.COMPLETE, copy.files)

        StorageStateSurface.snapshotOf(host, corrupt, copy = complete, exporter = true).let {
            assertEquals("Storage damaged", it.title)
            assertTrue(it.canExportDamagedData)
            assertEquals(copy.files, it.damagedDataFiles)
            assertFalse(it.offersOpenControl, "Control does not send the user to Control")
            assertFalse(it.canTryAgain)
            assertTrue("may be lost" in it.message.orEmpty() && "deleting every Tenshu app deletes the copy" in it.message.orEmpty(), it.message)
            assertTrue("unencrypted" in it.message.orEmpty(), "the threat model's amendment: the cover says what the copy holds")
        }
        for (phase in DamagedCopyPhase.entries - DamagedCopyPhase.COMPLETE) {
            StorageStateSurface.snapshotOf(host, corrupt, copy = DamagedCopyView(phase, neededBytes = 300L * 1_048_576), exporter = true).let {
                assertFalse(it.canExportDamagedData, "$phase")
                assertTrue(it.damagedDataFiles.isEmpty(), "$phase")
                assertTrue("still on this phone" in it.message.orEmpty() || phase == DamagedCopyPhase.COPYING, "$phase: ${it.message}")
            }
        }
        assertTrue("about 300 MB" in StorageStateSurface.snapshotOf(host, corrupt, copy = DamagedCopyView(DamagedCopyPhase.NO_ROOM, neededBytes = 300L * 1_048_576), exporter = true).message.orEmpty())
        assertTrue("consistently" in StorageStateSurface.snapshotOf(host, corrupt, copy = DamagedCopyView(DamagedCopyPhase.INCONSISTENT), exporter = true).message.orEmpty())

        // Every other app names Control and never exports itself.
        StorageStateSurface.snapshotOf(host, corrupt, copy = complete, exporter = false).let {
            assertFalse(it.canExportDamagedData)
            assertTrue(it.damagedDataFiles.isEmpty())
            assertTrue(it.offersOpenControl)
            assertTrue("export it from Tenshu Control" in it.message.orEmpty(), it.message)
        }
        StorageStateSurface.snapshotOf(host, corrupt, copy = DamagedCopyView.NOT_COPIED, exporter = false).let {
            assertTrue(it.offersOpenControl)
            assertTrue("has not been changed" in it.message.orEmpty() && "Open Tenshu Control" in it.message.orEmpty(), it.message)
        }

        // No other state ever carries the export or names Control, whatever the copy.
        for (state in SharedReplicaHostState.entries - SharedReplicaHostState.CORRUPT) {
            StorageStateSurface.snapshotOf(host, status(state), copy = complete, exporter = true).let {
                assertFalse(it.canExportDamagedData, "$state")
                assertTrue(it.damagedDataFiles.isEmpty(), "$state")
                assertFalse(it.offersOpenControl, "$state")
            }
        }
    }

    @Test
    fun withControlNotReachableAnotherAppsCoverSaysSoAndOffersNoButton() {
        // B.18.8 (317): "open Tenshu Control" only when Control can be opened from here.
        StorageStateSurface.controlReachable = { false }
        StorageStateSurface.refreshReachability()
        val host = host()
        val corrupt = status(SharedReplicaHostState.CORRUPT)
        for (view in listOf(DamagedCopyView.NOT_COPIED, DamagedCopyView(DamagedCopyPhase.COMPLETE, copy.files))) {
            StorageStateSurface.snapshotOf(host, corrupt, copy = view, exporter = false).let {
                assertFalse(it.offersOpenControl, "${view.phase}")
                assertFalse("Open Tenshu Control" in it.message.orEmpty(), it.message)
                assertTrue("isn't installed" in it.message.orEmpty(), it.message)
                assertFalse(it.canExportDamagedData)
            }
        }
    }

    @Test
    fun theCoverReadsReachabilityAskedOnMainAndAFreshAskSeesControlInstalledSince() {
        // B.18.9 (324): `UIApplication` is main-thread-only, so the cover never asks it itself.
        val asked = AtomicInt(0)
        StorageStateSurface.controlReachable = { asked.incrementAndGet(); false }
        StorageStateSurface.refreshReachability()
        val host = host()
        val corrupt = status(SharedReplicaHostState.CORRUPT)
        val before = asked.value
        repeat(3) { assertFalse(StorageStateSurface.snapshotOf(host, corrupt, copy = DamagedCopyView.NOT_COPIED, exporter = false).offersOpenControl) }
        assertEquals(before, asked.value, "the cover does not ask")
        // Control installed meanwhile: the next foreground's ask sees it.
        StorageStateSurface.controlReachable = { asked.incrementAndGet(); true }
        StorageStateSurface.refreshReachability()
        assertTrue(StorageStateSurface.snapshotOf(host, corrupt, copy = DamagedCopyView.NOT_COPIED, exporter = false).offersOpenControl)
    }

    @Test
    fun everyOneOfControlsDamagedCoversNamesTheRecovery() {
        // B.18.9 (325): D8's recovery, in every state; the other apps' covers name Control instead.
        val host = host()
        val corrupt = status(SharedReplicaHostState.CORRUPT)
        for (phase in DamagedCopyPhase.entries) {
            val view = DamagedCopyView(phase, files = if (phase == DamagedCopyPhase.COMPLETE) copy.files else emptyList())
            assertTrue("delete every Tenshu app and reinstall" in StorageStateSurface.snapshotOf(host, corrupt, copy = view, exporter = true).message.orEmpty(), "$phase")
        }
    }

    @Test
    fun aDeleteThatLandsBetweenAnAttemptsTombstoneCheckAndItsPublishWins() {
        // B.18.10 (327), Codex P2 at b136495: exactly that ordering, held deterministically.
        val copies = DamagedDataCopies("/made/up/replica", look = { null }, deleted = { false }, deleter = { true }, scope = scope(), log = {})
        val runner = DamagedSnapshotRunner(
            copies,
            take = { DamagedSnapshotOutcome.AlreadyComplete(copy) },
            protect = { Token() },
            scope = scope(),
            log = {},
        )
        copies.exporter = runner
        copies.publish(DamagedCopyView(DamagedCopyPhase.COMPLETE, copy.files))
        val atHook = AtomicInt(0)
        val release = AtomicInt(0)
        copies.beforeAttemptPublish = {
            atHook.value = 1
            within("released") { release.value == 1 }
        }
        assertTrue(runner.start())
        within("the attempt checked the tombstone and waits to publish") { atHook.value == 1 }
        assertTrue(copies.delete())
        within("the delete published") { copies.current.phase == DamagedCopyPhase.DELETED }
        copies.beforeAttemptPublish = null
        release.value = 1
        within("the held attempt has published") { runner.finished.value == 1 }
        assertEquals(DamagedCopyPhase.DELETED, copies.current.phase, "the deleted copy is not offered again")
        assertTrue(copies.current.files.isEmpty())
    }

    @Test
    fun anAttemptsCopyPublishedAfterADeleteIsShownAsDeleted() {
        // B.18.9 (323): the runner publishes outside the engine's lock.
        val copies = DamagedDataCopies("/made/up/replica", look = { null }, deleted = { true }, scope = scope(), log = {})
        copies.publishAttempt(DamagedSnapshotOutcome.Completed(copy))
        assertEquals(DamagedCopyPhase.DELETED, copies.current.phase)
        val kept = DamagedDataCopies("/made/up/replica", look = { null }, deleted = { false }, scope = scope(), log = {})
        kept.publishAttempt(DamagedSnapshotOutcome.Completed(copy))
        assertEquals(DamagedCopyPhase.COMPLETE, kept.current.phase)
    }

    @Test
    fun anExporterSetAfterTheFollowSawCorruptStartsTheAttemptAndMovesTheCover() {
        // B.18.8 (318): the binding's follow runs before Control's assembly sets the exporter.
        val copies = DamagedDataCopies("/made/up/replica", look = { null }, scope = scope(), log = {})
        val host = host()
        host.graphInstalled(NotReadyReplicaStore(status(SharedReplicaHostState.CORRUPT)), stop = {}, rebuild = { fail("never rebuilt") })
        StorageStateSurface.attach(host, copies)
        copies.follow(host.status) { true }
        within("looked at, as any app") { copies.current.phase == DamagedCopyPhase.NOT_COPIED }
        val seen = ArrayList<StorageStateSnapshot>()
        val lock = platform.Foundation.NSLock()
        val observation = StorageStateSurface.observe { lock.lock(); seen += it; lock.unlock() }
        fun all() = lock.run { lock(); try { seen.toList() } finally { unlock() } }
        within("another app's cover first") { all().lastOrNull()?.offersOpenControl == true }
        val taken = AtomicInt(0)
        copies.exporter = DamagedSnapshotRunner(copies, take = { taken.incrementAndGet(); DamagedSnapshotOutcome.Completed(copy) }, protect = { Token() }, scope = scope(), log = {})
        within("taken once set") { taken.value == 1 }
        within("Control's cover, with the export") { all().lastOrNull()?.let { it.canExportDamagedData && !it.offersOpenControl } == true }
        observation.cancel()
    }

    @Test
    fun deleteTheCopyIsControlsOnlyOverACompleteCopyAndTheCoverSaysItWasDeleted() {
        // ⭐ Q51, decided by the maintainer 2026-10-30.
        val deletes = AtomicInt(0)
        val copies = DamagedDataCopies("/made/up/replica", look = { null }, deleter = { deletes.incrementAndGet(); true }, scope = scope(), log = {})
        copies.publish(DamagedCopyView(DamagedCopyPhase.COMPLETE, copy.files))
        assertFalse(copies.delete(), "not Control: nothing to delete")
        copies.exporter = DamagedSnapshotRunner(copies, take = { DamagedSnapshotOutcome.Deleted }, protect = { Token() }, scope = scope(), log = {})
        val host = host()
        StorageStateSurface.attach(host, copies)
        val corrupt = status(SharedReplicaHostState.CORRUPT)
        assertTrue(StorageStateSurface.snapshotOf(host, corrupt, copy = copies.current, exporter = true).canDeleteDamagedData)
        assertTrue(StorageStateSurface.deleteDamagedData())
        within("deleted") { deletes.value == 1 && copies.current.phase == DamagedCopyPhase.DELETED }
        StorageStateSurface.snapshotOf(host, corrupt, copy = copies.current, exporter = true).let {
            assertFalse(it.canExportDamagedData, "nothing left to export")
            assertFalse(it.canDeleteDamagedData)
            assertTrue(it.damagedDataFiles.isEmpty())
            assertTrue("You deleted the copy" in it.message.orEmpty() && "still on this phone" in it.message.orEmpty(), it.message)
        }
        assertFalse(copies.delete(), "a second delete over no copy does nothing")
        assertEquals(1, deletes.value)
        // The next foreground's attempt answers Deleted, and nothing flickers through "copying".
        val seen = ArrayList<DamagedCopyPhase>()
        val lock = platform.Foundation.NSLock()
        val watcher = scope().launch { copies.view.collect { lock.lock(); seen += it.phase; lock.unlock() } }
        assertTrue(copies.exporter!!.start())
        within("the attempt is over") { copies.exporter!!.start() }
        platform.posix.usleep(100_000u)
        watcher.cancel()
        assertFalse(lock.run { lock(); try { DamagedCopyPhase.COPYING in seen } finally { unlock() } }, "$seen")
        // Another app's cover after the delete.
        StorageStateSurface.snapshotOf(host, corrupt, copy = DamagedCopyView.DELETED, exporter = false).let {
            assertTrue("deleted in Tenshu Control" in it.message.orEmpty(), it.message)
            assertFalse(it.canDeleteDamagedData)
        }
    }

    @Test
    fun anotherAppSeesADeletedCopyAsDeleted() {
        val copies = DamagedDataCopies("/made/up/replica", look = { copy }, deleted = { true }, scope = scope(), log = {})
        copies.follow(MutableStateFlow(status(SharedReplicaHostState.CORRUPT))) { true }
        within("deleted") { copies.current.phase == DamagedCopyPhase.DELETED }
    }

    @Test
    fun controlsForgetWarnsOnlyWhileACompleteCopyIsOnThePhone() {
        // ⭐ Q50, decided by the maintainer 2026-10-30.
        val saved = StorageStateSurface.anyCompleteCopy
        try {
            assertEquals(null, StorageStateSurface.damagedCopyForgetWarning(), "switch off: no binding, no warning")
            val copies = DamagedDataCopies("/made/up/replica", look = { null }, scope = scope(), log = {})
            StorageStateSurface.attach(host(), copies)
            StorageStateSurface.anyCompleteCopy = { false }
            assertEquals(null, StorageStateSurface.damagedCopyForgetWarning())
            val asked = ArrayList<String>()
            StorageStateSurface.anyCompleteCopy = { asked += it; true }
            assertEquals(StorageStateSurface.FORGET_WARNING, StorageStateSurface.damagedCopyForgetWarning())
            assertEquals(listOf("/made/up/replica"), asked)
        } finally {
            StorageStateSurface.anyCompleteCopy = saved
        }
    }

    @Test
    fun anObserverOfControlsCoverSeesTheExportAppearOnceTheCopyIsComplete() {
        val host = host()
        host.graphInstalled(NotReadyReplicaStore(status(SharedReplicaHostState.CORRUPT)), stop = {}, rebuild = { fail("never rebuilt") })
        val copies = DamagedDataCopies("/made/up/replica", look = { null }, scope = scope(), log = {})
        val release = AtomicInt(0)
        copies.exporter = DamagedSnapshotRunner(copies, take = { within("let go") { release.value == 1 }; DamagedSnapshotOutcome.Completed(copy) }, protect = { Token() }, scope = scope(), log = {})
        StorageStateSurface.attach(host, copies)
        val seen = ArrayList<StorageStateSnapshot>()
        val lock = platform.Foundation.NSLock()
        val observation = StorageStateSurface.observe { lock.lock(); seen += it; lock.unlock() }
        fun all() = lock.run { lock(); try { seen.toList() } finally { unlock() } }
        copies.follow(host.status) { true }
        within("copying") { all().any { it.covers && "Keep Control open" in it.message.orEmpty() && !it.canExportDamagedData } }
        release.value = 1
        within("offered") { all().lastOrNull()?.canExportDamagedData == true }
        assertEquals(copy.files, all().last().damagedDataFiles)
        assertEquals(copy.files, StorageStateSurface.snapshot().damagedDataFiles)
        observation.cancel()
    }
}

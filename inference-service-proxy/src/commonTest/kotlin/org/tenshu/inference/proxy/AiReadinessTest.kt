package org.tenshu.inference.proxy

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.inference.ModelProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AiReadiness] (`ios-on-device-inference.md` §3.2, *Readiness*): what it derives, what it serves,
 * and the order of every change.
 *
 * ⚠ Each `readiness()` is a new process over the same disk. Every step records what [AiReadiness.serves]
 * answered **while it ran**, because the order is the contract: a pass that is cancelled after the
 * sessions closed, or a model served before its vectors were cleared, looks the same afterwards.
 */
class AiReadinessTest {

    private val disk = InMemoryFileDatabase()
    private val steps = mutableListOf<String>()

    private val modelA = PreparedModel("/coreml/a/vision.mlmodelc", "/coreml/a/text.mlmodelc", "/models/a.vocabulary")
    private val modelB = PreparedModel("/coreml/b/vision.mlmodelc", "/coreml/b/text.mlmodelc", "/models/b.vocabulary")

    private fun key(
        vision: String? = "vision-a",
        text: String? = "text-a",
        osBuild: String = "os-1",
        vocabulary: String? = "vocabulary-a",
        appBuild: String = "1.0 (1)",
        probe: Int = 1,
    ) = PreflightKey(
        runtime = RuntimeFingerprint(vision, text, computeUnits = "cpuAndNeuralEngine", osBuild = osBuild, preprocessingRevision = 1),
        vocabularySha256 = vocabulary,
        appBuild = appBuild,
        preflightRevision = probe,
    )

    // What the store, the cache, the build and the custody say. A test moves them.
    private var installed = true
    private var prepared: PreparedModel? = modelA
    private var current: PreflightKey = key()
    private var storesUsable = true
    private var applying: (RuntimeFingerprint) -> FingerprintOutcome = { FingerprintOutcome.Unchanged }
    private var factsRead = 0
    private var cancelling: () -> Unit = {}

    private fun readiness(files: FileDatabase = disk, ledger: AttemptLedger = AttemptLedger.THIS_PROCESS): AiReadiness {
        lateinit var readiness: AiReadiness
        val recording = object : FileDatabase by files {
            override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
                steps += "persist serves=${readiness.serves()}"
                files.writeRawByteArray(filePath, bytes)
            }
        }
        readiness = AiReadiness(
            files = { recording },
            facts = {
                factsRead++
                AiModelFacts(installed, prepared)
            },
            currentKey = { current },
            storesUsable = { storesUsable },
            applyFingerprint = { fingerprint ->
                steps += "apply ${fingerprint.visionArchiveSha256} serves=${readiness.serves()}"
                applying(fingerprint)
            },
            cancelPass = {
                steps += "cancel serves=${readiness.serves()}"
                cancelling()
            },
            closeSessions = { steps += "close serves=${readiness.serves()}" },
            ledger = ledger,
        )
        return readiness
    }

    /** A readiness that has passed its preflight on [modelA]. */
    private suspend fun ready(): AiReadiness = readiness().also {
        assertEquals(AiReadinessState.Ready, it.recordVerdict(passed = true, probed = current))
        steps.clear()
    }

    // ─── what it derives, and what it serves ────────────────────────────────────────────────────

    @Test
    fun itStartsWithNoModelAndReadsNothingUntilAWriterRuns() = runTest {
        ready()
        factsRead = 0
        val relaunched = readiness()

        assertEquals(AiReadinessState.NoModel, relaunched.current())
        assertFalse(relaunched.serves(), "a verdict on disk was served before anything checked it")
        assertEquals(0, factsRead, "constructing it read the model store")
    }

    @Test
    fun theStateIsWhatTheStoreTheMarkersAndTheVerdictSay() = runTest {
        val readiness = readiness()

        installed = false
        prepared = null
        assertEquals(AiReadinessState.NoModel, readiness.fingerprintChecked())

        installed = true
        assertEquals(AiReadinessState.Preparing, readiness.fingerprintChecked())

        prepared = modelA
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.markerWritten())

        assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = current))
        assertEquals(AiReadinessState.Unsuitable, readiness.recordVerdict(passed = false, probed = current))
        assertEquals(AiReadinessState.Unsuitable, readiness.state.value, "the screen's flow says something else")
    }

    @Test
    fun onlyReadyServes() = runTest {
        val readiness = readiness()
        val served = mutableMapOf<AiReadinessState, Boolean>()
        fun note() {
            served[readiness.current()] = readiness.serves()
        }

        installed = false
        prepared = null
        readiness.fingerprintChecked()
        note()
        installed = true
        readiness.fingerprintChecked()
        note()
        prepared = modelA
        readiness.markerWritten()
        note()
        readiness.recordVerdict(passed = true, probed = current)
        note()
        assertEquals(modelA, readiness.serving())
        readiness.recordVerdict(passed = false, probed = current)
        note()
        assertNull(readiness.serving())

        assertEquals(
            mapOf(
                AiReadinessState.NoModel to false,
                AiReadinessState.Preparing to false,
                AiReadinessState.AwaitingVerdict to false,
                AiReadinessState.Ready to true,
                AiReadinessState.Unsuitable to false,
            ),
            served,
        )
    }

    /** The custody's half: `Ready` is about the model, and says nothing about the stores. */
    @Test
    fun aReadyModelIsNotServedWhileTheStoresAreUnusable() = runTest {
        val readiness = ready()
        assertTrue(readiness.serves())

        storesUsable = false
        assertFalse(readiness.serves(), "a model was served over stores the custody had closed")
        assertNull(readiness.serving())
        assertEquals(AiReadinessState.Ready, readiness.current(), "the custody's answer was copied into the state")

        storesUsable = true
        assertTrue(readiness.serves(), "the custody opening again needed a writer to be noticed")
    }

    // ─── entering Ready ─────────────────────────────────────────────────────────────────────────

    @Test
    fun enteringReadyAppliesTheFingerprintThenPersistsTheVerdictAndFlipsLast() = runTest {
        val readiness = readiness()
        readiness.markerWritten()
        steps.clear()

        readiness.recordVerdict(passed = true, probed = current)

        assertEquals(listOf("apply vision-a serves=false", "persist serves=false"), steps)
        assertTrue(readiness.serves())
    }

    @Test
    fun aFingerprintThatCannotBeAppliedIsNotReadyAndPersistsNothing() = runTest {
        val readiness = readiness()
        applying = { FingerprintOutcome.Failed(IllegalStateException("the vectors would not clear")) }

        assertEquals(AiReadinessState.AwaitingVerdict, readiness.recordVerdict(passed = true, probed = current))

        assertFalse(readiness.serves())
        assertFalse(steps.any { it.startsWith("persist") }, "a pass was persisted over vectors that were not cleared: $steps")
        applying = { FingerprintOutcome.Unchanged }
        assertEquals(AiReadinessState.AwaitingVerdict, readiness().fingerprintChecked(), "the next process found a verdict")
    }

    @Test
    fun aVerdictThatCannotBePersistedIsNotReady() = runTest {
        val refusing = object : FileDatabase by disk {
            override fun writeRawByteArray(filePath: String, bytes: ByteArray): Unit = error("the disk is full")
        }
        val readiness = readiness(refusing)

        assertEquals(AiReadinessState.AwaitingVerdict, readiness.recordVerdict(passed = true, probed = current))
        assertFalse(readiness.serves(), "a model was served on a verdict the next launch will not find")
    }

    // ─── a write that fails ─────────────────────────────────────────────────────────────────────

    /** The disk, refusing writes while [full]. */
    private var full = false
    private val fillable = object : FileDatabase by disk {
        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            if (full) error("the disk is full")
            disk.writeRawByteArray(filePath, bytes)
        }
    }

    @Test
    fun aFailingVerdictThatCannotBePersistedIsNotBelieved() = runTest {
        val readiness = readiness(fillable)
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.fingerprintChecked())
        full = true

        assertEquals(AiReadinessState.AwaitingVerdict, readiness.recordVerdict(passed = false, probed = current))

        assertEquals(AiReadinessState.AwaitingVerdict, readiness.state.value, "memory says unsuitable and the disk does not")
        assertNull(disk.readBytes(AiReadiness.VERDICT_PATH))
        assertEquals(AiReadinessState.AwaitingVerdict, readiness().fingerprintChecked(), "the next launch reads another state")
        // And once the disk takes it, it is believed.
        full = false
        assertEquals(AiReadinessState.Unsuitable, readiness.recordVerdict(passed = false, probed = current))
    }

    /**
     * ⚠⚠ The other rule, in the other direction: leaving `Ready` stops serving **before** the
     * write and whatever the write does. What the failed write decides is only what the state is
     * called afterwards: it has no answer, because none reached the disk.
     */
    @Test
    fun aFailingVerdictThatCannotBePersistedStillStopsServing() = runTest {
        val readiness = readiness(fillable)
        assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = current))
        steps.clear()
        full = true

        assertEquals(AiReadinessState.AwaitingVerdict, readiness.recordVerdict(passed = false, probed = current))

        assertFalse(readiness.serves(), "a model went on being served after it was failed")
        assertEquals(leftInOrder, steps.filterNot { it.startsWith("persist") }, "the pass was not cancelled, or the sessions not closed")
    }

    /** The write went through and a later step threw: memory and disk must still say the same. */
    @Test
    fun aVerdictThatWasWrittenIsBelievedEvenWhenStoppingThenThrows() = runTest {
        val readiness = ready()
        cancelling = { error("the pass would not stop") }

        assertFailsWith<IllegalStateException> { readiness.recordVerdict(passed = false, probed = current) }

        assertEquals(AiReadinessState.Unsuitable, readiness.current())
        assertFalse(readiness.serves())
        assertEquals("close serves=false", steps.last(), "the sessions were left open")
        cancelling = {}
        assertEquals(AiReadinessState.Unsuitable, readiness().fingerprintChecked(), "the disk says something else")
    }

    // ─── leaving Ready ──────────────────────────────────────────────────────────────────────────

    private val leftInOrder = listOf("cancel serves=false", "close serves=false")

    @Test
    fun aFailingVerdictLeavesReadyInOrder() = runTest {
        val readiness = ready()

        readiness.recordVerdict(passed = false, probed = current)

        assertEquals(leftInOrder, steps.filterNot { it.startsWith("persist") })
        assertEquals("persist serves=false", steps.single { it.startsWith("persist") })
    }

    @Test
    fun aNewOsBuildVoidsTheVerdictAndLeavesReadyInOrder() = runTest {
        val readiness = ready()

        current = key(osBuild = "os-2")

        assertEquals(AiReadinessState.AwaitingVerdict, readiness.fingerprintChecked())
        assertEquals(leftInOrder, steps)
    }

    @Test
    fun aRemovedModelLeavesReadyInOrder() = runTest {
        val readiness = ready()

        val state = readiness.storeChanging {
            steps += "store moves serves=${readiness.serves()}"
            installed = false
            prepared = null
        }.let { readiness.current() }

        assertEquals(AiReadinessState.NoModel, state)
        // ⚠ Around the change: nothing is served by the time the store declares the files garbage.
        assertEquals(leftInOrder + "store moves serves=false", steps)
    }

    @Test
    fun aMarkerThatIsGoneLeavesReadyInOrder() = runTest {
        val readiness = ready()

        prepared = null

        assertEquals(AiReadinessState.Preparing, readiness.fingerprintChecked())
        assertEquals(leftInOrder, steps)
    }

    @Test
    fun aCancelThatThrowsStillClosesTheSessions() = runTest {
        val readiness = ready()
        cancelling = { error("the runner is gone") }

        assertFailsWith<IllegalStateException> { readiness.recordVerdict(passed = false, probed = current) }

        assertEquals("close serves=false", steps.last())
        assertFalse(readiness.serves())
    }

    @Test
    fun aChangeBetweenStatesThatDoNotServeStopsNothing() = runTest {
        val readiness = readiness()
        prepared = null
        readiness.fingerprintChecked()
        prepared = modelA
        readiness.markerWritten()
        installed = false
        readiness.storeChanging { }

        assertEquals(emptyList<String>(), steps, "a pass was cancelled or a session closed for a model nothing served")
    }

    @Test
    fun whatCannotBeReadIsNotServed() = runTest {
        ready()
        val throwing = AiReadiness(
            files = { disk },
            facts = { error("the model index is unreadable") },
            currentKey = { current },
            storesUsable = { true },
            applyFingerprint = { FingerprintOutcome.Unchanged },
            cancelPass = {},
            closeSessions = {},
        )
        // Not ready to begin with: it stays where it is, and says nothing new.
        assertEquals(AiReadinessState.NoModel, throwing.fingerprintChecked())

        // Ready, and then the store stops answering.
        var broken = false
        val flaky = AiReadiness(
            files = { disk },
            facts = { if (broken) error("the model index is unreadable") else AiModelFacts(true, modelA) },
            currentKey = { current },
            storesUsable = { true },
            applyFingerprint = { FingerprintOutcome.Unchanged },
            cancelPass = { steps += "cancel" },
            closeSessions = { steps += "close" },
        )
        assertEquals(AiReadinessState.Ready, flaky.fingerprintChecked())
        assertTrue(flaky.serves())
        broken = true
        assertEquals(AiReadinessState.AwaitingVerdict, flaky.fingerprintChecked())
        assertFalse(flaky.serves())
        assertEquals(listOf("cancel", "close"), steps)
    }

    // ─── the stores close under a ready model ───────────────────────────────────────────────────

    /**
     * The custody closes at a gate, on its own schedule. The model is still the right one, so
     * the state does not move; the towers are unloaded at the next check all the same.
     */
    @Test
    fun aCheckThatFindsTheStoresClosedUnloadsTheModelAndKeepsTheState() = runTest {
        val readiness = ready()

        storesUsable = false
        assertEquals(AiReadinessState.Ready, readiness.fingerprintChecked())
        assertEquals(leftInOrder, steps)

        steps.clear()
        storesUsable = true
        assertEquals(AiReadinessState.Ready, readiness.fingerprintChecked())
        assertEquals(emptyList<String>(), steps, "usable stores cost a pass and a reload at every check")
        assertEquals(modelA, readiness.serving())
    }

    // ─── a tower the backend opens while ready (§14.19, §14.18.16 item 9) ─────────────────────

    private class Killed : Error("the process was killed")

    /** A process whose disk takes no write from the moment [kill] is called. */
    private inner class Dying {
        var dead = false
        val files = object : FileDatabase by disk {
            override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
                if (!dead) disk.writeRawByteArray(filePath, bytes)
            }
        }

        fun kill(): Nothing {
            dead = true
            throw Killed()
        }
    }

    private fun interrupted(): Int? =
        disk.readBytes(AiReadiness.VERDICT_PATH)?.get()?.decodeToString()?.substringAfter("\"interrupted\":", "")?.takeWhile { it.isDigit() }?.toIntOrNull()

    @Test
    fun aTowerOpenedWhileReadyThatPhotosDoesNotSurviveIsCountedAndTheSecondStopsTheModel() = runTest {
        repeat(2) { round ->
            val process = Dying()
            val graph = readiness(files = process.files, ledger = AttemptLedger())
            if (round == 0) assertEquals(AiReadinessState.Ready, graph.recordVerdict(passed = true, probed = current)) else graph.fingerprintChecked()
            runCatching { graph.servingOpen("vision", foreground = 1L, atEpochMs = 5L) { process.kill() } }
        }

        val relaunched = readiness(ledger = AttemptLedger())
        assertEquals(AiReadinessState.Unsuitable, relaunched.fingerprintChecked(), "two opens Photos did not survive did not stop the model")
        assertEquals(2, interrupted())
        assertFalse(relaunched.serves())
    }

    /**
     * ⚠⚠ One process killed with BOTH towers' opens in flight is one interrupted load, not two:
     * loading both at once is the likeliest moment of a jetsam, and counted per file it would make
     * the phone `Unsuitable` after a single kill. A second such kill, from another process, still
     * stops the model.
     */
    @Test
    fun aProcessKilledOpeningBothTowersCountsOneInterruptedLoad() = runTest {
        repeat(2) { round ->
            val process = Dying()
            val graph = readiness(files = process.files, ledger = AttemptLedger())
            if (round == 0) assertEquals(AiReadinessState.Ready, graph.recordVerdict(passed = true, probed = current)) else graph.fingerprintChecked()
            // The vision open is still in flight when the process dies: its clear never lands.
            runCatching {
                graph.servingOpen("vision", foreground = 1L, atEpochMs = 5L) {
                    process.dead = true
                    throw Killed()
                }
            }
            process.dead = false
            runCatching { graph.servingOpen("text", foreground = 1L, atEpochMs = 6L) { process.kill() } }
            assertTrue(disk.readBytes(AiReadiness.VERDICT_PATH + ".open.vision")?.get()?.decodeToString().orEmpty().contains("SERVE_OPEN"), "precondition: the vision open's record is on disk")
            assertTrue(disk.readBytes(AiReadiness.VERDICT_PATH + ".open.text")?.get()?.decodeToString().orEmpty().contains("SERVE_OPEN"), "precondition: the text open's record is on disk")

            if (round == 0) {
                val relaunched = readiness(ledger = AttemptLedger())
                assertEquals(AiReadinessState.Ready, relaunched.fingerprintChecked(), "one kill during two opens stopped the model")
                assertEquals(1, interrupted(), "one process's two opens were counted as two loads")
            }
        }

        val relaunched = readiness(ledger = AttemptLedger())
        assertEquals(AiReadinessState.Unsuitable, relaunched.fingerprintChecked(), "a second process's kill did not stop the model")
        assertEquals(2, interrupted())
        assertFalse(relaunched.serves())
    }

    @Test
    fun anOpenThatEndsLeavesNothingToCount() = runTest {
        val graph = ready()
        assertEquals("opened", graph.servingOpen("text", foreground = 1L, atEpochMs = 5L) { "opened" })
        assertEquals("opened", runCatching { graph.servingOpen("vision", foreground = 1L, atEpochMs = 5L) { error("would not load") } }.exceptionOrNull()?.message?.let { "opened" })

        assertEquals(AiReadinessState.Ready, readiness(ledger = AttemptLedger()).fingerprintChecked())
        assertEquals(0, interrupted() ?: 0, "an open that ended was counted")
    }

    @Test
    fun anOpenFrozenWhenTheSceneLeftIsTransient() = runTest {
        val process = Dying()
        val ledger = AttemptLedger()
        val graph = readiness(files = process.files, ledger = ledger)
        graph.recordVerdict(passed = true, probed = current)
        runCatching {
            graph.servingOpen("vision", foreground = 3L, atEpochMs = 5L) {
                graph.sceneChanged(active = false, leftForeground = 3L)
                process.kill()
            }
        }

        assertEquals(AiReadinessState.Ready, readiness(ledger = AttemptLedger()).fingerprintChecked())
        assertEquals(0, interrupted() ?: 0)
    }

    /**
     * ⚠⚠ The next process writes its scene record before its first reading: the record of the
     * process whose open was frozen is kept, named by the open's own file.
     */
    @Test
    fun anOpenFrozenWhenTheSceneLeftStaysTransientWhenTheNextProcessWritesItsSceneFirst() = runTest {
        val process = Dying()
        val graph = readiness(files = process.files, ledger = AttemptLedger())
        graph.recordVerdict(passed = true, probed = current)
        runCatching {
            graph.servingOpen("vision", foreground = 3L, atEpochMs = 5L) {
                graph.sceneChanged(active = false, leftForeground = 3L)
                process.kill()
            }
        }

        val relaunched = readiness(ledger = AttemptLedger())
        relaunched.sceneChanged(active = true, leftForeground = null)
        relaunched.sceneChanged(active = false, leftForeground = 1L)
        relaunched.sceneChanged(active = true, leftForeground = null)

        assertEquals(AiReadinessState.Ready, relaunched.fingerprintChecked())
        assertEquals(0, interrupted() ?: 0, "a frozen open was counted as a kill once the next process wrote its scene")
    }

    @Test
    fun nothingIsOpenedWhenNothingIsServedOrTheRecordCannotBeWritten() = runTest {
        val graph = readiness()
        assertFailsWith<AttemptNotRecordedException> { graph.servingOpen("vision", 1L, 5L) { error("opened while not ready") } }

        var failing = false
        val refusing = object : FileDatabase by disk {
            override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
                if (failing && ".open." in filePath) error("disk full")
                disk.writeRawByteArray(filePath, bytes)
            }
        }
        val ready = readiness(files = refusing)
        ready.recordVerdict(passed = true, probed = current)
        failing = true
        assertFailsWith<AttemptNotRecordedException> { ready.servingOpen("vision", 1L, 5L) { error("opened with no record") } }
    }

    /**
     * ⚠⚠ The record is never taken under readiness's lock: a writer that stops serving holds that
     * lock (and the verdict file's) while it waits for the tower the open holds. Here a reading
     * holds both, suspended, and an open still completes.
     */
    @Test
    fun anOpenNeverWaitsForReadinesssLock() = runTest {
        val graph = ready()
        val textOpen = AttemptLedger.THIS_PROCESS.lockFor(AiReadiness.VERDICT_PATH + ".open.text")
        textOpen.lock()
        val reading = backgroundScope.async(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { graph.fingerprintChecked() }
        runCurrent()
        assertFalse(reading.isCompleted, "precondition: the reading holds readiness's lock")

        assertEquals("opened", graph.servingOpen("vision", 1L, 5L) { "opened" })
        textOpen.unlock()
        reading.await()
    }

    @Test
    fun anOpenRecordThatCannotBeReadIsNotConcludedFromAndNothingIsServed() = runTest {
        var unreadable = false
        val files = object : FileDatabase by disk {
            override fun readBytes(filePath: String) = if (unreadable && ".open." in filePath) error("I/O error") else disk.readBytes(filePath)
        }
        val graph = readiness(files = files)
        graph.recordVerdict(passed = true, probed = current)
        unreadable = true

        graph.fingerprintChecked()
        assertFalse(graph.serves(), "a model was served over an open nobody could read")
        unreadable = false
        assertEquals(AiReadinessState.Ready, graph.fingerprintChecked())
    }

    // ─── retired: the runtime is torn down ──────────────────────────────────────────────────────

    @Test
    fun retiringLeavesReadyInOrder() = runTest {
        val readiness = ready()

        readiness.retire()
        assertFalse(readiness.serves(), "retiring waited for the unload to stop serving")
        assertEquals(emptyList<String>(), steps, "retiring cancelled or closed on the caller's thread")
        readiness.unloadRetired()

        assertEquals(leftInOrder, steps)
        assertEquals(AiReadinessState.NoModel, readiness.current())
        assertNull(readiness.serving())
    }

    /** A graph outlives its runtime. Whatever a later writer reads, a retired readiness stays out. */
    @Test
    fun noWriterBringsARetiredReadinessBack() = runTest {
        val readiness = ready()
        readiness.retire()
        readiness.unloadRetired()
        steps.clear()

        assertEquals(AiReadinessState.NoModel, readiness.fingerprintChecked())
        assertEquals(AiReadinessState.NoModel, readiness.markerWritten())
        assertEquals(AiReadinessState.NoModel, readiness.recordVerdict(passed = true, probed = current))
        readiness.storeChanging { }

        assertEquals(AiReadinessState.NoModel, readiness.current())
        assertFalse(readiness.serves(), "a torn-down runtime's backend was handed its model again")
        assertEquals(emptyList<String>(), steps)
    }

    /**
     * ⚠⚠ A retired graph that is still `Ready` stops nothing under the file's lock when the store
     * or the compiled cache changes: the stop waits on its own prediction, and the process's file
     * lock would be held across that wait. Its unload is [AiReadiness.unloadRetired]'s.
     */
    @Test
    fun aRetiredGraphStopsNothingUnderTheFileLockWhenTheStoreOrTheCacheChanges() = runTest {
        val readiness = ready()
        readiness.retire()

        readiness.storeChanging { }
        readiness.compiledChanging { }

        assertEquals(emptyList(), steps.filter { it.startsWith("cancel") || it.startsWith("close") }, "a retired graph stopped serving under the file's lock")
        readiness.unloadRetired()
        assertEquals(leftInOrder, steps.filter { it.startsWith("cancel") || it.startsWith("close") })
    }

    /**
     * ⚠ `retire` is not a suspend function and takes no lock: a writer in the middle of a slow
     * read still holds it, and the stores are relinquished as soon as `retire` has returned.
     */
    @Test
    fun retiringStopsServingAtOnceEvenWhileAWriterHoldsTheLock() = runTest {
        var duringTheRead: (() -> Unit)? = null
        var servedWhileRetiring: Boolean? = null
        lateinit var readiness: AiReadiness
        readiness = AiReadiness(
            files = { disk },
            facts = {
                duringTheRead?.invoke()
                AiModelFacts(true, modelA)
            },
            currentKey = { current },
            storesUsable = { true },
            applyFingerprint = { FingerprintOutcome.Unchanged },
            cancelPass = { steps += "cancel" },
            closeSessions = { steps += "close" },
        )
        assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = current))
        duringTheRead = {
            duringTheRead = null
            // From inside a writer, which holds the lock: it returns all the same.
            readiness.retire()
            servedWhileRetiring = readiness.serves()
        }

        assertEquals(AiReadinessState.Ready, readiness.fingerprintChecked(), "the writer in flight reports what it held")
        assertFalse(readiness.serves())
        assertEquals(emptyList<String>(), steps)
        readiness.unloadRetired()

        assertEquals(false, servedWhileRetiring, "a model was served after its runtime began tearing down")
        assertEquals(listOf("cancel", "close"), steps)
        assertEquals(AiReadinessState.NoModel, readiness.current())
    }

    // ─── a replacement ──────────────────────────────────────────────────────────────────────────

    @Test
    fun aReplacementIsNotServedUntilItsOwnVerdict() = runTest {
        val readiness = ready()
        val old = current

        // The store commits the new archives: installed, not compiled.
        readiness.storeChanging {
            prepared = null
            current = key(vision = "vision-b", text = "text-b")
        }
        assertEquals(AiReadinessState.Preparing, readiness.current())
        assertNull(readiness.serving())

        // Prepared. The verdict on disk is the old archive's, and a pass.
        prepared = modelB
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.markerWritten())
        assertNull(readiness.serving(), "the replacement was served on its predecessor's verdict")

        // A probe that answers late, about the archive that was replaced.
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.recordVerdict(passed = true, probed = old))
        assertNull(readiness.serving(), "a verdict about the old archive made the new one ready")

        steps.clear()
        assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = current))
        assertEquals(modelB, readiness.serving())
        assertEquals(listOf("apply vision-b serves=false", "persist serves=false"), steps)
    }

    @Test
    fun aFailingReplacementIsUnsuitableAtOnce() = runTest {
        val readiness = ready()
        readiness.storeChanging {
            prepared = modelB
            current = key(vision = "vision-b", text = "text-b")
        }
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current())

        assertEquals(AiReadinessState.Unsuitable, readiness.recordVerdict(passed = false, probed = current))
        assertNull(readiness.serving())
    }

    // ─── the verdict's key is wider than the vectors' fingerprint ───────────────────────────────

    /** The real custody and the real vector log, with one vector made under the current model. */
    private suspend fun readyOverTheRealStores(body: suspend (AiReadiness, InferenceStoreCustody, EmbeddingStore) -> Unit) {
        val embeddings = DefaultEmbeddingStore(disk, "/inference/embeddings.log")
        val custody = InferenceStoreCustody(
            files = disk,
            embeddings = embeddings,
            faces = DefaultFaceTemplateStore(disk, "/inference/faces.log"),
            cancelPass = {},
            currentRealm = { InferenceRealm("owner-a") },
            // ⚠ The custody's own half, as the graph hands it: never the preflight's key.
            currentFingerprint = { current.runtime },
        )
        val readiness = AiReadiness(
            files = { disk },
            facts = { AiModelFacts(installed, prepared) },
            currentKey = { current },
            storesUsable = custody::usable,
            applyFingerprint = custody::applyFingerprint,
            cancelPass = {},
            closeSessions = {},
        )
        assertTrue(custody.ensureCurrentRealm())
        assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = current))
        assertTrue(embeddings.put("/a.jpg", floatArrayOf(1f, 0f), ModelProvenance("siglip", 1)))
        body(readiness, custody, embeddings)
    }

    /**
     * ⚠⚠ A catalog update that replaces the vocabulary and keeps both towers. No stored vector
     * changes, so nothing is cleared; every query's tokens do, so the pass does not stand.
     */
    @Test
    fun aVocabularyOnlyReplacementVoidsTheVerdictAndKeepsTheVectors() = runTest {
        readyOverTheRealStores { readiness, custody, embeddings ->
            val old = current

            readiness.storeChanging {
                prepared = modelA.copy(vocabularyPath = "/models/b.vocabulary")
                current = key(vocabulary = "vocabulary-b")
            }

            assertEquals(AiReadinessState.AwaitingVerdict, readiness.current(), "a new vocabulary is served on the old one's pass")
            assertNull(readiness.serving())
            // The next process finds the same: the pass on disk is the old vocabulary's.
            assertEquals(AiReadinessState.AwaitingVerdict, readiness().fingerprintChecked())
            // A probe that answers late, about the vocabulary that was replaced.
            assertEquals(AiReadinessState.AwaitingVerdict, readiness.recordVerdict(passed = true, probed = old))

            // Its own pass, and the custody is asked nothing it would clear for.
            assertTrue(custody.ensureCurrentRealm())
            assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = current))
            assertEquals(1, embeddings.size(), "a vocabulary change cost the library its index")
            assertTrue(custody.usable())
        }
    }

    @Test
    fun aNewPreflightRevisionOrANewAppBuildVoidsTheVerdictAndKeepsTheVectors() = runTest {
        readyOverTheRealStores { readiness, custody, embeddings ->
            current = key(appBuild = "1.1 (2)")
            assertEquals(AiReadinessState.AwaitingVerdict, readiness.fingerprintChecked(), "an app update kept a pass its code did not earn")
            assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = current))

            current = key(appBuild = "1.1 (2)", probe = 2)
            assertEquals(AiReadinessState.AwaitingVerdict, readiness.fingerprintChecked(), "a pass under the old probe still stands")
            assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = current))

            assertTrue(custody.ensureCurrentRealm())
            assertEquals(1, embeddings.size())
        }
    }

    /**
     * ⚠⚠ A verdict file written under another shape of the key is **no verdict**, never a pass.
     * The first is what the first cut wrote: keyed on the runtime fingerprint alone.
     */
    @Test
    fun aVerdictWrittenUnderAnotherKeyShapeIsNoVerdict() = runTest {
        val runtime = """{"visionArchiveSha256":"vision-a","textArchiveSha256":"text-a","computeUnits":"cpuAndNeuralEngine","osBuild":"os-1","preprocessingRevision":1}"""
        listOf(
            """{"fingerprint":$runtime,"passed":true}""",
            """{"key":$runtime,"passed":true}""",
            """{"key":{"runtime":$runtime,"appBuild":"1.0 (1)","preflightRevision":1},"passed":true}""",
            """{"key":{"runtime":$runtime,"vocabularySha256":"vocabulary-a","preflightRevision":1},"passed":true}""",
            """{"key":{"runtime":$runtime,"vocabularySha256":"vocabulary-a","appBuild":"1.0 (1)"},"passed":true}""",
            // The revision under another name, `probeRevision`.
            """{"key":{"runtime":$runtime,"vocabularySha256":"vocabulary-a","appBuild":"1.0 (1)","probeRevision":1},"passed":true}""",
            // An answer with no attempt counts beside it.
            """{"key":{"runtime":$runtime,"vocabularySha256":"vocabulary-a","appBuild":"1.0 (1)","preflightRevision":1},"passed":true}""",
            // The counts with no answer field at all: `passed` has no default either.
            """{"key":{"runtime":$runtime,"vocabularySha256":"vocabulary-a","appBuild":"1.0 (1)","preflightRevision":1},"attempts":{"slow":0,"failed":0,"transient":0,"lastAtEpochMs":0}}""",
        ).forEach { written ->
            disk.writeRawByteArray(AiReadiness.VERDICT_PATH, written.encodeToByteArray())
            assertEquals(AiReadinessState.AwaitingVerdict, readiness().fingerprintChecked(), "read as a verdict: $written")
        }
        // The shape this build writes, with the same content, is one.
        disk.writeRawByteArray(
            AiReadiness.VERDICT_PATH,
            """{"key":{"runtime":$runtime,"vocabularySha256":"vocabulary-a","appBuild":"1.0 (1)","preflightRevision":1},"passed":true,"attempts":{"slow":0,"failed":0,"transient":0,"lastAtEpochMs":0}}""".encodeToByteArray(),
        )
        assertEquals(AiReadinessState.Ready, readiness().fingerprintChecked())
    }

    // ─── across a process ───────────────────────────────────────────────────────────────────────

    @Test
    fun aPassOutlivesTheProcessAndIsEnteredInOrder() = runTest {
        ready()

        val relaunched = readiness()
        assertEquals(AiReadinessState.Ready, relaunched.fingerprintChecked())

        // ⚠ Nothing to persist, and still the fingerprint first.
        assertEquals(listOf("apply vision-a serves=false"), steps)
        assertEquals(modelA, relaunched.serving())
    }

    @Test
    fun aFailOutlivesTheProcess() = runTest {
        readiness().recordVerdict(passed = false, probed = current)

        val relaunched = readiness()
        assertEquals(AiReadinessState.Unsuitable, relaunched.fingerprintChecked())
        assertNull(relaunched.serving(), "a phone found unsuitable was served its model after a relaunch")
        assertFalse(relaunched.serves())
    }

    /**
     * The same verdict, other paths: the compiled model or the vocabulary moved under a
     * fingerprint that did not. Never straight from one model to the next.
     */
    @Test
    fun aModelThatMovedUnderTheSameVerdictIsLeftBeforeItIsEntered() = runTest {
        val readiness = ready()

        prepared = modelB

        assertEquals(AiReadinessState.Ready, readiness.fingerprintChecked())
        assertEquals(leftInOrder + "apply vision-a serves=false", steps)
        assertEquals(modelB, readiness.serving())
    }

    @Test
    fun aVerdictFileThatIsNotOneIsNoVerdict() = runTest {
        disk.writeRawByteArray(AiReadiness.VERDICT_PATH, "not json".encodeToByteArray())

        assertEquals(AiReadinessState.AwaitingVerdict, readiness().fingerprintChecked())
    }

    // ─── with the real custody ──────────────────────────────────────────────────────────────────

    /**
     * §5's update case, over the real store: a passing replacement clears the old archive's
     * vectors **before** it is served, so no vector of the new one is ever written beside them.
     */
    @Test
    fun aPassingReplacementClearsTheOldVectorsBeforeItIsServed() = runTest {
        val provenance = ModelProvenance("siglip", 1)
        val embeddings = DefaultEmbeddingStore(disk, "/inference/embeddings.log")
        val custody = InferenceStoreCustody(
            files = disk,
            embeddings = embeddings,
            faces = DefaultFaceTemplateStore(disk, "/inference/faces.log"),
            cancelPass = {},
            currentRealm = { InferenceRealm("owner-a") },
            currentFingerprint = { current.runtime },
        )
        var vectorsWhenServed = -1
        lateinit var readiness: AiReadiness
        readiness = AiReadiness(
            files = { disk },
            facts = { AiModelFacts(installed, prepared) },
            currentKey = { current },
            storesUsable = custody::usable,
            applyFingerprint = custody::applyFingerprint,
            cancelPass = {},
            closeSessions = {},
        )
        assertFalse(readiness.serves())
        assertTrue(custody.ensureCurrentRealm())
        assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = current))
        assertTrue(embeddings.put("/a.jpg", floatArrayOf(1f, 0f), provenance))

        readiness.storeChanging {
            prepared = modelB
            current = key(vision = "vision-b", text = "text-b")
        }
        assertEquals(1, embeddings.size(), "an unverified replacement cleared the index")

        readiness.recordVerdict(passed = true, probed = current)
        if (readiness.serves()) vectorsWhenServed = embeddings.size()

        assertEquals(0, vectorsWhenServed, "the new archive was served beside the old archive's vectors")
        assertTrue(custody.usable())
    }
}

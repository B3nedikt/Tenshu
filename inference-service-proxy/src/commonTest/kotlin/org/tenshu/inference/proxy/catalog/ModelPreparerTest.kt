package org.tenshu.inference.proxy.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.inference.proxy.AiModelFacts
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.AttemptLedger
import org.tenshu.inference.proxy.ReadinessStanding
import org.tenshu.inference.proxy.PrepareRefusal
import org.tenshu.inference.proxy.ProbeResult
import org.tenshu.inference.proxy.CompiledCache
import org.tenshu.inference.proxy.CompiledCacheNotClearedException
import org.tenshu.inference.proxy.CompiledFiles
import org.tenshu.inference.proxy.CompiledModels
import org.tenshu.inference.proxy.MarkerRead
import org.tenshu.inference.proxy.PinnedModelChanges
import org.tenshu.inference.proxy.CompiledSweep
import org.tenshu.inference.proxy.CompiledTower
import org.tenshu.inference.proxy.FingerprintOutcome
import org.tenshu.inference.proxy.ModelPreparer
import org.tenshu.inference.proxy.PreflightAttempts
import org.tenshu.inference.proxy.PreflightFinding
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.PreflightOutcome
import org.tenshu.inference.proxy.PrepareKey
import org.tenshu.inference.proxy.PrepareOutcome
import org.tenshu.inference.proxy.PreparedModel
import org.tenshu.inference.proxy.RuntimeFingerprint
import org.tenshu.inference.proxy.catalog.DownloadFixtures.MODEL
import org.tenshu.inference.proxy.catalog.DownloadFixtures.entry
import org.tenshu.inference.proxy.catalog.DownloadFixtures.sha256
import org.tenshu.inference.proxy.catalog.DownloadFixtures.textBytes
import org.tenshu.inference.proxy.catalog.DownloadFixtures.visionBytes
import org.tenshu.inference.proxy.catalog.DownloadFixtures.vocabularyBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import org.tenshu.inference.proxy.SpaceRefusal
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the compiled cache does to a disk and asks of Core ML, **one primitive each**, over the
 * same [DownloadDisk] the store writes to. The order of a tower's steps, their cleanup and the
 * marker's place are NOT here: they are the real [CompiledCache]'s, which every case runs.
 *
 * Each primitive can do what the real one does: fail, be the moment something else happens
 * ([at]), leave its output **outside** the cache (the compiler does), and not delete ([stuck]).
 * A marker can be there and give no bytes; the directory can refuse to be listed.
 */
internal class FakeCompiledFiles(private val disk: DownloadDisk) : CompiledFiles {
    val primitives = listOf("space", "unpack", "compile", "move", "firstOpen", "mark")
    val events = mutableListOf<String>()

    /** What happens as [primitive] of [role] is reached: nothing, a throw, or another operation landing. */
    var at: (role: String, primitive: String) -> Unit = { _, _ -> }

    /** Paths under which a delete leaves everything where it is. */
    val stuck = mutableSetOf<String>()
    var unlisted: Boolean = false
    var noRoom: SpaceRefusal? = null

    /** The marker is not written although the call returns. */
    var writesNoMarker: Boolean = false
    private var compiles = 0
    private var compiling: String = ""

    fun directory(digest: String): String = "inference/coreml/${digest.take(16)}"
    fun marker(digest: String, unused: Unit = Unit): String = "${directory(digest)}/ready"
    fun compiled(role: String, digest: String): String = "${directory(digest)}/$role.mlmodelc/model.bin"

    /** Everything on the disk under the cache, by entry name. */
    fun directories(): Set<String> = disk.paths.filter { it.startsWith("inference/coreml/") }
        .map { it.removePrefix("inference/coreml/").substringBefore('/') }.sorted().toSet()

    /** Core ML's own outputs that are still on the disk, outside the cache. */
    fun compilerOutputs(): List<String> = disk.paths.filter { it.startsWith("system-tmp/") }

    private fun reach(role: String, primitive: String) {
        events += "$role $primitive"
        at(role, primitive)
    }

    private fun gone(prefix: String) {
        if (stuck.none { prefix.startsWith(it) || it.startsWith(prefix) }) {
            disk.paths.filter { it == prefix || it.startsWith("$prefix/") }.forEach { disk.deleteFile(it) }
        }
        if (disk.paths.any { it == prefix || it.startsWith("$prefix/") }) throw CompiledCacheNotClearedException("could not delete $prefix")
    }

    override fun marker(archiveDigest: String): MarkerRead {
        val path: String = marker(archiveDigest, Unit)
        val bytes: ByteArray? = try {
            disk.readBytes(path)?.get()
        } catch (failure: Exception) {
            return MarkerRead.Unreadable("$path: $failure")
        }
        if (bytes == null) return if (disk.fileSize(path) != null) MarkerRead.Unreadable("$path gave no bytes") else MarkerRead.Absent
        return MarkerRead.Held(bytes.decodeToString().removePrefix("digest="))
    }

    override fun towerThere(role: String, archiveDigest: String): Boolean = disk.fileSize(compiled(role, archiveDigest)) != null
    override fun towerPath(role: String, archiveDigest: String): String = compiled(role, archiveDigest).substringBeforeLast('/')
    override fun tagOf(archiveDigest: String): String {
        require(archiveDigest.length >= 16) { "'$archiveDigest' is not a digest" }
        return archiveDigest.take(16)
    }

    override fun spaceRefusal(archivePath: String): SpaceRefusal? {
        compiling = archivePath
        reach(roleOf(archivePath), "space")
        return noRoom
    }

    private fun roleOf(archivePath: String): String = archivePath.substringAfterLast('/').split('.')[1]

    override fun unpack(archivePath: String, role: String): String {
        check(disk.fileSize(archivePath) != null) { "no archive at $archivePath" }
        reach(role, "unpack")
        return "inference/coreml/tmp/$role/Model.mlpackage".also { disk.writeRawByteArray("$it/weights", ByteArray(4)) }
    }

    override fun compile(packagePath: String): String {
        val role: String = packagePath.removePrefix("inference/coreml/tmp/").substringBefore('/')
        reach(role, "compile")
        // ⚠ As Core ML does: the output is in a temporary place OUTSIDE the cache.
        return "system-tmp/${++compiles}.mlmodelc".also { disk.writeRawByteArray("$it/model.bin", ByteArray(8)) }
    }

    override fun move(temporary: String, role: String, archiveDigest: String) {
        reach(role, "move")
        disk.moveFile("$temporary/model.bin", compiled(role, archiveDigest))
    }

    override fun dropTemporary(temporary: String) = gone(temporary)

    override fun firstOpen(role: String, archiveDigest: String) = reach(role, "firstOpen")

    override fun writeMarker(archiveDigest: String) {
        reach(roleOf(compiling), "mark")
        if (!writesNoMarker) disk.writeRawByteArray(marker(archiveDigest, Unit), "digest=$archiveDigest".encodeToByteArray())
    }

    override fun deleteMarker(archiveDigest: String) = gone(marker(archiveDigest, Unit))
    override fun deleteTowers(archiveDigest: String) = gone(directory(archiveDigest))
    override fun deleteStaging(role: String) = gone("inference/coreml/tmp/$role")

    override fun entries(): List<String>? = if (unlisted) null else directories().toList()

    override fun deleteEntry(name: String) {
        events += "sweep $name"
        at("cache", "sweep")
        gone("inference/coreml/$name/ready")
        gone("inference/coreml/$name")
    }
}

/**
 * The prepare step (`ios-on-device-inference.md` §14.17), through the entry points the product
 * uses: the real [ModelPreparer], the real [CompiledCache], the real [AiReadiness] and the real
 * [DefaultModelStore], over [FakeCompiledFiles].
 * ⚠ The probe is a function a case answers for. The preflight's own half of the contract (its
 * backoff, what it holds, that it does not begin beside a prepare) is `PreflightTest`'s, on the
 * real `Preflight`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelPreparerTest {

    private val vision = ModelArtifactRole.VISION
    private val text = ModelArtifactRole.TEXT
    private val vocabulary = ModelArtifactRole.VOCABULARY
    private val bytes = mapOf(vision to visionBytes, text to textBytes, vocabulary to vocabularyBytes)

    private val disk = DownloadDisk()
    private val store = DownloadFixtures.storeOver(disk)
    private val files = FakeCompiledFiles(disk)
    private val cache = CompiledCache(files)
    private val steps = Mutex()
    private var foreground = true
    private val probes = mutableListOf<PreflightOutcome>()
    private val probed = mutableListOf<Boolean>()
    private var whileProbing: () -> Unit = {}
    private var beforeLoad: suspend () -> Unit = {}
    private var sessionsClosed = 0

    /** For each probe a check asked for: whether it was asked for past the backoff. */
    private val owedPastTheBackoff = mutableListOf<Boolean>()

    /** What a vocabulary-only update moves: part of the preflight's key, not of the towers'. */
    private var vocabularyRevision = 1
    private var passesCancelled = 0

    private fun record(store: ModelStore) = store.installed().firstOrNull { it.modelId == MODEL }

    private fun facts(store: ModelStore): AiModelFacts {
        val record = record(store)
        fun ready(role: String): String? = record?.artifacts?.firstOrNull { it.role == role }
            ?.let { (cache.tower(role, it.sha256) as? CompiledTower.Ready)?.path }
        val vocabularyThere: String? = record?.artifacts?.firstOrNull { it.role == vocabulary }
            ?.takeIf { disk.fileSize(it.localPath) == it.byteSize }?.localPath
        val parts = listOf(ready(vision), ready(text), vocabularyThere)
        return AiModelFacts(record != null, if (parts.all { it != null }) PreparedModel(parts[0]!!, parts[1]!!, parts[2]!!) else null)
    }

    private fun preflightKey(store: ModelStore): PreflightKey {
        val record = record(store)
        return PreflightKey(
            RuntimeFingerprint(record?.artifacts?.firstOrNull { it.role == vision }?.sha256, record?.artifacts?.firstOrNull { it.role == text }?.sha256, "cpuAndNeuralEngine", "23A1", 1),
            vocabularySha256 = "vocabulary-$vocabularyRevision", appBuild = "1", preflightRevision = 1,
        )
    }

    /** The process the next readiness is made in. ⚠ A new ledger is a new process (§14.5). */
    private var process = AttemptLedger()

    private fun readinessOver(store: ModelStore) = AiReadiness(
        files = { disk },
        facts = { facts(store) },
        currentKey = { preflightKey(store) },
        storesUsable = { true },
        applyFingerprint = { FingerprintOutcome.Unchanged },
        cancelPass = { passesCancelled++ },
        closeSessions = { sessionsClosed++ },
        ledger = process,
    )

    private val readiness = readinessOver(store)

    private fun TestScope.preparer(readiness: AiReadiness = this@ModelPreparerTest.readiness, store: ModelStore = this@ModelPreparerTest.store) = ModelPreparer(
        store = store,
        compiled = cache,
        readiness = readiness,
        preflight = { afterRecompile ->
            probed += afterRecompile
            whileProbing()
            probes.removeFirstOrNull() ?: PreflightOutcome.NotAwaiting
        },
        currentKey = { preflightKey(store) },
        notPrepared = { if (facts(store).prepared == null) "the vocabulary's file is not there at its recorded size" else null },
        inForeground = { foreground },
        beforeLoad = { beforeLoad() },
        steps = steps,
        modelId = MODEL,
        foregroundNow = { if (foreground) 1L else null },
    )

    /**
     * One check, as every trigger makes it (`PhotosOnDeviceAi.recheck`): the prepare, then the
     * foreground's probe when the state says one is owed. ⚠ The probe is this case's to answer.
     */
    private suspend fun ModelPreparer.checked(store: ModelStore = this@ModelPreparerTest.store): PrepareOutcome = check { pastTheBackoff ->
        probed += false
        owedPastTheBackoff += pastTheBackoff
        // ⚠ What the probe is of is what was installed when it BEGAN.
        val about: PreflightKey = preflightKey(store)
        whileProbing()
        ModelPreparer.Probed(about, probes.removeFirstOrNull() ?: PreflightOutcome.NotAwaiting)
    }.prepared

    private fun install(of: ModelEntryData = entry(), text: ByteArray = textBytes): InstalledModelData =
        store.add(of, bytes + (this.text to text))!!

    private fun key(of: InstalledModelData) = PrepareKey(MODEL, listOf(of.artifacts.first { it.role == vision }.sha256, of.artifacts.first { it.role == text }.sha256))

    private fun tower(role: String) = files.primitives.map { "$role $it" }
    private val visionTag = sha256(visionBytes).take(16)
    private val textTag = sha256(textBytes).take(16)

    /** The installed model made `Ready`, as the preflight does it: a probe let through, and a pass recorded. */
    private suspend fun TestScope.aReadyModel(): ModelPreparer {
        install()
        val preparer = preparer()
        assertIs<PrepareOutcome.Prepared>(preparer.checked())
        assertIs<org.tenshu.inference.proxy.Awaiting.Probe>(readiness.awaitingProbe())
        readiness.recordProbe(org.tenshu.inference.proxy.ProbeResult.PASSED, preflightKey(store), atEpochMs = 1)
        assertEquals(AiReadinessState.Ready, readiness.state.value)
        files.events.clear()
        probed.clear()
        sessionsClosed = 0
        passesCancelled = 0
        return preparer
    }

    // ─── The order of a prepare.

    @Test
    fun anInstalledModelIsCompiledTowerByTowerMarkedLastThenProbed() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        val preparer = preparer()
        assertEquals(AiReadinessState.Preparing, readiness.fingerprintChecked())

        val outcome = preparer.checked()

        assertEquals(tower(vision) + tower(text), files.events)
        // ⚠ What is reported is what readiness read once the markers were there, and the probe was asked.
        assertEquals(PrepareOutcome.Prepared(AiReadinessState.AwaitingVerdict, PreflightOutcome.NotAwaiting), outcome)
        assertEquals(listOf(false), probed)
        assertEquals(outcome, preparer.outcomeFor(key(installed)))
        assertEquals(emptyList(), files.compilerOutputs())
        assertEquals(setOf(visionTag, textTag), files.directories())
    }

    // ─── A call that changes nothing writes nothing.

    @Test
    fun aPrepareWithNothingToDoDoesNotTouchAModelThatIsBeingServed() = runTest(UnconfinedTestDispatcher()) {
        val preparer = aReadyModel()
        val states = mutableListOf<AiReadinessState>()
        val watching = backgroundScope.launch { readiness.state.collect { states += it } }

        val outcome = preparer.checked()

        // ⚠⚠ Every return to the foreground makes this call. It un-served the model, closed both
        // towers and cancelled a running pass each time, to compile nothing.
        assertEquals(PrepareOutcome.Prepared(AiReadinessState.Ready, preflight = null), outcome)
        assertEquals<List<AiReadinessState>>(listOf(AiReadinessState.Ready), states, "a prepare that changed nothing published a state")
        assertEquals(0, sessionsClosed, "a prepare that changed nothing closed the towers")
        assertEquals(0, passesCancelled, "a prepare that changed nothing cancelled a pass")
        assertNotNull(readiness.serving())
        assertEquals(emptyList(), files.events)
        assertEquals(emptyList(), probed)
        watching.cancel()
    }

    @Test
    fun sweepingWhatNoRecordNamesDoesNotTouchAModelThatIsBeingServed() = runTest(UnconfinedTestDispatcher()) {
        val preparer = aReadyModel()
        disk.writeRawByteArray("inference/coreml/ffffffffffffffff/text.mlmodelc/model.bin", ByteArray(3))

        assertEquals(PrepareOutcome.Prepared(AiReadinessState.Ready, preflight = null), preparer.checked())

        assertEquals(listOf("sweep ffffffffffffffff"), files.events)
        assertEquals(0, sessionsClosed)
        assertNotNull(readiness.serving())
    }

    // ─── Not on a graph that is gone, and nothing compiled in the background (I5).

    @Test
    fun nothingIsCompiledWhilePhotosIsNotInTheForeground() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        foreground = false
        val preparer = preparer()

        assertEquals(PrepareOutcome.NotInForeground, preparer.checked())

        assertEquals(emptyList(), files.events)
        assertEquals(PrepareOutcome.NotInForeground, preparer.outcomeFor(key(installed)))
        foreground = true
        assertIs<PrepareOutcome.Prepared>(preparer.checked())
    }

    @Test
    fun aStopAtAnyStepLeavesNothingOfTheTowerNotEvenCoreMlsOutput() = runTest(UnconfinedTestDispatcher()) {
        install()
        // ⚠⚠ Every step is a point where the question can answer "no": the scene has left.
        files.primitives.forEach { stopAt ->
            foreground = true
            files.at = { role, primitive -> if (role == vision && primitive == stopAt) foreground = false }
            val preparer = preparer()

            assertEquals(PrepareOutcome.NotInForeground, preparer.checked(), "stopped as $stopAt was reached")

            // What that step itself did is done; the NEXT step is not taken, and nothing is left:
            // no marker, no directory, no package, and not the compiler's output, which is
            // outside the cache and as large as the model. (It leaked when the stop came
            // between the compile and the move.)
            if (stopAt != "mark") {
                assertEquals(emptySet(), files.directories(), "stopped as $stopAt was reached")
                assertEquals(emptyList(), files.compilerOutputs(), "Core ML's output was left when the stop came at $stopAt")
            }
            if (stopAt != "mark") assertEquals(AiReadinessState.Preparing, readiness.fingerprintChecked())
            files.at = { _, _ -> }
            disk.paths.filter { it.startsWith("inference/coreml/") }.forEach { disk.deleteFile(it) }
        }
    }

    @Test
    fun aStepThatThrowsAtAnyPointLeavesNothingOfTheTower() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        files.primitives.forEach { failAt ->
            files.at = { role, primitive -> if (role == text && primitive == failAt) error("$failAt failed") }
            val preparer = preparer()

            val outcome = preparer.checked()

            assertEquals(PrepareOutcome.Failed(text, "$failAt failed"), outcome, failAt)
            assertEquals(outcome, preparer.outcomeFor(key(installed)))
            // The vision tower, finished before it, keeps its marker; nothing of the text tower is left.
            assertEquals(setOf(visionTag), files.directories(), "a step that threw at $failAt left something")
            assertEquals(emptyList(), files.compilerOutputs(), "Core ML's output was left when $failAt threw")
            assertEquals(AiReadinessState.Preparing, readiness.fingerprintChecked())
            files.at = { _, _ -> }
            disk.paths.filter { it.startsWith("inference/coreml/") }.forEach { disk.deleteFile(it) }
        }
    }

    @Test
    fun aTeardownUnderACompileLeavesNoMarkerAndTheGraphWritesNothingMore() = runTest(UnconfinedTestDispatcher()) {
        install()
        files.at = { role, primitive -> if (role == text && primitive == "firstOpen") readiness.retire() }
        val preparer = preparer()

        assertEquals(PrepareOutcome.Abandoned, preparer.checked())

        assertEquals(setOf(visionTag), files.directories())
        files.events.clear()
        assertEquals(PrepareOutcome.Abandoned, preparer.checked())
        assertEquals(emptyList(), files.events)
    }

    // ─── What a screen follows (§14.18.2): each is the owner's own value, not a copy.

    @Test
    fun aCheckIsCountedWhenItHasEndedAndNotBefore() = runTest(UnconfinedTestDispatcher()) {
        install()
        val preparer = preparer()
        assertEquals(0L, preparer.checksEnded.value)
        var duringTheProbe: Long? = null
        probes += passed
        preparer.check { _ ->
            duringTheProbe = preparer.checksEnded.value
            ModelPreparer.Probed(preflightKey(store), probes.removeAt(0))
        }
        assertEquals(0L, duringTheProbe, "a check was counted as ended while it ran")
        assertEquals(1L, preparer.checksEnded.value)

        // ⚠ One that threw is not one that ended.
        assertFailsWith<IllegalStateException> { preparer.check { _ -> error("the probe threw") } }
        assertEquals(1L, preparer.checksEnded.value)
    }

    @Test
    fun compilingIsSaidWhileATowerIsCompiledAndNotForASweepAnEvictionOrAnInstallThatCannotBePrepared() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        val preparer = preparer()
        val seen = mutableListOf<Pair<String, Boolean>>()
        files.at = { _, primitive -> seen += primitive to (preparer.compiling.value == key(installed)) }
        assertEquals(null, preparer.compiling.value)
        preparer.checked()
        // ⚠ Every primitive of a tower's prepare runs inside it...
        assertTrue(seen.isNotEmpty() && seen.filter { it.first == "space" }.all { it.second }, "$seen")
        assertEquals(null, preparer.compiling.value)

        // ...and a check with nothing to compile (the sweep every check makes) never says it.
        seen.clear()
        val changes = mutableListOf<Int>()
        preparer.checked()
        assertTrue(seen.none { it.second }, "a check that compiled nothing said it was compiling: $seen")
        // Nor does a removal's eviction, although the cache changes under it.
        preparer.evicting(MODEL)
        assertTrue(seen.none { it.second }, "$seen")
        assertEquals(key(installed), preparer.keyFor(installed))
        changes.clear()
    }

    @Test
    fun aStoreSaysInOneAnswerWhetherItHoldsRecordsNoIndexOrCouldNotBeRead() = runTest(UnconfinedTestDispatcher()) {
        val index = "$DEFAULT_MODELS_DIR/inference.models.index.json"
        assertEquals(InstalledIndex.Records(emptyList()), DownloadFixtures.storeOver(disk).read())
        val installed = install()
        assertEquals(InstalledIndex.Records(listOf(installed)), DownloadFixtures.storeOver(disk).read())

        // ⚠⚠ Bytes that are no index: `installed()` answers no records, by decision. This does not.
        disk.writeRawByteArray(index, "not json at all".encodeToByteArray())
        val notAnIndex = DownloadFixtures.storeOver(disk)
        assertEquals(emptyList(), notAnIndex.installed())
        assertEquals(InstalledIndex.NotAnIndex, notAnIndex.read())

        disk.readFaults[index] = DownloadDisk.ReadFault.THROWS
        assertIs<InstalledIndex.Unreadable>(DownloadFixtures.storeOver(disk).read())
    }

    @Test
    fun readinessPublishesItsStateWithTheInstallItIsAboutAndSaysWhenItReadNothing() = runTest(UnconfinedTestDispatcher()) {
        // ⚠⚠ Before the first reconcile the state flow holds `NoModel`, and that is not a reading.
        install()
        assertEquals(ReadinessStanding.NotReadYet, readiness.standing.value)
        assertEquals(AiReadinessState.NoModel, readiness.state.value)

        readiness.fingerprintChecked()
        assertEquals(ReadinessStanding.For(preflightKey(store), AiReadinessState.Preparing), readiness.standing.value)

        // Through the prepare and a pass: every state it publishes carries the key it is about.
        probes += passed
        val preparer = preparer()
        preparer.checked()
        assertEquals(ReadinessStanding.For(preflightKey(store), readiness.state.value), readiness.standing.value)

        // ⚠⚠ And a state that moves with NO reconcile (a probe's result leaving `Ready`, a
        // verdict entering it) is published with its key in the same write: followed here at
        // every value the state flow takes, not only at the end.
        val pairs = mutableListOf<Pair<AiReadinessState, ReadinessStanding>>()
        val following = backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            readiness.state.collect { state -> pairs += state to readiness.standing.value }
        }
        readiness.recordProbe(ProbeResult.FAILED, preflightKey(store), atEpochMs = 1)
        readiness.recordVerdict(passed = false, probed = preflightKey(store))
        following.cancel()
        assertTrue(pairs.size >= 2, "the state did not move: the case follows nothing ($pairs)")
        pairs.forEach { (state, standing) ->
            assertEquals(ReadinessStanding.For(preflightKey(store), state), standing, "a state was published without what it is about: $pairs")
        }

        // ⚠⚠ An update with another text tower: from the reconcile on, the standing is about the
        // NEW install's key, whatever the state is, and never the old key with a new state.
        val oldKey = preflightKey(store)
        val newText = ByteArray(8192) { 7 }
        install(entry(version = 2, text = newText), text = newText)
        readiness.fingerprintChecked()
        val after = assertIs<ReadinessStanding.For>(readiness.standing.value)
        assertEquals(preflightKey(store), after.key)
        assertTrue(after.key != oldKey)
        assertEquals(readiness.state.value, after.state)

        // A read that fails keeps the state it had, and says it could not read.
        disk.readFaults["$DEFAULT_MODELS_DIR/inference.models.index.json"] = DownloadDisk.ReadFault.THROWS
        val unreadable = readinessOver(DownloadFixtures.storeOver(disk))
        unreadable.fingerprintChecked()
        assertEquals(ReadinessStanding.Unreadable, unreadable.standing.value)
        disk.readFaults.clear()
        unreadable.fingerprintChecked()
        assertIs<ReadinessStanding.For>(unreadable.standing.value)
    }

    @Test
    fun theCacheIsSaidToBeChangingExactlyWhileItIs() = runTest(UnconfinedTestDispatcher()) {
        install()
        val seen = mutableListOf<Int>()
        files.at = { _, primitive -> if (primitive == "space") seen += readiness.cacheChanges.value }
        assertEquals(0, readiness.cacheChanges.value)
        preparer().checked()
        assertEquals(listOf(1, 1), seen, "a tower was compiled while the cache was not said to be changing")
        assertEquals(0, readiness.cacheChanges.value)
    }

    @Test
    fun aRetirementIsSaidOnTheFlowTheGatesRead() = runTest(UnconfinedTestDispatcher()) {
        assertFalse(readiness.retiredNow.value)
        readiness.retire()
        assertTrue(readiness.retiredNow.value)
        assertTrue(readiness.isRetired())
    }

    @Test
    fun theKeyOfAnInstallIsTheKeyItsReportCarries() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        val preparer = preparer()
        preparer.checked()
        assertEquals(preparer.keyFor(installed), preparer.lastReport.value?.key)
        assertEquals(key(installed), preparer.keyFor(installed))
    }

    @Test
    fun noRoomIsSaidForThisInstallAndStaysSaidWhilePhotosIsAway() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        files.noRoom = SpaceRefusal.Insufficient("vision", neededBytes = 555_000_000L, freeBytes = 12L)
        val preparer = preparer()
        // ⚠ The two figures as VALUES (§14.18, M8): a screen words them; the sentence is a log's.
        val failed = PrepareOutcome.Failed(vision, files.noRoom!!.why, PrepareOutcome.SpaceShort(555_000_000L, 12L))
        assertEquals(failed, preparer.checked())

        // An index that cannot be read at the next call: a refusal about no install.
        disk.readFaults["$DEFAULT_MODELS_DIR/inference.models.index.json"] = DownloadDisk.ReadFault.THROWS
        val unreadable = ModelPreparer(DownloadFixtures.storeOver(disk), cache, readiness, { PreflightOutcome.NotAwaiting }, { null }, { preflightKey(store) }, { true }, {}, steps, modelId = MODEL, foregroundNow = { if (foreground) 1L else null })
        assertIs<PrepareOutcome.Unreadable>(unreadable.checked())
        assertIs<PrepareOutcome.Unreadable>(unreadable.lastRefusal.value?.outcome)
        // ⚠⚠ Stamped with the install readiness was about when the call was refused: what a
        // screen compares before it says this of anything.
        val readinessWasAbout: PreflightKey? = (readiness.standing.value as? ReadinessStanding.For)?.key
        assertNotNull(readinessWasAbout)
        assertEquals(readinessWasAbout, unreadable.lastRefusal.value?.about)
        disk.readFaults.clear()

        // ⚠⚠ In this graph: a call that never read an install does not erase what was said of one.
        foreground = false
        files.noRoom = null
        readiness.retire()
        assertEquals(PrepareOutcome.Abandoned, preparer.checked())
        assertEquals(failed, preparer.outcomeFor(key(installed)), "a refusal about no install erased why this one is not prepared")
        assertEquals(PrepareRefusal(readinessWasAbout, PrepareOutcome.Abandoned), preparer.lastRefusal.value)

        // And one made before readiness had read anything is about NO install.
        disk.readFaults["$DEFAULT_MODELS_DIR/inference.models.index.json"] = DownloadDisk.ReadFault.THROWS
        val unread = readinessOver(DownloadFixtures.storeOver(disk))
        val early = ModelPreparer(DownloadFixtures.storeOver(disk), cache, unread, { PreflightOutcome.NotAwaiting }, { null }, { preflightKey(store) }, { true }, {}, steps, modelId = MODEL, foregroundNow = { if (foreground) 1L else null })
        early.checked()
        disk.readFaults.clear()
        assertEquals(ReadinessStanding.NotReadYet, unread.standing.value)
        assertEquals(null, early.lastRefusal.value?.about)
        assertIs<PrepareOutcome.Unreadable>(early.lastRefusal.value?.outcome)
    }

    @Test
    fun aPrepareThatReturnsAndLeftNoMarkerIsNotReportedAsPrepared() = runTest(UnconfinedTestDispatcher()) {
        install()
        files.writesNoMarker = true

        assertIs<PrepareOutcome.Failed>(preparer().checked())
        assertEquals(AiReadinessState.Preparing, readiness.fingerprintChecked())
        assertEquals(emptyList(), probed)
    }

    @Test
    fun anInstallReadinessDoesNotReadAsPreparedIsNotReportedAsPrepared() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        // The vocabulary's file is gone from under its record. Both towers compile.
        disk.deleteFile(installed.pathOf(vocabulary)!!)
        val preparer = preparer()

        val outcome = preparer.checked()

        // ⚠⚠ By readiness's own reading, not by the markers alone: "prepared" would be said at
        // every trigger for ever, of a model that stays `Preparing` with nothing to say why.
        assertEquals(PrepareOutcome.Incomplete("the vocabulary's file is not there at its recorded size"), outcome)
        assertEquals(outcome, preparer.outcomeFor(key(installed)))
        assertEquals(AiReadinessState.Preparing, readiness.state.value)
        assertEquals(emptyList(), probed)
        // And so it is at the next call, which compiles nothing.
        files.events.clear()
        assertEquals(outcome, preparer.checked())
        assertEquals(emptyList(), files.events)
    }

    // ─── The sweep: a removal, an update, a crash (I2).

    @Test
    fun aRemovalEvictsTheCompiledTowersInItsOwnStepWhereverPhotosIs() = runTest(UnconfinedTestDispatcher()) {
        install()
        val preparer = preparer()
        preparer.checked()
        assertEquals(2, files.directories().size)
        files.events.clear()
        // The user has left: the removal is made as Photos goes to the background.
        foreground = false

        store.remove(MODEL)
        // What the downloader's removal step calls, under the step lock it holds.
        steps.lock()
        preparer.evicted(MODEL)
        steps.unlock()

        // ⚠⚠ Not a compile: file deletes, made in the step that removed the model. Left to the
        // next prepare, 1.8 GB stayed until Photos was opened again.
        assertEquals(emptySet(), files.directories(), "a removed model's compiled towers were left")
        assertEquals(PrepareOutcome.NoModel, preparer.lastReport.value?.outcome)
    }

    @Test
    fun aRemovalDeletesTheModelsTowersBeforeItsRecordAndFailsWhenTheyDoNotGo() = runTest(UnconfinedTestDispatcher()) {
        val preparer = aReadyModel()
        val installed = record(store)!!
        // The user has left; a removal is a delete, not a compile.
        foreground = false
        files.stuck += files.directory(sha256(textBytes))

        steps.lock()
        // ⚠⚠ Before the record is removed, and it throws: the caller must not remove it.
        assertFailsWith<CompiledCacheNotClearedException> { preparer.evicting(MODEL) }
        steps.unlock()

        // The model is still installed (the removal was not made), it is not served any more,
        // and what is said of it is why.
        assertEquals(1, store.installed().size)
        assertFalse(readiness.serves(), "towers were being deleted under a model still served")
        assertEquals(1, sessionsClosed)
        assertIs<PrepareOutcome.Failed>(preparer.outcomeFor(key(installed)))

        // Asked again once it can be deleted: the towers go, and then the record may.
        files.stuck.clear()
        steps.lock()
        preparer.evicting(MODEL)
        store.remove(MODEL)
        preparer.evicted(MODEL)
        steps.unlock()
        assertEquals(emptySet(), files.directories())
        assertEquals(PrepareOutcome.NoModel, preparer.lastReport.value?.outcome)
    }

    @Test
    fun aPassAfterACompileIsTheChecksAnswer() = runTest(UnconfinedTestDispatcher()) {
        install()
        val passed = PreflightOutcome.Passed(org.tenshu.inference.proxy.PreflightMeasurement(thermalAtStart = 0, memoryWarned = false))
        probes += passed

        // The check compiles the towers and probes them: the pass is the answer it hands back,
        // which is what the warm-up follows.
        val checked = preparer().check { _ ->
            ModelPreparer.Probed(preflightKey(store), probes.removeAt(0))
        }
        assertEquals(passed, checked.probed)
    }

    @Test
    fun anUpdateDeletesTheOldCompiledGenerationBeforeItCompilesTheNew() = runTest(UnconfinedTestDispatcher()) {
        install()
        val preparer = preparer()
        preparer.checked()
        // A newer entry with another text tower; the vision tower is the same archive.
        val newText = ByteArray(8192) { 7 }
        install(entry(version = 2, text = newText), text = newText)
        var beside: Set<String>? = null
        files.at = { _, primitive -> if (primitive == "space" && beside == null) beside = files.directories() }
        files.events.clear()

        assertIs<PrepareOutcome.Prepared>(preparer.checked())

        // ⚠⚠ When the new tower's first step starts, the old one's directory is already gone.
        assertEquals(setOf(visionTag), beside, "the old generation was still there when the new one began to compile")
        assertEquals(listOf("sweep $textTag") + tower(text), files.events)
        assertEquals(setOf(visionTag, sha256(newText).take(16)), files.directories())
    }

    @Test
    fun whatACrashLeftIsSweptOrCompiledAgainAndNeverTrusted() = runTest(UnconfinedTestDispatcher()) {
        install()
        // A compiled directory with no marker for the installed vision tower; a directory no
        // record names, with its marker; the staging area.
        disk.writeRawByteArray(files.compiled(vision, sha256(visionBytes)), ByteArray(3))
        disk.writeRawByteArray("inference/coreml/ffffffffffffffff/text.mlmodelc/model.bin", ByteArray(3))
        disk.writeRawByteArray("inference/coreml/ffffffffffffffff/ready", "digest=ffffffffffffffff".encodeToByteArray())
        disk.writeRawByteArray("inference/coreml/tmp/vision/Model.mlpackage/weights", ByteArray(3))

        assertIs<PrepareOutcome.Prepared>(preparer().checked())

        assertEquals(listOf("sweep ffffffffffffffff", "sweep tmp"), files.events.take(2))
        // ⚠ No marker, so not trusted: the vision tower went through every step again.
        assertEquals(tower(vision), files.events.filter { it.startsWith(vision) })
        assertEquals(setOf(visionTag, textTag), files.directories())
    }

    @Test
    fun aDeleteThatDidNotDeleteIsNotADelete() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        disk.writeRawByteArray("inference/coreml/ffffffffffffffff/text.mlmodelc/model.bin", ByteArray(3))
        files.stuck += "inference/coreml/ffffffffffffffff"
        val preparer = preparer()

        // ⚠⚠ It was reported as swept, and the new generation compiled beside it.
        val outcome = assertIs<PrepareOutcome.Failed>(preparer.checked())
        assertEquals(outcome, preparer.outcomeFor(key(installed)))
        assertEquals(setOf("ffffffffffffffff"), files.directories(), "something was compiled beside a directory that could not be deleted")
    }

    @Test
    fun aDiscardTakesTheMarkerFirstSoAHalfDeletedTowerIsNeverReady() = runTest(UnconfinedTestDispatcher()) {
        install()
        preparer().checked()
        val digest = sha256(visionBytes)
        // The directory's delete does not finish (the process dies in it, or it fails).
        files.stuck += files.compiled(vision, digest).substringBeforeLast('/')

        assertFailsWith<CompiledCacheNotClearedException>("a discard that left the tower's files said it was gone") { cache.discard(digest) }

        // ⚠⚠ (I3) The marker went first: what is left is a directory with no marker, which is
        // not ready. A marker left beside a gutted directory read as ready, was probed, failed,
        // and counted against the install.
        assertEquals(CompiledTower.Absent, cache.tower(vision, digest))
    }

    // ─── A primitive that did not do its work is never a step that was taken.

    @Test
    fun aPackageThatCouldNotBeDeletedIsNotATowerThatIsReady() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        // The unpacked package of the vision tower cannot be deleted once it is there.
        files.at = { role, primitive -> if (role == vision && primitive == "move") files.stuck += "inference/coreml/tmp/vision" }
        val preparer = preparer()

        val outcome = assertIs<PrepareOutcome.Failed>(preparer.checked())

        // ⚠⚠ The delete's answer was ignored: the tower was opened and marked ready beside a
        // package of its own size, and the text tower compiled on top of that.
        assertEquals(vision, outcome.role)
        assertTrue("inference/coreml/tmp/vision" in outcome.why, outcome.why)
        assertEquals(outcome, preparer.outcomeFor(key(installed)))
        assertEquals(MarkerRead.Absent, files.marker(sha256(visionBytes)))
        assertFalse(files.events.any { it.startsWith(text) }, "the sibling tower was compiled beside what could not be deleted")
        assertEquals(listOf("vision space", "vision unpack", "vision compile", "vision move"), files.events)

        // And the next call meets it first, in its sweep, and still compiles nothing.
        files.events.clear()
        assertIs<PrepareOutcome.Failed>(preparer.checked())
        assertEquals(listOf("sweep tmp"), files.events)
        files.stuck.clear()
        files.at = { _, _ -> }
        assertIs<PrepareOutcome.Prepared>(preparer.checked())
    }

    @Test
    fun whatTheCleanupCouldNotDeleteIsSaidWithWhatWentWrong() = runTest(UnconfinedTestDispatcher()) {
        install()
        // The first open fails, and Core ML's leftovers in the cache cannot be deleted either.
        files.at = { role, primitive ->
            if (role == vision && primitive == "firstOpen") {
                files.stuck += files.directory(sha256(visionBytes))
                error("Core ML could not open the model")
            }
        }

        val outcome = assertIs<PrepareOutcome.Failed>(preparer().checked())

        assertTrue("Core ML could not open the model" in outcome.why, outcome.why)
        assertTrue("could not delete" in outcome.why, "what the cleanup left was not said: ${outcome.why}")
        assertFalse(files.events.any { it.startsWith(text) })
        // ⚠ (I3) The marker was never written, so what is left is not a tower.
        assertEquals(CompiledTower.Absent, cache.tower(vision, sha256(visionBytes)))
    }

    @Test
    fun coreMlsOutputThatCouldNotBeDeletedIsSaid() = runTest(UnconfinedTestDispatcher()) {
        install()
        files.at = { role, primitive ->
            if (role == vision && primitive == "move") {
                files.stuck += "system-tmp"
                error("the move failed")
            }
        }

        val outcome = assertIs<PrepareOutcome.Failed>(preparer().checked())

        assertTrue("the move failed" in outcome.why && "system-tmp" in outcome.why, outcome.why)
        assertEquals(1, files.compilerOutputs().size)
        // ⚠ Each part of the cleanup is attempted whatever became of the others: the package,
        // which CAN be deleted, is not left because the compiler's output could not be.
        assertEquals(emptySet(), files.directories(), "the cleanup stopped at the first thing it could not delete")
    }

    @Test
    fun whatAnEarlierPrepareLeftAndCannotBeDeletedIsNotCompiledOver() = runTest(UnconfinedTestDispatcher()) {
        install()
        // A directory with no marker for the installed vision tower, which will not go.
        disk.writeRawByteArray(files.compiled(vision, sha256(visionBytes)), ByteArray(3))
        files.stuck += files.directory(sha256(visionBytes))

        val outcome = assertIs<PrepareOutcome.Failed>(preparer().checked())

        assertEquals(vision, outcome.role)
        assertEquals(emptyList(), files.events, "something was compiled over a directory that could not be cleared")
    }

    // ─── Nothing after a read that failed (I4).

    @Test
    fun aMarkerThatCannotBeReadIsNotATowerToCompile() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        val preparer = preparer()
        preparer.checked()
        files.events.clear()
        disk.readFaults[files.marker(sha256(visionBytes), Unit)] = DownloadDisk.ReadFault.NULL_THOUGH_PRESENT

        val outcome = preparer.checked()

        assertIs<PrepareOutcome.Unreadable>(outcome)
        assertEquals(outcome, preparer.outcomeFor(key(installed)))
        assertEquals(emptyList(), files.events)
        assertEquals(2, files.directories().size)
    }

    @Test
    fun anIndexThatCannotBeReadOrIsNotAnIndexSweepsAndCompilesNothing() = runTest(UnconfinedTestDispatcher()) {
        install()
        preparer().checked()
        files.events.clear()
        val index = "$DEFAULT_MODELS_DIR/inference.models.index.json"

        disk.readFaults[index] = DownloadDisk.ReadFault.THROWS
        val unreadable = DownloadFixtures.storeOver(disk)
        assertIs<PrepareOutcome.Unreadable>(preparer(readinessOver(unreadable), unreadable).checked())
        disk.readFaults.clear()

        // ⚠⚠ Bytes that are not an index at all: the store answers "no records" for them, by
        // decision, and sweeps nothing of its own. "No record" would have swept both towers here.
        disk.writeRawByteArray(index, "not json at all".encodeToByteArray())
        val notAnIndex = DownloadFixtures.storeOver(disk)
        assertEquals(emptyList(), notAnIndex.installed())
        assertIs<PrepareOutcome.Unreadable>(preparer(readinessOver(notAnIndex), notAnIndex).checked())

        assertEquals(emptyList(), files.events)
        assertEquals(2, files.directories().size, "compiled towers were swept against an index that was not read")
    }

    @Test
    fun anIndexWrittenOverBytesThatWereNoIndexIsAnIndexAgain() = runTest(UnconfinedTestDispatcher()) {
        val index = "$DEFAULT_MODELS_DIR/inference.models.index.json"
        disk.writeRawByteArray(index, "not json at all".encodeToByteArray())
        val store = DownloadFixtures.storeOver(disk)
        val other = DownloadFixtures.storeOver(disk)
        assertFalse(store.holdsAnIndex())
        assertFalse(other.holdsAnIndex())
        // A commit writes a valid index over those bytes, and the model is compiled.
        store.add(entry(), bytes)!!
        val preparer = preparer(readinessOver(store), store)
        assertIs<PrepareOutcome.Prepared>(preparer.checked())
        assertEquals(2, files.directories().size)

        // The model is removed, in the same process.
        store.remove(MODEL)

        // ⚠⚠ What a store remembers of the OLD bytes must not outlive the write: a store that
        // answers a valid empty index and still says it holds none makes the eviction refuse to
        // delete the compiled models.
        assertTrue(store.holdsAnIndex(), "a store still said its index was no index after writing one")
        assertTrue(other.holdsAnIndex(), "a second store went on believing bytes that had been written over")
        steps.lock()
        preparer.evicted(MODEL)
        steps.unlock()
        assertEquals(emptySet(), files.directories(), "a removed model's towers were kept against an index that is one")
    }

    @Test
    fun aCacheThatCannotBeListedIsNotCompiledBeside() = runTest(UnconfinedTestDispatcher()) {
        install()
        files.unlisted = true

        assertIs<PrepareOutcome.Unreadable>(preparer().checked())
        assertEquals(emptyList(), files.events)
    }

    @Test
    fun whateverTheCacheThrowsIsAnOutcomeForThisInstallAndNeverLeavesTheCall() = runTest(UnconfinedTestDispatcher()) {
        val broken = object : CompiledModels by cache {
            override fun sweep(liveDigests: Set<String>): CompiledSweep = throw IllegalArgumentException("'not-a-digest' is not a sha256")
        }
        val installed = install()
        val preparer = ModelPreparer(store, broken, readiness, { PreflightOutcome.NotAwaiting }, { null }, { preflightKey(store) }, { true }, {}, steps, modelId = MODEL, foregroundNow = { if (foreground) 1L else null })

        val outcome = assertIs<PrepareOutcome.Failed>(preparer.checked())
        assertEquals(outcome, preparer.outcomeFor(key(installed)))
        assertFalse(steps.isLocked)
    }

    // ─── A first open the process did not survive (§14.5).

    /** What the last killed process did. */
    private var killedProcess: List<String> = emptyList()

    /** One check in which Photos is killed as [role]'s first open runs; then the next process. */
    private suspend fun TestScope.killedAtTheFirstOpenOf(role: String, readiness: AiReadiness, step: String = "firstOpen"): AiReadiness {
        files.at = { at, primitive ->
            if (at == role && primitive == step) {
                disk.dead = true
                error("killed")
            }
        }
        // ⚠ Whatever the dead process went on to do in memory, none of it reached the disk.
        files.events.clear()
        runCatching { preparer(readiness).checked() }
        killedProcess = files.events.toList()
        files.at = { _, _ -> }
        disk.dead = false
        process = AttemptLedger()
        files.events.clear()
        return readinessOver(store)
    }

    @Test
    fun aFirstOpenThatKillsPhotosIsCountedAndTheSecondStopsEveryPrepareOfThatInstall() = runTest(UnconfinedTestDispatcher()) {
        install()

        val second = killedAtTheFirstOpenOf(text, readiness)
        assertEquals(AiReadinessState.Preparing, second.fingerprintChecked(), "one load Photos did not survive is a verdict")
        val third = killedAtTheFirstOpenOf(text, second)
        assertTrue(listOf("$text compile", "$text firstOpen").all { it in killedProcess }, "one kill stopped the next process's prepare: $killedProcess")

        assertEquals(AiReadinessState.Unsuitable, third.fingerprintChecked(), "two loads Photos did not survive are not a verdict")
        val outcome = preparer(third).checked()

        assertEquals(PrepareOutcome.Prepared(AiReadinessState.Unsuitable, preflight = null), outcome)
        assertEquals(emptyList(), files.events.filter { "compile" in it || "firstOpen" in it }, "an install that killed Photos twice was loaded again")
        assertEquals(emptyList(), probed, "an unsuitable install was probed")
    }

    /**
     * ⚠⚠ With automatic loads stopped the model can always be removed (§14.5): no probe or
     * prepare holds the step lock or a pair of towers for the removal to wait behind. And a
     * download of the same bytes again is answered at once: one wasted download (D12).
     */
    @Test
    fun anInstallThatKilledPhotosCanBeRemovedAndIsNotLoadedAgainWhenFetchedAgain() = runTest(UnconfinedTestDispatcher()) {
        install()
        val third = killedAtTheFirstOpenOf(text, killedAtTheFirstOpenOf(text, readiness))
        val preparer = preparer(third)
        preparer.checked()

        preparer.evicting(MODEL)
        PinnedModelChanges(third, MODEL).changing(MODEL) { store.remove(MODEL) }
        preparer.evicted(MODEL)

        assertNull(record(store), "the removal did not happen")
        assertEquals(emptySet(), files.directories(), "the towers of the removed install are still on disk")
        assertEquals(AiReadinessState.NoModel, third.state.value)

        install()
        files.events.clear()
        assertEquals(PrepareOutcome.Prepared(AiReadinessState.Unsuitable, preflight = null), preparer.checked())
        assertEquals(emptyList(), files.events.filter { "compile" in it || "firstOpen" in it })
    }

    /** ⚠⚠ The compile is a load too: one that Photos does not survive is counted. */
    @Test
    fun aCompileThatKillsPhotosIsCountedLikeAFirstOpen() = runTest(UnconfinedTestDispatcher()) {
        install()

        val second = killedAtTheFirstOpenOf(text, readiness, step = "compile")
        assertEquals(AiReadinessState.Preparing, second.fingerprintChecked())
        val third = killedAtTheFirstOpenOf(text, second, step = "compile")

        assertEquals(AiReadinessState.Unsuitable, third.fingerprintChecked(), "two compiles Photos did not survive are not a verdict")
    }

    @Test
    fun aFirstOpenThatFailsOrIsStoppedLeavesNothingForTheNextProcessToCount() = runTest(UnconfinedTestDispatcher()) {
        install()
        val stops: List<Pair<String, () -> Unit>> = listOf(
            "a failed open" to { error("will not load") },
            "Photos left the foreground" to { foreground = false },
        )
        var now: AiReadiness = readiness
        stops.forEach { (what, stop) ->
            files.at = { role, primitive -> if (role == text && primitive == "firstOpen") stop() }
            assertIs<PrepareOutcome>(preparer(now).checked(), what)
            foreground = true
            files.at = { _, _ -> }
            // ⚠ Each stop is followed by a new process, which counts whatever it left.
            process = AttemptLedger()
            now = readinessOver(store)
            assertEquals(AiReadinessState.Preparing, now.fingerprintChecked(), what)
        }
        assertIs<PrepareOutcome.Prepared>(preparer(now).checked())
        val target = assertIs<org.tenshu.inference.proxy.Awaiting.Probe>(now.awaitingProbe()).target
        assertEquals(0, target.attempts.interrupted, "a first open that unwound was counted as a kill")
    }

    @Test
    fun nothingIsCompiledWhoseFirstOpenCouldNotBeCounted() = runTest(UnconfinedTestDispatcher()) {
        install()
        disk.readFaults[AiReadiness.VERDICT_PATH] = DownloadDisk.ReadFault.THROWS

        assertIs<PrepareOutcome.Unreadable>(preparer().checked())

        assertEquals(emptyList(), files.events.filter { "compile" in it || "firstOpen" in it }, "a tower was compiled to be opened unrecorded")
        disk.readFaults.clear()
        assertIs<PrepareOutcome.Prepared>(preparer().checked())
    }

    // ─── A compiled tower that will not load is compiled once more.

    private val towersFailed = PreflightOutcome.Retry(PreflightFinding.Failed("the vision tower did not load"), PreflightAttempts.NONE)

    @Test
    fun aTowerTheProbeCouldNotLoadIsCompiledOnceMoreAndProbedAtOnce() = runTest(UnconfinedTestDispatcher()) {
        install()
        probes += towersFailed
        val preparer = preparer()

        val outcome = preparer.checked()

        assertEquals(tower(vision) + tower(text) + tower(vision) + tower(text), files.events)
        // ⚠⚠ The second probe is asked as one that follows a recompile: not held back by the
        // backoff of the probe that failed seconds ago.
        assertEquals(listOf(false, true), probed)
        assertEquals(PrepareOutcome.Prepared(AiReadinessState.AwaitingVerdict, PreflightOutcome.NotAwaiting), outcome)

        // ⚠ Once: a model that still will not load is the preflight's to count. (Told so by
        // the foreground's own probe this time, which is how a later one arrives.)
        files.events.clear()
        assertEquals(towersFailed, preparer.probed(preflightKey(store), preparer.generation(), towersFailed))
        assertEquals(emptyList(), files.events)
    }

    @Test
    fun aFailedProbeMadeByTheForegroundCheckLeadsToTheRecompileToo() = runTest(UnconfinedTestDispatcher()) {
        install()
        val preparer = preparer()
        preparer.checked()
        files.events.clear()
        probed.clear()
        // A later foreground: the prepare changes nothing, a verdict is still awaited, and the
        // check's probe says the towers would not load.
        probes += towersFailed
        preparer.checked()

        assertEquals(tower(vision) + tower(text), files.events)
        assertEquals(listOf(false, true), probed)
    }

    // ─── An answer is acted on only for what it is about.

    @Test
    fun aFailedProbeOfTheOldGenerationDoesNotCostTheNewOneItsTowers() = runTest(UnconfinedTestDispatcher()) {
        val old = install()
        val preparer = preparer()
        preparer.checked()
        // A foreground probe of this generation begins: it notes what it is about.
        val about = preflightKey(store)
        val generation = preparer.generation()
        // While it runs, an update is committed and prepared.
        val newText = ByteArray(8192) { 7 }
        val newer = install(entry(version = 2, text = newText), text = newText)
        assertIs<PrepareOutcome.Prepared>(preparer.checked())
        files.events.clear()
        probed.clear()

        // The old probe's answer arrives: its towers would not load.
        assertEquals(towersFailed, preparer.probed(about, generation, towersFailed))

        // ⚠⚠ It is about towers that are gone. The new generation's were discarded and
        // compiled again for it, and their one recompile was used up.
        assertEquals(emptyList(), files.events, "the new generation was recompiled for the old one's failure")
        assertEquals(emptyList(), probed)
        assertEquals(PreflightOutcome.NotAwaiting, (preparer.outcomeFor(key(newer)) as PrepareOutcome.Prepared).preflight)

        // And the new install still has its own allowance, for its own probe's answer.
        assertEquals(PreflightOutcome.NotAwaiting, preparer.probed(preflightKey(store), preparer.generation(), towersFailed))
        assertEquals(tower(vision) + tower(text), files.events)
    }

    @Test
    fun anAnswerDeliveredAfterTheSameTowersWereCompiledAgainIsAboutWhatIsGone() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        val preparer = preparer()
        preparer.checked()
        // Two foreground checks each begin a probe of the same towers (checks are not serialised).
        val first = preparer.generation()
        val second = preparer.generation()
        files.events.clear()
        probed.clear()

        // The first answer says a tower would not load: the towers are compiled once more.
        assertEquals(PreflightOutcome.NotAwaiting, preparer.probed(preflightKey(store), first, towersFailed))
        assertEquals(tower(vision) + tower(text), files.events)
        val said = preparer.outcomeFor(key(installed))
        files.events.clear()

        // ⚠⚠ The second answer arrives now, about the same digests and other towers: nothing
        // is compiled, and it does not replace what the probe AFTER the recompile said.
        val late = PreflightOutcome.Retry(PreflightFinding.Failed("the text tower did not load"), PreflightAttempts.NONE)
        assertEquals(late, preparer.probed(preflightKey(store), second, late))
        assertEquals(emptyList(), files.events)
        assertEquals(said, preparer.outcomeFor(key(installed)), "an answer about towers that are gone replaced the one about the towers that are here")
    }

    @Test
    fun aFailedProbeOfTheOldVocabularysInstallDoesNotCostTheNewOneItsTowers() = runTest(UnconfinedTestDispatcher()) {
        install()
        val preparer = preparer()
        preparer.checked()
        // A foreground probe of this install begins: it notes the key it runs under, whole.
        val about: PreflightKey = preflightKey(store)
        val generation = preparer.generation()
        // While it runs, an update that changes ONLY the vocabulary is committed and checked:
        // both towers' digests stay, nothing is compiled, and the new install's own probe passes.
        vocabularyRevision = 2
        preparer.checked()
        files.events.clear()
        probed.clear()

        // The old probe's answer arrives: its towers would not load.
        assertEquals(towersFailed, preparer.probed(about, generation, towersFailed))

        // ⚠⚠ Same towers, same generation, ANOTHER install. Reduced to the towers' digests the
        // answer looked like this install's: its towers were discarded and its recompile spent.
        assertEquals(emptyList(), files.events, "the new install was recompiled for a probe of the old vocabulary's")
        assertEquals(emptyList(), probed)

        // And its own probe's answer still leads to its one recompile.
        assertEquals(PreflightOutcome.NotAwaiting, preparer.probed(preflightKey(store), preparer.generation(), towersFailed))
        assertEquals(tower(vision) + tower(text), files.events)
    }

    @Test
    fun anUpdateCommittedBetweenTheAnswersCheckAndItsPassIsNotRecompiledForIt() = runTest(UnconfinedTestDispatcher()) {
        install()
        val preparer = preparer()
        preparer.checked()
        files.events.clear()
        probed.clear()
        val about: PreflightKey = preflightKey(store)
        // A commit holds the step lock while the answer is handed over: the answer is the
        // install's when it is first looked at, and waits for the lock.
        steps.lock()
        val answering = backgroundScope.async { preparer.probed(about, preparer.generation(), towersFailed) }
        assertTrue(answering.isActive)
        // The commit changes only the vocabulary, and lets go.
        vocabularyRevision = 2
        steps.unlock()

        assertEquals(towersFailed, answering.await())

        // ⚠⚠ Looked at again under the lock, whole: the new install's towers are not discarded,
        // and a recompile that was not made is neither remembered nor followed by a probe.
        assertEquals(emptyList(), files.events, "a commit that landed before the pass was recompiled for the old install's probe")
        assertEquals(emptyList(), probed)
        assertEquals(PreflightOutcome.NotAwaiting, preparer.probed(preflightKey(store), preparer.generation(), towersFailed))
        assertEquals(tower(vision) + tower(text), files.events)
    }

    @Test
    fun theOneRecompileIsTheTowersAndAnUpdateThatKeepsThemHasHadIt() = runTest(UnconfinedTestDispatcher()) {
        install()
        probes += towersFailed
        val preparer = preparer()
        preparer.checked()
        assertEquals(listOf(false, true), probed)
        files.events.clear()

        // The vocabulary changes, the towers do not: another install over the same compiled towers.
        vocabularyRevision = 2
        probes += towersFailed
        preparer.checked()

        // ⚠ A recompile is about the towers, and these have been compiled twice already in this
        // process: compiling them a third time would cure nothing a second did not.
        assertEquals(emptyList(), files.events)
    }

    @Test
    fun whatIsSaidOfAnInstallIsSaidAgainByTheCheckThatFollowsItsUpdate() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        disk.deleteFile(installed.pathOf(vocabulary)!!)
        val preparer = preparer()
        assertIs<PrepareOutcome.Incomplete>(preparer.checked())

        // An update that brings the vocabulary back and keeps the towers: the same towers'
        // key, under which the old outcome is still what is said until the check re-records.
        disk.writeRawByteArray(installed.pathOf(vocabulary)!!, vocabularyBytes)
        vocabularyRevision = 2
        assertIs<PrepareOutcome.Incomplete>(preparer.outcomeFor(key(installed)))
        preparer.checked()

        assertIs<PrepareOutcome.Prepared>(preparer.outcomeFor(key(installed)))
    }

    @Test
    fun anAnswerAboutNoInstallIsActedOnForNone() = runTest(UnconfinedTestDispatcher()) {
        install()
        val preparer = preparer()
        preparer.checked()
        files.events.clear()

        // A probe whose answer readiness dropped is about no model (`PreflightReport.key` is null).
        assertEquals(towersFailed, preparer.probed(about = null, atGeneration = preparer.generation(), outcome = towersFailed))
        assertEquals(emptyList(), files.events)
    }

    // ─── The probe after a recompile is owed until one has run.

    private val passed = PreflightOutcome.Passed(org.tenshu.inference.proxy.PreflightMeasurement(thermalAtStart = 0, memoryWarned = false))

    @Test
    fun theProbeAfterARecompileStaysOwedWhenTheSceneLeftBeforeItRan() = runTest(UnconfinedTestDispatcher()) {
        install()
        val preparer = preparer()
        // The first probe says the towers would not load; they are compiled again; and the
        // probe that was to follow is refused: the scene left while they compiled.
        probes += towersFailed
        probes += PreflightOutcome.Waiting(PreflightOutcome.Waiting.Cause.NOT_IN_FOREGROUND, "Photos is not in the foreground")
        preparer.checked()
        assertEquals(tower(vision) + tower(text) + tower(vision) + tower(text), files.events)
        owedPastTheBackoff.clear()
        files.events.clear()

        // Back at once. The failed probe's backoff has not run out.
        probes += passed
        preparer.checked()

        // ⚠⚠ The rebuilt towers' probe is still owed, and is asked for past the backoff. The
        // debt went with the allowance, and they waited the backoff out unverified.
        assertEquals(listOf(true), owedPastTheBackoff, "the probe after a recompile was no longer owed once the scene had left")
        // Paid by a probe that RAN: the next one is an ordinary one.
        owedPastTheBackoff.clear()
        preparer.checked()
        assertEquals(listOf(false), owedPastTheBackoff)
        // ⚠ And the allowance is still one: another failure compiles nothing.
        probes += towersFailed
        preparer.checked()
        assertEquals(emptyList(), files.events)
    }

    @Test
    fun theOwedProbeIsNotPaidToAnotherInstallNorByAProbeThatDidNotRun() = runTest(UnconfinedTestDispatcher()) {
        install()
        val preparer = preparer()
        probes += towersFailed
        probes += PreflightOutcome.Waiting(PreflightOutcome.Waiting.Cause.NOT_IN_FOREGROUND, "Photos is not in the foreground")
        preparer.checked()
        owedPastTheBackoff.clear()

        // A probe that did not run (another was running) pays nothing: still owed.
        probes += PreflightOutcome.Busy
        preparer.checked()
        probes += PreflightOutcome.Busy
        preparer.checked()
        assertEquals(listOf(true, true), owedPastTheBackoff)

        // ⚠ A vocabulary-only update is another install: it inherits no debt, and its own
        // probes are ordinary ones.
        owedPastTheBackoff.clear()
        vocabularyRevision = 2
        preparer.checked()
        assertEquals(listOf(false), owedPastTheBackoff, "a probe owed to one install was given to another")
    }

    @Test
    fun aProbeThatFailedBeforeAnyTowerWasOpenedCompilesNothing() = runTest(UnconfinedTestDispatcher()) {
        install()
        val vocabularyFailed = PreflightOutcome.Retry(PreflightFinding.Failed("the vocabulary did not load", beforeAnyTower = true), PreflightAttempts.NONE)
        probes += vocabularyFailed
        val preparer = preparer()

        // ⚠⚠ 1.8 GB of compiling cures nothing of a vocabulary that does not parse.
        assertEquals(PrepareOutcome.Prepared(AiReadinessState.AwaitingVerdict, vocabularyFailed), preparer.checked())
        assertEquals(tower(vision) + tower(text), files.events)
        assertEquals(listOf(false), probed)
    }

    @Test
    fun aRecompileThatCouldNotDeleteTheTowersIsNotReportedAsMade() = runTest(UnconfinedTestDispatcher()) {
        val installed = install()
        probes += towersFailed
        val preparer = preparer()
        whileProbing = { files.stuck += files.directory(sha256(visionBytes)) }

        // ⚠⚠ The discard failed, the tower still read as ready, and the call said "prepared"
        // and remembered the recompile as done.
        val outcome = assertIs<PrepareOutcome.Failed>(preparer.checked())
        assertEquals(outcome, preparer.outcomeFor(key(installed)))
        assertEquals(tower(vision) + tower(text), files.events)

        // And it is still owed.
        files.stuck.clear()
        whileProbing = {}
        files.events.clear()
        preparer.checked()
        preparer.probed(preflightKey(store), preparer.generation(), towersFailed)
        assertEquals(tower(vision) + tower(text), files.events.filterNot { it.startsWith("sweep") })
    }

    @Test
    fun aRecompileIsNotMadeOfAModelThatWasReplacedUnderTheProbe() = runTest(UnconfinedTestDispatcher()) {
        install()
        probes += towersFailed
        val newText = ByteArray(8192) { 7 }
        whileProbing = { if (probed.size == 1) install(entry(version = 2, text = newText), text = newText) }
        val preparer = preparer()

        preparer.checked()

        // ⚠⚠ The answer is about an install that is gone (its key is not the one that would
        // run now): nothing is discarded, compiled or recorded for it.
        assertEquals(tower(vision) + tower(text), files.events, "a probe of a replaced install led to a recompile")
        // The check the commit asks for is about the install that is there NOW: the shared
        // vision tower is left, the old text tower swept, the new one compiled.
        files.events.clear()
        preparer.checked()
        assertEquals(listOf("sweep $textTag") + tower(text), files.events)
    }

    @Test
    fun aTowerIsNeverWrittenWhereAModelIsStillBeingServedFrom() = runTest(UnconfinedTestDispatcher()) {
        val preparer = aReadyModel()
        // The text tower's marker is gone from under a model readiness last read as `Ready`.
        disk.deleteFile(files.marker(sha256(textBytes), Unit))
        var servedWhenTheCompileBegan: Boolean? = null
        files.at = { role, primitive -> if (role == text && primitive == "space") servedWhenTheCompileBegan = readiness.serves() }

        assertIs<PrepareOutcome.Prepared>(preparer.checked())

        // ⚠⚠ Readiness read the cache before the first byte was written, left `Ready` and closed
        // the towers: nothing is served from a directory that is being compiled into.
        assertEquals(false, servedWhenTheCompileBegan, "a tower was compiled where a model was still served from")
        assertEquals(1, sessionsClosed)
        assertEquals(tower(text), files.events)
    }

    // ─── Under the process's step lock; no probe beside a prepare.

    @Test
    fun noStoreChangeAndNoOtherGraphRunsUnderACompile() = runTest(UnconfinedTestDispatcher()) {
        install()
        var other: kotlinx.coroutines.Deferred<PrepareOutcome>? = null
        var lockedUnderTheCompile = false
        files.at = { role, primitive ->
            if (role == vision && primitive == "compile" && other == null) {
                lockedUnderTheCompile = steps.isLocked
                // Another graph's prepare arrives under the compile: it runs as far as it can.
                other = backgroundScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { preparer(readinessOver(store)).checked() }
            }
        }

        assertIs<PrepareOutcome.Prepared>(preparer().checked())

        assertTrue(lockedUnderTheCompile)
        assertIs<PrepareOutcome.Prepared>(other!!.await())
        // ⚠ It waited: every step ran once, and the second graph found both towers ready.
        assertEquals(tower(vision) + tower(text), files.events)
    }

    @Test
    fun noProbeBeginsBesideAPrepareAndAProbeThatHadBegunIsWaitedFor() = runTest(UnconfinedTestDispatcher()) {
        install()
        var duringTheCompile: org.tenshu.inference.proxy.Awaiting? = null
        files.at = { role, primitive ->
            if (role == text && primitive == "compile") {
                // The vision tower is marked; were both, a probe asking now would be let through.
                backgroundScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { duringTheCompile = readiness.awaitingProbe() }
            }
        }
        val closed = CompletableDeferred<Unit>()
        beforeLoad = { closed.await() }
        val preparing = backgroundScope.async { preparer().checked() }

        // A probe that had begun holds its towers: nothing is compiled until it has closed them.
        assertEquals(emptyList(), files.events)
        closed.complete(Unit)
        assertIs<PrepareOutcome.Prepared>(preparing.await())
        assertEquals(org.tenshu.inference.proxy.Awaiting.NothingToProbe, duringTheCompile)
        // And afterwards a probe is let through again.
        assertIs<org.tenshu.inference.proxy.Awaiting.Probe>(readiness.awaitingProbe())

        // ⚠⚠ Nor beside a call that only DELETES: both towers are ready and a verdict is
        // awaited, so a probe asking now would be let through, onto a cache being swept.
        disk.writeRawByteArray("inference/coreml/ffffffffffffffff/text.mlmodelc/model.bin", ByteArray(3))
        var duringTheSweep: org.tenshu.inference.proxy.Awaiting? = null
        files.at = { _, primitive ->
            if (primitive == "sweep") {
                backgroundScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { duringTheSweep = readiness.awaitingProbe() }
            }
        }
        preparer().checked()
        assertEquals(org.tenshu.inference.proxy.Awaiting.NothingToProbe, duringTheSweep, "a probe began beside a sweep")
    }

    @Test
    fun nothingIsDeletedUnderAProbeThatIsStillRunning() = runTest(UnconfinedTestDispatcher()) {
        install()
        val preparer = preparer()
        preparer.checked()
        files.events.clear()
        // A probe of this generation holds its towers open, and an update lands under it.
        val probeEnded = CompletableDeferred<Unit>()
        beforeLoad = { probeEnded.await() }
        val newText = ByteArray(8192) { 7 }
        install(entry(version = 2, text = newText), text = newText)

        val preparing = backgroundScope.async { preparer.checked() }

        // ⚠⚠ The sweep waits with everything else. It ran ahead of the wait, and deleted the
        // old text tower while Core ML was still reading it for the probe.
        assertEquals(emptyList(), files.events, "a tower was deleted under a probe that was still running")
        assertTrue(textTag in files.directories())
        probeEnded.complete(Unit)
        assertIs<PrepareOutcome.Prepared>(preparing.await())
        assertEquals(listOf("sweep $textTag") + tower(text), files.events)

        // And a removal's eviction, in the removal's own step, waits the same way.
        files.events.clear()
        val secondProbe = CompletableDeferred<Unit>()
        beforeLoad = { secondProbe.await() }
        store.remove(MODEL)
        steps.lock()
        val evicting = backgroundScope.async { preparer.evicted(MODEL) }
        assertEquals(emptyList(), files.events, "a removal evicted towers under a probe that was still running")
        secondProbe.complete(Unit)
        evicting.await()
        steps.unlock()
        assertEquals(emptySet(), files.directories())
    }

    // ─── This graph is about ONE model; the store and the cache are not.

    private val facesVision = ByteArray(4096) { 11 }
    private val facesText = ByteArray(8192) { 12 }
    private val faces = "faces"

    private fun installFaces(): InstalledModelData = store.add(
        entry(vision = facesVision, text = facesText).copy(modelId = faces),
        mapOf(vision to facesVision, text to facesText, vocabulary to vocabularyBytes),
    )!!

    @Test
    fun theCommitAndTheRemovalOfAnotherModelLeaveThisOneServedAndItsTowersWhereTheyAre() = runTest(UnconfinedTestDispatcher()) {
        val preparer = aReadyModel()
        val changes = PinnedModelChanges(readiness, MODEL)
        val states = mutableListOf<AiReadinessState>()
        val watching = backgroundScope.launch { readiness.state.collect { states += it } }

        // Another model is committed, as the downloader commits it: saying which model it is.
        changes.changing(faces) { installFaces() }
        // And removed, through the removal's two hooks and its wrapper, in the removal's step.
        steps.lock()
        preparer.evicting(faces)
        changes.changing(faces) { store.remove(faces) }
        preparer.evicted(faces)
        steps.unlock()

        // ⚠⚠ The hooks named no model once: removing ANY model discarded this one's towers and
        // stopped serving it, and every store change went through this model's writer.
        assertEquals<List<AiReadinessState>>(listOf(AiReadinessState.Ready), states, "another model's change moved this model's state")
        assertEquals(0, sessionsClosed, "another model's change closed this model's towers")
        assertEquals(0, passesCancelled)
        assertNotNull(readiness.serving())
        assertEquals(setOf(visionTag, textTag), files.directories(), "another model's removal took this model's compiled towers")

        // ⚠ This model's own change does stand it down, as before.
        changes.changing(MODEL) { }
        assertEquals(1, sessionsClosed)
        watching.cancel()
    }

    @Test
    fun theSweepTakesNothingAnyInstalledModelNames() = runTest(UnconfinedTestDispatcher()) {
        install()
        installFaces()
        // A second model's compiled tower in the same cache (nothing compiles one today), and
        // a directory no record names.
        val facesTag = sha256(facesVision).take(16)
        disk.writeRawByteArray("inference/coreml/$facesTag/vision.mlmodelc/model.bin", ByteArray(3))
        disk.writeRawByteArray("inference/coreml/ffffffffffffffff/text.mlmodelc/model.bin", ByteArray(3))
        val preparer = preparer()

        preparer.checked()

        // ⚠⚠ "What this model's record does not name" would have taken the other model's tower
        // as an orphan. The sweep keeps what ANY install names.
        assertEquals(listOf("sweep ffffffffffffffff"), files.events.filter { it.startsWith("sweep") })
        assertTrue(facesTag in files.directories(), "the sweep took a compiled tower another installed model names")

        // Once that model is removed, nothing names it: it goes with the removal.
        store.remove(faces)
        steps.lock()
        preparer.evicted(faces)
        steps.unlock()
        assertEquals(setOf(visionTag, textTag), files.directories())
    }

    // ─── The probe is owed by the state, not by what the prepare did.

    @Test
    fun anUpdateThatKeepsBothTowersIsProbedWithoutAnythingBeingCompiled() = runTest(UnconfinedTestDispatcher()) {
        val preparer = aReadyModel()
        // An update that changes only the vocabulary: both towers' digests stay, the key moves.
        vocabularyRevision = 2
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.fingerprintChecked())
        files.events.clear()
        probed.clear()

        // The check a commit asks for. Photos has stayed in the foreground: no scene change.
        val outcome = preparer.checked()

        // ⚠⚠ Nothing to compile, and a verdict is awaited: the probe is asked. "I probe when
        // I compiled" asked for none, and the model waited for the user to leave and come back.
        assertEquals(emptyList(), files.events)
        assertEquals(listOf(false), probed, "an install that awaits its verdict was not probed because nothing was compiled")
        assertEquals(PrepareOutcome.Prepared(AiReadinessState.AwaitingVerdict, PreflightOutcome.NotAwaiting), outcome)
    }

    @Test
    fun aPrepareItselfAsksForNoProbe() = runTest(UnconfinedTestDispatcher()) {
        install()

        // ⚠ One owner: the check. A prepare called alone compiles and stops there.
        assertEquals(PrepareOutcome.Prepared(AiReadinessState.AwaitingVerdict, preflight = null), preparer().prepare())
        assertEquals(emptyList(), probed)
    }
}

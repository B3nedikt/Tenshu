package org.tenshu.inference.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.plus
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.inference.proxy.onnx.SiglipTextTokenizer
import org.tenshu.inference.proxy.onnx.UnigramVocabulary
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.inference.proxy.onnx.packedVocabulary
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * [Preflight] on fake towers and a fake phone (`ios-on-device-inference.md` §7 D12 ②, §14): what
 * makes a model `Ready`, what never does, and what it takes to call a phone `Unsuitable`.
 *
 * ⚠ The readiness is the real one over an in-memory disk, so every case reads the state and the
 * counts the way the next launch would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreflightTest {

    private val width: Int = VisionModel.PinnedSigLip.dimensions
    private val disk = InMemoryFileDatabase()
    private val time = TestTimeSource()

    // ─── the model, the build and the store ─────────────────────────────────────────────────────

    private val pieces = listOf(
        "<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f,
        "▁" to -3f, "▁a" to -1f, "▁dog" to -1f, "▁sunset" to -1f,
    )
    private val tokenizer = SiglipTextTokenizer(UnigramVocabulary.of(pieces), VisionModel.PinnedSigLip.textSequenceLength)
    private val probe = PreflightProbe(
        id = "test",
        visionArchiveSha256 = "vision-a",
        textArchiveSha256 = "text-a",
        vocabularySha256 = "vocabulary-a",
        imageRgbSha256 = "",
        imageTensorSha256 = "",
        captionDescribes = "a dog",
        captionDoesNot = "a sunset",
        tokensDescribes = tokenizer.encode("a dog"),
        tokensDoesNot = tokenizer.encode("a sunset"),
        hostMargin = 0.25,
        minMargin = 0.1,
        hostVisionNorm = 10.0,
        hostDescribesNorm = 20.0,
        hostDoesNotNorm = 20.0,
        normBand = 2.0,
    )
    private val model = PreparedModel("/coreml/a/vision.mlmodelc", "/coreml/a/text.mlmodelc", "/models/a.vocabulary")

    private fun key(vision: String? = "vision-a", text: String? = "text-a", vocabulary: String? = "vocabulary-a", osBuild: String = "os-1") =
        PreflightKey(
            runtime = RuntimeFingerprint(vision, text, computeUnits = "cpuAndNeuralEngine", osBuild = osBuild, preprocessingRevision = 1),
            vocabularySha256 = vocabulary,
            appBuild = "1.0 (1)",
            preflightRevision = PREFLIGHT_REVISION,
        )

    private var installed = true
    private var prepared: PreparedModel? = model
    private var current: PreflightKey = key()
    private var vocabulary: ByteArray? = packedVocabulary(pieces)
    private var backendClosed = 0

    // ─── the phone ───────────────────────────────────────────────────────────────────────────────

    private var thermal = 0
    private var warnings = 0L
    private var available: Long? = null
    private var now = 1_000_000_000L

    private val device = object : PreflightDevice {
        override fun thermalState(): Int = thermal
        override fun memoryWarnings(): Long = warnings
        override fun footprintBytes(): Long? = 100L * open
        override fun availableBytes(): Long? = available
        override fun nowEpochMs(): Long {
            // The moment between a probe's last prediction and its result being recorded.
            if (opened.isNotEmpty() && open == 0) afterTheTowersClosed().also { afterTheTowersClosed = {} }
            return now
        }
    }

    // ─── the towers ──────────────────────────────────────────────────────────────────────────────

    /** `length` along axis 0 scaled by `along`, the rest on [axis]: a vector whose cosine with e0 is [along]. */
    private fun vector(length: Float, along: Float = 1f, axis: Int = 1): FloatArray = FloatArray(width).also {
        it[0] = length * along
        if (along != 1f) it[axis] = length * sqrt(1f - along * along)
    }

    private val image: FloatArray = vector(10f)
    private val describes: FloatArray = vector(20f, along = 0.2f, axis = 1)
    private val doesNot: FloatArray = vector(20f, along = -0.05f, axis = 2)

    private var visionAnswer: (prediction: Int) -> FloatArray = { image }
    private var textAnswer: (describing: Boolean) -> FloatArray = { if (it) describes else doesNot }
    private var visionCost: Duration = 13.milliseconds
    private var textCost: Duration = 3.milliseconds
    private var openingVision: () -> Unit = {}
    private var openingText: () -> Unit = {}
    private var duringPrediction: (Int) -> Unit = {}
    private var afterTheTowersClosed: () -> Unit = {}

    private var open = 0
    private var mostOpen = 0
    private val opened = mutableListOf<String>()
    private var visionPredictions = 0
    private var textPredictions = 0
    private val events = mutableListOf<String>()

    private inner class FakeTower(private val name: String) : PreflightTower {
        init {
            open++
            mostOpen = maxOf(mostOpen, open)
            opened += name
        }

        override fun predict(floats: FloatArray): FloatArray {
            duringPrediction(visionPredictions)
            time += visionCost
            return visionAnswer(visionPredictions++)
        }

        override fun predict(ids: IntArray): FloatArray {
            time += textCost
            textPredictions++
            return textAnswer(ids.contentEquals(probe.tokensDescribes))
        }

        override fun close() {
            open--
            events += "close $name"
        }
    }

    private val towers = object : PreflightTowers {
        override fun openVision(compiledPath: String): PreflightTower {
            openingVision()
            return FakeTower(compiledPath)
        }

        override fun openText(compiledPath: String): PreflightTower {
            openingText()
            return FakeTower(compiledPath)
        }
    }

    // ─── the two objects under test ─────────────────────────────────────────────────────────────

    private var beforeLoad: suspend () -> Unit = {}
    private var failingWrites = false
    private var applying: () -> FingerprintOutcome = { FingerprintOutcome.Unchanged }
    private var duringWrite: () -> Unit = {}

    /** How a read of the verdict file goes: as it is, throwing, or `null` for a file that is there (iOS). */
    private var verdictRead = "ok"
    private var factsRead: () -> Unit = {}
    private var keyRead: () -> Unit = {}

    /** The process the next readiness is made in. ⚠ A new ledger is a new process (§14.5). */
    private var process = AttemptLedger()

    /** The process was killed: nothing it writes from here reaches the disk. */
    private var dead = false

    /** The scene's record throws when read. */
    private var sceneUnreadable = false

    private fun readiness(): AiReadiness {
        val recording = object : FileDatabase by disk {
            override fun readBytes(filePath: String) = when {
                filePath == AiReadiness.SCENE_PATH && sceneUnreadable -> error("I/O error")
                filePath != AiReadiness.VERDICT_PATH || verdictRead == "ok" -> disk.readBytes(filePath)
                verdictRead == "throws" -> error("I/O error")
                else -> null
            }

            override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
                if (dead) return
                if (failingWrites) error("disk full")
                events += "persist open=$open"
                disk.writeRawByteArray(filePath, bytes)
                duringWrite()
            }
        }
        return AiReadiness(
            files = { recording },
            facts = {
                factsRead()
                AiModelFacts(installed, prepared)
            },
            currentKey = {
                keyRead()
                current
            },
            storesUsable = { true },
            applyFingerprint = { applying() },
            cancelPass = {},
            closeSessions = { backendClosed++ },
            ledger = process,
        )
    }

    private fun TestScope.preflight(readiness: AiReadiness): Preflight = Preflight(
        readiness = readiness,
        towers = towers,
        readVocabulary = { vocabulary },
        device = device,
        beforeLoad = { beforeLoad() },
        probes = { key -> probe.takeIf { it.isFor(key) } },
        model = VisionModel.PinnedSigLip,
        time = time,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private suspend fun attempts(readiness: AiReadiness): PreflightAttempts? =
        (readiness.awaitingProbe() as? Awaiting.Probe)?.target?.attempts

    /** A later foreground, late enough for any backoff. */
    private fun later() {
        now += PreflightPolicy.RETRY_CAP_MS
    }

    private fun assertNothingWasOpened(why: String) {
        assertEquals(emptyList(), opened, why)
    }

    // ─── a pass ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aPassMakesTheModelReadyAndBothTowersAreClosedBeforeItIsServed() = runTest {
        val readiness = readiness()
        val preflight = preflight(readiness)

        val outcome = preflight.foregrounded(++scene)

        assertIs<PreflightOutcome.Passed>(outcome)
        assertEquals(AiReadinessState.Ready, readiness.current())
        assertEquals(model, readiness.serving())
        assertEquals(listOf(model.visionPath, model.textPath), opened, "one tower each, from the prepared paths")
        // ⚠⚠ The backend may open its pair the moment the verdict is on disk. ⚠⚠ And the probe's
        // record is on disk before the first tower opens (§14.5).
        assertEquals(listOf("persist open=0", "close ${model.visionPath}", "close ${model.textPath}", "persist open=0"), events)
        assertEquals(2, mostOpen)
        assertEquals(outcome, preflight.outcomeFor(current))
    }

    @Test
    fun aProbeIsTheWholeSearchPath() = runTest {
        val outcome = preflight(readiness()).foregrounded(++scene)

        val measured = assertIs<PreflightOutcome.Passed>(outcome).measurement
        assertEquals(PreflightBudget.PREDICTIONS_PER_TOWER, visionPredictions)
        assertEquals(PreflightBudget.PREDICTIONS_PER_TOWER, textPredictions)
        assertEquals(List(PreflightBudget.PREDICTIONS_PER_TOWER) { 13.0 }, measured.visionPredictMs)
        assertEquals(List(PreflightBudget.PREDICTIONS_PER_TOWER) { 3.0 }, measured.textPredictMs)
        assertEquals(measured.describes.size, measured.doesNot.size, "both captions were embedded as often")
        assertEquals(200L, measured.footprintBytes, "the footprint was not read with both towers open")
        assertEquals(0L, measured.footprintBeforeBytes)
    }

    @Test
    fun aPassOutlivesTheProcessAndIsNotProbedAgain() = runTest {
        preflight(readiness()).foregrounded(++scene)
        opened.clear()

        val relaunched = readiness()
        assertEquals(AiReadinessState.Ready, relaunched.fingerprintChecked())
        assertEquals(PreflightOutcome.NotAwaiting, preflight(relaunched).foregrounded(++scene))
        assertNothingWasOpened("a model with a pass was probed again")
    }

    // ─── when nothing is probed ─────────────────────────────────────────────────────────────────

    @Test
    fun nothingIsProbedUnlessAPreparedModelAwaitsItsVerdict() = runTest {
        installed = false
        prepared = null
        assertEquals(PreflightOutcome.NotAwaiting, preflight(readiness()).foregrounded(++scene), "no model")
        installed = true
        assertEquals(PreflightOutcome.NotAwaiting, preflight(readiness()).foregrounded(++scene), "not prepared")
        prepared = model
        val unsuitable = readiness().also { it.recordVerdict(passed = false, probed = current) }
        assertEquals(PreflightOutcome.NotAwaiting, preflight(unsuitable).foregrounded(++scene), "unsuitable")
        // Retired while it awaited a verdict: the state it holds still says so.
        current = key(osBuild = "os-2")
        val retired = readiness()
        assertEquals(AiReadinessState.AwaitingVerdict, retired.fingerprintChecked())
        retired.retire()
        assertEquals(PreflightOutcome.NotAwaiting, preflight(retired).foregrounded(++scene), "retired")
        assertIs<ProbeRecorded.Dropped>(retired.recordProbe(ProbeResult.SLOW, current, atEpochMs = now), "a retired readiness counted a probe")
        assertIs<ProbeRecorded.Dropped>(retired.recordProbe(ProbeResult.PASSED, current, atEpochMs = now), "a retired readiness took a pass")
        assertFalse(retired.serves())
        assertEquals(PreflightAttempts.NONE, attempts(readiness()), "a retired readiness wrote a count")

        assertNothingWasOpened("a tower was opened with nothing to probe")
    }

    @Test
    fun anExportThisBuildHasNoProbeForIsNeverCheckedAgainstAnotherExports() = runTest {
        listOf(key(vision = "vision-b"), key(text = "text-b"), key(vocabulary = "vocabulary-b"), key(vocabulary = null)).forEach { other ->
            current = other
            val readiness = readiness()

            assertEquals(PreflightOutcome.NoProbe, preflight(readiness).foregrounded(++scene), "$other")
            assertEquals(AiReadinessState.AwaitingVerdict, readiness.current())
            assertEquals(PreflightAttempts.NONE, attempts(readiness), "a missing probe was counted against the phone")
        }
        assertNothingWasOpened("a tower was opened for an export with no probe")
    }

    @Test
    fun everyProbeThisBuildCarriesIsOnlyForItsOwnThreeDigests() {
        assertTrue(PreflightProbes.ids.isNotEmpty(), "the build carries no probe at all")
        PreflightProbes.ids.forEach { id ->
            val record: PreflightProbe = PreflightProbes.byId(id)!!
            val own = key(record.visionArchiveSha256, record.textArchiveSha256, record.vocabularySha256)

            assertEquals(record, PreflightProbes.forKey(own))
            assertNull(PreflightProbes.forKey(key(record.visionArchiveSha256, record.textArchiveSha256, "other")))
            assertNull(PreflightProbes.forKey(key(record.visionArchiveSha256, "other", record.vocabularySha256)))
            assertNull(PreflightProbes.forKey(key("other", record.textArchiveSha256, record.vocabularySha256)))
        }
        assertNull(PreflightProbes.forKey(key(null, null, null)))
    }

    /** A second export's record, for a test: the same probe under three other digests. */
    private fun recordFor(id: String, vision: String, text: String, vocabulary: String, minMargin: Double = probe.minMargin) = PreflightProbe(
        id = id,
        visionArchiveSha256 = vision,
        textArchiveSha256 = text,
        vocabularySha256 = vocabulary,
        imageRgbSha256 = "",
        imageTensorSha256 = "",
        captionDescribes = probe.captionDescribes,
        captionDoesNot = probe.captionDoesNot,
        tokensDescribes = probe.tokensDescribes,
        tokensDoesNot = probe.tokensDoesNot,
        hostMargin = probe.hostMargin,
        minMargin = minMargin,
        hostVisionNorm = probe.hostVisionNorm,
        hostDescribesNorm = probe.hostDescribesNorm,
        hostDoesNotNorm = probe.hostDoesNotNorm,
        normBand = probe.normBand,
    )

    /**
     * ⚠⚠ A build carries one record per export a phone may hold. Were a second export's record to
     * replace the first, a phone still holding the first export would have no probe to renew a
     * voided verdict by.
     */
    @Test
    fun withTwoExportsRecordedEachFindsItsOwnProbeAndNeitherTheOthers() {
        val first = recordFor("first", "vision-a", "text-a", "vocabulary-a")
        val second = recordFor("second", "vision-b", "text-b", "vocabulary-a")
        val set = PreflightProbeSet(listOf(first, second))

        assertEquals(first, set.forKey(key()))
        assertEquals(second, set.forKey(key(vision = "vision-b", text = "text-b")))
        // Half of one export and half of the other is neither.
        assertNull(set.forKey(key(vision = "vision-a", text = "text-b")))
        assertNull(set.forKey(key(vision = "vision-b", text = "text-a")))
        assertNull(set.forKey(key(vision = "vision-c", text = "text-c")), "an export with no record found one")
        assertEquals(listOf("first", "second"), set.ids)
        assertEquals(second, set.byId("second"))
    }

    @Test
    fun aPhoneStillHoldingTheEarlierExportIsProbedAgainstItsOwnRecordAfterASecondIsAdded() = runTest {
        // The second export asks for a margin the fake towers do not reach; the first's does not.
        val earlier = recordFor("earlier", "vision-a", "text-a", "vocabulary-a")
        val later = recordFor("later", "vision-b", "text-b", "vocabulary-a", minMargin = 0.9)
        val set = PreflightProbeSet(listOf(later, earlier))
        fun TestScope.over(readiness: AiReadiness) = Preflight(
            readiness = readiness, towers = towers, readVocabulary = { vocabulary }, device = device, beforeLoad = {},
            probes = { key -> set.forKey(key) }, time = time, dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        // The phone holds the earlier export.
        assertIs<PreflightOutcome.Passed>(over(readiness()).foregrounded(++scene), "the earlier export was judged by the later export's record, or had none")

        // And one that holds the later export is held to the later record.
        disk.deleteFile(AiReadiness.VERDICT_PATH)
        current = key(vision = "vision-b", text = "text-b")
        assertIs<PreflightFinding.Failed>(assertIs<PreflightOutcome.Retry>(over(readiness()).foregrounded(++scene)).finding)
    }

    @Test
    fun twoRecordsThatCouldBeTakenForEachOtherAreRefused() {
        val one = recordFor("one", "vision-a", "text-a", "vocabulary-a")
        assertFailsWith<IllegalArgumentException> { PreflightProbeSet(listOf(one, recordFor("one", "vision-b", "text-b", "vocabulary-a"))) }
        assertFailsWith<IllegalArgumentException> { PreflightProbeSet(listOf(one, recordFor("two", "vision-a", "text-a", "vocabulary-a"))) }
    }

    // ─── output that is not the model's ─────────────────────────────────────────────────────────

    private suspend fun TestScope.assertFailedProbe(what: String, expectTowers: Boolean = true) {
        val readiness = readiness()
        val outcome = preflight(readiness).foregrounded(++scene)

        val retry = assertIs<PreflightOutcome.Retry>(outcome, what)
        assertIs<PreflightFinding.Failed>(retry.finding, what)
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current(), what)
        assertFalse(readiness.serves(), what)
        assertEquals(PreflightAttempts(slow = 0, failed = 1, transient = 0, lastAtEpochMs = now), attempts(readiness), what)
        assertEquals(0, open, "$what: a tower was left open")
        if (!expectTowers) assertNothingWasOpened("$what: a tower was opened for it")
    }

    @Test
    fun aVisionTowerThatAnswersNaNIsNeverReady() = runTest {
        visionAnswer = { image.copyOf().also { it[5] = Float.NaN } }
        assertFailedProbe("NaN")
    }

    @Test
    fun aTextTowerThatAnswersZerosIsNeverReady() = runTest {
        textAnswer = { FloatArray(width) }
        assertFailedProbe("zeros")
    }

    @Test
    fun aVectorOfAnotherWidthIsNeverReady() = runTest {
        visionAnswer = { FloatArray(width - 1) { 1f } }
        assertFailedProbe("width")
    }

    @Test
    fun aCollapsedOrAnExplodedNormIsNeverReady() = runTest {
        visionAnswer = { vector(10f / 2.1f) }
        assertFailedProbe("collapsed")
        disk.deleteFile(AiReadiness.VERDICT_PATH)
        textAnswer = { describing -> if (describing) vector(20f * 2.1f, along = 0.2f) else doesNot }
        assertFailedProbe("exploded")
    }

    @Test
    fun aMisRankedProbePairIsNeverReady() = runTest {
        textAnswer = { if (it) doesNot else describes }
        assertFailedProbe("swapped")
    }

    @Test
    fun aPairRankedRightByLessThanTheMarginIsNeverReady() = runTest {
        // 0.2 against 0.11: the right order, 0.09 apart, and 0.1 is required.
        textAnswer = { describing -> if (describing) describes else vector(20f, along = 0.11f, axis = 2) }
        assertFailedProbe("narrow")
    }

    @Test
    fun oneBadVectorLateInTheBatchIsEnough() = runTest {
        visionAnswer = { prediction -> if (prediction == PreflightBudget.PREDICTIONS_PER_TOWER - 1) FloatArray(width) else image }
        assertFailedProbe("the last prediction")
    }

    @Test
    fun aWrongOutputIsNotExcusedByBeingFastNorMadeSlowByBeingSlow() = runTest {
        visionAnswer = { FloatArray(width) { Float.NaN } }
        visionCost = 900.milliseconds
        assertFailedProbe("slow and wrong")
    }

    // ─── what will not load ─────────────────────────────────────────────────────────────────────

    @Test
    fun aVocabularyThatIsNotThereOrDoesNotParseFailsBeforeATowerIsOpened() = runTest {
        vocabulary = null
        assertFailedProbe("no vocabulary", expectTowers = false)
        disk.deleteFile(AiReadiness.VERDICT_PATH)
        vocabulary = ByteArray(64) { 7 }
        assertFailedProbe("noise", expectTowers = false)
    }

    @Test
    fun aVocabularyThatTokenisesTheCaptionsDifferentlyFailsBeforeATowerIsOpened() = runTest {
        // The same pieces in another order: every caption still tokenises, to other ids.
        vocabulary = packedVocabulary(pieces.take(3) + pieces.drop(3).reversed())
        assertFailedProbe("other rows", expectTowers = false)
    }

    @Test
    fun aTowerThatWillNotLoadIsAFailedProbeAndTheOtherIsClosed() = runTest {
        openingText = { error("Core ML said no") }
        assertFailedProbe("the text tower")
        assertEquals(listOf(model.visionPath), opened)
        assertEquals(listOf("close ${model.visionPath}"), events.filter { it.startsWith("close") })

        disk.deleteFile(AiReadiness.VERDICT_PATH)
        opened.clear()
        openingText = {}
        openingVision = { error("Core ML said no") }
        assertFailedProbe("the vision tower", expectTowers = false)
    }

    @Test
    fun aPredictionThatThrowsIsAFailedProbeAndBothTowersAreClosed() = runTest {
        duringPrediction = { if (it == 2) error("prediction failed") }
        assertFailedProbe("a throw")
    }

    @Test
    fun threeFailedProbesAreAFailedInstallAndNeverAnUnsuitablePhone() = runTest {
        visionAnswer = { FloatArray(width) }
        val readiness = readiness()
        val preflight = preflight(readiness)

        assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene))
        later()
        assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene))
        later()
        assertEquals(PreflightOutcome.InstallFailed, preflight.foregrounded(++scene))
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current(), "a model that answers wrongly made the phone unsuitable")

        // From here it is not loaded again, on this process or the next.
        opened.clear()
        later()
        assertEquals(PreflightOutcome.InstallFailed, preflight.foregrounded(++scene))
        val relaunched = readiness()
        assertEquals(PreflightOutcome.InstallFailed, preflight(relaunched).foregrounded(++scene))
        assertNothingWasOpened("a failed install was loaded again")
        assertEquals(AiReadinessState.AwaitingVerdict, relaunched.current())
    }

    @Test
    fun installingAgainStartsTheFailedCountAgainAndKeepsTheSlowOne() = runTest {
        val readiness = readiness()
        val preflight = preflight(readiness)
        visionCost = 900.milliseconds
        preflight.foregrounded(++scene)
        visionCost = 13.milliseconds
        visionAnswer = { FloatArray(width) }
        repeat(3) {
            later()
            preflight.foregrounded(++scene)
        }
        assertEquals(PreflightOutcome.InstallFailed, preflight.outcomeFor(current))

        // Writer 3: the download commits the same archive again.
        readiness.storeChanging { }

        assertEquals(PreflightAttempts(slow = 1, failed = 0, transient = 0, lastAtEpochMs = now), attempts(readiness))
        visionAnswer = { image }
        later()
        assertIs<PreflightOutcome.Passed>(preflight.foregrounded(++scene))
    }

    // ─── a phone that is too slow, or too full ──────────────────────────────────────────────────

    private suspend fun TestScope.assertSlowProbe(what: String) {
        val readiness = readiness()
        val outcome = preflight(readiness).foregrounded(++scene)

        assertIs<PreflightFinding.Slow>(assertIs<PreflightOutcome.Retry>(outcome, what).finding, what)
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current(), "$what: one slow probe was a verdict")
        assertEquals(PreflightAttempts(slow = 1, failed = 0, transient = 0, lastAtEpochMs = now), attempts(readiness), what)
        assertEquals(0, open)
    }

    @Test
    fun aSlowVisionTowerIsASlowProbe() = runTest {
        visionCost = 501.milliseconds
        assertSlowProbe("vision")
    }

    @Test
    fun aSlowTextTowerIsASlowProbe() = runTest {
        textCost = 501.milliseconds
        assertSlowProbe("text")
    }

    @Test
    fun tooLittleLeftWithBothTowersOpenIsASlowProbe() = runTest {
        available = PreflightBudget.PLACEHOLDER_MIN_AVAILABLE_BYTES - 1
        assertSlowProbe("memory")
    }

    @Test
    fun aMemoryReadingThatCouldNotBeTakenIsNotHeldAgainstThePhone() = runTest {
        available = null
        assertIs<PreflightOutcome.Passed>(preflight(readiness()).foregrounded(++scene))
    }

    @Test
    fun threeSlowProbesOnThreeForegroundsMakeThePhoneUnsuitable() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)

        assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene))
        later()
        assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene))
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current(), "two slow probes were a verdict")
        later()
        assertIs<PreflightOutcome.Unsuitable>(preflight.foregrounded(++scene))

        assertEquals(AiReadinessState.Unsuitable, readiness.current())
        assertEquals(AiReadinessState.Unsuitable, readiness().fingerprintChecked(), "the verdict did not outlive the process")
        assertEquals(0, backendClosed, "nothing was served, so nothing of the backend's was to close")
    }

    @Test
    fun theSlowCountOutlivesTheProcess() = runTest {
        visionCost = 900.milliseconds
        repeat(2) {
            assertIs<PreflightOutcome.Retry>(preflight(readiness()).foregrounded(++scene))
            later()
        }
        assertIs<PreflightOutcome.Unsuitable>(preflight(readiness()).foregrounded(++scene))
    }

    @Test
    fun aSecondSlowProbeInOneForegroundIsNotRun() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)
        preflight.foregrounded(++scene)
        opened.clear()

        // The prepare step's trigger (writer 1) in the same foreground, long after any backoff.
        later()
        assertIs<PreflightOutcome.Waiting>(preflight.prepared(scene))
        later()
        assertIs<PreflightOutcome.Waiting>(preflight.prepared(scene))

        assertNothingWasOpened("the same foreground was probed twice")
        assertEquals(1, attempts(readiness)?.slow)
        // The next foreground is another one.
        assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene))
        assertEquals(2, attempts(readiness)?.slow)
    }

    @Test
    fun aNewKeyStartsTheCountsAtZero() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)
        preflight.foregrounded(++scene)
        later()
        preflight.foregrounded(++scene)
        assertEquals(2, attempts(readiness)?.slow)

        // An OS update: the same three digests, so the same probe, under another key.
        current = key(osBuild = "os-2")
        assertEquals(PreflightAttempts.NONE, attempts(readiness), "the old key's probes were counted against the new one")
        later()
        assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene), "the third slow probe overall is the first under this key")
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current())
        assertEquals(1, attempts(readiness)?.slow)
    }

    // ─── what the phone was doing, not what it is ───────────────────────────────────────────────

    @Test
    fun aSlowProbeOnAHotPhoneDecidesNothingHoweverOftenItHappens() = runTest {
        visionCost = 900.milliseconds
        thermal = 2 // `ProcessInfo.ThermalState.serious`
        val readiness = readiness()
        val preflight = preflight(readiness)

        repeat(6) {
            assertIs<PreflightFinding.Transient>(assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene)).finding)
            later()
        }

        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current())
        assertEquals(PreflightAttempts(slow = 0, failed = 0, transient = 6, lastAtEpochMs = now - PreflightPolicy.RETRY_CAP_MS), attempts(readiness))
        // Cooled down, the same phone is judged on what it is.
        thermal = 1
        assertIs<PreflightFinding.Slow>(assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene)).finding)
    }

    @Test
    fun aFairPhoneIsJudged() = runTest {
        visionCost = 900.milliseconds
        thermal = 1 // `ProcessInfo.ThermalState.fair`
        assertSlowProbe("fair")
    }

    @Test
    fun aLoadErrorOnAHotPhoneIsNotCountedAgainstTheInstall() = runTest {
        thermal = 3
        openingVision = { error("Core ML said no") }
        val readiness = readiness()
        val preflight = preflight(readiness)

        repeat(4) {
            assertIs<PreflightFinding.Transient>(assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene)).finding)
            later()
        }
        assertEquals(0, attempts(readiness)?.failed)
    }

    @Test
    fun aSlowProbeUnderAMemoryWarningDecidesNothing() = runTest {
        visionCost = 900.milliseconds
        duringPrediction = { if (it == 3) warnings++ }
        val readiness = readiness()

        val outcome = preflight(readiness).foregrounded(++scene)

        assertIs<PreflightFinding.Transient>(assertIs<PreflightOutcome.Retry>(outcome).finding)
        assertEquals(PreflightAttempts(slow = 0, failed = 0, transient = 1, lastAtEpochMs = now), attempts(readiness))
    }

    @Test
    fun aWarningBeforeTheProbeStartedIsNotThisProbes() = runTest {
        warnings = 4
        visionCost = 900.milliseconds
        assertSlowProbe("an old warning")
    }

    @Test
    fun aHotOrWarnedPhoneThatPassesIsReady() = runTest {
        thermal = 3
        duringPrediction = { warnings++ }
        assertIs<PreflightOutcome.Passed>(preflight(readiness()).foregrounded(++scene))
    }

    // ─── the backoff ────────────────────────────────────────────────────────────────────────────

    @Test
    fun aProbeThatDidNotPassIsNotRunAgainBeforeItsBackoff() = runTest {
        visionAnswer = { FloatArray(width) }
        val readiness = readiness()
        val preflight = preflight(readiness)
        preflight.foregrounded(++scene)
        opened.clear()

        now += PreflightPolicy.RETRY_BASE_MS - 1
        assertIs<PreflightOutcome.Waiting>(preflight.foregrounded(++scene))
        assertIs<PreflightOutcome.Waiting>(preflight(readiness()).foregrounded(++scene), "a relaunch skipped the backoff")
        assertNothingWasOpened("a tower was loaded inside the backoff")
        assertEquals(1, attempts(readiness)?.failed, "a wait was counted as a probe")

        now += 1
        assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene))
        assertEquals(2, attempts(readiness)?.failed)
    }

    @Test
    fun theBackoffDoublesAndIsBounded() {
        fun after(misses: Int): Long = PreflightPolicy.delayMs(PreflightAttempts(slow = 0, failed = 0, transient = misses, lastAtEpochMs = 1))

        assertEquals(0, PreflightPolicy.delayMs(PreflightAttempts.NONE))
        assertEquals(listOf(60_000L, 120_000L, 240_000L), (1..3).map(::after))
        assertEquals(PreflightPolicy.RETRY_CAP_MS, after(7))
        // ⚠ Every count, not one: a shift takes its distance modulo 64, so 61 misses would shift
        // the minute out to zero and 65 would shift it by nothing.
        (7..200).forEach { misses -> assertEquals(PreflightPolicy.RETRY_CAP_MS, after(misses), "$misses misses wait less than the cap") }
        assertEquals(PreflightPolicy.RETRY_CAP_MS, after(100_000))
        // Every kind of miss counts towards it.
        assertEquals(240_000L, PreflightPolicy.delayMs(PreflightAttempts(slow = 1, failed = 1, transient = 1, lastAtEpochMs = 1)))
    }

    @Test
    fun aClockThatWentBackwardsDoesNotWaitForItsOldFuture() {
        val attempts = PreflightAttempts(slow = 1, failed = 0, transient = 0, lastAtEpochMs = 5_000_000)

        assertTrue(PreflightPolicy.mayProbe(attempts, nowEpochMs = 1_000))
        assertFalse(PreflightPolicy.mayProbe(attempts, nowEpochMs = 5_000_000 + 59_999))
        assertTrue(PreflightPolicy.mayProbe(attempts, nowEpochMs = 5_000_000 + 60_000))
    }

    // ─── what moves under a probe ───────────────────────────────────────────────────────────────

    @Test
    fun aPassForAKeyThatMovedUnderTheProbeIsDroppedAndNotReported() = runTest {
        duringPrediction = { if (it == 4) current = key(osBuild = "os-2") }
        val readiness = readiness()
        val preflight = preflight(readiness)

        val outcome = preflight.foregrounded(++scene)

        assertEquals(AiReadinessState.AwaitingVerdict, assertIs<PreflightOutcome.NotRecorded>(outcome).state)
        assertFalse(readiness.serves(), "a verdict about the old key was applied to the new one")
        assertNoRecord("a verdict about the old key was written")
        assertNull(preflight.outcomeFor(current), "the old model's probe was reported about the new one")
        assertNull(preflight.outcomeFor(key()), "a probe nothing was taken from was reported about the old one")
    }

    /**
     * ⚠⚠ The new key already has a pass on disk, so readiness is `Ready` the moment the old model's
     * pass is dropped. That `Ready` is the other model's: this probe proved nothing about it, so it
     * is not reported `Passed`.
     */
    @Test
    fun aPassForAnOldKeyIsNotReportedAsThePassOfAReplacementThatIsReady() = runTest {
        val old: PreflightKey = current
        val replacement: PreflightKey = key(osBuild = "os-2")
        current = replacement
        readiness().recordVerdict(passed = true, probed = replacement)
        current = old
        duringPrediction = { if (it == 4) current = replacement }
        val readiness = readiness()
        val preflight = preflight(readiness)

        val outcome = preflight.foregrounded(++scene)

        assertEquals(AiReadinessState.Ready, readiness.current())
        assertIs<PreflightOutcome.NotRecorded>(outcome, "a probe of the old model was reported as this model's pass")
        assertNull(preflight.outcomeFor(replacement))
    }

    /** The same, at the other point: every prediction done, the towers closed, the result not yet recorded. */
    @Test
    fun aStoreChangeBetweenTheLastPredictionAndTheRecordIsSeenForEveryKindOfResult() = runTest {
        val replacement: PreflightKey = key(osBuild = "os-2")
        val results: List<Pair<String, () -> Unit>> = listOf(
            "pass" to {},
            "slow" to { visionCost = 900.milliseconds },
            "failed" to { visionAnswer = { FloatArray(width) } },
            "transient" to {
                visionCost = 900.milliseconds
                thermal = 2
            },
        )
        results.forEach { (what, arrange) ->
            disk.deleteFile(AiReadiness.VERDICT_PATH)
            current = key()
            visionCost = 13.milliseconds
            visionAnswer = { image }
            thermal = 0
            opened.clear()
            arrange()
            val readiness = readiness()
            val preflight = preflight(readiness)
            afterTheTowersClosed = { current = replacement }

            val outcome = preflight.foregrounded(++scene)

            assertIs<PreflightOutcome.NotRecorded>(outcome, what)
            assertEquals(AiReadinessState.AwaitingVerdict, readiness.current(), what)
            assertNoRecord("$what: a result about the old model was written")
            assertEquals(PreflightAttempts.NONE, attempts(readiness), "$what: counted against the replacement")
            assertNull(preflight.outcomeFor(replacement), what)
        }
    }

    /**
     * ⚠⚠ The third slow probe is counted and decided in one write. Counted in one call and decided
     * in a second, it would conclude `Unsuitable` from the count whatever the second call did: a
     * replacement committed between the two would have its verdict dropped and still be reported
     * unsuitable.
     */
    @Test
    fun theThirdSlowProbeIsCountedAndDecidedInOneWrite() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)
        repeat(2) {
            preflight.foregrounded(++scene)
            later()
        }
        events.clear()
        val written = ArrayList<String>()
        duringWrite = { written += disk.readBytes(AiReadiness.VERDICT_PATH)!!.get().decodeToString() }

        assertIs<PreflightOutcome.Unsuitable>(preflight.foregrounded(++scene))

        // ⚠ The probe's own record before it is not a count (§14.5): the one write after it is.
        val outcomes: List<String> = written.filter { "\"begun\"" !in it }
        assertEquals(1, outcomes.size, "the count and the verdict were written apart: $written")
        assertTrue("\"passed\":false" in outcomes.single() && "\"slow\":3" in outcomes.single(), outcomes.single())
        assertEquals(AiReadinessState.Unsuitable, readiness.current())
    }

    @Test
    fun unsuitableIsReportedOnlyWhenReadinessAppliedIt() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)
        repeat(2) {
            preflight.foregrounded(++scene)
            later()
        }
        // The third slow probe of a model that is replaced before its result is recorded.
        opened.clear()
        afterTheTowersClosed = { current = key(osBuild = "os-2") }

        val outcome = preflight.foregrounded(++scene)

        assertIs<PreflightOutcome.NotRecorded>(outcome, "Unsuitable was reported for a verdict that was dropped")
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current())
        assertEquals(AiReadinessState.AwaitingVerdict, readiness().fingerprintChecked(), "the replacement was persisted as unsuitable")
    }

    @Test
    fun aPassThatReadinessCouldNotApplyIsNotReportedAsReady() = runTest {
        applying = { FingerprintOutcome.Failed(IllegalStateException("the vectors could not be cleared")) }
        val readiness = readiness()
        val preflight = preflight(readiness)

        val outcome = preflight.foregrounded(++scene)

        assertEquals(AiReadinessState.AwaitingVerdict, assertIs<PreflightOutcome.NotRecorded>(outcome).state)
        assertFalse(readiness.serves())
        assertNull(preflight.outcomeFor(current))
    }

    @Test
    fun aMissForAKeyThatMovedUnderTheProbeIsNotCounted() = runTest {
        visionCost = 900.milliseconds
        duringPrediction = { if (it == 4) current = key(osBuild = "os-2") }
        val readiness = readiness()
        val preflight = preflight(readiness)

        assertIs<PreflightOutcome.NotRecorded>(preflight.foregrounded(++scene))

        assertEquals(PreflightAttempts.NONE, attempts(readiness))
        // Uncounted, so it did not spend this foreground's slow probe either.
        opened.clear()
        assertIs<PreflightOutcome.Retry>(preflight.prepared(scene))
        assertEquals(1, attempts(readiness)?.slow)
    }

    // ─── what the last report is about ──────────────────────────────────────────────────────────

    @Test
    fun aReportIsAboutTheModelThatWasProbedAndNoOther() = runTest {
        visionAnswer = { FloatArray(width) }
        val readiness = readiness()
        val preflight = preflight(readiness)
        val first: PreflightKey = current
        repeat(3) {
            preflight.foregrounded(++scene)
            later()
        }
        assertEquals(PreflightOutcome.InstallFailed, preflight.outcomeFor(first))

        // The store commits a replacement. Until the next call the report still names the first model.
        val replacement: PreflightKey = key(vision = "vision-b")
        current = replacement
        assertNull(preflight.outcomeFor(replacement), "the first model's failed install was reported about its replacement")
        assertEquals(first, preflight.lastReport.value?.key)

        // The next call looks at the replacement, for which this build has no probe.
        assertEquals(PreflightOutcome.NoProbe, preflight.foregrounded(++scene))
        assertEquals(PreflightOutcome.NoProbe, preflight.outcomeFor(replacement))
        assertNull(preflight.outcomeFor(first))
    }

    @Test
    fun withNothingLeftToProbeTheLastReportIsAboutNoModel() = runTest {
        visionAnswer = { FloatArray(width) }
        val readiness = readiness()
        val preflight = preflight(readiness)
        preflight.foregrounded(++scene)
        assertIs<PreflightOutcome.Retry>(preflight.outcomeFor(current))

        // The model is removed.
        installed = false
        prepared = null
        assertEquals(PreflightOutcome.NotAwaiting, preflight.foregrounded(++scene))

        assertNull(preflight.outcomeFor(current), "a removed model's probe is still reported")
        assertNull(preflight.lastReport.value?.key)
    }

    @Test
    fun aBusyCallDoesNotReplaceWhatTheRunningProbeWillReport() = runTest {
        val allowed = CompletableDeferred<Unit>()
        beforeLoad = { allowed.await() }
        val preflight = preflight(readiness())
        try {
            val probing = async(start = CoroutineStart.UNDISPATCHED) { preflight.foregrounded(++scene) }
            assertEquals(PreflightOutcome.Busy, preflight.foregrounded(++scene))
            assertNull(preflight.lastReport.value, "Busy was stored as a report")
            allowed.complete(Unit)
            assertIs<PreflightOutcome.Passed>(probing.await())
            assertIs<PreflightOutcome.Passed>(preflight.outcomeFor(current))
        } finally {
            allowed.complete(Unit)
        }
    }

    @Test
    fun aMissIsNeverWrittenOverAnAnswer() = runTest {
        val readiness = readiness()
        readiness.recordVerdict(passed = true, probed = current)

        assertIs<ProbeRecorded.Dropped>(readiness.recordProbe(ProbeResult.SLOW, current, atEpochMs = now))
        assertIs<ProbeRecorded.Dropped>(readiness.recordProbe(ProbeResult.PASSED, current, atEpochMs = now))

        assertEquals(AiReadinessState.Ready, readiness().fingerprintChecked())
    }

    @Test
    fun aMissThatCannotBeWrittenIsNotCounted() = runTest {
        visionCost = 900.milliseconds
        // ⚠ The disk fills once the probe's own record is written (§14.5).
        openingVision = { failingWrites = true }
        val readiness = readiness()
        val preflight = preflight(readiness)

        assertIs<PreflightOutcome.NotRecorded>(preflight.foregrounded(++scene))

        failingWrites = false
        openingVision = {}
        assertEquals(PreflightAttempts.NONE, attempts(readiness))
        // ⚠ The record its clear could not write is this process's, and ended: cleared, not counted.
        assertNoRecord("a load that ended in this process is left to be counted")
    }

    /**
     * ⚠⚠ Nothing is loaded whose record could not be written first (§14.5): a probe that could
     * not be counted if it killed Photos is not begun.
     */
    @Test
    fun aProbeWhoseRecordCannotBeWrittenFirstLoadsNothing() = runTest {
        failingWrites = true
        val preflight = preflight(readiness())

        assertEquals(PreflightOutcome.Unreadable, preflight.foregrounded(++scene))

        assertNothingWasOpened("a tower was loaded with no record of the load on disk")
        assertEquals(PreflightOutcome.Unreadable, preflight.outcomeFor(current))
    }

    // ─── a load the process did not survive (§14.5) ─────────────────────────────────────────────

    /** What a kill under a load leaves: the disk as it was at that moment, and no unwinding. */
    private class Killed : Error("the process was killed")

    /** Kills the process the moment [at] is reached, and starts the next one over the same disk. */
    private suspend fun TestScope.killedAt(at: String) {
        val kill: () -> Unit = {
            dead = true
            throw Killed()
        }
        when (at) {
            "vision" -> openingVision = kill
            "text" -> openingText = kill
            else -> duringPrediction = { kill() }
        }
        // ⚠ Whatever the dead process went on to do in memory, none of it reached the disk.
        runCatching { preflight(readiness()).foregrounded(++scene) }
        openingVision = {}
        openingText = {}
        duringPrediction = {}
        dead = false
        process = AttemptLedger()
        opened.clear()
    }

    @Test
    fun aProbeThatKillsPhotosIsCountedAtTheNextLaunchAndTheSecondMakesThePhoneUnsuitable() = runTest {
        killedAt("text")

        val relaunched = readiness()
        assertEquals(AiReadinessState.AwaitingVerdict, relaunched.fingerprintChecked())
        val counted: PreflightAttempts = assertNotNull(attempts(relaunched))
        assertEquals(1, counted.interrupted, "a probe Photos did not survive was not counted")
        assertEquals(now, counted.lastAtEpochMs, "the backoff does not start at the kill")
        // ⚠ Not again at once: the next launch waits out the backoff, and nothing is loaded.
        assertEquals(PreflightOutcome.Waiting(PreflightOutcome.Waiting.Cause.BACKING_OFF, "backing off"), preflight(relaunched).foregrounded(++scene))
        assertNothingWasOpened("a probe that killed Photos was run again at once")

        later()
        killedAt("vision")

        val third = readiness()
        assertEquals(AiReadinessState.Unsuitable, third.fingerprintChecked(), "two loads Photos did not survive are not a verdict")
        assertEquals(PreflightOutcome.NotAwaiting, preflight(third).foregrounded(++scene))
        later()
        assertEquals(PreflightOutcome.NotAwaiting, preflight(readiness()).foregrounded(++scene))
        assertNothingWasOpened("an unsuitable phone was probed again")
    }

    // ⚠⚠ A load frozen when Photos left the foreground, then ended by iOS, is no kill.

    /** A probe that the scene leaves under while its text tower opens, and then the process dies there. */
    private suspend fun TestScope.frozenAt(leaves: (AiReadiness, Long) -> Unit) {
        val readiness = readiness()
        val preflight = preflight(readiness)
        val epoch = ++scene
        openingText = {
            leaves(readiness, epoch)
            dead = true
            throw Killed()
        }
        runCatching { preflight.foregrounded(epoch) }
        openingText = {}
        dead = false
        process = AttemptLedger()
        opened.clear()
    }

    @Test
    fun aLoadFrozenWhenTheSceneLeftAndThenEndedIsTransientNotAKill() = runTest {
        repeat(2) {
            frozenAt { readiness, epoch -> readiness.sceneChanged(active = false, leftForeground = epoch) }
            later()
        }

        val relaunched = readiness()
        assertEquals(AiReadinessState.AwaitingVerdict, relaunched.fingerprintChecked(), "a phone left twice under a load was made unsuitable")
        val counted = assertNotNull(attempts(relaunched))
        assertEquals(0, counted.interrupted)
        assertEquals(2, counted.transient, "a frozen load is not counted at all, so the backoff does not hold")
    }

    /** ⚠⚠ A second leave (`applicationWillTerminate` after `.background`) keeps the first one's record. */
    @Test
    fun aSecondLeaveDoesNotEraseTheForegroundTheFirstOneLeft() = runTest {
        frozenAt { readiness, epoch ->
            readiness.sceneChanged(active = false, leftForeground = epoch)
            readiness.sceneChanged(active = false, leftForeground = null)
        }

        val counted = assertNotNull(attempts(readiness()))
        assertEquals(0, counted.interrupted, "a swipe-away after the background was counted as a kill")
        assertEquals(1, counted.transient)
    }

    @Test
    fun aLoadThatCameBackToTheForegroundAndWasKilledThereStillCounts() = runTest {
        frozenAt { readiness, epoch ->
            readiness.sceneChanged(active = false, leftForeground = epoch)
            readiness.sceneChanged(active = true, leftForeground = null)
        }

        assertEquals(1, attempts(readiness())?.interrupted, "a kill in the foreground was taken for a frozen load")
    }

    /**
     * ⚠⚠ The next process writes its own scene record before its first read of the verdict (the
     * scene changes before the launch's check gets there). The earlier process's record is kept
     * beside it, so its frozen load is still read as frozen.
     */
    @Test
    fun theNextProcessesSceneRecordDoesNotEraseTheEarlierOnesBeforeItIsRead() = runTest {
        frozenAt { readiness, epoch -> readiness.sceneChanged(active = false, leftForeground = epoch) }

        val relaunched = readiness()
        // Before anything of this process reads the verdict: active, then away, then back.
        relaunched.sceneChanged(active = true, leftForeground = null)
        relaunched.sceneChanged(active = false, leftForeground = ++scene)
        relaunched.sceneChanged(active = true, leftForeground = null)

        assertEquals(AiReadinessState.AwaitingVerdict, relaunched.fingerprintChecked())
        val counted = assertNotNull(attempts(relaunched))
        assertEquals(0, counted.interrupted, "a frozen load was counted as a kill once the next process wrote its scene")
        assertEquals(1, counted.transient)
    }

    @Test
    fun aSettledProcessesSceneRecordIsNotKeptOnceItsLoadWasCounted() = runTest {
        frozenAt { readiness, epoch -> readiness.sceneChanged(active = false, leftForeground = epoch) }
        val earlier: String = assertNotNull(onDisk(AiReadiness.SCENE_PATH)).substringAfter("\"process\":\"").substringBefore('"')

        val relaunched = readiness()
        relaunched.fingerprintChecked()
        relaunched.sceneChanged(active = true, leftForeground = null)

        val scene: String = assertNotNull(onDisk(AiReadiness.SCENE_PATH))
        assertFalse(earlier in scene, "the record of a process whose load was settled was kept: $scene")
    }

    @Test
    fun aSceneRecordOfAnotherProcessSaysNothingOfThisOnesLoad() = runTest {
        // An earlier process left its foreground 7; this one's load began in its own foreground.
        readiness().sceneChanged(active = false, leftForeground = 7)
        process = AttemptLedger()
        killedAt("text")

        assertEquals(1, attempts(readiness())?.interrupted)
    }

    @Test
    fun aSceneRecordThatCannotBeReadIsNotReadAsAFrozenLoadOrAKill() = runTest {
        frozenAt { readiness, epoch -> readiness.sceneChanged(active = false, leftForeground = epoch) }
        sceneUnreadable = true

        val relaunched = readiness()
        assertEquals(PreflightOutcome.Unreadable, preflight(relaunched).foregrounded(++scene))
        assertNothingWasOpened("a probe was loaded with its last load neither counted nor forgiven")

        sceneUnreadable = false
        assertEquals(1, attempts(relaunched)?.transient)
    }

    /**
     * ⚠⚠ One lock for the file in the process, not one per graph: a load's clear in a retired
     * graph waits for its successor's writer 3, and does not interleave with it.
     */
    @Test
    fun twoGraphsOfOneProcessTakeTheVerdictFilesLockInTurn() = runTest {
        val old = readiness()
        val successor = readiness()
        successor.fingerprintChecked()
        val commitHeld = CompletableDeferred<Unit>()
        val committing = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { successor.storeChanging { commitHeld.await() } }
        val loading = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            old.attempting(AttemptKind.PROBE, role = null, key = current, atEpochMs = now, foreground = 1L) { }
        }
        runCurrent()
        assertFalse(loading.isCompleted, "a load's record was written beside another graph's writer 3")

        commitHeld.complete(Unit)
        committing.await()
        loading.await()
        assertNoRecord("the load's record outlived the load")
    }

    @Test
    fun aLoadWhoseCoroutineIsCancelledClearsItsRecord() = runTest {
        val graph = readiness()
        graph.fingerprintChecked()
        val load = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            graph.attempting(AttemptKind.PROBE, role = null, key = current, atEpochMs = now, foreground = 1L) { kotlinx.coroutines.awaitCancellation() }
        }
        runCurrent()
        assertTrue(onDisk()?.contains("\"begun\"") == true)
        // ⚠ With the file's lock held elsewhere at that moment, so the clear has to wait for it:
        // a clear that is not `NonCancellable` is cancelled there and leaves the record.
        val elsewhere = readiness()
        val held = CompletableDeferred<Unit>()
        val commit = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { elsewhere.storeChanging { held.await() } }
        runCurrent()

        load.cancel()
        runCurrent()
        held.complete(Unit)
        commit.await()
        runCurrent()

        assertNoRecord("a cancelled load left a record to be counted as a kill")
        process = AttemptLedger()
        assertEquals(0, attempts(readiness())?.interrupted ?: 0)
    }

    // ⚠⚠ The one way out of a fail that interrupted loads made.

    @Test
    fun aPhoneStoppedByInterruptedLoadsMayBeTriedOnceMoreAndTheNextKillStopsItAgain() = runTest {
        killedAt("text")
        later()
        killedAt("text")
        val stopped = readiness()
        assertEquals(AiReadinessState.Unsuitable, stopped.fingerprintChecked())
        assertEquals(ReadinessStanding.For(current, AiReadinessState.Unsuitable, stoppedLoading = true), stopped.standing.value)
        assertEquals(AiReadiness.KeyAnswer.Unsuitable(stoppedLoading = true), stopped.answerFor(current))

        assertTrue(stopped.loadAgain(current), "the way out was refused")

        assertEquals(AiReadinessState.AwaitingVerdict, stopped.state.value)
        assertEquals(AiReadiness.KeyAnswer.Open, stopped.answerFor(current))
        // One more load is allowed, not the backoff's wait, and the next kill stops it at once.
        process = AttemptLedger()
        killedAt("vision")
        assertEquals(AiReadinessState.Unsuitable, readiness().fingerprintChecked(), "the next interrupted load did not stop it again")
    }

    @Test
    fun aPhoneThatWasSlowThreeTimesHasNoWayOut() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)
        repeat(3) {
            preflight.foregrounded(++scene)
            later()
        }
        assertEquals(AiReadinessState.Unsuitable, readiness.state.value)
        assertEquals(false, (readiness.standing.value as ReadinessStanding.For).stoppedLoading)

        assertFalse(readiness.loadAgain(current), "D12's fail of a slow phone was withdrawn")
        assertEquals(AiReadiness.KeyAnswer.Unsuitable(stoppedLoading = false), readiness.answerFor(current))
        assertEquals(AiReadinessState.Unsuitable, readiness().fingerprintChecked())
    }

    @Test
    fun aKillDuringThePredictionsIsCountedToo() = runTest {
        killedAt("prediction")

        assertEquals(1, attempts(readiness())?.interrupted)
    }

    @Test
    fun aLoadOfAnotherInstallOrOsBuildIsNotCountedAgainstThisOne() = runTest {
        killedAt("text")
        // The OS was updated before the next launch: another key.
        current = key(osBuild = "os-2")

        val relaunched = readiness()
        assertEquals(AiReadinessState.AwaitingVerdict, relaunched.fingerprintChecked())
        assertEquals(PreflightAttempts.NONE, attempts(relaunched), "a load of the old OS build was counted against the new one")
        assertIs<PreflightOutcome.Passed>(preflight(relaunched).foregrounded(++scene))
    }

    @Test
    fun aLoadStillRunningInAnotherGraphOfThisProcessIsNotCounted() = runTest {
        val graph = readiness()
        graph.fingerprintChecked()
        var seenByAnotherGraph: PreflightAttempts? = null
        var stillOnDisk = false

        graph.attempting(AttemptKind.PROBE, role = null, key = current, atEpochMs = now, foreground = 1L) {
            // ⚠ A second graph of the same process reads the file while this load runs.
            seenByAnotherGraph = attempts(readiness())
            // ⚠⚠ And leaves its record where it is: a kill from here on is still counted.
            stillOnDisk = onDisk()?.contains("\"begun\"") == true
        }

        assertEquals(PreflightAttempts.NONE, seenByAnotherGraph, "a load running in this process was counted as a kill")
        assertTrue(stillOnDisk, "another graph of the process cleared the record of a load still running")
        assertNoRecord("the load's record outlived the load")
    }

    @Test
    fun aStopThatUnwindsLeavesNothingForTheNextProcessToCount() = runTest {
        val stops: List<Pair<String, (AiReadiness) -> Unit>> = listOf(
            "a failed load" to { _ -> openingText = { error("will not load") } },
            "a teardown" to { readiness -> openingText = { readiness.retire() } },
        )
        stops.forEach { (what, arrange) ->
            disk.deleteFile(AiReadiness.VERDICT_PATH)
            val readiness = readiness()
            arrange(readiness)
            preflight(readiness).foregrounded(++scene)
            openingText = {}
            process = AttemptLedger()

            assertEquals(0, attempts(readiness())?.interrupted ?: 0, "$what was counted as a load Photos did not survive")
        }
    }

    @Test
    fun aProbeTheSceneLeftUnderLeavesNothingForTheNextProcessToCount() = runTest {
        var foreground: Long? = null
        val readiness = readiness()
        val preflight = Preflight(
            readiness = readiness, towers = towers, readVocabulary = { vocabulary }, device = device, beforeLoad = {},
            probes = { key -> probe.takeIf { it.isFor(key) } }, model = VisionModel.PinnedSigLip, time = time,
            dispatcher = UnconfinedTestDispatcher(testScheduler), foregroundNow = { foreground },
        )
        foreground = ++scene
        openingText = { foreground = null }

        assertIs<PreflightOutcome.Waiting>(preflight.foregrounded(scene))

        process = AttemptLedger()
        assertEquals(PreflightAttempts.NONE, attempts(readiness()))
    }

    @Test
    fun aRecordAfterAKillThatCannotBeReadIsNotReadAsNoLoad() = runTest {
        killedAt("text")
        verdictRead = "throws"

        val relaunched = readiness()
        assertEquals(PreflightOutcome.Unreadable, preflight(relaunched).foregrounded(++scene))
        assertNothingWasOpened("a probe was loaded over a record nobody could read")

        verdictRead = "ok"
        assertEquals(1, attempts(relaunched)?.interrupted)
    }

    @Test
    fun aKillThatCannotBeCountedOnDiskIsNotBelievedAndNothingIsLoaded() = runTest {
        killedAt("text")
        failingWrites = true

        val relaunched = readiness()
        relaunched.fingerprintChecked()
        assertEquals(ReadinessStanding.Unreadable, relaunched.standing.value, "a count that is not on disk was believed")
        assertEquals(PreflightOutcome.Unreadable, preflight(relaunched).foregrounded(++scene))
        assertNothingWasOpened("a probe was loaded with its last kill uncounted")

        failingWrites = false
        assertEquals(1, attempts(relaunched)?.interrupted)
        assertEquals(1, attempts(readiness())?.interrupted, "counted twice")
    }

    // ─── a write that fails: memory never gets ahead of the disk ────────────────────────────────

    private fun onDisk(path: String = AiReadiness.VERDICT_PATH): String? = disk.readBytes(path)?.get()?.decodeToString()

    /** The file without a load's [AttemptBegun]: what this process's next read leaves of it. */
    private fun withoutTheLoad(written: String): String =
        verdictJson.encodeToString(PreflightVerdict.serializer(), verdictJson.decodeFromString(PreflightVerdict.serializer(), written).copy(begun = null))

    private val verdictJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * ⚠⚠ No answer, no count **and no load**: no file, or a record with none of them. A load's
     * record that stays is a kill to the next process (§14.5), so a stop must not leave one.
     */
    private fun assertNoRecord(why: String) {
        val written: String = onDisk() ?: return
        val record: PreflightVerdict = verdictJson.decodeFromString(PreflightVerdict.serializer(), written)
        assertEquals(PreflightVerdict(record.key, passed = null, attempts = PreflightAttempts.NONE), record, why)
    }

    @Test
    fun aThirdSlowProbeThatCannotBeWrittenIsNotUnsuitable() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)
        repeat(2) {
            preflight.foregrounded(++scene)
            later()
        }
        val before: String? = onDisk()
        openingVision = { failingWrites = true }

        val outcome = preflight.foregrounded(++scene)

        assertIs<PreflightOutcome.NotRecorded>(outcome, "Unsuitable was reported for a verdict that never reached the disk")
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current(), "the phone is unsuitable in memory only")
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.state.value)
        assertEquals(before, onDisk()?.let(::withoutTheLoad), "the file moved")
        openingVision = {}
        assertNull(preflight.outcomeFor(current))
        assertEquals(AiReadinessState.AwaitingVerdict, readiness().fingerprintChecked(), "the next launch reads another state")

        // The disk recovers. The same foreground was not spent, and the count goes on from the
        // two that were written, not from a third that was not.
        failingWrites = false
        assertEquals(2, attempts(readiness)?.slow)
        assertIs<PreflightOutcome.Unsuitable>(preflight.prepared(scene))
        assertTrue("\"slow\":3" in onDisk()!!, onDisk())
    }

    @Test
    fun aPassThatCannotBeWrittenIsNotReadyAndTheNextOneIs() = runTest {
        val readiness = readiness()
        val preflight = preflight(readiness)
        openingVision = { failingWrites = true }

        val outcome = preflight.foregrounded(++scene)

        assertEquals(AiReadinessState.AwaitingVerdict, assertIs<PreflightOutcome.NotRecorded>(outcome).state)
        assertFalse(readiness.serves(), "a model is served on a verdict the next launch will not find")
        assertTrue(onDisk()?.contains("\"passed\":true") != true, onDisk())
        assertNull(preflight.outcomeFor(current))

        failingWrites = false
        openingVision = {}
        assertIs<PreflightOutcome.Passed>(preflight.foregrounded(++scene))
        assertEquals(AiReadinessState.Ready, readiness().fingerprintChecked())
    }

    @Test
    fun aCountOfAnyKindThatCannotBeWrittenLeavesEverythingAsItWas() = runTest {
        val kinds: List<Pair<String, () -> Unit>> = listOf(
            "slow" to { visionCost = 900.milliseconds },
            "failed" to { visionAnswer = { FloatArray(width) } },
            "transient" to {
                visionCost = 900.milliseconds
                thermal = 2
            },
        )
        kinds.forEach { (what, arrange) ->
            disk.deleteFile(AiReadiness.VERDICT_PATH)
            visionCost = 13.milliseconds
            visionAnswer = { image }
            thermal = 0
            arrange()
            val readiness = readiness()
            val preflight = preflight(readiness)
            // One counted probe, so there is a count on disk to keep.
            assertIs<PreflightOutcome.Retry>(preflight.foregrounded(++scene), what)
            val before: String? = onDisk()
            later()
            openingVision = { failingWrites = true }

            assertIs<PreflightOutcome.NotRecorded>(preflight.foregrounded(++scene), what)

            assertEquals(before, onDisk()?.let(::withoutTheLoad), "$what: the file moved")
            assertEquals(AiReadinessState.AwaitingVerdict, readiness.current(), what)
            failingWrites = false
            openingVision = {}
            val counted: PreflightAttempts = attempts(readiness)!!
            assertEquals(1, counted.slow + counted.failed + counted.transient, "$what: a count that was not written is held somewhere")
            // The next probe is the second, in this process and in the next.
            val next = assertIs<PreflightOutcome.Retry>(preflight.prepared(scene), what).attempts
            assertEquals(2, next.slow + next.failed + next.transient, what)
            assertEquals(next, attempts(readiness()), what)
        }
    }

    @Test
    fun aResetOfTheFailedCountThatCannotBeWrittenLeavesTheInstallFailed() = runTest {
        visionAnswer = { FloatArray(width) }
        val readiness = readiness()
        val preflight = preflight(readiness)
        repeat(3) {
            preflight.foregrounded(++scene)
            later()
        }
        val before: String? = onDisk()
        failingWrites = true

        // Writer 3: the commit goes through, the reset of the count does not.
        readiness.storeChanging { }

        assertEquals(before, onDisk())
        failingWrites = false
        assertEquals(3, attempts(readiness)?.failed)
        assertEquals(PreflightOutcome.InstallFailed, preflight.foregrounded(++scene), "the count was reset in memory and not on disk")
        assertEquals(PreflightOutcome.InstallFailed, preflight(readiness()).foregrounded(++scene))
        // The next commit resets it.
        readiness.storeChanging { }
        assertEquals(0, attempts(readiness)?.failed)
    }

    // ─── a read that fails: nothing is written from a record assumed empty ─────────────────────

    /** Each way a read of something readiness trusts can fail, and how to mend it. */
    private val unreadable: List<Pair<String, (Boolean) -> Unit>> = listOf(
        "the verdict read throws" to { broken -> verdictRead = if (broken) "throws" else "ok" },
        "the verdict reads as null though the file is there" to { broken -> verdictRead = if (broken) "null" else "ok" },
        "the store or a marker cannot be read" to { broken -> factsRead = if (broken) ({ error("I/O error") }) else ({}) },
        "the install record cannot be read for the key" to { broken -> keyRead = if (broken) ({ error("I/O error") }) else ({}) },
    )

    @Test
    fun aPersistedPassSurvivesAReadThatFailsAndIsReadyAgainAfterwards() = runTest {
        unreadable.forEach { (what, breakIt) ->
            disk.deleteFile(AiReadiness.VERDICT_PATH)
            opened.clear()
            preflight(readiness()).foregrounded(++scene)
            val pass: String = onDisk()!!
            opened.clear()

            // The next launch, and the read fails.
            breakIt(true)
            val readiness = readiness()
            val preflight = preflight(readiness)
            readiness.fingerprintChecked()
            assertFalse(readiness.serves(), "$what: a model was served off a failed read")
            assertEquals(PreflightOutcome.Unreadable, preflight.foregrounded(++scene), "$what: read as nothing to do, or probed")
            readiness.storeChanging { }
            readiness.recordVerdict(passed = false, probed = current)
            assertIs<ProbeRecorded.Dropped>(readiness.recordProbe(ProbeResult.SLOW, current, atEpochMs = now), what)
            assertNothingWasOpened("$what: a tower was loaded for a probe whose result could not be recorded")
            assertEquals(pass, onDisk(), "$what: the pass on disk was written over")

            breakIt(false)
            assertEquals(AiReadinessState.Ready, readiness.fingerprintChecked(), "$what: the pass did not survive")
            assertEquals(PreflightOutcome.NotAwaiting, preflight.foregrounded(++scene), what)
            assertNothingWasOpened("$what: a model with a pass was probed again")
        }
    }

    @Test
    fun aReadyModelIsNotServedWhileAReadFailsAndIsServedAgainAfterwards() = runTest {
        unreadable.forEach { (what, breakIt) ->
            disk.deleteFile(AiReadiness.VERDICT_PATH)
            backendClosed = 0
            val readiness = readiness()
            preflight(readiness).foregrounded(++scene)
            assertTrue(readiness.serves())
            val pass: String = onDisk()!!

            breakIt(true)
            readiness.fingerprintChecked()
            assertFalse(readiness.serves(), "$what: what cannot be read was served")
            assertEquals(1, backendClosed, "$what: the sessions were left open")
            assertEquals(pass, onDisk(), what)

            breakIt(false)
            assertEquals(AiReadinessState.Ready, readiness.fingerprintChecked(), what)
            assertTrue(readiness.serves())
        }
    }

    /** The preflight's own read is the first to meet the failure: it stops the serving too. */
    @Test
    fun aReadThatFailsFirstUnderThePreflightStopsServingAsWell() = runTest {
        unreadable.forEach { (what, breakIt) ->
            disk.deleteFile(AiReadiness.VERDICT_PATH)
            backendClosed = 0
            val readiness = readiness()
            val preflight = preflight(readiness)
            preflight.foregrounded(++scene)
            assertTrue(readiness.serves())

            breakIt(true)
            assertEquals(PreflightOutcome.Unreadable, preflight.foregrounded(++scene), what)

            assertFalse(readiness.serves(), "$what: what cannot be read went on being served")
            assertEquals(1, backendClosed, "$what: the sessions were left open")
            breakIt(false)
            assertEquals(AiReadinessState.Ready, readiness.fingerprintChecked(), what)
        }
    }

    @Test
    fun theCountsSurviveAReadThatFailsAndNoProbeIsRunMeanwhile() = runTest {
        unreadable.forEach { (what, breakIt) ->
            disk.deleteFile(AiReadiness.VERDICT_PATH)
            visionCost = 900.milliseconds
            val readiness = readiness()
            val preflight = preflight(readiness)
            repeat(2) {
                preflight.foregrounded(++scene)
                later()
            }
            val counted: String = onDisk()!!
            opened.clear()

            breakIt(true)
            assertEquals(PreflightOutcome.Unreadable, preflight.foregrounded(++scene), "$what: probed from counts it could not see")
            assertNull(preflight.lastReport.value?.key, what)
            readiness.storeChanging { }
            assertNothingWasOpened(what)
            assertEquals(counted, onDisk(), "$what: the counts were reset or written over")

            breakIt(false)
            later()
            assertEquals(2, attempts(readiness)?.slow, what)
            assertIs<PreflightOutcome.Unsuitable>(preflight.foregrounded(++scene), "$what: the third slow probe started from zero")
        }
    }

    @Test
    fun aReadThatFailsOnlyWhenTheResultIsRecordedDropsTheResult() = runTest {
        unreadable.forEach { (what, breakIt) ->
            disk.deleteFile(AiReadiness.VERDICT_PATH)
            visionCost = 900.milliseconds
            val readiness = readiness()
            val preflight = preflight(readiness)
            preflight.foregrounded(++scene)
            later()
            val counted: String = onDisk()!!
            opened.clear()
            afterTheTowersClosed = { breakIt(true) }

            assertIs<PreflightOutcome.NotRecorded>(preflight.foregrounded(++scene), what)

            assertEquals(counted, onDisk()?.let(::withoutTheLoad), "$what: a result was written after a failed read")
            breakIt(false)
            assertEquals(1, attempts(readiness)?.slow, what)
        }
    }

    /**
     * ⚠ The other case, kept apart on purpose: bytes that were **read** and are not this build's
     * record. There is no verdict in them and nothing to protect; the next result replaces them.
     */
    @Test
    fun aFileThatWasReadAndDoesNotDecodeIsNoVerdictAndIsWrittenOver() = runTest {
        listOf("", "not json", """{"key":"another shape","passed":true}""").forEach { written ->
            disk.writeRawByteArray(AiReadiness.VERDICT_PATH, written.encodeToByteArray())
            opened.clear()
            val readiness = readiness()

            assertEquals(AiReadinessState.AwaitingVerdict, readiness.fingerprintChecked(), "read as a verdict: $written")
            assertIs<PreflightOutcome.Passed>(preflight(readiness).foregrounded(++scene), written)
            assertTrue("\"passed\":true" in onDisk()!!)
            assertEquals(AiReadinessState.Ready, readiness().fingerprintChecked())
        }
    }

    @Test
    fun theCountsStayBesideTheVerdictTheyLedTo() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)
        repeat(3) {
            preflight.foregrounded(++scene)
            later()
        }

        val written: String = disk.readBytes(AiReadiness.VERDICT_PATH)!!.get().decodeToString()
        assertTrue("\"passed\":false" in written && "\"slow\":3" in written, written)
    }

    @Test
    fun aBareVerdictKeepsTheCountsToo() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)
        preflight.foregrounded(++scene)
        later()
        preflight.foregrounded(++scene)

        readiness.recordVerdict(passed = false, probed = current)

        val written: String = disk.readBytes(AiReadiness.VERDICT_PATH)!!.get().decodeToString()
        assertTrue("\"passed\":false" in written && "\"slow\":2" in written, written)
    }

    @Test
    fun aRuntimeRetiredUnderTheProbeClosesItsTowersAndRecordsNothing() = runTest {
        val readiness = readiness()
        duringPrediction = { if (it == 2) readiness.retire() }
        val preflight = preflight(readiness)

        assertEquals(PreflightOutcome.Abandoned, preflight.foregrounded(++scene))

        assertNull(preflight.lastReport.value?.key, "a retired runtime's probe is reported about a model")
        assertEquals(0, open, "the retired runtime's probe left a tower open")
        assertEquals(3, visionPredictions, "it went on predicting after the retirement")
        assertNoRecord("a retired runtime's probe left a record")
        preflight.awaitSessionsClosed()
    }

    @Test
    fun aRuntimeRetiredAfterTheLastPredictionRecordsAndReportsNothing() = runTest {
        val readiness = readiness()
        afterTheTowersClosed = { readiness.retire() }
        val preflight = preflight(readiness)

        val outcome = preflight.foregrounded(++scene)

        assertIs<PreflightOutcome.NotRecorded>(outcome, "a pass was reported for a runtime that was retired before it was recorded")
        assertFalse(readiness.serves())
        assertNoRecord("a retired runtime's probe left a record")
        assertNull(preflight.outcomeFor(current))
    }

    @Test
    fun aRuntimeRetiredBeforeTheFirstTowerOpensNone() = runTest {
        val readiness = readiness()
        beforeLoad = { readiness.retire() }

        assertEquals(PreflightOutcome.Abandoned, preflight(readiness).foregrounded(++scene))

        assertNothingWasOpened("a retired runtime loaded a model")
    }

    // ─── memory: who may hold a tower when ──────────────────────────────────────────────────────

    @Test
    fun noTowerIsOpenedBeforeTheLoadIsAllowed() = runTest {
        val allowed = CompletableDeferred<Unit>()
        beforeLoad = { allowed.await() }
        val preflight = preflight(readiness())
        try {
            val probing = async(start = CoroutineStart.UNDISPATCHED) { preflight.foregrounded(++scene) }

            assertNothingWasOpened("a tower was opened while an earlier runtime was still unloading")
            assertEquals(PreflightOutcome.Busy, preflight.foregrounded(++scene), "a second probe started beside the first")

            allowed.complete(Unit)
            assertIs<PreflightOutcome.Passed>(probing.await())
        } finally {
            allowed.complete(Unit)
        }
    }

    @Test
    fun whoeverWaitsForTheTowersWaitsUntilBothAreClosed() = runTest {
        val preflight = preflight(readiness())
        // How many towers were open when the wait returned. ⚠ Read after the probe, never asserted
        // inside the prediction: a throw there is the probe's "a prediction failed", and is caught.
        var openWhenReleased = -1
        duringPrediction = { prediction ->
            if (prediction == 0) {
                // Undispatched: it reaches the wait now, inside a prediction, with both towers open.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    preflight.awaitSessionsClosed()
                    openWhenReleased = open
                }
            }
        }

        assertIs<PreflightOutcome.Passed>(preflight.foregrounded(++scene))
        testScheduler.advanceUntilIdle()

        assertEquals(0, openWhenReleased, "the wait returned while the probe held a tower, or never returned")
    }

    @Test
    fun withNoProbeRunningNobodyWaits() = runTest {
        preflight(readiness()).awaitSessionsClosed()
    }

    @Test
    fun aModelThatWasReadyHasItsSessionsClosedBeforeTheProbeOpensItsOwn() = runTest {
        val readiness = readiness()
        readiness.recordVerdict(passed = true, probed = current)
        assertEquals(0, backendClosed)

        // A new OS build, seen first by the preflight's own read.
        current = key(osBuild = "os-2")
        openingVision = { assertEquals(1, backendClosed, "the backend's pair was still open beside the probe's") }

        assertIs<PreflightOutcome.Passed>(preflight(readiness).foregrounded(++scene))
    }

    // ─── What the prepare step relies on (§14.17).

    @Test
    fun theProbeAfterARecompileIsNotHeldBackByTheBackoffOfTheProbeThatFailed() = runTest {
        openingVision = { error("Core ML said no") }
        val readiness = readiness()
        val preflight = preflight(readiness)
        val failed = assertIs<PreflightOutcome.Retry>(preflight.prepared(scene))
        assertEquals(false, assertIs<PreflightFinding.Failed>(failed.finding).beforeAnyTower)
        // Seconds later, the towers compiled again: an ordinary probe is still backing off.
        openingVision = {}
        assertIs<PreflightOutcome.Waiting>(preflight.prepared(scene))

        // ⚠⚠ The probe that follows a recompile is about other bytes, and runs now. Held back,
        // it never ran while the user stayed in the app.
        assertIs<PreflightOutcome.Passed>(preflight.recompiled(scene))
    }

    @Test
    fun aFailureBeforeAnyTowerWasOpenedSaysSo() = runTest {
        vocabulary = null
        val failed = assertIs<PreflightOutcome.Retry>(preflight(readiness()).prepared(scene))

        // ⚠ What keeps the prepare step from compiling 1.8 GB again for a vocabulary.
        assertEquals(true, assertIs<PreflightFinding.Failed>(failed.finding).beforeAnyTower)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun noProbeBeginsWhileTheCompiledCacheIsChangingAndAProbeHoldsFromTheMomentItIsLetThrough() = runTest {
        val readiness = readiness()
        val preflight = preflight(readiness)

        // The prepare step has marked the cache as changing: nothing is probed.
        val outcome = readiness.cacheChanging { preflight.prepared(scene) }
        assertEquals(PreflightOutcome.NotAwaiting, outcome)
        assertEquals(emptyList(), opened)

        // ⚠⚠ And a probe that is let through holds from that moment, before the vocabulary is read:
        // `awaitSessionsClosed` is what the prepare step waits on, and it is held from then, not
        // only once the first tower is opened.
        var heldBeforeTheVocabularyWasRead: Boolean? = null
        val watching = Preflight(
            readiness = readiness,
            towers = towers,
            readVocabulary = {
                heldBeforeTheVocabularyWasRead = holdingNow?.invoke()
                vocabulary
            },
            device = device,
            beforeLoad = {},
            probes = { key -> probe.takeIf { it.isFor(key) } },
            model = VisionModel.PinnedSigLip,
            time = time,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        holdingNow = {
            var free = false
            val asking = backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                watching.awaitSessionsClosed()
                free = true
            }
            asking.cancel()
            !free
        }
        assertIs<PreflightOutcome.Passed>(watching.prepared(scene))
        assertEquals(true, heldBeforeTheVocabularyWasRead, "a probe was let through and did not hold its towers yet")
        watching.awaitSessionsClosed()
    }

    @Test
    fun aProbeHoldsUntilItsAnswerIsRecordedNotOnlyUntilItsTowersClose() = runTest {
        val readiness = readiness()
        val preflight = preflight(readiness)
        var heldWhileRecording: Boolean? = null
        // The verdict is being written: the towers are closed, and the answer is not recorded yet.
        duringWrite = {
            var free = false
            val asking = backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                preflight.awaitSessionsClosed()
                free = true
            }
            asking.cancel()
            heldWhileRecording = !free
        }

        val report = preflight.foregroundedReport(++scene)

        // ⚠⚠ Released when the towers closed, the prepare step could discard and recompile them
        // in between, and this pass was then recorded for towers nobody had probed.
        assertIs<PreflightOutcome.Passed>(report.outcome)
        assertEquals(true, heldWhileRecording, "the probe let go of its towers before its answer was recorded")
        // ⚠ And the answer says what it is about.
        assertEquals(current, report.key)
        preflight.awaitSessionsClosed()
    }

    // ─── A foreground is the scene's, not a call's.

    @Test
    fun checksThatOverlapInOneForegroundCountOneSlowProbeHoweverManyTheyAre() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)
        val foreground = 7L

        // The launch's check, a rehydration's and a third, all in the same foreground of the scene.
        assertIs<PreflightOutcome.Retry>(preflight.foregrounded(foreground))
        later()
        assertIs<PreflightOutcome.Waiting>(preflight.foregrounded(foreground))
        later()
        assertIs<PreflightOutcome.Waiting>(preflight.foregroundedReport(foreground).outcome)

        // ⚠⚠ One slow probe counted. Each call was "a foreground" once: three slow probes
        // inside one real foreground made the phone `Unsuitable`, for good.
        assertEquals(1, attempts(readiness)?.slow)
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current())

        // And a graph that replaces this one inside the same foreground gets no second one,
        // when it is given the process's record of which foreground has had its probe.
        val process = SlowProbes()
        val first = Preflight(readiness, towers, { vocabulary }, device, {}, { key -> probe.takeIf { it.isFor(key) } }, time, UnconfinedTestDispatcher(testScheduler), VisionModel.PinnedSigLip, process)
        val second = Preflight(readiness, towers, { vocabulary }, device, {}, { key -> probe.takeIf { it.isFor(key) } }, time, UnconfinedTestDispatcher(testScheduler), VisionModel.PinnedSigLip, process)
        later()
        assertIs<PreflightOutcome.Retry>(first.foregrounded(8L))
        later()
        assertIs<PreflightOutcome.Waiting>(second.foregrounded(8L))
        assertEquals(2, attempts(readiness)?.slow)
    }

    @Test
    fun threeSlowProbesOnThreeForegroundsOfTheSceneAreStillWhatMakesAPhoneUnsuitable() = runTest {
        visionCost = 900.milliseconds
        val readiness = readiness()
        val preflight = preflight(readiness)

        assertIs<PreflightOutcome.Retry>(preflight.foregrounded(1L))
        later()
        assertIs<PreflightOutcome.Retry>(preflight.foregrounded(2L))
        later()
        assertIs<PreflightOutcome.Unsuitable>(preflight.foregrounded(3L))
    }

    @Test
    fun nothingIsProbedForAPhotosThatIsNotInTheForeground() = runTest {
        val readiness = readiness()
        val preflight = preflight(readiness)

        // ⚠ No foreground to count it in, and a probe opens both towers.
        val report = preflight.foregroundedReport(null)
        assertIs<PreflightOutcome.Waiting>(report.outcome)
        assertNull(report.key)
        assertIs<PreflightOutcome.Waiting>(preflight.prepared(null))
        assertEquals(emptyList(), opened)
        assertIs<PreflightOutcome.Passed>(preflight.foregrounded(1L))
    }

    @Test
    fun aProbeTheSceneLeftUnderLeavesNoTrace() = runTest {
        // A slow tower, on a probe during which Photos is sent to the background.
        visionCost = 900.milliseconds
        val readiness = readiness()
        var sceneNow: Long? = 4L
        val process = SlowProbes()
        val preflight = Preflight(
            readiness, towers, { vocabulary }, device, {}, { key -> probe.takeIf { it.isFor(key) } }, time,
            UnconfinedTestDispatcher(testScheduler), VisionModel.PinnedSigLip, process, foregroundNow = { sceneNow },
        )
        openingText = { sceneNow = null }

        val report = preflight.foregroundedReport(4L)

        // ⚠⚠ Nothing of it is kept: not a slow probe counted towards `Unsuitable`, not the
        // backoff's stamp, not this foreground's one slow probe.
        assertIs<PreflightOutcome.Waiting>(report.outcome)
        assertNull(report.key)
        assertEquals(PreflightAttempts.NONE, attempts(readiness))
        assertFalse(process.spentIn(4L))
        // So the very next probe, back in the same foreground, runs: nothing to back off from.
        sceneNow = 4L
        openingText = {}
        visionCost = 13.milliseconds
        assertIs<PreflightOutcome.Passed>(preflight.foregrounded(4L))
    }

    // ─── A teardown at every point of a probe: nobody waits for themselves.

    /**
     * A probe of this graph, with the graph torn down at [point] of it; then a successor graph
     * asks to load. Everything must END: the probe, the unload, and the successor's wait.
     */
    private fun TestScope.aTeardownDuringAProbeEndsEverything(point: String, arm: (retire: () -> Unit) -> Unit) {
        val unloads = PendingUnloads()
        val readiness = readiness()
        lateinit var preflight: Preflight
        var unloading: kotlinx.coroutines.Job? = null
        val eager = UnconfinedTestDispatcher(testScheduler)
        val retire: () -> Unit = {
            if (unloading == null) unloading = unloads.retire(readiness, backgroundScope + eager) { preflight.awaitSessionsClosed() }
        }
        // ⚠ As Photos wires it: the probe waits for an unload before it loads, and says whose it is.
        beforeLoad = { unloads.awaitFor(readiness) }
        preflight = preflight(readiness)
        arm(retire)

        val probing = backgroundScope.async(eager) { preflight.foregrounded(++scene) }
        runCurrent()

        // ⚠⚠ COMPLETED, each of them, not merely "no exception": a probe that had claimed its
        // towers once waited for its own graph's unload, which waited for that claim.
        assertTrue(probing.isCompleted, "$point: the probe never ended")
        assertTrue(assertNotNull(unloading, "$point: the case never tore the graph down").isCompleted, "$point: the teardown never finished")
        assertFalse(unloads.pending(), "$point: an unload is still pending")
        assertEquals(0, open, "$point: a tower was left open")
        assertNotEquals(AiReadinessState.Ready, readiness.current(), point)
        // And a successor graph is let through to load.
        val successor = backgroundScope.async(eager) { unloads.awaitFor(readiness()) }
        runCurrent()
        assertTrue(successor.isCompleted, "$point: a later graph could not load a model")
        // ⚠ Nothing of the probe was kept by a graph that was gone.
        assertNoRecord("$point: a retired graph recorded a probe")
    }

    @Test
    fun aTeardownBetweenAProbesClaimAndItsWaitForAnUnloadEndsEverything() = runTest {
        // ⚠⚠ The interleaving: the claim is made inside readiness's lock, and the wait for an
        // unload comes after it. Torn down in between, the unload that is pending is OURS.
        aTeardownDuringAProbeEndsEverything("between the claim and the wait") { retire ->
            val wired = beforeLoad
            beforeLoad = {
                retire()
                wired()
            }
        }
    }

    @Test
    fun aTeardownBeforeAProbeBeginsEndsEverything() = runTest {
        aTeardownDuringAProbeEndsEverything("before the probe") { retire -> retire() }
    }

    @Test
    fun aTeardownWhileATowerLoadsEndsEverything() = runTest {
        aTeardownDuringAProbeEndsEverything("while the vision tower loads") { retire -> openingVision = { retire() } }
    }

    @Test
    fun aTeardownDuringTheMeasurementEndsEverything() = runTest {
        aTeardownDuringAProbeEndsEverything("during a prediction") { retire -> duringPrediction = { retire() } }
    }

    @Test
    fun aTeardownBetweenTheMeasurementAndItsRecordingEndsEverything() = runTest {
        // The window the claim was widened over: the towers are closed, the answer not recorded.
        aTeardownDuringAProbeEndsEverything("after the towers closed") { retire -> afterTheTowersClosed = { retire() } }
    }

    @Test
    fun aProbeSaysWhichInstallItIsOfWhileItHoldsItsTowersAndNothingBeforeOrAfter() = runTest {
        // ⚠ What a screen reads to say "checking now": the report it has is the attempt before.
        lateinit var preflight: Preflight
        var whileItLoads: PreflightKey? = null
        beforeLoad = { whileItLoads = preflight.probing.value }
        preflight = preflight(readiness())
        assertEquals(null, preflight.probing.value)

        val report = preflight.foregroundedReport(++scene)

        assertNotNull(whileItLoads, "a probe that had claimed its towers was not said to be running")
        // ⚠ With the key its report carries: of this install, and of no other.
        assertEquals(report.key, whileItLoads)
        assertEquals(null, preflight.probing.value)
    }

    @Test
    fun aProbeWhoseGraphAndItsSuccessorWereBothTornDownWaitsForNeither() = runTest {
        // ⚠⚠ The sequence: A's probe has claimed its towers; A is torn down; its successor B is
        // torn down too, before A's probe reaches its wait. What is pending is B's unload, which
        // waits for A's, which waits for A's probe.
        val unloads = PendingUnloads()
        val a = readiness()
        val b = readiness()
        val eager = UnconfinedTestDispatcher(testScheduler)
        lateinit var preflight: Preflight
        var unloadingA: kotlinx.coroutines.Job? = null
        var unloadingB: kotlinx.coroutines.Job? = null
        beforeLoad = {
            if (unloadingA == null) {
                unloadingA = unloads.retire(a, backgroundScope + eager) { preflight.awaitSessionsClosed() }
                unloadingB = unloads.retire(b, backgroundScope + eager) { }
            }
            unloads.awaitFor(a)
        }
        preflight = preflight(a)

        val probing = backgroundScope.async(eager) { preflight.foregrounded(++scene) }
        runCurrent()

        assertTrue(probing.isCompleted, "the probe waited for an unload that waits for its own")
        assertTrue(unloadingA!!.isCompleted, "the first teardown never finished")
        assertTrue(unloadingB!!.isCompleted, "the second teardown never finished")
        assertFalse(unloads.pending())
        // A third graph is let through to load.
        val third = backgroundScope.async(eager) { unloads.awaitFor(readiness()) }
        runCurrent()
        assertTrue(third.isCompleted, "a later graph could not load a model")
    }

    @Test
    fun aTeardownThatLandsWhileAGraphWaitsForItsPredecessorEndsEverything() = runTest {
        val unloads = PendingUnloads()
        val earlier = readiness()
        val mine = readiness()
        val eager = UnconfinedTestDispatcher(testScheduler)
        val earlierLetsGo = CompletableDeferred<Unit>()
        val first = unloads.retire(earlier, backgroundScope + eager) { earlierLetsGo.await() }
        // A party of my graph, not retired, waits for the predecessor's unload...
        val waiting = backgroundScope.async(eager) { unloads.awaitFor(mine) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        // ...and my graph is torn down under it. ⚠ What it waits for was published BEFORE my
        // teardown, so nothing in its chain is my own unload.
        val second = unloads.retire(mine, backgroundScope + eager) { waiting.join() }
        runCurrent()
        assertFalse(second.isCompleted)

        earlierLetsGo.complete(Unit)
        runCurrent()

        assertTrue(first.isCompleted)
        assertTrue(waiting.isCompleted, "a party suspended on its predecessor never came back")
        assertTrue(second.isCompleted, "my own teardown never finished")
        assertFalse(unloads.pending())
    }

    @Test
    fun aChainOfTeardownsUnwindsInOrder() = runTest {
        val unloads = PendingUnloads()
        val eager = UnconfinedTestDispatcher(testScheduler)
        val gates = List(4) { CompletableDeferred<Unit>() }
        val order = mutableListOf<Int>()
        val jobs = gates.mapIndexed { index, gate ->
            unloads.retire(readiness(), backgroundScope + eager) {
                gate.await()
                order += index
            }
        }
        // Let go in the WRONG order: each still waits for the one before it.
        gates.reversed().forEach { it.complete(Unit) }
        runCurrent()

        assertEquals(listOf(0, 1, 2, 3), order)
        assertTrue(jobs.all { it.isCompleted })
        assertFalse(unloads.pending())
    }

    @Test
    fun aGraphWaitsForAnEarlierGraphsUnloadAndNotForItsOwn() = runTest {
        val unloads = PendingUnloads()
        val earlier = readiness()
        val mine = readiness()
        val letGo = CompletableDeferred<Unit>()
        val eager = UnconfinedTestDispatcher(testScheduler)
        unloads.retire(earlier, backgroundScope + eager) { letGo.await() }

        // The earlier graph's unload is waited for by another graph, and not by its own parties.
        val own = backgroundScope.async(eager) { unloads.awaitFor(earlier) }
        val other = backgroundScope.async(eager) { unloads.awaitFor(mine) }
        runCurrent()
        assertTrue(own.isCompleted, "a party waited for its own graph's unload")
        assertFalse(other.isCompleted, "a graph loaded while an earlier one was still unloading")
        letGo.complete(Unit)
        runCurrent()
        assertTrue(other.isCompleted)
        assertFalse(unloads.pending())
    }

    private var holdingNow: (() -> Boolean)? = null

    /** The scene's foreground: each `foregrounded` of a case is a new one; `prepared` is in the one that is. */
    private var scene = 1L
}

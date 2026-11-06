package org.tenshu.inference.proxy

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** [PreflightJudge]'s lines, each from both sides (`ios-on-device-inference.md` §7 D12 ②). */
class PreflightJudgeTest {

    private val width = 8
    private val probe = PreflightProbe(
        id = "test",
        visionArchiveSha256 = "v", textArchiveSha256 = "t", vocabularySha256 = "w",
        imageRgbSha256 = "", imageTensorSha256 = "",
        captionDescribes = "", captionDoesNot = "",
        tokensDescribes = IntArray(0), tokensDoesNot = IntArray(0),
        hostMargin = 0.5, minMargin = 0.25,
        hostVisionNorm = 10.0, hostDescribesNorm = 20.0, hostDoesNotNorm = 20.0, normBand = 2.0,
    )

    private fun vector(length: Float, along: Float = 1f): FloatArray = FloatArray(width).also {
        it[0] = length * along
        it[1] = length * sqrt(1f - along * along)
    }

    private fun measured(
        visionMs: List<Double> = listOf(10.0, 10.0, 10.0),
        textMs: List<Double> = listOf(5.0, 5.0),
        vision: List<FloatArray> = listOf(vector(10f)),
        describes: List<FloatArray> = listOf(vector(20f, along = 0.6f)),
        doesNot: List<FloatArray> = listOf(vector(20f, along = 0.1f)),
        available: Long? = null,
        thermal: Int = 0,
        warned: Boolean = false,
        failure: String? = null,
    ) = PreflightMeasurement(
        thermalAtStart = thermal, memoryWarned = warned, failure = failure,
        vision = vision, describes = describes, doesNot = doesNot,
        visionPredictMs = visionMs, textPredictMs = textMs, availableBytes = available,
    )

    private fun judge(measurement: PreflightMeasurement): PreflightFinding = PreflightJudge.judge(measurement, probe, width)

    @Test
    fun aGoodProbePassesWithItsMargin() {
        assertEquals(0.5, assertIs<PreflightFinding.Passed>(judge(measured())).margin, 1e-6)
    }

    @Test
    fun theLatencyLineIsTheMedianAndFiveHundredMillisecondsPasses() {
        assertIs<PreflightFinding.Passed>(judge(measured(visionMs = listOf(500.0, 500.0, 500.0))))
        assertIs<PreflightFinding.Slow>(judge(measured(visionMs = listOf(500.5, 500.5, 500.5))))
        assertIs<PreflightFinding.Slow>(judge(measured(textMs = listOf(500.5, 500.5))))
        // One slow first prediction is not a slow tower; a slow half is.
        assertIs<PreflightFinding.Passed>(judge(measured(visionMs = listOf(4_000.0, 10.0, 10.0))))
        assertIs<PreflightFinding.Slow>(judge(measured(visionMs = listOf(10.0, 900.0, 900.0))))
        assertIs<PreflightFinding.Slow>(judge(measured(visionMs = listOf(10.0, 900.0)), ), "an even count is judged on its upper median")
    }

    @Test
    fun aTowerThatWasNeverTimedIsNotFast() {
        assertIs<PreflightFinding.Slow>(judge(measured(visionMs = emptyList())))
        assertIs<PreflightFinding.Slow>(judge(measured(textMs = emptyList())))
    }

    @Test
    fun theMemoryLineIsThePlaceholder() {
        val line: Long = PreflightBudget.PLACEHOLDER_MIN_AVAILABLE_BYTES
        assertIs<PreflightFinding.Passed>(judge(measured(available = line)))
        assertIs<PreflightFinding.Slow>(judge(measured(available = line - 1)))
        assertIs<PreflightFinding.Passed>(judge(measured(available = null)))
    }

    @Test
    fun theMarginLineIsTheProbesOwn() {
        // 0.6 against 0.35 is exactly the 0.25 required; against 0.36 it is short.
        assertIs<PreflightFinding.Passed>(judge(measured(doesNot = listOf(vector(20f, along = 0.349f)))))
        assertIs<PreflightFinding.Failed>(judge(measured(doesNot = listOf(vector(20f, along = 0.36f)))))
        assertIs<PreflightFinding.Failed>(judge(measured(describes = listOf(vector(20f, along = 0.1f)), doesNot = listOf(vector(20f, along = 0.6f)))))
    }

    @Test
    fun theMarginIsTheWorstPairingOfTheBatch() {
        val drifting = listOf(vector(20f, along = 0.6f), vector(20f, along = 0.2f))
        assertIs<PreflightFinding.Failed>(judge(measured(describes = drifting)))
        assertIs<PreflightFinding.Failed>(judge(measured(vision = listOf(vector(10f), vector(10f, along = -1f)))))
    }

    @Test
    fun theNormBandIsTheProbesOwnOnEveryTower() {
        assertIs<PreflightFinding.Passed>(judge(measured(vision = listOf(vector(5f)))))
        assertIs<PreflightFinding.Failed>(judge(measured(vision = listOf(vector(4.9f)))))
        assertIs<PreflightFinding.Passed>(judge(measured(vision = listOf(vector(20f)))))
        assertIs<PreflightFinding.Failed>(judge(measured(vision = listOf(vector(20.5f)))))
        assertIs<PreflightFinding.Failed>(judge(measured(describes = listOf(vector(41f, along = 0.6f)))))
        assertIs<PreflightFinding.Failed>(judge(measured(doesNot = listOf(vector(9f, along = 0.1f)))))
    }

    @Test
    fun aTowerWithNoVectorOrAnUnusableOneFails() {
        assertIs<PreflightFinding.Failed>(judge(measured(vision = emptyList())))
        assertIs<PreflightFinding.Failed>(judge(measured(describes = emptyList())))
        assertIs<PreflightFinding.Failed>(judge(measured(doesNot = emptyList())))
        assertIs<PreflightFinding.Failed>(judge(measured(doesNot = listOf(FloatArray(width) { Float.POSITIVE_INFINITY }))))
        assertIs<PreflightFinding.Failed>(judge(measured(vision = listOf(vector(10f), FloatArray(width)))))
    }

    @Test
    fun theOutputIsJudgedBeforeTheSpeed() {
        assertIs<PreflightFinding.Failed>(judge(measured(visionMs = listOf(900.0), vision = listOf(FloatArray(width) { Float.NaN }))))
        assertIs<PreflightFinding.Failed>(judge(measured(failure = "the text tower did not load", visionMs = listOf(900.0))))
    }

    @Test
    fun aHotStartOrAMemoryWarningExcusesEveryMissAndNoPass() {
        val misses = listOf(
            measured(visionMs = listOf(900.0)),
            measured(failure = "the vision tower did not load"),
            measured(vision = listOf(FloatArray(width))),
            measured(available = 1),
        )
        misses.forEach { miss ->
            assertIs<PreflightFinding.Transient>(judge(PreflightMeasurement(
                thermalAtStart = 2, memoryWarned = false, failure = miss.failure,
                vision = miss.vision, describes = miss.describes, doesNot = miss.doesNot,
                visionPredictMs = miss.visionPredictMs, textPredictMs = miss.textPredictMs, availableBytes = miss.availableBytes,
            )))
            assertIs<PreflightFinding.Transient>(judge(PreflightMeasurement(
                thermalAtStart = 0, memoryWarned = true, failure = miss.failure,
                vision = miss.vision, describes = miss.describes, doesNot = miss.doesNot,
                visionPredictMs = miss.visionPredictMs, textPredictMs = miss.textPredictMs, availableBytes = miss.availableBytes,
            )))
        }
        // 2 is `serious`, 1 is `fair`: a fair phone is judged.
        assertIs<PreflightFinding.Slow>(judge(measured(visionMs = listOf(900.0), thermal = 1)))
        assertIs<PreflightFinding.Passed>(judge(measured(thermal = 3, warned = true)))
    }
}

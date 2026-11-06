package org.tenshu.file.proxy.jvm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

/**
 * Opt-in timing for P1a — asserts nothing. Run with
 * `TENSHU_BENCH=1 ./gradlew :file-service-proxy:jvmTest --tests '*ThumbnailLadderBenchmarkTest*'`
 * and read the `[bench]` lines in the test output. A 12 MP (4000 × 3000) photo through the real
 * upload op, the still ladder rendered rung by rung and then from one decode.
 */
class ThumbnailLadderBenchmarkTest {

    @Test
    fun uploadLadderTimings() = runTest(timeout = 10.minutes) {
        if (System.getenv("TENSHU_BENCH") != "1") return@runTest
        val bytes = LadderFixtures.photo(4_000, 3_000)
        println("[bench] fixture ${bytes.size / 1024} KB JPEG, 4000x3000")
        repeat(WARMUP) {
            LadderFixtures.upload(backgroundScope, "p.jpg", bytes, ladder = false)
            LadderFixtures.upload(backgroundScope, "p.jpg", bytes, ladder = true)
        }
        val perRung = (1..ROUNDS).map {
            measureTime { LadderFixtures.upload(backgroundScope, "p.jpg", bytes, ladder = false) }.inWholeMilliseconds
        }.sorted()
        val ladder = (1..ROUNDS).map {
            measureTime { LadderFixtures.upload(backgroundScope, "p.jpg", bytes, ladder = true) }.inWholeMilliseconds
        }.sorted()
        println("[bench] whole upload op, per-rung GetPreview: median ${perRung[ROUNDS / 2]} ms  $perRung")
        println("[bench] whole upload op, one-decode ladder:   median ${ladder[ROUNDS / 2]} ms  $ladder")

        val renderer = JvmThumbnailLadderRenderer()
        val rungs = org.tenshu.file.proxy.ThumbnailClasses.STILLS.map {
            org.tenshu.file.proxy.ThumbnailLadderRenderer.Rung(
                it.px, org.tenshu.core.file.GetPreview.Params.FORMAT_JPEG,
                org.tenshu.file.impl.ThumbnailClassMinter.STEP_DOWN_QUALITIES, it.maxBytes,
            )
        }
        val renderOnly = (1..ROUNDS).map { measureTime { renderer.render("p.jpg", bytes, rungs) }.inWholeMilliseconds }.sorted()
        println("[bench] ladder render alone:                 median ${renderOnly[ROUNDS / 2]} ms  $renderOnly")
    }

    private companion object {
        const val WARMUP = 2
        const val ROUNDS = 7
    }
}

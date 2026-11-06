package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import org.tenshu.mia.askMia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole query pipeline over the op wire: utterance → plan → chain → validator →
 * execution → a materialised [MiaAnswer].
 *
 * ⚠⚠ **Over the wire on purpose, and the assertions read the fields.** A composite that
 * an op *returns* is built by the **proxy**, through the `dtoFactory` in
 * `miaServiceCommonModule` — and a missing registration there is invisible to every unit
 * test, kills the op at runtime, and (because callers fold failures) looks like "nothing
 * found". Reading the materialised fields is what proves the composite was built at all.
 */
class AskMiaOpTest {

    /** 15 July 2026, 12:00 UTC — the clock every utterance below is resolved against. */
    private val now = 1_784_116_800_000L
    private val zone = "UTC"

    private suspend fun FileService.upload(name: String, capturedAtMs: Long) {
        assertTrue(
            defaultFileSystem.uploadFile(
                "/", name, byteArrayOf(1, 2, 3), sourceModifiedAt = capturedAtMs,
            ) is TResult.Success,
            "seeding /$name should succeed",
        )
    }

    private suspend fun answerOf(utterance: String, proxy: org.tenshu.core.common.TestProxy): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, zone)
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun MiaAnswer.paths(): List<String> =
        hits.toList().filterIsInstance<MiaHit>().map { it.targetKey }

    @Test
    fun aNameSearchCitesRealRows() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.fileService().upload("invoice-2026.pdf", now)
        proxy.fileService().upload("holiday.jpg", now)

        val answer = answerOf("find the invoice", proxy)

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf("/invoice-2026.pdf"), answer.paths())
        // MQ-D3's explainability half — the user can check what was searched.
        assertTrue(answer.explanation.get().contains("invoice"), answer.explanation.get())
    }

    @Test
    fun aPhotoSearchHonoursTheResolvedWindow() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val insideLastSummer = 1_754_000_000_000L // 1 Aug 2025
        val thisYear = now - 1_000

        proxy.fileService().upload("beach.jpg", insideLastSummer)
        proxy.fileService().upload("recent.jpg", thisYear)

        val answer = answerOf("photos from last summer", proxy)

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf("/beach.jpg"), answer.paths())
        assertTrue(answer.explanation.get().contains("summer 2025"), answer.explanation.get())
    }

    @Test
    fun theTimestampSurvivesTheRoundTrip() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val capturedAt = 1_754_000_000_000L
        proxy.fileService().upload("beach.jpg", capturedAt)

        val hit = answerOf("photos from last summer", proxy)
            .hits.toList().filterIsInstance<MiaHit>().single()

        assertEquals(capturedAt, hit.at.get())
        assertEquals(File.ID, hit.targetType)
        assertEquals("beach.jpg", hit.title.get())
    }

    @Test
    fun understoodButEmptyIsAnAnswer_notANoMatch() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.fileService().upload("holiday.jpg", now)

        val answer = answerOf("find the invoice", proxy)

        // ⚠⚠ The distinction MQ-D4 exists to protect: Mia understood the question and
        // there is genuinely nothing. Collapsing this into `noMatch` would tell the user
        // their sentence was wrong when it was fine.
        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertTrue(answer.paths().isEmpty())
    }

    @Test
    fun anUnderstoodNothingSaysWhatMiaCanDo() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val answer = answerOf("show me my files", proxy)

        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertTrue(answer.paths().isEmpty())
        assertTrue(answer.explanation.get().contains("photos"), answer.explanation.get())
    }

    @Test
    fun theAnswerIsCappedByThePlansLimit() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        repeat(25) { index -> proxy.fileService().upload("report-$index.pdf", now) }

        val answer = answerOf("find report", proxy)

        // MQ-D10's cost bound is structural: the lowered chain carries a `take`, so no
        // plan can ask the proxy to materialise the whole realm.
        assertEquals(org.tenshu.mia.plan.QueryPlanner.DEFAULT_LIMIT, answer.paths().size)
    }

    @Test
    fun anUnknownTimeZoneStillAnswers() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.fileService().upload("invoice.pdf", now)

        val result = proxy.miaService().askMia("find the invoice", now, "Not/AZone")
        val answer = (result as TResult.Success<*>).value as MiaAnswer

        // ⚠ A wrong-by-hours window is correctable; a failed turn tells the user nothing.
        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf("/invoice.pdf"), answer.paths())
    }
}

package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.sync.DeclarableContent
import org.tenshu.sync.common.ContentDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No emit ever races the pass: every stamp stays put, so nothing is revalidated. */
private object NoRacingEmits : ContentDeclarationBackfill.EmitGuard {
    override suspend fun stampOf(contentId: String): Long = 0
    override suspend fun currentSequence(): Long = 0
    override suspend fun currentContentOf(holder: DataId, propertyId: String): String? = null
}

/**
 * The declaration backfill (`content-plane-refcount.md` §9.7). Two properties carry this class, and
 * both fail quietly if they are wrong: the **batching**, which stalls the pass for ever if it can
 * produce a request the relay refuses, and the **marker**, which strands a realm for ever if it
 * latches over a degraded run.
 *
 * ⭐ The pass describes ref sets and declares no edge (`content-plane-cr2-b3-wire-spec.md` §3.4/§5).
 */
class ContentDeclarationBackfillTest {

    private class Harness(
        val found: DeclarableContent.Result,
        val refsByContent: Map<String, List<String>> = emptyMap(),
        /**
         * What the relay says about a manifest the fetch did not return. Default `false`, its 404: the
         * "never emitted" case a test here means by a content with no refs, unless it says otherwise.
         */
        val manifestPresent: (String) -> Boolean? = { false },
        /** The repair hook. Null, the platform that wires none, unless a test is about it. */
        val repairAbsent: (suspend (DataId, String, String) -> Boolean)? = null,
        val acceptDescribe: (List<ContentDescription>) -> Boolean = { true },
        var alreadyComplete: Boolean = false,
        /** Default `true` — the settled device every test assumes unless it is about catch-up. */
        var caughtUp: Boolean = true,
    ) {
        /** Every describe request, in order. */
        val batches = mutableListOf<List<ContentDescription>>()
        var markedComplete: Boolean? = null
        val reported = mutableListOf<String>()

        fun backfill() = ContentDeclarationBackfill(
            declarableContent = { found },
            chunkRefsOf = { contentId -> refsByContent[contentId] },
            manifestPresent = { contentId -> manifestPresent(contentId) },
            repairAbsent = repairAbsent,
            manifestRefOf = { contentId -> "manifest-of-$contentId" },
            describe = { contents -> batches += contents; acceptDescribe(contents) },
            isCaughtUp = { caughtUp },
            emitGuard = NoRacingEmits,
            readComplete = { alreadyComplete },
            writeComplete = { markedComplete = it },
            report = { reported += it },
        )
    }

    private val context = TestProxy().tContext

    /**
     * ⚠⚠ The line is the only evidence a device pass leaves, and the runbook reads it. Rows the walk
     * could not ask about are in no other number on it: not in the descriptions, not in "without refs".
     */
    @Test
    fun rowsTheWalkSkippedAreOnTheCompletionLine_andInTheOutcome() = runTest {
        val h = Harness(
            found = DeclarableContent.Result(
                entries = listOf(entry("h1", "c1")),
                walkComplete = true,
                rowsRead = 40,
                skipped = DeclarableContent.Skipped(undecodable = 3, unkeyed = 1, unresolvedProperties = 2),
            ),
            refsByContent = mapOf("c1" to listOf("blob-a")),
        )

        val outcome = h.backfill().run()

        assertEquals(4, outcome.rowsSkipped)
        assertEquals(2, outcome.propertiesUnresolved)
        val line = h.reported.last()
        assertTrue("from 40 rows" in line, line)
        assertTrue("4 ROWS SKIPPED UNDESCRIBED (3 would not decode, 1 had no DataId)" in line, line)
        assertTrue("2 PROPERTIES SKIPPED UNDESCRIBED" in line, line)
    }

    /** A walk that skipped every row finds nothing, so "nothing to describe" must say so too. */
    @Test
    fun aWalkThatSkippedEverythingDoesNotReadAsAnEmptyRealm() = runTest {
        val h = Harness(
            found = DeclarableContent.Result(
                entries = emptyList(),
                walkComplete = true,
                rowsRead = 12,
                skipped = DeclarableContent.Skipped(undecodable = 12),
            ),
        )

        val outcome = h.backfill().run()

        assertEquals(12, outcome.rowsSkipped)
        assertTrue("12 ROWS SKIPPED UNDESCRIBED" in h.reported.last(), h.reported.last())
    }

    @Test
    fun aCleanWalkSaysHowMuchItRead_andNamesNoSkips() = runTest {
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true, rowsRead = 7),
            refsByContent = mapOf("c1" to listOf("blob-a")),
        )

        h.backfill().run()

        val line = h.reported.last()
        assertTrue("from 7 rows" in line, line)
        assertFalse("SKIPPED" in line, line)
    }

    /**
     * ⚠⚠ One manifest fetch that never answers must not suspend the pass for ever, silently. It must
     * move on, say so, and NOT record itself complete: a timed-out content went undescribed by
     * accident, not because it has no refs.
     */
    @Test
    fun aManifestFetchThatNeverAnswersIsTimedOut_reported_andThePassIsNotComplete() = runTest {
        val reported = mutableListOf<String>()
        var marked: Boolean? = null
        val backfill = ContentDeclarationBackfill(
            declarableContent = {
                DeclarableContent.Result(listOf(entry("h1", "hangs"), entry("h2", "fine")), walkComplete = true, rowsRead = 2)
            },
            chunkRefsOf = { contentId ->
                if (contentId == "hangs") kotlinx.coroutines.awaitCancellation() else listOf("blob-a")
            },
            manifestPresent = { false },
            repairAbsent = null,
            manifestRefOf = { "manifest-of-$it" },
            describe = { true },
            isCaughtUp = { true },
            emitGuard = NoRacingEmits,
            readComplete = { false },
            writeComplete = { marked = it },
            report = { reported += it },
        )

        val outcome = backfill.run()

        assertFalse(outcome.complete, "a timed-out fetch is 'did not see everything'")
        assertEquals(null, marked, "and the marker is not written")
        assertEquals(1, outcome.contentsDescribed, "the pass moved on and described the content that answered")
        assertTrue("1 MANIFEST FETCHES GOT NO ANSWER" in reported.last(), reported.last())
        assertTrue("NOT recorded complete" in reported.last(), reported.last())
    }

    /**
     * ⚠⚠ A null from the fetch is not "no manifest". When the relay could not be asked either (a 5xx, a
     * dropped connection), the content went undescribed by accident and the pass must
     * run again. Only the relay's own 404 is the legitimate "never emitted".
     */
    @Test
    fun aFetchFailureTheRelayCouldNotExplainKeepsThePassIncomplete_aRelay404DoesNot() = runTest {
        val found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true, rowsRead = 1)

        val unknown = Harness(found, manifestPresent = { null })
        assertFalse(unknown.backfill().run().complete, "the relay could not be asked, so nothing is known")
        assertEquals(null, unknown.markedComplete)
        assertTrue("1 MANIFEST FETCHES GOT NO ANSWER" in unknown.reported.last(), unknown.reported.last())

        val absent = Harness(found, manifestPresent = { false })
        assertTrue(absent.backfill().run().complete, "a 404 is a content that never existed")
        assertEquals(true, absent.markedComplete)
    }

    /**
     * The relay holds the manifest and the first fetch returned nothing: one more fetch decides. A
     * transient failure resolves; a manifest that will not parse is reported and must NOT block the
     * marker, or one unreadable manifest re-runs the whole pass for ever.
     */
    @Test
    fun aManifestTheRelayHoldsIsFetchedAgain_andAnUnreadableOneIsReportedWithoutBlocking() = runTest {
        val found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true, rowsRead = 1)
        var fetches = 0
        val reported = mutableListOf<String>()
        fun backfill(secondFetch: List<String>?) = ContentDeclarationBackfill(
            declarableContent = { found },
            chunkRefsOf = { if (fetches++ == 0) null else secondFetch },
            manifestPresent = { true },
            repairAbsent = null,
            manifestRefOf = { "manifest-of-$it" },
            describe = { true },
            isCaughtUp = { true },
            emitGuard = NoRacingEmits,
            readComplete = { false },
            writeComplete = { },
            report = { reported += it },
        )

        val transient = backfill(secondFetch = listOf("blob-a")).run()
        assertEquals(2, transient.refsDescribed, "the second fetch resolved it: chunk + manifest")
        assertEquals(0, transient.withoutRefs)
        assertTrue(transient.complete)

        fetches = 0
        val unreadable = backfill(secondFetch = null).run()
        assertEquals(1, unreadable.withoutRefs)
        assertTrue(unreadable.complete, "permanent, so it must not re-run the pass for ever")
        assertTrue("1 MANIFESTS THE RELAY HOLDS COULD NOT BE READ" in reported.last(), reported.last())
    }

    /**
     * ⚠⚠ A relay that has stopped answering ends the pass: nothing after a run of unanswered fetches
     * can succeed, and describing on would send every remaining description without its refs. An
     * isolated failure between answers must NOT stop it.
     */
    @Test
    fun aRunOfUnansweredFetchesStopsThePass_andDeclaresNothing_isolatedOnesDoNot() = runTest {
        val n = ContentDeclarationBackfill.MAX_UNANSWERED_IN_A_ROW
        val entries = (1..(n * 4)).map { entry("h$it", "c$it") }

        val gone = Harness(
            found = DeclarableContent.Result(entries, walkComplete = true, rowsRead = entries.size),
            manifestPresent = { null },
        )
        val outcome = gone.backfill().run()
        assertFalse(outcome.complete)
        assertEquals(null, gone.markedComplete)
        assertTrue(gone.batches.isEmpty(), "nothing is declared once the relay is plainly gone")
        assertTrue("STOPPED after $n of ${entries.size} manifests" in gone.reported.last(), gone.reported.last())

        // A few isolated failures, fewer than n in total: never n in a row in the scan or in the
        // retry, so the pass runs to its end.
        val isolated = entries.filterIndexed { i, _ -> i % 5 == 0 }.map { it.contentId }.toSet()
        assertTrue(isolated.size < n)
        val flaky = Harness(
            found = DeclarableContent.Result(entries, walkComplete = true, rowsRead = entries.size),
            refsByContent = entries.filter { it.contentId !in isolated }.associate { it.contentId to listOf("blob-${it.contentId}") },
            manifestPresent = { null },
        )
        val went = flaky.backfill().run()
        assertFalse(went.complete, "unanswered fetches still block completion")
        assertEquals(entries.size - isolated.size, went.contentsDescribed, "but the pass ran to its end and described every content it could resolve")

        // ⚠ Many NON-consecutive failures in the scan, and a relay that is gone by the retry: the
        // retry must stop after n in a row too, not work through every one of them.
        val everyOther = entries.filterIndexed { i, _ -> i % 2 == 0 }.map { it.contentId }.toSet()
        var fetches = 0
        val lostLater = Harness(
            found = DeclarableContent.Result(entries, walkComplete = true, rowsRead = entries.size),
            refsByContent = entries.filter { it.contentId !in everyOther }.associate { it.contentId to listOf("blob-${it.contentId}") },
            manifestPresent = { fetches++; null },
        )
        val stopped = lostLater.backfill().run()
        assertFalse(stopped.complete)
        assertTrue(lostLater.batches.isEmpty(), "stopped in the retry, so nothing is declared")
        assertTrue("STOPPED while retrying ($n of ${everyOther.size})" in lostLater.reported.last(), lostLater.reported.last())
    }

    /**
     * ⚠⚠ A transient failure must not cost a whole second pass. What got no answer is tried once more
     * at the end: a fetch that works then is resolved and described, and the pass completes. One that
     * still gets no answer keeps the pass incomplete.
     */
    @Test
    fun anUnansweredFetchIsRetriedOnceAtTheEnd_andOnlyAStillUnansweredOneBlocksCompletion() = runTest {
        val found = DeclarableContent.Result(
            listOf(entry("h1", "blip"), entry("h2", "fine"), entry("h3", "down")),
            walkComplete = true, rowsRead = 3,
        )
        fun backfill(fetch: suspend (String, Int) -> List<String>?, reported: MutableList<String>, marked: (Boolean) -> Unit): ContentDeclarationBackfill {
            val attempts = mutableMapOf<String, Int>()
            return ContentDeclarationBackfill(
                declarableContent = { found },
                chunkRefsOf = { id ->
                    val attempt = (attempts[id] ?: 0) + 1
                    attempts[id] = attempt
                    fetch(id, attempt)
                },
                manifestPresent = { null },   // the relay cannot be asked while the fetch is failing
                repairAbsent = null,
                manifestRefOf = { "manifest-of-$it" },
                describe = { true },
                isCaughtUp = { true },
                emitGuard = NoRacingEmits,
                readComplete = { false },
                writeComplete = marked,
                report = { reported += it },
            )
        }

        // "blip" fails once and then works; nothing else fails.
        val lines = mutableListOf<String>()
        var marked: Boolean? = null
        val recovered = backfill(
            fetch = { id, attempt -> if ((id == "blip" && attempt == 1) || id == "down" && attempt == 1) null else listOf("blob-$id") },
            reported = lines, marked = { marked = it },
        ).run()
        assertTrue(recovered.complete, "both transient failures resolved on the retry")
        assertEquals(true, marked)
        assertEquals(0, recovered.withoutRefs, "and their refs were described, not dropped")
        assertTrue(lines.any { "retried 2 unanswered manifests: 2 resolved, 0 not on the relay, 0 still unanswered" in it }, lines.toString())

        // "down" never answers: the retry must not paper over it.
        val lines2 = mutableListOf<String>()
        var marked2: Boolean? = null
        val stillDown = backfill(
            fetch = { id, attempt -> if (id == "down" || (id == "blip" && attempt == 1)) null else listOf("blob-$id") },
            reported = lines2, marked = { marked2 = it },
        ).run()
        assertFalse(stillDown.complete)
        assertEquals(null, marked2)
        assertTrue("1 MANIFEST FETCHES GOT NO ANSWER" in lines2.last(), lines2.last())
    }

    /**
     * On the retry the relay may answer the probe with "I hold it" while the fetch still returns
     * nothing: an unreadable manifest. That is permanent, so it is reported and must not block the
     * marker, nor count toward the unreachable-relay cutoff.
     */
    @Test
    fun aManifestTheRelayHoldsOnTheRetryButCannotBeReadIsReported_notLeftUnanswered() = runTest {
        var probes = 0
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true, rowsRead = 1),
            // The scan's probe could not ask; the retry's probe is answered: the relay holds it.
            manifestPresent = { if (probes++ == 0) null else true },
        )

        val outcome = h.backfill().run()

        assertTrue(outcome.complete, "an unreadable manifest is permanent and must not re-run the pass")
        assertEquals(true, h.markedComplete)
        assertTrue("1 MANIFESTS THE RELAY HOLDS COULD NOT BE READ" in h.reported.last(), h.reported.last())
        assertFalse("GOT NO ANSWER" in h.reported.last(), h.reported.last())
    }

    /**
     * ⚠ "The relay holds it" followed by a fetch that TIMES OUT is a degraded read, not an unreadable
     * manifest. Unreadable does not block the marker, so folding the two would latch the pass over
     * content described without its refs.
     */
    @Test
    fun aHeldManifestWhoseSecondFetchTimesOutStaysUnanswered_notUnreadable() = runTest {
        var fetches = 0
        val reported = mutableListOf<String>()
        var marked: Boolean? = null
        val outcome = ContentDeclarationBackfill(
            declarableContent = { DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true, rowsRead = 1) },
            // First fetch completes with nothing; every later one never answers.
            chunkRefsOf = { if (fetches++ == 0) null else kotlinx.coroutines.awaitCancellation() },
            manifestPresent = { true },
            repairAbsent = null,
            manifestRefOf = { "manifest-of-$it" },
            describe = { true },
            isCaughtUp = { true },
            emitGuard = NoRacingEmits,
            readComplete = { false },
            writeComplete = { marked = it },
            report = { reported += it },
        ).run()

        assertFalse(outcome.complete)
        assertEquals(null, marked)
        assertTrue("1 MANIFEST FETCHES GOT NO ANSWER" in reported.last(), reported.last())
        assertFalse("COULD NOT BE READ" in reported.last(), reported.last())
    }

    /**
     * ⚠⚠ A content the relay answers 404 for is handed to the repair; one whose fetch merely got no
     * answer is NOT. The first is "no manifest there", which on the uploading device is an upload that
     * never finished. The second says nothing about the relay, and a repair on it would re-upload
     * content that is very likely present.
     */
    @Test
    fun aContentWithNoManifestOnTheRelayIsHandedToRepair_anUnansweredOneIsNot() = runTest {
        val asked = mutableListOf<String>()
        val h = Harness(
            found = DeclarableContent.Result(
                listOf(entry("h1", "gone", "tenshu/file/data"), entry("h2", "silent", "tenshu/file/data"), entry("h3", "fine")),
                walkComplete = true, rowsRead = 3,
            ),
            refsByContent = mapOf("fine" to listOf("blob-fine")),
            manifestPresent = { contentId -> if (contentId == "gone") false else null },
            repairAbsent = { holder, propertyId, contentId ->
                asked += "${(holder as DataId.StringDataId).keyValue.get()}|$propertyId|$contentId"
                true
            },
        )

        val outcome = h.backfill().run()

        assertEquals(listOf("h1|tenshu/file/data|gone"), asked)
        assertEquals(1, outcome.handedToRepair)
        assertTrue("1 with NO MANIFEST ON THE RELAY handed to repair" in h.reported.last(), h.reported.last())
        assertFalse(outcome.complete, "the unanswered one blocks")
    }

    /**
     * ⚠⚠ "Handed to repair" is not "on the relay": the upload can be parked, or land and have its
     * declare refused, and this pass described the content with no refs. A pass that repaired never
     * latches; the next one, which finds the manifest and nothing to hand over, does.
     */
    @Test
    fun aPassThatHandedContentToRepairDoesNotLatch_theNextOneThatFindsItDoes() = runTest {
        val onRelay = mutableMapOf<String, List<String>>()
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "gone", "tenshu/file/data")), walkComplete = true, rowsRead = 1),
            refsByContent = onRelay,
            manifestPresent = { it in onRelay },
            repairAbsent = { _, _, _ -> true },
        )

        val first = h.backfill().run()
        assertEquals(1, first.handedToRepair)
        assertFalse(first.complete)
        assertEquals(null, h.markedComplete)
        assertTrue("the next pass confirms them" in h.reported.last(), h.reported.last())

        onRelay["gone"] = listOf("blob-gone")
        val second = h.backfill().run()
        assertEquals(0, second.handedToRepair)
        assertTrue(second.complete, h.reported.last())
        assertEquals(true, h.markedComplete)
    }

    /**
     * ⚠ A repair that throws has not journalled anything, so nothing else will retry it. The pass must
     * stay incomplete, or the marker closes the only door.
     */
    @Test
    fun aRepairThatThrowsKeepsThePassIncomplete() = runTest {
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "gone", "tenshu/file/data")), walkComplete = true, rowsRead = 1),
            manifestPresent = { false },
            repairAbsent = { _, _, _ -> error("could not resolve the target") },
        )

        val outcome = h.backfill().run()

        assertFalse(outcome.complete)
        assertEquals(null, h.markedComplete)
        assertTrue("1 REPAIRS FAILED" in h.reported.last(), h.reported.last())
    }

    /** The pass says what it found before the long part, and how far it is, so a stall is visible. */
    @Test
    fun aLongPassReportsTheWalkAndItsProgress() = runTest {
        val n = ContentDeclarationBackfill.PROGRESS_EVERY * 2 + 10
        val h = Harness(
            found = DeclarableContent.Result((1..n).map { entry("h$it", "c$it") }, walkComplete = true, rowsRead = n),
            refsByContent = (1..n).associate { "c$it" to listOf("blob-$it") },
        )

        h.backfill().run()

        assertTrue("walked: $n references to $n contents, from $n rows; resolving manifests" in h.reported.first(), h.reported.first())
        assertEquals(
            listOf("resolved 500 of $n manifests", "resolved 1000 of $n manifests"),
            h.reported.filter { "resolved " in it }.map { it.substringAfter("-> ").substringBefore(" (") },
        )
        assertTrue(h.reported.any { "described batch 1 of " in it }, "several requests, so each batch is named")
        assertTrue("recorded complete" in h.reported.last() && "NOT" !in h.reported.last(), h.reported.last())
    }

    /**
     * ⚠ A counted zero and "this walk does not count" must not print alike: the bump exists so that
     * every completed walk states a number, and an empty realm's number is 0.
     */
    @Test
    fun aCountedEmptyWalkSaysZeroRows_andAnUncountedOneSaysSo() = runTest {
        val counted = Harness(DeclarableContent.Result(emptyList(), walkComplete = true, rowsRead = 0))
        counted.backfill().run()
        assertTrue("from 0 rows" in counted.reported.last(), counted.reported.last())

        val uncounted = Harness(DeclarableContent.Result(emptyList(), walkComplete = true))
        uncounted.backfill().run()
        assertTrue("rows not counted" in uncounted.reported.last(), uncounted.reported.last())
    }

    private fun entry(holder: String, content: String, property: String = "p") =
        DeclarableContent.Entry(DataId.StringDataId.create(context, "tenshu/test/holder", holder), property, content)

    @Test
    fun theManifestRefIsDescribedAlongsideTheChunks() = runTest {
        // ⚠ The manifest is a blob of this content too. Freeing the chunks and leaving it behind
        // would just move the orphan.
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true),
            refsByContent = mapOf("c1" to listOf("chunk-1", "chunk-2")),
        )
        val outcome = h.backfill().run()

        assertEquals(
            listOf("chunk-1", "chunk-2", "manifest-of-c1"),
            h.batches.single().single().refs,
        )
        assertTrue(outcome.complete)
        assertEquals(true, h.markedComplete)
    }

    @Test
    fun aManifestThatWillNotResolveIsNotDescribed_andDoesNotBlockCompleteness() = runTest {
        // ⚠⚠ Blocking on it would make the marker unlatchable on every device for ever. A thumbnail
        // class has a derivable content id whether or not it was ever emitted, so a walk legitimately
        // finds content that never existed, and its manifest is *correctly* unresolvable.
        //
        // ⚠ Nothing to describe (an empty ref set is refused by the relay), still counted and reported.
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true),
            refsByContent = emptyMap(),
        )
        val outcome = h.backfill().run()

        assertTrue(h.batches.isEmpty(), "no description without refs; got ${h.batches}")
        assertEquals(1, outcome.withoutRefs, "still counted, and still reported")
        assertTrue(outcome.complete, "an undescribable thumbnail is not an unfinished walk")
        assertEquals(true, h.markedComplete)
    }

    @Test
    fun anIncompleteWalkDescribesWhatItFound_andDoesNotLatch() = runTest {
        // ⚠⚠ The asymmetry with the veto. A short backfill describes FEWER contents, so fewer things
        // become candidates — a leak, never a loss — so it is right to describe what it has. It is
        // wrong to record itself done, because the pass runs once by design.
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = false),
            refsByContent = mapOf("c1" to listOf("chunk-1")),
        )
        val outcome = h.backfill().run()

        assertEquals(1, outcome.contentsDescribed, "it must still describe what it walked")
        assertFalse(outcome.complete)
        assertEquals(null, h.markedComplete)
    }

    @Test
    fun aRefusedBatchDoesNotStopTheOthers_norLatchTheMarker() = runTest {
        val h = Harness(
            found = DeclarableContent.Result(
                (1..600).map { entry("h$it", "c$it") },
                walkComplete = true,
            ),
            refsByContent = (1..600).associate { "c$it" to listOf("chunk-$it") },
            acceptDescribe = { contents -> contents.first().contentId != "c1" },
        )
        val outcome = h.backfill().run()

        assertTrue(h.batches.size >= 2, "600 descriptions must span more than one request")
        assertTrue(outcome.contentsDescribed > 0, "the batches that landed must count")
        assertFalse(outcome.complete)
        assertEquals(null, h.markedComplete)
    }

    @Test
    fun noBatchCanExceedTheRelaysCeilings() = runTest {
        // ⚠⚠ The failure this guards is SILENT: a batch over the relay's 8,192-ref request cap is a
        // 400, `allAccepted` stays false, and the marker never latches — a backfill that runs for
        // ever and finishes nothing. Batching by description count alone produces exactly that,
        // because 64 descriptions of a large file's ~1,000 refs is 64,000.
        val h = Harness(
            found = DeclarableContent.Result((1..40).map { entry("h$it", "c$it") }, walkComplete = true),
            // 1,000 refs each: any count-only batching over 8 would breach the request ceiling.
            refsByContent = (1..40).associate { c -> "c$c" to (1..1_000).map { "chunk-$c-$it" } },
        )
        h.backfill().run()

        assertTrue(h.batches.isNotEmpty())
        h.batches.forEach { batch ->
            assertTrue(
                batch.size <= ContentDeclarationBackfill.MAX_DESCRIPTIONS_PER_REQUEST,
                "batch of ${batch.size} descriptions exceeds the relay's per-request cap",
            )
            assertTrue(
                batch.sumOf { it.refs.size } <= ContentDeclarationBackfill.MAX_TOTAL_REFS,
                "batch of ${batch.sumOf { it.refs.size }} refs exceeds the relay's request cap",
            )
        }
        assertEquals(40, h.batches.sumOf { it.size }, "every description must land in some batch")
    }

    @Test
    fun aContentObjectWithMoreRefsThanOneDescriptionAllowsIsNotDescribed() = runTest {
        // ⚠ Sending them would 400 the batch and stall the pass for ever on a file nobody can do
        // anything about. Left undescribed it simply cannot be freed.
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true),
            refsByContent = mapOf(
                "c1" to (1..ContentDeclarationBackfill.MAX_REFS_PER_DESCRIPTION + 10).map { "chunk-$it" },
            ),
        )
        val outcome = h.backfill().run()

        assertTrue(h.batches.isEmpty(), "a description carrying those refs would be refused")
        assertEquals(1, outcome.withoutRefs, "counted and reported")
        // ⚠ As above: undescribable is not unfinished.
        assertTrue(outcome.complete)
    }

    @Test
    fun oneManifestFetchPerContentId_notPerHolder() = runTest {
        // ⚠ Five entries share one contentId (a copy, or a trash/restore). The manifest fetch is
        // this pass's only expensive step, so multiplying it by the sharing factor is the
        // difference between one GET per file and one per reference.
        var fetches = 0
        val found = DeclarableContent.Result(
            (1..5).map { entry("h$it", "shared") },
            walkComplete = true,
        )
        val backfill = ContentDeclarationBackfill(
            declarableContent = { found },
            chunkRefsOf = { fetches++; listOf("chunk-1") },
            manifestPresent = { false },
            repairAbsent = null,
            manifestRefOf = { "m" },
            describe = { true },
            isCaughtUp = { true },
            emitGuard = NoRacingEmits,
            readComplete = { false },
            writeComplete = { },
        )
        backfill.run()

        assertEquals(1, fetches)
    }

    // ---- The marker may not latch while the realm is still arriving --------------------------

    /**
     * ⚠⚠ A `TENSHU_STORAGE_FORMAT_VERSION` wipe empties the store; a pass that walked that
     * genuinely-but-transiently empty realm and latched would let the corpus re-sync *behind* the
     * latch, leaving every file undescribed and the sweep inert.
     */
    @Test
    fun anEmptyRealmDoesNotLatchWhileTheDeviceIsStillCatchingUp() = runTest {
        val h = Harness(
            found = DeclarableContent.Result(emptyList(), walkComplete = true),
            caughtUp = false,
        )

        val outcome = h.backfill().run()

        assertEquals("still catching up", outcome.skipped)
        assertEquals(false, outcome.complete, "a wiped store is not a finished realm")
        assertEquals(null, h.markedComplete, "and nothing may be written to the marker")
    }

    /** A settled, genuinely empty realm still latches. */
    @Test
    fun anEmptyRealmOnASettledDeviceStillLatches() = runTest {
        val h = Harness(
            found = DeclarableContent.Result(emptyList(), walkComplete = true),
            caughtUp = true,
        )

        val outcome = h.backfill().run()

        assertTrue(outcome.complete, "a settled device over a genuinely empty realm is done")
        assertEquals(true, h.markedComplete)
    }

    /**
     * ⚠⚠ The same defect on the non-empty path: a pass that
     * runs mid-catch-up sees *part* of the corpus, describes that part perfectly cleanly, and would
     * latch over the rest for ever.
     *
     * ⭐ It does not run at all, rather than declaring-without-latching. Declaring early buys
     * nothing — undeclared content is unreachable by the sweep by construction (§9.5), so it is already
     * safe — while a partial pass costs one manifest `GET` per content object **every slot** until
     * catch-up ends. Pinned so the expensive half cannot be reintroduced as a kindness.
     */
    @Test
    fun aCleanPartialWalkDoesNotEvenRunWhileTheDeviceIsStillCatchingUp() = runTest {
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true),
            refsByContent = mapOf("c1" to listOf("chunk-1")),
            caughtUp = false,
        )

        val outcome = h.backfill().run()

        assertEquals("still catching up", outcome.skipped)
        assertTrue(h.batches.isEmpty(), "no manifest fetches, no descriptions, until the realm settles")
        assertEquals(false, outcome.complete)
        assertEquals(null, h.markedComplete)
    }

    /**
     * The whole sequence: wiped realm → re-sync → settled. The pass must decline to latch on the
     * first run and actually describe on the second, or the realm stays undescribed for ever.
     */
    @Test
    fun afterTheRealmArrivesTheNextPassDescribesIt() = runTest {
        val wiped = Harness(
            found = DeclarableContent.Result(emptyList(), walkComplete = true),
            caughtUp = false,
        )
        assertEquals("still catching up", wiped.backfill().run().skipped, "run 1, mid-wipe: no latch")
        assertEquals(null, wiped.markedComplete)

        // The corpus has arrived and the backward walk reached the bottom.
        val settled = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true),
            refsByContent = mapOf("c1" to listOf("chunk-1")),
            caughtUp = true,
            alreadyComplete = false,   // ⚠ only true because run 1 declined to latch
        )
        val outcome = settled.backfill().run()

        assertEquals(listOf("chunk-1", "manifest-of-c1"), settled.batches.single().single().refs)
        assertTrue(outcome.complete)
        assertEquals(true, settled.markedComplete)
    }

    @Test
    fun aCompletedBackfillDoesNotRunAgain() = runTest {
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true),
            alreadyComplete = true,
        )
        val outcome = h.backfill().run()

        assertEquals("already backfilled", outcome.skipped)
        assertTrue(h.batches.isEmpty(), "the expensive pass must not re-run once it is done")
    }

    @Test
    fun anEmptyRealmLatchesOnlyIfTheWalkFinished() = runTest {
        val complete = Harness(DeclarableContent.Result(emptyList(), walkComplete = true))
        complete.backfill().run()
        assertEquals(true, complete.markedComplete)

        // ⚠⚠ An unreadable root walks to zero entries too, and latching there strands the realm.
        val degraded = Harness(DeclarableContent.Result(emptyList(), walkComplete = false))
        degraded.backfill().run()
        assertEquals(null, degraded.markedComplete)
    }

    private companion object {
        fun keyOf(holder: DataId): String = (holder as DataId.StringDataId).keyValue.get()

    }

    /** The pass describes a content and its refs — there is no reference handle to send. */
    @Test
    fun theBackfillDescribesTheRefs_andNoHandle() = runTest {
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1", property = "prop-a")), walkComplete = true),
            refsByContent = mapOf("c1" to listOf("chunk-1")),
        )

        val outcome = h.backfill().run()

        val described = h.batches.flatten().single()
        assertEquals(ContentDescription("c1", listOf("chunk-1", "manifest-of-c1")), described)
        assertFalse("reference root" in h.reported.last(), "the report counts no roots: ${h.reported.last()}")
        assertTrue(outcome.complete)
    }

    @Test
    fun twoPropertiesSharingOneContentId_areDescribedOnce() = runTest {
        // A description names content, not a reference: two properties sharing one `contentId` describe
        // the same refs, so one description says it all.
        val h = Harness(
            found = DeclarableContent.Result(
                listOf(entry("h1", "shared", property = "prop-a"), entry("h1", "shared", property = "prop-b")),
                walkComplete = true,
            ),
            refsByContent = mapOf("shared" to listOf("chunk-1")),
        )

        h.backfill().run()

        assertEquals(listOf("shared"), h.batches.flatten().map { it.contentId })
    }

    @Test
    fun oneContentReachedTwice_isDescribedOnce() = runTest {
        // A copy: the same content — one description, not one per holder.
        val h = Harness(
            found = DeclarableContent.Result(listOf(entry("h1", "c1"), entry("h2", "c1")), walkComplete = true),
            refsByContent = mapOf("c1" to listOf("chunk-1")),
        )

        h.backfill().run()

        assertEquals(1, h.batches.flatten().size)
    }

    // ---- An emit racing the pass ----

    /** A fake emit plane: one manifest per content, one stamp per content, and the content each holder names. */
    private class RacingEmits : ContentDeclarationBackfill.EmitGuard {
        val manifests = mutableMapOf<String, List<String>>()
        val stamps = mutableMapOf<String, Long>()
        val names = mutableMapOf<String, String>()
        /** Contents with an emit in progress — read as busy. */
        val busy = mutableSetOf<String>()

        /** One monotonic sequence, like `EmitKeyLocks`. */
        var sequence = 0L

        /** What an emit does: a new manifest (fresh CEK ⇒ new refs), and the content's stamp moves. */
        fun publish(contentId: String, refs: List<String>) {
            manifests[contentId] = refs
            stamps[contentId] = ++sequence
        }

        override suspend fun stampOf(contentId: String): Long? = if (contentId in busy) null else stamps[contentId] ?: 0
        override suspend fun currentSequence(): Long = sequence
        override suspend fun currentContentOf(holder: DataId, propertyId: String): String? = names[keyOf(holder)]
    }

    /** The last ref set described for each content id. */
    private fun lastDescribed(described: List<ContentDescription>): Map<String, List<String>> =
        described.associate { it.contentId to it.refs }

    private fun racingBackfill(
        emits: RacingEmits,
        entries: List<DeclarableContent.Entry>,
        declared: MutableList<ContentDescription>,
        unresolvable: Set<String> = emptySet(),
        duringFirstDeclare: () -> Unit,
    ): ContentDeclarationBackfill {
        var first = true
        return ContentDeclarationBackfill(
            declarableContent = { DeclarableContent.Result(entries, walkComplete = true) },
            chunkRefsOf = { contentId -> if (contentId in unresolvable) null else emits.manifests[contentId] },
            manifestPresent = { false },
            repairAbsent = null,
            manifestRefOf = { "manifest-of-$it" },
            describe = { contents ->
                // ⚠ The race, in the order that loses: the emit publishes and describes B while the pass
                // is on its way to describing the A it fetched earlier — so the pass's write lands LAST.
                if (first) {
                    first = false
                    duringFirstDeclare()
                }
                declared += contents
                true
            },
            isCaughtUp = { true },
            emitGuard = emits,
            readComplete = { false },
            writeComplete = { },
        )
    }

    @Test
    fun anEmitThatRepublishedWhileThePassRan_endsDescribedByTheLiveRefs() = runTest {
        val emits = RacingEmits().apply {
            manifests["c1"] = listOf("A")
            names["h1"] = "c1"
        }
        val declared = mutableListOf<ContentDescription>()
        val backfill = racingBackfill(emits, listOf(entry("h1", "c1")), declared) {
            emits.publish("c1", listOf("B"))
            declared += ContentDescription("c1", listOf("B", "manifest-of-c1")) // the emit's own describe
        }

        val outcome = backfill.run()

        assertEquals(
            listOf("B", "manifest-of-c1"),
            lastDescribed(declared)["c1"],
            "the LIVE chunk set B must end described — the stale A written over it leaves B sweepable",
        )
        assertTrue(outcome.complete)
    }

    @Test
    fun aHolderOverwrittenWhileThePassRan_endsNamingItsNewContent() = runTest {
        // An overwrite holds the OLD content's key, so its stamp moves too. The pass must end describing
        // what the holder names NOW, not the content it walked.
        val emits = RacingEmits().apply {
            manifests["c1"] = listOf("A")
            names["h1"] = "c1"
        }
        val declared = mutableListOf<ContentDescription>()
        val backfill = racingBackfill(emits, listOf(entry("h1", "c1")), declared) {
            emits.publish("c2", listOf("X"))
            emits.stamps["c1"] = ++emits.sequence // the overwrite held c1's key
            emits.names["h1"] = "c2"
            declared += ContentDescription("c2", listOf("X", "manifest-of-c2"))
        }

        backfill.run()

        assertEquals(listOf("X", "manifest-of-c2"), lastDescribed(declared)["c2"])
    }

    @Test
    fun withNoRacingEmit_nothingIsDescribedTwice() = runTest {
        val emits = RacingEmits().apply {
            manifests["c1"] = listOf("A")
            names["h1"] = "c1"
        }
        val declared = mutableListOf<ContentDescription>()

        racingBackfill(emits, listOf(entry("h1", "c1")), declared) {}.run()

        assertEquals(1, declared.size, "an unmoved stamp costs no second describe")
    }

    @Test
    fun anEmitThroughOneProperty_realignsEveryPropertySharingTheContent() = runTest {
        // ⚠⚠ The manifest is content-addressed, so an emit through p1 replaces it for p2 too. A stamp
        // per (content, property) would leave p2's stale refs looking current.
        val emits = RacingEmits().apply {
            manifests["c1"] = listOf("A")
            names["h1"] = "c1"
            names["h2"] = "c1"
        }
        val declared = mutableListOf<ContentDescription>()
        val backfill = racingBackfill(emits, listOf(entry("h1", "c1", "p1"), entry("h2", "c1", "p2")), declared) {
            emits.publish("c1", listOf("B"))
        }

        backfill.run()

        // One description per content, so "both properties realigned" is the content's last refs.
        assertEquals(listOf("B", "manifest-of-c1"), lastDescribed(declared)["c1"], "the live refs end described")
    }

    @Test
    fun aRaceWhoseRefsCannotBeReResolved_leavesThePassIncomplete() = runTest {
        // ⚠⚠ After a detected race a manifest exists, so an unresolvable re-fetch is a transient
        // failure — the pass must not latch over the stale description.
        val emits = RacingEmits().apply {
            manifests["c1"] = listOf("A")
            names["h1"] = "c1"
        }
        val unresolvable = mutableSetOf<String>()
        val declared = mutableListOf<ContentDescription>()
        var marked: Boolean? = null
        val backfill = ContentDeclarationBackfill(
            declarableContent = { DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true) },
            chunkRefsOf = { contentId -> if (contentId in unresolvable) null else emits.manifests[contentId] },
            manifestPresent = { false },
            repairAbsent = null,
            manifestRefOf = { "manifest-of-$it" },
            describe = { contents ->
                if (declared.isEmpty()) {
                    emits.publish("c1", listOf("B"))
                    unresolvable += "c1" // the relay read fails on the re-fetch
                }
                declared += contents
                true
            },
            isCaughtUp = { true },
            emitGuard = emits,
            readComplete = { false },
            writeComplete = { marked = it },
        )

        val outcome = backfill.run()

        assertFalse(outcome.complete)
        assertEquals(null, marked, "not latched — the pass runs again")
    }

    @Test
    fun aContentBusyThroughoutThePass_isNeverTakenAsSettled() = runTest {
        // ⚠⚠ Busy at the snapshot AND at the check: "null == null" must not read as "no emit overlapped" —
        // an emit was running the whole time, so what the pass described may already be stale. And bounded:
        // incomplete (the pass runs again), not a wait for ever.
        val emits = RacingEmits().apply {
            manifests["c1"] = listOf("A")
            names["h1"] = "c1"
            busy += "c1"
        }
        val declared = mutableListOf<ContentDescription>()

        assertFalse(racingBackfill(emits, listOf(entry("h1", "c1")), declared) {}.run().complete)
    }

    @Test
    fun anUnreadableHolderAfterARace_leavesThePassIncomplete() = runTest {
        // ⚠⚠ Null is "retired" OR "unreadable". After a detected race the pass may have written stale
        // refs over the emitter's, so a failed read must not let the marker latch.
        val emits = RacingEmits().apply {
            manifests["c1"] = listOf("A")
            names["h1"] = "c1"
        }
        val declared = mutableListOf<ContentDescription>()
        val backfill = racingBackfill(emits, listOf(entry("h1", "c1")), declared) {
            emits.publish("c1", listOf("B"))
            emits.names.remove("h1") // the read fails
        }

        assertFalse(backfill.run().complete)
    }

    @Test
    fun anOverwriteBetweenTheWalkAndTheDeclare_isCaught() = runTest {
        // ⚠⚠ The walk read h1 → c1, then an overwrite moved h1 on to c2 BEFORE any per-content stamp
        // was read. Such a snapshot already contains the overwrite, so the stale h1 → c1 description
        // would pass revalidation. The sequence is read before the walk, so the overwrite reads as moved.
        val emits = RacingEmits().apply {
            manifests["c1"] = listOf("A")
            names["h1"] = "c1"
        }
        val declared = mutableListOf<ContentDescription>()
        val backfill = ContentDeclarationBackfill(
            declarableContent = {
                val walked = DeclarableContent.Result(listOf(entry("h1", "c1")), walkComplete = true)
                // The overwrite lands right after the walk read h1, before the pass does anything else.
                emits.publish("c2", listOf("X"))
                emits.stamps["c1"] = ++emits.sequence
                emits.names["h1"] = "c2"
                declared += ContentDescription("c2", listOf("X", "manifest-of-c2"))
                walked
            },
            chunkRefsOf = { contentId -> emits.manifests[contentId] },
            manifestPresent = { false },
            repairAbsent = null,
            manifestRefOf = { "manifest-of-$it" },
            describe = { contents -> declared += contents; true },
            isCaughtUp = { true },
            emitGuard = emits,
            readComplete = { false },
            writeComplete = { },
        )

        backfill.run()

        assertEquals(listOf("X", "manifest-of-c2"), lastDescribed(declared)["c2"], "h1's live content ends described")
    }

    /**
     * Strictly serial, one manifest per round trip, a pass would cover at most ~21,000 manifests in a
     * 30-minute job. Fetches overlap [ContentDeclarationBackfill.FETCH_CONCURRENCY] wide, and the outcome is
     * the serial one.
     */
    @Test
    fun manifestFetchesOverlap_fourAtATime() = runTest {
        var inFlight = 0
        var peak = 0
        val ids = (1..10).map { "c$it" }
        var marked: Boolean? = null
        val backfill = ContentDeclarationBackfill(
            declarableContent = { DeclarableContent.Result(ids.map { entry("h-$it", it) }, walkComplete = true, rowsRead = ids.size) },
            chunkRefsOf = { contentId ->
                inFlight++
                peak = maxOf(peak, inFlight)
                kotlinx.coroutines.delay(100)
                inFlight--
                listOf("blob-$contentId")
            },
            manifestPresent = { true },
            repairAbsent = null,
            manifestRefOf = { "manifest-of-$it" },
            describe = { true },
            isCaughtUp = { true },
            emitGuard = NoRacingEmits,
            readComplete = { false },
            writeComplete = { marked = it },
        )

        val outcome = backfill.run()

        assertEquals(4, peak, "fetches overlap, four at a time")
        assertEquals(10, outcome.contentsDescribed)
        assertEquals(true, marked)
    }
}

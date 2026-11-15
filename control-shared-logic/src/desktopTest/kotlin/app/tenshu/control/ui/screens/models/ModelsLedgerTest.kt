package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Control knows of its models on Android and desktop, and what it tells a screen
 * (`ios-on-device-inference.md` §14.18.12): the two reads, the downloads this process runs, and
 * how each ended.
 *
 * ⚠⚠ The double moves only when a case moves it. A download ends when the case ends it, a stream
 * ticks when the case ticks it, and a read answers what the case last set: nothing lands because
 * it was asked for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsLedgerTest {

    private fun facts(id: String = "siglip", version: Int = 3, bytes: Long = 383) =
        ModelFacts(id, name = id.uppercase(), byteSize = bytes, modelVersion = version, capabilityIds = listOf("search"))

    private class Fixture(val scope: TestScope, val calls: FakeModelCalls, var storeAccess: StoreReadOnly? = null) {
        val ledger = ModelsLedger(calls, { storeAccess }, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler))
        val seen = mutableListOf<ModelsState>()

        init {
            scope.backgroundScope.launch { ledger.state.collect { seen += it } }
            // What a screen does when it opens: the ledger reads nothing by itself.
            scope.backgroundScope.launch { ledger.checkAgain() }
        }

        val now: ModelsState get() { scope.runCurrent(); return seen.last() }
        val rows get() = (now.availability as ModelsAvailability.Listed).models
        fun row(modelId: String = "siglip") = rows.single { it.modelId == modelId }
    }

    private fun TestScope.fixture(calls: FakeModelCalls = FakeModelCalls()) = Fixture(this, calls)

    /** Long enough for a stream that ended under a running call to be subscribed to again. */
    private fun TestScope.settle() {
        advanceTimeBy(600)
        runCurrent()
    }

    @Test
    fun nothingIsSaidOfTheListUntilBothReadsHaveAnswered() = runTest {
        val f = fixture(FakeModelCalls(ModelRead.Got(listOf(facts(version = 2))), ModelRead.Got(listOf(facts(), facts("other")))))

        assertEquals(ModelsAvailability.Loading, f.seen.firstOrNull()?.availability ?: ModelsAvailability.Loading)
        val listed = f.now.availability as ModelsAvailability.Listed

        assertEquals(ModelsAvailability.Loading, f.seen.first().availability)
        assertEquals(ModelsAvailability.Catalog.READ, listed.catalog)
        assertEquals(listOf("siglip", "other"), listed.models.map { it.modelId })
        assertTrue(f.row().updateAvailable)
        assertEquals(mapOf("siglip" to listOf("search"), "other" to listOf("search")), f.now.serves)
        // ⚠ One read for any number of screens: the second collector starts none.
        val again = mutableListOf<ModelsState>()
        backgroundScope.launch { f.ledger.state.collect { again += it } }
        runCurrent()
        assertEquals(1, f.calls.reads)
        assertEquals(listed, again.last().availability)
    }

    /**
     * ⚠⚠ A failed read of what is installed is that failure, in both its kinds, and never an empty
     * list: an empty list shows every installed model as absent, with a download offered.
     */
    @Test
    fun whatIsInstalledCouldNotBeReadIsThatAndNeverNothingInstalled() = runTest {
        val f = fixture(FakeModelCalls(installed = ModelRead.Failed, available = ModelRead.Got(listOf(facts()))))
        assertEquals(ModelsAvailability.InstalledUnreadable, f.now.availability)

        f.calls.installed = ModelRead.NoAnswer
        f.ledger.checkAgain()
        assertEquals(ModelsAvailability.NotConnected, f.now.availability)

        // ⚠ It leaves that state only when the read works. And a read that fails after one that
        // worked is the failure again: the rows it showed are not kept as if they were still known.
        f.calls.installed = ModelRead.Got(listOf(facts()))
        f.ledger.checkAgain()
        assertTrue(f.row().installed)
        f.calls.installed = ModelRead.Failed
        f.ledger.checkAgain()
        assertEquals(ModelsAvailability.InstalledUnreadable, f.now.availability)
    }

    /**
     * ⚠⚠ A catalog that never read is *unavailable*; one that read and then failed is *stale*, and
     * its rows stay. The refresh used to replace the list, dropping every model not installed.
     */
    @Test
    fun aCatalogThatFailsAfterAGoodReadKeepsItsRowsAndIsSaidToBeStale() = runTest {
        val f = fixture(FakeModelCalls(ModelRead.Got(listOf(facts("held"))), available = ModelRead.Failed))
        var listed = f.now.availability as ModelsAvailability.Listed
        assertEquals(ModelsAvailability.Catalog.UNAVAILABLE, listed.catalog)
        assertEquals(ModelSetup.Update.Unknown, (f.row("held").setup as ModelSetup.Held).update)

        f.calls.available = ModelRead.Got(listOf(facts("held"), facts("offered")))
        f.ledger.checkAgain()
        assertEquals(listOf("held", "offered"), f.rows.map { it.modelId })

        for (failure in listOf(ModelRead.Failed, ModelRead.NoAnswer)) {
            f.calls.available = failure
            f.ledger.checkAgain()
            listed = f.now.availability as ModelsAvailability.Listed
            assertEquals(ModelsAvailability.Catalog.STALE, listed.catalog)
            assertEquals(listOf("held", "offered"), listed.models.map { it.modelId })
            assertEquals("HELD", f.row("held").name, "the catalog's name is kept with it")
        }

        f.calls.available = ModelRead.Got(listOf(facts("held")))
        f.ledger.checkAgain()
        assertEquals(ModelsAvailability.Catalog.READ, (f.now.availability as ModelsAvailability.Listed).catalog)
        assertEquals(listOf("held"), f.rows.map { it.modelId })
    }

    /**
     * ⚠⚠ The row is *fetching* from the press, because the ledger's call is under way; the press
     * is answered when the proxy reports the download, not when it lands; and the model is
     * installed when the store says so. A screen that opens in the middle sees all of it.
     */
    @Test
    fun aDownloadIsARowFromThePressAnsweredWhenItIsUnderWayAndInstalledWhenTheStoreSaysSo() = runTest {
        val f = fixture(FakeModelCalls(available = ModelRead.Got(listOf(facts()))))
        assertEquals(ModelSetup.Absent::class, f.row().setup::class)

        val answer = async { f.ledger.download("siglip") }
        assertEquals(ModelSetup.Download(true, null, 383, null), f.row().setup.download)
        assertFalse(answer.isCompleted, "answered before anything said the download was under way")

        f.calls.begin("siglip")
        settle()
        assertFalse(answer.isCompleted, "a stream that has not ticked says nothing yet")
        f.calls.tick("siglip", 100, total = 400)
        assertEquals(ModelSetup.Download(true, 100, 400, null), f.row().setup.download)
        assertEquals(ModelActionResult.Accepted, answer.await())

        // A screen opened now.
        val opened = mutableListOf<ModelsState>()
        backgroundScope.launch { f.ledger.state.collect { opened += it } }
        runCurrent()
        assertEquals(100L, (opened.last().availability as ModelsAvailability.Listed).models.single().setup.download?.writtenBytes)

        // ⚠ Not installed because the call returned: installed because the store was read again.
        f.calls.installed = ModelRead.Got(listOf(facts()))
        f.calls.endStream("siglip")
        f.calls.running.getValue("siglip").complete(ModelDownloadOutcome.Landed)
        assertEquals(ModelSetup.Held::class, f.row().setup::class)
        assertNull(f.row().setup.download)
        assertEquals(listOf("siglip"), f.calls.downloads)
    }

    /**
     * ⚠⚠ A call that ends before the download was under way is the press's answer, typed, and is
     * recorded nowhere else: the row is as it was, and nothing is shown under it afterwards.
     */
    @Test
    fun aCallThatEndsBeforeItIsUnderWayIsThePresssAnswerAndNothingElse() = runTest {
        val cases = listOf(
            ModelDownloadOutcome.NoCatalog to ModelActionResult.Why.NoCatalog,
            ModelDownloadOutcome.UnknownModel to ModelActionResult.Why.UnknownModel,
            ModelDownloadOutcome.TransportFailed to ModelActionResult.Why.Said(ModelSetup.DownloadSaid.Failed(integrity = false)),
            ModelDownloadOutcome.IntegrityFailed to ModelActionResult.Why.Said(ModelSetup.DownloadSaid.Failed(integrity = true)),
            ModelDownloadOutcome.StoreNotOwned to ModelActionResult.Why.Said(ModelSetup.DownloadSaid.StoreRefused),
            ModelDownloadOutcome.StoreLockUnavailable to ModelActionResult.Why.Said(ModelSetup.DownloadSaid.StoreRefused),
        )
        cases.forEach { (outcome, why) ->
            val f = fixture(FakeModelCalls(available = ModelRead.Got(listOf(facts()))))
            f.now
            val answer = async { f.ledger.download("siglip") }
            runCurrent()
            f.calls.running.getValue("siglip").complete(outcome)

            assertEquals(ModelActionResult.Refused(why), answer.await(), "$outcome")
            assertEquals(ModelSetup.Absent(f.row().let { (it.setup as ModelSetup.Absent).model }, last = null), f.row().setup, "$outcome")
            // ⚠ The store's refusals are a standing state, with their kind, and stay.
            val latched = when (outcome) {
                ModelDownloadOutcome.StoreNotOwned -> StoreReadOnly.OwnedElsewhere
                ModelDownloadOutcome.StoreLockUnavailable -> StoreReadOnly.LockUnavailable()
                else -> null
            }
            assertEquals(latched, f.now.storeReadOnly, "$outcome")
        }
        // A call that threw is a failure nobody named.
        val f = fixture(FakeModelCalls(available = ModelRead.Got(listOf(facts()))))
        f.now
        val answer = async { f.ledger.download("siglip") }
        runCurrent()
        f.calls.running.getValue("siglip").completeExceptionally(IllegalStateException("boom"))
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.Failed(integrity = false))), answer.await())
        assertEquals(emptySet(), f.rows.filter { it.fetching }.map { it.modelId }.toSet())
    }

    /**
     * ⚠⚠ A download that ends badly after its press was answered is said by its row, under the
     * model and the catalog version it asked for: not under the version that replaces it, and not
     * after the next press.
     */
    @Test
    fun aDownloadThatEndsAfterItWasAnsweredIsTheRowsToSayForTheVersionItAskedFor() = runTest {
        val f = fixture(FakeModelCalls(available = ModelRead.Got(listOf(facts(version = 3), facts("other")))))
        f.now
        val answer = async { f.ledger.download("siglip") }
        f.calls.begin("siglip")
        settle()
        f.calls.tick("siglip", 1)
        assertEquals(ModelActionResult.Accepted, answer.await())

        f.calls.endStream("siglip")
        f.calls.running.getValue("siglip").complete(ModelDownloadOutcome.IntegrityFailed)

        assertEquals(ModelSetup.DownloadSaid.Failed(integrity = true), (f.row().setup as ModelSetup.Absent).last)
        assertNull((f.row("other").setup as ModelSetup.Absent).last, "said under another row")

        // The catalog moves on: the sentence was about version 3.
        f.calls.available = ModelRead.Got(listOf(facts(version = 4), facts("other")))
        f.ledger.checkAgain()
        assertNull((f.row().setup as ModelSetup.Absent).last)
        f.calls.available = ModelRead.Got(listOf(facts(version = 3), facts("other")))
        f.ledger.checkAgain()
        assertEquals(ModelSetup.DownloadSaid.Failed(integrity = true), (f.row().setup as ModelSetup.Absent).last)

        // ⚠ And a new press withdraws it at once: it is about the attempt before.
        val second = async { f.ledger.download("siglip") }
        assertEquals(ModelSetup.Fetching::class, f.row().setup::class)
        f.calls.running.getValue("siglip").complete(ModelDownloadOutcome.NoCatalog)
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NoCatalog), second.await())
        assertNull((f.row().setup as ModelSetup.Absent).last)
    }

    /** A store that refuses the commit, after the press was answered, is the standing state too. */
    @Test
    fun aStoreRefusalAfterThePressWasAnsweredIsStillLatched() = runTest {
        val f = fixture(FakeModelCalls(available = ModelRead.Got(listOf(facts()))))
        f.now
        val answer = async { f.ledger.download("siglip") }
        f.calls.begin("siglip")
        settle()
        f.calls.tick("siglip", 1)
        answer.await()

        f.calls.running.getValue("siglip").complete(ModelDownloadOutcome.StoreNotOwned)

        assertEquals(StoreReadOnly.OwnedElsewhere, f.now.storeReadOnly)
        assertNull((f.row().setup as ModelSetup.Absent).last)
        // ⚠ The proxy's own answer wins when it has one: it carries the detail.
        f.storeAccess = StoreReadOnly.LockUnavailable("/p/store.lock")
        f.calls.available = ModelRead.Got(listOf(facts(), facts("other")))
        f.ledger.checkAgain()
        assertEquals(StoreReadOnly.LockUnavailable("/p/store.lock"), f.now.storeReadOnly)
    }

    /**
     * ⚠⚠ One operation at a time. One call per model, however many presses; and no second
     * download beside a running one: the progress stream cannot tell two apart, so a second
     * would show the first one's bytes and be answered on the first one's tick.
     */
    @Test
    fun oneCallPerModelAndNoSecondDownloadBesideIt() = runTest {
        val f = fixture(FakeModelCalls(available = ModelRead.Got(listOf(facts("a"), facts("b")))))
        f.now
        val first = async { f.ledger.download("a") }
        val again = async { f.ledger.download("a") }
        runCurrent()
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.Busy), f.ledger.download("b"))

        assertEquals(listOf("a"), f.calls.downloads)
        assertEquals(setOf("a"), f.rows.filter { it.fetching }.map { it.modelId }.toSet())

        f.calls.running.getValue("a").complete(ModelDownloadOutcome.UnknownModel)
        val refused = ModelActionResult.Refused(ModelActionResult.Why.UnknownModel)
        assertEquals(listOf<ModelActionResult>(refused, refused), listOf(first.await(), again.await()))

        // The first has ended: the other model can be downloaded now.
        val other = async { f.ledger.download("b") }
        f.calls.begin("b")
        settle()
        f.calls.tick("b", 7)
        assertEquals(ModelActionResult.Accepted, other.await())
        assertEquals(7L, f.row("b").setup.download?.writtenBytes)
        assertNull(f.row("a").setup.download)
    }

    /**
     * ⚠⚠ A tick is used only for the model it is about. The real client can hand a subscription
     * another model's stream; such a tick is not a byte of this row's bar, and is not what says
     * this download is under way.
     */
    @Test
    fun aTickOfAnotherModelSaysNothingOfThisOne() = runTest {
        val calls = FakeModelCalls(available = ModelRead.Got(listOf(facts("a"), facts("b"))))
        val f = fixture(calls)
        f.now
        calls.begin("a")
        calls.tick("a", 150)
        calls.everySubscriptionGets = "a"

        val answer = async { f.ledger.download("b") }
        settle()

        assertFalse(answer.isCompleted, "answered accepted on another model's tick")
        assertEquals(ModelSetup.Download(true, null, 383, null), f.row("b").setup.download)
        calls.running.getValue("b").complete(ModelDownloadOutcome.NoCatalog)
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NoCatalog), answer.await())
    }

    /**
     * ⚠⚠ A removal is the ledger's state, as a download is: it is published while it runs, and
     * nothing else is started on that model meanwhile. A download of a model being removed is
     * refused (it would install again what was removed), a second removal joins the first, and
     * nothing is queued: when the removal has ended a press is a fresh download.
     */
    @Test
    fun aModelBeingRemovedIsSaidSoAndNothingElseIsStartedOnIt() = runTest {
        val calls = FakeModelCalls(ModelRead.Got(listOf(facts(version = 2))), ModelRead.Got(listOf(facts(version = 3))))
        calls.deleteGate = CompletableDeferred()
        val f = fixture(calls)
        f.now

        val removal = async { f.ledger.delete("siglip") }
        val second = async { f.ledger.delete("siglip") }
        runCurrent()
        assertEquals(setOf("siglip"), f.now.removing)
        assertTrue(f.row().installed, "removing qualifies the row; what is installed is the store's to say")
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.Busy), f.ledger.download("siglip"))
        assertEquals(emptyList(), calls.downloads)

        calls.installed = ModelRead.Got(emptyList())
        calls.deleteGate!!.complete(Unit)
        assertEquals(listOf(ModelDeleteOutcome.Removed, ModelDeleteOutcome.Removed), listOf(removal.await(), second.await()))
        assertEquals(listOf("siglip"), calls.deletions)
        assertEquals(emptySet(), f.now.removing)
        assertFalse(f.row().installed)
        // ⚠ Never installed-and-not-removing in between: the store was read before the mark went.
        val from = f.seen.indexOfFirst { "siglip" in it.removing }
        assertTrue(f.seen.drop(from).none { state -> state.removing.isEmpty() && (state.availability as ModelsAvailability.Listed).models.single().installed })
        assertEquals(emptyList(), calls.downloads)

        // And the other way round: a model being downloaded is not removed under it.
        val download = async { f.ledger.download("siglip") }
        runCurrent()
        assertEquals(ModelDeleteOutcome.Busy, f.ledger.delete("siglip"))
        assertEquals(listOf("siglip"), calls.deletions)
        calls.running.getValue("siglip").complete(ModelDownloadOutcome.UnknownModel)
        download.await()

        // ⚠⚠ A removal that takes long: the WAIT is bounded, the removal is not. Its caller is
        // told *still removing*, never *failed*; the row stays removing and still refuses a
        // download; and when the removal does end, it has ended.
        calls.installed = ModelRead.Got(listOf(facts(version = 2)))
        f.ledger.checkAgain()
        calls.deleteGate = CompletableDeferred()
        val slow = async { f.ledger.delete("siglip") }
        runCurrent()
        advanceTimeBy(61_000)
        assertEquals(ModelDeleteOutcome.StillRemoving, slow.await())
        assertEquals(setOf("siglip"), f.now.removing)
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.Busy), f.ledger.download("siglip"))
        calls.installed = ModelRead.Got(emptyList())
        calls.deleteGate!!.complete(Unit)
        assertEquals(emptySet(), f.now.removing)
        assertFalse(f.row().installed)
    }

    /**
     * ⚠⚠ Told or recorded, never both. A call that ends before its caller has got as far as
     * waiting still has that caller counted: the failure is the press's answer, and is not also
     * written under the row (which would say it a second and a third time).
     */
    @Test
    fun aCallThatEndsBeforeItsCallerWaitsIsStillThatCallersAnswerAndNotTheRowsToo() = runTest {
        val calls = FakeModelCalls(available = ModelRead.Got(listOf(facts())))
        // ⚠ Unconfined: the ledger's coroutine runs inside `download`, ahead of its caller.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val ledger = ModelsLedger(calls, { null }, scope, Dispatchers.Unconfined)
            ledger.checkAgain()
            calls.endsAtOnceWith = ModelDownloadOutcome.TransportFailed

            val answer = ledger.download("siglip")

            assertEquals(ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.Failed(integrity = false))), answer)
            val row = (ledger.state.first().availability as ModelsAvailability.Listed).models.single()
            assertNull((row.setup as ModelSetup.Absent).last, "delivered to its caller and recorded under the row")
        } finally {
            scope.cancel()
        }
    }

    /** On real threads: a download and a removal of one model, pressed together, never run together. */
    @Test
    fun aDownloadAndARemovalOfOneModelNeverRunTogetherOnRealThreads() {
        val calls = FakeModelCalls(ModelRead.Got(listOf(facts(version = 2))), ModelRead.Got(listOf(facts(version = 3))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ledger = ModelsLedger(calls, { null }, scope, Dispatchers.Default)
            runBlocking {
                withTimeout(30_000) {
                    ledger.checkAgain()
                    calls.endsByItselfWith = ModelDownloadOutcome.UnknownModel
                    val answers: List<Any> = List(96) { i ->
                        async(Dispatchers.Default) { if (i % 2 == 0) ledger.download("siglip") else ledger.delete("siglip") }
                    }.awaitAll()
                    assertFalse(calls.removedUnderADownload, "a removal and a download of one model ran side by side")
                    assertTrue(calls.mostDownloadsAtOnce.get() <= 1, "two downloads ran side by side")
                    // ⚠ Every press was ANSWERED, with an answer this order of events can give:
                    // a download ran and was refused by the proxy, or was refused for being busy;
                    // a removal happened (or joined one that did), or was refused under a download.
                    // (Which of the two wins the first step is not decided, so neither count is.)
                    val unknownModel = ModelActionResult.Refused(ModelActionResult.Why.UnknownModel)
                    val busy = ModelActionResult.Refused(ModelActionResult.Why.Busy)
                    val downloads = answers.filterIsInstance<ModelActionResult>()
                    val removals = answers.filterIsInstance<ModelDeleteOutcome>()
                    assertEquals(48 to 48, downloads.size to removals.size)
                    assertTrue(downloads.all { it == unknownModel || it == busy }, "$downloads")
                    assertTrue(removals.all { it == ModelDeleteOutcome.Removed || it == ModelDeleteOutcome.Busy }, "$removals")
                    // ⚠ And something ran: a run in which every press was refused proves nothing.
                    assertTrue(unknownModel in downloads || ModelDeleteOutcome.Removed in removals, "nothing ran: $answers")
                    // Nothing is left claimed once every call has ended. ⚠ Awaited, not read once: a
                    // press is answered from inside its call, which releases the claim in its own
                    // `finally` a moment later, so a single read right after the last answer can still
                    // see a row fetching (seen in 1 of 9 runs in a 4-core Linux container). A claim that is never
                    // released fails on the `withTimeout` above instead.
                    ledger.state.first { after ->
                        after.removing.isEmpty() &&
                            (after.availability as ModelsAvailability.Listed).models.none { it.fetching }
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * ⚠⚠ An ending nobody was told is the row's to say. A press whose screen has gone before the
     * answer has no one to tell; the next screen finds the sentence under the row.
     */
    @Test
    fun aCallThatEndsWithNobodyWaitingForItsAnswerIsSaidByItsRow() = runTest {
        val f = fixture(FakeModelCalls(available = ModelRead.Got(listOf(facts()))))
        f.now
        val screen = launch { f.ledger.download("siglip") }
        runCurrent()
        screen.cancelAndJoin()

        f.calls.running.getValue("siglip").complete(ModelDownloadOutcome.NoCatalog)

        assertEquals(ModelSetup.DownloadSaid.Failed(integrity = false), (f.row().setup as ModelSetup.Absent).last)
    }

    /**
     * ⚠ What is installed is read under its own lock: a landing and a removal publish at once,
     * though a catalog fetch somebody else started is still running.
     */
    @Test
    fun aLandingAndARemovalDoNotWaitForACatalogFetchThatIsRunning() = runTest {
        val calls = FakeModelCalls(available = ModelRead.Got(listOf(facts())))
        val f = fixture(calls)
        f.now
        calls.availableGate = CompletableDeferred()
        val reading = launch { f.ledger.checkAgain() }
        runCurrent()

        val answer = async { f.ledger.download("siglip") }
        runCurrent()
        calls.installed = ModelRead.Got(listOf(facts()))
        calls.running.getValue("siglip").complete(ModelDownloadOutcome.Landed)
        assertEquals(ModelActionResult.Accepted, answer.await())
        assertTrue(f.row().installed)

        calls.installed = ModelRead.Got(emptyList())
        assertEquals(ModelDeleteOutcome.Removed, f.ledger.delete("siglip"))
        assertFalse(f.row().installed)
        assertTrue(reading.isActive, "the catalog fetch was meant to be still running")
        calls.availableGate!!.complete(Unit)
    }

    /**
     * The same exclusion on real threads: every other case here runs on one test dispatcher, and
     * the ledger's state is shared between whoever presses.
     */
    @Test
    fun manyPressesAtOnceOnRealThreadsMakeOneCall() {
        val calls = FakeModelCalls(available = ModelRead.Got(listOf(facts())))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ledger = ModelsLedger(calls, { null }, scope, Dispatchers.Default)
            runBlocking {
                withTimeout(20_000) {
                    ledger.checkAgain()
                    calls.endsByItselfWith = ModelDownloadOutcome.UnknownModel
                    val answers = List(64) { async(Dispatchers.Default) { ledger.download("siglip") } }.awaitAll().toSet()
                    assertEquals(setOf<ModelActionResult>(ModelActionResult.Refused(ModelActionResult.Why.UnknownModel)), answers)
                    assertEquals(1, calls.mostDownloadsAtOnce.get(), "two calls of one model ran side by side")
                }
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * ⚠⚠ Opening the screen reads again, and what was shown stays while it does. Control runs
     * for days: what was published since the process started appears at the next open. Requests
     * made while a read runs are answered by one more read, not one each.
     */
    @Test
    fun everyOpenReadsAgainAndWhatWasShownStaysMeanwhile() = runTest {
        val f = fixture(FakeModelCalls(ModelRead.Got(listOf(facts(version = 3))), ModelRead.Got(listOf(facts(version = 3)))))
        assertEquals(listOf("siglip"), f.rows.map { it.modelId })
        assertEquals(1, f.calls.reads)

        f.calls.available = ModelRead.Got(listOf(facts(version = 4), facts("new")))
        f.ledger.checkAgain()
        assertEquals(listOf("siglip", "new"), f.rows.map { it.modelId })
        assertTrue(f.row().updateAvailable)
        assertTrue(f.seen.drop(1).none { it.availability == ModelsAvailability.Loading }, "a re-read flipped the list to loading")

        val before = f.calls.reads
        val three = List(3) { async { f.ledger.checkAgain() } }
        three.forEach { it.await() }
        assertTrue(f.calls.reads - before <= 2, "three requests made ${f.calls.reads - before} reads")
    }

    /**
     * ⚠⚠ What is installed is local and is shown before the catalog answers; and a catalog fetch
     * that never returns is bounded, so it does not hold every later read behind it.
     */
    @Test
    fun whatIsInstalledIsShownBeforeTheCatalogAndAReadThatNeverReturnsIsBounded() = runTest {
        val calls = FakeModelCalls(ModelRead.Got(listOf(facts())), ModelRead.Got(listOf(facts())))
        calls.availableHangs = true
        val f = fixture(calls)

        var listed = f.now.availability as ModelsAvailability.Listed
        assertEquals(ModelsAvailability.Catalog.NOT_READ_YET, listed.catalog)
        assertTrue(f.row().installed)

        advanceTimeBy(46_000)
        listed = f.now.availability as ModelsAvailability.Listed
        assertEquals(ModelsAvailability.Catalog.UNAVAILABLE, listed.catalog)

        // A later open is not stuck behind the first.
        calls.availableHangs = false
        f.ledger.checkAgain()
        assertEquals(ModelsAvailability.Catalog.READ, (f.now.availability as ModelsAvailability.Listed).catalog)
    }

    /**
     * ⚠⚠ A removal is the ledger's call. A screen that is closed while it runs leaves a removal
     * that ends and a list that is read again: not a list that shows a model that is gone.
     */
    @Test
    fun aRemovalEndsAndTheListIsReadAgainThoughItsCallerLeft() = runTest {
        val calls = FakeModelCalls(ModelRead.Got(listOf(facts())), ModelRead.Got(listOf(facts())))
        calls.deleteGate = CompletableDeferred()
        val f = fixture(calls)
        assertTrue(f.row().installed)

        val screen = launch { f.ledger.delete("siglip") }
        runCurrent()
        assertEquals(listOf("siglip"), calls.deletions)
        screen.cancelAndJoin()

        calls.installed = ModelRead.Got(emptyList())
        calls.deleteGate!!.complete(Unit)
        assertFalse(f.row().installed)
    }

    /** ⚠⚠ And so is a read: a screen that is closed while the list is read again stops nothing. */
    @Test
    fun aReadEndsThoughTheScreenThatAskedForItLeft() = runTest {
        val calls = FakeModelCalls(available = ModelRead.Got(listOf(facts())))
        val f = fixture(calls)
        assertEquals(listOf("siglip"), f.rows.map { it.modelId })

        calls.availableGate = CompletableDeferred()
        calls.available = ModelRead.Got(listOf(facts(), facts("new")))
        val screen = launch { f.ledger.checkAgain() }
        runCurrent()
        screen.cancelAndJoin()
        calls.availableGate!!.complete(Unit)

        assertEquals(listOf("siglip", "new"), f.rows.map { it.modelId })
    }

    /**
     * ⚠⚠ A read that throws is a read that gave no answer; it takes nothing down. And a removal
     * that happened is reported as removed even when the read after it fails: the list says the
     * read failed.
     */
    @Test
    fun aReadThatThrowsIsNoAnswerAndARemovalThatHappenedIsStillRemoved() = runTest {
        val calls = FakeModelCalls(ModelRead.Got(listOf(facts())), ModelRead.Got(listOf(facts())))
        calls.installedThrows = IllegalStateException("a row that would not read")
        val f = fixture(calls)
        assertEquals(ModelsAvailability.NotConnected, f.now.availability)

        calls.installedThrows = null
        calls.availableThrows = IllegalStateException("boom")
        f.ledger.checkAgain()
        // (The catalog had read once, beside the installed read that threw: so stale, not unavailable.)
        assertEquals(ModelsAvailability.Catalog.STALE, (f.now.availability as ModelsAvailability.Listed).catalog)

        // ⚠ Somebody else's cancellation, under a live ledger, is a failed read too.
        calls.availableThrows = CancellationException("the session went")
        f.ledger.checkAgain()
        assertTrue(f.row().installed)

        calls.installedThrows = IllegalStateException("boom")
        assertEquals(ModelDeleteOutcome.Removed, f.ledger.delete("siglip"))
        assertEquals(ModelsAvailability.NotConnected, f.now.availability)
        calls.deleteThrows = IllegalStateException("boom")
        assertEquals(ModelDeleteOutcome.Failed, f.ledger.delete("siglip"))
    }

    /**
     * ⚠⚠ A call that is cancelled by somebody else while the ledger lives (a session torn down,
     * a realm locked) is a download that did not finish. Its row stops fetching, and the next
     * press makes a new call: the row is never left fetching for the process.
     */
    @Test
    fun aCallCancelledUnderALiveLedgerEndsItsRowAndTheNextPressCallsAgain() = runTest {
        val f = fixture(FakeModelCalls(available = ModelRead.Got(listOf(facts()))))
        f.now
        val answer = async { f.ledger.download("siglip") }
        runCurrent()
        assertTrue(f.row().fetching)

        f.calls.running.getValue("siglip").completeExceptionally(CancellationException("the session went"))

        assertEquals(
            ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.Failed(integrity = false))),
            answer.await(),
        )
        assertFalse(f.row().fetching)

        val again = async { f.ledger.download("siglip") }
        runCurrent()
        assertEquals(listOf("siglip", "siglip"), f.calls.downloads)
        f.calls.running.getValue("siglip").complete(ModelDownloadOutcome.UnknownModel)
        again.await()
    }

    /**
     * ⚠⚠ The stream is the bar and nothing else. One that fails, or never ends, holds nothing:
     * the call's end is what ends the row, a tick that arrives after it writes nothing, and every
     * press collects a stream of its own. ⚠ And no stream is asked for a model nobody pressed:
     * nothing but this ledger starts a download on these platforms.
     */
    @Test
    fun aStreamThatFailsOrNeverEndsHoldsNothingAndNoneIsAskedForWithoutACall() = runTest {
        val calls = FakeModelCalls(available = ModelRead.Got(listOf(facts())))
        val f = fixture(calls)
        f.ledger.checkAgain()
        f.now
        assertEquals(0, calls.subscribes)

        // A stream that fails: the press is answered when the call ends.
        calls.streamThrows = IllegalStateException("the stream broke")
        val first = async { f.ledger.download("siglip") }
        settle()
        assertFalse(first.isCompleted)
        calls.running.getValue("siglip").complete(ModelDownloadOutcome.NoCatalog)
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NoCatalog), first.await())
        assertFalse(f.row().fetching)

        // A stream that never ends: the model lands, and the row is installed, not fetching.
        calls.streamThrows = null
        calls.begin("siglip")
        val second = async { f.ledger.download("siglip") }
        settle()
        calls.tick("siglip", 10)
        assertEquals(ModelActionResult.Accepted, second.await())
        calls.installed = ModelRead.Got(listOf(facts()))
        calls.running.getValue("siglip").complete(ModelDownloadOutcome.Landed)
        assertTrue(f.row().installed)
        assertNull(f.row().setup.download)
        calls.tick("siglip", 20)
        assertNull(f.row().setup.download, "a tick after the call ended brought the bar back")

        // ⚠ A model that landed was never shown as absent on the way.
        val since = f.seen.indexOfLast { state -> (state.availability as? ModelsAvailability.Listed)?.models?.any { it.fetching } == true }
        assertTrue(f.seen.drop(since).none { state -> (state.availability as? ModelsAvailability.Listed)?.models?.single()?.setup is ModelSetup.Absent })
    }

    /** A removal's refusal by the store is latched like a download's, and the store is read again. */
    @Test
    fun aRemovalReadsTheStoreAgainAndAStoreThatRefusesItIsLatched() = runTest {
        val f = fixture(FakeModelCalls(installed = ModelRead.Got(listOf(facts())), available = ModelRead.Got(listOf(facts()))))
        assertTrue(f.row().installed)

        f.calls.installed = ModelRead.Got(emptyList())
        assertEquals(ModelDeleteOutcome.Removed, f.ledger.delete("siglip"))
        assertFalse(f.row().installed)
        assertNull(f.now.storeReadOnly)

        f.calls.deleteOutcome = ModelDeleteOutcome.StoreLockUnavailable
        assertEquals(ModelDeleteOutcome.StoreLockUnavailable, f.ledger.delete("siglip"))
        assertEquals(StoreReadOnly.LockUnavailable(), f.now.storeReadOnly)
        assertEquals(listOf("siglip", "siglip"), f.calls.deletions)
    }
}

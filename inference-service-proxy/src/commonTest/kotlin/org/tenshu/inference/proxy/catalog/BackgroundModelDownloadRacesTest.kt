package org.tenshu.inference.proxy.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.inference.proxy.catalog.DownloadFixtures.DIR
import org.tenshu.inference.proxy.catalog.DownloadFixtures.JOURNAL
import org.tenshu.inference.proxy.catalog.DownloadFixtures.MODEL
import org.tenshu.inference.proxy.catalog.DownloadFixtures.entry
import org.tenshu.inference.proxy.catalog.DownloadFixtures.key
import org.tenshu.inference.proxy.catalog.DownloadFixtures.ref
import org.tenshu.inference.proxy.catalog.DownloadFixtures.sha256
import org.tenshu.inference.proxy.catalog.DownloadFixtures.textBytes
import org.tenshu.inference.proxy.catalog.DownloadFixtures.url
import org.tenshu.inference.proxy.catalog.DownloadFixtures.visionBytes
import org.tenshu.inference.proxy.catalog.DownloadFixtures.vocabularyBytes
import org.tenshu.inference.proxy.catalog.FakeTransport.Served
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model download's ordering races (`ios-on-device-inference.md` §14.11): the cases where two
 * things that are each right meet in the wrong order. The real [BackgroundModelDownloader], its
 * real lock and its real queued settlement, over the real [ModelTransferLedger] and the real
 * [DefaultModelStore].
 *
 * ⚠ Every order here is put in by hand ([FakeTransport]'s gates), so each case is one
 * interleaving and deterministic. It is not a search for others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundModelDownloadRacesTest {

    private val vision = ModelArtifactRole.VISION
    private val text = ModelArtifactRole.TEXT
    private val vocabulary = ModelArtifactRole.VOCABULARY

    private val disk = DownloadDisk()
    private val transport = FakeTransport(disk, DIR)

    private fun serve(of: ModelEntryData = entry(), vision: Served? = null, text: Served? = null, vocabulary: Served? = null) {
        transport.served[url(of, this.vision)] = vision ?: Served.Body(visionBytes)
        transport.served[url(of, this.text)] = text ?: Served.Body(textBytes)
        transport.served[url(of, this.vocabulary)] = vocabulary ?: Served.Body(vocabularyBytes)
    }

    private fun TestScope.process(
        entry: () -> ModelEntryData? = { DownloadFixtures.entry() },
        catalog: ModelCatalogSource = DownloadFixtures.catalogOf(entry),
    ) = DownloadProcess(disk, transport, backgroundScope, entry, catalog = catalog)

    private fun TestScope.downloading(process: DownloadProcess): Deferred<Result<InstalledModelData>> =
        backgroundScope.async { runCatching { process.downloader.download(MODEL) } }

    private suspend fun Deferred<Result<InstalledModelData>>.settled(): Result<InstalledModelData> {
        assertTrue(isCompleted, "the download has not ended")
        return await()
    }

    private fun journalText(): String? = disk.readBytes(JOURNAL)?.get()?.decodeToString()

    private fun catalogData(of: ModelEntryData = entry()) =
        ModelCatalogData(schemaVersion = 1, buildDate = "2026-10-12", models = listOf(of))

    // ─── 1. Stale staging and an installed artifact.

    @Test
    fun stagingLeftForAnotherDigestIsDroppedOnceTheInstalledArtifactIsWhatIsWanted() = runTest(UnconfinedTestDispatcher()) {
        // The catalog comes back to the installed vision tower, with a text tower of its own.
        val v3 = entry(version = 3, text = ByteArray(8192) { 7 })
        val v2 = entry(version = 2, vision = ByteArray(5000) { 9 }, text = ByteArray(8192) { 8 })
        var offered: ModelEntryData = entry()
        val phone = process(entry = { offered })
        serve()
        val install = downloading(phone)
        transport.run(phone.downloader)
        install.settled().getOrThrow()
        // An update to another vision tower, of another length: it stages that tower and fails.
        offered = v2
        serve(v2, vision = Served.Body(ByteArray(5000) { 9 }), text = Served.Error("The network connection was lost."))
        val update = downloading(phone)
        transport.run(phone.downloader)
        assertIs<ModelDownloadException>(update.settled().exceptionOrNull())
        // Kept for the retry.
        assertEquals(5000L, phone.store.stagedBytes(MODEL, vision, v2.artifact(vision)!!.sha256))

        offered = v3
        serve(v3, text = Served.Stall)
        val third = downloading(phone)

        // ⚠⚠ Admitted, and its vision tower is the installed one: nothing is fetched for it,
        // and what the failed update had staged for that role is gone NOW, before any commit.
        assertEquals(listOf(url(v3, text)), transport.started.takeLast(1).map { it.url })
        assertEquals(0L, phone.store.stagedBytes(MODEL, vision, v2.artifact(vision)!!.sha256), "the other digest's staging was left beside the installed tower")
        assertFalse(disk.paths.any { it.contains(".part") && vision in it }, "staging left: ${disk.paths}")

        serve(v3, text = Served.Body(ByteArray(8192) { 7 }))
        transport.run(phone.downloader)
        val installed: InstalledModelData = third.settled().getOrThrow()
        // ⚠⚠ The index says v1's vision digest, and the file under it is v1's vision tower.
        assertEquals(sha256(visionBytes), installed.artifacts.first { it.role == vision }.sha256)
        assertContentEquals(visionBytes, disk.readBytes(installed.pathOf(vision)!!)?.get())
    }

    @Test
    fun theStoreNeverRenamesStagingOfAnotherDigestOntoAnInstalledArtifact() {
        // ⚠ The store's own half of the same rule, with no downloader to tidy up before it.
        val store = DownloadFixtures.storeOver(disk)
        val v1 = entry()
        assertNotNull(store.add(v1, mapOf(vision to visionBytes, text to textBytes, vocabulary to vocabularyBytes)))
        val other = ByteArray(5000) { 9 }
        store.beginStaging(MODEL, vision, sha256(other))
        store.writeStaged(MODEL, vision, 0, other)

        // A commit of an entry whose vision tower is the installed one, with a new text tower.
        val newText = ByteArray(8192) { 7 }
        val v3 = entry(version = 3, text = newText)
        store.beginStaging(MODEL, text, sha256(newText))
        store.writeStaged(MODEL, text, 0, newText)
        val installed: InstalledModelData = store.commitStaged(v3, emptyMap())

        // ⚠⚠ The resident tower was adopted. It was once judged "not whole" against the LENGTH OF
        // WHAT WAS IN STAGING, and the other digest's 5000 bytes were renamed onto it.
        assertContentEquals(visionBytes, disk.readBytes(installed.pathOf(vision)!!)?.get(), "the wrong bytes are under the installed digest")
        assertContentEquals(newText, disk.readBytes(installed.pathOf(text)!!)?.get())
        assertEquals(0L, store.stagedBytes(MODEL, vision, sha256(other)))
    }

    @Test
    fun theStoreCommitsNothingWhenAnArtifactIsStagedForAnotherDigest() {
        val store = DownloadFixtures.storeOver(disk)
        val other = ByteArray(5000) { 9 }
        entry().artifacts.forEach { artifact ->
            val bytes: ByteArray = mapOf(vision to visionBytes, text to textBytes, vocabulary to vocabularyBytes).getValue(artifact.role)
            // ⚠ The vision tower's staging was begun for another digest.
            val staged: ByteArray = if (artifact.role == vision) other else bytes
            store.beginStaging(MODEL, artifact.role, sha256(staged))
            store.writeStaged(MODEL, artifact.role, 0, staged)
        }
        val before: Set<String> = disk.paths.toSet()

        assertFailsWith<ModelNotCommittableException> { store.commitStaged(entry(), emptyMap()) }

        // ⚠⚠ Whole or not at all: nothing was renamed, declared or indexed.
        assertEquals(emptyList(), store.installed())
        assertEquals(before, disk.paths.toSet())
    }

    @Test
    fun theStoreCommitsNothingWhenAnArtifactWasBegunAndNeverStaged() {
        val store = DownloadFixtures.storeOver(disk)
        mapOf(text to textBytes, vocabulary to vocabularyBytes).forEach { (role, bytes) ->
            store.beginStaging(MODEL, role, sha256(bytes))
            store.writeStaged(MODEL, role, 0, bytes)
        }
        // ⚠ Begun, so its marker names the right digest; and not one byte of it is there.
        store.beginStaging(MODEL, vision, sha256(visionBytes))

        assertFailsWith<ModelNotCommittableException> { store.commitStaged(entry(), emptyMap()) }
        assertEquals(emptyList(), store.installed(), "a record was written naming a file that does not exist")
    }

    // ─── 2. A start decided on a picture that has since changed.

    @Test
    fun aStartDecidedBeforeTheTransferReportedIsNotMadeOnceItsBytesAreStaged() = runTest(UnconfinedTestDispatcher()) {
        serve()
        downloading(process())
        assertEquals(3, transport.started.size)

        // A relaunch. The vision tower's transfer has ended in the session, and its callbacks
        // have not been delivered to this process yet: it is in no listing.
        val relaunched = process()
        val finished = assertNotNull(transport.finish(ref(entry(), vision)))
        transport.holdStarts = true
        relaunched.downloader.resume()
        // The resume was told the ref is free (nobody here has seen the task) and asked for it.
        assertEquals(listOf(ref(entry(), vision)), transport.asked.drop(3))

        // Now the callbacks arrive: the file, and the report, which the downloader acts on.
        assertNotNull(finished.callBack()).report(relaunched.downloader).join()
        assertEquals(visionBytes.size.toLong(), relaunched.store.stagedBytes(MODEL, vision, sha256(visionBytes)))

        // ⚠⚠ And only now does the session answer the start. The bytes are staged: no task is made.
        transport.answerStarts()
        assertEquals(3, transport.started.size, "a staged artifact was fetched a second time")
        assertEquals(setOf(ref(entry(), text), ref(entry(), vocabulary)), transport.live.keys)

        transport.run(relaunched.downloader)
        assertEquals(1, relaunched.store.installed().size)
    }

    // ─── 3. Who an ending answers.

    @Test
    fun aCallThatAskedAfterACancelIsNotToldOfTheCancelAndItsDownloadBegins() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        val first = downloading(phone)
        assertEquals(3, transport.started.size)
        // A step holds the lock (a resume, waiting for the transport), so the cancel waits.
        transport.answerWhen = CompletableDeferred()
        val resuming = backgroundScope.async { phone.downloader.resume() }
        assertEquals(1, transport.unanswered)
        val cancelling = backgroundScope.async { phone.downloader.cancel(MODEL) }
        // ⚠ Asked for AFTER the cancel was, and before the cancel has run: it has joined nothing.
        val second = downloading(phone)

        transport.answerWhen!!.complete(Unit)
        transport.answerWhen = null
        resuming.await()
        cancelling.await()

        assertIs<ModelDownloadCancelledException>(first.settled().exceptionOrNull())
        // ⚠⚠ The cancel ended the download that was running, and nothing asked for after it:
        // the second call is not told "cancelled", and its request is not lost.
        assertFalse(second.isCompleted && second.await().isFailure, "a call made after the cancel was told of it")
        assertEquals(6, transport.started.size)
        serve()
        transport.run(phone.downloader)
        assertEquals(phone.store.installed().single(), second.settled().getOrThrow())
    }

    @Test
    fun aCallThatAskedBeforeAFailureIsNotToldOfTheFailureAndItsDownloadBegins() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Error("The network connection was lost."))
        var fetches = 0
        val secondFetch = CompletableDeferred<Unit>()
        val phone = process(
            catalog = ModelCatalogSource {
                if (++fetches == 2) secondFetch.await()
                catalogData()
            },
        )
        val first = downloading(phone)
        val second = downloading(phone)

        transport.run(phone.downloader)

        assertIs<ModelDownloadException>(first.settled().exceptionOrNull())
        assertTrue(second.isActive, "a call that had not begun was told of another download's failure")
        serve()
        secondFetch.complete(Unit)
        transport.run(phone.downloader)
        assertEquals(phone.store.installed().single(), second.settled().getOrThrow())
    }

    // ─── 4. A caller that stops waiting stops nothing.

    @Test
    fun aCallerThatLeavesWhileTheCatalogIsFetchedStopsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val catalogArrives = CompletableDeferred<Unit>()
        val phone = process(
            catalog = ModelCatalogSource {
                catalogArrives.await()
                catalogData()
            },
        )
        val caller = downloading(phone)
        caller.cancel()

        catalogArrives.complete(Unit)

        assertTrue(MODEL in journalText().orEmpty(), "the download of a caller that left never began")
        assertEquals(3, transport.started.size)
        transport.run(phone.downloader)
        assertEquals(1, phone.store.installed().size)
    }

    @Test
    fun aCallerThatLeavesBetweenTheJournalAndTheFirstStartStopsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        // The step has journalled and waits for the transport to say what it holds.
        transport.answerWhen = CompletableDeferred()
        val caller = downloading(phone)
        assertTrue(MODEL in journalText().orEmpty())
        assertEquals(1, transport.unanswered)
        assertEquals(emptyList(), transport.started)

        caller.cancel()
        transport.answerWhen!!.complete(Unit)
        transport.answerWhen = null

        // ⚠⚠ It was cancelled with its caller once: a record no transfer ran for, until the
        // next return to the foreground.
        assertEquals(3, transport.started.size, "a journalled download started nothing because its caller had left")
        transport.run(phone.downloader)
        assertEquals(1, phone.store.installed().size)
    }

    // ─── 5. A report is about the transfer it came from.

    @Test
    fun aSuccessReportedBehindACancelAndTheNextDownloadIsNotTheNextDownloads() = runTest(UnconfinedTestDispatcher()) {
        serve(vision = Served.Stall, vocabulary = Served.Stall)
        val phone = process()
        val first = downloading(phone)
        // The text tower is delivered. Its completion has been handed over and not yet made a report.
        val ended = assertNotNull(transport.end(ref(entry(), text)))

        // Ahead of it: the user cancels, and asks again.
        phone.downloader.cancel(MODEL)
        assertIs<ModelDownloadCancelledException>(first.settled().exceptionOrNull())
        val second = downloading(phone)
        assertEquals(6, transport.started.size)

        // The old transfer's report arrives: status 200, and its file went with the cancel.
        ended.report(phone.downloader).join()

        // ⚠⚠ It is nobody's. It was once the new download's failure ("status 200 and delivered
        // nothing"), which cancelled the new transfers too.
        assertTrue(second.isActive, "the old transfer's report ended the new download: ${second.takeIf { it.isCompleted }?.await()}")
        assertNull(phone.downloader.outcomeFor(key(entry()))?.let { it as? ModelDownloadOutcome.Failed })
        assertEquals(3, transport.live.size)
        assertTrue(MODEL in journalText().orEmpty())

        serve()
        transport.run(phone.downloader)
        assertEquals(phone.store.installed().single(), second.settled().getOrThrow())
    }

    @Test
    fun aFailureReportedBehindAResumeIsTheEndOfItsOwnDownloadAndStartsNoReplacement() = runTest(UnconfinedTestDispatcher()) {
        serve(vision = Served.Stall, vocabulary = Served.Stall, text = Served.Error("The network connection was lost."))
        val phone = process()
        val waiting = downloading(phone)

        // A step holds the downloader's lock, waiting for the session to say what it holds.
        transport.answerWhen = CompletableDeferred()
        val resuming = backgroundScope.async { phone.downloader.resume() }
        assertEquals(1, transport.unanswered)
        // The text tower's transfer fails: the session forgets the task, the report is queued.
        val reported = assertNotNull(transport.end(ref(entry(), text))).report(phone.downloader)
        transport.answerWhen!!.complete(Unit)
        transport.answerWhen = null
        resuming.await()
        reported.join()

        assertIs<ModelDownloadException>(waiting.settled().exceptionOrNull())
        // ⚠⚠ The resume was told the ref was still held, and asked for no replacement.
        assertEquals(3, transport.asked.size, "the resume was told the ref was free: ${transport.asked}")
        assertEquals(3, transport.started.size)
        assertIs<ModelDownloadOutcome.Failed>(phone.downloader.outcomeFor(key(entry())))
        assertEquals(emptyList(), transport.ledger.liveRefs(emptyList()))
    }

    @Test
    fun aReportOfNothingDeliveredFailsNoDownloadWhoseStagedBytesCouldNotBeRead() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall, vocabulary = Served.Stall)
        val first = process()
        downloading(first)
        transport.end(ref(entry(), vision))!!.report(first.downloader).join()
        // A relaunch: the vision tower is staged, by a process that is gone, and cannot be read
        // now (the phone has not been unlocked since it started).
        val relaunched = process()
        val staging: String = disk.paths.single { it.endsWith(".part") && vision in it }
        disk.readFaults[staging] = DownloadDisk.ReadFault.NULL_THOUGH_PRESENT

        // A late report about that transfer: it ended, and no delivered file is there (it was staged).
        relaunched.downloader.settleFinished(ref(entry(), vision), 200, null)

        // ⚠⚠ Nothing is concluded from bytes that could not be read: the download is not failed,
        // its record and its staging stay, and its other transfers run on.
        assertNull(relaunched.downloader.outcomeFor(key(entry())), "a download was failed on a read that failed")
        assertTrue(MODEL in journalText().orEmpty())
        assertTrue(staging in disk.paths)
        assertEquals(setOf(ref(entry(), text), ref(entry(), vocabulary)), transport.live.keys)

        // Readable again: the same report now finds the artifact staged, and still fails nothing.
        disk.readFaults.clear()
        relaunched.downloader.settleFinished(ref(entry(), vision), 200, null)
        assertNull(relaunched.downloader.outcomeFor(key(entry())))
        serve()
        transport.run(relaunched.downloader)
        assertEquals(1, relaunched.store.installed().size)
    }

    // ─── The store-change wrapper waits, as the real one does.

    @Test
    fun aCancelAskedForWhileTheCommitWaitsForTheStoreFindsTheModelInstalled() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val waiting = downloading(phone)
        phone.changes.runWhen = CompletableDeferred()
        transport.end(ref(entry(), vision))!!.report(phone.downloader).join()
        transport.end(ref(entry(), text))!!.report(phone.downloader).join()
        // The last artifact: every one is staged, and the commit waits in the wrapper.
        val committing = transport.end(ref(entry(), vocabulary))!!.report(phone.downloader)
        assertEquals(1, phone.changes.waiting)
        assertTrue(phone.downloader.isDownloading(MODEL))

        val cancelling = backgroundScope.async { phone.downloader.cancel(MODEL) }
        assertTrue(cancelling.isActive)
        phone.changes.runWhen!!.complete(Unit)
        committing.join()
        cancelling.await()

        // The cancel came second: there was no download left to end, and nothing was undone.
        assertEquals(phone.store.installed().single(), waiting.settled().getOrThrow())
        assertIs<ModelDownloadOutcome.Installed>(phone.downloader.outcomeFor(key(entry())))
        assertEquals("[]", journalText())
    }

    // ─── A journal that cannot be read is not a journal with nothing in it.

    /** The journal gives its bytes [times] more times, and then cannot be read. */
    private fun theJournalFailsAfter(times: Int) {
        var reads = 0
        disk.whileReading = { path ->
            if (path == JOURNAL && ++reads == times) disk.readFaults[JOURNAL] = DownloadDisk.ReadFault.THROWS
        }
    }

    private fun theJournalReadsAgain() {
        disk.whileReading = {}
        disk.readFaults.clear()
    }

    @Test
    fun aJournalThatCannotBeReadWhenItIsReadAgainIsNotARecordThatIsGone() = runTest(UnconfinedTestDispatcher()) {
        serve(vision = Served.Stall, vocabulary = Served.Stall, text = Served.Error("The network connection was lost."))
        val phone = process()
        val waiting = downloading(phone)
        // The report's first read of the journal succeeds; the read made after it has looked
        // at the artifacts fails (a transient I/O error; a file not yet readable after a boot).
        theJournalFailsAfter(1)

        transport.end(ref(entry(), text))!!.report(phone.downloader).join()

        // ⚠⚠ The transfer has ended and its report is acknowledged, so nothing else would wake
        // the caller. It is told, with what is true: the journal could not be read.
        assertIs<ModelDownloadJournalUnreadableException>(waiting.settled().exceptionOrNull())
        assertEquals(
            ModelDownloadRefusal.JournalUnreadable,
            assertIs<ModelDownloadOutcome.Refused>(phone.downloader.outcomeFor(key(entry()))).reason,
        )
        // ⚠⚠ And nothing was concluded from the failed read: the record, and the two transfers
        // that are still running, are untouched.
        theJournalReadsAgain()
        assertTrue(MODEL in journalText().orEmpty(), "a failed read ended the record")
        assertEquals(setOf(ref(entry(), vision), ref(entry(), vocabulary)), transport.live.keys)
        assertEquals(emptyList(), transport.cancelled)

        // The next resume finds the text tower's transfer gone and starts it again.
        phone.downloader.resume()
        assertEquals(ref(entry(), text), transport.started.last().ref)
        serve()
        transport.run(phone.downloader)
        assertEquals(1, phone.store.installed().size)
    }

    @Test
    fun aReportThatMeetsAnUnreadableJournalTellsWhoeverWaitsAndKeepsWhatWasDelivered() = runTest(UnconfinedTestDispatcher()) {
        serve(vision = Served.Stall, vocabulary = Served.Stall)
        val phone = process()
        val waiting = downloading(phone)
        val ended = transport.end(ref(entry(), text))!!
        disk.readFaults[JOURNAL] = DownloadDisk.ReadFault.NULL_THOUGH_PRESENT

        ended.report(phone.downloader).join()

        assertIs<ModelDownloadJournalUnreadableException>(waiting.settled().exceptionOrNull())
        // The delivered file stays, unverified, for the step that can read the journal.
        assertEquals(setOf(ref(entry(), text)), disk.delivered(DIR))
        theJournalReadsAgain()
        serve()
        phone.downloader.resume()
        transport.run(phone.downloader)
        assertEquals(1, phone.store.installed().size)
    }

    @Test
    fun aFailureThatCannotReadTheJournalToEndItsRecordTellsWhoeverWaits() = runTest(UnconfinedTestDispatcher()) {
        // The text tower arrives with the right length and other bytes: the download is to be failed.
        val other: ByteArray = textBytes.copyOf().also { it[4000] = (it[4000] + 1).toByte() }
        serve(vision = Served.Stall, vocabulary = Served.Stall, text = Served.Body(other))
        val phone = process()
        val waiting = downloading(phone)
        theJournalFailsAfter(1)

        transport.end(ref(entry(), text))!!.report(phone.downloader).join()

        assertIs<ModelDownloadJournalUnreadableException>(waiting.settled().exceptionOrNull())
        theJournalReadsAgain()
        assertTrue(MODEL in journalText().orEmpty())
        assertEquals(2, transport.live.size)
    }

    @Test
    fun aFailureWhoseRecordCannotBeRemovedTellsWhoeverWaitsAndKeepsTheRecord() = runTest(UnconfinedTestDispatcher()) {
        serve(vision = Served.Stall, vocabulary = Served.Stall, text = Served.Error("The network connection was lost."))
        val phone = process()
        val waiting = downloading(phone)
        disk.failingWrites += JOURNAL

        transport.end(ref(entry(), text))!!.report(phone.downloader).join()

        // ⚠ The record stays (it could not be removed), nothing was released, and the caller
        // is not left waiting on a transfer that has ended.
        assertIs<ModelDownloadException>(waiting.settled().exceptionOrNull())
        assertTrue(MODEL in journalText().orEmpty())
        assertEquals(2, transport.live.size)
    }

    @Test
    fun bytesThatCannotBeReadWithNothingLeftToWaitForAreSaidAndKept() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val waiting = downloading(phone)
        transport.end(ref(entry(), vision))!!.report(phone.downloader).join()
        transport.end(ref(entry(), vocabulary))!!.report(phone.downloader).join()
        // The last artifact is delivered, and its file gives no bytes.
        val ended = transport.end(ref(entry(), text))!!
        val file: String = BackgroundModelDownloader.deliveredPath(DIR, ref(entry(), text))
        disk.readFaults[file] = DownloadDisk.ReadFault.NULL_THOUGH_PRESENT

        ended.report(phone.downloader).join()

        // ⚠⚠ No transfer is running and none is to be started: no report will come to look
        // again. Nothing is concluded (the file and the record stay), and the caller is told.
        assertIs<ModelDownloadException>(waiting.settled().exceptionOrNull())
        assertFalse(assertIs<ModelDownloadOutcome.Failed>(phone.downloader.outcomeFor(key(entry()))).integrity)
        assertTrue(file in disk.paths)
        assertTrue(MODEL in journalText().orEmpty())
        assertEquals(emptyList(), phone.store.installed())

        disk.readFaults.clear()
        phone.downloader.resume()
        assertEquals(1, phone.store.installed().size)
    }

    @Test
    fun aStagingMarkerThatCannotBeReadIsNotAMarkerForAnotherDigest() {
        val store = DownloadFixtures.storeOver(disk)
        store.beginStaging(MODEL, text, sha256(textBytes))
        store.writeStaged(MODEL, text, 0, textBytes)
        val marker: String = disk.paths.single { it.endsWith(".sha") }
        val staging: String = marker.removeSuffix(".sha")
        disk.readFaults[marker] = DownloadDisk.ReadFault.NULL_THOUGH_PRESENT

        // ⚠⚠ It once read as "staged for something else": the bytes were deleted and fetched again.
        assertFailsWith<IllegalStateException> { store.beginStaging(MODEL, text, sha256(textBytes)) }
        assertEquals(textBytes.size.toLong(), disk.fileSize(staging), "staged bytes were deleted on a read that failed")

        disk.readFaults.clear()
        assertEquals(textBytes.size.toLong(), store.beginStaging(MODEL, text, sha256(textBytes)))
    }

    // ─── Two graphs of one process, one journal.

    @Test
    fun aGraphTornDownDuringItsCommitDoesNotEraseTheRecordItsSuccessorWrites() = runTest(UnconfinedTestDispatcher()) {
        serve()
        var retired = false
        val old = DownloadProcess(disk, transport, backgroundScope, retired = { retired })
        val first = downloading(old)
        transport.end(ref(entry(), vision))!!.report(old.downloader).join()
        transport.end(ref(entry(), text))!!.report(old.downloader).join()

        // The successor: the same process (the same lock, the same ledger), a graph of its own.
        // ⚠ Its store has read its index already, so it does not know of the commit to come.
        val next = DownloadProcess(disk, transport, backgroundScope, newProcess = false, steps = old.steps)
        assertEquals(emptyList(), next.store.installed())
        var second: Deferred<Result<InstalledModelData>>? = null
        // ⚠⚠ The teardown lands INSIDE the old graph's commit, after its last question and
        // after its renames: the staging is gone and the index is about to be written. And the
        // successor is asked for the model then.
        val index = "$DEFAULT_MODELS_DIR/inference.models.index.json"
        var armed = false
        disk.whileReading = { path ->
            if (armed && second == null && path == index && disk.paths.none { it.endsWith(".part") }) {
                retired = true
                // ⚠ Undispatched: it runs HERE, inside the commit, as far as it can get. (Left to
                // the test dispatcher it would be queued until the old step had ended, and the
                // case would pass with a lock for each graph: it did, the first time.)
                second = backgroundScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    runCatching { next.downloader.download(MODEL) }
                }
            }
        }
        armed = true
        transport.end(ref(entry(), vocabulary))!!.report(old.downloader).join()
        disk.whileReading = {}
        val successor = assertNotNull(second, "the commit never read the index after its renames: the case did not reach its moment")

        // The old graph committed, and tidied its own record.
        assertEquals(1, old.store.installed().size)
        first.settled().getOrThrow()
        // ⚠⚠ The successor's step waited for that one to end, and then read the index again:
        // the model is installed, and nothing is fetched. It was not waiting once: it saw no
        // record and no staging, journalled and started transfers in the middle of the commit,
        // and the old graph's cleanup, which removes "the record of this model", removed the
        // successor's. Its transfers then reported to no record, their files were deleted, and
        // its caller waited for good.
        assertTrue(successor.isCompleted, "the successor's download never ended")
        assertEquals(sha256(visionBytes), successor.await().getOrThrow().artifacts.first { it.role == vision }.sha256)
        assertEquals(3, transport.started.size, "the successor fetched a model that was being installed")
        assertEquals("[]", journalText())
    }

    @Test
    fun twoGraphsNeverInterleaveOverTheOneJournal() = runTest(UnconfinedTestDispatcher()) {
        serve(vision = Served.Stall, text = Served.Stall, vocabulary = Served.Stall)
        val old = process()
        downloading(old)
        val next = DownloadProcess(disk, transport, backgroundScope, newProcess = false, steps = old.steps)

        // The old graph is in a step: it has read the journal, and waits for the transport.
        transport.answerWhen = CompletableDeferred()
        val resuming = backgroundScope.async { old.downloader.resume() }
        assertEquals(1, transport.unanswered)
        // The successor cancels the download.
        val cancelling = backgroundScope.async { next.downloader.cancel(MODEL) }

        // ⚠⚠ It waits. It did not once: it removed the record and cancelled the transfers, and
        // the old graph's step, going on from the journal it had read before, found three
        // artifacts with no transfer and started them again: 743 MB for a download the user
        // had just cancelled, named by no record.
        assertTrue(cancelling.isActive, "two graphs were in a step over the one journal at once")
        transport.answerWhen!!.complete(Unit)
        transport.answerWhen = null
        resuming.await()
        cancelling.await()

        assertEquals("[]", journalText())
        assertEquals(emptySet(), transport.live.keys, "transfers run for a download that was cancelled")
    }

    // ─── A call that has not journalled yet, and the store another graph wrote.

    /** [MODEL] installed, and a second call for a newer entry held in its catalog fetch. */
    private suspend fun TestScope.aCallHeldInItsCatalogFetch(
        installFirst: Boolean,
    ): Triple<DownloadProcess, Deferred<Result<InstalledModelData>>, CompletableDeferred<Unit>> {
        serve()
        val newer = entry(version = 2, text = ByteArray(8192) { 7 })
        serve(newer, text = Served.Body(ByteArray(8192) { 7 }))
        var fetches = 0
        val held = CompletableDeferred<Unit>()
        val heldAt = if (installFirst) 2 else 1
        val phone = process(
            catalog = ModelCatalogSource {
                val nth = ++fetches
                if (nth == heldAt) held.await()
                catalogData(if (nth == 1 && installFirst) entry() else newer)
            },
        )
        if (installFirst) {
            val install = downloading(phone)
            transport.run(phone.downloader)
            install.settled().getOrThrow()
        }
        val pending = downloading(phone)
        assertTrue(pending.isActive)
        return Triple(phone, pending, held)
    }

    @Test
    fun aModelRemovedWhileItsUpdateWasStillFetchingTheCatalogStaysRemoved() = runTest(UnconfinedTestDispatcher()) {
        val (phone, pending, catalogArrives) = aCallHeldInItsCatalogFetch(installFirst = true)
        val startedBefore: Int = transport.started.size

        phone.downloader.withoutDownloadOf(MODEL, ModelStoreChanges.Direct) { phone.store.remove(MODEL) }
        assertEquals(emptyList(), phone.store.installed())
        catalogArrives.complete(Unit)

        // ⚠⚠ The update had no record for the removal to end. It began all the same once the
        // catalog answered, and installed the model the user had just deleted.
        assertIs<ModelDownloadCancelledException>(pending.settled().exceptionOrNull())
        assertEquals(emptyList(), phone.store.installed(), "a removed model came back")
        assertEquals(startedBefore, transport.started.size)
        assertFalse(MODEL in journalText().orEmpty())

        // ⚠ A call made AFTER the removal is a new request, and gets its download.
        val again = downloading(phone)
        assertTrue(transport.started.size > startedBefore, "a download asked for after the removal was refused with it")
        transport.run(phone.downloader)
        assertEquals(2, again.settled().getOrThrow().modelVersion)
    }

    @Test
    fun aDownloadCancelledWhileItWasStillFetchingTheCatalogDoesNotBegin() = runTest(UnconfinedTestDispatcher()) {
        val (phone, pending, catalogArrives) = aCallHeldInItsCatalogFetch(installFirst = false)

        phone.downloader.cancel(MODEL)
        catalogArrives.complete(Unit)

        // ⚠⚠ The user tapped cancel. With no record yet the cancel found nothing, and the
        // download started afterwards.
        assertIs<ModelDownloadCancelledException>(pending.settled().exceptionOrNull())
        assertEquals(emptyList(), transport.started, "a cancelled download began")
        assertFalse(MODEL in journalText().orEmpty())

        val again = downloading(phone)
        assertEquals(3, transport.started.size)
        transport.run(phone.downloader)
        assertEquals(2, again.settled().getOrThrow().modelVersion)
    }

    @Test
    fun aSuccessorGraphSeesWhatTheGraphBeforeItCommitted() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val old = process()
        // The successor's store reads its index before the commit, and remembers it.
        val next = DownloadProcess(disk, transport, backgroundScope, newProcess = false, steps = old.steps)
        assertEquals(emptyList(), next.store.installed())
        val first = downloading(old)
        transport.run(old.downloader)
        first.settled().getOrThrow()

        val asked = downloading(next)

        // ⚠⚠ It answered from the index it had remembered: the model absent, 743 MB fetched again.
        assertEquals(old.store.installed().single(), asked.settled().getOrThrow())
        assertEquals(3, transport.started.size, "an installed model was fetched again by the successor graph")
        assertEquals(1, next.store.installed().size)
    }

    @Test
    fun twoStoresOverOneIndexNeverWriteBackWhatTheyRemembered() {
        val first = DownloadFixtures.storeOver(disk)
        val second = DownloadFixtures.storeOver(disk)
        // The second store has read the index, and remembers it empty.
        assertEquals(emptyList(), second.installed())
        val bytes = mapOf(vision to visionBytes, text to textBytes, vocabulary to vocabularyBytes)
        assertNotNull(first.add(entry(), bytes))

        // It commits another model.
        assertNotNull(second.add(entry().copy(modelId = "faces"), bytes))

        // ⚠⚠ Both records are there. The second store once wrote its remembered index plus its
        // own record, and the first store's model was gone from the index, its files with no
        // record to keep them from the next sweep.
        assertEquals(setOf(MODEL, "faces"), DownloadFixtures.storeOver(disk).installed().mapTo(mutableSetOf()) { it.modelId })

        // And a removal by the store that remembers less removes only what it was asked to.
        assertNotNull(first.add(entry().copy(modelId = "third"), bytes))
        second.remove("faces")
        assertEquals(setOf(MODEL, "third"), DownloadFixtures.storeOver(disk).installed().mapTo(mutableSetOf()) { it.modelId })
    }

    @Test
    fun aReReadOfTheIndexThatFailsForgetsWhatWasRemembered() {
        val store = DownloadFixtures.storeOver(disk)
        assertNotNull(store.add(entry(), mapOf(vision to visionBytes, text to textBytes, vocabulary to vocabularyBytes)))
        assertEquals(1, store.installed().size)
        val index = "$DEFAULT_MODELS_DIR/inference.models.index.json"
        disk.readFaults[index] = DownloadDisk.ReadFault.THROWS

        // A write reads the index from the disk first, and that read fails.
        val before: Set<String> = disk.paths.toSet()
        assertFailsWith<ModelIndexUnreadableException> { store.remove(MODEL) }
        assertEquals(before, disk.paths.toSet())
        // ⚠⚠ The disk was asked and did not answer: what was remembered is not served in its place.
        assertFailsWith<ModelIndexUnreadableException>("the store answered from memory after a read that failed") { store.installed() }

        disk.readFaults.clear()
        assertEquals(1, store.installed().size)
    }

    @Test
    fun aReaderOnASecondStoreSeesWhatTheFirstCommittedWithoutBeingTold() {
        val first = DownloadFixtures.storeOver(disk)
        val second = DownloadFixtures.storeOver(disk)
        assertEquals(emptyList(), second.installed())
        val bytes = mapOf(vision to visionBytes, text to textBytes, vocabulary to vocabularyBytes)
        val installed = assertNotNull(first.add(entry(), bytes))

        // ⚠⚠ No refresh, no downloader step: a reader (the Models list, the backend's `pathOf`,
        // readiness) on a graph whose store had read the index earlier. It once went on
        // answering "absent" until its downloader's next step.
        assertEquals(listOf(installed), second.installed(), "a second store answered from an index it only remembered")
        assertEquals(installed.pathOf(vision), second.pathOf(MODEL, vision))

        first.remove(MODEL)
        assertEquals(emptyList(), second.installed())
    }

    // ─── A delivery is two questions around the move.

    private fun TestScope.cancelNow(phone: DownloadProcess) {
        // Undispatched: it runs here, to its end (nothing holds the step lock).
        backgroundScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { phone.downloader.cancel(MODEL) }
    }

    @Test
    fun aCancelThatLandsBeforeTheMoveLeavesNoDeliveredFile() = runTest(UnconfinedTestDispatcher()) {
        serve(vision = Served.Stall, vocabulary = Served.Stall)
        val phone = process()
        downloading(phone)
        // The task was let through, and the cancel deletes the destination before the move.
        transport.whileDelivering = { cancelNow(phone) }

        assertNull(transport.end(ref(entry(), text)), "a cancelled transfer was reported")

        // ⚠⚠ The move then put the file back, for nobody: 555 MB no record names, and no report
        // to follow. Asked again after the move, the ledger says the hold is gone.
        assertEquals(emptySet(), disk.delivered(DIR), "a delivered file was left for a download that was cancelled")
        assertEquals("[]", journalText())
    }

    @Test
    fun aCancelThatLandsAfterTheMoveLeavesNoDeliveredFileEither() = runTest(UnconfinedTestDispatcher()) {
        serve(vision = Served.Stall, vocabulary = Served.Stall)
        val phone = process()
        downloading(phone)
        transport.afterTheMove = { cancelNow(phone) }

        assertNull(transport.end(ref(entry(), text)))

        assertEquals(emptySet(), disk.delivered(DIR))
    }

    @Test
    fun aDeliveryNobodyInterruptedIsKept() = runTest(UnconfinedTestDispatcher()) {
        serve(vision = Served.Stall, vocabulary = Served.Stall)
        val phone = process()
        downloading(phone)

        val ended = assertNotNull(transport.end(ref(entry(), text)))

        // ⚠ The second question takes nothing from a delivery that kept its hold.
        assertEquals(setOf(ref(entry(), text)), disk.delivered(DIR))
        ended.report(phone.downloader).join()
        assertEquals(textBytes.size.toLong(), phone.store.stagedBytes(MODEL, text, sha256(textBytes)))
    }

    // ─── Two returns that must not leave a caller waiting.

    @Test
    fun aTransportThatDoesNotSayWhatItHoldsIsSaidAndNothingIsStartedOrEnded() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        // The enumeration times out: the port answers "could not tell".
        transport.answersWhatIsLive = false

        val waiting = downloading(phone)

        // ⚠⚠ Journalled, and nothing started: a transfer that may be running is not started
        // twice. The caller was left waiting on that, with no step to come before the next
        // foreground.
        assertIs<ModelTransfersUnknownException>(waiting.settled().exceptionOrNull())
        assertEquals(
            ModelDownloadRefusal.TransfersUnknown,
            assertIs<ModelDownloadOutcome.Refused>(phone.downloader.outcomeFor(key(entry()))).reason,
        )
        assertEquals(emptyList(), transport.started)
        assertTrue(MODEL in journalText().orEmpty())
        assertTrue(phone.downloader.isDownloading(MODEL))

        // Asked again, the transport is asked again: the same record, and now its transfers.
        transport.answersWhatIsLive = true
        val again = downloading(phone)
        assertEquals(3, transport.started.size)
        transport.run(phone.downloader)
        assertEquals(phone.store.installed().single(), again.settled().getOrThrow())
    }

    @Test
    fun aResumeThatCannotReadTheJournalTellsWhoeverHadJoinedADownload() = runTest(UnconfinedTestDispatcher()) {
        serve(vision = Served.Stall, text = Served.Stall, vocabulary = Served.Stall)
        val phone = process()
        val waiting = downloading(phone)
        disk.readFaults[JOURNAL] = DownloadDisk.ReadFault.NULL_THOUGH_PRESENT

        phone.downloader.resume()

        // ⚠ The resume is what would have started again whatever the transfers had lost.
        assertIs<ModelDownloadJournalUnreadableException>(waiting.settled().exceptionOrNull())
        // Nothing was touched: the transfers run on, and the record is there when it reads again.
        assertEquals(3, transport.live.size)
        assertEquals(emptyList(), transport.cancelled)
        theJournalReadsAgain()
        assertTrue(MODEL in journalText().orEmpty())
        serve()
        transport.run(phone.downloader)
        assertEquals(1, phone.store.installed().size)
    }

    // ─── An ending and the downloads around it take effect in the order asked for.

    @Test
    fun aCancelPreemptedBeforeItsStepEndsTheDownloadItWasAskedToEndAndNotTheNextOne() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        val first = downloading(phone)
        assertEquals(3, transport.started.size)
        // The cancel is counted, and preempted before it reaches the lock, which is free.
        val goOn = CompletableDeferred<Unit>()
        phone.betweenAnEndingAndItsStep = { goOn.await() }
        val cancelling = backgroundScope.async { phone.downloader.cancel(MODEL) }
        // A download asked for after the cancel: a new request.
        val second = downloading(phone)

        // ⚠⚠ It does not begin ahead of the cancel. It did: it joined the record that was
        // about to be cancelled (or wrote its own), and the cancel then ended it.
        goOn.complete(Unit)
        cancelling.await()

        assertIs<ModelDownloadCancelledException>(first.settled().exceptionOrNull())
        assertFalse(second.isCompleted && second.await().isFailure, "the cancel ended a download asked for after it")
        assertTrue(MODEL in journalText().orEmpty(), "the later download's record was dropped by the earlier cancel")
        assertEquals(6, transport.started.size)
        assertEquals(3, transport.live.size)
        serve()
        transport.run(phone.downloader)
        assertEquals(phone.store.installed().single(), second.settled().getOrThrow())
    }

    @Test
    fun aRemovalPreemptedBeforeItsStepRemovesTheModelAndTheDownloadAskedForAfterItInstallsIt() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val install = downloading(phone)
        transport.run(phone.downloader)
        install.settled().getOrThrow()
        val startedBefore: Int = transport.started.size
        val goOn = CompletableDeferred<Unit>()
        phone.betweenAnEndingAndItsStep = { goOn.await() }
        val removing = backgroundScope.async {
            phone.downloader.withoutDownloadOf(MODEL, ModelStoreChanges.Direct) { phone.store.remove(MODEL) }
        }
        // Asked for after the removal: delete, then download again.
        val again = downloading(phone)
        // ⚠⚠ It was answered "installed" from the model the removal had not reached yet, and
        // the removal then took the model: asked for last, and left with nothing.
        assertTrue(again.isActive, "a download asked for after a removal was answered from the model about to be removed")

        goOn.complete(Unit)
        removing.await()

        assertEquals(3, transport.started.size - startedBefore)
        transport.run(phone.downloader)
        assertEquals(phone.store.installed().single(), again.settled().getOrThrow())
    }

    @Test
    fun anEndingThatFailsOrWhoseCallerLeftHoldsUpNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        // A cancel that throws: the journal cannot be read.
        disk.writeRawByteArray(JOURNAL, "[]".encodeToByteArray())
        disk.readFaults[JOURNAL] = DownloadDisk.ReadFault.THROWS
        assertFailsWith<ModelDownloadJournalUnreadableException> { phone.downloader.cancel(MODEL) }
        // And one whose caller leaves while it is preempted.
        disk.readFaults.clear()
        phone.betweenAnEndingAndItsStep = { CompletableDeferred<Unit>().await() }
        val abandoned = backgroundScope.async { phone.downloader.cancel(MODEL) }
        abandoned.cancel()
        phone.betweenAnEndingAndItsStep = {}

        // ⚠ Neither is waited for by the download asked for next.
        val waiting = downloading(phone)
        assertEquals(3, transport.started.size, "a download waits for an ending that will never finish")
        transport.run(phone.downloader)
        assertEquals(1, waiting.settled().getOrThrow().modelVersion)
    }

    // ─── What the prepare step follows (§14.17).

    @Test
    fun aCommitIsSaidOnceItsStepHasEndedARemovalInItsOwnStepAndAFailureIsNot() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Error("The network connection was lost."))
        val phone = process()
        val failing = downloading(phone)
        transport.run(phone.downloader)
        assertIs<ModelDownloadException>(failing.settled().exceptionOrNull())
        // Nothing was committed: nothing is said.
        assertEquals(emptyList(), phone.storeChanges)

        serve()
        val waiting = downloading(phone)
        transport.run(phone.downloader)
        waiting.settled().getOrThrow()
        // ⚠⚠ After the step, not inside it: whoever is told takes the step lock for itself.
        assertEquals(listOf("after the step"), phone.storeChanges)

        // ⚠⚠ A removal's follow-up runs INSIDE its step: when the removal returns, what only
        // that model kept is gone, wherever the app is.
        phone.downloader.withoutDownloadOf(MODEL, ModelStoreChanges.Direct) { phone.store.remove(MODEL) }
        assertEquals(listOf("after the step", "removal, in its step"), phone.storeChanges)
        // ⚠ And each hook is told WHICH model: the commit's, then the removal's before and after.
        assertEquals(listOf(MODEL, MODEL, MODEL), phone.changedModels)
        // And so is the wrapper the commit ran in, which stands down what serves THAT model.
        assertEquals(listOf(MODEL), phone.changes.changedFor.distinct())
    }

    @Test
    fun aCommitTheNextStepFindsAlreadyInTheStoreIsSaidToo() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val waiting = downloading(phone)
        transport.end(ref(entry(), vision))!!.report(phone.downloader).join()
        transport.end(ref(entry(), text))!!.report(phone.downloader).join()
        // The commit lands and its journal record cannot be removed: the record outlives it.
        disk.failingWrites += JOURNAL
        transport.end(ref(entry(), vocabulary))!!.report(phone.downloader).join()
        waiting.settled().getOrThrow()
        phone.storeChanges.clear()
        disk.failingWrites.clear()

        // The next step finds the record, and the store already holding exactly that.
        phone.downloader.resume()

        // ⚠⚠ It answers *installed* without committing anything, and that is said all the same:
        // it was derived from a count of commits, which this path never moved.
        assertEquals(listOf("after the step"), phone.storeChanges)
        assertEquals("[]", journalText())
    }

    @Test
    fun aRemovalWhoseOwnPreparationThrowsRemovesNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val waiting = downloading(phone)
        transport.run(phone.downloader)
        waiting.settled().getOrThrow()
        phone.storeChanges.clear()
        // What goes before the record (on iOS, the compiled towers) cannot be deleted.
        phone.beforeRemoval = { throw IllegalStateException("could not delete the compiled directory") }
        var removed = false

        val failure = runCatching {
            phone.downloader.withoutDownloadOf(MODEL, ModelStoreChanges.Direct) {
                removed = true
                phone.store.remove(MODEL)
            }
        }.exceptionOrNull()

        // ⚠⚠ The removal fails, and nothing was removed: no "deleted" for a model whose files stayed.
        assertIs<IllegalStateException>(failure)
        assertFalse(removed, "the record was removed although what goes before it did not go")
        assertEquals(1, phone.store.installed().size)
        assertEquals(emptyList(), phone.storeChanges)
    }
}

package org.tenshu.inference.proxy.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.inference.proxy.catalog.DownloadFixtures.BASE
import org.tenshu.inference.proxy.catalog.DownloadFixtures.DIR
import org.tenshu.inference.proxy.catalog.DownloadFixtures.JOURNAL
import org.tenshu.inference.proxy.catalog.DownloadFixtures.MODEL
import org.tenshu.inference.proxy.catalog.DownloadFixtures.entry
import org.tenshu.inference.proxy.catalog.DownloadFixtures.key
import org.tenshu.inference.proxy.catalog.DownloadFixtures.ref
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
 * The model download (`ios-on-device-inference.md` §14.11) over a transport that outlives the
 * process.
 *
 * ⭐ The store is the real [DefaultModelStore] and the journal is the real file, over a disk that
 * can be listed and can fail. Only the transport is a fake, and it is one the cases steer: it
 * holds every transfer until the case says what the network did.
 *
 * ⚠ A "relaunch" is a new [DownloadProcess] over the same disk and transport: a fresh store, a
 * fresh downloader, no memory of anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundModelDownloaderTest {

    private val vision = ModelArtifactRole.VISION
    private val text = ModelArtifactRole.TEXT
    private val vocabulary = ModelArtifactRole.VOCABULARY

    private val disk = DownloadDisk()
    private val transport = FakeTransport(disk, DIR)

    private fun serve(of: ModelEntryData? = null, vision: Served? = null, text: Served? = null, vocabulary: Served? = null) {
        val offered: ModelEntryData = of ?: entry()
        transport.served[url(offered, this.vision)] = vision ?: Served.Body(visionBytes)
        transport.served[url(offered, this.text)] = text ?: Served.Body(textBytes)
        transport.served[url(offered, this.vocabulary)] = vocabulary ?: Served.Body(vocabularyBytes)
    }

    private fun TestScope.process(
        entry: () -> ModelEntryData? = { DownloadFixtures.entry() },
        transportInstalled: () -> Boolean = { true },
        retired: () -> Boolean = { false },
        need: (Long, Long) -> Long = CoreMlDiskBudget::additionalNeed,
        newProcess: Boolean = true,
    ) = DownloadProcess(disk, transport, backgroundScope, entry, transportInstalled, retired, need, newProcess = newProcess)

    private fun TestScope.downloading(process: DownloadProcess, allowsCellular: Boolean = false): Deferred<Result<InstalledModelData>> =
        // ⚠ On the background scope: a process that "dies" in a case leaves its waiter waiting.
        backgroundScope.async { runCatching { process.downloader.download(MODEL, allowsCellular) } }

    /**
     * The download's answer, which must be there by now. ⚠ Asserted before it is awaited: a
     * download that never ends is a red case naming what did not happen, not a timeout.
     */
    private suspend fun Deferred<Result<InstalledModelData>>.settled(): Result<InstalledModelData> {
        assertTrue(isCompleted, "the download has not ended")
        return await()
    }

    /**
     * The refusal of a download that must not have started. ⚠ Never `assertFailsWith` around
     * `download` itself: a download that was wrongly let through waits for its transfers, and the
     * case would end as a timeout and not as what it is.
     */
    private suspend inline fun <reified T : Throwable> TestScope.refused(
        process: DownloadProcess,
        allowsCellular: Boolean = false,
        message: String? = null,
    ): T {
        val attempt = downloading(process, allowsCellular)
        assertTrue(attempt.isCompleted, "${message.orEmpty()} the download was let through".trim())
        return assertIs<T>(attempt.await().exceptionOrNull(), message)
    }

    private fun journalText(): String? = disk.readBytes(JOURNAL)?.get()?.decodeToString()

    private fun delivered(role: String): String =
        BackgroundModelDownloader.deliveredPath(DIR, ref(entry(), role))

    // ─── The order of a download.

    @Test
    fun aDownloadIsJournalledBeforeAnyTransferAndInstalledOnceEveryArtifactIsVerified() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()

        val waiting = downloading(phone)

        // Journalled: the entry, every digest and every ref, and the Wi-Fi-only choice.
        val journal: String = assertNotNull(journalText())
        entry().artifacts.forEach { artifact ->
            assertTrue(artifact.sha256 in journal)
            assertTrue(BackgroundModelDownloader.refOf(MODEL, artifact, allowsCellular = false) in journal)
        }
        assertTrue("\"allowsCellular\":false" in journal)
        // One plain request an artifact, to the catalog's own path.
        assertEquals(
            listOf("$BASE/siglip/1/vision.tar", "$BASE/siglip/1/text.tar", "$BASE/siglip/1/tokenizer.bin"),
            transport.started.map { it.url },
        )
        assertTrue(waiting.isActive)
        assertEquals(emptyList(), phone.store.installed())

        transport.run(phone.downloader)

        val installed: InstalledModelData = waiting.settled().getOrThrow()
        // What is reported is the store's own record.
        assertEquals(phone.store.installed().single(), installed)
        assertEquals(visionBytes.size.toLong() + textBytes.size + vocabularyBytes.size, installed.byteSize)
        assertContentEquals(visionBytes, disk.readBytes(installed.pathOf(vision)!!)?.get())
        assertContentEquals(textBytes, disk.readBytes(installed.pathOf(text)!!)?.get())
        // Nothing is left behind: no record, no delivered file, no transfer.
        assertEquals("[]", journalText())
        assertEquals(emptySet(), disk.delivered(DIR))
        assertEquals(emptySet(), transport.live.keys)
        assertEquals(ModelDownloadOutcome.Installed(key(entry()), installed), phone.downloader.outcomeFor(key(entry())))
        assertFalse(phone.downloader.isDownloading(MODEL))
        assertEquals(emptyMap(), phone.progress.progress.value)
    }

    @Test
    fun aJournalThatCannotBeWrittenStartsNoTransfer() = runTest(UnconfinedTestDispatcher()) {
        serve()
        disk.failingWrites += JOURNAL
        val phone = process()

        refused<IllegalStateException>(phone)

        // Nothing was handed to the transport: a transfer nothing recorded cannot be verified.
        assertEquals(emptyList(), transport.started)
        assertNull(journalText())
    }

    @Test
    fun withNoTransportTheDownloadIsRefusedAndNothingElseIsTried() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process(transportInstalled = { false })

        refused<ModelTransportUnavailableException>(phone)

        assertNull(journalText())
        assertEquals(
            ModelDownloadOutcome.Refused(key(entry()), ModelDownloadRefusal.NoTransport),
            phone.downloader.outcomeFor(key(entry())),
        )
    }

    // ─── D6: Wi-Fi only, enforced in the request.

    @Test
    fun theRequestRefusesCellularUnlessThatDownloadsChoiceAllowsIt() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()

        downloading(phone)
        assertEquals(listOf(false, false, false), transport.started.map { it.allowsCellular })

        phone.downloader.cancel(MODEL)
        transport.started.clear()

        downloading(phone, allowsCellular = true)
        assertEquals(listOf(true, true, true), transport.started.map { it.allowsCellular })
    }

    @Test
    fun theDownloadEveryOtherCallerKnowsIsWifiOnly() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        // `ModelDownloader.download`, which is all the `DownloadModel` operation can call.
        val plain: ModelDownloader = phone.downloader

        backgroundScope.async { runCatching { plain.download(MODEL) } }

        assertEquals(listOf(false, false, false), transport.started.map { it.allowsCellular })
        assertTrue("\"allowsCellular\":false" in journalText().orEmpty())
    }

    @Test
    fun aDownloadStartedOnWifiWaitsOnCellularAndIsNotFailed() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val waiting = downloading(phone)

        transport.network = FakeTransport.Network.CELLULAR
        transport.run(phone.downloader)

        // It waits: nothing arrived, nothing failed, the transfers are still the transport's.
        assertTrue(waiting.isActive)
        assertEquals(3, transport.live.size)
        assertEquals(emptySet(), disk.delivered(DIR))
        assertNull(phone.downloader.outcomeFor(key(entry())))
        assertTrue(phone.downloader.isDownloading(MODEL))

        transport.network = FakeTransport.Network.WIFI
        transport.run(phone.downloader)

        assertEquals(phone.store.installed().single(), waiting.settled().getOrThrow())
    }

    @Test
    fun aDownloadThatAllowsCellularProceedsThere() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val waiting = downloading(phone, allowsCellular = true)

        transport.network = FakeTransport.Network.CELLULAR
        transport.run(phone.downloader)

        assertEquals(phone.store.installed().single(), waiting.settled().getOrThrow())
    }

    @Test
    fun aTransferStartedUnderTheOtherChoiceNeverCarriesADownload() = runTest(UnconfinedTestDispatcher()) {
        serve()
        // A download that allowed cellular was cancelled, and the process died before the cancel
        // reached the transport: its three transfers are still there, with their old requests.
        val allowed = process()
        downloading(allowed, allowsCellular = true)
        val stale: Set<String> = transport.live.keys.toSet()
        disk.deleteFile(JOURNAL)
        transport.started.clear()

        // The next process is asked for the same model, Wi-Fi only.
        val phone = process()
        val waiting = downloading(phone)

        // ⚠⚠ Not one of them is taken for this download: each would spend cellular data on a
        // download that may not. Three transfers of its own, refusing cellular.
        assertEquals(listOf(false, false, false), transport.started.map { it.allowsCellular })
        assertTrue(transport.started.none { it.ref in stale })
        // And the stale ones are transfers no record names: the next resume cancels them.
        phone.downloader.resume()
        assertEquals(stale, transport.cancelled.toSet())
        transport.run(phone.downloader)
        assertEquals(phone.store.installed().single(), waiting.settled().getOrThrow())
    }

    @Test
    fun aRelaunchStartsALostTransferAgainWithTheChoiceThatWasJournalled() = runTest(UnconfinedTestDispatcher()) {
        serve()
        downloading(process(), allowsCellular = true)
        // The transport lost everything (the user force-quit the app: iOS cancels its transfers).
        transport.live.clear()
        transport.started.clear()

        process().downloader.resume()

        assertEquals(listOf(true, true, true), transport.started.map { it.allowsCellular })
    }

    @Test
    fun aSecondCallJoinsTheRunningDownloadAndAnotherCellularChoiceIsRefused() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val first = downloading(phone)
        val second = downloading(phone)

        // Joined: no second set of transfers.
        assertEquals(3, transport.started.size)

        // ⚠ The first choice stands: a download that may not use cellular is not quietly made
        // into one that may by asking again.
        refused<ModelDownloadChoiceConflictException>(phone, allowsCellular = true)
        assertEquals(listOf(false, false, false), transport.started.map { it.allowsCellular })

        transport.run(phone.downloader)
        assertEquals(first.settled().getOrThrow(), second.settled().getOrThrow())
    }

    // ─── The free-space check.

    @Test
    fun belowTheAdditionalNeedTheDownloadIsRefusedBeforeAnythingIsFetched() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val total: Long = entry().byteSize
        val need: Long = CoreMlDiskBudget.additionalNeed(total, total)
        val phone = process(need = CoreMlDiskBudget::additionalNeed)

        disk.free = need - 1
        val refused = refused<ModelStorageInsufficientException>(phone)

        assertEquals(need, refused.neededBytes)
        assertEquals(need - 1, refused.freeBytes)
        assertEquals(emptyList(), transport.started)
        assertNull(journalText())
        assertEquals(
            ModelDownloadOutcome.Refused(key(entry()), ModelDownloadRefusal.InsufficientStorage(need, need - 1)),
            phone.downloader.outcomeFor(key(entry())),
        )

        // Exactly the need is enough.
        disk.free = need
        val waiting = downloading(phone)
        transport.run(phone.downloader)
        assertEquals(phone.store.installed().single(), waiting.settled().getOrThrow())
    }

    @Test
    fun anUpdateNeedsTheSameAsAFirstInstall() = runTest(UnconfinedTestDispatcher()) {
        // An older generation is installed: nothing of it is credited (§3.4).
        val older = entry(vision = ByteArray(4096) { 1 }, text = ByteArray(8192) { 2 }, version = 1)
        val newer = entry(version = 2)
        var offered: ModelEntryData = older
        serve(older, vision = Served.Body(ByteArray(4096) { 1 }), text = Served.Body(ByteArray(8192) { 2 }))
        serve(newer)
        val phone = process(entry = { offered }, need = CoreMlDiskBudget::additionalNeed)
        val first = downloading(phone)
        transport.run(phone.downloader)
        first.settled().getOrThrow()

        offered = newer
        // ⚠ Both towers are new; the vocabulary is the same bytes and is installed already, so
        // it is neither fetched nor budgeted again. Nothing of the OLD towers is credited.
        val towers: Long = newer.byteSize - vocabularyBytes.size
        val need: Long = CoreMlDiskBudget.additionalNeed(towers, towers)
        assertEquals(CoreMlDiskBudget.additionalNeed(older.byteSize - vocabularyBytes.size, older.byteSize - vocabularyBytes.size), need)

        disk.free = need - 1
        refused<ModelStorageInsufficientException>(phone)
        assertEquals(older.artifacts.map { it.sha256 }, phone.store.installed().single().artifacts.map { it.sha256 })

        disk.free = need
        transport.started.clear()
        val update = downloading(phone)
        assertEquals(listOf(url(newer, vision), url(newer, text)), transport.started.map { it.url })
        transport.run(phone.downloader)
        assertEquals(newer.artifacts.map { it.sha256 }, update.settled().getOrThrow().artifacts.map { it.sha256 })
    }

    @Test
    fun freeSpaceThatCannotBeReadIsARefusalNotAGuess() = runTest(UnconfinedTestDispatcher()) {
        serve()
        disk.free = null
        val phone = process(need = CoreMlDiskBudget::additionalNeed)

        refused<ModelFreeSpaceUnknownException>(phone)

        assertEquals(emptyList(), transport.started)
        assertNull(journalText())
        assertEquals(
            ModelDownloadOutcome.Refused(key(entry()), ModelDownloadRefusal.FreeSpaceUnknown),
            phone.downloader.outcomeFor(key(entry())),
        )
    }

    @Test
    fun whatAnEarlierAttemptStagedIsNeitherFetchedNorBudgetedAgain() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Error("the connection was lost"))
        val phone = process(need = CoreMlDiskBudget::additionalNeed)
        val first = downloading(phone)
        transport.run(phone.downloader)
        assertIs<ModelDownloadException>(first.settled().exceptionOrNull())
        transport.started.clear()

        // Vision had arrived and is staged; the text tower failed and took the vocabulary's
        // transfer with it. Only those two are left to fetch, so only they are budgeted for.
        serve()
        val total: Long = entry().byteSize
        val need: Long = CoreMlDiskBudget.additionalNeed(total, textBytes.size.toLong() + vocabularyBytes.size)
        assertTrue(need < CoreMlDiskBudget.additionalNeed(total, total))
        disk.free = need - 1
        assertEquals(need, refused<ModelStorageInsufficientException>(phone).neededBytes)

        disk.free = need
        val second = downloading(phone)
        // The bar starts at what is held.
        assertEquals(visionBytes.size.toLong(), phone.progress.progress.value[MODEL]?.writtenBytes)
        assertEquals(listOf(url(entry(), text), url(entry(), vocabulary)), transport.started.map { it.url })
        transport.run(phone.downloader)
        assertEquals(phone.store.installed().single(), second.settled().getOrThrow())
    }

    // ─── Integrity: only the catalog's bytes are ever installed.

    /**
     * ⚠⚠ Whatever the transport assembled. The second is the resume answered `200`: the whole body
     * after bytes already held. Nothing above the transport appends, so this is what a transport
     * that did would deliver; it is caught whole, by size.
     */
    @Test
    fun whateverTheTransportDeliveredOnlyTheCatalogsBytesAreInstalled() = runTest(UnconfinedTestDispatcher()) {
        val wrong: Map<String, ByteArray> = mapOf(
            "a short body" to textBytes.copyOf(textBytes.size - 1),
            "the whole body appended to a partial one" to textBytes.copyOf(1000) + textBytes,
            "the right length and other bytes" to textBytes.copyOf().also { it[4000] = (it[4000] + 1).toByte() },
            "an error page with a success status" to "<html>not found</html>".encodeToByteArray(),
            "nothing" to ByteArray(0),
        )
        wrong.forEach { (what, body) ->
            run {
                val disk = DownloadDisk()
                val transport = FakeTransport(disk, DIR)
                transport.served[url(entry(), vision)] = Served.Body(visionBytes)
                transport.served[url(entry(), text)] = Served.Body(body)
                transport.served[url(entry(), vocabulary)] = Served.Body(vocabularyBytes)
                val phone = DownloadProcess(disk, transport, backgroundScope)
                val waiting = backgroundScope.async { runCatching { phone.downloader.download(MODEL) } }

                transport.run(phone.downloader)

                val failure = waiting.settled().exceptionOrNull()
                // ⚠⚠ Only bytes of the RIGHT LENGTH with another digest are an integrity failure
                // ("asking again fetches the same bytes"). Another length is what a transfer cut
                // short, or a resume the CDN answered with a whole body, leaves: a retry works
                // (§14.12), so it is not reported as the one failure a retry cannot cure.
                val sameLength: Boolean = body.size == textBytes.size
                if (sameLength) assertIs<ModelIntegrityException>(failure, what) else assertIs<ModelDownloadException>(failure, what)
                // Discarded, not staged, not committed.
                assertEquals(emptyList(), phone.store.installed(), what)
                assertTrue(BackgroundModelDownloader.deliveredPath(DIR, ref(entry(), text)) in disk.deleted, what)
                assertEquals(emptySet(), disk.delivered(DIR), what)
                assertEquals("[]", disk.readBytes(JOURNAL)?.get()?.decodeToString(), what)
                val outcome = assertIs<ModelDownloadOutcome.Failed>(phone.downloader.outcomeFor(key(entry())), what)
                assertEquals(sameLength, outcome.integrity, what)

                // What had arrived whole before it is kept: the retry does not fetch vision again.
                // (The vocabulary's transfer was still running, and went with the download.)
                transport.started.clear()
                transport.served[url(entry(), text)] = Served.Body(textBytes)
                val retry = backgroundScope.async { runCatching { phone.downloader.download(MODEL) } }
                assertEquals(listOf(url(entry(), text), url(entry(), vocabulary)), transport.started.map { it.url }, what)
                transport.run(phone.downloader)
                assertEquals(phone.store.installed().single(), retry.settled().getOrThrow(), what)
            }
        }
    }

    @Test
    fun aCorruptedFileIsDiscardedEvenWhenTheFailureCannotBeRecorded() = runTest(UnconfinedTestDispatcher()) {
        val wrong: Map<String, ByteArray> = mapOf(
            "other bytes" to textBytes.copyOf().also { it[0] = 99 },
            "another length" to textBytes.copyOf(textBytes.size + 1),
        )
        wrong.forEach { (what, body) ->
            run {
                val disk = DownloadDisk()
                val transport = FakeTransport(disk, DIR)
                transport.served[url(entry(), vision)] = Served.Body(visionBytes)
                transport.served[url(entry(), text)] = Served.Body(body)
                transport.served[url(entry(), vocabulary)] = Served.Body(vocabularyBytes)
                val phone = DownloadProcess(disk, transport, backgroundScope)
                val waiting = backgroundScope.async { runCatching { phone.downloader.download(MODEL) } }
                transport.runUnheard()
                disk.failingWrites += JOURNAL
                val file: String = BackgroundModelDownloader.deliveredPath(DIR, ref(entry(), text))

                phone.downloader.resume()

                // ⚠ The bytes are wrong whatever the journal can be made to say: they go at
                // once, and are not hashed again at every launch.
                assertTrue(file in disk.deleted, what)
                assertNull(disk.fileSize(file), what)
                // The record could not be removed: the download is still journalled, and the
                // caller is told what happened to it rather than left waiting.
                assertTrue(waiting.isCompleted, what)
                assertTrue(waiting.await().isFailure, what)
                assertIs<ModelDownloadOutcome.Failed>(phone.downloader.outcomeFor(key(entry())), what)
                assertEquals(emptyList(), phone.store.installed(), what)
            }
        }
    }

    @Test
    fun aDeliveredFileThatCannotBeReadIsLeftAndNothingIsConcludedFromIt() = runTest(UnconfinedTestDispatcher()) {
        DownloadDisk.ReadFault.entries.forEach { fault ->
            run {
                val disk = DownloadDisk()
                val transport = FakeTransport(disk, DIR)
                entry().artifacts.forEach { transport.served["$BASE/${it.path}"] = Served.Stall }
                transport.served[url(entry(), text)] = Served.Body(textBytes)
                backgroundScope.async { runCatching { DownloadProcess(disk, transport, backgroundScope).downloader.download(MODEL) } }
                transport.runUnheard()
                val file: String = BackgroundModelDownloader.deliveredPath(DIR, ref(entry(), text))
                // What a phone does before its first unlock: the file is there and gives no bytes.
                disk.readFaults[file] = fault
                val relaunched = DownloadProcess(disk, transport, backgroundScope)

                relaunched.downloader.resume()
                relaunched.downloader.settleFinished(ref(entry(), text), 200, null)

                // ⚠ Not hashed is not wrong: it is neither discarded, nor staged, nor swept, and
                // the download has not failed.
                assertEquals(emptyList(), disk.deleted, fault.name)
                assertEquals(textBytes.size.toLong(), disk.fileSize(file), fault.name)
                assertNull(relaunched.downloader.outcomeFor(key(entry())), fault.name)
                assertTrue(relaunched.downloader.isDownloading(MODEL), fault.name)
                assertEquals(0L, DownloadFixtures.storeOver(disk).beginStaging(MODEL + "-probe", text, "x"), fault.name)

                disk.readFaults.clear()
                transport.served[url(entry(), vision)] = Served.Body(visionBytes)
                transport.served[url(entry(), vocabulary)] = Served.Body(vocabularyBytes)
                relaunched.downloader.resume()
                transport.run(relaunched.downloader)
                assertEquals(1, relaunched.store.installed().size, fault.name)
            }
        }
    }

    @Test
    fun stagedBytesThatCannotBeReadAreNotFetchedAgain() = runTest(UnconfinedTestDispatcher()) {
        DownloadDisk.ReadFault.entries.forEach { fault ->
            run {
                val disk = DownloadDisk()
                val transport = FakeTransport(disk, DIR)
                entry().artifacts.forEach { transport.served["$BASE/${it.path}"] = Served.Stall }
                transport.served[url(entry(), text)] = Served.Body(textBytes)
                backgroundScope.async { runCatching { DownloadProcess(disk, transport, backgroundScope).downloader.download(MODEL) } }
                // The text tower arrives and an earlier process stages it; then that process dies
                // and its transfer bookkeeping with it (the session holds nothing for the tower).
                transport.runUnheard()
                DownloadProcess(disk, transport, backgroundScope).downloader.resume()
                val staging = "$DEFAULT_MODELS_DIR/$MODEL.$text.model.part"
                assertEquals(textBytes.size.toLong(), disk.fileSize(staging), fault.name)
                transport.started.clear()
                disk.deleted.clear()

                disk.readFaults[staging] = fault
                val relaunched = DownloadProcess(disk, transport, backgroundScope)
                relaunched.downloader.resume()

                // ⚠ Not read is not missing: 555 MB are not fetched again because a read failed,
                // and the bytes that are there are not dropped.
                assertEquals(emptyList(), transport.started, fault.name)
                assertEquals(emptyList(), disk.deleted, fault.name)
                assertEquals(textBytes.size.toLong(), disk.fileSize(staging), fault.name)

                disk.readFaults.clear()
                transport.served[url(entry(), vision)] = Served.Body(visionBytes)
                transport.served[url(entry(), vocabulary)] = Served.Body(vocabularyBytes)
                relaunched.downloader.resume()
                transport.run(relaunched.downloader)
                assertEquals(emptyList(), transport.started, fault.name)
                assertEquals(1, relaunched.store.installed().size, fault.name)
            }
        }
    }

    @Test
    fun aLateFailureReportForAnArtifactThatIsStagedFailsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        val waiting = downloading(phone)
        transport.run(phone.downloader)
        assertEquals(setOf(ref(entry(), text)), transport.live.keys)

        // A report about a transfer whose file was settled long ago (an earlier task under the
        // same ref, a replayed event): nothing was delivered *now*, and the artifact is staged.
        phone.downloader.settleFinished(ref(entry(), vision), -1, "The operation couldn't be completed.")

        assertTrue(waiting.isActive)
        assertNull(phone.downloader.outcomeFor(key(entry())))
        assertEquals(emptyList(), transport.cancelled)
        assertTrue(MODEL in journalText().orEmpty())

        serve()
        transport.run(phone.downloader)
        assertEquals(phone.store.installed().single(), waiting.settled().getOrThrow())
    }

    @Test
    fun aDownloadIsRunningFromTheMomentItIsAskedForAndOnlyInTheProcessThatWorksOnIt() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val catalogArrives = CompletableDeferred<Unit>()
        val phone = DownloadProcess(
            disk, transport, backgroundScope,
            catalog = ModelCatalogSource {
                catalogArrives.await()
                ModelCatalogData(schemaVersion = 1, buildDate = "2026-10-11", models = listOf(entry()))
            },
        )
        assertFalse(phone.downloader.isDownloading(MODEL))

        val waiting = downloading(phone)

        // ⚠ Already running, with nothing journalled and no progress entry yet: the catalog
        // fetch can take tens of seconds, and a progress stream that read "not running" here
        // would end before the download began.
        assertNull(journalText())
        assertEquals(emptyMap(), phone.progress.progress.value)
        assertTrue(phone.downloader.isDownloading(MODEL))

        catalogArrives.complete(Unit)
        assertTrue(phone.downloader.isDownloading(MODEL))
        assertNotNull(phone.progress.progress.value[MODEL])

        // ⚠⚠ A process that was only just relaunched, asked before its launch check has resumed
        // anything: the transfers are live, so the download is running, and has a bar. A screen
        // told "not downloading" here offers Download, or Delete, over a running download.
        val relaunched = process()
        assertTrue(relaunched.downloader.isDownloading(MODEL))
        assertEquals(entry().byteSize, relaunched.progress.progress.value[MODEL]?.totalBytes)
        // It looked, and did nothing else.
        assertEquals(3, transport.started.size)
        relaunched.downloader.resume()
        assertTrue(relaunched.downloader.isDownloading(MODEL))

        phone.downloader.cancel(MODEL)
        assertFalse(phone.downloader.isDownloading(MODEL))
        assertIs<ModelDownloadCancelledException>(waiting.settled().exceptionOrNull())
    }

    @Test
    fun aRefusedDownloadIsNotRunning() = runTest(UnconfinedTestDispatcher()) {
        serve()
        disk.free = 0
        val phone = process(need = CoreMlDiskBudget::additionalNeed)

        refused<ModelStorageInsufficientException>(phone)

        assertFalse(phone.downloader.isDownloading(MODEL))
    }

    @Test
    fun aTransferThatEndsWithAnErrorStatusFailsAsTransportAndInstallsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Body("denied".encodeToByteArray(), status = 403))
        val phone = process()
        val waiting = downloading(phone)

        transport.run(phone.downloader)

        val failure = assertIs<ModelDownloadException>(waiting.settled().exceptionOrNull())
        assertTrue("403" in failure.message.orEmpty())
        assertEquals(emptyList(), phone.store.installed())
        // Not an integrity failure: asking again is worth it.
        assertFalse(assertIs<ModelDownloadOutcome.Failed>(phone.downloader.outcomeFor(key(entry()))).integrity)
        assertEquals("[]", journalText())
        assertFalse(phone.downloader.isDownloading(MODEL))
    }

    @Test
    fun aFailureWhoseRecordCouldNotBeRemovedEndsNothingAndIsSaid() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Body("denied".encodeToByteArray(), status = 403))
        val phone = process()
        val waiting = downloading(phone)
        disk.failingWrites += JOURNAL

        transport.run(phone.downloader)

        // ⚠ The journal still says the download is running: nothing is cancelled or released,
        // and the next settle meets it again.
        // ⚠⚠ But the caller is told, not left waiting on a download whose failed transfer
        // nothing starts again before the next foreground.
        assertIs<ModelDownloadException>(waiting.settled().exceptionOrNull())
        assertTrue(phone.downloader.isDownloading(MODEL))
        assertEquals(emptyList(), transport.cancelled)
        assertTrue(MODEL in journalText().orEmpty())

        disk.failingWrites.clear()
        serve()
        phone.downloader.resume()
        transport.run(phone.downloader)
        assertEquals(1, phone.store.installed().size)
    }

    @Test
    fun aCancelWhoseRecordCouldNotBeRemovedCancelsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        val waiting = downloading(phone)
        transport.runUnheard()
        disk.failingWrites += JOURNAL
        disk.deleted.clear()

        assertFailsWith<IllegalStateException> { phone.downloader.cancel(MODEL) }

        // The record stayed, so everything it names stayed with it.
        assertEquals(emptyList(), transport.cancelled)
        assertEquals(emptyList(), disk.deleted)
        assertTrue(waiting.isActive)
        assertNull(phone.downloader.outcomeFor(key(entry())))
    }

    @Test
    fun bytesAnEarlierProcessStagedAreHashedAgainAndWrongOnesAreDropped() = runTest(UnconfinedTestDispatcher()) {
        serve()
        // Staging holds the text tower's length under the text tower's digest, and other bytes.
        val earlier = DownloadFixtures.storeOver(disk)
        earlier.beginStaging(MODEL, text, entry().artifact(text)!!.sha256)
        earlier.writeStaged(MODEL, text, 0, ByteArray(textBytes.size) { 3 })
        val phone = process()

        val waiting = downloading(phone)

        // ⚠ The marker and the length are not the digest: it is fetched like the other two.
        assertEquals(3, transport.started.size)
        transport.run(phone.downloader)
        val installed: InstalledModelData = waiting.settled().getOrThrow()
        assertContentEquals(textBytes, disk.readBytes(installed.pathOf(text)!!)?.get())
    }

    @Test
    fun aStalledTransferInstallsNothingAndCancelEndsIt() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        val waiting = downloading(phone)

        transport.run(phone.downloader)
        transport.run(phone.downloader)

        // The two that arrived are staged; nothing is installed on two of three.
        assertTrue(waiting.isActive)
        assertEquals(emptyList(), phone.store.installed())
        assertEquals(setOf(ref(entry(), text)), transport.live.keys)
        assertTrue(phone.downloader.isDownloading(MODEL))

        phone.downloader.cancel(MODEL)

        assertIs<ModelDownloadCancelledException>(waiting.settled().exceptionOrNull())
        assertEquals(listOf(ref(entry(), vision), ref(entry(), text), ref(entry(), vocabulary)), transport.cancelled)
        assertEquals("[]", journalText())
        assertEquals(ModelDownloadOutcome.Cancelled(key(entry())), phone.downloader.outcomeFor(key(entry())))
        assertEquals(emptyMap(), phone.progress.progress.value)
    }

    // ─── A relaunch: no memory, only the journal.

    @Test
    fun aRelaunchWithNoMemoryOfTheDownloadInstallsItFromTheJournal() = runTest(UnconfinedTestDispatcher()) {
        serve()
        downloading(process())
        // The app is dead; the transport finishes all three and nobody hears of it.
        transport.runUnheard()
        assertEquals(3, disk.delivered(DIR).size)

        val relaunched = process()
        relaunched.downloader.resume()

        val installed: InstalledModelData = relaunched.store.installed().single()
        assertContentEquals(textBytes, disk.readBytes(installed.pathOf(text)!!)?.get())
        assertEquals(emptyList(), transport.started.drop(3))
        assertEquals("[]", journalText())
        assertEquals(emptySet(), disk.delivered(DIR))
        assertEquals(ModelDownloadOutcome.Installed(key(entry()), installed), relaunched.downloader.outcomeFor(key(entry())))
    }

    @Test
    fun aFileCorruptedWhileTheAppWasDeadIsDiscardedAtTheRelaunchAndNotCommitted() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Body(textBytes.copyOf().also { it[0] = 99 }))
        downloading(process())
        transport.runUnheard()

        val relaunched = process()
        relaunched.downloader.resume()

        assertEquals(emptyList(), relaunched.store.installed())
        assertTrue(delivered(text) in disk.deleted)
        assertEquals(emptySet(), disk.delivered(DIR))
        assertTrue(assertIs<ModelDownloadOutcome.Failed>(relaunched.downloader.outcomeFor(key(entry()))).integrity)

        // ⚠ Its two siblings had arrived whole and were staged before anything was concluded:
        // the next attempt fetches the one artifact that was wrong.
        transport.started.clear()
        serve()
        val retry = downloading(relaunched)
        assertEquals(listOf(url(entry(), text)), transport.started.map { it.url })
        transport.run(relaunched.downloader)
        assertEquals(relaunched.store.installed().single(), retry.settled().getOrThrow())
    }

    @Test
    fun aRelaunchMidTransferStartsNothingTwiceAndStartsAgainOnlyWhatTheTransportLost() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        downloading(process())
        // Vision arrived unheard; the text tower is still moving; the vocabulary's transfer is gone.
        transport.live.remove(ref(entry(), vocabulary))
        transport.served[url(entry(), vocabulary)] = Served.Stall
        transport.runUnheard()
        transport.started.clear()

        val relaunched = process()
        relaunched.downloader.resume()

        assertEquals(listOf(url(entry(), vocabulary)), transport.started.map { it.url })
        assertEquals(emptyList(), relaunched.store.installed())
        // The bar starts from what is already held, not from zero.
        assertEquals(visionBytes.size.toLong(), relaunched.progress.progress.value[MODEL]?.writtenBytes)

        serve()
        transport.run(relaunched.downloader)
        assertEquals(1, relaunched.store.installed().size)
    }

    @Test
    fun whenTheTransportCannotSayWhatItHoldsNothingIsStartedAgainOrCancelled() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        downloading(process())
        transport.started.clear()
        transport.live["a.transfer.nobody-named"] = ModelTransferRequest("a.transfer.nobody-named", "$BASE/x", false)
        transport.answersWhatIsLive = false

        process().downloader.resume()

        // ⚠ Unknown is not none: reading it as none would start every artifact a second time.
        assertEquals(emptyList(), transport.started)
        assertEquals(emptyList(), transport.cancelled)
    }

    @Test
    fun aCommitWhoseJournalWriteDidNotLandIsNotFetchedAgain() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val waiting = downloading(phone)
        // The commit lands; the write that removes the record does not.
        disk.failingWrites += JOURNAL
        transport.run(phone.downloader)
        val installed: InstalledModelData = waiting.settled().getOrThrow()
        assertTrue(MODEL in journalText().orEmpty())
        disk.failingWrites.clear()
        transport.started.clear()

        process().downloader.resume()

        assertEquals(emptyList(), transport.started)
        assertEquals("[]", journalText())
        assertEquals(installed, DownloadFixtures.storeOver(disk).installed().single())
    }

    // ─── The journal's read contract (§13.7's, on this file).

    @Test
    fun anUnreadableJournalStartsNothingDeletesNothingAndIsReadAgain() = runTest(UnconfinedTestDispatcher()) {
        DownloadDisk.ReadFault.entries.forEach { fault ->
            run {
                val disk = DownloadDisk()
                val transport = FakeTransport(disk, DIR)
                entry().artifacts.forEach { transport.served["$BASE/${it.path}"] = Served.Stall }
                backgroundScope.async { runCatching { DownloadProcess(disk, transport, backgroundScope).downloader.download(MODEL) } }
                // Dead. Vision arrives; a transfer and a file nothing names are there too.
                transport.served[url(entry(), vision)] = Served.Body(visionBytes)
                transport.runUnheard()
                transport.live["a.transfer.nobody-named"] = ModelTransferRequest("a.transfer.nobody-named", "$BASE/x", false)
                val stray: String = BackgroundModelDownloader.deliveredPath(DIR, "a.file.nobody-named")
                disk.writeRawByteArray(stray, ByteArray(10))
                val journalBefore: String? = disk.fileSize(JOURNAL)?.toString()
                transport.started.clear()
                disk.deleted.clear()

                disk.readFaults[JOURNAL] = fault
                val relaunched = DownloadProcess(disk, transport, backgroundScope)
                relaunched.downloader.resume()
                relaunched.downloader.settleFinished("a.file.nobody-named", 200, null)
                relaunched.downloader.settleFinished(ref(entry(), vision), 200, null)

                // Nothing started, cancelled, deleted, staged or written on the strength of it.
                assertEquals(emptyList(), transport.started, fault.name)
                assertEquals(emptyList(), transport.cancelled, fault.name)
                assertEquals(emptyList(), disk.deleted, fault.name)
                assertNotNull(disk.fileSize(stray), fault.name)
                assertNotNull(disk.fileSize(BackgroundModelDownloader.deliveredPath(DIR, ref(entry(), vision))), fault.name)
                assertEquals(journalBefore, disk.fileSize(JOURNAL)?.toString(), fault.name)
                assertEquals(emptyList(), relaunched.store.installed(), fault.name)
                // A new download is refused, typed, and nothing is fetched for it.
                refused<ModelDownloadJournalUnreadableException>(relaunched, message = fault.name)
                assertEquals(
                    ModelDownloadOutcome.Refused(key(entry()), ModelDownloadRefusal.JournalUnreadable),
                    relaunched.downloader.outcomeFor(key(entry())),
                    fault.name,
                )
                assertFailsWith<ModelDownloadJournalUnreadableException>(fault.name) { relaunched.downloader.cancel(MODEL) }
                assertEquals(emptyList(), transport.started, fault.name)
                assertEquals(emptyList(), transport.cancelled, fault.name)
                assertFalse(relaunched.downloader.isDownloading(MODEL), fault.name)

                // ⚠ Nothing was remembered: the next read that works carries on.
                disk.readFaults.clear()
                transport.served[url(entry(), text)] = Served.Body(textBytes)
                transport.served[url(entry(), vocabulary)] = Served.Body(vocabularyBytes)
                relaunched.downloader.resume()
                assertEquals(listOf("a.transfer.nobody-named"), transport.cancelled, fault.name)
                assertNull(disk.fileSize(stray), fault.name)
                transport.run(relaunched.downloader)
                assertEquals(1, relaunched.store.installed().size, fault.name)
            }
        }
    }

    @Test
    fun aJournalThatIsNotThisBuildsRecordIsNoJournalAndIsSweptAndWrittenOver() = runTest(UnconfinedTestDispatcher()) {
        val notOurs: Map<String, String> = mapOf(
            "JSON of another shape" to """[{"modelId":"siglip","tasks":[1,2,3]}]""",
            "a record with a field this build does not know" to """[{"modelId":"siglip","future":true}]""",
            "not JSON at all" to "\u0000\u0001 half a wri",
        )
        notOurs.forEach { (what, bytes) ->
            run {
                val disk = DownloadDisk()
                val transport = FakeTransport(disk, DIR)
                transport.served[url(entry(), vision)] = Served.Body(visionBytes)
                transport.served[url(entry(), text)] = Served.Body(textBytes)
                transport.served[url(entry(), vocabulary)] = Served.Body(vocabularyBytes)
                disk.writeRawByteArray(JOURNAL, bytes.encodeToByteArray())
                // What that record's build left: a transfer and a delivered file. Without its
                // digests neither can ever be verified.
                transport.live["old.transfer"] = ModelTransferRequest("old.transfer", "$BASE/old", false)
                val stray: String = BackgroundModelDownloader.deliveredPath(DIR, "old.file")
                disk.writeRawByteArray(stray, ByteArray(10))
                val phone = DownloadProcess(disk, transport, backgroundScope)

                phone.downloader.resume()

                assertEquals(listOf("old.transfer"), transport.cancelled, what)
                assertNull(disk.fileSize(stray), what)
                // ⚠ The bytes themselves are left until something has a record to write.
                assertEquals(bytes, disk.readBytes(JOURNAL)?.get()?.decodeToString(), what)

                val waiting = backgroundScope.async { runCatching { phone.downloader.download(MODEL) } }
                assertTrue(entry().artifacts.first().sha256 in disk.readBytes(JOURNAL)?.get()?.decodeToString().orEmpty(), what)
                transport.run(phone.downloader)
                assertEquals(phone.store.installed().single(), waiting.settled().getOrThrow(), what)
            }
        }
    }

    @Test
    fun theSweepRemovesOnlyWhatNoRecordNames() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall, vocabulary = Served.Stall)
        downloading(process())
        transport.served[url(entry(), vision)] = Served.Stall
        transport.live["a.transfer.nobody-named"] = ModelTransferRequest("a.transfer.nobody-named", "$BASE/x", false)
        val stray: String = BackgroundModelDownloader.deliveredPath(DIR, "a.file.nobody-named")
        disk.writeRawByteArray(stray, ByteArray(10))
        // ⚠ A name that is not a ref is never turned into a path, whatever lists it.
        val relaunched = process()

        relaunched.downloader.resume()

        assertEquals(listOf("a.transfer.nobody-named"), transport.cancelled)
        assertEquals(listOf(stray), disk.deleted)
        assertEquals(setOf(ref(entry(), vision), ref(entry(), text), ref(entry(), vocabulary)), transport.live.keys)
    }

    @Test
    fun aTransferNoRecordNamesIsDiscardedWhenItReports() = runTest(UnconfinedTestDispatcher()) {
        val phone = process()
        val stray: String = BackgroundModelDownloader.deliveredPath(DIR, "a.file.nobody-named")
        disk.writeRawByteArray(stray, ByteArray(10))
        disk.writeRawByteArray("$DIR/../escaped.delivered", ByteArray(1))

        phone.downloader.settleFinished("a.file.nobody-named", 200, null)
        phone.downloader.settleFinished("../escaped", 200, null)

        assertEquals(listOf(stray), disk.deleted)
    }

    // ─── Readiness's writer 3, and a retired graph.

    @Test
    fun theCommitRunsInsideTheStoreChangeWrapper() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val waiting = downloading(phone)

        transport.run(phone.downloader)
        waiting.settled().getOrThrow()

        // Entered with nothing installed, left with the model installed: the commit was inside.
        assertEquals(listOf(0), phone.changes.installedOnEntry)
        assertEquals(listOf(1), phone.changes.installedOnExit)
    }

    @Test
    fun aRetiredGraphNeitherStagesNorInstallsNorCleansUp() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Body("denied".encodeToByteArray(), status = 403))
        var retired = false
        val phone = process(retired = { retired })
        val waiting = downloading(phone)
        // Things nothing names, which a live graph would sweep.
        transport.served["$BASE/x"] = Served.Stall
        transport.live["a.transfer.nobody-named"] = ModelTransferRequest("a.transfer.nobody-named", "$BASE/x", false)
        val stray: String = BackgroundModelDownloader.deliveredPath(DIR, "a.file.nobody-named")
        disk.writeRawByteArray(stray, ByteArray(10))

        retired = true
        // Two arrive, one fails, a stranger reports, and the graph is asked to resume.
        transport.run(phone.downloader)
        phone.downloader.settleFinished("a.file.nobody-named", 200, null)
        phone.downloader.resume()

        // ⚠⚠ Nothing was moved, deleted, cancelled, failed, started or installed by it.
        assertTrue(waiting.isActive)
        assertEquals(emptyList(), phone.store.installed())
        assertEquals(emptyList(), phone.changes.installedOnEntry)
        assertEquals(setOf(ref(entry(), vision), ref(entry(), vocabulary), "a.file.nobody-named"), disk.delivered(DIR))
        assertEquals(emptyList(), disk.deleted)
        assertEquals(emptyList(), transport.cancelled)
        assertEquals(3, transport.started.size)
        assertTrue(MODEL in journalText().orEmpty())
        assertNull(phone.downloader.outcomeFor(key(entry())))
        refused<ModelDownloadCancelledException>(phone)
        assertEquals(3, transport.started.size)

        // The delivered files are the next graph's to verify, from the same journal.
        serve()
        val next = process()
        next.downloader.resume()
        transport.run(next.downloader)
        assertEquals(1, next.store.installed().size)
    }

    @Test
    fun aGraphRetiredWhileItSettlesMovesNothingMore() = runTest(UnconfinedTestDispatcher()) {
        serve()
        // Retired the moment the first delivered file has been moved into staging.
        var armed = false
        val phone = process(retired = { armed && disk.delivered(DIR).size < 4 })
        downloading(phone)
        transport.runUnheard()
        // And a file nothing names, which the sweep at the end of a resume would delete.
        val stray: String = BackgroundModelDownloader.deliveredPath(DIR, "a.file.nobody-named")
        disk.writeRawByteArray(stray, ByteArray(10))
        armed = true

        phone.downloader.resume()

        // One was moved before the teardown; the other two and the stranger are still there.
        assertEquals(setOf(ref(entry(), text), ref(entry(), vocabulary), "a.file.nobody-named"), disk.delivered(DIR))
        assertEquals(emptyList(), phone.store.installed())
    }

    @Test
    fun aGraphRetiredOnceEverythingIsStagedDoesNotCommit() = runTest(UnconfinedTestDispatcher()) {
        serve()
        // Retired the moment the last delivered file has been moved into staging.
        var armed = false
        val phone = process(retired = { armed && disk.delivered(DIR).isEmpty() })
        downloading(phone)
        transport.runUnheard()
        armed = true

        phone.downloader.resume()

        assertEquals(emptySet(), disk.delivered(DIR))
        assertEquals(emptyList(), phone.store.installed())
        // ⚠ The wrapper was not even entered: on iOS entering it stops a model being served.
        assertEquals(emptyList(), phone.changes.installedOnEntry)
    }

    @Test
    fun aGraphRetiredWhileTheCommitWaitsForItsTurnInstallsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        var retired = false
        val phone = process(retired = { retired })
        // The teardown lands after the commit asked, while the wrapper holds it back (on iOS:
        // while readiness's lock is taken and a pass is being cancelled).
        phone.changes.whileWaiting = { retired = true }
        val waiting = downloading(phone)

        transport.run(phone.downloader)

        assertEquals(listOf(0), phone.changes.installedOnEntry)
        assertEquals(emptyList(), DownloadFixtures.storeOver(disk).installed())
        // ⚠ The record is the next graph's; whoever waited on this one is told, not left waiting.
        assertIs<ModelDownloadCancelledException>(waiting.settled().exceptionOrNull())
        assertTrue(MODEL in journalText().orEmpty())
        assertNull(phone.downloader.outcomeFor(key(entry())))
    }

    @Test
    fun aTeardownThatLandsUnderAResumeTellsWhoeverWaits() = runTest(UnconfinedTestDispatcher()) {
        serve()
        var retired = false
        val phone = process(retired = { retired })
        val waiting = downloading(phone)
        transport.runUnheard()
        phone.changes.whileWaiting = { retired = true }

        phone.downloader.resume()

        assertEquals(emptyList(), DownloadFixtures.storeOver(disk).installed())
        assertIs<ModelDownloadCancelledException>(waiting.settled().exceptionOrNull())
    }

    @Test
    fun whoeverWaitsOnAGraphThatIsTornDownIsToldAtOnceAndTheDownloadIsLeftAlone() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        val first = downloading(phone)
        val second = downloading(phone)
        transport.run(phone.downloader)
        assertTrue(first.isActive)
        disk.deleted.clear()

        // What the teardown calls, from the main thread. ⚠ No step runs after it: a retired
        // graph hears no report and is resumed by nobody, so nothing else would ever end the wait.
        phone.downloader.tornDown()

        assertIs<ModelDownloadCancelledException>(first.settled().exceptionOrNull())
        assertIs<ModelDownloadCancelledException>(second.settled().exceptionOrNull())
        assertEquals(emptyMap(), phone.progress.progress.value)
        assertFalse(phone.downloader.isDownloading(MODEL))
        // The download itself is the next graph's: nothing cancelled, deleted or unjournalled.
        assertEquals(emptyList(), transport.cancelled)
        assertEquals(emptyList(), disk.deleted)
        assertTrue(MODEL in journalText().orEmpty())
        // ⚠ And nothing is tracked again on a graph that is gone, whatever still reads the
        // journal there: it would be "running" for good, with nobody to end it.
        assertFalse(phone.downloader.isDownloading(MODEL))
        phone.downloader.settleFinished("a.file.nobody-named", 200, null)
        assertFalse(phone.downloader.isDownloading(MODEL))
        assertEquals(emptyMap(), phone.progress.progress.value)
        serve()
        val next = process()
        next.downloader.resume()
        transport.run(next.downloader)
        assertEquals(1, next.store.installed().size)
    }

    @Test
    fun aCallerIsReleasedByATeardownWhereverItsCallIsWaiting() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        // 1. Waiting for the catalog, which has not answered: nothing is journalled yet.
        val catalogArrives = CompletableDeferred<Unit>()
        val fetching = DownloadProcess(
            disk, transport, backgroundScope,
            catalog = ModelCatalogSource {
                catalogArrives.await()
                ModelCatalogData(schemaVersion = 1, buildDate = "2026-10-12", models = listOf(entry()))
            },
        )
        val duringTheFetch = downloading(fetching)
        assertTrue(duringTheFetch.isActive)
        assertTrue(fetching.downloader.isDownloading(MODEL))

        fetching.downloader.tornDown()

        // ⚠⚠ At once, not when the catalog request returns.
        assertIs<ModelDownloadCancelledException>(duringTheFetch.settled().exceptionOrNull())
        assertFalse(fetching.downloader.isDownloading(MODEL))
        assertNull(journalText())
        // ⚠ And the step that was fetching for it runs on (it is never cancelled) into a graph
        // that is gone: when the catalog does answer, nothing is journalled and nothing started.
        catalogArrives.complete(Unit)
        assertNull(journalText())
        assertEquals(emptyList(), transport.started)

        // 2. One caller's step is waiting for the transport to say what it holds, under the
        //    downloader's lock; a second caller is waiting for that lock.
        val phone = process()
        transport.answerWhen = CompletableDeferred()
        val holdingTheLock = downloading(phone)
        val waitingForTheLock = downloading(phone)
        assertTrue(holdingTheLock.isActive && waitingForTheLock.isActive)

        phone.downloader.tornDown()

        assertIs<ModelDownloadCancelledException>(holdingTheLock.settled().exceptionOrNull())
        assertIs<ModelDownloadCancelledException>(waitingForTheLock.settled().exceptionOrNull())
        // ⚠ And a call made after the teardown is not registered at all.
        assertIs<ModelDownloadCancelledException>(downloading(phone).settled().exceptionOrNull())
        transport.answerWhen = null
    }

    @Test
    fun aGraphRetiredWhileItHashedStagedBytesDropsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        downloading(process())
        transport.live.clear()
        // Staging holds the text tower's length under its digest, and other bytes: what a live
        // graph hashes and then drops.
        val earlier = DownloadFixtures.storeOver(disk)
        earlier.beginStaging(MODEL, text, entry().artifact(text)!!.sha256)
        earlier.writeStaged(MODEL, text, 0, ByteArray(textBytes.size) { 3 })
        val staging = "$DEFAULT_MODELS_DIR/$MODEL.$text.model.part"
        disk.deleted.clear()
        // The successor resumes the journal and is torn down while it hashes those bytes.
        var retired = false
        val phone = process(retired = { retired })
        disk.whileReading = { path -> if (path == staging) retired = true }

        phone.downloader.resume()

        // ⚠⚠ The staging file and its marker are the next graph's to judge.
        assertEquals(emptyList(), disk.deleted)
        assertEquals(textBytes.size.toLong(), disk.fileSize(staging))
        assertNotNull(disk.fileSize("$staging.sha"))
    }

    // ─── A teardown that lands UNDER a step: every act asks again.

    private fun markersWritten(): Int = disk.paths.count { it.endsWith(".part.sha") }

    @Test
    fun aGraphRetiredWhileTheTransportIsAskedWhatItHoldsStartsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        downloading(process())
        // The transport lost all three; a later graph resumes, and is torn down while it waits
        // for the transport's answer.
        transport.live.clear()
        transport.started.clear()
        disk.paths.filter { it.endsWith(".part.sha") }.forEach(disk::deleteFile)
        var retired = false
        val phone = process(retired = { retired })
        transport.whileAsked = { retired = true }

        phone.downloader.resume()

        // ⚠⚠ Not one transfer: the successor graph resumes the same journal and would start
        // the same refs. And nothing was written on the way to starting them.
        assertEquals(emptyList(), transport.started)
        assertEquals(0, markersWritten())
        assertTrue(MODEL in journalText().orEmpty())
    }

    @Test
    fun aGraphRetiredAfterItLookedAtItsStagingStartsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        downloading(process())
        transport.live.clear()
        transport.started.clear()
        disk.paths.filter { it.endsWith(".part.sha") }.forEach(disk::deleteFile)
        // Retired the moment the third artifact's staging has been looked at: the only act
        // left between there and the transport is the start itself.
        var armed = false
        val phone = process(retired = { armed && markersWritten() == 3 })
        armed = true

        phone.downloader.resume()

        assertEquals(3, markersWritten())
        assertEquals(emptyList(), transport.started)
    }

    @Test
    fun aGraphRetiredWhileItBeginsADownloadJournalsAndStartsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        // Torn down while the catalog was being fetched, which can take tens of seconds.
        var retired = false
        val whileFetching = process(
            retired = { retired },
            entry = {
                retired = true
                entry()
            },
        )
        refused<ModelDownloadCancelledException>(whileFetching)
        assertEquals(0, markersWritten())
        assertNull(journalText())
        assertEquals(emptyList(), transport.started)

        // And torn down while the free space was read: the journal is the very next act.
        var late = false
        val beforeTheJournal = process(retired = { late }, need = CoreMlDiskBudget::additionalNeed)
        disk.whileAskedForSpace = { late = true }
        refused<ModelDownloadCancelledException>(beforeTheJournal)
        assertNull(journalText())
        assertEquals(emptyList(), transport.started)
    }

    @Test
    fun aGraphRetiredUnderAStepDeletesAndCancelsNothing() = runTest(UnconfinedTestDispatcher()) {
        // A corrupt file, a transfer and a file nothing names: all three a live graph removes.
        // ⚠ The corrupt one is the first artifact, so discarding it is the first act of the settle.
        serve(vision = Served.Body(visionBytes.copyOf().also { it[0] = 99 }), text = Served.Stall, vocabulary = Served.Stall)
        downloading(process())
        transport.runUnheard()
        transport.served["$BASE/x"] = Served.Stall
        transport.live["a.transfer.nobody-named"] = ModelTransferRequest("a.transfer.nobody-named", "$BASE/x", false)
        val stray: String = BackgroundModelDownloader.deliveredPath(DIR, "a.file.nobody-named")
        disk.writeRawByteArray(stray, ByteArray(10))
        disk.deleted.clear()
        var retired = false
        val phone = process(retired = { retired })
        transport.whileAsked = { retired = true }

        phone.downloader.resume()

        assertEquals(emptyList(), disk.deleted)
        assertNotNull(disk.fileSize(delivered(vision)))
        assertEquals(emptyList(), transport.cancelled)
        assertTrue(MODEL in journalText().orEmpty())
        assertNull(phone.downloader.outcomeFor(key(entry())))

        // With no download at all, the sweep is the whole of a resume.
        val idleDisk = DownloadDisk()
        val idleTransport = FakeTransport(idleDisk, DIR)
        idleTransport.live["a.transfer.nobody-named"] = ModelTransferRequest("a.transfer.nobody-named", "$BASE/x", false)
        idleDisk.writeRawByteArray(stray, ByteArray(10))
        var idleRetired = false
        idleTransport.whileAsked = { idleRetired = true }
        DownloadProcess(idleDisk, idleTransport, backgroundScope, retired = { idleRetired }).downloader.resume()
        assertEquals(emptyList(), idleTransport.cancelled)
        assertNotNull(idleDisk.fileSize(stray))

        // And with no transfer to cancel first, the stray file is the first thing it would delete.
        idleTransport.live.clear()
        idleRetired = false
        DownloadProcess(idleDisk, idleTransport, backgroundScope, retired = { idleRetired }).downloader.resume()
        assertNotNull(idleDisk.fileSize(stray))
    }

    @Test
    fun aRetiredGraphCancelsNothing() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        var retired = false
        val phone = process(retired = { retired })
        downloading(phone)
        retired = true

        assertFailsWith<ModelDownloadCancelledException> { phone.downloader.cancel(MODEL) }

        assertEquals(emptyList(), transport.cancelled)
        assertTrue(MODEL in journalText().orEmpty())
    }

    @Test
    fun aStoreWhoseIndexCannotBeReadRefusesBeforeAnythingIsFetched() = runTest(UnconfinedTestDispatcher()) {
        serve()
        disk.writeRawByteArray("$DEFAULT_MODELS_DIR/inference.models.index.json", """{"not":"an index"}""".encodeToByteArray())
        val phone = process()

        refused<ModelIndexUnreadableException>(phone)

        assertEquals(emptyList(), transport.started)
        assertNull(journalText())
        assertIs<ModelDownloadRefusal.StoreRefused>(
            assertIs<ModelDownloadOutcome.Refused>(phone.downloader.outcomeFor(key(entry()))).reason,
        )
    }

    @Test
    fun aCommitTheStoreCouldNotMakeTellsWhoeverWaitsAndKeepsTheRecord() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val waiting = downloading(phone)
        disk.failingWrites += "$DEFAULT_MODELS_DIR/inference.models.index.json"

        transport.run(phone.downloader)

        // ⚠ Nothing is reported installed that the store did not confirm.
        assertIs<IllegalStateException>(waiting.settled().exceptionOrNull())
        assertIs<ModelDownloadOutcome.Failed>(phone.downloader.outcomeFor(key(entry())))
        // ⚠ Asked of THIS process's store. A second store over the disk is a second process,
        // whose first read sweeps what the failed commit had renamed (the case below).
        assertEquals(emptyList(), phone.store.installed())
        assertTrue(MODEL in journalText().orEmpty())

        // The record stayed, so the next settle makes the commit.
        disk.failingWrites.clear()
        phone.downloader.resume()
        assertEquals(1, DownloadFixtures.storeOver(disk).installed().size)
        assertEquals("[]", journalText())
    }

    @Test
    fun aCommitWhoseFilesAreGoneVouchesForNothingAndTheArtifactsAreFetchedAgain() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val waiting = downloading(phone)
        disk.failingWrites += "$DEFAULT_MODELS_DIR/inference.models.index.json"
        transport.run(phone.downloader)
        assertIs<IllegalStateException>(waiting.settled().exceptionOrNull())
        // The failed commit had made its renames and declared them. Another process's sweep
        // takes them: no record names them.
        assertEquals(emptyList(), DownloadFixtures.storeOver(disk).installed())
        disk.failingWrites.clear()
        transport.started.clear()

        // ⚠⚠ This process hashed every artifact, and none of them is there any more. The commit
        // is refused whole; it once wrote an index record naming three files that did not exist.
        phone.downloader.resume()
        assertEquals(emptyList(), DownloadFixtures.storeOver(disk).installed(), "a record was written for files that are gone")
        assertTrue(MODEL in journalText().orEmpty())
        // And what was hashed licenses nothing more: the next settle looks at the disk and fetches.
        phone.downloader.resume()
        assertEquals(3, transport.started.size)
        transport.run(phone.downloader)
        val installed: InstalledModelData = phone.store.installed().single()
        assertContentEquals(visionBytes, disk.readBytes(installed.pathOf(vision)!!)?.get())
    }

    // ─── Two operations on one model (the plan's matrix, §14.11).

    /** An update under way: the older generation installed, the newer one journalled, its vision tower staged. */
    private suspend fun TestScope.anUpdateUnderWay(): Pair<ModelEntryData, ModelEntryData> {
        val older = entry(vision = ByteArray(4096) { 1 }, text = ByteArray(8192) { 2 }, vocabulary = ByteArray(100) { 3 })
        val newer = entry(version = 2)
        var offered: ModelEntryData = older
        serve(older, vision = Served.Body(ByteArray(4096) { 1 }), text = Served.Body(ByteArray(8192) { 2 }), vocabulary = Served.Body(ByteArray(100) { 3 }))
        serve(newer, text = Served.Stall, vocabulary = Served.Stall)
        val phone = process(entry = { offered })
        val install = downloading(phone)
        transport.run(phone.downloader)
        install.settled().getOrThrow()
        offered = newer
        downloading(phone)
        transport.run(phone.downloader)
        transport.started.clear()
        transport.cancelled.clear()
        disk.deleted.clear()
        return older to newer
    }

    @Test
    fun deletingAModelEndsTheUpdateThatWasDownloadingSoNothingBringsItBack() = runTest(UnconfinedTestDispatcher()) {
        val (older, newer) = anUpdateUnderWay()
        // ⚠ A process that was only just relaunched: it has resumed nothing and remembers nothing.
        val phone = process(entry = { newer })
        var journalWhenRemoved: String? = null

        phone.downloader.withoutDownloadOf(MODEL, phone.changes) {
            journalWhenRemoved = journalText()
            phone.store.remove(MODEL)
        }

        // The record went before the model did, and its transfers with it.
        assertEquals("[]", journalWhenRemoved)
        assertTrue(transport.cancelled.containsAll(listOf(ref(newer, text), ref(newer, vocabulary))))
        assertEquals(emptyList(), phone.store.installed())
        assertEquals(ModelDownloadOutcome.Cancelled(key(newer)), phone.downloader.outcomeFor(key(newer)))

        // ⚠⚠ Whatever the session still delivers, and whoever resumes: the model stays removed.
        serve(newer)
        transport.live[ref(newer, text)] = ModelTransferRequest(ref(newer, text), url(newer, text), false)
        transport.run(phone.downloader)
        phone.downloader.resume()
        process(entry = { newer }).downloader.resume()
        assertEquals(emptyList(), DownloadFixtures.storeOver(disk).installed())
        assertEquals(emptySet(), disk.delivered(DIR))
        assertFalse(phone.downloader.isDownloading(MODEL))
        assertEquals(older.modelVersion, 1)
    }

    // ─── What becomes of staged bytes when a record ends.

    private fun stagedFiles(): List<String> = disk.paths.filter { it.endsWith(".part") || it.endsWith(".part.sha") }.sorted()

    @Test
    fun deletingAModelLeavesNothingStagedOfTheUpdateThatWasDownloadingNotEvenUnderANewRole() = runTest(UnconfinedTestDispatcher()) {
        // Installed: vision, text, vocabulary. The update gives the text tower another role
        // and other bytes, and that tower has arrived and is staged.
        val older = entry()
        val newer = entry(version = 2, vision = ByteArray(4096) { 8 }, text = ByteArray(8192) { 9 }).let { e ->
            e.copy(artifacts = e.artifacts.map { if (it.role == text) it.copy(role = "textTower") else it })
        }
        var offered: ModelEntryData = older
        serve(older)
        newer.artifacts.forEach { transport.served["$BASE/${it.path}"] = Served.Stall }
        transport.served["$BASE/${newer.artifacts.first { it.role == "textTower" }.path}"] = Served.Body(ByteArray(8192) { 9 })
        val phone = process(entry = { offered })
        val install = downloading(phone)
        transport.run(phone.downloader)
        install.settled().getOrThrow()
        offered = newer
        downloading(phone)
        transport.run(phone.downloader)
        assertEquals(
            listOf("$DEFAULT_MODELS_DIR/$MODEL.textTower.model.part", "$DEFAULT_MODELS_DIR/$MODEL.textTower.model.part.sha"),
            stagedFiles().filter { "textTower" in it },
        )

        phone.downloader.withoutDownloadOf(MODEL, phone.changes) { phone.store.remove(MODEL) }

        // ⚠⚠ The store's removal drops staging for the roles of the record it removes, and
        // `textTower` is not one of them: hundreds of megabytes nothing would ever reclaim.
        assertEquals(emptyList(), stagedFiles())
        assertEquals(emptySet(), disk.delivered(DIR))
    }

    @Test
    fun aCancelledDownloadLeavesNothingStagedAndAFailedOneKeepsWhatArrived() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        downloading(phone)
        transport.run(phone.downloader)
        assertTrue(stagedFiles().any { it.endsWith("$vision.model.part") })

        phone.downloader.cancel(MODEL)

        // The user does not want it: nothing of it stays.
        assertEquals(emptyList(), stagedFiles())

        // ⚠ A FAILED transfer is the other case: what arrived is what makes the retry cheap.
        serve(text = Served.Error("the connection was lost"))
        val failed = downloading(phone)
        transport.run(phone.downloader)
        assertIs<ModelDownloadException>(failed.settled().exceptionOrNull())
        assertTrue(stagedFiles().any { it.endsWith("$vision.model.part") })
    }

    @Test
    fun aNewerEntryThatTakesOverDropsTheStagingOfARoleItNoLongerHas() = runTest(UnconfinedTestDispatcher()) {
        // The running download has a fourth artifact, staged; the newer entry has no such role.
        val extra = ByteArray(2048) { 5 }
        val older = entry().let { e -> e.copy(artifacts = e.artifacts + DownloadFixtures.artifact("faceDetector", "siglip/1/extra.bin", extra)) }
        val newer = entry(version = 2, vision = ByteArray(4096) { 8 }, text = ByteArray(8192) { 9 }, vocabulary = ByteArray(100) { 7 })
        var offered: ModelEntryData = older
        older.artifacts.forEach { transport.served["$BASE/${it.path}"] = Served.Stall }
        newer.artifacts.forEach { transport.served["$BASE/${it.path}"] = Served.Stall }
        transport.served["$BASE/siglip/1/extra.bin"] = Served.Body(extra)
        val phone = process(entry = { offered })
        downloading(phone)
        transport.run(phone.downloader)
        assertTrue(stagedFiles().any { "faceDetector" in it })

        offered = newer
        downloading(phone)

        // ⚠ The newer entry's own settle replaces staging role by role; nothing would ever
        // touch a role it does not have.
        assertTrue(stagedFiles().none { "faceDetector" in it }, stagedFiles().toString())
    }

    @Test
    fun aModelWhoseDownloadCannotBeEndedIsNotRemoved() = runTest(UnconfinedTestDispatcher()) {
        anUpdateUnderWay()
        var removed = 0

        // The journal cannot be read: what is downloading is unknown.
        disk.readFaults[JOURNAL] = DownloadDisk.ReadFault.NULL_THOUGH_PRESENT
        assertFailsWith<ModelDownloadJournalUnreadableException> { process().downloader.withoutDownloadOf(MODEL, ModelStoreChanges.Direct) { removed++ } }
        disk.readFaults.clear()
        // The graph was torn down: the download is the next graph's, and so is the removal.
        assertFailsWith<ModelDownloadCancelledException> { process(retired = { true }).downloader.withoutDownloadOf(MODEL, ModelStoreChanges.Direct) { removed++ } }

        // ⚠ Removed beside a download that then installs it, a model is not removed.
        assertEquals(0, removed)
        assertEquals(emptyList(), transport.cancelled)
        assertEquals(1, DownloadFixtures.storeOver(disk).installed().size)
        assertTrue(MODEL in journalText().orEmpty())
    }

    @Test
    fun aModelWithNoDownloadIsSimplyRemoved() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val install = downloading(phone)
        transport.run(phone.downloader)
        install.settled().getOrThrow()

        assertEquals("gone", phone.downloader.withoutDownloadOf(MODEL, phone.changes) { phone.store.remove(MODEL); "gone" })

        assertEquals(emptyList(), phone.store.installed())
        assertEquals(emptyList(), transport.cancelled)
        // ⚠ Inside the store-change wrapper: once for the install, once for the removal.
        assertEquals(listOf(0, 1), phone.changes.installedOnEntry)
        assertEquals(listOf(1, 0), phone.changes.installedOnExit)
    }

    @Test
    fun aGraphTornDownWhileTheRemovalWaitsForItsTurnRemovesNothing() = runTest(UnconfinedTestDispatcher()) {
        // With a download to end first, and with none: the removal is the last act either way.
        listOf(true, false).forEach { updating ->
            run {
                val disk = DownloadDisk()
                val transport = FakeTransport(disk, DIR)
                val older = entry()
                val newer = entry(version = 2, vision = ByteArray(4096) { 8 }, text = ByteArray(8192) { 9 }, vocabulary = ByteArray(100) { 7 })
                older.artifacts.forEach { transport.served["$BASE/${it.path}"] = Served.Stall }
                newer.artifacts.forEach { transport.served["$BASE/${it.path}"] = Served.Stall }
                transport.served[url(older, vision)] = Served.Body(visionBytes)
                transport.served[url(older, text)] = Served.Body(textBytes)
                transport.served[url(older, vocabulary)] = Served.Body(vocabularyBytes)
                var offered: ModelEntryData = older
                var retired = false
                val phone = DownloadProcess(disk, transport, backgroundScope, entry = { offered }, retired = { retired })
                backgroundScope.async { runCatching { phone.downloader.download(MODEL) } }
                transport.run(phone.downloader)
                if (updating) {
                    offered = newer
                    backgroundScope.async { runCatching { phone.downloader.download(MODEL) } }
                }
                // The teardown lands while the wrapper keeps the removal waiting (on iOS: for
                // readiness's lock, behind a pass being cancelled).
                phone.changes.whileWaiting = { retired = true }
                var removed = 0

                val refused = runCatching {
                    phone.downloader.withoutDownloadOf(MODEL, phone.changes) {
                        removed++
                        phone.store.remove(MODEL)
                    }
                }

                // ⚠⚠ The store is the next graph's now: nothing is removed from it, and the
                // caller is told so.
                assertIs<ModelDownloadCancelledException>(refused.exceptionOrNull(), "updating=$updating")
                assertEquals(0, removed, "updating=$updating")
                assertEquals(
                    older.artifacts.map { it.sha256 },
                    DownloadFixtures.storeOver(disk).installed().single().artifacts.map { it.sha256 },
                    "updating=$updating",
                )
            }
        }
    }

    @Test
    fun aNewerEntryThatIsRefusedTouchesNothingOfTheDownloadThatIsRunning() = runTest(UnconfinedTestDispatcher()) {
        val refusals: List<String> = listOf("no room", "free space unknown", "no transport", "journal unreadable")
        refusals.forEach { why ->
            run {
                val disk = DownloadDisk()
                val transport = FakeTransport(disk, DIR)
                val older = entry()
                val newer = entry(version = 2, vision = ByteArray(4096) { 8 }, text = ByteArray(8192) { 9 }, vocabulary = ByteArray(100) { 7 })
                older.artifacts.forEach { transport.served["$BASE/${it.path}"] = Served.Stall }
                transport.served[url(older, vision)] = Served.Body(visionBytes)
                var offered: ModelEntryData = older
                var transportThere = true
                val phone = DownloadProcess(
                    disk, transport, backgroundScope,
                    entry = { offered }, transportInstalled = { transportThere }, need = CoreMlDiskBudget::additionalNeed,
                )
                val running = backgroundScope.async { runCatching { phone.downloader.download(MODEL) } }
                transport.run(phone.downloader)
                // The running download has its vision tower staged, under the OLDER digest.
                val staging = "$DEFAULT_MODELS_DIR/$MODEL.$vision.model.part"
                assertEquals(visionBytes.size.toLong(), disk.fileSize(staging), why)
                val journalBefore: String? = disk.readBytes(JOURNAL)?.get()?.decodeToString()
                disk.deleted.clear()

                // The catalog moves on to other bytes, and the newer download is refused.
                offered = newer
                when (why) {
                    "no room" -> disk.free = 1
                    "free space unknown" -> disk.free = null
                    "no transport" -> transportThere = false
                    else -> disk.readFaults[JOURNAL] = DownloadDisk.ReadFault.NULL_THOUGH_PRESENT
                }
                val attempt = backgroundScope.async { runCatching { phone.downloader.download(MODEL) } }
                assertTrue(attempt.isCompleted && attempt.await().isFailure, why)

                // ⚠⚠ Refused means nothing happened: what the running download had staged is
                // there, under its own marker, and its record and transfers are as they were.
                disk.readFaults.clear()
                assertEquals(emptyList(), disk.deleted, why)
                assertEquals(visionBytes.size.toLong(), disk.fileSize(staging), why)
                assertEquals(older.artifact(vision)!!.sha256, disk.readBytes("$staging.sha")?.get()?.decodeToString(), why)
                assertEquals(journalBefore, disk.readBytes(JOURNAL)?.get()?.decodeToString(), why)
                assertEquals(emptyList(), transport.cancelled, why)
                assertTrue(running.isActive, why)

                // And it goes on to install, fetching what it lacked and not the tower it had.
                disk.free = Long.MAX_VALUE
                transportThere = true
                transport.started.clear()
                transport.served[url(older, text)] = Served.Body(textBytes)
                transport.served[url(older, vocabulary)] = Served.Body(vocabularyBytes)
                transport.run(phone.downloader)
                assertEquals(emptyList(), transport.started, why)
                assertEquals(older.artifacts.map { it.sha256 }, running.await().getOrThrow().artifacts.map { it.sha256 }, why)
            }
        }
    }

    @Test
    fun aNewerEntryThatIsAdmittedTakesOverAndTheOlderStagingGoes() = runTest(UnconfinedTestDispatcher()) {
        val older = entry()
        val newer = entry(version = 2, vision = ByteArray(4096) { 8 }, text = ByteArray(8192) { 9 }, vocabulary = ByteArray(100) { 7 })
        var offered: ModelEntryData = older
        serve(older, text = Served.Stall, vocabulary = Served.Stall)
        serve(newer, vision = Served.Body(ByteArray(4096) { 8 }), text = Served.Body(ByteArray(8192) { 9 }), vocabulary = Served.Body(ByteArray(100) { 7 }))
        val phone = process(entry = { offered })
        val first = downloading(phone)
        transport.run(phone.downloader)

        offered = newer
        val second = downloading(phone)

        assertIs<ModelDownloadCancelledException>(first.settled().exceptionOrNull())
        // Admitted: now, and only now, the older download's staged tower is dropped.
        assertTrue("$DEFAULT_MODELS_DIR/$MODEL.$vision.model.part" in disk.deleted)
        transport.run(phone.downloader)
        assertEquals(newer.artifacts.map { it.sha256 }, second.settled().getOrThrow().artifacts.map { it.sha256 })
    }

    @Test
    fun askingAgainForAModelThatIsInstalledAsTheCatalogHasItFetchesNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process(need = CoreMlDiskBudget::additionalNeed)
        val first = downloading(phone)
        transport.run(phone.downloader)
        val installed: InstalledModelData = first.settled().getOrThrow()
        transport.started.clear()
        disk.free = 0

        assertEquals(installed, downloading(phone).settled().getOrThrow())

        assertEquals(emptyList(), transport.started)
        assertEquals("[]", journalText())
        // ⚠ Nothing was committed a second time: the store was not asked to change.
        assertEquals(1, phone.changes.installedOnEntry.size)
    }

    // ─── A report is settled exactly once, and said to be.

    @Test
    fun everyReportIsAcknowledgedOnceItHasBeenActedOnWhateverThatWas() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        var retired = false
        val phone = process(retired = { retired })
        downloading(phone)
        var acknowledged = 0
        val counted = object : ModelTransferReport {
            override fun stands(): Boolean = true

            override fun settled() {
                acknowledged++
            }
        }

        // Acted on: a transfer that delivered.
        transport.runUnheard()
        phone.downloader.transferFinished(ref(entry(), vision), 200, null, counted)
        assertEquals(1, acknowledged)
        // Nobody's: no record names it.
        phone.downloader.transferFinished("a.file.nobody-named", 200, null, counted)
        assertEquals(2, acknowledged)
        // Not acted on: the journal cannot be read.
        disk.readFaults[JOURNAL] = DownloadDisk.ReadFault.THROWS
        phone.downloader.transferFinished(ref(entry(), text), -1, "lost", counted)
        assertEquals(3, acknowledged)
        disk.readFaults.clear()
        // Threw: a file nothing names could not be deleted.
        val stray: String = BackgroundModelDownloader.deliveredPath(DIR, "another.file.nobody-named")
        disk.writeRawByteArray(stray, ByteArray(10))
        disk.failingDeletes += stray
        phone.downloader.transferFinished("another.file.nobody-named", 200, null, counted)
        assertEquals(4, acknowledged)
        // Torn down: the report is not this graph's any more.
        retired = true
        phone.downloader.transferFinished(ref(entry(), text), -1, "lost", counted)
        // ⚠⚠ Each of them once: the transport keeps an ended transfer as its ref's holder
        // until this is called, and a report never acknowledged would hold its ref for good.
        assertEquals(5, acknowledged)
    }

    // ─── What a download was admitted under holds again at every start.

    @Test
    fun aLostTransferIsNotStartedAgainOntoADiskThatHasFilledUp() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall, vocabulary = Served.Stall)
        downloading(process(need = CoreMlDiskBudget::additionalNeed))
        // The app is dead. Vision arrives; the text tower's transfer is lost; and meanwhile
        // something else fills the disk.
        transport.runUnheard()
        transport.live.remove(ref(entry(), text))
        transport.started.clear()
        val stillToCome: Long = textBytes.size.toLong() + vocabularyBytes.size
        val need: Long = CoreMlDiskBudget.additionalNeed(entry().byteSize, stillToCome)
        val journalBefore: String? = journalText()

        val refusals: List<Pair<Long?, ModelDownloadRefusal>> = listOf(
            need - 1 to ModelDownloadRefusal.InsufficientStorage(need, need - 1),
            null to ModelDownloadRefusal.FreeSpaceUnknown,
        )
        refusals.forEach { (free, why) ->
            disk.free = free
            val relaunched = process(need = CoreMlDiskBudget::additionalNeed)
            relaunched.downloader.resume()

            // ⚠⚠ Not one byte is fetched onto a disk that cannot take the model.
            assertEquals(emptyList(), transport.started, "$free")
            // ⚠ And nothing is ended: the record, what is staged and the transfer that is
            // still running all stay, so freeing space is all it takes.
            assertEquals(journalBefore, journalText(), "$free")
            assertEquals(emptyList(), transport.cancelled, "$free")
            assertEquals(visionBytes.size.toLong(), disk.fileSize("$DEFAULT_MODELS_DIR/$MODEL.$vision.model.part"), "$free")
            assertEquals(ModelDownloadOutcome.Refused(key(entry()), why), relaunched.downloader.outcomeFor(key(entry())), "$free")
            assertTrue(relaunched.downloader.isDownloading(MODEL), "$free")
        }

        // Room again: the next resume starts the one transfer that was lost.
        disk.free = need
        process(need = CoreMlDiskBudget::additionalNeed).downloader.resume()
        assertEquals(listOf(url(entry(), text)), transport.started.map { it.url })
    }

    @Test
    fun aLostTransferWithNoTransportToStartItOnIsSaidAndNothingIsEnded() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        downloading(process())
        transport.live.remove(ref(entry(), text))
        transport.started.clear()
        val journalBefore: String? = journalText()

        val relaunched = process(transportInstalled = { false })
        relaunched.downloader.resume()

        assertEquals(
            ModelDownloadOutcome.Refused(key(entry()), ModelDownloadRefusal.NoTransport),
            relaunched.downloader.outcomeFor(key(entry())),
        )
        assertEquals(journalBefore, journalText())
        assertEquals(emptyList(), transport.started)
    }

    @Test
    fun theBarIsTheRecordsOwnAfterANewerEntryTakesOver() = runTest(UnconfinedTestDispatcher()) {
        val older = entry()
        val bigger = ByteArray(20_000) { 9 }
        val newer = entry(version = 2, text = bigger)
        var offered: ModelEntryData = older
        serve(older, text = Served.Stall, vocabulary = Served.Stall)
        serve(newer, text = Served.Stall, vocabulary = Served.Stall)
        val phone = process(entry = { offered })
        downloading(phone)
        transport.run(phone.downloader)
        assertEquals(older.byteSize, phone.progress.progress.value[MODEL]?.totalBytes)

        offered = newer
        downloading(phone)

        // ⚠ The newer record's total, and what it holds of it: not the older record's total,
        // which the bar would run past.
        val bar = assertNotNull(phone.progress.progress.value[MODEL])
        assertEquals(newer.byteSize, bar.totalBytes)
        assertEquals(visionBytes.size.toLong(), bar.writtenBytes)
        phone.downloader.transferProgressed(ref(newer, text), 1_000_000L)
        val later = assertNotNull(phone.progress.progress.value[MODEL])
        assertTrue(later.writtenBytes <= later.totalBytes, "$later")
        assertEquals(visionBytes.size.toLong() + bigger.size, later.writtenBytes)
    }

    // ─── What is tracked in memory, and what the journal holds beside identity.

    @Test
    fun theFirstQuestionNeverWaitsForAStepAndTracksNothingBesideIt() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        downloading(process())
        // A relaunched process: its launch check is resuming, held up by the session's answer
        // with the downloader's lock taken (it could as well be hashing half a gigabyte).
        val relaunched = process()
        transport.answerWhen = CompletableDeferred()
        val resuming = backgroundScope.async { relaunched.downloader.resume() }
        assertTrue(resuming.isActive)

        val asked = backgroundScope.async { relaunched.downloader.isDownloading(MODEL) }

        // ⚠ Answered at once, and rightly: the step that holds the lock tracked what it read
        // before it began to wait.
        assertTrue(asked.isCompleted, "the question waited for the step")
        assertTrue(asked.await())
        transport.answerWhen!!.complete(Unit)
        transport.answerWhen = null
        assertTrue(resuming.isCompleted)
    }

    @Test
    fun aRecordThatEndsWhileTheFirstQuestionReadsTheJournalIsNotLeftTracked() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        downloading(process())
        val relaunched = process()
        // ⚠⚠ The interleaving as it was found: the first question has READ the journal and not
        // yet tracked what it read, and in that gap another step ends the record (here a
        // cancel; a commit or a replacement does the same).
        var once = true
        disk.whileReading = { path ->
            if (path == JOURNAL && once) {
                once = false
                backgroundScope.launch { relaunched.downloader.cancel(MODEL) }
            }
        }

        relaunched.downloader.isDownloading(MODEL)
        disk.whileReading = {}

        // The cancel went through, before or after the look. Either way nothing the journal
        // no longer names is tracked: not "running", and no bar.
        assertEquals("[]", journalText())
        assertFalse(relaunched.downloader.isDownloading(MODEL))
        assertEquals(emptyMap(), relaunched.progress.progress.value)
    }

    @Test
    fun theCatalogIsFetchedWithoutHoldingUpEveryOtherStep() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val catalogArrives = CompletableDeferred<Unit>()
        val phone = DownloadProcess(
            disk, transport, backgroundScope,
            catalog = ModelCatalogSource {
                catalogArrives.await()
                ModelCatalogData(schemaVersion = 1, buildDate = "2026-10-12", models = listOf(entry()))
            },
        )
        val waiting = downloading(phone)

        // The catalog request can take tens of seconds. Meanwhile a launch check resumes, and
        // a transfer of another download reports: neither waits for it.
        val resumed = backgroundScope.async { phone.downloader.resume() }
        val reported = backgroundScope.async { phone.downloader.settleFinished("a.file.nobody-named", 200, null) }

        assertTrue(resumed.isCompleted && reported.isCompleted, "a step waited for somebody else's catalog request")
        catalogArrives.complete(Unit)
        assertEquals(3, transport.started.size)
        assertTrue(waiting.isActive)
    }

    @Test
    fun anArtifactTheCatalogMovedIsFetchedFromItsNewPlaceAfterARelaunchToo() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall, vocabulary = Served.Stall)
        var offered: ModelEntryData = entry()
        val phone = process(entry = { offered })
        downloading(phone)
        transport.run(phone.downloader)
        // The text tower's transfer is lost, and the signed catalog moves that artifact:
        // the same role, digest and size, the same version, label set and licence. Another path.
        transport.live.remove(ref(entry(), text))
        val moved = entry().copy(artifacts = entry().artifacts.map { if (it.role == text) it.copy(path = "siglip/moved/text.tar") else it })
        transport.served["$BASE/siglip/moved/text.tar"] = Served.Stall
        offered = moved
        transport.started.clear()

        downloading(phone)

        // This call fetches from the new place, and leaves alone the transfer that is still
        // running for the vocabulary (same ref: the bytes are the same wherever they come from).
        assertEquals(listOf("$BASE/siglip/moved/text.tar"), transport.started.map { it.url })
        assertEquals(emptyList(), transport.cancelled)
        // ⚠⚠ And so does a process that knows only the journal: the record holds the new path.
        assertTrue("siglip/moved/text.tar" in journalText().orEmpty())
        assertFalse("siglip/1/text.tar" in journalText().orEmpty())
        transport.live.remove(ref(entry(), text))
        transport.started.clear()
        process(entry = { moved }).downloader.resume()
        assertEquals(listOf("$BASE/siglip/moved/text.tar"), transport.started.map { it.url })
    }

    // ─── The same bytes under a newer catalog entry.

    /** The entry with its version, label set and licence moved, and not one byte or path of an artifact. */
    private fun renumbered(): ModelEntryData = entry().copy(modelVersion = 2, labelSetVersion = 7, licence = "MIT")

    private fun assertCarriesTheNewerEntry(installed: InstalledModelData) {
        assertEquals(2, installed.modelVersion)
        assertEquals(7, installed.labelSetVersion)
        assertEquals("MIT", installed.licence)
        assertEquals(entry().artifacts.map { it.sha256 }, installed.artifacts.map { it.sha256 })
    }

    @Test
    fun aNewerEntryOverTheSameBytesMidDownloadKeepsTheTransfersAndInstallsTheNewerRecord() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        var offered: ModelEntryData = entry()
        val phone = process(entry = { offered })
        val first = downloading(phone)
        transport.run(phone.downloader)
        assertEquals(3, transport.started.size)

        // The signed catalog now gives the same three artifacts another version, label set and licence.
        offered = renumbered()
        val second = downloading(phone)

        // ⚠ Not one transfer more, none cancelled, nothing staged thrown away.
        assertEquals(3, transport.started.size)
        assertEquals(emptyList(), transport.cancelled)
        assertTrue("\"modelVersion\":2" in journalText().orEmpty())
        assertTrue("\"licence\":\"MIT\"" in journalText().orEmpty())

        serve()
        transport.run(phone.downloader)
        // ⚠⚠ What the store holds is the newer entry, for whoever asked, and for a later process.
        assertCarriesTheNewerEntry(second.settled().getOrThrow())
        assertCarriesTheNewerEntry(first.settled().getOrThrow())
        assertCarriesTheNewerEntry(DownloadFixtures.storeOver(disk).installed().single())
        assertEquals(ModelDownloadOutcome.Installed(key(renumbered()), phone.store.installed().single()), phone.downloader.outcomeFor(key(renumbered())))
        assertNull(phone.downloader.outcomeFor(key(entry())))
    }

    @Test
    fun aNewerEntryOverBytesThatAreAllStagedFetchesNothingAndInstallsTheNewerRecord() = runTest(UnconfinedTestDispatcher()) {
        serve()
        // Everything arrived and was staged, and the runtime was torn down before the commit.
        var armed = false
        val earlier = process(retired = { armed && disk.delivered(DIR).isEmpty() })
        downloading(earlier)
        transport.runUnheard()
        armed = true
        earlier.downloader.resume()
        assertEquals(emptyList(), DownloadFixtures.storeOver(disk).installed())
        assertEquals(emptySet(), disk.delivered(DIR))
        transport.started.clear()

        val phone = process(entry = { renumbered() })
        val waiting = downloading(phone)

        assertEquals(emptyList(), transport.started)
        assertCarriesTheNewerEntry(waiting.settled().getOrThrow())
        assertCarriesTheNewerEntry(DownloadFixtures.storeOver(disk).installed().single())
    }

    @Test
    fun aNewerEntryOverBytesThatAreInstalledRewritesTheRecordAndFetchesNothing() = runTest(UnconfinedTestDispatcher()) {
        serve()
        var offered: ModelEntryData = entry()
        // ⚠ With the real budget and no room at all: a record that is only rewritten needs none.
        val phone = process(entry = { offered }, need = CoreMlDiskBudget::additionalNeed)
        val first = downloading(phone)
        transport.run(phone.downloader)
        val before: InstalledModelData = first.settled().getOrThrow()
        assertEquals(1, before.modelVersion)
        transport.started.clear()
        disk.deleted.clear()
        disk.free = 0

        offered = renumbered()
        val again = downloading(phone)

        // ⚠⚠ Zero bytes fetched, and the stored record is the newer entry's.
        assertEquals(emptyList(), transport.started)
        val after: InstalledModelData = again.settled().getOrThrow()
        assertCarriesTheNewerEntry(after)
        assertCarriesTheNewerEntry(DownloadFixtures.storeOver(disk).installed().single())
        // The same files, untouched: each is where it was, whole.
        assertEquals(before.artifacts.map { it.localPath }, after.artifacts.map { it.localPath })
        assertContentEquals(textBytes, disk.readBytes(after.pathOf(text)!!)?.get())
        assertTrue(disk.deleted.none { it.endsWith(".model") })
        // ⚠ Through the store-change wrapper like any commit: once for the install, once for this.
        assertEquals(listOf(0, 1), phone.changes.installedOnEntry)
        assertEquals("[]", journalText())
    }

    @Test
    fun eachOfTheThreeFactsTheStoreKeepsIsEnoughToRewriteTheRecord() = runTest(UnconfinedTestDispatcher()) {
        val moved: Map<String, ModelEntryData> = mapOf(
            "the version" to entry().copy(modelVersion = 2),
            "the label set" to entry().copy(labelSetVersion = 7),
            "the licence" to entry().copy(licence = "MIT"),
        )
        moved.forEach { (what, newer) ->
            run {
                val disk = DownloadDisk()
                val transport = FakeTransport(disk, DIR)
                entry().artifacts.forEach { transport.served["$BASE/${it.path}"] = Served.Stall }
                transport.served[url(entry(), vision)] = Served.Body(visionBytes)
                transport.served[url(entry(), text)] = Served.Body(textBytes)
                transport.served[url(entry(), vocabulary)] = Served.Body(vocabularyBytes)
                var offered: ModelEntryData = entry()
                val phone = DownloadProcess(disk, transport, backgroundScope, entry = { offered })
                backgroundScope.async { runCatching { phone.downloader.download(MODEL) } }
                transport.run(phone.downloader)
                transport.started.clear()

                offered = newer
                val again = backgroundScope.async { runCatching { phone.downloader.download(MODEL) } }

                assertTrue(again.isCompleted, what)
                val stored: InstalledModelData = DownloadFixtures.storeOver(disk).installed().single()
                assertEquals(
                    Triple(newer.modelVersion, newer.labelSetVersion, newer.licence),
                    Triple(stored.modelVersion, stored.labelSetVersion, stored.licence),
                    what,
                )
                assertEquals(emptyList(), transport.started, what)
            }
        }
    }

    @Test
    fun theSameBytesUnderAnotherRoleAreInstalledUnderThatRole() = runTest(UnconfinedTestDispatcher()) {
        serve()
        var offered: ModelEntryData = entry()
        val phone = process(entry = { offered })
        val first = downloading(phone)
        transport.run(phone.downloader)
        first.settled().getOrThrow()
        transport.started.clear()

        // The signed catalog renames a role and changes nothing else: not a byte, not a path,
        // not the version, the label set or the licence.
        val renamed = entry().copy(artifacts = entry().artifacts.map { if (it.role == text) it.copy(role = "textTower") else it })
        transport.served[url(entry(), text)] = Served.Body(textBytes)
        offered = renamed
        val again = downloading(phone)
        transport.run(phone.downloader)

        // ⚠⚠ The stored record names the role the entry does: a backend finds a tower by
        // `pathOf(role)`, and a record left as it was would hide this one for good.
        val stored: InstalledModelData = again.settled().getOrThrow()
        assertEquals(listOf(vision, "textTower", vocabulary), stored.artifacts.map { it.role })
        assertContentEquals(textBytes, disk.readBytes(stored.pathOf("textTower")!!)?.get())
        assertNull(stored.pathOf(text))
        assertEquals(stored, DownloadFixtures.storeOver(disk).installed().single())
        // The store keeps a file per role, so the renamed artifact is fetched; the others are not.
        assertEquals(listOf(url(entry(), text)), transport.started.map { it.url })
        assertEquals(ModelDownloadOutcome.Installed(key(renamed), stored), phone.downloader.outcomeFor(key(renamed)))
        assertNull(phone.downloader.outcomeFor(key(entry())))
    }

    /**
     * ⚠⚠ The comparison "is what is installed what this entry would install?" is of two whole
     * records, so it covers whatever a record holds. This pins what a record holds **today**:
     * a field added to it is compared without anyone remembering to, and this case then asks
     * for a line in `eachOfTheThreeFactsTheStoreKeepsIsEnoughToRewriteTheRecord` for it.
     */
    @Test
    fun whatAnInstallRecordHoldsIsWhatADownloadIsComparedBy() {
        val record = InstalledModelData("m", 1, listOf(InstalledArtifactData("vision", "p", 3L, "sha")), 1, "l")
        val json = kotlinx.serialization.json.Json.encodeToJsonElement(InstalledModelData.serializer(), record)
            as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("modelId", "modelVersion", "artifacts", "labelSetVersion", "licence"), json.keys)
        val artifact = (json["artifacts"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("role", "localPath", "byteSize", "sha256"), artifact.keys)
    }

    @Test
    fun anInstallRecordWhoseFileIsGoneIsNotTakenForTheBytes() = runTest(UnconfinedTestDispatcher()) {
        serve()
        var offered: ModelEntryData = entry()
        val phone = process(entry = { offered })
        val first = downloading(phone)
        transport.run(phone.downloader)
        // The text tower's file is gone from under its record.
        val installed: InstalledModelData = first.settled().getOrThrow()
        disk.deleteFile(installed.pathOf(text)!!)
        transport.started.clear()

        // ⚠⚠ The SAME entry is asked for again, which is what a user does: the record is, field
        // for field, what a commit would write. It was once taken for "installed" on that alone,
        // with no look at the files, and the lost tower was never fetched again. (This case
        // passed all the same, because it asked for a renumbered entry.)
        val same = downloading(phone)
        assertTrue(same.isActive, "a record whose file is gone answered 'installed'")
        // ⚠ A record is not the file: that artifact, and only that one, is fetched.
        assertEquals(listOf(url(entry(), text)), transport.started.map { it.url })
        transport.run(phone.downloader)
        assertEquals(installed, same.settled().getOrThrow())
        assertContentEquals(textBytes, disk.readBytes(installed.pathOf(text)!!)?.get())

        // And under a newer entry, with a file gone again.
        disk.deleteFile(installed.pathOf(text)!!)
        transport.started.clear()
        offered = renumbered()
        val again = downloading(phone)
        assertEquals(listOf(url(entry(), text)), transport.started.map { it.url })
        transport.run(phone.downloader)
        val after: InstalledModelData = again.settled().getOrThrow()
        assertCarriesTheNewerEntry(after)
        assertContentEquals(textBytes, disk.readBytes(after.pathOf(text)!!)?.get())
    }

    // ─── Outcomes are about the download they describe.

    @Test
    fun anOutcomeSaysNothingAboutAnotherDownload() = runTest(UnconfinedTestDispatcher()) {
        val first = entry()
        val second = entry(version = 2, text = ByteArray(8192) { 9 })
        var offered: ModelEntryData = first
        serve(first, text = Served.Body("denied".encodeToByteArray(), status = 500))
        val phone = process(entry = { offered })
        val failed = downloading(phone)
        transport.run(phone.downloader)
        assertIs<ModelDownloadException>(failed.settled().exceptionOrNull())

        assertIs<ModelDownloadOutcome.Failed>(phone.downloader.outcomeFor(key(first)))
        // The catalog now offers other bytes under the same id: the failure is not theirs.
        offered = second
        assertNull(phone.downloader.outcomeFor(key(second)))
    }

    @Test
    fun aNewerCatalogEntryReplacesTheDownloadThatWasRunning() = runTest(UnconfinedTestDispatcher()) {
        val older = entry()
        val newer = entry(version = 2, text = ByteArray(8192) { 9 })
        var offered: ModelEntryData = older
        serve(older, text = Served.Stall)
        serve(newer, text = Served.Body(ByteArray(8192) { 9 }))
        val phone = process(entry = { offered })
        val first = downloading(phone)

        offered = newer
        val second = downloading(phone)

        assertIs<ModelDownloadCancelledException>(first.settled().exceptionOrNull())
        // ⚠ Only the transfer the newer entry does not share. Vision and the vocabulary are the
        // same bytes under the same ref: they are neither cancelled nor started a second time.
        assertEquals(listOf(ref(older, text)), transport.cancelled)
        assertEquals(4, transport.started.size)
        transport.run(phone.downloader)
        assertEquals(newer.artifacts.map { it.sha256 }, second.settled().getOrThrow().artifacts.map { it.sha256 })
        // ⚠⚠ Nothing of the download that was replaced is still "running": a ref left tracked
        // would keep this true, and a finished bar restarting, until the process is replaced.
        assertFalse(phone.downloader.isDownloading(MODEL))
        assertEquals(emptyMap(), phone.progress.progress.value)
    }

    // ─── What a screen follows (§14.18.2): the journal as this process knows it, and the last
    // thing said, each with what it is about.

    private val named = mapOf(MODEL to JournalledModelDownload(key(entry()), allowsCellular = false))

    @Test
    fun aProcessSaysNothingOfTheJournalBeforeItsFirstLookAndWhatItNamesAfter() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        assertEquals(ModelDownloadJournalView.NotLookedYet, phone.downloader.journalNow())
        downloading(phone)
        // ⚠ With the record's own key and choice: what a bar and an outcome are compared with.
        assertEquals(ModelDownloadJournalView.Read(named), phone.downloader.journalNow())
        assertEquals(entry().downloadKey(), named.getValue(MODEL).key)

        // The app is dead. A new process knows nothing: not *none*, and not what the last one knew.
        val relaunched = process()
        assertEquals(ModelDownloadJournalView.NotLookedYet, relaunched.downloader.journalNow())
        assertEquals(null, relaunched.downloader.lastOutcome.value)
        relaunched.downloader.resume()
        assertEquals(ModelDownloadJournalView.Read(named), relaunched.downloader.journalNow())
    }

    @Test
    fun theCellularChoiceOfARunningDownloadIsTheOneItWasJournalledWith() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        downloading(process(), allowsCellular = true)

        val relaunched = process()
        relaunched.downloader.resume()

        val view = assertIs<ModelDownloadJournalView.Read>(relaunched.downloader.journalNow())
        assertEquals(true, view.named.getValue(MODEL).allowsCellular)
    }

    @Test
    fun aDownloadIsNamedUntilItsRecordIsGoneAndNotAfter() = runTest(UnconfinedTestDispatcher()) {
        serve()
        val phone = process()
        val views = mutableListOf<ModelDownloadJournalView>()
        backgroundScope.launch { phone.downloader.journalView.collect { views += it } }
        val install = downloading(phone)
        transport.run(phone.downloader)
        install.settled().getOrThrow()

        assertEquals(ModelDownloadJournalView.Read(emptyMap()), phone.downloader.journalNow())
        assertTrue(ModelDownloadJournalView.Read(named) in views)

        // And a cancel takes it off too.
        serve(text = Served.Stall)
        phone.store.remove(MODEL)
        downloading(phone)
        assertEquals(ModelDownloadJournalView.Read(named), phone.downloader.journalNow())
        phone.downloader.cancel(MODEL)
        assertEquals(ModelDownloadJournalView.Read(emptyMap()), phone.downloader.journalNow())
    }

    @Test
    fun aJournalThatCannotBeReadIsSaidAndIsNeitherNoneNorNotLookedAt() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        downloading(phone)

        disk.readFaults[JOURNAL] = DownloadDisk.ReadFault.THROWS
        // A process that had read it before: what it named then, and that this read failed.
        phone.downloader.resume()
        assertEquals(ModelDownloadJournalView.Unreadable(named), phone.downloader.journalNow())
        // A new process: nothing was ever named to it. ⚠ Not `Read(emptyMap())`.
        val relaunched = process()
        relaunched.downloader.resume()
        assertEquals(ModelDownloadJournalView.Unreadable(emptyMap()), relaunched.downloader.journalNow())

        // Readable again: the failure is withdrawn by the read that worked.
        disk.readFaults.clear()
        relaunched.downloader.resume()
        assertEquals(ModelDownloadJournalView.Read(named), relaunched.downloader.journalNow())
    }

    @Test
    fun aGraphThatIsTornDownSaysSoToWhoeverFollowsItAndNothingAfter() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        downloading(phone)
        val views = mutableListOf<ModelDownloadJournalView>()
        backgroundScope.launch { phone.downloader.journalView.collect { views += it } }

        phone.downloader.tornDown()

        assertEquals(ModelDownloadJournalView.Retired, views.last())
        // ⚠ Whatever is asked of it afterwards: it does not go back to naming a download.
        phone.downloader.resume()
        phone.downloader.isDownloading(MODEL)
        assertEquals(ModelDownloadJournalView.Retired, phone.downloader.journalNow())
        assertEquals(ModelDownloadJournalView.Retired, views.last())
    }

    @Test
    fun whatWasSaidOfADownloadIsWithdrawnOnceItGoesOn() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall, vocabulary = Served.Stall)
        downloading(process(need = CoreMlDiskBudget::additionalNeed))
        transport.runUnheard()
        transport.live.remove(ref(entry(), text))
        transport.started.clear()
        val need: Long = CoreMlDiskBudget.additionalNeed(entry().byteSize, textBytes.size.toLong() + vocabularyBytes.size)

        disk.free = need - 1
        val relaunched = process(need = CoreMlDiskBudget::additionalNeed)
        relaunched.downloader.resume()
        val refused = ModelDownloadOutcome.Refused(key(entry()), ModelDownloadRefusal.InsufficientStorage(need, need - 1))
        assertEquals(refused, relaunched.downloader.lastOutcome.value)
        assertEquals(refused, relaunched.downloader.outcomeFor(key(entry())))

        // Room again, in the SAME process: the transfer is started, and "refused" no longer stands.
        disk.free = need
        relaunched.downloader.resume()
        assertEquals(listOf(url(entry(), text)), transport.started.map { it.url })
        assertEquals(null, relaunched.downloader.lastOutcome.value, "a download that goes on is still said to be refused")
        assertEquals(ModelDownloadJournalView.Read(named), relaunched.downloader.journalNow())

        // And once more with nothing to start: every missing artifact has its transfer.
        disk.free = need - 1
        relaunched.downloader.resume()
        assertEquals(null, relaunched.downloader.lastOutcome.value)
    }

    @Test
    fun whatWasSaidOfAnotherDownloadIsNotWithdrawnByThisOne() = runTest(UnconfinedTestDispatcher()) {
        val older = entry()
        val newer = entry(version = 2, vision = ByteArray(4096) { 8 }, text = ByteArray(8192) { 9 }, vocabulary = ByteArray(100) { 7 })
        serve(older, text = Served.Stall, vocabulary = Served.Stall)
        var offered: ModelEntryData = older
        val phone = process(entry = { offered }, need = CoreMlDiskBudget::additionalNeed)
        downloading(phone)
        transport.run(phone.downloader)

        // The catalog moves on to other bytes, and there is no room for them: refused, and the
        // download that is running is not touched (§14.11 ✓⁹).
        offered = newer
        disk.free = 1
        val attempt = downloading(phone)
        assertTrue(attempt.isCompleted)
        val saidOfTheNewer = assertIs<ModelDownloadOutcome.Refused>(phone.downloader.lastOutcome.value)
        assertEquals(key(newer), saidOfTheNewer.key)

        // The older download goes on (its transfers are running). ⚠ That says nothing of the newer.
        offered = older
        phone.downloader.resume()
        assertEquals(saidOfTheNewer, phone.downloader.lastOutcome.value, "one download going on withdrew what was said of another")
        assertEquals(key(older), assertIs<ModelDownloadJournalView.Read>(phone.downloader.journalNow()).named.getValue(MODEL).key)
    }

    // ─── §14.11's matrix, `cancel` in S4 (⚠ᵃ until §14.18: the screen is its first caller).

    @Test
    fun cancellingAnUpdateEndsTheUpdateAndLeavesTheInstalledModelAsItWas() = runTest(UnconfinedTestDispatcher()) {
        val (older, newer) = anUpdateUnderWay()
        // ⚠ A process that was only just relaunched, as the screen's may be.
        val phone = process(entry = { newer })
        val installedBefore: InstalledModelData = phone.store.installed().single()
        assertEquals(older.modelVersion, installedBefore.modelVersion)
        assertTrue(stagedFiles().isNotEmpty(), "nothing of the update was staged: the case sets up no S4")

        phone.downloader.cancel(MODEL)

        // The update: its record, its transfers, what it had staged.
        assertEquals("[]", journalText())
        assertTrue(transport.cancelled.containsAll(listOf(ref(newer, text), ref(newer, vocabulary))))
        assertEquals(emptyList(), stagedFiles())
        assertEquals(ModelDownloadOutcome.Cancelled(key(newer)), phone.downloader.outcomeFor(key(newer)))
        assertEquals(ModelDownloadJournalView.Read(emptyMap()), phone.downloader.journalNow())
        assertFalse(phone.downloader.isDownloading(MODEL))
        // ⚠⚠ The installed model: the same record, and every byte of it still where it says.
        assertEquals(listOf(installedBefore), DownloadFixtures.storeOver(disk).installed())
        installedBefore.artifacts.forEach { artifact ->
            assertEquals(artifact.byteSize, disk.fileSize(artifact.localPath), artifact.role)
        }
        // ⚠ And the store was not changed on the way: readiness's writer 3 was not run.
        assertEquals(emptyList(), phone.changes.changedFor)

        // Whatever the session still delivers: the update does not come back.
        serve(newer)
        transport.run(phone.downloader)
        phone.downloader.resume()
        assertEquals(listOf(installedBefore), DownloadFixtures.storeOver(disk).installed())
    }

    // ─── What the view may not go on saying.

    @Test
    fun aDownloadWhoseRecordIsGoneIsNotRunningAlthoughItsCleanupThrew() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall, vocabulary = Served.Stall)
        val phone = process()
        downloading(phone)
        transport.run(phone.downloader)
        val staged = "$DEFAULT_MODELS_DIR/$MODEL.$vision.model.part"
        assertEquals(visionBytes.size.toLong(), disk.fileSize(staged))

        // The cancel removes the record, and then cannot delete what the download had staged.
        disk.failingDeletes += staged
        val cancelled = runCatching { phone.downloader.cancel(MODEL) }
        assertTrue(cancelled.isFailure, "the case sets up no cleanup that throws")
        assertEquals("[]", journalText())

        // ⚠⚠ The journal names nothing, so nothing is running: no bar, and no Cancel that finds no record.
        assertEquals(ModelDownloadJournalView.Read(emptyMap()), phone.downloader.journalNow())
        assertFalse(phone.downloader.isDownloading(MODEL))
        assertEquals(emptyMap(), phone.progress.progress.value)
    }

    @Test
    fun aDownloadTheJournalNoLongerNamesIsDroppedAtTheNextRead() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        downloading(phone)
        assertEquals(ModelDownloadJournalView.Read(named), phone.downloader.journalNow())

        // The journal goes from under it: absent, then bytes that are no journal of this build.
        listOf<ByteArray?>(null, "{\"another\":\"shape\"}".encodeToByteArray()).forEach { bytes ->
            if (bytes == null) disk.deleteFile(JOURNAL) else disk.writeRawByteArray(JOURNAL, bytes)
            phone.downloader.resume()

            // ⚠⚠ What is shown is what THIS read named: it was only ever added to.
            assertEquals(ModelDownloadJournalView.Read(emptyMap()), phone.downloader.journalNow())
            assertFalse(phone.downloader.isDownloading(MODEL))
            assertEquals(emptyMap(), phone.progress.progress.value)
        }
    }

    @Test
    fun theOtherCellularChoiceIsRefusedToItsCallerAndSaidOfNoDownload() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        downloading(phone)
        transport.run(phone.downloader)

        refused<ModelDownloadChoiceConflictException>(phone, allowsCellular = true)

        // ⚠⚠ The outcome is keyed by the record, which is the running download's too: said
        // there, it was shown as why the running download was not moving, while it moved.
        assertEquals(null, phone.downloader.lastOutcome.value)
        assertEquals(null, phone.downloader.outcomeFor(key(entry())))
    }

    @Test
    fun noCollectorIsEverRunInsideTheDownloadersLock() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        val insideTheLock = mutableListOf<ModelDownloadJournalView>()
        val seen = mutableListOf<ModelDownloadJournalView>()
        // ⚠ An immediate collector, as a view model's is on Kotlin/Native: it runs in the setter.
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            phone.downloader.journalView.collect { view ->
                seen += view
                if (phone.downloader.changingNow()) insideTheLock += view
            }
        }
        downloading(phone)
        phone.downloader.cancel(MODEL)
        downloading(phone)
        phone.downloader.tornDown()
        collector.cancel()

        assertTrue(ModelDownloadJournalView.Read(named) in seen && seen.last() == ModelDownloadJournalView.Retired, "$seen")
        assertEquals(emptyList(), insideTheLock, "a collector ran inside the lock, and inside the teardown")
    }

    // ─── What "accepted" may rest on: the call is told when it has a record.

    @Test
    fun aCallIsToldOnceItHasARecordInTheJournalAndNeverWhenItHasNone() = runTest(UnconfinedTestDispatcher()) {
        serve(text = Served.Stall)
        val phone = process()
        val told = mutableListOf<Pair<JournalledModelDownload, String?>>()
        fun asking(allowsCellular: Boolean = false) = backgroundScope.async {
            runCatching { phone.downloader.download(MODEL, allowsCellular) { told += it to journalText() } }
        }

        // A first call: told with its record, AFTER the record was written.
        asking()
        assertEquals(1, told.size)
        assertEquals(JournalledModelDownload(key(entry()), allowsCellular = false), told.single().first)
        assertTrue(told.single().second.orEmpty().contains(MODEL), "a call was told before its record was on the disk")
        // A second call joins the running record: told the same.
        asking()
        assertEquals(2, told.size)
        // The other choice is refused: told nothing.
        assertTrue(asking(allowsCellular = true).await().isFailure)
        assertEquals(2, told.size)

        // Refused before any record (no room), and torn down before one: told nothing.
        val other = DownloadDisk()
        other.free = 1
        val cramped = DownloadProcess(other, FakeTransport(other, DIR), backgroundScope)
        var toldCramped = 0
        assertTrue(backgroundScope.async { runCatching { cramped.downloader.download(MODEL, false) { toldCramped += 1 } } }.await().isFailure)
        phone.downloader.tornDown()
        var toldAfterTeardown = 0
        assertTrue(backgroundScope.async { runCatching { phone.downloader.download(MODEL, false) { toldAfterTeardown += 1 } } }.await().isFailure)
        assertEquals(0, toldCramped + toldAfterTeardown)
    }
}

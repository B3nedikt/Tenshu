package org.tenshu.inference.proxy.catalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.inference.proxy.catalog.DownloadFixtures.MODEL
import org.tenshu.inference.proxy.catalog.DownloadFixtures.entry
import org.tenshu.inference.proxy.catalog.DownloadFixtures.sha256
import org.tenshu.inference.proxy.catalog.DownloadFixtures.textBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PIO-7 PR-5 slice 5c (`ios-on-device-inference.md` §14.11–§14.13): the pieces the background
 * download stands on that are not the downloader itself.
 */
class ModelDeliveryForATransportThatOutlivesTheProcessTest {

    private val text = ModelArtifactRole.TEXT

    // ─── §3.4's budgets, as the check computes them.

    @Test
    fun theMeasuredDownloadNeedsTheCachesOwnChecksSummedAndTheMargin() {
        val download: Long = CoreMlDiskBudget.MEASURED_DOWNLOAD_BYTES

        // 743.4 MB to fetch + 555.0 MB + 1 281.4 MB to prepare = 2 579.8 MB (§3.4) is what was
        // measured; the cache asks for 3 × each archive before its tower, and 3 × the whole
        // download (2 230.2 MB) is more than the measured 1 836.4 MB, so that is what is asked.
        assertEquals(1_836_400_000L, CoreMlDiskBudget.prepareBytes(download))
        assertEquals(
            743_400_000L + 2_230_200_000L + CoreMlDiskBudget.PLACEHOLDER_MARGIN_BYTES,
            CoreMlDiskBudget.additionalNeed(download, download),
        )
        // ⚠ So a phone with exactly the measured figure free is refused: §3.4's figures carry
        // no margin, and the check adds its own.
        assertTrue(2_579_800_000L < CoreMlDiskBudget.additionalNeed(download, download))
        assertEquals(256L * 1024 * 1024, CoreMlDiskBudget.PLACEHOLDER_MARGIN_BYTES)
    }

    /**
     * ⚠⚠ §14.17.5: a download the budget admits is never refused at a tower's own check for room
     * the budget did not ask for. With the measured prepare alone, a phone admitted with the least
     * it allowed was refused at the text tower after vision took its share: a band of ~115 MB.
     * Held here for the published archives, in both orders, with each earlier tower taking all
     * the cache allows it.
     */
    @Test
    fun aDownloadTheBudgetAdmitsPassesEveryTowersOwnCheckInEitherOrder() {
        val vision = 184_842_240L
        val text = 555_540_480L
        val entry: Long = vision + text + 3_066_150L
        // The least free space the budget admits, the whole download still to fetch.
        val admitted: Long = CoreMlDiskBudget.additionalNeed(entry, entry)
        listOf(listOf(vision, text), listOf(text, vision)).forEach { order ->
            var free: Long = admitted - entry
            order.forEach { archive ->
                assertTrue(free >= CoreMlDiskBudget.towerNeed(archive), "$order: refused at the $archive-byte tower with $free free")
                free -= CoreMlDiskBudget.towerNeed(archive)
            }
        }
    }

    @Test
    fun theBudgetScalesWithTheArtifactAndNeverRoundsDown() {
        // The published entry: 184 842 240 + 555 540 480 bytes of archives and the vocabulary's
        // 3 066 150 (`models.md`). 48 870 bytes more than the measured 743.4 MB as §3.4 rounds
        // it, so slightly more to prepare.
        val published: Long = 184_842_240L + 555_540_480L + 3_066_150L
        assertEquals(743_448_870L, published)
        val prepare: Long = CoreMlDiskBudget.prepareBytes(published)
        assertTrue(prepare > CoreMlDiskBudget.MEASURED_PREPARE_BYTES)
        assertTrue(prepare < CoreMlDiskBudget.MEASURED_PREPARE_BYTES + 1_000_000L)
        // Rounded up, at every size, and exact at whole multiples.
        assertEquals(2 * CoreMlDiskBudget.MEASURED_PREPARE_BYTES, CoreMlDiskBudget.prepareBytes(2 * CoreMlDiskBudget.MEASURED_DOWNLOAD_BYTES))
        assertEquals(3L, CoreMlDiskBudget.prepareBytes(1L))
        assertEquals(0L, CoreMlDiskBudget.prepareBytes(0L))
        // What is already held is not budgeted for a second time; the prepare step always is.
        assertEquals(
            CoreMlDiskBudget.additionalNeed(published, published) - 184_842_240L,
            CoreMlDiskBudget.additionalNeed(published, published - 184_842_240L),
        )
    }

    // ─── Adopting a delivered file into the store's staging.

    @Test
    fun adoptingMovesTheDeliveredFileIntoStagingAndTheNextAttemptResumesFromIt() {
        val disk = DownloadDisk()
        val store = DownloadFixtures.storeOver(disk)
        disk.writeRawByteArray("inference/downloads/x.delivered", textBytes)

        val staged: Long = store.adoptStaged(MODEL, text, sha256(textBytes), "inference/downloads/x.delivered")

        assertEquals(textBytes.size.toLong(), staged)
        // Moved, not copied: the artifact is on the disk once.
        assertNull(disk.fileSize("inference/downloads/x.delivered"))
        assertContentEquals(textBytes, store.readStaged(MODEL, text, 0, textBytes.size))
        // Staged for this digest, and for no other.
        assertEquals(textBytes.size.toLong(), DownloadFixtures.storeOver(disk).beginStaging(MODEL, text, sha256(textBytes)))
        assertEquals(0L, DownloadFixtures.storeOver(disk).beginStaging(MODEL, text, "another digest"))
    }

    @Test
    fun adoptingReplacesWhatAnEarlierAttemptHadStaged() {
        val disk = DownloadDisk()
        val store = DownloadFixtures.storeOver(disk)
        store.beginStaging(MODEL, text, "an older digest")
        store.writeStaged(MODEL, text, 0, ByteArray(20_000) { 5 })
        disk.writeRawByteArray("inference/downloads/x.delivered", textBytes)

        store.adoptStaged(MODEL, text, sha256(textBytes), "inference/downloads/x.delivered")

        // ⚠ Exactly the delivered bytes: nothing of the longer file that was there survives.
        assertEquals(0L, DownloadFixtures.storeOver(disk).beginStaging(MODEL + "-other", text, "an older digest"))
        assertEquals(textBytes.size.toLong(), store.beginStaging(MODEL, text, sha256(textBytes)))
        assertContentEquals(textBytes, store.readStaged(MODEL, text, 0, 20_000))
    }

    @Test
    fun aMoveThatLeftNothingIsNotStagedUnderTheNewDigest() {
        val disk = DownloadDisk()
        val store = DownloadFixtures.storeOver(disk)
        store.beginStaging(MODEL, text, sha256(textBytes))
        store.writeStaged(MODEL, text, 0, textBytes.copyOf(100))

        // Nothing at the source: the move moves nothing.
        assertFailsWith<IllegalStateException> {
            store.adoptStaged(MODEL, text, sha256(textBytes), "inference/downloads/missing.delivered")
        }

        // ⚠ What was staged went before the move, so nothing is left to be offered as a resume.
        assertEquals(0L, DownloadFixtures.storeOver(disk).beginStaging(MODEL, text, sha256(textBytes)))
    }

    @Test
    fun aStoreWhoseIndexCannotBeReadAdoptsNothing() {
        val disk = DownloadDisk()
        disk.writeRawByteArray("$DEFAULT_MODELS_DIR/inference.models.index.json", """{"not":"an index"}""".encodeToByteArray())
        disk.writeRawByteArray("inference/downloads/x.delivered", textBytes)

        assertFailsWith<ModelIndexUnreadableException> {
            DownloadFixtures.storeOver(disk).adoptStaged(MODEL, text, sha256(textBytes), "inference/downloads/x.delivered")
        }

        assertEquals(textBytes.size.toLong(), disk.fileSize("inference/downloads/x.delivered"))
    }

    @Test
    fun aStoreThatKeepsNoFilesRefusesToAdoptOne() {
        assertFailsWith<UnsupportedOperationException> {
            InMemoryModelStore(::sha256).adoptStaged(MODEL, text, "digest", "anywhere")
        }
    }

    // ─── The in-process downloader, where a resume IS a `Range` request.

    /**
     * ⚠⚠ What the model CDN did on 2026-10-11: the first range request to the text archive was
     * answered `200` with the whole body, and every later one `206`. Over the real store, whose
     * staging is a file: the bytes already staged are dropped and the artifact starts from zero.
     * Appended instead, the file is the partial body followed by the whole one, which only the
     * digest catches, after the whole download.
     */
    @Test
    fun aResumeAnsweredTwoHundredRestartsTheArtifactFromZeroOverTheRealStore() = runTest {
        val disk = DownloadDisk()
        val store = DownloadFixtures.storeOver(disk)
        val one = ModelEntryData(
            modelId = MODEL, name = "SigLIP", modelVersion = 1, capabilities = emptyList(), labelSetVersion = 1,
            runtime = "onnx", artifactVersion = 1, licence = "Apache-2.0",
            artifacts = listOf(DownloadFixtures.artifact(text, "siglip/text.onnx", textBytes)),
        )
        store.beginStaging(MODEL, text, sha256(textBytes))
        store.writeStaged(MODEL, text, 0, textBytes.copyOf(3000))
        val ranges = mutableListOf<String?>()
        val engine = MockEngine { request ->
            ranges += request.headers[HttpHeaders.Range]
            // The header is ignored: the whole body, with a plain 200.
            respond(textBytes, status = HttpStatusCode.OK)
        }
        val downloader = DefaultModelDownloader(
            httpClient = HttpClient(engine),
            baseUrl = "https://models.example",
            catalogSource = DownloadFixtures.catalogOf { one },
            store = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            liveness = BackgroundLiveness(),
            progress = ModelDownloadProgressHolder(),
            chunkBytes = 1024,
        )

        val installed: InstalledModelData = downloader.download(MODEL)

        assertEquals(listOf<String?>("bytes=3000-"), ranges)
        assertEquals(textBytes.size.toLong(), disk.fileSize(installed.pathOf(text)!!))
        assertContentEquals(textBytes, disk.readBytes(installed.pathOf(text)!!)?.get())
    }

    // ─── Which downloader a graph gets.

    @Test
    fun aGraphThatContributesADownloaderGetsItAndNotTheInProcessFetch() {
        val disk = DownloadDisk()
        val mine = object : ModelDownloader {
            override suspend fun download(modelId: String): InstalledModelData = error("not asked")
            override suspend fun isDownloading(modelId: String): Boolean = false
        }
        var handed: Triple<ModelStore, ModelDownloadProgressHolder, String>? = null
        fun components(factory: ModelDownloaderFactory?, changes: ModelStoreChanges?) = modelDeliveryComponents(
            httpClient = HttpClient(MockEngine { respond("") }),
            storageDelegate = org.tenshu.core.common.delegate.InMemorySecureStorageDelegate(),
            fileDatabase = disk,
            ed25519 = object : org.tenshu.core.common.security.Ed25519Backend {
                override fun sign(privateKeySeed: ByteArray, message: ByteArray) = ByteArray(64)
                override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray) = true
                override fun derivePublicKey(privateKeySeed: ByteArray) = ByteArray(32)
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            backgroundLiveness = BackgroundLiveness(),
            catalogBaseUrl = "https://models.example",
            storeOwnership = ModelStoreOwnership.SoleProcess,
            downloaderFactory = factory,
            storeChanges = changes ?: ModelStoreChanges.Direct,
        )
        val wrapper = RecordingChanges { 0 }

        val owner = components(
            factory = { _, store, progress, baseUrl ->
                handed = Triple(store, progress, baseUrl)
                mine
            },
            changes = wrapper,
        )
        val everyoneElse = components(factory = null, changes = null)

        assertTrue(owner.downloader === mine)
        // Over the components' own store and progress holder, not a second pair.
        assertTrue(handed?.first === owner.store)
        assertTrue(handed?.second === owner.progress)
        assertEquals("https://models.example", handed?.third)
        assertTrue(owner.storeChanges === wrapper)
        assertTrue(everyoneElse.downloader is DefaultModelDownloader)
        assertTrue(everyoneElse.storeChanges === ModelStoreChanges.Direct)
    }
}

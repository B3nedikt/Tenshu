@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.tenshu.sdk.ios

import app.tenshu.control.inference.ModelActionResult
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OnDeviceModelSource
import app.tenshu.control.di.INFERENCE_FILE_DATABASE
import app.tenshu.control.di.OwnedInferenceStores
import app.tenshu.control.di.PhotosModelDownloads
import app.tenshu.control.di.PhotosOnDeviceAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.core.common.util.Sha256Digest
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.deleteModel
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.AiStaticGates
import org.tenshu.inference.proxy.ProbeRecorded
import org.tenshu.inference.proxy.ProbeResult
import org.tenshu.inference.proxy.ModelPreparer
import org.tenshu.inference.proxy.PrepareOutcome
import org.tenshu.inference.proxy.catalog.ModelTransferReport
import org.tenshu.inference.proxy.catalog.BackgroundModelDownloader
import org.tenshu.inference.proxy.catalog.DefaultModelDownloader
import org.tenshu.inference.proxy.catalog.ModelArtifactData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelDeliveryComponents
import org.tenshu.inference.proxy.catalog.ModelDownloaderFactory
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.catalog.ModelStoreChanges
import org.tenshu.inference.proxy.catalog.ModelTransferPort
import org.tenshu.inference.proxy.catalog.ModelTransferRequest
import org.tenshu.inference.proxy.coreml.iosPreflightKey
import org.tenshu.inference.proxy.coreml.iosRuntimeFingerprint
import org.tenshu.inference.proxy.onnx.VisionModel
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Foundation.NSTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.lifecycle.viewModelScope
import app.tenshu.control.ui.screens.models.CapabilityConsent
import app.tenshu.control.ui.screens.models.ClientModelsDevices
import app.tenshu.control.ui.screens.models.ModelsMessage
import app.tenshu.control.ui.screens.models.ModelsRepository
import app.tenshu.control.ui.screens.models.ModelsViewModel
import app.tenshu.control.ui.screens.models.OnDeviceModelsCapabilities
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import org.tenshu.sync.common.SyncManager
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.inference.proxy.hasInferenceConsent
import app.tenshu.photos.ui.screens.search.ModelsSwitchHome
import app.tenshu.photos.model.SemanticSearchStatus

/**
 * `ios-on-device-inference.md` §14.11: the model download **as Photos' graph
 * binds it**. The downloader's own rules are `BackgroundModelDownloaderTest`'s, on the JVM and the
 * simulator; here it is the wiring, over the real `IosFileDatabase`, the real model store and the
 * graph's own readiness.
 *
 * ⭐ Every graph is `PhotosLocalRuntime.assemble`'s own, as in [PhotosOnDeviceAiTest].
 * ⚠⚠ **No graph here has a catalog** ([noCatalogThen]): the production binding is the real CDN,
 * and a graph built over it asks it whenever a source is collected or a download is pressed. A
 * case that expects *no catalog* would then answer whatever the network does
 * (`ios-on-device-inference.md` §14.18.17): `Accepted` where the simulator can reach the CDN (the
 * published `coreml` entry replaces the journalled one), `StillStarting` where the fetch outlasts
 * the press's 15 s, and the expected refusal only where the fetch fails at once (spawned
 * `--standalone` on a simulator that is not booted). A download is *started* only in the downloader's own cases;
 * what runs here is everything after the journal is on disk, which is all a relaunched process
 * ever has.
 */
@OptIn(ExperimentalEncodingApi::class)
class PhotosModelDownloadTest {

    @BeforeTest
    fun redirectMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun restore() {
        // ⚠ Process globals: the installed runtime, the surface, and the transport Swift installs.
        if (PhotosLocalRuntime.isInstalled()) PhotosLocalRuntime.deprovision()
        PhotosSurface.installNotConnected()
        PhotosModelDownloads.installTransfers(null)
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
        Dispatchers.resetMain()
    }

    private val manager = NSFileManager.defaultManager

    private val settled = kotlin.concurrent.AtomicInt(0)
    private val modelId: String = VisionModel.PinnedSigLip.modelId

    private fun temp(name: String): String = NSTemporaryDirectory() + "$name-" + Random.nextLong().toString(16)

    private fun material() = RealmKeyMaterial(
        sharedIdentityKey = Random.nextBytes(32),
        sharedVolumeKey = Random.nextBytes(32),
        deviceSigningBlob = (
            Base64.encode(Random.nextBytes(32)) + "|" + Base64.encode(Random.nextBytes(32))
            ).encodeToByteArray(),
        ownerId = "owner-a",
        deviceId = "device-1",
    )

    private val eightGigabytes = 8L * 1024 * 1024 * 1024
    private val admittedPhone = PhotosLocalRuntime.OnDeviceAiInputs(buildFlag = true, osMajorVersion = 17, physicalMemoryBytes = eightGigabytes)

    private fun photos(
        root: String,
        onDeviceAi: PhotosLocalRuntime.OnDeviceAiInputs? = admittedPhone,
        overrides: Module? = null,
    ): PhotosLocalRuntime.Assembly = PhotosLocalRuntime.assemble(
        replicaDirectory = temp("photos-dl-replica"),
        secureStorage = InMemorySecureStorageDelegate(),
        handedOffMaterial = material(),
        relayBaseUrl = "http://127.0.0.1:1",
        inferenceRoot = root,
        deviceRealmOwner = { "owner-a" },
        onDeviceAi = onDeviceAi,
        // ⚠⚠ No catalog under any graph of this class (§14.18.17); the case's own bindings after it.
        onDeviceAiOverrides = noCatalogThen(overrides),
    )

    private suspend fun eventually(what: String, condition: suspend () -> Boolean) {
        runCatching { withTimeout(30_000) { while (!condition()) delay(20) } }
        assertTrue(condition(), what)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun sha256(bytes: ByteArray): String = Sha256Digest().apply { update(bytes, bytes.size) }.digest().toHexString()

    /** The session as a relaunched process finds it: holding [live], and recording what it is asked. */
    private class Session(val live: MutableSet<String> = mutableSetOf()) : ModelTransferPort {
        val started = ArrayList<ModelTransferRequest>()
        val cancelled = ArrayList<String>()
        override fun start(request: ModelTransferRequest) {
            started += request
        }
        override suspend fun liveRefs(): Set<String> = live.toSet()
        override fun cancel(ref: String) {
            cancelled += ref
        }
    }

    private fun over(session: Session): Module = module { single<ModelTransferPort> { session } }

    private val generation: Map<String, ByteArray> = mapOf(
        ModelArtifactRole.VISION to ByteArray(300_000) { (it % 251).toByte() },
        ModelArtifactRole.TEXT to ByteArray(700_000) { (it % 241).toByte() },
        ModelArtifactRole.VOCABULARY to ByteArray(5_000) { (it % 7).toByte() },
    )

    private fun artifacts(bytes: Map<String, ByteArray>): List<ModelArtifactData> =
        bytes.map { (role, content) -> ModelArtifactData(role, "$modelId/1/coreml/$role.bin", sha256(content), content.size.toLong()) }

    private fun refOf(bytes: Map<String, ByteArray>, role: String): String =
        BackgroundModelDownloader.refOf(modelId, artifacts(bytes).first { it.role == role }, allowsCellular = false)

    /**
     * The journal as [BackgroundModelDownloader] writes it before it starts a transfer. ⚠ Written
     * out by hand on purpose: this is the format a process that knows nothing else reads.
     */
    private fun journal(root: String, bytes: Map<String, ByteArray>, allowsCellular: Boolean = false) {
        val records: String = artifacts(bytes).joinToString(",") { artifact ->
            """{"role":"${artifact.role}","path":"${artifact.path}","sha256":"${artifact.sha256}",""" +
                """"byteSize":${artifact.byteSize},"ref":"${BackgroundModelDownloader.refOf(modelId, artifact, allowsCellular)}"}"""
        }
        IosFileDatabase(root).writeRawByteArray(
            "${BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR}/${BackgroundModelDownloader.JOURNAL_FILE}",
            (
                """[{"modelId":"$modelId","name":"SigLIP","modelVersion":1,"capabilities":["semanticImageSearch"],""" +
                    """"labelSetVersion":1,"runtime":"coreml","artifactVersion":1,"licence":"Apache-2.0",""" +
                    """"allowsCellular":$allowsCellular,"artifacts":[$records]}]"""
                ).encodeToByteArray(),
        )
    }

    private fun journalText(root: String): String? = IosFileDatabase(root)
        .readBytes("${BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR}/${BackgroundModelDownloader.JOURNAL_FILE}")?.get()?.decodeToString()

    /** What the session's delegate does when a transfer ends: the temporary file, handed over. */
    private fun deliver(root: String, ref: String, body: ByteArray, status: Int = 200): Boolean {
        val temporary: String = temp("CFNetworkDownload") + ".tmp"
        assertTrue(writeBytesToFile(temporary, body))
        return PhotosModelDownloads.acceptDelivered(ref, temporary, status, root = root, excludeFromBackup = { null })
    }

    private fun PhotosLocalRuntime.Assembly.store(): ModelStore = koin.get()

    // ─── What is bound, and when ────────────────────────────────────────────────────────────────

    @Test
    fun withTheFlagOffNoRuntimeIsContributedAndNoBackgroundDownloadExists() = runBlocking {
        val photos = photos(temp("photos-dl"), onDeviceAi = PhotosLocalRuntime.OnDeviceAiInputs(false, 26, eightGigabytes))
        val koin: Koin = photos.koin

        // ⚠⚠ The catalog offers this graph nothing: with no runtime contributed its filter is empty.
        assertNull(koin.getOrNull<Set<String>>(named("supportedModelRuntimes")), "a ~709 MB download is offered")
        assertNull(koin.getOrNull<ModelDownloaderFactory>())
        assertNull(koin.getOrNull<ModelStoreChanges>())
        assertNull(PhotosModelDownloads.downloader(koin))
        assertIs<DefaultModelDownloader>(koin.get<ModelDeliveryComponents>().downloader)
        // And a transfer that reports into it reaches nothing.
        PhotosLocalRuntime.install(photos)
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = true)
        assertFalse(
            PhotosLocalRuntime.onModelTransferCompleted("anything", 200, null, ModelTransferReport.Unquestioned),
            "a report was taken with nothing to take it",
        )
        PhotosLocalRuntime.onModelTransferProgress("anything", 1)
    }

    @Test
    fun onAnAdmittedPhoneExactlyTheCoreMlRuntimeIsContributedWithTheDownloaderThatMayCarryIt() = runBlocking {
        val photos = photos(temp("photos-dl"))
        val koin: Koin = photos.koin

        // ⚠⚠ Exactly this set: a build that also contributed `onnx` would see two catalog entries
        // under one model id and take the first (`models-pipeline.md` §6.1).
        assertEquals(setOf("coreml"), koin.get<Set<String>>(named("supportedModelRuntimes")))
        // ⚠⚠ And never the in-process fetch, which cannot be held to Wi-Fi.
        val delivery: ModelDeliveryComponents = koin.get()
        assertIs<BackgroundModelDownloader>(delivery.downloader)
        assertFalse(delivery.storeChanges === ModelStoreChanges.Direct, "a commit would not go through readiness")
        assertTrue(delivery.downloader === PhotosModelDownloads.downloader(koin))
        // ⚠⚠ With §14.17.5's budget: for the published entry, the download and the cache's own
        // checks summed (4 × 743 448 870 bytes) and the margin must be free before a byte is
        // fetched, for a first install and an update alike.
        assertEquals(3_242_230_936L, PhotosModelDownloads.downloader(koin)!!.additionalDiskNeed(743_448_870L, 743_448_870L))
    }

    // ─── A relaunch: the journal, the disk, the graph's own readiness ───────────────────────────

    @Test
    fun aRelaunchInstallsFromTheJournalThroughTheGraphsOwnStoreAndReadiness() = runBlocking {
        val root = temp("photos-dl")
        // An earlier process journalled the download and died; the session delivered all three.
        journal(root, generation)
        generation.forEach { (role, body) -> assertTrue(deliver(root, refOf(generation, role), body)) }
        assertEquals(3, PhotosModelDownloads.deliveredRefs(root)?.size)
        val session = Session()
        val photos = photos(root, overrides = over(session))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))

        // What `install` and every return to the foreground run.
        PhotosOnDeviceAi.recheck(photos.koin, launch = true)

        val installed = photos.store().installed().single()
        assertEquals(modelId, installed.modelId)
        val files: FileDatabase = photos.koin.get(named(INFERENCE_FILE_DATABASE))
        generation.forEach { (role, body) ->
            assertContentEquals(body, files.readBytes(installed.pathOf(role)!!)?.get(), role)
        }
        // Nothing fetched again, nothing left behind.
        assertTrue(session.started.isEmpty(), "${session.started}")
        assertEquals("[]", journalText(root))
        assertEquals(emptySet<String>(), PhotosModelDownloads.deliveredRefs(root))
        // ⚠ Installed, and not prepared: nothing has compiled the model, so nothing is served.
        assertEquals(AiReadinessState.Preparing, photos.koin.get<AiReadiness>().current())
        assertFalse(photos.koin.get<AiReadiness>().serves())
        // The index is the one a later process reads.
        assertEquals(listOf(modelId), photos(root, overrides = over(Session())).store().installed().map { it.modelId })
    }

    @Test
    fun aFileCorruptedOnTheWayIsDiscardedAtTheRelaunchAndNothingIsInstalled() = runBlocking {
        val root = temp("photos-dl")
        journal(root, generation)
        generation.forEach { (role, body) ->
            val delivered: ByteArray = if (role == ModelArtifactRole.TEXT) body.copyOf().also { it[123_456] = 1 } else body
            assertTrue(deliver(root, refOf(generation, role), delivered))
        }
        val photos = photos(root, overrides = over(Session()))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))

        PhotosOnDeviceAi.recheck(photos.koin, launch = true)

        assertTrue(photos.store().installed().isEmpty())
        assertEquals(emptySet<String>(), PhotosModelDownloads.deliveredRefs(root))
        assertEquals(AiReadinessState.NoModel, photos.koin.get<AiReadiness>().current())
        assertEquals("[]", journalText(root))
    }

    @Test
    fun aTransferTheSessionLostIsStartedAgainWithItsJournalledChoiceAndTheCdnsOwnUrl() = runBlocking {
        val root = temp("photos-dl")
        journal(root, generation, allowsCellular = false)
        // Vision arrived; the text tower is still with the session; the vocabulary's transfer is gone.
        assertTrue(deliver(root, refOf(generation, ModelArtifactRole.VISION), generation.getValue(ModelArtifactRole.VISION)))
        val session = Session(live = mutableSetOf(refOf(generation, ModelArtifactRole.TEXT)))
        val photos = photos(root, overrides = over(session))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))

        PhotosOnDeviceAi.recheck(photos.koin, launch = true)

        val again: ModelTransferRequest = session.started.single()
        assertEquals(refOf(generation, ModelArtifactRole.VOCABULARY), again.ref)
        assertEquals("https://models.service.tenshu.app/$modelId/1/coreml/${ModelArtifactRole.VOCABULARY}.bin", again.url)
        // ⚠⚠ D6: the choice the user made for this download, and it was Wi-Fi only.
        assertFalse(again.allowsCellular)
        assertTrue(photos.store().installed().isEmpty())
    }

    @Test
    fun theSessionSwiftInstallsIsWhatTheGraphDrivesAndWhatItReportsReachesTheInstalledRuntime() = runBlocking {
        val root = temp("photos-dl")
        journal(root, generation)
        val bridge = object : ModelTransferBridge {
            val started = ArrayList<Triple<String, String, Boolean>>()
            override fun start(ref: String, urlString: String, allowsCellular: Boolean) {
                started += Triple(ref, urlString, allowsCellular)
            }
            override fun liveTransferRefs(completion: (List<String>) -> Unit) = completion(emptyList())
            override fun cancel(ref: String) = Unit
        }
        PhotosLocalRuntime.installModelTransferBridge(bridge)
        // ⚠ No transport in the graph: it is the process's, the one Swift installed.
        val photos = photos(root)
        PhotosLocalRuntime.install(photos)

        eventually("the launch started the three transfers the session did not hold") { bridge.started.size == 3 }
        assertTrue(bridge.started.none { it.third }, "a Wi-Fi-only download was started with cellular allowed")

        val counted = object : ModelTransferReport {
            override fun stands(): Boolean = true

            override fun settled() {
                settled.incrementAndGet()
            }
        }
        // ⚠⚠ In the background nothing is settled, runtime or no runtime: a Photos that was only
        // suspended is woken for the session's events with its graph resident, and would hash
        // and commit on a few seconds of background time. The file is moved into place, the
        // report is not taken, and the caller frees the ref.
        val (firstRole, firstBody) = generation.entries.first()
        assertTrue(deliver(root, refOf(generation, firstRole), firstBody))
        assertFalse(
            PhotosLocalRuntime.onModelTransferCompleted(refOf(generation, firstRole), 200, null, counted),
            "a transfer's end was acted on while Photos was in the background",
        )
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = true)

        // The session delivers, and reports to the runtime that is installed, in the foreground.
        generation.forEach { (role, body) ->
            if (role != firstRole) assertTrue(deliver(root, refOf(generation, role), body))
            assertTrue(PhotosLocalRuntime.onModelTransferCompleted(refOf(generation, role), 200, null, counted))
        }
        eventually("the installed runtime verified and committed what was delivered") {
            runCatching { photos.store().installed().size }.getOrDefault(0) == 1
        }
        // ⚠ Every report that was taken is acknowledged once it has been acted on: until then
        // the session's ledger keeps the ended transfer as its ref's holder.
        eventually("each of the three reports was acknowledged") { settled.value == 3 }

        // ⚠⚠ And the commit is what the prepare step follows (§14.17), in this graph, through
        // the real cache: the downloader says the store changed, the preparer takes the step
        // lock and compiles. These archives are not Core ML packages, so the real cache refuses
        // the first tower, and the outcome says so for THIS install; nothing reads as ready.
        val preparer: ModelPreparer = photos.koin.get()
        eventually("the prepare step ran after the commit and reported") {
            preparer.lastReport.value?.outcome is PrepareOutcome.Failed
        }
        val report = preparer.lastReport.value!!
        assertEquals(ModelArtifactRole.VISION, (report.outcome as PrepareOutcome.Failed).role)
        assertEquals(photos.store().installed().single().artifacts.first { it.role == ModelArtifactRole.VISION }.sha256, report.key!!.towerDigests.first())
        assertFalse(
            platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath("$root/inference/coreml/${report.key!!.towerDigests.first().take(16)}"),
            "a tower that was not prepared left a directory",
        )
    }

    @Test
    fun aGraphThatWasTornDownInstallsNothingAndTheNextOneDoes() = runBlocking {
        val root = temp("photos-dl")
        journal(root, generation)
        val photos = photos(root, overrides = over(Session(live = generation.keys.mapTo(mutableSetOf()) { refOf(generation, it) })))
        PhotosLocalRuntime.install(photos)
        eventually("the launch check ran") { photos.koin.get<AiReadiness>().current() == AiReadinessState.NoModel && journalText(root) != "[]" }
        val downloader = PhotosModelDownloads.downloader(photos.koin)!!
        eventually("the launch check tracks the journalled download") { downloader.isDownloading(modelId) }
        // The runtime goes (a sign-out), and only then does the session deliver.
        PhotosLocalRuntime.deprovision()
        // ⚠⚠ Whoever watched or waited on the old graph's download is released by the teardown
        // itself: nothing else ever reaches a retired graph.
        assertFalse(downloader.isDownloading(modelId), "the teardown left the old graph's download \"running\"")
        generation.forEach { (role, body) -> assertTrue(deliver(root, refOf(generation, role), body)) }

        // ⚠ The old graph is still reachable, and is asked: it must neither stage nor commit.
        PhotosModelDownloads.resume(photos.koin)
        generation.keys.forEach { PhotosModelDownloads.downloader(photos.koin)!!.settleFinished(refOf(generation, it), 200, null) }

        assertEquals(3, PhotosModelDownloads.deliveredRefs(root)?.size)
        assertTrue(modelId in journalText(root).orEmpty())

        // ⚠ The weights are every realm's: the next runtime installs them from the same journal.
        val next = photos(root, overrides = over(Session()))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(next))
        PhotosOnDeviceAi.recheck(next.koin, launch = true)
        assertEquals(listOf(modelId), next.store().installed().map { it.modelId })
    }

    // ─── Readiness's writer 3, in the graph ─────────────────────────────────────────────────────

    /** What the prepare step leaves for the installed towers, written here by hand. */
    private fun PhotosLocalRuntime.Assembly.markPrepared(root: String) {
        val fingerprint = iosRuntimeFingerprint(store().installed())
        listOf("vision" to fingerprint.visionArchiveSha256!!, "text" to fingerprint.textArchiveSha256!!).forEach { (role, digest) ->
            manager.createDirectoryAtPath("$root/inference/coreml/${digest.take(16)}/$role.mlmodelc", true, null, null)
            IosFileDatabase(root).writeRawByteArray(
                "inference/coreml/${digest.take(16)}/ready",
                "digest=$digest\nosBuild=os\n".encodeToByteArray(),
            )
        }
    }

    private fun verdictText(root: String): String =
        IosFileDatabase(root).readBytes(AiReadiness.VERDICT_PATH)?.get()?.decodeToString().orEmpty()

    /** A prepared model with one failed probe on record: what writer 3, and only writer 3, forgets. */
    private suspend fun withAFailedProbeOnRecord(root: String, session: Session): PhotosLocalRuntime.Assembly {
        val photos = photos(root, overrides = over(session))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        val entry = ModelEntryData(
            modelId = modelId, name = "SigLIP", modelVersion = 1,
            capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH), labelSetVersion = 1,
            runtime = "coreml", artifactVersion = 1, artifacts = artifacts(generation), licence = "Apache-2.0",
        )
        assertNotNull(photos.store().add(entry, generation))
        photos.markPrepared(root)
        val readiness: AiReadiness = photos.koin.get()
        assertIs<ProbeRecorded.Counted>(readiness.recordProbe(ProbeResult.FAILED, iosPreflightKey(photos.store().installed()), 1L))
        assertTrue("\"failed\":1" in verdictText(root), verdictText(root))
        return photos
    }

    @Test
    fun aCommitFromTheJournalRunsInsideReadinessWriterThree() = runBlocking {
        val root = temp("photos-dl")
        val photos = withAFailedProbeOnRecord(root, Session())
        // The same archive is downloaded again: removed, journalled, delivered.
        photos.store().remove(modelId)
        journal(root, generation)
        generation.forEach { (role, body) -> assertTrue(deliver(root, refOf(generation, role), body)) }

        PhotosOnDeviceAi.recheck(photos.koin, launch = true)

        assertEquals(listOf(modelId), photos.store().installed().map { it.modelId })
        // ⚠ Writer 3's own mark: a commit that went through is a new install, and the failed
        // probes of the last one are forgotten. A commit beside readiness leaves them.
        assertTrue("\"failed\":0" in verdictText(root), verdictText(root))
    }

    @Test
    fun deletingTheModelRunsInsideReadinessWriterThree() = runBlocking {
        val root = temp("photos-dl")
        val photos = withAFailedProbeOnRecord(root, Session())

        val answer = photos.tenshuClient.inferenceService.deleteModel(modelId)

        assertIs<TResult.Success<*>>(answer, "deleteModel answered: $answer")
        assertTrue(photos.store().installed().isEmpty())
        assertEquals(AiReadinessState.NoModel, photos.koin.get<AiReadiness>().current())
        assertTrue("\"failed\":0" in verdictText(root), verdictText(root))
    }

    // ─── The delivered file ─────────────────────────────────────────────────────────────────────

    @Test
    fun onlyAWholeSuccessfulResponseIsTakenAndOnlyIntoADirectoryExcludedFromBackup() {
        val root = temp("photos-dl")
        val body = ByteArray(1000) { 3 }
        fun temporary(): String = (temp("CFNetworkDownload") + ".tmp").also { assertTrue(writeBytesToFile(it, body)) }

        // An error page is not a file.
        val refused = temporary()
        assertFalse(PhotosModelDownloads.acceptDelivered("m.text.abc", refused, 404, root = root, excludeFromBackup = { null }))
        assertTrue(manager.fileExistsAtPath(refused))
        // A name that is not a ref never becomes a path.
        assertFalse(PhotosModelDownloads.acceptDelivered("../../escaped", temporary(), 200, root = root, excludeFromBackup = { null }))
        assertFalse(PhotosModelDownloads.acceptDelivered("", temporary(), 200, root = root, excludeFromBackup = { null }))
        // ⚠⚠ A directory that cannot be excluded from backup holds no archive.
        assertFalse(PhotosModelDownloads.acceptDelivered("m.text.abc", temporary(), 200, root = root, excludeFromBackup = { "no" }))
        assertFalse(manager.fileExistsAtPath("$root/inference/downloads"))
        assertEquals(emptySet<String>(), PhotosModelDownloads.deliveredRefs(root))

        // The real exclusion, set and read back, and then the move.
        val taken = temporary()
        assertTrue(PhotosModelDownloads.acceptDelivered("m.text.abc", taken, 200, root = root))
        assertFalse(manager.fileExistsAtPath(taken), "copied, not moved: the archive is on the disk twice")
        assertContentEquals(body, IosFileDatabase(root).readBytes("inference/downloads/m.text.abc.delivered")?.get())
        assertNull(OwnedInferenceStores.excludeFromBackup(root))
        assertEquals(setOf("m.text.abc"), PhotosModelDownloads.deliveredRefs(root))

        // ⚠ Under the tree a lost backup exclusion purges, journal and all.
        IosFileDatabase(root).writeRawByteArray("inference/downloads/journal.json", "[]".encodeToByteArray())
        assertTrue(OwnedInferenceStores.purge(root).isEmpty())
        assertFalse(manager.fileExistsAtPath("$root/inference/downloads"))
    }

    // ─── A build that offers no model, met by what an enabled build left.

    @Test
    fun aBuildThatOffersNoModelDeletesWhatWasDeliveredAndLeavesTheJournalForABuildThatDoes() {
        val manager = platform.Foundation.NSFileManager.defaultManager
        // A phone that never had the feature: there is nothing to attach a session for.
        assertFalse(PhotosModelDownloads.leftBehind(temp("photos-never")))

        val root = temp("photos-left")
        journal(root, generation)
        val (role, body) = generation.entries.first()
        assertTrue(deliver(root, refOf(generation, role), body))
        val directory = "$root/${BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR}"
        val delivered = "$root/${BackgroundModelDownloader.deliveredPath(BackgroundModelDownloader.DEFAULT_DOWNLOADS_DIR, refOf(generation, role))}"
        assertTrue(manager.fileExistsAtPath(delivered))
        assertTrue(PhotosModelDownloads.leftBehind(root))

        // With no runtime and no graph: this is all a build with the feature off has.
        assertEquals(emptySet(), PhotosModelDownloads.discardDelivered(root))

        assertFalse(manager.fileExistsAtPath(delivered), "a delivered archive was left for a build that installs nothing")
        // ⚠ The journal stays: a later build that offers the model resumes from it.
        assertTrue(manager.fileExistsAtPath("$directory/${BackgroundModelDownloader.JOURNAL_FILE}"), "the journal was swept")
        assertTrue(PhotosModelDownloads.leftBehind(root))
    }

    // ─── What the Models screen reads (§14.18): one source, bound under its interface ──────────

    @Test
    fun theModelSourceIsBoundUnderItsInterfaceOnAnAdmittedPhoneAndNowhereElse() = runBlocking {
        val admitted: Koin = photos(temp("photos-dl")).koin
        // ⚠⚠ Resolved by the INTERFACE: bound under its concrete type it would be resolved by nothing.
        val source: OnDeviceModelSource = admitted.get()
        assertTrue(source === PhotosOnDeviceAi.modelSource(admitted))

        val flagOff: Koin = photos(temp("photos-dl"), onDeviceAi = PhotosLocalRuntime.OnDeviceAiInputs(false, 26, eightGigabytes)).koin
        assertNull(PhotosOnDeviceAi.modelSource(flagOff))
        // ⚠ And WHY a phone is not offered a model is kept, not printed and dropped.
        assertEquals(AiStaticGates.Decision.Admitted, PhotosOnDeviceAi.decision(true, 17, eightGigabytes))
        assertEquals(
            AiStaticGates.Decision.Refused(AiStaticGates.Reason.OS_BELOW_FLOOR),
            PhotosOnDeviceAi.decision(true, 16, eightGigabytes),
        )
        assertEquals(
            AiStaticGates.Decision.Refused(AiStaticGates.Reason.MEMORY_BELOW_THRESHOLD),
            PhotosOnDeviceAi.decision(true, 17, 4L shl 30),
        )
        assertEquals(
            AiStaticGates.Decision.Refused(AiStaticGates.Reason.BUILD_FLAG_OFF),
            PhotosOnDeviceAi.decision(false, 17, eightGigabytes),
        )
    }

    /**
     * ⚠⚠ The fixture's own guard, independent of the network: every admitted graph of this class
     * pins no catalog key, which is what makes *no catalog* answer without a request. With the
     * production key, `theSourceShows…` answered what the CDN did (`Accepted`, `StillStarting`).
     */
    @Test
    fun noGraphOfThisClassTrustsTheRealCatalog() {
        val admitted: Koin = photos(temp("photos-dl")).koin
        assertEquals("", admitted.get<String>(named("modelCatalogPublicKeyHex")))
        // ⚠ Under the case's own overrides too: a module the case adds keeps the blank key.
        val overSession: Koin = photos(temp("photos-dl"), overrides = over(Session())).koin
        assertEquals("", overSession.get<String>(named("modelCatalogPublicKeyHex")))
    }

    @Test
    fun theSourceShowsARelaunchedDownloadEndsItThroughTheSameJournalAndStopsWhenTheGraphIsRetired() = runBlocking {
        val root = temp("photos-dl")
        // An earlier process journalled the download, on cellular, and died; its transfers are live.
        journal(root, generation, allowsCellular = true)
        val session = Session(
            live = generation.keys.mapTo(mutableSetOf()) { role ->
                BackgroundModelDownloader.refOf(modelId, artifacts(generation).first { it.role == role }, allowsCellular = true)
            },
        )
        val photos = photos(root, overrides = over(session))
        val koin: Koin = photos.koin
        // ⚠⚠ The premise of the refusal below, asked of the graph and not of the network.
        assertNull(koin.get<ModelDeliveryComponents>().catalogSource.load(), "this graph must have no catalog")
        val source: OnDeviceModelSource = koin.get()
        val seen = ArrayList<ModelsAvailability>()
        val collecting = launch(Dispatchers.Default) { source.models.collect { seen += it } }
        fun row(): ModelSetup? = (seen.lastOrNull() as? ModelsAvailability.Listed)?.models?.singleOrNull()?.setup

        // The launch's look at the journal: the download is running, with the choice it was started under.
        PhotosModelDownloads.resume(koin)
        eventually("the journalled download was not shown: $seen") { row() is ModelSetup.Fetching }
        val running: ModelSetup.Download = assertIs<ModelSetup.Fetching>(row()).download
        assertTrue(running.allowsCellular)
        assertEquals(generation.values.sumOf { it.size.toLong() }, running.totalBytes)
        // ⚠ Never shown as absent on the way: nothing offered a download beside the one running.
        assertTrue(seen.none { it is ModelsAvailability.Listed && it.models.any { row -> row.setup is ModelSetup.Absent } }, "$seen")
        // ⚠⚠ A second request is answered by the DOWNLOADER, never from the view that names
        // the model: this graph has no catalog, so it cannot say what is asked for, refuses,
        // and neither starts anything nor touches the download that is running.
        val journalBefore: String? = journalText(root)
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NoCatalog), source.download(modelId, allowsCellular = false))
        assertEquals(journalBefore, journalText(root))
        assertEquals(emptyList(), session.started.toList())
        assertIs<ModelSetup.Fetching>(row())

        // ⚠⚠ The source's cancel and the operation's downloader are one journal.
        assertEquals(ModelActionResult.Accepted, source.cancel(modelId))
        assertEquals("[]", journalText(root))
        assertFalse(PhotosModelDownloads.downloader(koin)!!.isDownloading(modelId))
        eventually("the cancelled download is still shown: $seen") { row() !is ModelSetup.Fetching }

        // Torn down under the collector: it is told, and its flow ends.
        PhotosOnDeviceAi.retire(koin)
        eventually("a retired graph's source did not end: $seen") { collecting.isCompleted }
        assertEquals(ModelsAvailability.NotConnected, seen.last())
    }

    // ─── The Models repository and its view model, over the real graph (§14.18.14) ─────────────

    @Test
    fun theModelsRepositoryIsOneForTheGraphUnderItsInterfaceAndSaysWhyAPhoneIsOfferedNothing() = runBlocking {
        val admitted = photos(temp("photos-dl"))
        // ⚠⚠ Resolved by the INTERFACE, and the same one every time: two screens share its *removing*.
        val bound: ModelsRepository = admitted.koin.get()
        assertTrue(bound === admitted.modelsRepository())
        assertTrue(bound === admitted.modelsRepository())
        assertEquals(OnDeviceModelsCapabilities, bound.capabilities)

        fun refused(os: Int, memory: Long, flag: Boolean = true) =
            photos(temp("photos-dl"), onDeviceAi = PhotosLocalRuntime.OnDeviceAiInputs(flag, os, memory)).modelsRepository()
        val whys = mapOf(
            refused(26, eightGigabytes, flag = false) to ModelsAvailability.NotOffered.Why.NOT_IN_THIS_BUILD,
            refused(16, eightGigabytes) to ModelsAvailability.NotOffered.Why.OS_TOO_OLD,
            refused(17, 4L shl 30) to ModelsAvailability.NotOffered.Why.NOT_ENOUGH_MEMORY,
        )
        for ((repository, why) in whys) {
            assertEquals(ModelsAvailability.NotOffered(why), repository.models().first().availability)
            // ⚠ No switch on a phone it would change nothing on.
            assertEquals(emptyList(), repository.capabilities.features)
        }
        // With no runtime: a failure, never an empty list.
        val none = PhotosOnDeviceAi.modelsRepository(koin = null, buildFlag = true, osMajorVersion = 17, physicalMemoryBytes = eightGigabytes)
        assertEquals(listOf(ModelsAvailability.NotConnected), none.models().toList().map { it.availability })
    }

    /** The two switches as an operation reads them (`PhotosOnDeviceAi.searchConsented`'s read). */
    private fun proxyReadsConsent(koin: Koin): Boolean =
        koin.get<ClientRegistry>().getExecutionContext(koin.get<String>(named("platformPackageName")))
            .hasInferenceConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH, koin.get<SyncManager>().currentDeviceId)

    /** §1.8: where a backend is bound the search bar's consent line names Photos' own Settings. */
    @Test
    fun theSearchBarsConsentLineNamesPhotosOwnSettingsWhereABackendIsBound() {
        PhotosSurface.installOnDeviceAi { false }
        assertEquals(ModelsSwitchHome.THIS_APP, PhotosSurface.search("iPhone").state.value.modelsSwitch)
        // With none bound the proxy is never asked, so no such line can be reached.
        PhotosSurface.installNotConnected()
        assertIs<SemanticSearchStatus.NotBuiltHere>(PhotosSurface.search("iPhone").state.value.semanticStatus)
    }

    /**
     * M4's open claim, run: Photos' own client reads this device's id and the roster, and what
     * the screen writes for consent is what the proxy reads before it loads a model.
     */
    @Test
    fun photosOwnClientReadsTheDeviceIdAndTheRosterAndItsConsentIsTheOneTheProxyReads() = runBlocking {
        // ⚠ Was red ~4 in 20 at the post-write read, `realm=false, device=true`: the read joined a
        // `realmSettings` stream another collector held open, and `TList.flow` replayed its log one
        // emission per element — the first being the pre-write snapshot. A product defect, fixed in
        // `:core` (`StreamLateSubscriberTest.aLateSubscribersFirstEmissionIsTheBuffersState`).
        // ⚠ Every check below names itself, and a wait that runs out names its step instead of
        // failing as a bare `TimeoutCancellationException`: a red under load must say which line.
        val photos = photos(temp("photos-dl"))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos), "the inference stores' check passes in a fresh graph")
        val devices = ClientModelsDevices(photos.tenshuClient)
        val proxyDeviceId = photos.koin.get<SyncManager>().currentDeviceId
        assertEquals(proxyDeviceId, devices.deviceId(), "the client's device id is the proxy's")
        assertTrue(devices.deviceId().isNotBlank(), "the client's device id is not blank")
        // ⚠ The read ANSWERS: a list, or `null` for *could not be read*. What it must not do is hang.
        val roster = answered("the roster read") { devices.roster.first() }
        println("PhotosModelDownloadTest -> the roster in a Photos graph with no sync: $roster")

        val repository: ModelsRepository = photos.modelsRepository()
        val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
        assertEquals(
            listOf(CapabilityConsent(search)),
            answered("the consents before any write") { repository.consents().first() },
            "before any write both halves read as their defaults",
        )
        assertFalse(proxyReadsConsent(photos.koin), "the proxy reads no consent before any write (device $proxyDeviceId)")
        // §38.2's order: the realm, then this device.
        assertTrue(repository.setRealmConsent(search, true), "the realm's yes is written")
        assertFalse(proxyReadsConsent(photos.koin), "half a consent is none")
        assertTrue(repository.setDeviceConsent(search, true), "this device's yes is written")
        // Every emission is kept: a red here says whether a stale half was taken or none came.
        val seen = mutableListOf<List<CapabilityConsent>>()
        assertEquals(
            listOf(CapabilityConsent(search, realm = true, device = true)),
            answered("the consents after both writes") { repository.consents().onEach { seen += it }.first { it.single().device } },
            "after both writes the client reads both halves on; every emission seen: $seen",
        )
        // ⚠⚠ Under the id the proxy reads with: a row under any other id is a switch that moves and changes nothing.
        assertTrue(proxyReadsConsent(photos.koin), "the proxy reads both halves on, under device $proxyDeviceId")
        assertTrue(repository.setDeviceConsent(search, false), "this device's no is written")
        assertFalse(proxyReadsConsent(photos.koin), "the proxy reads this device's no at once")
    }

    /** What [read] answers within 30 s (`null` is an answer); a failure naming [what] when none comes. */
    private suspend fun <T> answered(what: String, read: suspend () -> T): T {
        var done = false
        var answer: T? = null
        withTimeoutOrNull(30_000) {
            answer = read()
            done = true
        }
        assertTrue(done, "$what gave no answer within 30 s")
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }

    @Test
    fun theViewModelFromTheSurfaceShowsTheGraphsModelAndBothDoorsReachOneJournalAndOneStore() = runBlocking {
        val root = temp("photos-dl")
        // An earlier process journalled the download and died; its transfers are live.
        journal(root, generation)
        val session = Session(live = generation.keys.mapTo(mutableSetOf()) { refOf(generation, it) })
        val photos = photos(root, overrides = over(session))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        PhotosSurface.installModels(photos.modelsRepository)
        val vm: ModelsViewModel = PhotosSurface.models().viewModel
        fun setup(): ModelSetup? = vm.uiState.value.models.singleOrNull()?.setup
        try {
            // The screen's own open asks for the check, which reads the journal: no tap, and the download is shown.
            eventually("the journalled download was not shown: ${vm.uiState.value}") { setup() is ModelSetup.Fetching }
            assertEquals(OnDeviceModelsCapabilities, vm.uiState.value.capabilities)

            // ⚠⚠ The source's door ends what the downloader journalled: one journal.
            vm.cancel(modelId)
            eventually("the cancelled download is still shown: ${vm.uiState.value}") { setup() !is ModelSetup.Fetching }
            assertEquals("[]", journalText(root))
            assertFalse(PhotosModelDownloads.downloader(photos.koin)!!.isDownloading(modelId))
            assertNull(vm.uiState.value.message)

            // The download is journalled again and delivered; the next check installs it. ⚠ No tap.
            journal(root, generation)
            generation.forEach { (role, body) -> assertTrue(deliver(root, refOf(generation, role), body)) }
            vm.retry()
            eventually("the installed model was not shown: ${vm.uiState.value}") { setup() is ModelSetup.Held }
            // ⚠ Installed is not usable: nothing was compiled (Photos is not in the foreground here).
            assertEquals(false, vm.uiState.value.anyModelUsable)
            assertNull(vm.uiState.value.featureOffer)

            // ⚠⚠ The operation's door: the removal the screen asks for is `DeleteModel`, through
            // readiness's writer 3, and the row goes because the SOURCE read the store again.
            vm.confirmDelete(modelId)
            vm.delete()
            eventually("the removed model is still shown: ${vm.uiState.value}") { setup() == null && vm.uiState.value.removing.isEmpty() }
            assertTrue(photos.store().installed().isEmpty())
            eventually("the removal was not said: ${vm.uiState.value}") { vm.uiState.value.message == ModelsMessage.RemovedSpacePending }

            // Torn down under the screen: not connected, and the next screen is the next install's.
            PhotosOnDeviceAi.retire(photos.koin)
            eventually("a retired graph was not said: ${vm.uiState.value}") { vm.uiState.value.availability == ModelsAvailability.NotConnected }
            PhotosSurface.installNotConnected()
            val after: ModelsViewModel = PhotosSurface.models().viewModel
            try {
                // ⚠ The build flag of the test host is off: with no runtime the screen says nothing is offered here.
                eventually("no runtime was not said: ${after.uiState.value}") { after.uiState.value.availability !is ModelsAvailability.Loading }
                assertTrue(after.uiState.value.availability is ModelsAvailability.NotOffered || after.uiState.value.availability == ModelsAvailability.NotConnected)
            } finally {
                after.viewModelScope.cancel()
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}

/**
 * Write [bytes] to [path] atomically, as CFNetwork leaves a finished download. The test's own copy:
 * the production helper is `internal` to Drive's framework module since `ios-per-app-frameworks.md`
 * P3, and Photos' downloads never needed it.
 */
private fun writeBytesToFile(path: String, bytes: ByteArray): Boolean {
    val data: NSData = if (bytes.isEmpty()) {
        NSData()
    } else {
        memScoped { NSData.create(bytes = allocArrayOf(bytes), length = bytes.size.convert()) }
    }
    return data.writeToFile(path, atomically = true)
}

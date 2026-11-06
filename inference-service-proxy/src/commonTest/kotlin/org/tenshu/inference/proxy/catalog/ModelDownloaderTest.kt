package org.tenshu.inference.proxy.catalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.util.Sha256Digest
import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The streamed, resumable, digest-gated model fetch (`inference-service.md` §19.2), over Ktor's
 * `MockEngine` and an in-memory store — no network, no filesystem, no key material.
 *
 * ⚠ These assert *how the bytes land*, not just that they do: every case below is one where a
 * download that "worked" would have installed something wrong.
 */
class ModelDownloaderTest {

    private val artifact = ByteArray(3 * 1024) { (it % 251).toByte() }

    @OptIn(ExperimentalStdlibApi::class)
    private fun sha256(bytes: ByteArray): String {
        val digest = Sha256Digest()
        digest.update(bytes, bytes.size)
        return digest.digest().toHexString()
    }

    private val vision = ModelArtifactRole.VISION

    private fun entry(sha: String = sha256(artifact)) = entryOf(
        ModelArtifactData(vision, "models/siglip.onnx", sha, artifact.size.toLong()),
    )

    private fun entryOf(vararg artifacts: ModelArtifactData) = ModelEntryData(
        modelId = "siglip",
        name = "SigLIP",
        modelVersion = 1,
        capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        labelSetVersion = 0,
        runtime = "onnx",
        artifactVersion = 1,
        artifacts = artifacts.toList(),
        licence = "Apache-2.0",
    )

    private fun catalogOf(entry: ModelEntryData?) = ModelCatalogSource {
        entry?.let { ModelCatalogData(schemaVersion = 1, buildDate = "2026-09-03", models = listOf(it)) }
    }

    private class Fixture(
        val store: InMemoryModelStore,
        val progress: ModelDownloadProgressHolder,
        val liveness: BackgroundLiveness,
        val downloader: DefaultModelDownloader,
        val requests: MutableList<String?>,
    )

    /**
     * ⚠⚠ **[scope] is a real thread pool by default, and that is a hazard, not a convenience.** The
     * downloader runs there while the test body runs on `runTest`'s virtual scheduler, so any
     * assertion reading state the download *produced* is racing it. Two cases in this file meet
     * that race, and they need opposite remedies: one is settled by *removing* the concurrency
     * (`aSecondDownloadOfTheSameModelJoinsTheRunningOne…` passes
     * `StandardTestDispatcher(testScheduler)`, because its claim is an ordering between two
     * test-owned coroutines), while `progressAccumulates…` keeps it and samples at a gated
     * happens-before instead (its own KDoc).
     *
     * ⚠ **Kept per-test rather than made the class default, deliberately.** Ktor's `MockEngine`
     * delivers the response body on its own dispatchers either way, so the test scheduler never
     * makes a download single-threaded — it only stops it yielding to a collector. A test that
     * observes a flow the *client* fills wants the real pool; only an ordering claim wants the
     * scheduler.
     */
    private fun TestScope.fixture(
        entry: ModelEntryData? = entry(),
        engine: MockEngine,
        requests: MutableList<String?> = mutableListOf(),
        store: InMemoryModelStore = InMemoryModelStore(::sha256),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): Fixture {
        val progress = ModelDownloadProgressHolder()
        val liveness = BackgroundLiveness()
        return Fixture(
            store = store,
            progress = progress,
            liveness = liveness,
            downloader = DefaultModelDownloader(
                httpClient = HttpClient(engine),
                baseUrl = "https://models.example",
                catalogSource = catalogOf(entry),
                store = store,
                scope = scope,
                liveness = liveness,
                progress = progress,
                // Small, so a 3 KiB artifact is genuinely streamed over several chunks.
                chunkBytes = 512,
            ),
            requests = requests,
        )
    }

    private fun serving(
        body: ByteArray,
        status: HttpStatusCode = HttpStatusCode.OK,
        requests: MutableList<String?>,
    ) = MockEngine { request ->
        requests += request.headers[HttpHeaders.Range]
        respond(
            body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
        )
    }

    @Test
    fun aCleanDownloadLandsTheArtifactAndItsIndexRecord() = runTest {
        val requests = mutableListOf<String?>()
        val f = fixture(engine = serving(artifact, requests = requests), requests = requests)

        val installed = f.downloader.download("siglip")

        assertEquals("siglip", installed.modelId)
        assertEquals(artifact.size.toLong(), installed.byteSize)
        assertContentEquals(artifact, f.store.bytesOf("siglip", vision))
        // No Range header on a fresh download.
        assertEquals(listOf<String?>(null), requests)
    }

    /**
     * ⚠⚠ The gate. Bytes that do not match the **signed catalog's** digest must not install — and
     * the staged copy must not survive, or every later attempt resumes onto the corruption and
     * fails the same gate forever.
     */
    @Test
    fun aDigestMismatchThrowsAndDiscardsTheStagedBytes() = runTest {
        val requests = mutableListOf<String?>()
        val badEntry = entry(sha = sha256(byteArrayOf(9, 9, 9)))
        val f = fixture(
            entry = badEntry,
            engine = serving(artifact, requests = requests),
            requests = requests,
        )

        assertFailsWith<ModelIntegrityException> { f.downloader.download("siglip") }

        assertNull(f.store.pathOf("siglip", vision))
        // ⚠⚠ Asked against the SAME digest the failed attempt staged under. Asking with any other
        // digest returns 0 whether or not the corrupt bytes were discarded — which is how this
        // assertion originally passed against a build that kept them (mutant survived).
        assertEquals(0L, f.store.beginStaging("siglip", vision, badEntry.artifacts.single().sha256))
    }

    /**
     * ⚠ The opposite ordering, and the reason the two cases are separate: a *transport* failure
     * keeps the staged bytes, which is what makes the next attempt a Range request for the tail
     * rather than a fresh ~300 MB download.
     */
    @Test
    fun aTransportFailureKeepsTheStagedBytesSoTheNextAttemptResumes() = runTest {
        val requests = mutableListOf<String?>()
        val store = InMemoryModelStore(::sha256)
        val failing = MockEngine { request ->
            requests += request.headers[HttpHeaders.Range]
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val f = fixture(engine = failing, requests = requests, store = store)
        // Pre-stage a partial attempt against this same content.
        store.beginStaging("siglip", vision, entry().artifacts.single().sha256)
        store.writeStaged("siglip", vision, 0, artifact.copyOfRange(0, 1024))

        assertFailsWith<ModelDownloadException> { f.downloader.download("siglip") }

        // The staged 1024 bytes survived, and are offered back for resumption.
        assertEquals(1024L, store.beginStaging("siglip", vision, entry().artifacts.single().sha256))
    }

    @Test
    fun aResumedDownloadAsksForTheTailAndAppendsIt() = runTest {
        val requests = mutableListOf<String?>()
        val store = InMemoryModelStore(::sha256)
        store.beginStaging("siglip", vision, entry().artifacts.single().sha256)
        store.writeStaged("siglip", vision, 0, artifact.copyOfRange(0, 1024))

        val engine = MockEngine { request ->
            requests += request.headers[HttpHeaders.Range]
            respond(
                artifact.copyOfRange(1024, artifact.size),
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
            )
        }
        val f = fixture(engine = engine, requests = requests, store = store)

        val installed = f.downloader.download("siglip")

        assertEquals(listOf<String?>("bytes=1024-"), requests)
        assertEquals(artifact.size.toLong(), installed.byteSize)
        // ⚠ The digest was rehydrated over the staged prefix, so the gate covered the WHOLE file.
        assertContentEquals(artifact, store.bytesOf("siglip", vision))
    }

    /**
     * ⚠⚠ A `Range` request answered **200** carries the whole artifact, not the tail. Appending it
     * to staged bytes silently produces a corrupt file that only the sha gate catches — after
     * paying for the entire download. Servers may ignore `Range` legitimately, so this must
     * restart cleanly rather than fail.
     */
    @Test
    fun aRangeRequestAnsweredWithTwoHundredRestartsInsteadOfAppending() = runTest {
        val requests = mutableListOf<String?>()
        val store = InMemoryModelStore(::sha256)
        store.beginStaging("siglip", vision, entry().artifacts.single().sha256)
        store.writeStaged("siglip", vision, 0, artifact.copyOfRange(0, 1024))

        // Ignores the Range header and serves the whole body with a plain 200.
        val f = fixture(engine = serving(artifact, requests = requests), requests = requests, store = store)

        val installed = f.downloader.download("siglip")

        assertEquals(listOf<String?>("bytes=1024-"), requests)
        assertEquals(artifact.size.toLong(), installed.byteSize)
        assertContentEquals(artifact, store.bytesOf("siglip", vision))
    }

    /**
     * ⚠⚠ The first snapshot a subscriber sees must already carry the staged bytes. A `0` here is a
     * progress bar that visibly snaps to empty and re-climbs ground the user already paid for —
     * and publishing 0 then correcting it is not equivalent, because the 0 goes out on the wire.
     */
    @Test
    fun aResumedDownloadsFirstProgressSnapshotIsTheStagedBytesNotZero() = runTest {
        val store = InMemoryModelStore(::sha256)
        store.beginStaging("siglip", vision, entry().artifacts.single().sha256)
        store.writeStaged("siglip", vision, 0, artifact.copyOfRange(0, 1024))

        val gate = CompletableDeferred<Unit>()
        // ⚠ Entering the engine means `progress.start` has already run — it is called before the
        // GET is prepared — so this is a real happens-before, not a sleep.
        val requestSeen = CompletableDeferred<Unit>()
        val engine = MockEngine {
            requestSeen.complete(Unit)
            gate.await()
            respond(
                artifact.copyOfRange(1024, artifact.size),
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
            )
        }
        val f = fixture(engine = engine, store = store)

        val job = async { f.downloader.download("siglip") }
        requestSeen.await()
        val firstSnapshot = f.progress.progress.value.getValue("siglip").writtenBytes
        gate.complete(Unit)
        job.await()

        assertEquals(1024L, firstSnapshot)
    }

    @Test
    fun aSecondDownloadOfTheSameModelJoinsTheRunningOneRatherThanFetchingTwice() = runTest {
        val requests = mutableListOf<String?>()
        val gate = CompletableDeferred<Unit>()
        val requestSeen = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            requests += request.headers[HttpHeaders.Range]
            requestSeen.complete(Unit)
            gate.await()
            respond(
                artifact,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
            )
        }
        // ⚠⚠ The test scheduler, NOT the fixture's default `Dispatchers.Default`, and it is what
        // makes this deterministic. `second` is only *created* before the gate opens — its body has
        // not run. On a real thread pool the first download can finish and its completion handler
        // can clear `inFlight` before `second` is ever dispatched, leaving it nothing to join and a
        // second fetch on the wire: `expected:<1> but was:<2>`. With one dispatcher the order is a
        // property of the code — `second` is queued before the gate's resumption, so it reaches
        // `startOrJoin` first. A non-suspending spin after the gate, which lets a real pool run and
        // the test dispatcher not, turns this red on `Dispatchers.Default` and leaves it green
        // here.
        val f = fixture(
            engine = engine,
            requests = requests,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )

        val first = async { f.downloader.download("siglip") }
        requestSeen.await()
        val second = async { f.downloader.download("siglip") }
        gate.complete(Unit)

        assertEquals(first.await(), second.await())
        // ⚠ One fetch, not two: the second call joined the in-flight job.
        assertEquals(1, requests.size)
    }

    @Test
    fun anUnknownModelIdIsRefusedBeforeAnyRequest() = runTest {
        val requests = mutableListOf<String?>()
        val f = fixture(engine = serving(artifact, requests = requests), requests = requests)

        assertFailsWith<UnknownModelException> { f.downloader.download("not-in-the-catalog") }

        assertTrue(requests.isEmpty())
    }

    /**
     * ⚠⚠ Today's real state: no release key, so no trusted catalog. The failure must say *that*
     * rather than "no such model", which would blame the wrong thing.
     */
    /**
     * ⚠ The commit at the end would be refused (`ios-on-device-inference.md` §13.7), so the refusal
     * has to come before the fetch: several hundred megabytes, possibly metered, for nothing.
     */
    @Test
    fun aStoreWhoseIndexCannotBeReadRefusesTheDownloadBeforeAnyRequest() = runTest {
        val requests = mutableListOf<String?>()
        val delegate = InMemorySecureStorageDelegate()
        // JSON that is not this build's record: the form of it every platform can meet.
        delegate.storeString("inference.models.index", """{"models":[]}""")
        val downloader = DefaultModelDownloader(
            httpClient = HttpClient(serving(artifact, requests = requests)),
            baseUrl = "https://models.example",
            catalogSource = catalogOf(entry()),
            store = DefaultModelStore(
                delegate,
                InMemoryFileDatabase(),
                modelsDir = "/models",
                digestOf = ::sha256,
                ownership = ModelStoreOwnership.SoleProcess,
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            liveness = BackgroundLiveness(),
            progress = ModelDownloadProgressHolder(),
        )

        assertFailsWith<ModelIndexUnreadableException> { downloader.download("siglip") }

        assertEquals(emptyList(), requests)
    }

    @Test
    fun noTrustedCatalogIsItsOwnFailureNotAnEmptyOne() = runTest {
        val requests = mutableListOf<String?>()
        val f = fixture(entry = null, engine = serving(artifact, requests = requests), requests = requests)

        assertFailsWith<ModelCatalogUnavailableException> { f.downloader.download("siglip") }

        assertTrue(requests.isEmpty())
    }

    // ─── A checkpoint is three files (§22.2). These are the cases that shape exists for.

    private val textBytes = ByteArray(2048) { (it % 97).toByte() }
    private val vocabBytes = ByteArray(512) { (it % 31).toByte() }

    private fun threeArtifactEntry(textSha: String = sha256(textBytes)) = entryOf(
        ModelArtifactData(ModelArtifactRole.VISION, "m/vision.onnx", sha256(artifact), artifact.size.toLong()),
        ModelArtifactData(ModelArtifactRole.TEXT, "m/text.onnx", textSha, textBytes.size.toLong()),
        ModelArtifactData(ModelArtifactRole.VOCABULARY, "m/vocab.bin", sha256(vocabBytes), vocabBytes.size.toLong()),
    )

    private fun servingByPath(vararg bodies: Pair<String, ByteArray>) = MockEngine { request ->
        val body = bodies.toMap()[request.url.encodedPath]
        if (body == null) {
            respondError(HttpStatusCode.NotFound)
        } else {
            respond(
                body,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
            )
        }
    }

    @Test
    fun everyArtifactOfACheckpointLandsUnderItsOwnRole() = runTest {
        val f = fixture(
            entry = threeArtifactEntry(),
            engine = servingByPath(
                "/m/vision.onnx" to artifact,
                "/m/text.onnx" to textBytes,
                "/m/vocab.bin" to vocabBytes,
            ),
        )

        val installed = f.downloader.download("siglip")

        assertContentEquals(artifact, f.store.bytesOf("siglip", ModelArtifactRole.VISION))
        assertContentEquals(textBytes, f.store.bytesOf("siglip", ModelArtifactRole.TEXT))
        assertContentEquals(vocabBytes, f.store.bytesOf("siglip", ModelArtifactRole.VOCABULARY))
        // ⚠ The advertised size is the WHOLE checkpoint — what the user is actually waiting for.
        assertEquals(
            (artifact.size + textBytes.size + vocabBytes.size).toLong(),
            installed.byteSize,
        )
    }

    /**
     * ⚠⚠ The property the one-entry-N-artifacts shape exists for. The vision tower downloads and
     * verifies fine; the text tower's bytes are wrong. Nothing may be installed — a device holding
     * a vision tower indexed against a text tower from a *different* export produces cosines that
     * are meaningless and computable, and nothing downstream could notice (§21.1).
     */
    @Test
    fun aFailureOnTheSecondArtifactInstallsNothingAtAll() = runTest {
        val f = fixture(
            // The catalog claims a digest the served text bytes will not match.
            entry = threeArtifactEntry(textSha = sha256(byteArrayOf(1, 2, 3))),
            engine = servingByPath(
                "/m/vision.onnx" to artifact,
                "/m/text.onnx" to textBytes,
                "/m/vocab.bin" to vocabBytes,
            ),
        )

        assertFailsWith<ModelIntegrityException> { f.downloader.download("siglip") }

        // No index record at all — not one naming only the artifact that succeeded.
        assertTrue(f.store.installed().isEmpty())
        assertNull(f.store.pathOf("siglip", ModelArtifactRole.VISION))
        assertNull(f.store.pathOf("siglip", ModelArtifactRole.TEXT))
    }

    /**
     * ⚠⚠ The case the all-or-nothing commit creates. Because artifacts commit **together**, an
     * interrupted download can leave one of them *fully* staged and still uninstalled. Resuming it
     * naively asks for `bytes=<size>-` — an empty tail — which a real server answers **416 Range
     * Not Satisfiable**, and a 416 is not a success, so the whole checkpoint could never finish.
     *
     * ⚠ A finished artifact must therefore be recognised before any request is made, not fetched
     * again: it is already on disk, and re-downloading 100 MB to discover that costs the user the
     * bytes twice.
     */
    @Test
    fun anArtifactThatIsAlreadyFullyStagedIsNotRequestedAgain() = runTest {
        val requests = mutableListOf<String>()
        val store = InMemoryModelStore(::sha256)
        val entry = threeArtifactEntry()
        // An earlier attempt finished the vision tower, then died before the checkpoint committed.
        store.beginStaging("siglip", ModelArtifactRole.VISION, sha256(artifact))
        store.writeStaged("siglip", ModelArtifactRole.VISION, 0, artifact)

        val engine = MockEngine { request ->
            requests += request.url.encodedPath
            val body = mapOf(
                "/m/vision.onnx" to artifact,
                "/m/text.onnx" to textBytes,
                "/m/vocab.bin" to vocabBytes,
            )[request.url.encodedPath]
            val range = request.headers[HttpHeaders.Range]
            // A real server's answer to a range starting at or past the end of the body.
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull() ?: 0L
            if (body == null) {
                respondError(HttpStatusCode.NotFound)
            } else if (start >= body.size) {
                respondError(HttpStatusCode.RequestedRangeNotSatisfiable)
            } else {
                respond(
                    body.copyOfRange(start.toInt(), body.size),
                    status = if (start > 0) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
                )
            }
        }
        val f = fixture(entry = entry, engine = engine, store = store)

        val installed = f.downloader.download("siglip")

        // ⚠ The finished artifact was never asked for again.
        assertEquals(listOf("/m/text.onnx", "/m/vocab.bin"), requests)
        assertContentEquals(artifact, store.bytesOf("siglip", ModelArtifactRole.VISION))
        assertContentEquals(textBytes, store.bytesOf("siglip", ModelArtifactRole.TEXT))
        assertEquals(entry.byteSize, installed.byteSize)
    }

    /**
     * ⚠ The other half: fully staged but **wrong**. Recognising "already done" by byte count alone
     * would install bytes that never passed the digest gate, which is the one thing this path must
     * never do. It has to start over instead.
     */
    @Test
    fun anArtifactStagedToFullLengthWithWrongBytesIsRefetched() = runTest {
        val requests = mutableListOf<String>()
        val store = InMemoryModelStore(::sha256)
        val entry = threeArtifactEntry()
        store.beginStaging("siglip", ModelArtifactRole.VISION, sha256(artifact))
        // Right length, wrong content.
        store.writeStaged("siglip", ModelArtifactRole.VISION, 0, ByteArray(artifact.size) { 0 })

        val f = fixture(
            entry = entry,
            engine = MockEngine { request ->
                requests += request.url.encodedPath
                respond(
                    mapOf(
                        "/m/vision.onnx" to artifact,
                        "/m/text.onnx" to textBytes,
                        "/m/vocab.bin" to vocabBytes,
                    ).getValue(request.url.encodedPath),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
                )
            },
            store = store,
        )

        f.downloader.download("siglip")

        assertEquals(listOf("/m/vision.onnx", "/m/text.onnx", "/m/vocab.bin"), requests)
        assertContentEquals(artifact, store.bytesOf("siglip", ModelArtifactRole.VISION))
    }

    /**
     * ⚠ Progress spans the checkpoint, not each file: a user installing SigLIP is waiting for one
     * ~383 MB job, and three bars that each run 0→100 describe work nobody asked about.
     *
     * ⚠⚠ **This is asserted at gated happens-before points, NOT by collecting the flow.** Sampling
     * `progress` from a concurrent collector is flaky: `progress` is a conflating `StateFlow` whose
     * producer runs on `Dispatchers.Default`, so *any* claim about what a collector observed is a
     * claim about scheduling. Even a bound as weak as "> the largest single artifact" fails,
     * because conflation can drop **everything** after a given point and `finish()` then removes
     * the entry. Weakening a threshold cannot fix a sampling race — only not sampling can.
     *
     * ⭐ So the engine gates each artifact's GET. Entering the handler for artifact N is a real
     * happens-before: [ModelDownloader] loops `for (artifact in entry.artifacts)` **sequentially**,
     * so artifacts 1..N-1 are fully fetched and published before N's request is prepared. The
     * holder is then read **synchronously** — `progress.value`, not a collected emission — which
     * makes the observation a fact rather than a timing. The same pattern the resume and
     * join-in-flight cases in this file use, and `inference-service.md` §29.4.
     */
    @Test
    fun progressAccumulatesAcrossArtifactsRatherThanRestartingPerFile() = runTest {
        // ⚠⚠ One gate PER artifact, not one shared release. A single gate looks like it works and
        // does not: once completed for the first sample, every later request passes straight
        // through, so the download races on while the test reads and sees
        // `expected:<3072> but was:<3584>` — 3072 plus one 512-byte chunk of the NEXT artifact.
        // Each request must still be BLOCKED at the moment its sample is taken.
        val requested = mapOf(
            "/m/vision.onnx" to CompletableDeferred<Unit>(),
            "/m/text.onnx" to CompletableDeferred<Unit>(),
            "/m/vocab.bin" to CompletableDeferred<Unit>(),
        )
        val gates = requested.keys.associateWith { CompletableDeferred<Unit>() }
        val bodies = mapOf(
            "/m/vision.onnx" to artifact,
            "/m/text.onnx" to textBytes,
            "/m/vocab.bin" to vocabBytes,
        )
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requested.getValue(path).complete(Unit)
            gates.getValue(path).await()
            respond(
                bodies.getValue(path),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
            )
        }
        val f = fixture(entry = threeArtifactEntry(), engine = engine)

        val job = async { f.downloader.download("siglip") }

        /** Sample the holder while [path]'s request is held, so nothing can advance underneath. */
        suspend fun sampleWhileBlockedOn(path: String): Long {
            requested.getValue(path).await()
            return f.progress.progress.value.getValue("siglip").writtenBytes
        }

        val beforeAnyBytes = sampleWhileBlockedOn("/m/vision.onnx")
        gates.getValue("/m/vision.onnx").complete(Unit)

        val afterVision = sampleWhileBlockedOn("/m/text.onnx")
        gates.getValue("/m/text.onnx").complete(Unit)

        val afterText = sampleWhileBlockedOn("/m/vocab.bin")
        gates.getValue("/m/vocab.bin").complete(Unit)

        job.await()

        val largestArtifact = maxOf(artifact.size, textBytes.size, vocabBytes.size).toLong()
        assertEquals(0L, beforeAnyBytes, "nothing staged, so the bar opens at zero")
        assertEquals(artifact.size.toLong(), afterVision, "the first artifact's bytes are published")
        assertEquals(
            (artifact.size + textBytes.size).toLong(),
            afterText,
            "the second artifact ACCUMULATES onto the first rather than restarting",
        )
        // ⚠⚠ The property a per-file bar could not have: the running total passes the biggest
        // single file. Now a consequence of exact values, not of what a sampler caught.
        assertTrue(afterText > largestArtifact, "$afterText exceeds the largest artifact ($largestArtifact)")
        assertTrue(afterVision < afterText, "monotonic: a per-file reset would go backwards")
    }

    /**
     * ⚠ The download must keep the proxy process alive while it runs — a 280 MB tower is minutes,
     * and Android's foreground service is torn down without this.
     */
    @Test
    fun theProcessIsHeldAliveForTheDownloadAndReleasedAfterwards() = runTest {
        val gate = CompletableDeferred<Unit>()
        val requestSeen = CompletableDeferred<Unit>()
        val engine = MockEngine {
            requestSeen.complete(Unit)
            gate.await()
            respond(
                artifact,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
            )
        }
        val f = fixture(engine = engine)

        val job = async { f.downloader.download("siglip") }
        requestSeen.await()
        assertEquals(1, f.liveness.active.value)

        gate.complete(Unit)
        job.await()
        // ⚠ The completion handler lowers liveness on the download's own scope, so it is not
        // ordered against `await()` — observe the flow rather than reading it once.
        assertEquals(0, f.liveness.active.first { it == 0 })
    }
}

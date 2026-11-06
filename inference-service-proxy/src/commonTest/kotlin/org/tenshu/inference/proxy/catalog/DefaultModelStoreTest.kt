package org.tenshu.inference.proxy.catalog

import org.tenshu.core.TByteArray
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.InferenceCapability
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The durable model store — durable because an in-memory store that forgets on restart is
 * discovered as *"my model downloads again every launch"*.
 *
 * Mirrors `DefaultRegionStoreTest`, including the properties that are about **ordering** rather
 * than outcome: those are the ones a crash can violate, and the only way to see them is to record
 * how the bytes landed rather than where they ended up.
 */
class DefaultModelStoreTest {

    private val delegate = MapSecureStorageDelegate()
    private val files = MapFileDatabase()
    private val bytes = "WEIGHTS".encodeToByteArray()

    private fun digest(value: ByteArray) = value.decodeToString().hashCode().toString()

    private fun store() = storeOver(delegate)

    private fun entry(
        modelId: String = "siglip",
        sha256: String = digest(bytes),
        modelVersion: Int = 1,
    ) = ModelEntryData(
        modelId = modelId,
        name = "SigLIP",
        modelVersion = modelVersion,
        capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        labelSetVersion = 1,
        runtime = "onnx",
        artifactVersion = 1,
        // ⚠ One artifact: these suites are about the index and the staging mechanics, not about
        // the multi-file checkpoint — `ModelDownloaderTest` owns that.
        artifacts = listOf(
            ModelArtifactData(role, "models/$modelId.onnx", sha256, bytes.size.toLong()),
        ),
        licence = "Apache-2.0",
    )

    private val role = ModelArtifactRole.VISION

    /** The content-addressed path where [DefaultModelStore] lands an artifact of [sha256]. */
    private fun installed(modelId: String, role: String, sha256: String) =
        "/models/$modelId.$role.${sha256.filter { it.isLetterOrDigit() }.lowercase().take(32)}.model"

    private val vPath get() = installed("siglip", role, digest(bytes))

    @Test
    fun theIndexSurvivesARestart() {
        val landed = assertNotNull(store().add(entry(), mapOf(role to bytes)))
        assertEquals(vPath, landed.pathOf(role))

        // ⚠ A *fresh* store over the same backing stores — the whole reason this class exists.
        assertEquals(listOf("siglip"), store().installed().map { it.modelId })
        assertEquals("Apache-2.0", store().installed().single().licence)
    }

    @Test
    fun bytesLandByRenameRatherThanOverAliveFile() {
        store().add(entry(), mapOf(role to bytes))

        // ⚠⚠ The property a crash can violate: writing a multi-hundred-MB artifact over the live
        // file leaves, for the whole write, something that is neither the old model nor the new
        // one — and a process death leaves a truncated file the index still vouches for.
        assertEquals(listOf("/models/siglip.vision.model.part"), files.writes.filter { it.endsWith(".part") })
        assertEquals("/models/siglip.vision.model.part" to vPath, files.moves.single())
    }

    @Test
    fun bytesThatDoNotMatchTheSignedDigestNeverLand() {
        assertNull(store().add(entry(sha256 = "wrong"), mapOf(role to bytes)))

        assertEquals(emptyList(), store().installed())
        assertEquals(emptyList(), files.paths().filter { it.endsWith(".model") })
    }

    @Test
    fun aStagedDownloadResumesOnlyWhenItIsTheSameContent() {
        val store = store()
        assertEquals(0L, store.beginStaging("siglip", role, expectedSha256 = "digest-v1"))
        store.writeStaged("siglip", role, 0, "PART".encodeToByteArray())

        // Same content → the already-staged bytes are reusable.
        assertEquals(4L, store.beginStaging("siglip", role, expectedSha256 = "digest-v1"))

        // ⚠⚠ Different content → discarded. A model id is stable across versions, so `.part` bytes
        // may belong to a DIFFERENT VERSION of the same model; appending would produce a file that
        // fails the digest gate only after the whole remainder had been downloaded — over a metered
        // connection, twice.
        assertEquals(0L, store.beginStaging("siglip", role, expectedSha256 = "digest-v2"))
    }

    @Test
    fun stagedBytesAreInvisibleUntilTheyAreCommitted() {
        val store = store()
        // ⚠ Begun for the digest that is then committed, as every download does: the commit
        // promotes nothing that was staged for another.
        store.beginStaging("siglip", role, digest(bytes))
        store.writeStaged("siglip", role, 0, bytes)

        // ⚠ A download that dies half-way leaves the previously-installed model intact and usable.
        assertEquals(emptyList(), store.installed())
        assertEquals(emptyList(), files.paths().filter { it.endsWith(".model") })

        val landed = store.commitStaged(entry(), stagedBytes = mapOf(role to bytes.size.toLong()))

        assertEquals(vPath, landed.pathOf(role))
        assertEquals(listOf("siglip"), store().installed().map { it.modelId })
        // The marker would only mislead the next attempt now that the staging file is gone.
        assertTrue(files.deletes.contains("/models/siglip.vision.model.part.sha"))
    }

    @Test
    fun abortingDiscardsTheStagedBytesAndTheMarker() {
        val store = store()
        store.beginStaging("siglip", role, "d")
        store.writeStaged("siglip", role, 0, bytes)

        store.abortStaging("siglip", role)

        assertEquals(0L, store.beginStaging("siglip", role, "d"), "nothing is left to resume")
    }

    @Test
    fun evictionClearsTheIndexBeforeTheBytes() {
        store().add(entry(), mapOf(role to bytes))
        files.clearLog()

        val evicting = store()
        evicting.remove("siglip")

        // ⚠⚠ The evicting process stops serving the model and deletes NOTHING it had installed:
        // its index write may not be on disk yet, and deleting ahead of it leaves an index entry
        // vouching for a file that is gone — a model that reports itself installed and then loads
        // nothing.
        assertEquals(emptyList(), evicting.installed())
        assertNull(evicting.pathOf("siglip", role))
        assertNotNull(files.bytesAt(vPath), "the bytes outlive the process that evicted them")

        // The next process read the cleared index back, so it reclaims them.
        assertEquals(emptyList(), store().installed())
        assertTrue(files.deletes.contains(vPath))
        assertNull(files.readBytes(vPath))
    }

    @Test
    fun anUpdateReplacesTheEntryRatherThanDuplicatingIt() {
        store().add(entry(modelVersion = 1), mapOf(role to bytes))
        val newer = "WEIGHTS-2".encodeToByteArray()

        store().add(entry(modelVersion = 2, sha256 = digest(newer)), mapOf(role to newer))

        assertEquals(1, store().installed().size)
        assertEquals(2, store().installed().single().modelVersion)
    }

    /**
     * ⚠ Bytes that are not JSON at all, and only those: no build could read them as records, so
     * they are no index and the next commit writes over them. JSON of another shape, and an index
     * that could not be read, are `DefaultModelStoreUnreadableIndexTest`'s.
     */
    @Test
    fun anIndexThatIsNotJsonIsEmptyRatherThanFatal() {
        delegate.storeString("inference.models.index", "{ not a list }")

        assertEquals(emptyList(), store().installed())
    }

    /**
     * ⚠⚠ [DefaultModelStore.pathOf] is on the **per-image** path — `OnnxInferenceBackend` resolves
     * the model for every `supports` check and every embed. Re-reading the delegate and re-parsing
     * the JSON per photograph is ten thousand reads over a real library. The index is memoised, and
     * a write refreshes it.
     */
    @Test
    fun theIndexIsReadOnceAndAWriteRefreshesIt() {
        val store = store()
        store.add(entry(), mapOf(role to bytes))
        delegate.stringReads.clear()

        repeat(50) { store.pathOf("siglip", role) }

        assertEquals(emptyList(), delegate.stringReads)

        // ⚠ …and the memo must not outlive a mutation, or an evicted model still reads as installed.
        store.remove("siglip")
        assertNull(store.pathOf("siglip", role))
        assertEquals(emptyList(), store.installed())
    }

    @Test
    fun theStoreCanSayWhereAModelIs() {
        store().add(entry(), mapOf(role to bytes))

        assertEquals(vPath, store().pathOf("siglip", role))
        assertNull(store().pathOf("absent", role))
        // ⚠ A role this checkpoint does not carry is absent, not an error.
        assertNull(store().pathOf("siglip", ModelArtifactRole.TEXT))
    }

    // ─── a commit never overwrites bytes a live install record points at ────────────────────────

    private val v1 = mapOf(role to "AAAA".encodeToByteArray(), ModelArtifactRole.TEXT to "BBBB".encodeToByteArray())

    /** ⚠⚠ Exactly v1's byte lengths — the case the locator's size check cannot see (§12.12). */
    private val v2 = mapOf(role to "CCCC".encodeToByteArray(), ModelArtifactRole.TEXT to "DDDD".encodeToByteArray())

    private fun pair(modelVersion: Int, content: Map<String, ByteArray>) = ModelEntryData(
        modelId = "siglip",
        name = "SigLIP",
        modelVersion = modelVersion,
        capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        labelSetVersion = 1,
        runtime = "onnx",
        artifactVersion = 1,
        artifacts = content.map { (r, b) -> ModelArtifactData(r, "models/siglip.$r", digest(b), b.size.toLong()) },
        licence = "Apache-2.0",
    )

    /**
     * What a backend would load right now: the record [store] serves, and the bytes at the paths it
     * names. ⚠ Through the SAME instance the commit runs on — its memoised index is what an in-process
     * locator reads during the window — and failing on a missing file as well as on wrong bytes.
     */
    private fun assertResolvesWhole(
        store: DefaultModelStore,
        label: String,
        newer: Map<String, ByteArray> = v2,
    ) {
        val record = store.installed().single()
        val expected = if (record.modelVersion == 1) v1 else newer
        record.artifacts.forEach { artifact ->
            val onDisk = files.bytesAt(artifact.localPath)
            assertNotNull(onDisk, "$label: v${record.modelVersion}'s ${artifact.role} names a missing file")
            assertContentEquals(
                expected.getValue(artifact.role),
                onDisk,
                "$label: v${record.modelVersion}'s record names other bytes for ${artifact.role}",
            )
        }
    }

    /**
     * ⚠⚠ `commitStaged` must never rename v2 over v1's paths: with the index rewritten last, the
     * record would say v1 for the whole window while the paths held v2 — and with equal lengths,
     * `faceStoreLocator`'s size check passes (`photos-people-and-pets.md` §12.12). Observed after
     * EVERY filesystem and index mutation of the update, not just at the end.
     */
    @Test
    fun anUpdateWithEqualByteLengthsIsNeverVisibleUnderTheOldRecordNorAsAMix() {
        val store = store()
        assertNotNull(store.add(pair(1, v1), v1))
        var observations = 0
        val observe = { observations++; assertResolvesWhole(store, "mutation $observations") }
        files.afterMutation = observe
        delegate.afterMutation = observe

        assertNotNull(store.add(pair(2, v2), v2))

        assertTrue(observations > 4, "the window was actually observed")
        assertEquals(2, store.installed().single().modelVersion)
        assertResolvesWhole(store, "after the commit")
    }

    @Test
    fun aCompletedUpdateResolvesToTheNewFilesAtNewPathsAndTheNextProcessReclaimsTheOld() {
        store().add(pair(1, v1), v1)
        val updating = store()
        val before = updating.installed().single().artifacts.map { it.localPath }

        updating.add(pair(2, v2), v2)

        assertEquals(2, updating.installed().single().modelVersion)
        // ⚠⚠ Not by the process that replaced them — see the write-behind tests below.
        before.forEach { assertNotNull(files.bytesAt(it), "$it was deleted by the process that replaced it") }

        val after = store().installed().single()
        assertEquals(2, after.modelVersion)
        after.artifacts.forEach { artifact ->
            // ⚠ A NEW path, not just new bytes: `OnnxInferenceBackend` keys its session by path
            // alone, so an update at the same path would keep answering with the old checkpoint.
            assertTrue(artifact.localPath !in before, "${artifact.role} reused a path")
            assertContentEquals(v2.getValue(artifact.role), files.bytesAt(artifact.localPath))
        }
        before.forEach { assertNull(files.bytesAt(it), "$it is unreferenced and must be reclaimed") }
    }

    /**
     * ⚠⚠ Installs made before content addressing hold SigLIP under a flat path. The record stores
     * its path, so it keeps resolving with no move and no re-download; the first update then
     * replaces it.
     */
    @Test
    fun aPreF2vInstallKeepsResolvingUntilAnUpdateReplacesIt() {
        val flat = v1.keys.associateWith { "/models/siglip.$it.model" }
        v1.forEach { (r, b) -> files.writeRawByteArray(flat.getValue(r), b) }
        val legacy = pair(1, v1).installedRecord { r, size -> flat.getValue(r) to size }
        delegate.storeString(
            "inference.models.index",
            Json.encodeToString(ListSerializer(InstalledModelData.serializer()), listOf(legacy)),
        )

        val store = store()
        v1.keys.forEach { assertEquals(flat.getValue(it), store.pathOf("siglip", it)) }
        assertResolvesWhole(store, "legacy install")
        // ⚠ The first read runs a sweep; it must not touch what a live record names.
        flat.values.forEach { assertNotNull(files.bytesAt(it)) }

        store.add(pair(2, v2), v2)

        // ⚠ `store()` is the next process: reclamation is its first read, never the update itself.
        assertResolvesWhole(store(), "after the update")
        flat.values.forEach { assertNull(files.bytesAt(it), "the flat file is reclaimed once unreferenced") }
    }

    /** ⚠ The sweep's live-record filter: same digest ⇒ same path, and the live file must survive. */
    @Test
    fun reinstallingIdenticalBytesKeepsTheLiveFile() {
        store().add(pair(1, v1), v1)
        val paths = store().installed().single().artifacts.map { it.localPath }

        store().add(pair(1, v1), v1)

        assertEquals(paths, store().installed().single().artifacts.map { it.localPath })
        assertResolvesWhole(store(), "after the re-install")
    }

    /**
     * ⚠⚠ A process death at ANY point of an update — every write, rename, delete and index write —
     * leaves, after a restart, one whole install (v1 or v2, never a mix) and no `.model` file that
     * no record names. Old generations go only once nothing live points at them.
     */
    @Test
    fun aCrashAnywhereInAnUpdateLeavesOneWholeInstallAndNoOrphans() {
        var crashAt = 0
        while (true) {
            files.reset(); delegate.reset()
            store().add(pair(1, v1), v1)
            var ops = 0
            val tick = { if (ops++ == crashAt) throw Crash() }
            files.beforeMutation = tick
            delegate.beforeMutation = tick

            val crashed = try { store().add(pair(2, v2), v2); false } catch (_: Crash) { true }
            files.beforeMutation = null
            delegate.beforeMutation = null

            val restarted = store()
            assertResolvesWhole(restarted, "crash at op $crashAt")
            val live = restarted.installed().single().artifacts.map { it.localPath }.toSet()
            assertEquals(
                emptyList(),
                files.paths().filter { it.endsWith(".model") && it !in live },
                "crash at op $crashAt left an orphan",
            )
            if (!crashed) break
            crashAt++
        }
        assertTrue(crashAt > 4, "every step was crashed through")
    }

    // ─── the index beside the bytes (`ios-on-device-inference.md` §3.1) ─────────────────────────

    private fun storeBesideTheBytes() = DefaultModelStore(
        FileModelIndexStorage(files, "/models"),
        files,
        modelsDir = "/models",
        digestOf = ::digest,
        ownership = ModelStoreOwnership.SoleProcess,
    )

    @Test
    fun anIndexBesideTheBytesIsLostWithThem() {
        assertNotNull(storeBesideTheBytes().add(entry(), mapOf(role to bytes)))
        assertEquals(listOf("siglip"), storeBesideTheBytes().installed().map { it.modelId })
        assertNull(delegate.getString("inference.models.index"), "nothing of it is in the delegate")

        // The container goes, as on an uninstall. The delegate — a Keychain — would have stayed.
        files.reset()

        assertTrue(storeBesideTheBytes().installed().isEmpty())
        assertNull(storeBesideTheBytes().pathOf("siglip", role))
    }

    /** The defect the port exists for, stated on the default storage so the contrast is in the suite. */
    @Test
    fun anIndexInTheDelegateOutlivesTheBytesAndStillReportsTheModel() {
        assertNotNull(store().add(entry(), mapOf(role to bytes)))

        files.reset()

        assertEquals(vPath, store().pathOf("siglip", role), "a path with nothing at it")
        assertNull(files.bytesAt(vPath))
    }

    /** ⚠ The barrier is the store's, not the storage's: a file is no more durable on return than a delegate. */
    @Test
    fun anIndexBesideTheBytesStillLeavesReclamationToTheNextProcess() {
        storeBesideTheBytes().add(pair(1, v1), v1)
        val updating = storeBesideTheBytes()
        val before = updating.installed().single().artifacts.map { it.localPath }

        updating.add(pair(2, v2), v2)

        before.forEach { assertNotNull(files.bytesAt(it), "$it was deleted by the process that replaced it") }
        assertEquals(2, storeBesideTheBytes().installed().single().modelVersion)
        before.forEach { assertNull(files.bytesAt(it), "$it is unreferenced and must be reclaimed") }
    }

    @Test
    fun anIndexKeyThatIsNotAFileNameIsRefused() {
        val storage = FileModelIndexStorage(files, "/models")
        assertTrue(runCatching { storage.storeString("../escape", "x") }.isFailure)
        assertTrue(runCatching { storage.getString("a/b") }.isFailure)
    }

    // ─── the index write is not durable when it returns ──────────────────────────────────────────

    private fun storeOver(storage: SecureStorageDelegate) =
        DefaultModelStore(storage, files, modelsDir = "/models", digestOf = ::digest, ownership = ModelStoreOwnership.SoleProcess)

    /**
     * ⚠⚠ An update over a delegate that writes behind, killed with every possible amount of that
     * backlog on disk. Whatever the restarted store's index names must be there, whole.
     *
     * The hazard: a commit that swept the old generation right after its index write would put the
     * deletes on disk at once and the index write not, so a death in between restarts on the v1
     * record over v1 files that are gone.
     * [aCrashAnywhereInAnUpdateLeavesOneWholeInstallAndNoOrphans] cannot see this — its delegate is
     * durable on return, so it only ever crashes *between* calls.
     */
    @Test
    fun anUpdateKilledBeforeItsIndexWriteReachedDiskStillResolvesWhatTheIndexNames() {
        var kept = 0
        while (true) {
            files.reset()
            val storage = WriteBehindStorage()
            storeOver(storage).add(pair(1, v1), v1)
            storage.flush()

            storeOver(storage).add(pair(2, v2), v2)
            val backlog = storage.unwritten
            assertTrue(backlog >= 2, "the update left its declaration and its index write unwritten")
            storage.crash(keep = kept)

            val restarted = storeOver(storage)
            assertResolvesWhole(restarted, "killed with $kept of $backlog writes on disk")
            if (kept == backlog) {
                // Everything landed: this is the completed update, and the old generation goes now.
                val live = restarted.installed().single().artifacts.map { it.localPath }.toSet()
                assertEquals(2, restarted.installed().single().modelVersion)
                assertEquals(emptyList(), files.paths().filter { it.endsWith(".model") && it !in live })
                break
            }
            kept++
        }
        assertTrue(kept >= 2, "more than one backlog length was crashed through")
    }

    /** The same death during an eviction: still installed means still loadable, gone means reclaimed. */
    @Test
    fun anEvictionKilledBeforeItsIndexWriteReachedDiskLeavesTheModelLoadableOrGone() {
        var kept = 0
        while (true) {
            files.reset()
            val storage = WriteBehindStorage()
            storeOver(storage).add(pair(1, v1), v1)
            storage.flush()

            storeOver(storage).remove("siglip")
            val backlog = storage.unwritten
            storage.crash(keep = kept)

            val restarted = storeOver(storage)
            if (restarted.installed().isEmpty()) {
                assertEquals(emptyList(), files.paths().filter { it.endsWith(".model") })
            } else {
                assertResolvesWhole(restarted, "killed with $kept of $backlog writes on disk")
            }
            if (kept == backlog) {
                assertEquals(emptyList(), restarted.installed(), "a fully written eviction is an eviction")
                break
            }
            kept++
        }
        assertTrue(kept >= 2, "more than one backlog length was crashed through")
    }

    // ─── a promotion must not target a file that is already there ──────────────────────────────

    /** ⚠ v1's vision artifact, unchanged, beside a new text tower: the update that keeps one digest. */
    private val v2KeepingVision = mapOf(role to v1.getValue(role), ModelArtifactRole.TEXT to "DDDD".encodeToByteArray())

    private val garbageKey = "inference.models.index.garbage"

    private fun declared(storage: SecureStorageDelegate): List<String> =
        storage.getString(garbageKey)?.let { Json.decodeFromString(ListSerializer(String.serializer()), it) }.orEmpty()

    /**
     * ⚠⚠ The hazard: the kept artifact's destination IS the path the live v1 record names, and
     * `moveFile` deletes a destination before it renames. A death in that gap would leave the
     * durable v1 index naming a file that is gone. [MapFileDatabase.moveDiesOverExisting] is that
     * death.
     */
    @Test
    fun anUpdateThatKeepsOneArtifactNeverMovesOntoTheFileALiveRecordNames() {
        store().add(pair(1, v1), v1)
        val keptPath = store().pathOf("siglip", role)
        files.clearLog()
        files.moveDiesOverExisting = true

        val updated = try { store().add(pair(2, v2KeepingVision), v2KeepingVision) } catch (_: Crash) { null }

        // Whatever happened, the next process's index names only files that are there.
        assertResolvesWhole(store(), "after the update", newer = v2KeepingVision)
        assertNotNull(updated, "the update died moving onto an existing destination")
        assertEquals(keptPath, updated.pathOf(role), "same digest, same path")
        assertTrue(files.moves.none { it.second == keptPath }, "the kept artifact was moved onto: ${files.moves}")
        // The staged copy of the kept artifact is discarded, not left for the next attempt to resume.
        assertNull(files.bytesAt("/models/siglip.vision.model.part"))
        assertEquals(2, store().installed().single().modelVersion)
    }

    /** The same rule for a re-install: every destination is resident, so nothing moves at all. */
    @Test
    fun reinstallingIdenticalBytesMovesNothing() {
        store().add(pair(1, v1), v1)
        files.clearLog()
        files.moveDiesOverExisting = true

        assertNotNull(store().add(pair(1, v1), v1))

        assertEquals(emptyList(), files.moves)
        assertResolvesWhole(store(), "after the re-install")
    }

    /**
     * ⚠⚠ A rollback in one process, over a delegate that writes behind: v1 is on disk, v2's index
     * write is only in memory, and v1 comes back. v1's paths are not what the in-memory record
     * names, but they ARE what the durable one does — so "is it live?" cannot be the test for
     * whether a destination may be moved onto. Whole-and-resident is.
     */
    @Test
    fun aRollbackNeverMovesOntoFilesTheDurableIndexStillNames() {
        val storage = WriteBehindStorage()
        storeOver(storage).add(pair(1, v1), v1)
        storage.flush()
        val running = storeOver(storage)
        running.add(pair(2, v2), v2)
        files.clearLog()
        files.moveDiesOverExisting = true

        val rolledBack = try { running.add(pair(1, v1), v1) } catch (_: Crash) { null }
        storage.crash(keep = 0)

        assertResolvesWhole(storeOver(storage), "killed with the rollback unwritten")
        assertNotNull(rolledBack, "the rollback died moving onto an existing destination")
        assertEquals(emptyList(), files.moves)
    }

    /**
     * A retry after a commit that renamed one artifact and died with nothing on disk: the renamed
     * file is resident, named by no record and declared nowhere. The retry adopts it.
     */
    @Test
    fun aRetryAdoptsWhatADeadCommitAlreadyRenamed() {
        val storage = WriteBehindStorage()
        storeOver(storage).add(pair(1, v1), v1)
        storage.flush()
        files.clearLog()
        // Dies at the first file mutation after the first rename has landed.
        files.beforeMutation = { if (files.moves.size == 1) throw Crash() }
        assertTrue(try { storeOver(storage).add(pair(2, v2), v2); false } catch (_: Crash) { true })
        files.beforeMutation = null
        storage.crash(keep = 0)
        val orphan = files.moves.single().second
        assertNotNull(files.bytesAt(orphan), "the dead commit's renamed artifact is resident")
        files.clearLog()
        files.moveDiesOverExisting = true

        val retried = storeOver(storage)
        assertEquals(1, retried.installed().single().modelVersion)
        assertNotNull(retried.add(pair(2, v2), v2), "the retry died moving onto the resident file")

        assertTrue(files.moves.none { it.second == orphan }, "the resident file was moved onto")
        assertResolvesWhole(retried, "after the retry")
        storage.flush()
        val restarted = storeOver(storage)
        assertResolvesWhole(restarted, "after the next start")
        val live = restarted.installed().single().artifacts.map { it.localPath }.toSet()
        assertEquals(emptyList(), files.paths().filter { it.endsWith(".model") && it !in live })
    }

    /**
     * ⚠⚠ Adoption is not "a file is there". `moveFile` falls back to a copy when a rename fails,
     * and a death part-way leaves a SHORT file at the final path. Adopting that would install it
     * for good; a resident file of the wrong length is replaced instead.
     */
    @Test
    fun aTruncatedResidentFileIsReplacedNotAdopted() {
        val destination = installed("siglip", role, digest(v2.getValue(role)))
        files.writeRawByteArray(destination, "CC".encodeToByteArray())
        files.clearLog()

        val landed = assertNotNull(store().add(pair(2, v2), v2))

        assertEquals(destination, landed.pathOf(role))
        assertContentEquals(v2.getValue(role), files.bytesAt(destination))
        assertTrue(files.moves.any { it.second == destination }, "the short file was adopted")
    }

    /**
     * ⚠⚠ A commit declares what it creates and what it replaces, never what it adopts: a file that
     * was there before the commit and that the new record names is garbage under no outcome.
     */
    @Test
    fun aCommitNeverDeclaresAPathItAdopts() {
        store().add(pair(1, v1), v1)
        val before = store().installed().single()
        delegate.remove(garbageKey)

        val after = assertNotNull(store().add(pair(2, v2KeepingVision), v2KeepingVision))

        assertEquals(
            setOf(after.pathOf(ModelArtifactRole.TEXT), before.pathOf(ModelArtifactRole.TEXT)),
            declared(delegate).toSet(),
            "declared: the text tower it created and the one it replaced — not the kept vision file",
        )
    }

    private class Crash : RuntimeException()

    /**
     * A delegate whose writes are **not durable when they return**: each is visible to this process
     * at once and reaches disk later, in order. [crash] is a process death with only the first
     * `keep` of the unwritten ones on disk; what the next reader sees is what was on disk.
     *
     * ⚠⚠ This models `AndroidSecureStorageDelegate.storeString`/`remove`, which end in
     * `SharedPreferences.Editor.apply()` (androidx's `edit {}` defaults to `commit = false`), for
     * keys that share one preferences file. **That behaviour was read from the code, not observed
     * on a device.** It models nothing else: files are as durable as [MapFileDatabase] makes them,
     * which is at once — what a kernel gives a process that dies.
     */
    private class WriteBehindStorage : SecureStorageDelegate {
        private var onDisk = mapOf<String, String>()
        private val inMemory = mutableMapOf<String, String>()
        private val backlog = mutableListOf<Map<String, String>>()

        /** How many writes have returned without reaching disk. */
        val unwritten: Int get() = backlog.size

        fun flush() { onDisk = inMemory.toMap(); backlog.clear() }

        fun crash(keep: Int) {
            if (keep > 0) onDisk = backlog[keep - 1]
            backlog.clear()
            inMemory.clear()
            inMemory.putAll(onDisk)
        }

        private fun write(block: () -> Unit) { block(); backlog += inMemory.toMap() }

        override fun storeSecret(key: String, value: String) = error("the model store keeps no secrets")
        override fun getSecret(key: String): String? = error("the model store keeps no secrets")
        override fun storeString(key: String, value: String) = write { inMemory[key] = value }
        override fun getString(key: String): String? = inMemory[key]
        override fun remove(key: String) = write { inMemory.remove(key) }
    }

    private class MapSecureStorageDelegate : SecureStorageDelegate {
        private val map = mutableMapOf<String, String>()
        /** ⚠ Counted, because "how often is this read" is itself under test. */
        val stringReads = mutableListOf<String>()
        var beforeMutation: (() -> Unit)? = null
        var afterMutation: (() -> Unit)? = null
        fun reset() { map.clear(); stringReads.clear() }
        private fun mutate(block: () -> Unit) { beforeMutation?.invoke(); block(); afterMutation?.invoke() }
        override fun storeSecret(key: String, value: String) { map[key] = value }
        override fun getSecret(key: String): String? = map[key]
        override fun storeString(key: String, value: String) = mutate { map[key] = value }
        override fun getString(key: String): String? { stringReads += key; return map[key] }
        override fun remove(key: String) = mutate { map.remove(key) }
    }

    /**
     * Records writes / moves / deletes so a test can assert *how* bytes landed. ⚠ [moveFile] is a
     * true key-move, mirroring `AndroidFileDatabaseImpl`'s `File.renameTo`; the interface's
     * copy+delete default would defeat the very property under test.
     */
    private class MapFileDatabase : FileDatabase {
        private val files = mutableMapOf<String, ByteArray>()
        val writes = mutableListOf<String>()
        val moves = mutableListOf<Pair<String, String>>()
        val deletes = mutableListOf<String>()

        /**
         * ⚠ Hooks around every mutation: a crash injected *before* one leaves it unapplied, as a
         * process death would, and an observer *after* one sees exactly what a concurrent reader would.
         */
        var beforeMutation: (() -> Unit)? = null
        var afterMutation: (() -> Unit)? = null

        /**
         * ⚠⚠ The gap in every real `moveFile` — JVM, Android and iOS all delete an existing
         * destination and THEN rename. When set, a move onto an existing path deletes it and dies
         * there, as a process killed in that gap would. Read from those implementations' code.
         */
        var moveDiesOverExisting = false

        fun clearLog() { writes.clear(); moves.clear(); deletes.clear() }
        fun reset() { files.clear(); clearLog(); moveDiesOverExisting = false }
        fun paths(): List<String> = files.keys.toList()
        fun bytesAt(path: String): ByteArray? = files[path]

        private fun mutate(block: () -> Unit) { beforeMutation?.invoke(); block(); afterMutation?.invoke() }

        override fun writeRawByteArray(filePath: String, bytes: ByteArray) = mutate {
            writes += filePath
            files[filePath] = bytes
        }

        override fun writeByteArray(filePath: String, bytes: TByteArray) = mutate {
            writes += filePath
            files[filePath] = bytes.get()
        }

        override fun readBytes(filePath: String): TByteArray? = files[filePath]?.let { TByteArrayDto(it) }

        override fun moveFile(fromPath: String, toPath: String) = mutate {
            moves += fromPath to toPath
            if (moveDiesOverExisting && toPath in files && fromPath in files) {
                files.remove(toPath)
                throw Crash()
            }
            files.remove(fromPath)?.let { files[toPath] = it }
        }

        override fun deleteFile(filePath: String) = mutate {
            deletes += filePath
            files.remove(filePath)
        }
    }
}

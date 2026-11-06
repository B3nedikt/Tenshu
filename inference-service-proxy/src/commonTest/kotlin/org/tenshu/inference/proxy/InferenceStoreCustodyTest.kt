package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TByteArray
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.catalog.DefaultModelStore
import org.tenshu.inference.proxy.catalog.FileModelIndexStorage
import org.tenshu.inference.proxy.catalog.ModelArtifactData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStoreOwnership
import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The realm stamp and the runtime fingerprint (`ios-on-device-inference.md` §3.1, §3.2), over the
 * real log stores.
 *
 * ⚠ Each `custody(...)` is a new process over the same disk: the stamp is read from the file, and
 * a fresh one starts unusable.
 */
class InferenceStoreCustodyTest {

    private val model = ModelProvenance("siglip", 1)
    private val disk = InMemoryFileDatabase()
    private val steps = mutableListOf<String>()
    private var realm: InferenceRealm? = InferenceRealm("owner-a")

    private val embeddingsPath = "/inference/embeddings.log"
    private val facesPath = "/inference/faces.log"

    private fun embeddings(files: FileDatabase = disk): EmbeddingStore = DefaultEmbeddingStore(files, embeddingsPath)
    private fun faces(files: FileDatabase = disk): FaceTemplateStore = DefaultFaceTemplateStore(files, facesPath)

    private fun custody(
        files: FileDatabase = disk,
        embeddings: EmbeddingStore = embeddings(),
        faces: FaceTemplateStore = faces(),
        runtimeServes: (InferenceRealm) -> Boolean = { true },
        currentFingerprint: (() -> RuntimeFingerprint)? = null,
    ): InferenceStoreCustody {
        lateinit var custody: InferenceStoreCustody
        custody = InferenceStoreCustody(
            files = files,
            embeddings = Recording("vectors", embeddings),
            faces = RecordingFaces(faces),
            cancelPass = {
                steps += "cancel"
                usableWhileClearing += custody.usable()
            },
            currentRealm = { realm },
            runtimeServes = runtimeServes,
            currentFingerprint = currentFingerprint,
        )
        return custody
    }

    /** What `usable()` answered each time a pass was being stopped for a clear. */
    private val usableWhileClearing = mutableListOf<Boolean>()

    private inner class Recording(val name: String, val inner: EmbeddingStore) : EmbeddingStore by inner {
        override suspend fun clear() {
            steps += "clear $name"
            inner.clear()
        }
    }

    private inner class RecordingFaces(val inner: FaceTemplateStore) : FaceTemplateStore by inner {
        override suspend fun clear() {
            steps += "clear faces"
            inner.clear()
        }
    }

    private class FailingClear(inner: EmbeddingStore) : EmbeddingStore by inner {
        override suspend fun clear(): Unit = error("the disk refused")
    }

    private class FailingFaceClear(inner: FaceTemplateStore) : FaceTemplateStore by inner {
        override suspend fun clear(): Unit = error("the disk refused")
    }

    /** Refuses every write to [refused], as a full or read-only volume does. */
    private class RefusingWrites(val inner: FileDatabase, val refused: String) : FileDatabase by inner {
        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            if (filePath == refused) error("cannot write $filePath")
            inner.writeRawByteArray(filePath, bytes)
        }

        override fun writeByteArray(filePath: String, bytes: TByteArray) = writeRawByteArray(filePath, bytes.get())
    }

    private fun face() = StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), floatArrayOf(1f, 0f))

    /** A store stamped for `owner-a`, holding one vector and one template. */
    private suspend fun indexedForOwnerA() {
        val embeddings = embeddings()
        val faces = faces()
        assertTrue(custody(embeddings = embeddings, faces = faces).ensureCurrentRealm())
        assertTrue(embeddings.put("/a.jpg", floatArrayOf(1f, 0f), model))
        assertTrue(faces.put("/a.jpg", listOf(face()), model))
        steps.clear()
    }

    private fun stamp(): String? = disk.readBytes(InferenceStoreCustody.REALM_STAMP_PATH)?.get()?.decodeToString()

    // ─── the realm stamp ────────────────────────────────────────────────────────────────────────

    @Test
    fun nothingIsUsableBeforeACheckHasFinished() = runTest {
        indexedForOwnerA()
        assertFalse(custody().usable())
    }

    @Test
    fun aMatchingStampKeepsEverything() = runTest {
        indexedForOwnerA()
        val custody = custody()
        assertTrue(custody.ensureCurrentRealm())
        assertTrue(custody.usable())
        assertEquals(1, embeddings().size())
        assertEquals(1, faces().size())
        assertEquals(emptyList(), steps)
    }

    @Test
    fun aStampForAnotherRealmClearsVectorsAndTemplatesBeforeAnythingIsUsable() = runTest {
        indexedForOwnerA()
        realm = InferenceRealm("owner-b")
        val custody = custody()
        assertFalse(custody.usable())

        assertTrue(custody.ensureCurrentRealm())

        assertEquals(0, embeddings().size(), "the old realm's vectors are gone from the disk")
        assertEquals(0, faces().size(), "and its templates")
        assertTrue("owner-b" in stamp().orEmpty())
        assertTrue(custody.usable())
    }

    @Test
    fun aMismatchStopsThePassBeforeItClears() = runTest {
        indexedForOwnerA()
        realm = InferenceRealm("owner-b")
        custody().ensureCurrentRealm()
        assertEquals(listOf("cancel", "clear vectors", "clear faces"), steps)
    }

    @Test
    fun thePassItselfIsNeverCancelledFromItsOwnCheck() = runTest {
        indexedForOwnerA()
        realm = InferenceRealm("owner-b")
        assertTrue(custody().ensureCurrentRealm(fromPass = true))
        assertEquals(listOf("clear vectors", "clear faces"), steps)
    }

    @Test
    fun aStoreWithNoStampIsCleared() = runTest {
        // What a store written before the stamp existed looks like, and what `relinquish` leaves.
        assertTrue(embeddings().put("/a.jpg", floatArrayOf(1f, 0f), model))
        assertTrue(custody().ensureCurrentRealm())
        assertEquals(0, embeddings().size())
    }

    @Test
    fun aVectorClearThatThrowsLeavesTheStoresUnusableAndTheOldStamp() = runTest {
        indexedForOwnerA()
        realm = InferenceRealm("owner-b")
        val custody = custody(embeddings = FailingClear(embeddings()))

        assertFalse(custody.ensureCurrentRealm())

        assertFalse(custody.usable())
        assertIs<InferenceStoreState.ClearFailed>(custody.state())
        assertTrue("owner-a" in stamp().orEmpty(), "the stamp still names the realm the content is from")
        // The next process, with a disk that works, finishes the job.
        assertTrue(custody().ensureCurrentRealm())
        assertEquals(0, embeddings().size())
    }

    @Test
    fun aTemplateClearThatThrowsLeavesTheOldStampToo() = runTest {
        // ⚠ BOTH clears, then the stamp: a stamp written after the first would let the next check
        // pass over the old realm's templates.
        indexedForOwnerA()
        realm = InferenceRealm("owner-b")
        val custody = custody(faces = FailingFaceClear(faces()))

        assertFalse(custody.ensureCurrentRealm())

        assertFalse(custody.usable())
        assertTrue("owner-a" in stamp().orEmpty())
        assertEquals(1, faces().size())
    }

    @Test
    fun aStampThatCannotBeWrittenLeavesTheStoresUnusable() = runTest {
        indexedForOwnerA()
        realm = InferenceRealm("owner-b")
        val refusing = RefusingWrites(disk, InferenceStoreCustody.REALM_STAMP_PATH)
        val custody = custody(files = refusing)

        assertFalse(custody.ensureCurrentRealm())

        assertFalse(custody.usable())
        assertTrue("owner-a" in stamp().orEmpty())
    }

    @Test
    fun aRealmThatChangesWhileResidentIsCaughtByTheNextCheck() = runTest {
        indexedForOwnerA()
        val embeddings = embeddings()
        val custody = custody(embeddings = embeddings)
        assertTrue(custody.ensureCurrentRealm())
        assertEquals(1, embeddings.size())

        realm = InferenceRealm("owner-b")
        assertTrue(custody.open(fromPass = false))

        assertEquals(0, embeddings.size(), "the resident store's memory is cleared, not only its file")
        assertEquals(listOf("cancel", "clear vectors", "clear faces"), steps)
    }

    @Test
    fun withNoRealmNothingIsUsableAndNothingIsCleared() = runTest {
        indexedForOwnerA()
        realm = null
        val custody = custody()

        assertFalse(custody.ensureCurrentRealm())

        assertEquals(InferenceStoreState.NoRealm, custody.state())
        assertEquals(1, embeddings().size())
        assertEquals(listOf("cancel"), steps)
    }

    @Test
    fun aRuntimeAssembledForAnotherRealmIsUnusableAfterTheClear() = runTest {
        indexedForOwnerA()
        realm = InferenceRealm("owner-b")
        val custody = custody(runtimeServes = { it.ownerId == "owner-a" })

        assertFalse(custody.ensureCurrentRealm())

        assertEquals(InferenceStoreState.StaleRuntime, custody.state())
        assertEquals(0, embeddings().size(), "the device left owner-a, so its vectors go either way")
    }

    @Test
    fun theStoreEpochIsHalfOfTheStamp() = runTest {
        // Same owner, a store that was replaced.
        indexedForOwnerA()
        realm = InferenceRealm("owner-a", storeEpoch = "e1")
        assertTrue(custody().ensureCurrentRealm())
        assertEquals(0, embeddings().size())

        assertTrue(embeddings().put("/a.jpg", floatArrayOf(1f, 0f), model))
        assertTrue(custody().ensureCurrentRealm())
        assertEquals(1, embeddings().size(), "the same epoch keeps them")

        realm = InferenceRealm("owner-a", storeEpoch = "e2")
        assertTrue(custody().ensureCurrentRealm())
        assertEquals(0, embeddings().size())
    }

    @Test
    fun theModelStoreIsNeverCleared() = runTest {
        val bytes = "WEIGHTS".encodeToByteArray()
        fun digest(value: ByteArray) = value.decodeToString().hashCode().toString()
        fun models() = DefaultModelStore(
            FileModelIndexStorage(disk),
            disk,
            modelsDir = "inference/models",
            digestOf = ::digest,
            ownership = ModelStoreOwnership.SoleProcess,
        )
        val entry = ModelEntryData(
            modelId = "siglip",
            name = "SigLIP",
            modelVersion = 1,
            capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
            labelSetVersion = 1,
            runtime = "coreml",
            artifactVersion = 1,
            artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "models/siglip", digest(bytes), bytes.size.toLong())),
            licence = "Apache-2.0",
        )
        models().add(entry, mapOf(ModelArtifactRole.VISION to bytes))
        indexedForOwnerA()

        realm = InferenceRealm("owner-b")
        val custody = custody()
        assertTrue(custody.ensureCurrentRealm())
        custody.applyFingerprint(fingerprint(vision = "other"))
        custody.relinquish()

        val installed = models().installed().single()
        assertEquals(bytes.size.toLong(), disk.fileSize(installed.pathOf(ModelArtifactRole.VISION)!!))
    }

    /**
     * ⚠⚠ The fingerprint is made from the install record (`OwnedInferenceStores`), and *no model*
     * is a fingerprint too: one the vectors do not survive. An index that could not be read and
     * was answered as empty therefore cleared every vector. It throws now, which this custody
     * already reads as *unusable, nothing cleared*.
     */
    @Test
    fun anInstallRecordThatCannotBeReadClearsNoVector() = runTest {
        val bytes = "WEIGHTS".encodeToByteArray()
        fun digest(value: ByteArray) = value.decodeToString().hashCode().toString()
        val noBytesFor = mutableSetOf<String>()
        // `IosFileDatabase`'s answer for a file it cannot read: `null`, while a stat finds it.
        val files = object : FileDatabase by disk {
            override fun readBytes(filePath: String) = if (filePath in noBytesFor) null else disk.readBytes(filePath)
        }
        fun models() = DefaultModelStore(
            FileModelIndexStorage(files),
            files,
            modelsDir = "inference/models",
            digestOf = ::digest,
            ownership = ModelStoreOwnership.SoleProcess,
        )
        fun fromTheRecord(store: DefaultModelStore): () -> RuntimeFingerprint = {
            fingerprint(vision = store.installed().firstOrNull()?.artifacts?.firstOrNull()?.sha256)
        }
        val entry = ModelEntryData(
            modelId = "siglip",
            name = "SigLIP",
            modelVersion = 1,
            capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
            labelSetVersion = 1,
            runtime = "coreml",
            artifactVersion = 1,
            artifacts = listOf(ModelArtifactData(ModelArtifactRole.VISION, "models/siglip", digest(bytes), bytes.size.toLong())),
            licence = "Apache-2.0",
        )
        models().add(entry, mapOf(ModelArtifactRole.VISION to bytes))
        assertTrue(custody(currentFingerprint = fromTheRecord(models())).ensureCurrentRealm())
        assertTrue(embeddings().put("/a.jpg", floatArrayOf(1f, 0f), model))

        noBytesFor += "inference/models/inference.models.index.json"
        val custody = custody(currentFingerprint = fromTheRecord(models()))
        assertFalse(custody.ensureCurrentRealm())
        assertEquals(1, embeddings().size(), "cleared on an install record that was not read")

        noBytesFor.clear()
        assertTrue(custody.ensureCurrentRealm())
        assertEquals(1, embeddings().size())
    }

    // ─── the runtime fingerprint ────────────────────────────────────────────────────────────────

    private fun fingerprint(
        vision: String? = "v1",
        text: String? = "t1",
        computeUnits: String = "CPU_AND_NEURAL_ENGINE",
        osBuild: String = "24A434",
        preprocessing: Int = 1,
    ) = RuntimeFingerprint(vision, text, computeUnits, osBuild, preprocessing)

    /** A store whose vector was made under [fingerprint]`()`. */
    private suspend fun indexedUnderTheFingerprint(): InferenceStoreCustody {
        indexedForOwnerA()
        val custody = custody()
        assertTrue(custody.ensureCurrentRealm())
        // No sidecar yet: the first application clears, so the vector goes in after it.
        assertEquals(FingerprintOutcome.Applied(vectorsCleared = true), custody.applyFingerprint(fingerprint()))
        assertTrue(embeddings().put("/a.jpg", floatArrayOf(1f, 0f), model))
        assertTrue(faces().put("/a.jpg", listOf(face()), model))
        steps.clear()
        return custody()
    }

    private suspend fun assertClears(changed: RuntimeFingerprint) {
        val custody = indexedUnderTheFingerprint()
        assertEquals(FingerprintOutcome.Applied(vectorsCleared = true), custody.applyFingerprint(changed))
        assertEquals(0, embeddings().size())
        assertEquals(1, faces().size(), "a face template is another model's, and stays")
        assertEquals(listOf("cancel", "clear vectors"), steps)
        assertEquals(FingerprintOutcome.Unchanged, custody().applyFingerprint(changed))
    }

    @Test
    fun theSameFingerprintChangesNothing() = runTest {
        val custody = indexedUnderTheFingerprint()
        assertEquals(FingerprintOutcome.Unchanged, custody.applyFingerprint(fingerprint()))
        assertEquals(1, embeddings().size())
        assertEquals(emptyList(), steps)
    }

    @Test
    fun aChangedVisionArchiveClearsTheVectors() = runTest { assertClears(fingerprint(vision = "v2")) }

    @Test
    fun aChangedOsBuildClearsTheVectors() = runTest { assertClears(fingerprint(osBuild = "24B91")) }

    /**
     * §14.19.6: the screen says *cleared after an iOS update* only when the OS build is why. ⚠ Not
     * on the first application (no sidecar: nothing was there to update), not for a new model,
     * and not after a clear for another reason.
     */
    @Test
    fun aFirstFingerprintIsNoOsUpdate() = runTest {
        val fresh = custody()
        assertTrue(fresh.ensureCurrentRealm())
        assertEquals(FingerprintOutcome.Applied(vectorsCleared = true), fresh.applyFingerprint(fingerprint()))
        assertFalse(fresh.clearedByOsUpdate.value, "a first fingerprint is no update")
    }

    @Test
    fun aClearIsSaidToBeAnOsUpdatesOnlyWhenTheOsBuildIsWhatChanged() = runTest {
        val updated = indexedUnderTheFingerprint()
        assertFalse(updated.clearedByOsUpdate.value)
        assertEquals(FingerprintOutcome.Applied(vectorsCleared = true), updated.applyFingerprint(fingerprint(osBuild = "24B91")))
        assertTrue(updated.clearedByOsUpdate.value, "an OS update's clear was not said to be one")
        assertEquals(FingerprintOutcome.Unchanged, updated.applyFingerprint(fingerprint(osBuild = "24B91")))
        assertTrue(updated.clearedByOsUpdate.value, "a check that changed nothing forgot why the index is empty")
        updated.applyFingerprint(fingerprint(osBuild = "24B91", vision = "v2"))
        assertFalse(updated.clearedByOsUpdate.value, "a new model's clear was said to be an OS update's")

    }

    /** ⚠ A realm's clear empties the index for its own reason: the update is no longer why. */
    @Test
    fun aRealmsClearAfterAnOsUpdateIsNoLongerSaidToBeTheUpdates() = runTest {
        val updated = indexedUnderTheFingerprint()
        assertTrue(updated.ensureCurrentRealm())
        updated.applyFingerprint(fingerprint(osBuild = "24B91"))
        assertTrue(updated.clearedByOsUpdate.value)
        realm = InferenceRealm("owner-b")
        assertTrue(updated.ensureCurrentRealm())
        assertFalse(updated.clearedByOsUpdate.value, "a realm's clear was said to be an OS update's")
    }

    @Test
    fun aNewModelsClearIsNoOsUpdate() = runTest {
        val newModel = indexedUnderTheFingerprint()
        newModel.applyFingerprint(fingerprint(vision = "v2"))
        assertFalse(newModel.clearedByOsUpdate.value)
    }

    @Test
    fun aChangedPreprocessingRevisionClearsTheVectors() = runTest { assertClears(fingerprint(preprocessing = 2)) }

    @Test
    fun changedComputeUnitsClearTheVectors() = runTest { assertClears(fingerprint(computeUnits = "ALL")) }

    @Test
    fun aRemovedVisionArchiveClearsTheVectors() = runTest { assertClears(fingerprint(vision = null)) }

    @Test
    fun applyingAFingerprintLeavesAUsableCustodyUsableOnceItsClearIsDone() = runTest {
        val custody = indexedUnderTheFingerprint()
        assertTrue(custody.ensureCurrentRealm())

        assertEquals(FingerprintOutcome.Applied(vectorsCleared = true), custody.applyFingerprint(fingerprint(vision = "v2")))

        assertTrue(custody.usable(), "a finished clear left the stores closed until the next realm check")
    }

    @Test
    fun aChangedTextArchiveKeepsTheVectorsAndIsStillAChange() = runTest {
        val custody = indexedUnderTheFingerprint()
        assertEquals(FingerprintOutcome.Applied(vectorsCleared = false), custody.applyFingerprint(fingerprint(text = "t2")))
        assertEquals(1, embeddings().size())
        assertEquals(emptyList(), steps)
    }

    @Test
    fun vectorsWithNoSidecarAreCleared() = runTest {
        indexedForOwnerA()
        val custody = custody()
        assertEquals(FingerprintOutcome.Applied(vectorsCleared = true), custody.applyFingerprint(fingerprint()))
        assertEquals(0, embeddings().size())
    }

    @Test
    fun aFingerprintClearThatThrowsLeavesTheSidecarAndTheStoresUnusable() = runTest {
        indexedUnderTheFingerprint()
        val custody = custody(embeddings = FailingClear(embeddings()))
        assertTrue(custody.ensureCurrentRealm())

        assertIs<FingerprintOutcome.Failed>(custody.applyFingerprint(fingerprint(vision = "v2")))

        assertFalse(custody.usable())
        // ⚠ A realm check that passes afterwards must not make them usable again.
        assertFalse(custody.ensureCurrentRealm())
        // The sidecar still says v1, so the next process clears.
        assertEquals(FingerprintOutcome.Applied(vectorsCleared = true), custody().applyFingerprint(fingerprint(vision = "v2")))
    }

    // ─── the fingerprint inside the one check ────────────────────────────────────────────────

    private fun sidecar(): String? = disk.readBytes(InferenceStoreCustody.FINGERPRINT_PATH)?.get()?.decodeToString()

    /** One vector for `owner-a`, made under [fingerprint]`()` by a custody that owns its fingerprint. */
    private suspend fun indexedByACustodyWithAFingerprint() {
        val embeddings = embeddings()
        assertTrue(custody(embeddings = embeddings, currentFingerprint = { fingerprint() }).ensureCurrentRealm())
        assertTrue(embeddings.put("/a.jpg", floatArrayOf(1f, 0f), model))
        steps.clear()
        usableWhileClearing.clear()
    }

    @Test
    fun aQueryThatReachesTheGateFirstClearsVectorsAnotherArchiveMadeBeforeItOpens() = runTest {
        // The realm matches. No launch check has run in this process: the gate is the first caller.
        indexedByACustodyWithAFingerprint()
        val embeddings = embeddings()
        val custody = custody(embeddings = embeddings, currentFingerprint = { fingerprint(vision = "v2") })
        assertFalse(custody.usable())

        assertTrue(custody.open(fromPass = false))

        assertEquals(0, embeddings.size(), "the gate opened over vectors another archive made")
        assertEquals(listOf("cancel", "clear vectors"), steps)
        assertEquals(listOf(false), usableWhileClearing, "usable while the stale vectors were still there")
        assertTrue("v2" in sidecar().orEmpty())
        assertTrue(custody.usable())
    }

    @Test
    fun aPassThatReachesTheGateFirstClearsThemTooAndIsNotCancelled() = runTest {
        indexedByACustodyWithAFingerprint()
        val embeddings = embeddings()
        val custody = custody(embeddings = embeddings, currentFingerprint = { fingerprint(osBuild = "24B91") })

        assertTrue(custody.open(fromPass = true))

        assertEquals(0, embeddings.size())
        assertEquals(listOf("clear vectors"), steps)
    }

    @Test
    fun theGateClearsVectorsThatHaveNoSidecar() = runTest {
        indexedForOwnerA()
        val embeddings = embeddings()
        val custody = custody(embeddings = embeddings, currentFingerprint = { fingerprint() })

        assertTrue(custody.open(fromPass = false))

        assertEquals(0, embeddings.size())
        assertEquals(1, faces().size())
    }

    @Test
    fun aFingerprintThatChangesWhileResidentClosesTheStoresForAsLongAsTheClearTakes() = runTest {
        // An archive replaced in a running process: this custody was usable a moment ago.
        indexedByACustodyWithAFingerprint()
        var current = fingerprint()
        val embeddings = embeddings()
        val custody = custody(embeddings = embeddings, currentFingerprint = { current })
        assertTrue(custody.open(fromPass = false))
        assertTrue(custody.usable())

        current = fingerprint(vision = "v2")
        assertTrue(custody.open(fromPass = false))

        assertEquals(listOf(false), usableWhileClearing, "usable while the old archive's vectors were still held")
        assertEquals(0, embeddings.size())
        assertTrue(custody.usable())
    }

    @Test
    fun theGateKeepsVectorsTheCurrentFingerprintMade() = runTest {
        indexedByACustodyWithAFingerprint()
        val custody = custody(currentFingerprint = { fingerprint() })
        assertTrue(custody.open(fromPass = false))
        assertTrue(custody.open(fromPass = true))
        assertEquals(1, embeddings().size())
        assertEquals(emptyList(), steps)
    }

    @Test
    fun aFingerprintClearThatThrowsLeavesTheGateClosed() = runTest {
        indexedByACustodyWithAFingerprint()
        val custody = custody(
            embeddings = FailingClear(embeddings()),
            currentFingerprint = { fingerprint(vision = "v2") },
        )

        assertFalse(custody.open(fromPass = false))
        assertFalse(custody.open(fromPass = true), "a second caller got through")

        assertFalse(custody.usable())
        assertIs<InferenceStoreState.ClearFailed>(custody.state())
        assertTrue("v1" in sidecar().orEmpty(), "the sidecar moved over uncleared vectors")
    }

    @Test
    fun aFingerprintThatCannotBeReadLeavesTheGateClosed() = runTest {
        indexedByACustodyWithAFingerprint()
        val custody = custody(currentFingerprint = { error("the install index is unreadable") })

        assertFalse(custody.open(fromPass = false))

        assertFalse(custody.usable())
        assertEquals(1, embeddings().size(), "nothing is cleared on a fingerprint nobody could read")
    }

    @Test
    fun aRealmMismatchAndAFingerprintChangeAreOneCheck() = runTest {
        indexedByACustodyWithAFingerprint()
        realm = InferenceRealm("owner-b")
        val custody = custody(currentFingerprint = { fingerprint(vision = "v2") })

        assertTrue(custody.open(fromPass = false))

        assertTrue("owner-b" in stamp().orEmpty())
        assertTrue("v2" in sidecar().orEmpty())
        assertEquals(0, embeddings().size())
    }

    // ─── giving the stores up ───────────────────────────────────────────────────────────────────

    @Test
    fun relinquishingClearsBothStoresAndRemovesTheStamp() = runTest {
        indexedForOwnerA()
        val custody = custody()
        assertTrue(custody.ensureCurrentRealm())

        custody.relinquish()

        assertFalse(custody.usable())
        assertEquals(0, embeddings().size())
        assertEquals(0, faces().size())
        assertNull(stamp())
        assertEquals(listOf("cancel", "clear vectors", "clear faces"), steps)
    }

    @Test
    fun aRelinquishWhoseClearThrowsHasAlreadyRemovedTheStampAndStillClearsTheOtherStore() = runTest {
        indexedForOwnerA()
        val custody = custody(embeddings = FailingClear(embeddings()))

        assertTrue(runCatching { custody.relinquish() }.isFailure)

        assertNull(stamp(), "so the next check clears before anything reads")
        assertEquals(0, faces().size())
        assertTrue(custody().ensureCurrentRealm())
        assertEquals(0, embeddings().size())
    }

    @Test
    fun erasingAtRestNeedsNoStore() = runTest {
        indexedForOwnerA()
        InferenceStoreCustody.eraseAtRest(disk, listOf(embeddingsPath, facesPath))
        assertNull(stamp())
        assertEquals(0, embeddings().size())
        assertEquals(0, faces().size())
    }

    @Test
    fun erasingAtRestWritesNothingWhereNothingWas() = runTest {
        // ⚠ Counted, not looked for afterwards: a write that is then unlinked leaves no file, and
        // on a real disk it has still made the directory.
        val writes = mutableListOf<String>()
        val counting = object : FileDatabase by disk {
            override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
                writes += filePath
                disk.writeRawByteArray(filePath, bytes)
            }
        }
        InferenceStoreCustody.eraseAtRest(counting, listOf(embeddingsPath, facesPath))
        assertEquals(emptyList(), writes)
    }
}

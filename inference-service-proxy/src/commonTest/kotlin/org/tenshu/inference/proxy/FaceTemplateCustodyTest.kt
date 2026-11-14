package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.onnx.FaceModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The face pair's custody (`ios-face-grouping.md` §14.4a, F5): its own fingerprint file, the
 * templates the only thing it clears, and search's key refused.
 */
class FaceTemplateCustodyTest {

    private val disk = InMemoryFileDatabase()
    private val templates = InMemoryFaceTemplateStore()
    private var cancels = 0

    private fun custody() = FaceTemplateCustody(disk, templates, cancelPass = { cancels++ })

    private fun face(embedder: String = "embedder-a", osBuild: String = "os-1", revision: Int? = FaceModel.VISION_LANDMARKS_REVISION) =
        RuntimeFingerprint(embedder, null, computeUnits = "CPU_AND_NEURAL_ENGINE", osBuild = osBuild, preprocessingRevision = 1, detectorRevision = revision)

    private val search = RuntimeFingerprint("vision-a", "text-a", computeUnits = "CPU_AND_NEURAL_ENGINE", osBuild = "os-1", preprocessingRevision = 1)

    private suspend fun template(path: String = "/a.jpg") {
        assertTrue(templates.put(path, listOf(StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), floatArrayOf(1f, 0f))), ModelProvenance("auraface", 1, 100)))
    }

    private fun held(path: String): String? = disk.readBytes(path)?.get()?.decodeToString()

    @Test
    fun itKeepsItsFingerprintInTheFaceSlotsFileAndNeverInSearchs() {
        assertEquals(ReadinessSlot.FACES.fingerprintPath, custody().fingerprintPath)
        assertFailsWith<IllegalArgumentException> { FaceTemplateCustody(disk, templates, {}, InferenceStoreCustody.FINGERPRINT_PATH) }
    }

    @Test
    fun aFirstFingerprintClearsTemplatesOfAnUnknownMakingAndWritesOnlyTheFaceFile() = runTest {
        template()
        val custody = custody()

        val outcome = custody.applyFingerprint(face())

        assertEquals(FingerprintOutcome.Applied(vectorsCleared = true), outcome)
        assertEquals(0, templates.size())
        assertEquals(1, cancels, "a pass ran under the clear")
        assertTrue(held(ReadinessSlot.FACES.fingerprintPath)!!.contains("embedder-a"))
        assertNull(held(InferenceStoreCustody.FINGERPRINT_PATH), "the face custody wrote search's fingerprint")
        assertTrue(custody.usable())
    }

    @Test
    fun theSameFingerprintKeepsTheTemplates() = runTest {
        val custody = custody()
        custody.applyFingerprint(face())
        template()

        assertEquals(FingerprintOutcome.Unchanged, custody.applyFingerprint(face()))
        assertEquals(FingerprintOutcome.Unchanged, custody().applyFingerprint(face()), "a new process read the file otherwise")
        assertEquals(1, templates.size())
    }

    /** FD9: another embedder, OS build or Vision revision makes other templates. */
    @Test
    fun anotherEmbedderOsBuildOrVisionRevisionClearsThem() = runTest {
        val custody = custody()
        custody.applyFingerprint(face())
        for (next in listOf(face(embedder = "embedder-b"), face(embedder = "embedder-b", osBuild = "os-2"), face(embedder = "embedder-b", osBuild = "os-2", revision = 4))) {
            template()
            assertEquals(FingerprintOutcome.Applied(vectorsCleared = true), custody.applyFingerprint(next), "$next kept the templates")
            assertEquals(0, templates.size())
        }
    }

    /** ⚠⚠ §14.4a: search's key handed to the face custody is a wrong binding, refused, and nothing is served. */
    @Test
    fun searchsKeyIsRefusedAndTheTemplatesAreUnusableUntilAFaceKeyComes() = runTest {
        val custody = custody()
        custody.applyFingerprint(face())
        template()
        val before: String? = held(ReadinessSlot.FACES.fingerprintPath)

        assertIs<FingerprintOutcome.Failed>(custody.applyFingerprint(search))
        assertIs<FingerprintOutcome.Failed>(custody.applyFingerprint(face(revision = null)), "a key with no detector revision is search's shape")

        assertFalse(custody.usable())
        assertEquals(before, held(ReadinessSlot.FACES.fingerprintPath), "search's key was written over the face file")
        assertEquals(1, templates.size(), "a refused key cleared anything")
        // The right key again: the file already says it, so nothing is cleared, and it serves.
        assertEquals(FingerprintOutcome.Applied(vectorsCleared = false), custody.applyFingerprint(face()))
        assertEquals(1, templates.size())
        assertTrue(custody.usable())
    }

    @Test
    fun aClearThatThrowsLeavesTheOldFileAndNothingUsable() = runTest {
        val custody = FaceTemplateCustody(
            disk,
            object : FaceTemplateStore by templates {
                override suspend fun clear(): Unit = error("the disk refused")
            },
            cancelPass = {},
        )

        assertIs<FingerprintOutcome.Failed>(custody.applyFingerprint(face()))

        assertFalse(custody.usable())
        assertNull(held(ReadinessSlot.FACES.fingerprintPath), "the file moved over uncleared templates")
    }
}

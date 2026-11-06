package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.AiModelFacts
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.FingerprintOutcome
import org.tenshu.inference.proxy.ImageEmbedding
import org.tenshu.inference.proxy.PREFLIGHT_REVISION
import org.tenshu.inference.proxy.Preflight
import org.tenshu.inference.proxy.PreflightJudge
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.PreflightOutcome
import org.tenshu.inference.proxy.PreflightProbe
import org.tenshu.inference.proxy.PreflightProbes
import org.tenshu.inference.proxy.PreparedModel
import org.tenshu.inference.proxy.RuntimeFingerprint
import org.tenshu.inference.proxy.onnx.SiglipTextTokenizer
import org.tenshu.inference.proxy.onnx.UnigramVocabulary
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.inference.proxy.onnx.hostFileBytes
import org.tenshu.inference.proxy.onnx.realVocabularyBytes
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The backend over the **real** fp16 towers, on the simulator. Skips with a printed reason unless
 * it is handed a directory `scripts/inference/export-coreml-towers.py` wrote:
 *
 * ```
 * ./gradlew :inference-service-proxy:iosSimulatorArm64Test \
 *     -Dtenshu.inference.coremlExport=/path/to/export \
 *     -Dtenshu.inference.tokenizer=/path/to/export/tokenizer.bin
 * ```
 *
 * ⚠⚠ Never in a gate: the archives are ~740 MB, uncommitted and fetched by no build. ⚠ And the
 * simulator is not a phone: nothing here is a latency or a footprint for D12.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreMlRealArtifactTest {

    private val fixtures = CoreMlFixtures()
    private val model = VisionModel.PinnedSigLip

    @AfterTest
    fun cleanUp() = fixtures.cleanUp()

    private fun exportDirectory(): String? {
        val directory: String? = NSProcessInfo.processInfo.environment["TENSHU_COREML_EXPORT"] as? String
        if (directory == null) println("SKIPPED: no -Dtenshu.inference.coremlExport=<export directory>, see the KDoc")
        return directory
    }

    /** Prepares one real tower through the cache and returns its digest stand-in. */
    private fun prepare(cache: CoreMlModelCache, directory: String, spec: CoreMlTowerSpec): String {
        val name = "${spec.role}_fp16.mlpackage.tar"
        NSFileManager.defaultManager.createDirectoryAtPath("${fixtures.root}/archives", true, null, null)
        // ⚠ A clone on APFS: the 555 MB text archive costs no second copy.
        check(NSFileManager.defaultManager.copyItemAtPath("$directory/$name", "${fixtures.root}/archives/$name", null)) {
            "no $name in $directory"
        }
        val digest: String = CoreMlFixtures.digestFor(spec.role)
        runBlocking { cache.prepare(spec, "archives/$name", digest) }
        return digest
    }

    private fun cosine(a: ImageEmbedding, b: ImageEmbedding): Float =
        a.vector.indices.fold(0f) { sum, i -> sum + a.vector[i] * b.vector[i] }

    @Test
    fun bothRealTowersEmbedIntoOneSpace() {
        val directory: String = exportDirectory() ?: return
        val vocabulary: ByteArray = hostFileBytes("TENSHU_INFERENCE_TOKENIZER")
            ?: return println("SKIPPED: no -Dtenshu.inference.tokenizer=<tokenizer.bin>")
        val cache: CoreMlModelCache = fixtures.cache()
        val visionDigest: String = prepare(cache, directory, CoreMlTowerSpec.vision(model))
        val textDigest: String = prepare(cache, directory, CoreMlTowerSpec.text(model))
        val backend = CoreMlInferenceBackend(
            locate = CoreMlInferenceBackend.compiledTower(cache, CoreMlTowerSpec.VISION_ROLE) { visionDigest },
            locateText = CoreMlInferenceBackend.compiledTower(cache, CoreMlTowerSpec.TEXT_ROLE) { textDigest },
            locateVocabulary = { "tokenizer.bin" },
            readVocabulary = { vocabulary },
        )
        assertTrue(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))

        runBlocking {
            val red: ByteArray = testJpeg(480, 360) { _, _ -> 0xE01010 }
            val image: ImageEmbedding = assertNotNull(backend.embedImage(red), "no image embedding")
            assertEquals(model.dimensions, image.vector.size)
            assertEquals(model.provenance, image.producedBy)
            val length: Float = sqrt(image.vector.fold(0f) { sum, v -> sum + v * v })
            assertTrue(abs(length - 1f) < 1e-3f, "the image vector is not unit length: $length")
            assertEquals(image, backend.embedImage(red), "the same bytes embedded differently twice")

            val dog: ImageEmbedding = assertNotNull(backend.embedText("a photo of a dog"), "no text embedding")
            val puppy: ImageEmbedding = assertNotNull(backend.embedText("a photo of a puppy"))
            val code: ImageEmbedding = assertNotNull(backend.embedText("a screenshot of source code"))
            val near: Float = cosine(dog, puppy)
            val far: Float = cosine(dog, code)
            assertTrue(near - far > 0.05f, "dog~puppy ($near) should beat dog~code ($far) by a margin")

            // ⭐ Across the towers: the property search rests on, and the one a mismatched pair
            // or a wrong pad id breaks while every vector still looks ordinary.
            val redText: ImageEmbedding = assertNotNull(backend.embedText("a plain red image"))
            val blueText: ImageEmbedding = assertNotNull(backend.embedText("a plain blue image"))
            val matching: Float = cosine(image, redText)
            val other: Float = cosine(image, blueText)
            val unrelated: Float = cosine(image, dog)
            println("COREML-REAL image~'a plain red image' $matching, ~'a plain blue image' $other, ~'a photo of a dog' $unrelated")
            assertTrue(matching > other, "a red image is closer to 'blue' ($other) than to 'red' ($matching)")
            assertTrue(matching > unrelated, "a red image is closer to 'a dog' ($unrelated) than to 'red' ($matching)")

            backend.closeSessions()
            assertNotNull(backend.embedText("a photo of a dog"), "no text embedding after a close")
        }
    }

    /**
     * ⭐ The whole preflight (D12 ②), as a phone would run it, over the real towers: the probe this
     * build carries, the image drawn in Kotlin, the app's tokenizer over the published vocabulary,
     * sessions opened through [CoreMlPreflightTowers], and the judge's own lines.
     *
     * ⚠⚠ The simulator's Core ML, not a phone's Neural Engine: the margin printed here is a second
     * host reading beside the record's, and the timings and memory are a Mac's.
     * ⚠ The directory is taken to be the export the record names; its 740 MB are not hashed here.
     */
    @Test
    fun theProbeThisBuildCarriesPassesOnTheRealTowers() {
        val directory: String = exportDirectory() ?: return
        val vocabulary: ByteArray = hostFileBytes("TENSHU_INFERENCE_TOKENIZER")
            ?: return println("SKIPPED: no -Dtenshu.inference.tokenizer=<tokenizer.bin>")
        val cache: CoreMlModelCache = fixtures.cache()
        val visionDigest: String = prepare(cache, directory, CoreMlTowerSpec.vision(model))
        val textDigest: String = prepare(cache, directory, CoreMlTowerSpec.text(model))
        val prepared = PreparedModel(
            visionPath = assertNotNull(cache.compiledPath(CoreMlTowerSpec.VISION_ROLE, visionDigest)),
            textPath = assertNotNull(cache.compiledPath(CoreMlTowerSpec.TEXT_ROLE, textDigest)),
            vocabularyPath = "tokenizer.bin",
        )
        // The kept export of §9.6, by the id its record is filed under.
        val record: PreflightProbe = assertNotNull(PreflightProbes.byId("3fd1918106d4"), "the build carries no record for that export")
        val key = PreflightKey(
            runtime = RuntimeFingerprint(record.visionArchiveSha256, record.textArchiveSha256, CoreMlComputeUnits.SHIPPED.name, "simulator", IOS_PREPROCESSING_REVISION),
            vocabularySha256 = record.vocabularySha256,
            appBuild = "test",
            preflightRevision = PREFLIGHT_REVISION,
        )
        val readiness = AiReadiness(
            files = { fixtures.files },
            facts = { AiModelFacts(installed = true, prepared = prepared) },
            currentKey = { key },
            storesUsable = { true },
            applyFingerprint = { FingerprintOutcome.Unchanged },
            cancelPass = {},
            closeSessions = {},
        )
        val preflight = Preflight(readiness, CoreMlPreflightTowers(), { vocabulary }, IosPreflightDevice, beforeLoad = {})

        val outcome: PreflightOutcome = runBlocking { preflight.foregrounded(1) }

        val passed: PreflightOutcome.Passed = assertIs(outcome, "the shipped probe did not pass on the real towers: $outcome")
        val finding = PreflightJudge.judge(passed.measurement, record)
        println("PREFLIGHT-REAL $finding; the record's host margin is ${record.hostMargin}, required ${record.minMargin}; ${passed.measurement.summary()}")
        assertEquals(AiReadinessState.Ready, readiness.current())
        assertEquals(prepared, readiness.serving())
    }

    /**
     * The 250 100-entry vocabulary map under Kotlin/Native, as a first reading (§3.2).
     *
     * ⚠ The Kotlin heap after a collection, on the simulator: not `phys_footprint`, not a phone.
     */
    @OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    @Test
    fun theVocabularyMapsFootprintIsReported() {
        val bytes: ByteArray = realVocabularyBytes()
            ?: return println("SKIPPED: no -Dtenshu.inference.tokenizer=<tokenizer.bin>")

        fun heap(): Long {
            GC.collect()
            return GC.lastGCInfo?.memoryUsageAfter?.values?.sumOf { it.totalObjectsSizeBytes } ?: -1L
        }
        val before: Long = heap()
        val started = TimeSource.Monotonic.markNow()
        val vocabulary: UnigramVocabulary = assertNotNull(UnigramVocabulary.read(bytes))
        val parse = started.elapsedNow()
        val tokenizer = SiglipTextTokenizer(vocabulary)
        val after: Long = heap()
        val encodeStarted = TimeSource.Monotonic.markNow()
        repeat(100) { tokenizer.encode("a sunset over water with my dog in the snow") }
        val encode = encodeStarted.elapsedNow() / 100

        assertEquals(250_100, vocabulary.size)
        println(
            "VOCABULARY-FOOTPRINT file ${bytes.size} bytes, Kotlin heap +${(after - before) / 1024} KiB " +
                "(before ${before / 1024} KiB, after ${after / 1024} KiB), parse ${parse.inWholeMilliseconds} ms, " +
                "encode ${encode.inWholeMicroseconds} µs a query",
        )
    }
}

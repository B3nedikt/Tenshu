package org.tenshu.inference.proxy

import org.tenshu.core.common.util.Sha256Digest
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import org.tenshu.inference.proxy.onnx.SiglipTextTokenizer
import org.tenshu.inference.proxy.onnx.UnigramVocabulary
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.inference.proxy.onnx.realVocabularyBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The probe the phone draws is the probe the export's record was measured on
 * (`ios-on-device-inference.md` §14; `scripts/inference/preflight-probe.py`).
 *
 * The record holds two digests the script computed in Python: of the image's RGB bytes and of the
 * float32 tensor it fed the vision tower. Both are recomputed here from [PreflightProbeImage] and
 * `ImagePreprocessing`, on the JVM and on the simulator. If either moves, the phone would be
 * checked against a margin measured on other pixels.
 */
class PreflightProbeImageTest {

    private val model = VisionModel.PinnedSigLip
    /** Every record the build carries was measured on this one image, so each is held to it. */
    private val records: List<PreflightProbe> = PreflightProbeRegistry.records

    private fun sha256(bytes: ByteArray): String =
        Sha256Digest().apply { update(bytes, bytes.size) }.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun theDrawnPixelsAreTheRecordedOnes() {
        val argb: IntArray = PreflightProbeImage.argb()
        assertEquals(model.side, PreflightProbeImage.SIDE, "the probe is not the pinned model's input size")
        assertEquals(model.side * model.side, argb.size)
        val rgb = ByteArray(3 * argb.size)
        argb.forEachIndexed { index, pixel ->
            assertEquals(0xFF, pixel ushr 24, "a pixel that is not opaque")
            rgb[3 * index] = (pixel shr 16).toByte()
            rgb[3 * index + 1] = (pixel shr 8).toByte()
            rgb[3 * index + 2] = pixel.toByte()
        }
        assertTrue(records.isNotEmpty(), "the build carries no probe at all")
        records.forEach { assertEquals(it.imageRgbSha256, sha256(rgb), "record ${it.id}") }
    }

    @Test
    fun theTensorFedToTheVisionTowerIsTheRecordedOneBitForBit() {
        val tensor: FloatArray = ImagePreprocessing.preprocessPixels(PreflightProbeImage.argb(), model.side, model.profile)
        val bytes = ByteArray(4 * tensor.size)
        tensor.forEachIndexed { index, value ->
            val bits: Int = value.toRawBits()
            for (byte in 0 until 4) bytes[4 * index + byte] = (bits ushr (8 * byte)).toByte()
        }
        records.forEach { assertEquals(it.imageTensorSha256, sha256(bytes), "record ${it.id}") }
    }

    @Test
    fun theRecordedRowsAreFullRowsOfTheTowersLength() {
        records.forEach { record ->
            assertEquals(model.textSequenceLength, record.tokensDescribes.size, record.id)
            assertEquals(model.textSequenceLength, record.tokensDoesNot.size, record.id)
            assertTrue(record.minMargin > 0 && record.minMargin < record.hostMargin, "${record.id}: the phone is asked for more than the host measured, or for nothing")
        }
    }

    /**
     * ⚠ Opt-in: `-Dtenshu.inference.tokenizer=<the export's tokenizer.bin>`. With it, the app's
     * tokenizer over the published vocabulary must make exactly the rows the checkpoint's own
     * tokenizer made in the script's run, which is what the preflight then requires on a phone.
     */
    @Test
    fun theAppsTokenizerMakesTheRecordedRowsFromTheRealVocabulary() {
        val bytes: ByteArray = realVocabularyBytes()
            ?: return println("SKIPPED: no -Dtenshu.inference.tokenizer=<tokenizer.bin>")
        val own: List<PreflightProbe> = records.filter { it.vocabularySha256 == sha256(bytes) }
        assertTrue(own.isNotEmpty(), "no record this build carries was measured with that vocabulary")
        val tokenizer = SiglipTextTokenizer(assertNotNull(UnigramVocabulary.read(bytes)), model.textSequenceLength)
        own.forEach { record ->
            assertContentEquals(record.tokensDescribes, tokenizer.encode(record.captionDescribes), record.id)
            assertContentEquals(record.tokensDoesNot, tokenizer.encode(record.captionDoesNot), record.id)
        }
    }
}

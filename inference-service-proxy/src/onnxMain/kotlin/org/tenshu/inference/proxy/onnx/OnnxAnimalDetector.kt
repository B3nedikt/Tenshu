package org.tenshu.inference.proxy.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.tenshu.inference.proxy.DetectedAnimal
import org.tenshu.inference.proxy.PixelAnimalDetector
import java.io.File
import java.nio.FloatBuffer

/**
 * RF-DETR over ONNX Runtime (`photos-pets.md` §10.1): decoded pixels in, cats and dogs out.
 *
 * ⚠⚠ **Not thread-safe, by design.** It is owned by one face backend and called under that backend's
 * lock, beside the embedder session; a second owner would need its own instance.
 * ⚠⚠ `null` from [detect] means **the model could not run** — missing, rejected, or failed this time —
 * never "no animals". A caller whose stamp says the veto ran must not record a photograph on it.
 *
 * @param locate the model file, asked per call (a side-load can arrive or vanish while the proxy runs).
 */
class OnnxAnimalDetector(
    private val locate: () -> String?,
    private val model: AnimalDetectorModel = AnimalDetectorModel.RfDetrNanoInt8,
    private val environment: () -> OrtEnvironment = { OrtEnvironment.getEnvironment() },
    private val sessions: OnnxSessionFactory = OnnxSessionFactory(environment = environment),
) : PixelAnimalDetector {

    private var session: OrtSession? = null
    private var sessionKey: String? = null

    /** ⚠⚠ Latched per file: a graph with another interface is a property of the artifact, not of a photograph. */
    @Volatile
    private var rejectedKey: String? = null

    /** Whether a model file is there and not already refused. Cheap: no session is opened. */
    override fun available(): Boolean {
        val path = locate() ?: return false
        return fileKey(path) != rejectedKey
    }

    /** Every cat and dog in a `width × height` packed-ARGB image, upright; `null` when the model cannot run. */
    override fun detect(argb: IntArray, width: Int, height: Int): List<DetectedAnimal>? {
        val held = session() ?: return null
        val input = AnimalDetectorInput.tensor(argb, width, height)
        val side = AnimalDetectorModel.SIDE.toLong()
        return runCatching {
            OnnxTensor.createTensor(environment(), FloatBuffer.wrap(input), longArrayOf(1, 3, side, side)).use { tensor ->
                held.run(mapOf(held.inputNames.first() to tensor)).use { result ->
                    val dets = (result.get(DETS).get() as OnnxTensor).floatBuffer.let { FloatArray(it.remaining()).also { out -> it.get(out) } }
                    val logits = (result.get(LABELS).get() as OnnxTensor).floatBuffer.let { FloatArray(it.remaining()).also { out -> it.get(out) } }
                    RfDetrOutput.animals(dets, logits, model.minConfidence)
                }
            }
        }.onFailure { println("OnnxAnimalDetector -> run failed: $it") }.getOrNull()
    }

    private fun session(): OrtSession? {
        val path = locate() ?: return null
        val key = fileKey(path)
        if (key == rejectedKey) return null
        session?.let { if (sessionKey == key) return it }
        session?.let { runCatching { it.close() } }
        session = null
        sessionKey = null
        val created = runCatching { sessions.open(path) }.onFailure { println("OnnxAnimalDetector -> open failed: $it") }.getOrNull()
            ?: return null
        if (!accepts(created)) {
            println("OnnxAnimalDetector -> $path is not the RF-DETR interface; refused")
            runCatching { created.close() }
            rejectedKey = key
            return null
        }
        session = created
        sessionKey = key
        return created
    }

    /** `input` [1, 3, 384, 384] → `dets` [1, 300, 4] and `labels` [1, 300, 91], by name. */
    private fun accepts(created: OrtSession): Boolean = runCatching {
        val side = AnimalDetectorModel.SIDE.toLong()
        fun shape(info: ai.onnxruntime.NodeInfo): List<Long> = (info.info as ai.onnxruntime.TensorInfo).shape.toList()
        val input = created.inputInfo.values.singleOrNull() ?: return false
        shape(input) == listOf(1L, 3L, side, side) &&
            created.outputInfo[DETS]?.let(::shape) == listOf(1L, AnimalDetectorModel.QUERIES.toLong(), 4L) &&
            created.outputInfo[LABELS]?.let(::shape) == listOf(1L, AnimalDetectorModel.QUERIES.toLong(), AnimalDetectorModel.CLASSES.toLong())
    }.getOrDefault(false)

    private fun fileKey(path: String): String = File(path).let { "$path:${it.length()}:${it.lastModified()}" }

    private companion object {
        const val DETS = "dets"
        const val LABELS = "labels"
    }
}

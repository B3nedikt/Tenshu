package org.tenshu.inference.proxy.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.tenshu.inference.proxy.PixelPetEmbedder
import java.io.File
import java.nio.FloatBuffer

/**
 * DINOv2-small over ONNX Runtime (`photos-pets.md` PT-D10): [PetCropInput]'s tensor in, the 768-wide
 * `[CLS ‖ mean]` out. [OnnxAnimalDetector]'s shape, and its rules:
 *
 * ⚠⚠ **Not thread-safe** — owned by one `PixelPetBackend`, called under its lock.
 * ⚠⚠ `null` from [embed] means the model could not run, never anything about the crop.
 *
 * @param locate the model file, asked per call (a download or side-load can arrive while the proxy runs).
 */
class OnnxPetEmbedder(
    private val locate: () -> String?,
    private val environment: () -> OrtEnvironment = { OrtEnvironment.getEnvironment() },
    private val sessions: OnnxSessionFactory = OnnxSessionFactory(environment = environment),
) : PixelPetEmbedder {

    private var session: OrtSession? = null
    private var sessionKey: String? = null

    /** ⚠⚠ Latched per file: a graph with another interface is a property of the artifact. */
    @Volatile
    private var rejectedKey: String? = null

    override fun available(): Boolean {
        val path = locate() ?: return false
        return fileKey(path) != rejectedKey
    }

    override fun embed(input: FloatArray): FloatArray? {
        val side = PetEmbedderModel.SIDE.toLong()
        if (input.size != (3 * side * side).toInt()) return null
        val held = session() ?: return null
        return runCatching {
            OnnxTensor.createTensor(environment(), FloatBuffer.wrap(input), longArrayOf(1, 3, side, side)).use { tensor ->
                held.run(mapOf(held.inputNames.first() to tensor)).use { result ->
                    (result.get(OUTPUT).get() as OnnxTensor).floatBuffer.let { FloatArray(it.remaining()).also { out -> it.get(out) } }
                }
            }
        }.onFailure { println("OnnxPetEmbedder -> run failed: $it") }.getOrNull()
    }

    private fun session(): OrtSession? {
        val path = locate() ?: return null
        val key = fileKey(path)
        if (key == rejectedKey) return null
        session?.let { if (sessionKey == key) return it }
        session?.let { runCatching { it.close() } }
        session = null
        sessionKey = null
        val created = runCatching { sessions.open(path) }.onFailure { println("OnnxPetEmbedder -> open failed: $it") }.getOrNull()
            ?: return null
        if (!accepts(created)) {
            println("OnnxPetEmbedder -> $path is not the DINOv2 [CLS ‖ mean] interface; refused")
            runCatching { created.close() }
            rejectedKey = key
            return null
        }
        session = created
        sessionKey = key
        return created
    }

    /** One input `[1, 3, 224, 224]` → `embedding` `[1, 768]`, by name. */
    private fun accepts(created: OrtSession): Boolean = runCatching {
        val side = PetEmbedderModel.SIDE.toLong()
        fun shape(info: ai.onnxruntime.NodeInfo): List<Long> = (info.info as ai.onnxruntime.TensorInfo).shape.toList()
        val input = created.inputInfo.values.singleOrNull() ?: return false
        shape(input) == listOf(1L, 3L, side, side) &&
            created.outputInfo[OUTPUT]?.let(::shape) == listOf(1L, PetEmbedderModel.WIDTH.toLong())
    }.getOrDefault(false)

    private fun fileKey(path: String): String = File(path).let { "$path:${it.length()}:${it.lastModified()}" }

    private companion object {
        const val OUTPUT = "embedding"
    }
}

package org.tenshu.inference.proxy.coreml

import org.tenshu.inference.proxy.DetectedAnimal
import org.tenshu.inference.proxy.PixelAnimalDetector
import org.tenshu.inference.proxy.PixelPetEmbedder
import org.tenshu.inference.proxy.onnx.AnimalDetectorInput
import org.tenshu.inference.proxy.onnx.AnimalDetectorModel
import org.tenshu.inference.proxy.onnx.RfDetrOutput

/**
 * Opens a compiled Core ML model against a spec — [CoreMlSession.open] in production, a fake in tests.
 * ⚠ The first open after an install costs seconds: never on the main thread.
 */
fun interface PetCoreMlOpener {
    fun open(compiledPath: String, spec: CoreMlTowerSpec): CoreMlSessionPort
}

/** What every product graph uses. */
val SystemPetCoreMlOpener = PetCoreMlOpener { path, spec -> CoreMlSession.open(path, spec) }

/**
 * One Core ML model held for Pets: a session per compiled path, opened lazily, reopened when the path
 * moves, and ⚠⚠ **latched per path** when Core ML loads it and it is another model — a property of the
 * artifact, not of a photograph. A load failure is NOT latched: it may be a memory moment.
 *
 * ⚠⚠ Not thread-safe — `PixelPetBackend` serialises every call under its own lock, as the ONNX twins.
 */
internal class PetCoreMlModel(
    private val locate: () -> String?,
    private val spec: CoreMlTowerSpec,
    private val opener: PetCoreMlOpener,
) {
    private var session: CoreMlSessionPort? = null
    private var rejectedPath: String? = null

    fun available(): Boolean {
        val path = locate() ?: return false
        return path != rejectedPath
    }

    fun session(): CoreMlSessionPort? {
        val path = locate() ?: return null
        if (path == rejectedPath) return null
        session?.let { if (it.compiledPath == path) return it }
        close()
        return try {
            opener.open(path, spec).also { session = it }
        } catch (rejected: CoreMlRejectedModelException) {
            println("PetCoreMl -> $path refused: ${rejected.message}")
            rejectedPath = path
            null
        } catch (failure: Exception) {
            println("PetCoreMl -> ${spec.role} could not open: $failure")
            null
        }
    }

    /** The path a session must be opened for before the next use, or `null` when one is held (or none can be). */
    fun pathToOpen(): String? {
        val path = locate() ?: return null
        if (path == rejectedPath) return null
        return path.takeUnless { session?.compiledPath == path }
    }

    /**
     * Takes a session opened elsewhere — the iOS backend opens through readiness's record
     * (`AiReadiness.servingOpen`), which is suspending; [session] would open it unrecorded.
     */
    fun adopt(opened: CoreMlSessionPort) {
        close()
        session = opened
    }

    /** Latches [path] as another model, as [session] does when the opener refuses it. */
    fun reject(path: String) {
        rejectedPath = path
    }

    val modelSpec: CoreMlTowerSpec get() = spec

    /** Lets the model go — a withdrawal, a teardown, a memory warning. Reopened on next use. */
    fun close() {
        session?.let { runCatching { it.close() } }
        session = null
    }
}

/**
 * RF-DETR Nano over Core ML (`photos-pets.md` PT-4): [AnimalDetectorInput]'s tensor in, `dets` and
 * `labels` out through [CoreMlSessionPort.predictAll], read by the same [RfDetrOutput] as Android's — so
 * a box means the same thing on both platforms (§10.13: template parity median 0.989).
 *
 * ⚠⚠ `null` from [detect] means the model could not run, never "no animals".
 *
 * @param locate the compiled `.mlmodelc`, asked per call (readiness can move it).
 */
class CoreMlAnimalDetector(
    locate: () -> String?,
    private val model: AnimalDetectorModel = AnimalDetectorModel.RfDetrNanoCoreMl,
    opener: PetCoreMlOpener = SystemPetCoreMlOpener,
) : PixelAnimalDetector {

    internal val held = PetCoreMlModel(locate, CoreMlTowerSpec.animalDetector(), opener)

    override fun available(): Boolean = held.available()

    override fun detect(argb: IntArray, width: Int, height: Int): List<DetectedAnimal>? {
        val session = held.session() ?: return null
        return try {
            val outputs = session.predictAll(AnimalDetectorInput.tensor(argb, width, height))
            RfDetrOutput.animals(outputs.getValue("dets"), outputs.getValue("labels"), model.minConfidence)
        } catch (failure: CoreMlPredictionException) {
            println("CoreMlAnimalDetector -> run failed: ${failure.message}")
            null
        }
    }

    fun close() = held.close()
}

/**
 * DINOv2-small over Core ML: `PetCropInput`'s tensor in, the 768-wide `[CLS ‖ mean]` out — the
 * `PixelPetEmbedder` contract the ONNX twin keeps. ⚠⚠ `null` means the model could not run.
 */
class CoreMlPetEmbedder(
    locate: () -> String?,
    opener: PetCoreMlOpener = SystemPetCoreMlOpener,
) : PixelPetEmbedder {

    internal val held = PetCoreMlModel(locate, CoreMlTowerSpec.petEmbedder(), opener)

    override fun available(): Boolean = held.available()

    override fun embed(input: FloatArray): FloatArray? {
        val session = held.session() ?: return null
        return try {
            session.predict(input)
        } catch (failure: CoreMlPredictionException) {
            println("CoreMlPetEmbedder -> run failed: ${failure.message}")
            null
        } catch (wrongInput: IllegalArgumentException) {
            null
        }
    }

    fun close() = held.close()
}

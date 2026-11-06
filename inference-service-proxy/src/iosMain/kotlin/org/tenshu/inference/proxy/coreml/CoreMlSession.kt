package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreML.MLComputeUnits
import platform.CoreML.MLComputeUnitsAll
import platform.CoreML.MLComputeUnitsCPUAndNeuralEngine
import platform.CoreML.MLComputeUnitsCPUOnly
import platform.CoreML.MLDictionaryFeatureProvider
import platform.CoreML.MLFeatureDescription
import platform.CoreML.MLFeatureProviderProtocol
import platform.CoreML.MLFeatureValue
import platform.CoreML.MLModel
import platform.CoreML.MLModelConfiguration
import platform.CoreML.MLMultiArray
import platform.CoreML.MLMultiArrayDataType
import platform.CoreML.MLMultiArrayDataTypeFloat32
import platform.CoreML.MLMultiArrayDataTypeInt32
import platform.CoreML.create
import platform.CoreML.multiArrayConstraint
import platform.CoreML.objectAtIndexedSubscript
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.posix.memcpy

/** The compiled model at a path would not load, or would not compile. */
class CoreMlLoadException(message: String) : Exception(message)

/** The model loaded and is not the tower it was opened as ([CoreMlTowerSpec.rejectionFor]). */
class CoreMlRejectedModelException(message: String) : Exception(message)

/** A prediction failed, or produced something that cannot be an embedding. */
class CoreMlPredictionException(message: String) : Exception(message)

/**
 * Which hardware Core ML may place the graph on.
 *
 * ⚠⚠ **[SHIPPED] is a build constant, never a run-time choice** (§3.2). `.cpuAndNeuralEngine` is as
 * fast as `.all` on the measured phone at a tenth of the footprint (§2.2), and a constant keeps the
 * vector space a property of the build. The other two exist for the IN-3m-iOS harness's comparison
 * rows; nothing in a product graph passes them.
 */
enum class CoreMlComputeUnits {
    CPU_AND_NEURAL_ENGINE,
    ALL,
    CPU_ONLY,
    ;

    companion object {
        val SHIPPED: CoreMlComputeUnits = CPU_AND_NEURAL_ENGINE
    }
}

/**
 * One loaded tower, as the backend sees it — a port, so the backend's own guards are testable with
 * a fake (§3.2).
 *
 * ⚠ Not thread-safe. The owner serialises predictions on a tower and closes under the same lock.
 */
interface CoreMlSessionPort {
    /** The compiled model this session was opened from. One session per path. */
    val compiledPath: String

    /** @throws CoreMlPredictionException on a failed prediction or an unusable output. */
    fun predict(floats: FloatArray): FloatArray

    /** @throws CoreMlPredictionException on a failed prediction or an unusable output. */
    fun predict(ids: IntArray): FloatArray

    fun close()
}

/**
 * A compiled `.mlmodelc` loaded through `platform.CoreML` (§3.2).
 *
 * The vector is returned as the model produced it. The backend normalises once, as
 * `OnnxInferenceBackend` does.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class CoreMlSession private constructor(
    private var model: MLModel?,
    private val spec: CoreMlTowerSpec,
    override val compiledPath: String,
) : CoreMlSessionPort {

    override fun predict(floats: FloatArray): FloatArray {
        requireInput(CoreMlElementType.FLOAT32, floats.size)
        return run(MLMultiArrayDataTypeFloat32) { target ->
            floats.usePinned { pinned -> memcpy(target.dataPointer, pinned.addressOf(0), (floats.size * 4).convert()) }
        }
    }

    override fun predict(ids: IntArray): FloatArray {
        requireInput(CoreMlElementType.INT32, ids.size)
        return run(MLMultiArrayDataTypeInt32) { target ->
            ids.usePinned { pinned -> memcpy(target.dataPointer, pinned.addressOf(0), (ids.size * 4).convert()) }
        }
    }

    /** ⚠ Whether Core ML frees the model's memory and Neural Engine resources here is [I] (§8). */
    override fun close() {
        model = null
    }

    private fun requireInput(type: CoreMlElementType, count: Int) {
        require(spec.inputType == type) { "the ${spec.role} tower takes ${spec.inputType}, not $type" }
        require(count == spec.inputCount) {
            "the ${spec.role} tower takes ${spec.inputCount} values (${spec.inputShape}), got $count"
        }
    }

    private fun run(dataType: MLMultiArrayDataType, fill: (MLMultiArray) -> Unit): FloatArray {
        val loaded: MLModel = model ?: throw CoreMlPredictionException("the ${spec.role} session is closed")
        // ⚠ An index pass predicts in a loop on a thread with no run loop; without a pool every
        // input array and feature provider lives until the thread ends.
        val vector: FloatArray = autoreleasepool {
            val input: MLMultiArray = withError("allocating the ${spec.role} input") { error ->
                MLMultiArray.create(shape = spec.inputShape, dataType = dataType, error = error)
            }
            fill(input)
            val features: MLDictionaryFeatureProvider = withError("wrapping the ${spec.role} input") { error ->
                MLDictionaryFeatureProvider(
                    dictionary = mapOf<Any?, Any?>(spec.inputName to MLFeatureValue.featureValueWithMultiArray(input)),
                    error = error,
                )
            }
            val result: MLFeatureProviderProtocol = withError("the ${spec.role} prediction") { error ->
                loaded.predictionFromFeatures(features, error = error)
            }
            val output: MLMultiArray = result.featureValueForName(spec.outputName)?.multiArrayValue
                ?: throw CoreMlPredictionException("the ${spec.role} prediction has no '${spec.outputName}'")
            floatsOf(output)
        }
        spec.unusableReason(vector)?.let { reason ->
            throw CoreMlPredictionException("the ${spec.role} tower's output is unusable: $reason")
        }
        return vector
    }

    /**
     * ⚠ The fast path needs Float32 **and** a contiguous layout: `dataPointer` is the raw buffer,
     * and a strided array read through it yields the right count of the wrong numbers. Anything
     * else goes through the subscript, which applies the strides and converts the type.
     */
    private fun floatsOf(array: MLMultiArray): FloatArray {
        val count: Int = array.count.toInt()
        val out = FloatArray(count)
        if (count == 0) return out
        if (array.dataType == MLMultiArrayDataTypeFloat32 && isContiguous(array)) {
            out.usePinned { pinned -> memcpy(pinned.addressOf(0), array.dataPointer, (count * 4).convert()) }
        } else {
            for (i in 0 until count) out[i] = array.objectAtIndexedSubscript(i.toLong()).floatValue
        }
        return out
    }

    private fun isContiguous(array: MLMultiArray): Boolean {
        val shape: List<Long> = array.shape.map { (it as NSNumber).longValue }
        val strides: List<Long> = array.strides.map { (it as NSNumber).longValue }
        var expected = 1L
        for (axis in shape.indices.reversed()) {
            if (strides[axis] != expected) return false
            expected *= shape[axis]
        }
        return true
    }

    private inline fun <T : Any> withError(
        what: String,
        call: (kotlinx.cinterop.CPointer<ObjCObjectVar<NSError?>>) -> T?,
    ): T = memScoped {
        val failure = alloc<ObjCObjectVar<NSError?>>()
        call(failure.ptr)
            ?: throw CoreMlPredictionException("$what failed: ${failure.value?.localizedDescription ?: "no NSError reported"}")
    }

    companion object {
        /**
         * Loads the compiled model at [compiledPath] and checks it is [spec].
         *
         * ⚠ The first open of a model after an install costs seconds (2.2 s vision, 3.5 s text on
         * the measured phone, §2.2), and the cost is per [computeUnits] setting. Never on the main
         * thread.
         *
         * @throws CoreMlLoadException when Core ML will not load it.
         * @throws CoreMlRejectedModelException when it loads and is another model.
         */
        fun open(
            compiledPath: String,
            spec: CoreMlTowerSpec,
            computeUnits: CoreMlComputeUnits = CoreMlComputeUnits.SHIPPED,
        ): CoreMlSession {
            val configuration = MLModelConfiguration()
            configuration.computeUnits = platformValue(computeUnits)
            val model: MLModel = memScoped {
                val failure = alloc<ObjCObjectVar<NSError?>>()
                MLModel.modelWithContentsOfURL(NSURL.fileURLWithPath(compiledPath), configuration, failure.ptr)
                    ?: throw CoreMlLoadException(
                        "cannot load $compiledPath: ${failure.value?.localizedDescription ?: "no NSError reported"}",
                    )
            }
            spec.rejectionFor(shapeOf(model))?.let { reason ->
                throw CoreMlRejectedModelException("$compiledPath is not the ${spec.role} tower: $reason")
            }
            return CoreMlSession(model, spec, compiledPath)
        }

        private fun platformValue(units: CoreMlComputeUnits): MLComputeUnits = when (units) {
            CoreMlComputeUnits.CPU_AND_NEURAL_ENGINE -> MLComputeUnitsCPUAndNeuralEngine
            CoreMlComputeUnits.ALL -> MLComputeUnitsAll
            CoreMlComputeUnits.CPU_ONLY -> MLComputeUnitsCPUOnly
        }

        private fun shapeOf(model: MLModel): CoreMlModelShape {
            val description = model.modelDescription
            val inputs = HashMap<String, CoreMlInputDescription>()
            description.inputDescriptionsByName.forEach { (name, value) ->
                val constraint = (value as MLFeatureDescription).multiArrayConstraint()
                inputs[name as String] = CoreMlInputDescription(
                    shape = constraint?.shape?.map { (it as NSNumber).intValue } ?: emptyList(),
                    type = when (constraint?.dataType) {
                        MLMultiArrayDataTypeFloat32 -> CoreMlElementType.FLOAT32
                        MLMultiArrayDataTypeInt32 -> CoreMlElementType.INT32
                        else -> null
                    },
                )
            }
            val outputs: Set<String> = description.outputDescriptionsByName.keys.map { it as String }.toSet()
            return CoreMlModelShape(inputs, outputs)
        }
    }
}

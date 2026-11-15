package org.tenshu.inference.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.FaceSuggestionRejections
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.RejectFaceSuggestion
import org.tenshu.inference.RejectedFace

/**
 * [RejectFaceSuggestion] — the "No" to *"Is this Anna?"*, recorded on this device only
 * (IN-6 F6c name suggestions; `photos-people-and-pets.md` §6a *F6c-s as built*).
 *
 * ⚠⚠ **No realm write.** The record goes to the template store's [FaceRejections], which shares
 * the templates' custody (threat model A40 *Name suggestions*). Consent gates it exactly as it gates
 * `nameFaceGroup`: a "No" arriving after a withdrawal must not create face data the switch was
 * just used to stop.
 */
internal class RejectFaceSuggestionImplementation(
    private val faces: FaceTemplateStore,
    private val deviceId: String,
    /** See [FaceGroupsImplementation.storeGate] — a store that may not be served may not be written either. */
    private val storeGate: (suspend () -> Boolean)? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val name = (params?.get(RejectFaceSuggestion.Params.NAME) as? TString)?.get().orEmpty().trim()
        val paths = (params?.get(RejectFaceSuggestion.Params.FILE_PATHS) as? TList<*>)
            ?.toList()?.map { (it as? TString)?.get().orEmpty() }.orEmpty()
        val boxes = (params?.get(RejectFaceSuggestion.Params.BOXES_PER_MILLE) as? TList<*>)
            ?.toList()?.map { ((it as? TInteger)?.get() ?: -1L).toInt() }.orEmpty()

        // ⚠ A caller mistake writes nothing and claims nothing — before consent, as `nameFaceGroup`
        // does, so a malformed call is never reported as a switch that is off.
        val zero = 0L.toTInteger(executionContext.context) as T
        if (name.isBlank() || paths.isEmpty() || boxes.size != paths.size * 4) return zero
        if (paths.any { it.isBlank() } || boxes.any { it !in 0..1000 }) return zero

        if (storeGate != null && !storeGate.invoke()) {
            throw TenshuException(
                FaceGroupsUnavailable.create(
                    context = executionContext.context,
                    reason = FaceGroupsUnavailable.Reasons.NO_BACKEND,
                    message = "this device's face data cannot be used right now",
                ),
            )
        }
        if (!executionContext.hasInferenceConsent(CAPABILITY, deviceId)) {
            throw TenshuException(
                FaceGroupsUnavailable.create(
                    context = executionContext.context,
                    reason = FaceGroupsUnavailable.Reasons.NO_CONSENT,
                    message = "faceClustering consent absent for realm or device $deviceId",
                ),
            )
        }

        val records = paths.mapIndexed { i, path ->
            FaceRejection(
                filePath = path,
                name = name,
                leftPerMille = boxes[4 * i],
                topPerMille = boxes[4 * i + 1],
                rightPerMille = boxes[4 * i + 2],
                bottomPerMille = boxes[4 * i + 3],
            )
        }
        // ⚠ A repeat "No" folds: the count is of records the store did not hold, and a re-sent
        // answer is still an answer recorded, so the caller reads any non-negative count as saved.
        // ⚠ Faces on photographs the store holds no template for are dropped (`reject`'s custody).
        return faces.reject(records).toLong().toTInteger(executionContext.context) as T
    }

    private companion object {
        val CAPABILITY = InferenceCapability.FACE_CLUSTERING
    }
}

/**
 * [FaceSuggestionRejections] — every recorded "No", for the app to stop asking.
 *
 * ⚠⚠ Refused (never answered empty) where `faceGroups` would refuse to SERVE: no consent, or a
 * store that may not be read — an empty success would bring every answered question back.
 */
internal class FaceSuggestionRejectionsImplementation(
    private val faces: FaceTemplateStore,
    private val deviceId: String,
    /** See [FaceGroupsImplementation.storeGate]. */
    private val storeGate: (suspend () -> Boolean)? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        if (storeGate != null && !storeGate.invoke()) {
            unavailable(executionContext, FaceGroupsUnavailable.Reasons.NO_BACKEND, "this device's face data cannot be used right now")
        }
        // ⚠⚠ Before the store is read: a withdrawn switch must not disclose what was rejected.
        if (!executionContext.hasInferenceConsent(CAPABILITY, deviceId)) {
            unavailable(
                executionContext,
                FaceGroupsUnavailable.Reasons.NO_CONSENT,
                "faceClustering consent absent for realm or device $deviceId",
            )
        }
        // ⚠⚠ An unreadable rejections file THROWS here (`RejectionsUnreadable`) and the op fails:
        // answering empty would fold and ask about every No this device was ever told.
        val held = faces.rejections().map { rejection ->
            RejectedFace.create(
                context = executionContext.context,
                filePath = rejection.filePath,
                name = rejection.name,
                boxLeftPerMille = rejection.leftPerMille,
                boxTopPerMille = rejection.topPerMille,
                boxRightPerMille = rejection.rightPerMille,
                boxBottomPerMille = rejection.bottomPerMille,
            )
        }
        return SimpleTList(held) as T
    }

    private fun unavailable(executionContext: ExecutionContext, reason: String, message: String): Nothing =
        throw TenshuException(
            FaceGroupsUnavailable.create(context = executionContext.context, reason = reason, message = message),
        )

    private companion object {
        val CAPABILITY = InferenceCapability.FACE_CLUSTERING
    }
}

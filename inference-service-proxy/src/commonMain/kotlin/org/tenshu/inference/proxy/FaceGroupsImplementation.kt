package org.tenshu.inference.proxy

import org.tenshu.core.Data
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.GroupedFace
import org.tenshu.inference.InferenceCapability

/**
 * [org.tenshu.inference.FaceGroups] — group every stored face template on this device, largest
 * group first, and return paths only. The face sibling of [PhotosLikeImplementation], deliberately
 * the same shape including its refusal ordering.
 *
 * ⚠⚠ **No vector crosses this boundary.** The templates are read, compared and dropped inside this
 * call; what leaves is a file path, a face index and an id valid for this call alone. That is the
 * whole reason an app process may render People at all — `photos-people-and-pets.md` §3.1.
 *
 * ⚠⚠ **Every refusal throws [FaceGroupsUnavailable] instead of returning an empty list**, and this
 * op is where that rule earns its keep: a library with no recognisable faces produces a legitimately
 * empty People grid, so an empty *success* is indistinguishable from a broken build by eye.
 */
internal class FaceGroupsImplementation(
    private val registry: InferenceBackendRegistry,
    private val faces: FaceTemplateStore,
    private val clustering: FaceClustering,
    private val deviceId: String,
    /** See [FaceGroupingComponents.livePaths]. `null` clusters every stored row. */
    private val livePaths: LivePaths? = null,
    /**
     * Whether [faces] may be read to be SERVED for this request — `PhotosLikeImplementation
     * .storeGate`'s twin, and for a stronger reason: a template is biometric (threat model A40).
     * ⚠ Asked after the withdrawal deletion, never before it: a closed gate stops serving, not
     * deleting. ⚠ [livePaths] is no substitute: after a realm reset the same
     * path can name a live photograph of somebody else.
     */
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
        val limit = params?.get(org.tenshu.inference.FaceGroups.Params.LIMIT)
            ?.let { (it as TInteger).get() } ?: 0L

        // ⚠ A non-positive cap is a caller mistake, not an unavailable feature.
        if (limit <= 0L) return SimpleTList(emptyList<Data>()) as T

        // ⚠⚠ **Withdrawal DELETES the templates** (`photos-people-and-pets.md` F4) — and it happens
        // HERE, before any refusal, not beside the consent refusal below: a user
        // who deleted the model and then switched faces off would otherwise stop at NO_BACKEND and
        // keep biometric data the Settings copy promised to remove. `MediaIndexer.indexPass` is the
        // other place this runs. ⚠ `clear`, not a read — it destroys without disclosing. Names are
        // untouched; they are the user's.
        // ⚠⚠ **And before the store gate.** The gate decides what may be SERVED; it must never
        // decide what may be deleted. A gate closed by something unrelated to the templates (a
        // vector clear that failed) would otherwise keep a withdrawn user's templates on disk for
        // as long as it stayed closed — and the index pass, the other place this runs, stands
        // behind the same gate.
        val consented = executionContext.hasInferenceConsent(CAPABILITY, deviceId)
        // ⚠⚠ Only an EXPLICIT withdrawal deletes — a read that found nothing still
        // refuses below (absence is NO for serving) but must not destroy the templates.
        // ⚠ The withdrawal is asked first, so with no withdrawal the store is not touched at all
        // ahead of the gate; with one, only its stamp is, to decide whether there is anything to
        // delete.
        if (!consented && executionContext.inferenceConsentWithdrawn(CAPABILITY, deviceId) &&
            faces.provenance() != null
        ) {
            faces.clear()
        }

        // ⚠⚠ Before anything is read to be served: a store that may not be read is one this
        // device cannot serve People from right now.
        if (storeGate != null && !storeGate.invoke()) {
            unavailable(
                executionContext,
                FaceGroupsUnavailable.Reasons.NO_BACKEND,
                "this device's face templates cannot be used right now",
            )
        }

        // ⚠ Backend first, then consent — the ordering is the refusal's meaning, argued in
        // `PhotosLikeImplementation`: on a device that can never detect a face, naming the consent
        // switch would send the user to turn on something that changes nothing. Asking the registry
        // runs no model.
        val backend = registry.backendFor(CAPABILITY)
            ?: unavailable(
                executionContext,
                FaceGroupsUnavailable.Reasons.NO_BACKEND,
                "no backend claims faceClustering on this device",
            )

        // ⚠⚠ Before the store is read, not after. Consent can be withdrawn *after* an index pass,
        // which leaves templates on disk that this op must then refuse to serve — reading them to
        // discover that would be the disclosure the switch exists to prevent (IN-D3).
        if (!consented) {
            unavailable(
                executionContext,
                FaceGroupsUnavailable.Reasons.NO_CONSENT,
                "faceClustering consent absent for realm or device $deviceId",
            )
        }

        // ⚠⚠ **Templates from another model are never served**. An app update that
        // moves the pinned model leaves the old store behind until the next index pass rebases it;
        // opening People first would cluster the old space's vectors under the new model's name.
        // Cleared here too, so the answer below is NOT_INDEXED — true, and it tells the user to wait.
        (backend as? ModelBackedBackend)?.provenance?.let { current ->
            val held = faces.provenance()
            if (held != null && held != current) faces.clear()
        }

        // ⚠⚠ **Rows, not faces.** `size()` sums the faces held, so a library that HAS been indexed
        // and simply contains nobody sums to zero — and reporting that as `NOT_INDEXED` would tell
        // such a user to wait for a pass that already ran, forever. A visited photograph with no
        // faces is a recorded *fact* (`MediaIndexer` writes the empty row deliberately), so the
        // honest question is "has anything been looked at", which is what a row answers.
        // ⚠ Read once and reused below: `rows()` is the expensive call on this path.
        val rows = faces.rows()
        if (rows.isEmpty()) {
            unavailable(
                executionContext,
                FaceGroupsUnavailable.Reasons.NOT_INDEXED,
                "face template store holds no rows; no pass has run",
            )
        }

        // ⚠⚠ The gap F2 closes, surfaced rather than swallowed. `clusterFaces` answers
        // `Uncalibrated` with an empty list, so without this branch a build with no recorded
        // threshold would report "this library contains nobody" — confidently, and forever.
        if (clustering is FaceClustering.Uncalibrated) {
            unavailable(
                executionContext,
                FaceGroupsUnavailable.Reasons.UNCALIBRATED,
                "FaceClustering.Uncalibrated; no threshold or minFaces recorded in this build",
            )
        }

        // ⚠⚠ **Only photographs the user still has** (`media-processing-pipeline-sweep.md` F0). A
        // template outlives its photo until the next pass reconciles the store, and every template
        // here pulls a centroid, counts toward `minFaces` and can become a group's cover — so a
        // deleted or trashed photograph is dropped BEFORE clustering, not from the answer after it.
        // ⚠ After the NOT_INDEXED check, which asks whether a pass has run, not what survives. ⚠
        // Rows with no faces are not looked up: they cannot shape a group, and they are most of a
        // library.
        val liveRows = livePaths?.let { predicate ->
            val withFaces = rows.filter { it.faces.isNotEmpty() }
            val live = predicate.liveAmong(executionContext, withFaces.map { it.filePath })
            withFaces.filter { it.filePath in live }
        } ?: rows

        // ⚠ F6a: per photograph, every face it holds — across ALL groups, before the limit and
        // `minFaces` drop any. "Only them" needs the true count, not the count of returned rows.
        val facesByPath = liveRows.associate { it.filePath to it.faces }

        val grouped = clusterFaces(liveRows, clustering)
            // ⚠ Groups, not faces — the cap drops whole people, never half of one. `clusterFaces`
            // returns largest first, so what is dropped is the least-evidenced group.
            .take(limit.toInt())
            .flatMap { cluster ->
                val groupId = cluster.identifier()
                cluster.faces.map { face ->
                    val inPhoto = facesByPath[face.filePath].orEmpty()
                    val box = inPhoto.getOrNull(face.faceIndex)?.box
                    GroupedFace.create(
                        context = executionContext.context,
                        groupId = groupId,
                        filePath = face.filePath,
                        faceIndex = face.faceIndex,
                        boxLeftPerMille = box?.left.perMille(),
                        boxTopPerMille = box?.top.perMille(),
                        boxRightPerMille = box?.right.perMille(),
                        boxBottomPerMille = box?.bottom.perMille(),
                        facesInPhoto = inPhoto.size,
                    )
                }
            }

        return SimpleTList(grouped) as T
    }

    /**
     * An id for this group, for this call.
     *
     * ⚠⚠ **Derived, never stored.** It names the group's first member, which `clusterFaces` makes
     * deterministic by sorting its input — so two calls over an unchanged store agree, and a call
     * after an index pass may not. That is the contract [GroupedFace] documents, and it is
     * deliberate: an id that survived re-clustering would be cluster identity, which is exactly
     * what IN-D5 keeps out of durable state.
     */
    private fun FaceCluster.identifier(): String =
        faces.first().let { "${it.filePath}#${it.faceIndex}" }

    /** A box fraction as thousandths, clamped — a mesh can overhang the frame at its edge. */
    private fun Float?.perMille(): Int = ((this ?: 0f) * 1000f).toInt().coerceIn(0, 1000)

    private fun unavailable(
        executionContext: ExecutionContext,
        reason: String,
        message: String,
    ): Nothing = throw TenshuException(
        FaceGroupsUnavailable.create(
            context = executionContext.context,
            reason = reason,
            message = message,
        ),
    )

    private companion object {
        val CAPABILITY = InferenceCapability.FACE_CLUSTERING
    }
}

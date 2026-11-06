package org.tenshu.inference.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.core.number.toTInteger
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.ModelProvenance

/**
 * [org.tenshu.inference.NameFaceGroup] — write one [MediaLabel] per photograph, stamped
 * [ModelProvenance.User].
 *
 * ⚠⚠⚠ **The only place in IN-6 where data leaves the device.** Everything else this feature holds
 * — templates, groups, crops — is device-local by construction (IN-D5). These rows are `Syncable`
 * and reach every one of the user's devices, so what is written here is what the threat model's
 * A40 covers, and nothing beyond a path and a typed string may be added to it without amending
 * that entry (House Rule 2).
 *
 * ⚠⚠ **Consent is required even though the user typed the words.** It looks redundant — a person
 * cannot reach a naming UI without groups to name — and it is not: consent can be withdrawn while
 * a client is still holding a screen full of faces, and a write arriving after that must not
 * create face data the switch was just used to stop. The refusal is cheap; the stale-client window
 * is real.
 */
internal class NameFaceGroupImplementation(
    private val deviceId: String,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val name = params?.get(org.tenshu.inference.NameFaceGroup.Params.NAME)
            ?.let { (it as TString).get() }
            .orEmpty()
            .trim()
        val paths = (params?.get(org.tenshu.inference.NameFaceGroup.Params.FILE_PATHS) as? TList<*>)
            ?.toList()
            ?.filterIsInstance<TString>()
            ?.map { it.get() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()

        // ⚠ A caller mistake writes nothing and claims nothing. ⚠⚠ Checked BEFORE consent on
        // purpose: "you sent me an empty list" is not a fact about this realm's switches, and
        // reporting it as `NO_CONSENT` would send someone to toggle a setting that is already on.
        if (name.isBlank() || paths.isEmpty()) return null as T

        if (!executionContext.hasInferenceConsent(CAPABILITY, deviceId)) {
            throw TenshuException(
                FaceGroupsUnavailable.create(
                    context = executionContext.context,
                    reason = FaceGroupsUnavailable.Reasons.NO_CONSENT,
                    // ⚠⚠ **Diagnostics, never copy.** `reason` is what a screen renders
                    // from; this string exists for a log and the Errors screen, and
                    // `core-error-copy-catalogue.md` §5.0 is explicit that a catalogue miss shows
                    // the generic sentence rather than an error's own `message`. Phrased as a
                    // statement of fact rather than as an instruction, so it cannot be mistaken
                    // for a sentence meant for a user — which is how a proxy ends up owning
                    // localisation it must not own.
                    message = "faceClustering consent absent for realm or device $deviceId",
                ),
            )
        }

        // ⚠⚠ Counted as they land, and the count is returned even on the way out of a throw.
        // The rows are saved one at a time, so a store failure part way through leaves the earlier
        // ones durable and synced; reporting that as a plain failure makes the caller tell the user
        // nothing saved, and they retype a name that is already partly applied.
        var written = 0
        var failedOn: String? = null
        try {
            paths.forEach { path ->
                failedOn = path
                executionContext.saveData(
                    Entity.create<MediaLabel>(
                        context = executionContext.context,
                        composite = MediaLabel.create(
                            context = executionContext.context,
                            filePath = path,
                            capability = CAPABILITY,
                            label = name,
                            // ⚠⚠ The user is not a classifier: 1000‰ is certainty, and anything
                            // less would let a confidence filter somewhere downstream quietly drop a
                            // name a person typed in favour of a model's guess.
                            confidencePerMille = 1_000,
                            producedBy = ModelProvenance.User,
                        ),
                    ),
                )
                written++
                failedOn = null
            }
        } catch (failure: Throwable) {
            // ⚠⚠⚠ **The failing path is ASKED ABOUT, not guessed at.** `saveData` persists
            // the row and only then dispatches observers, so a throw can mean either "nothing
            // landed" or "it landed and something downstream objected" — and the two need
            // opposite answers. Assuming it landed risks calling a torn write complete, which
            // loses a photograph silently; assuming it did not claims nothing saved while a
            // durable row exists. Reading it back costs one keyed lookup and removes the guess.
            if (failedOn?.let { executionContext.landed(it, name) } == true) written++
            // ⚠ Re-thrown when NOTHING landed, because then it really is a plain failure and the
            // typed error carries more than a zero would. ⚠⚠ Swallowed once something has:
            // the write is torn, and the count is the only honest description of a torn write.
            if (written == 0) throw failure
        }

        return written.toLong().toTInteger(executionContext.context) as T
    }

    /** Whether the label for this (path, name) is actually in the store. */
    private fun ExecutionContext.landed(path: String, name: String): Boolean = runCatching {
        database.getData(
            DataId.StringDataId.create(
                context = context,
                type = MediaLabel.ID,
                keyValue = MediaLabel.labelKey(path, CAPABILITY, name),
            ),
        ) != null
        // ⚠ A probe that itself fails answers "no". The store is already misbehaving at this
        // point, and the conservative answer asks the user to retry — which folds, because
        // `labelKey` makes a repeat write the same row.
    }.getOrDefault(false)

    private companion object {
        val CAPABILITY = InferenceCapability.FACE_CLUSTERING
    }
}

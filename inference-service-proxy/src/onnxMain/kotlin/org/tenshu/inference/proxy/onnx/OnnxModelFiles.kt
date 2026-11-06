package org.tenshu.inference.proxy.onnx

import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelIndexUnreadableException
import org.tenshu.inference.proxy.catalog.ModelStore
import java.io.File

/**
 * Where a device keeps the weights, and the one place both platforms agree about it.
 *
 * ⚠⚠ **Two sources, and only one is verified.** `DefaultModelStore` owns installed models —
 * staging, digest check against the signed catalog, an index. Beside it is a side-load convention:
 * a maintainer pushes the artifact here by hand, which is enough to run the feature end to end and
 * is **not** enough to ship it, because nothing has verified those bytes against a digest. The
 * store's answer wins ([storeLocator], `inference-service.md` §22.4).
 *
 * ⚠ Under the `FileDatabase` root, beside `/inference/embeddings.log`, so a device's inference
 * state lives in one place and `retention-quota-model.md`'s local-cache tier can find all of it.
 */
object OnnxModelFiles {

    /** ⚠ Relative to the platform's `FileDatabase` root — an absolute path is passed in. */
    const val DIRECTORY = "inference/models"

    fun fileName(model: VisionModel = VisionModel.PinnedSigLip): String = "${model.modelId}.onnx"

    /**
     * The text tower's two artifacts, named after the same model id so a directory cannot hold half
     * of one checkpoint and half of another without it being visible.
     *
     * ⚠⚠ Visible, not *prevented*: nothing here verifies that the text tower is the vision tower's
     * sibling, and a mismatched pair produces cosines that are meaningless and computable — the
     * same hazard `inference-service.md` §16.1 describes for two models' image spaces. An installed
     * checkpoint's digest check against the signed catalog closes it; for a side-loaded pair the
     * naming is a convention and the shape checks in [OnnxInferenceBackend] catch the crude cases.
     */
    fun textFileName(model: VisionModel = VisionModel.PinnedSigLip): String = "${model.modelId}.text.onnx"

    fun vocabularyFileName(model: VisionModel = VisionModel.PinnedSigLip): String =
        "${model.modelId}.tokenizer.bin"

    /**
     * A locator for [OnnxInferenceBackend], resolved per call.
     *
     * ⚠ Per call rather than once, so a model that arrives after the proxy started is picked up
     * without a restart — and so a model *deleted* stops being claimed. `isFile` rather than
     * `exists` because a directory of that name would otherwise read as an installed model.
     */
    fun locator(root: File, model: VisionModel = VisionModel.PinnedSigLip): () -> String? =
        locatorFor(root) { fileName(model) }

    /** The text tower, absent on a device that only indexes. */
    fun textLocator(root: File, model: VisionModel = VisionModel.PinnedSigLip): () -> String? =
        locatorFor(root) { textFileName(model) }

    /** The packed vocabulary, useless without [textLocator]'s graph and shipped beside it. */
    fun vocabularyLocator(root: File, model: VisionModel = VisionModel.PinnedSigLip): () -> String? =
        locatorFor(root) { vocabularyFileName(model) }

    /**
     * The face pair's two artifacts: the MediaPipe `.task` detector and the ONNX embedder,
     * side-loaded as `<modelId>.detector.task` and `<modelId>.embedder.onnx`.
     */
    fun faceFileName(model: FaceModel, role: String): String = when (role) {
        ModelArtifactRole.FACE_DETECTOR -> "${model.modelId}.detector.task"
        else -> "${model.modelId}.embedder.onnx"
    }

    /**
     * [storeLocator] for the face pair — installed first, side-load second, resolved per call.
     *
     * ⚠⚠ Its own function rather than a branch in [storeLocator]: that one is keyed by a
     * [VisionModel], and a face role routed through it would fall to the SigLIP side-load path —
     * so a face backend would silently look for the vision tower's file.
     */
    fun faceStoreLocator(store: ModelStore, root: File, role: String, model: FaceModel): () -> String? {
        val sideLoad = locatorFor(root) { faceFileName(model, role) }
        return locate@{
            // ⚠⚠ An index that cannot be read is not "nothing installed": no path, and no
            // side-load either. See [storeLocator].
            val index: List<InstalledModelData> = try {
                store.installed()
            } catch (unreadable: ModelIndexUnreadableException) {
                return@locate null
            }
            val installed = index.firstOrNull { it.modelId == model.modelId }
                ?: return@locate sideLoad()
            // ⚠⚠ **A different version is REFUSED, not loaded**. Templates are
            // stamped with [FaceModel.provenance] — the PINNED version — so loading version 2's
            // weights under this build's version-1 stamp would append a second embedding space to a
            // store that cannot tell them apart. The backend claims nothing until a build pinning
            // the installed version arrives; People says NO_BACKEND meanwhile, which is honest.
            if (installed.modelVersion != model.modelVersion) return@locate null
            val artifact = installed.artifacts.firstOrNull { it.role == role } ?: return@locate sideLoad()
            val file = File(root, artifact.localPath)
            when {
                !file.isFile -> sideLoad()
                // ⚠ **The bytes must be the record's bytes**. This is defence in depth, not the
                // guard: `commitStaged` lands every update at a content-addressed path, so a live
                // record's files are never overwritten and the equal-length window this check could
                // not see is closed by construction (`photos-people-and-pets.md` §12.12). It still
                // refuses a file truncated or replaced by hand under the record. ⚠ Size, not a
                // digest: this runs per `supports()` call.
                file.length() != artifact.byteSize -> null
                else -> file.absolutePath
            }
        }
    }

    private fun locatorFor(root: File, name: () -> String): () -> String? = {
        File(File(root, DIRECTORY), name()).takeIf { it.isFile }?.absolutePath
    }

    /**
     * The locator a wired device uses: **an installed checkpoint first, the side-load convention
     * second** (§22).
     *
     * ⚠⚠ The store's answer wins because it is the only one that has been *verified*. A record in
     * the index means every artifact of that checkpoint passed its sha256 against the signed
     * catalog, and that all three landed together — neither of which the filename convention can
     * assert. A side-loaded file that happens to share the name is exactly the mismatched-sibling
     * case [textFileName] warns about, so it must not shadow a verified one.
     *
     * ⚠ The fallback stays because it is the only route to a model the store does not hold: a
     * maintainer pushing weights by hand relies on it.
     *
     * ⚠ Resolved per call, so a model that arrives — by download or by hand — after the proxy
     * started is picked up without a restart, and one deleted stops being claimed.
     */
    fun storeLocator(
        store: ModelStore,
        root: File,
        role: String,
        model: VisionModel = VisionModel.PinnedSigLip,
    ): () -> String? {
        val sideLoad = when (role) {
            ModelArtifactRole.TEXT -> textLocator(root, model)
            ModelArtifactRole.VOCABULARY -> vocabularyLocator(root, model)
            else -> locator(root, model)
        }
        return locate@{
            // ⚠⚠ An index that cannot be read is not "nothing installed". The side-load path is
            // for a device whose store holds no verified checkpoint, and that is exactly what is
            // not known here: a side-loaded file would shadow an install nobody could see. No path,
            // so the backend claims nothing until the index reads again.
            val installed: String? = try {
                store.pathOf(model.modelId, role)
            } catch (unreadable: ModelIndexUnreadableException) {
                return@locate null
            }
            // ⚠ `isFile` on the store's answer too: an index record whose bytes were deleted from
            // under it must fall through to the side-load path rather than name a missing file.
            installed
                ?.let { File(root, it) }
                ?.takeIf { it.isFile }
                ?.absolutePath
                ?: sideLoad()
        }
    }
}

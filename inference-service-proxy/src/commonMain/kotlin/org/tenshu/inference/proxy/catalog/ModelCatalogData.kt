package org.tenshu.inference.proxy.catalog

import kotlinx.serialization.Serializable
import org.tenshu.inference.InferenceCapability

/**
 * The model catalog and the local install index (IN-2, `inference-service.md` IN-D10) — the same
 * two shapes region delivery already ships, for the same reason: "large, immutable, public
 * binaries delivered to clients that must not talk to the internet" is a solved problem here.
 *
 * ⚠ Proxy-internal plain data; these never cross the IPC wire, so HR #4 does not apply. When an op
 * surface exists (IN-2c) the wire types are what will be schema-first.
 */
data class ModelCatalogData(
    /** The catalog document's own version — the client refuses what it cannot read. */
    val schemaVersion: Int,
    val buildDate: String,
    val models: List<ModelEntryData>,
)

/**
 * The roles a checkpoint's files play. Plain strings, because the catalog carries them and a
 * future one may name a role this build has never heard of — which costs nothing, since a backend
 * asks for the roles it knows by name.
 */
object ModelArtifactRole {
    /** The image tower — what an index pass runs (§20). */
    const val VISION: String = "vision"

    /** The text tower — what turns a query into a vector (§21). */
    const val TEXT: String = "text"

    /** The packed tokenizer vocabulary; useless without [TEXT]'s graph and shipped beside it. */
    const val VOCABULARY: String = "vocabulary"

    /**
     * IN-6: the face **detector** — boxes plus the five landmarks the crop is aligned by.
     *
     * ⚠⚠ Two roles rather than one, because they are two models and a device may legitimately hold
     * only one of them: on Apple the detector comes free from Vision and only [FACE_EMBEDDER] is
     * ours (IN-D13). A single "faces" artifact would make that platform download a detector it will
     * never run.
     */
    const val FACE_DETECTOR: String = "faceDetector"

    /**
     * IN-6: the face **identity** model — an aligned crop in, a template out.
     *
     * ⚠ Its vectors live in a different space from [VISION]'s and are stored separately
     * (`FaceTemplateStore`); a cosine across the two is computable and meaningless.
     */
    const val FACE_EMBEDDER: String = "faceEmbedder"
}

/**
 * One file belonging to a checkpoint.
 *
 * ⚠⚠ **The digest is per artifact, not per entry.** Three files means three independent
 * opportunities for a CDN to serve the wrong bytes, and a single entry-level hash could only be
 * checked after all ~383 MB had arrived.
 */
data class ModelArtifactData(
    /** See [ModelArtifactRole]. Unknown values are carried, not rejected. */
    val role: String,
    val path: String,
    val sha256: String,
    val byteSize: Long,
)

/**
 * One downloadable model.
 *
 * ⚠⚠ **Per capability, never one blob** (IN-D10). IN-D3 makes capabilities individually opt-in, so
 * a single bundle would make a user who wants text-in-images pay — in bytes and in battery — for
 * face clustering they explicitly refused. [capabilities] is a list because one model can serve
 * two (IN-D8's whole argument: embeddings power classification *and* search), never because it is
 * convenient to bundle.
 *
 * ⚠⚠ **[artifacts] is the opposite case, and the distinction is the point.** A *capability* split
 * exists so a user can decline half; a checkpoint's files cannot be declined half — the pinned
 * SigLIP needs its vision tower, its text tower and its vocabulary, and any two of the three is a
 * device that answers questions wrongly rather than one that answers fewer of them. They are one
 * entry so that a half-installed checkpoint is **unrepresentable**: the store indexes all three or
 * none (§22.2), and `OnnxModelFiles`' naming convention — which ties the three together by
 * filename and nothing else — stops being what guarantees they are siblings.
 */
data class ModelEntryData(
    /** Stable identity, never a filename — `siglip-base-patch16`, not `model_v3_final.onnx`. */
    val modelId: String,
    /**
     * What a screen calls this model.
     *
     * ⚠ Carried by the catalog rather than derived from [modelId], because the id is an identifier
     * and reads like one (`siglip-base-patch16-256-multilingual`). ⚠ Blank is tolerated and falls
     * back to the id at the read site — a nameless entry is still installable, and refusing one
     * would let a missing display string cost a user their model.
     */
    val name: String,
    val modelVersion: Int,
    /** What this model can serve. Empty means the client has no use for it. */
    val capabilities: List<InferenceCapability>,
    /** The vocabulary revision, for capabilities that have one (IN-D9). */
    val labelSetVersion: Int,
    /** ⚠ Data, not a decision: which runtime executes it is IN-D2's business, behind the backend. */
    val runtime: String,
    /**
     * The artifact format's version.
     *
     * ⚠ The `tileSchemaVersion` equivalent: a catalog is served to every client version at once, so
     * an entry a client cannot *read* must be filtered out rather than downloaded and puzzled over.
     */
    val artifactVersion: Int,
    /** Every file this checkpoint needs. ⚠ An entry with none is dropped by [usableModels]. */
    val artifacts: List<ModelArtifactData>,
    /**
     * The licence the weights are under, verbatim from the catalog.
     *
     * ⚠⚠ Carried because ⚖️ licence and training-corpus provenance is an **open item** (§7.7) and a
     * model whose licence we cannot state is one we must not ship. Having the field forces the
     * catalog to answer; `docs/models.md` is where the answer is reviewed.
     */
    val licence: String,
) {
    /** What installing this costs in bytes — ⚠ the whole checkpoint, which is the honest figure. */
    val byteSize: Long get() = artifacts.sumOf { it.byteSize }

    fun artifact(role: String): ModelArtifactData? = artifacts.firstOrNull { it.role == role }
}

/** One installed file, and where it landed. */
@Serializable
data class InstalledArtifactData(
    val role: String,
    val localPath: String,
    val byteSize: Long,
    val sha256: String,
)

/**
 * A model present on this device.
 *
 * ⚠ `@Serializable` because it doubles as the persisted index record, exactly as
 * `InstalledRegionData` does.
 *
 * ⚠⚠ An index record exists only when **every** artifact landed, so its presence is the assertion
 * that this device holds a complete checkpoint (§22.2).
 */
@Serializable
data class InstalledModelData(
    val modelId: String,
    val modelVersion: Int,
    val artifacts: List<InstalledArtifactData>,
    val labelSetVersion: Int,
    val licence: String,
) {
    val byteSize: Long get() = artifacts.sumOf { it.byteSize }

    /** Where [role]'s bytes are, or `null` when this checkpoint has no such file. */
    fun pathOf(role: String): String? = artifacts.firstOrNull { it.role == role }?.localPath
}

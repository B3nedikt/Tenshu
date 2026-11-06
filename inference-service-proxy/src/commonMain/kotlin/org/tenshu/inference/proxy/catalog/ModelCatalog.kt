package org.tenshu.inference.proxy.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tenshu.inference.InferenceCapability

/**
 * ⚠⚠ **The pinned signing key, not the pinned hashes** (IN-D10, amending IN-D4).
 *
 * IN-D4 said each model's hash would live in source. That means an **app-store release to ship a
 * model update — including one that fixes a bad model**. The tiles precedent is better and already
 * shipped: pin the *key*, let the signed catalog carry the hashes. The supply-chain property is
 * identical (a compromised CDN still cannot swap a model) and revocation and rotation become
 * possible instead of impossible.
 *
 * ⭐ **The release key, ROTATED 2026-09-25.** The first key (`26e8bb92…d0d0`, pinned 2026-09-06) was
 * lost: its seed file could not be found when IN-6's face model had to be published. This key was
 * minted with `:model-signer`'s `generate-key` straight into the maintainer's login Keychain (item
 * `tenshu-model-signing-key`) with an off-machine copy (`docs/how-to/models-pipeline.md` §1). The
 * seed is not in this repository, nor recoverable from here. The public half is public by
 * construction — that is the whole point of pinning it. ⚠ Builds pinning the old key refuse every
 * catalog signed with this one until they update; models they already installed keep working.
 *
 * ⚠⚠ **Changing this value is an app-store release, and it strands every device until they take
 * it.** A device trusts exactly this key; a catalog signed by any other is refused. That cost is
 * what IN-D10 accepted when it chose to pin the *key* rather than each model's hash — rotation
 * becomes possible instead of impossible, but it is never cheap.
 *
 * ⚠ A pinned key is NOT sufficient to make anything download: the catalog source also needs
 * `named("modelCatalogUrl")`, and a blank URL means "do not even ask".
 */
const val MODEL_CATALOG_PUBLIC_KEY_HEX: String = "b417e9105c61b59f335bed4f7e6b4a86ca811518c6f90d7184d1239c5c7d8877"

/**
 * The artifact format this build can read. Entries above it are filtered out (IN-D10 / the
 * `tileSchemaVersion` precedent): a catalog is served to every client version at once, and an
 * entry a client cannot read must be skipped rather than downloaded and puzzled over.
 */
const val SUPPORTED_ARTIFACT_VERSION: Int = 1

/** The catalog document version this build parses. */
const val SUPPORTED_CATALOG_SCHEMA_VERSION: Int = 1

/**
 * The runtimes this build can actually execute (IN-D13, owed by IN-3b).
 *
 * ⭐ **`onnx` since IN-3b bound `OnnxInferenceBackend`** — this build can execute an ONNX artifact
 * on JVM and Android. ⚠ It stays a *build* claim: an iOS or web build of this module has no
 * runtime, and the honest consequence there is that nothing matches and nothing downloads.
 *
 * ⚠⚠ **Empty was the correct value until a backend was bound, and it meant "download nothing".**
 * `ModelEntryData.runtime` has always been carried as data; nothing checked it, because no build
 * could execute anything — so a catalog naming `coreml` or `tflite` would have been downloaded in
 * full and then puzzled over at load time, over a metered connection, having spent the user's
 * bytes and battery on an artifact this device can never run.
 *
 * ⚠ It is a **build** capability, not a device capability: it says which artifact *formats* this
 * binary can load, and IN-D13's execution-provider ladder (CoreML / NNAPI / DirectML / XNNPACK)
 * sits *below* it — a device with no accelerator still runs an `onnx` artifact on the CPU floor.
 *
 * ⚠⚠ **This constant lives in `commonMain` but describes `onnxMain`, which compiles into the JVM
 * and Android artifacts only.** Reading it directly on iOS or web asserts a runtime that binary
 * does not contain. It is therefore the value the JVM/Android DI sites *contribute*, never a
 * default anyone inherits: `modelDeliveryComponents` defaults to the **empty** set, so a platform
 * that says nothing offers nothing (§23.2).
 *
 * ⚠ The rule this was written under — *add `"onnx"` in the same commit that binds an ONNX runtime,
 * never before, or the filter starts lying in the permissive direction, which is the one that costs
 * bytes* — is what IN-3b honoured. It applies unchanged to the next runtime added here.
 */
val SUPPORTED_RUNTIMES: Set<String> = setOf("onnx")

/**
 * Verifies a detached signature over the raw `catalog.json` bytes against the pinned key
 * (IN-D10 / `maps-region-delivery.md` D3), then parses it.
 *
 * ⚠⚠ **Signature first, parse second, and never the other way round.** A parser is a much larger
 * attack surface than a signature check, so nothing about the document — not even its schema
 * version — is read until the bytes are known to be ours.
 *
 * Pure, and takes a [SignatureVerifier] SAM so production wires `Ed25519Backend::verify` while
 * tests inject a deterministic fake: no crypto backend leaks into `commonTest`, whose actuals
 * differ per platform. Exactly `RegionCatalogVerifier`'s shape.
 */
class ModelCatalogVerifier(
    private val verifier: SignatureVerifier,
    private val publicKeyHex: String = MODEL_CATALOG_PUBLIC_KEY_HEX,
    /**
     * ⚠ Injectable so a test can state which runtime it is exercising rather than inheriting
     * whatever this build happens to support. Production passes the default, which is **empty**
     * until a backend is bound — see [SUPPORTED_RUNTIMES].
     */
    private val supportedRuntimes: Set<String> = SUPPORTED_RUNTIMES,
) {

    /**
     * Is there a key to check anything against at all?
     *
     * ⚠ Exposed so a caller can decline to **fetch** rather than fetching and then refusing. The
     * check itself does not need this — [verifyAndParse] refuses a blank pin on its own, and that
     * is the property that matters. What this saves is a request per refresh against a CDN whose
     * answer could not be trusted whatever it said, which on a device that never configures a key
     * is every request it would ever make.
     */
    val hasPinnedKey: Boolean
        get() = publicKeyHex.hexToBytesOrNull()?.isNotEmpty() == true

    /**
     * The catalog, or `null` when it is not ours, not readable, or empty of anything this build
     * can use.
     *
     * ⚠ `null` rather than a throw: this runs over bytes from a CDN, and "we could not trust that"
     * is an ordinary outcome that leaves the previously installed models exactly where they are.
     */
    fun verifyAndParse(catalogBytes: ByteArray, signature: ByteArray): ModelCatalogData? {
        val publicKey = publicKeyHex.hexToBytesOrNull() ?: return null
        // ⚠ An empty key is "no key configured" and must refuse, not accept — a verifier that
        // treats a blank pin as "skip the check" is the single most dangerous default here.
        if (publicKey.isEmpty()) return null
        if (!verifier.verify(publicKey, catalogBytes, signature)) return null

        val parsed = runCatching { parseModelCatalog(catalogBytes.decodeToString()) }.getOrNull()
            ?: return null
        if (parsed.schemaVersion > SUPPORTED_CATALOG_SCHEMA_VERSION) return null
        return parsed.copy(models = usableModels(parsed.models, supportedRuntimes = supportedRuntimes))
    }
}

/** Narrow signature-check seam matching `Ed25519Backend::verify` — non-throwing. */
fun interface SignatureVerifier {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/**
 * Keeps the entries this build can actually use.
 *
 * ⚠ An entry that serves **no** capability we know is dropped too: a future catalog naming a
 * capability this build has never heard of would otherwise download bytes nothing can consume.
 *
 * ⚠⚠ Three independent reasons to drop an entry, and they fail in the same direction on purpose:
 * an artifact format too new, a capability we do not know, or a **runtime we cannot execute**. All
 * three are allowlists, so a catalog that grows a field this build has never seen costs nothing
 * rather than costing a download.
 */
internal fun usableModels(
    models: List<ModelEntryData>,
    supportedArtifactVersion: Int = SUPPORTED_ARTIFACT_VERSION,
    supportedRuntimes: Set<String> = SUPPORTED_RUNTIMES,
): List<ModelEntryData> = models.filter {
    it.artifactVersion <= supportedArtifactVersion &&
        it.capabilities.isNotEmpty() &&
        // ⚠ An entry naming no files is not a download, it is a parse artefact — most likely an
        // older-shaped catalog read by this build. Dropping it keeps "an entry survived the filter"
        // meaning "this is installable".
        it.artifacts.isNotEmpty() &&
        // ⚠⚠ IN-D13's filter. Without it a device downloads weights nothing on it can run — the
        // failure is silent at download time and only appears as "the feature does nothing".
        it.runtime in supportedRuntimes
}

internal fun parseModelCatalog(json: String): ModelCatalogData {
    val doc = catalogJson.decodeFromString(CatalogJson.serializer(), json)
    return ModelCatalogData(
        schemaVersion = doc.schemaVersion,
        buildDate = doc.buildDate,
        models = doc.models.map { model ->
            ModelEntryData(
                modelId = model.id,
                name = model.name,
                modelVersion = model.version,
                // ⚠ Unknown capability names are dropped rather than failing the whole catalog: a
                // newer catalog naming a capability this build lacks must still deliver the ones
                // it does have.
                capabilities = model.capabilities.mapNotNull { name ->
                    InferenceCapability.entries.firstOrNull { it.id == name }
                },
                labelSetVersion = model.labelSetVersion,
                runtime = model.runtime,
                artifactVersion = model.artifactVersion,
                // ⚠ An artifact missing a path or a digest is dropped rather than carried as a
                // blank: the download would fail at the gate anyway, and an entry that survives
                // this parse should be one every field of which can be acted on.
                artifacts = model.artifacts
                    .filter { it.path.isNotBlank() && it.sha256.isNotBlank() }
                    .map {
                        ModelArtifactData(
                            role = it.role,
                            path = it.path,
                            sha256 = it.sha256,
                            byteSize = it.byteSize,
                        )
                    },
                licence = model.licence,
            )
        },
    )
}

/** Unknown keys are ignored so a newer catalog still parses on an older client. */
private val catalogJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class CatalogJson(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("buildDate") val buildDate: String = "",
    @SerialName("models") val models: List<ModelJson> = emptyList(),
)

@Serializable
private data class ModelJson(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("version") val version: Int,
    @SerialName("capabilities") val capabilities: List<String> = emptyList(),
    @SerialName("labelSetVersion") val labelSetVersion: Int = 0,
    @SerialName("runtime") val runtime: String = "",
    @SerialName("artifactVersion") val artifactVersion: Int = 1,
    @SerialName("artifacts") val artifacts: List<ArtifactJson> = emptyList(),
    @SerialName("licence") val licence: String = "",
)

@Serializable
private data class ArtifactJson(
    @SerialName("role") val role: String = "",
    @SerialName("path") val path: String = "",
    @SerialName("sha256") val sha256: String = "",
    @SerialName("byteSize") val byteSize: Long = 0,
)

private fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val high = this[i * 2].digitToIntOrNull(16) ?: return null
        val low = this[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}

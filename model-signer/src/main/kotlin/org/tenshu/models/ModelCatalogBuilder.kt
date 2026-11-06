package org.tenshu.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.tenshu.inference.InferenceCapability
import java.io.File

/**
 * Builds `catalog.json` for the model catalog (IN-2 / IN-D10) from a hand-written manifest plus the
 * artifact files themselves.
 *
 * ⚠⚠ **The manifest names artifacts; this computes their size and digest.** An operator writing a
 * sha256 by hand is an operator who will eventually write a stale one, and a stale digest is
 * indistinguishable at the client from a CDN serving the wrong bytes — it fails the integrity gate
 * with no way to tell which. So the manifest carries identity and policy (`id`, `capabilities`,
 * `runtime`, `licence`), and the bytes on disk carry `sha256` and `byteSize`.
 *
 * ⚠ A manifest artifact MAY state `sha256` and `byteSize`, and then they are a **pin, never the
 * value**: the catalog still carries what was computed, and a file that differs is refused. That is
 * for an artifact whose bytes were accepted by something outside this tool (the Core ML towers pass
 * a retrieval gate, and a re-export of the same checkpoint has other digests).
 *
 * ⚠ The emitted keys are exactly the ones `ModelCatalog.kt`'s private `CatalogJson` parses. That
 * parser sets `ignoreUnknownKeys = true`, so a typo'd key here is silently DROPPED rather than
 * rejected — which is why [ManifestJson] mirrors the client's required fields and this builder
 * fails on a missing artifact file rather than emitting an entry the client will quietly skip.
 */
class ModelCatalogBuilder(
    private val artifactRoot: File,
) {

    /** What the operator writes. Identity and policy only — never sizes or digests. */
    @Serializable
    data class ManifestJson(
        @SerialName("schemaVersion") val schemaVersion: Int = 1,
        @SerialName("models") val models: List<ManifestModel> = emptyList(),
    )

    @Serializable
    data class ManifestModel(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String = "",
        @SerialName("version") val version: Int,
        @SerialName("capabilities") val capabilities: List<String> = emptyList(),
        @SerialName("labelSetVersion") val labelSetVersion: Int = 0,
        @SerialName("runtime") val runtime: String = "",
        @SerialName("artifactVersion") val artifactVersion: Int = 1,
        @SerialName("licence") val licence: String = "",
        /** Where the weights came from. Optional: absent means nothing records it, not "unknown repo". */
        @SerialName("source") val source: ModelSource? = null,
        @SerialName("artifacts") val artifacts: List<ManifestArtifact> = emptyList(),
    )

    @Serializable
    data class ManifestArtifact(
        @SerialName("role") val role: String,
        /** Path under the artifact root, and the path the client GETs under the catalog base URL. */
        @SerialName("path") val path: String,
        /** ⚠ A pin, not the value: when stated, the file's computed digest must equal it. */
        @SerialName("sha256") val sha256: String = "",
        /** ⚠ A pin, not the value: when stated, the file's length must equal it. */
        @SerialName("byteSize") val byteSize: Long? = null,
    )

    /**
     * The upstream snapshot an artifact was exported from, copied into the signed catalog as written.
     *
     * ⚠ Provenance for a reader, not an input to any client: every shipped parser ignores unknown
     * keys, and none reads this one. It is signed so that what a device installed can be traced to
     * a commit, and it is optional because an entry whose origin nothing recorded must not invent one.
     */
    @Serializable
    data class ModelSource(
        @SerialName("repo") val repo: String,
        /** A commit, never a tag or a branch: those move. */
        @SerialName("revision") val revision: String,
        /** sha256 of each upstream file the export read, by file name. */
        @SerialName("files") val files: Map<String, String> = emptyMap(),
        /** The export's toolchain, as its manifest recorded it. */
        @SerialName("toolchain") val toolchain: JsonObject? = null,
        /** sha256 of the export's own manifest, which names the run the artifacts came from. */
        @SerialName("exportManifestSha256") val exportManifestSha256: String = "",
    )

    fun build(manifest: ManifestJson, buildDate: String): String {
        require(manifest.models.isNotEmpty()) {
            "the manifest names no models — publishing an empty catalog would tell every device " +
                "there is nothing to install, which is indistinguishable from the CDN being wrong"
        }
        checkManifest(manifest)
        val models = manifest.models.map { model ->
            require(model.artifacts.isNotEmpty()) {
                "model '${model.id}' names no artifacts; a checkpoint with no files is not installable"
            }
            require(model.id.isNotBlank()) { "a model entry has a blank id" }
            // ⚠⚠ A capability the client cannot name is DROPPED there, not rejected — and an entry
            // left with none is filtered out entirely (`usableModels`). So a single typo here
            // publishes a model that every device silently ignores, with nothing in any log to say
            // why. Caught at build time, where the fix is a character.
            val unknown = model.capabilities.filter { name -> name !in knownCapabilities }
            require(unknown.isEmpty()) {
                "model '${model.id}' names unknown capabilities $unknown — known: " +
                    "${knownCapabilities.sorted()}. The client drops names it cannot resolve, so " +
                    "this would publish an entry every device ignores."
            }
            require(model.capabilities.isNotEmpty()) {
                "model '${model.id}' names no capabilities; the client filters out such an entry"
            }
            CatalogModel(
                id = model.id,
                name = model.name,
                version = model.version,
                capabilities = model.capabilities,
                labelSetVersion = model.labelSetVersion,
                runtime = model.runtime,
                artifactVersion = model.artifactVersion,
                licence = model.licence,
                source = model.source,
                artifacts = model.artifacts.map { artifact ->
                    val file = File(artifactRoot, artifact.path)
                    // ⚠⚠ Fail here, not at the client. A missing file would otherwise publish an
                    // entry whose GET 404s on every device that tries to install it.
                    require(file.isFile) {
                        "artifact '${artifact.path}' for model '${model.id}' is not a file under " +
                            "${artifactRoot.absolutePath} — nothing was signed"
                    }
                    val sha256 = sha256File(file).toHex()
                    // ⚠⚠ The pins. Without them a re-export staged under the same name signs
                    // cleanly, and the catalog then serves bytes no gate ever saw.
                    // ⚠ Only the default empty string means "unpinned". Anything else, a blank or
                    // a mistyped value included, is a pin that does not match.
                    require(artifact.sha256.isEmpty() || artifact.sha256 == sha256) {
                        "artifact '${artifact.path}' for model '${model.id}' has sha256 $sha256, " +
                            "but the manifest pins ${artifact.sha256}. These are not the accepted " +
                            "bytes (a re-export has other digests) — nothing was signed"
                    }
                    require(artifact.byteSize == null || artifact.byteSize == file.length()) {
                        "artifact '${artifact.path}' for model '${model.id}' is ${file.length()} " +
                            "bytes, but the manifest pins ${artifact.byteSize} — nothing was signed"
                    }
                    CatalogArtifact(
                        role = artifact.role,
                        path = artifact.path,
                        sha256 = sha256,
                        byteSize = file.length(),
                    )
                },
            )
        }
        return json.encodeToString(
            CatalogJson.serializer(),
            CatalogJson(schemaVersion = manifest.schemaVersion, buildDate = buildDate, models = models),
        )
    }

    // ⚠ Mirrors `ModelCatalog.kt`'s private `CatalogJson`. It is private there — a build tool
    // cannot import it — so this is a deliberate second copy of the WIRE SHAPE. Change both.
    //
    // ⭐ The two ARE pinned together, just not from here: `SignedCatalogRoundTripTest`
    // (`:inference-service-proxy` jvmTest) builds a catalog with this class and parses it back
    // through the real `ModelCatalogVerifier`. It lives on the consumer side because
    // `parseModelCatalog` is `internal` — that source set takes an explicit `:model-signer`
    // dependency to reach both halves. So renaming a key here fails `jvmTest`, not only on a
    // device.
    //
    // ⚠⚠ That holds only because the test's fixture values avoid the consumer's DEFAULTS. Both
    // parsers set `ignoreUnknownKeys`, so a renamed key is dropped silently and the consumer
    // falls back — and a field whose fixture equals that default then round-trips "successfully"
    // while carrying nothing. `labelSetVersion` was exactly that until 2026-09-14: written as 2,
    // never asserted, and a rename would have read 0 with the test still green. `artifactVersion`
    // cannot be pinned that way at all (both sides default to 1, which is the only usable value),
    // so it is pinned through the ceiling filter instead — see that test's own note.
    //
    // ⚠ Adding a field here means adding an assertion there, with a fixture value the consumer
    // would never reach on its own. Otherwise the pin silently stops covering it.
    //
    // ⚠ `source` is the exception, on purpose: the client has no such field, so there is nothing to
    // mirror. The round trip asserts it is in the signed bytes and that the client still parses them.
    @Serializable
    private data class CatalogJson(
        @SerialName("schemaVersion") val schemaVersion: Int,
        @SerialName("buildDate") val buildDate: String,
        @SerialName("models") val models: List<CatalogModel>,
    )

    @Serializable
    private data class CatalogModel(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        @SerialName("version") val version: Int,
        @SerialName("capabilities") val capabilities: List<String>,
        @SerialName("labelSetVersion") val labelSetVersion: Int,
        @SerialName("runtime") val runtime: String,
        @SerialName("artifactVersion") val artifactVersion: Int,
        @SerialName("licence") val licence: String,
        // ⚠⚠ The ONLY key with a default, and it must stay `null`: `json` does not encode defaults,
        // so an entry without a source serialises to exactly the bytes it did before this key
        // existed. `ModelCatalogBuilderTest` pins those bytes.
        @SerialName("source") val source: ModelSource? = null,
        @SerialName("artifacts") val artifacts: List<CatalogArtifact>,
    )

    @Serializable
    private data class CatalogArtifact(
        @SerialName("role") val role: String,
        @SerialName("path") val path: String,
        @SerialName("sha256") val sha256: String,
        @SerialName("byteSize") val byteSize: Long,
    )

    companion object {
        private val json = Json { prettyPrint = true }

        /** The ids the client can resolve — read from the enum, never a second hand-kept list. */
        private val knownCapabilities: Set<String> =
            InferenceCapability.entries.map { it.id }.toSet()

        private val commit = Regex("[0-9a-f]{40}")
        private val digestHex = Regex("[0-9a-f]{64}")

        /**
         * The checks that need the manifest alone, so a test can run them over the tracked
         * `models.json` without the artifacts. [build] runs them first.
         *
         * ⚠⚠ **One model id may appear once per runtime** (`ios-on-device-inference.md` D13). The
         * same checkpoint is published once per runtime under one id, and a client keeps the
         * entries whose runtime it executes. Two entries a single client would both keep are the
         * failure: its downloader takes the first with a matching id and its store's index is
         * keyed by id, so the second is unreachable and nothing says so.
         */
        /**
         * The build manifest, parsed **strictly**: an unknown key is an error.
         *
         * ⚠⚠ A lenient parse drops a misspelt key, and the fields it would have set fall back to
         * their defaults. For a pin (`sha256`, `byteSize`) the default means *unpinned*: the
         * signer would hash whatever was staged and sign it, which is the check the pin exists
         * to make. The published catalog's readers stay lenient; this file is ours.
         */
        fun parseManifest(text: String): ManifestJson =
            strictManifestJson.decodeFromString(ManifestJson.serializer(), text)

        private val strictManifestJson = Json { ignoreUnknownKeys = false }

        fun checkManifest(manifest: ManifestJson) {
            val repeated = manifest.models.groupBy { it.id to it.runtime }.filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) {
                "the manifest names ${repeated.map { (id, runtime) -> "'$id' with runtime '$runtime'" }} " +
                    "more than once. A client keeps every entry whose runtime it executes and " +
                    "resolves a model by id alone, so one of the two could never be installed"
            }
            // ⚠⚠ Entries sharing an id are ONE checkpoint: a row is stamped with (id, version) and
            // nothing else, so two versions under one id would make the same stamp mean two models.
            manifest.models.groupBy { it.id }.forEach { (id, entries) ->
                val stamps = entries.map { it.version to it.labelSetVersion }.distinct()
                require(stamps.size == 1) {
                    "model '$id' is listed with differing (version, labelSetVersion) pairs $stamps. " +
                        "Entries sharing an id are one checkpoint under several runtimes and must " +
                        "carry one version; a different checkpoint needs its own id or a new version " +
                        "on every runtime"
                }
            }
            manifest.models.forEach { model ->
                val source = model.source ?: return@forEach
                require(source.repo.isNotBlank()) { "model '${model.id}' has a source with no repo" }
                // ⚠ A tag or a branch names whatever it points at today.
                require(commit.matches(source.revision)) {
                    "model '${model.id}' has source revision '${source.revision}', which is not a " +
                        "40-character commit. A tag or a branch moves; the catalog must name the snapshot"
                }
                val malformed = source.files.filterValues { !digestHex.matches(it) }.keys
                require(malformed.isEmpty()) {
                    "model '${model.id}' has source file hashes that are not sha256 hex: $malformed"
                }
                // ⚠ Optional, but when given it is what identifies the export run: a mistyped one
                // would be signed provenance nobody can resolve.
                val export = source.exportManifestSha256
                require(export.isNullOrEmpty() || digestHex.matches(export)) {
                    "model '${model.id}' has an exportManifestSha256 that is not sha256 hex: '$export'"
                }
            }
        }
    }
}

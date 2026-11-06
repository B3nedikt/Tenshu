package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.useContents
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.proxy.CompiledModels
import org.tenshu.inference.proxy.LoadRecording
import org.tenshu.inference.proxy.CompiledSweep
import org.tenshu.inference.proxy.CompiledTower
import org.tenshu.inference.proxy.AiModelFacts
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiStaticGates
import org.tenshu.inference.proxy.PreparedModel
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.onnx.ImageDecoder
import org.tenshu.inference.proxy.onnx.VisionModel
import platform.Foundation.NSProcessInfo

/**
 * The backend as Photos binds it: every path it opens, and whether it claims anything, come from
 * [readiness] (`ios-on-device-inference.md` §3.2, *Readiness*).
 *
 * ⚠⚠ The three locators read the readiness **snapshot**, not the install record and the marker
 * file. `supports` is on the per-image path, and a locator over the cache reads a file per call
 * (§10.6). It also means there is one answer to *which generation*: a tower this state does not
 * serve has no path here, so it cannot be opened.
 */
fun coreMlBackendOver(
    readiness: AiReadiness,
    readVocabulary: (String) -> ByteArray?,
    decoder: ImageDecoder = IosImageDecoder,
    open: (compiledPath: String, spec: CoreMlTowerSpec) -> CoreMlSessionPort =
        { compiledPath, spec -> CoreMlSession.open(compiledPath, spec) },
    beforeLoad: suspend () -> Unit = {},
    /** Which foreground of the scene an open begins in, and the time now: what its record carries. */
    foregroundNow: () -> Long? = { null },
    nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
): CoreMlInferenceBackend = CoreMlInferenceBackend(
    locate = { readiness.serving()?.visionPath },
    locateText = { readiness.serving()?.textPath },
    locateVocabulary = { readiness.serving()?.vocabularyPath },
    readVocabulary = readVocabulary,
    decoder = decoder,
    ready = readiness::serves,
    open = open,
    beforeLoad = beforeLoad,
    // ⚠⚠ Every open while `Ready` is recorded (§14.19): one Photos does not survive is counted.
    recordedOpen = { role, opening -> readiness.servingOpen(role, foregroundNow(), nowEpochMs(), opening) },
)

/** Opens a compiled tower. A graph binds one only to stand in for [CoreMlSession.open] in a test. */
fun interface CoreMlSessionOpener {
    fun open(compiledPath: String, spec: CoreMlTowerSpec): CoreMlSessionPort
}

/**
 * What [store] and [cache] hold of the pinned checkpoint, **as the disk has it**.
 *
 * ⚠⚠ Nothing here is taken from the install record alone. A record outlives its files (a purge
 * that was interrupted, a container restored without them), and a `Ready` built on a path that
 * leads nowhere claims a search whose every query then fails with the wrong refusal:
 * - a tower counts only with its ready marker for the **installed** digest and its compiled
 *   directory on disk ([CoreMlModelCache.compiledPath]);
 * - the vocabulary counts only when [files] holds a file of the recorded size at the recorded
 *   path. ⚠ Size, not digest: this runs at every launch and foreground. The bytes were verified
 *   against the signed catalog when the store committed them, and whether they still parse and
 *   tokenise is the preflight's question (D12 ②), asked before any verdict.
 *
 * @param files the database the model store keeps its bytes in.
 */
fun coreMlModelFacts(store: ModelStore, cache: CoreMlModelCache, files: FileDatabase): AiModelFacts {
    val checkpoint: InstalledModelData = store.installed().firstOrNull { it.modelId == VisionModel.PinnedSigLip.modelId }
        ?: return AiModelFacts(installed = false, prepared = null)
    val vision: String? = checkpoint.compiled(cache, ModelArtifactRole.VISION)
    val text: String? = checkpoint.compiled(cache, ModelArtifactRole.TEXT)
    val vocabulary: String? = checkpoint.artifacts.firstOrNull { it.role == ModelArtifactRole.VOCABULARY }
        // ⚠ A stat that throws is not a missing vocabulary: it propagates, and readiness reads
        // the whole of this as *unreadable* (not served, nothing written), not as `Preparing`.
        ?.takeIf { recorded -> files.fileSize(recorded.localPath) == recorded.byteSize }
        ?.localPath
    val prepared: PreparedModel? =
        if (vision != null && text != null && vocabulary != null) PreparedModel(vision, text, vocabulary) else null
    return AiModelFacts(installed = true, prepared = prepared)
}

private fun InstalledModelData.compiled(cache: CoreMlModelCache, role: String): String? =
    artifacts.firstOrNull { it.role == role }?.sha256?.let { digest ->
        // ⚠ A digest that is not a sha256 is the cache's `require`: no model, not a crash.
        // ⚠ That and nothing else: a marker that cannot be read is not a model that is not
        // prepared, and it propagates so that readiness reads *unreadable*.
        try {
            cache.compiledPath(role, digest)
        } catch (notADigest: IllegalArgumentException) {
            null
        }
    }

/** This phone, for [AiStaticGates]. Neither value changes while a process runs. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun iosAiDevice(): AiStaticGates.Device = autoreleasepool {
    val process: NSProcessInfo = NSProcessInfo.processInfo
    AiStaticGates.Device(
        osMajorVersion = process.operatingSystemVersion.useContents { majorVersion.toInt() },
        physicalMemoryBytes = process.physicalMemory.toLong(),
    )
}

/**
 * [CoreMlModelCache] as the prepare step drives it ([ModelPreparer], §14.17). ⚠ No logic: which
 * tower is compiled when, and what is swept, is the preparer's; the order of a tower's steps
 * and their cleanup is [CompiledCache]'s.
 */
class CoreMlCompiledModels(
    private val cache: CoreMlModelCache,
    private val model: VisionModel = VisionModel.PinnedSigLip,
) : CompiledModels {

    override fun tower(role: String, archiveDigest: String): CompiledTower = try {
        cache.tower(role, archiveDigest)
    } catch (notADigest: IllegalArgumentException) {
        // ⚠ A digest that is not a sha256 names no directory: nothing is compiled for it.
        CompiledTower.Unreadable("'$archiveDigest' is not a digest the compiled cache can name")
    }

    override suspend fun prepare(role: String, archivePath: String, archiveDigest: String, proceed: () -> Unit, loads: LoadRecording) {
        val spec: CoreMlTowerSpec = when (role) {
            ModelArtifactRole.VISION -> CoreMlTowerSpec.vision(model)
            ModelArtifactRole.TEXT -> CoreMlTowerSpec.text(model)
            else -> throw CoreMlPrepareException("'$role' is not a tower")
        }
        // ⚠⚠ [proceed] before EVERY step, the marker's included.
        cache.prepare(spec, archivePath, archiveDigest, onStep = { proceed() }, loads = loads)
    }

    override fun discard(archiveDigest: String) = cache.discard(archiveDigest)

    override fun sweep(liveDigests: Set<String>): CompiledSweep = cache.sweep(liveDigests)
}

/**
 * Why readiness does not read the installed checkpoint as prepared, by **the very reading**
 * [coreMlModelFacts] makes; `null` when it does, or when nothing is installed. What the prepare
 * step says of an install it cannot finish.
 */
fun coreMlNotPrepared(store: ModelStore, cache: CoreMlModelCache, files: FileDatabase): String? {
    val facts: AiModelFacts = coreMlModelFacts(store, cache, files)
    if (!facts.installed || facts.prepared != null) return null
    val checkpoint: InstalledModelData = store.installed().firstOrNull { it.modelId == VisionModel.PinnedSigLip.modelId } ?: return null
    val missing: List<String> = listOf(ModelArtifactRole.VISION, ModelArtifactRole.TEXT).filter { checkpoint.compiled(cache, it) == null }
    if (missing.isNotEmpty()) return "not compiled: $missing"
    return "the vocabulary's file is not there at its recorded size; downloading the model again fetches it"
}

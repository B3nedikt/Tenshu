package org.tenshu.inference.proxy

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.tenshu.core.common.proxy.FileDatabase

/**
 * The face pair's custody of **its own** runtime fingerprint (`ios-face-grouping.md` §14.4a, F5):
 * what a face readiness's `applyFingerprint` is bound to, and nothing else is.
 *
 * Templates whose making this build could not repeat are cleared before the face readiness enters
 * `Ready`: another embedder archive, another Vision revision, another face decode, another OS build
 * (FD9: Vision's points differ by platform). Same order as [InferenceStoreCustody]: clear, then write.
 *
 * ⚠⚠ **Never SigLIP's custody, and never SigLIP's file.** [InferenceStoreCustody.applyFingerprint]
 * clears the *vectors*; a face key's fingerprint handed to it would read as another vision archive
 * and clear every search vector, while leaving every template in place. So:
 * - this class clears the [FaceTemplateStore] and holds no [EmbeddingStore] at all;
 * - its [fingerprintPath] may not be SigLIP's ([InferenceStoreCustody.FINGERPRINT_PATH]);
 * - a fingerprint that is SigLIP's shape (a text archive, or no detector revision) is refused, and
 *   the templates are unusable until a face fingerprint is applied.
 *
 * ⚠ **Not the realm stamp.** Which realm the templates belong to (owner and `meta.epoch`) stays
 * [InferenceStoreCustody]'s, which already clears the templates on a change and gates `faceGroups`.
 * This answers only *"could this build have made them?"*.
 *
 * @param cancelPass stops a running pass and waits for it, before anything is cleared.
 */
class FaceTemplateCustody(
    private val files: FileDatabase,
    private val faces: FaceTemplateStore,
    private val cancelPass: suspend () -> Unit,
    val fingerprintPath: String = ReadinessSlot.FACES.fingerprintPath,
) {
    init {
        require(fingerprintPath != InferenceStoreCustody.FINGERPRINT_PATH) {
            "the face custody may not keep its fingerprint in SigLIP's file ($fingerprintPath)"
        }
    }

    private val lock = Mutex()

    /** Why the last apply failed, or `null`. ⚠ Until a later apply succeeds no template is usable. */
    @Volatile
    private var failure: Throwable? = null

    /** Whether the templates may be served: no apply has failed since the last that succeeded. */
    fun usable(): Boolean = failure == null

    /**
     * Records what the templates are made with, clearing them first when [current] could not have
     * made the ones held. ⚠ A missing sidecar clears: templates whose making is unknown cannot stay.
     */
    suspend fun applyFingerprint(current: RuntimeFingerprint): FingerprintOutcome = lock.withLock {
        if (current.textArchiveSha256 != null || current.detectorRevision == null) {
            // ⚠⚠ A SigLIP key was handed to the face custody: the binding is wrong. Nothing is
            // written over the face sidecar, and nothing is served until the right key comes.
            val wrong = IllegalArgumentException("not a face fingerprint: $current")
            println("FaceTemplateCustody -> refused: $wrong")
            failure = wrong
            return@withLock FingerprintOutcome.Failed(wrong)
        }
        val held: RuntimeFingerprint? = readFingerprint()
        if (held == current && failure == null) return@withLock FingerprintOutcome.Unchanged
        val clear: Boolean = held == null || !held.vectorsSurvive(current)
        try {
            if (clear) {
                cancelPass()
                faces.clear()
            }
            files.writeRawByteArray(fingerprintPath, json.encodeToString(RuntimeFingerprint.serializer(), current).encodeToByteArray())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refused: Throwable) {
            println("FaceTemplateCustody -> template clear failed, templates unusable: $refused")
            failure = refused
            return@withLock FingerprintOutcome.Failed(refused)
        }
        failure = null
        FingerprintOutcome.Applied(vectorsCleared = clear)
    }

    private fun readFingerprint(): RuntimeFingerprint? =
        runCatching { files.readBytes(fingerprintPath)?.get()?.decodeToString() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(RuntimeFingerprint.serializer(), it) }.getOrNull() }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

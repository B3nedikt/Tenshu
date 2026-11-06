package org.tenshu.inference.proxy

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tenshu.core.common.proxy.FileDatabase

/**
 * The realm a device-local inference store was built for.
 *
 * ⚠⚠ **[storeEpoch] is a seam, and nothing fills it yet.** The owner id alone cannot see a reset
 * that keeps the owner. The shared store's `meta.epoch` can (`ios-shared-store-sqlite.md` §3), and
 * it does not exist until S0b-1. Whoever supplies the realm then sets it; the first check after
 * that finds a stamp without one, which is a mismatch, and clears once.
 */
@Serializable
data class InferenceRealm(
    val ownerId: String,
    val storeEpoch: String? = null,
)

/**
 * What the stored vectors were made with, beyond what `ModelProvenance` carries
 * (`ios-on-device-inference.md` §3.2). Device-local and never synced.
 */
@Serializable
data class RuntimeFingerprint(
    /** The installed vision archive's sha256, or `null` with no model. */
    val visionArchiveSha256: String? = null,
    /** The installed text archive's sha256. Text vectors are not stored. */
    val textArchiveSha256: String? = null,
    val computeUnits: String,
    val osBuild: String,
    val preprocessingRevision: Int,
) {
    /**
     * Whether vectors made under [this] may stay beside vectors made under [current].
     *
     * ⚠ The OS build and the compute units clear too. §3.2's probe check, which could keep the
     * store across them, has no measured threshold yet; until it has one, clearing is the rule.
     */
    internal fun vectorsSurvive(current: RuntimeFingerprint): Boolean =
        visionArchiveSha256 == current.visionArchiveSha256 &&
            preprocessingRevision == current.preprocessingRevision &&
            osBuild == current.osBuild &&
            computeUnits == current.computeUnits
}

/** What [InferenceStoreCustody.applyFingerprint] did. */
sealed interface FingerprintOutcome {
    /** The sidecar already said this. */
    data object Unchanged : FingerprintOutcome

    /** The sidecar now says this. ⚠ A verdict keyed on the old one is void, whatever [vectorsCleared]. */
    data class Applied(val vectorsCleared: Boolean) : FingerprintOutcome

    /** The vectors could not be cleared, or the sidecar not written. The stores are unusable. */
    data class Failed(val cause: Throwable) : FingerprintOutcome
}

/**
 * Whether a process's inference stores may be read or written right now, asked where the plan
 * names a check: at the start of every pass and before every `photosLike`.
 */
fun interface InferenceStoreGate {
    /** @param fromPass see [InferenceStoreCustody.ensureCurrentRealm]. */
    suspend fun open(fromPass: Boolean): Boolean

    companion object {
        /**
         * For stores with no realm stamp: the one process that holds them clears them from its own
         * realm-wipe hook, so there is nothing to compare.
         */
        val Unstamped: InferenceStoreGate = InferenceStoreGate { true }
    }
}

/** Why the stores may or may not be read and written right now. */
sealed interface InferenceStoreState {
    /** No check has finished in this process. */
    data object Unchecked : InferenceStoreState

    /** The stamp names the current realm, this runtime serves it, and the fingerprint is current. */
    data object Usable : InferenceStoreState

    /** The device belongs to no realm anyone can name right now. Nothing was cleared. */
    data object NoRealm : InferenceStoreState

    /** The stores are stamped for the device's realm, and this runtime was assembled for another. */
    data object StaleRuntime : InferenceStoreState

    /** A clear or the stamp write failed. The old stamp stands, so the next check clears again. */
    data class ClearFailed(val cause: Throwable) : InferenceStoreState
}

/**
 * Keeps one process's inference stores honest about **which realm** and **which runtime** their
 * content came from (`ios-on-device-inference.md` §3.1, §3.2).
 *
 * ⚠⚠ Why a stamp at all. The stores are keyed by file path, and a path names a different
 * photograph in another realm. Where the stores are private to one app, a realm reset made
 * elsewhere reaches that app as nothing: no wipe hook runs in it. The stamp is how it finds out.
 *
 * ⚠⚠ **The stamp is written only after both clears return.** A clear that throws leaves the old
 * stamp, so the next check clears again; repeating a clear is harmless, and a stamp over uncleared
 * content is not.
 *
 * ⚠ Models are never touched here. They are public weights, the same for every realm.
 *
 * ⚠⚠ **Usable means both halves were checked, by one call, under one lock.** [ensureCurrentRealm]
 * is the whole check: the realm stamp, then the fingerprint. A query or a pass that reaches the
 * gate before any launch-time check has run therefore runs the whole check itself, and sees the
 * stores only after whatever it clears has been cleared. There is no state in which the realm has
 * been validated and the fingerprint has not.
 *
 * ⚠ [usable] is `false` until a check has finished, so an owner that binds this may construct it
 * off its launch path and nothing reads the stores in between.
 */
class InferenceStoreCustody(
    private val files: FileDatabase,
    private val embeddings: EmbeddingStore,
    private val faces: FaceTemplateStore,
    /** Stops a running pass and waits for it (`MediaIndexRunner.cancelRunningPass`). */
    private val cancelPass: suspend () -> Unit,
    /** The realm this device is on now, or `null` when that cannot be told. */
    private val currentRealm: () -> InferenceRealm?,
    /**
     * Whether this process's runtime was assembled for [currentRealm]'s answer. ⚠ A resident
     * runtime outlives a reset it did not see; its reads are the old realm's until it is rebuilt.
     */
    private val runtimeServes: (InferenceRealm) -> Boolean = { true },
    /**
     * What this build would make a vector with, asked on every check. `null` keeps no fingerprint
     * at all: the check is the realm stamp alone.
     */
    private val currentFingerprint: (() -> RuntimeFingerprint)? = null,
    private val stampPath: String = REALM_STAMP_PATH,
    private val fingerprintPath: String = FINGERPRINT_PATH,
) : InferenceStoreGate {

    override suspend fun open(fromPass: Boolean): Boolean = ensureCurrentRealm(fromPass)

    private val lock = Mutex()

    @Volatile
    private var realmState: InferenceStoreState = InferenceStoreState.Unchecked

    /** ⚠ Separate from [realmState]: a later realm check that passes must not hide it. */
    @Volatile
    private var fingerprintFailure: Throwable? = null

    private val osUpdateClear = MutableStateFlow(false)

    /**
     * Whether this process cleared the vectors because **the OS build changed** (and only that:
     * a held fingerprint whose OS build differs from the current one's), so that a screen can say
     * *the index was cleared after an iOS update* rather than a bare *0 of N*
     * (`ios-on-device-inference.md` §14.19.6). ⚠ In memory and process-local: the OS build only
     * changes across launches, so it is this launch's check that sets it. A later clear for any
     * other reason (a new model, a realm change) sets it back, since the update is then not
     * why the index is empty. ⚠ Never set by a clear that failed.
     */
    val clearedByOsUpdate: StateFlow<Boolean> = osUpdateClear.asStateFlow()

    /** Whether the stores may be read or written. A snapshot; [ensureCurrentRealm] is the check. */
    fun usable(): Boolean = realmState == InferenceStoreState.Usable && fingerprintFailure == null

    fun state(): InferenceStoreState =
        fingerprintFailure?.let { InferenceStoreState.ClearFailed(it) } ?: realmState

    /**
     * The whole check. Compares the stamp with the realm the device is on and clears on a
     * difference; then, with a [currentFingerprint], applies it, which clears the vectors it could
     * not have made. Usable only when both have returned.
     *
     * @param fromPass the caller IS the index pass, at its start. It is not cancelled: cancelling
     *   joins the pass, and a pass cannot join itself.
     * @return whether the stores are [usable] now.
     */
    suspend fun ensureCurrentRealm(fromPass: Boolean = false): Boolean = lock.withLock {
        val now: InferenceRealm? = currentRealm()
        if (now == null) {
            realmState = InferenceStoreState.NoRealm
            if (!fromPass) cancelPass()
            return@withLock false
        }
        if (readStamp() != now) {
            // ⚠ Before the clears, so nothing that reads [usable] sees the old answer meanwhile.
            realmState = InferenceStoreState.Unchecked
            try {
                if (!fromPass) cancelPass()
                embeddings.clear()
                faces.clear()
                osUpdateClear.value = false
                files.writeRawByteArray(stampPath, json.encodeToString(InferenceRealm.serializer(), now).encodeToByteArray())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                println("InferenceStoreCustody -> realm clear failed, stores unusable: $failure")
                realmState = InferenceStoreState.ClearFailed(failure)
                return@withLock false
            }
        }
        if (!runtimeServes(now)) {
            realmState = InferenceStoreState.StaleRuntime
            if (!fromPass) cancelPass()
            return@withLock false
        }
        // ⚠⚠ Before `Usable`, under the same lock: a caller that got here ahead of any launch
        // check must not be let through to vectors another archive, OS build or transform made.
        if (currentFingerprint != null) {
            val fingerprint: RuntimeFingerprint = try {
                currentFingerprint.invoke()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                println("InferenceStoreCustody -> fingerprint unreadable, stores unusable: $failure")
                fingerprintFailure = failure
                return@withLock false
            }
            applyFingerprintLocked(fingerprint, fromPass)
        }
        // ⚠ [usable] also reads the fingerprint's failure, so a failed apply above still answers false.
        realmState = InferenceStoreState.Usable
        usable()
    }

    /**
     * Records what the vectors are made with, clearing them first when [current] could not have
     * made the ones held. Same ordering as the stamp: clear, then write.
     *
     * ⚠ A missing sidecar clears. Vectors whose making is unknown cannot be kept beside new ones.
     */
    suspend fun applyFingerprint(current: RuntimeFingerprint): FingerprintOutcome =
        lock.withLock { applyFingerprintLocked(current, fromPass = false) }

    /** ⚠ Under [lock]. [fromPass] as in [ensureCurrentRealm]: a pass cannot cancel itself. */
    private suspend fun applyFingerprintLocked(current: RuntimeFingerprint, fromPass: Boolean): FingerprintOutcome {
        val held: RuntimeFingerprint? = readFingerprint()
        if (held == current && fingerprintFailure == null) return FingerprintOutcome.Unchanged
        val clear: Boolean = held == null || !held.vectorsSurvive(current)
        val before: InferenceStoreState = realmState
        try {
            if (clear) {
                // ⚠ Only when something is about to be cleared: [usable] is read per image, and a
                // check that changes nothing must not blink it.
                realmState = InferenceStoreState.Unchecked
                if (!fromPass) cancelPass()
                embeddings.clear()
            }
            files.writeRawByteArray(
                fingerprintPath,
                json.encodeToString(RuntimeFingerprint.serializer(), current).encodeToByteArray(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            println("InferenceStoreCustody -> fingerprint clear failed, stores unusable: $failure")
            fingerprintFailure = failure
            return FingerprintOutcome.Failed(failure)
        }
        fingerprintFailure = null
        realmState = before
        // ⚠ Why it was cleared, for the screen: the OS build alone is *an iOS update*.
        if (clear) osUpdateClear.value = held != null && held.osBuild != current.osBuild
        return FingerprintOutcome.Applied(vectorsCleared = clear)
    }

    /**
     * Gives the stores up: this device is leaving its realm.
     *
     * ⚠⚠ **The stamp goes first here, the reverse of [ensureCurrentRealm].** Removing it is one
     * small write, and with no stamp the next check clears before anything is usable. So a clear
     * that is interrupted or fails below is finished by the next launch.
     */
    suspend fun relinquish() = lock.withLock {
        realmState = InferenceStoreState.NoRealm
        // ⚠ Every step is tried: one store that will not clear must not keep the other's content.
        val failures: List<Throwable> = listOfNotNull(
            attempt { erase(files, stampPath) },
            attempt { cancelPass() },
            attempt { embeddings.clear() },
            attempt { faces.clear() },
        )
        failures.firstOrNull()?.let { throw it }
    }

    private suspend fun attempt(step: suspend () -> Unit): Throwable? = try {
        step()
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        failure
    }

    private fun readStamp(): InferenceRealm? = read(stampPath)?.let {
        runCatching { json.decodeFromString(InferenceRealm.serializer(), it) }.getOrNull()
    }

    private fun readFingerprint(): RuntimeFingerprint? = read(fingerprintPath)?.let {
        runCatching { json.decodeFromString(RuntimeFingerprint.serializer(), it) }.getOrNull()
    }

    private fun read(path: String): String? =
        runCatching { files.readBytes(path)?.get()?.decodeToString() }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val REALM_STAMP_PATH: String = "/inference/realm.stamp"
        const val FINGERPRINT_PATH: String = "/inference/runtime.fingerprint"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * [relinquish] for a process that holds no stores: the files themselves, by path.
         *
         * ⚠ Bounded whatever the logs hold: a few writes of nothing and as many unlinks, no read.
         */
        fun eraseAtRest(files: FileDatabase, logPaths: List<String>, stampPath: String = REALM_STAMP_PATH) {
            erase(files, stampPath)
            logPaths.forEach { erase(files, it) }
        }

        /**
         * ⚠ Emptied through the write that fails loudly, then unlinked: `deleteFile` reports
         * nothing, and both the stamp and a log read an empty file as absent.
         */
        private fun erase(files: FileDatabase, path: String) {
            if (files.fileSize(path) == null) return
            files.writeRawByteArray(path, ByteArray(0))
            files.deleteFile(path)
        }
    }
}

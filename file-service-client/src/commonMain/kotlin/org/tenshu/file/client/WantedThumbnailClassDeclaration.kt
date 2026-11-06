package org.tenshu.file.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.file.ThumbnailPreferences
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.client.readOnceForDevice
import org.tenshu.preference.client.setForDevice

/**
 * The row's value after declaring [classes] on top of [stored], or `null` when nothing changes
 * — pure, so the read-modify-write around it has exactly one decision to test.
 */
fun wantedThumbnailClassesAfterDeclaring(stored: List<String>, classes: Set<String>): List<String>? {
    val before = ThumbnailPreferences.declarable(stored)
    val after = ThumbnailPreferences.declarable(before + classes)
    return if (after == before) null else after.toList()
}

/**
 * Union [classes] into this device's `thumbnails.wantedClasses` row. Returns whether the row was
 * written — a class already declared, or one outside [ThumbnailPreferences.DECLARABLE_CLASSES],
 * writes nothing, so calling this at every launch puts no block on the wire in steady state.
 */
suspend fun PreferenceService.declareWantedThumbnailClasses(
    deviceId: String,
    classes: Set<String>,
    /** A caller's write boundary (ADR 0025 §8.6): runs before the write and throws to refuse it. ⚠ Required. */
    beforeWrite: suspend () -> Unit,
): Boolean {
    val stored = readOnceForDevice(ThumbnailPreferences.WantedClasses, deviceId)
    val next = wantedThumbnailClassesAfterDeclaring(stored, classes) ?: return false
    beforeWrite()
    setForDevice(ThumbnailPreferences.WantedClasses, next, deviceId)
    return true
}

/**
 * Declare at launch, best-effort, off the UI: waits for this device's identity (which a cold
 * start resolves a moment after the session opens), asks [classes] which rungs this app's screens
 * draw on *this* device, and unions them into the row. Fails silently — a declaration is
 * bookkeeping, and the proxy's pull-path half still runs.
 *
 * This is the **app-side** half of `drive-synced-thumbnails.md` §15.2's declaration — *"an app may
 * also declare at install; the proxy unions the two"* (§15.11).
 *
 * ⚠⚠ Why a pull-path declaration alone misses the class a device renders most: the proxy declares
 * a class the first time a client *pulls* the rung, and Photos pulls `File.ThumbnailXLarge` only on
 * a preview-cache **miss**. A phone that already holds the hero still for nearly every file never
 * misses, so the files without one stay without one on the very device that renders heroes daily
 * and holds their bytes. **A class rendered constantly from cache is pulled never, and declared
 * never.** This call says it at launch instead, from the one place that knows which rungs its
 * screens draw.
 *
 * ⚠ Class granularity only, like the row it writes: no file, no count, no time
 * ([ThumbnailPreferences.WantedClasses] carries the rule).
 *
 * @param deviceId this device's own id, or `null` while it has not resolved yet; polled every
 *   [DECLARE_RETRY_MS] for up to [DECLARE_TIMEOUT_MS].
 * @param classes the rungs to declare, decided with the resolved device id in hand (a per-device
 *   switch may gate one — Photos' *Play videos in the grid* gates `motion`).
 */
fun CoroutineScope.declareWantedThumbnailClassesAtLaunch(
    preferences: PreferenceService,
    deviceId: suspend () -> String?,
    /**
     * ADR 0025 §8.6 realm gate, as three seams because this module cannot depend on
     * `:app-reachability`: [awaitWritable] suspends until writes may land, [beforeWrite] refuses the
     * write while they may not (by throwing), and [retryAfter] says which throw is such a refusal —
     * after one, the declaration waits for the next release and runs again. ⚠⚠ All required, never
     * defaulted: a caller with no gate (the web runtime, iOS) passes open seams explicitly.
     */
    awaitWritable: suspend () -> Unit,
    beforeWrite: suspend () -> Unit,
    retryAfter: (Throwable) -> Boolean,
    classes: suspend (deviceId: String) -> Set<String>,
): Job = launch {
    while (true) {
        try {
            awaitWritable()
            val id = withTimeoutOrNull(DECLARE_TIMEOUT_MS) {
                var resolved = deviceId()
                while (resolved == null) {
                    delay(DECLARE_RETRY_MS)
                    resolved = deviceId()
                }
                resolved
            } ?: return@launch
            preferences.declareWantedThumbnailClasses(id, classes(id), beforeWrite)
            return@launch
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Bookkeeping never surfaces at launch; the proxy's pull-path declaration still runs.
            // ⚠ A realm-gate refusal is the one throw that is retried, after the next release.
            if (!retryAfter(t)) return@launch
        }
    }
}

/** How long a cold start is given to resolve the device id before the declaration is skipped for this launch. */
const val DECLARE_TIMEOUT_MS = 60_000L

/** Poll interval for the device id while it resolves. */
const val DECLARE_RETRY_MS = 2_000L

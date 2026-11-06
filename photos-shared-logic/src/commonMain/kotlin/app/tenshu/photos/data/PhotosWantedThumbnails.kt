package app.tenshu.photos.data

import app.tenshu.photos.settings.PhotosPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.getOrNull
import org.tenshu.file.client.declareWantedThumbnailClassesAtLaunch
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.readOnceForDevice
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus

/**
 * The thumbnail rungs Photos draws, and so asks every device to backfill (W-1,
 * `drive-synced-thumbnails.md` §15.11).
 *
 * ⚠⚠ Here rather than in a shell's DI module (PIO-0, `photos-ios.md` §1): the answer is the same on
 * every platform that runs this app, and before this move the only copy lived in the Compose
 * module, where the SwiftUI shell could not call it. Two copies of a list that decides which rungs a
 * realm mints is how one platform's viewer quietly ends up blurred.
 *
 * - `XLARGE` — ⚠⚠ the proxy's pull-path declaration cannot see it here: the viewer pulls it only on
 *   a preview-cache miss, and a warm phone never misses, so without this line the hero rung is never
 *   backfilled on the device that renders it daily.
 * - `XXLARGE` — ⚠⚠ X-3 (`drive-xxlarge-thumbnail-rung.md` §4.4): the viewer draws it. ⚠ Declaring is
 *   not pulling — only `viewerPreview` names it, never a tile or a feed row. `XLARGE` stays declared
 *   beside it as the fallback for a photograph no device minted the larger rung for.
 * - `MOTION` — only when this device both wants grid video and can play it; the same gate as the
 *   feed's rotation.
 */
fun photosWantedThumbnailClasses(playVideosInGrid: Boolean, motionPlaybackSupported: Boolean): Set<String> =
    buildSet {
        add(GetStoredThumbnail.CLASS_XLARGE)
        add(GetStoredThumbnail.CLASS_XXLARGE)
        if (playVideosInGrid && motionPlaybackSupported) add(GetStoredThumbnail.CLASS_MOTION)
    }

/**
 * Declare [photosWantedThumbnailClasses] for this device, once, at launch — the call every Photos
 * shell makes. [motionPlaybackSupported] is the shell's answer (Android `MediaPlayer`, desktop
 * AVFoundation, iOS `AVPlayer`), since only the shell knows what it can draw.
 */
fun CoroutineScope.declarePhotosWantedThumbnailClasses(
    client: TenshuClient,
    motionPlaybackSupported: Boolean,
    /**
     * The realm gate (ADR 0025 §8.6), resolved lazily — the shells call this while their client
     * is still being built. ⚠ Required: a shell with no realm probe (iOS) passes `{ null }` explicitly.
     */
    realmGate: () -> app.tenshu.reachability.ControlReachability?,
): Job = declareWantedThumbnailClassesAtLaunch(
    preferences = client.preferenceService,
    deviceId = {
        client.syncService.getSyncStatus().getOrNull()?.deviceId?.get()?.takeIf { it.isNotBlank() }
    },
    awaitWritable = { realmGate()?.awaitRealmWritable() },
    beforeWrite = { realmGate()?.refuseIfRealmWritesHeld() },
    retryAfter = { it is app.tenshu.reachability.RealmWritesHeldException },
) { deviceId ->
    photosWantedThumbnailClasses(
        playVideosInGrid = client.preferenceService.readOnceForDevice(PhotosPreferences.PlayVideosInGrid, deviceId),
        motionPlaybackSupported = motionPlaybackSupported,
    )
}

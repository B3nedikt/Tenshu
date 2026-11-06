package org.tenshu.core.file.sync

import org.tenshu.preference.LongAdapter
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope

/**
 * The synced per-device file-synchronisation settings
 * (`docs/proposals/drive-offline-online.md` §4) — declared in `:file-service`
 * (not an app) because they govern file-byte retention. Read/written through the
 * typed `FileSynchronisationSettings` surface in `:file-service-client`, and
 * surfaced read-only cross-device by Control (§4.6).
 *
 * Both are DEVICE-scoped (one row per device): each device keeps its own policy,
 * synced so other devices (and Control) can see it.
 */
object FileSyncPreferences {

    /**
     * This device's **default** sync policy (§4.2.1). Defaults to
     * [SyncPolicy.ONLINE_ONLY] — the §4.9 baseline, "online-only unless pinned". Stored as the self-describing
     * [SyncPolicy] constant itself (a `TConstant` in the polymorphic
     * `genericData` value slot — no codec).
     */
    val DefaultPolicy = Preference(
        id = "file.sync.defaultPolicy",
        scope = PreferenceScope.DEVICE,
        default = SyncPolicy.ONLINE_ONLY,
        adapter = SyncPolicyAdapter,
    )

    /**
     * This device's sparse, folder-granular, bidirectional policy overrides
     * (§4.2.2) — the exceptions to [DefaultPolicy]. Empty by default. Stored as a
     * self-describing `PathSyncOverrideSet` composite (§4.10), wrapped because the `genericData` value slot can't
     * hold a bare array.
     */
    val Overrides = Preference(
        id = "file.sync.overrides",
        scope = PreferenceScope.DEVICE,
        default = emptyList<PathSyncOverride>(),
        adapter = PathSyncOverrideSetAdapter,
    )

    /**
     * "Keep at most N bytes of **cached** file content on this device"
     * (`retention-quota-model.md` Face B / D6). Content over the ceiling is evicted
     * coldest-first by the offline keeper.
     *
     * ⚠⚠ **Scoped to evictable content: [SyncPolicy.KEEP_OFFLINE] files are neither
     * evicted to satisfy this nor counted against it.** The two go together —
     * counting bytes that cannot be spent makes the setting unsatisfiable the moment
     * pins exceed it (pin 50 GB under a 10 GB ceiling and the *evictable* cache is
     * wiped forever while the total stays 40 GB over, with no eviction able to close
     * the gap). So a device holds `pinned + at most this`, and bounding the total is
     * [MinFreeSpaceBytes]'s job. ⚠ Any UI for this must therefore say **cache** —
     * a label promising a total footprint we do not enforce is the worse failure.
     *
     * ⚠ **Defaults to `0` = no ceiling, and that is deliberate.** D6 calls for a
     * platform-tuned default, but a non-zero default would start silently deleting
     * cached content on existing installs without a screen to show the number or
     * change it. The always-on safety is [MinFreeSpaceBytes], which
     * addresses the actual harm (filling the user's disk); the explicit ceiling
     * turns on when its settings UI does.
     *
     * ⚠ **Never tier-coupled** (D6): the local cache is a *device* concern, and
     * coupling it to the storage plan would evict a downgraded user's data — the
     * durable-content-vs-cache conflation of `retention-quota-model.md` §2.
     */
    val MaxLocalBytes = Preference(
        id = "file.sync.maxLocalBytes",
        scope = PreferenceScope.DEVICE,
        default = 0L,
        adapter = LongAdapter,
    )

    /**
     * "Always leave at least N bytes free on this device" — the hard free-disk
     * floor of D6, and the half that is **on by default**. When the volume backing
     * the content cache drops below this, the keeper evicts coldest-first
     * (excluding pinned content) until it is satisfied, whatever [MaxLocalBytes]
     * says.
     *
     * ⚠ The floor is about the *device*, not about us: it fires even when our own
     * cache is small, because the correct response to a nearly-full disk is for a
     * cache to yield. Skipped entirely when the platform cannot report free space
     * (`FileDatabase.availableSpaceBytes() == null`) — unknown must not read as
     * zero.
     */
    val MinFreeSpaceBytes = Preference(
        id = "file.sync.minFreeSpaceBytes",
        scope = PreferenceScope.DEVICE,
        default = DEFAULT_MIN_FREE_SPACE_BYTES,
        adapter = LongAdapter,
    )

    /**
     * "Keep at most N bytes of **stored thumbnail classes above `LARGE`** on this device"
     * (`drive-xxlarge-thumbnail-rung.md` D-20) — a second, much smaller ceiling beside
     * [MaxLocalBytes], over a different plane.
     *
     * ⚠⚠ **Unlike [MaxLocalBytes], `0` is not "unlimited" and is not the default.** A file's cache
     * holds bytes the user asked for, so a default-on ceiling there would delete what they chose to
     * keep. These blobs are the opposite: pure re-fetchable derivatives no one asked for by name,
     * minted by an upload or a backfill, and a viewer swiping a large library accumulates them
     * without limit. So this one ships **on**, at 1 GiB, and `0` means "keep no cache" — a real
     * choice, not an unset state. A negative value is the escape hatch for "no ceiling".
     *
     * ⚠ `LIST` and `LARGE` are outside it entirely: rows re-blank without them, they are 64/48 KiB,
     * and they are minted at upload on every device rather than pulled.
     */
    val MaxThumbnailBytes = Preference(
        id = "file.sync.maxThumbnailBytes",
        scope = PreferenceScope.DEVICE,
        default = DEFAULT_MAX_THUMBNAIL_BYTES,
        adapter = LongAdapter,
    )

    /** 1 GiB — low enough that it only fires on a genuinely full device. */
    const val DEFAULT_MIN_FREE_SPACE_BYTES: Long = 1L * 1024 * 1024 * 1024

    /**
     * 1 GiB — about 2 000 `XXLARGE` blobs at ~0.5 MB, or ~7 000 `XLARGE` at ~139 KB: more than a
     * long viewing session, and a bound on the multi-gigabyte case a swiped library reaches without one.
     */
    const val DEFAULT_MAX_THUMBNAIL_BYTES: Long = 1L * 1024 * 1024 * 1024
}

package app.tenshu.drive.ui.common

import org.tenshu.core.file.sync.SyncPolicy

/**
 * The offline-state glyph a file row should show, or `null` for none. Two axes
 * feed it (`docs/proposals/drive-offline-online.md` §3–§4):
 *
 *  - **presence** (`hasLocalData`): are the bytes on local disk? `null` while
 *    the per-row probe is still in flight / not wired.
 *  - **intent** (`effectivePolicy`): the file's resolved [SyncPolicy] on this
 *    device — the longest-matching folder/path override, or the device default
 *    (§4.2). This replaced the v1/v2 boolean "is pinned" once the glyph started
 *    reading the effective rule (§4.5).
 */
enum class OfflineRowGlyph {
    /** Online-only and not on this device — purely cloud-resident. */
    OnlineOnly,

    /** Kept offline, but the bytes aren't here yet (will download). */
    OfflinePending,
}

/**
 * Decide a file row's offline glyph from presence + the effective sync policy.
 * Presence (v2) refines intent (v1/v3):
 *
 *  - bytes present (`true`)             → no glyph (it's here — kept-offline *or*
 *                                         merely opened-and-cached)
 *  - absent (`false`) + KEEP_OFFLINE    → [OfflineRowGlyph.OfflinePending]
 *  - absent (`false`) + ONLINE_ONLY     → [OfflineRowGlyph.OnlineOnly]
 *  - presence unknown (`null`)          → no glyph (neutral)
 *
 * The unknown state renders **neutral** rather than guessing a cloud
 * (`docs/proposals/drive-offline-online.md` §4.5): the earlier fallback drew the
 * "online only" glyph while the async probe was still in flight, so every row —
 * including a file you just created locally — flashed a cloud until its probe
 * resolved. The cloud now appears only once the probe *confirms* the bytes are
 * absent.
 *
 * Folders carry no presence glyph; callers must not invoke this for them.
 */
fun offlineRowGlyph(hasLocalData: Boolean?, effectivePolicy: SyncPolicy): OfflineRowGlyph? =
    when (hasLocalData) {
        true -> null
        false -> when (effectivePolicy) {
            SyncPolicy.KEEP_OFFLINE -> OfflineRowGlyph.OfflinePending
            SyncPolicy.ONLINE_ONLY -> OfflineRowGlyph.OnlineOnly
        }
        null -> null
    }

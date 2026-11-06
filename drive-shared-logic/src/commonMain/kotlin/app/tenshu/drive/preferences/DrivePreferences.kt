package app.tenshu.drive.preferences

import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.StringAdapter
import org.tenshu.preference.StringListAdapter

/**
 * Drive's preference declarations. App-specific preferences live in the owning
 * app's shared module rather than `:preference-service`
 * (`docs/proposals/core-preference-service.md` §3.8 / §8.1).
 *
 * The v3 per-device sync-rule settings (default policy + overrides) are **not**
 * here — they govern file-byte retention and live in `:file-service`
 * (`org.tenshu.core.file.sync.FileSyncPreferences`), read/written through the
 * typed `FileSynchronisationSettings` surface so Control can read them too (§4.6).
 */
object DrivePreferences {

    /**
     * Paths of the files the user has marked "keep offline" on *this* device — the
     * legacy v1/v2 pin set. DEVICE-scoped: each device pins its own set (storage
     * differs per device), but every paired device sees every device's row, so a
     * recovered/replaced device never silently loses its pins (§3.9).
     *
     * This is the `KEEP_OFFLINE`, exact-path special case of the file-service
     * overrides (`docs/proposals/drive-offline-online.md` §4.9); it stays a Drive
     * preference because it's driven by the Drive pin UI. The effective-rule
     * resolver folds these in as winning exact-path overrides
     * (`SyncRuleSet.withPinnedPaths`).
     */
    val OfflineFiles = Preference(
        id = "drive.offline.files",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )

    /**
     * ⚠ **Superseded by `OwnerPreferences.Name`** (`docs/proposals/fit-app.md` §18.2). The user's
     * own display name is the *user's*, not Drive's — Drive only owned it because Drive needed it
     * first, and Fit's Profile shows the same value. Nothing reads or writes this row any more;
     * `TenshuContactsRepository.ownDisplayName` moved to the platform row in FP-1.
     *
     * Kept as a declaration purely so the id stays claimed, and deliberately **not** migrated:
     * pre-release, the name is re-prompted inline on first use, so an abandoned row costs at most
     * one extra prompt — while two writable self-names would be exactly the divergence FP-1 exists
     * to remove. Delete once no installed build reads it.
     */
    @Deprecated("Use OwnerPreferences.Name — the owner's name is platform-level (fit-app.md §18.2)")
    val ContactDisplayName = Preference(
        id = "drive.contact.selfName",
        scope = PreferenceScope.REALM,
        default = "",
        adapter = StringAdapter,
    )
}

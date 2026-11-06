package app.tenshu.ingest

import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.StringListAdapter
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope

/**
 * Ingest policy that is **not** any one app's (`photos-app.md` PH-D2 / PH-D3).
 *
 * ⚠⚠ The reason this is not in `DrivePreferences`: the drain is Drive's, the screen that offers
 * the switch is Photos', and a preference declared in one app's module cannot be read by the
 * other. Two ids for one question is how a toggle comes to lie about what the engine is doing —
 * so the declaration lives where both can see it, exactly like `IngestSource` itself.
 */
object IngestPreferences {

    /**
     * *"Skip photos already in the realm"* — do not upload a file whose bytes are already here
     * (PH-D2). **Defaults on**, which is what both Google Photos and Apple Photos do at the door,
     * and is the setting that actually prevents duplicates rather than cleaning them up after.
     *
     * ⚠ DEVICE-scoped like every other backup setting, because it describes what *this* device's
     * drain does. A second device backing up the same camera roll makes its own decision — and
     * reaches the same one, since the index it consults is the shared realm.
     *
     * ⚠⚠ Distinct from the drain's two existing dedup layers, and it must stay distinct: the
     * per-bucket cursor stops a device re-offering an asset it already handled, and `seenUris`
     * stops one asset uploading once per selected album in a single run. Both are **device-local
     * asset identity**. This is the **cross-device content** layer — it is the only one of the
     * three that can tell that the photo your phone is about to upload is the one your laptop
     * uploaded last year.
     */
    val SkipExistingContent = Preference(
        id = "ingest.skipExistingContent",
        scope = PreferenceScope.DEVICE,
        default = true,
        adapter = BooleanAdapter,
    )

    // ── the auto-backup selection, moved out of Drive by PH-4b ───────────────────────────────
    //
    // ⚠⚠ **Every `id` below is unchanged, and must stay that way.** These are stored preference
    // rows on real devices; renaming `drive.backup.enabled` to something tidier would not migrate
    // anyone's settings, it would orphan them — every paired device would read the declared
    // default instead and quietly stop backing up. The `drive.` prefix is now a historical fact
    // about where the key was born, not a claim about who owns it.
    //
    // ⚠ They moved because Photos must be able to *add* a source (§14.4), and a source is
    // selected by writing these rows. The drain that reads them is now here too.
    // --- Synced Folders auto-backup (docs/proposals/drive-synced-folders.md §4.2) ---
    // All DEVICE-scoped: a bucket id / directory path is a device-local identity
    // (§3.1), so the selection policy never roams. (The uploaded *content* syncs
    // normally; only the selection is device-local.)

    /**
     * Master switch for automatic folder backup. Defaults **OFF** — auto-egress of
     * user content is a consent decision, so the user explicitly opts in (§3.7),
     * matching Google Photos / OneDrive / Dropbox.
     */
    val AutoBackupEnabled = Preference(
        id = "drive.backup.enabled",
        scope = PreferenceScope.DEVICE,
        default = false,
        adapter = BooleanAdapter,
    )


    /** The selected source buckets (opaque ids — `MediaStore` bucket id / desktop
     *  directory path). Once the master switch is on, image buckets default
     *  selected (§3.7); this records the user's explicit per-bucket choices. */
    val BackupBuckets = Preference(
        id = "drive.backup.buckets",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )


    /**
     * Control-owned desktop security-scoped folder grants. Kept separate from
     * [BackupBuckets] because disabling a folder must not forget the authorization
     * needed to offer it again later. DEVICE-scoped because the bookmark is valid
     * only for this device and app installation.
     */
    val BackupGrantedBuckets = Preference(
        id = "drive.backup.grantedBuckets",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )


    /**
     * SAF document-tree roots the user has added for backup (Phase 3b) — the
     * persisted `OPEN_DOCUMENT_TREE` URIs (e.g. WhatsApp Documents). Each entry is a
     * watched root for the `drive.document-tree` ingest source; they are this
     * source's selected "buckets". DEVICE-scoped like every other backup selection —
     * a SAF tree URI is a device-local grant that must not roam (§3.1). Android-only
     * (desktop folder backup is Control-hosted); empty elsewhere.
     */
    val DocumentTreeUris = Preference(
        id = "drive.backup.documentTreeUris",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )


    /**
     * Notify when a folder appears in the gallery that isn't backed up yet
     * (`drive-synced-folders-new-folder-prompt.md`). Defaults **ON**: the prompt is
     * how a user discovers that a new folder exists at all, and it is self-limiting
     * (each folder is announced at most once, ever).
     *
     * ⚠ This preference shipped earlier as an *auto-include* switch ("include new
     * folders without asking") that nothing ever read. It was re-pointed at the
     * prompt rather than implemented as written: silently auto-egressing a folder
     * the user has never seen contradicts the §3.7 consent stance that made the
     * master switch default-off in the first place.
     */
    val BackupNewFolders = Preference(
        id = "drive.backup.newFolders",
        scope = PreferenceScope.DEVICE,
        default = true,
        adapter = BooleanAdapter,
    )


    /**
     * Buckets the new-folder prompt has already accounted for — announced, answered,
     * backed up, or present when the baseline was first seeded (`scanForNewFolders`).
     * The prompt's entire anti-nag guarantee ("asked at most once, ever") is this set.
     *
     * DEVICE-scoped like every other backup selection: a `MediaStore` bucket id is a
     * device-local identity, and "which folders this phone has been asked about" is
     * meaningless on another device (§3.1).
     */
    val BackupKnownBuckets = Preference(
        id = "drive.backup.knownBuckets",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )


    /** Also back up videos. Defaults **OFF**: video is a bandwidth/quota cost
     *  choice, not a stability one (open-Q4 — images default-on, video opt-in). */
    val BackupVideos = Preference(
        id = "drive.backup.videos",
        scope = PreferenceScope.DEVICE,
        default = false,
        adapter = BooleanAdapter,
    )


    // "Back up over Wi-Fi only" is no longer a Drive-local preference — it folded
    // into the generic realm-scoped `ControlPreferences.WifiOnly` background-data
    // policy (drive-synced-folders-phase2.md §2), read/written via
    // `BackupPreferences.observeWifiOnly`/`setWifiOnly`.

    /** Only back up while charging (§3.8). Defaults off. */
    val BackupChargingOnly = Preference(
        id = "drive.backup.chargingOnly",
        scope = PreferenceScope.DEVICE,
        default = false,
        adapter = BooleanAdapter,
    )


    /**
     * The user pressed **Stop** on a running backup (ST-2b, `drive-upload-stop-and-reclamation.md`
     * D1). Distinct from [BackupEnabled] on purpose: *pause* and *off* are different sentences.
     *
     * ⚠⚠ Stop is a **pause**, not a cancel — it does not forget the queue, and unticking a folder
     * remains the only destructive gesture (D1). That is why this is its own key rather than
     * writing `enabled = false`: the latter would make Resume indistinguishable from re-enabling
     * backup, and would silently re-run the "backup was just turned on" paths.
     *
     * ⚠ DEVICE-scoped, so a pause does not travel to the user's other phone — stopping an upload
     * is a statement about *this* device's uplink, never a policy for the realm.
     */
    val BackupPausedByUser = Preference(
        id = "drive.backup.pausedByUser",
        scope = PreferenceScope.DEVICE,
        default = false,
        adapter = BooleanAdapter,
    )


    /**
     * Per-bucket upload cursors as an opaque encoded blob (§3.5 / open-Q2 — "blob
     * for v1"). Each entry is `bucketId␁cursor`; see `BackupCursors`. Pure local
     * progress, **not** synced semantics beyond riding the device row — re-deriving
     * it from realm contents is the rejected expensive alternative.
     */
    val BackupCursors = Preference(
        id = "drive.backup.cursors",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )
}

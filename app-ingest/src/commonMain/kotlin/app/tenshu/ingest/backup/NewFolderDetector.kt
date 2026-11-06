package app.tenshu.ingest.backup


/**
 * "A new folder appeared — back it up?" detection
 * (`docs/proposals/drive-synced-folders-new-folder-prompt.md`), the pure half.
 *
 * The feature every gallery-backup app ships (OneDrive, Google Photos, Dropbox) is
 * **not** filesystem awareness — it is a *diff of the media library's bucket set*
 * against a remembered baseline. Both OSes only let an app enumerate media buckets
 * without a per-folder grant, which is why every competitor's prompt is always
 * about `Camera` / `Screenshots` / `WhatsApp Images`: those are the only folders
 * visible to ask about. Android's half of that is [scanForNewFolders] + a
 * [NewFolderAnnouncer]; the SAF document-tree source is deliberately excluded (a
 * tree can only appear by the user granting it, so it is never a surprise — see
 * [BackupSource.announcesNewBuckets]).
 *
 * The design constraint that shapes everything here is **anti-nag**: an
 * unsolicited notification about the user's photo folders is the most annoying
 * thing this app can do. So a bucket is announced **at most once, ever** — the
 * scan folds every announced id into the persisted known-set immediately, before
 * the user answers. Ignoring the notification is therefore a permanent "no", and
 * only an explicit re-visit of the Synced Folders screen brings the folder back.
 */

/** Announce a bucket at most this many times per scan, so a burst (a device
 *  restore, a newly-installed messaging app) can't fire a wall of notifications.
 *  Buckets over the cap are left *out* of the known-set and picked up next scan
 *  rather than silently dropped. */
const val MAX_NEW_FOLDER_ANNOUNCEMENTS_PER_SCAN = 3

/** A bucket must hold at least this many items to be worth asking about. A folder
 *  with one or two files in it is usually transient scaffolding (an app writing a
 *  cache dir), and asking about it is pure noise. */
const val MIN_NEW_FOLDER_ITEMS = 3

/**
 * The result of one scan: which buckets to announce, and the known-set to persist.
 *
 * [known] is deliberately *not* "every bucket that exists" — a bucket below
 * [MIN_NEW_FOLDER_ITEMS] is left unknown so it is still announced once it grows
 * past the threshold, and buckets beyond [MAX_NEW_FOLDER_ANNOUNCEMENTS_PER_SCAN]
 * are left unknown so the next scan picks them up.
 */
data class NewFolderScan(
    val announce: List<MediaBucket>,
    val known: Set<String>,
)

/**
 * Diff the current [buckets] against the persisted [known] baseline.
 *
 * **Baselining (the load-bearing rule).** An empty [known] means "we have never
 * looked" — every existing install upgrading into this feature, and every fresh
 * one. Announcing then would fire a notification for every folder on the device,
 * so an empty baseline **seeds silently and announces nothing**. The accepted cost
 * is one corner: a device whose gallery is genuinely empty when backup is first
 * enabled will baseline the first bucket that appears (usually `Camera`, which the
 * setup flow default-selects anyway) instead of asking about it.
 *
 * [selected] — the buckets the user already backs up — is folded into the
 * known-set on every scan, so a folder in active use is never announced as "new".
 */
fun scanForNewFolders(
    buckets: List<MediaBucket>,
    known: Set<String>,
    selected: Set<String>,
    minItemCount: Int = MIN_NEW_FOLDER_ITEMS,
    maxAnnouncements: Int = MAX_NEW_FOLDER_ANNOUNCEMENTS_PER_SCAN,
): NewFolderScan {
    val ids = buckets.map { it.id }.toSet()

    // First look: seed the baseline, announce nothing.
    if (known.isEmpty()) return NewFolderScan(emptyList(), ids + selected)

    val announce = buckets
        .filter { it.id !in known && it.id !in selected && it.itemCount >= minItemCount }
        // Stable order so the cap is deterministic (and the biggest folder — the one
        // most likely worth backing up — is announced first).
        .sortedByDescending { it.itemCount }
        .take(maxAnnouncements)

    // Announced == known: the prompt fires once per folder whether or not the user
    // answers it. Sub-threshold and over-cap buckets stay unknown on purpose.
    return NewFolderScan(announce, known + selected + announce.map { it.id })
}

/**
 * The platform seam that actually surfaces the prompt. Android posts a
 * notification with *Back up* / *No thanks* actions; desktop binds nothing (the
 * Control-hosted watcher owns desktop ingest, and a desktop "folder appeared"
 * prompt has no equivalent OS gallery to diff against).
 */
interface NewFolderAnnouncer {
    suspend fun announce(bucket: MediaBucket)
}

/** Default binding — platforms without a prompt surface. */
object NoopNewFolderAnnouncer : NewFolderAnnouncer {
    override suspend fun announce(bucket: MediaBucket) = Unit
}

/**
 * Applies the user's answer to a prompt. Lives here (rather than inline in the
 * Android `BroadcastReceiver`) so the read-modify-write against the two
 * device-scoped preferences is unit-testable without an Android runtime.
 *
 * Both answers mark the bucket known — the scan already did that at announce time,
 * so these are idempotent repairs for the case where the user answers a
 * notification that outlived a reinstall.
 */
class NewFolderDecisions(private val preferences: BackupPreferences) {

    /** *Back up*: add the bucket to the backed-up selection. The next drain picks
     *  it up with a `null` cursor, i.e. it uploads the folder's current contents. */
    suspend fun accept(bucketId: String) {
        // One-shot: a read-modify-write must see the stored selection, not the flow's seed.
        val selected = preferences.readSelectedBuckets()
        if (bucketId !in selected) preferences.setSelectedBuckets(selected + bucketId)
        preferences.addKnownBuckets(setOf(bucketId))
    }

    /** *No thanks*: remember the folder so it is never announced again, without
     *  backing it up. */
    suspend fun dismiss(bucketId: String) {
        preferences.addKnownBuckets(setOf(bucketId))
    }
}

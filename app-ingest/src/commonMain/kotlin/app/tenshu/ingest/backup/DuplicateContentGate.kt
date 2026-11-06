package app.tenshu.ingest.backup

import app.tenshu.ingest.IngestSettings
import app.tenshu.ingest.RealmContentReads
import org.tenshu.core.common.TenshuClient

/**
 * The ingest-time duplicate skip (`photos-app.md` PH-D2 / PH-6): *"don't upload bytes that are
 * already in the realm."*
 *
 * ⚠⚠ This is the **third and last** dedup layer in the drain, and the three do genuinely different
 * jobs — conflating them is how one of them ends up removed as redundant:
 *
 * | layer | identity | stops |
 * |---|---|---|
 * | per-bucket cursor | device-local asset id | this device re-offering an asset it handled |
 * | `seenUris` | device-local asset id | one asset uploading once per selected album, in one run |
 * | **this** | **content hash** | **a photo another device already uploaded** |
 *
 * Only the third can tell that the photo your phone is about to upload is one your laptop uploaded
 * last year, and only the third needs to read the candidate's bytes to find out.
 */
interface DuplicateContentGate {
    /**
     * Open the gate for one drain, or return `null` when the skip is **switched off** — the one
     * case in which the drain uploads without asking.
     *
     * ⚠⚠ Not for "could not look" (`photos-app.md` §40). That used to return `null` too, and a
     * drain that could not read the realm then re-uploaded every candidate — content the realm
     * already held, billed twice, with nothing on screen saying so. A failed read now surfaces per
     * candidate as [ContentPresence.Unknown], which the drain defers.
     */
    suspend fun openForDrain(): DuplicateContentView?
}

/** What the realm says about one candidate's content. */
enum class ContentPresence {
    /** Some file in the realm — or an earlier candidate in this drain — carries it: skip. */
    Held,

    /** Nothing carries it: upload. The hash is remembered, so a second copy in this drain is [Held]. */
    New,

    /**
     * The realm could not be read. ⚠⚠ Neither skip nor upload: skipping would drop a photograph
     * the realm may not have, and uploading would re-bill one it may have. The drain defers it.
     */
    Unknown,
}

/**
 * The realm's answer to *"which of these content hashes do you hold?"*, or `null` when it could not
 * be read. [RealmContentReads.heldContentHashes] in production.
 */
fun interface HeldContentLookup {
    suspend fun held(hashes: Collection<String>): Set<String>?
}

/**
 * One drain's view of what content the realm already holds.
 *
 * ## ⚠⚠ It asks, it does not hold the realm
 *
 * It used to be built over a snapshot of every file in the realm — one reply, which past ~1 MB
 * (≈1,600 files) Binder does not deliver at all. It now asks the realm about the candidates the
 * drain is about to upload, in batches ([prefetch]), and remembers the answers for this drain.
 *
 * ⚠ Deliberately mutable and drain-scoped. Two identical assets in a single run must not both
 * upload, and the realm cannot know about the first one yet — so an accepted candidate is
 * remembered here, which is the same trick `seenUris` plays one layer up.
 */
class DuplicateContentView(private val lookup: HeldContentLookup) {

    /** hash → whether the realm holds it, for every hash the realm has answered about. */
    private val answered = mutableMapOf<String, Boolean>()
    private val acceptedThisRun = mutableSetOf<String>()

    /**
     * Ask the realm about every one of [hashes] not answered yet, in one lookup. A failed lookup
     * leaves them unanswered, and [check] asks again one at a time.
     */
    suspend fun prefetch(hashes: Collection<String>) {
        val ask = hashes.filter { it.isNotEmpty() && it !in answered && it !in acceptedThisRun }.distinct()
        if (ask.isEmpty()) return
        val held = lookup.held(ask) ?: return
        ask.forEach { answered[it] = it in held }
    }

    /**
     * What the realm — and this drain — says about [contentHash]. A [ContentPresence.New] answer
     * **records** the hash, so the second copy in one run answers [ContentPresence.Held].
     *
     * ⚠ A blank hash is [ContentPresence.New]: *"I could not identify it"* must never read as
     * *"I have it"*, and the upload then fails with a reason the user can act on.
     */
    suspend fun check(contentHash: String): ContentPresence {
        if (contentHash.isEmpty()) return ContentPresence.New
        if (contentHash in acceptedThisRun) return ContentPresence.Held
        if (contentHash !in answered) prefetch(listOf(contentHash))
        return when (answered[contentHash]) {
            null -> ContentPresence.Unknown
            true -> ContentPresence.Held
            false -> {
                acceptedThisRun += contentHash
                ContentPresence.New
            }
        }
    }
}

/** Skips nothing. The default everywhere the gate has not been wired (desktop, tests). */
object NoopDuplicateContentGate : DuplicateContentGate {
    override suspend fun openForDrain(): DuplicateContentView? = null
}

/**
 * The real gate: read the shared setting, then ask the realm per batch of candidates
 * ([RealmContentReads.heldContentHashes] — byte-bounded replies, `photos-app.md` §40).
 *
 * ⚠⚠ Falls back to [RealmContentReads.everyContentHash] when the batch question fails, once per
 * drain, and answers the rest of the drain from that set. A Control from before §40 cannot answer
 * `contentHash oneOf …` on any realm that has a folder — its predicate raised on the folder's absent
 * hash — and a drain that deferred every candidate against it would stop backing up altogether
 * until Control was updated. The fallback is the old cost class (every hash in the realm, once per
 * drain) but in bounded pages, so it answers where the old single reply did not.
 */
class TenshuDuplicateContentGate(
    private val client: TenshuClient,
    private val settings: IngestSettings,
) : DuplicateContentGate {

    override suspend fun openForDrain(): DuplicateContentView? {
        if (!settings.readSkipExistingContent()) return null
        var everyHash: Set<String>? = null
        return DuplicateContentView { hashes ->
            everyHash?.let { all -> return@DuplicateContentView hashes.filterTo(mutableSetOf()) { it in all } }
            RealmContentReads.heldContentHashes(client, hashes)?.let { return@DuplicateContentView it }
            val all = RealmContentReads.everyContentHash(client) ?: return@DuplicateContentView null
            everyHash = all
            hashes.filterTo(mutableSetOf()) { it in all }
        }
    }
}

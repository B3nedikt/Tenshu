package app.tenshu.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.share.Share
import org.tenshu.core.file.share.createdAtMillis
import org.tenshu.core.file.share.expiresAtMillis
import org.tenshu.core.file.share.fileId
import org.tenshu.core.file.share.maxOpens
import org.tenshu.core.file.share.revoked
import org.tenshu.core.file.share.revokeShareLink
import org.tenshu.core.file.share.shareId
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.listSharedFolder
import org.tenshu.core.fold
import org.tenshu.core.getOrNull
import org.tenshu.file.client.fileService
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.identity.client.identityService
import org.tenshu.identity.displayName
import org.tenshu.identity.getRealmMembers
import org.tenshu.identity.getSharedRealmMemberships
import org.tenshu.identity.joinedAt
import org.tenshu.identity.memberId
import org.tenshu.identity.role
import org.tenshu.sync.client.syncService
import org.tenshu.sync.removeSharedRealmMember

/**
 * Control's read+revoke surface over the realm's outbound shares — the data
 * client behind the **Data → Shares** subsection (`control-ia.md` §5.3).
 *
 * Two halves, one forward-compatible interface:
 *  - **Link shares.** [shares] projects the realm-wide
 *    `FileService.shares` collection ([Share] is `Syncable(Eager)`, so every
 *    realm device sees the same list); [revoke] calls the `RevokeShareLink` op
 *    (Eager-flips `revoked` + `DELETE /share/{shareId}` on the relay). This is
 *    the *inventory + revoke* dashboard — per-file *creation* of links stays in
 *    Drive (`docs/file-sharing.md` / `control-ia.md` §5.3).
 *  - **Shared folders.** [sharedFolders] / [members] / [removeMember] are the
 *    shared-**realm** surface (`tenshu-rei.md` §2.3 / §5.C). ⚠ **All three are
 *    implemented** over the membership-grant ops.
 *    ⚠⚠ But only [sharedFolders] has a **caller**: Control renders it as a read-only
 *    inventory row (name, member count, ownership), and nothing in Control on any
 *    platform calls [members] or [removeMember]. Control is not a member-management
 *    surface — Drive's *Manage access* is (`control-ia.md` §5.3). Whether it
 *    should become one is an open IA decision, not a missing screen:
 *    `ios-control-notifications-and-shares.md` §10.
 *
 * Sibling of [PublisherClient] / [SyncRepository] — a `:control-shared`-local
 * data client over [TenshuClient], not its own module.
 */
interface ShareManager {

    /**
     * Every outbound link share for the realm, **newest-first**. Revoked rows
     * are kept (unlike Drive's *My shares*, which filters them) so the Control
     * inventory can render a Revoked badge + history. Emits `emptyList()` on a
     * read failure (logged upstream); cold-starts empty until the first row
     * syncs.
     */
    fun shares(): Flow<List<ShareSummary>>

    /** Revoke a link share by id: Eager-flips `revoked` + `DELETE`s the relay record. */
    suspend fun revoke(shareId: String)

    /**
     * Shared **folders** the realm is in — owned (`role = owner`) and joined
     * (`role = member`) alike, newest information first read at collection time.
     *
     * Re-emits after [removeMember], because the underlying reads are one-shot ops
     * rather than a reactive projection (unlike [shares]): `getSharedRealmMemberships`
     * is a dedicated read op by design (`GetSharedRealmMemberships` KDoc — the same
     * rationale as `listPlacements` / `listSharedFolder`), so there is no chain-DSL
     * flow to converge on its own.
     */
    fun sharedFolders(): Flow<List<SharedFolderSummary>>

    /**
     * Members of a shared folder's realm — the Q25 pseudonymous `Member` roster
     * (`getRealmMembers`), the same source Drive's *Manage access* reads. Re-emits
     * after [removeMember]; see [sharedFolders] for why that is explicit.
     */
    fun members(realmId: String): Flow<List<SharedRealmMember>>

    /**
     * Remove a member from a shared folder's realm (`removeSharedRealmMember`).
     * **Owner-only** — it needs the realm master key.
     *
     * Throws on failure rather than folding it away: unlike [revoke], no reactive
     * projection would contradict a silent no-op, so a caller that saw no error would
     * show the member as removed when they are not.
     */
    suspend fun removeMember(realmId: String, memberId: String)
}

/**
 * One realm-wide outbound link share, projected from the `FileService.shares`
 * collection. [opensSoFar] is `null`: the open-count lives only on the
 * relay's `ShareRecord` and is not synced back into the `Share` entity, so the
 * inventory can't show it (the cap [maxOpens] is sharer-authored and *is*
 * synced). [expiresAtMillis] / [maxOpens] are `null` for "no expiry" / "no cap"
 * (the `0L` schema sentinel normalised away).
 */
data class ShareSummary(
    val shareId: String,
    val filePath: String,
    val fileName: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long?,
    val maxOpens: Int?,
    val opensSoFar: Int?,
    val revoked: Boolean,
)

/**
 * A shared folder the realm owns or has joined.
 *
 * ⚠ **[name] and [memberCount] are both nullable, and for different reasons — neither
 * is a placeholder.** A `SharedRealmMembership` row carries only
 * `(sharedRealmId, wrappedMasterKey, role)`; everything else is read per realm:
 *
 * - [name] is the realm's **wrapper-root `SharedFile` name**, which is `null`
 *   when that node has not synced in yet — the state a joiner is in for the first
 *   moments after accepting. Drive's *Shared with me* has the same field and renders
 *   `shared_with_me_unnamed`; Control does the same rather than inventing a label.
 * - [memberCount] is `null` when the **roster read failed for this realm** — deliberately
 *   not `0`. A folder with members that renders "0 members" is a wrong answer stated
 *   confidently; the row omits the count instead. (Compare
 *   `OfflineMapsSnapshot.catalogUnavailable`, which exists for exactly this
 *   can't-look vs nothing-there conflation.)
 */
data class SharedFolderSummary(
    val realmId: String,
    val name: String?,
    val memberCount: Int?,
    val ownerSelf: Boolean,
)

/** A member of a shared folder's realm — pseudonymous by default (Q25). */
data class SharedRealmMember(
    val memberId: String,
    val pseudonym: String,
    val role: String,
    val joinedAtMillis: Long,
)

/**
 * The plain-data projection of one membership row plus the two per-realm reads it needs,
 * so the join rule below stays pure and testable without a client — the same reason
 * `RegionFacts` is extracted in `OfflineMapsRepository`.
 */
internal data class SharedRealmFacts(
    val realmId: String,
    val role: String,
    /** The wrapper-root name, or null if it has not synced yet. */
    val wrapperName: String?,
    /** Roster size, or null if the roster read failed for this realm. */
    val memberCount: Int?,
)

/**
 * Projects a membership row + its per-realm reads into a row for the inventory.
 * Ownership is `role == owner` — the single-owner model (§5.C Q22), so the two roles
 * partition the list rather than overlapping.
 */
internal fun sharedFolderSummary(facts: SharedRealmFacts): SharedFolderSummary =
    SharedFolderSummary(
        realmId = facts.realmId,
        name = facts.wrapperName,
        memberCount = facts.memberCount,
        ownerSelf = facts.role == SharedRealmMembership.Role.OWNER,
    )

/**
 * Orders the inventory: **owned folders first**, then joined, each newest-name-stable
 * by realm id so the list does not reshuffle between reads (the rows carry no timestamp
 * — `SharedRealmMembership` has no `joinedAt`, only the `Member` roster does).
 */
internal fun orderSharedFolders(rows: List<SharedFolderSummary>): List<SharedFolderSummary> =
    rows.sortedWith(compareByDescending<SharedFolderSummary> { it.ownerSelf }.thenBy { it.realmId })

/**
 * Real [ShareManager] over the in-realm [TenshuClient]. Mirrors
 * `drive-shared`'s `TenshuFilesRepository.shares()` projection — but keeps
 * revoked rows and sorts newest-first for the Control inventory.
 */
class TenshuShareManager(
    private val client: TenshuClient,
) : ShareManager {

    override fun shares(): Flow<List<ShareSummary>> {
        return client.fileService.shares
            .select {
                fields(
                    Share.Field.SHARE_ID,
                    Share.Field.FILE_ID,
                    Share.Field.CREATED_AT_MILLIS,
                    Share.Field.EXPIRES_AT_MILLIS,
                    Share.Field.MAX_OPENS,
                    Share.Field.REVOKED,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { rows ->
                        rows.toList()
                            .map { entity ->
                                val filePath = entity.fileId.get()
                                ShareSummary(
                                    shareId = entity.shareId.get(),
                                    filePath = filePath,
                                    fileName = filePath.substringAfterLast('/'),
                                    createdAtMillis = entity.createdAtMillis.get(),
                                    expiresAtMillis = entity.expiresAtMillis.get()
                                        .takeIf { it != 0L },
                                    maxOpens = entity.maxOpens.get().toInt()
                                        .takeIf { it != 0 },
                                    // Relay-only; not synced into the Share entity.
                                    opensSoFar = null,
                                    revoked = entity.revoked.get(),
                                )
                            }
                            // Newest-first; stable order for ties.
                            .sortedByDescending { it.createdAtMillis }
                    },
                    onFailure = { emptyList() }
                )
            }
            .flowOn(Dispatchers.Default)
    }

    override suspend fun revoke(shareId: String) {
        // The op owns both the Eager local flip and the relay DELETE; the
        // result is folded away — the reactive `shares()` projection reflects
        // the flipped `revoked` flag, so the UI converges without a return value.
        client.fileService.defaultFileSystem.revokeShareLink(shareId)
    }

    /**
     * Bumped by [removeMember] so the one-shot roster reads below re-run. `shares()`
     * needs no equivalent — it is a chain-DSL projection that re-emits on its own.
     */
    private val rosterRevision = MutableStateFlow(0)

    override fun sharedFolders(): Flow<List<SharedFolderSummary>> =
        rosterRevision
            .map { orderSharedFolders(loadSharedFolders().map(::sharedFolderSummary)) }
            .flowOn(Dispatchers.Default)

    override fun members(realmId: String): Flow<List<SharedRealmMember>> =
        rosterRevision
            .map { rosterOrNull(realmId).orEmpty() }
            .flowOn(Dispatchers.Default)

    override suspend fun removeMember(realmId: String, memberId: String) {
        val result = client.syncService.removeSharedRealmMember(realmId, memberId)
        if (result is TResult.Failure) {
            // Not folded away (see the interface KDoc): with no reactive projection to
            // contradict it, a swallowed failure reads to the user as a removal.
            throw MemberRemovalFailedException(realmId, memberId, result.error)
        }
        rosterRevision.update { it + 1 }
    }

    /**
     * One membership row per shared realm, each enriched with the two per-realm reads
     * the summary needs.
     *
     * ⚠ **N+1 by construction, and bounded by design.** `getSharedRealmMemberships`
     * returns `(sharedRealmId, wrappedMasterKey, role)` and nothing else, so a name and
     * a member count cost one `listSharedFolder` + one `getRealmMembers` per realm.
     * That is fine for an inventory of shared folders (a handful) and would not be for a
     * file listing — if this ever needs to scale, the fix is a roster-count field on the
     * membership row, not a cache here.
     *
     * A failure to read the *memberships* yields an empty list (they are local +
     * Eager-synced, so failure is unlikely — Drive's `ownedRealmIds` takes the same
     * line). A failure on a *per-realm* read degrades that one row instead: an unsynced
     * wrapper root is a null name, an unreadable roster is a null count.
     */
    private suspend fun loadSharedFolders(): List<SharedRealmFacts> {
        val result = client.identityService.getSharedRealmMemberships()
        if (result !is TResult.Success) return emptyList()
        return (result.value?.toList() ?: emptyList()).map { membership ->
            val realmId = membership.sharedRealmId.get()
            SharedRealmFacts(
                realmId = realmId,
                role = membership.role.get(),
                wrapperName = wrapperName(realmId),
                memberCount = memberCount(realmId),
            )
        }
    }

    /**
     * The realm's roster, or **null when the read failed** — the distinction
     * [memberCount] needs and [members] flattens away. Kept in one place so the two
     * callers cannot drift on what an empty list means.
     */
    private suspend fun rosterOrNull(realmId: String): List<SharedRealmMember>? {
        val result = client.identityService.getRealmMembers(realmId)
        if (result !is TResult.Success) return null
        return (result.value?.toList() ?: emptyList()).map { member ->
            SharedRealmMember(
                memberId = member.memberId.get(),
                pseudonym = member.displayName.get(),
                role = member.role.get(),
                joinedAtMillis = member.joinedAt.get(),
            )
        }
    }

    /**
     * The realm's display name = the name of its wrapper-root `SharedFile`,
     * or null if that node has not synced in yet. Mirrors Drive's `wrapperName`.
     */
    private suspend fun wrapperName(realmId: String): String? =
        client.fileService.defaultFileSystem
            .listSharedFolder(realmId, SharedFile.ROOT_PARENT)
            .getOrNull()
            ?.toList()
            ?.firstOrNull()
            ?.name?.get()

    /** Roster size, or null when the read failed — never 0-on-failure (see [SharedFolderSummary]). */
    private suspend fun memberCount(realmId: String): Int? = rosterOrNull(realmId)?.size
}

/**
 * A `removeSharedRealmMember` op that failed — most often because this device is not the
 * realm owner (the op needs the realm master key) or the relay was unreachable for the
 * revocation post.
 */
class MemberRemovalFailedException(
    realmId: String,
    memberId: String,
    val error: TError,
) : RuntimeException("Removing member $memberId from shared realm $realmId failed: $error")

/**
 * In-memory [ShareManager] for screenshots, Compose previews, and screen tests
 * — no relay / proxy chain. [revoke] flips the row's `revoked` flag in place so
 * the Revoked-badge + optimistic-revoke UI can be exercised. Pass
 * `initial = emptyList()` to render the empty state.
 */
class MockShareManager(
    initial: List<ShareSummary> = SAMPLE,
    initialFolders: List<SharedFolderSummary> = SAMPLE_FOLDERS,
    initialMembers: Map<String, List<SharedRealmMember>> = SAMPLE_MEMBERS,
) : ShareManager {

    private val _shares = MutableStateFlow(initial.sortedByDescending { it.createdAtMillis })
    private val _folders = MutableStateFlow(orderSharedFolders(initialFolders))
    private val _members = MutableStateFlow(initialMembers)

    override fun shares(): Flow<List<ShareSummary>> = _shares.asStateFlow()

    override suspend fun revoke(shareId: String) {
        _shares.value = _shares.value.map {
            if (it.shareId == shareId) it.copy(revoked = true) else it
        }
    }

    override fun sharedFolders(): Flow<List<SharedFolderSummary>> = _folders.asStateFlow()

    override fun members(realmId: String): Flow<List<SharedRealmMember>> =
        _members.map { it[realmId].orEmpty() }

    /**
     * Drops the member and decrements the folder's count, so a preview exercises the
     * same converge-after-removal the real manager gets from its roster re-read.
     * Removing from a realm this mock does not know is a no-op, not a throw — the real
     * op's failure mode is an owner-only rejection, which a preview cannot model.
     */
    override suspend fun removeMember(realmId: String, memberId: String) {
        val roster = _members.value[realmId] ?: return
        _members.value = _members.value + (realmId to roster.filterNot { it.memberId == memberId })
        _folders.value = _folders.value.map {
            if (it.realmId == realmId && it.memberCount != null) {
                it.copy(memberCount = (it.memberCount - 1).coerceAtLeast(0))
            } else {
                it
            }
        }
    }

    companion object {
        /** A couple of representative rows — one capped, one expiring, one revoked. */
        val SAMPLE: List<ShareSummary> = listOf(
            ShareSummary(
                shareId = "sample-tax",
                filePath = "/Tax/2024/return.pdf",
                fileName = "return.pdf",
                createdAtMillis = 1_717_000_000_000L,
                expiresAtMillis = null,
                maxOpens = 5,
                opensSoFar = null,
                revoked = false,
            ),
            ShareSummary(
                shareId = "sample-photo",
                filePath = "/Photos/beach.jpg",
                fileName = "beach.jpg",
                createdAtMillis = 1_716_500_000_000L,
                expiresAtMillis = 1_719_000_000_000L,
                maxOpens = null,
                opensSoFar = null,
                revoked = false,
            ),
            ShareSummary(
                shareId = "sample-old",
                filePath = "/Docs/draft.txt",
                fileName = "draft.txt",
                createdAtMillis = 1_715_000_000_000L,
                expiresAtMillis = null,
                maxOpens = null,
                opensSoFar = null,
                revoked = true,
            ),
        )

        /**
         * One owned folder, one joined, and one still-unnamed joined realm — the third
         * is the joiner's first-moments state (the wrapper root has not synced yet), so
         * a preview renders the fallback label rather than only the happy path.
         */
        val SAMPLE_FOLDERS: List<SharedFolderSummary> = listOf(
            SharedFolderSummary(
                realmId = "realm-holiday",
                name = "Holiday photos",
                memberCount = 3,
                ownerSelf = true,
            ),
            SharedFolderSummary(
                realmId = "realm-band",
                name = "Band practice",
                memberCount = 2,
                ownerSelf = false,
            ),
            SharedFolderSummary(
                realmId = "realm-fresh",
                name = null,
                memberCount = null,
                ownerSelf = false,
            ),
        )

        val SAMPLE_MEMBERS: Map<String, List<SharedRealmMember>> = mapOf(
            "realm-holiday" to listOf(
                SharedRealmMember("member-self", "You", "owner", 1_715_000_000_000L),
                SharedRealmMember("member-jo", "Jo", "member", 1_716_000_000_000L),
                SharedRealmMember("member-sam", "Sam", "member", 1_716_400_000_000L),
            ),
            "realm-band" to listOf(
                SharedRealmMember("member-ali", "Ali", "owner", 1_714_000_000_000L),
                SharedRealmMember("member-self", "You", "member", 1_714_500_000_000L),
            ),
        )
    }
}

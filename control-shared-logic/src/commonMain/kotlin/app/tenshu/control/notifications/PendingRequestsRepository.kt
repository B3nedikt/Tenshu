package app.tenshu.control.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.contact.ContactInvite
import org.tenshu.contact.claimedFingerprint
import org.tenshu.contact.claimedName
import org.tenshu.contact.claimedSas
import org.tenshu.contact.client.contactService
import org.tenshu.contact.completeContactHandshake
import org.tenshu.contact.createdAt
import org.tenshu.contact.inviteId
import org.tenshu.contact.status
import org.tenshu.core.GenericError
import app.tenshu.error.ErrorCopyKey
import org.tenshu.core.TError
import app.tenshu.error.errorCopyKey
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.get
import org.tenshu.core.fold
import org.tenshu.core.notifications.DismissedRequest
import org.tenshu.core.notifications.dismissPendingRequest
import org.tenshu.core.notifications.requestId
import org.tenshu.notifications.client.notificationService
import org.tenshu.sync.acceptSharedRealmGrant
import org.tenshu.sync.client.syncService
import org.tenshu.sync.declinePendingGrant
import org.tenshu.sync.pollPendingGrants

/**
 * Control's central feed of inbound actionable requests
 * (`docs/proposals/control-central-notifications.md`, Phase 1). Surfaces, as one
 * reactive [requests] stream, the genuinely cold-inbound flows Control owns as the
 * realm/identity plane — incoming membership grants + redeemed contact invites —
 * which today are only read by Drive. Control reads them in-process via its own
 * [TenshuClient] over the contact/sync service clients (the realm-plane decision,
 * 2026-06-22).
 */
interface PendingRequestsRepository {
    /**
     * Inbound actionable requests, newest first; empty when there are none. Includes
     * requests the user has already **dismissed** (marked seen) — the notification
     * center lists them until they actually resolve. Cross-reference [seenIds] to
     * split unread from seen.
     */
    val requests: StateFlow<List<PendingRequest>>

    /**
     * Ids (`PendingRequest.id`) the user has dismissed/marked-seen on **any** paired
     * device (`control-central-notifications.md` Phase 4 — synced dismissal). A seen
     * request drops out of the unread badge + the pinned Home slot but stays in
     * [requests]; the marker is synced, so dismissing on one device clears the badge
     * on all of them.
     */
    val seenIds: StateFlow<Set<String>>

    /**
     * [requests] minus [seenIds] — the not-yet-dismissed requests. Drives every
     * **elevation** surface (the unread badge, the pinned Home slot, the OS
     * notifications): a synced dismissal drops the request here on every device, so
     * the badge clears and the Android/desktop OS notification is cancelled — while
     * the request stays in [requests] for the center.
     */
    val unread: StateFlow<List<PendingRequest>>

    /** Re-poll the poll-only sources (membership grants) — e.g. after acting on one. */
    fun refresh()

    /**
     * Mark [request] seen — records a synced [DismissedRequest] keyed by its
     * content-stable id (`dismissPendingRequest`), clearing it from the unread badge +
     * pinned slot on every device. Distinct from resolving it (accept/decline): a
     * dismissed-not-resolved request stays in the center, actionable.
     */
    suspend fun dismiss(request: PendingRequest): RequestActionResult

    /**
     * Accept the membership grant from this grantor (`acceptSharedRealmGrant`, GR-3) —
     * joins the shared realm. The grant leaves the feed once the poll re-runs.
     */
    suspend fun acceptGrant(request: PendingRequest.MembershipGrant): RequestActionResult

    /** Decline (discard) the quarantined grant (`declinePendingGrant`, GR-6). */
    suspend fun declineGrant(request: PendingRequest.MembershipGrant): RequestActionResult

    /**
     * Confirm a redeemed contact invite (`completeContactHandshake` ACCEPT, CI-4) —
     * creates the contact named [petname]. [sasConfirmed] reflects the user's §5.3
     * "the codes match" check: `true` mints a `VERIFIED`/`QR_SAS` identity, `false`
     * an `UNVERIFIED`/`INVITE_LINK` one (the SAS check upgrades trust, it does not
     * gate confirmation — mirrors Drive's `ContactPromptDialog`). v1 sends no
     * self-asserted own-name (Control has no own-name field yet — a follow-up).
     */
    suspend fun confirmInvite(
        request: PendingRequest.ContactInviteRedeemed,
        petname: String,
        sasConfirmed: Boolean,
    ): RequestActionResult

    /** Reject a redeemed contact invite (`completeContactHandshake` REJECT, CI-4). */
    suspend fun declineInvite(request: PendingRequest.ContactInviteRedeemed): RequestActionResult
}

/** Outcome of an action on a [PendingRequest]; [Failure] carries a display message. */
sealed interface RequestActionResult {
    data object Success : RequestActionResult
    /**
     * ⚠⚠ Carries a catalogue **key**, not a sentence — `core-error-copy-catalogue.md` §5.0. It
     * used to carry `GenericError.message`, i.e. whatever English the proxy wrote, which on
     * Android meant Control's process authoring copy that a feature app rendered.
     */
    data class Failure(val copyKey: ErrorCopyKey) : RequestActionResult
}

/**
 * [PendingRequestsRepository] over Control's in-process [TenshuClient]. Contact invites
 * are reactive (a synced `TList` streamed via the chain DSL); membership grants are
 * **poll-only** (`pollPendingGrants` is a suspend op), so they're polled on [scope]
 * every [pollIntervalMs] and merged with the invite stream. [scope] is process-lifetime
 * (a Koin `single`), so the feed watches for the whole session.
 */
class TenshuPendingRequestsRepository(
    private val client: TenshuClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val pollIntervalMs: Long = 30_000,
) : PendingRequestsRepository {

    private val grants = MutableStateFlow<List<PendingRequest>>(emptyList())

    private val redeemedInvites: Flow<List<PendingRequest>> =
        client.contactService.contactInvites
            .select {
                fields(
                    ContactInvite.Field.INVITE_ID,
                    ContactInvite.Field.STATUS,
                    ContactInvite.Field.CLAIMED_NAME,
                    ContactInvite.Field.CLAIMED_FINGERPRINT,
                    ContactInvite.Field.CLAIMED_SAS,
                    ContactInvite.Field.CREATED_AT,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { invites ->
                        invites.toList().mapNotNull { entity ->
                            contactInviteRequestOrNull(
                                status = entity.status.get(),
                                inviteId = entity.inviteId.get(),
                                claimedName = entity.claimedName.get(),
                                claimedFingerprint = entity.claimedFingerprint.get(),
                                claimedSas = entity.claimedSas.get(),
                                createdAtMillis = entity.createdAt.get(),
                            )
                        }
                    },
                    onFailure = { emptyList() },
                )
            }
            .flowOn(Dispatchers.Default)

    override val requests: StateFlow<List<PendingRequest>> =
        combine(grants, redeemedInvites) { grantRequests, inviteRequests ->
            aggregatePendingRequests(grantRequests, inviteRequests)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val seenIds: StateFlow<Set<String>> =
        client.notificationService.dismissedRequests
            .select { fields(DismissedRequest.Fields.REQUEST_ID) }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { dismissed -> dismissed.toList().map { it.requestId.get() }.toSet() },
                    onFailure = { emptySet() },
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override val unread: StateFlow<List<PendingRequest>> =
        combine(requests, seenIds) { all, seen ->
            all.filterNot { it.id in seen }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            while (isActive) {
                pollGrants()
                delay(pollIntervalMs)
            }
        }
    }

    override fun refresh() {
        scope.launch { pollGrants() }
    }

    override suspend fun dismiss(request: PendingRequest): RequestActionResult =
        client.notificationService.dismissPendingRequest(request.id).fold(
            onSuccess = { RequestActionResult.Success },
            onFailure = { error -> RequestActionResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun acceptGrant(request: PendingRequest.MembershipGrant): RequestActionResult =
        client.syncService.acceptSharedRealmGrant(request.grantorFingerprint).fold(
            onSuccess = { refresh(); RequestActionResult.Success },
            onFailure = { error -> RequestActionResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun declineGrant(request: PendingRequest.MembershipGrant): RequestActionResult =
        client.syncService.declinePendingGrant(request.grantorFingerprint).fold(
            onSuccess = { refresh(); RequestActionResult.Success },
            onFailure = { error -> RequestActionResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun confirmInvite(
        request: PendingRequest.ContactInviteRedeemed,
        petname: String,
        sasConfirmed: Boolean,
    ): RequestActionResult =
        client.contactService.completeContactHandshake(
            inviteId = request.inviteId,
            decision = CompleteContactHandshake.Decision.ACCEPT,
            // The raw fingerprint the card displayed (threat model T-CI-10): the proxy stores the
            // response only if it is this key, whatever the synced row says by now.
            shownFingerprint = request.claimedFingerprint,
            newPersonName = petname.ifEmpty { request.claimedName },
            sasConfirmed = sasConfirmed,
        ).fold(
            onSuccess = { RequestActionResult.Success },
            onFailure = { error -> RequestActionResult.Failure(error.completeHandshakeCopyKey()) },
        )

    override suspend fun declineInvite(request: PendingRequest.ContactInviteRedeemed): RequestActionResult =
        client.contactService.completeContactHandshake(
            inviteId = request.inviteId,
            decision = CompleteContactHandshake.Decision.REJECT,
            shownFingerprint = request.claimedFingerprint,
        ).fold(
            onSuccess = { RequestActionResult.Success },
            onFailure = { error -> RequestActionResult.Failure(error.errorCopyKey()) },
        )


    /** Whether the last poll threw: the throw is logged once per run of them, and so is the recovery. */
    @kotlin.concurrent.Volatile
    private var pollThrowing = false

    private suspend fun pollGrants() {
        // ⚠⚠ Caught: [scope] has no exception handler, and on K/N an uncaught throw there aborts the
        // process. The `fold` does not cover it — the first poll can be the call that mints the
        // in-process session, and a mint over iOS's not-ready stand-in (or a fenced store) throws
        // before any TResult exists. The next poll (30 s, or [refresh]) asks again.
        // ⚠ Only `println`: Control installs no `:diagnostics` sink, and the error-event channel is a
        // store write, which is exactly what the stand-in refuses.
        try {
            client.syncService.pollPendingGrants().fold(
                onSuccess = { pending ->
                    grants.value = pending.toList().map { grant ->
                        membershipGrantRequest(
                            grantorFingerprint = grant.grantorFingerprint.get(),
                            discoveredAtMillis = grant.discoveredAt.toEpochMilliseconds(),
                        )
                    }
                },
                onFailure = { /* keep last-known grants — a transient poll failure must not clear the feed */ },
            )
            if (pollThrowing) {
                pollThrowing = false
                println("TenshuPendingRequestsRepository: polling pending grants no longer throws")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!pollThrowing) {
                pollThrowing = true
                println("TenshuPendingRequestsRepository: polling pending grants threw (logged once until it recovers): $e")
            }
        }
    }
}

/**
 * The catalogue key for a failed `CompleteContactHandshake`.
 *
 * ⚠⚠ Not plain [errorCopyKey]: the two refusals of threat model T-CI-10 travel as
 * `InvalidParameter`, whose derived key is the generic sentence — and "the reply changed, decline
 * this invite and send a new one" is the one thing the user needs to be told. They are recognised
 * by type ([CompleteContactHandshake.Refusal]), never by reading a message.
 */
internal fun TError.completeHandshakeCopyKey(): ErrorCopyKey = when {
    CompleteContactHandshake.Refusal.isResponseChanged(this) -> ErrorCopyKey.INVITE_RESPONSE_CHANGED
    CompleteContactHandshake.Refusal.isCodeNotShown(this) -> ErrorCopyKey.INVITE_CODE_NOT_SHOWN
    else -> errorCopyKey()
}

@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.get
import org.tenshu.core.fold
import org.tenshu.identity.client.identityService
import org.tenshu.identity.contactFingerprint
import org.tenshu.identity.displayName
import org.tenshu.identity.getRealmMembers
import org.tenshu.identity.memberId
import org.tenshu.identity.role
import org.tenshu.sync.acceptSharedRealmGrant
import org.tenshu.sync.client.syncService
import org.tenshu.sync.declinePendingGrant
import org.tenshu.sync.grantSharedRealmMembership
import org.tenshu.sync.pollPendingGrants
import org.tenshu.sync.removeSharedRealmMember
import kotlin.js.Promise

/**
 * Web rendering of the cross-user shared-realm membership grant
 * (`drive-web-mode-b-sharing.md` MB-2 / GR-1..GR-7). The thin `@JsExport`
 * *operation* surface (House Rule #5): each call dispatches a `SyncService`
 * (grant/accept/poll/remove/decline) or `IdentityService` (`getRealmMembers`)
 * op through the in-process [JsRuntime] proxy — the same op code natives run.
 * No protocol change; the apex holds the keys, React owns state.
 *
 * The grant runtime (realm registry + per-realm-device-key relay) is wired by
 * the `SyncPlugin` (FirstPartyPlugins); without it these ops would resolve to
 * `null`. The *outbound* grant ([grant]) needs a shared realm to target, which
 * only exists once a folder is shared (MB-4) — so MB-2 ships the inbound side
 * ([pollPendingGrants] / [accept] / [decline]) + the [members] read in the UI,
 * with the full op surface available here for MB-3/MB-4 to consume.
 */
@JsExport
object Membership {

    /**
     * Grant connected contact [personId] membership of shared realm [realmId].
     * [label] is advisory (the folder name). Resolves true on success.
     */
    fun grant(realmId: String, personId: String, label: String): Promise<Boolean> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.syncService
                .grantSharedRealmMembership(realmId, personId, label)
                .fold(onSuccess = { true }, onFailure = { false })
        }

    /**
     * Picks up + accepts the grant waiting from [grantorFingerprint] (the GR-3 single-op
     * pickup+accept). Resolves `{ ok: true, realmId }` with the joined realm id, or
     * `{ ok: false, error }` (e.g. nothing shared at the derived address yet).
     *
     * ⚠ `null` only ever meant failure here (success always yields a realm id), so this carries
     * the reason rather than fixing an ambiguity — and a refused grant is exactly the case a
     * member needs told, since the remedy is on the grantor's side, not theirs.
     */
    fun accept(grantorFingerprint: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.syncService.acceptSharedRealmGrant(grantorFingerprint)
                .fold(
                    onSuccess = { realmId -> okResult { it.realmId = realmId.get() } },
                    onFailure = { error -> failureResult("grant", error.toString()) },
                )
        }

    /** The in-realm member roster of [realmId] (the Q25 `Member` records). */
    fun members(realmId: String): Promise<Array<MemberItem>> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.identityService.getRealmMembers(realmId).fold(
                onSuccess = { members ->
                    (members?.toList() ?: emptyList()).map { entity ->
                        MemberItem(
                            memberId = entity.memberId.get(),
                            displayName = entity.displayName.get(),
                            contactFingerprint = entity.contactFingerprint.get(),
                            role = entity.role.get(),
                        )
                    }.toTypedArray()
                },
                onFailure = { emptyArray() },
            )
        }

    /**
     * Remove member [memberId] from shared realm [realmId]: revokes them at the
     * relay (purges their devices) + tombstones their roster rows. Owner-only.
     */
    fun remove(realmId: String, memberId: String): Promise<Boolean> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.syncService.removeSharedRealmMember(realmId, memberId)
                .fold(onSuccess = { true }, onFailure = { false })
        }

    /**
     * Sweep grantable contacts' grant addresses for waiting grants — the
     * quarantined inbound set the "Shared with me" pending-grant cards render.
     */
    fun pollPendingGrants(): Promise<Array<PendingGrantItem>> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.syncService.pollPendingGrants().fold(
                onSuccess = { pending ->
                    (pending?.toList() ?: emptyList()).map { entity ->
                        PendingGrantItem(
                            grantorFingerprint = entity.grantorFingerprint.get(),
                            discoveredAtMillis = entity.discoveredAt.toEpochMilliseconds().toDouble(),
                        )
                    }.toTypedArray()
                },
                onFailure = { emptyArray() },
            )
        }

    /** Decline (discard) the quarantined grant from [grantorFingerprint]. */
    fun declinePendingGrant(grantorFingerprint: String): Promise<Boolean> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.syncService.declinePendingGrant(grantorFingerprint)
                .fold(onSuccess = { true }, onFailure = { false })
        }
}

@JsExport
class MemberItem internal constructor(
    /** `hash(memberAuthorityPub)` — the removal handle. */
    val memberId: String,
    val displayName: String,
    /** The member's A13 (empty when not a contact) — dedupes against a completed invite (D7). */
    val contactFingerprint: String,
    /** `owner` | `member`. */
    val role: String,
)

@JsExport
class PendingGrantItem internal constructor(
    val grantorFingerprint: String,
    val discoveredAtMillis: Double,
)

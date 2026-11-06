package org.tenshu.storage.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.identity.Entitlement
import org.tenshu.identity.EntitlementSignature

/** How often the owner-resolution backstop re-checks a still-unresolved owner (see below). */
private const val OWNER_RESOLVE_POLL_MS = 1_000L

/**
 * How often the relay-reported usage is re-fetched while a verified entitlement is held.
 *
 * ⚠⚠ **The gauge has no other way to move.** Usage changes when this device uploads, when it
 * reclaims, and when a *peer* does either — and not one of those writes an [Entitlement], which is
 * the only thing the reader's other triggers watch. Before this, `GET /sync/usage` was reachable
 * only from `refreshFigures`, so the number was fetched at proxy start and then frozen for the life
 * of the process: bytes freed by a delete still rendered as "5 GB of 5 GB" until Control restarted.
 *
 * ⚠ Sixty seconds bounds the staleness rather than chasing an event. A poll is the right shape here
 * precisely because a peer's upload produces **no local signal at all** — a content-plane callback
 * could cover this device's own writes and would still leave the gauge wrong on every other device
 * in the realm. Cost is one counter read per minute, against a relay the sync scheduler is already
 * polling far harder, and only while there is an entitlement to render a fill level against.
 */
private const val USAGE_REFRESH_INTERVAL_MS = 60_000L

/**
 * The **reader half** of the entitlement-derived storage figures
 * (storage-entitlement-sync.md slice 3), relocated out of `:control-shared`'s
 * `initEntitlementStorage` in web-storage-status.md Bucket 2 so both Control and
 * the web SDK derive figures from the synced [Entitlement] through the same code.
 *
 * A [DataObserver] recomputes [provider]'s figures from *any* [Entitlement] save
 * — written locally by the mock Publisher (the writer, still in `:control-shared`),
 * **or** arriving via the sync-merge path on a 2nd device — and on delete. Seeded
 * once from the existing entitlement so a restart (or a device that already pulled
 * the grant) starts correct. This is the cross-device payoff: the owner's
 * entitlement syncs in and the figures (hence the Drive banner / Tier-2 gate /
 * `StorageStatus`) flip on every device, with no local account.
 *
 * The writer (account flow → mint → `saveData` the `Entitlement`) stays in
 * `:control-shared` because it depends on the commerce-side `PublisherClient` /
 * `EntitlementMintClient`; on the web the entitlement arrives purely via sync,
 * so the reader alone is enough.
 */
fun ExecutionContext.initEntitlementStorageReader(
    provider: EntitlementStorageProvider,
    ownerId: () -> String?,
    now: () -> Long,
    // Track B3 (second-device-entitlement-enforcement.md option 4): the same current-entitlement
    // read the figures derive from also feeds the record-credential provider, so a session-less 2nd
    // device can present the synced signed record as `X-Entitlement`. `null` (web SDK / tests) skips
    // it — the reader's figures path is unchanged.
    recordSink: ((EntitlementSignature.SignedEntitlement?) -> Unit)? = null,
    // When provided, backstops every owner transition for the life of the session: poll this scope and
    // re-derive whenever `ownerId()` stops matching the owner the figures were last derived for. Covers
    // a locked/still-initialising start (the seed reads no entitlement, so `hasStorage` stays false
    // until the keys arrive) AND any later lock/unlock cycle. Without it, figures move only when an
    // incidental save trips the observer — which on an idle device may never come, leaving Services
    // showing "sign up" and Drive showing "Local only" though the synced entitlement is present and
    // readable. `null` (web SDK / tests) keeps the save-driven behaviour only.
    scope: CoroutineScope? = null,
    // retention-quota-model.md §5 / D5 (P1): the relay-reported per-owner usage seam — a best-effort
    // `suspend () -> Long?` (a `RelayUsageClient::fetchUsage` in Control's DI; the interface stays a
    // plain lambda so `:storage-service-proxy` needs no `:sync-service-proxy` dependency). When set and
    // a [scope] is available, each figure derivation kicks a background fetch that re-pushes the figures
    // with the real `usedBytes`, replacing the `0L` mock. `null` (web SDK / tests) leaves usage at 0.
    // P1b: now carries the relay-reported LIMIT alongside usage, so the authority that enforces
    // the quota is also the one that reports it. Returns `(usedBytes, limitBytes?)`; `limitBytes`
    // is null when the relay could not determine it.
    usageReader: (suspend () -> Pair<Long, Long?>?)? = null,
    // `entitlement-trust-anchor-distribution.md` D1: verify the record's publisher signature HERE,
    // on the read path, rather than at sync ingest. Two reasons this is the better place:
    //
    //  1. **A client update carrying a new key re-trusts already-stored rows with no re-sync.**
    //     Verifying at ingest meant a key change only ever applied to records arriving afterwards,
    //     so a fix shipped after a rotation did nothing for data already on the device.
    //  2. It closes the writer/reader asymmetry that made the 2026-08-07 incident oscillate: the
    //     writer stores records without verifying, so a bad record used to look fine until the
    //     next pull re-checked it. Checking on read means a key mismatch fails immediately and
    //     locally, at mint.
    //
    // ⚠ Returns true = "I could confirm this myself". `null` (web SDK / tests) skips verification
    // entirely, which is the historical behaviour.
    // ⚠⚠ Failing verification excludes the record from FIGURES ONLY — [recordSink] still receives
    // it, because the relay re-verifies against its own key and is the authority on whether it is
    // acceptable. Withholding it there would strand session-less paired devices on `402`.
    verifyRecord: ((EntitlementSignature.SignedEntitlement) -> Boolean)? = null,
    // Raised (once per transition, not per poll) when a stored record fails [verifyRecord].
    reportUnverified: ((publisherId: String) -> Unit)? = null,
) {
    fun entitlementId(owner: String): DataId.StringDataId =
        DataId.StringDataId.create(context = context, type = Entitlement.ID, keyValue = owner)

    fun storedEntitlement(): Entitlement? {
        val owner = ownerId() ?: return null
        return database.getData(entitlementId(owner)) as? Entitlement
    }

    /** Whether [record] carries a signature this client can confirm. No verifier wired ⇒ accept. */
    fun verified(record: Entitlement): Boolean {
        val verify = verifyRecord ?: return true
        val fields = EntitlementSignature.fieldsOf(record) ?: return false
        return verify(fields)
    }

    // The owner the figures were last derived for. The seed below runs at proxy-init
    // time, which on a locked-start device is *before* unlock — so `ownerId()` is null
    // and the lookup trivially finds nothing. Without re-deriving once the owner
    // appears, an entitlement already persisted from a prior session (no fresh save to
    // trip the observer) is never read and `hasStorage` stays stuck false, closing the
    // personal push gate. Tracking the last-derived owner lets the observer re-derive
    // on the owner null→known transition (the first save after unlock).
    var lastDerivedOwner: String? = null
    var hasDerivedOnce = false
    // Last relay-reported usage. Held so a figure re-derivation (e.g. an entitlement change)
    // keeps showing the real fill level instead of flickering back to 0 while a fresh fetch is
    // in flight; a successful fetch updates it and re-pushes. Best-effort — stays at 0 until the
    // first successful fetch (or forever, if no [usageReader] is wired: the web SDK / tests).
    var lastKnownUsage = 0L
    // The relay's last reported limit. ⚠ Held across derivations for the same reason as usage: a
    // re-derivation must not flicker the gauge back to the record-derived tier while a fresh fetch
    // is in flight.
    var lastKnownRelayLimit: Long? = null
    // When the next background usage fetch is due. Reset by every fetch, whichever path asked for
    // it, so a derivation-driven refresh and the interval below cannot stack up two calls.
    var nextUsageRefreshAtMs: Long = now() + USAGE_REFRESH_INTERVAL_MS
    // ⚠ Guards against pile-up, not against races. A fetch that hangs must not accumulate one more
    // in-flight request per interval behind it; the field is read and written only from the reader's
    // own scope, following the unsynchronised precedent the owner backstop below already sets.
    var usageFetchInFlight = false
    // Report the unverified state on TRANSITION only. ⚠ `refreshFigures` runs on a 1s poll, so
    // reporting per derivation would flood the error stream with one row per second — the failure
    // it is meant to make visible would bury everything else in the log.
    var reportedUnverifiedFor: String? = null
    // When a derivation denied storage *only* because the record is not yet valid, the instant it
    // becomes valid — so the backstop below can re-derive then.
    //
    // ⚠⚠ Without this a transient denial is PERMANENT. Figures move only on an entitlement save or
    // an owner transition, and neither recurs on an idle device: a record that was a second early
    // when it landed reads as "no storage" until the process restarts. That is the whole shape of
    // the 2026-08-08 incident, where a genuine 2 TiB grant showed as nothing until Control was
    // relaunched.
    //
    // ⚠ Deliberately scoped to the not-yet-valid case, which is the one denial that resolves on its
    // own at a *known* time. Expiry and failed verification are terminal — retrying them would spin
    // a DB read every second forever and never change the answer.
    var retryFiguresAtMs: Long? = null

    /**
     * Kick a background `GET /sync/usage` and re-push the figures if the answer moved.
     *
     * ⚠ Best-effort and non-blocking by design: usage is approximate and eventually consistent, so
     * a derivation must never wait on the network to render a limit it already knows. A failed or
     * offline fetch leaves the last known fill level standing rather than flickering the gauge to
     * zero.
     *
     * ⚠⚠ It re-reads the entitlement *after* the fetch rather than deriving against a captured
     * one: a save can land while the request is in flight, and pushing figures built from the record
     * as it was before that would undo the newer derivation.
     */
    fun kickUsageRefresh() {
        if (usageReader == null || scope == null) return
        // Reset the clock on every consideration, not only on the ones that fetch — an interval
        // that only advanced on success would retry once a second on a device with no entitlement.
        nextUsageRefreshAtMs = now() + USAGE_REFRESH_INTERVAL_MS
        // No point querying usage with nothing to render it against; and an unverified record must
        // not drive the figures (D1), so it must not drive a fetch for them either.
        if (storedEntitlement()?.takeIf { verified(it) } == null) return
        if (usageFetchInFlight) return
        usageFetchInFlight = true
        scope.launch {
            try {
                val (used, limit) = usageReader() ?: return@launch
                if (used != lastKnownUsage || limit != lastKnownRelayLimit) {
                    lastKnownUsage = used
                    lastKnownRelayLimit = limit
                    provider.update(
                        entitlementToFigures(
                            storedEntitlement()?.takeIf { verified(it) },
                            now(),
                            used,
                            limit,
                        ),
                    )
                }
            } finally {
                usageFetchInFlight = false
            }
        }
    }

    /**
     * @param clearRecordOnMiss when the read resolves no entitlement, whether that should also
     *   **clear** the record credential. Only an explicit entitlement *delete* passes `true`; see
     *   the credential note below for why every other caller must not.
     */
    fun refreshFigures(clearRecordOnMiss: Boolean = false) {
        lastDerivedOwner = ownerId()
        hasDerivedOnce = true
        val entitlement = storedEntitlement()

        // D1: a record this client cannot verify must not drive the figures. It is NOT discarded —
        // see the credential note below — it simply does not get to say how much storage you have.
        val usable = entitlement?.takeIf { verified(it) }
        if (entitlement != null && usable == null) {
            val publisher = EntitlementSignature.fieldsOf(entitlement)?.publisherId ?: "unknown"
            if (reportedUnverifiedFor != publisher) {
                reportedUnverifiedFor = publisher
                reportUnverified?.invoke(publisher)
            }
        } else if (usable != null) {
            reportedUnverifiedFor = null
        }
        val figures = entitlementToFigures(usable, now(), lastKnownUsage, lastKnownRelayLimit)
        provider.update(figures)

        // Arm the not-yet-valid backstop (see [retryFiguresAtMs]). Cleared on every other outcome,
        // so a record that later becomes valid — or is replaced, or expires — stops being retried.
        retryFiguresAtMs = if (!figures.hasStorage && usable != null) {
            usable.validFrom.get().takeIf { it > now() }
        } else {
            null
        }

        // ⚠ The credential is replaced only on a REAL read — a miss must never wipe a good one.
        //
        // This line used to mirror the read unconditionally (`recordSink(entitlement?.let { … })`),
        // which made every empty read *clear* the credential. Because `currentEntitlement()`
        // resolves by `ownerId()`, any transient null owner — a lock/unlock, a re-derivation
        // mid-initialisation — turned a momentary read miss into `402 entitlement_required` on
        // every push until the next successful refresh; while a realm stayed locked it held that
        // way indefinitely. That is the production incident in `docs/tech-debt.md`
        // ("Entitlement enforcement is untested end-to-end"): the relay logged
        // "NO credential presented" although a valid, owner-bound, in-window record sat in the
        // store the whole time.
        //
        // Keeping the last good record is safe *because the record carries its own proof*: it is
        // signed, owner-bound and windowed, and the relay re-checks all three on every write. So a
        // stale-but-still-valid credential is either accepted on its merits or refused by the relay
        // — never a false grant. Revocation is handled by the record's TTL (it stops being
        // re-minted), and a genuine local delete clears it through [clearRecordOnMiss] below.
        // The figures above deliberately keep mirroring the read: "no entitlement" is the correct
        // thing for the UI to show, even while the credential we already hold stays presentable.
        // ⚠⚠ Deliberately the STORED record, not the verified one: an unverifiable record is still
        // forwarded as `X-Entitlement-Proof`. The relay re-verifies against its own configured key,
        // so this cannot grant anything — while withholding it would leave a session-less paired
        // device write-dead on `402`, the exact failure `EntitlementRecovery` exists to prevent.
        if (entitlement != null) {
            recordSink?.invoke(EntitlementSignature.fieldsOf(entitlement))
        } else if (clearRecordOnMiss) {
            recordSink?.invoke(null)
        }

        // retention-quota-model.md P1: the fill level is fetched out of band and re-pushed once it
        // lands, so a derivation never blocks on the network.
        kickUsageRefresh()
    }

    addDataObserver(object : DataObserver {
        override fun onDataSaved(data: Data, origin: WriteOrigin) {
            // Re-derive on an entitlement change, OR the first time the owner becomes
            // known after a locked-start seed (figures were derived against a null owner
            // and an already-persisted entitlement would otherwise never be read).
            if (data.isEntitlement() || (!hasDerivedOnce || ownerId() != lastDerivedOwner)) {
                refreshFigures()
            }
        }

        override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
            // The one caller that MAY clear the credential: the entitlement is genuinely gone, not
            // merely unreadable right now.
            if ((dataId as? DataId.StringDataId)?.type?.get() == Entitlement.ID) {
                refreshFigures(clearRecordOnMiss = true)
            }
        }
    })
    refreshFigures()

    // Owner-transition backstop (see [scope]): poll the session scope and re-derive whenever the
    // owner differs from the one the figures were last derived for — the idiom SyncManagerImpl
    // documents (readers tolerate the async gap and self-heal on a tick). It covers *both* edges:
    //
    //  - null → known (unlock, or a locked start whose seed ran before the keys were available):
    //    closes the window where an idle device with no incidental save shows "sign up" though its
    //    synced entitlement is present.
    //  - known → null (lock): zeroes the figures promptly instead of waiting for a stray write.
    //
    // ⚠ This deliberately runs for the whole session rather than exiting after the first
    // resolution, as it originally did. The one-shot form was armed only when the *initial*
    // derivation saw a null owner, so a lock/unlock cycle later in the same process had nothing
    // watching: the observer's `ownerId() != lastDerivedOwner` test only fires when some *other*
    // write happens to occur, and an idle Control after a biometric unlock produces none. The
    // figures then stayed false while the realm was unlocked and entitled, and Drive rendered
    // "Local only" until an unrelated save silently repaired it (hence: intermittent).
    //
    // Cost is a field read plus a comparison per second; `refreshFigures()` (which hits the DB)
    // runs only on an actual change, and it is self-deduping against the observer path because it
    // updates `lastDerivedOwner` itself.
    if (scope != null) {
        scope.launch {
            while (isActive) {
                delay(OWNER_RESOLVE_POLL_MS)
                val retryAt = retryFiguresAtMs
                if (ownerId() != lastDerivedOwner) {
                    refreshFigures()
                } else if (retryAt != null && now() >= retryAt) {
                    // The record has reached its start instant — re-derive so a grant that landed
                    // fractionally early isn't stuck denied for the life of the process.
                    refreshFigures()
                } else if (now() >= nextUsageRefreshAtMs) {
                    // ⚠⚠ The only thing that moves the gauge after a byte changes hands. Uploads,
                    // reclamation and every peer's writes all alter this owner's usage without
                    // touching an [Entitlement], so none of the branches above ever fires for them
                    // — see [USAGE_REFRESH_INTERVAL_MS]. Deliberately last: a pending owner
                    // transition or a maturing record both re-derive, and each kicks a fetch itself.
                    kickUsageRefresh()
                }
            }
        }
    }
}

private fun Data.isEntitlement(): Boolean = when (this) {
    is Entitlement -> true
    is Entity<*> -> type.get() == Entitlement.ID
    else -> false
}

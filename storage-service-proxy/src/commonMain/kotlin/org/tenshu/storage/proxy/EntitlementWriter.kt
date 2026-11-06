package org.tenshu.storage.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.identity.Entitlement
import org.tenshu.identity.EntitlementSignature

/**
 * How often [initEntitlementWriter]'s renewal tick re-checks the current record while a session is
 * available. [mintEntitlement] only actually re-mints once the record passes the halfway point of its
 * own window (see there), so this cadence just bounds how promptly a renewal fires after that point —
 * a few hours is ample for a days-long TTL, and a device coming online also re-checks immediately.
 */
private const val DEFAULT_RENEW_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 h

/** How often the owner-resolve backstop re-checks a still-unresolved owner (mirrors the reader's). */
private const val OWNER_RESOLVE_POLL_MS = 1_000L

/**
 * What to mint for the owner: the resolved `productId` plus the optional gating
 * `email` (a whitelist-gated commerce minter needs it; the in-process dev minter
 * ignores it). The writer's [PublisherAccount]-free input (web-storage-status.md
 * Bucket 3) — Control maps its `PublisherAccount` to this; the web apex emits a
 * default-free intent on genesis.
 */
data class EntitlementMintIntent(
    val productId: String,
    val email: String?,
)

/**
 * Mint + upsert the owner's [Entitlement] into the realm (the **writer** half,
 * relocated out of `:control-shared` in web-storage-status.md Bucket 3).
 *
 * Writes when the product is absent, **changed**, or the existing record is **due for renewal** —
 * past the halfway point of its own `validFrom→validUntil` window ([isPastRenewalPoint]). Renewal
 * fetches a fresh, later-`validUntil` record from [mintClient] while the account is in good standing,
 * so a long-lived good-standing account's record never silently expires and a session-less peer's
 * proof stays valid; if commerce declines (revoked / lapsed), the record simply ages out and
 * enforcement takes over — bounding the forced-revocation window to ~the renewal cadence (T-CB-08).
 *
 * A still-valid record that grants **more** than the intent is never replaced by a lower one here.
 * Before its renewal point it is left alone without asking [mintClient]; past it, [mintClient] is
 * asked (commerce answers from the session's ledger, not from the requested product) and the answer
 * is written only if it keeps at least the record's quota AND extends its `validUntil` — otherwise
 * the higher record runs out on its own window.
 *
 * Otherwise idempotent, so identical intents / restarts / a fresh record cause **no sync churn**
 * (deriving the threshold from the record's own window self-limits renewal to ~once per half-window).
 * A `null` mint (not whitelisted / service unreachable) is a deliberate **no-op** — never a delete of
 * an entitlement another of the owner's devices authored. Returns `true` iff a record was written.
 */
suspend fun ExecutionContext.mintEntitlement(
    ownerId: String,
    productId: String,
    email: String?,
    mintClient: EntitlementMintClient,
    nowMs: Long,
): Boolean {
    val id = DataId.StringDataId.create(context = context, type = Entitlement.ID, keyValue = ownerId)
    val existing = database.getData(id) as? Entitlement
    // Same product and not yet due for renewal → nothing to do.
    if (existing != null && existing.productId.get() == productId && !existing.isPastRenewalPoint(nowMs)) {
        return false
    }
    // ⚠⚠ An AUTOMATIC re-mint must never cut short a still-valid higher grant.
    //
    // Observed 2026-08-08: a `FOUNDER-1` redemption for `tenshu.drive.premium` was consumed at
    // commerce, yet the device sat on Free with no Premium anywhere. Two writers author this one
    // row — this account-driven mint and `initRedeemedEntitlementWriter` — and they have no notion
    // of each other, so last-write-wins. `productIdForPlan` maps the account plan, whose enum is
    // only Free|Rei and CANNOT EXPRESS Premium, so the mint asks for Free; `saveSignedEntitlement`
    // writes on any product change; and the renewal tick re-drives the mint on a timer. The
    // automatic path therefore always wins eventually, silently undoing what the user explicitly
    // redeemed — seconds later, with nothing reported.
    //
    // The rule below is the one this system already states elsewhere: "revocation is handled by the
    // record's TTL (it stops being re-minted)" (`EntitlementStorageReader`). A downgrade lands when
    // the higher record EXPIRES, not by truncating a window commerce itself chose. Honouring that
    // window is not an over-grant — the issuer decided its length.
    //
    // ⚠ Scoped to the automatic path on purpose. An explicit redeem still writes whatever commerce
    // signed, including a lower tier: a user action being silently ignored is the very failure this
    // fixes, and reproducing it in the other direction would be no better.
    //
    // ⚠ Past its renewal point the higher record is offered for renewal instead: the production minter
    // ignores the requested product, so its answer may be the same paid tier. That answer is kept only
    // if it takes no storage away and lasts longer; a lower answer (the dev minter echoes Free) is not.
    val higher = existing?.takeIf { it.grantsMoreThan(productId, nowMs) }
    if (higher != null && !higher.isPastRenewalPoint(nowMs)) {
        return false
    }
    val signed = mintClient.mint(ownerId = ownerId, email = email, productId = productId)
        ?: return false
    if (higher != null && !signed.keepsAtLeast(higher)) {
        return false
    }
    return saveSignedEntitlement(signed)
}

/**
 * Whether this record is still in its validity window AND confers strictly more storage than
 * [otherProductId] would. ⚠ Compares QUOTA, not product identity: the point is "would replacing
 * this take storage away from the user", and an unknown/newer tier floors to 0 here so it never
 * blocks a mint it cannot reason about.
 */
private fun Entitlement.grantsMoreThan(otherProductId: String, nowMs: Long): Boolean {
    val inWindow = nowMs in validFrom.get()..validUntil.get()
    if (!inWindow) return false
    return EntitlementProducts.quotaBytes(productId.get()) >
        EntitlementProducts.quotaBytes(otherProductId)
}

/**
 * Whether replacing [existing] with this grant takes no storage away: at least its quota, and a
 * strictly later `validUntil`.
 */
private fun EntitlementSignature.SignedEntitlement.keepsAtLeast(existing: Entitlement): Boolean =
    EntitlementProducts.quotaBytes(productId) >= EntitlementProducts.quotaBytes(existing.productId.get()) &&
        validUntil > existing.validUntil.get()

/**
 * Whether this record has passed the halfway point of its own `[validFrom, validUntil]` window and is
 * due for renewal. Relative to the record's own window (not a fixed constant) so it adapts to any
 * commerce TTL and self-limits: a freshly re-minted record has its full window remaining, so it won't
 * re-trip until it ages back past the midpoint. A non-positive window is treated as due.
 */
private fun Entitlement.isPastRenewalPoint(nowMs: Long): Boolean {
    val window = validUntil.get() - validFrom.get()
    if (window <= 0) return true
    return (validUntil.get() - nowMs) < window / 2
}

/**
 * Upsert an **already-signed** [EntitlementSignature.SignedEntitlement] into the realm — the shared
 * save half of [mintEntitlement], reused by the identity-light redeem path
 * (`commerce-accounts-and-sessions.md` §2.3), which receives a commerce-signed grant directly from
 * `/commerce/entitlement/redeem` rather than minting through a session. Keyed by `ownerId`, so it
 * replaces the owner's current entitlement (the reader recomputes figures off any `Entitlement`
 * save). Idempotent against an already-at-least-as-fresh record to avoid sync churn; a **renewal**
 * (same product, **later** `validUntil`) or a product change is written so the fresh window syncs to
 * peers. Returns `true` iff a record was written.
 */
suspend fun ExecutionContext.saveSignedEntitlement(signed: EntitlementSignature.SignedEntitlement): Boolean {
    val id = DataId.StringDataId.create(context = context, type = Entitlement.ID, keyValue = signed.ownerId)
    val existing = database.getData(id) as? Entitlement
    // Skip only when the existing record is the same product AND not older — so a renewal that extends
    // `validUntil` still writes, but an identical / stale re-mint does not churn sync.
    if (existing != null &&
        existing.productId.get() == signed.productId &&
        existing.validUntil.get() >= signed.validUntil
    ) {
        return false
    }
    saveData(
        Entitlement.create(
            context = context,
            ownerId = signed.ownerId,
            publisherId = signed.publisherId,
            productId = signed.productId,
            validFrom = signed.validFrom,
            validUntil = signed.validUntil,
            publisherSignature = signed.signature,
        ),
    )
    return true
}

/**
 * Collects [redeemed] grants (the commerce-signed entitlements from the redeem path) and
 * [saveSignedEntitlement]s each into the realm. A redeem has no session to drive
 * [initEntitlementWriter], so this is its persistence seam — wired alongside the account writer in
 * the platform init hooks. A no-op stream (the mock, which grants via the account → mint writer)
 * simply never fires.
 */
fun ExecutionContext.initRedeemedEntitlementWriter(
    redeemed: Flow<EntitlementSignature.SignedEntitlement>,
    scope: CoroutineScope,
) {
    scope.launch {
        redeemed.collect { signed -> saveSignedEntitlement(signed) }
    }
}

/**
 * Collects [intents] and [mintEntitlement]s the owner's [Entitlement] whenever a
 * non-null intent binds — the reactive form Control drives from its
 * `PublisherClient.account` flow. A `null` intent (no subscription) is a no-op.
 *
 * Also runs a **renewal tick**: while this (session-holding) device is online, it periodically
 * re-drives [mintEntitlement] for the current product. Because that call is renewal-aware, it only
 * reaches commerce (and only writes) once the record passes the halfway point of its window — keeping
 * the synced record fresh for every device without churn, and bounding the forced-revocation window
 * to ~the renewal cadence (T-CB-08). A device coming online re-checks immediately, since the intent
 * flow's first value always emits. [now] supplies the clock; [renewCheckIntervalMs] the cadence.
 */
fun ExecutionContext.initEntitlementWriter(
    intents: Flow<EntitlementMintIntent?>,
    ownerId: () -> String?,
    scope: CoroutineScope,
    mintClient: EntitlementMintClient,
    now: () -> Long,
    renewCheckIntervalMs: Long = DEFAULT_RENEW_CHECK_INTERVAL_MS,
) {
    val latestIntent = MutableStateFlow<EntitlementMintIntent?>(null)

    scope.launch {
        intents.distinctUntilChanged().collect { intent ->
            latestIntent.value = intent
            val owner = ownerId() ?: return@collect
            if (intent == null) return@collect
            mintEntitlement(
                ownerId = owner,
                productId = intent.productId,
                email = intent.email,
                mintClient = mintClient,
                nowMs = now(),
            )
        }
    }

    // Owner-resolve backstop (mirrors initEntitlementStorageReader's): a **persisted** account
    // (MockPublisherClient / a restored session) makes the intent flow emit its bound value at
    // startup — *before* genesis/unlock resolves the owner. The collect above then skips it
    // (owner null), and because `distinctUntilChanged` holds that same value, a later re-bind of
    // the identical intent never re-fires the collect — so the owner's entitlement would never be
    // minted (hasStorage stuck false → the ADR-0019 relay-registration gate never opens). Poll
    // until the owner resolves, then mint the latest intent once. Idempotent with the collect
    // (`mintEntitlement` no-ops an unchanged record), and a no-op when there's no intent.
    scope.launch {
        while (isActive && ownerId() == null) delay(OWNER_RESOLVE_POLL_MS)
        val owner = ownerId() ?: return@launch
        val intent = latestIntent.value ?: return@launch
        mintEntitlement(
            ownerId = owner,
            productId = intent.productId,
            email = intent.email,
            mintClient = mintClient,
            nowMs = now(),
        )
    }

    scope.launch {
        while (isActive) {
            delay(renewCheckIntervalMs)
            val owner = ownerId() ?: continue
            val intent = latestIntent.value ?: continue
            mintEntitlement(
                ownerId = owner,
                productId = intent.productId,
                email = intent.email,
                mintClient = mintClient,
                nowMs = now(),
            )
        }
    }
}

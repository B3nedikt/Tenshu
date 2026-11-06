package app.tenshu.control.data

import org.tenshu.storage.proxy.EntitlementRecordCredentialProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.identity.EntitlementSignature
import org.tenshu.identity.EntitlementUnverifiedError
import org.tenshu.storage.proxy.EntitlementMintClient
import org.tenshu.storage.proxy.EntitlementMintIntent
import org.tenshu.storage.proxy.EntitlementProducts
import org.tenshu.storage.proxy.EntitlementStorageProvider
import org.tenshu.storage.proxy.initEntitlementStorageReader
import org.tenshu.storage.proxy.initEntitlementWriter
import org.tenshu.storage.proxy.initRedeemedEntitlementWriter

/**
 * The commerce-side `plan → productId` mapping. Stays in `:control-shared` (it
 * depends on [PublisherAccount.Plan]); the generic `productId → quota` half lives
 * in `:storage-service-proxy`'s [EntitlementProducts] (relocated in
 * web-storage-status.md Bucket 2).
 */
fun productIdForPlan(plan: PublisherAccount.Plan): String = when (plan) {
    PublisherAccount.Plan.Free -> EntitlementProducts.FREE
    // v1: Rei has no real product yet (redeem-only); treat it as the Basic quota tier
    // — the 50 GiB the retired Lifetime tier used to confer.
    PublisherAccount.Plan.Rei -> EntitlementProducts.BASIC
}

/**
 * Wires the entitlement-derived storage figures on the proxy session
 * (storage-entitlement-sync.md slice 3), both directions — now a thin adapter
 * over the pieces relocated to `:storage-service-proxy` (web-storage-status.md
 * Buckets 2+3):
 *
 * - **Reader** ([initEntitlementStorageReader]): a `DataObserver` recomputes
 *   [provider]'s figures from *any* [org.tenshu.identity.Entitlement] save —
 *   written locally by the writer below, **or** arriving via sync-merge on a 2nd
 *   device — and on delete, seeded once. This is the cross-device payoff: the
 *   owner's entitlement syncs in and the figures (hence the Drive banner / Tier-2
 *   gate) flip on every device.
 *
 * - **Writer** ([initEntitlementWriter]): maps Control's [accountFlow] to a
 *   product/email [EntitlementMintIntent] and upserts the owner's entitlement when
 *   a subscription binds — minted off-device in production (the commerce service
 *   signs with `tenshu.commerce`) or in-process with the dev key under
 *   `TENSHU_BILLING_MOCK` (`publishing-commerce-reconciliation.md` §10.3).
 *   Idempotent; a `null` account / `null` mint is a deliberate no-op (a device
 *   that never subscribed must not delete another device's grant).
 *
 * Decoupled from `PublisherClient` / `SyncManager` (takes [accountFlow] +
 * [ownerId]) so the wiring is exercised against plain fakes in tests.
 */
/** Pseudo-operation id for the read-path verification failure; ⚠ named so it stays greppable. */
private const val ENTITLEMENT_READ_OPERATION_ID = "tenshu/storage/read/entitlementVerify"

fun ExecutionContext.initEntitlementStorage(
    provider: EntitlementStorageProvider,
    accountFlow: Flow<PublisherAccount?>,
    ownerId: () -> String?,
    scope: CoroutineScope,
    now: () -> Long,
    mintClient: EntitlementMintClient,
    // Decides whether the build-configured issuer (`TENSHU_EXTRA_ISSUER`) comes into force:
    // never in a release binary. ⚠ No default on purpose — a platform that forgot to say would
    // silently run with the dev lab's issuer untrusted, or trusted in a release.
    isRelease: Boolean,
    redeemedFlow: Flow<EntitlementSignature.SignedEntitlement> = emptyFlow(),
    // Track B3: fed the current signed record so a session-less 2nd device can present it as the
    // `X-Entitlement` fallback when no bearer token is obtainable. `null` leaves behaviour unchanged.
    recordCredentialProvider: EntitlementRecordCredentialProvider? = null,
    // retention-quota-model.md §5 / D5 (P1): the relay-reported per-owner usage seam (a
    // `RelayUsageClient::fetchUsage` from the platform DI). A plain `suspend () -> Long?` so this
    // module needs no `:sync-service-proxy` dependency. `null` leaves usage at the historical 0 mock.
    // P1b: carries the relay-reported limit alongside usage — `(usedBytes, limitBytes?)`.
    usageReader: (suspend () -> Pair<Long, Long?>?)? = null,
    // `entitlement-trust-anchor-distribution.md` D1: the Ed25519 verify primitive for the read-path
    // signature check (`SyncCryptoManager::verifyEd25519` in the platform DI). ⚠ `null` skips
    // verification — the historical behaviour, and what tests/fakes get.
    verifyEd25519: ((publicKey: ByteArray, message: ByteArray, signature: ByteArray) -> Boolean)? = null,
) {
    // Before the reader below: its first seed verifies against whatever is in force by then.
    val extraIssuer = EntitlementSignature.trustBuildConfiguredIssuer(isRelease)
    // ⚠ The only place a build with the variable differs VISIBLY from one without: both run
    // identically until a record from the other plane fails as "unknown publisher".
    println(
        "Entitlement trust: publishers=${EntitlementSignature.trustedPublisherIds()}" +
            when {
                extraIssuer != null -> " (build-configured: $extraIssuer)"
                EntitlementSignature.buildConfiguredIssuer != null ->
                    " (build-configured ${EntitlementSignature.buildConfiguredIssuer?.publisherId} IGNORED: release build)"
                else -> ""
            },
    )
    initEntitlementStorageReader(
        provider = provider,
        ownerId = ownerId,
        now = now,
        recordSink = recordCredentialProvider?.let { p -> { signed -> p.update(signed) } },
        // Backstop the locked/async-start seed: re-derive as soon as the owner resolves so a 2nd
        // device reflects its synced entitlement promptly, not only after an incidental save.
        scope = scope,
        usageReader = usageReader,
        // `entitlement-trust-anchor-distribution.md` D1: the read-path signature check. Verifying
        // here (rather than at sync ingest) means a client update carrying a new trusted key
        // re-trusts records already on the device, with no re-sync.
        verifyRecord = verifyEd25519?.let { verify ->
            { signed ->
                EntitlementSignature.publicKeysFor(signed.publisherId)
                    .any { verify(it, signed.message(), signed.signature) }
            }
        },
        // Durable + user-visible, replacing a `println` the packaged app discards.
        reportUnverified = { publisherId ->
            errorEventRecorder?.record(
                error = EntitlementUnverifiedError.create(context = context, publisherId = publisherId),
                operationId = ENTITLEMENT_READ_OPERATION_ID,
                callstackDepth = 0,
            )
        },
    )
    initEntitlementWriter(
        intents = accountFlow.map { account ->
            account?.let {
                EntitlementMintIntent(
                    productId = productIdForPlan(it.plan),
                    email = (it.identityMethod as? PublisherAccount.IdentityMethod.Email)?.address,
                )
            }
        },
        ownerId = ownerId,
        scope = scope,
        mintClient = mintClient,
        now = now,
    )
    // The identity-light redeem path (`commerce-accounts-and-sessions.md` §2.3) hands the device a
    // commerce-signed grant with no session to drive the mint writer above; persist it directly.
    initRedeemedEntitlementWriter(redeemed = redeemedFlow, scope = scope)
}

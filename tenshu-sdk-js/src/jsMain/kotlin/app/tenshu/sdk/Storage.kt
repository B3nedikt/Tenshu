@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class, ExperimentalEncodingApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.identity.EntitlementSignature
import org.tenshu.storage.StorageStatus
import org.tenshu.storage.client.status
import org.tenshu.storage.client.storageService
import org.tenshu.storage.proxy.EntitlementProducts
import org.tenshu.storage.proxy.saveSignedEntitlement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise

/**
 * Storage-provision surface (web-storage-status.md) — the Tier-1 signal the
 * upload / share entry points gate on (`storage-capacity-gating.md`). Mirrors
 * the native `StorageService.status()` reactive read; on the thin-SDK web side
 * the snapshot is taken with `Flow.first()` and the React layer refetches.
 *
 * Backed by the apex `JsRuntime`'s storage plugin (FirstPartyPlugins
 * `StoragePlugin`). Resolves `null` until the proxy has derived a status — on a
 * web device that hasn't joined a funded realm that is `{ hasStorage: false }`
 * (the Bucket-1 default provider); Bucket 2 makes it reflect the synced
 * `Entitlement`.
 */
@JsExport
object Storage {

    /**
     * Current storage snapshot — a flat `{ hasStorage, usedBytes, limitBytes }`
     * JS object, or `null` if no status has been derived yet. `usedBytes` /
     * `limitBytes` are JS numbers (the schema's `TInteger` is Long-backed; byte
     * counts beyond 2^53 are not expected for v1 quotas).
     */
    fun status(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.storageService.status().first()?.toJs()
    }

    /**
     * Save a commerce-signed entitlement — the body of `POST /commerce/entitlement/mint` — into
     * the realm, so a plan bought in this browser reaches it. Resolves `true` when the realm now
     * holds that plan, `false` when the record was refused.
     *
     * ⚠⚠ The web had no writer for a paid plan at all: the Stripe webhook only writes commerce's
     * ledger, the signed record is client-pull, and the one write here was the dev-signed Free
     * record at genesis. A purchase with no session-holding native device in the realm therefore
     * never arrived (first live Stripe pass, 2026-10-12).
     *
     * ⚠ Refuses a record for another owner or one no trusted key signed. The save itself does not
     * verify, and this row REPLACES the owner's current entitlement — an unverifiable record would
     * take a working Free plan down to "no storage", since the reader declines to derive figures
     * from it.
     *
     * Waits (bounded) for the reader to derive the new figures, because [status] is a snapshot and
     * the caller's next read would otherwise still say Free.
     */
    fun applyEntitlement(fields: dynamic): Promise<Boolean> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val signed = signedEntitlementOrNull(fields) ?: return@promise false
        if (signed.ownerId != JsRuntime.syncManager.currentOwnerId) return@promise false
        val trusted = EntitlementSignature.publicKeysFor(signed.publisherId).any { key ->
            JsRuntime.syncCryptoManager.verifyEd25519(key, signed.message(), signed.signature)
        }
        if (!trusted) return@promise false

        JsRuntime.executionContext.saveSignedEntitlement(signed)
        val quota = EntitlementProducts.quotaBytes(signed.productId)
        withTimeoutOrNull(STATUS_SETTLE_MS) {
            JsRuntime.tenshuClient.storageService.status().first { it?.limitBytes?.get() == quota }
        }
        true
    }
}

private const val STATUS_SETTLE_MS = 3_000L

// The six mint fields as JSON carries them: epoch-millis as numbers, the signature base64.
private fun signedEntitlementOrNull(fields: dynamic): EntitlementSignature.SignedEntitlement? = runCatching {
    EntitlementSignature.SignedEntitlement(
        ownerId = fields.ownerId as String,
        publisherId = fields.publisherId as String,
        productId = fields.productId as String,
        validFrom = (fields.validFrom as Number).toLong(),
        validUntil = (fields.validUntil as Number).toLong(),
        signature = Base64.Default.decode(fields.publisherSignature as String),
    )
}.getOrNull()

// Flattens a `StorageStatus` Composite into the `NativeStorageStatus` shape the
// hand-written `web/sdk/src/native.d.ts` declares. Bytes surface as JS numbers.
private fun StorageStatus.toJs(): dynamic {
    val obj: dynamic = js("({})")
    obj.hasStorage = hasStorage.get()
    obj.usedBytes = usedBytes.get().toDouble()
    obj.limitBytes = limitBytes.get().toDouble()
    return obj
}

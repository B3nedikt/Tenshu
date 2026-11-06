@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.errorMessage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.tenshu.core.TResult
import org.tenshu.core.common.util.Bip39
import org.tenshu.storage.proxy.EntitlementProducts
import org.tenshu.storage.proxy.mintEntitlement
import org.tenshu.sync.client.syncService
import org.tenshu.sync.createRealm
import kotlin.js.Promise

// First-device realm bootstrap (UX `web-onboarding-ux.md` §4b — new user).
// Wraps `:sync-service`'s `CreateRealm` op; see `docs/sync/pairing-flow.md`
// §13 (first-device bootstrap) and `docs/proposals/sync-relay-api.md` §3.2.
//
// JS contract: `web/sdk/src/onboarding.ts` `RealmApi.create({mnemonic,
// deviceName}) → Promise<TResult<void>>`. The Kotlin side takes the two
// strings positionally; the TS adapter destructures the args object before
// calling into here.
//
// On success the relay has stored `(ownerId → masterPublicKey)` and
// registered this device's public key — subsequent JWT-authenticated relay
// calls verify. Locally, the device's own `Device { isAuthorized = true }`
// entity is pushed into the synced DB so the device list shows it
// immediately even before sync round-trips.
@JsExport
object Realm {

    fun create(mnemonic: String, deviceName: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            // Defensive Throwable catch: TResult-aware errors come back as
            // `TResult.Failure` and route through onFailure (a refused `fetch`
            // included, since `TransportFailureMapping`). The SDK boundary needs to translate every error into the
            // contract's `{ ok: false, error: string }` shape — apex must not
            // see raw Promise rejections.
            try {
                val client = JsRuntime.tenshuClient
                val result = client.syncService.createRealm(mnemonic, deviceName)
                when (result) {
                    is TResult.Success<*> -> {
                        // Hold the BIP-39 entropy so the passkey-registration
                        // tail wraps it into the v2 blob and
                        // `Recovery.revealPhrase` can reconstruct the mnemonic
                        // later (low-friction onboarding defers the phrase to
                        // Settings §7.4). In-memory only; the mnemonic is never
                        // persisted in the clear. Best-effort — a decode failure
                        // here can't fail a realm we just created.
                        runCatching { JsRuntime.currentEntropy = Bip39.decode(mnemonic) }
                        // Persist deviceName so `Session.detect()` can route
                        // returning users to `locked` instead of `firstTime`.
                        // Sync the disk write before resolving — apex routing
                        // decisions made on a stale `detect()` after a reload
                        // would land the user on the new-user flow again.
                        JsRuntime.sessionStore.setDeviceName(deviceName)
                        JsRuntime.sessionStore.flush()
                        // web-storage-status.md Bucket 3: mint a default-free
                        // entitlement for the new realm so it has storage, mirroring
                        // native's "free on signup" — the size is whatever
                        // StorageTiers.FREE_BYTES says, not a number worth repeating
                        // here (this comment claimed 1 GiB long after it became 5).
                        // The storage plugin's
                        // reader observes the save → `StorageStatus` flips to
                        // hasStorage; the Entitlement is Syncable, so it reaches the
                        // owner's other devices. Best-effort — a mint failure must
                        // not fail a realm we just created.
                        JsRuntime.syncManager.currentOwnerId?.let { owner ->
                            runCatching {
                                JsRuntime.executionContext.mintEntitlement(
                                    ownerId = owner,
                                    productId = EntitlementProducts.FREE,
                                    email = null,
                                    mintClient = JsRuntime.entitlementMintClient,
                                    nowMs = kotlin.js.Date.now().toLong(),
                                )
                            }
                        }
                        val ok: dynamic = js("({})")
                        ok.ok = true
                        ok.value = undefined
                        ok
                    }

                    is TResult.Failure<*> -> {
                        val err: dynamic = js("({})")
                        err.ok = false
                        err.error = result.error.errorMessage()
                        err
                    }

                    else -> {
                        val err: dynamic = js("({})")
                        err.ok = false
                        err.error = "unexpected result type"
                        err
                    }
                }
            } catch (t: Throwable) {
                val err: dynamic = js("({})")
                err.ok = false
                err.error = t.message ?: t::class.simpleName ?: "unknown"
                err
            }
        }

    /**
     * This realm's `ownerId`, or `null` before one exists (no realm created or
     * joined yet, or the realm is locked and the master key not derived).
     *
     * Every commerce endpoint is keyed by `ownerId` — it is what binds a paid
     * entitlement to the realm the bytes live in — so the web account surface
     * cannot call one without it. Native reads it straight off `SyncManager`;
     * the web had no exported route to the same value.
     *
     * ⚠ Not folded into `Session.detect()`'s state object. That answers "what
     * should the app show right now" and is consumed by the whole onboarding
     * router; the owner id is realm identity, is meaningful in states the
     * session shape does not model, and giving it its own read keeps a
     * commerce concern out of the boot path.
     *
     * ⚠ Public, non-secret, and already known to the relay — it is the
     * keyspace name, derived from the master *public* key. Exposing it to the
     * apex page discloses nothing the relay does not route on.
     */
    fun ownerId(): Promise<String?> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.syncManager.currentOwnerId?.takeIf { it.isNotBlank() }
    }
}

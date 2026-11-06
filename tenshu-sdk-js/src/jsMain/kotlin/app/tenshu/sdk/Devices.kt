@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.errorMessage
import app.tenshu.sdk.internal.looksLikeNetworkError
import app.tenshu.sdk.internal.toJs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.tenshu.core.SimpleComposite
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.getOrNull
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.client.identityService
import org.tenshu.identity.getDevices
import org.tenshu.sync.client.syncService
import org.tenshu.sync.revokeDevice
import kotlin.js.Promise

// Devices SDK surface for the apex's Settings → Data → Devices section
// (per `web-control-plane.md` §4.3 + `control-ia.md` §5.2). Mirrors the
// pattern used by Pairing.kt: thin Kotlin wrappers over `:identity-service`
// and `:sync-service` ops that already exist on the platform side and are
// consumed by `:control-shared`'s `TenshuSyncRepository`.
//
// `observe(callback)` polls `IdentityService.getDevices()` on a 4 s
// cadence — there is no realm-device StateFlow exposed at the
// identity-service client surface today, so the SDK polls the same way
// `SyncDashboardViewModel` polls pending pairing requests. The poll
// interval is conservative: realm-membership churn is rare and one
// extra request every few seconds is comfortably below the relay's
// rate-limit budget. Once the identity-service grows a stream-shaped
// op (a future protocol-improvements item), `observe` can switch over
// without changing the JS contract.

@JsExport
object Devices {

    // Existing prototype-demo entrypoint. Phase B keeps it for the dev
    // surface (`MockDevtools`) and for the per-service entry point's
    // smoke test; all real device-list rendering goes through `observe`.
    fun demo(): dynamic = SimpleComposite(
        schema = Device.Schema,
        fieldValues = mapOf(
            Device.Fields.DEVICE_ID to "demo-device-1234".toTString(),
            Device.Fields.NAME to "Demo MacBook".toTString(),
            Device.Fields.PUBLIC_KEY to ByteArray(32) { it.toByte() }.toTByteArray(),
            Device.Fields.IS_AUTHORIZED to true.toTBoolean(),
        ),
    ).toJs()

    // Subscribes to the realm device list. Emits the FULL list of
    // authorised devices on each refresh (filters out `isAuthorized=false`
    // entries — revoked devices kept for tombstone ordering, not active
    // pairings the user can act on). First emission happens after the
    // first successful poll; subsequent emissions only fire when the
    // device-id set actually changes, so the UI doesn't flicker.
    //
    // Caller pattern: callback + unsubscribe lambda, same as
    // `Notifications.observe`. Cleanly composes with React useEffect:
    // `useEffect(() => Devices.observe(setItems), [])`.
    fun observe(callback: (Array<dynamic>) -> Unit): () -> Unit {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var lastSnapshot: List<String>? = null
        scope.launch {
            JsRuntime.awaitReady()
            val client = JsRuntime.tenshuClient
            while (isActive) {
                val result = try {
                    client.identityService.getDevices()
                } catch (t: Throwable) {
                    // Network blips fall through to the next poll; the UI
                    // keeps rendering the last-known snapshot. Surfacing
                    // an empty list on transient failure would flicker.
                    null
                }
                @Suppress("UNCHECKED_CAST")
                val list: List<Device> =
                    if (result is TResult.Success<*>) {
                        (result.value as? TList<Device>)?.toList() ?: emptyList()
                    } else {
                        emptyList()
                    }
                if (result is TResult.Success<*>) {
                    val authorised = list.filter { it.isAuthorized.get() }
                    // Each device's self-published hardware model (`DeviceHardware`). Best-effort,
                    // like the roster on the other platforms: a failed read renders names alone
                    // rather than holding back the list.
                    val hardware: Map<String, String> = try {
                        client.identityService.deviceHardware.getAll().getOrNull()?.toList()
                            ?.associate { it.deviceId.get() to it.hardwareName.get() }
                            .orEmpty()
                    } catch (t: Throwable) {
                        emptyMap()
                    }
                    // ⚠ The snapshot carries the hardware names too, so a row published after
                    // the first emission still reaches the UI.
                    val snapshot = authorised.map { it.deviceId.get() + "\u0000" + hardware[it.deviceId.get()].orEmpty() }
                    if (snapshot != lastSnapshot) {
                        lastSnapshot = snapshot
                        callback(
                            authorised.map { device ->
                                val js = device.toJs()
                                js.hardwareName = hardware[device.deviceId.get()].orEmpty()
                                js
                            }.toTypedArray()
                        )
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        return { scope.cancel() }
    }

    // Revoke a paired device by its `deviceId`. Calls `:sync-service`'s
    // `RevokeDevice`, which marks the device's `Device { isAuthorized }`
    // entry false and bumps the realm epoch (per the sync-device-
    // management proposal). Subsequent polls of `observe` will drop the
    // device from the emitted list.
    fun revoke(deviceId: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            try {
                val client = JsRuntime.tenshuClient
                when (val result = client.syncService.revokeDevice(deviceId)) {
                    is TResult.Success<*> -> okVoid()
                    is TResult.Failure<*> -> errorResult(
                        kind = "other",
                        message = result.error.errorMessage(),
                    )
                    else -> errorResult("other", "unexpected result type")
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.simpleName ?: "unknown"
                val kind = if (looksLikeNetworkError(msg)) "network" else "other"
                errorResult(kind, msg)
            }
        }

    // Revoke THIS device (web self-revoke / "sign out everywhere", roadmap
    // Phase B). Resolves the current device id internally from the
    // SyncManager — the apex has no reliable handle on it. Unlike `revoke`,
    // the ordering is load-bearing: `RevokeDevice` only *emits* a DeleteBlock
    // tombstone into the sync pipeline, so this op must force a drain
    // (`SyncScheduler.drainNow()`) to push the tombstone to the relay BEFORE
    // resolving — the caller (`DeviceMenu`) wipes local state on success, and
    // the wipe destroys the device signing key + relay JWT, so a not-yet-
    // pushed tombstone would strand this browser as a permanent ghost device.
    fun revokeSelf(): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            try {
                val client = JsRuntime.tenshuClient
                val deviceId = JsRuntime.syncManager.currentDeviceId
                when (val result = client.syncService.revokeDevice(deviceId)) {
                    is TResult.Success<*> -> {
                        // Force the revocation tombstone out to the relay while we
                        // still hold the signing key + JWT. `drainNow()` serialises
                        // with the in-process loop and suspends until the push (and
                        // any in-flight tick ahead of it) completes. If the
                        // scheduler isn't wired yet (cold-boot race) we surface a
                        // network error rather than claim a push that didn't happen.
                        val scheduler = JsRuntime.syncSchedulerHolder.scheduler
                            ?: return@promise errorResult("network", "sync not ready")
                        scheduler.drainNow()
                        okVoid()
                    }
                    is TResult.Failure<*> -> errorResult(
                        kind = "other",
                        message = result.error.errorMessage(),
                    )
                    else -> errorResult("other", "unexpected result type")
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.simpleName ?: "unknown"
                val kind = if (looksLikeNetworkError(msg)) "network" else "other"
                errorResult(kind, msg)
            }
        }
}

private const val POLL_INTERVAL_MS = 4_000L

private fun okVoid(): dynamic {
    val ok: dynamic = js("({})")
    ok.ok = true
    ok.value = undefined
    return ok
}

private fun errorResult(kind: String, message: String?): dynamic {
    val err: dynamic = js("({})")
    err.ok = false
    val errorObj: dynamic = js("({})")
    errorObj.kind = kind
    if (message != null && kind == "other") {
        errorObj.message = message
    }
    err.error = errorObj
    return err
}

@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.tenshu.core.TResult
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.app.removeApp
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.app.appService
import org.tenshu.core.fold
import org.tenshu.identity.Device
import org.tenshu.identity.client.identityService
import kotlin.js.Promise

/**
 * The realm's app registrations — which app has run on which device
 * (`docs/proposals/control-app-registration.md` §4, `web-apex-home.md` §3.5 / H6).
 *
 * The rows are the synced `AppRegistration` composites (one per `(appId, deviceId)`), read through
 * `AppService.appRegistrations` — the same relation native Control's `TenshuAppRegistry` reads. The
 * browser is a sync peer, so it holds every device's rows; the relation is maintained by the generic
 * `RelationMaintainer` the Home/Health/Place plugins already install.
 *
 * ⚠⚠ **The browser writes no registration of its own.** `RegisterApp` fires at the native IPC
 * handshake; the apex hosts its apps in-process and has no such handshake. So a row is always about a
 * *native* device, and "registered on this device" is never true here — a surface must not read a
 * missing row as "not used in this browser".
 *
 * ⚠ The `appId`s are the native catalogue's (`app.tenshu.photos`), not the web registry's
 * (`photos`); the apex maps between them.
 */
@JsExport
object Apps {

    /**
     * Every app with an **Active** registration, live: re-emits when a row syncs in or is removed, and
     * when a device is renamed, admitted or revoked (the device names are joined in here).
     *
     * `{ ok: true, apps: [{ appId, installedAtMs, verified, devices: [{ deviceId, name }] }] }`, or
     * `{ ok: false, error }`.
     *
     * - `installedAtMs` — the earliest `createdAt` across the app's rows (native's install date).
     * - `devices[].name` — the device's name, or `null` when the realm roster has no **authorised**
     *   device with that id (revoked, or its row has not synced yet). ⚠ A caller that names devices
     *   must skip a `null` rather than guess one.
     *
     * ⚠ Seeded with one-shot reads: a relation that has never held a row emits nothing, so a bare
     * collect on a fresh realm would wait forever. ⚠⚠ Every end but the caller's own cancel reaches
     * [onError] once, so a dead stream never freezes a surface on its last value.
     */
    fun observeRegistrations(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            val failure = try {
                JsRuntime.awaitReady()
                var last: List<RegisteredApp>? = null
                registrationsFlow().collect { read ->
                    // ⚠ Both relations re-emit on any write to them; forward only a changed view.
                    if (read !is Read.Ok || read.value != last) {
                        last = (read as? Read.Ok)?.value
                        callback(registrationsResult(read))
                    }
                }
                "observe_ended"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            onError(failure)
        }
        return { job.cancel() }
    }

    /**
     * Deregisters [appId] on **every** device — `RemoveApp(appId, deviceId = null)`: each row becomes
     * a `Removed` tombstone and the app's grants are purged (`control-app-registration.md` §5).
     *
     * ⚠⚠ There is no "this device" form on the web, on purpose: the browser holds no row of its own
     * (see the object KDoc), so a per-device removal here would be a silent no-op.
     */
    fun removeApp(appId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (appId.isBlank()) return@promise failureResult("invalid_app_id", null)
        JsRuntime.tenshuClient.appService.removeApp(appId, deviceId = null).fold(
            onSuccess = { okResult() },
            onFailure = { failureResult("remove_failed", it.toString()) },
        )
    }
}

internal data class RegisteredDevice(val deviceId: String, val name: String?)

internal data class RegisteredApp(
    val appId: String,
    val installedAtMs: Long,
    val verified: Boolean,
    val devices: List<RegisteredDevice>,
)

private fun registrationsFlow(): Flow<Read<List<RegisteredApp>>> {
    val client = JsRuntime.tenshuClient
    val rows = client.appService.appRegistrations.let { relation ->
        relation.flow().onStart { emit(relation.getAll()) }
    }
    val devices = client.identityService.devices.let { relation ->
        relation.flow().onStart { emit(relation.getAll()) }
    }
    return combine(rows, devices) { r, d -> joinRegistrations(r, d) }
}

/**
 * Groups the Active rows by app and names each device. A failed **device** read is not a failed
 * view — the registrations are still true, they just cannot be named — so it degrades every name
 * to `null` (which callers already must handle) rather than failing the whole read.
 */
internal fun joinRegistrations(
    rows: TResult<TList<AppRegistration>>,
    devices: TResult<TList<Device>>,
): Read<List<RegisteredApp>> = rows.fold(
    onFailure = { Read.Failed(it) },
    onSuccess = { list ->
        val names: Map<String, String> = (devices as? TResult.Success)?.value?.toList().orEmpty()
            .filter { it.isAuthorized.get() }
            .associate { it.deviceId.get() to it.name.get() }
        Read.Ok(
            list.toList()
                .filter { it.state.get() == AppRegistration.State.ACTIVE }
                .groupBy { it.appId.get() }
                .map { (appId, appRows) ->
                    RegisteredApp(
                        appId = appId,
                        installedAtMs = appRows.minOf { it.createdAt.get() },
                        verified = appRows.all { it.verified.get() },
                        devices = appRows.map { it.deviceId.get() }.distinct().sorted().map { id ->
                            RegisteredDevice(id, names[id]?.takeIf { it.isNotBlank() })
                        },
                    )
                }
                .sortedBy { it.appId },
        )
    },
)

private fun registrationsResult(read: Read<List<RegisteredApp>>): dynamic = when (read) {
    is Read.Ok -> okResult { result ->
        result.apps = read.value.map { app ->
            val js: dynamic = js("({})")
            js.appId = app.appId
            js.installedAtMs = app.installedAtMs.toDouble()
            js.verified = app.verified
            js.devices = app.devices.map { device ->
                val d: dynamic = js("({})")
                d.deviceId = device.deviceId
                d.name = device.name
                d
            }.toTypedArray()
            js
        }.toTypedArray()
    }
    is Read.Failed -> failureResult("read_failed", read.error.toString())
}

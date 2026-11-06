package org.tenshu.core.common.app.proxy

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.app.RegisterApp
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.time.clock

/**
 * Proxy-only, idempotent registration write
 * (`docs/proposals/control-app-registration.md` §3.1). Looks the
 * `(appId, deviceId)` row up by its derived `StringDataId`:
 *
 *  - **an existing Active row** → only the `verified` badge is refreshed, and only when it
 *    actually changed (see [refreshVerified]). Everything else is left alone: `createdAt` is the
 *    install date and must not creep forward on every re-bind.
 *  - **an existing Removed row** → no-op, completely untouched, so a still-installed binary
 *    cannot silently re-register (§5.1); re-add is an explicit user action.
 *  - **no row** → create one with `createdAt = now` (the install date),
 *    `state = Active`, and the `verified` badge passed by the handshake.
 *
 * The write is reachable only after the IPC handshake's code-signing check, so
 * a forged client cannot create a registration (threat-model T-C-11, §6).
 */
internal class RegisterAppImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val appId: TString = params.read(RegisterApp.Params.APP_ID)
        val deviceId: TString = params.read(RegisterApp.Params.DEVICE_ID)
        val verified: TBoolean = params.read(RegisterApp.Params.VERIFIED)

        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = AppRegistration.ID,
            keyValue = AppRegistration.compositeKey(appId.get(), deviceId.get())
        )

        when (val existing = executionContext.database.getData(dataId) as AppRegistration?) {
            null -> {
                val now = executionContext.context.clock().nowEpochMillis()
                executionContext.saveData(
                    AppRegistration.create(
                        context = executionContext.context,
                        appId = appId.get(),
                        deviceId = deviceId.get(),
                        createdAt = now,
                        state = AppRegistration.State.ACTIVE,
                        verified = verified.get(),
                    )
                )
            }

            else -> refreshVerified(executionContext, existing, verified.get())
        }

        return null as T
    }

    /**
     * Re-registration keeps its **narrow** update: the `verified` badge, and nothing else.
     *
     * `verified` is a *current* property of the connecting peer — whether this session's identity
     * was attested — not a fact about the install. Freezing it at first contact was the wrong
     * semantics and had a visible cost: rows written before desktop attestation (P3) and Android's
     * claim (C4) existed kept `verified = false` forever, because registration short-circuited on
     * any existing row. Since the rows are synced and durable they never self-healed, so the Apps
     * screen's badge stayed on for every app and therefore meant nothing.
     *
     * ⚠ Everything else still short-circuits, and the two reasons matter:
     *  - **`createdAt` is the install date** and must not creep forward on every re-bind;
     *  - **a `Removed` tombstone is left completely untouched** (`control-app-registration.md`
     *    §5.1) so a still-installed binary cannot silently re-register itself. Re-add stays an
     *    explicit user action — refreshing a badge on a removed app would be the first step back
     *    towards that.
     *
     * ⚠ Writes only on an actual change. This runs on **every** session establishment, and each
     * `saveData` emits a sync block; an unconditional write would put one on the wire per client
     * bind, forever, to say nothing new.
     *
     * ⚠ **That last guard is deliberately UNTESTED, not accidentally.** Removing it leaves the
     * resulting row byte-identical, so every data-shape assertion still passes — and the module's
     * harness has no write counter (`TestProxy.updates` records proxy→client *push callbacks*, so
     * with no live subscription it stays empty and any such test is vacuously green). A test that
     * passes for the wrong reason is worse than none, because it claims a coverage it does not
     * have. If this guard ever needs real cover, the observable is the emitted sync block, which
     * lives a layer up in `SyncObserver`.
     */
    private fun refreshVerified(
        executionContext: ExecutionContext,
        existing: AppRegistration,
        verified: Boolean,
    ) {
        if (existing.state.get() != AppRegistration.State.ACTIVE) return
        if (existing.verified.get() == verified) return

        executionContext.saveData(
            AppRegistration.create(
                context = executionContext.context,
                appId = existing.appId.get(),
                deviceId = existing.deviceId.get(),
                createdAt = existing.createdAt.get(),
                state = existing.state.get(),
                verified = verified,
            )
        )
    }
}

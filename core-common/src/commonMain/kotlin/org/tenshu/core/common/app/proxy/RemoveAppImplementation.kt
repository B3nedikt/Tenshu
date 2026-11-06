package org.tenshu.core.common.app.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.app.AppGrant
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.app.RemoveApp
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId

/**
 * Deregisters an app (`docs/proposals/control-app-registration.md` §5):
 * tombstones the targeted [AppRegistration] row(s) (`state = Removed`, not a
 * delete — §5.1) and purges every [AppGrant] for the app.
 *
 *  - `deviceId` present → only the `(appId, deviceId)` row (per-device default,
 *    §5.2).
 *  - `deviceId` absent → every `(appId, *)` registration (*"Remove from all
 *    devices"*).
 *
 * Grant purge is unconditional on `appId` either way: grants are realm-wide, so
 * a re-add — on any device — starts from default-deny.
 */
internal class RemoveAppImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val appId: String = params.read<TString>(RemoveApp.Params.APP_ID).get()
        val deviceId: String? = (params?.get(RemoveApp.Params.DEVICE_ID) as? TString)?.get()

        if (deviceId != null) {
            tombstone(executionContext, appId, deviceId)
        } else {
            registrationsFor(executionContext, appId).forEach { reg ->
                tombstone(executionContext, appId, reg.deviceId.get())
            }
        }

        purgeGrants(executionContext, appId)

        return null as T
    }

    /** Re-saves the `(appId, deviceId)` registration with `state = Removed`, preserving its install date + verified badge. No-op if the row is absent. */
    private fun tombstone(executionContext: ExecutionContext, appId: String, deviceId: String) {
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = AppRegistration.ID,
            keyValue = AppRegistration.compositeKey(appId, deviceId)
        )
        val existing = executionContext.database.getData(dataId) as? AppRegistration ?: return
        if (existing.state.get() == AppRegistration.State.REMOVED) return
        executionContext.saveData(
            AppRegistration.create(
                context = executionContext.context,
                appId = appId,
                deviceId = deviceId,
                createdAt = existing.createdAt.get(),
                state = AppRegistration.State.REMOVED,
                verified = existing.verified.get(),
            )
        )
    }

    private fun registrationsFor(executionContext: ExecutionContext, appId: String): List<AppRegistration> =
        executionContext.getArrayData(executionContext.appRegistrationsRef())
            ?.mapNotNull { it as? AppRegistration }
            ?.filter { it.appId.get() == appId }
            ?.toList()
            ?: emptyList()

    private fun purgeGrants(executionContext: ExecutionContext, appId: String) {
        val grants = executionContext.getArrayData(executionContext.appGrantsRef())
            ?.mapNotNull { it as? AppGrant }
            ?.filter { it.appId.get() == appId }
            ?.toList()
            ?: return
        grants.forEach { grant ->
            val dataId = DataId.StringDataId.create(
                context = executionContext.context,
                type = AppGrant.ID,
                keyValue = grant.compositeKey.get()
            )
            executionContext.deleteData(dataId)
        }
    }
}

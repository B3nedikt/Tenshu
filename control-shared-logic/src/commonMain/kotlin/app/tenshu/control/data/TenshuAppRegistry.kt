package app.tenshu.control.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.tenshu.core.TResult
import org.tenshu.core.getOrNull
import org.tenshu.core.app.AppGrant
import org.tenshu.core.app.AppManifestSource
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.app.CapabilityClass
import org.tenshu.core.app.removeApp
import org.tenshu.core.app.setAppGrant
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.app.HandlerTable
import org.tenshu.core.common.app.PrimaryHandler
import org.tenshu.core.common.app.appService
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.StringAdapter
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.set
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus

/**
 * Real [AppRegistry] over the SDK: reads `AppRegistration` / `AppGrant` from
 * [org.tenshu.core.app.AppService]'s synced relations, joins them with the
 * bundled [AppManifestSource], and routes writes through `SetAppGrant` /
 * `RemoveApp`. Mirrors `TenshuSyncRepository` (constructor takes [TenshuClient]).
 */
class TenshuAppRegistry(
    private val client: TenshuClient,
    private val manifestSource: AppManifestSource,
) : AppRegistry {

    override fun installedApps(): Flow<List<InstalledApp>> =
        client.appService.appRegistrations.flow().map { result ->
            // Resolved once per emission rather than per app: it is a suspend read, and every
            // row in this emission is judged against the same device (D5).
            val thisDevice = currentDeviceId()
            activeRegistrations(result)
                .groupBy { it.appId.get() }
                .map { (appId, rows) -> toInstalledApp(appId, rows, thisDevice) }
                .sortedByDescending { it.installedAt }
        }

    override fun appDetail(appId: String): Flow<AppDetail?> {
        val service = client.appService
        return combine(
            service.appRegistrations.flow(),
            service.appGrants.flow(),
        ) { regResult, grantResult ->
            val rows = activeRegistrations(regResult).filter { it.appId.get() == appId }
            if (rows.isEmpty()) return@combine null

            val grants = grantsFor(grantResult, appId)
            val grantByKey = grants.associateBy { it.serviceId.get() to it.operationId.get() }
            val manifest = manifestSource.manifest(appId)

            val permissions = if (manifest != null) {
                manifest.permissions.map { perm ->
                    val g = grantByKey[perm.serviceId to perm.operationId]
                    PermissionRow(
                        serviceId = perm.serviceId,
                        operationId = perm.operationId,
                        humanLabel = perm.humanLabel,
                        capabilityClass = perm.capabilityClass,
                        scopeLabel = if (perm.capabilityClass == CapabilityClass.Scoped) "Scoped" else null,
                        grant = GrantState.fromWire(g?.grant?.get()),
                        lastUsedAt = g?.lastUsedAt?.get() ?: 0L,
                    )
                }
            } else {
                // No bundled manifest (third-party / dev-mode): surface whatever grant rows
                // exist so the app still shows its known permissions.
                grants.map { g ->
                    PermissionRow(
                        serviceId = g.serviceId.get(),
                        operationId = g.operationId.get(),
                        humanLabel = g.operationId.get(),
                        capabilityClass = CapabilityClass.Picker,
                        scopeLabel = null,
                        grant = GrantState.fromWire(g.grant.get()),
                        lastUsedAt = g.lastUsedAt.get(),
                    )
                }
            }

            AppDetail(
                app = toInstalledApp(appId, rows, currentDeviceId()),
                permissions = permissions,
                services = permissions.map { it.serviceId }.distinct(),
            )
        }
    }

    override fun openHandlerChoices(): Flow<List<OpenHandlerChoice>> {
        val choosable = HandlerTable.choosableTypes()
        val preference = client.preferenceService
        // ⚠⚠ One `combine` over the installed list and every choosable type's preference flow, so
        // the picker re-renders both when an app is installed or removed AND when the choice
        // changes on another device — the setting is realm-scoped, so the second really happens.
        // ⚠ `read` stays silent on a realm that never held the row, so each flow is seeded with
        // the "no choice" default rather than awaited (`core-preference-service.md` §6.5).
        val chosenFlows = choosable.map { type ->
            preference.read(primaryHandlerPreference(type)).onStart { emit(NO_CHOICE) }
        }
        return combine(installedApps(), combine(chosenFlows) { it.toList() }) { apps, chosen ->
            // ⚠ The derivation is shared with `MockAppRegistry` on purpose — see its KDoc.
            openHandlerChoices(
                apps = apps,
                chosen = choosable.indices.associate { choosable[it] to chosen[it] },
            )
        }
    }

    override suspend fun setPrimaryHandler(type: String, appId: String?) {
        // ⚠ Clearing writes the empty string rather than calling `clear`: `preferredHandlerFor`
        // already treats empty as "no preference", so one code path covers both and a cleared row
        // still syncs the *decision* to the user's other devices — a deletion would too, but the
        // empty value is what the reader's test pins.
        client.preferenceService.set(primaryHandlerPreference(type), appId ?: NO_CHOICE)
    }

    override suspend fun setGrant(
        appId: String,
        serviceId: String,
        operationId: String,
        grant: GrantState,
    ) {
        client.appService.setAppGrant(appId, serviceId, operationId, grant.wire)
    }

    override suspend fun revokeAllPermissions(appId: String) {
        val service = client.appService
        // Reset every capability the app declares (manifest) plus any grant row that exists,
        // so nothing is left Granted/Denied — all return to "ask each time".
        val manifestKeys = manifestSource.manifest(appId)?.permissions
            ?.map { it.serviceId to it.operationId }
            ?: emptyList()
        val grantKeys = grantsFor(service.appGrants.flow().first(), appId)
            .map { it.serviceId.get() to it.operationId.get() }
        (manifestKeys + grantKeys).distinct().forEach { (serviceId, operationId) ->
            service.setAppGrant(appId, serviceId, operationId, GrantState.AskEachTime.wire)
        }
    }

    override suspend fun uninstall(appId: String, scope: UninstallScope) {
        val deviceId = when (scope) {
            UninstallScope.ThisDevice -> currentDeviceId()
            UninstallScope.AllDevices -> null
        }
        client.appService.removeApp(appId, deviceId)
    }

    /**
     * ⚠⚠ Built here rather than in `:core-common` beside [PrimaryHandler]: `Preference` lives in
     * `:preference-service`, which `:core-common` does not depend on and must not start. The **id**
     * is shared, which is the part that has to agree with the proxy's reader.
     */
    private fun primaryHandlerPreference(type: String) = Preference(
        id = PrimaryHandler.preferenceId(type),
        scope = PreferenceScope.REALM,
        default = NO_CHOICE,
        adapter = StringAdapter,
    )

    private suspend fun currentDeviceId(): String? =
        client.syncService.getSyncStatus().getOrNull()?.deviceId?.get()

    private fun toInstalledApp(
        appId: String,
        rows: List<AppRegistration>,
        thisDevice: String?,
    ): InstalledApp {
        val manifest = manifestSource.manifest(appId)
        return InstalledApp(
            appId = appId,
            name = manifest?.name ?: appId,
            iconName = manifest?.iconName ?: "",
            version = manifest?.version ?: "",
            shortDescription = manifest?.shortDescription ?: "",
            installedAt = rows.minOf { it.createdAt.get() },
            verified = rows.all { it.verified.get() },
            deviceCount = rows.map { it.deviceId.get() }.distinct().size,
            installedOnThisDevice = thisDevice != null && rows.any { it.deviceId.get() == thisDevice },
            // The fallback above (name = appId) stays — an app we cannot name is still an app
            // the user has, and hiding it would be the worst option for a control plane. What
            // changes is that the surface can now *say* so instead of showing a bare id (C3).
            recognised = manifest != null,
        )
    }

    private fun activeRegistrations(result: TResult<TList<AppRegistration>>): List<AppRegistration> =
        (result as? TResult.Success)?.value?.toList()
            ?.filter { it.state.get() == AppRegistration.State.ACTIVE }
            ?: emptyList()

    private fun grantsFor(result: TResult<TList<AppGrant>>, appId: String): List<AppGrant> =
        (result as? TResult.Success)?.value?.toList()
            ?.filter { it.appId.get() == appId }
            ?: emptyList()

    private companion object {
        /** ⚠ The empty string IS "no preference" — the proxy reader treats it that way too. */
        const val NO_CHOICE = ""
    }
}

package app.tenshu.control.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.tenshu.core.app.CapabilityClass

/**
 * In-memory [AppRegistry] for Compose previews, screenshot tests, and unit
 * tests that don't want a full proxy. Seeds Drive (verified, first-party) plus
 * one synthetic dev-mode app (unverified) so list / detail / badge / empty
 * states are all exercisable. Pass `apps = emptyList()` for the empty state.
 *
 * Mirrors `MockPublisherClient`: holds state in a [MutableStateFlow] and
 * mutates it on writes. Not the production binding — `TenshuAppRegistry` is.
 */
class MockAppRegistry(
    apps: List<InstalledApp> = DEFAULT_APPS,
    permissions: Map<String, List<PermissionRow>> = DEFAULT_PERMISSIONS,
) : AppRegistry {

    private val appsState = MutableStateFlow(apps)
    private val permissionsState = MutableStateFlow(permissions)
    private val chosenState = MutableStateFlow(emptyMap<String, String>())

    override fun installedApps(): Flow<List<InstalledApp>> =
        appsState.map { list -> list.sortedByDescending { it.installedAt } }

    override fun appDetail(appId: String): Flow<AppDetail?> =
        permissionsState.map { perms ->
            val app = appsState.value.firstOrNull { it.appId == appId } ?: return@map null
            val rows = perms[appId] ?: emptyList()
            AppDetail(app = app, permissions = rows, services = rows.map { it.serviceId }.distinct())
        }

    /**
     * ⚠⚠ Computed from [HandlerTable] over the seeded apps, **not** hard-coded rows. A fake that
     * returned a fixed list would keep showing a place picker after the table stopped having two
     * candidates for places, and every screen test built on it would agree with it.
     */
    /**
     * ⚠⚠ Computed by the **shared** [openHandlerChoices] derivation, not hard-coded rows. A fake
     * that returned a fixed list would keep showing a place picker after the table stopped having
     * two candidates for places, and every screen test built on it would agree with it.
     */
    override fun openHandlerChoices(): Flow<List<OpenHandlerChoice>> =
        combine(appsState, chosenState) { apps, chosen -> openHandlerChoices(apps, chosen) }

    override suspend fun setPrimaryHandler(type: String, appId: String?) {
        chosenState.value = if (appId == null) {
            chosenState.value - type
        } else {
            chosenState.value + (type to appId)
        }
    }

    override suspend fun setGrant(
        appId: String,
        serviceId: String,
        operationId: String,
        grant: GrantState,
    ) {
        mutatePermissions(appId) { row ->
            if (row.serviceId == serviceId && row.operationId == operationId) row.copy(grant = grant) else row
        }
    }

    override suspend fun revokeAllPermissions(appId: String) {
        mutatePermissions(appId) { it.copy(grant = GrantState.AskEachTime) }
    }

    override suspend fun uninstall(appId: String, scope: UninstallScope) {
        appsState.value = appsState.value.filterNot { it.appId == appId }
        permissionsState.value = permissionsState.value - appId
    }

    private fun mutatePermissions(appId: String, transform: (PermissionRow) -> PermissionRow) {
        val current = permissionsState.value
        val rows = current[appId] ?: return
        permissionsState.value = current + (appId to rows.map(transform))
    }

    companion object {
        val DEFAULT_APPS = listOf(
            InstalledApp(
                appId = BundledAppManifestSource.DRIVE_APP_ID,
                name = "Drive",
                iconName = "drive",
                version = "1.0.0",
                shortDescription = "Files, folders, and sync across your devices.",
                installedAt = 1_716_000_000_000L,
                verified = true,
                deviceCount = 2,
            ),
            InstalledApp(
                appId = "dev.example.notes",
                name = "Notes (dev build)",
                iconName = "",
                version = "0.0.1",
                shortDescription = "An unsigned development build.",
                installedAt = 1_716_500_000_000L,
                verified = false,
                deviceCount = 1,
            ),
        )

        val DEFAULT_PERMISSIONS = mapOf(
            BundledAppManifestSource.DRIVE_APP_ID to listOf(
                PermissionRow("tenshu/file/service", "tenshu/file/getData", "Read your files", CapabilityClass.Ambient, null, GrantState.Granted, 1_716_400_000_000L),
                PermissionRow("tenshu/file/system", "tenshu/file/system/uploadFiles", "Add files", CapabilityClass.Picker, null, GrantState.AskEachTime, 0L),
                PermissionRow("tenshu/file/system", "tenshu/file/system/deleteFile", "Delete files", CapabilityClass.Picker, null, GrantState.Denied, 0L),
            ),
            "dev.example.notes" to listOf(
                PermissionRow("tenshu/file/service", "tenshu/file/getData", "Read your files", CapabilityClass.Ambient, null, GrantState.AskEachTime, 0L),
            ),
        )
    }
}

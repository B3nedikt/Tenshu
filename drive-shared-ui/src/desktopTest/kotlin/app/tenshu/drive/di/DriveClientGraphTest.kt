package app.tenshu.drive.di

import app.tenshu.drive.di.driveSchemaConfigs
import org.tenshu.core.common.assertClientGraphRegisters
import org.tenshu.contact.ContactService
import org.tenshu.core.file.FileService
import org.tenshu.identity.IdentityService
import org.tenshu.core.notifications.NotificationService
import org.tenshu.preference.PreferenceService
import org.tenshu.storage.StorageService
import org.tenshu.sync.SyncService
import kotlin.test.Test

/**
 * Every service Drive reaches is registered in its client graph.
 *
 * ⚠⚠ The failure this guards is **silent**: `getService` errors on an unregistered schema and the
 * app's repositories catch, so a missing registration renders as absent data rather than as a
 * crash. The announce gate cannot see it either — it compares only services the client declares.
 * `assertClientGraphRegisters` carries the full reasoning and Photos' §18.4 is the worked example.
 *
 * ⚠ [reached] is grepped, not derived — the compiler cannot connect a Gradle dependency to a
 * runtime list two modules away. **When a repository reaches a new service, add it here and to
 * `driveSchemaConfigs`.**
 */
class DriveClientGraphTest {

    private val reached = mapOf(
        "every file listing, upload and preview" to FileService.ID,
        "the device a file modification names" to IdentityService.ID,
        "upload and share notifications" to NotificationService.ID,
        "share targets and member names" to ContactService.ID,
        "synced-folder and backup preferences" to PreferenceService.ID,
        "this device's identity and sync health" to SyncService.ID,
        "the realm storage banner" to StorageService.ID,
    )

    @Test
    fun everyServiceTheAppReachesIsRegistered() =
        assertClientGraphRegisters("Drive", driveSchemaConfigs, reached)
}

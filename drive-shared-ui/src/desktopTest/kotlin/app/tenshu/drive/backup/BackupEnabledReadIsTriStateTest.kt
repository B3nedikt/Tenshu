package app.tenshu.drive.backup

import app.tenshu.ingest.DeviceIdentity
import app.tenshu.ingest.backup.TenshuBackupPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Service
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * `readEnabledOrNull()` says "could not read" instead of "off" (`drive-synced-folders-phase2.md`
 * §4.1a).
 *
 * ⚠⚠ `readEnabled()` answers both of these cases with `false`. A caller that cancels backup jobs on
 * that answer turns automatic backup off whenever a WorkManager cold start finds Control dead or
 * slow, until Drive is next opened.
 */
class BackupEnabledReadIsTriStateTest {

    /** A client whose services cannot be reached, as with no Control bound. */
    private class UnreachableControl : TenshuClient {
        override val environment: TenshuEnvironment
            get() = error("No schema registered for service id: tenshu/preference/service")
        override val proxyConnected: StateFlow<Boolean> = MutableStateFlow(false)
        override fun <T : Service> getService(id: String): T = error("No schema registered for service id: $id")
        override fun init() = Unit
        override fun destroy() = Unit
    }

    @Test
    fun anUnreachableProxy_isUnknown_notOff() = runTest {
        val prefs = TenshuBackupPreferences(UnreachableControl(), DeviceIdentity { "dev1" })

        assertNull(prefs.readEnabledOrNull(), "a failed read must not read as 'backup is off'")
    }

    @Test
    fun anUnresolvedDeviceIdentity_isUnknown_notOff() = runTest {
        val prefs = TenshuBackupPreferences(UnreachableControl(), DeviceIdentity { null })

        assertNull(prefs.readEnabledOrNull())
    }
}

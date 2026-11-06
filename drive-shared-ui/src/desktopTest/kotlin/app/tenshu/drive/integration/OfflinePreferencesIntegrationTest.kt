package app.tenshu.drive.integration

import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 4 DoD for `core-preference-service.md`: pinning a file from Drive
 * persists. Exercises Drive's real [OfflinePreferences] (device-id resolution
 * via `getSyncStatus` + `setForDevice` / `readForDevice`) end-to-end through an
 * in-process proxy — the same client→proxy path the running app uses.
 */
class OfflinePreferencesIntegrationTest {

    private lateinit var appKoin: AppIntegrationKoin

    // Resolved off `appKoin` rather than `by inject()`: the delegate reads the GLOBAL
    // container, which is exactly the ambient lookup this suite no longer has. `single`,
    // so re-resolving per access returns the same instance the lazy delegate would have.
    private val offlinePreferences: OfflinePreferences get() = appKoin.get()

    @After
    fun tearDown() {
        appKoin.stop()
    }

    @Test
    fun offlineFiles_roundTripThroughProxy() = runBlocking {
        appKoin = startAppIntegrationKoin()

        val pinned = listOf("/photos/a.jpg", "/docs/b.pdf")
        offlinePreferences.setOffline(pinned)

        val readBack = withTimeout(10.seconds) { offlinePreferences.observe().first() }
        assertEquals(pinned, readBack, "Drive should read back the device's pinned file list")
    }

    @Test
    fun offlineFiles_unpinPersists() = runBlocking {
        appKoin = startAppIntegrationKoin()

        offlinePreferences.setOffline(listOf("/a", "/b"))
        assertEquals(
            listOf("/a", "/b"),
            withTimeout(10.seconds) { offlinePreferences.observe().first() }
        )

        offlinePreferences.setOffline(listOf("/a"))
        val updated = withTimeout(10.seconds) {
            offlinePreferences.observe().first { it == listOf("/a") }
        }
        assertEquals(listOf("/a"), updated, "unpinning should persist the reduced list")
    }
}

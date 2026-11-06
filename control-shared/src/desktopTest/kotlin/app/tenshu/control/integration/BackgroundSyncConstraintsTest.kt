package app.tenshu.control.integration

import app.tenshu.control.data.backgroundSyncConstraintSpec
import app.tenshu.control.data.backgroundSyncConstraints
import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.tenshu.core.TResult
import org.tenshu.core.core.TContext
import org.tenshu.preference.ControlPreferences
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.set
import org.tenshu.sync.ConstraintSpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * preference-service Phase 5 (`core-preference-service.md` §9 step 6): the
 * background-sync preferences resolve into the [ConstraintSpec] a sync job
 * would carry, and toggling a preference re-emits a fresh constraint.
 *
 * bgproc Phase 5 (ScheduleService) is landed; this covers the preference-side
 * consumer. Actual enforcement of the constraint is bgproc Phase 6
 * (PlatformScheduler, Android) — out of scope here.
 */
class BackgroundSyncConstraintsTest {

    @After
    fun tearDown() = stopAppIntegrationKoin()

    @Test
    fun pureMapping_translatesWifiAndDataCap() = runBlocking {
        val service = startAppIntegrationKoin().preferenceService
        val ctx = service as TContext

        val unmetered = backgroundSyncConstraintSpec(ctx, wifiOnly = true, maxMonthlyDataMb = 500)
        assertEquals(ConstraintSpec.NETWORK_UNMETERED, unmetered.network.get())
        assertEquals(500L, unmetered.maxDataMb?.get())

        val any = backgroundSyncConstraintSpec(ctx, wifiOnly = false, maxMonthlyDataMb = 0)
        assertEquals(ConstraintSpec.NETWORK_ANY, any.network.get())
        assertNull(any.maxDataMb, "a 0 monthly cap should leave maxDataMb absent")
    }

    @Test
    fun reactive_reschedulesConstraintWhenWifiOnlyToggled() = runBlocking {
        val service = startAppIntegrationKoin().preferenceService

        // ControlPreferences.WifiOnly defaults to true → the seeded source flow
        // yields an UNMETERED constraint before any row is ever written.
        val initial = withTimeout(10.seconds) { service.backgroundSyncConstraints().first() }
        assertEquals(ConstraintSpec.NETWORK_UNMETERED, initial.network.get())

        // Toggling it off must re-emit an ANY constraint (the reschedule signal).
        assertTrue(service.set(ControlPreferences.WifiOnly, value = false) is TResult.Success<*>)
        val afterToggle = withTimeout(10.seconds) {
            service.backgroundSyncConstraints()
                .first { it.network.get() == ConstraintSpec.NETWORK_ANY }
        }
        assertEquals(ConstraintSpec.NETWORK_ANY, afterToggle.network.get())
    }
}

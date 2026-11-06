package app.tenshu.control.indexing

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tenshu.inference.proxy.IndexProgressSnapshot
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * *Index now* as a WorkManager long-running worker (`control-background-work-off-fgs.md` §5.6.1):
 * the foreground type per API level, the notification's words and its Stop action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualIndexWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun theTypeIsMediaProcessingFromApi35_andDataSyncBelow() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, manualIndexForegroundType(35))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, manualIndexForegroundType(37))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, manualIndexForegroundType(34))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, manualIndexForegroundType(29))
    }

    @Test
    @Config(sdk = [35])
    fun theForegroundInfoCarriesTheTypeForItsApiLevel_andAStopAction() {
        val snapshot = IndexProgressSnapshot(done = 1240, total = 8500)
        val on35 = manualIndexForegroundInfo(context, UUID.randomUUID(), snapshot, sdkInt = 35)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, on35.foregroundServiceType)
        val on34 = manualIndexForegroundInfo(context, UUID.randomUUID(), snapshot, sdkInt = 34)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, on34.foregroundServiceType)

        val stop = on35.notification.actions?.singleOrNull()
        assertNotNull(stop, "one action: Stop")
        assertEquals("Stop", stop.title.toString())
        assertNotNull(stop.actionIntent, "Stop cancels this work through WorkManager's cancel intent")
    }

    @Test
    fun theTextSaysHowFarIn_inTheDevicesNumbers() {
        val snapshot = IndexProgressSnapshot(done = 1240, total = 8500)
        assertEquals("Indexing photos — 1,240 of 8,500", manualIndexProgressText(context, snapshot, Locale.US))
    }

    @Test
    @Config(sdk = [35], qualifiers = "de")
    fun theGermanTextUsesGermanNumbers() {
        val snapshot = IndexProgressSnapshot(done = 1240, total = 8500)
        assertEquals("Fotos werden indexiert — 1.240 von 8.500", manualIndexProgressText(context, snapshot, Locale.GERMANY))
    }

    @Test
    fun beforeTheWalkHasATotal_itSaysIndexingWithoutNumbers() {
        assertEquals("Indexing photos…", manualIndexProgressText(context, null, Locale.US))
        assertEquals("Indexing photos…", manualIndexProgressText(context, IndexProgressSnapshot(done = 0, total = 0), Locale.US))
    }
}

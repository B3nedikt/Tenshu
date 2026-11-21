package app.tenshu.drive.integration

import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.TContext
import org.tenshu.core.file.notifications.ChangeLostNotification
import org.tenshu.core.notifications.dismissPendingRequest
import org.tenshu.core.notifications.sendNotification
import org.tenshu.notifications.client.notificationService
import kotlin.test.assertEquals

/**
 * The change-lost banner's dismissal over the real in-process notification service
 * (`control-central-notifications.md` §10 D1).
 *
 * ⚠⚠ The defect this pins: dismissals were held in memory, so the same "was deleted" notice came
 * back on every restart for as long as it stayed in the newest-50 window — days, on a quiet realm.
 */
class ChangeLostDismissalIntegrationTest {

    private lateinit var appKoin: AppIntegrationKoin
    private lateinit var client: TenshuClient
    private lateinit var repository: FilesRepository

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
        client = appKoin.get()
        repository = appKoin.get()
    }

    @After
    fun tearDown() {
        appKoin.stop()
    }

    private suspend fun sendNotice(uuid: String, timestamp: Long) {
        val notice = ChangeLostNotification.create(
            context = client.notificationService as TContext,
            uuid = uuid,
            timestamp = timestamp,
            fileName = "$uuid.heic",
            path = "/Backup/$uuid.heic",
        )
        client.notificationService.sendNotification(notice)
    }

    private suspend fun shownIds(expected: List<String>): List<String> =
        withTimeout(5_000) { repository.changesLost().first { notices -> notices.map { it.id } == expected } }
            .map { it.id }

    @Test
    fun aDismissedNoticeStaysGone_includingForAFreshReader() = runBlocking {
        sendNotice("a", timestamp = 1_000)
        sendNotice("b", timestamp = 2_000)
        assertEquals(listOf("b", "a"), shownIds(listOf("b", "a")), "both notices, newest first")

        repository.dismissChangeLost("b")

        assertEquals(listOf("a"), shownIds(listOf("a")), "the dismissed notice is gone")
        // A fresh subscription reads only what is stored — the in-memory hide plays no part here.
        assertEquals(listOf("a"), repository.changesLost().first().map { it.id }, "and stays gone on a re-read")
    }

    @Test
    fun aPendingRequestMarkerHidesNoNotice() = runBlocking {
        sendNotice("grant:x", timestamp = 1_000)
        // The same relation holds pending-request "seen" markers, keyed by their bare id.
        client.notificationService.dismissPendingRequest("grant:x")

        assertEquals(listOf("grant:x"), shownIds(listOf("grant:x")), "only a notification: marker hides a notice")
    }
}

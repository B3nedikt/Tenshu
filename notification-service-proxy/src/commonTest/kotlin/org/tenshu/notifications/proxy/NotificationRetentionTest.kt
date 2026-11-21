package org.tenshu.notifications.proxy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.notifications.AppRegisteredNotification
import org.tenshu.core.notifications.DismissedRequest
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.notifications.dismissedNotificationMarker
import org.tenshu.notifications.test.createNotificationProxy
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.TombstoneEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `control-central-notifications.md` §10 D2: the notification log is capped by construction — the
 * newest 200, within 30 days of the newest. ⚠⚠ Before it, nothing removed a notification anywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRetentionTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun theRule_keepsTheNewestRowsWithinTheWindowOfTheNewest() {
        val rows = (1..250).map { "n$it" to it * 1_000L }
        val doomed = notificationsToPrune(rows)
        assertEquals((1..50).map { "n$it" }.toSet(), doomed, "the 50 oldest go, the newest 200 stay")

        val aged = listOf("new" to 40 * day, "edge" to 10 * day, "old" to 10 * day - 1)
        assertEquals(setOf("old"), notificationsToPrune(aged), "only rows more than 30 days before the newest")

        assertEquals(emptySet(), notificationsToPrune(emptyList()))
    }

    @Test
    fun theRule_breaksTiesTheSameWayOnEveryDevice() {
        val rows = listOf("b" to 5L, "a" to 5L, "c" to 5L)
        assertEquals(setOf("c"), notificationsToPrune(rows, keepRows = 2), "same timestamp ⇒ by uuid")
        assertEquals(setOf("c"), notificationsToPrune(rows.reversed(), keepRows = 2), "whatever the read order")
    }

    @Test
    fun aPass_removesAgedNoticesAndTheirDismissalMarkers() = runTest {
        val proxy = createNotificationProxy(this)
        save(proxy, "old", timestamp = 1 * day)
        save(proxy, "kept", timestamp = 35 * day)
        save(proxy, "newest", timestamp = 40 * day)
        proxy.executionContext.saveData(dismissed(proxy, dismissedNotificationMarker("old")))
        proxy.executionContext.saveData(dismissed(proxy, dismissedNotificationMarker("kept")))
        proxy.executionContext.saveData(dismissed(proxy, "grant:someone"))
        advanceUntilIdle()

        val removed = NotificationRetention(proxy.executionContext, scope = this, tombstones = null).prune()

        assertEquals(1, removed)
        assertEquals(setOf("kept", "newest"), notificationUuids(proxy))
        assertEquals(
            setOf(dismissedNotificationMarker("kept"), "grant:someone"),
            markers(proxy),
            "the pruned notice's marker goes with it; nothing else's does",
        )
    }

    @Test
    fun localSaves_keepTheLogAtTheCap() = runTest {
        val proxy = createNotificationProxy(this)
        repeat(NotificationRetention.RETAIN_ROWS + 1) { save(proxy, "n$it", timestamp = it.toLong()) }
        settle()

        assertEquals(NotificationRetention.RETAIN_ROWS, notificationUuids(proxy).size)
        assertEquals(false, "n0" in notificationUuids(proxy), "the oldest went")
    }

    @Test
    fun aPass_tombstonesEveryRowItDeletes() = runTest {
        val proxy = createNotificationProxy(this)
        seedOneAgedNoticeWithMarker(proxy)
        val emitter = RecordingEmitter()

        NotificationRetention(proxy.executionContext, scope = this, tombstones = holderOf(emitter)).prune()

        assertEquals(
            setOf(AppRegisteredNotification.ID, DismissedRequest.ID),
            emitter.emitted.map { it.entityType }.toSet(),
            "⚠⚠ the notice AND its marker reach the other devices — a local delete alone reaches none",
        )
        assertEquals(setOf("kept"), notificationUuids(proxy))
    }

    @Test
    fun aPass_deletesNothingItCouldNotTombstone() = runTest {
        val proxy = createNotificationProxy(this)
        seedOneAgedNoticeWithMarker(proxy)

        // Sync not up yet: the holder exists but its emitter does not.
        NotificationRetention(proxy.executionContext, scope = this, tombstones = TombstoneEmitterHolder()).prune()
        assertEquals(setOf("old", "kept"), notificationUuids(proxy), "no emitter yet ⇒ wait, never delete locally")

        // The emitter could not name the rows for the wire.
        NotificationRetention(proxy.executionContext, scope = this, tombstones = holderOf(RecordingEmitter(refuse = true))).prune()
        assertEquals(setOf("old", "kept"), notificationUuids(proxy), "unnameable ⇒ keep the ids that could tombstone them later")
    }

    private fun kotlinx.coroutines.test.TestScope.seedOneAgedNoticeWithMarker(proxy: TestProxy) {
        save(proxy, "old", timestamp = 1 * day)
        save(proxy, "kept", timestamp = 40 * day)
        proxy.executionContext.saveData(dismissed(proxy, dismissedNotificationMarker("old")))
        settle()
        // The wired observer (no sync in this proxy) prunes on the first local save; put both back.
        // Saves within the 20-save interval trigger no further pass.
        save(proxy, "old", timestamp = 1 * day)
        proxy.executionContext.saveData(dismissed(proxy, dismissedNotificationMarker("old")))
        settle()
    }

    private class RecordingEmitter(private val refuse: Boolean = false) : TombstoneEmitter {
        val emitted = mutableListOf<TombstoneEmitter.Target>()
        override fun emit(targetEntityIdHash: String, targetEntityType: String, revokedDeviceId: String?): String? =
            error("prune must name entities by DataId, never by a hash it computed")
        override fun emit(targetEntityId: DataId, targetEntityType: String, revokedDeviceId: String?): String? =
            error("prune must emit one batch")
        override fun emitAll(targets: List<TombstoneEmitter.Target>): List<String>? {
            if (refuse) return null
            emitted += targets
            return targets.map { "block-${it.entityType}" }
        }
    }

    private fun holderOf(emitter: TombstoneEmitter) = TombstoneEmitterHolder().also { it.emitter = emitter }

    @Test
    fun syncIngest_neverTriggersAPass() = runTest {
        val proxy = createNotificationProxy(this)
        repeat(NotificationRetention.RETAIN_ROWS + 1) {
            proxy.executionContext.saveData(notice(proxy, "n$it", it.toLong()), origin = WriteOrigin.SYNC_INGEST)
        }
        settle()

        assertEquals(NotificationRetention.RETAIN_ROWS + 1, notificationUuids(proxy).size, "a catching-up device prunes nothing")
    }

    /**
     * ⚠ The pass rides `backgroundScope`, and `advanceUntilIdle` counts background work as idle — it
     * returns without running it. `runCurrent` does run it.
     */
    private fun kotlinx.coroutines.test.TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    private fun notice(proxy: TestProxy, uuid: String, timestamp: Long) =
        AppRegisteredNotification.create(proxy.executionContext.context, uuid = uuid, timestamp = timestamp, appName = "App")

    private fun save(proxy: TestProxy, uuid: String, timestamp: Long) =
        proxy.executionContext.saveData(notice(proxy, uuid, timestamp))

    private fun dismissed(proxy: TestProxy, requestId: String) =
        DismissedRequest.create(proxy.executionContext.context, requestId)

    private fun notificationUuids(proxy: TestProxy): Set<String> =
        proxy.executionContext.getArrayData(proxy.executionContext.relationReference(NotificationService.Relations.NOTIFICATIONS))
            ?.toList().orEmpty().filterIsInstance<Notification>().map { it.uuid.get() }.toSet()

    private fun markers(proxy: TestProxy): Set<String> =
        proxy.executionContext.getArrayData(proxy.executionContext.relationReference(NotificationService.Relations.DISMISSED_REQUESTS))
            ?.toList().orEmpty().filterIsInstance<DismissedRequest>().map { it.requestId.get() }.toSet()
}

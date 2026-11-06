package org.tenshu.notifications.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.notifications.DismissPendingRequest
import org.tenshu.core.notifications.DismissedRequest
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.toTString
import org.tenshu.notifications.test.createNotificationProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 4 (synced dismissal): `DismissPendingRequest` records a `DismissedRequest`
 * `Syncable(Eager)` marker, keyed by the request id, surfaced through the
 * `dismissedRequests` relation. Mirrors `ProxyNotificationServiceUnitTest`.
 */
class DismissPendingRequestUnitTest {

    @Test
    fun dismissRecordsAMarkerAndIsIdempotent() = runTest {
        val testProxy = createNotificationProxy(this)
        val requestId = "grant:fingerprintABCDEF"

        suspend fun dismiss(): ByteArray = testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(NotificationService.ID),
                *idToUuid(DismissPendingRequest.ID),
                *testProxy.serializer.serialize(requestId.toTString(testProxy.executionContext.context))
            )
        )

        assertContentEquals(
            expected = byteArrayOf(*idToUuid(TResult.Success.ID)),
            actual = dismiss(),
        )

        suspend fun readDismissed(flowId: Long): List<DismissedRequest> {
            testProxy.handleRequest(
                byteArrayOf(
                    *idToUuid(NotificationService.ID),
                    *idToUuid(NotificationService.Relations.DISMISSED_REQUESTS),
                    *idToUuid(Stream.ID),
                    *flowId.toByteArray()
                )
            )
            val updates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
                .filter { it.streamId.get() == flowId }
            assertTrue(updates.isNotEmpty())
            @Suppress("UNCHECKED_CAST")
            return (updates.last().payload as TList<DismissedRequest>).toList()
        }

        val dismissed = readDismissed(flowId = 1L)
        assertEquals(1, dismissed.size)
        assertEquals(requestId, dismissed.first().requestId.get())

        // Re-dismissing the same id folds to one row (keyed by requestId), not two —
        // the union-merge property that makes concurrent multi-device dismissals safe.
        dismiss()
        assertEquals(1, readDismissed(flowId = 2L).size)
    }
}

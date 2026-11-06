package org.tenshu.timeline.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.timeline.RecordTrip
import org.tenshu.core.timeline.TimelineService
import org.tenshu.core.timeline.Trip
import org.tenshu.timeline.test.createTimelineProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordTripUnitTest {

    @Test
    fun recordTripRoundTrips() = runTest {
        val testProxy = createTimelineProxy(this)
        val context = testProxy.executionContext.context

        val trip = Trip.create(
            context = context,
            tripId = "trip-1",
            title = "Lisbon",
            dateRange = "Apr 12 – 17",
            year = "2026",
        )

        val request = byteArrayOf(
            *idToUuid(TimelineService.ID),
            *idToUuid(RecordTrip.ID),
            *testProxy.serializer.serialize(trip),
        )

        val resultBytes = testProxy.handleRequest(request)
        assertContentEquals(
            expected = byteArrayOf(*idToUuid(TResult.Success.ID)),
            actual = resultBytes,
        )

        val flowId = 1L
        val tripsRequest = byteArrayOf(
            *idToUuid(TimelineService.ID),
            *idToUuid(TimelineService.Relations.TRIPS),
            *idToUuid(Stream.ID),
            *flowId.toByteArray(),
        )
        testProxy.handleRequest(tripsRequest)

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val trips = (valueUpdates.first().payload as TList<Trip>).toList()
        assertEquals(1, trips.size)

        val received = trips.first()
        assertEquals("trip-1", received.tripId.get())
        assertEquals("Lisbon", received.title.get())
        assertEquals("Apr 12 – 17", received.dateRange.get())
        assertEquals("2026", received.year.get())
    }
}

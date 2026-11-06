package org.tenshu.place.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceService
import org.tenshu.core.place.RecordPlace
import org.tenshu.place.test.createPlaceProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordPlaceUnitTest {

    @Test
    fun recordPlaceRoundTrips() = runTest {
        val testProxy = createPlaceProxy(this)
        val context = testProxy.executionContext.context

        val place = Place.create(
            context = context,
            placeId = "place-1",
            latE7 = 385_000_000L,
            lngE7 = -1_224_000_000L,
            capturedAt = UnixInstantDto(123_456_789L),
            label = "Blue Bottle",
        )

        val request = byteArrayOf(
            *idToUuid(PlaceService.ID),
            *idToUuid(RecordPlace.ID),
            *testProxy.serializer.serialize(place),
        )

        val resultBytes = testProxy.handleRequest(request)
        assertContentEquals(
            expected = byteArrayOf(*idToUuid(TResult.Success.ID)),
            actual = resultBytes,
        )

        // Read back the places relation stream.
        val flowId = 1L
        val placesRequest = byteArrayOf(
            *idToUuid(PlaceService.ID),
            *idToUuid(PlaceService.Relations.PLACES),
            *idToUuid(Stream.ID),
            *flowId.toByteArray(),
        )
        testProxy.handleRequest(placesRequest)

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val places = (valueUpdates.first().payload as TList<Place>).toList()
        assertEquals(1, places.size)

        val received = places.first()
        assertEquals("place-1", received.placeId.get())
        assertEquals(385_000_000L, received.latE7.get())
        assertEquals(-1_224_000_000L, received.lngE7.get())
        assertEquals("Blue Bottle", received.label.get())
    }
}

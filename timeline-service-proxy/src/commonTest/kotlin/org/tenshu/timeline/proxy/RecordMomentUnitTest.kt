package org.tenshu.timeline.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.RecordMoment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService
import org.tenshu.timeline.test.createTimelineProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordMomentUnitTest {

    @Test
    fun recordMomentWithSubjectRoundTrips() = runTest {
        val testProxy = createTimelineProxy(this)
        val context = testProxy.executionContext.context

        // A Visit moment whose subject points at a Place in :place-service (Option A ref).
        val subject = SubjectRef.create(context, kind = SubjectKind.PLACE, id = "place-123")
        val moment = Moment.create(
            context = context,
            momentId = "moment-1",
            timestamp = UnixInstantDto(1_700_000_000_000L),
            kind = Moment.MomentKind.VISIT,
            title = "Blue Bottle",
            subject = subject,
            durationSec = 4_920,
        )

        val request = byteArrayOf(
            *idToUuid(TimelineService.ID),
            *idToUuid(RecordMoment.ID),
            *testProxy.serializer.serialize(moment),
        )

        val resultBytes = testProxy.handleRequest(request)
        assertContentEquals(
            expected = byteArrayOf(*idToUuid(TResult.Success.ID)),
            actual = resultBytes,
        )

        // Read back the moments relation stream.
        val flowId = 1L
        val momentsRequest = byteArrayOf(
            *idToUuid(TimelineService.ID),
            *idToUuid(TimelineService.Relations.MOMENTS),
            *idToUuid(Stream.ID),
            *flowId.toByteArray(),
        )
        testProxy.handleRequest(momentsRequest)

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val moments = (valueUpdates.first().payload as TList<Moment>).toList()
        assertEquals(1, moments.size)

        val received = moments.first()
        assertEquals("moment-1", received.momentId.get())
        assertEquals(Moment.MomentKind.VISIT, received.kind.get())
        assertEquals("Blue Bottle", received.title.get())
        assertEquals(4_920L, received.durationSec.get())
        // The polymorphic subject (Option A) round-trips as an embedded composite.
        assertEquals(SubjectKind.PLACE, received.subject.kind.get())
        assertEquals("place-123", received.subject.id.get())
    }
}

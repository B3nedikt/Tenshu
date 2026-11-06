@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.WorkoutStreamsJournal
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.externalPropertyWriter
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.health.ContentId
import org.tenshu.core.health.Streams
import org.tenshu.core.health.Workout
import org.tenshu.health.common.HrSample
import org.tenshu.health.common.RouteSample
import org.tenshu.health.common.WorkoutStreams
import org.tenshu.health.common.WorkoutStreamsCodec
import org.tenshu.health.proxy.workoutStreamsPath
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fit run B, "Streams on web": a workout's `Workout.Streams` must travel on the **content plane** in a
 * tab, as it does natively. Native registers `WorkoutStreamsPropertyWriter` in the
 * `ExternalPropertyWriter` fan-out beside `FileBytesPropertyWriter` (`PlatformProxyModule`, all three
 * platforms); the JS runtime held the file writer alone ("the web SDK has no health service"), which
 * broke workout streams in both directions without one error:
 *
 *  - **inbound** — `OnDemandResolutionImpl` asks the port for the row's `contentId` and, on `null`,
 *    takes the entity-keyed block fetch, where a phone's content-plane workout has no manifest. And
 *    both landing paths (`FileContentManifestApplier`, `SyncProcessor.attachPropertyAndSave`) refuse
 *    a property no writer `supports`, so even fetched bytes had nowhere to go: a phone's route and
 *    heart rate never showed in the web detail.
 *  - **outbound** — a web import's emit resolved no `contentId` either, so its upload was journalled
 *    and published on the legacy entity-keyed branch, although the row names a `contentId`.
 *
 * ⚠ The relay is the unreachable sink, so the test asserts the routing decisions the port makes and
 * the bytes landing where the detail reads them, not a round trip; that is the dev-lab pass.
 */
class WorkoutStreamsOnWebTest : SdkRuntimeTest() {

    private val ctx get() = JsRuntime.tenshuClient.environment.tContext

    private fun farStart(): Long =
        LocalDate.fromEpochDays(LocalDate(2300, 1, 1).toEpochDays() + (0..220_000).random())
            .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 9 * 3_600_000L

    private fun holder(workoutId: String): DataId =
        DataId.StringDataId.create(context = ctx, type = Workout.ID, keyValue = workoutId)

    /**
     * A phone-recorded workout as sync ingest stores it: the row names its content id, and the
     * streams are not local (they live on the content plane until a read fetches them).
     */
    @Test
    fun aSyncedWorkout_routesItsStreamsToTheContentPlane_andTheirBytesLandWhereTheDetailReads() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val ec = JsRuntime.executionContext
        val start = farStart()
        val id = "phone-${Date.now().toLong()}-${(0..999_999).random()}"
        val contentId = "content-$id"
        ec.saveData(
            Entity.create<Workout>(
                context = ctx,
                composite = Workout.create(
                    ctx, id, "Running", "HealthConnect", UnixInstantDto(start), UnixInstantDto(start + 600_000),
                    durationSec = 600, distanceM = 1_000,
                ),
            ) { set(Workout.ContentId, contentId) },
            origin = WriteOrigin.SYNC_INGEST,
        )

        val port = assertNotNull(ec.context.externalPropertyWriter(), "the runtime registers a writer")
        assertTrue(port.supports(Workout.Streams.id), "a workout's streams have a writer to land on")
        assertEquals(
            contentId,
            port.resolveContentId(ec, holder(id), Workout.Streams.id),
            "the read must fetch the content-plane manifest by the row's content id",
        )
        assertEquals(workoutStreamsPath(id), port.resolveExternalPath(ec, holder(id), Workout.Streams.id))

        // What the applier does once the chunks verify: write the bytes at the writer's path.
        val streams = WorkoutStreams(
            route = listOf(RouteSample(0, 525_200_000, 134_050_000, 3_400), RouteSample(600, 525_230_000, 134_090_000, 3_500)),
            heartRate = listOf(HrSample(0, 118), HrSample(600, 152)),
        )
        port.write(ec, holder(id), Workout.Streams.id, TByteArrayDto(WorkoutStreamsCodec.encode(streams)))

        val detail = Fit.workout(id, "UTC").await()
        assertEquals(true, detail.ok, "detail: ${JSON.stringify(detail)}")
        assertEquals(2, detail.workout.route.unsafeCast<Array<dynamic>>().size, "the phone's route shows")
        assertEquals(
            listOf(118, 152),
            detail.workout.heartRate.unsafeCast<Array<dynamic>>().map { it.bpm as Int },
            "the phone's heart rate shows",
        )
    }

    /** A web import publishes its streams under the row's content id, never the legacy entity key. */
    @Test
    fun aWebImport_journalsItsStreamsUnderTheRowsContentId() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val created = !JsRuntime.syncManager.isEnabled()
        if (created) Realm.create(Bip39.generateMnemonic(), "streams-test-device").await()
        try {
            assertTrue(JsRuntime.syncManager.isEnabled(), "precondition: the runtime holds a realm")
            val ec = JsRuntime.executionContext
            val start = farStart()
            val id = "gpx-$start"
            val result = Fit.importActivityFile("run.gpx", gpx(start), "UTC").await()
            assertEquals(true, result.ok, "import: ${JSON.stringify(result)}")
            assertEquals(listOf(id), result.streamsPending.unsafeCast<Array<String>>().toList())

            val stored = assertNotNull(WorkoutStreamsJournal.storedContentId(ec, id), "the row names a content id")
            assertEquals(
                listOf(stored),
                WorkoutStreamsJournal.journalledContentIds(ec, id),
                "exactly one upload, keyed by the row's content id (an empty id is the legacy branch)",
            )

            // A retry re-records under a fresh content id. ⚠ The journal is keyed by content now, so
            // the superseded intent is retired at once (`discardSuperseded`); left to the drain, the
            // same bytes at the same path would publish a second time under a dead id.
            val retry = Fit.importActivityFile("run (1).gpx", gpx(start), "UTC").await()
            assertEquals(true, retry.ok, "retry: ${JSON.stringify(retry)}")
            val restored = assertNotNull(WorkoutStreamsJournal.storedContentId(ec, id))
            assertTrue(restored != stored, "precondition: the retry re-recorded")
            assertEquals(listOf(restored), WorkoutStreamsJournal.journalledContentIds(ec, id), "only the live content is queued")
        } finally {
            if (created) JsRuntime.syncManager.lock()
        }
    }

    private fun gpx(startMs: Long): ByteArray {
        val t0 = Date(startMs.toDouble()).toISOString()
        val t1 = Date((startMs + 600_000).toDouble()).toISOString()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <trk><name>Run</name><type>running</type><trkseg>
                <trkpt lat="52.5200" lon="13.4050"><time>$t0</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
                <trkpt lat="52.5230" lon="13.4090"><time>$t1</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>150</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent().encodeToByteArray()
    }
}

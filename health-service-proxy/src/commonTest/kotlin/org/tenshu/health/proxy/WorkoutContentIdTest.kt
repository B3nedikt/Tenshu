package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TString
import org.tenshu.health.proxy.executor.RecordWorkoutImplementation
import org.tenshu.core.toTByteArray
import org.tenshu.core.health.RecordWorkout
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.health.ContentId
import org.tenshu.core.health.Streams
import org.tenshu.core.health.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Workout.ContentId` (`content-plane-refcount.md` §9.12) — the identity a workout's `Streams`
 * content is addressed by.
 *
 * ⚠⚠ Two properties depend on it, and only one is reclamation:
 *
 * - an id routes the manifest to the **content plane**; its absence routes it to a block on the
 *   ordered metadata drain, which joiner catch-up pays for at a measured 7.5 seq/s, permanently.
 * - a re-record mints a **new** id, leaving the superseded content declared-with-no-live-edge — a
 *   CR-5 candidate rather than an orphan under an id that is still declared.
 */
class WorkoutContentIdTest {

    private fun writer() = WorkoutStreamsPropertyWriter(fileDatabase = InMemoryFileDatabase())

    @Test
    fun aWorkoutWithNoContentIdKeepsTheLegacyBranch() = runTest {
        // ⚠ `null` is a valid answer, and it is what routes the emit down the entity-id-keyed
        // branch. A summary-only workout owns no content; resolving one for it would address a
        // manifest that does not exist.
        val proxy = createHealthProxy()
        val id = proxy.saveWorkout(workoutId = "w-1", contentId = null)

        assertNull(writer().resolveContentId(proxy.executionContext, id, Workout.Streams.id))
    }

    @Test
    fun aWorkoutWithAContentIdIsContentAddressed() = runTest {
        val proxy = createHealthProxy()
        val id = proxy.saveWorkout(workoutId = "w-1", contentId = "cid-abc")

        assertEquals("cid-abc", writer().resolveContentId(proxy.executionContext, id, Workout.Streams.id))
    }

    @Test
    fun theWriterAnswersOnlyForItsOwnProperty() = runTest {
        // ⚠ `supports` gates it, or a File property asked of this writer would get a workout's id
        // and address the wrong content.
        val proxy = createHealthProxy()
        val id = proxy.saveWorkout(workoutId = "w-1", contentId = "cid-abc")

        assertNull(writer().resolveContentId(proxy.executionContext, id, "tenshu/file/data"))
    }

    @Test
    fun contentIdIsAnIdentity_soTwoWorkoutsNeverShareOne() = runTest {
        // ⚠⚠ The id names CONTENT, never the workout. A `workoutId`-derived id would be stable
        // across a re-emit, so the superseded chunks would be orphaned under an id that is STILL
        // declared — unreachable by CR-5 and only ever by a subtractive sweep.
        val proxy = createHealthProxy()
        val a = proxy.saveWorkout(workoutId = "w-1", contentId = "cid-first")
        val reRecorded = proxy.saveWorkout(workoutId = "w-1", contentId = "cid-second")

        assertEquals(a, reRecorded, "precondition: the same workout, re-recorded")
        assertEquals(
            "cid-second",
            writer().resolveContentId(proxy.executionContext, reRecorded, Workout.Streams.id),
            "the live edge must name the NEW id; the old one is what becomes a CR-5 candidate",
        )
    }


    /**
     * CR-2 B6 §1: a record — first or re-record — writes no reference root any more. The relay holds no
     * handles, so nothing would read one.
     */
    @Test
    fun aRecordAndAReRecordWriteNoReferenceRoot() = runTest {
        val proxy = createHealthProxy()
        val first = proxy.recordWithStreams(workoutId = "w-1")
        assertTrue(!proxy.contentIdOf(first).isNullOrEmpty(), "control: the record minted its content id")
        assertTrue(proxy.storedPropertyIds(first).none { it.endsWith("/referenceRoot") }, "a record mints no root")

        val second = proxy.recordWithStreams(workoutId = "w-1")
        assertEquals(first, second, "precondition: the same workout, re-recorded")
        assertTrue(proxy.storedPropertyIds(second).none { it.endsWith("/referenceRoot") }, "nor does a re-record")
    }


    // --- §10.12 Q5 (option d): a bare composite never clears content -----------------------------

    /**
     * ⭐ The Q5 case: Health Connect re-syncs a workout with route consent withheld, so the same id comes
     * back summary-only. Saving the bare composite used to wipe `ContentId`; under
     * CR-2 B that reaches the relay as `(Streams, none)` and the streams are freed after T.
     */
    @Test
    fun aSummaryReRecordKeepsTheStreamsReference() = runTest {
        val proxy = createHealthProxy()
        val id = proxy.recordWithStreams(workoutId = "w-1")
        val contentId = proxy.contentIdOf(id)
        assertTrue(!contentId.isNullOrEmpty(), "precondition: streams recorded")

        proxy.recordSummary(workoutId = "w-1", durationSec = 42)

        assertEquals(contentId, proxy.contentIdOf(id), "the summary must not clear the streams' content id")
        assertEquals(42L, proxy.durationOf(id), "while the summary's own fields still update")
        val stored = proxy.executionContext.database.getData(id, asEntity = true)!!
        assertEquals(
            listOf(contentId),
            org.tenshu.core.common.content.ContentDeclarations
                .of(proxy.executionContext.schemaProvider as org.tenshu.core.common.proxy.SchemaRegistry)
                .edgesOf(stored)!!.map { it.contentId },
            "so the row B3d pushes still names the streams, and the relay keeps the edge",
        )
    }

    @Test
    fun anEntitySaveWithoutTheSlotStillClears_soADeliberateClearIsExpressible() = runTest {
        // ⚠ Only a BARE composite keeps content. An Entity is a complete statement of the row.
        val proxy = createHealthProxy()
        val id = proxy.recordWithStreams(workoutId = "w-1")

        proxy.saveWorkout(workoutId = "w-1", contentId = null, asEntity = true)

        assertNull(proxy.contentIdOf(id))
    }

    @Test
    fun aPeersBareRowIsAppliedAsWritten() = runTest {
        // ⚠ Sync ingest is whole-row LWW: the carry-over is the WRITER's job, never the receiver's,
        // or this device's row and the relay's edges would disagree.
        val proxy = createHealthProxy()
        val id = proxy.recordWithStreams(workoutId = "w-1")

        proxy.executionContext.saveData(proxy.bareWorkout("w-1"), origin = org.tenshu.core.common.proxy.WriteOrigin.SYNC_INGEST)

        assertNull(proxy.contentIdOf(id))
    }

    @Test
    fun aFreshSummaryRecordStaysBare() = runTest {
        val proxy = createHealthProxy()
        val id = proxy.recordSummary(workoutId = "w-new", durationSec = 1)

        assertNull(proxy.contentIdOf(id))
    }

    // --- harness ------------------------------------------------------------------------------

    private class Proxy(val executionContext: org.tenshu.core.common.proxy.ExecutionContext, val ctx: org.tenshu.core.core.TContext) {
        fun bareWorkout(workoutId: String, durationSec: Long = 1) = Workout.create(
            context = ctx,
            workoutId = workoutId,
            activityType = "run",
            source = "test",
            start = UnixInstantDto(1_700_000_000_000L),
            end = UnixInstantDto(1_700_003_600_000L),
            durationSec = durationSec,
        )

        suspend fun saveWorkout(workoutId: String, contentId: String?, asEntity: Boolean = false): DataId {
            val base = Workout.create(
                context = ctx,
                workoutId = workoutId,
                activityType = "run",
                source = "test",
                start = UnixInstantDto(1_700_000_000_000L),
                end = UnixInstantDto(1_700_003_600_000L),
                durationSec = 1,
            )
            val entity = if (contentId == null && !asEntity) base else org.tenshu.core.entity.Entity.create<Workout>(
                context = ctx,
                composite = base,
            ) { if (contentId != null) set(Workout.ContentId, contentId) }
            executionContext.saveData(entity)
            return DataId.StringDataId.create(ctx, Workout.ID, workoutId)
        }
    }

    /**
     * Records through the REAL [RecordWorkoutImplementation].
     *
     * ⚠ Not a hand-written `set(Workout.ContentId, …)`: the rule under test lives in that
     * implementation's mint block, and a fixture that wrote the property itself would pass with the
     * production code reverted — which is exactly how this site went untested in the first place.
     */
    private suspend fun Proxy.recordWithStreams(workoutId: String): DataId {
        val workout = Workout.create(
            context = ctx,
            workoutId = workoutId,
            activityType = "run",
            source = "test",
            start = UnixInstantDto(1_700_000_000_000L),
            end = UnixInstantDto(1_700_003_600_000L),
            durationSec = 1,
        )
        RecordWorkoutImplementation(fileDatabase = InMemoryFileDatabase())
            .execute<Data?>(
                executionContext = executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = mapOf(
                    RecordWorkout.Params.WORKOUT to workout,
                    RecordWorkout.Params.STREAMS to byteArrayOf(1, 2, 3).toTByteArray(ctx),
                ),
            )
        return DataId.StringDataId.create(ctx, Workout.ID, workoutId)
    }

    /** Records summary-only through the REAL [RecordWorkoutImplementation] (no STREAMS param). */
    private suspend fun Proxy.recordSummary(workoutId: String, durationSec: Long): DataId {
        RecordWorkoutImplementation(fileDatabase = InMemoryFileDatabase())
            .execute<Data?>(
                executionContext = executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = mapOf(RecordWorkout.Params.WORKOUT to bareWorkout(workoutId, durationSec)),
            )
        return DataId.StringDataId.create(ctx, Workout.ID, workoutId)
    }

    private fun Proxy.contentIdOf(id: DataId): String? =
        (executionContext.database.getData(id, asEntity = true) as? org.tenshu.core.entity.Entity<*>)
            ?.getProperty<Data>(Workout.ContentId.id)?.let { it as? TString }?.get()

    private fun Proxy.durationOf(id: DataId): Long? =
        (executionContext.database.getData(id) as? Workout)?.durationSec?.get()

    private fun Proxy.storedPropertyIds(id: DataId): Set<String> =
        (executionContext.database.getData(id, asEntity = true) as? org.tenshu.core.entity.Entity<*>)
            ?.properties?.keys?.map { it.get() }?.toSet().orEmpty()

    private fun createHealthProxy(): Proxy {
        val p = org.tenshu.core.common.TestProxy(
            schemaRegistry = org.tenshu.core.common.createCoreTestRegistry().apply {
                registerConfig(org.tenshu.health.common.healthServiceCommonModule)
            },
        )
        return Proxy(p.executionContext, p.tContext)
    }
}

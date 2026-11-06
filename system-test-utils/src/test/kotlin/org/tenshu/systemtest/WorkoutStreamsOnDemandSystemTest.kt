package org.tenshu.systemtest

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.equalTo
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.health.Streams
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordWorkout
import org.tenshu.health.client.healthService
import org.tenshu.core.collection.list.toList
import org.tenshu.health.proxy.workoutStreamsPath
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The health twin of [FileBytesOnDemandSystemTest] — proves `Workout.Streams`
 * genuinely rides the content plane cross-device (read-scaling option B,
 * `fit-workout-streams-content-plane.md` B2):
 *
 *  - the host's `recordWorkout(streams)` emits the blob as CEK-sealed chunks +
 *    an entity-id-addressed manifest (no `contentId` on a Workout), NOT inside
 *    the eager entity block;
 *  - the joiner converges on the small `Workout` entity with **no blob bytes
 *    ingested** (the manifest is applied OnDemand-only — the pre-option-B
 *    behaviour shipped the ~56 KiB blob into every receiver's store);
 *  - the fit detail-read chain (search → select(relations(Streams)) → Maybe
 *    `get()`, the shape `FitProjectionRepository.workout(id)` uses) triggers
 *    `OnDemandResolution` on the joiner, which fetches, decrypts and gates the
 *    chunks into the joiner's `FileDatabase` under `/.health/streams/{id}`.
 */
class WorkoutStreamsOnDemandSystemTest {

    @Test fun joinerFetchesWorkoutStreamsOnDemand() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client(
                deviceId = "host",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 1_000
            )
            val joiner = system.client(
                deviceId = "joiner",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 1_000
            )

            host.bootstrapAsHost(deviceName = "Host Tablet")
            host.tenshuClient.syncService.startPairing()
            val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)

            joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner Phone",
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            val request = host.tenshuClient.syncService.getPendingPairingRequests()
                .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
            host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
            system.advanceUntil(timeout = 30.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            val workoutId = "workout-ondemand-1"
            val streamPath = workoutStreamsPath(workoutId)
            // Big enough to span multiple content-plane chunk boundaries is not
            // the point here (chunking is covered by the file twin) — distinct
            // bytes that must round-trip exactly are.
            val payload = ByteArray(48 * 1024) { (it % 251).toByte() }

            val workout = Workout.create(
                context = host.executionContext.context,
                workoutId = workoutId,
                activityType = "Running",
                source = "Recorder",
                start = UnixInstantDto(1_700_000_000_000L),
                end = UnixInstantDto(1_700_003_600_000L),
                durationSec = 3_600,
                title = "Cross-device run",
                distanceM = 10_000,
                activeEnergyKj = 2_500,
                avgHr = 148,
            )
            assertTrue(
                host.tenshuClient.healthService.recordWorkout(workout, TByteArrayDto(payload))
                    is TResult.Success
            )

            // The small entity converges eagerly…
            system.advanceUntil(timeout = 30.seconds) {
                joiner.executionContext.database.getData(
                    org.tenshu.core.entity.DataId.StringDataId.create(
                        type = Workout.ID,
                        keyValue = workoutId
                    )
                ) != null
            }
            // …with NO blob ingested: the manifest is applied OnDemand-only, so
            // receiver-side residency stays small (the entire point of option B).
            assertNull(
                joiner.fileDatabase.readBytes(streamPath),
                "streams must not be proactively pushed into the receiver's store"
            )

            // The fit detail-read chain triggers the OnDemand fetch — re-issued until the bytes land,
            // never once-then-assert. `RecordWorkout` enqueues the Workout block BEFORE the manifest
            // block (entity-before-bytes, ADR 0015) and the host's tick pushes them one request at
            // a time, so a joiner that pulls between the two sees the entity with no manifest on
            // the relay yet. `FileContentManifestApplier`'s contract for that gap is a clean no-op —
            // "a later resolve retries (idempotent — chunks immutable)" — which is exactly what a
            // user reopening the workout does, and what this loop does. A single resolve followed by
            // an immediate read raced the host's second push: 1 red in a cross-module gate on
            // 2026-08-21, and deterministic with a 1.5 s hold between the host's pushes
            // (docs/tech-debt.md § "The full gate is flaky under parallel load").
            try {
                system.advanceUntil(timeout = 30.seconds) {
                    joiner.tenshuClient.healthService.workouts
                        .search<Workout> { this.workoutId equalTo workoutId }
                        .select {
                            fields(Workout.Fields.WORKOUT_ID)
                            relations(Workout.Streams)
                        }
                        .resolve()
                    joiner.fileDatabase.readBytes(streamPath) != null
                }
            } catch (e: TimeoutCancellationException) {
                fail(
                    "OnDemandResolution should have written the stream blob to the joiner's " +
                        "FileDatabase — still absent after 30 s of re-resolving the detail read"
                )
            }

            val fetched = joiner.fileDatabase.readBytes(streamPath)
            assertTrue(
                fetched != null,
                "OnDemandResolution should have written the stream blob to the joiner's FileDatabase"
            )
            assertContentEquals(payload, fetched.get())
        } finally {
            system.shutdown()
        }
    }
}

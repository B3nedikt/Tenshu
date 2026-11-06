package org.tenshu.contact.proxy

import kotlinx.coroutines.CancellationException
import org.tenshu.contact.SweepContactPhotos
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.toTString
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec

/** Stable schedule id for the photo sweep; coalesces re-registration on each startup. */
const val CONTACT_PHOTO_SWEEP_DEDUP_KEY = "tenshu/contact/photoSweep"

/** A day: the sweep only reclaims local disk, so nothing is waiting on it. */
const val CONTACT_PHOTO_SWEEP_PERIOD_MS = 24L * 60 * 60 * 1000

/** Long enough that a cold start has finished its first sync before this competes with it. */
const val CONTACT_PHOTO_SWEEP_INITIAL_DELAY_MS = 10L * 60 * 1000

/**
 * Register [SweepContactPhotos] as a periodic `HeadlessSafe` `ScheduleItem` (`contacts-app.md`
 * §9.10) — shaped like `registerThumbnailBackfillSchedule`, registered beside it. Local work only:
 * no network constraint, battery not low.
 *
 * ⚠ Best-effort by decision, for the reason its neighbours spell out: the call site is a bare
 * `sessionScope.launch { … }` at proxy init, so a throw here would reach the thread's uncaught
 * handler — which on Android kills Control.
 */
suspend fun ExecutionContext.registerContactPhotoSweepSchedule(
    periodMs: Long = CONTACT_PHOTO_SWEEP_PERIOD_MS,
    initialDelayMs: Long = CONTACT_PHOTO_SWEEP_INITIAL_DELAY_MS,
) {
    try {
        executeAsync<Data>(
            operationId = Schedule.ID,
            caller = null,
            params = mapOf(
                Schedule.Params.OP_ID to SweepContactPhotos.ID.toTString(context),
                Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(
                    context = context,
                    kind = ScheduleSpec.KIND_PERIODIC,
                    minPeriodMs = periodMs,
                    initialDelayMs = initialDelayMs,
                ),
                Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(
                    context = context,
                    network = ConstraintSpec.NETWORK_NONE,
                    battery = ConstraintSpec.BATTERY_NOT_LOW,
                ),
                Schedule.Params.RETRY_POLICY to RetryPolicy.create(context),
                Schedule.Params.DEDUP_KEY to CONTACT_PHOTO_SWEEP_DEDUP_KEY.toTString(context),
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Named line rather than silence: losing it leaves superseded photo blobs on disk.
        println("ContactPhotoSweep -> schedule registration failed, the sweep will not run: $e")
    }
}

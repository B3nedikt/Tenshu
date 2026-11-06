package org.tenshu.contact

import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The scheduled superseded-photo sweep (`contacts-app.md` §9.10): local photo blobs that no stored
 * `ContactSource` row names any more are deleted here, never at the save that superseded them
 * (`sweepSupersededContactPhotos`).
 *
 * `HeadlessSafe` like `BackfillThumbnailClasses`: only the scheduler dispatches it, behind a periodic
 * `ScheduleItem` (`registerContactPhotoSweepSchedule`). Never called from a client; no receiver,
 * params, or client RO. The web has no schedules and runs the same sweep from its sync scheduler's
 * maintenance hook instead.
 */
object SweepContactPhotos {

    const val ID = "${ContactService.ID}/sweepContactPhotos"

    val Schema: OperationSchema = operation(
        id = ID,
        traits = listOf(
            IoOperation.Schema,
            TerminalOperation.Schema,
            HeadlessSafe.Schema,
        ),
    )
}

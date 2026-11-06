package org.tenshu.core.file.system

import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The thumbnail-class **backfill** (`drive-synced-thumbnails.md` §15.3 D-6, W-2): a param-less,
 * self-driving background op that, for every file this device holds bytes for, mints each class in
 * `wanted(realm) − present(file)` and publishes it through the upload's own emit path — so a rung
 * added after a library existed (`XLARGE`, `MOTION`, the next one) reaches the files uploaded
 * before it.
 *
 * ⚠⚠ **Never correctness-bearing** (§12.6, restated at §15.3): nothing may depend on this having
 * run. The preview resolver keeps every fall-through, the grid keeps skipping a tile with no clip,
 * and a device that never idles on a charger simply has a library that moves less.
 *
 * `HeadlessSafe` like [OfflineKeeper], for the same reason: only the scheduler dispatches it — the
 * in-process `ScheduleDrainLoop`, or Android `WorkManager` when Control is closed — behind an
 * idle + charging + unmetered `ScheduleItem`, because it uploads (~139 KB per photo for `XLARGE`,
 * ~90 KB per video for `MOTION`). Never called from a client; no receiver, params, or client RO.
 */
object BackfillThumbnailClasses {

    const val ID = "tenshu/file/system/backfillThumbnailClasses"

    val Schema: OperationSchema = operation(
        id = ID,
        traits = listOf(
            IoOperation.Schema,
            TerminalOperation.Schema,
            HeadlessSafe.Schema,
        ),
    )
}

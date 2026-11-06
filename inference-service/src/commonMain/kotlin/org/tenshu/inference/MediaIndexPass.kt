package org.tenshu.inference

import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * One background pass over the user's media, producing labels and vectors (IN-3, **IN-D6**).
 *
 * ⚠⚠ **This is `MediaIndexer`'s first production call site.** It was built in IN-3a, extended in
 * IN-4b, and for five slices was constructed only in tests — so a device with a model, consent and a
 * library still indexed nothing, and `photosLike` answered `NOT_INDEXED` forever. Scheduling it is
 * what turns everything IN-3b through 1c built into a feature.
 *
 * ⚠ **Never on the foreground path.** IN-D6: indexing a library is not an interactive workload. It
 * runs as idle + charging maintenance, so a first index of a large library — hours, and visible in
 * the battery — happens when the device is plugged in and unused.
 *
 * ⚠ `HeadlessSafe`, and therefore with **no receiver, no params and no client RO**: only the
 * scheduler dispatches it by id (the in-process `ScheduleDrainLoop`, or Android `WorkManager` when
 * Control is closed). `ScheduleService` refuses to schedule an op without the trait and
 * `ScheduleDrainLoop` re-checks before dispatch — fail-closed, the `OfflineKeeper` shape.
 *
 * ⚠ Every refusal is *inside* the pass, not here: consent is re-read per file (a revocation must
 * stop a running pass), and with no backend the pass is a counted no-op. A scheduled op that
 * refuses cheaply and often is the correct shape for maintenance.
 */
object MediaIndexPass {

    const val ID = "tenshu/inference/system/mediaIndexPass"

    val Schema: OperationSchema = operation(
        id = ID,
        traits = listOf(
            IoOperation.Schema,
            TerminalOperation.Schema,
            HeadlessSafe.Schema,
        ),
    )
}

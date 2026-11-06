package org.tenshu.core.common.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.tenshu.core.entity.DataId

/**
 * The one buffer between *"something asked us to show this row"* and *"the composition is up to
 * collect it"* (`control-cross-app-open.md` TT-1b).
 *
 * ⚠⚠ **[replay] is the whole point, for the same reason `IcsOpenRelay` needs it.** A cold start
 * delivers the target in the launch `Intent`, which the activity reads *before* `setContent` has
 * run — so without a held emission the one case that motivated the whole mechanism would drop the
 * request on the floor. A warm push has no such problem, and that asymmetry is exactly why one
 * relay serves both rather than each path inventing its own callback.
 *
 * ⭐ **Two carriers, one shape.** Whether the `DataId` arrived as an `Intent` extra or as a
 * proxy→client push, a handling app collects the same flow and cannot tell — which is what makes
 * CO-D3's *"the OS is only for cold start"* true for the receiver and not merely for the sender.
 *
 * ⚠ Nothing here is durable. A target that nobody collects dies with the process, and that is
 * CO-D8 holding on the receiving side: the request belongs to a gesture, and a gesture does not
 * survive a restart.
 */
class OpenTargetInbox {

    private val _targets = MutableSharedFlow<DataId>(
        // ⚠ Holds the launch-time target for a collector that does not exist yet.
        replay = 1,
        extraBufferCapacity = 8,
        // ⚠ With this buffer and policy [deliver] can never block or fail, which is what lets it
        // be called from a lifecycle callback and from a Binder thread alike.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** What the app's root collects to navigate. */
    val targets: Flow<DataId> = _targets.asSharedFlow()

    /** Hands a target to the app. Never suspends, never fails. */
    fun deliver(target: DataId) {
        _targets.tryEmit(target)
    }
}

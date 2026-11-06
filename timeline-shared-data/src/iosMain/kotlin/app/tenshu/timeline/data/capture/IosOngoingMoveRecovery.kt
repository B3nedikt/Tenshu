package app.tenshu.timeline.data.capture

import kotlin.time.Clock

/**
 * Republish, at launch, the journey a **previous process** left open — the iOS twin of
 * `restoreOngoingMove(Context, …)` (`timeline-movement-capture.md` §12.6).
 *
 * iOS needs this more than Android does, not less: there is no capture process here at all. The
 * `CLVisit`/significant-change monitors relaunch the app per event and suspend it again in between,
 * so [OngoingMoveState] is empty on *every* wake — the journal is the only thing that knows a
 * journey is open, and reading it is the only way the ride you are on can ever be on screen.
 *
 * The Swift shell reaches this through `TimelineCaptureRuntime.startIfEnabled()`, which already
 * runs on every launch and already gates on the capture opt-in. [iosOpenMoveJournal] stays
 * `internal` to this module — the durable record keeps one owner.
 */
fun restoreOngoingMove(
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    config: MovementDetectionConfig = MovementDetectionConfig(),
): OngoingMove? = OngoingMoveState.restoreFrom(iosOpenMoveJournal(), nowMs, config)

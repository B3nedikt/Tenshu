package app.tenshu.timeline.data.capture

import android.content.Context

/**
 * Republish, at app launch, the journey a **previous process** left open — the app-open half of the
 * in-progress ride (`timeline-movement-capture.md` §12.6).
 *
 * [OngoingMoveState] dies with the process; the [OpenMoveJournal] does not. ColorOS force-kills the
 * capture foreground service routinely (§11.4), so the ordinary case is: you are still walking, the
 * capture process died twenty minutes ago, you open Timeline — and without this the feed shows
 * nothing about the walk you are on until it finally flushes.
 *
 * The app layer calls **this**, not the journal, deliberately: [androidOpenMoveJournal] is `internal`
 * to this module so the durable record has exactly one owner, and a reader outside it could not be
 * trusted not to clear or rewrite the record. All the guards (recordability, freshness, never
 * overwrite live state) live in [OngoingMoveState.restoreFrom].
 *
 * ⚠ **Caller must gate on the capture opt-in.** A stale-but-inside-the-window journal from a session
 * the user has since switched capture *off* for is not a journey they are on. That check needs
 * `TimelineCaptureControl`, which lives in `:timeline-shared-logic` — the wrong direction for this
 * module to depend in — so it stays the caller's duty (`TimelineApplication`).
 */
fun restoreOngoingMove(
    context: Context,
    nowMs: Long = System.currentTimeMillis(),
    config: MovementDetectionConfig = MovementDetectionConfig(),
): OngoingMove? = OngoingMoveState.restoreFrom(androidOpenMoveJournal(context), nowMs, config)

package org.tenshu.ui.test

import androidx.compose.ui.MotionDurationScale

/**
 * A [MotionDurationScale] of zero: every duration-based animation snaps to its end value on the
 * next frame instead of being rendered to completion one 16 ms frame at a time.
 *
 * Pass it as the `effectContext` of `runComposeUiTest`, `runDesktopComposeUiTest` or
 * `createComposeRule`. A test whose *subject* is animation timing — a fade that must still be
 * mid-flight, a timer driven through `mainClock.advanceTimeBy` against an animated value — keeps
 * real motion; docs/testing.md §13 has the rule and the classes that stay on it.
 *
 * ⚠ It shortens animations, not delays: a `delay(…)` inside a `LaunchedEffect` still runs on the
 * test clock at full length.
 */
object NoMotion : MotionDurationScale {
    override val scaleFactor: Float = 0f
}

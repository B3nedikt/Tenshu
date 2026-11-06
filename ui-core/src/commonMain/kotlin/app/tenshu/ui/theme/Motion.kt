package app.tenshu.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing

/**
 * Tenshu motion tokens — duration + easing primitives for animations.
 *
 * This is a stub — no component uses these yet. The hook exists so that
 * when Phase 7 (Material 3 Expressive) lands, the motion scheme can be
 * swapped here in one place rather than across N component files.
 *
 * Standard curves come from the M3 motion spec. Durations follow the
 * common short / medium / long buckets.
 */
object AppMotion {

    // Durations (milliseconds) -----------------------------------------------

    /** Instant feedback, ≤ 50ms: ripples, pressed-state color swaps. */
    const val DurationShort1: Int = 50
    const val DurationShort2: Int = 100

    /** Typical UI transitions: expand/collapse, list item swaps. */
    const val DurationMedium1: Int = 200
    const val DurationMedium2: Int = 300

    /** Large surface transitions: full-screen nav, sheet reveal. */
    const val DurationLong1: Int = 400
    const val DurationLong2: Int = 500

    // Easings ----------------------------------------------------------------

    /** Standard easing for most motion — "natural" feel. */
    val EasingStandard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** For elements entering the screen. */
    val EasingStandardAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)

    /** For elements leaving the screen. */
    val EasingStandardDecelerate: Easing = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)

    /** Emphasis easing — snappier, used for attention-grabbing transitions. */
    val EasingEmphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** Linear — almost never what you want; use sparingly. */
    val EasingLinear: Easing = LinearEasing
}

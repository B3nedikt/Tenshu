package app.tenshu.ui.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import org.tenshu.preference.MeasurementSystem

/**
 * Metric vs imperial, for every app on this device (`docs/proposals/fit-app.md` §18.2).
 *
 * The value is the realm-wide `OwnerPreferences.Units` — **one answer the user gives once**, not a
 * per-app setting. Before this existed, Fit owned the only toggle while Timeline rendered
 * kilometres unconditionally, so an imperial user got an imperial Fit and a metric Timeline.
 *
 * **A `CompositionLocal`, not a parameter**, because units are the textbook ambient concern: every
 * distance/height/weight formatter needs them, none of the call sites in between have an opinion,
 * and threading a `units` argument through the whole UI tree would put the plumbing everywhere the
 * decision is not. Same reasoning as the theme it sits beside.
 *
 * ⚠ `static` — flipping units re-composes everything that reads a distance, which is exactly the
 * intent. The alternative caches a read that would then be wrong on half the screen.
 *
 * Defaults to [MeasurementSystem.METRIC] so a preview, a test, or a shell that has not wired the
 * preference still renders sensibly rather than blank.
 */
val LocalMeasurementSystem: ProvidableCompositionLocal<MeasurementSystem> =
    staticCompositionLocalOf { MeasurementSystem.METRIC }

/** Sugar for a shell that has just collected the owner's preference. */
@Composable
fun ProvideMeasurementSystem(units: MeasurementSystem, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMeasurementSystem provides units, content = content)
}

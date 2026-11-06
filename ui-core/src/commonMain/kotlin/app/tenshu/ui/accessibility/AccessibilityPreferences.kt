package app.tenshu.ui.accessibility

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * The two OS accessibility signals a time-driven UI has to respect.
 *
 * Read through [rememberAccessibilityPreferences]; a component that acts on its own after a delay
 * (`AppAutoContinueButton`) takes this as a defaulted parameter so tests can pin either signal.
 *
 * @property screenReaderActive A screen reader is driving the UI (Android: touch exploration, i.e.
 *   TalkBack; iOS: VoiceOver). ⚠ Desktop has no reliable signal, so it reports `false` there — a
 *   caller must treat this as "known to be on", never as "known to be off".
 * @property reduceMotion The user asked for less motion (Android: animator duration scale 0; iOS:
 *   Reduce Motion). Drop decorative animation; keep the behaviour it decorates.
 */
@Immutable
data class AccessibilityPreferences(
    val screenReaderActive: Boolean = false,
    val reduceMotion: Boolean = false,
)

/**
 * The platform's current [AccessibilityPreferences].
 *
 * ⚠ Platform delegate, not a design fork (`ui-agent.md` §3): Android listens for touch-exploration
 * changes while composed; iOS and desktop read once per composition entry (desktop always reports
 * both signals off — there is nothing reliable to read).
 */
@Composable
expect fun rememberAccessibilityPreferences(): AccessibilityPreferences

package app.tenshu.ui.feedback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.tenshu.ui.accessibility.AccessibilityPreferences
import app.tenshu.ui.accessibility.rememberAccessibilityPreferences
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.resources.Res
import app.tenshu.ui.resources.auto_continue_countdown
import app.tenshu.ui.resources.auto_continue_countdown_one
import app.tenshu.ui.resources.auto_continue_stay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * How long an armed [AppAutoContinueButton] waits before it continues by itself. One constant per
 * platform, and all three agree: the SwiftUI twin (`AutoContinueButton.swift`, control-ios) and the
 * web twin (`AutoContinueButton.tsx`, web/apex) both spell 3000 ms. `control-pairing-ux.md` §9.
 */
const val AUTO_CONTINUE_DURATION_MS: Long = 3_000L

/**
 * The cancel half of an [AppAutoContinueButton], hoisted so the screen around the button can stop
 * the countdown too — a tap anywhere on a success screen means "I'm reading this", not "go on".
 *
 * Sticky: once cancelled, the button stays a plain Continue for the life of this state. Key
 * [rememberAutoContinueState] on whatever identifies the screen if a new terminal should re-arm.
 */
@Stable
class AutoContinueState internal constructor() {
    /** True once the user (or the screen) has cancelled the countdown. */
    var isCancelled: Boolean by mutableStateOf(false)
        private set

    /** Stop the countdown for good; the button stays, as a plain Continue. Idempotent. */
    fun cancel() {
        isCancelled = true
    }
}

/** A fresh [AutoContinueState], re-created whenever any of [keys] changes. */
@Composable
fun rememberAutoContinueState(vararg keys: Any?): AutoContinueState = remember(*keys) { AutoContinueState() }

/**
 * Cancel [state] on any touch or click inside this element — the "tap anywhere to stay" half of the
 * auto-continue pattern. Observes on the [PointerEventPass.Initial] pass and consumes nothing, so the
 * tapped child (the Continue button included) still gets its click.
 */
fun Modifier.appCancelAutoContinueOnTouch(state: AutoContinueState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.cancel()
        }
    }

/**
 * A full-width Continue button that, once [armed], counts down [durationMs] behind a progress ring
 * and then fires [onContinue] by itself; a "Stay" text button under it cancels the countdown.
 *
 * ### When to use
 * On a terminal success screen whose only action is "carry on" and whose content is **settled** —
 * nothing on it can still change in a way the user must read. Arm it only then: a screen that may
 * yet swap its body (an unfunded-realm warning, a code to compare) must pass `armed = false` until
 * it knows, or never arm at all. Not for anything destructive, and never for a screen that asks
 * the user to compare or confirm something (`control-pairing-ux.md` §9).
 *
 * ### Variants
 * - **Counting** (`armed`, not cancelled, app resumed, no screen reader): ring + label + "Stay".
 * - **Plain**: a normal Continue button — before arming, after [AutoContinueState.cancel], or
 *   whenever a screen reader is running.
 * - **Reduced motion**: counts down the same, without the ring animation.
 * - [outlined]: the secondary style, for a screen whose primary action is something else.
 *
 * ### Accessibility
 * - ⚠ With a screen reader on it never auto-continues: a spoken UI cannot be raced. It renders the
 *   plain button.
 * - While counting, the button's state description reads "Continuing in N seconds".
 * - Pauses while the app is below RESUMED (backgrounded, or covered) and restarts the full
 *   countdown on return, so nobody comes back to a screen that already left.
 * - Announce the screen's title with a polite live region; this component does not own the title.
 *
 * @param text The button label (normally the screen's own "Continue" string).
 * @param onContinue Runs on a tap, or when the countdown completes — at most once from the timer.
 * @param armed Whether the screen's state is settled enough to count down. May turn true late; the
 *   countdown starts then.
 * @param state Hoisted cancel state; pass the same instance to [appCancelAutoContinueOnTouch].
 * @param durationMs Countdown length; leave at [AUTO_CONTINUE_DURATION_MS] outside tests.
 * @param accessibility The OS signals; defaulted from the platform, overridable in tests.
 * @param outlined Render the button in the outlined (secondary) style.
 */
@Composable
fun AppAutoContinueButton(
    text: String,
    onContinue: () -> Unit,
    armed: Boolean,
    modifier: Modifier = Modifier,
    state: AutoContinueState = rememberAutoContinueState(),
    durationMs: Long = AUTO_CONTINUE_DURATION_MS,
    accessibility: AccessibilityPreferences = rememberAccessibilityPreferences(),
    outlined: Boolean = false,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val currentOnContinue by rememberUpdatedState(onContinue)

    var fired by remember { mutableStateOf(false) }
    val counting = armed && !fired && !state.isCancelled && !accessibility.screenReaderActive && resumed
    val ring = remember { Animatable(0f) }
    var secondsLeft by remember { mutableIntStateOf(secondsCeil(durationMs)) }

    LaunchedEffect(counting, durationMs, accessibility.reduceMotion) {
        // Restart from the full duration on every (re-)arm — including the return from background.
        ring.snapTo(0f)
        secondsLeft = secondsCeil(durationMs)
        if (!counting) return@LaunchedEffect
        if (!accessibility.reduceMotion) {
            launch { ring.animateTo(1f, tween(durationMs.toInt(), easing = LinearEasing)) }
        }
        launch {
            var left = durationMs
            while (left > 0) {
                secondsLeft = secondsCeil(left)
                val step = (left % 1_000L).takeIf { it != 0L } ?: 1_000L
                delay(step)
                left -= step
            }
        }
        delay(durationMs)
        fired = true
        currentOnContinue()
    }

    val countdownLabel = if (secondsLeft == 1) stringResource(Res.string.auto_continue_countdown_one)
    else stringResource(Res.string.auto_continue_countdown, secondsLeft)
    val buttonModifier = Modifier
        .fillMaxWidth()
        .testTag(TAG_AUTO_CONTINUE_BUTTON)
        .then(if (counting) Modifier.semantics { stateDescription = countdownLabel } else Modifier)
    val onClick: () -> Unit = {
        fired = true
        currentOnContinue()
    }
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (counting && !accessibility.reduceMotion) {
                CircularProgressIndicator(
                    progress = { ring.value },
                    modifier = Modifier.size(18.dp).testTag(TAG_AUTO_CONTINUE_RING),
                    color = LocalContentColor.current,
                    trackColor = LocalContentColor.current.copy(alpha = 0.3f),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(text)
        }
    }

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (outlined) {
            OutlinedButton(onClick = onClick, modifier = buttonModifier) { content() }
        } else {
            Button(onClick = onClick, modifier = buttonModifier) { content() }
        }
        if (counting) {
            TextButton(onClick = state::cancel, modifier = Modifier.testTag(TAG_AUTO_CONTINUE_STAY)) {
                Text(stringResource(Res.string.auto_continue_stay))
            }
        }
    }
}

private fun secondsCeil(ms: Long): Int = ((ms + 999L) / 1_000L).toInt()

/** Test tag of the Continue button itself. */
const val TAG_AUTO_CONTINUE_BUTTON = "auto_continue_button"

/** Test tag of the countdown ring (absent when not counting, or under reduced motion). */
const val TAG_AUTO_CONTINUE_RING = "auto_continue_ring"

/** Test tag of the "Stay" button (present only while counting). */
const val TAG_AUTO_CONTINUE_STAY = "auto_continue_stay"

@Preview
@Composable
private fun AppAutoContinueButtonCountingPreview() = PreviewBox {
    AppAutoContinueButton(text = "Continue", onContinue = {}, armed = true)
}

@Preview
@Composable
private fun AppAutoContinueButtonPlainPreview() = PreviewBox {
    AppAutoContinueButton(text = "Continue", onContinue = {}, armed = false)
}

@Preview
@Composable
private fun AppAutoContinueButtonCountingDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppAutoContinueButton(text = "Continue", onContinue = {}, armed = true)
}

@Preview
@Composable
private fun AppAutoContinueButtonOutlinedPreview() = PreviewBox {
    AppAutoContinueButton(text = "Continue", onContinue = {}, armed = false, outlined = true)
}

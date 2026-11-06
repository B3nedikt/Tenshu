package app.tenshu.fit.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_detect_prompt_always
import app.tenshu.fit.shared.resources.fit_detect_prompt_body
import app.tenshu.fit.shared.resources.fit_detect_prompt_never
import app.tenshu.fit.shared.resources.fit_detect_prompt_once
import app.tenshu.fit.shared.resources.fit_detect_prompt_ride
import app.tenshu.fit.shared.resources.fit_detect_prompt_run
import app.tenshu.fit.shared.resources.fit_detect_prompt_title
import app.tenshu.fit.shared.resources.fit_detect_prompt_walk
import app.tenshu.fit.ui.FirstDetectionViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * The one-time introduction to automatic logging (`fit-automatic-activity-detection.md` D1).
 *
 * ⚠ **Three buttons, not two.** "Just this one" is what makes "no thanks" an honest refusal
 * rather than a cost: without it, someone who wants *this* walk logged has to accept the standing
 * setting to get it, and the prompt becomes a toll rather than a question.
 *
 * ⚠ Hand-rolled `AlertDialog` rather than `ui-core`'s `AppDialog`, which models one or two
 * labelled buttons. Three does not fit its shape, and its KDoc says to reach for `AlertDialog`
 * directly in exactly that case.
 */
@Composable
fun FirstDetectionPrompt(
    candidate: FirstDetectionViewModel.Candidate,
    onAlways: () -> Unit,
    onOnce: () -> Unit,
    onNever: () -> Unit,
) {
    val duration = durationClock(candidate.durationSec)
    val what = when (candidate.activityType) {
        ActivityType.Running -> stringResource(Res.string.fit_detect_prompt_run, duration)
        ActivityType.Cycling -> stringResource(Res.string.fit_detect_prompt_ride, duration)
        // ⚠ Walking is the fallback rather than an exhaustive `when`: the promoter only ever
        // yields these three, and a future fourth should read as a walk rather than fail to build.
        else -> stringResource(Res.string.fit_detect_prompt_walk, duration)
    }
    AlertDialog(
        // ⚠ No dismiss-on-outside-tap: an accidental tap would spend the one-time prompt without
        // an answer, and the promise is "asked once".
        onDismissRequest = { },
        title = { Text(stringResource(Res.string.fit_detect_prompt_title, what)) },
        text = { Text(stringResource(Res.string.fit_detect_prompt_body)) },
        confirmButton = { TextButton(onClick = onAlways) { Text(stringResource(Res.string.fit_detect_prompt_always)) } },
        dismissButton = {
            // Both remaining answers live here; `AlertDialog` has only two button slots, and
            // stacking them keeps "just this one" beside the refusal it exists to soften.
            TextButton(onClick = onOnce) { Text(stringResource(Res.string.fit_detect_prompt_once)) }
            TextButton(onClick = onNever) { Text(stringResource(Res.string.fit_detect_prompt_never)) }
        },
    )
}

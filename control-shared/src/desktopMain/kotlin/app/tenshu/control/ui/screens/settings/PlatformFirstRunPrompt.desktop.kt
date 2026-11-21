package app.tenshu.control.ui.screens.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.tenshu.control.lifecycle.DesktopControlSettings
import app.tenshu.control.lifecycle.WindowsStartupTask
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.start_at_login_prompt_body
import app.tenshu.control.shared.resources.start_at_login_prompt_decline
import app.tenshu.control.shared.resources.start_at_login_prompt_failed_body
import app.tenshu.control.shared.resources.start_at_login_prompt_failed_ok
import app.tenshu.control.shared.resources.start_at_login_prompt_failed_title
import app.tenshu.control.shared.resources.start_at_login_prompt_accept
import app.tenshu.control.shared.resources.start_at_login_prompt_title
import app.tenshu.control.ui.LocalWindowVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun PlatformFirstRunPrompt(active: Boolean) {
    // Null off Windows and outside the package, where there is no startup task to ask about.
    val task = remember { WindowsStartupTask.forThisProcess() } ?: return
    val settings = remember { DesktopControlSettings() }
    StartAtLoginPrompt(
        active = active && LocalWindowVisible.current,
        settings = settings,
        readState = task::state,
        enable = task::enable,
    )
}

/** What the startup task's state means for the one-time question. */
internal enum class StartAtLoginPromptDecision {
    /** Off, and nobody has decided: ask. */
    ASK,

    /** Already decided, by the user in Windows' Settings or by policy, or already on: never ask. */
    NEVER,

    /** The task could not be read. Neither ask nor record anything, so the next launch looks again. */
    NOT_NOW,
}

internal fun startAtLoginPromptDecision(state: WindowsStartupTask.State): StartAtLoginPromptDecision = when (state) {
    WindowsStartupTask.State.DISABLED -> StartAtLoginPromptDecision.ASK
    WindowsStartupTask.State.UNAVAILABLE -> StartAtLoginPromptDecision.NOT_NOW
    WindowsStartupTask.State.ENABLED,
    WindowsStartupTask.State.ENABLED_BY_POLICY,
    WindowsStartupTask.State.DISABLED_BY_USER,
    WindowsStartupTask.State.DISABLED_BY_POLICY,
    -> StartAtLoginPromptDecision.NEVER
}

private enum class PromptPhase { IDLE, ASKING, WORKING, FAILED, DONE }

/**
 * Asks once whether Control may start at login. ⚠ The package's startup task stays `Enabled="false"` and only an
 * explicit yes here or in Settings turns it on: the Store expects consent. Declining or dismissing is an answer and
 * is recorded, so this never nags; the switch in Settings stays the way to change it.
 *
 * ⚠ [readState] and [enable] start the tray agent as a process, so they run on [Dispatchers.IO].
 */
@Composable
internal fun StartAtLoginPrompt(
    active: Boolean,
    settings: DesktopControlSettings,
    readState: () -> WindowsStartupTask.State,
    enable: () -> WindowsStartupTask.State,
) {
    var phase by remember { mutableStateOf(PromptPhase.IDLE) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(active) {
        if (!active || phase != PromptPhase.IDLE || settings.startAtLoginPromptAnswered()) return@LaunchedEffect
        when (startAtLoginPromptDecision(withContext(Dispatchers.IO) { readState() })) {
            StartAtLoginPromptDecision.ASK -> phase = PromptPhase.ASKING
            StartAtLoginPromptDecision.NEVER -> settings.setStartAtLoginPromptAnswered(true)
            StartAtLoginPromptDecision.NOT_NOW -> Unit
        }
    }

    // Kept while the window is hidden or something else takes over, and shown again when it is back.
    if (!active) return

    fun decline() {
        settings.setStartAtLoginPromptAnswered(true)
        phase = PromptPhase.DONE
    }

    fun accept() {
        phase = PromptPhase.WORKING
        scope.launch {
            val state = withContext(Dispatchers.IO) { enable() }
            settings.setStartAtLoginPromptAnswered(true)
            val on = state == WindowsStartupTask.State.ENABLED || state == WindowsStartupTask.State.ENABLED_BY_POLICY
            phase = if (on) PromptPhase.DONE else PromptPhase.FAILED
        }
    }

    when (phase) {
        PromptPhase.ASKING, PromptPhase.WORKING -> {
            val working = phase == PromptPhase.WORKING
            AlertDialog(
                onDismissRequest = { if (!working) decline() },
                title = { Text(stringResource(Res.string.start_at_login_prompt_title)) },
                text = { Text(stringResource(Res.string.start_at_login_prompt_body)) },
                confirmButton = {
                    Button(onClick = ::accept, enabled = !working) {
                        Text(stringResource(Res.string.start_at_login_prompt_accept))
                    }
                },
                dismissButton = {
                    TextButton(onClick = ::decline, enabled = !working) {
                        Text(stringResource(Res.string.start_at_login_prompt_decline))
                    }
                },
            )
        }
        PromptPhase.FAILED -> AlertDialog(
            onDismissRequest = { phase = PromptPhase.DONE },
            title = { Text(stringResource(Res.string.start_at_login_prompt_failed_title)) },
            text = { Text(stringResource(Res.string.start_at_login_prompt_failed_body)) },
            confirmButton = {
                Button(onClick = { phase = PromptPhase.DONE }) {
                    Text(stringResource(Res.string.start_at_login_prompt_failed_ok))
                }
            },
        )
        PromptPhase.IDLE, PromptPhase.DONE -> Unit
    }
}

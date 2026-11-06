package app.tenshu.mia.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.mia.data.AssistantRoleLauncher
import app.tenshu.mia.shared.resources.Res
import app.tenshu.mia.shared.resources.mia_onboarding_assistant_body
import app.tenshu.mia.shared.resources.mia_onboarding_assistant_title
import app.tenshu.mia.shared.resources.mia_onboarding_back
import app.tenshu.mia.shared.resources.mia_onboarding_done_body
import app.tenshu.mia.shared.resources.mia_onboarding_done_title
import app.tenshu.mia.shared.resources.mia_onboarding_next
import app.tenshu.mia.shared.resources.mia_onboarding_skip
import app.tenshu.mia.shared.resources.mia_onboarding_start
import app.tenshu.mia.shared.resources.mia_onboarding_welcome_body
import app.tenshu.mia.shared.resources.mia_onboarding_welcome_title
import app.tenshu.mia.shared.resources.mia_settings_assistant_open
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin

/**
 * The one-time intro flow (docs/proposals/home-and-mia-apps.md §4, M5): Welcome →
 * Assistant → Done, every step skippable ([OnboardingViewModel]). The Assistant step's
 * action button appears only where an [AssistantRoleLauncher] is bound (Android); voice-ID
 * joins as a step when the voice pipeline lands.
 */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel) {
    val state by viewModel.uiState.collectAsState()
    val koin = getKoin()
    val assistantLauncher = remember(koin) { koin.getOrNull<AssistantRoleLauncher>() }

    // ⚠⚠ **The `Surface` is load-bearing, and §14.8 is what its absence looked like**: a bare
    // `Column` paints no background and sets no content colour, so onboarding drew the *window's*
    // dark background under Compose's default black `LocalContentColor` — a near-black headline on
    // dark grey, on the first screen a new user ever sees.
    //
    // ⚠ Every other screen in this app is fine because `App()` hosts the tabs inside
    // `AdaptiveNavScaffold`, which brings its own surface. Onboarding is rendered *beside* that
    // branch, so it is the one screen that has to supply its own — which is exactly why the bug
    // was invisible everywhere else.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.step != OnboardingStep.Done) {
                    TextButton(onClick = viewModel::complete) {
                        Text(stringResource(Res.string.mia_onboarding_skip))
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    stepIcon(state.step),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(24.dp))
                Text(stepTitle(state.step), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    stepBody(state.step),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (state.step == OnboardingStep.Assistant && assistantLauncher != null) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = assistantLauncher::openAssistantSettings) {
                        Text(stringResource(Res.string.mia_settings_assistant_open))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.step != OnboardingStep.Welcome) {
                    TextButton(onClick = viewModel::back) {
                        Text(stringResource(Res.string.mia_onboarding_back))
                    }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                if (state.step == OnboardingStep.Done) {
                    Button(onClick = viewModel::complete) {
                        Text(stringResource(Res.string.mia_onboarding_start))
                    }
                } else {
                    Button(onClick = viewModel::next) {
                        Text(stringResource(Res.string.mia_onboarding_next))
                    }
                }
            }
        }
    }
}

private fun stepIcon(step: OnboardingStep): ImageVector = when (step) {
    OnboardingStep.Welcome -> Icons.Outlined.ChatBubbleOutline
    OnboardingStep.Assistant -> Icons.Default.AutoAwesome
    OnboardingStep.Done -> Icons.Default.Check
}

@Composable
private fun stepTitle(step: OnboardingStep): String = when (step) {
    OnboardingStep.Welcome -> stringResource(Res.string.mia_onboarding_welcome_title)
    OnboardingStep.Assistant -> stringResource(Res.string.mia_onboarding_assistant_title)
    OnboardingStep.Done -> stringResource(Res.string.mia_onboarding_done_title)
}

@Composable
private fun stepBody(step: OnboardingStep): String = when (step) {
    OnboardingStep.Welcome -> stringResource(Res.string.mia_onboarding_welcome_body)
    OnboardingStep.Assistant -> stringResource(Res.string.mia_onboarding_assistant_body)
    OnboardingStep.Done -> stringResource(Res.string.mia_onboarding_done_body)
}

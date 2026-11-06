package app.tenshu.control.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.home_recovery_affordance_body
import app.tenshu.control.shared.resources.home_recovery_affordance_dismiss
import app.tenshu.control.shared.resources.home_recovery_affordance_local_only_body
import app.tenshu.control.shared.resources.home_recovery_affordance_local_only_title
import app.tenshu.control.shared.resources.home_recovery_affordance_save_phrase
import app.tenshu.control.shared.resources.home_recovery_affordance_set_up_sync
import app.tenshu.control.shared.resources.home_recovery_affordance_title
import org.jetbrains.compose.resources.stringResource

/**
 * §7.2 Level 0 home-screen banner per `docs/proposals/onboarding-low-friction.md`: save the
 * recovery phrase. Visibility and which of its two wordings to use come from
 * [RecoveryAffordanceViewModel] (its KDoc has the table); consumers route the actions.
 *
 * - Phrase wording — one action, [onSavePhrase].
 * - Local-only wording (no storage plan, nothing syncs) — [onSetUpSync] leads, because the phrase
 *   restores access but not data that never left the device; [onSavePhrase] stays reachable.
 *
 * Pure UI — no state lives here. Test the composable for rendering + the VM for state logic.
 */
@Composable
fun RecoveryAffordance(
    viewModel: RecoveryAffordanceViewModel,
    onSavePhrase: () -> Unit,
    onSetUpSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AnimatedVisibility(visible = state.visible, modifier = modifier) {
        RecoveryAffordanceContent(
            localOnly = state.localOnly,
            onSavePhrase = onSavePhrase,
            onSetUpSync = onSetUpSync,
            onDismiss = viewModel::onDismiss,
        )
    }
}

@Composable
internal fun RecoveryAffordanceContent(
    localOnly: Boolean,
    onSavePhrase: () -> Unit,
    onSetUpSync: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(
                    if (localOnly) Res.string.home_recovery_affordance_local_only_title
                    else Res.string.home_recovery_affordance_title
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (localOnly) Res.string.home_recovery_affordance_local_only_body
                    else Res.string.home_recovery_affordance_body
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Primary last, so it sits at the right end of the row as on the other Home cards.
            BannerActions(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.home_recovery_affordance_dismiss))
                }
                if (localOnly) {
                    TextButton(onClick = onSavePhrase) {
                        Text(stringResource(Res.string.home_recovery_affordance_save_phrase))
                    }
                }
                OutlinedButton(onClick = if (localOnly) onSetUpSync else onSavePhrase) {
                    Text(
                        stringResource(
                            if (localOnly) Res.string.home_recovery_affordance_set_up_sync
                            else Res.string.home_recovery_affordance_save_phrase
                        )
                    )
                }
            }
        }
    }
}

/**
 * The card's buttons, primary **last**: one right-aligned row when they fit, as on the other Home
 * cards; otherwise stacked, right-aligned, primary on **top** — the order Material's own dialogs use.
 *
 * ⚠ Not a `FlowRow`: overflow moves the *last* items down, which put "Later" above the primary —
 * reached by the German phrase wording on an ordinary phone, and by the three-button local-only
 * wording in English.
 */
@Composable
private fun BannerActions(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val spacing = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val rowWidth = placeables.sumOf { it.width } + spacing * (placeables.size - 1).coerceAtLeast(0)
        val width = constraints.maxWidth
        if (rowWidth <= width) {
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, height) {
                var x = width - rowWidth
                placeables.forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += it.width + spacing
                }
            }
        } else {
            val stacked = placeables.asReversed()
            val height = stacked.sumOf { it.height } + spacing * (stacked.size - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                stacked.forEach {
                    it.placeRelative(width - it.width, y)
                    y += it.height + spacing
                }
            }
        }
    }
}

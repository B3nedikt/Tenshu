package app.tenshu.control.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.home_offline_maps_suggestion_action
import app.tenshu.control.shared.resources.home_offline_maps_suggestion_body
import app.tenshu.control.shared.resources.home_offline_maps_suggestion_dismiss
import app.tenshu.control.shared.resources.home_offline_maps_suggestion_title
import org.jetbrains.compose.resources.stringResource

/**
 * Home's offline-maps discovery card (`maps-region-delivery.md` §7 phase 7), mirroring
 * [RecoveryAffordance]: pure UI, visibility owned by [OfflineMapsSuggestionViewModel].
 *
 * ⚠ The copy deliberately opens by saying maps **already work**. Streaming is the render default
 * (D10), so a card implying they are broken until something is downloaded would be false — and would
 * push a hundreds-of-megabytes download for a problem the user does not have. What a region buys is
 * working with *no* connection, which is what it offers.
 */
@Composable
fun OfflineMapsSuggestion(
    viewModel: OfflineMapsSuggestionViewModel,
    onChooseRegion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = state.visible) {
        Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(Res.string.home_offline_maps_suggestion_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.home_offline_maps_suggestion_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = viewModel::onDismiss) {
                        Text(stringResource(Res.string.home_offline_maps_suggestion_dismiss))
                    }
                    Spacer(Modifier.height(0.dp))
                    OutlinedButton(onClick = onChooseRegion) {
                        Text(stringResource(Res.string.home_offline_maps_suggestion_action))
                    }
                }
            }
        }
    }
}

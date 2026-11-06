package app.tenshu.control.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.home_schema_fence_body
import app.tenshu.control.shared.resources.home_schema_fence_title
import org.jetbrains.compose.resources.stringResource

/**
 * The "Update Control to keep syncing" home banner. State lives in
 * [SchemaFenceBannerViewModel]; this only renders it.
 *
 * ⚠ No button and no dismiss. No platform has an update route to point at
 * (`core-schema-skew-surface.md` §8.5), so the copy states the fix without promising a
 * mechanism; and the condition is durable, so it stays until the fence lifts.
 */
@Composable
fun SchemaFenceBanner(
    viewModel: SchemaFenceBannerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AnimatedVisibility(visible = state.visible, modifier = modifier) {
        SchemaFenceBannerContent()
    }
}

@Composable
internal fun SchemaFenceBannerContent(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = Icons.Default.SystemUpdateAlt, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(Res.string.home_schema_fence_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.home_schema_fence_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

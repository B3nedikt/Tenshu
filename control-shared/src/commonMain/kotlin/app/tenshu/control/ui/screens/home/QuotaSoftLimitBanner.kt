package app.tenshu.control.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.home_quota_soft_limit_body
import app.tenshu.control.shared.resources.home_quota_soft_limit_dismiss
import app.tenshu.control.shared.resources.home_quota_soft_limit_see_plans
import app.tenshu.control.shared.resources.home_quota_soft_limit_title
import org.jetbrains.compose.resources.stringResource

/**
 * The one-time free-tier quota soft-limit home banner
 * (`docs/proposals/control-subscription-ux.md` §2.3 / `retention-quota-model.md`
 * P1b). Reads its visibility from a Koin-injected [QuotaSoftLimitViewModel];
 * the consumer wires [onSeePlans] to the subscription destination. Tapping
 * *See plans* also permanently dismisses (via the VM), so the prompt never
 * returns — "the only marketing prompt in the app".
 *
 * Pure UI — no state lives here. Test the composable for rendering + the VM
 * for state logic, separately (mirrors [RecoveryAffordance]).
 */
@Composable
fun QuotaSoftLimitBanner(
    viewModel: QuotaSoftLimitViewModel,
    onSeePlans: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AnimatedVisibility(visible = state.visible, modifier = modifier) {
        QuotaSoftLimitContent(
            onSeePlans = {
                viewModel.onSeePlans()
                onSeePlans()
            },
            onDismiss = viewModel::onDismiss,
        )
    }
}

@Composable
internal fun QuotaSoftLimitContent(
    onSeePlans: () -> Unit,
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
                text = stringResource(Res.string.home_quota_soft_limit_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.home_quota_soft_limit_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.home_quota_soft_limit_dismiss))
                }
                Button(onClick = onSeePlans) {
                    Text(stringResource(Res.string.home_quota_soft_limit_see_plans))
                }
            }
        }
    }
}

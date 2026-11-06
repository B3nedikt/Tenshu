package app.tenshu.home.ui.screens.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tenshu.home.model.Automation
import app.tenshu.home.shared.resources.Res
import app.tenshu.home.shared.resources.home_automation_edit
import app.tenshu.home.shared.resources.home_automation_menu
import app.tenshu.home.shared.resources.home_automation_remove
import app.tenshu.home.shared.resources.home_automations_add
import app.tenshu.home.shared.resources.home_automations_empty_body
import app.tenshu.home.shared.resources.home_automations_empty_title
import app.tenshu.ui.chrome.AppExtendedFab
import app.tenshu.ui.chrome.appFabInset
import org.jetbrains.compose.resources.stringResource

/**
 * The Automations tab (docs/proposals/home-and-mia-apps.md §5, M2): the rule list with
 * enable switches and a shared human-readable summary line; create/edit push the
 * full-screen editor.
 */
@Composable
fun AutomationsScreen(
    viewModel: AutomationsViewModel,
    onCreate: () -> Unit,
    onEdit: (automationId: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Box(Modifier.fillMaxSize()) {
        if (state.loaded && state.automations.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(Res.string.home_automations_empty_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.home_automations_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.automations, key = { it.id }) { automation ->
                    AutomationRow(
                        automation = automation,
                        viewModel = viewModel,
                        onEdit = { onEdit(automation.id) },
                    )
                }
            }
        }

        AppExtendedFab(
            onClick = onCreate,
            icon = Icons.Default.Add,
            label = stringResource(Res.string.home_automations_add),
            modifier = Modifier.align(Alignment.BottomEnd).appFabInset(),
        )
    }
}

@Composable
private fun AutomationRow(
    automation: Automation,
    viewModel: AutomationsViewModel,
    onEdit: () -> Unit,
) {
    val deviceName = viewModel::deviceName
    val automationName: (String) -> String? = { id ->
        viewModel.uiState.value.automations.firstOrNull { it.id == id }?.name
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    automation.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    automationRowSummary(automation, deviceName, automationName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = automation.enabled,
                onCheckedChange = { viewModel.setEnabled(automation.id, it) },
            )
            AutomationRowMenu(
                onEdit = onEdit,
                onRemove = { viewModel.removeAutomation(automation.id) },
            )
        }
    }
}

@Composable
private fun AutomationRowMenu(onEdit: () -> Unit, onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.home_automation_menu))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.home_automation_edit)) },
                onClick = { expanded = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.home_automation_remove)) },
                onClick = { expanded = false; onRemove() },
            )
        }
    }
}

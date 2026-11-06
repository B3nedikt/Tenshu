package app.tenshu.drive.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.contact_prompt_claim_note
import app.tenshu.drive.shared.resources.contact_prompt_decline
import app.tenshu.drive.shared.resources.contact_prompt_error
import app.tenshu.drive.shared.resources.contact_prompt_grant_body
import app.tenshu.drive.shared.resources.contact_prompt_grant_confirm
import app.tenshu.drive.shared.resources.contact_prompt_grant_skip
import app.tenshu.drive.shared.resources.contact_prompt_grant_title
import app.tenshu.drive.shared.resources.contact_prompt_header
import app.tenshu.drive.shared.resources.contact_prompt_link_existing
import app.tenshu.drive.shared.resources.contact_prompt_link_existing_none
import app.tenshu.drive.shared.resources.contact_prompt_own_name_label
import app.tenshu.ui.error.label
import app.tenshu.drive.shared.resources.contact_prompt_own_name_note
import app.tenshu.drive.shared.resources.contact_prompt_petname_label
import app.tenshu.drive.shared.resources.contact_prompt_save
import app.tenshu.drive.shared.resources.contact_prompt_unnamed
import org.jetbrains.compose.resources.stringResource

/**
 * The completion prompt (`docs/proposals/drive-manage-access-ux.md` §2.3) —
 * Drive-hosted now, Contacts-app-bound in v2 (pure UI over synced
 * `ContactInvite` rows + the `CompleteContactHandshake` op; no contact logic
 * lands here). The header renders the self-asserted name as *their claim*
 * (quoted) with the key fingerprint beneath; the petname field is user-owned.
 */
@Composable
fun ContactPromptDialog(
    viewModel: ContactPromptViewModel,
    onDismiss: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.done) {
        if (uiState.done) onDismiss()
    }

    // The grant offer used to live here, replacing the form after an accept. It is now
    // a deferred card on the Shared screen, derived from the COMPLETED invite row — which
    // is what lets this dialog be pure UI over synced rows + one op again, and therefore
    // movable to the Contacts app (contacts-app.md §9.1).


    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    Res.string.contact_prompt_header,
                    uiState.claimedName.ifEmpty {
                        stringResource(Res.string.contact_prompt_unnamed)
                    },
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = uiState.claimedFingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.contact_prompt_claim_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // §5.2 SAS panel: comparing the code (in person, both screens
                // show it) and checking the box mints VERIFIED/QR_SAS;
                // ignoring it keeps the link-flow UNVERIFIED default.
                if (uiState.claimedSas.isNotEmpty()) {
                    SasConfirmPanel(
                        sas = uiState.claimedSas,
                        peerFingerprint = uiState.claimedFingerprint,
                        confirmed = uiState.sasConfirmed,
                        onConfirmedChange = viewModel::onSasConfirmedChanged,
                    )
                }

                OutlinedTextField(
                    value = uiState.petname,
                    onValueChange = viewModel::onPetnameChanged,
                    label = { Text(stringResource(Res.string.contact_prompt_petname_label)) },
                    singleLine = true,
                    enabled = uiState.selectedPersonId == null,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Link-to-existing, collapsed by default (§2.3): selecting a
                // person re-points instead of creating.
                TextButton(onClick = viewModel::onLinkExpandedToggled) {
                    Text(stringResource(Res.string.contact_prompt_link_existing))
                }
                if (uiState.linkExpanded) {
                    if (uiState.persons.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.contact_prompt_link_existing_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    uiState.persons.forEach { person ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onPersonSelected(person.personId) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = uiState.selectedPersonId == person.personId,
                                onClick = { viewModel.onPersonSelected(person.personId) },
                            )
                            Text(
                                text = person.displayName.ifEmpty {
                                    stringResource(Res.string.contact_prompt_unnamed)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = uiState.ownName,
                    onValueChange = viewModel::onOwnNameChanged,
                    label = { Text(stringResource(Res.string.contact_prompt_own_name_label)) },
                    supportingText = {
                        Text(stringResource(Res.string.contact_prompt_own_name_note))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                uiState.errorCopyKey?.let { key ->
                    Text(
                        // ⚠⚠ The catalogue's sentence for the key, never the error object's
                        // `toString()` — which is what this slot used to be filled with.
                        text = stringResource(Res.string.contact_prompt_error, key.label()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::onSaveClicked,
                // Not just `!isSubmitting`: no accept before the claim has loaded (T-CI-10).
                enabled = uiState.canSave,
            ) {
                Text(stringResource(Res.string.contact_prompt_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = viewModel::onDeclineClicked,
                enabled = !uiState.isSubmitting,
            ) {
                Text(stringResource(Res.string.contact_prompt_decline))
            }
        },
    )
}

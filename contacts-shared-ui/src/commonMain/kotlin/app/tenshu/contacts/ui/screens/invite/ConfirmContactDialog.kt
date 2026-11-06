package app.tenshu.contacts.ui.screens.invite

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tenshu.contacts.ui.error.contactsLabel
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_confirm_claim_note
import app.tenshu.contacts.shared.resources.contacts_confirm_decline
import app.tenshu.contacts.shared.resources.contacts_confirm_header
import app.tenshu.contacts.shared.resources.contacts_confirm_link_existing
import app.tenshu.contacts.shared.resources.contacts_confirm_link_existing_none
import app.tenshu.contacts.shared.resources.contacts_confirm_own_name_label
import app.tenshu.contacts.shared.resources.contacts_confirm_own_name_note
import app.tenshu.contacts.shared.resources.contacts_confirm_petname_label
import app.tenshu.contacts.shared.resources.contacts_confirm_sas_check
import app.tenshu.contacts.shared.resources.contacts_confirm_sas_note
import app.tenshu.contacts.shared.resources.contacts_confirm_save
import app.tenshu.contacts.shared.resources.contacts_confirm_unnamed
import app.tenshu.ui.input.AppTextField
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/**
 * The completion prompt for a bare invitation — the surface
 * `drive-manage-access-ux.md` §2.3 parked for "the v2 Contacts app".
 *
 * Three rules the layout encodes rather than merely respects:
 *
 *  - the claimed name appears **quoted**, with the key fingerprint beneath it, and the
 *    petname field is separate — the name they assert is evidence, not identity;
 *  - the SAS check is **unticked by default** and its own explicit act, because ticking it
 *    is what elevates the key to `VERIFIED` (threat-model A24: never auto-elevated);
 *  - *Link to existing* is collapsed, because re-pointing a key at someone you already know
 *    is the rarer and more consequential choice.
 */
@Composable
fun ConfirmContactDialog(
    viewModel: ConfirmContactViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.done) {
        if (state.done) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    Res.string.contacts_confirm_header,
                    state.claimedName.ifBlank { stringResource(Res.string.contacts_confirm_unnamed) },
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                Text(
                    text = stringResource(Res.string.contacts_confirm_claim_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.claimedFingerprint,
                    style = MaterialTheme.typography.titleSmall,
                )

                if (state.hasSas) {
                    Text(
                        text = state.claimedSas,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = MaterialTheme.spacing.s),
                    )
                    Text(
                        text = stringResource(Res.string.contacts_confirm_sas_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onSasConfirmedChanged(!state.sasConfirmed) },
                    ) {
                        Checkbox(
                            checked = state.sasConfirmed,
                            onCheckedChange = viewModel::onSasConfirmedChanged,
                        )
                        Text(stringResource(Res.string.contacts_confirm_sas_check))
                    }
                }

                AppTextField(
                    value = state.petname,
                    onValueChange = viewModel::onPetnameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(Res.string.contacts_confirm_petname_label),
                    enabled = state.selectedPersonId == null,
                )

                AppTextField(
                    value = state.ownName,
                    onValueChange = viewModel::onOwnNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(Res.string.contacts_confirm_own_name_label),
                    supportingText = stringResource(Res.string.contacts_confirm_own_name_note),
                )

                Text(
                    text = stringResource(Res.string.contacts_confirm_link_existing),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onLinkExpandedToggled() }
                        .padding(vertical = MaterialTheme.spacing.xs),
                )
                if (state.linkExpanded) {
                    if (state.contacts.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.contacts_confirm_link_existing_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.contacts.forEach { contact ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.onContactSelected(contact.personId) },
                            ) {
                                RadioButton(
                                    selected = state.selectedPersonId == contact.personId,
                                    onClick = { viewModel.onContactSelected(contact.personId) },
                                )
                                Text(contact.displayName)
                            }
                        }
                    }
                }

                state.errorCopyKey?.let { key ->
                    Text(
                        text = key.contactsLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::onSaveClicked, enabled = state.canSave) {
                Text(stringResource(Res.string.contacts_confirm_save))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::onDeclineClicked, enabled = !state.isSubmitting) {
                Text(stringResource(Res.string.contacts_confirm_decline))
            }
        },
    )
}

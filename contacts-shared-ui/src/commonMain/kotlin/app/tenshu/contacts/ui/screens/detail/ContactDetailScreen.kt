package app.tenshu.contacts.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.contacts.ui.error.contactsLabel
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactField
import app.tenshu.contacts.model.ContactIdentity
import app.tenshu.contacts.model.TrustState
import app.tenshu.contacts.model.Verification
import app.tenshu.contacts.model.displayFingerprint
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_detail_claimed_name
import app.tenshu.contacts.shared.resources.contacts_detail_info_title
import app.tenshu.contacts.shared.resources.contacts_detail_keyring_title
import app.tenshu.contacts.shared.resources.contacts_detail_note
import app.tenshu.contacts.shared.resources.contacts_detail_organisation
import app.tenshu.contacts.shared.resources.contacts_edit_details_title
import app.tenshu.contacts.shared.resources.contacts_detail_missing_body
import app.tenshu.contacts.shared.resources.contacts_detail_missing_title
import app.tenshu.contacts.shared.resources.contacts_detail_no_keys_body
import app.tenshu.contacts.shared.resources.contacts_detail_title
import app.tenshu.contacts.shared.resources.contacts_delete_access_warning
import app.tenshu.contacts.shared.resources.contacts_delete_body
import app.tenshu.contacts.shared.resources.contacts_delete_confirm
import app.tenshu.contacts.shared.resources.contacts_delete_title
import app.tenshu.contacts.shared.resources.contacts_dialog_cancel
import app.tenshu.contacts.shared.resources.contacts_rename_confirm
import app.tenshu.contacts.shared.resources.contacts_rename_supporting
import app.tenshu.contacts.shared.resources.contacts_photo_remove
import app.tenshu.contacts.shared.resources.contacts_rename_title
import app.tenshu.contacts.shared.resources.contacts_unlink_body
import app.tenshu.contacts.shared.resources.contacts_unlink_confirm
import app.tenshu.contacts.shared.resources.contacts_unlink_title
import app.tenshu.contacts.shared.resources.contacts_unverify_action
import app.tenshu.contacts.shared.resources.contacts_verify_action
import app.tenshu.contacts.shared.resources.contacts_verify_body
import app.tenshu.contacts.shared.resources.contacts_verify_confirm
import app.tenshu.contacts.shared.resources.contacts_verify_loading
import app.tenshu.contacts.shared.resources.contacts_verify_title
import app.tenshu.contacts.shared.resources.contacts_verify_unavailable
import app.tenshu.contacts.ui.components.ContactAvatar
import app.tenshu.contacts.ui.components.ContactDetailsEditor
import app.tenshu.contacts.ui.components.NameDialog
import app.tenshu.contacts.ui.components.TrustBadge
import app.tenshu.contacts.ui.components.trustDetail
import app.tenshu.contacts.ui.components.trustLabel
import app.tenshu.ui.chrome.AppEmptyState
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.feedback.ErrorBanner
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/**
 * One contact (`docs/proposals/contacts-app.md` D3/D4). The header carries the petname and
 * the derived trust state; underneath it the **keyring**, one row per key.
 *
 * Showing the keyring rather than a single "verified" flag is the point: trust lives on the
 * key, and a person can hold several (rotation, re-handshake). It is what makes the Q2b
 * demotion legible — the user can see the old verified key sitting beside the new unverified
 * one instead of being told, without explanation, that a checkmark disappeared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    viewModel: ContactDetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val contact = state.contact

    // The row is gone — leave rather than render a "contact not found" screen the user
    // never asked for.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    if (state.renaming) {
        NameDialog(
            title = stringResource(Res.string.contacts_rename_title),
            supporting = stringResource(Res.string.contacts_rename_supporting),
            value = state.draftName,
            confirmLabel = stringResource(Res.string.contacts_rename_confirm),
            canConfirm = state.canSaveName,
            onValueChange = viewModel::onDraftNameChanged,
            onConfirm = viewModel::onRenameConfirmed,
            onDismiss = viewModel::onRenameDismissed,
        )
    }

    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = viewModel::onDeleteDismissed,
            title = { Text(stringResource(Res.string.contacts_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                    Text(stringResource(Res.string.contacts_delete_body))
                    // ⚠⚠ Q7b. Deleting a contact revokes nothing — memberships are keyed by
                    // the member's A26 identity, not by personId — so a user deleting
                    // someone to cut them off would otherwise get the opposite, silently.
                    Text(
                        text = stringResource(Res.string.contacts_delete_access_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::onDeleteConfirmed, enabled = !state.isSaving) {
                    Text(
                        text = stringResource(Res.string.contacts_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDeleteDismissed) {
                    Text(stringResource(Res.string.contacts_dialog_cancel))
                }
            },
        )
    }

    state.confirmingUnlink?.let { fingerprint ->
        AlertDialog(
            onDismissRequest = viewModel::onUnlinkDismissed,
            title = { Text(stringResource(Res.string.contacts_unlink_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.contacts_unlink_body,
                        displayFingerprint(fingerprint),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::onUnlinkConfirmed, enabled = !state.isSaving) {
                    Text(stringResource(Res.string.contacts_unlink_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onUnlinkDismissed) {
                    Text(stringResource(Res.string.contacts_dialog_cancel))
                }
            },
        )
    }

    state.editingDetails?.let { editing ->
        ContactDetailsEditor(
            details = editing,
            isSaving = state.isSaving,
            onFieldChanged = viewModel::onDetailFieldChanged,
            onFieldAdded = viewModel::onDetailFieldAdded,
            onOrganisationChanged = viewModel::onOrganisationChanged,
            onNoteChanged = viewModel::onNoteChanged,
            onSave = viewModel::onEditDetailsSaved,
            onDismiss = viewModel::onEditDetailsDismissed,
        )
    }

    state.verifying?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::onVerifyDismissed,
            title = { Text(stringResource(Res.string.contacts_verify_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                    Text(
                        text = stringResource(Res.string.contacts_verify_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (prompt.hasSas) {
                        Text(
                            text = prompt.sas,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = displayFingerprint(prompt.fingerprint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        // No digits: either still loading, or the realm is locked. Either
                        // way there is nothing to compare, so confirm stays disabled below.
                        Text(
                            text = stringResource(
                                if (prompt.loading) {
                                    Res.string.contacts_verify_loading
                                } else {
                                    Res.string.contacts_verify_unavailable
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                // ⚠ Gated on digits actually being on screen — confirming a code nobody
                // could read is how a key gets marked verified without a comparison.
                TextButton(
                    onClick = viewModel::onVerifyConfirmed,
                    enabled = prompt.hasSas && !state.isSaving,
                ) {
                    Text(stringResource(Res.string.contacts_verify_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onVerifyDismissed) {
                    Text(stringResource(Res.string.contacts_dialog_cancel))
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        AppTopAppBar(
            title = stringResource(Res.string.contacts_detail_title),
            onBack = onBack,
            actions = if (contact == null) {
                null
            } else {
                {
                    IconButton(onClick = viewModel::onEditDetailsClicked) {
                        Icon(
                            imageVector = Icons.Default.ContactPage,
                            contentDescription = stringResource(Res.string.contacts_edit_details_title),
                        )
                    }
                    IconButton(onClick = viewModel::onRenameClicked) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(Res.string.contacts_rename_title),
                        )
                    }
                    IconButton(onClick = viewModel::onDeleteClicked) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(Res.string.contacts_delete_title),
                        )
                    }
                }
            },
        )

        state.errorCopyKey?.let { key -> ErrorBanner(key.contactsLabel()) }

        when {
            !state.loaded -> AppLoadingIndicator(Modifier.padding(MaterialTheme.spacing.xxl))

            contact == null -> AppEmptyState(
                title = stringResource(Res.string.contacts_detail_missing_title),
                body = stringResource(Res.string.contacts_detail_missing_body),
                icon = Icons.Outlined.PersonOff,
            )

            else -> ContactDetailContent(
                contact = contact,
                onUnlinkKey = viewModel::onUnlinkClicked,
                onVerifyKey = viewModel::onVerifyClicked,
                onUnverifyKey = viewModel::onUnverifyClicked,
                onRemovePhoto = viewModel::onRemovePhotoClicked,
            )
        }
    }
}

@Composable
private fun ContactDetailContent(
    contact: Contact,
    onUnlinkKey: (String) -> Unit,
    onVerifyKey: (String) -> Unit,
    onUnverifyKey: (String) -> Unit,
    onRemovePhoto: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .appContentWidth()
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = MaterialTheme.spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ContactAvatar(
            displayName = contact.displayName,
            personId = contact.personId,
            size = 96.dp,
            photo = contact.avatar,
        )

        // Only when there is one to remove. A permanent "Remove photo" under a monogram
        // would read as a broken affordance, and there is no *set* counterpart yet to pair
        // it with (D1's other source, pick-from-Drive, waits on the §5 picker track).
        if (contact.avatar != null) {
            TextButton(onClick = onRemovePhoto) {
                Text(stringResource(Res.string.contacts_photo_remove))
            }
        }

        Text(
            text = contact.displayName,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(
                top = MaterialTheme.spacing.l,
                start = MaterialTheme.spacing.l,
                end = MaterialTheme.spacing.l,
            ),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
        ) {
            TrustBadge(contact.trust)
            Text(
                text = trustLabel(contact.trust),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = trustDetail(contact.trust),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(
                top = MaterialTheme.spacing.s,
                start = MaterialTheme.spacing.xl,
                end = MaterialTheme.spacing.xl,
            ),
        )

        // The Ph6 read-through union: everything the contact's source records say about
        // them, merged across records. Absent for a handshake-only contact, which carries
        // no card at all — so the section hides rather than showing an empty shell.
        if (!contact.details.isEmpty) {
            HorizontalDivider(Modifier.padding(vertical = MaterialTheme.spacing.l))
            SectionHeader(stringResource(Res.string.contacts_detail_info_title))
            contact.details.phones.forEach { DetailRow(it) }
            contact.details.emails.forEach { DetailRow(it) }
            contact.details.addresses.forEach { DetailRow(it) }
            if (contact.details.organisation.isNotEmpty()) {
                AppListItem(
                    headline = contact.details.organisation,
                    supportingText = stringResource(Res.string.contacts_detail_organisation),
                )
            }
            if (contact.details.note.isNotEmpty()) {
                AppListItem(
                    headline = contact.details.note,
                    supportingText = stringResource(Res.string.contacts_detail_note),
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = MaterialTheme.spacing.l))

        SectionHeader(stringResource(Res.string.contacts_detail_keyring_title))

        if (contact.identities.isEmpty()) {
            // D5: a contact with no key is an ordinary contact, not a broken one. Say what
            // it means rather than leaving an empty section that reads as a failure.
            Text(
                text = stringResource(Res.string.contacts_detail_no_keys_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.l, vertical = MaterialTheme.spacing.s),
            )
        } else {
            contact.identities.forEach { identity ->
                IdentityRow(
                    identity = identity,
                    onUnlink = { onUnlinkKey(identity.fingerprint) },
                    onVerify = { onVerifyKey(identity.fingerprint) },
                    onUnverify = { onUnverifyKey(identity.fingerprint) },
                )
            }
        }
    }
}

@Composable
private fun IdentityRow(
    identity: ContactIdentity,
    onUnlink: () -> Unit,
    onVerify: () -> Unit,
    onUnverify: () -> Unit,
) {
    val isVerified = identity.verification != Verification.UNVERIFIED
    AppListItem(
        // The fingerprint is the row's identity — the claimed name is a claim and is
        // deliberately demoted to supporting text, quoted, never promoted to the headline.
        headline = displayFingerprint(identity.fingerprint),
        supportingText = stringResource(
            Res.string.contacts_detail_claimed_name,
            identity.selfAssertedName.ifBlank { "—" },
        ),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrustBadge(
                    when (identity.verification) {
                        Verification.UNVERIFIED -> TrustState.Unverified
                        Verification.VERIFIED, Verification.ATTESTED -> TrustState.Verified
                    }
                )
                // The two remedies for a key that reads "not verified": compare the code
                // (Q2a), or — for a stale one — drop it.
                TextButton(
                    onClick = if (isVerified) onUnverify else onVerify,
                    // ATTESTED is Rei-carried and outranks a local verdict; the op refuses
                    // to move it, so the screen does not pretend otherwise.
                    enabled = identity.verification != Verification.ATTESTED,
                ) {
                    Text(
                        stringResource(
                            if (isVerified) {
                                Res.string.contacts_unverify_action
                            } else {
                                Res.string.contacts_verify_action
                            }
                        )
                    )
                }
                IconButton(onClick = onUnlink) {
                    Icon(
                        imageVector = Icons.Default.LinkOff,
                        contentDescription = stringResource(Res.string.contacts_unlink_title),
                    )
                }
            }
        },
    )
}

/** One value with its type label — `mobile`, `work`, or none if the record gave none. */
@Composable
private fun DetailRow(field: ContactField) {
    AppListItem(
        headline = field.value,
        supportingText = field.label.ifEmpty { null },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.l, vertical = MaterialTheme.spacing.s),
    )
}

package app.tenshu.contacts.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.contacts.model.displayFingerprint
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_invite_create
import app.tenshu.contacts.shared.resources.contacts_profile_edit_name
import app.tenshu.contacts.shared.resources.contacts_profile_identity_pending
import app.tenshu.contacts.shared.resources.contacts_profile_identity_title
import app.tenshu.contacts.shared.resources.contacts_profile_name_cancel
import app.tenshu.contacts.shared.resources.contacts_profile_name_label
import app.tenshu.contacts.shared.resources.contacts_profile_name_save
import app.tenshu.contacts.shared.resources.contacts_profile_name_supporting
import app.tenshu.contacts.shared.resources.contacts_profile_name_unset
import app.tenshu.contacts.shared.resources.contacts_profile_title
import app.tenshu.contacts.ui.components.ContactAvatar
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.input.AppTextField
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.legal.AppPrivacyPolicyListItem
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource
import app.tenshu.ui.legal.OpenSourceLicencesListItem

/**
 * The own profile card (`docs/proposals/contacts-app.md` D6) — a detail-shaped view of
 * yourself, which is why it deliberately reuses the same monogram and section furniture as
 * the contact detail.
 *
 * The name here is the platform-level `OwnerPreferences.Name`: the same name Drive puts
 * inside handshake payloads and Fit's Profile shows. Editing it here edits it everywhere,
 * which is the point — Drive prompted for it inline only because it had no profile surface
 * of its own.
 *
 * The identity section renders its absence honestly. There is no client-readable
 * own-identity op today and the invite QR / link is Ph3; inventing a fingerprint to fill the
 * space would be the mock-data-as-real mistake Timeline's shell shipped once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onInviteSomeone: () -> Unit = {},
    /** Opens the open-source notices (`docs/oss-licences.md`). */
    onOpenLicences: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        AppTopAppBar(title = stringResource(Res.string.contacts_profile_title))

        if (!state.loaded) {
            AppLoadingIndicator(Modifier.padding(MaterialTheme.spacing.xxl))
            // ⚠ The legal links show in every state, loading included (App Store 5.1.1(i)).
            AppPrivacyPolicyListItem(Modifier.fillMaxWidth())
            OpenSourceLicencesListItem(onClick = onOpenLicences, modifier = Modifier.fillMaxWidth())
            return@Column
        }

        Column(
            modifier = Modifier
                .appContentWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ContactAvatar(
                displayName = state.profile.displayName,
                // A stable, non-empty key so the monogram tint doesn't change as the name
                // is edited — this card has no personId of its own.
                personId = "tenshu/self",
                size = 96.dp,
            )

            if (state.editing) {
                AppTextField(
                    value = state.draftName,
                    onValueChange = viewModel::onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = MaterialTheme.spacing.l,
                            start = MaterialTheme.spacing.l,
                            end = MaterialTheme.spacing.l,
                        ),
                    label = stringResource(Res.string.contacts_profile_name_label),
                    supportingText = stringResource(Res.string.contacts_profile_name_supporting),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
                    modifier = Modifier.padding(top = MaterialTheme.spacing.s),
                ) {
                    TextButton(onClick = viewModel::cancelEditing) {
                        Text(stringResource(Res.string.contacts_profile_name_cancel))
                    }
                    Button(onClick = viewModel::save, enabled = state.canSave) {
                        Text(stringResource(Res.string.contacts_profile_name_save))
                    }
                }
            } else {
                Text(
                    text = state.profile.displayName.ifBlank {
                        stringResource(Res.string.contacts_profile_name_unset)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(
                        top = MaterialTheme.spacing.l,
                        start = MaterialTheme.spacing.l,
                        end = MaterialTheme.spacing.l,
                    ),
                )
                TextButton(onClick = viewModel::startEditing) {
                    Text(stringResource(Res.string.contacts_profile_edit_name))
                }
            }

            // D6: the card is where you hand your identity to someone. A bare invitation
            // carries no share intent — the whole outcome is that you two become contacts.
            Button(
                onClick = onInviteSomeone,
                modifier = Modifier.padding(top = MaterialTheme.spacing.s),
            ) {
                Text(stringResource(Res.string.contacts_invite_create))
            }

            HorizontalDivider(Modifier.padding(vertical = MaterialTheme.spacing.l))

            Text(
                text = stringResource(Res.string.contacts_profile_identity_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.l),
            )

            Text(
                text = if (state.hasFingerprint) {
                    displayFingerprint(state.profile.fingerprint)
                } else {
                    stringResource(Res.string.contacts_profile_identity_pending)
                },
                style = if (state.hasFingerprint) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.l, vertical = MaterialTheme.spacing.s),
            )

            // The legal entries close "You", Contacts' only settings-like screen: the in-app privacy
            // policy (App Store 5.1.1(i), Play User Data policy), then the open-source notices this
            // binary ships (docs/oss-licences.md).
            HorizontalDivider(Modifier.padding(vertical = MaterialTheme.spacing.l))
            AppPrivacyPolicyListItem(Modifier.fillMaxWidth())
            OpenSourceLicencesListItem(onClick = onOpenLicences, modifier = Modifier.fillMaxWidth())
        }
    }
}

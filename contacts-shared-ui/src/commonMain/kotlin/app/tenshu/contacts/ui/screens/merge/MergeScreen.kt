package app.tenshu.contacts.ui.screens.merge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.tenshu.contacts.ui.error.contactsLabel
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.MergeCandidate
import app.tenshu.contacts.model.MergeReason
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_merge_confirm
import app.tenshu.contacts.shared.resources.contacts_merge_empty_body
import app.tenshu.contacts.shared.resources.contacts_merge_empty_title
import app.tenshu.contacts.shared.resources.contacts_merge_keep_label
import app.tenshu.contacts.shared.resources.contacts_merge_name_only_note
import app.tenshu.contacts.shared.resources.contacts_merge_no_undo
import app.tenshu.contacts.shared.resources.contacts_merge_pair
import app.tenshu.contacts.shared.resources.contacts_merge_reason_email
import app.tenshu.contacts.shared.resources.contacts_merge_reason_name
import app.tenshu.contacts.shared.resources.contacts_merge_reason_phone
import app.tenshu.contacts.shared.resources.contacts_merge_title
import app.tenshu.contacts.shared.resources.contacts_dialog_cancel
import app.tenshu.contacts.ui.components.ContactAvatar
import app.tenshu.ui.chrome.AppEmptyState
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.feedback.ErrorBanner
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/**
 * *Possible duplicates* (`docs/proposals/contacts-app.md` D7, Ph9).
 *
 * ⚠⚠ Every row here is a **question**, not a finding. Nothing merges until the user opens a
 * pair, reads what will be combined, and confirms — D7's *never auto-merge* rule, which
 * exists because everything a handshake delivers is self-asserted: a system that merged on a
 * name match would let an attacker's key inherit a known person's contact card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(
    viewModel: MergeViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    state.reviewing?.let { review ->
        AlertDialog(
            onDismissRequest = viewModel::onReviewDismissed,
            title = { Text(stringResource(Res.string.contacts_merge_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                    Text(
                        text = stringResource(Res.string.contacts_merge_keep_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // The one question worth asking, because it is the only choice that
                    // changes the outcome: the target's petname is what remains.
                    listOf(review.candidate.first, review.candidate.second).forEach { contact ->
                        TargetChoice(
                            contact = contact,
                            selected = contact.personId == review.targetPersonId,
                            onSelect = { viewModel.onTargetChanged(contact.personId) },
                        )
                    }
                    // Stated before the merge, not discovered after it. The rows that move
                    // carry no memory of where they came from.
                    Text(
                        text = stringResource(Res.string.contacts_merge_no_undo),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::onMergeConfirmed, enabled = !state.isSaving) {
                    Text(stringResource(Res.string.contacts_merge_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onReviewDismissed) {
                    Text(stringResource(Res.string.contacts_dialog_cancel))
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        AppTopAppBar(
            title = stringResource(Res.string.contacts_merge_title),
            onBack = onBack,
        )

        state.errorCopyKey?.let { key -> ErrorBanner(key.contactsLabel()) }

        when {
            !state.loaded -> AppLoadingIndicator(Modifier.padding(MaterialTheme.spacing.xxl))

            !state.hasCandidates -> AppEmptyState(
                title = stringResource(Res.string.contacts_merge_empty_title),
                body = stringResource(Res.string.contacts_merge_empty_body),
                icon = Icons.Outlined.CheckCircle,
            )

            else -> LazyColumn(Modifier.appContentWidth().fillMaxSize()) {
                items(
                    items = state.candidates,
                    key = { "${it.first.personId}|${it.second.personId}" },
                ) { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        onClick = { viewModel.onCandidateClicked(candidate) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: MergeCandidate, onClick: () -> Unit) {
    AppListItem(
        headline = stringResource(
            Res.string.contacts_merge_pair,
            candidate.first.displayName,
            candidate.second.displayName,
        ),
        // The reason is the whole content of the row: "same name" and "same phone number"
        // deserve very different amounts of the user's trust, and hiding which one fired
        // would make the weakest suggestion look like the strongest.
        supportingText = stringResource(
            when (candidate.headlineReason) {
                MergeReason.SharedPhone -> Res.string.contacts_merge_reason_phone
                MergeReason.SharedEmail -> Res.string.contacts_merge_reason_email
                MergeReason.SameName -> Res.string.contacts_merge_reason_name
            }
        ) + if (candidate.isNameOnly) {
            " " + stringResource(Res.string.contacts_merge_name_only_note)
        } else {
            ""
        },
        onClick = onClick,
        leadingContent = { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null) },
    )
}

@Composable
private fun TargetChoice(contact: Contact, selected: Boolean, onSelect: () -> Unit) {
    AppListItem(
        headline = contact.displayName,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        leadingContent = {
            ContactAvatar(
                displayName = contact.displayName,
                personId = contact.personId,
                photo = contact.avatar,
            )
        },
        trailingContent = { RadioButton(selected = selected, onClick = onSelect) },
    )
}

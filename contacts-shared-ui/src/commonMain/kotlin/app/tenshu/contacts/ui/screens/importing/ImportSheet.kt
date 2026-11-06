package app.tenshu.contacts.ui.screens.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ImportContacts
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_import_address_book
import app.tenshu.contacts.shared.resources.contacts_import_address_book_supporting
import app.tenshu.contacts.shared.resources.contacts_import_done
import app.tenshu.contacts.shared.resources.contacts_import_failed_no_cards
import app.tenshu.contacts.shared.resources.contacts_import_failed_nothing
import app.tenshu.contacts.shared.resources.contacts_import_failed_permission
import app.tenshu.contacts.shared.resources.contacts_import_failed_unreadable
import app.tenshu.contacts.shared.resources.contacts_import_file
import app.tenshu.contacts.shared.resources.contacts_import_file_supporting
import app.tenshu.contacts.shared.resources.contacts_import_intro
import app.tenshu.contacts.shared.resources.contacts_import_no_merge_note
import app.tenshu.contacts.shared.resources.contacts_import_one_contact
import app.tenshu.contacts.shared.resources.contacts_import_one_contact_supporting
import app.tenshu.contacts.shared.resources.contacts_import_result_many
import app.tenshu.contacts.shared.resources.contacts_import_result_one
import app.tenshu.contacts.shared.resources.contacts_import_title
import app.tenshu.contacts.shared.resources.contacts_import_working
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * *Import contacts* (`docs/proposals/contacts-app.md` Ph7). One sheet over the platform seam:
 * it lists whichever sources [ContactImporter.supports] returns true for, so Android offers
 * three rows and desktop one, without either shell knowing about the other.
 *
 * The sources are ordered by what they cost the user, cheapest first — one contact through a
 * picker that needs no permission, then a file, then the whole address book behind
 * `READ_CONTACTS`. Nothing here asks for a permission until its row is tapped.
 */
@Composable
fun ImportSheetContent(
    viewModel: ContactImportViewModel,
    importer: ContactImporter = rememberContactImporter(),
) {
    val state by viewModel.uiState.collectAsState()
    val imported = state.importedCount

    fun launch(source: ContactImportSource) {
        viewModel.onPickStarted()
        importer.import(source) { outcome ->
            when (outcome) {
                is ContactImportOutcome.Cards -> viewModel.onVcardText(outcome.vcard)
                ContactImportOutcome.Cancelled -> viewModel.onPickCancelled()
                is ContactImportOutcome.Failed -> viewModel.onPickFailed(outcome.problem.toProblem())
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
    ) {
        Text(
            text = stringResource(Res.string.contacts_import_title),
            style = MaterialTheme.typography.titleLarge,
        )

        when {
            state.isImporting -> {
                Text(
                    text = stringResource(Res.string.contacts_import_working),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                AppLoadingIndicator(Modifier.padding(MaterialTheme.spacing.l))
            }

            imported != null -> {
                Text(
                    // Says the number, because "Imported" alone leaves the user to go and
                    // count. It is also how a partial import (some cards refused) is visible
                    // rather than passed off as complete.
                    text = if (imported == 1) {
                        stringResource(Res.string.contacts_import_result_one)
                    } else {
                        stringResource(Res.string.contacts_import_result_many, imported)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = viewModel::onResultAcknowledged) {
                    Text(stringResource(Res.string.contacts_import_done))
                }
            }

            else -> {
                Text(
                    text = stringResource(Res.string.contacts_import_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                state.problem?.let { problem ->
                    Text(
                        text = stringResource(problem.message()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                SourceRow(
                    source = ContactImportSource.OneContact,
                    importer = importer,
                    icon = Icons.Default.PersonSearch,
                    label = Res.string.contacts_import_one_contact,
                    supporting = Res.string.contacts_import_one_contact_supporting,
                    onClick = ::launch,
                )
                SourceRow(
                    source = ContactImportSource.File,
                    importer = importer,
                    icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                    label = Res.string.contacts_import_file,
                    supporting = Res.string.contacts_import_file_supporting,
                    onClick = ::launch,
                )
                SourceRow(
                    source = ContactImportSource.AddressBook,
                    importer = importer,
                    icon = Icons.Default.ImportContacts,
                    label = Res.string.contacts_import_address_book,
                    supporting = Res.string.contacts_import_address_book_supporting,
                    onClick = ::launch,
                )

                // D7 stated up front rather than discovered afterwards: importing a file you
                // already imported gives you everyone twice, and merging is never automatic —
                // the note points at the list's duplicates banner instead.
                Text(
                    text = stringResource(Res.string.contacts_import_no_merge_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: ContactImportSource,
    importer: ContactImporter,
    icon: ImageVector,
    label: StringResource,
    supporting: StringResource,
    onClick: (ContactImportSource) -> Unit,
) {
    // Absent, not disabled: a greyed-out "Import from your address book" on desktop would
    // read as something the user has failed to enable.
    if (!importer.supports(source)) return
    AppListItem(
        headline = stringResource(label),
        supportingText = stringResource(supporting),
        onClick = { onClick(source) },
        modifier = Modifier.fillMaxWidth(),
        leadingContent = { Icon(icon, contentDescription = null) },
    )
}

private fun ImportFailure.toProblem(): ContactImportProblem = when (this) {
    ImportFailure.PermissionDenied -> ContactImportProblem.PermissionDenied
    ImportFailure.Unreadable -> ContactImportProblem.Unreadable
    ImportFailure.NoCards -> ContactImportProblem.NoCards
}

private fun ContactImportProblem.message(): StringResource = when (this) {
    ContactImportProblem.PermissionDenied -> Res.string.contacts_import_failed_permission
    ContactImportProblem.Unreadable -> Res.string.contacts_import_failed_unreadable
    ContactImportProblem.NoCards -> Res.string.contacts_import_failed_no_cards
    ContactImportProblem.NothingImported -> Res.string.contacts_import_failed_nothing
}

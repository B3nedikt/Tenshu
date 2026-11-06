@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.tenshu.control.ui.screens.shares

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.data.ShareSummary
import app.tenshu.control.data.SharedFolderSummary
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.shares_caps_expires
import app.tenshu.control.shared.resources.shares_caps_max_opens_plural
import app.tenshu.control.shared.resources.shares_caps_max_opens_singular
import app.tenshu.control.shared.resources.shares_caps_no_limits
import app.tenshu.control.shared.resources.shares_default_file_name
import app.tenshu.control.shared.resources.shares_empty_body
import app.tenshu.control.shared.resources.shares_empty_title
import app.tenshu.control.shared.resources.shares_revoke_action
import app.tenshu.control.shared.resources.shares_revoke_dialog_body
import app.tenshu.control.shared.resources.shares_revoke_dialog_cancel
import app.tenshu.control.shared.resources.shares_revoke_dialog_confirm
import app.tenshu.control.shared.resources.shares_revoke_dialog_title
import app.tenshu.control.shared.resources.shares_revoked_badge
import app.tenshu.control.shared.resources.shares_section_links
import app.tenshu.control.shared.resources.shares_section_shared_folders
import app.tenshu.control.shared.resources.shares_shared_folder_unnamed
import app.tenshu.control.shared.resources.shares_shared_on
import app.tenshu.control.shared.resources.shares_title
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource
import org.tenshu.core.common.util.TimestampUtil
import kotlin.time.Instant

/**
 * Data → Shares (`control-ia.md` §5.3): the realm-wide inventory of outbound
 * shares with per-row revoke. Stateful wrapper — collects [SharesViewModel] and
 * delegates rendering to the stateless [SharesScreenContent].
 */
@Composable
fun SharesScreen(
    viewModel: SharesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SharesScreenContent(
        uiState = state,
        onBack = onBack,
        onRevokeClicked = viewModel::onRevokeClicked,
        onRevokeConfirmed = viewModel::onRevokeConfirmed,
        onRevokeDismissed = viewModel::onRevokeDismissed,
    )
}

@Composable
fun SharesScreenContent(
    uiState: SharesUiState,
    onBack: () -> Unit,
    onRevokeClicked: (String) -> Unit,
    onRevokeConfirmed: () -> Unit,
    onRevokeDismissed: () -> Unit,
) {
    Scaffold(topBar = { AppTopAppBar(title = stringResource(Res.string.shares_title), onBack = onBack) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (uiState.shares.isEmpty() && uiState.sharedFolders.isEmpty()) {
                EmptyShares(modifier = Modifier.appContentWidth().padding(24.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.appContentWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (uiState.shares.isNotEmpty()) {
                        item { SectionHeader(stringResource(Res.string.shares_section_links)) }
                        items(uiState.shares, key = { it.shareId }) { share ->
                            ShareRow(
                                share = share,
                                onRevoke = { onRevokeClicked(share.shareId) },
                            )
                        }
                    }
                    // The "Shared folders" group renders only when the realm is in at
                    // least one (owned or joined) — control-ia.md §5.3.
                    if (uiState.sharedFolders.isNotEmpty()) {
                        item { SectionHeader(stringResource(Res.string.shares_section_shared_folders)) }
                        items(uiState.sharedFolders, key = { it.realmId }) { folder ->
                            SharedFolderRow(folder)
                        }
                    }
                }
            }
        }
    }

    uiState.pendingRevokeShareId?.let { shareId ->
        val fallback = stringResource(Res.string.shares_default_file_name)
        val name = uiState.shares.firstOrNull { it.shareId == shareId }?.fileName ?: fallback
        RevokeConfirmDialog(
            fileName = name,
            onConfirm = onRevokeConfirmed,
            onDismiss = onRevokeDismissed,
        )
    }
}

@Composable
private fun EmptyShares(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.shares_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.shares_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ShareRow(
    share: ShareSummary,
    onRevoke: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = share.fileName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (share.revoked) {
                        RevokedBadge(modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(
                    text = stringResource(
                        Res.string.shares_shared_on,
                        TimestampUtil.formatTimestamp(
                            Instant.fromEpochMilliseconds(share.createdAtMillis)
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = capsLineText(share),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Revoking an already-revoked share is a no-op — hide the action.
            if (!share.revoked) {
                IconButton(onClick = onRevoke) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.shares_revoke_action),
                    )
                }
            }
        }
    }
}

@Composable
private fun RevokedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(Res.string.shares_revoked_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SharedFolderRow(folder: SharedFolderSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // A realm whose wrapper root hasn't synced yet has no name — the joiner's
            // first moments after accepting. Same fallback Drive's *Shared with me* uses.
            Text(
                text = folder.name ?: stringResource(Res.string.shares_shared_folder_unnamed),
                style = MaterialTheme.typography.titleMedium,
            )
            // ADR 0016: literal `{count}` ICU placeholders in the catalogue, inlined
            // here until a MessageFormat helper lands. Plural/select branches resolved
            // in Kotlin against the English source.
            val ownerSuffix = if (folder.ownerSelf) " · you own this" else ""
            // A null count means the roster read failed, not that the folder is empty —
            // so the line drops the count rather than claiming "0 members".
            val subtitle = when (folder.memberCount) {
                null -> if (folder.ownerSelf) "You own this" else "Shared with you"
                1 -> "1 member$ownerSuffix"
                else -> "${folder.memberCount} members$ownerSuffix"
            }
            // Tests assert on rendered English text — keep the surface identical.
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RevokeConfirmDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.shares_revoke_dialog_title)) },
        text = {
            Text(stringResource(Res.string.shares_revoke_dialog_body, fileName))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.shares_revoke_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.shares_revoke_dialog_cancel))
            }
        },
    )
}

/** "Up to N opens · expires <date>" / "No limits" — the share's caps in one line. */
@Composable
private fun capsLineText(share: ShareSummary): String {
    val noLimits = stringResource(Res.string.shares_caps_no_limits)
    val expiresFormatted = share.expiresAtMillis?.let {
        stringResource(
            Res.string.shares_caps_expires,
            TimestampUtil.formatTimestamp(Instant.fromEpochMilliseconds(it)),
        )
    }
    val parts = buildList {
        share.maxOpens?.let { count ->
            add(
                if (count == 1) stringResource(Res.string.shares_caps_max_opens_singular)
                else stringResource(Res.string.shares_caps_max_opens_plural, count)
            )
        }
        expiresFormatted?.let { add(it) }
    }
    return if (parts.isEmpty()) noLimits
    else parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

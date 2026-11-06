package app.tenshu.drive.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.multi_select_delete
import app.tenshu.drive.shared.resources.multi_select_make_offline
import app.tenshu.drive.shared.resources.multi_select_move
import app.tenshu.drive.shared.resources.multi_select_share
import app.tenshu.drive.shared.resources.multi_select_share_link
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun MultiSelectBottomSheet(
    selectedPaths: Set<String>,
    isOffline: Boolean,
    /**
     * Mode-B *Share* is folder-only (drive-shared-realm-file-model.md §6 Phase
     * 3d.2b): the host enables the card iff exactly one **folder** is selected.
     */
    shareEnabled: Boolean,
    onShare: () -> Unit,
    /**
     * Mode-A (`docs/file-sharing.md`). v1 supports
     * single-file selection only — the host enables the card iff
     * `selectedPaths.size == 1`.
     */
    onShareLink: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onOfflineToggled: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.l)
                .padding(bottom = MaterialTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag Handle (Clickable to close)
            Box(
                modifier = Modifier
                    .padding(vertical = MaterialTheme.spacing.m)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    .clickable(onClick = onClose)
            )

            // Peek Content: Share and Delete
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MaterialTheme.spacing.l),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)
            ) {
                // *Share* — Mode-B (share a folder into a shared realm,
                // drive-shared-realm-file-model.md §6). Folder-only: enabled iff
                // exactly one folder is selected (3d.2b), greyed + inert otherwise.
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Share,
                    text = stringResource(Res.string.multi_select_share),
                    enabled = shareEnabled,
                    onClick = onShare
                )
                // *Share link* — Mode A. P1a wires the click; the host disables
                // the card unless exactly one file is selected (folder support
                // is P5 per the proposal's phased plan).
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Link,
                    text = stringResource(Res.string.multi_select_share_link),
                    onClick = onShareLink
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Delete,
                    text = stringResource(Res.string.multi_select_delete),
                    onClick = onDelete
                )
            }

            // Expanded Content: The rest of the actions
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.l)
            ) {
                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOfflineToggled(!isOffline) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.l)
                ) {
                    Icon(imageVector = Icons.Default.CloudDownload, contentDescription = "")
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(Res.string.multi_select_make_offline),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Switch(checked = isOffline, onCheckedChange = onOfflineToggled)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMove() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.l)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                        contentDescription = ""
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(Res.string.multi_select_move),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(MaterialTheme.spacing.l),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)
        ) {
            Icon(imageVector = icon, contentDescription = "", tint = contentColor)
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = contentColor
            )
        }
    }
}

@Preview
@Composable
fun MultiSelectBottomSheetPreview() {
    MultiSelectBottomSheet(
        selectedPaths = setOf("/file1", "/file2"),
        isOffline = false,
        shareEnabled = false,
        onShare = {},
        onShareLink = {},
        onDelete = {},
        onMove = {},
        onOfflineToggled = {},
        onClose = {}
    )
}

@Preview
@Composable
fun ActionCardPreview() {
    ActionCard(
        icon = Icons.Default.Share,
        text = "Share",
        onClick = {}
    )
}

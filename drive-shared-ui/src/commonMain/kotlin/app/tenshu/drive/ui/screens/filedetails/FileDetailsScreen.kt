package app.tenshu.drive.ui.screens.filedetails

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.ui.common.asImageVector
import app.tenshu.ui.image.decodePreviewBitmap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.FileActivity
import app.tenshu.drive.data.FileActivityKind
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.file_details_activity_kind_created
import app.tenshu.drive.shared.resources.file_details_activity_kind_modified
import app.tenshu.drive.shared.resources.file_details_activity_kind_moved
import app.tenshu.drive.shared.resources.file_details_activity_kind_renamed
import app.tenshu.drive.shared.resources.file_details_activity_on_device
import app.tenshu.drive.shared.resources.file_details_created
import app.tenshu.drive.shared.resources.file_details_last_modified
import app.tenshu.drive.shared.resources.file_details_no_activity
import app.tenshu.drive.shared.resources.file_details_section_activity
import app.tenshu.drive.shared.resources.file_details_section_details
import app.tenshu.drive.shared.resources.file_details_size
import app.tenshu.drive.shared.resources.file_item_preview_description
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.theme.TenshuTheme
import org.jetbrains.compose.resources.stringResource
import org.tenshu.core.common.util.TimestampUtil
import org.tenshu.core.common.util.formatTimestamp
import androidx.compose.ui.tooling.preview.Preview

/**
 * "Details & Activity" destination
 * ([docs/proposals/drive-file-details-screen.md]).
 *
 * Renders the header (icon + name), a **Details** section (size / created /
 * last-modified, Phase 2) and an **Activity** section (the modification history,
 * Phase 3). The "Who has access" section is deferred to the sharing proposal.
 */
@Composable
fun FileDetailsScreen(
    viewModel: FileDetailsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    FileDetailsScreen(
        uiState = uiState,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileDetailsScreen(
    uiState: FileDetailsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        AppTopAppBar(title = uiState.file.name, onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .appContentWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            FileDetailsHeader(file = uiState.file)

            DetailsSection(
                size = uiState.size,
                created = uiState.created,
                lastModified = uiState.lastModified,
            )

            ActivitySection(activity = uiState.activity)
        }
    }
}

@Composable
private fun FileDetailsHeader(
    file: FileUiModel,
) {
    // Icon→image swap, mirroring [HorizontalFileItem]: the category icon is the
    // loading / fallback state; once the thumbnail decodes it replaces the icon.
    // The bytes arrive through the details stream itself — `File.Preview(480, 480)`
    // is a DEFERRED projection (`core-deferred-properties.md`): metadata first,
    // then the stream patches the row and `previewBytes` flips non-null, which
    // re-keys this `produceState` and decodes off the UI thread.
    val previewImage: ImageBitmap? by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = file.path,
        key2 = file.previewBytes,
    ) {
        value = file.previewBytes?.decodePreviewBitmap()
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Local copy so the smart-cast survives the `!= null` check — see the
        // same note in [HorizontalFileItem].
        val resolvedPreview = previewImage
        if (resolvedPreview != null) {
            Image(
                modifier = Modifier.size(160.dp),
                bitmap = resolvedPreview,
                contentDescription = stringResource(Res.string.file_item_preview_description),
            )
        } else {
            Icon(
                imageVector = file.previewIcon.asImageVector(),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
        }
        Text(text = file.name, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DetailsSection(
    size: String?,
    created: String?,
    lastModified: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(Res.string.file_details_section_details))
        // `null` (not yet loaded) and "" (recorded as empty, e.g. a pre-createdAt
        // file) both fall back to the placeholder dash.
        MetadataRow(
            label = stringResource(Res.string.file_details_size),
            value = size.orPlaceholder()
        )
        MetadataRow(
            label = stringResource(Res.string.file_details_created),
            value = created.orPlaceholder()
        )
        MetadataRow(
            label = stringResource(Res.string.file_details_last_modified),
            value = lastModified.orPlaceholder()
        )
    }
}

private fun String?.orPlaceholder(): String =
    if (isNullOrEmpty()) PLACEHOLDER_VALUE else this

@Composable
private fun ActivitySection(activity: List<FileActivity>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(Res.string.file_details_section_activity))
        if (activity.isEmpty()) {
            Text(
                text = stringResource(Res.string.file_details_no_activity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            activity.forEach { ActivityRow(it) }
        }
    }
}

@Composable
private fun ActivityRow(activity: FileActivity) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = activity.kind.label(), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = TimestampUtil.formatTimestamp(activity.timestampMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                Res.string.file_details_activity_on_device,
                activity.deviceLabel,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        activity.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileActivityKind.label(): String = when (this) {
    FileActivityKind.Created -> stringResource(Res.string.file_details_activity_kind_created)
    FileActivityKind.Modified -> stringResource(Res.string.file_details_activity_kind_modified)
    FileActivityKind.Renamed -> stringResource(Res.string.file_details_activity_kind_renamed)
    FileActivityKind.Moved -> stringResource(Res.string.file_details_activity_kind_moved)
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Text(text = text, style = MaterialTheme.typography.titleSmall)
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

// Em-dash standing in for not-yet-plumbed metadata (Phase 2).
private const val PLACEHOLDER_VALUE = "—"

@Preview
@Composable
private fun FileDetailsScreenPreview() {
    TenshuTheme {
        Surface {
            Box {
                FileDetailsScreen(
                    uiState = FileDetailsUiState(
                        file = FileUiModel(
                            path = "/photos/sunset.jpg",
                            name = "sunset.jpg",
                            subLine = "",
                            previewIcon = FileIcon.GenericFile,
                        ),
                        size = "1.2 MB",
                        created = "Jan 1, 2024, 09:00",
                        lastModified = "Mar 3, 2024, 14:25",
                        activity = listOf(
                            FileActivity(FileActivityKind.Renamed, "old.jpg → sunset.jpg", 1_709_475_900_000, "d1"),
                            FileActivity(FileActivityKind.Created, null, 1_704_099_600_000, "d1"),
                        ),
                    ),
                    onBack = {},
                )
            }
        }
    }
}

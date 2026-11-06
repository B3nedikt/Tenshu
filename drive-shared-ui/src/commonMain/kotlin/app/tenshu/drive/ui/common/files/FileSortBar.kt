package app.tenshu.drive.ui.common.files

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileSortKey
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.file_sort_ascending
import app.tenshu.drive.shared.resources.file_sort_button_description
import app.tenshu.drive.shared.resources.file_sort_descending
import app.tenshu.drive.shared.resources.file_sort_key_created
import app.tenshu.drive.shared.resources.file_sort_key_modified
import app.tenshu.drive.shared.resources.file_sort_key_name
import app.tenshu.drive.shared.resources.file_sort_key_size
import app.tenshu.drive.shared.resources.file_sort_selected_description
import org.jetbrains.compose.resources.stringResource

/**
 * The folder-listing "Sort by" control — Drive's counterpart of the Google Drive
 * sort popup in the reference design. A labelled chip (current key + direction
 * arrow) that opens a [DropdownMenu] with the four sort keys and a direction
 * section, mirroring the reference's two-section layout (a check on both the
 * active key AND the active direction).
 *
 * Sits below the search bar / folder title (not flanking the search bar): the
 * subfolder screen has no search bar to flank, so a dedicated row is the only
 * placement uniform across root + subfolders. The right side of this row is where
 * the list/grid display toggle will land as a follow-up.
 */
@Composable
fun FileSortBar(
    sort: FileSort,
    onSortChange: (FileSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(modifier) {
        TextButton(onClick = { expanded = true }) {
            // ⚠ The chip always names a key and a direction. It used to read a neutral "Sort"
            // with no arrow before any explicit choice, because the listing was then in natural
            // (store) order — a state SP-6 removed (D5): there is always a sort, so a
            // placeholder would describe nothing.
            Text(labelFor(sort.key))
            Icon(
                imageVector = if (sort.descending) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.Default.KeyboardArrowUp
                },
                contentDescription = stringResource(Res.string.file_sort_button_description),
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Section 1 — the sort key. Picking a key keeps the current direction
            // (or the key's default when coming from the unsorted state), so a
            // check rides both sections.
            FileSortKey.entries.forEach { key ->
                DropdownMenuItem(
                    text = { Text(labelFor(key)) },
                    leadingIcon = leadingCheck(selected = key == sort.key),
                    onClick = {
                        onSortChange(FileSort(key, sort.descending))
                        expanded = false
                    },
                )
            }

            HorizontalDivider()

            // Section 2 — the direction, independent of the chosen key.
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.file_sort_ascending)) },
                leadingIcon = leadingCheck(selected = !sort.descending),
                onClick = {
                    onSortChange(FileSort(sort.key, descending = false))
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.file_sort_descending)) },
                leadingIcon = leadingCheck(selected = sort.descending),
                onClick = {
                    onSortChange(FileSort(sort.key, descending = true))
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun labelFor(key: FileSortKey): String = stringResource(
    when (key) {
        FileSortKey.NAME -> Res.string.file_sort_key_name
        FileSortKey.MODIFIED -> Res.string.file_sort_key_modified
        FileSortKey.CREATED -> Res.string.file_sort_key_created
        FileSortKey.SIZE -> Res.string.file_sort_key_size
    }
)

/**
 * A check icon when [selected], else a same-size spacer so the menu items' text
 * stays left-aligned whether or not their row is the active one.
 */
private fun leadingCheck(selected: Boolean): @Composable () -> Unit = {
    if (selected) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = stringResource(Res.string.file_sort_selected_description),
        )
    } else {
        Spacer(Modifier.size(24.dp))
    }
}

package app.tenshu.photos.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import app.tenshu.photos.ui.components.WidthScaledGridCells
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoHit
import app.tenshu.photos.ui.components.PhotoTile
import app.tenshu.photos.ui.components.PreviewLoader
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

/**
 * Search over the Home tab (`photos-app.md` S2) — one bar, three sources, one list.
 *
 * ⚠⚠ **It draws; it decides nothing** (`photos-ios.md` §8.19, PIO-D2). Which state is showing, the
 * note above the results, when a retry is offered, the badge and every sentence are the shared
 * [PhotoSearchLayout] / [PhotoSearchCopy] — the same ones the iOS screen draws.
 *
 * ⚠⚠ **The states that layout keeps apart**, and they look identical if you let them: *"nothing
 * matched"*, *"one of the sources cannot run here"* and *"a source could not look"*. `photosLike`
 * throws rather than answering empty for exactly this reason. Rendering a refusal as an empty result
 * is the mistake `mia-query-layer.md` §14.1 names.
 *
 * ⚠ On every device today sources 2 and 3 contribute nothing — the production backend registry is
 * empty — so the common case is name search alone, and the screen is built to read as complete
 * that way rather than as a feature with two thirds missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSearchScreen(
    state: PhotoSearchUiState,
    loadPreview: PreviewLoader,
    onQueryChanged: (String) -> Unit,
    onPhotoClick: (Photo) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The column count on a phone-width grid; a wider one multiplies it (`WidthScaledGridCells`). */
    columns: Int = 3,
    /** Ask again — what "could not look" offers (`PhotoSearchViewModel.retry`). */
    onRetry: () -> Unit = {},
    /**
     * The screen is back on top after the viewer (`PhotoSearchViewModel.refresh`): a photograph
     * trashed there must not still be a result here.
     */
    onReturn: () -> Unit = {},
) {
    // ⚠ The query field takes focus when the screen is first opened — by the Search button or by
    // Cmd/Ctrl+F, so the user can type at once without clicking into the field. ⚠ Once per
    // opening, remembered across a round trip through the viewer, so coming
    // back to the results does not raise a phone's keyboard over them again.
    val queryFocus = remember { FocusRequester() }
    var focusedOnOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusedOnOpen) {
            focusedOnOpen = true
            runCatching { queryFocus.requestFocus() }
        } else {
            // ⚠ Entered again with the flag already set: a RETURN, not an opening.
            onReturn()
        }
    }
    val layout = state.layout
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChanged,
                        placeholder = { Text(PhotoSearchCopy.PROMPT) },
                        singleLine = true,
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChanged("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        keyboardActions = KeyboardActions(),
                        modifier = Modifier.fillMaxWidth().focusRequester(queryFocus),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SourceNotes(layout, onRetry)

            when (val body = layout.body) {
                is PhotoSearchLayout.Body.Idle -> Message(body.text)

                PhotoSearchLayout.Body.Searching ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                is PhotoSearchLayout.Body.Empty -> Message(body.text)

                // ⚠⚠ Not the empty state: nothing was found because not everything was looked
                // through, and the way to look again is right here.
                is PhotoSearchLayout.Body.CouldNotLook -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = body.text,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onRetry) { Text(PhotoSearchCopy.RETRY) }
                    }
                }

                is PhotoSearchLayout.Body.Results -> LazyVerticalGrid(
                    columns = WidthScaledGridCells(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(body.hits, key = { it.photo.path }) { hit ->
                        Column {
                            PhotoTile(
                                photo = hit.photo,
                                loadPreview = loadPreview,
                                onClick = { onPhotoClick(hit.photo) },
                            )
                            SourceBadge(hit)
                        }
                    }
                    items(body.footers, span = { GridItemSpan(maxLineSpan) }) { footer ->
                        Text(
                            text = footer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Why a source did not contribute — [PhotoSearchLayout.notes].
 *
 * ⚠⚠ They appear **above** the results, not instead of them: name search may well have found
 * plenty, and a refusal from source 3 is a footnote on a working screen rather than an error state.
 */
@Composable
private fun SourceNotes(layout: PhotoSearchLayout, onRetry: () -> Unit) {
    if (layout.notes.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            layout.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (layout.retryInNotes) {
            TextButton(onClick = onRetry) { Text(PhotoSearchCopy.RETRY) }
        }
    }
    HorizontalDivider()
}

/** Which source found this row — [PhotoSearchCopy.badge]. */
@Composable
private fun SourceBadge(hit: PhotoHit) {
    val label = PhotoSearchCopy.badge(hit) ?: return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
    )
}

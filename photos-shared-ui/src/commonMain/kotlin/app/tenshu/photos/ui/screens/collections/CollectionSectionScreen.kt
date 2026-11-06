package app.tenshu.photos.ui.screens.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.ui.components.AlbumCard
import app.tenshu.photos.ui.components.CoverImage
import app.tenshu.photos.ui.components.PersonCard
import app.tenshu.photos.ui.components.PreviewLoader
import app.tenshu.photos.ui.components.WidthScaledGridCells
import app.tenshu.photos.ui.components.toAlbumSummary
import app.tenshu.photos.ui.faceGroupingRunsHere

/**
 * One Collections section in full — the page behind its row's *View all* (`photos-app.md` §35).
 *
 * People is a 3-column grid of faces, named first; Albums and Things are the card grid; Places and
 * Years are a list, because what distinguishes them is the name and the count, not the cover.
 *
 * ⚠ Reads the same [CollectionsUiState] the tab does — the route carries only [section].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionSectionScreen(
    section: CollectionSection,
    state: CollectionsUiState,
    loadPreview: PreviewLoader,
    onOpenAlbum: (albumId: String, title: String) -> Unit,
    onOpenSmartAlbum: (SmartAlbum) -> Unit,
    onCreateAlbum: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** *Try again* for a section whose read failed (§36) — the tab's own [CollectionsViewModel.retry]. */
    onRetry: () -> Unit = {},
    /** See [CollectionsScreen]'s parameter of the same name. */
    groupingRunsHere: Boolean = faceGroupingRunsHere,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(section.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (section == CollectionSection.ALBUMS) {
                        IconButton(onClick = onCreateAlbum) {
                            Icon(Icons.Default.Add, contentDescription = "New album")
                        }
                    }
                },
            )
        },
    ) { padding ->
        // ⚠ Every section page says a failed read (§36) — the same sentence its row on
        // the tab says, from the shared layout — beside whatever it kept, with *Try again*.
        val failure = CollectionsLayout.sectionFailure(section, state, groupingRunsHere)
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (failure != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = failure,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
            val content = Modifier.fillMaxWidth().weight(1f)
            when (section) {
                CollectionSection.PEOPLE -> PeopleGrid(state, loadPreview, onOpenSmartAlbum, onRetry, groupingRunsHere, content)
                CollectionSection.ALBUMS -> CardGrid(state.albums, { it.albumId }, content) { album ->
                    AlbumCard(album, loadPreview, onClick = { onOpenAlbum(album.albumId, album.name) })
                }
                CollectionSection.THINGS -> CardGrid(state.things, { it.id }, content) { smart ->
                    AlbumCard(smart.toAlbumSummary(), loadPreview, onClick = { onOpenSmartAlbum(smart) })
                }
                CollectionSection.PLACES -> SmartList(state.places, loadPreview, onOpenSmartAlbum, content)
                CollectionSection.YEARS -> SmartList(state.years, loadPreview, onOpenSmartAlbum, content)
            }
        }
    }
}

/**
 * ⚠ **Named people first**, each group in the section's own order: a name is the user's own work,
 * and the faces they have not named yet are the ones they scroll past to find someone.
 */
@Composable
private fun PeopleGrid(
    state: CollectionsUiState,
    loadPreview: PreviewLoader,
    onOpen: (SmartAlbum) -> Unit,
    onRetry: () -> Unit,
    groupingRunsHere: Boolean,
    modifier: Modifier,
) {
    val people = state.peopleNamedFirst
    // ⚠ Not while People's read is in flight: `peopleStatus` holds its default until it lands (§36).
    val line = when (state.peopleLoad) {
        SectionLoad.LOADING -> null
        SectionLoad.FAILED -> null
        SectionLoad.LOADED -> peopleStatusLine(state.peopleStatus, hasPeople = people.isNotEmpty(), groupingRunsHere = groupingRunsHere)
    }
    LazyVerticalGrid(
        columns = WidthScaledGridCells(3),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (line != null) {
            item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(PEOPLE_STATUS_TAG),
                )
            }
        }
        items(people, key = { it.id }) { person ->
            PersonCard(person = person, loadPreview = loadPreview, onClick = { onOpen(person) })
        }
    }
}

@Composable
private fun <T> CardGrid(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier,
    card: @Composable (T) -> Unit,
) {
    LazyVerticalGrid(
        columns = WidthScaledGridCells(2),
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = key) { card(it) }
    }
}

@Composable
private fun SmartList(
    albums: List<SmartAlbum>,
    loadPreview: PreviewLoader,
    onOpen: (SmartAlbum) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(albums, key = { it.id }) { smart ->
            CollectionListRow(smart.toAlbumSummary(), smart.subtitle, loadPreview, onClick = { onOpen(smart) })
        }
    }
}

/**
 * Thumbnail, name, and a second line — Google Photos' Places list.
 *
 * ⚠ The second line is the section's own subtitle (a place's city, else its count), so a place
 * reads the way its card does.
 */
@Composable
private fun CollectionListRow(
    album: AlbumSummary,
    subtitle: String,
    loadPreview: PreviewLoader,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            coverPath = album.coverPath,
            pixelWidth = album.coverPixelWidth,
            pixelHeight = album.coverPixelHeight,
            loadPreview = loadPreview,
            modifier = Modifier.size(72.dp),
        )
        Column(Modifier.padding(start = 16.dp)) {
            Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

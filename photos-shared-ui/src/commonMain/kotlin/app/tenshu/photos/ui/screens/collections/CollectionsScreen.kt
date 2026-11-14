package app.tenshu.photos.ui.screens.collections

import app.tenshu.ui.layout.LocalNavBarClearance
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.ui.components.AlbumCard
import app.tenshu.photos.ui.components.PersonCard
import app.tenshu.photos.ui.components.PreviewLoader
import app.tenshu.photos.ui.components.toAlbumSummary
import app.tenshu.photos.ui.faceGroupingRunsHere
import app.tenshu.photos.ui.screens.feed.FeedError

/**
 * The Collections tab (`photos-app.md` S4, renamed §21): everything the library groups into —
 * compact tiles for Favourites, Duplicates and Videos, then one ROW per section (People, Albums,
 * Places, Things, Years), each with a *View all* page (§35, the Google Photos shape).
 *
 * ⚠⚠ **Rows, not the sections in full** (§35): drawn in full, the tab grew with the library — once
 * People found everyone it ran to several screens of faces before the first album.
 *
 * ⚠⚠ **Named for the tab, not for one of its sections.** Google Photos and Apple Photos both call
 * this Collections, and an album is one thing inside it. The old name made every section that was
 * not an album read like an intruder.
 *
 * ⚠ Every section here renders **only when it has something in it** — Things most of all, which is
 * empty on every device until IN-3b lands an inference backend. A row that is greyed out until
 * some future release is advertising rather than a feature, the same argument that kept two dead
 * tabs out of PH-1. ⚠⚠ **People is the exception**: an empty People section is ambiguous in a way
 * an empty Things is not (switched off, not indexed, or nobody there), so it keeps its header and
 * says which in one line (`peopleStatusLine`).
 */
@Composable
fun CollectionsScreen(
    state: CollectionsUiState,
    loadPreview: PreviewLoader,
    onOpenAlbum: (albumId: String, title: String) -> Unit,
    onOpenSmartAlbum: (SmartAlbum) -> Unit,
    onOpenFavourites: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onCreateAlbum: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** *View all* on a section — its own page (`photos-app.md` §35). */
    onOpenSection: (CollectionSection) -> Unit = {},
    /** Whether People may explain itself when empty — see [faceGroupingRunsHere]. A seam for tests. */
    groupingRunsHere: Boolean = faceGroupingRunsHere,
    /** A row under *Utilities* (PIO-D3) — the Trash today. */
    onOpenUtility: (CollectionsUtility.Kind) -> Unit = {},
) {
    // ⚠ What the tab shows is the shared `CollectionsLayout`; the iOS tab draws the same one.
    when (val layout = CollectionsLayout.of(state, groupingRunsHere)) {
        CollectionsLayout.Loading ->
            Centered(modifier) { CircularProgressIndicator() }

        is CollectionsLayout.Failed ->
            Centered(modifier) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = layout.message,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Try again")
                    }
                }
            }

        is CollectionsLayout.Content -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            // The bottom also clears the floating nav pill, which the list scrolls under.
            contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp + LocalNavBarClearance.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            layout.refreshFailure?.let { failure ->
                item(key = "refresh-failed") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag(REFRESH_FAILED_TAG),
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
            }

            item(key = "tiles") {
                QuickTiles(
                    tiles = layout.tiles.map { tile ->
                        // ⚠ A failed tile retries on tap (§36) instead of opening what it kept.
                        if (tile.failed) return@map QuickTile(tile.title, tile.subtitle, Icons.Default.Favorite.takeIf { tile.kind == CollectionsTile.Kind.FAVOURITES } ?: Icons.Default.PlayCircle, onRetry)
                        when (tile.kind) {
                            CollectionsTile.Kind.FAVOURITES ->
                                QuickTile(tile.title, tile.subtitle, Icons.Default.Favorite, onOpenFavourites)
                            CollectionsTile.Kind.DUPLICATES ->
                                QuickTile(tile.title, tile.subtitle, Icons.Default.ContentCopy, onOpenDuplicates)
                            CollectionsTile.Kind.VIDEOS ->
                                QuickTile(tile.title, tile.subtitle, Icons.Default.PlayCircle) {
                                    tile.smartAlbum?.let(onOpenSmartAlbum)
                                }
                        }
                    },
                )
            }

            layout.people?.let { people ->
                item(key = "people") {
                    SectionRow(
                        title = CollectionSection.PEOPLE.title,
                        onViewAll = { onOpenSection(CollectionSection.PEOPLE) }.takeIf { people.viewAll },
                    ) {
                        if (people.pending) PlaceholderRow(circles = true)
                        people.failure?.let { failure -> SectionFailed(failure, onRetry) }
                        people.statusLine?.let { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp).testTag(PEOPLE_STATUS_TAG),
                            )
                        }
                        if (people.people.isNotEmpty()) {
                            CardRow(people.people, key = { it.id }) { person ->
                                PersonCard(
                                    person = person,
                                    loadPreview = loadPreview,
                                    onClick = { onOpenSmartAlbum(person) },
                                    modifier = Modifier.width(PERSON_ROW_EDGE),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "albums") {
                SectionRow(
                    title = CollectionSection.ALBUMS.title,
                    onViewAll = { onOpenSection(CollectionSection.ALBUMS) }.takeIf { layout.albumsViewAll },
                ) {
                    layout.albumsFailure?.let { failure -> SectionFailed(failure, onRetry) }
                    CardRow(
                        items = listOf<AlbumSummary?>(null) + layout.albums,
                        key = { it?.albumId ?: "new-album" },
                    ) { album ->
                        if (album == null) {
                            NewAlbumTile(onClick = onCreateAlbum, modifier = Modifier.width(CARD_ROW_EDGE))
                        } else {
                            AlbumCard(
                                album = album,
                                loadPreview = loadPreview,
                                onClick = { onOpenAlbum(album.albumId, album.name) },
                                modifier = Modifier.width(CARD_ROW_EDGE),
                            )
                        }
                    }
                }
            }

            layout.rows.forEach { row -> smartRow(row, loadPreview, onOpenSmartAlbum, onOpenSection, onRetry) }
            // §36: the library walk is what years (and everything derived) wait for — say so while it runs.
            if (layout.libraryPending) {
                item(key = "library-pending") {
                    SectionRow(title = CollectionSection.YEARS.title, onViewAll = null) { PlaceholderRow(circles = false) }
                }
            }

            if (layout.utilities.isNotEmpty()) {
                item(key = "utilities-header") {
                    Text(
                        text = CollectionsUtility.SECTION_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(layout.utilities, key = { "utility-${it.kind}" }) { utility ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenUtility(utility.kind) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(utility.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                utility.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            layout.emptyText?.let { empty ->
                item(key = "empty") {
                    Text(
                        text = empty,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * A derived section as a row of cards, or nothing at all when it is empty.
 *
 * ⚠ Only People may be present while empty — see the tab's KDoc.
 */
private fun LazyListScope.smartRow(
    row: CollectionsSmartRow,
    loadPreview: PreviewLoader,
    onOpen: (SmartAlbum) -> Unit,
    onOpenSection: (CollectionSection) -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "row-${row.section.title}") {
        SectionRow(title = row.section.title, onViewAll = { onOpenSection(row.section) }.takeIf { row.albums.isNotEmpty() }) {
            if (row.albums.isNotEmpty()) {
                CardRow(row.albums, key = { it.id }) { smart ->
                    AlbumCard(
                        album = smart.toAlbumSummary(),
                        loadPreview = loadPreview,
                        onClick = { onOpen(smart) },
                        modifier = Modifier.width(CARD_ROW_EDGE),
                    )
                }
            }
            row.failure?.let { failure -> SectionFailed(failure, onRetry) }
        }
    }
}

/**
 * A section's header and its content.
 *
 * ⚠ *View all* whenever the section has anything, not only when it overflows the row: a link that
 * appears and disappears makes the user count tiles to learn whether there are more (§35).
 */
@Composable
private fun SectionRow(
    title: String,
    onViewAll: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (onViewAll != null) {
                TextButton(onClick = onViewAll) { Text("View all") }
            }
        }
        content()
    }
}

@Composable
private fun <T> CardRow(items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = key) { card(it) }
    }
}

/** The *New album* card that leads the Albums row — the row's own way in, as Google's `+` tile. */
@Composable
private fun NewAlbumTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
        Text("New album", style = MaterialTheme.typography.titleSmall)
    }
}

private class QuickTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/** Two to a row, as Google's *Other collections*. */
@Composable
private fun QuickTiles(tiles: List<QuickTile>) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile ->
                    Row(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(onClick = tile.onClick)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(tile.icon, contentDescription = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(tile.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                tile.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A section still waiting for its read (§36): grey shapes where its cards will be. */
@Composable
private fun PlaceholderRow(circles: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(SECTION_PLACEHOLDER_TAG),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) {
            Box(
                Modifier.width(if (circles) PERSON_ROW_EDGE else CARD_ROW_EDGE)
                    .aspectRatio(1f)
                    .clip(if (circles) CircleShape else RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

/** One section's read failed (§36) — said in that section, with its own way to try again. */
@Composable
private fun SectionFailed(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

/** A section's placeholder row while its read is in flight. */
const val SECTION_PLACEHOLDER_TAG = "sectionPlaceholder"

/** How many items a row shows before *View all* — the shared layout's number. */
internal const val ROW_LIMIT = CollectionsLayout.ROW_LIMIT
private val PERSON_ROW_EDGE = 96.dp
private val CARD_ROW_EDGE = 140.dp

/** The one-line "couldn't refresh" notice over content already on screen. */
const val REFRESH_FAILED_TAG = "collectionsRefreshFailed"

/** The People section's one explanatory line. */
const val PEOPLE_STATUS_TAG = "peopleStatus"

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(32.dp), Alignment.Center) { content() }
}

package app.tenshu.photos.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Collections
import app.tenshu.ui.layout.AdaptiveNavItem

/**
 * The bottom bar / rail: **Photos · Collections** (`photos-creation-features.md` §8, CR-D8). ⚠ The
 * Create tab is deferred (§8): the bar stays at two items.
 *
 * ⚠⚠ Settings is NOT here. It is the ⚙ in the feed's top app bar (as on iOS), pushed rather than
 * selected, so Back returns to the feed.
 */
fun photosNavItems(current: Destination, onSelect: (Destination.TopLevel) -> Unit): List<AdaptiveNavItem> =
    listOf(
        AdaptiveNavItem(
            key = Destination.Feed,
            icon = Icons.Default.PhotoLibrary,
            label = "Photos",
            selected = current is Destination.Feed,
            onClick = { onSelect(Destination.Feed) },
        ),
        AdaptiveNavItem(
            key = Destination.Collections,
            icon = Icons.Outlined.Collections,
            label = "Collections",
            selected = current is Destination.Collections,
            onClick = { onSelect(Destination.Collections) },
        ),
    )

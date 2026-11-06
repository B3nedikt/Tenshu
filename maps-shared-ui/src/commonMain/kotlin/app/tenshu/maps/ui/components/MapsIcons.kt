package app.tenshu.maps.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Train
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.maps.model.PlaceCategory
import app.tenshu.maps.model.ReasonKind

/**
 * Maps glyphs (Ph4, docs/proposals/maps-app.md §6 / handoff §3). The design's base set is
 * **Lucide** (stroke 1.75 @ 24); v1 maps to `material-icons-extended` approximations as a
 * stand-in (mirrors `TimelineIcons`). A curated Lucide set replaces these later.
 *
 * TODO(icons): swap for the real Lucide glyph set — maps-agent.md §3.
 */
object MapsIcons {

    /** Category avatar glyph (handoff `CATEGORY[cat].icon`). */
    fun forCategory(category: PlaceCategory): ImageVector = when (category) {
        PlaceCategory.Home -> Icons.Outlined.Home
        PlaceCategory.Work -> Icons.Outlined.BusinessCenter // briefcase
        PlaceCategory.Cafe -> Icons.Outlined.LocalCafe // coffee
        PlaceCategory.Restaurant -> Icons.Outlined.Restaurant // utensils
        PlaceCategory.Park -> Icons.Outlined.Park // tree
        PlaceCategory.Store -> Icons.Outlined.ShoppingBag // bag
        PlaceCategory.Culture -> Icons.Outlined.AccountBalance // landmark
        PlaceCategory.Transit -> Icons.Outlined.Train
        PlaceCategory.Viewpoint -> Icons.Outlined.Landscape // mountain
    }

    /** Recommendation provenance glyph (handoff `REASON_META[kind].icon`). */
    fun forReason(kind: ReasonKind): ImageVector = when (kind) {
        ReasonKind.Timeline -> Icons.Outlined.History
        ReasonKind.Contact -> Icons.Outlined.People
        ReasonKind.Curated -> Icons.Outlined.AutoAwesome // sparkles
    }

    val pin: ImageVector = Icons.Outlined.Place
    val bookmark: ImageVector = Icons.Outlined.BookmarkBorder
    val bookmarkFilled: ImageVector = Icons.Filled.Bookmark
    val chevronRight: ImageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight
    val lock: ImageVector = Icons.Outlined.Lock
    val layers: ImageVector = Icons.Outlined.Layers

    /** "Keep this area offline" — the contextual region download (maps-region-delivery.md track 3d). */
    val download: ImageVector = Icons.Outlined.CloudDownload
    val search: ImageVector = Icons.Outlined.Search
    val settings: ImageVector = Icons.Outlined.Settings
    val plus: ImageVector = Icons.Outlined.Add
    val edit: ImageVector = Icons.Outlined.Edit
    val directions: ImageVector = Icons.Outlined.Navigation
    val share: ImageVector = Icons.Outlined.Share
    val info: ImageVector = Icons.Outlined.Info
    val flag: ImageVector = Icons.Outlined.Flag
    val route: ImageVector = Icons.Outlined.Route
    val arrowUpRight: ImageVector = Icons.AutoMirrored.Outlined.ArrowForward
}

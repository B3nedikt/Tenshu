package app.tenshu.photos.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.data.tilePreviewRequestPx
import app.tenshu.photos.ui.screens.collections.photoCount
import app.tenshu.ui.image.decodePreviewBitmap

/**
 * One card in the Collections grid: cover, name, count. ⚠ Used for albums AND for derived collections — a place, a year, a label — because the card is the same card.
 *
 * ⚠ Loads its own cover bytes keyed on the path, the [PhotoTile] argument — an album list is short,
 * but the reason holds: nothing outside the composition retains the bitmap.
 */
@Composable
fun AlbumCard(
    album: AlbumSummary,
    loadPreview: PreviewLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** `false` for a person drawn as a whole photograph: People shows no counts (`photos-app.md` §35). */
    showCount: Boolean = true,
) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        CoverImage(
            coverPath = album.coverPath,
            pixelWidth = album.coverPixelWidth,
            pixelHeight = album.coverPixelHeight,
            loadPreview = loadPreview,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).testTag(ALBUM_COVER_TAG),
        )
        Text(
            text = album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showCount) {
            Text(
                // ⚠ Says "photos", not "items": an album holds photographs, and the count is the one
                // number that tells the user whether the album is what they think it is.
                text = photoCount(album.photoCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * A collection's cover, centre-cropped into whatever box [modifier] gives it — the card's square
 * and the list row's thumbnail alike (`photos-app.md` §35).
 *
 * ⚠ Asked for at the size it is drawn, after layout, and read once for the producer —
 * [PhotoTile]'s rules, and its reasons.
 */
@Composable
fun CoverImage(
    coverPath: String?,
    pixelWidth: Int?,
    pixelHeight: Int?,
    loadPreview: PreviewLoader,
    modifier: Modifier = Modifier,
) {
    var coverEdgePx by remember { mutableIntStateOf(0) }
    val requestPx = if (coverEdgePx == 0) 0 else tilePreviewRequestPx(
        tileEdgePx = coverEdgePx,
        pixelWidth = pixelWidth,
        pixelHeight = pixelHeight,
    )
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, coverPath, requestPx) {
        if (requestPx == 0) return@produceState
        value = coverPath?.let { loadPreview(it, requestPx) }?.decodePreviewBitmap()
    }
    Box(
        modifier
            .onSizeChanged { coverEdgePx = maxOf(it.width, it.height) }
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The whole-photograph cover of an [AlbumCard]. */
const val ALBUM_COVER_TAG = "albumCover"

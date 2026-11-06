package app.tenshu.photos.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.ui.screens.collections.SmartAlbum

/**
 * One person in the Collections People row and grid: their face and their name.
 *
 * ⚠ **No count** (`photos-app.md` §35): a face is found by who it is, not by how many photos it has —
 * Google Photos' People grid shows none either — and the person page's header carries it.
 *
 * ⚠ **A circle, filling the card's width**, not a rounded square. Every other card on the tab is a
 * rounded square showing a whole photograph, and a person drawn in the same frame reads as one more
 * album of group shots. The circle is the one shape that says "this is someone" before the name is
 * read, the way Google Photos' People row does, and it is the same shape the person page's header
 * opens with, so the card and the page it opens look like the same person. A face crop is square by
 * construction ([faceSourceRect]), so the circle loses only the corners, which hold hair and
 * background, never the face.
 *
 * ⚠ Falls back to [AlbumCard], the whole photograph centre-cropped, when there is no [SmartAlbum.coverFace]:
 * a name synced from another device with no cluster here has no face this device can place, and a
 * circle cut from the middle of an arbitrary photograph would claim to be a face that it is not.
 *
 * ⚠ An unnamed person keeps an empty title line, as every card does. The face is what identifies
 * them in the grid, "Add a name" lives on the page the card opens, and the blank line keeps the
 * grid's rows aligned.
 */
@Composable
fun PersonCard(
    person: SmartAlbum,
    loadPreview: PreviewLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val face = person.coverFace
    if (face == null) {
        AlbumCard(
            album = person.toAlbumSummary(),
            loadPreview = loadPreview,
            onClick = onClick,
            modifier = modifier,
            // ⚠ No count here either: a named person synced to a device with no face of
            // them — desktop, always — must not be the one person in People that shows a number.
            showCount = false,
        )
        return
    }

    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            FaceAvatar(
                path = face.path,
                region = face.region,
                loadPreview = loadPreview,
                size = maxWidth,
            )
        }
        Text(
            text = person.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            minLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A derived section as the [AlbumCard] every non-person card is drawn with. */
internal fun SmartAlbum.toAlbumSummary(): AlbumSummary = AlbumSummary(
    albumId = id,
    name = title,
    createdAtMs = 0L,
    // ⚠ The card's count and cover, not `photos`: a snapshot-drawn card (§36 step 2) has none yet.
    photoCount = photoCount,
    coverPath = coverPath,
    coverPixelWidth = coverPixelWidth,
    coverPixelHeight = coverPixelHeight,
)

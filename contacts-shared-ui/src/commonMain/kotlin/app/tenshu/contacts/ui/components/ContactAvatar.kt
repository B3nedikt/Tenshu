package app.tenshu.contacts.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

/**
 * A contact's picture: their [photo] when they have one (Ph8), otherwise the monogram — the
 * initials of the petname on a tint derived from the stable `personId`, so the same person
 * keeps the same colour across devices and sessions.
 *
 * The monogram is not a missing-image placeholder. Most contacts will never have a photo —
 * a handshake carries none, and a hand-typed contact has nothing to carry one — so it is
 * built to look deliberate.
 *
 * ⚠ Bytes that will not decode fall back to the monogram rather than to a broken-image
 * glyph. A card can embed a format this platform has no codec for (Skiko has none for HEIC),
 * and a contact whose picture the app cannot read is still a contact.
 */
@Composable
fun ContactAvatar(
    displayName: String,
    personId: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    photo: ByteArray? = null,
) {
    val palette = avatarPalette()
    val tint = palette[(personId.hashCode().absoluteValue) % palette.size]

    // Keyed on the bytes so a changed photo re-decodes and an unchanged one does not: this
    // runs per row in a scrolling list.
    val bitmap = remember(photo) { photo?.let { decodeAvatar(it) } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            // Named by the person, not "avatar": a screen reader already reads the row's
            // headline, so repeating the name would stutter — but the image alone in the
            // detail header needs to say whose it is.
            contentDescription = null,
            modifier = modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        return
    }

    Box(
        modifier = modifier.size(size).background(tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(displayName),
            // Scaled from the circle so one composable serves the 40dp row and the 96dp
            // detail header without a second set of tokens.
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (size.value * 0.38f).sp,
                lineHeight = (size.value * 0.44f).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Up to two initials: the first letter of the first and last word. Falls back to `?` for a
 * name that is empty or has no letters at all — an imported record can carry either.
 */
internal fun initialsOf(displayName: String): String {
    val words = displayName.trim().split(' ', '\t', '\n').filter { it.isNotBlank() }
    val letters = words.mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    return when {
        letters.isEmpty() -> "?"
        letters.size == 1 -> letters.first().uppercase()
        else -> "${letters.first()}${letters.last()}".uppercase()
    }
}

/**
 * Muted surface tints rather than saturated colour: the monogram is identifying furniture,
 * not an accent, and it sits behind text that must stay legible in both themes.
 */
@Composable
private fun avatarPalette(): List<Color> = with(MaterialTheme.colorScheme) {
    listOf(
        primaryContainer,
        secondaryContainer,
        tertiaryContainer,
        surfaceContainerHighest,
    )
}

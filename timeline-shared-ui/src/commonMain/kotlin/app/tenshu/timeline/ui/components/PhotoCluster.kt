package app.tenshu.timeline.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.model.Photo

/**
 * The signature photo interaction (Ph4, docs/proposals/timeline-app.md §6). One photo → a
 * single 3:2 frame; two or more → a horizontal snap carousel whose first frame is ~78% wide
 * so the **next peeks off the right edge** (inviting a swipe), with page dots below whose
 * active dot elongates. Reused everywhere photos appear.
 *
 * @param accent the (personalised) accent colour for the active dot.
 * @param onPhotoTap what a tap on a frame does — see [tapAction]. `null` (the default) means the
 *   cross-app open, which is the right answer only where the photo is the subject.
 */
@Composable
fun PhotoCluster(
    photos: List<Photo>,
    accent: Color,
    modifier: Modifier = Modifier,
    onPhotoTap: ((Photo) -> Unit)? = null,
) {
    when {
        photos.isEmpty() -> Unit
        photos.size == 1 -> PhotoFrame(
            photo = photos.first(),
            modifier = modifier.fillMaxWidth().aspectRatio(3f / 2f)
                .tapAction(photos.first(), onPhotoTap),
        )
        else -> MultiPhotoCarousel(photos, accent, modifier, onPhotoTap)
    }
}

/**
 * What a tap on a frame does — and there are two right answers, because a frame is two different
 * things depending on where it is drawn.
 *
 * ⭐⭐ **Where the photo IS the subject** — moment detail, a place's visit rows — a tap opens it in
 * the app that owns the file (`control-cross-app-open.md` TT-2a). That is the whole of §10.5's
 * deep link and it stays exactly as it was.
 *
 * ⚠⚠ **Inside a feed row the photo is part of the row, not a destination of its own.** [onPhotoTap]
 * carries the row's own click there, so the picture goes where the title goes. Leaving the frame on
 * the cross-app path made one card open two different *apps* depending on which pixel was pressed —
 * a title tap landed on the moment, a photo tap left Timeline entirely — and no affordance
 * distinguished them.
 *
 * ⚠ A no-op when neither is available, so the modifier chain is identical on a surface that has
 * no proxy behind it — and the frame stays visibly inert rather than offering a tap that does
 * nothing (see [LocalPhotoOpener]).
 */
@Composable
private fun Modifier.tapAction(photo: Photo, onPhotoTap: ((Photo) -> Unit)?): Modifier {
    if (onPhotoTap != null) return clickable { onPhotoTap(photo) }
    val opener = LocalPhotoOpener.current ?: return this
    // ⚠ A photo with no path cannot be addressed — `uri` IS the file's path, and a `DataId` needs
    // it. A placeholder frame is drawn from a colour alone and has nothing to open.
    if (photo.uri.isNullOrEmpty()) return this
    return clickable { opener.open(photo) }
}

private const val FIRST_FRAME_FRACTION = 0.78f

/**
 * A fractional page size (~78% of the viewport) so the next frame peeks off the right edge.
 * CMP 1.7.3's `PageSize` ships only `Fill`/`Fixed`, so we supply the fraction ourselves.
 */
private val PeekPageSize = object : PageSize {
    override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int =
        (availableSpace * FIRST_FRAME_FRACTION).toInt()
}

@Composable
private fun MultiPhotoCarousel(
    photos: List<Photo>,
    accent: Color,
    modifier: Modifier,
    onPhotoTap: ((Photo) -> Unit)?,
) {
    val state = rememberPagerState(pageCount = { photos.size })
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints {
            val frameHeight = (maxWidth * FIRST_FRAME_FRACTION) / (3f / 2f)
            HorizontalPager(
                state = state,
                pageSize = PeekPageSize,
                pageSpacing = 8.dp,
                modifier = Modifier.fillMaxWidth().height(frameHeight),
            ) { page ->
                PhotoFrame(photos[page], Modifier.fillMaxSize().tapAction(photos[page], onPhotoTap))
            }
        }
        PageDots(
            count = photos.size,
            active = state.currentPage,
            accent = accent,
            modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun PageDots(
    count: Int,
    active: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val isActive = i == active
            val width by animateDpAsState(if (isActive) 16.dp else 6.dp, label = "dotWidth")
            Box(
                Modifier
                    .size(width = width, height = 6.dp)
                    .clip(CircleShape)
                    .background(if (isActive) accent else accent.copy(alpha = 0.3f)),
            )
        }
    }
}

package app.tenshu.photos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.components.PhotoTile
import app.tenshu.ui.theme.TenshuTheme
import org.tenshu.core.file.GetStoredThumbnail
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a tile asks for is decided by the pixels it is drawn at (`drive-synced-thumbnails.md` §16).
 *
 * ⚠⚠ The pair is the point. The same 137 dp tile — three across a 412 dp phone — asks for the
 * `LARGE` rung at 1x and the hero rung at 3x. The grid used to ask for a flat 256 px whatever the
 * density, which is the case the first test would have caught and the second could not.
 *
 * ⚠ Each asserts the whole list, so it also pins **one** load. `TenshuTheme` measures inside a
 * `BoxWithConstraints`, where layout reports the size before the tile's first producer starts; a
 * producer reading the size state rather than its own key loaded every photograph twice.
 */
@OptIn(ExperimentalTestApi::class)
class PhotoTileRequestSizeTest {

    private val photo = Photo(path = "/p.jpg", capturedAtMs = 1_000L, isVideo = false)

    private fun requestsAt(density: Float, photo: Photo = this.photo, tileDp: Int = PHONE_TILE_DP): List<Int> {
        val requests = mutableListOf<Int>()
        runDesktopComposeUiTest {
            setContent {
                TenshuTheme {
                    CompositionLocalProvider(LocalDensity provides Density(density)) {
                        Box(Modifier.size(tileDp.dp)) {
                            PhotoTile(
                                photo = photo,
                                loadPreview = { _, requestPx -> requests += requestPx; null },
                                onClick = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
        }
        return requests
    }

    @Test
    fun `a phone tile at 3x asks for the hero rung`() {
        assertEquals(listOf(GetStoredThumbnail.XLARGE_PX), requestsAt(density = 3f))
    }

    @Test
    fun `the same tile at 1x asks for the large rung`() {
        assertEquals(listOf(GetStoredThumbnail.LARGE_PX), requestsAt(density = 1f))
    }

    /** The tile passes the photo's shape on: a 16:9 `LARGE` is 256×144, which a 160 px square outgrows. */
    @Test
    fun `a wide photo outgrows the large rung sooner than an unknown one`() {
        val wide = photo.copy(pixelWidth = 1920, pixelHeight = 1080)
        assertEquals(listOf(GetStoredThumbnail.XLARGE_PX), requestsAt(density = 1f, photo = wide, tileDp = 160))
        assertEquals(listOf(GetStoredThumbnail.LARGE_PX), requestsAt(density = 1f, tileDp = 160))
    }

    /**
     * ⚠⚠ A photograph's row syncs before its thumbnail: the tile is composed with no bytes anywhere,
     * and one ask left it a placeholder until a scroll recycled it. It asks again while on screen —
     * and stops once it has an answer.
     */
    @Test
    fun `a tile whose bytes had not arrived asks again until they have`() {
        var asks = 0
        runDesktopComposeUiTest {
            setContent {
                TenshuTheme {
                    Box(Modifier.size(PHONE_TILE_DP.dp)) {
                        PhotoTile(
                            photo = photo,
                            // Not an image: bytes that do not decode are still an answer.
                            loadPreview = { _, _ -> if (++asks < 3) null else ByteArray(4) },
                            onClick = {},
                        )
                    }
                }
            }
            waitForIdle()
            assertEquals(1, asks, "one ask on composition")
            mainClock.advanceTimeBy(1_100)
            assertEquals(2, asks, "again after the first wait")
            mainClock.advanceTimeBy(2_100)
            assertEquals(3, asks, "and again, which is answered")
            mainClock.advanceTimeBy(600_000)
            assertEquals(3, asks, "an answer ends the asking")
        }
    }

    private companion object {
        const val PHONE_TILE_DP = 137
    }
}

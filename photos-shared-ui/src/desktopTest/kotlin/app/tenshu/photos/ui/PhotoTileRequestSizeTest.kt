package app.tenshu.photos.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import app.tenshu.photos.ui.components.LocalPreviewWakes
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
import app.tenshu.photos.ui.components.PREVIEW_RETRY_MAX_MS
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
            // Any further ask would come within one ceiling of the last. ⚠ Not ten minutes: Compose's
            // test clock steps every 16 ms frame, so 600 000 ms was ~37 500 frames and 15 s of this
            // test's 15.5 s for no extra coverage.
            mainClock.advanceTimeBy(PREVIEW_RETRY_MAX_MS + 1_000)
            assertEquals(3, asks, "an answer ends the asking")
        }
    }

    /**
     * ⚠⚠ SS-3 (`suite-sync-status.md`): a tile deep in its backoff asks again **at once** when the
     * shell says sync came back, instead of up to a minute later — the grey grid of 2026-11-12 —
     * and its backoff starts over.
     */
    @Test
    fun `a waiting tile asks again at once when woken`() {
        var asks = 0
        val wakes = MutableStateFlow(0)
        runDesktopComposeUiTest {
            setContent {
                TenshuTheme {
                    CompositionLocalProvider(LocalPreviewWakes provides wakes) {
                        Box(Modifier.size(PHONE_TILE_DP.dp)) {
                            PhotoTile(
                                photo = photo,
                                loadPreview = { _, _ -> asks++; null },
                                onClick = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            // 1 s, 2 s, 4 s, 8 s, 16 s: six asks, now waiting 32 s for the next.
            mainClock.advanceTimeBy(31_500)
            assertEquals(6, asks, "precondition: deep in the backoff")

            wakes.value++
            mainClock.advanceTimeBy(100)
            assertEquals(7, asks, "woken: asks at once, not 32 s later")
            mainClock.advanceTimeBy(1_100)
            assertEquals(8, asks, "and the backoff started over from its first step")
        }
    }

    /**
     * ⚠⚠ The review's case: sync comes back WHILE the tile's own ask is in flight (a slow
     * `GetPreview` during the outage). That wake must still count once the ask returns empty.
     */
    @Test
    fun `a wake during the tile's own ask is not lost`() {
        var asks = 0
        val wakes = MutableStateFlow(0)
        runDesktopComposeUiTest {
            setContent {
                TenshuTheme {
                    CompositionLocalProvider(LocalPreviewWakes provides wakes) {
                        Box(Modifier.size(PHONE_TILE_DP.dp)) {
                            PhotoTile(
                                photo = photo,
                                loadPreview = { _, _ ->
                                    asks++
                                    // The 6th ask is slow, and sync recovers while it runs.
                                    if (asks == 6) {
                                        wakes.value++
                                        delay(5_000)
                                    }
                                    null
                                },
                                onClick = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            mainClock.advanceTimeBy(31_500)
            assertEquals(6, asks, "precondition: the slow ask is running")
            mainClock.advanceTimeBy(5_100)
            assertEquals(7, asks, "asked again as soon as the slow ask returned, not 32 s later")
        }
    }

    private companion object {
        const val PHONE_TILE_DP = 137
    }
}

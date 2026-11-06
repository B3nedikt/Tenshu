package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.mintAlbumId
import app.tenshu.photos.data.trashPathFor
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * **Every identity this app mints must actually differ between two things.**
 *
 * ⚠⚠ This file exists because it did not. Four string templates across the Photos module shipped
 * with their `$` escaped — `"album-${'$'}{clockMs()}-…"` and friends — so each one produced
 * one byte-identical literal for every input. The escaping is what a shell heredoc does to a
 * Kotlin template, and Kotlin compiles the result without a murmur.
 *
 * The consequences were not cosmetic:
 *
 * - **every** album the user created got the same id, so the second folded onto the first;
 * - **every** trashed photo moved to the same `/Trash` path — a collision on the second trash,
 *   which is the exact thing the content-id prefix exists to prevent;
 * - **every** place and **every** year got the same `SmartAlbum.id`, and the Collections grid keys
 *   its items on that id — so a user with two years of photographs opened the tab into
 *   `IllegalArgumentException: Key … was already used`.
 *
 * ⚠⚠ The existing tests all passed, because they asserted **titles and counts** and never once an
 * id. A test that reads only the fields a human reads cannot see an identity bug — so these assert
 * the fields the *framework* reads.
 */
class CollectionIdentityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(id: String, year: Int, lat: Long? = null) = Photo(
        path = "/$id.jpg",
        capturedAtMs = (year - 1970) * 31_556_952_000L,
        isVideo = false,
        contentId = id,
        latE7 = lat,
        lngE7 = if (lat == null) null else 116_760_000,
    )

    @Test
    fun twoAlbumsMintedInDifferentMillisecondsGetDifferentIds() {
        assertNotEquals(mintAlbumId(1_000L, "Holiday"), mintAlbumId(1_001L, "Holiday"))
    }

    @Test
    fun twoAlbumsNamedDifferentlyGetDifferentIds() {
        assertNotEquals(mintAlbumId(1_000L, "Holiday"), mintAlbumId(1_000L, "Work"))
    }

    @Test
    fun anAlbumIdInterpolatesRatherThanCarryingTheTemplate() {
        // ⚠ The literal check, not just an inequality: two *different* broken templates would
        // still be unequal, and the thing that went wrong was the template surviving verbatim.
        val id = mintAlbumId(1_700_000_000_000L, "Holiday")
        assertTrue(id.startsWith("album-1700000000000-"), "was: $id")
        assertTrue('{' !in id && '$' !in id, "an un-interpolated template leaked into the id: $id")
    }

    @Test
    fun twoPhotosWithTheSameNameTrashToDifferentPaths() {
        // ⚠⚠ The whole point of the content-id prefix. Same filename, two folders, two copies —
        // and the second move must not target the first one's destination.
        assertNotEquals(trashPathFor("c1", "IMG_0001.jpg"), trashPathFor("c2", "IMG_0001.jpg"))
    }

    @Test
    fun aTrashPathInterpolatesRatherThanCarryingTheTemplate() {
        val path = trashPathFor("c1", "IMG_0001.jpg")
        assertEquals("/Trash/c1-IMG_0001.jpg", path)
    }

    @Test
    fun everyDerivedCollectionHasItsOwnId() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The grid keys on `SmartAlbum.id`, so a duplicate is not a wrong label — it is a
        // crash. Two places AND two years, because the broken version collided within each kind.
        val vm = vms.own(CollectionsViewModel(
            MockPhotosRepository(
                photos = listOf(
                    photo("a", 2026, lat = 481_370_100),
                    photo("b", 2024, lat = 481_380_100),
                    photo("c", 2024),
                ),
                places = listOf(
                    PhotoPlace("p1", "Café", "Munich", 481_370_000, 116_760_000),
                    PhotoPlace("p2", "Park", "Munich", 481_380_000, 116_760_000),
                ),
            ),
            yearOf = { ms -> (ms / 31_556_952_000L + 1970).toInt() },
        ))
        testScheduler.advanceUntilIdle()

        val ids = vm.state.value.smartAlbums.map { it.id }
        assertTrue(ids.size >= 4, "need at least two places and two years to catch a collision: $ids")
        assertEquals(ids.size, ids.toSet().size, "duplicate collection ids crash the grid: $ids")
        assertTrue(ids.none { '{' in it }, "an un-interpolated template leaked into an id: $ids")
    }

    @Test
    fun aDerivedCollectionsSubtitleCountsRatherThanEchoingItsTemplate() = vmTest(dispatcher) { vms ->
        val vm = vms.own(CollectionsViewModel(
            MockPhotosRepository(photos = listOf(photo("a", 2026), photo("b", 2026))),
            yearOf = { 2026 },
        ))
        testScheduler.advanceUntilIdle()

        assertEquals("2 photos", vm.state.value.years.single().subtitle)
    }

    @Test
    fun twoAlbumsFromTheMockAlsoDifferInId() = vmTest(dispatcher) { vms ->
        // ⚠ The mock carried the same defect, and every other test in this module reads through
        // it — so a fake that mints one id for every album hides the failure it is standing in for.
        val repo = MockPhotosRepository(photos = emptyList())
        val first = repo.createAlbum("Holiday")
        val second = repo.createAlbum("Work")
        assertNotEquals(first, second)
    }

    @Test
    fun theMockTrashesTwoPhotosToDifferentPaths() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository(photos = listOf(photo("c1", 2026), photo("c2", 2026)))
        repo.trash(photo("c1", 2026))
        repo.trash(photo("c2", 2026))
        val paths = (repo.trashed() as app.tenshu.photos.data.TrashResult.Items).items.map { it.photo.path }
        assertEquals(paths.size, paths.toSet().size, "two trashed photos share a path: $paths")
    }
}

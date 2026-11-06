package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
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
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest

/**
 * The derived sections of the Collections tab (`photos-app.md` PH-3).
 *
 * ⚠⚠ The last test is the one that matters most: a user with no Timeline has no places, and the
 * tab must simply not show the section rather than showing an empty or fabricated one.
 */
class SmartAlbumsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val munich = PhotoPlace("p1", "Café", "Munich", 481_370_000, 116_760_000)

    /** ~7 km north of the café — far outside any grouping tolerance, so the two never cross-match. */
    private val park = PhotoPlace("p2", "Park", "Munich", 482_000_000, 116_760_000)

    private fun photo(id: String, year: Int, atPlace: Boolean = false, video: Boolean = false) = Photo(
        path = "/$id.jpg",
        // A crude but monotonic year → epoch: the view model's `yearOf` is injected, so the test
        // supplies both halves and the arithmetic stays honest.
        capturedAtMs = (year - 1970) * 31_556_952_000L,
        isVideo = video,
        contentId = id,
        latE7 = if (atPlace) 481_370_100 else null,
        lngE7 = if (atPlace) 116_760_000 else null,
    )

    /** As [photo], but at an explicit coordinate — for fixtures that need *two* distinct places. */
    private fun photoAtPark(id: String, year: Int) = photo(id, year).copy(
        latE7 = 482_000_100,
        lngE7 = 116_760_000,
    )

    private fun vm(vms: OwnedViewModels, photos: List<Photo>, places: List<PhotoPlace>) = vms.own(CollectionsViewModel(
        MockPhotosRepository(photos = photos, places = places),
        yearOf = { ms -> (ms / 31_556_952_000L + 1970).toInt() },
    ))

    @Test
    fun photosAtANamedPlaceBecomeASection() = vmTest(dispatcher) { vms ->
        val vm = vm(vms, 
            listOf(photo("a", 2026, atPlace = true), photo("b", 2026, atPlace = true), photo("c", 2026)),
            listOf(munich),
        )
        testScheduler.advanceUntilIdle()

        val place = vm.state.value.places.single()
        assertEquals("Café", place.title)
        assertEquals("Munich", place.subtitle)
        assertEquals(2, place.photos.size, "only the two with a coordinate near the café")
    }

    @Test
    fun eachYearWithPhotosBecomesASection_newestFirst() = vmTest(dispatcher) { vms ->
        val vm = vm(vms, listOf(photo("a", 2026), photo("b", 2024), photo("c", 2024)), emptyList())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("2026", "2024"), vm.state.value.years.map { it.title })
        assertEquals(listOf(1, 2), vm.state.value.years.map { it.photos.size })
    }

    @Test
    fun videosAreASectionOnlyWhenThereAreSome() = vmTest(dispatcher) { vms ->
        val none = vm(vms, listOf(photo("a", 2026)), emptyList())
        testScheduler.advanceUntilIdle()
        assertEquals(null, none.state.value.videos, "an empty Videos row is a promise, not a feature")

        val some = vm(vms, listOf(photo("a", 2026), photo("v", 2026, video = true)), emptyList())
        testScheduler.advanceUntilIdle()
        assertEquals(1, some.state.value.videos?.photos?.size)
    }

    @Test
    fun aSmartAlbumCarriesItsPhotos_soOpeningOneCostsNothing() = vmTest(dispatcher) { vms ->
        // ⚠ The whole point of PH-3's one-pass design: the sections are computed from a feed read
        // that already happened, so opening one must not walk the realm again.
        val vm = vm(vms, listOf(photo("a", 2026, atPlace = true)), listOf(munich))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.smartAlbums.all { it.photos.isNotEmpty() })
    }

    @Test
    fun aUserWithNoPlacesGetsNoPlacesSection() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Places come from `:place-service`, which TIMELINE populates. Photos records none of
        // its own, so this is the normal case for a Photos-only user — and the tab must be honest
        // about it rather than inventing "Unknown place".
        val vm = vm(vms, listOf(photo("a", 2026, atPlace = true)), emptyList())
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.places.isEmpty())
        assertTrue(vm.state.value.years.isNotEmpty(), "the sections that do not need places still work")
    }

    @Test
    fun theSectionsSurviveAnEmptyLibrary() = vmTest(dispatcher) { vms ->
        val vm = vm(vms, emptyList(), listOf(munich))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.smartAlbums.isEmpty())
        assertTrue(vm.state.value.isEmpty, "nothing anywhere, read successfully, is empty")
    }

    @Test
    fun everySmartAlbumHasItsOwnId_becauseTheGridKeysOnIt() = vmTest(dispatcher) { vms ->
        // ⚠⚠ THE CRASH. `smartAlbums` is what the Albums grid renders, and Compose demands a unique
        // key per item: a duplicate is a hard `IllegalArgumentException` that takes the app down.
        //
        // This shipped broken in PH-3 and stayed broken for a week. The id read
        // `"year:${'$'}year"` — the escaped-dollar idiom — so it was a LITERAL, identical for every
        // year, and a library spanning two years crashed the tab on open. Found on a phone, in a
        // crash buffer, not by any of the tests below.
        //
        // ⚠⚠ `eachYearWithPhotosBecomesASection_newestFirst` ALREADY had a two-year fixture and
        // passed throughout: it asserted `title` and `photos.size` and never once read an `id`. The
        // fixture was right and the assertion was looking elsewhere — which is why this test reads
        // the id and nothing else.
        val vm = vm(vms, 
            listOf(
                photo("a", 2026, atPlace = true),
                photo("b", 2024, atPlace = true),
                photoAtPark("c", 2026),
                photo("v", 2024, video = true),
            ),
            listOf(munich, park),
        )
        testScheduler.advanceUntilIdle()

        val ids = vm.state.value.smartAlbums.map { it.id }
        assertTrue(ids.size >= 4, "the fixture must actually produce rivals to collide: $ids")
        assertEquals(ids.size, ids.toSet().size, "duplicate smart-album id — the grid will crash: $ids")
    }

    @Test
    fun oneVideoReadsOneVideo() = vmTest(dispatcher) { vms ->
        val vm = vm(vms, listOf(photo("a", 2026, video = true), photo("b", 2026)), emptyList())
        testScheduler.advanceUntilIdle()

        assertEquals("1 video", vm.state.value.videos?.subtitle)
    }

    @Test
    fun aSmartAlbumSubtitleCountsTheRealPhotos() = vmTest(dispatcher) { vms ->
        // ⚠ The same defect's visible half: the subtitles were literals too, so the tab read
        // "${'$'}{photos.size} photos" back to the user. An id assertion alone would not catch that,
        // because the two are separate strings that happened to share one bug.
        val vm = vm(vms, listOf(photo("a", 2026), photo("b", 2026), photo("c", 2024)), emptyList())
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("2 photos", "1 photo"),
            vm.state.value.years.map { it.subtitle },
            "the subtitle must be interpolated, not printed",
        )
    }
}

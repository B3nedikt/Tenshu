package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.NotConnectedPhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.feed.FeedDensity
import app.tenshu.photos.ui.screens.feed.columnsAt
import app.tenshu.photos.ui.screens.feed.FeedLine
import app.tenshu.photos.ui.screens.feed.FeedStatus
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.ui.screens.feed.PhotosFeedBinding
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import androidx.paging.LoadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PhotosFeedBinding] — what the SwiftUI shell draws the feed from. It holds no arithmetic
 * of its own; these pin that it hands the shell the SAME [app.tenshu.photos.ui.screens.feed.FeedLayout]
 * Compose builds, and that it pages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotosFeedBindingTest {

    private val day = 86_400_000L

    private fun photo(id: String, at: Long) =
        Photo(path = "/Photos/$id.jpg", capturedAtMs = at, isVideo = false, contentId = id)

    // ⚠⚠ Main is set for the whole test and reset only in @AfterTest — AFTER `vmTest` has closed
    // the owned view models. Resetting it inside `bind` would leave each `viewModelScope`
    // pointing at a Main that is gone, so its cancel-and-join never finishes and every test here
    // times out. `runTest` shares this dispatcher's scheduler, so
    // `advanceUntilIdle()` drives the view model, the presenter and the binding together.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.bind(
        vms: OwnedViewModels,
        repository: app.tenshu.photos.data.PhotosRepository,
        sectionStartOf: (FeedDensity, Long) -> Long = { _, ms -> ms / day * day },
        block: (PhotosFeedBinding) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = vms.ownScope(CoroutineScope(dispatcher))
        val vm = vms.own(PhotosFeedViewModel(repository, sectionStartOf = sectionStartOf, pageSize = 20))
        block(PhotosFeedBinding(vm, scope, dispatcher))
    }

    @Test
    fun the_layout_is_headers_and_rows_in_feed_order() = vmTest { vms ->
        val photos = listOf(
            photo("a", 3 * day + 5_000),
            photo("b", 3 * day + 1_000),
            photo("c", 1 * day + 9_000),
        )
        bind(vms, MockPhotosRepository(photos = photos)) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()

            val layout = binding.layout.value
            val lines = List(layout.lineCount) { layout[it] }
            assertEquals(4, lines.size, "day-3 header, its row, day-1 header, its row: $lines")
            assertIs<FeedLine.Header>(lines[0])
            assertIs<FeedLine.Row>(lines[1])
            assertIs<FeedLine.Header>(lines[2])
            assertIs<FeedLine.Row>(lines[3])

            assertEquals(3 * day, binding.sectionStartAt(lines[0].start))
            assertEquals("a", binding.photoAt(lines[1].start)?.contentId)
            assertNull(binding.photoAt(lines[0].start), "a header is not a photograph")
            assertEquals("c", binding.photoAt(lines[3].start)?.contentId)

            // The viewer's pages: photographs only, headers skipped, in feed order.
            assertEquals(
                listOf("a", "b", "c"),
                binding.loadedPhotoPositions().map { binding.photoAt(it)?.contentId },
            )
        }
    }

    @Test
    fun a_wider_window_widens_the_rows() = vmTest { vms ->
        val photos = List(12) { photo("p$it", 5 * day - it * 1_000L) }
        bind(vms, MockPhotosRepository(photos = photos)) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val narrow = binding.layout.value.lineCount

            // An iPad Pro 13" in portrait: the width the shared rule widens, and the count Compose draws.
            binding.setGridWidth(1024f)
            advanceUntilIdle()
            val wide = binding.layout.value
            assertEquals(FeedDensity.Day.columnsAt(1024f), wide.columns)
            assertTrue(wide.columns > FeedDensity.Day.columns, "a tablet must not keep the phone's count")
            assertTrue(wide.lineCount < narrow, "12 photographs in ${wide.columns} columns take fewer lines than in ${FeedDensity.Day.columns}")

            // A phone's width keeps the phone's grid exactly.
            binding.setGridWidth(390f)
            advanceUntilIdle()
            assertEquals(FeedDensity.Day.columns, binding.layout.value.columns)

            binding.setGridWidth(null)
            advanceUntilIdle()
            assertEquals(FeedDensity.Day.columns, binding.layout.value.columns)
        }
    }

    /** Where [photo] (a library position) is drawn: the row of [layout] that holds it. */
    private fun rowHolding(layout: app.tenshu.photos.ui.screens.feed.FeedLayout, line: Int, photo: Int): Boolean {
        // A header and the row under it share a position; the request may name either.
        for (candidate in listOf(line, line + 1)) {
            if (candidate >= layout.lineCount) continue
            val l = layout[candidate]
            val first = layout.photoAt(candidate) ?: continue
            if (l is FeedLine.Row && photo >= first && photo < first + l.size) return true
        }
        return false
    }

    private fun library(count: Int) = List(count) { photo("p$it", 400 * day - it * (8 * 3_600_000L)) }

    @Test
    fun a_density_change_keeps_the_photograph_at_the_top_in_view() = vmTest { vms ->
        bind(vms, MockPhotosRepository(photos = library(90))) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            assertNull(binding.scrollRequests.value, "pages landing are not a reason to scroll")

            val day = binding.layout.value
            val top = (0 until day.lineCount).first { day[it] is FeedLine.Row && (day.photoAt(it) ?: 0) >= 30 }
            val photo = day.photoAt(top)!!
            binding.setViewport(topLine = top, scrolledPast = 0f, layout = binding.layout.value)

            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()

            val request = assertNotNull(binding.scrollRequests.value, "a density change must re-anchor")
            val month = binding.layout.value
            assertEquals(FeedDensity.Month.columns, month.columns)
            assertTrue(
                rowHolding(month, request.line, photo),
                "line ${request.line} must draw photograph $photo — the one at the top before the change",
            )
        }
    }

    @Test
    fun a_width_change_re_anchors_without_waiting_for_a_rebuilt_stream() = vmTest { vms ->
        bind(vms, MockPhotosRepository(photos = library(90))) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val narrow = binding.layout.value
            val top = (0 until narrow.lineCount).first { narrow[it] is FeedLine.Row && (narrow.photoAt(it) ?: 0) >= 45 }
            val photo = narrow.photoAt(top)!!
            binding.setViewport(topLine = top, scrolledPast = 0f, layout = binding.layout.value)

            binding.setGridWidth(1024f)
            advanceUntilIdle()

            val request = assertNotNull(binding.scrollRequests.value)
            assertTrue(rowHolding(binding.layout.value, request.line, photo), "the wider grid keeps photograph $photo on top")
            val serial = request.serial
            // A page or a re-emit after it has settled must not scroll again.
            binding.setGridWidth(1024f)
            advanceUntilIdle()
            assertEquals(serial, binding.scrollRequests.value?.serial)
        }
    }

    @Test
    fun the_viewport_is_resolved_against_the_layout_it_was_reported_for() = vmTest { vms ->
        // The shell's line indexes the layout it DREW. A newer layout reaching the
        // binding first — a page, or a second density change before Swift renders — must not be the
        // one the line is looked up in, or the same number names another photograph.
        // ⚠ 24 a day: the harness groups by UTC day at every density, so a day must outgrow a row
        // for 3 and 5 columns to cut different lines — with 3 a day both layouts are identical.
        val full = List(120) { photo("f$it", 400 * day - it * 3_600_000L) }
        bind(vms, MockPhotosRepository(photos = full)) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val drawn = binding.layout.value
            // ⚠ Near the top on purpose: the same line number must name a REAL, different photograph in
            // the Month layout, or a wrong lookup finds nothing and the test passes by coincidence.
            val top = (0 until drawn.lineCount).first { drawn[it] is FeedLine.Row && (drawn.photoAt(it) ?: 0) >= 12 }
            val photo = drawn.photoAt(top)!!
            binding.setViewport(topLine = top, scrolledPast = 0f, layout = drawn)

            // Two changes, and the shell never drew the Month layout in between.
            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            val month = binding.layout.value
            assertTrue(
                month.photoAt(top) != null && month.photoAt(top) != photo,
                "precondition: line $top must name another photograph in Month (${month.photoAt(top)} vs $photo), or this test cannot tell the layouts apart",
            )
            binding.viewModel.setDensity(FeedDensity.Year)
            advanceUntilIdle()

            val request = assertNotNull(binding.scrollRequests.value)
            assertTrue(
                rowHolding(binding.layout.value, request.line, photo),
                "line ${request.line} must draw photograph $photo — the one on top of the layout the shell drew",
            )
        }
    }

    @Test
    fun cancelling_withdraws_a_request_already_published() = vmTest { vms ->
        // Clearing only the pending pass would leave the published request live, and the shell
        // could still perform it after the user had taken the list back.
        bind(vms, MockPhotosRepository(photos = library(90))) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val drawn = binding.layout.value
            binding.setViewport(topLine = 20, scrolledPast = 0f, layout = drawn)
            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            assertNotNull(binding.scrollRequests.value, "precondition: a re-anchor was published")

            binding.cancelReanchor()
            assertNull(binding.scrollRequests.value, "a cancelled re-anchor must not stay performable")
            binding.setGridWidth(390f)
            advanceUntilIdle()
            assertNull(binding.scrollRequests.value, "and no later pass revives it")
        }
    }

    @Test
    fun a_regrouping_that_presents_an_identical_window_still_settles() = vmTest { vms ->
        // The window is a StateFlow, so a rebuilt generation equal to the old one is swallowed and
        // a second pass for the settle to wait on never comes — the anchor would stay live, and the
        // next unrelated page jump back to it. Settling must follow the rebuilt generation's
        // presentation, not a second DISTINCT layout.
        bind(vms, MockPhotosRepository(photos = library(90))) { binding ->
            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            val drawn = binding.layout.value
            binding.setViewport(topLine = 6, scrolledPast = 0f, layout = drawn)

            // This harness groups by day at every density, so Month and Year present the same window.
            binding.viewModel.setDensity(FeedDensity.Year)
            advanceUntilIdle()
            assertNotNull(binding.scrollRequests.value, "precondition: the regrouping re-anchored")

            // ⚠ Asserted on the state, not by provoking a page: this static source cannot present a
            // later page, and the identical re-presentations it can make are swallowed by the same
            // StateFlow — so a live anchor would sit unobserved and a page-driven check pass anyway.
            assertTrue(!binding.isReanchoring, "the regrouping has been presented; nothing is left for a later page to act on")
        }
    }

    /**
     * A feed whose SECOND and later generations wait for [gate] — so a test can hold the rebuilt
     * stream back while other events land. The first generation is served at once.
     */
    private class GatedFeed(
        private val mock: MockPhotosRepository,
        val gate: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred(),
        /** What the second and later generations serve — a library that changed meanwhile. */
        private val later: MockPhotosRepository = mock,
    ) : app.tenshu.photos.data.PhotosRepository by mock {
        private var served = 0
        override fun feedPaging(pageSize: Int, readContext: kotlin.coroutines.CoroutineContext): kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<Photo>> {
            val first = served++ == 0
            return kotlinx.coroutines.flow.flow {
                if (!first) gate.await()
                emitAll((if (first) mock else later).feedPaging(pageSize))
            }
        }
    }

    @Test
    fun a_regrouping_settles_only_once_its_own_generation_is_presented() = vmTest { vms ->
        // Counting presentations would let an OLD-generation event, landing after the density
        // changed, pass for the rebuilt one — and the anchor settle early. Settling must wait for a
        // generation built for the NEW density.
        val feed = GatedFeed(MockPhotosRepository(photos = library(90)))
        bind(vms, feed) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val drawn = binding.layout.value
            val top = (0 until drawn.lineCount).first { drawn[it] is FeedLine.Row && (drawn.photoAt(it) ?: 0) >= 12 }
            binding.setViewport(topLine = top, scrolledPast = 0f, layout = drawn)

            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            // The old window re-cut at five columns holds the photograph — "loaded" is true — but the
            // Month generation has not been presented, so nothing may settle yet.
            assertTrue(binding.isReanchoring, "held open until the Month generation is presented")

            // The interleaving: an OLD-generation presentation after the change. A
            // re-collect makes `cachedIn` replay the Day generation while Month is still held back.
            binding.retry()
            advanceUntilIdle()
            assertTrue(binding.isReanchoring, "a replayed Day generation is not the regrouping")

            feed.gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(!binding.isReanchoring, "the Month generation is presented and holds the photograph")
        }
    }

    @Test
    fun the_scroll_target_is_decided_synchronously_against_the_layout_on_screen() = vmTest { vms ->
        // A shell mirroring the request asynchronously would (1) show a cancelled request as live
        // to a queued scroll, and (2) never release a request whose exact layout it never saw
        // (StateFlow conflation). The decision is the binding's.
        val feed = GatedFeed(MockPhotosRepository(photos = library(90)))
        bind(vms, feed) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val day = binding.layout.value
            val top = (0 until day.lineCount).first { day[it] is FeedLine.Row && (day.photoAt(it) ?: 0) >= 12 }
            binding.setViewport(topLine = top, scrolledPast = 0f, layout = day)

            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            val request = assertNotNull(binding.scrollRequests.value)
            val current = binding.layout.value

            assertNull(binding.scrollTarget(drawn = day), "a layout OLDER than the request's is not scrolled against")
            val target = assertNotNull(binding.scrollTarget(drawn = current), "the request's own layout")
            assertEquals(request.serial, target.serial)
            assertEquals(request.line, target.line)

            // Superseded: the shell has caught up to a layout NEWER than the request's — released,
            // and resolved there rather than stuck waiting for a layout it will never draw.
            feed.gate.complete(Unit)
            advanceUntilIdle()
            val newest = binding.layout.value
            val released = assertNotNull(binding.scrollTarget(drawn = newest))
            assertEquals(newest.lineOfPhoto(request.photo, request.onHeader), released.line)

            // Cancelled: gone at once, whatever the shell has mirrored.
            binding.cancelReanchor()
            assertNull(binding.scrollTarget(drawn = newest), "a cancelled re-anchor has no target, synchronously")
        }
    }

    @Test
    fun a_label_is_read_off_the_layout_the_shell_drew() = vmTest { vms ->
        // The sticky header must not look its line up in the binding's NEWEST layout, which can be
        // ahead of what is drawn, and name another section ("2 January" over a 31 December row). It
        // must read the drawn layout, and format for the density THAT layout was cut at.
        bind(vms, MockPhotosRepository(photos = List(120) { photo("f$it", 400 * day - it * 3_600_000L) })) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val day = binding.layout.value
            val line = (0 until day.lineCount).first { day[it] is FeedLine.Row && (day.photoAt(it) ?: 0) >= 40 }
            val dayLabel = binding.labelAt(line, day)

            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            assertTrue(binding.layout.value !== day, "precondition: the binding has moved on")
            assertEquals(dayLabel, binding.labelAt(line, day), "the drawn Day layout keeps its own label")
            assertTrue(binding.labelAt(line) != dayLabel, "precondition: the newest layout names something else there")
        }
    }

    @Test
    fun a_tapped_photograph_is_found_by_identity_in_the_current_list() = vmTest { vms ->
        // A tile drawn from an older layout knows its OLD paged index, and resolved in the newest
        // list — where headers inserted or removed ahead of it — that names another photograph. The
        // tap carries the photograph; its position is looked up where it is now.
        // ⚠ Its own grouping: Year is ONE section, so regrouping removes the day headers ahead of the
        // tile and shifts its index — with the default by-day rule every density has the same headers.
        val grouping: (FeedDensity, Long) -> Long = { d, ms -> if (d == FeedDensity.Year) 0L else ms / day * day }
        bind(vms, MockPhotosRepository(photos = List(120) { photo("f$it", 400 * day - it * 3_600_000L) }), grouping) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val day = binding.layout.value
            val drawnAt = binding.loadedPhotoPositions().first { binding.photoAt(it)?.contentId == "f40" }

            binding.viewModel.setDensity(FeedDensity.Year)
            advanceUntilIdle()
            assertTrue(binding.photoAt(drawnAt)?.contentId != "f40", "precondition: the old index names another photograph now")

            val now = assertNotNull(binding.positionOf("/Photos/f40.jpg"))
            assertEquals("f40", binding.photoAt(now)?.contentId)
            assertNull(binding.positionOf("/Photos/nowhere.jpg"), "a photograph not in the list has no position")
            assertTrue(day !== binding.layout.value)
        }
    }

    @Test
    fun positions_and_photographs_come_from_one_snapshot() = vmTest { vms ->
        // The layout is derived from the presenter's window and updates a dispatch later, so
        // positions from one and photographs from the other disagree. Every position the binding
        // hands out must name a photograph in the very layout it reads photographs from.
        val feed = GatedFeed(MockPhotosRepository(photos = library(90)))
        bind(vms, feed) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val snapshot = binding.layout.value
            assertEquals(snapshot.loadedPhotoPositions(), binding.loadedPhotoPositions())
            for (position in binding.loadedPhotoPositions()) {
                assertNotNull(binding.photoAt(position), "position $position must name a photograph in the same layout")
            }
        }
    }

    @Test
    fun stale_generation_events_do_not_use_up_the_re_anchor() = vmTest { vms ->
        // Were every emission counted against the pass limit, a slow regrouping whose OLD
        // generation kept presenting would run out of passes before its own generation arrived —
        // and the feed stay on the wrong photograph. Only the awaited generation's passes count. ⚠
        // Pins the settle; it does NOT reproduce stale APPENDS of the old generation — this static
        // source's replays present an equal window, which never reaches the anchor at all. That
        // needs a real Pager; the rule is small and read in `onShaped`.
        val feed = GatedFeed(MockPhotosRepository(photos = library(90)))
        bind(vms, feed) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val day = binding.layout.value
            val top = (0 until day.lineCount).first { day[it] is FeedLine.Row && (day.photoAt(it) ?: 0) >= 30 }
            val photo = day.photoAt(top)!!
            binding.setViewport(topLine = top, scrolledPast = 0f, layout = day)

            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            repeat(5) {
                binding.retry() // a replay of the OLD generation — a stale presentation each time
                advanceUntilIdle()
            }
            assertTrue(binding.isReanchoring, "still waiting for the Month generation after stale events")

            feed.gate.complete(Unit)
            advanceUntilIdle()
            val request = assertNotNull(binding.scrollRequests.value)
            assertTrue(request.layout === binding.layout.value, "the last request is for the Month generation's layout")
            assertTrue(rowHolding(binding.layout.value, request.line, photo), "and it holds photograph $photo")
        }
    }

    @Test
    fun a_photograph_is_found_by_its_path_and_a_moved_one_is_not() = vmTest { vms ->
        // The path is the identity (`file-content-addressing.md` §3.3.1): a moved photograph is a
        // new one, so its old path finds nothing — never another file with the same bytes.
        bind(vms, MockPhotosRepository(photos = library(12))) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val at = assertNotNull(binding.positionOf("/Photos/p7.jpg"))
            assertEquals("p7", binding.photoAt(at)?.contentId)
            assertNull(binding.positionOf("/Photos/renamed.jpg"), "an old or unknown path names nothing")
        }
    }

    @Test
    fun a_performed_scroll_is_acknowledged_and_not_offered_again() = vmTest { vms ->
        // A request's being performed must not live only in the disposable grid coordinator — a
        // recreated grid would see it again and jump back. The binding consumes it.
        bind(vms, MockPhotosRepository(photos = library(90))) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val day = binding.layout.value
            binding.setViewport(topLine = 20, scrolledPast = 0f, layout = day)
            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            val target = assertNotNull(binding.scrollTarget(drawn = binding.layout.value))

            binding.acknowledgeScroll(target.serial + 1)
            assertNotNull(binding.scrollTarget(drawn = binding.layout.value), "another serial acknowledges nothing")
            binding.acknowledgeScroll(target.serial)
            assertNull(binding.scrollTarget(drawn = binding.layout.value), "a performed request is not offered again")
        }
    }

    @Test
    fun a_reanchor_is_pending_from_the_change_until_it_is_performed_or_cancelled() = vmTest { vms ->
        // What a shell following the top of the feed stands aside for (`photos-ios.md` §8.20): the
        // position is the binding's from the regrouping until the scroll has been performed.
        bind(vms, MockPhotosRepository(photos = library(90))) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            assertTrue(!binding.reanchorPending, "a feed at rest moves nothing")

            binding.setViewport(topLine = 20, scrolledPast = 0f, layout = binding.layout.value)
            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            // ⚠ Settled in the binding, yet still pending: the request is published, not performed.
            assertTrue(!binding.isReanchoring)
            assertTrue(binding.reanchorPending, "a published request the shell has not performed")

            binding.acknowledgeScroll(assertNotNull(binding.scrollTarget(drawn = binding.layout.value)).serial)
            assertTrue(!binding.reanchorPending, "performed")

            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            assertTrue(binding.reanchorPending)
            binding.cancelReanchor()
            assertTrue(!binding.reanchorPending, "the user took the list back")
        }
    }

    @Test
    fun a_pending_re_anchor_follows_the_photograph_when_photographs_arrive_ahead() = vmTest { vms ->
        // The pending anchor holds the photograph, not only its library ORDINAL: photographs
        // arriving ahead between the Day and Month generations would make the Month generation read
        // an ordinal as another photo.
        val base = library(90)
        val arrivals = List(4) { photo("new$it", 401 * day - it * 3_600_000L) } // newer than all
        val feed = GatedFeed(MockPhotosRepository(photos = base), later = MockPhotosRepository(photos = arrivals + base))
        bind(vms, feed) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val day = binding.layout.value
            val top = (0 until day.lineCount).first { day[it] is FeedLine.Row && (day.photoAt(it) ?: 0) >= 30 }
            val tracked = binding.photoAt(day[top].start)!!
            binding.setViewport(topLine = top, scrolledPast = 0f, layout = day)

            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            feed.gate.complete(Unit)
            advanceUntilIdle()

            val month = binding.layout.value
            val request = assertNotNull(binding.scrollRequests.value)
            val now = assertNotNull(month.photoOrdinalOf(tracked.path), "precondition: it is loaded")
            assertTrue(rowHolding(month, request.line, now), "the request draws ${tracked.path} — not whatever took its old ordinal")
        }
    }

    @Test
    fun a_drawn_layout_keeps_its_own_density_however_many_layouts_follow() = vmTest { vms ->
        // The drawn layout's density must not come from a bounded cache of recent layouts: more
        // newer layouts than it holds (a regrouping, then pages) would evict it and give a Day row
        // a Month label.
        bind(vms, MockPhotosRepository(photos = List(120) { photo("f$it", 400 * day - it * 3_600_000L) })) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val drawn = binding.layout.value
            val line = (0 until drawn.lineCount).first { drawn[it] is FeedLine.Row && (drawn.photoAt(it) ?: 0) >= 40 }
            val dayLabel = binding.labelAt(line, drawn)

            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            for (width in listOf(400f, 500f, 700f, 900f, 1100f, 1300f, 1500f, 1700f)) {
                binding.setGridWidth(width) // eight more layouts, none of them drawn
                advanceUntilIdle()
            }
            assertEquals(FeedDensity.Day, drawn.density)
            assertEquals(dayLabel, binding.labelAt(line, drawn), "still labelled for the density it was cut at")
        }
    }

    @Test
    fun an_identified_photograph_that_is_absent_is_never_scrolled_to_by_its_stale_ordinal() = vmTest { vms ->
        // Falling back to the ORDINAL when the drawn layout lacks the photograph would scroll the
        // shell to whatever took its place. An identity that does not resolve yields no target, and
        // the binding publishes none for it.
        val base = library(90)
        val feed = GatedFeed(MockPhotosRepository(photos = base), later = MockPhotosRepository(photos = base.drop(40)))
        bind(vms, feed) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val day = binding.layout.value
            val top = (0 until day.lineCount).first { day[it] is FeedLine.Row && (day.photoAt(it) ?: 0) >= 12 }
            binding.setViewport(topLine = top, scrolledPast = 0f, layout = day)

            binding.viewModel.setDensity(FeedDensity.Month)
            advanceUntilIdle()
            // The OLD window re-cut still holds the photograph, so a request is published against it.
            val earlier = assertNotNull(binding.scrollRequests.value, "precondition: a request for the re-cut layout")
            feed.gate.complete(Unit) // the Month generation arrives WITHOUT the first 40 photographs
            advanceUntilIdle()

            val newest = binding.layout.value
            assertNull(binding.scrollTarget(drawn = newest), "no target for a photograph that is not there")
            // A grid still drawing the EARLIER request's layout must not scroll to the
            // gone photograph's old place — the earlier request is withdrawn, not merely not replaced.
            assertNull(binding.scrollTarget(drawn = earlier.layout), "the earlier request is withdrawn")
            assertTrue(!binding.isReanchoring, "absent from a complete layout: gone, the anchor ends")
        }
    }

    @Test
    fun a_paging_failure_is_reported_to_a_caller_waiting_for_a_page() = vmTest { vms ->
        // `status` reports only the refresh; `pagingFailed` is what a lookup paging
        // toward an edge watches. ⚠ This static source can only fail its REFRESH; an edge failure needs
        // a real Pager — the flow reads the append and prepend states the same way.
        bind(vms, MockPhotosRepository(photos = emptyList(), outcome = PhotoPageResult.Unreachable)) { binding ->
            advanceUntilIdle()
            assertTrue(binding.pagingFailed.value)
        }
        bind(vms, MockPhotosRepository(photos = library(12))) { binding ->
            advanceUntilIdle()
            assertTrue(!binding.pagingFailed.value, "a healthy feed has failed nothing")
        }
    }

    @Test
    fun a_row_is_labelled_with_its_section_like_the_header_above_it() = vmTest { vms ->
        bind(vms, MockPhotosRepository(photos = library(12))) { binding ->
            binding.viewModel.setDensity(FeedDensity.Day)
            advanceUntilIdle()
            val layout = binding.layout.value
            val header = (0 until layout.lineCount).first { layout[it] is FeedLine.Header }
            assertNotNull(binding.labelAt(header))
            assertEquals(binding.labelAt(header), binding.labelAt(header + 1), "the sticky header must name the row's own section")
            assertNull(binding.labelAt(layout.lineCount), "past the end nothing is known")
        }
    }

    @Test
    fun an_unconnected_feed_reports_unreachable_and_draws_nothing() = vmTest { vms ->
        bind(vms, NotConnectedPhotosRepository()) { binding ->
            advanceUntilIdle()
            assertEquals(0, binding.layout.value.lineCount)
            val refresh = binding.presenter.loadState.value?.refresh
            assertIs<LoadState.Error>(refresh, "the shell's wall reads this, never an empty grid")
            assertEquals(FeedStatus.Unreachable, binding.status.value)
        }
    }

    @Test
    fun the_four_outcomes_stay_four() = vmTest { vms ->
        bind(vms, MockPhotosRepository(photos = listOf(photo("a", day)))) { binding ->
            advanceUntilIdle()
            assertEquals(FeedStatus.Loaded, binding.status.value)
        }
        bind(vms, MockPhotosRepository(photos = emptyList())) { binding ->
            advanceUntilIdle()
            assertEquals(FeedStatus.Empty, binding.status.value, "a settled, genuinely empty library")
        }
        bind(vms, MockPhotosRepository(outcome = PhotoPageResult.Failed("boom"))) { binding ->
            advanceUntilIdle()
            assertEquals(FeedStatus.Failed, binding.status.value, "a failed read is not an empty library")
        }
        bind(vms, MockPhotosRepository(outcome = PhotoPageResult.Unreachable)) { binding ->
            advanceUntilIdle()
            assertEquals(FeedStatus.Unreachable, binding.status.value)
        }
    }
}

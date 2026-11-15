package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoHitSource
import app.tenshu.photos.model.SemanticSearchStatus
import app.tenshu.photos.ui.screens.search.DescriptionSearch
import app.tenshu.photos.ui.screens.search.PhotoSearchLayout
import app.tenshu.photos.ui.screens.search.PhotoSearchViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Search over the Home tab (`photos-app.md` S2).
 *
 * ⚠⚠ Two properties carry this screen and both are tested first:
 *
 * 1. **A refusal is never "nothing matched".** `photosLike` throws when no model is installed —
 *    which is *every device today* — and rendering that as an empty result is the mistake
 *    `mia-query-layer.md` §14.1 names.
 * 2. **The screen is complete with source 1 alone.** Labels and semantic search need a backend the
 *    production registry does not have, so "sources 2 and 3 contributed nothing" is the normal
 *    case, not a failure.
 */
class PhotoSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(name: String, i: Int = 0) =
        Photo("/Backup/Phone/$name", 1_000L - i, isVideo = false, contentId = "c$name")

    @Test
    fun aNameMatchIsFoundWithNoModelOnTheDevice() = vmTest(dispatcher) { vms ->
        // The default mock answers NO_BACKEND for semantic search, which is what a real device
        // answers. The screen must still work.
        val repo = MockPhotosRepository(listOf(photo("dog-park.jpg"), photo("cat.jpg", 1)))
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf("/Backup/Phone/dog-park.jpg"), state.hits.map { it.photo.path })
        assertEquals(setOf(PhotoHitSource.NAME), state.hits.single().sources)
        assertFalse(state.isEmpty)
    }

    @Test
    fun aRefusalIsCarriedSeparatelyFromTheResults() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The rule S2 is written around. "No model installed" must reach the screen as a
        // *reason*, not as an absence — and it must not turn a successful name search into a
        // failure either.
        val repo = MockPhotosRepository(listOf(photo("dog-park.jpg")))
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(SemanticSearchStatus.Unavailable("noBackend"), state.semanticStatus)
        assertEquals(1, state.hits.size, "a refusal from source 3 must not empty the list")
        assertFalse(state.isEmpty)
    }

    @Test
    fun nothingMatchingIsStillNothingMatching() = vmTest(dispatcher) { vms ->
        // The other half: a genuine miss must read as a miss, not be softened by the refusal.
        val repo = MockPhotosRepository(listOf(photo("cat.jpg")))
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("giraffe")
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.isEmpty)
        assertTrue(vm.state.value.settled)
    }

    @Test
    fun onePhotographFoundThreeWaysIsOneRow() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Three sources over one library agree often. A list that repeats a photograph once per
        // source reads as a broken search rather than a thorough one.
        val dog = photo("dog-park.jpg")
        val repo = MockPhotosRepository(listOf(dog))
        repo.label(dog.path, "dog")
        repo.semanticOutcome = listOf(dog) to SemanticSearchStatus.Available

        val vm = vms.own(PhotoSearchViewModel(repo))
        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()

        val hits = vm.state.value.hits
        assertEquals(1, hits.size)
        assertEquals(
            setOf(PhotoHitSource.NAME, PhotoHitSource.LABEL, PhotoHitSource.SEMANTIC),
            hits.single().sources,
        )
        // ⚠ The badge leads with the most checkable claim — a name match the user can verify.
        assertEquals(PhotoHitSource.NAME, hits.single().primarySource)
        assertEquals("dog", hits.single().matchedLabel)
    }

    @Test
    fun theOrderIsCheapestAndMostCertainFirst() = vmTest(dispatcher) { vms ->
        val named = photo("dog-park.jpg")
        val labelled = photo("IMG_0007.jpg", 1)
        val similar = photo("IMG_0009.jpg", 2)
        val repo = MockPhotosRepository(listOf(named, labelled, similar))
        repo.label(labelled.path, "dog")
        repo.semanticOutcome = listOf(similar) to SemanticSearchStatus.Available

        val vm = vms.own(PhotoSearchViewModel(repo))
        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(named.path, labelled.path, similar.path),
            vm.state.value.hits.map { it.photo.path },
        )
        assertEquals(
            listOf(PhotoHitSource.NAME, PhotoHitSource.LABEL, PhotoHitSource.SEMANTIC),
            vm.state.value.hits.map { it.primarySource },
        )
    }

    @Test
    fun aBurstOfKeystrokesRunsOneSearch() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Source 3 scores every indexed vector *per call* — its own KDoc says "not fine to
        // re-read per keystroke" — and source 1 walks the whole tree. Neither is a per-key cost.
        val repo = CountingRepository(MockPhotosRepository(listOf(photo("dog-park.jpg"))))
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("d")
        vm.onQueryChanged("do")
        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.nameSearches)
        assertEquals(1, repo.semanticSearches)
    }

    @Test
    fun noResultsNeverFlashesBetweenTwoLetters() = vmTest(dispatcher) { vms ->
        // ⚠ `settled` is cleared on every keystroke: without it a query mid-typing renders "No
        // photos match" for a moment before the results it will find arrive.
        val repo = MockPhotosRepository(listOf(photo("dog-park.jpg")))
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.settled)

        vm.onQueryChanged("dogg")
        assertFalse(vm.state.value.settled, "a new keystroke un-settles the screen at once")
        assertFalse(vm.state.value.isEmpty, "and nothing may render as empty while it is unsettled")
    }

    @Test
    fun clearingTheBarEmptiesEverything() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository(listOf(photo("dog-park.jpg")))
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()
        vm.clear()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.isIdle)
        assertTrue(vm.state.value.hits.isEmpty())
        assertFalse(vm.state.value.isEmpty, "an idle bar is not a search that found nothing")
    }

    @Test
    fun aSlowSearchDoesNotOverwriteANewerOne() = vmTest(dispatcher) { vms ->
        // ⚠ The stale-result guard: a scan for "do" completing after the user typed "dog" must not
        // replace the newer query's results.
        val repo = MockPhotosRepository(listOf(photo("dog-park.jpg"), photo("door.jpg", 1)))
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("do")
        testScheduler.advanceTimeBy(PhotoSearchViewModel.SEARCH_DEBOUNCE_MS + 1)
        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()

        assertEquals("dog", vm.state.value.query)
        assertEquals(listOf("/Backup/Phone/dog-park.jpg"), vm.state.value.hits.map { it.photo.path })
    }

    // ── `photos-ios.md` §8.19: what the move into shared logic found, and what it added ─────────────

    private val debounce = PhotoSearchViewModel.SEARCH_DEBOUNCE_MS

    @Test
    fun aQuestionAlreadyAnsweredNeverLeavesTheSpinnerUp() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The defect this slice found, on Android today: a keystroke un-settled the screen, and the
        // pipeline then dropped a query equal to the last one it ran — so NOTHING ran, and the spinner
        // stayed for good. Typing "dog" and then a space was enough; so was a letter typed and taken
        // back before the debounce fired.
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"))))
        val vm = vms.own(PhotoSearchViewModel(repo))
        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.settled, "precondition")

        vm.onQueryChanged("dog ")
        assertFalse(vm.state.value.searching, "at once — not after a second scan")
        assertTrue(vm.state.value.settled)
        testScheduler.advanceUntilIdle()
        assertEquals(1, repo.nameSearches, "the question was answered: the library is not walked again")
        assertEquals("dog ", vm.state.value.query, "the bar keeps what was typed")
        assertFalse(vm.state.value.searching, "the same question: nothing to wait for")
        assertTrue(vm.state.value.settled)
        assertEquals(1, vm.state.value.hits.size, "and its answer is still on screen")

        vm.onQueryChanged("dogs")
        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.searching, "typed and taken back inside the debounce")
        assertTrue(vm.state.value.layout.body is PhotoSearchLayout.Body.Results, "got ${vm.state.value.layout.body}")
        assertEquals(1, repo.nameSearches, "nor for a letter typed and taken back")
    }

    @Test
    fun aNameSearchThatCouldNotLookIsNotNothingMatched_andARetryAsksAgain() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A failed walk found nothing because it looked at nothing. The repository used to fold
        // that into an empty list, and the screen said "No photos match".
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"))))
        repo.nameFails = true
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue(PhotoHitSource.NAME in state.couldNotLook, "got ${state.couldNotLook}")
        assertTrue(state.settled, "it is an answer — the spinner does not stay")
        val body = state.layout.body
        assertTrue(body is PhotoSearchLayout.Body.CouldNotLook, "never Empty; got $body")

        repo.nameFails = false
        vm.retry()
        assertTrue(vm.state.value.searching, "a retry is seen to run")
        testScheduler.runCurrent()                             // ⚠ no debounce: it is a button
        assertEquals(listOf("/Backup/Phone/dog-park.jpg"), vm.state.value.hits.map { it.photo.path })
        assertTrue(vm.state.value.couldNotLook.isEmpty())
    }

    @Test
    fun aSourceThatThrowsIsCouldNotLook_andTheOthersStillAnswer() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A dropped connection reaches a client as a THROW (§8.18 note 4). Unguarded it left the
        // view model's scope as an uncaught exception — the search never settled, or the app went down.
        val dog = photo("IMG_0007.jpg")
        val repo = FailingReads(MockPhotosRepository(listOf(dog)).apply { label(dog.path, "dog") })
        repo.nameThrows = IllegalStateException("Control went away")
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.settled)
        assertEquals(setOf(PhotoHitSource.NAME), state.couldNotLook)
        assertEquals(listOf(dog.path), state.hits.map { it.photo.path }, "the tag source still answered")
        assertTrue(state.layout.retryInNotes, "results on screen, and a way to ask for the rest")
        assertEquals(
            listOf(
                "Searching photos by what's in them needs a model on this device, and there isn't one yet.",
                "Couldn't search by file name this time, so there may be more.",
            ),
            state.layout.notes,
        )
    }

    @Test
    fun aCancellationThrownByTheCallIsAFailedRead_notASearchThatNeverSettles() = vmTest(dispatcher) { vms ->
        // ⚠ Only the SEARCH being superseded may end it silently. A `CancellationException` out of the
        // repository, with this search still wanted, would otherwise end it without publishing.
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"))))
        repo.nameThrows = kotlinx.coroutines.CancellationException("a child of the call was cancelled")
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.settled, "the spinner must not stay")
        assertFalse(vm.state.value.searching)
        assertTrue(PhotoHitSource.NAME in vm.state.value.couldNotLook)
    }

    @Test
    fun aSourceThatNeverAnswersIsBounded_theRestAreNotAsked_andTheNextQueryRuns() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A lost reply never returns and a Binder call ignores cancellation. Unbounded, the search
        // pipeline waited on it for ever — and every later query behind it.
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"), photo("cat.jpg", 1))))
        val hung = CompletableDeferred<Unit>().also { repo.nameHangsOn = it }
        val vm = vms.own(PhotoSearchViewModel(repo, timeoutMs = 5_000))

        vm.onQueryChanged("dog")
        testScheduler.advanceTimeBy(debounce + 4_000)
        assertTrue(vm.state.value.searching, "inside the bound it is still searching")
        testScheduler.advanceTimeBy(1_100)
        testScheduler.runCurrent()

        val state = vm.state.value
        assertTrue(state.settled, "the bound ends the wait")
        assertEquals(
            setOf(PhotoHitSource.NAME, PhotoHitSource.LABEL, PhotoHitSource.SEMANTIC), state.couldNotLook,
            "the sources behind an unanswered one are not asked — and say so",
        )
        assertEquals(0, repo.labelSearches, "not asked")
        assertEquals(0, repo.semanticSearches, "not asked")
        assertEquals(SemanticSearchStatus.Failed, state.semanticStatus, "⚠ never Unavailable: that names a cause")
        assertEquals(
            PhotoSearchLayout.Body.CouldNotLook(
                "Couldn't search your photos. Nothing was looked through — that doesn't mean nothing matches.",
            ),
            state.layout.body,
        )

        // The next question is not stuck behind the lost one.
        repo.nameHangsOn = null
        vm.onQueryChanged("cat")
        testScheduler.advanceTimeBy(debounce + 1)
        testScheduler.runCurrent()
        assertEquals(listOf("/Backup/Phone/cat.jpg"), vm.state.value.hits.map { it.photo.path })
        hung.complete(Unit)
    }

    @Test
    fun aNewerQuestionIsAnsweredWhileAnOlderOneStillRuns_andTheOlderAnswerIsNeverPublished() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The searches ran one behind the other: "dog" waited for the scan for "do" to finish — for
        // ever, when its reply was lost. And an answer for an old query must never be published over a
        // newer one. The scan for "do" here ignores cancellation, as a Binder call does.
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"), photo("door.jpg", 1))))
        val held = CompletableDeferred<Unit>()
        repo.nameHolds["do"] = held
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("do")
        testScheduler.advanceTimeBy(debounce + 1)
        testScheduler.runCurrent()
        assertEquals(1, repo.nameSearches, "precondition: the scan for \"do\" is running")

        vm.onQueryChanged("dog")
        testScheduler.advanceTimeBy(debounce + 1)
        testScheduler.runCurrent()
        assertEquals(
            listOf("/Backup/Phone/dog-park.jpg"), vm.state.value.hits.map { it.photo.path },
            "the newer question is answered without waiting for the older scan",
        )
        assertTrue(vm.state.value.settled)

        held.complete(Unit)                                    // the older scan answers LAST
        testScheduler.advanceUntilIdle()
        assertEquals("dog", vm.state.value.query)
        assertEquals(
            listOf("/Backup/Phone/dog-park.jpg"), vm.state.value.hits.map { it.photo.path },
            "the older answer (two photographs) never replaces the newer one",
        )
    }

    @Test
    fun aSearchWritesOnlyItsOwnFields_neverTheBarsTextAsItWasWhenItBegan() = vmTest(dispatcher) { vms ->
        // ⚠⚠ §8.16 note 5: a read that publishes a copy of the WHOLE state puts back whatever changed
        // while it ran. Here the user types a space while "dog" is being searched — the same question,
        // so the search goes on — and its answer must not take the space back out of the bar.
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"))))
        val held = CompletableDeferred<Unit>()
        repo.nameHolds["dog"] = held
        val vm = vms.own(PhotoSearchViewModel(repo))

        vm.onQueryChanged("dog")
        testScheduler.advanceTimeBy(debounce + 1)
        testScheduler.runCurrent()
        vm.onQueryChanged("dog ")
        testScheduler.advanceTimeBy(debounce + 1)
        testScheduler.runCurrent()
        assertEquals(1, repo.nameSearches, "the same question is not asked twice")

        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("dog ", vm.state.value.query, "the answer left the bar alone")
        assertEquals(1, vm.state.value.hits.size)
        assertTrue(vm.state.value.settled)
    }

    @Test
    fun clearingTheBarWhileASearchRunsStaysIdle() = vmTest(dispatcher) { vms ->
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"))))
        val held = CompletableDeferred<Unit>()
        repo.nameHolds["dog"] = held
        val vm = vms.own(PhotoSearchViewModel(repo))
        vm.onQueryChanged("dog")
        testScheduler.advanceTimeBy(debounce + 1)
        testScheduler.runCurrent()

        vm.onQueryChanged("")
        held.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.isIdle)
        assertTrue(vm.state.value.hits.isEmpty(), "an answer for a bar that was cleared is nobody's")
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun whereDescriptionSearchIsNotBuilt_theSourceIsNeverAsked_andItReadsAsOff() = vmTest(dispatcher) { vms ->
        // ⚠⚠ iOS until PIO-7 (`photos-ios.md` §3.2): no index, no model, no consent switch. The state
        // is "off — not available here yet", never "no results", and never the proxy's `noConsent`
        // sentence, which names a switch in Control's Settings → Models that Control-on-iOS lacks.
        // The repository here WOULD answer `noConsent` — the proof that it is not asked.
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"))))
        repo.semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Unavailable("noConsent")
        val vm = vms.own(PhotoSearchViewModel(repo, descriptionSearch = DescriptionSearch.NotBuilt("iPhone")))
        val off = "Search by description isn't available on this iPhone yet."

        assertEquals(SemanticSearchStatus.NotBuiltHere("iPhone"), vm.state.value.semanticStatus, "known before any search")

        vm.onQueryChanged("dog")
        assertEquals(listOf(off), vm.state.value.layout.notes, "said while it searches too — it is true of the device")
        testScheduler.advanceUntilIdle()
        assertEquals(0, repo.semanticSearches, "never asked")
        assertEquals(SemanticSearchStatus.NotBuiltHere("iPhone"), vm.state.value.semanticStatus)
        assertEquals(listOf(off), vm.state.value.layout.notes)
        assertTrue(vm.state.value.layout.body is PhotoSearchLayout.Body.Results, "name search still found it")
        assertTrue(vm.state.value.couldNotLook.isEmpty(), "off is not a failed read: no retry")
        assertFalse(vm.state.value.layout.retryInNotes)

        vm.onQueryChanged("giraffe")
        testScheduler.advanceUntilIdle()
        assertEquals(PhotoSearchLayout.Body.Empty("No photos match \"giraffe\"."), vm.state.value.layout.body)
        assertEquals(listOf(off), vm.state.value.layout.notes, "⚠ nothing matched AND a source is off: both are said")

        vm.onQueryChanged("")
        assertEquals(SemanticSearchStatus.NotBuiltHere("iPhone"), vm.state.value.semanticStatus, "idle keeps it")
    }

    // ─── a bound backend that may not be ready (`ios-on-device-inference.md` §3.2) ─────────────

    private val noModelYet = "Searching photos by what's in them needs a model on this device, and there isn't one yet."
    private val notOnThisIphone = "Search by description isn't available on this iPhone yet."

    @Test
    fun aNoBackendRefusalReadsAsNoModelYet_untilTheDeviceSaysItIsUnsuitable() = vmTest(dispatcher) { vms ->
        // ⚠⚠ iOS with a backend bound: the route is `AskThisDevice` for the life of the process, and
        // the proxy answers `noBackend` for every state that is not ready. That sentence promises a
        // model, which is right while one can still arrive and wrong on a phone found unsuitable.
        // The answer is read when the refusal arrives, so the SAME view model follows the device.
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"))))
        var unsuitable = false
        var asked = 0
        val vm = vms.own(
            PhotoSearchViewModel(
                repo,
                descriptionSearch = DescriptionSearch.AskThisDevice,
                onNoBackend = {
                    asked++
                    if (unsuitable) SemanticSearchStatus.NotBuiltHere("iPhone") else null
                },
            ),
        )

        assertEquals(SemanticSearchStatus.Available, vm.state.value.semanticStatus, "nothing is said before the proxy is asked")
        assertEquals(0, asked, "asked at construction: the answer would be frozen there")

        // No model, preparing, awaiting its verdict: all three are the proxy's `noBackend`.
        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()
        assertEquals(1, repo.semanticSearches, "the proxy is asked on this route")
        assertEquals(SemanticSearchStatus.Unavailable("noBackend"), vm.state.value.semanticStatus)
        assertEquals(listOf(noModelYet), vm.state.value.layout.notes)

        // Unsuitable: the same refusal, another sentence. No relaunch, no new view model.
        unsuitable = true
        vm.retry()
        testScheduler.advanceUntilIdle()
        assertEquals(SemanticSearchStatus.NotBuiltHere("iPhone"), vm.state.value.semanticStatus)
        assertEquals(listOf(notOnThisIphone), vm.state.value.layout.notes)
        assertTrue(vm.state.value.layout.body is PhotoSearchLayout.Body.Results, "name search still found it")
        assertTrue(vm.state.value.couldNotLook.isEmpty(), "off is not a failed read: no retry")

        // Ready: the query runs and nothing is said.
        repo.semanticOutcome = listOf(photo("dog-park.jpg")) to SemanticSearchStatus.Available
        vm.retry()
        testScheduler.advanceUntilIdle()
        assertEquals(SemanticSearchStatus.Available, vm.state.value.semanticStatus)
        assertTrue(vm.state.value.layout.notes.isEmpty())
        assertEquals(setOf(PhotoHitSource.NAME, PhotoHitSource.SEMANTIC), vm.state.value.hits.single().sources)
    }

    @Test
    fun onlyTheNoBackendRefusalIsRemapped() = vmTest(dispatcher) { vms ->
        // ⚠ Every other reason is the proxy's own and names something true of a ready device:
        // consent, an index not built, a tower that cannot embed text. None may be replaced.
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"))))
        var asked = 0
        val vm = vms.own(
            PhotoSearchViewModel(
                repo,
                onNoBackend = {
                    asked++
                    SemanticSearchStatus.NotBuiltHere("iPhone")
                },
            ),
        )

        listOf("noConsent", "notIndexed", "noTextEmbedding").forEach { reason ->
            repo.semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Unavailable(reason)
            vm.retryWith("dog")
            testScheduler.advanceUntilIdle()
            assertEquals(SemanticSearchStatus.Unavailable(reason), vm.state.value.semanticStatus)
        }
        repo.semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Failed
        vm.retryWith("dog")
        testScheduler.advanceUntilIdle()
        assertEquals(SemanticSearchStatus.Failed, vm.state.value.semanticStatus)
        assertEquals(0, asked, "a refusal that is not `noBackend` went through the device's answer")
    }

    private fun PhotoSearchViewModel.retryWith(query: String) {
        if (state.value.query.trim() == query) retry() else onQueryChanged(query)
    }

    @Test
    fun aQuietReReadDropsAPhotographThatIsGone_withoutASpinner() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Back from the viewer, where a result may have been trashed: the list must not go on
        // citing a photograph that is gone.
        val dog = photo("dog-park.jpg")
        val repo = MockPhotosRepository(listOf(dog, photo("dog-bed.jpg", 1)))
        val vm = vms.own(PhotoSearchViewModel(repo))
        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.state.value.hits.size, "precondition")

        assertTrue(repo.trash(dog), "precondition: trashed in the viewer")
        vm.refresh()
        assertFalse(vm.state.value.searching, "quiet: the results stay up meanwhile")
        assertTrue(vm.state.value.settled)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("/Backup/Phone/dog-bed.jpg"), vm.state.value.hits.map { it.photo.path })
        assertEquals(listOf(vm.state.value.hits.single().photo), vm.state.value.photos, "what the viewer pages over")
    }

    @Test
    fun aTaggedOrLookalikePhotographThatWasTrashedIsNotCited() = vmTest(dispatcher) { vms ->
        // ⚠⚠ `media-processing-pipeline-sweep.md` F12: a label row and a vector outlive their file.
        // The real join drops them (`RealRepositoryReadTest.aSearchHitMayOnlyCite…`); this pins the
        // screen's half over a double that now does the same — it used to cite both.
        val tagged = photo("IMG_0007.jpg")
        val similar = photo("IMG_0009.jpg", 1)
        val kept = photo("IMG_0011.jpg", 2)
        val repo = MockPhotosRepository(listOf(tagged, similar, kept))
        repo.label(tagged.path, "dog")
        repo.label(kept.path, "dog")
        repo.semanticOutcome = listOf(similar) to SemanticSearchStatus.Available
        assertTrue(repo.trash(tagged) && repo.trash(similar), "precondition: both are in the trash")

        val vm = vms.own(PhotoSearchViewModel(repo))
        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(kept.path), vm.state.value.hits.map { it.photo.path })
    }

    @Test
    fun aRetryIsNotASecondSearchBesideOneAlreadyRunning() = vmTest(dispatcher) { vms ->
        val repo = FailingReads(MockPhotosRepository(listOf(photo("dog-park.jpg"))))
        val held = CompletableDeferred<Unit>()
        repo.nameHolds["dog"] = held
        val vm = vms.own(PhotoSearchViewModel(repo))
        vm.onQueryChanged("dog")
        testScheduler.advanceTimeBy(debounce + 1)
        testScheduler.runCurrent()

        vm.retry()
        vm.retry()
        testScheduler.runCurrent()
        assertEquals(1, repo.nameSearches, "one scan per question at a time")
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun aSearchMayStartInsideTheCallThatAskedForIt() {
        // ⚠⚠ The phone's shape (§8.16 note 2): on K/N `viewModelScope` is `Main.immediate`, so a search
        // started by `retry()` runs inside that call, and one that finishes there publishes before the
        // launch has even returned. `StandardTestDispatcher` defers all of it.
        Dispatchers.resetMain()
        val eager = kotlinx.coroutines.test.UnconfinedTestDispatcher()
        Dispatchers.setMain(eager)
        vmTest(eager) { vms ->
            val repo = MockPhotosRepository(listOf(photo("dog-park.jpg")))
            val vm = vms.own(PhotoSearchViewModel(repo, descriptionSearch = DescriptionSearch.NotBuilt("iPhone")))
            vm.onQueryChanged("dog")
            testScheduler.advanceUntilIdle()
            assertEquals(1, vm.state.value.hits.size)
            vm.retry()
            testScheduler.advanceUntilIdle()
            assertTrue(vm.state.value.settled, "a search that finished inside retry() is still the answer")
            assertFalse(vm.state.value.searching)
            vm.refresh()
            testScheduler.advanceUntilIdle()
            assertEquals(1, vm.state.value.hits.size)
        }
    }

    private fun idleLine(vm: PhotoSearchViewModel): String =
        (vm.state.value.layout.body as PhotoSearchLayout.Body.Idle).text

    @Test
    fun theOpeningQuestionLetsTheIdleLineSayDescriptionSearchWorks() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The maintainer's phone (`ios-on-device-inference.md` §1.8): a ready model, consent given,
        // "cat" finds cats — and the empty bar said "Search by file name.".
        val inner = MockPhotosRepository(listOf(photo("cat.jpg"))).apply {
            semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Available
        }
        val repo = CountingRepository(inner)
        val vm = vms.own(PhotoSearchViewModel(repo, askOnOpen = true))
        assertEquals("Search by file name.", idleLine(vm), "nothing is known before the proxy answers")
        testScheduler.advanceUntilIdle()
        assertEquals(1, repo.semanticSearches, "asked once, on opening")
        // ⚠ A blank text is answered by the repository without asking the proxy at all.
        assertTrue(PhotoSearchViewModel.OPENING_QUERY.isNotBlank())
        assertEquals(listOf(PhotoSearchViewModel.OPENING_QUERY), inner.semanticQueries, "the opening question never reached the repository")
        assertEquals("Search by file name or by what's in a photo.", idleLine(vm))
        assertTrue(vm.state.value.layout.notes.isEmpty())
    }

    @Test
    fun theOpeningQuestionsRefusalKeepsThePlainLine() = vmTest(dispatcher) { vms ->
        for (refusal in listOf("noBackend", "noConsent", "notIndexed", "noTextEmbedding")) {
            val repo = MockPhotosRepository(listOf(photo("cat.jpg"))).apply {
                semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Unavailable(refusal)
            }
            val vm = vms.own(PhotoSearchViewModel(repo, askOnOpen = true))
            testScheduler.advanceUntilIdle()
            assertEquals("Search by file name.", idleLine(vm), refusal)
        }
        // ⚠ An unsuitable phone: its noBackend reads as NotBuiltHere, and the plain line.
        val unsuitable = vms.own(
            PhotoSearchViewModel(
                MockPhotosRepository(emptyList()),
                askOnOpen = true,
                onNoBackend = { SemanticSearchStatus.NotBuiltHere("iPhone") },
            ),
        )
        testScheduler.advanceUntilIdle()
        assertEquals(SemanticSearchStatus.NotBuiltHere("iPhone"), unsuitable.state.value.semanticKnown)
        assertEquals("Search by file name.", idleLine(unsuitable))
    }

    @Test
    fun withoutTheOpeningQuestionTheSeedPromisesNothing() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Android and desktop: the seed is Available, and their production registry has no backend.
        val repo = CountingRepository(MockPhotosRepository(listOf(photo("cat.jpg"))).apply {
            semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Available
        })
        val vm = vms.own(PhotoSearchViewModel(repo))
        testScheduler.advanceUntilIdle()
        assertEquals(0, repo.semanticSearches, "nothing asked before a query")
        assertEquals("Search by file name.", idleLine(vm))
        // ⚠ Learned from the first query, and kept across the return to an empty bar.
        vm.onQueryChanged("cat")
        testScheduler.advanceUntilIdle()
        vm.clear()
        assertEquals("Search by file name or by what's in a photo.", idleLine(vm))
    }

    @Test
    fun aRefusalLearnedFromAQueryTakesThePromiseBack() = vmTest(dispatcher) { vms ->
        val inner = MockPhotosRepository(listOf(photo("cat.jpg"))).apply {
            semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Available
        }
        val vm = vms.own(PhotoSearchViewModel(inner, askOnOpen = true))
        testScheduler.advanceUntilIdle()
        assertEquals("Search by file name or by what's in a photo.", idleLine(vm))
        // Consent withdrawn while the screen is up: the next answer says so, and the bar stops promising.
        inner.semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Unavailable("noConsent")
        vm.onQueryChanged("cat")
        testScheduler.advanceUntilIdle()
        vm.clear()
        assertEquals("Search by file name.", idleLine(vm))
    }

    @Test
    fun aQueryAnsweredFirstOutranksTheOpeningQuestion() = vmTest(dispatcher) { vms ->
        // ⚠ The opening answer arrives after a query's: the query's status is the newer one.
        val gate = CompletableDeferred<Unit>()
        val inner = MockPhotosRepository(listOf(photo("cat.jpg"))).apply {
            semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Unavailable("noConsent")
        }
        val repo = object : app.tenshu.photos.data.PhotosRepository by inner {
            override suspend fun searchSemantic(query: String, limit: Int): Pair<List<Photo>, SemanticSearchStatus> {
                if (query == PhotoSearchViewModel.OPENING_QUERY) {
                    withContext(NonCancellable) { gate.await() }
                    return emptyList<Photo>() to SemanticSearchStatus.Available
                }
                return inner.searchSemantic(query, limit)
            }
        }
        val vm = vms.own(PhotoSearchViewModel(repo, askOnOpen = true))
        vm.onQueryChanged("cat")
        // ⚠ Past the debounce, well inside the opening question's bound — `advanceUntilIdle` would
        // time it out and test nothing.
        testScheduler.advanceTimeBy(PhotoSearchViewModel.SEARCH_DEBOUNCE_MS + 1_000)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.settled, "the query answered while the opening question was held")
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(SemanticSearchStatus.Unavailable("noConsent"), vm.state.value.semanticStatus)
        vm.clear()
        assertEquals("Search by file name.", idleLine(vm))
    }

    @Test
    fun anEarlierRefusalIsNotTheNextQuerysNote() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Consent turned on since "cat" was refused: while "dog" runs, no "switched off" note.
        for (askOnOpen in listOf(false, true)) {
            val inner = MockPhotosRepository(listOf(photo("cat.jpg"), photo("dog.jpg", 1))).apply {
                semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Unavailable("noConsent")
            }
            val vm = vms.own(PhotoSearchViewModel(inner, askOnOpen = askOnOpen))
            vm.onQueryChanged("cat")
            testScheduler.advanceUntilIdle()
            assertEquals(1, vm.state.value.layout.notes.size, "the refusal is this query's note")
            vm.clear()
            inner.semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Available
            vm.onQueryChanged("dog")
            assertTrue(vm.state.value.searching)
            assertTrue(vm.state.value.layout.notes.isEmpty(), "askOnOpen=$askOnOpen: a refusal from before the bar was emptied")
            testScheduler.advanceUntilIdle()
            assertTrue(vm.state.value.layout.notes.isEmpty())
            // ⚠ As before: within one typed question a refusal stays up while a newer query runs.
            inner.semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Unavailable("noConsent")
            vm.onQueryChanged("do")
            testScheduler.advanceUntilIdle()
            vm.onQueryChanged("dog!")
            assertTrue(vm.state.value.searching)
            assertEquals(1, vm.state.value.layout.notes.size, "askOnOpen=$askOnOpen: a note true of the device shook with a keystroke")
        }
    }

    @Test
    fun shownAgainTheOpeningQuestionIsAskedAgain() = vmTest(dispatcher) { vms ->
        // ⚠ Consent switched on in Settings → Models, then back to Search: no query typed.
        val inner = MockPhotosRepository(listOf(photo("cat.jpg"))).apply {
            semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Unavailable("noConsent")
        }
        val vm = vms.own(PhotoSearchViewModel(inner, askOnOpen = true))
        testScheduler.advanceUntilIdle()
        assertEquals("Search by file name.", idleLine(vm))
        inner.semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Available
        vm.screenShown()
        testScheduler.advanceUntilIdle()
        assertEquals("Search by file name or by what's in a photo.", idleLine(vm))
        assertEquals(2, inner.semanticQueries.size)

        // ⚠ Never over a typed bar, never beside one in flight.
        vm.onQueryChanged("cat")
        testScheduler.advanceUntilIdle()
        val asked = inner.semanticQueries.size
        vm.screenShown()
        testScheduler.advanceUntilIdle()
        assertEquals(asked, inner.semanticQueries.size, "asked over a typed bar")
        vm.clear()
        vm.screenShown()
        vm.screenShown()
        testScheduler.advanceUntilIdle()
        assertEquals(asked + 1, inner.semanticQueries.size, "two opening questions at once")

        // Without askOnOpen (Android, desktop) it asks nothing.
        val android = MockPhotosRepository(emptyList())
        val plain = vms.own(PhotoSearchViewModel(android))
        plain.screenShown()
        testScheduler.advanceUntilIdle()
        assertTrue(android.semanticQueries.isEmpty())
    }

    @Test
    fun aQueryCancelsTheOpeningQuestionInFlight() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Two `photosLike` calls must not compete for the text tower's first load.
        val held = CompletableDeferred<Unit>()
        var openingCancelled = false
        val inner = MockPhotosRepository(listOf(photo("cat.jpg"))).apply {
            semanticOutcome = emptyList<Photo>() to SemanticSearchStatus.Available
        }
        val repo = object : app.tenshu.photos.data.PhotosRepository by inner {
            override suspend fun searchSemantic(query: String, limit: Int): Pair<List<Photo>, SemanticSearchStatus> {
                if (query == PhotoSearchViewModel.OPENING_QUERY) {
                    try {
                        held.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        openingCancelled = true
                        throw e
                    }
                }
                return inner.searchSemantic(query, limit)
            }
        }
        val vm = vms.own(PhotoSearchViewModel(repo, askOnOpen = true))
        testScheduler.runCurrent()
        vm.onQueryChanged("cat")
        testScheduler.runCurrent()
        assertTrue(openingCancelled, "the opening question still ran beside the query")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.settled)
    }

    @Test
    fun anOpeningQuestionThatThrowsOrTimesOutKeepsThePlainLine() = vmTest(dispatcher) { vms ->
        val inner = MockPhotosRepository(emptyList())
        val throwing = object : app.tenshu.photos.data.PhotosRepository by inner {
            override suspend fun searchSemantic(query: String, limit: Int): Pair<List<Photo>, SemanticSearchStatus> =
                throw IllegalStateException("the connection dropped")
        }
        val a = vms.own(PhotoSearchViewModel(throwing, askOnOpen = true))
        testScheduler.advanceUntilIdle()
        assertEquals("Search by file name.", idleLine(a))
        assertEquals(null, a.state.value.semanticKnown)

        val hanging = object : app.tenshu.photos.data.PhotosRepository by inner {
            override suspend fun searchSemantic(query: String, limit: Int): Pair<List<Photo>, SemanticSearchStatus> {
                withContext(NonCancellable) { CompletableDeferred<Unit>().await() }
                return emptyList<Photo>() to SemanticSearchStatus.Available
            }
        }
        val b = vms.own(PhotoSearchViewModel(hanging, askOnOpen = true, timeoutMs = 1_000))
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals("Search by file name.", idleLine(b))
        assertEquals(null, b.state.value.semanticKnown)
    }

    @Test
    fun aPlatformWithNoIndexIsNeverAskedOnOpening() = vmTest(dispatcher) { vms ->
        val repo = CountingRepository(MockPhotosRepository(emptyList()))
        val vm = vms.own(PhotoSearchViewModel(repo, descriptionSearch = DescriptionSearch.NotBuilt("iPhone"), askOnOpen = true))
        testScheduler.advanceUntilIdle()
        assertEquals(0, repo.semanticSearches)
        assertEquals("Search by file name.", idleLine(vm))
    }

    /** A repository whose search reads can fail, throw, hang or be held — and which counts them. */
    private class FailingReads(
        private val delegate: MockPhotosRepository,
    ) : app.tenshu.photos.data.PhotosRepository by delegate {
        var nameFails = false
        var nameThrows: Throwable? = null
        var nameHangsOn: CompletableDeferred<Unit>? = null
        val nameHolds = mutableMapOf<String, CompletableDeferred<Unit>>()
        var semanticOutcome: Pair<List<Photo>, SemanticSearchStatus>? = null
        var nameSearches = 0
        var labelSearches = 0
        var semanticSearches = 0

        override suspend fun searchByNameRead(query: String): List<Photo>? {
            nameSearches++
            nameThrows?.let { throw it }
            // ⚠ Ignores cancellation, as a Binder transaction does.
            nameHangsOn?.let { withContext(NonCancellable) { it.await() } }
            nameHolds[query]?.let { withContext(NonCancellable) { it.await() } }
            return if (nameFails) null else delegate.searchByName(query)
        }

        override suspend fun searchByLabelRead(query: String): List<Pair<Photo, String>>? {
            labelSearches++
            return delegate.searchByLabel(query)
        }

        override suspend fun searchSemantic(query: String, limit: Int): Pair<List<Photo>, SemanticSearchStatus> {
            semanticSearches++
            return semanticOutcome ?: delegate.searchSemantic(query, limit)
        }
    }

    /** Counts scans, so "was it debounced?" is a fact rather than an inference. */
    private class CountingRepository(
        private val delegate: MockPhotosRepository,
    ) : app.tenshu.photos.data.PhotosRepository by delegate {
        var nameSearches = 0
            private set
        var semanticSearches = 0
            private set

        // ⚠ The `…Read` twin is what the view model calls — and `by delegate` forwards an interface's
        // DEFAULT methods to the delegate too, so counting `searchByName` here would count nothing.
        override suspend fun searchByNameRead(query: String): List<Photo>? {
            nameSearches++
            return delegate.searchByNameRead(query)
        }

        override suspend fun searchSemantic(
            query: String,
            limit: Int,
        ): Pair<List<Photo>, SemanticSearchStatus> {
            semanticSearches++
            return delegate.searchSemantic(query, limit)
        }
    }
}

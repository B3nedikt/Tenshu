package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.groupPhotosByLabel
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.inference.INDEX_MARKER_LABEL
import org.tenshu.inference.InferenceCapability
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The Things section of the Collections tab (`photos-app.md` S4 / §21) — collections derived from
 * what a model said about a photograph.
 *
 * ⚠⚠ **Every one of these passes today against zero real labels.** The production proxy binds
 * `InferenceBackendRegistry.Empty` (`inference-service.md` §17.4), so no `MediaLabel` row exists on
 * any device and the section is absent everywhere. These tests are what makes that a *waiting*
 * consumer rather than an unverified one: the day IN-3b lands a backend, the behaviour below is
 * what the user gets, and none of it has to be written then.
 */
class LabelCollectionsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(id: String) = Photo("/$id.jpg", 1_000L, isVideo = false, contentId = id)

    private val library = listOf(photo("a"), photo("b"), photo("c"))

    private fun label(
        id: String,
        label: String,
        confidence: Int = 900,
        capability: String = InferenceCapability.CONTENT_TAGGING.id,
    ) = PhotoLabel("/$id.jpg", label, confidence, capability)

    @Test
    fun aLabelOnEnoughPhotosBecomesACollection() {
        val groups = groupPhotosByLabel(
            library,
            listOf(label("a", "dog"), label("b", "dog")),
        )
        assertEquals(listOf("Dog"), groups.map { it.label })
        assertEquals(2, groups.single().photos.size)
    }

    @Test
    fun aLabelOnOnePhotoIsNotACollection() {
        // ⚠ One photograph is a search result, not a collection — and the tab already has search.
        // Without this floor an open-ended vocabulary buries the sections a user can actually use.
        assertTrue(groupPhotosByLabel(library, listOf(label("a", "dog"))).isEmpty())
    }

    @Test
    fun theIndexSentinelNeverBecomesACollection() {
        // ⚠⚠ The pass writes this for EVERY file it looks at, opinion or not. Un-dropped, the
        // largest "collection" a user owns is one called `__indexed` holding their whole library.
        val groups = groupPhotosByLabel(
            library,
            listOf(
                label("a", INDEX_MARKER_LABEL),
                label("b", INDEX_MARKER_LABEL),
                label("c", INDEX_MARKER_LABEL),
            ),
        )
        assertTrue(groups.isEmpty(), "the sentinel is machinery, not a label: $groups")
    }

    @Test
    fun aLowScoringLabelIsStillACollection_becausePhotosOwnsNoThreshold() {
        // ⚠⚠ The scores below are NOT weak — they are normal. SigLIP scores
        // `sigmoid(111.00·cos − 12.89)`, and over a real library no (image, label) pair reaches
        // p = 0.5 at all: the highest observed is ≈ 0.25 and usable cut-offs sit near 0.05–0.15
        // (`models.md` §3.7.2). A floor that "sounds cautious" here would label NOTHING, and the
        // cut-off belongs to whoever calibrated the vocabulary (IN-D9) rather than to this grid.
        val groups = groupPhotosByLabel(
            library,
            listOf(label("a", "dog", confidence = 100), label("b", "dog", confidence = 200)),
        )
        assertEquals(listOf("Dog"), groups.map { it.label })
    }

    @Test
    fun aCallerWithACalibratedNumberCanStillPassOne() {
        // ⚠ The parameter stays for exactly this — it is the default that is 0, not the capability.
        val groups = groupPhotosByLabel(
            library,
            listOf(label("a", "dog", confidence = 100), label("b", "dog", confidence = 200)),
            minConfidencePerMille = 250,
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun oneLabelInTwoCasingsIsOneCollection() {
        // ⚠ A tagger that emits both `dog` and `Dog` has said one thing twice.
        val groups = groupPhotosByLabel(library, listOf(label("a", "dog"), label("b", "Dog")))
        assertEquals(listOf("Dog"), groups.map { it.label })
        assertEquals(2, groups.single().photos.size)
    }

    @Test
    fun theCoverIsThePhotoTheModelWasSurestAbout() {
        // ⚠ `photos.first()` is the card's cover, so ordering by confidence is what makes a "Dog"
        // card look like a dog rather than like whatever was indexed first.
        val groups = groupPhotosByLabel(
            library,
            listOf(label("a", "dog", confidence = 600), label("b", "dog", confidence = 990)),
        )
        assertEquals("/b.jpg", groups.single().photos.first().path)
    }

    @Test
    fun theSameFileLabelledTwiceCountsOnce() {
        // ⚠ One file, two capabilities, same word — still one photograph in the collection.
        val groups = groupPhotosByLabel(
            library,
            listOf(label("a", "dog", 700), label("a", "dog", 900), label("b", "dog")),
        )
        assertEquals(2, groups.single().photos.size)
    }

    @Test
    fun aLabelForAPhotoTheTabCannotSeeIsDropped() {
        // ⚠ A label whose file is trashed or otherwise absent from the shared-index read must not
        // inflate a count the user can then not reconcile by opening the card.
        val groups = groupPhotosByLabel(
            library,
            listOf(label("a", "dog"), label("b", "dog"), label("gone", "dog")),
        )
        assertEquals(2, groups.single().photos.size)
    }

    @Test
    fun collectionsAreOrderedByHowManyPhotosTheyHold() {
        val groups = groupPhotosByLabel(
            library,
            listOf(
                label("a", "dog"), label("b", "dog"), label("c", "dog"),
                label("a", "beach"), label("b", "beach"),
            ),
        )
        assertEquals(listOf("Dog", "Beach"), groups.map { it.label })
    }

    @Test
    fun theThingsSectionIsAbsentOnADeviceWithNoBackend() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Every device today. The tab must read as complete without the section rather than
        // showing an empty or greyed-out row that promises a feature nobody can turn on.
        val vm = vms.own(CollectionsViewModel(MockPhotosRepository(photos = library), yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.things.isEmpty())
        assertTrue(
            vm.state.value.placesAndTimes.isNotEmpty(),
            "the sections that need no model still work",
        )
    }

    @Test
    fun labelsBecomeCollectionsOnTheTabWhenTheyExist() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository(photos = library)
        repo.label("/a.jpg", "dog")
        repo.label("/b.jpg", "dog")
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        val things = vm.state.value.things
        assertEquals(listOf("Dog"), things.map { it.title })
        assertEquals("2 photos", things.single().subtitle)
        assertEquals("label:dog", things.single().id)
        // ⚠ Things lead: it is the section a user cannot reach any other way.
        assertEquals("label:dog", vm.state.value.smartAlbums.first().id)
    }

    @Test
    fun aLabelCollectionCarriesItsPhotos_soOpeningOneCostsNothing() = vmTest(dispatcher) { vms ->
        // ⚠ The same one-pass contract every other derived section holds to (PH-3): the photos
        // came from a read that already happened, so opening the card must not walk the realm.
        val repo = MockPhotosRepository(photos = library)
        repo.label("/a.jpg", "dog")
        repo.label("/b.jpg", "dog")
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.things.all { it.photos.isNotEmpty() })
    }
}

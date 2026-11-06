package app.tenshu.photos

import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.peopleFrom
import app.tenshu.photos.ui.screens.albumdetail.companionsFor
import app.tenshu.photos.ui.screens.albumdetail.photosWithBoth
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "With…" chip on a person's page (IN-6 F6b): narrow to photographs of BOTH people, by path.
 *
 * ⚠⚠ Built through [peopleFrom] where it matters, so the rule "a named person is EXACTLY their
 * labelled photographs" is what the intersection is tested against — not a hand-made list that
 * could quietly include an inferred photograph.
 */
class PeopleTogetherTest {

    private fun photo(path: String) = Photo(path, 0L, isVideo = false, contentId = path)

    private fun person(id: String, name: String, vararg paths: String, aliases: List<String> = emptyList()) =
        SmartAlbum(id, name, "", paths.map(::photo), alternateIds = aliases)

    private fun label(path: String, name: String) =
        PhotoLabel(path = path, label = name, confidencePerMille = 1000, capability = InferenceCapability.FACE_CLUSTERING.id)

    @Test
    fun twoNamedPeopleNarrowToTheirSharedLabelledPhotographs() {
        val people = peopleFrom(
            faceGroups = emptyList(),
            faceLabels = listOf(
                label("/a.jpg", "Anna"), label("/b.jpg", "Anna"), label("/c.jpg", "Anna"),
                label("/b.jpg", "Bob"), label("/c.jpg", "Bob"), label("/d.jpg", "Bob"),
            ),
        )
        val anna = people.single { it.name == "Anna" }.filePaths.map(::photo)
        val bob = people.single { it.name == "Bob" }.filePaths.map(::photo)

        assertEquals(listOf("/b.jpg", "/c.jpg"), photosWithBoth(anna, bob).map { it.path })
        // ⚠ Symmetric in membership; the order follows the page being narrowed.
        assertEquals(listOf("/b.jpg", "/c.jpg"), photosWithBoth(bob, anna).map { it.path })
    }

    /**
     * ⚠⚠ The unlabelled photograph this device clusters beside Anna is NOT hers (peopleFrom's rule),
     * so it must not appear in "Anna with the unnamed group" either — only labelled paths do.
     */
    @Test
    fun aNamedPersonWithAnUnnamedGroupIsTheirPathsInCommon_andInfersNothing() {
        val people = peopleFrom(
            faceGroups = listOf(FaceGroup("g1", listOf("/b.jpg", "/x.jpg"))),
            faceLabels = listOf(label("/a.jpg", "Anna"), label("/b.jpg", "Anna")),
        )
        val anna = people.single { it.name == "Anna" }.filePaths.map(::photo)
        val unnamed = people.single { it.name.isEmpty() }.filePaths.map(::photo)

        assertEquals(listOf("/b.jpg"), photosWithBoth(anna, unnamed).map { it.path })
        assertTrue("/x.jpg" !in anna.map { it.path }, "nothing inferred onto the named person")
    }

    @Test
    fun peopleNeverPhotographedTogetherIntersectToNothing() {
        val anna = person("person:name:Anna", "Anna", "/a.jpg")
        val bob = person("person:name:Bob", "Bob", "/b.jpg")
        assertEquals(emptyList(), photosWithBoth(anna.photos, bob.photos))
    }

    @Test
    fun aPersonIsNeverOfferedThemselves_evenUnderAnAlias() {
        val anna = person("person:name:Anna", "Anna", "/a.jpg", aliases = listOf("person:g1"))
        val bob = person("person:name:Bob", "Bob", "/b.jpg")
        val unnamed = person("person:g2", "", "/c.jpg")
        val people = listOf(anna, bob, unnamed)

        assertEquals(listOf(bob, unnamed), companionsFor(anna, people))
        // The route may still hold the cluster id Anna was named from.
        val annaByAlias = person("person:g1", "", "/a.jpg")
        assertEquals(listOf(bob, unnamed), companionsFor(annaByAlias, people))
    }
}

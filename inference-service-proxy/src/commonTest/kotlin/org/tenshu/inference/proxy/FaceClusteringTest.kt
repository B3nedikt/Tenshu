package org.tenshu.inference.proxy

import org.tenshu.inference.ModelProvenance
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Face clustering (IN-6 F1, `photos-people-and-pets.md` §3).
 *
 * ⚠⚠ Vectors are built **by hand** here rather than taken from `FakeInferenceBackend`, and that is
 * the point: the fake emits orthogonal one-hot vectors, so every assertion about a *threshold* would
 * be measuring the fixture. Hand-made vectors at known angles are the only way to pin what the
 * threshold does.
 */
class FaceClusteringTest {

    private val stamp = ModelProvenance.Fake

    /** A unit vector at [degrees] from the x-axis in the first two dimensions. */
    private fun faceAt(degrees: Double, dimensions: Int = 4): FloatArray {
        val radians = degrees * kotlin.math.PI / 180.0
        return FloatArray(dimensions) { index ->
            when (index) {
                0 -> kotlin.math.cos(radians).toFloat()
                1 -> kotlin.math.sin(radians).toFloat()
                else -> 0f
            }
        }
    }

    private fun row(path: String, vararg vectors: FloatArray) = FaceRow(
        filePath = path,
        faces = vectors.map { StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), it) },
        producedBy = stamp,
    )

    private fun calibrated(cosine: Float, minFaces: Int = 2) =
        FaceClustering.Calibrated(FaceSimilarityThreshold(cosine), minFaces)

    /**
     * ⚠⚠ **The production value emits nothing.** `LabelVocabulary.Empty`'s posture: until the number
     * is measured against a real library, the honest output is no people at all — not a guess that
     * teaches the user the feature is unreliable.
     */
    @Test
    fun uncalibratedClusteringProducesNoPeopleAtAll() {
        val rows = listOf(row("/a.jpg", faceAt(0.0)), row("/b.jpg", faceAt(0.0)))

        assertEquals(emptyList(), clusterFaces(rows, FaceClustering.Uncalibrated))
    }

    @Test
    fun facesOfOnePersonAcrossPhotographsBecomeOneCluster() {
        val rows = listOf(
            row("/a.jpg", faceAt(0.0)),
            row("/b.jpg", faceAt(4.0)),
            row("/c.jpg", faceAt(8.0)),
        )

        val people = clusterFaces(rows, calibrated(cosine = 0.9f))

        assertEquals(1, people.size)
        assertEquals(listOf("/a.jpg", "/b.jpg", "/c.jpg"), people.single().filePaths)
    }

    @Test
    fun twoPeopleStayApart() {
        val rows = listOf(
            row("/a.jpg", faceAt(0.0)),
            row("/b.jpg", faceAt(2.0)),
            row("/c.jpg", faceAt(90.0)),
            row("/d.jpg", faceAt(92.0)),
        )

        val people = clusterFaces(rows, calibrated(cosine = 0.9f))

        assertEquals(2, people.size)
        assertEquals(listOf("/a.jpg", "/b.jpg"), people[0].filePaths)
        assertEquals(listOf("/c.jpg", "/d.jpg"), people[1].filePaths)
    }

    /**
     * ⚠⚠ The threshold is the whole feature, and both ways of being wrong are visible to the user:
     * too tight splits one person into several rows, too loose merges two. These two cases are the
     * same library at two thresholds.
     */
    @Test
    fun theThresholdDecidesWhetherOnePersonIsOneRowOrFour() {
        val rows = listOf(
            row("/a.jpg", faceAt(0.0)),
            row("/b.jpg", faceAt(10.0)),
            row("/c.jpg", faceAt(20.0)),
            row("/d.jpg", faceAt(30.0)),
        )

        // cos 10° ≈ 0.985: each step is within reach of the last, so the run collapses to one person.
        assertEquals(1, clusterFaces(rows, calibrated(cosine = 0.95f)).size)

        // Tighter than any pair: every face is its own person, and `minFaces = 2` then hides them all.
        assertEquals(0, clusterFaces(rows, calibrated(cosine = 0.999f)).size)
        assertEquals(4, clusterFaces(rows, calibrated(cosine = 0.999f, minFaces = 1)).size)
    }

    /** ⚠ A person seen once is noise on the grid — Things makes the same call at `minPhotos = 2`. */
    @Test
    fun aPersonSeenOnceDoesNotEarnARow() {
        val rows = listOf(
            row("/a.jpg", faceAt(0.0)),
            row("/b.jpg", faceAt(1.0)),
            row("/stranger.jpg", faceAt(90.0)),
        )

        val people = clusterFaces(rows, calibrated(cosine = 0.9f))

        assertEquals(1, people.size)
        assertTrue("/stranger.jpg" !in people.single().filePaths)
    }

    /** ⚠ Two faces in one photograph are two members of possibly-different people, but one photo. */
    @Test
    fun twoFacesInOnePhotographAreTwoFacesAndOnePhoto() {
        val rows = listOf(
            row("/together.jpg", faceAt(0.0), faceAt(90.0)),
            row("/anna.jpg", faceAt(1.0)),
            row("/bob.jpg", faceAt(91.0)),
        )

        val people = clusterFaces(rows, calibrated(cosine = 0.9f))

        assertEquals(2, people.size)
        people.forEach { person ->
            assertEquals(2, person.size, "each person has two faces")
            assertEquals(2, person.filePaths.size, "and they came from two photographs")
            assertTrue("/together.jpg" in person.filePaths)
        }
    }

    /**
     * ⚠⚠ The algorithm is order-dependent by construction, so the **input order must not be**. A grid
     * that reshuffled itself between openings would look broken even though nothing changed.
     */
    @Test
    fun theSameStoreClustersTheSameWayTwice() {
        val rows = listOf(
            row("/c.jpg", faceAt(8.0)),
            row("/a.jpg", faceAt(0.0)),
            row("/b.jpg", faceAt(4.0)),
        )

        val first = clusterFaces(rows, calibrated(cosine = 0.9f))
        val second = clusterFaces(rows.reversed(), calibrated(cosine = 0.9f))

        assertEquals(first, second)
        assertEquals(listOf("/a.jpg", "/b.jpg", "/c.jpg"), first.single().filePaths)
    }

    /**
     * ⚠ Largest first — the people a user photographs most are the ones they want to name.
     *
     * ⚠⚠ The **smaller** person is discovered first here, deliberately. Rows are visited in path
     * order, so a fixture whose largest cluster is also created first passes whether or not anything
     * sorts — which is exactly what an earlier version of this test did.
     */
    @Test
    fun thePeopleSeenMostComeFirst() {
        val rows = listOf(
            row("/a1.jpg", faceAt(0.0)),   // person A, seen twice — created first
            row("/b1.jpg", faceAt(90.0)),  // person B, seen three times
            row("/b2.jpg", faceAt(91.0)),
            row("/b3.jpg", faceAt(92.0)),
            row("/z1.jpg", faceAt(1.0)),   // … and A's second photograph sorts last
        )

        val people = clusterFaces(rows, calibrated(cosine = 0.9f))

        assertEquals(listOf(3, 2), people.map { it.size })
        assertEquals(listOf("/b1.jpg", "/b2.jpg", "/b3.jpg"), people.first().filePaths)
    }

    /** ⚠ A photograph with no faces contributes nothing rather than an empty person. */
    @Test
    fun aPhotographWithNoFacesContributesNothing() {
        val rows = listOf(
            row("/landscape.jpg"),
            row("/a.jpg", faceAt(0.0)),
            row("/b.jpg", faceAt(1.0)),
        )

        val people = clusterFaces(rows, calibrated(cosine = 0.9f))

        assertEquals(1, people.size)
        assertTrue("/landscape.jpg" !in people.single().filePaths)
    }

    /** ⚠ A threshold outside [-1, 1] is not a cautious choice, it is a mistake — refuse it loudly. */
    @Test
    fun aThresholdOutsideTheCosineRangeIsRefused() {
        assertFailsWith<IllegalArgumentException> { FaceSimilarityThreshold(1.5f) }
        assertFailsWith<IllegalArgumentException> { FaceSimilarityThreshold(-2f) }
    }

    /** ⚠ A zero vector cannot be normalised; it must not poison a cluster with NaNs. */
    @Test
    fun aZeroVectorDoesNotProduceNaNs() {
        val rows = listOf(
            row("/zero.jpg", FloatArray(4)),
            row("/a.jpg", faceAt(0.0)),
            row("/b.jpg", faceAt(1.0)),
        )

        val people = clusterFaces(rows, calibrated(cosine = 0.9f))

        assertEquals(1, people.size)
        assertTrue("/zero.jpg" !in people.single().filePaths)
    }

    /**
     * ⚠⚠ A zero **centroid** is the reachable case, and the one the guard is really for. Two
     * opposite faces admitted by a permissive threshold average to the origin; without the guard
     * that centroid becomes NaN, every later comparison against it is false, and the cluster
     * silently stops accepting anyone — a person who splits in two for no visible reason.
     */
    @Test
    fun aClusterWhoseCentroidCancelsToZeroKeepsAccepting() {
        // ⚠⚠ Built by hand, NOT with `faceAt(180.0)`: sin(180°) is 1.2e-16 rather than 0, so the
        // centroid lands a hair off the origin, the guard never fires, and the mutation that removes
        // it survives. A fixture one rounding error away from the state under test cannot test it.
        val rows = listOf(
            row("/1.jpg", floatArrayOf(1f, 0f, 0f, 0f)),
            row("/2.jpg", floatArrayOf(-1f, 0f, 0f, 0f)),
            row("/3.jpg", floatArrayOf(0f, 1f, 0f, 0f)),
        )

        // ⚠ A threshold of -1 admits everything — not a realistic setting, but the only one that can
        // drive a centroid to the origin, which is what makes the guard observable at all.
        val people = clusterFaces(rows, calibrated(cosine = -1f, minFaces = 1))

        assertEquals(1, people.size, "every face is within reach of the others at this threshold")
        assertEquals(3, people.single().size)
    }

    /** A sanity check on the fixture itself: [faceAt] really does produce unit vectors. */
    @Test
    fun theFixtureProducesUnitVectors() {
        listOf(0.0, 10.0, 90.0, 180.0).forEach { degrees ->
            val length = sqrt(faceAt(degrees).fold(0f) { sum, v -> sum + v * v })
            assertTrue(kotlin.math.abs(length - 1f) < 1e-5f, "at $degrees the length was $length")
        }
    }
}

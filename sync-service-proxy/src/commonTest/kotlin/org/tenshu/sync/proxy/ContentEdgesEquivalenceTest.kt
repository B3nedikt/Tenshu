package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.content.ContentDeclarations
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.common.content.deriveContentId
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.util.sha256
import org.tenshu.core.common.util.toHexLower
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.THUMBNAIL_CONTENT_NAMESPACE
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.ThumbnailMotion
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.ThumbnailXXLarge
import org.tenshu.core.health.ContentId
import org.tenshu.core.health.Streams
import org.tenshu.core.health.Workout
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.health.common.healthServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ CR-2 B3a (`content-plane-cr2-quorumless.md` §10.11): *which content a row references* is now a
 * schema declaration, and these tests pin that the declaration answers **exactly what the code
 * answered before it existed**.
 *
 * ⚠⚠ The thumbnail vectors are the load-bearing half. A rung's content id feeds `manifestContentRef`,
 * so a declaration that derives even one byte differently re-addresses every stored thumbnail of that
 * class and strands the existing manifests. The expected hex below was computed **outside Kotlin**
 * (Python's `hashlib.sha256("thumbnail:cid-golden:<class>")`), from the form
 * `FileBytesPropertyWriter.derivedThumbnailContentId` minted before B3a — so it cannot drift with the
 * code it checks.
 */
class ContentEdgesEquivalenceTest {

    private val goldenThumbnailIds = mapOf(
        GetStoredThumbnail.CLASS_LIST to "b8ee4d210271cd6dd126c0f8c6bd3531cd86a7f441987ab60377ee9dc2eb8d0a",
        GetStoredThumbnail.CLASS_LARGE to "5c4a6500c0a546d55621acc7e838fe0c68d75fe282b65156de6bf113663fcfdb",
        GetStoredThumbnail.CLASS_XLARGE to "c9cc7568cb2e8ac7588db16c659b0a77bf3bcd9f28dad831eca086987932579e",
        GetStoredThumbnail.CLASS_XXLARGE to "c51e0f4b7b413ec1fd831e480158c0525f00a46741ef007f49ecbcda3ec525fe",
        GetStoredThumbnail.CLASS_MOTION to "2de557a333868fb51ac223398769f798996a3953c985e11b661476c5c804eef8",
    )

    private val rungs = mapOf(
        File.Thumbnail.id to GetStoredThumbnail.CLASS_LIST,
        File.ThumbnailLarge.id to GetStoredThumbnail.CLASS_LARGE,
        File.ThumbnailXLarge.id to GetStoredThumbnail.CLASS_XLARGE,
        File.ThumbnailXXLarge.id to GetStoredThumbnail.CLASS_XXLARGE,
        File.ThumbnailMotion.id to GetStoredThumbnail.CLASS_MOTION,
    )

    private fun proxy() = TestProxy(
        schemaRegistry = createSyntheticTestRegistry().apply {
            registerConfig(fileServiceCommonModule)
            registerConfig(healthServiceCommonModule)
        },
    )

    private fun TestProxy.registry() = executionContext.schemaProvider as SchemaRegistry

    /** The stored row, read the way every caller holding only a `DataId` must hand it over. */
    private fun TestProxy.storedEdges(dataId: DataId): List<ContentEdge>? {
        val row = assertNotNull(executionContext.database.getData(dataId, asEntity = true), "the row was saved")
        return ContentDeclarations.of(registry()).edgesOf(row)
    }

    private fun TestProxy.saveFile(path: String, contentId: String?): DataId {
        val composite = File.create(tContext, path)
        val row = if (contentId == null) composite else Entity.create<File>(context = tContext, composite = composite) {
            set(File.ContentId, contentId)
        }
        executionContext.saveData(row)
        return assertNotNull(executionContext.getDataId(composite))
    }

    private fun TestProxy.saveWorkout(workoutId: String, contentId: String?): DataId {
        val composite = Workout.create(
            context = tContext,
            workoutId = workoutId,
            activityType = "run",
            source = "test",
            start = UnixInstantDto(1_000L),
            end = UnixInstantDto(2_000L),
            durationSec = 1_000L,
        )
        val row = if (contentId == null) composite else Entity.create<Workout>(context = tContext, composite = composite) {
            set(Workout.ContentId, contentId)
        }
        executionContext.saveData(row)
        return assertNotNull(executionContext.getDataId(composite))
    }

    @Test
    fun theDerivationReproducesThePreB3aThumbnailIds_byteForByte() {
        for ((thumbnailClass, golden) in goldenThumbnailIds) {
            assertEquals(golden, deriveContentId(THUMBNAIL_CONTENT_NAMESPACE, "cid-golden", thumbnailClass), thumbnailClass)
            assertEquals(golden, FileBytesPropertyWriter.derivedThumbnailContentId("cid-golden", thumbnailClass), thumbnailClass)
            // ⚠ And the literal form, so a change to `deriveContentId`'s shape fails here too.
            assertEquals(golden, sha256("thumbnail:cid-golden:$thumbnailClass".encodeToByteArray()).toHexLower())
        }
    }

    @Test
    fun everyRegisteredContentBearingPropertyDeclaresItsDerivation() {
        // ⚠⚠ An OnDemand property with no `ContentDerivation` makes its whole type answer "cannot say",
        // which the walk counts as skipped — the type's content would go undeclared. This is the guard
        // for the next content-bearing property somebody adds.
        val declarations = ContentDeclarations.of(proxy().registry())
        val undeclared = declarations.byReceiver.flatMap { (receiver, properties) ->
            properties.withIndex().filter { it.value == null }
                .map { "$receiver -> ${declarations.onDemandByReceiver.getValue(receiver)[it.index]}" }
        }
        assertTrue(undeclared.isEmpty(), "OnDemand properties without a ContentDerivation: $undeclared")
        assertEquals(
            setOf(File.Data.id) + rungs.keys,
            declarations.byReceiver.getValue(File.ID).map { it!!.propertyId }.toSet(),
        )
        assertEquals(listOf(Workout.Streams.id), declarations.byReceiver.getValue(Workout.ID).map { it!!.propertyId })
    }

    @Test
    fun aFileRow_namesItsContentAndFiveDerivedRungs_asThePortDidBeforeB3a() = runTest {
        val proxy = proxy()
        val dataId = proxy.saveFile("/photo.jpg", contentId = "cid-golden")

        val edges = assertNotNull(proxy.storedEdges(dataId), "File declares, so the answer is never 'cannot say'")
        val byProperty = edges.associateBy { it.propertyId }
        assertEquals(6, byProperty.size, "the complete set, one per content-bearing property: $edges")

        val data = byProperty.getValue(File.Data.id)
        assertEquals("cid-golden", data.contentId)
        assertEquals(false, data.regenerable, "the primary is the one thing that cannot be rebuilt")
        for ((propertyId, thumbnailClass) in rungs) {
            val rung = byProperty.getValue(propertyId)
            assertEquals(goldenThumbnailIds.getValue(thumbnailClass), rung.contentId, propertyId)
            assertTrue(rung.regenerable, "$propertyId is minted again on view, never revived")
        }

        // ⚠ And the content emit's port, which now goes through the same declaration — the id a blob
        // is uploaded under and the edge a push names must be the same string.
        val port = FileBytesPropertyWriter(InMemoryFileDatabase())
        for (edge in edges) {
            assertEquals(edge.contentId, port.resolveContentId(proxy.executionContext, dataId, edge.propertyId), edge.propertyId)
        }
    }

    @Test
    fun aFileWithNoContentId_namesNoneForAllSix_neverCannotSay() = runTest {
        val proxy = proxy()
        val dataId = proxy.saveFile("/notes.txt", contentId = null)

        val edges = assertNotNull(proxy.storedEdges(dataId))
        assertEquals(6, edges.size, "removals are explicit: every property answers, `none` included")
        assertTrue(edges.all { it.contentId == null }, "got $edges")
        val port = FileBytesPropertyWriter(InMemoryFileDatabase())
        for (edge in edges) assertEquals(null, port.resolveContentId(proxy.executionContext, dataId, edge.propertyId))
    }

    @Test
    fun aWorkoutRow_namesItsStreams_andASummaryOnlyRowNamesNone() = runTest {
        val proxy = proxy()
        val withStreams = proxy.saveWorkout("w-1", contentId = "cid-workout")
        val summaryOnly = proxy.saveWorkout("w-2", contentId = null)

        assertEquals(
            listOf(ContentEdge(Workout.Streams.id, "cid-workout", regenerable = false)),
            proxy.storedEdges(withStreams),
        )
        // ⚠ The B2 audit's W8 shape: a bare composite carries no property map, so it names `none` —
        // what such a save does to the stored row. Whether that wipe is wanted is audit Q5.
        assertEquals(
            listOf(ContentEdge(Workout.Streams.id, null, regenerable = false)),
            proxy.storedEdges(summaryOnly),
        )
    }

    @Test
    fun theAnswerComesFromTheRowHandedOver_notFromTheStore() {
        // ⚠⚠ The B2 audit's §2.1: blocks are built later than the save, so the edges must come from the
        // payload in hand. An entity that was never saved still answers from its own property map.
        val proxy = proxy()
        val unsaved = Entity.create<File>(context = proxy.tContext, composite = File.create(proxy.tContext, "/new.jpg")) {
            set(File.ContentId, "cid-in-hand")
        }

        val edges = assertNotNull(ContentDeclarations.of(proxy.registry()).edgesOf(unsaved))

        assertEquals("cid-in-hand", edges.single { it.propertyId == File.Data.id }.contentId)
    }
}

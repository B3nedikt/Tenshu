package app.tenshu.timeline.data

import app.tenshu.takeout.StreamingTakeoutArchive
import app.tenshu.takeout.TakeoutArchive
import app.tenshu.takeout.zip.ByteArrayZipSource
import app.tenshu.takeout.zip.ZipArchive
import app.tenshu.takeout.zip.jvmInflate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.place.proxy.placeServiceProxyImplementationFactory
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.proxy.initTimelineService
import org.tenshu.timeline.proxy.timelineServiceProxyImplementationFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole Takeout **archive** in, timeline rows out (B1) — over a real in-process proxy, from a zip
 * `java.util.zip` wrote.
 *
 * ⚠⚠ **This is the seam B1 exists to close.** Before it, `FileTimelineImporter` had exactly one
 * production caller, which opened a single-file dialog: a user with the export Google actually
 * hands out — one zip, and for the legacy vintage one JSON *per month* — had to unzip it and pick
 * the files by hand, or on a phone could not import at all.
 *
 * ⚠ On desktop rather than in `commonTest` so it runs against a real DEFLATE stream. Hand-built
 * stored entries prove the reader's shapes (`:takeout-import`'s own tests); only a real archive
 * proves the whole path.
 */
class TakeoutArchiveImportTest {

    /** One monthly file of the legacy vintage — a visit at a named place. */
    private fun monthlyExport(place: String, at: String) = """
        {
          "timelineObjects": [
            {
              "placeVisit": {
                "location": {
                  "latitudeE7": 525200000,
                  "longitudeE7": 134050000,
                  "placeId": "g-$place",
                  "name": "$place"
                },
                "duration": {
                  "startTimestamp": "${at}T09:00:00Z",
                  "endTimestamp": "${at}T10:00:00Z"
                }
              }
            }
          ]
        }
    """.trimIndent()

    private fun takeoutZip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private suspend fun import(scope: CoroutineScope, zip: ByteArray): Pair<TakeoutImportResult, TenshuClient> {
        val proxy = stackProxy(scope)
        val client = clientOf(proxy)
        val archive = ZipArchive.open(ByteArrayZipSource(zip), jvmInflate)!!.asTakeoutArchive()
        return FileTimelineImporter(client, proxy.tContext).importArchive(archive) to client
    }

    @Test
    fun everyMonthlyExportInTheArchiveIsImported_inOnePick() = runTest {
        val zip = takeoutZip(
            mapOf(
                "Takeout/archive_browser.html" to "<html></html>",
                "Takeout/Location History (Timeline)/Semantic Location History/2021/2021_APRIL.json"
                    to monthlyExport("Blue Bottle", "2021-04-07"),
                "Takeout/Location History (Timeline)/Semantic Location History/2021/2021_MAY.json"
                    to monthlyExport("Some Bakery", "2021-05-09"),
            ),
        )

        val (result, client) = import(backgroundScope, zip)

        // ⚠ Two files, one pick. Two places + two visit moments.
        assertEquals(2, result.timelineFiles)
        assertEquals(4, result.imported)
        val places = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe")).places().first().map { it.name }
        assertTrue(places.containsAll(listOf("Blue Bottle", "Some Bakery")), places.toString())
    }

    @Test
    fun theSameArchiveImportsIdenticallyWhenItIsStreamed() = runTest {
        // ⚠⚠ The seekable reader and the stream must agree, because Android can only use the
        // stream: a `content://` cannot seek, and a ZIP's index is at the end of the file. If these
        // two ever diverge, the phone quietly imports something different from the desktop.
        val zip = takeoutZip(
            mapOf(
                "Takeout/archive_browser.html" to "<html></html>",
                "Takeout/Standortverlauf/2021_APRIL.json" to monthlyExport("Blue Bottle", "2021-04-07"),
                "Takeout/Google Photos/IMG_1.jpg" to "not really a jpeg",
            ),
        )
        val proxy = stackProxy(backgroundScope)
        val client = clientOf(proxy)

        val streamed = FileTimelineImporter(client, proxy.tContext).importArchive(
            StreamingTakeoutArchive { ByteArrayInputStream(zip) },
        )

        assertEquals(1, streamed.timelineFiles)
        assertEquals(2, streamed.imported)
        assertEquals(3, streamed.total)
        // The photo is understood (and never read); the browser page is not.
        assertEquals(2, streamed.understood)
        assertEquals(listOf("Takeout/archive_browser.html"), streamed.unmatched)
    }

    @Test
    fun aStreamedArchiveNeverReadsAPhotosBytes() = runTest {
        val zip = takeoutZip(
            mapOf(
                "Takeout/Google Photos/IMG_1.jpg" to "x".repeat(50_000),
                "Takeout/Timeline.json" to monthlyExport("Blue Bottle", "2021-04-07"),
            ),
        )
        var bytesDelivered = 0L
        val archive = TakeoutArchive { wanted, consume ->
            StreamingTakeoutArchive { ByteArrayInputStream(zip) }.walk(wanted) { name, bytes ->
                bytesDelivered += bytes?.size ?: 0
                consume(name, bytes)
            }
        }
        val proxy = stackProxy(backgroundScope)

        FileTimelineImporter(clientOf(proxy), proxy.tContext).importArchive(archive)

        // ⚠ The 50 KB photo is decided by its name and never inflated — the property that makes a
        // 40 GB export importable on a phone.
        assertTrue(bytesDelivered < 10_000, "read $bytesDelivered bytes; the photo alone is 50 000")
    }

    @Test
    fun aGermanExportImportsJustTheSame() = runTest {
        // ⚠⚠ TB-D2's whole point. Matching on the folder name would import nothing here — and
        // report success, which is the one failure shape a user cannot diagnose.
        val zip = takeoutZip(
            mapOf(
                "Takeout/Standortverlauf/Semantischer Standortverlauf/2021/2021_APRIL.json"
                    to monthlyExport("Blue Bottle", "2021-04-07"),
            ),
        )

        val (result, _) = import(backgroundScope, zip)

        assertEquals(1, result.timelineFiles)
        assertEquals(2, result.imported)
    }

    @Test
    fun whatWasNotUnderstoodIsCounted_andNamed() = runTest {
        val zip = takeoutZip(
            mapOf(
                "Takeout/Chrome/History.json" to "{\"Browser History\":[]}",
                "Takeout/YouTube/watch-history.html" to "<html></html>",
                "Takeout/Timeline.json" to "{\"semanticSegments\":[]}",
            ),
        )

        val (result, _) = import(backgroundScope, zip)

        assertEquals(3, result.total)
        assertEquals(1, result.understood)
        // ⚠⚠ "Understood 1 of 3" is a report a user can act on; "imported 0 records" is not.
        assertTrue(result.unmatched.contains("Takeout/Chrome/History.json"), result.unmatched.toString())
    }

    @Test
    fun photosInTheSameArchiveAreSeenButNotRead() = runTest {
        val zip = takeoutZip(
            mapOf(
                "Takeout/Google Photos/2021/IMG_1.jpg" to "not really a jpeg",
                "Takeout/Google Photos/2021/IMG_1.jpg.json" to "{\"photoTakenTime\":{}}",
                "Takeout/Timeline.json" to "{\"semanticSegments\":[]}",
            ),
        )

        val (result, _) = import(backgroundScope, zip)

        // ⚠ B5's business, not B1's: they are classified (so the summary is honest) and their bytes
        // are never pulled through a path built for JSON.
        assertEquals(3, result.understood)
        assertEquals(1, result.timelineFiles)
        assertEquals(emptyList(), result.unmatched)
    }

    @Test
    fun reImportingTheSameArchiveChangesNothing() = runTest {
        val zip = takeoutZip(
            mapOf("Takeout/Timeline2021.json" to monthlyExport("Blue Bottle", "2021-04-07")),
        )
        val proxy = stackProxy(backgroundScope)
        val client = clientOf(proxy)
        val importer = FileTimelineImporter(client, proxy.tContext)
        val archive = ZipArchive.open(ByteArrayZipSource(zip), jvmInflate)!!.asTakeoutArchive()

        importer.importArchive(archive)
        importer.importArchive(archive)

        // ⚠⚠ TB-D8: content-addressed ids make a re-run free, which is what makes it acceptable for
        // a huge archive to fail halfway — the fix is "run it again", with no bookkeeping.
        val cafes = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe")).places().first().filter { it.name == "Blue Bottle" }
        assertEquals(1, cafes.size)
        assertEquals(1, cafes.single().visitsCount)
    }

    @Test
    fun somethingThatIsNotAnArchiveIsNotACrash() = runTest {
        // The reader answers `null`, so there is nothing to import and nothing to explain away.
        assertEquals(null, ZipArchive.open(ByteArrayZipSource("nope".encodeToByteArray()), jvmInflate))
    }

    // ── the same in-process stack the pipeline test stands up ────────────────

    private fun stackProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(timelineServiceCommonModule); registerConfig(timelineServiceClientModule)
            registerConfig(placeServiceCommonModule); registerConfig(placeServiceClientModule)
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy ->
                timelineServiceProxyImplementationFactory(id)
                    ?: placeServiceProxyImplementationFactory(id)
                    ?: healthServiceProxyImplementationFactory(id, proxy.fileDatabase)
            },
        ).also {
            it.executionContext.initTimelineService(SemanticMergerRegistry())
            it.executionContext.initPlaceService(SemanticMergerRegistry())
            it.executionContext.initHealthService()
        }
    }

    private fun clientOf(proxy: TestProxy): TenshuClient {
        val env = TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
        return DefaultTenshuClient(env)
    }
}

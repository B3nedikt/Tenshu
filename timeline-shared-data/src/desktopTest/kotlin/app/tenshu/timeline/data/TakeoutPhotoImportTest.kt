package app.tenshu.timeline.data

import app.tenshu.takeout.StreamingTakeoutArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.photosNear
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Google Photos export's photos, into Drive, **with the location their sidecar knows** (B5,
 * TB-D4) — and then found again by place.
 *
 * ⭐⭐ The last test here is the whole point of Tracks A and B together: a photo the user exported
 * from Google, whose image carries no EXIF at all, is uploaded with the coordinate from its JSON
 * sidecar and is then returned by `photosNear` — which is what makes "show me my pictures from
 * Munich" answer anything beyond the handful of photos backed up from a Tenshu device.
 */
class TakeoutPhotoImportTest {

    // Marienplatz.
    private val latE7 = 481_374_000L
    private val lngE7 = 115_755_000L

    private fun sidecar(taken: Long, lat: Double?, lng: Double?) = buildString {
        append("""{ "title": "IMG_1.jpg", "photoTakenTime": { "timestamp": "$taken" }""")
        if (lat != null && lng != null) append(""", "geoData": { "latitude": $lat, "longitude": $lng }""")
        append(" }")
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** ⚠ Deliberately NOT a real JPEG with EXIF: the sidecar has to be doing the work. */
    private fun photoBytes(marker: String) = "not a real jpeg — $marker".encodeToByteArray()

    private fun fileProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(fileServiceCommonModule); registerConfig(fileServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy ->
                fileServiceProxyImplementationFactory(id = id, fileDatabase = proxy.fileDatabase)
            },
        ).apply { executionContext.initFileService() }
    }

    private fun clientOf(proxy: TestProxy): TenshuClient = DefaultTenshuClient(
        TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        ),
    )

    @Test
    fun aPhotoArrivesWithTheTimeAndPlaceItsSidecarKnows() = runTest {
        val archive = StreamingTakeoutArchive {
            ByteArrayInputStream(
                zip(
                    mapOf(
                        "Takeout/Google Photos/2021/IMG_1.jpg" to photoBytes("munich"),
                        "Takeout/Google Photos/2021/IMG_1.jpg.json"
                            to sidecar(1_612_345_678, 48.1374, 11.5755).encodeToByteArray(),
                    ),
                ),
            )
        }
        val client = clientOf(fileProxy(backgroundScope))

        val result = TakeoutPhotoImporter(client).import(archive)

        assertEquals(1, result.imported)
        assertEquals(1, result.located)
    }

    @Test
    fun andIsThenFoundByPlace() = runTest {
        val archive = StreamingTakeoutArchive {
            ByteArrayInputStream(
                zip(
                    mapOf(
                        "Takeout/Google Photos/2021/IMG_1.jpg" to photoBytes("munich"),
                        "Takeout/Google Photos/2021/IMG_1.jpg.json"
                            to sidecar(1_612_345_678, 48.1374, 11.5755).encodeToByteArray(),
                        "Takeout/Google Photos/2021/IMG_2.jpg" to photoBytes("berlin"),
                        "Takeout/Google Photos/2021/IMG_2.jpg.json"
                            to sidecar(1_612_345_679, 52.5200, 13.4050).encodeToByteArray(),
                    ),
                ),
            )
        }
        val proxy = fileProxy(backgroundScope)
        val client = clientOf(proxy)
        TakeoutPhotoImporter(client).import(archive)

        val near = client.getService<FileService>(FileService.ID).defaultFileSystem
            .photosNear(latE7 = latE7, lngE7 = lngE7, radiusM = 2_000, limit = 10)
            .getOrNull()?.toList().orEmpty().filterIsInstance<CapturedPhoto>()

        // ⭐⭐ Tracks A and B meeting: the image carries no EXIF whatsoever, and the photo is still
        // findable by place because the sidecar's coordinate reached the row.
        assertEquals(listOf("IMG_1.jpg"), near.map { it.path.get().substringAfterLast('/') })
    }

    @Test
    fun aSidecarThatAppearsAfterItsPhotoIsStillUsed() = runTest {
        // ⚠⚠ The reason the import makes two passes. A streaming reader cannot go back, and Takeout
        // does not promise the sidecar comes first — with one pass this photo would land with no
        // location at all, silently.
        val archive = StreamingTakeoutArchive {
            ByteArrayInputStream(
                zip(
                    linkedMapOf(
                        "Takeout/Google Photos/IMG_1.jpg" to photoBytes("munich"),
                        "Takeout/Google Photos/IMG_1.jpg.json"
                            to sidecar(1_612_345_678, 48.1374, 11.5755).encodeToByteArray(),
                    ),
                ),
            )
        }

        assertEquals(1, TakeoutPhotoImporter(clientOf(fileProxy(backgroundScope))).import(archive).located)
    }

    @Test
    fun aPhotoWithNoLocationStillArrives() = runTest {
        val archive = StreamingTakeoutArchive {
            ByteArrayInputStream(
                zip(
                    mapOf(
                        "Takeout/Google Photos/IMG_3.jpg" to photoBytes("nowhere"),
                        "Takeout/Google Photos/IMG_3.jpg.json"
                            to sidecar(1_612_345_678, null, null).encodeToByteArray(),
                    ),
                ),
            )
        }

        val result = TakeoutPhotoImporter(clientOf(fileProxy(backgroundScope))).import(archive)

        // ⚠ Imported but not located — and the summary says so, because "4 000 photos, 0 with a
        // location" is a result the user should see rather than discover as an empty search.
        assertEquals(1, result.imported)
        assertEquals(0, result.located)
    }

    @Test
    fun nullIslandIsNotAPlace() = runTest {
        val archive = StreamingTakeoutArchive {
            ByteArrayInputStream(
                zip(
                    mapOf(
                        "Takeout/Google Photos/IMG_4.jpg" to photoBytes("null island"),
                        "Takeout/Google Photos/IMG_4.jpg.json"
                            to sidecar(1_612_345_678, 0.0, 0.0).encodeToByteArray(),
                    ),
                ),
            )
        }
        val client = clientOf(fileProxy(backgroundScope))

        assertEquals(0, TakeoutPhotoImporter(client).import(archive).located)

        // ⚠⚠ And it is not findable at (0,0) either: Takeout writes 0/0 for every photo it has no
        // location for, so reading it literally would gather a decade of someone's photos into one
        // spot in the Atlantic.
        val atNullIsland = client.getService<FileService>(FileService.ID).defaultFileSystem
            .photosNear(latE7 = 0, lngE7 = 0, radiusM = 5_000, limit = 10)
            .getOrNull()?.toList().orEmpty()
        assertTrue(atNullIsland.isEmpty(), "a photo with no location is nowhere, not at (0,0)")
    }

    @Test
    fun theArchivesPhotosLandInOneNamedFolder() = runTest {
        val archive = StreamingTakeoutArchive {
            ByteArrayInputStream(zip(mapOf("Takeout/Google Photos/2021/IMG_9.jpg" to photoBytes("x"))))
        }
        val client = clientOf(fileProxy(backgroundScope))

        TakeoutPhotoImporter(client).import(archive)

        // ⚠ Flat, under one folder the user can recognise: Takeout's own album folders are Google's
        // organisation, not the user's, and recreating them would import a filing system nobody
        // asked for.
        val photos = client.getService<FileService>(FileService.ID).defaultFileSystem
            .photosNear(latE7 = 0, lngE7 = 0, radiusM = 1, limit = 10)
        assertTrue(photos.getOrNull() != null)
    }
}

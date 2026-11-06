package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.photosNear
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.file.test.createFileProxy
import org.tenshu.file.test.exifJpegAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `photosNear` (`mia-query-layer.md` Track A) end-to-end over the op wire — a real JPEG's EXIF is
 * parsed at upload, the coordinate lands on the row, and the radius query finds it again.
 *
 * ⚠ **Over the wire on purpose**, for the same reason as `PhotosCapturedBetweenOperationTest`: the
 * params must arrive, the scan must walk a real store, and the returned composites must materialise
 * on the far side. None of that is visible to a unit test of the distance function.
 */
class PhotosNearOperationTest {

    // Marienplatz, and the Hauptbahnhof about 1.1 km west of it.
    private val marienplatzLat = 48.137_4
    private val marienplatzLng = 11.575_5
    private val hauptbahnhofLat = 48.140_2
    private val hauptbahnhofLng = 11.558_6

    private fun fileService(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService(FileService.ID)
    }

    private suspend fun FileService.uploadAt(name: String, lat: Double, lng: Double) {
        assertTrue(
            defaultFileSystem.uploadFile("/", name, exifJpegAt(lat, lng)) is TResult.Success,
            "seeding /$name should succeed",
        )
    }

    private suspend fun FileService.near(
        lat: Double,
        lng: Double,
        radiusM: Long,
        limit: Int = 10,
    ): List<CapturedPhoto> =
        defaultFileSystem.photosNear(
            latE7 = (lat * 1e7).toLong(),
            lngE7 = (lng * 1e7).toLong(),
            radiusM = radiusM,
            limit = limit,
        ).getOrNull()?.toList().orEmpty().filterIsInstance<CapturedPhoto>()

    private fun proxy(scope: kotlinx.coroutines.CoroutineScope) =
        createFileProxy(scope, clock = FakeClock(initial = 1L))

    @Test
    fun findsThePhotographTakenAtThatCoordinate() = runTest {
        val service = fileService(proxy(backgroundScope))
        service.uploadAt("marienplatz.jpg", marienplatzLat, marienplatzLng)

        val photos = service.near(marienplatzLat, marienplatzLng, radiusM = 500)

        assertEquals(listOf("/marienplatz.jpg"), photos.map { it.path.get() })
        // The coordinate survived the upload: EXIF → row → op result.
        assertEquals((marienplatzLat * 1e7).toLong() / 100, photos.single().latE7.get() / 100)
    }

    @Test
    fun aPhotographOutsideTheRadiusIsNotFound() = runTest {
        val service = fileService(proxy(backgroundScope))
        service.uploadAt("station.jpg", hauptbahnhofLat, hauptbahnhofLng)

        // ~1.3 km away, so a 500 m radius must not reach it — and a 3 km one must.
        assertEquals(emptyList(), service.near(marienplatzLat, marienplatzLng, radiusM = 500))
        assertEquals(
            listOf("/station.jpg"),
            service.near(marienplatzLat, marienplatzLng, radiusM = 3_000).map { it.path.get() },
        )
    }

    @Test
    fun nearestComesFirst() = runTest {
        val service = fileService(proxy(backgroundScope))
        service.uploadAt("far.jpg", hauptbahnhofLat, hauptbahnhofLng)
        service.uploadAt("close.jpg", marienplatzLat, marienplatzLng)

        val photos = service.near(marienplatzLat, marienplatzLng, radiusM = 3_000)

        assertEquals(listOf("/close.jpg", "/far.jpg"), photos.map { it.path.get() })
    }

    @Test
    fun aPhotographWithNoCoordinateIsNeverNearAnything() = runTest {
        val service = fileService(proxy(backgroundScope))
        assertTrue(
            service.defaultFileSystem.uploadFile("/", "no-gps.jpg", byteArrayOf(1, 2, 3))
                is TResult.Success,
        )

        // ⚠⚠ The interesting case: a coordinate-less row must not read as (0,0) and then match a
        // query near the Atlantic — nor be silently swept into a nearby city's results.
        assertEquals(emptyList(), service.near(marienplatzLat, marienplatzLng, radiusM = 3_000))
        assertEquals(emptyList(), service.near(0.0, 0.0, radiusM = 3_000))
    }

    @Test
    fun theLimitCapsTheResult() = runTest {
        val service = fileService(proxy(backgroundScope))
        service.uploadAt("a.jpg", marienplatzLat, marienplatzLng)
        service.uploadAt("b.jpg", marienplatzLat + 0.000_1, marienplatzLng)
        service.uploadAt("c.jpg", marienplatzLat + 0.000_2, marienplatzLng)

        assertEquals(2, service.near(marienplatzLat, marienplatzLng, radiusM = 500, limit = 2).size)
        // A zero limit is a request for nothing, not a request for everything.
        assertEquals(0, service.near(marienplatzLat, marienplatzLng, radiusM = 500, limit = 0).size)
    }

    @Test
    fun ignoresFilesThatAreNotImages() = runTest {
        val service = fileService(proxy(backgroundScope))
        // Same bytes, a name that is not an image: the category comes from the filename.
        assertTrue(
            service.defaultFileSystem.uploadFile(
                "/", "scan.pdf", exifJpegAt(marienplatzLat, marienplatzLng),
            ) is TResult.Success,
        )

        assertEquals(emptyList(), service.near(marienplatzLat, marienplatzLng, radiusM = 500))
    }
}

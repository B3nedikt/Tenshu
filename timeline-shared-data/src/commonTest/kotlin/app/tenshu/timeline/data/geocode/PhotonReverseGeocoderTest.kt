package app.tenshu.timeline.data.geocode

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [PhotonReverseGeocoder] over Ktor's `MockEngine` — the komoot default endpoint is **never**
 * queried from tests (constraint in place-geocoding.md G3: network only ever behind explicit
 * consent at runtime). Covers the happy path, the street fallback (no POI `name`), and the
 * untrusted-input posture: empty features, malformed JSON and HTTP errors are all `null`.
 *
 * Bodies run on a real dispatcher: the geocoder's request timeout is a `withTimeoutOrNull`, and
 * under `runTest`'s virtual clock the scheduler would fast-forward past it while `MockEngine`
 * responds on its own (real) dispatcher — a false timeout, not the behaviour under test.
 */
class PhotonReverseGeocoderTest {

    private fun photonTest(block: suspend () -> Unit) =
        runTest { withContext(Dispatchers.Default) { block() } }

    private fun geocoder(handler: MockRequestHandler): PhotonReverseGeocoder =
        PhotonReverseGeocoder(HttpClient(MockEngine(handler)), endpoint = "https://photon.test")

    private fun jsonResponse(body: String): MockRequestHandler = {
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun poiNameWinsAndCityCountryRideAlong() = photonTest {
        val geocoder = geocoder(
            jsonResponse(
                """
                {"type":"FeatureCollection","features":[{"type":"Feature","properties":{
                  "osm_id":123456,"name":"Bistro","housenumber":"2","street":"Street",
                  "city":"Lisboa","countrycode":"PT","osm_key":"amenity","osm_value":"cafe"
                },"geometry":{"type":"Point","coordinates":[-9.1443,38.7096]}}]}
                """.trimIndent(),
            ),
        )

        assertEquals(
            GeocodeResult(label = "Bistro", city = "Lisboa", countryCode = "PT"),
            geocoder.resolve(38.7096, -9.1443),
        )
    }

    @Test
    fun streetFallbackWhenNoName() = photonTest {
        val geocoder = geocoder(
            jsonResponse(
                """
                {"features":[{"properties":{
                  "housenumber":"26","street":"Street","city":"Lisboa","countrycode":"PT"
                }}]}
                """.trimIndent(),
            ),
        )

        assertEquals(
            GeocodeResult(label = "Street", city = "Lisboa", countryCode = "PT"),
            geocoder.resolve(38.7104, -9.1450),
        )
    }

    @Test
    fun emptyFeaturesIsNoAnswer() = photonTest {
        val geocoder = geocoder(jsonResponse("""{"type":"FeatureCollection","features":[]}"""))

        assertNull(geocoder.resolve(0.0, 0.0))
    }

    @Test
    fun malformedJsonIsNoAnswerNotAThrow() = photonTest {
        val geocoder = geocoder(jsonResponse("""<html>definitely not GeoJSON"""))

        assertNull(geocoder.resolve(38.7096, -9.1443))
    }

    @Test
    fun httpErrorIsNoAnswerNotAThrow() = photonTest {
        val geocoder = geocoder { respondError(HttpStatusCode.InternalServerError) }

        assertNull(geocoder.resolve(38.7096, -9.1443))
    }

    @Test
    fun requestCarriesCoordinatesAndLimitOnConfiguredEndpoint() = photonTest {
        var requested: Url? = null
        val geocoder = geocoder { request ->
            requested = request.url
            respond(
                content = """{"features":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        geocoder.resolve(38.7096, -9.1443)

        val url = requested ?: error("no request was made")
        assertEquals("photon.test", url.host)
        assertEquals("/reverse", url.encodedPath)
        assertEquals("38.7096", url.parameters["lat"])
        assertEquals("-9.1443", url.parameters["lon"])
        assertEquals("1", url.parameters["limit"])
    }
}

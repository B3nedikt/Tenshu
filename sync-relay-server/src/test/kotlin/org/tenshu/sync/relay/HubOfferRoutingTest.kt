package org.tenshu.sync.relay

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.sync.relay.mailbox.HubOfferRegistry
import org.tenshu.sync.relay.mailbox.HubOfferStore
import org.tenshu.sync.relay.routing.MAX_SEALED_DEVICE_NAME_BYTES

/**
 * `POST /sync/pairing/hub-offer` and `POST /sync/pairing/hub-offer/fetch` — the H2 headless-pairing rendezvous
 * (hub proposal §11.3/§11.4):
 *
 *  - both directions are unauthenticated (the hub has no credentials yet, the phone
 *    has no relation to the hub yet); the stored blob is public material + a signature
 *    the relay never verifies — the phone verifies against the PRINTED key;
 *  - a fetch **consumes** the offer (single-fetch closes the arming window);
 *  - a re-post under the same hash **replaces** (re-arming a long-lived printed secret);
 *  - the empty-mailbox 404 carries the marker body so clients can tell it from an old
 *    relay's unmatched-route 404;
 *  - expiry: a lapsed offer serves 404 exactly like an absent one;
 *  - the fetch carries the hub's long-lived rendezvous key in the BODY; the old `GET ?hash=`
 *    route is gone (T-P-17).
 */
class HubOfferRoutingTest {

    private val hash = "b".repeat(64)
    private val ephemeralKey = ByteArray(32) { (it + 10).toByte() }
    private val signature = ByteArray(64) { (it + 40).toByte() }
    private val b64: Base64.Encoder = Base64.getEncoder()

    /** Stand-in for `PairingRendezvous.sealHubDeviceName` output — opaque bytes to the relay. */
    private fun sealed(tag: Int) = ByteArray(66) { (it * 3 + tag).toByte() }

    private fun body(
        ephemeral: ByteArray = ephemeralKey,
        sealedName: ByteArray = sealed(1),
    ): String = """
        {
          "pairingSecretHash": "$hash",
          "ephemeralPublicKey": "${b64.encodeToString(ephemeral)}",
          "deviceId": "hub-device",
          "sealedDeviceName": "${b64.encodeToString(sealedName)}",
          "signature": "${b64.encodeToString(signature)}"
        }
    """.trimIndent()

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.fetchOffer(): io.ktor.client.statement.HttpResponse =
        client.post("/sync/pairing/hub-offer/fetch") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$hash"}""")
        }

    @Test
    fun theFetchKeyNeverTravelsInAUri_theOldGetRouteIsGone() = testApplication {
        application { testModule() }
        client.post("/sync/pairing/hub-offer") {
            contentType(ContentType.Application.Json)
            setBody(body())
        }
        // A stale client's `GET ?hash=` no longer matches any route, and does not consume.
        val stale = client.get("/sync/pairing/hub-offer?hash=$hash")
        assertTrue(stale.status == HttpStatusCode.NotFound || stale.status == HttpStatusCode.MethodNotAllowed, "${stale.status}")
        assertTrue(stale.bodyAsText() != "hub-offer:none", "an unmatched route carries no marker")
        assertEquals(HttpStatusCode.OK, fetchOffer().status, "the offer is still there for the POST fetch")
        // A body without the key is a 400, not a fetch of nothing.
        val missing = client.post("/sync/pairing/hub-offer/fetch") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, missing.status)
    }

    @Test
    fun postedHubOffer_roundTripsThroughTheFetch_andIsConsumedByIt() = testApplication {
        application { testModule() }

        val post = client.post("/sync/pairing/hub-offer") {
            contentType(ContentType.Application.Json)
            setBody(body())
        }
        assertEquals(HttpStatusCode.OK, post.status)

        val get = fetchOffer()
        assertEquals(HttpStatusCode.OK, get.status)
        val json = Json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals(b64.encodeToString(ephemeralKey), json["ephemeralPublicKey"]?.jsonPrimitive?.content)
        assertEquals("hub-device", json["deviceId"]?.jsonPrimitive?.content)
        // F7: the sealed name round-trips byte-for-byte; no clear name exists to serve.
        assertEquals(b64.encodeToString(sealed(1)), json["sealedDeviceName"]?.jsonPrimitive?.content)
        assertEquals(null, json["deviceName"], "the reply must carry no clear device name")
        assertEquals(b64.encodeToString(signature), json["signature"]?.jsonPrimitive?.content)

        // Consumed: the second fetch finds nothing (arming window closed, §11.4).
        val second = fetchOffer()
        assertEquals(HttpStatusCode.NotFound, second.status)
        assertEquals("hub-offer:none", second.bodyAsText())
    }

    @Test
    fun emptyMailbox404_carriesTheMarkerBody() = testApplication {
        application { testModule() }
        val get = fetchOffer()
        assertEquals(HttpStatusCode.NotFound, get.status)
        assertEquals(
            "hub-offer:none",
            get.bodyAsText(),
            "clients key old-relay detection off this marker — a bare 404 means 'relay unsupported'",
        )
    }

    @Test
    fun rePostUnderTheSameHash_replacesTheOffer() = testApplication {
        application { testModule() }

        client.post("/sync/pairing/hub-offer") {
            contentType(ContentType.Application.Json)
            setBody(body(ephemeral = ByteArray(32) { 1 }, sealedName = sealed(2)))
        }
        // Re-arm (fresh ephemeral) — the printed secret (and thus the hash) is long-lived,
        // so this must replace, not 409 like the single-use offer-response mailbox.
        val rePost = client.post("/sync/pairing/hub-offer") {
            contentType(ContentType.Application.Json)
            setBody(body(sealedName = sealed(3)))
        }
        assertEquals(HttpStatusCode.OK, rePost.status)

        val get = fetchOffer()
        assertEquals(HttpStatusCode.OK, get.status)
        val json = Json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals(b64.encodeToString(sealed(3)), json["sealedDeviceName"]?.jsonPrimitive?.content)
        assertEquals(b64.encodeToString(ephemeralKey), json["ephemeralPublicKey"]?.jsonPrimitive?.content)
    }

    @Test
    fun missingFields_are400() = testApplication {
        application { testModule() }
        val post = client.post("/sync/pairing/hub-offer") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash": "$hash"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, post.status)
    }

    @Test
    fun aClearDeviceName_isRefused_notStored() = testApplication {
        // F7: a pre-F7 hub body (clear `deviceName`) fails loudly instead of leaking the name.
        application { testModule() }
        val post = client.post("/sync/pairing/hub-offer") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "pairingSecretHash": "$hash",
                  "ephemeralPublicKey": "${b64.encodeToString(ephemeralKey)}",
                  "deviceId": "hub-device",
                  "deviceName": "Living Room Hub",
                  "sealedDeviceName": "${b64.encodeToString(sealed(1))}",
                  "signature": "${b64.encodeToString(signature)}"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, post.status)
        assertEquals(HttpStatusCode.NotFound, fetchOffer().status)
    }

    @Test
    fun anOversizedSealedName_isRefused() = testApplication {
        application { testModule() }
        val post = client.post("/sync/pairing/hub-offer") {
            contentType(ContentType.Application.Json)
            setBody(body(sealedName = ByteArray(MAX_SEALED_DEVICE_NAME_BYTES + 1)))
        }
        assertEquals(HttpStatusCode.BadRequest, post.status)
    }

    @Test
    fun expiredOffer_servesNotFound_andGcSweepDropsIt() {
        // Store-level expiry semantics (no clock control through the route layer).
        val store = HubOfferStore()
        val offer = HubOfferRegistry.HubOffer(ephemeralKey, "hub-device", sealed(1), signature)
        store.post(hash, offer, expiresAtMs = 1_000L, nowMs = 0L)

        assertEquals(
            HubOfferRegistry.Fetch.NotFound,
            store.consume(hash, nowMs = 1_000L),
            "a lapsed offer must serve exactly like an absent one",
        )

        store.post(hash, offer, expiresAtMs = 1_000L, nowMs = 0L)
        assertEquals(1, store.gcSweep(nowMs = 2_000L))
        assertEquals(HubOfferRegistry.Fetch.NotFound, store.consume(hash, nowMs = 0L))
    }
}

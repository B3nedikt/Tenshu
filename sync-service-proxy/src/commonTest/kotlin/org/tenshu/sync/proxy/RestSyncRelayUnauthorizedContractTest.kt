package org.tenshu.sync.proxy

import org.tenshu.core.common.RelayRefusal
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.GenericError
import org.tenshu.core.messageOrNull
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.SignedHlcTip
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.common.TestProxy
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the producer half of the `401` detection that drives the registration repair + the
 * "this device isn't recognised" health state.
 *
 * ⚠⚠ **Why a string contract needs a test at all.** The relay deliberately refuses to say *why* it
 * returned `401` — `JwtAuth` answers "unknown device" and "bad signature" identically so the
 * response is not a device-existence oracle — so the client cannot key on a marker the way it does
 * for the `402` ([ENTITLEMENT_REJECTION_MARKER], which the producer attaches explicitly). It keys on
 * the HTTP status text that `RestSyncRelay` interpolates instead ([UNAUTHORIZED_REJECTION_MARKER]). That
 * is a contract between two files with nothing but a literal holding it together: reword the failure
 * message and the repair silently stops firing, while every test that mocks at the operation layer
 * keeps passing. Hence this test, which goes through the real HTTP layer.
 */
class RestSyncRelayUnauthorizedContractTest {

    private val testProxy = TestProxy().also {
        it.schemaRegistry.registerConfig(syncServiceCommonModule)
    }

    private fun relayAnswering(status: HttpStatusCode, body: String = "Invalid token"): RestSyncRelay {
        val engine = MockEngine { respond(body, status) }
        return RestSyncRelay(
            baseUrl = "https://relay.example",
            httpClient = HttpClient(engine) { install(ContentNegotiation) { json() } },
            context = testProxy.tContext,
            authTokenProvider = { "device-jwt" },
            ownerIdProvider = { "owner" },
        )
    }

    @Test
    fun a401WithNoReasonPhraseIsStillDetected() = runTest {
        // ⚠⚠ THE regression. HTTP/2 has no reason phrase, so the production relay behind Caddy
        // answers with an empty description and Ktor renders the failure as "401 " — while `h1` and
        // `MockEngine`'s own default both render "401 Unauthorized". The first version of this
        // detector matched that literal text, so on 2026-08-17 a real device pushed 386 queued
        // blocks per pass at ~38 req/s with the backoff, the drain break and the health state all
        // inert, and this very test file was green throughout because the fixture supplied the one
        // shape the matcher understood. Assert the h2 shape FIRST, so the fixture can never again be
        // gentler than the wire.
        val failure = relayAnswering(HttpStatusCode(401, "")).createRealm(
            ownerId = "owner",
            masterPublicKey = byteArrayOf(1),
            firstDevicePublicKey = byteArrayOf(2),
            firstDeviceId = "device",
            masterSelfSignature = byteArrayOf(3),
        )

        assertTrue(failure is TResult.Failure<*>, "a 401 must surface as a failure")
        assertTrue(
            TenshuException(failure.error).isRelayUnauthorized(),
            "a 401 with an empty reason phrase (h2) must be detected, else the repair never fires",
        )
    }

    @Test
    fun theF2PrimeRefusalIsDetected_withNoReasonPhrase_andIsNotConfusedWithTheOther403() = runTest {
        // F2′: the joiner was removed and can never rejoin as itself. Terminal — unlike every other
        // rejection modelled here, no retry, credential or repair resolves it.
        //
        // ⚠⚠ Asserted at the HOSTILE shape first, for the reason the 401 test above exists: h2
        // sends no reason phrase, so `HttpStatusCode(403, "")` is what production actually looks
        // like and a fixture that supplies "403 Forbidden" would hide a text-matching detector.
        //
        // ⚠⚠ And the status alone must NOT be the discriminator: the same route answers 403 for a
        // revoked APPROVER capability, which is about a different device and is not terminal.
        val removed = relayAnswering(
            HttpStatusCode(403, ""),
            RelayRefusal.body(RelayRefusal.DEVICE_REMOVED, "This device was removed from the realm"),
        ).createRealm(
            ownerId = "owner",
            masterPublicKey = byteArrayOf(1),
            firstDevicePublicKey = byteArrayOf(2),
            firstDeviceId = "device",
            masterSelfSignature = byteArrayOf(3),
        )
        assertTrue(removed is TResult.Failure<*>)
        assertTrue(
            TenshuException(removed.error).isDeviceRemoved(),
            "a removal refusal with an empty reason phrase (h2) must still be recognised",
        )

        val revoked = relayAnswering(
            HttpStatusCode(403, ""),
            RelayRefusal.body(RelayRefusal.ADMISSION_REVOKED, "Admission capability has been revoked"),
        ).createRealm(
            ownerId = "owner",
            masterPublicKey = byteArrayOf(1),
            firstDevicePublicKey = byteArrayOf(2),
            firstDeviceId = "device",
            masterSelfSignature = byteArrayOf(3),
        )
        assertTrue(revoked is TResult.Failure<*>)
        assertFalse(
            TenshuException(revoked.error).isDeviceRemoved(),
            "the approver-capability 403 must NOT read as a removed joiner — different device, " +
                "and treating it as terminal would send the wrong user to 'start fresh'",
        )
    }

    @Test
    fun theF2PrimeRefusalIsDetected_onTheOfferResponseRoute_theOneW10bQuotes() = runTest {
        // `Offer-response failed: 403 …` is the exact string W10b quotes, and the offer route is
        // the one a returning device uses — so the detector has to fire through THIS call, not
        // only through genesis. Same hostile shape as above: no reason phrase, token in the body.
        val failure = relayAnswering(
            HttpStatusCode(403, ""),
            RelayRefusal.body(RelayRefusal.DEVICE_REMOVED, "This device was removed from the realm"),
        ).postOfferResponse(
            ownerId = "owner",
            responderDeviceId = "responder",
            pairingSecretHash = "a".repeat(64),
            status = "approved",
            publicKeyA = byteArrayOf(1),
            payload = byteArrayOf(2),
            hostSignature = byteArrayOf(3),
            offererDeviceId = "offerer",
            offererSigningKey = byteArrayOf(4),
            masterDelegation = byteArrayOf(5),
            epoch = 1L,
            admissionCertChain = null,
            admittedAtHlc = null,
        )
        assertTrue(failure is TResult.Failure<*>, "a 403 must surface as a failure")
        assertTrue(
            TenshuException(failure.error).isDeviceRemoved(),
            "the offer-response refusal must classify as device-removed — it is the W10b path",
        )
    }

    @Test
    fun theConsumedPairingResponse409IsDetected_andAPlain409IsNot() = runTest {
        // F7: an acked join hash is retired, so a host retrying its own response post — first
        // reply lost, joiner already acked — gets a 409 it must read as delivered. Hostile h2
        // shape (no reason phrase); the discriminator is the body token, never the status.
        suspend fun post(status: HttpStatusCode, body: String) = relayAnswering(status, body).postPairingResponse(
            pairingSecretHash = "a".repeat(64),
            encryptedMasterKey = byteArrayOf(1),
            newDevicePublicKey = byteArrayOf(2),
            newDeviceId = "joiner",
            masterDelegation = byteArrayOf(3),
            epoch = 0,
            admissionCertChain = null,
            admittedAtHlc = null,
        )
        val consumed = post(
            HttpStatusCode(409, ""),
            RelayRefusal.body(RelayRefusal.PAIRING_RESPONSE_CONSUMED, "pairingSecretHash already consumed"),
        )
        assertTrue(consumed is TResult.Failure<*>)
        assertTrue(consumed.isPairingResponseConsumed(), "got ${consumed.error.messageOrNull()}")

        val other = post(HttpStatusCode(409, ""), "something else")
        assertTrue(other is TResult.Failure<*>)
        assertFalse(other.isPairingResponseConsumed())
    }

    @Test
    fun aPlain403IsNotMistakenForARemovedDevice() = runTest {
        // A 403 with no token at all (an older relay, or a route that refuses for another reason)
        // must stay non-terminal. The marker is opt-in by construction.
        val failure = relayAnswering(HttpStatusCode(403, "")).createRealm(
            ownerId = "owner",
            masterPublicKey = byteArrayOf(1),
            firstDevicePublicKey = byteArrayOf(2),
            firstDeviceId = "device",
            masterSelfSignature = byteArrayOf(3),
        )
        assertTrue(failure is TResult.Failure<*>)
        assertFalse(TenshuException(failure.error).isDeviceRemoved())
    }

    @Test
    fun a401FromTheRelayIsDetectedAsUnauthorized() = runTest {
        val failure = relayAnswering(HttpStatusCode.Unauthorized).createRealm(
            ownerId = "owner",
            masterPublicKey = byteArrayOf(1),
            firstDevicePublicKey = byteArrayOf(2),
            firstDeviceId = "device",
            masterSelfSignature = byteArrayOf(3),
        )

        assertTrue(failure is TResult.Failure<*>, "a 401 must surface as a failure")
        assertTrue(
            TenshuException(failure.error).isRelayUnauthorized(),
            "the 401 must be recognised by isRelayUnauthorized(), else the repair never fires",
        )
    }

    @Test
    fun a401FromEitherSyncLogRouteIsDetected() = runTest {
        // ⚠⚠ THE SECOND regression, and the reason this file now tests routes rather than one
        // route. `getSyncLog` / `getSyncLogBefore` return the sealed [SyncRelay.SyncLogFetch]
        // instead of a `TResult`, so when the merged lane landed they did not inherit the
        // `statusWithReason()` convention the other 26 error constructions in `RestSyncRelay`
        // use — they interpolated the bare status and the marker was never attached.
        //
        // Those two routes carry essentially ALL of a healthy client's relay traffic, so the
        // detector measured as fixed while the storm it exists to stop continued: on the live
        // relay 2026-08-18 a desktop whose registration had been dropped sent **41 `POST
        // /sync/log` per minute for 31 hours** (~30k requests), every one a 401, with the
        // backoff, the re-registration repair AND the "device not recognised" banner all inert.
        // The client logged `Server error: 401 ` — the h2 shape, with no marker in it.
        val relay = relayAnswering(HttpStatusCode(401, ""))
        val tip = SignedHlcTip.create(
            testProxy.tContext,
            "owner",
            "peer",
            HybridLogicalClock.create(testProxy.tContext, 1L, 0L, "peer"),
            byteArrayOf(9),
        )

        val routes: List<Pair<String, suspend () -> SyncRelay.SyncLogFetch>> = listOf(
            "getSyncLog" to { relay.getSyncLog(0L, tip) },
            "getSyncLogBefore" to { relay.getSyncLogBefore(10L, tip) },
        )

        for ((name, call) in routes) {
            val result = call()
            assertTrue(
                result is SyncRelay.SyncLogFetch.Error,
                "$name: a 401 must surface as an error, got $result",
            )
            // Wrapped exactly as GetSyncLogImplementation wraps it before the scheduler ever
            // sees it — asserting on the raw message would pin the string, not the behaviour.
            val asThrown = TenshuException(
                GenericError.create(testProxy.tContext, result.message)
            )
            assertTrue(
                asThrown.isRelayUnauthorized(),
                "$name: a 401 must classify as unauthorized, else the pull storms at tick " +
                    "cadence forever and the device never learns it was dropped",
            )
        }
    }

    @Test
    fun a409OnGenesisIsRecognisedAsAlreadyRegistered_notAFailure() = runTest {
        // ⚠⚠ `POST /sync/realm` answering 409 means a realm already exists for this ownerId — and
        // `ownerId` is derived from the master public key, so that realm is OURS. It is the state
        // genesis wanted, not an error.
        //
        // It is reachable in ordinary operation, not just in tests: the scheduler calls
        // `ensureRegistered` as soon as pushes are allowed, while `CreateRealmImplementation` holds
        // a DIFFERENT registrar instance with its own latch — so whichever loses the race POSTs and
        // is refused. Reported as failure, that made genesis look broken
        // (`BootstrapSystemTest`, 2026-08-18).
        //
        // ⚠ Asserted through the real HTTP layer with an EMPTY reason phrase, the h2 shape: a
        // status-text match ("409 Conflict") would be inert in production exactly as the 401 one
        // was, twice.
        val failure = relayAnswering(HttpStatusCode(409, "")).createRealm(
            ownerId = "owner",
            masterPublicKey = byteArrayOf(1),
            firstDevicePublicKey = byteArrayOf(2),
            firstDeviceId = "device",
            masterSelfSignature = byteArrayOf(3),
        )

        assertTrue(failure is TResult.Failure<*>, "the relay call itself still reports the 409")
        assertTrue(
            failure.isRealmAlreadyExistsFailure(),
            "a 409 on genesis must be recognised as already-registered, else the caller reports a " +
                "working realm as a failed bootstrap",
        )
        assertFalse(
            TenshuException(failure.error).isRelayUnauthorized(),
            "and it must not be confused with a 401 — different cause, different response",
        )
    }

    @Test
    fun otherFailuresAreNotMistakenForUnauthorized() = runTest {
        // The repair is idempotent but not free, and mis-classifying a 500 as "the relay forgot me"
        // would also mis-report the health state to the user. Only a 401 counts.
        val failure = relayAnswering(HttpStatusCode.InternalServerError).createRealm(
            ownerId = "owner",
            masterPublicKey = byteArrayOf(1),
            firstDevicePublicKey = byteArrayOf(2),
            firstDeviceId = "device",
            masterSelfSignature = byteArrayOf(3),
        )

        assertTrue(failure is TResult.Failure<*>)
        assertFalse(
            TenshuException(failure.error).isRelayUnauthorized(),
            "a 500 is not an identity rejection",
        )
    }
}

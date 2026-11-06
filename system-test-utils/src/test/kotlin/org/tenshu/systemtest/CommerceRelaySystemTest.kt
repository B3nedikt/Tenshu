package org.tenshu.systemtest

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.commerce.CommerceConfig
import org.tenshu.commerce.EmailSender
import org.tenshu.commerce.EntitlementMinter
import org.tenshu.commerce.EntitlementTokenMinter
import org.tenshu.commerce.IssuerKeypair
import org.tenshu.commerce.module
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.core.TResult
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.file.system.createFolder
import org.tenshu.file.client.fileService
import org.tenshu.sync.relay.RelayConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **The two-plane hop, in CI** (`commerce-end-to-end-dry-run.md` CE-7 / D6).
 *
 * Everything about "commerce mints a credential and the relay honours it" was previously proven
 * only by hand against a deployed droplet. `EntitlementEnforcementSystemTest` covers enforcement
 * well, but it **self-signs** its entitlement with `EntitlementSignature.DEV_SEED` — the commerce
 * process is never in the loop — so a change that broke the mint route, the session gate, the token
 * encoding or the issuer-key hand-off would leave every test green.
 *
 * This test closes that by running the **real `:commerce-service` Ktor module** in-process beside
 * the real relay, and driving the actual production sequence:
 *
 * ```
 * register → (OTP) → verify → session → /commerce/entitlement/token → X-Entitlement → POST /sync/push
 * ```
 *
 * ⚠⚠ **The relay is configured with the key commerce actually signs with**, read off the live
 * `IssuerKeypair` rather than the pinned constant. That is deliberately the same hand-off an
 * operator performs (commerce prints its public half; you paste it into `ENTITLEMENT_ISSUER_PUBKEY`),
 * so a mismatch in key derivation or encoding fails here instead of at a deployment.
 *
 * ⚠ Assertions read [TestSystem.syncMailbox] rather than client state, for the same reason as the
 * other enforcement tests: a refused push is invisible from the client — the block simply stays
 * queued and the client retries. "Did the relay accept this write?" is only answerable on the relay.
 *
 * ⚠ The two planes share no state and no credential: commerce never learns about the realm, the
 * relay never calls commerce. The only thing crossing is a signed token the client carries
 * (`commerce-billing-plane.md` D1/D2), which is exactly what makes this a *bridge* test.
 */
@OptIn(ExperimentalEncodingApi::class)
class CommerceRelaySystemTest {

    /** Captures the OTP the service "sends", so the test can complete `/verify` like a user. */
    private class CapturingEmailSender : EmailSender {
        private val codes = ConcurrentHashMap<String, String>()
        override fun sendOtp(email: String, code: String) {
            codes[email.trim().lowercase()] = code
        }

        override fun sendAccountDeletionCode(email: String, code: String) = Unit

        override fun sendNoAccountNotice(email: String) = Unit

        override fun sendSignupsPausedNotice(email: String) = Unit

        fun code(email: String): String? = codes[email.trim().lowercase()]
    }

    private val json = Json { ignoreUnknownKeys = true }

    // The issuer, exactly as `main()` builds it: dev defaults (no env), so the seed is the committed
    // dev issuer seed and the id is `tenshu.commerce`.
    private val commerceConfig = CommerceConfig.fromEnv { null }
    private val issuerKeypair = IssuerKeypair(commerceConfig.issuerSeed, BouncyCastleEd25519Backend())
    private val commerceStore = InMemoryCommerceStore()
    private val emailSender = CapturingEmailSender()

    private val commerceApp = TestApplication {
        application {
            module(
                minter = EntitlementMinter(commerceConfig, issuerKeypair),
                tokenMinter = EntitlementTokenMinter(commerceConfig, issuerKeypair),
                store = commerceStore,
                emailSender = emailSender,
            )
        }
    }

    private val commerce: HttpClient by lazy {
        commerceApp.createClient { install(ContentNegotiation) { json() } }
    }

    @AfterTest
    fun tearDown() = runBlocking { commerceApp.stop() }

    /**
     * A relay that gates writes and trusts **this** issuer — the public half of the keypair the
     * commerce app above is signing with, not a constant that could drift from it.
     */
    private fun enforcingRelayConfig() = RelayConfig(
        devMode = false,
        bindHost = "127.0.0.1",
        port = 0,
        entitlementIssuerPublicKeyB64 = Base64.Default.encode(issuerKeypair.publicKey),
        entitlementEnforce = true,
    )

    /** Register → read the captured OTP → verify. Returns the commerce session token. */
    private suspend fun signUp(email: String, ownerId: String): String {
        val register = commerce.post("/commerce/account/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","tosVersion":"v1","privacyVersion":"v1"}""")
        }
        assertEquals(202, register.status.value, "register should accept and send an OTP")

        val code = assertNotNull(emailSender.code(email), "no OTP was sent for $email")

        val verify = commerce.post("/commerce/account/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","code":"$code","ownerId":"$ownerId"}""")
        }
        assertEquals(200, verify.status.value, "verify should open a session")
        return json.parseToJsonElement(verify.bodyAsText())
            .jsonObject["sessionToken"]!!.jsonPrimitive.content
    }

    /** The production token fetch: session in, signed bearer token out. */
    private suspend fun fetchToken(sessionToken: String): String {
        val response = commerce.post("/commerce/entitlement/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"$sessionToken"}""")
        }
        assertEquals(200, response.status.value, "the session should authorise a token mint")
        return json.parseToJsonElement(response.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
    }

    private fun blocksAccepted(system: TestSystem, ownerId: String): Int =
        system.syncMailbox.getChanges(ownerId, 0L).blocks.size

    /**
     * The bridge, end to end: a device that signs up with the real commerce service obtains a real
     * bearer token, and the relay — verifying against the issuer's public key — accepts its writes.
     *
     * ⚠ The token is fetched **lazily by the provider**, not pre-computed, because that is how
     * production behaves: `CachingEntitlementTokenProvider` asks on the request path once a session
     * exists, and returns null before that. The atomic holder is this test's stand-in for
     * `PublisherClient.currentSessionToken()` — and it must be atomic, because the provider is
     * invoked from the scheduler's coroutines while the test body writes to it.
     */
    @Test fun aCommerceMintedTokenIsAcceptedByTheRelay() = runBlocking {
        val session = AtomicReference<String?>(null)

        val system = TestSystem.builder().relayConfig(enforcingRelayConfig()).build()
        try {
            val host = system.client(
                deviceId = "host",
                role = TestClientRole.RealmHost,
                entitlementTokenProvider = { session.get()?.let { fetchToken(it) } },
            )
            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = host.syncManager.currentOwnerId!!

            // Sign up *after* bootstrap, because /verify binds the device's real ownerId — the same
            // ordering the app has (you onboard, then sign up in Services).
            session.set(signUp(email = "bridge@example.com", ownerId = ownerId))

            val created = host.tenshuClient.fileService.defaultFileSystem.createFolder("/entitled")
            assertTrue(created is TResult.Success, "host createFolder should succeed; got $created")

            system.advanceUntil(timeout = 15.seconds) { blocksAccepted(system, ownerId) > 0 }

            assertTrue(
                blocksAccepted(system, ownerId) > 0,
                "a token minted by the real commerce service must make gated pushes succeed — " +
                    "zero accepted blocks means the mint, the session gate, the token encoding or " +
                    "the issuer-key hand-off is broken",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * The negative control, and the reason the test above means anything: the identical setup with
     * **no session** presents no token, and the relay accepts nothing. Without this, a relay that
     * had quietly stopped enforcing would pass the positive test.
     */
    @Test fun withoutACommerceSessionNothingIsAccepted() = runBlocking {
        val system = TestSystem.builder().relayConfig(enforcingRelayConfig()).build()
        try {
            val host = system.client(
                deviceId = "host",
                role = TestClientRole.RealmHost,
                // Never signed in: exactly what `currentSessionToken()` returns before sign-up.
                entitlementTokenProvider = { null },
            )
            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = host.syncManager.currentOwnerId!!

            val created = host.tenshuClient.fileService.defaultFileSystem.createFolder("/refused")
            assertTrue(created is TResult.Success, "the LOCAL write still succeeds; got $created")

            // Give the scheduler real cycles to try and be refused — a "nothing happened" assertion
            // is worth only as much as the time it was given to happen.
            delay(2_000)

            assertEquals(
                0,
                blocksAccepted(system, ownerId),
                "with no commerce session there is no token, so the relay must accept nothing",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * The gate that decides *what* gets minted lives in commerce, not in the relay: `/token` is
     * refused outright without a valid session, so an unauthenticated caller cannot obtain a
     * credential to present in the first place.
     */
    @Test fun theTokenRouteRefusesAnUnknownSession() = runBlocking {
        val response = commerce.post("/commerce/entitlement/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"not-a-real-session"}""")
        }
        assertEquals(401, response.status.value, "an unknown session must not yield a token")
    }

    /**
     * ⚠ The token asserts a **tier and quota**, and the relay reads them for per-owner quota
     * enforcement. A fresh account is Free, so this pins that the service's own catalogue — not a
     * client-supplied value — decides what the credential claims.
     */
    @Test fun theMintedTokenCarriesTheAccountsRealTier() = runBlocking {
        val session = signUp(email = "tier@example.com", ownerId = "owner-tier")
        val response = commerce.post("/commerce/entitlement/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionToken":"$session"}""")
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("tenshu.drive.free", body["tier"]!!.jsonPrimitive.content)
        assertEquals(
            5L * 1024 * 1024 * 1024,
            body["quotaBytes"]!!.jsonPrimitive.content.toLong(),
            "Free is 5 GiB — the quota the relay will hold this owner to",
        )
    }
}

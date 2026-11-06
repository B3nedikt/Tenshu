package org.tenshu.sync.relay.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.identity.EntitlementSignature
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier
import org.tenshu.sync.relay.testModule
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `GET /sync/usage` reports the caller's entitled **limit** alongside their usage
 * (`entitlement-trust-anchor-distribution.md` P1) — the missing half that lets storage figures come
 * from the authority that enforces them instead of from a client-verified record.
 *
 * ⚠⚠ Reporting is deliberately NOT [resolveEntitlement]. That helper *rejects* (writes a 402/403
 * and the caller returns), which is right for a write gate and wrong here: failing to prove an
 * entitlement must leave usage readable rather than turning a status read into a payment error.
 * These tests pin that a missing or bad credential yields `null` **with a 200**, not an error.
 */
@OptIn(ExperimentalEncodingApi::class)
class UsageQuotaReportingTest {

    private val ed = BouncyCastleEd25519Backend()
    private val issuerSeed = ByteArray(32) { (it + 65).toByte() }
    private val issuerPublicKeyB64 = Base64.Default.encode(ed.derivePublicKey(issuerSeed))

    private fun configWithIssuer(enforce: Boolean = false) = RelayConfig(
        devMode = true,
        bindHost = "127.0.0.1",
        port = 8080,
        entitlementIssuerPublicKeyB64 = issuerPublicKeyB64,
        entitlementEnforce = enforce,
    )

    @Serializable
    private data class RecordDto(
        val ownerId: String,
        val publisherId: String,
        val productId: String,
        val validFrom: Long,
        val validUntil: Long,
        val sig: String,
    )

    private fun recordProof(
        ownerId: String,
        productId: String,
        signSeed: ByteArray = issuerSeed,
    ): String {
        val from = 0L
        val until = Long.MAX_VALUE / 2
        val msg = EntitlementSignature.message(
            ownerId, EntitlementSignature.COMMERCE_PUBLISHER_ID, productId, from, until,
        )
        val dto = RecordDto(
            ownerId, EntitlementSignature.COMMERCE_PUBLISHER_ID, productId, from, until,
            Base64.Default.encode(ed.sign(signSeed, msg)),
        )
        val json = Json.encodeToString(RecordDto.serializer(), dto)
        return RelayEntitlementVerifier.SIGNED_RECORD_PROOF_TYPE +
            Base64.UrlSafe.encode(json.encodeToByteArray())
    }

    private fun usageOf(body: String): UsageResponse =
        Json { ignoreUnknownKeys = true }.decodeFromString(UsageResponse.serializer(), body)

    @Test
    fun reportsTheLimitFromAPresentedRecordProof() = testApplication {
        application { testModule(config = configWithIssuer(), knownDevices = KnownDevicesStore(), ed25519 = ed) }

        val response = client.get("/sync/usage") {
            header("X-Dev-Owner", "alice")
            header(RelayEntitlementVerifier.PROOF_HEADER, recordProof("alice", "tenshu.drive.basic"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            50L * 1024 * 1024 * 1024,
            usageOf(response.bodyAsText()).limitBytes,
            "the relay must report the tier its own verifier derives from the presented record",
        )
    }

    /**
     * ⚠ Independent of `entitlementEnforce` on purpose. Enforcement is about *refusing writes*;
     * knowing your own quota is not enforcement. If reporting were gated on the flag, every
     * non-enforcing deployment would show "unknown" forever.
     */
    @Test
    fun reportsTheLimitEvenWhenEnforcementIsOff() = testApplication {
        application {
            testModule(
                config = configWithIssuer(enforce = false),
                knownDevices = KnownDevicesStore(),
                ed25519 = ed,
            )
        }

        val response = client.get("/sync/usage") {
            header("X-Dev-Owner", "alice")
            header(RelayEntitlementVerifier.PROOF_HEADER, recordProof("alice", "tenshu.drive.premium"))
        }

        assertEquals(
            2L * 1024 * 1024 * 1024 * 1024,
            usageOf(response.bodyAsText()).limitBytes,
        )
    }

    @Test
    fun noCredential_reportsNullLimit_notAnError() = testApplication {
        application { testModule(config = configWithIssuer(), knownDevices = KnownDevicesStore(), ed25519 = ed) }

        val response = client.get("/sync/usage") { header("X-Dev-Owner", "alice") }

        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "a status read must stay readable without an entitlement — it is not a write gate",
        )
        assertNull(
            usageOf(response.bodyAsText()).limitBytes,
            "unknown must be null, never 0 — a 0 limit reads as 'you have no storage', which is a " +
                "different and far more alarming claim than 'I could not determine your tier'",
        )
    }

    @Test
    fun proofSignedByTheWrongKey_reportsNullLimit() = testApplication {
        application { testModule(config = configWithIssuer(), knownDevices = KnownDevicesStore(), ed25519 = ed) }

        val wrongSeed = ByteArray(32) { (it + 9).toByte() }
        val response = client.get("/sync/usage") {
            header("X-Dev-Owner", "alice")
            header(
                RelayEntitlementVerifier.PROOF_HEADER,
                recordProof("alice", "tenshu.drive.premium", signSeed = wrongSeed),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(usageOf(response.bodyAsText()).limitBytes)
    }

    /**
     * ⚠ Owner-scoping: a proof asserting a *different* owner must not report that owner's tier.
     * Everything else on this route is caller-scoped and this must not become the exception.
     */
    @Test
    fun proofForAnotherOwner_reportsNullLimit() = testApplication {
        application { testModule(config = configWithIssuer(), knownDevices = KnownDevicesStore(), ed25519 = ed) }

        val response = client.get("/sync/usage") {
            header("X-Dev-Owner", "bob")
            header(
                RelayEntitlementVerifier.PROOF_HEADER,
                recordProof("alice", "tenshu.drive.premium"),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(
            usageOf(response.bodyAsText()).limitBytes,
            "a proof for another owner must not let bob read alice's tier",
        )
    }
}

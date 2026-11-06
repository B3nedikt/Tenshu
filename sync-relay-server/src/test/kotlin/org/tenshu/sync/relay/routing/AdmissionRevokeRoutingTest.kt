package org.tenshu.sync.relay.routing

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.common.AdmissionRevocation
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.AdmissionRevocationStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `POST /sync/admission/revoke` (`delegated-device-admission-authority.md` §5,
 * P2): unauthenticated, gated by the realm-master signature over the
 * revocation — the `/sync/realm-member/revoke` posture. A revocation signed by
 * anything else must not enter the ledger (it would strip an admitter).
 */
class AdmissionRevokeRoutingTest {

    private val masterKey = "master-recovery-key".encodeToByteArray()
    private val ownerId = "owner-revoke"

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    private val a33Key = ByteArray(32) { (it + 40).toByte() }

    private fun body(
        owner: String = ownerId,
        deviceId: String = "device-x",
        signer: ByteArray = masterKey,
        signedKey: ByteArray = a33Key,
        sentKey: String? = b64(a33Key),
    ): String {
        val message = AdmissionRevocation.message(owner, deviceId, epoch = 3L, revokedAt = 2_000L, deviceA33PublicKey = signedKey)
        val keyField = sentKey?.let { ""","deviceA33PublicKey": "$it"""" } ?: ""
        return """
            {
                "ownerId": "$owner",
                "deviceId": "$deviceId",
                "epoch": 3,
                "revokedAt": 2000,
                "signature": "${b64(stubEd25519Signature(signer, message))}"$keyField
            }
        """.trimIndent()
    }

    private fun runCase(
        body: String,
        expectedStatus: HttpStatusCode,
        expectedRecords: Int,
        seedRealm: Boolean = true,
    ) = testApplication {
        val realmStore = RealmStore().apply {
            if (seedRealm) {
                createIfAbsent(RealmStore.RealmRecord(ownerId = ownerId, masterPublicKey = masterKey, createdAt = 0L))
            }
        }
        val store = AdmissionRevocationStore()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore,
                admissionRevocationStore = store,
            )
        }

        val response = client.post("/sync/admission/revoke") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(expectedStatus, response.status)
        assertEquals(expectedRecords, store.recordCount())
    }

    @Test
    fun masterSignedRevocation_isAppended() =
        runCase(body(), HttpStatusCode.NoContent, expectedRecords = 1)

    @Test
    fun nonMasterSignature_returns401_andIsNotAppended() =
        runCase(
            body(signer = "attacker-key".encodeToByteArray()),
            HttpStatusCode.Unauthorized,
            expectedRecords = 0,
        )

    @Test
    fun unknownRealm_returns404() =
        runCase(body(), HttpStatusCode.NotFound, expectedRecords = 0, seedRealm = false)

    /**
     * ⚠⚠ F5b (§7g): a revocation without a key is **refused**, not stored as a device-scoped one.
     * The signature here is a valid master signature over the keyed message — the body simply
     * omits the key — so the only thing that can reject it is the key being required.
     */
    @Test
    fun keylessRevocation_returns400_andIsNotAppended() =
        runCase(body(sentKey = null), HttpStatusCode.BadRequest, expectedRecords = 0)

    @Test
    fun emptyKeyRevocation_returns400_andIsNotAppended() =
        runCase(
            body(signedKey = ByteArray(0), sentKey = ""),
            HttpStatusCode.BadRequest,
            expectedRecords = 0,
        )

    /** The key is inside the signed bytes: naming a different key than A1 signed is a 401. */
    @Test
    fun aKeyOtherThanTheSignedOne_returns401() =
        runCase(
            // ⚠ One byte off the signed key, deliberately: the test stub signature is an XOR mix, so
            // two arbitrary keys can collide under it — a single flipped byte cannot.
            body(sentKey = b64(a33Key.copyOf().also { it[0] = (it[0] + 1).toByte() })),
            HttpStatusCode.Unauthorized,
            expectedRecords = 0,
        )

    @Test
    fun malformedBody_returns400() =
        runCase("""{"ownerId": "$ownerId"}""", HttpStatusCode.BadRequest, expectedRecords = 0)

    /** B1: a revocation names an Ed25519 key — 31 bytes is not one. */
    @Test
    fun aKeyThatIsNot32Bytes_returns400() =
        runCase(
            body(signedKey = ByteArray(31) { 4 }, sentKey = b64(ByteArray(31) { 4 })),
            HttpStatusCode.BadRequest,
            expectedRecords = 0,
        )
}

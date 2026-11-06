package org.tenshu.sync.relay

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTString
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncRelayTest {

    @Test
    fun testPushAndChanges() = testApplication {
        application {
            testModule()
        }

        val context = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { context },
            extraConfigs = listOf(
                coreCommonModule,
                syncServiceCommonModule,
                identityServiceCommonModule
            )
        )
        context.components = mapOf(
            SchemaProvider.COMPONENT_ID to registry,
            DataFactory.COMPONENT_ID to registry
        )

        val parser = BinaryParser(registry, registry)
        val serializer = BinarySerializer(registry)

        val hlc = HybridLogicalClock.create(context, 1000L, 0, "device-1")
        val block = SyncBlock.create(
            context = context,
            blockId = "block-1",
            ownerId = "user-push",
            hlc = hlc,
            encryptedPayload = "payload-1".encodeToByteArray()
        )

        // 1. Push block — relay runs in devMode so X-Dev-Owner is the auth shape per
        // sync-backend-relay-plan.md §4.1. Production swaps in JWT auth (Stage C).
        val pushResponse = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "user-push")
            setBody(serializer.serialize(edgedPush(context, block)))
        }
        assertEquals(HttpStatusCode.OK, pushResponse.status)
        pushResponse.bodyAsBytes()

        // 2. Get changes — now POST /sync/changes carrying a signed HLC tip
        // (sync-backend-relay-plan.md §5.1). Dev-mode bypass uses X-Dev-Owner +
        // X-Dev-Device; tip-signature verification is skipped (no real device key).
        val devTip = org.tenshu.sync.SignedHlcTip.create(
            context = context,
            ownerId = "user-push",
            deviceId = "device-1",
            hlc = HybridLogicalClock.create(context, 1500L, 0, "device-1"),
            signature = byteArrayOf()  // dev-mode skips verification
        )
        val changesResponse = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "user-push")
            header("X-Dev-Device", "device-1")
            url { parameters.append("since", "0") }
            setBody(serializer.serialize(devTip))
        }
        assertEquals(HttpStatusCode.OK, changesResponse.status)

        val response1 = parser.parse<org.tenshu.sync.ChangesResponse>(changesResponse.bodyAsBytes())
        assertEquals(1, response1.blocks.toList().size)
        assertEquals("block-1", response1.blocks.toList().first().blockId.get())

        // 3. Push a second block targeted at an entity, named by its EI-1 wire id (32 hex chars of
        // keyed HMAC, which the relay cannot compute) — exactly as a client stamps it.
        val entityId = org.tenshu.core.entity.DataId.StringDataId.create(context, "test", "id-1")
        val expectedHash = "0123456789abcdef0123456789abcdef"

        val entityBlock = SyncBlock.create(
            context = context,
            blockId = "block-entity",
            ownerId = "user-push",
            hlc = hlc,
            encryptedPayload = "heavy-data".encodeToByteArray(),
            targetEntityIdHash = expectedHash
        )
        client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "user-push")
            setBody(serializer.serialize(edgedPush(context, entityBlock)))
        }.bodyAsBytes()

        // 4. /sync/changes returns every block since the timestamp — the
        // old getChanges filter that excluded property blocks was removed
        // when `targetPropertyId` moved off the wire (zero-trust:
        // `docs/sync/threat-model.md` §"Property-ID exposure to relay").
        val devTip2 = org.tenshu.sync.SignedHlcTip.create(
            context = context,
            ownerId = "user-push",
            deviceId = "device-1",
            hlc = HybridLogicalClock.create(context, 2000L, 0, "device-1"),
            signature = byteArrayOf()
        )
        val changesResponse2 = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "user-push")
            header("X-Dev-Device", "device-1")
            url { parameters.append("since", "0") }
            setBody(serializer.serialize(devTip2))
        }
        val response2 =
            parser.parse<org.tenshu.sync.ChangesResponse>(changesResponse2.bodyAsBytes())
        assertEquals(2, response2.blocks.toList().size)

        // 5. /sync/fetch-entity-blocks returns every block scoped to the entity. The body is the
        // entity's WIRE ID, compared verbatim with what blocks are stored under (B3 wire spec §11.3) —
        // `propertyId` lives inside the encrypted payload, and the plaintext `DataId` never travels.
        val fetchResponse = client.post("/sync/fetch-entity-blocks") {
            contentType(ContentType.Text.Plain)
            header("X-Dev-Owner", "user-push")
            setBody(expectedHash)
        }

        assertEquals(HttpStatusCode.OK, fetchResponse.status)
        val fetched = parser.parse<org.tenshu.core.collection.list.TList<SyncBlock>>(
            fetchResponse.bodyAsBytes()
        ).toList()
        assertEquals(1, fetched.size)
        assertEquals("block-entity", fetched.first().blockId.get())

        // ⚠⚠ The pre-fix body — a serialised `DataId`, which carried a File's path in the clear — is
        // refused rather than hashed: no request may make the relay handle a plaintext path.
        val legacyBody = client.post("/sync/fetch-entity-blocks") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "user-push")
            setBody(serializer.serialize(entityId))
        }
        assertEquals(HttpStatusCode.BadRequest, legacyBody.status)
    }

    @Test
    fun testPairingHandshake() = testApplication {
        // Per sync-relay-api.md §3.3, POST /sync/pairing/response now requires a master
        // delegation that verifies against the realm's stored master public key — pre-seed
        // the realm so the verifier has the right key.
        val masterKey = "test-master-key".encodeToByteArray().copyOf(32)
        val realmStore = org.tenshu.sync.relay.mailbox.RealmStore().apply {
            createIfAbsent(
                org.tenshu.sync.relay.mailbox.RealmStore.RealmRecord(
                    ownerId = "user-handshake",
                    masterPublicKey = masterKey,
                    createdAt = 0L
                )
            )
        }
        application {
            testModule(
                config = RelayConfig(
                    devMode = true,
                    bindHost = "127.0.0.1",
                    port = 8080
                ),
                knownDevices = org.tenshu.sync.relay.auth.KnownDevicesStore(),
                realmStore = realmStore
            )
        }

        val context = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { context },
            extraConfigs = listOf(
                coreCommonModule,
                syncServiceCommonModule,
                identityServiceCommonModule
            )
        )
        context.components = mapOf(
            SchemaProvider.COMPONENT_ID to registry,
            DataFactory.COMPONENT_ID to registry
        )

        val parser = BinaryParser(registry, registry)

        // F7: the joiner posts the secret's hash and its name sealed — opaque bytes to the relay.
        val secretHash = "7".repeat(64)
        val sealedName = ByteArray(66) { (it * 5).toByte() }
        val b64 = java.util.Base64.getEncoder()
        // Workstream B: Device B's id is its A4 key's — the same key the host admits in step 3.
        val deviceBPubKey = ByteArray(32) { (0xB0 + it).toByte() }
        val deviceBId = org.tenshu.sync.common.deviceIdForPublicKey(deviceBPubKey)

        // 1. Device B posts request
        val requestResponse = client.post("/sync/pairing/request") {
            header(io.ktor.http.HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                """{
                    "ownerId": "user-handshake",
                    "pairingSecretHash": "$secretHash",
                    "deviceId": "$deviceBId",
                    "sealedDeviceName": "${b64.encodeToString(sealedName)}",
                    "publicKey": "${
                    java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
                }",
                    "signingPublicKey": "${
                    java.util.Base64.getEncoder().encodeToString(deviceBPubKey)
                }"
                }""".trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, requestResponse.status)
        requestResponse.bodyAsBytes() // Consume

        // 2. Device A polls and retrieves request — JWT-protected; X-Dev-Owner in dev mode.
        val pollResponse = client.get("/sync/pairing/requests") {
            header("X-Dev-Owner", "user-handshake")
        }
        assertEquals(HttpStatusCode.OK, pollResponse.status)

        // F7: JSON carrying exactly the hash, the sealed name and the two public keys — no field
        // the relay could have filled with a secret or a clear name.
        val requests = kotlinx.serialization.json.Json.parseToJsonElement(
            pollResponse.bodyAsBytes().decodeToString()
        ) as kotlinx.serialization.json.JsonArray
        val request = requests.single() as kotlinx.serialization.json.JsonObject
        assertEquals(
            setOf("deviceId", "sealedDeviceName", "publicKey", "signingPublicKey", "pairingSecretHash"),
            request.keys,
        )
        fun field(name: String) = (request[name] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals(deviceBId, field("deviceId"))
        assertEquals(b64.encodeToString(sealedName), field("sealedDeviceName"))
        assertEquals(secretHash, field("pairingSecretHash"))

        // 3. Device A posts response with master delegation. The host signs
        // (newDevicePublicKey || newDeviceId || epoch) with the master identity; the
        // relay verifies and registers Device B's public key in KnownDevicesStore.
        val epoch = 0L
        val delegationMessage = org.tenshu.sync.relay.auth.MasterDelegationVerifier
            .pairingResponseMessage(deviceBPubKey, deviceBId, epoch)
        val masterDelegation =
            org.tenshu.core.security.stubEd25519Signature(masterKey, delegationMessage)

        val approveResponse = client.post("/sync/pairing/response") {
            header(io.ktor.http.HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Dev-Owner", "user-handshake")
            setBody(
                """{
                    "pairingSecretHash": "$secretHash",
                    "encryptedMasterKey": "${
                    java.util.Base64.getEncoder().encodeToString(byteArrayOf(5, 6))
                }",
                    "newDevicePublicKey": "${
                    java.util.Base64.getEncoder().encodeToString(deviceBPubKey)
                }",
                    "newDeviceId": "$deviceBId",
                    "masterDelegation": "${
                    java.util.Base64.getEncoder().encodeToString(masterDelegation)
                }",
                    "epoch": "$epoch"
                }""".trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, approveResponse.status)
        approveResponse.bodyAsBytes() // Consume

        // 4. Device B retrieves response — by POST body, never a query string (F4/F7).
        val finalResponse = client.post("/sync/pairing/response/peek") {
            header(io.ktor.http.HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"pairingSecretHash": "$secretHash"}""")
        }
        assertEquals(HttpStatusCode.OK, finalResponse.status)
        val encryptedKey = finalResponse.bodyAsBytes()
        assertEquals(2, encryptedKey.size)
        assertEquals(5, encryptedKey[0])
    }
}

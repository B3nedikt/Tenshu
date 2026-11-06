package org.tenshu.sync.relay

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.auth.KnownDevicesStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stage B of `sync-backend-relay-plan.md` §4.1: `/sync/push` requires JWT auth in production
 * mode. These tests cover:
 *   - JWT-authed push happy path (production mode).
 *   - Missing/expired JWT in production rejects with 401.
 *   - block.ownerId ≠ JWT.iss rejects with 403.
 *   - Dev mode accepts X-Dev-Owner header (ensures the legacy fixture still works).
 */
class SyncPushAuthTest {

    private val devicePrivateKey = "device-stage-b-key".encodeToByteArray()
    private val devicePublicKey = devicePrivateKey  // stub-level: public == private

    private fun makeContext(): Pair<TContext, BinarySerializer> {
        val ctx = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(
                coreCommonModule,
                syncServiceCommonModule,
                identityServiceCommonModule
            )
        )
        ctx.components = mapOf(
            SchemaProvider.COMPONENT_ID to registry,
            DataFactory.COMPONENT_ID to registry
        )
        return ctx to BinarySerializer(registry)
    }

    private fun makeBlock(ctx: TContext, ownerId: String): SyncBlock = SyncBlock.create(
        context = ctx,
        blockId = "block-${ownerId}-1",
        ownerId = ownerId,
        hlc = HybridLogicalClock.create(ctx, 1_000L, 0, "device-stage-b"),
        encryptedPayload = "payload".encodeToByteArray()
    )

    private fun signToken(claims: JwtClaims): String =
        encodeJwt(claims) { msg -> stubEd25519Signature(devicePrivateKey, msg) }

    @Test
    fun production_pushWithValidJwt_succeeds() = testApplication {
        val store = KnownDevicesStore().apply {
            register("alice", "device-stage-b", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }

        val (ctx, serializer) = makeContext()
        val nowSec = System.currentTimeMillis() / 1000
        val token = signToken(JwtClaims("alice", "device-stage-b", nowSec - 5, nowSec + 60))

        val response = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(serializer.serialize(edgedPush(ctx, makeBlock(ctx, "alice"))))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        response.bodyAsBytes()
    }

    @Test
    fun production_pushWithoutAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }

        val (ctx, serializer) = makeContext()
        val response = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            setBody(serializer.serialize(edgedPush(ctx, makeBlock(ctx, "alice"))))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun production_pushWithMismatchedOwner_returns403() = testApplication {
        // JWT identifies Alice; block claims to belong to Mallory. Mismatch is a 403 — the
        // request is well-formed but the device is trying to push under another identity.
        val store = KnownDevicesStore().apply {
            register("alice", "device-stage-b", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }

        val (ctx, serializer) = makeContext()
        val nowSec = System.currentTimeMillis() / 1000
        val token = signToken(JwtClaims("alice", "device-stage-b", nowSec - 5, nowSec + 60))
        val malloryBlock = makeBlock(ctx, "mallory")

        val response = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(serializer.serialize(edgedPush(ctx, malloryBlock)))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun production_pushWithExpiredJwt_returns401() = testApplication {
        val store = KnownDevicesStore().apply {
            register("alice", "device-stage-b", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }

        val (ctx, serializer) = makeContext()
        // Issued and expired well in the past.
        val expiredToken = signToken(JwtClaims("alice", "device-stage-b", 100L, 200L))

        val response = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $expiredToken")
            setBody(serializer.serialize(edgedPush(ctx, makeBlock(ctx, "alice"))))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun devMode_pushWithDevOwnerHeader_succeeds() = testApplication {
        // Dev-mode bypass: X-Dev-Owner is the legacy/test shape. RelayConfig.validate()
        // forbids combining devMode=true with a non-loopback bind, so this can't ship.
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }

        val (ctx, serializer) = makeContext()
        val response = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(serializer.serialize(edgedPush(ctx, makeBlock(ctx, "alice"))))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        response.bodyAsBytes()
    }

    @Test
    fun devMode_acceptsJwtToo() = testApplication {
        // Dev mode is additive: it ALSO accepts JWT auth, not just X-Dev-Owner. This keeps
        // production-like tests possible without flipping the config gate.
        val store = KnownDevicesStore().apply {
            register("alice", "device-stage-b", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }

        val (ctx, serializer) = makeContext()
        val nowSec = System.currentTimeMillis() / 1000
        val token = signToken(JwtClaims("alice", "device-stage-b", nowSec - 5, nowSec + 60))
        val response = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(serializer.serialize(edgedPush(ctx, makeBlock(ctx, "alice"))))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        response.bodyAsBytes()
    }
}

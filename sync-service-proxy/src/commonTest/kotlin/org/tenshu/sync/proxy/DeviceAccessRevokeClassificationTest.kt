package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.sync.common.DeviceAccessRevocation
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import org.tenshu.core.common.RelayRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * How `RestSyncRelay.revokeDeviceAccess` reads the relay's answer (`sync-entity-identity.md` §5b): which refusals are
 * retried and which are terminal. ⚠ A `426` is protocol skew, cleared by an update — it was once filed as terminal.
 */
class DeviceAccessRevokeClassificationTest {

    private val context = TestProxy(
        schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
    ).tContext

    private suspend fun answerTo(status: HttpStatusCode, body: String = ""): SyncRelay.DeviceAccessRevokeOutcome =
        RestSyncRelay(
            baseUrl = "https://relay.example",
            httpClient = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) { install(ContentNegotiation) { json() } },
            context = context,
            authTokenProvider = { null },
            ownerIdProvider = { null },
        ).revokeDeviceAccess("owner", "device", "1:0:dev", DeviceAccessRevocation.Authority.MASTER, ByteArray(64))

    @Test
    fun a426_isUpgradeRequired_notTerminal() = runTest {
        assertIs<SyncRelay.DeviceAccessRevokeOutcome.UpgradeRequired>(answerTo(HttpStatusCode.UpgradeRequired))
    }

    @Test
    fun a404_isUnsupported_andKept() = runTest {
        assertEquals(SyncRelay.DeviceAccessRevokeOutcome.Unsupported, answerTo(HttpStatusCode.NotFound))
    }

    @Test
    fun a5xx_or429_isRetried() = runTest {
        assertIs<SyncRelay.DeviceAccessRevokeOutcome.Retry>(answerTo(HttpStatusCode.ServiceUnavailable))
        assertIs<SyncRelay.DeviceAccessRevokeOutcome.Retry>(answerTo(HttpStatusCode.TooManyRequests))
        assertIs<SyncRelay.DeviceAccessRevokeOutcome.Retry>(answerTo(HttpStatusCode.RequestTimeout))
    }

    /** `applied` decides drop-vs-superseded, so a `200` that does not say which is retried, never read as done. */
    @Test
    fun a200_withAnUnreadableBody_isRetried_andAReadableOneIsDone() = runTest {
        assertIs<SyncRelay.DeviceAccessRevokeOutcome.Retry>(answerTo(HttpStatusCode.OK, "<html>gateway</html>"))
        assertEquals(
            SyncRelay.DeviceAccessRevokeOutcome.Done(applied = false),
            answerTo(HttpStatusCode.OK, """{"applied":false}"""),
        )
    }

    @Test
    fun a401_or400_isRefused_withItsStatus() = runTest {
        assertEquals(401, assertIs<SyncRelay.DeviceAccessRevokeOutcome.Refused>(answerTo(HttpStatusCode.Unauthorized)).status)
        assertEquals(400, assertIs<SyncRelay.DeviceAccessRevokeOutcome.Refused>(answerTo(HttpStatusCode.BadRequest)).status)
    }

    /** Only the body token reads as "already gone" — the route's other 401, a bad signature, stays a refusal. */
    @Test
    fun a401_isNotRegistered_onlyWithTheToken() = runTest {
        assertIs<SyncRelay.DeviceAccessRevokeOutcome.NotRegistered>(
            answerTo(HttpStatusCode.Unauthorized, RelayRefusal.body(RelayRefusal.DEVICE_NOT_REGISTERED, "Unknown device")),
        )
        assertIs<SyncRelay.DeviceAccessRevokeOutcome.Refused>(answerTo(HttpStatusCode.Unauthorized, "Invalid revocation signature"))
        assertIs<SyncRelay.DeviceAccessRevokeOutcome.Refused>(answerTo(HttpStatusCode.Unauthorized, "Unknown device"))
    }
}

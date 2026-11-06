package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A relay URL configured with a trailing `/` must not produce `//share/<id>`: Ktor on the relay
 * still routes that, but the Caddy access-log rules anchored on `^/share/` miss it
 * (relay-metadata-minimisation F4, `docs/how-to/server-logging.md`). The clients trim it.
 */
class RelayBaseUrlTrailingSlashTest {

    private val context = TestProxy(
        schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
    ).tContext
    private val paths = mutableListOf<String>()
    private val client = HttpClient(MockEngine { request ->
        paths += request.url.encodedPath
        respond("", HttpStatusCode.NotFound)
    })

    @Test
    fun capabilityUrlsCarryASingleSlash() = runTest {
        val base = "https://relay.example/"
        RestShareRelayClient(baseUrl = base, httpClient = client, context = context, authTokenProvider = { null })
            .deleteShare("aB3_-xYz09QwErTyUiOpAs")
        RestSyncRelay(
            baseUrl = base, httpClient = client, context = context,
            authTokenProvider = { null }, ownerIdProvider = { null },
        ).pickupGrant("fedcba98765432100123456789abcdef")

        assertEquals(
            listOf("/share/aB3_-xYz09QwErTyUiOpAs", "/sync/grant/fedcba98765432100123456789abcdef"),
            paths,
        )
    }
}

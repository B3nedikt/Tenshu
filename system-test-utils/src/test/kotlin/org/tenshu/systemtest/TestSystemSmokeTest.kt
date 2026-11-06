package org.tenshu.systemtest

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke: the framework can boot a real `Application.module()` in-process and
 * its root endpoint replies. If this fails, every other SystemTest will too —
 * so this is the cheapest possible guard on the testApplication wiring.
 */
class TestSystemSmokeTest {

    @Test fun relay_isReachable_throughTestApplicationClient() = runTest {
        val system = TestSystem.builder().build()
        try {
            val response = system.relayHttpClient.get("/")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue("Tenshu Sync Relay" in response.bodyAsText())
        } finally {
            system.shutdown()
        }
    }
}

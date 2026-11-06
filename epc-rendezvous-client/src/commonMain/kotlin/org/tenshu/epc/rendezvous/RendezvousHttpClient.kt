package org.tenshu.epc.rendezvous

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Build an [HttpClient] configured for [RendezvousRelayClient] over the given
 * [engine] — it installs the JSON content negotiation the `/sync/epc` frame
 * envelopes need (`RendezvousRelayClient` calls `response.body<…>()`). The
 * engine is the platform's choice: iOS supplies Darwin
 * ([darwinRendezvousHttpClient]); JVM/Android typically OkHttp; tests a mock or
 * the test-application client. Keeping the plugin config in one place means
 * every platform's client negotiates frames identically.
 */
fun rendezvousHttpClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) { install(ContentNegotiation) { json() } }

package org.tenshu.epc.rendezvous

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * A Darwin-engine [HttpClient] for [RendezvousRelayClient] on iOS, JSON-ready
 * via [rendezvousHttpClient]. Lets the Swift app obtain a working rendezvous
 * transport without constructing a Ktor client (Darwin engine + plugins) from
 * Swift — `URLSession`-backed, the platform's native HTTP stack.
 */
fun darwinRendezvousHttpClient(): HttpClient = rendezvousHttpClient(Darwin.create())

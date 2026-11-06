package org.tenshu.sync.relay.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.common.RELAY_PROTOCOL_MIN_SUPPORTED_VERSION
import org.tenshu.core.common.RELAY_PROTOCOL_SUPPORTED_RANGE
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.sync.relay.routeTemplate

/**
 * Read a binary request body and parse it, answering **400** if it does not parse.
 *
 * Returns null after writing the response — callers `return@post`, the same shape as
 * [requireOwnerId].
 *
 * ⚠⚠ **Do not go back to a bare `parser.parse(call.receive())`.** [BinaryParser] throws on a
 * truncated or corrupted envelope (`"Buffer overflow parsing value of type tenshu/core/string"`),
 * and with nothing catching it at the route the throw reached the catch-all `exception<Throwable>`
 * mapping in `Application.kt` — which answers **500 internal_error** and logs at ERROR as an
 * unhandled fault. Both halves are wrong:
 *
 *  - A client that sent bytes the relay cannot parse is told the *relay* broke, so a retry loop
 *    keeps re-sending a body that can never succeed.
 *  - The relay's ERROR log is where a real fault is supposed to be visible, and any JWT-holding
 *    client could fill it at will with its own malformed uploads.
 *
 * The relay stores ciphertext and never inspects what a body *means* (House Rule #1), so whether
 * the envelope parses at all is the only judgement it can make about a payload — which is exactly
 * why it should make that one precisely. The parse failure is logged at debug: it is a client
 * error, not an operator's problem.
 *
 * ⚠ [CancellationException] is rethrown rather than reported as a bad request; a client that
 * disconnects mid-body has not sent a malformed one. This mirrors the `StatusPages` handler.
 */
internal suspend inline fun <reified T : Data> receiveParsed(
    call: ApplicationCall,
    parser: BinaryParser
): T? {
    val bytes = call.receive<ByteArray>()
    return try {
        parser.parse<T>(bytes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        call.application.log.debug(
            "Malformed body on ${call.routeTemplate()} ({} bytes)", bytes.size, e
        )
        call.respond(HttpStatusCode.BadRequest, "malformed request body")
        null
    }
}

/**
 * The **per-request** relay-protocol gate (CR-2 B3 wire spec §3; `core-schema-evolution.md` §7.5):
 * every B3 body carries `relayProtocolVersion`, and a value outside
 * [RELAY_PROTOCOL_SUPPORTED_RANGE] is answered **426**, naming the side that has to move. A missing
 * version ([declared] null) is itself a 426, as at genesis.
 *
 * Returns `false` after having responded.
 */
internal suspend fun relayProtocolSupported(call: ApplicationCall, declared: Long?): Boolean {
    if (declared != null && declared in RELAY_PROTOCOL_SUPPORTED_RANGE.first.toLong()..RELAY_PROTOCOL_SUPPORTED_RANGE.last.toLong()) {
        return true
    }
    val remedy = when {
        declared == null -> "this client did not declare a relayProtocolVersion and must be updated"
        declared < RELAY_PROTOCOL_MIN_SUPPORTED_VERSION -> "this client must be updated"
        else -> "this relay must be redeployed (the client is newer than the deployment)"
    }
    call.respond(
        HttpStatusCode.UpgradeRequired,
        "Client relay protocol version ${declared ?: "(none)"} is outside the range this relay " +
            "accepts ($RELAY_PROTOCOL_MIN_SUPPORTED_VERSION..$RELAY_PROTOCOL_VERSION) — $remedy",
    )
    return false
}

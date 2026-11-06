package org.tenshu.commerce.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.tenshu.commerce.store.CommerceStore

/**
 * `GET /health` — **can this service serve**, not merely *is the process up*.
 *
 * Those are different claims, and conflating them has already cost a diagnosis. `GET /` answers a
 * static string, so a 200 there proves only that Ktor is listening; on 2026-08-15, after the
 * Postgres container was recreated underneath a running commerce, `GET /` was a cheerful 200 while
 * the app held ten dead sockets and had **zero** live connections to the database
 * (`drive-upload-stop-and-reclamation.md` §4.6). This route round-trips the store, so that state
 * reads as **503**.
 *
 * Ktor ships no first-party health plugin — the ecosystem options (KHealth, ktor-health-check,
 * Cohort) are all third-party, and a `SELECT 1` behind one route does not justify a dependency on
 * this trust boundary.
 *
 * **Deliberately says almost nothing.** It is unauthenticated, because a monitor cannot hold a
 * session, so the body is a fixed `ok` / `degraded` — no exception text, no driver or version
 * strings, no counts. The reason lands in the server log where it is already privileged. A prober
 * learns liveness and nothing it could not learn by trying a real request.
 *
 * ⚠ It can be slow rather than fail: an exhausted or stalled pool blocks on Hikari's
 * `connectionTimeout` (30 s by default) instead of throwing. That is honest — a health check that
 * hangs *is* reporting ill-health — but it means the **caller** owns the deadline. Give any monitor
 * an explicit timeout (`curl --max-time`), or a stall reads as "no answer yet" rather than "down".
 */
fun Route.configureHealthRouting(store: CommerceStore) {
    get("/health") {
        val failure = runCatching { store.probe() }.exceptionOrNull()
        if (failure == null) {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "ok"))
        } else {
            application.log.error("Health probe failed — the store is unreachable", failure)
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(status = "degraded"))
        }
    }
}

@Serializable
data class HealthResponse(val status: String)

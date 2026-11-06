package org.tenshu.sync.relay.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * `GET /health` — **can this relay accept a write**, not merely *is the process up*.
 *
 * `GET /` answers a static string, so it reports a cheerful 200 while the blob volume is 100 %
 * full, every `POST /sync/push` returns 500, and reads are perfect. From the outside that is
 * indistinguishable from health (`drive-upload-stop-and-reclamation.md` §4.6). This route writes a
 * few bytes to the blob volume and removes them, so a full disk reads as **503**.
 *
 * ⚠⚠ **The database is not probed through SQL**, and that is the difference from commerce's
 * `/health`. [org.tenshu.sync.relay.storage.RelayDatabase] serialises every caller on one
 * `ReentrantLock` over a single connection, so even a `SELECT 1` would queue behind whatever sync
 * request holds it — coupling the health signal to *load*, and reporting a merely busy relay as
 * down. What is probed instead is its **volume**, with a lock-free file write.
 *
 * ⚠⚠ **The volume probe is explicit.** Probing only the blob store, on the reasoning that "a full
 * volume breaks the SQLite write too, so probing the disk covers both", holds only while the blobs
 * and `relay.db` share a volume. With [org.tenshu.sync.relay.storage.S3BlobStore] the blob probe
 * writes to a bucket in another datacentre, and the volume still holding the database and every
 * index row would go unwatched. So the probes are a **list**, and every one of them must pass
 * (`hosting-provider-economics.md` D10).
 *
 * ⚠ It reports the **binary** question. Whether the disk is at 70 % or 95 % is monitoring's job
 * (ST-0), not this route's — a health check that goes red early is a health check people learn to
 * ignore.
 *
 * ⚠ **A relay with no `DATABASE_URL` has no blob store at all** (in-memory dev mode). It answers
 * `200 {"status":"ok","durable":false}` — nothing to check, and saying so beats both a false green
 * and a false red. A production relay always has one; `durable:false` in production means
 * `DATABASE_URL` is unset: every restart wipes the registrations, and every paired device gets a 401.
 */
fun Route.configureHealthRouting(probes: List<HealthProbe>) {
    get("/health") {
        if (probes.isEmpty()) {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "ok", durable = false))
            return@get
        }
        // Every probe, not the first failure: a relay whose bucket is fine and whose volume is full
        // is degraded, and so is the reverse. `firstNotNullOfOrNull` would stop early, which is
        // fine for the verdict but loses the other probe's diagnosis from the log.
        val failures = probes.mapNotNull { probe ->
            runCatching { probe.probe() }.exceptionOrNull()?.let { probe.name to it }
        }
        if (failures.isEmpty()) {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "ok", durable = true))
        } else {
            failures.forEach { (name, failure) ->
                application.log.error("Health probe '$name' failed — it rejected a write", failure)
            }
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                HealthResponse(status = "degraded", durable = true),
            )
        }
    }
}

/**
 * One named durable-write check. The name reaches the **log only** — never the response, which
 * stays binary for the reason [HealthResponse] documents.
 */
class HealthProbe(val name: String, val probe: () -> Unit)

/**
 * ⚠ Says nothing a prober could not learn by attempting a real write. No free-space figure, no path,
 * no exception text — `/health` is unauthenticated, because a monitor holds no device JWT.
 */
@Serializable
data class HealthResponse(val status: String, val durable: Boolean)

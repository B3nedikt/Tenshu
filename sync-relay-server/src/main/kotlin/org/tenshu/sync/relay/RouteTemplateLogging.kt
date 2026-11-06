package org.tenshu.sync.relay

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.path
import io.ktor.util.AttributeKey

/**
 * The **route template** a call matched (`/share/{shareId}`), recorded so that every log line
 * about a request can name the route without naming the request.
 *
 * ⚠⚠ **Never log `request.path()` or `request.uri` on the relay** (relay-metadata-minimisation
 * F4; `docs/how-to/server-logging.md`). Half the relay's routes carry a capability in a path
 * segment — a share id, a grant-mailbox address, an EPC session id, an invite id, a content ref —
 * and knowing one is what lets a caller read or delete the thing it names. The template keeps
 * the route family, which is all an operator needs to read a log, and drops the value.
 *
 * Written by [recordRouteTemplates] when routing resolves a call; a call routing never resolved
 * (a 404 for a path nothing serves, a scanner) has none and reads as [UNMATCHED_ROUTE] — never as
 * its raw path, which is attacker-chosen text and can be anything.
 */
internal val RouteTemplateKey: AttributeKey<String> = AttributeKey("tenshu.relay.routeTemplate")

/** What a log line names when routing matched nothing. Fixed text, never the request's path. */
internal const val UNMATCHED_ROUTE = "<unmatched>"

/**
 * Record the matched route's template on every routed call. Install it before the server takes
 * traffic. The template is read off the resolved [io.ktor.server.routing.RoutingNode] via Ktor's
 * own `Route.path`, which renders only path selectors, so `rateLimit { }` and method selectors
 * never appear in it.
 *
 * ⚠ `RoutingCall.attributes` is the engine call's attribute map, so the value is visible to
 * `CallLogging` and `StatusPages`, which see the outer call rather than the routing one.
 */
internal fun Application.recordRouteTemplates() {
    monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
        call.attributes.put(RouteTemplateKey, call.route.path.ifEmpty { "/" })
    }
}

/** The template this call matched, or [UNMATCHED_ROUTE]. Safe to put in any log line. */
internal fun ApplicationCall.routeTemplate(): String =
    attributes.getOrNull(RouteTemplateKey) ?: UNMATCHED_ROUTE

/**
 * The one-line-per-request call log: `200 OK: GET /share/{shareId} in 12ms`.
 *
 * Same fields as Ktor's default format (status, method, route, duration) with the concrete path
 * replaced by [routeTemplate]. Still no query string, client IP or header — keep it that way.
 */
internal fun formatCallLogLine(call: ApplicationCall): String {
    val status = call.response.status()?.let(HttpStatusCode::toString) ?: "Unhandled"
    return "$status: ${call.request.httpMethod.value} ${call.routeTemplate()} in ${call.processingTimeMillis()}ms"
}

package org.tenshu.logging.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import org.tenshu.logging.common.ErrorReport
import org.tenshu.logging.common.ErrorReportUploader
import org.tenshu.logging.common.ReportEvent

/**
 * Phase 6 of `docs/proposals/core-error-channel.md` (§4.3): real HTTP impl
 * of [ErrorReportUploader]. POSTs `/reports` against the configured App
 * Console base URL with the JSON payload below.
 *
 * **Anonymous transport.** No JWT, no Authorization header — attaching
 * per-user identity defeats the anonymity goal (proposal §4.3, threat-model
 * T-L-01). The Console operator may rate-limit by IP / payload shape if it
 * wants; that's a Console-side concern.
 *
 * **Wire schema.** [ReportWireBody] mirrors [ErrorReport] verbatim;
 * [ReportEventWire] mirrors the already-redacted [ReportEvent]. Keep
 * additive — future Console schemas should default-tolerate new optional
 * fields, and the proposal explicitly notes the wire calcifies as a
 * backwards-compat contract (§1.3).
 *
 * Sibling of [org.tenshu.sync.proxy.RestShareRelayClient]'s anonymous-share
 * GET path and the sync relay client; same Ktor `HttpClient` + serialization
 * plugin (`ContentNegotiation` + `kotlinx.serialization.json`).
 *
 * **Throws on real failure.** Any non-2xx response or thrown
 * `Exception` is rethrown to the caller (the ViewModel surfaces it as
 * inline status). One-shot — no retry by this port.
 */
class HttpErrorReportUploader(
    private val appConsoleUrl: String,
    private val httpClient: HttpClient,
) : ErrorReportUploader {

    override val isConfigured: Boolean
        get() = appConsoleUrl.isNotBlank()

    override suspend fun upload(report: ErrorReport) {
        val response: HttpResponse = httpClient.post("$appConsoleUrl/reports") {
            contentType(ContentType.Application.Json)
            setBody(
                ReportWireBody(
                    uploadId = report.uploadId,
                    events = report.events.map { it.toWire() },
                )
            )
        }
        if (!response.status.isSuccess()) {
            error("App Console upload failed: HTTP ${response.status.value}")
        }
    }

    private fun ReportEvent.toWire(): ReportEventWire = ReportEventWire(
        errorTypeId = errorTypeId,
        operationId = operationId,
        callstackDepth = callstackDepth,
        capturedAtMillis = capturedAtMillis,
        appId = appId,
        appVersion = appVersion,
        osLabel = osLabel,
    )
}

@Serializable
internal data class ReportWireBody(
    val uploadId: String,
    val events: List<ReportEventWire>,
)

@Serializable
internal data class ReportEventWire(
    val errorTypeId: String,
    val operationId: String,
    val callstackDepth: Int,
    val capturedAtMillis: Long,
    val appId: String,
    val appVersion: String,
    val osLabel: String,
)

/**
 * Phase 6 fallback for the "Console URL not configured" path. The Errors
 * screen's "Send error report" CTA stays disabled when `isConfigured` is
 * `false`, so [upload] should be unreachable in production — but a defensive
 * no-op is friendlier than a crash if a wiring mishap routes through here.
 */
class NoOpErrorReportUploader : ErrorReportUploader {
    override val isConfigured: Boolean get() = false
    override suspend fun upload(report: ErrorReport) {
        // Intentional no-op — proposal §4.3 "stub upload endpoint" for the
        // pre-Console-milestone window. Future hooks (a local audit log of
        // attempted uploads, say) belong in a separate decorator, not here.
    }
}

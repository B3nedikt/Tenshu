package org.tenshu.logging.common

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Phase 6 of `docs/proposals/core-error-channel.md` (§4.3 — App-Console-gated
 * user-initiated upload). Port for the proxy-side HTTP client that ships an
 * [ErrorReport] to the App Console.
 *
 * **Always user-initiated.** Implementations are invoked from the
 * Settings → Errors screen's "Send error report" button, never on a timer
 * or in the background. No auto-send.
 *
 * **Anonymous.** The wire payload carries [ReportEvent] rows already stripped
 * of `deviceId` and `internalDetail` ([toReportEvent] is the seam), plus an
 * opaque [ErrorReport.uploadId] for **server-side dedup only** — minted
 * per-upload, never persisted, never reused.
 *
 * **Transport.** TLS (same posture as the sync relay); no JWT (the proposal
 * explicitly treats the Console as a separate trust class — no per-user
 * authentication on the upload path because attaching user identity defeats
 * the anonymity goal). The Console operator may rate-limit by IP / payload
 * shape; that's a Console-side concern.
 *
 * **Implementations must not throw on success.** A typed exception thrown
 * for a real failure (HTTP error, network outage) bubbles up to the
 * ViewModel which surfaces it as inline status; the upload is one-shot, no
 * retry by this port.
 */
interface ErrorReportUploader {

    /** Whether an upload destination is configured. The "Send" CTA disables when this is `false`. */
    val isConfigured: Boolean

    /**
     * POSTs [report] to the configured Console endpoint. Throws on a real
     * failure (network / 5xx / malformed). 2xx returns successfully.
     */
    suspend fun upload(report: ErrorReport)
}

/**
 * Wire-stable payload for a single user-initiated diagnostic upload.
 *
 * The shape is a backwards-compat contract with future Console versions —
 * the proposal §1.3 calls this out: "the Console schema calcifies later;
 * design the wire today while there is one consumer." Keep additive: future
 * fields should default to absent on the receiver side.
 */
data class ErrorReport(
    /**
     * Server-side dedup id — opaque, per-upload, never persisted locally.
     * The Console operator can collapse retries of the same upload; cannot
     * link multiple uploads from the same user.
     */
    val uploadId: String,
    val events: List<ReportEvent>,
) {

    companion object {

        /**
         * Mint a fresh report from a list of locally-stored events. The
         * caller passes the rows currently visible on the Errors screen;
         * each is redacted via [toReportEvent] at this seam, and a new
         * one-shot [uploadId] is generated. **No persistent identifier
         * leaks into [uploadId].**
         */
        @OptIn(ExperimentalUuidApi::class)
        fun create(events: List<UploadableEvent>): ErrorReport = ErrorReport(
            uploadId = Uuid.random().toString(),
            events = events.map { it.toReportEvent() },
        )
    }
}

/**
 * Redacted view of an `ErrorEvent`. **Two omissions vs. the local entity**
 * (proposal §5 PII-by-construction; threat-model T-L-03):
 *  - `deviceId` is **dropped** — useful for local cross-device dedup, but
 *    serves no purpose Console-side and is identifying.
 *  - `internalDetail` is **dropped** — proxy-local diagnostic text kept
 *    only for the local Settings → Errors detail view; never crosses the
 *    upload seam.
 *
 * Every other field is preserved verbatim because they're typed identifiers
 * or coarse platform metadata (`errorTypeId` / `operationId` / app /
 * version / OS / `callstackDepth` / `capturedAt`).
 */
data class ReportEvent(
    val errorTypeId: String,
    val operationId: String,
    val callstackDepth: Int,
    val capturedAtMillis: Long,
    val appId: String,
    val appVersion: String,
    val osLabel: String,
)

/**
 * Common interface satisfied by both the entity (`Entity<ErrorEvent>`) and
 * the UI-side `ErrorEventSummary` data class. Lets the Phase 5 screen ship
 * a typed list straight into [ErrorReport.create] without re-fetching
 * entities, while keeping the redaction at the upload seam testable in
 * isolation.
 */
interface UploadableEvent {
    val errorTypeId: String
    val operationId: String
    val callstackDepth: Int
    val capturedAtMillis: Long
    val appId: String
    val appVersion: String
    val osLabel: String
}

/**
 * The single redaction seam. **Do not bypass.** Any future field added to
 * `ErrorEvent` that the Console doesn't strictly need must be added to the
 * redaction list here, not silently included.
 */
fun UploadableEvent.toReportEvent(): ReportEvent = ReportEvent(
    errorTypeId = errorTypeId,
    operationId = operationId,
    callstackDepth = callstackDepth,
    capturedAtMillis = capturedAtMillis,
    appId = appId,
    appVersion = appVersion,
    osLabel = osLabel,
)

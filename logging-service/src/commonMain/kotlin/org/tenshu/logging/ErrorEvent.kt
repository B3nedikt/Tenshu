package org.tenshu.logging

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * One captured exception that escaped an [org.tenshu.core.common.proxy.executor.OperationImplementation]
 * body — Phase 5 of `docs/proposals/core-error-channel.md` §4.1.
 *
 * Recorded by `:logging-service-proxy`'s `ErrorEventRecorder` from
 * `LocalTaskExecutor`'s single catch-all and stored in the realm-scoped
 * [LoggingService.events] relation. `Syncable(Eager)` so devices in the
 * same realm converge on the same diagnostic history; `LastWriterWins` is
 * vacuously the right merge — each event is keyed by a unique [eventId].
 *
 * **PII by construction (proposal §5).** Every field except [internalDetail]
 * carries typed identifiers or platform metadata only. [internalDetail] is
 * optional and present **only** when [errorTypeId] resolves to
 * `tenshu/core/error/internal` — i.e. the catch-all wrapped an unexpected
 * exception whose `e.message` we kept for local debugging. It is **redacted
 * at the upload seam** (Phase 6); the Settings → Errors screen renders it
 * locally so the user sees exactly what would *not* be sent.
 */
interface ErrorEvent : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable, globally-unique id (UUID). The DataId provider keys on this. */
    val eventId: TString
        get() = getField(Fields.EVENT_ID)

    /**
     * The typed [org.tenshu.core.TError]'s schema id — e.g.
     * `tenshu/core/error/dispatch/missing-parameter`,
     * `tenshu/core/error/internal`. The Settings → Errors UI looks the id
     * up in its localised label catalogue (ADR 0016 anchor #4).
     */
    val errorTypeId: TString
        get() = getField(Fields.ERROR_TYPE_ID)

    /**
     * Best-effort: the operation id at the top of the callstack when the
     * exception escaped. Empty string when the catch fired outside any
     * dispatched op (e.g. parser failure on an unrecognised wire frame).
     * The platform never reads inner-callstack ids or entity ids — only
     * the top-level dispatch site.
     */
    val operationId: TString
        get() = getField(Fields.OPERATION_ID)

    /**
     * Depth of the callstack at the catch site — not the ids. Useful for
     * "this op chain was N entries deep when it broke" debug context
     * without ever reading what was in those entries.
     */
    val callstackDepth: TInteger
        get() = getField(Fields.CALLSTACK_DEPTH)

    /**
     * Wall-clock proxy time when the catch fired. [UnixInstant] is the
     * codebase's existing milliseconds-since-epoch type (`Instant : TInteger`
     * with a wall-clock marker schema).
     */
    val capturedAt: UnixInstant
        get() = getField(Fields.CAPTURED_AT)

    /**
     * The proxy's own device id. Useful for cross-device dedup on the local
     * realm-synced log; **stripped at the upload seam** (Phase 6) so the
     * Console never sees it. See proposal §4.3.
     */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** Which Tenshu-enabled app surface — `"control"`, `"drive"`, etc. */
    val appId: TString
        get() = getField(Fields.APP_ID)

    /** App version string at capture time. */
    val appVersion: TString
        get() = getField(Fields.APP_VERSION)

    /** Coarse OS label — e.g. `"Android 14"`, `"macOS 14"`, `"Linux/x86_64"`. No model strings. */
    val osLabel: TString
        get() = getField(Fields.OS_LABEL)

    /**
     * Proxy-local detail kept for `tenshu/core/error/internal` only.
     * **Never crosses the Phase 6 upload boundary** (proposal §5,
     * threat-model T-L-03). The Diagnostics screen reads it; the Console
     * never sees it.
     */
    val internalDetail: TString?
        get() = getOptionalField(Fields.INTERNAL_DETAIL)

    companion object {
        const val ID = "tenshu/logging/error-event"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // `Syncable(Eager)` so the diagnostic history converges across
            // every device in the realm — same trust model as `Notification`,
            // and load-bearing for "see the error my phone hit on my desktop".
            // Each event is identified by a globally-unique `eventId`, so the
            // default `LastWriterWins` merge is vacuous (no two devices ever
            // produce a block for the same `eventId`).
            traits = listOf(ErrorEventDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.EVENT_ID)
                string(Fields.ERROR_TYPE_ID)
                string(Fields.OPERATION_ID)
                int(Fields.CALLSTACK_DEPTH)
                timestamp(Fields.CAPTURED_AT)
                string(Fields.DEVICE_ID)
                string(Fields.APP_ID)
                string(Fields.APP_VERSION)
                string(Fields.OS_LABEL)
                string(Fields.INTERNAL_DETAIL, isOptional = true)
            }
        )
    }

    object Fields {
        const val EVENT_ID = "$ID/event-id"
        const val ERROR_TYPE_ID = "$ID/error-type-id"
        const val OPERATION_ID = "$ID/operation-id"
        const val CALLSTACK_DEPTH = "$ID/callstack-depth"
        const val CAPTURED_AT = "$ID/captured-at"
        const val DEVICE_ID = "$ID/device-id"
        const val APP_ID = "$ID/app-id"
        const val APP_VERSION = "$ID/app-version"
        const val OS_LABEL = "$ID/os-label"
        const val INTERNAL_DETAIL = "$ID/internal-detail"
    }
}

val Entity<out ErrorEvent>.eventId: TString
    get() = getFieldValue(ErrorEvent.Fields.EVENT_ID)

val Entity<out ErrorEvent>.errorTypeId: TString
    get() = getFieldValue(ErrorEvent.Fields.ERROR_TYPE_ID)

val Entity<out ErrorEvent>.operationId: TString
    get() = getFieldValue(ErrorEvent.Fields.OPERATION_ID)

val Entity<out ErrorEvent>.callstackDepth: TInteger
    get() = getFieldValue(ErrorEvent.Fields.CALLSTACK_DEPTH)

val Entity<out ErrorEvent>.capturedAt: UnixInstant
    get() = getFieldValue(ErrorEvent.Fields.CAPTURED_AT)

val Entity<out ErrorEvent>.deviceId: TString
    get() = getFieldValue(ErrorEvent.Fields.DEVICE_ID)

val Entity<out ErrorEvent>.appId: TString
    get() = getFieldValue(ErrorEvent.Fields.APP_ID)

val Entity<out ErrorEvent>.appVersion: TString
    get() = getFieldValue(ErrorEvent.Fields.APP_VERSION)

val Entity<out ErrorEvent>.osLabel: TString
    get() = getFieldValue(ErrorEvent.Fields.OS_LABEL)

val Entity<out ErrorEvent>.internalDetail: TString?
    get() = getFieldValue<TString>(ErrorEvent.Fields.INTERNAL_DETAIL)

interface ErrorEventDataIdProvider : CompositeDataIdProvider<ErrorEvent> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/logging/error-event/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = ErrorEvent.Fields.EVENT_ID
        )
    }
}

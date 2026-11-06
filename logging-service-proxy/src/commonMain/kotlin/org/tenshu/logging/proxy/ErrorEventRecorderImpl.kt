package org.tenshu.logging.proxy

import org.tenshu.core.InternalError
import org.tenshu.core.TError
import org.tenshu.core.common.proxy.ErrorEventRecorder
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.number.toTInteger
import org.tenshu.core.time.clock
import org.tenshu.core.toTString
import org.tenshu.logging.ErrorEvent
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Phase 5 of `docs/proposals/core-error-channel.md`: persists every typed
 * `TError` that escapes an impl body into the realm-scoped
 * [org.tenshu.logging.LoggingService.events] relation, as a Syncable
 * [ErrorEvent].
 *
 * Captured fields:
 *  - **errorTypeId** — the typed error's schema id (e.g.
 *    `tenshu/core/error/dispatch/missing-parameter`). The Settings → Errors
 *    UI looks this up in the localised label catalogue (ADR 0016 anchor #4).
 *  - operationId + callstackDepth — top-level dispatch site only;
 *    inner-callstack ids and entity ids are deliberately not read
 *    (proposal §4.2). Empty string when the catch fired outside any dispatched
 *    op (parser failure on the wire, etc.).
 *  - capturedAt — proxy wall-clock at the moment of the catch.
 *  - deviceId / appId / appVersion / osLabel — non-PII platform metadata
 *    supplied by the platform DI at construction.
 *  - internalDetail — kept only when [error] resolves to [InternalError]
 *    (the catch-all wrapped an unexpected `Throwable`). It is redacted at
 *    the Phase 6 upload seam (proposal §5, threat-model T-L-03).
 *
 * The session [ExecutionContext] is captured at construction so the recorder
 * can save into the realm DB without a per-request hand-off. `Syncable(Eager)`
 * on the entity drives propagation; nothing to do here beyond `saveData`.
 *
 * **Never throws.** A bad save (e.g. realm not yet bootstrapped) is silently
 * swallowed by the catch-all layer above; a propagated throw would push an
 * unrelated `InternalError` onto the wire reply.
 */
internal class ErrorEventRecorderImpl(
    private val sessionExecutionContext: ExecutionContext,
    private val deviceId: String,
    private val appId: String,
    private val appVersion: String,
    private val osLabel: String,
) : ErrorEventRecorder {

    @OptIn(ExperimentalUuidApi::class)
    override fun record(error: TError, operationId: String, callstackDepth: Int) {
        val context = sessionExecutionContext.context
        val capturedAt = context.clock().nowEpochMillis()
        val internalDetail = (error as? InternalError)?.internalDetail?.get()

        val event = Entity.create<ErrorEvent>(
            context = context,
            composite = context.dataFactory().createComposite<ErrorEvent>(
                type = ErrorEvent.ID,
                fields = listOf(
                    Uuid.random().toString().toTString(context),
                    error.id.toTString(context),
                    operationId.toTString(context),
                    callstackDepth.toLong().toTInteger(context),
                    capturedAt.toTInteger(context),
                    deviceId.toTString(context),
                    appId.toTString(context),
                    appVersion.toTString(context),
                    osLabel.toTString(context),
                    internalDetail?.toTString(context)
                )
            )
        )
        // Saving fires the SyncObserver (outbound Eager block) and the
        // generic RelationMaintainer (local LoggingService.events
        // membership). Same shape as Notification.
        sessionExecutionContext.saveData(event)
    }
}

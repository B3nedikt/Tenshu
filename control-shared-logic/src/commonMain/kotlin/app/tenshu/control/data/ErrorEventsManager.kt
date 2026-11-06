package app.tenshu.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold
import org.tenshu.logging.ErrorEvent
import org.tenshu.logging.LoggingService
import org.tenshu.logging.common.UploadableEvent
import org.tenshu.logging.appId
import org.tenshu.logging.appVersion
import org.tenshu.logging.callstackDepth
import org.tenshu.logging.capturedAt
import org.tenshu.logging.client.loggingService
import org.tenshu.logging.deviceId
import org.tenshu.logging.errorTypeId
import org.tenshu.logging.eventId
import org.tenshu.logging.internalDetail
import org.tenshu.logging.operationId
import org.tenshu.logging.osLabel

/**
 * Phase 5 of `docs/proposals/core-error-channel.md`: read surface over the
 * realm-scoped [LoggingService.events] relation. The Settings → Errors screen
 * collects [events] into its `UiState`. Phase 6 will add the "Send error
 * report" CTA over this same projection.
 *
 * Mirrors [ShareManager]'s shape — a `:control-shared`-local data client over
 * [TenshuClient], not its own module.
 */
interface ErrorEventsManager {

    /**
     * Every captured [ErrorEventSummary] for the realm, **newest-first**. Emits
     * `emptyList()` on a read failure (cold-start until the first event is
     * recorded, or when no `:logging-service-proxy` is wired upstream).
     */
    fun events(): Flow<List<ErrorEventSummary>>
}

/**
 * One captured error, projected from the `LoggingService.events` collection.
 * Fields mirror [org.tenshu.logging.ErrorEvent] §4.1; the screen renders typed
 * label + relative time on the row and expands into the full detail block on
 * tap (proposal §4.3 + v0.2 review notes on Settings → Errors scope).
 */
data class ErrorEventSummary(
    val eventId: String,
    override val errorTypeId: String,
    override val operationId: String,
    override val callstackDepth: Int,
    override val capturedAtMillis: Long,
    val deviceId: String,
    override val appId: String,
    override val appVersion: String,
    override val osLabel: String,
    /**
     * Proxy-local `internalDetail` for `tenshu/core/error/internal` errors —
     * **never sent to the App Console** (Phase 6 upload pipeline strips it,
     * proposal §5 / threat-model T-L-03). The Diagnostics screen renders it
     * locally so the user can see exactly what would *not* be uploaded.
     */
    val internalDetail: String?,
) : UploadableEvent

/**
 * Real [ErrorEventsManager] over the in-realm [TenshuClient]. The recorder
 * lives in `:logging-service-proxy`; this is the read side.
 */
class TenshuErrorEventsManager(
    private val client: TenshuClient,
) : ErrorEventsManager {

    private companion object {
        /**
         * How many recent events the Diagnostics screen reads. Generous enough that the list
         * still looks unbounded to a user scrolling it, small enough that the read cost does not
         * track the size of the log. Paging it properly is the follow-up once Phase 5b gives the
         * log a retention policy.
         */
        const val EVENT_WINDOW = 500

        val EVENT_FIELDS = arrayOf(
            ErrorEvent.Fields.EVENT_ID,
            ErrorEvent.Fields.ERROR_TYPE_ID,
            ErrorEvent.Fields.OPERATION_ID,
            ErrorEvent.Fields.CALLSTACK_DEPTH,
            ErrorEvent.Fields.CAPTURED_AT,
            ErrorEvent.Fields.DEVICE_ID,
            ErrorEvent.Fields.APP_ID,
            ErrorEvent.Fields.APP_VERSION,
            ErrorEvent.Fields.OS_LABEL,
            ErrorEvent.Fields.INTERNAL_DETAIL,
        )
    }

    /**
     * The most recent [EVENT_WINDOW] error events, newest first.
     *
     * ⚠ This read used to be unbounded and unprojected — every stored event, every field, sorted
     * client-side after the whole table had crossed IPC. The error log is append-only and has no
     * eviction yet (`core-error-channel.md` Phase 5b retention is still outstanding), so it grows
     * without limit on a busy device; a diagnostics screen that gets slower the more there is to
     * diagnose is the wrong failure mode. This is the `take` bound
     * `core-ordered-window-fusion.md` F2 calls for.
     *
     * `sortByDescending { capturedAt }` + `take` + `select` is the ordered-window-fusion shape:
     * the newest window is chosen store-side in one projected pass, so the client sorts nothing
     * and only the rows it shows cross the wire.
     */
    override fun events(): Flow<List<ErrorEventSummary>> {
        return client.loggingService.events
            .sortByDescending { capturedAt }
            .take(EVENT_WINDOW)
            .select { fields(*EVENT_FIELDS) }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { rows ->
                        rows.toList()
                            .map { entity ->
                                ErrorEventSummary(
                                    eventId = entity.eventId.get(),
                                    errorTypeId = entity.errorTypeId.get(),
                                    operationId = entity.operationId.get(),
                                    callstackDepth = entity.callstackDepth.get().toInt(),
                                    capturedAtMillis = entity.capturedAt.get(),
                                    deviceId = entity.deviceId.get(),
                                    appId = entity.appId.get(),
                                    appVersion = entity.appVersion.get(),
                                    osLabel = entity.osLabel.get(),
                                    internalDetail = entity.internalDetail?.get(),
                                )
                            }
                    },
                    onFailure = { emptyList() }
                )
            }
            .flowOn(Dispatchers.Default)
    }
}

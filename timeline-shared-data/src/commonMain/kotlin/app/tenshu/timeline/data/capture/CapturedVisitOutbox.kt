package app.tenshu.timeline.data.capture

import app.tenshu.outbox.JsonOutbox
import app.tenshu.outbox.OutboxEntry
import app.tenshu.outbox.OutboxStore
import app.tenshu.timeline.data.ImportedTimeline

/**
 * Timeline's aliases onto the shared `:app-outbox` seam — the store interface and entry type
 * started here and were generalised once Fit's recorder needed the identical mechanic; existing
 * call sites (and the Android store binding) keep their names.
 */
typealias VisitOutboxStore = OutboxStore

typealias VisitOutboxEntry = OutboxEntry

/**
 * The **durable visit outbox** (tech-debt "Visits captured before Control finishes onboarding
 * are lost silently"): a captured [ImportedTimeline] is persisted here *before* the record ops
 * are attempted, removed only on confirmed write, and replayed later — so a visit detected while
 * the proxy has no realm yet (first-run Welcome), is unreachable, or hangs is never lost. The
 * record ops are content-addressed (`placeId`/`momentId`), so replaying an entry that did in
 * fact land folds to the same rows — replay is idempotent by construction.
 *
 * A thin typed front over the generic `:app-outbox` [JsonOutbox] (see its KDoc for the payload
 * format + corrupt-entry posture), keeping Timeline's id derivation: the first moment's
 * content-addressed id is stable per visit, prefixed with its timestamp so store ordering
 * matches capture ordering.
 */
class CapturedVisitOutbox(store: VisitOutboxStore) {

    private val outbox = JsonOutbox(
        store = store,
        serializer = ImportedTimeline.serializer(),
        idOf = { timeline ->
            val first = timeline.moments.firstOrNull()
            "${first?.timestampMs ?: 0}-${first?.momentId ?: timeline.places.firstOrNull()?.placeId ?: "entry"}"
        },
    )

    /** Persist [timeline]; returns the entry id to [remove] on confirmed write. */
    /**
     * Serialises drains of THIS outbox across every writer that shares it (the capture engine's
     * visit and move writers, and the app-start drain) — ADR 0025 §8.6: a release that triggers a
     * drain while another is running gets a follow-up drain, never a concurrent second one.
     */
    internal val drainLock = kotlinx.coroutines.sync.Mutex()

    fun persist(timeline: ImportedTimeline): String = outbox.persist(timeline)

    fun remove(id: String) = outbox.remove(id)

    /** Pending entries, oldest first; corrupt payloads are dropped and purged. */
    fun pending(): List<Pair<String, ImportedTimeline>> = outbox.pending()
}

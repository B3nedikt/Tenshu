package app.tenshu.fit.data

import app.tenshu.outbox.JsonOutbox
import app.tenshu.outbox.OutboxStore

/**
 * The **durable recorder outbox** (fit-app.md §9.13): a finished recording is journalled here —
 * as the same [ImportedHealth] shape every importer produces — *before* the record ops are
 * attempted, removed only on confirmed write, and replayed by
 * [FitRepository.drainPendingWorkouts] (next launch / next save). Without it, a workout that
 * failed to save (Control unreachable) lived only in the recording engine and died with the
 * process.
 *
 * Mirrors Timeline's `CapturedVisitOutbox` over the shared `:app-outbox` [JsonOutbox] (see its
 * KDoc for the payload format + corrupt-entry posture); replay is idempotent because
 * `recordWorkout` is content-addressed by `workoutId`. Id = start-timestamp-prefixed workoutId,
 * so store ordering matches recording ordering.
 *
 * The journal holds route + HR plaintext (GDPR Art. 9, threat-model A34) — app-private storage
 * only, same posture as the visit outbox (A35).
 */
class RecordedWorkoutOutbox(store: OutboxStore) {

    private val outbox = JsonOutbox(
        store = store,
        serializer = ImportedHealth.serializer(),
        idOf = { health ->
            val first = health.workouts.firstOrNull()
            "${first?.startMs ?: 0}-${first?.workoutId ?: "entry"}"
        },
    )

    /** Persist [health]; returns the entry id to [remove] on confirmed write. */
    fun persist(health: ImportedHealth): String = outbox.persist(health)

    fun remove(id: String) = outbox.remove(id)

    /** Pending entries, oldest first; corrupt payloads are dropped and purged. */
    fun pending(): List<Pair<String, ImportedHealth>> = outbox.pending()
}

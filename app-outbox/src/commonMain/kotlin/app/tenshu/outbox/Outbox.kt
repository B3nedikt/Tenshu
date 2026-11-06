package app.tenshu.outbox

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Durable storage seam for an outbox — the platform provides *where* the entries live
 * (Android: app-private files; desktop: an app data directory; iOS: the app container), the
 * outbox provides *what they mean*. Entries are ordered oldest-first by [entries]; ids are
 * opaque to the caller.
 */
interface OutboxStore {
    /** Persist [payload] under [id]. Overwriting an existing id is a benign no-op-equivalent. */
    fun append(id: String, payload: String)

    /** All persisted entries, oldest first. */
    fun entries(): List<OutboxEntry>

    /** Delete the entry with [id]. Unknown ids are a no-op. */
    fun remove(id: String)
}

data class OutboxEntry(val id: String, val payload: String)

/**
 * A **durable outbox** over a typed payload: a value is persisted here *before* the real write is
 * attempted, removed only on confirmed success, and replayed later — so work captured while the
 * write target is unreachable (a proxy with no realm yet, a stopped Control, a hung op) is never
 * lost. The write the caller replays must be idempotent (Tenshu record ops are content-addressed,
 * so a replayed entry that did in fact land folds onto the same rows).
 *
 * Payloads are JSON of the plain [T] DTOs — a local, app-private format (never wire, House Rule
 * #4 unaffected). [pending] is tolerant of a corrupt payload (a partial write from a crash): the
 * entry is dropped from the result *and* removed from the store rather than wedging the replay
 * loop forever.
 *
 * [idOf] derives the entry id; make it stable per logical unit (replay-idempotence folds a
 * duplicate persist) and prefix it with a timestamp so store ordering matches capture ordering.
 */
class JsonOutbox<T>(
    private val store: OutboxStore,
    private val serializer: KSerializer<T>,
    private val idOf: (T) -> String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Persist [value]; returns the entry id to [remove] on confirmed write. */
    fun persist(value: T): String {
        val id = idOf(value)
        store.append(id, json.encodeToString(serializer, value))
        return id
    }

    fun remove(id: String) = store.remove(id)

    /** Pending entries, oldest first; corrupt payloads are dropped and purged. */
    fun pending(): List<Pair<String, T>> =
        store.entries().mapNotNull { entry ->
            try {
                entry.id to json.decodeFromString(serializer, entry.payload)
            } catch (_: Exception) {
                store.remove(entry.id)
                null
            }
        }
}

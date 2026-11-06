package app.tenshu.timeline.data.capture

import app.tenshu.outbox.OutboxStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The **durable open-move journal** — the crash-survival seam for the one piece of capture state that
 * the outbox does *not* protect (`docs/proposals/timeline-movement-capture.md` §12.0).
 *
 * `CapturedVisitOutbox` makes a *detected* leg durable before its write ops run. But a leg is only
 * detected when the [MovementSegmenter] **flushes** — at a dwell, at the `maxOpenMove` cap, or when
 * capture stops. Until then the whole journey lives in an in-memory `moveBuffer`, and an OEM
 * force-kill (ColorOS routinely; iOS suspends between significant-change wakes) took the entire
 * journey with it. Per §12.0 that loss is *permanent*: **import loss is re-runnable, capture loss is
 * not** — a fix that was never persisted cannot be re-derived from anything.
 *
 * This is the same journal-then-replay contract as the outbox, one level upstream: the open path is
 * checkpointed to durable storage as it grows, and the next segmenter picks it up
 * ([MovementSegmenter] resumes it, or flushes it as its own leg if the process was dead too long to
 * claim continuity). Replay is idempotent for free — a recorded move is content-addressed by its
 * `startMs` (`cap-move:$startMs` / `cap-trace:$startMs`, `MovementMapper`), so a leg that *was*
 * written before the kill folds onto the same rows rather than duplicating.
 *
 * An **injected interface with a no-op default** ([None]), not an `expect`/`actual`: the segmenter is
 * pure `commonMain` shared by Android's streaming source and iOS's event-driven session, and the
 * durable store it needs already exists as a platform-bound seam (`:app-outbox`'s [OutboxStore],
 * exactly how `CapturedVisitOutbox` is bound). Fixtures and pure algorithm tests keep [None].
 *
 * Implementations **must never throw** — a failed journal write may degrade recovery, but it must
 * not kill live capture (the [DurableImportWriter] posture).
 */
interface OpenMoveJournal {

    /** The persisted open path (oldest fix first), or empty if there is none / it was unreadable. */
    fun read(): List<CapturedFix>

    /** Replace the persisted open path with [path]. An empty [path] is equivalent to [clear]. */
    fun checkpoint(path: List<CapturedFix>)

    /** Forget the open path — it has been closed and handed to the write path. */
    fun clear()

    /** The default: capture keeps today's in-memory-only behaviour (tests, fixtures, desktop). */
    object None : OpenMoveJournal {
        override fun read(): List<CapturedFix> = emptyList()
        override fun checkpoint(path: List<CapturedFix>) = Unit
        override fun clear() = Unit
    }
}

/**
 * The persisted shape. Versioned so a later fix-model widening (§12.1 wants `speed`/`bearing`/
 * `provider` on [CapturedFix]) can reject rather than mis-read an older record — the journal is
 * **device-local and app-private**, never wire and never a stored schema row, so it carries its own
 * tiny version instead of riding `TENSHU_STORAGE_FORMAT_VERSION`.
 */
@Serializable
internal data class OpenMoveRecord(
    val version: Int = OPEN_MOVE_RECORD_VERSION,
    val fixes: List<CapturedFix> = emptyList(),
)

internal const val OPEN_MOVE_RECORD_VERSION = 1

/**
 * The real [OpenMoveJournal]: one JSON record in a platform [OutboxStore] — app-private files on
 * Android (`filesDir/move_journal/`), the app container on iOS. Reuses `:app-outbox`'s proven
 * mechanics (write-temp-then-rename, so a crash mid-checkpoint never leaves a half-written path)
 * rather than inventing a second durable-store seam.
 *
 * ⚠ It must be given a store **of its own**, not the visit outbox's — `DurableImportWriter.drain`
 * treats every entry in its store as an `ImportedTimeline` and *purges* anything that fails to
 * decode, which would silently eat this record.
 *
 * Coordinates at rest are the visit outbox's posture (threat-model A35): app-private storage,
 * encrypted at rest by the platform, never leaving the device.
 */
internal class JsonOpenMoveJournal(
    private val store: OutboxStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /**
     * Which record in the store this journal owns.
     *
     * ⚠ Parameterised so the **open dwell** can reuse this codec rather than fork it (§29): the two
     * persist the same thing — a list of [CapturedFix] that must survive process death — and a second
     * copy of the version/corruption handling would be a second place for it to drift.
     */
    private val entryId: String = ENTRY_ID,
) : OpenMoveJournal {

    override fun read(): List<CapturedFix> = runCatching {
        val payload = store.entries().firstOrNull { it.id == entryId }?.payload ?: return emptyList()
        val record = json.decodeFromString(OpenMoveRecord.serializer(), payload)
        if (record.version != OPEN_MOVE_RECORD_VERSION) {
            // A record written by a different fix model: drop it rather than mis-read a path whose
            // distance/mode would then be computed over garbage.
            store.remove(entryId)
            emptyList()
        } else {
            record.fixes
        }
    }.getOrElse {
        // Corrupt payload (a torn write predating the rename, a hand-edited file): purge it so the
        // next checkpoint starts clean, mirroring JsonOutbox's corrupt-entry posture.
        runCatching { store.remove(entryId) }
        emptyList()
    }

    override fun checkpoint(path: List<CapturedFix>) {
        if (path.isEmpty()) {
            clear()
            return
        }
        runCatching { store.append(entryId, json.encodeToString(OpenMoveRecord.serializer(), OpenMoveRecord(fixes = path))) }
    }

    override fun clear() {
        runCatching { store.remove(entryId) }
    }

    internal companion object {
        /** One record, overwritten in place — there is only ever one open move per device. */
        const val ENTRY_ID = "open-move"

        /** The open dwell's record, in a store of its own (§29). */
        const val DWELL_ENTRY_ID = "open-dwell"
    }
}

package app.tenshu.calendar.data.external

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import app.tenshu.calendar.data.CalendarMirrorRepository
import app.tenshu.calendar.data.CalendarMirrorSummary
import app.tenshu.calendar.data.MirrorSyncResult
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * The last snapshot each mirror was synced from, **on this device only**.
 *
 * ⚠ A hint, never the truth: it only lets a refresh skip a calendar whose source has not changed.
 * A lost store costs one full diff against the realm, nothing more — which is why it can live in
 * app-local storage rather than in the synced realm, where every refresh would be a sync write.
 */
interface MirrorDigestStore {
    fun get(mirrorId: String): String?
    fun put(mirrorId: String, digest: String)
}

/** For platforms and tests with nothing to persist to — every refresh then diffs in full. */
class InMemoryMirrorDigestStore : MirrorDigestStore {
    private val digests = mutableMapOf<String, String>()
    override fun get(mirrorId: String): String? = digests[mirrorId]
    override fun put(mirrorId: String, digest: String) {
        digests[mirrorId] = digest
    }
}

/** What refreshing one mirror did. */
sealed interface MirrorRefreshOutcome {
    val mirrorId: String

    data class Synced(override val mirrorId: String, val result: MirrorSyncResult) : MirrorRefreshOutcome

    /** The source is exactly as last synced; nothing was read from the realm or written. */
    data class Unchanged(override val mirrorId: String) : MirrorRefreshOutcome

    /**
     * This device no longer lists the source — the account was removed, or the calendar deleted.
     *
     * ⚠ The mirror is left exactly as it is, not emptied: an account removed from the phone is
     * not the user deleting their calendar, and the events stay until they say otherwise.
     */
    data class SourceGone(override val mirrorId: String) : MirrorRefreshOutcome

    /**
     * The source exists but the OS is not syncing its events (`SYNC_EVENTS = 0`), or it read back
     * **empty**.
     *
     * ⚠⚠ Neither is applied. The provider may drop a calendar's events when its sync is switched
     * off, and is briefly empty while an account re-syncs — read as the truth, either would sweep
     * every event out of the mirror. An empty read never empties a mirror; the cost is that a
     * source the user genuinely emptied is not mirrored until it has an event again.
     */
    data class NotApplied(override val mirrorId: String) : MirrorRefreshOutcome

    data class Failed(override val mirrorId: String, val accessDenied: Boolean) : MirrorRefreshOutcome
}

/**
 * Keeps this device's mirrors in step with their sources (`calendar-external-sources.md` CX-1b).
 *
 * ⚠⚠ **Only mirrors bound to this device** (§5.1): no other device can see the source, and two
 * devices refreshing one mirror from two different views of it would fight.
 *
 * ⚠ Clockless — [nowEpochMs] comes from the composition root, like every other "now" in Calendar.
 */
class MirrorRefresher(
    private val mirrors: CalendarMirrorRepository,
    private val reader: ExternalCalendarReader,
    private val digests: MirrorDigestStore,
    private val nowEpochMs: () -> Long,
) {

    /** Refreshes every mirror bound here. Cheap when nothing changed: a read, a digest, no writes. */
    suspend fun refreshAll(force: Boolean = false): List<MirrorRefreshOutcome> {
        val bound = mirrors.mirrors().filter { it.boundHere }
        if (bound.isEmpty()) return emptyList()
        val sources = runCatchingCancellable { reader.calendars() }.getOrElse { failure ->
            return bound.map { MirrorRefreshOutcome.Failed(it.mirrorId, failure is ExternalCalendarAccessDenied) }
        }.associateBy { it.kind.name to it.sourceKey }
        return bound.map { refreshOne(it, sources[it.sourceKind to it.sourceKey], force) }
    }

    /** Refreshes one mirror now, regardless of its digest — *Refresh now*, and a new mirror. */
    suspend fun refresh(mirrorId: String): MirrorRefreshOutcome? {
        val mirror = mirrors.mirrors().firstOrNull { it.mirrorId == mirrorId && it.boundHere } ?: return null
        val source = runCatchingCancellable { reader.calendars() }.getOrElse { failure ->
            return MirrorRefreshOutcome.Failed(mirrorId, failure is ExternalCalendarAccessDenied)
        }.firstOrNull { it.kind.name == mirror.sourceKind && it.sourceKey == mirror.sourceKey }
        return refreshOne(mirror, source, force = true)
    }

    /**
     * ⚠⚠ One refresh at a time, read through digest: *Refresh now*, the content trigger and the
     * daily job share this refresher, and two overlapping reads could land out of order — the
     * older snapshot applied after the newer one would roll its edits and deletions back. The
     * repository lock orders writes, not reads, so the whole cycle is serialised here. ⚠ Across
     * mirrors too: one device holds few, and a provider read is cheap next to a stale write.
     */
    private val cycle = Mutex()

    private suspend fun refreshOne(
        mirror: CalendarMirrorSummary,
        source: ExternalCalendar?,
        force: Boolean,
    ): MirrorRefreshOutcome = cycle.withLock { refreshOneLocked(mirror, source, force) }

    private suspend fun refreshOneLocked(
        mirror: CalendarMirrorSummary,
        source: ExternalCalendar?,
        force: Boolean,
    ): MirrorRefreshOutcome {
        source ?: return MirrorRefreshOutcome.SourceGone(mirror.mirrorId)
        if (!source.holdsEvents) return MirrorRefreshOutcome.NotApplied(mirror.mirrorId)
        return runCatchingCancellable {
            val snapshot = reader.read(source)
            if (snapshot.events.isEmpty()) return@runCatchingCancellable MirrorRefreshOutcome.NotApplied(mirror.mirrorId)

            val digest = digestOf(mirror, snapshot)
            // ⚠ A zero stamp means "never synced, or a setting changed since" (`setMirrorReminders`):
            // the cached digest cannot speak for that, so reconcile.
            if (!force && mirror.lastRefreshedEpochMs > 0L && digests.get(mirror.mirrorId) == digest) {
                stampIfDue(mirror, changed = false)
                return@runCatchingCancellable MirrorRefreshOutcome.Unchanged(mirror.mirrorId)
            }
            val result = mirrors.syncMirror(mirror, snapshot)
            digests.put(mirror.mirrorId, digest)
            stampIfDue(mirror, changed = result.changedAnything)
            MirrorRefreshOutcome.Synced(mirror.mirrorId, result)
        }.getOrElse { failure ->
            // ⚠ A failure leaves the stamp alone, so every device can see how stale the mirror
            // really is rather than one that claims it just refreshed.
            MirrorRefreshOutcome.Failed(mirror.mirrorId, failure is ExternalCalendarAccessDenied)
        }
    }

    /**
     * ⚠ Coarse: an unchanged refresh re-stamps only once [STAMP_INTERVAL_MS] has passed. The
     * stamp is a synced row (`CalendarMirrorStamp`, this device's own — never the shared mirror
     * row, P2-a), and the change trigger can fire for every edit to *any* calendar on the phone —
     * stamping each time would turn it into a stream of sync writes.
     */
    private suspend fun stampIfDue(mirror: CalendarMirrorSummary, changed: Boolean) {
        val now = nowEpochMs()
        if (changed || now - mirror.lastRefreshedEpochMs >= STAMP_INTERVAL_MS) {
            mirrors.stampMirror(mirror.mirrorId, now)
        }
    }

    companion object {
        const val STAMP_INTERVAL_MS = 60L * 60 * 1000

        /**
         * A 64-bit FNV-1a over everything a refresh would write.
         *
         * ⚠ The reminder switch and the target calendar are part of it, so turning Tenshu
         * reminders on is a change even when the source is not. ⚠ 64 bits, not `hashCode()`: a
         * collision here skips a real change silently.
         */
        fun digestOf(mirror: CalendarMirrorSummary, snapshot: ExternalSnapshot): String {
            var hash = -0x340d631b7bdddcdbL   // FNV-1a 64 offset basis
            fun mix(text: String) {
                for (c in text) {
                    hash = hash xor c.code.toLong()
                    hash *= 0x100000001b3L         // FNV-1a 64 prime
                }
            }
            mix("${mirror.calendarId}|${mirror.remindersEnabled}|${snapshot.skipped}|")
            // ⚠ Which rows were unreadable changes what a refresh keeps, so it is part of "changed":
            // two partial reads with the same events but different gaps must not look identical.
            mix(snapshot.unreadableUids.sorted().joinToString(",", postfix = "|"))
            mix(snapshot.incompleteOverridesFor.sorted().joinToString(",", postfix = "|"))
            // ⚠⚠ And what the read covered: a rolling window (EventKit) brings dates into the safe
            // sweep span as it advances, with the events unchanged — matching on events alone would
            // skip that reconcile forever. Costs one full reconcile a day, when the window moves.
            snapshot.window?.let { mix("${it.startMs}-${it.endMs}|") }
            snapshot.observedOccurrenceDates.entries.sortedBy { it.key }.forEach { (uid, dates) ->
                mix(uid); mix(dates.sorted().joinToString(",", postfix = "|"))
            }
            snapshot.events.forEach { mix(it.toString()); mix("\u0000") }
            return hash.toULong().toString(16)
        }
    }
}

/**
 * Arms whatever runs [MirrorRefresher] in the background on this platform (CX-1b).
 *
 * ⚠ Called when a mirror starts, not only at app launch: a user who starts *Keep in sync* and
 * then leaves the app for a week must not wait for the next cold start for the first background
 * refresh. Idempotent.
 */
fun interface MirrorBackgroundScheduler {
    fun ensureScheduled()
}

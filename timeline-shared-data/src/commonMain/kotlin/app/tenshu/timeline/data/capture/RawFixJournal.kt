package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.StateFlow

/**
 * The **raw-fix journal** — the tuning corpus capture has never had
 * (`docs/proposals/timeline-movement-capture.md` §18.9).
 *
 * ### Why this exists at all
 *
 * Nothing in the pipeline persists a *raw* fix. A dwell keeps only its accuracy-weighted centroid
 * and drops the cluster; a move keeps a **decimated** polyline; [OpenMoveJournal] holds only the
 * path currently open. So every question of the form *"would a different rule have read this day
 * differently?"* is unanswerable after the fact — which is why §9 has asked for a "tuning corpus"
 * since M1, why `MODE_BOUNDARIES` has never been calibrated, and why S6a's accuracy-aware radius
 * (§18.2) cannot be replayed against the very report that motivated it: the jitter that split the
 * stay was never written down.
 *
 * This records the fix stream verbatim, so [SegmentationReplay] can re-run the whole segmenter over
 * a real day under any config. It is the instrument, not a feature.
 *
 * ### ⚠⚠ The most sensitive artefact the app produces
 *
 * A raw fix stream is strictly more revealing than anything capture stores today — it is the
 * *un-generalised* record, before dwell clustering rounds a stay to a centroid and before
 * decimation throws away the shape of a walk. Threat-model **A35** governs it, and the posture is
 * therefore deliberately stricter than the capture opt-in it sits behind:
 *
 *  - **Its own opt-in** ([RawFixJournalControl]), off by default and independent of capture's — a
 *    user who accepted background location did not thereby accept a verbatim trace log.
 *  - **Bound only in debug builds**, so it cannot be switched on in a release build at all. The
 *    Settings section follows the established bound-only-shows idiom and simply is not there.
 *  - **App-private storage, never synced, never uploaded** — no schema row, no wire shape, no
 *    relay. It is a local file and nothing reads it but the replay.
 *  - **Deletable**, from the same screen that enables it.
 *
 * ### Bounded by *stopping*, not by rotation
 *
 * At [maxFixes] the journal stops recording and says so. A ring buffer would be the reflexive
 * choice and is the wrong one here: a corpus whose window silently slides is a corpus whose replay
 * results cannot be attributed to a period, and "the oldest hour of the day I was debugging
 * vanished" is a much worse failure than "recording stopped, here is when". The cap is generous
 * enough that a bounded diagnostic session never reaches it.
 */
interface RawFixJournal {

    /** Append one fix. Must never throw — a failed journal write must not disturb live capture. */
    fun record(fix: CapturedFix)

    /** Everything recorded, oldest first. Empty when disabled, unwritten, or unreadable. */
    fun readAll(): List<CapturedFix>

    /** How many fixes are on disk, and whether the cap has been reached. Cheap enough to poll. */
    fun status(): Status

    /** Discard the corpus. */
    fun clear()

    data class Status(val fixCount: Int, val capped: Boolean)

    /** The default everywhere the journal is not explicitly bound: records nothing, keeps nothing. */
    object None : RawFixJournal {
        override fun record(fix: CapturedFix) = Unit
        override fun readAll(): List<CapturedFix> = emptyList()
        override fun status(): Status = Status(fixCount = 0, capped = false)
        override fun clear() = Unit
    }
}

/**
 * The raw-fix recording opt-in — the [RawFixJournal]'s own consent, separate from capture's.
 *
 * Mirrors `WifiFingerprintControl`: a platform binds it or does not, and the Settings section
 * renders only where one is bound. ⚠ Android binds it **only in debug builds** (see
 * `TimelineAndroidModule`), which is what keeps a verbatim location log unreachable in release.
 */
interface RawFixJournalControl {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}

/**
 * The platform half: an append-only line log. Deliberately the *smallest* thing a platform must
 * provide — append a line, read the lines, delete the file — so all encoding, decoding, capping and
 * consent live in `commonMain` where they are unit-testable without a device.
 *
 * Implementations must not throw; failures degrade to "nothing recorded".
 */
interface RawFixLog {
    fun append(line: String)
    fun lines(): List<String>
    fun clear()
    fun lineCount(): Int
}

/**
 * Line codec for one fix: `latE7,lngE7,timestampMs,accuracyM`.
 *
 * ⚠ Plain CSV rather than the compact [MovementTracePolyline] delta encoding, deliberately. The
 * polyline codec is *streaming and delta-based* — it cannot be appended to one fix at a time
 * without rewriting the file, which is exactly what an append-only journal must avoid. Text also
 * means a corpus can be pulled with `adb` and inspected by eye, which for a diagnostic instrument
 * is worth more than the bytes it costs.
 */
internal object RawFixCodec {

    fun encode(fix: CapturedFix): String = buildString {
        append(fix.latE7); append(','); append(fix.lngE7); append(',')
        append(fix.timestampMs); append(','); append(fix.accuracyM)
        // ⚠ The 5th column is OMITTED when no pedometer reported, rather than written as 0 — the
        // corpus must preserve `null` vs `0`, which is the whole cadence distinction (LegMode).
        fix.stepsSinceLastFix?.let { append(','); append(it) }
    }

    /**
     * `null` for a line that does not parse — a torn tail write must not poison the whole corpus.
     *
     * ⚠⚠ **Accepts 4 OR 5 columns.** S3 added the step count as a fifth, and a corpus recorded before
     * it must keep decoding: a strict arity check would silently drop every pre-S3 line, i.e. throw
     * away exactly the historic data this journal exists to accumulate. A 4-column line means *no
     * pedometer reading*, which is `null` — not `0`.
     */
    fun decode(line: String): CapturedFix? {
        val parts = line.split(',')
        if (parts.size != 4 && parts.size != 5) return null
        val lat = parts[0].toLongOrNull() ?: return null
        val lng = parts[1].toLongOrNull() ?: return null
        val ts = parts[2].toLongOrNull() ?: return null
        val acc = parts[3].toIntOrNull() ?: return null
        val steps = if (parts.size == 5) parts[4].toIntOrNull() ?: return null else null
        return CapturedFix(
            latE7 = lat, lngE7 = lng, timestampMs = ts, accuracyM = acc, stepsSinceLastFix = steps,
        )
    }
}

/**
 * The real journal: consent-gated, capped, over a platform [RawFixLog].
 *
 * ⚠ Consent is read **per record**, not captured at construction: the control is a `StateFlow` the
 * user can flip mid-session from Settings, and a journal that latched its verdict at DI time would
 * keep writing after the switch went off.
 */
class LoggingRawFixJournal(
    private val log: RawFixLog,
    private val control: RawFixJournalControl,
    /**
     * Cap, in fixes. 250 000 is ~10 MB of CSV and, at S1's most aggressive vehicle cadence (10 s),
     * about a month of continuous movement — far past any diagnostic session, so reaching it means
     * something was left on rather than that the window was too small.
     */
    private val maxFixes: Int = DEFAULT_MAX_FIXES,
) : RawFixJournal {

    override fun record(fix: CapturedFix) {
        if (!control.enabled.value) return
        runCatching {
            if (log.lineCount() >= maxFixes) return
            log.append(RawFixCodec.encode(fix))
        }
    }

    /**
     * ⚠ Readable **regardless of the current opt-in**: turning recording off must not hide a corpus
     * that is still on disk. Hiding it would be the worst of both — the data exists, and the screen
     * that could delete it says there is nothing there.
     */
    override fun readAll(): List<CapturedFix> =
        runCatching { log.lines().mapNotNull(RawFixCodec::decode) }.getOrElse { emptyList() }

    override fun status(): RawFixJournal.Status = runCatching {
        val count = log.lineCount()
        RawFixJournal.Status(fixCount = count, capped = count >= maxFixes)
    }.getOrElse { RawFixJournal.Status(0, false) }

    override fun clear() {
        runCatching { log.clear() }
    }

    companion object {
        const val DEFAULT_MAX_FIXES: Int = 250_000
    }
}

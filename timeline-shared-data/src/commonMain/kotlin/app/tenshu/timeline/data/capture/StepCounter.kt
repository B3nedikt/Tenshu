package app.tenshu.timeline.data.capture

/**
 * The pedometer seam (S3, `timeline-movement-capture.md` §19) — deliberately the *smallest* thing a
 * platform must provide: start, stop, and "how many steps since I last asked".
 *
 * An injected interface with a no-op default ([None]), not an `expect`/`actual`, matching
 * [OpenMoveJournal] and [RawFixLog]: the segmenter and classifier are pure `commonMain` shared by
 * Android's streaming source and iOS's event-driven session, and a platform that has no binding must
 * degrade to *exactly* today's speed-only behaviour rather than to a different one.
 *
 * ⚠⚠ **[stepsSinceLast] returning `null` is a first-class answer** — "nobody counted" — and is not
 * interchangeable with `0`. See [LegMode]; the whole cycle-vs-run discrimination rests on the
 * difference.
 *
 * Implementations must never throw: a sensor failure degrades cadence to unknown, it does not
 * disturb capture.
 */
interface StepCounter {

    /** Is a pedometer present at all? `false` ⇒ [stepsSinceLast] is always `null`. */
    fun isAvailable(): Boolean

    /** Begin counting. Idempotent. */
    fun start()

    /** Stop counting and forget the baseline. Idempotent. */
    fun stop()

    /** Steps since the previous call, or `null` when unavailable / not yet baselined. */
    fun stepsSinceLast(): Int?

    /** The default: no pedometer, so every fix carries `null` and classification is speed-only. */
    object None : StepCounter {
        override fun isAvailable(): Boolean = false
        override fun start() = Unit
        override fun stop() = Unit
        override fun stepsSinceLast(): Int? = null
    }
}

package org.tenshu.sync.proxy

/**
 * Decides whether this device should be *reported* as offline, from the raw per-tick relay
 * outcomes (`sync-metered-plane-split.md` §8).
 *
 * ## Why the raw flag is not good enough
 *
 * The raw flag is "the outcome of the last relay call we happened to make", carried forward
 * indefinitely (`online = tickOnline ?: it.online`). It has no notion of *when* that call
 * happened, which makes it wrong in two opposite directions:
 *
 *  - **Stale latch.** A tick that attempts nothing (`tickOnline == null` — pull skipped because the
 *    realm is unregistered, schema-fenced, or simply idle) leaves the last verdict standing
 *    forever. One failed attempt can therefore report "Offline" for hours with no evidence behind
 *    it, while the relay answers a ping in milliseconds.
 *  - **Flap.** A single failed pull between two successful ones flips the banner to "Offline" for
 *    one tick — at the hot 1.5s cadence, visible flicker.
 *
 * `Offline` outranks `Deferred` (§8), so an unfounded offline verdict *masks* the "Waiting for Wi-Fi" state entirely — the one
 * state the whole slice exists to show. The banner priority rests on `isOnline` being trustworthy,
 * so this is what makes that ordering safe rather than merely plausible.
 *
 * ## The rule
 *
 * Report offline only on a failure that is both **recent** and **sustained**:
 *
 *  1. the most recent attempt failed — never claim offline on no evidence;
 *  2. that attempt is no older than [EVIDENCE_TTL_MS] — evidence expires, so a device that has
 *     stopped attempting stops claiming anything (kills the stale latch);
 *  3. nothing has succeeded within [CONFIRM_MS] — one bad tick among good ones is noise
 *     (kills the flap).
 *
 * A device that is genuinely offline keeps attempting and keeps failing every tick, so its
 * evidence stays fresh and (2) always holds — the rule costs it only the [CONFIRM_MS] grace before
 * the banner appears.
 *
 * ⚠ **The raw outcome is still what drives the reconnect re-drive** in
 * [SyncScheduler.reportSyncHealth]: clearing the entitlement backoff on a true offline→online edge
 * must react to the actual transition, not to this smoothed, deliberately-laggy verdict.
 *
 * Not thread-safe; only ever touched from inside the scheduler's `tickMutex`.
 */
internal class RelayReachability(private val nowMs: () -> Long) {

    private var lastAttemptAtMillis = 0L
    private var lastAttemptFailed = false
    private var lastSuccessAtMillis = 0L
    private var everAttempted = false

    /**
     * Whether the **most recent** attempt was authorized, not merely answered.
     *
     * ⚠⚠ Tracked separately from [lastSuccessAtMillis] because a `401` is deliberately recorded as
     * reachable — see [record]. The banner wants that (the relay IS up; telling the user to check
     * their network is the wrong advice), and a fence must not have it.
     *
     * ⚠⚠ And it is the *latest* answer, not a high-water mark. Keeping the last authorized
     * timestamp instead would leave the fence confirmed for the rest of the window after a `401`
     * arriving a second after a good tick — the exact lost-registration episode the split exists
     * to catch. Pinned by `a401AfterAnAcceptance_revokesConfirmation`.
     */
    private var lastAttemptAuthorized = false

    /**
     * Record one tick's outcome: `true` reached the relay, `false` failed to, `null` made no
     * attempt (which is deliberately *not* evidence either way).
     */
    fun record(outcome: Boolean?, authorized: Boolean? = null) {
        if (outcome == null) return
        val now = nowMs()
        everAttempted = true
        lastAttemptAtMillis = now
        lastAttemptFailed = !outcome
        if (outcome) lastSuccessAtMillis = now
        // ⚠ `null` (unknown, e.g. a caller that does not classify) counts as NOT authorized: the
        // fence fails closed on anything it cannot positively establish.
        lastAttemptAuthorized = outcome && authorized == true
    }

    /**
     * The verdict to publish as `SyncHealthFigures.online`. Optimistic by default: a device that
     * has never attempted anything reports online, so the banner never flashes "Offline" before the
     * first relay call (the same optimism as the `SyncHealthFigures.online = true` default).
     */
    fun isOnline(): Boolean {
        if (!everAttempted || !lastAttemptFailed) return true
        val now = nowMs()
        val evidenceIsStale = now - lastAttemptAtMillis > EVIDENCE_TTL_MS
        // `lastSuccessAtMillis == 0L` = never reached the relay at all; a device failing from the
        // start is offline immediately rather than after a grace period it can never serve out.
        val sustained = lastSuccessAtMillis == 0L || now - lastSuccessAtMillis >= CONFIRM_MS
        return evidenceIsStale || !sustained
    }

    /**
     * **Positive evidence that the relay accepted a write from this device just now** — the signal
     * a *fence* needs, as opposed to the one a *banner* needs.
     *
     * ⚠⚠ [isOnline] is deliberately smoothed and deliberately optimistic: it reports online before
     * the first attempt and for [CONFIRM_MS] after failures begin, so a banner never flashes on one
     * bad tick. This class's own KDoc already warns that a caller needing "the actual transition"
     * must not use that verdict — a safety fence is such a caller. A fence reading [isOnline] would
     * let moves through the whole 20-second post-disconnect window.
     *
     * ⚠⚠ **Keyed on AUTHORIZED acceptance, not on the relay merely answering.** [record] books a
     * `401` as reachable on purpose (the relay is up; blaming the user's network is the wrong
     * advice). But during a lost-
     * registration episode every metadata write is refused, so a move let through by this signal
     * creates a holder no peer will ever see while a peer is free to delete the old one. Reachable
     * and able-to-write are different questions and only the second one fences.
     *
     * ⚠ A cached verdict cannot close the window entirely — connectivity can drop between the last
     * accepted tick and this call, and nothing here would know. [FENCE_EVIDENCE_TTL_MS] bounds that
     * exposure rather than removing it; removing it needs an acknowledged round-trip at move time,
     * which `SyncRelay.probeWrite` provides.
     *
     * ⇒ answers true only on a fresh *authorized* acceptance. Unknown reads as NOT confirmed, so
     * the fence defers — the safe direction, because a deferred move is visible and temporary while
     * an unfenced one can lose bytes permanently and silently.
     */
    fun isConfirmedReachable(): Boolean {
        if (!everAttempted || lastAttemptFailed || !lastAttemptAuthorized) return false
        return nowMs() - lastAttemptAtMillis <= FENCE_EVIDENCE_TTL_MS
    }

    internal companion object {
        /**
         * How long a failure must persist (measured from the last success) before it counts as
         * offline. Comfortably above the hot 1.5s cadence so a single bad tick is absorbed, and
         * below the 30s cold interval so a genuinely offline device is named within one poll.
         */
        const val CONFIRM_MS = 20_000L

        /**
         * How long a failed attempt stays admissible as evidence. Past this the device is not
         * *known* to be offline — it simply has not tried lately — so it stops claiming to be.
         * Three cold poll intervals, so an actively-failing device (which retries every tick)
         * never trips it.
         */
        const val EVIDENCE_TTL_MS = 90_000L

        /**
         * How long an authorized acceptance stays admissible **to a fence**.
         *
         * ⚠ Deliberately tighter than [EVIDENCE_TTL_MS]. That one answers "has this device stopped
         * trying?" and is three cold poll intervals so an actively-failing device never trips it.
         * A fence asks a different question — "did the relay accept something recently enough that
         * I believe it would accept this?" — and 90 s of silence is not evidence for it. One cold
         * interval (30 s) plus half again absorbs ordinary jitter without tolerating a skipped poll.
         */
        const val FENCE_EVIDENCE_TTL_MS = 45_000L
    }
}

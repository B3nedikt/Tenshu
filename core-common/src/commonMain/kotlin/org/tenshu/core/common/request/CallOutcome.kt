package org.tenshu.core.common.request

/**
 * How one round trip to the proxy ended, as the **executor** — the only layer that can tell —
 * classifies it.
 *
 * ⚠⚠ **This exists because "could not read the answer" was being reported as "could not reach the
 * proxy".** An app's repositories each re-derive reachability by hand from the outcome of every
 * call, and a broad `catch` cannot distinguish a dead channel from an answer this build does not
 * understand. So a version skew — a proxy that answered perfectly well with a type this app has
 * never heard of — surfaced to the user as *"Control is unreachable"*: an outage they cannot fix,
 * instead of the update they can.
 *
 * Only the executor sees all four: it owns the connection check, the round trip, the parse and the
 * result. By the time a `TResult` reaches a repository the distinction is already gone.
 */
enum class CallOutcome {

    /**
     * The proxy answered, this build understood the answer, **and the call succeeded.** Control is
     * reachable *and serving this app*.
     *
     * ⚠⚠ **The success is part of the claim, and separating it from [AnsweredWithError] is what
     * makes this usable as a reachability signal at all.** A proxy that refuses the session on a
     * schema mismatch still *answers* — the announce's own round trip comes back with a perfectly
     * readable typed refusal, and since every first-party app announces at launch it would arrive
     * before any screen has read anything. Treating that as "reachable" would tell a device that
     * can read nothing that everything is fine, which is the SK-1 defect the whole skew surface
     * exists to prevent.
     */
    Answered,

    /**
     * The proxy answered and this build understood it, but the call came back a `TResult.Failure`.
     *
     * ⚠ Proof the channel and the schemas are fine, and **not** proof the proxy is serving reads: a
     * refused session and an ordinary `notFound` are both here. So it clears a skew latch — the
     * answer *was* readable — and asserts nothing about reachability.
     */
    AnsweredWithError,

    /**
     * No channel was available, or it broke mid-call. Control is unreachable **and waiting may fix
     * it** — this is the transient case a banner should offer a retry for.
     */
    NotConnected,

    /**
     * ⚠⚠ **The proxy answered, and this build could not parse what it said.**
     *
     * Not an outage: the channel is fine and Control is running. The two sides disagree about the
     * schema — a type id this build cannot resolve — which is version skew and is fixed by
     * updating, never by waiting. Reporting it as unreachable tells the user to check a connection
     * that is working.
     *
     * ⚠ Which side is behind is **not knowable from here**. The announce computes that by
     * comparing two version vectors; a stray unparseable response only proves they differ. The
     * consumer therefore words it as "both", rather than guessing at a target and being wrong half
     * the time.
     */
    Unintelligible,
}

package org.tenshu.core.common.proxy

import org.tenshu.core.common.schema.VersionVector

/**
 * Per-session schema-announce state (`core-schema-evolution.md` §6.4): the
 * vector the client announced on this session, recorded by
 * `AnnounceSessionImplementation`.
 *
 * Owned by the session [ExecutionContext] and **shared** with every
 * per-request context derived via [ExecutionContext.withEvaluationScope]
 * (the announce op executes on a per-request context, but the state is a
 * property of the session — same sharing rationale as `streamRegistry` /
 * `observerRegistry`). A proxy update that kills the binding also drops the
 * session and this state with it, which is exactly the §6.4 semantics:
 * re-announce on every reconnect.
 *
 * This is also the hook per-session polyfill routing (§9.2) later attaches
 * to, and what the reject-if-silent dispatcher gate (§6.5) reads.
 */
class SessionAnnouncement {

    /**
     * The client's *current* vector from the last admitted announce on this session, or `null` if
     * none yet — what the response half of O6 down-levels against
     * (`core-version-compatibility.md` §4.5).
     */
    var announcedVector: VersionVector? = null

    /** The client's *requirement* vector from that same announce (§4.3); `null` if none yet. */
    var announcedRequirement: VersionVector? = null

    /** The announcing app's id, as passed to the announce op. */
    var announcedAppId: String? = null

    val announced: Boolean get() = announcedVector != null
}

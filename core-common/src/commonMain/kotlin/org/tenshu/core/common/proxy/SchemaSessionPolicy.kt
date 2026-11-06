package org.tenshu.core.common.proxy

/**
 * The silent-client policy (`core-schema-evolution.md` §6.5), decided now so
 * it never becomes an incident:
 *
 * - **Pre-release (current): reject-if-silent immediately.** Every first-party
 *   client gains the announce in the same release wave; the Android smoke gate
 *   asserts it. A serialized-transport call on a session with no admitted
 *   announce fails typed ([org.tenshu.core.UnannouncedSessionError]) instead
 *   of risking positional garbling.
 * - **Post-release: this becomes the transition knob.** A proxy release may
 *   run legacy-allowed (`false`) for a deprecation window — silent sessions
 *   then behave as "vector unknown, assume the proxy's own version" (today's
 *   implicit behavior, made explicit) — before flipping back to
 *   reject-if-silent.
 *
 * The gate applies only at the serialized-transport boundary
 * (`LocalTaskExecutor.executeAsyncFromBytes`): in-process callers pass live
 * objects, which cannot positionally garble — route-1 clients still announce
 * (§6.6, one code path), but their calls are not gated.
 */
object SchemaSessionPolicy {
    const val REJECT_IF_SILENT: Boolean = true
}

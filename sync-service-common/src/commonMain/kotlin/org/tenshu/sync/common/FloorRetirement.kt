package org.tenshu.sync.common

/**
 * Whether a service's old migration steps can now be **deleted** — the last link
 * in E4's chain (`core-schema-evolution.md` §8.3), and the point where the
 * machinery hands back to a human.
 *
 * ## ⚠⚠ Why this is a verdict and not a mover
 *
 * The readable floor is **derived, never stored**:
 * `SchemaProvider.currentVersionFloors` walks the *registered* migration chain,
 * and §4's cut-off mechanism is literally "delete steps". So the floor moves when
 * a developer removes steps in a PR — there is no runtime value for code to
 * advance, and anything claiming to "advance the floor" at runtime would be
 * moving a number nothing reads.
 *
 * What compaction and the roster genuinely produce is the *evidence* that the
 * deletion is now safe. This computes that, so the decision is made on data
 * rather than on nerve.
 *
 * ## The two conditions, and why each is necessary
 *
 * Deleting the steps below `F` means this build can no longer read history
 * written under versions `< F`. That is safe only when:
 *
 *  1. ⚠ **No device still runs below `F`** — because a device at `v < F` is still
 *     *producing* blocks at `v`, and after the deletion nobody could read them.
 *     The roster is therefore gating on **producers**, not on readers; it is the
 *     same MIN [RosterSchemaFloor] computes, used for a different question.
 *  2. ⚠ **History has been re-emitted at `≥ F`** — the compaction receipt. Without
 *     it, blocks below `F` are still the only copy of that state, and deleting
 *     their upcasters strands them.
 *
 * ⚠ The receipt proves **re-emission**, not deletion: superseded blocks leave the
 * relay on the ordinary retention sweep afterwards. That is why this answers "the
 * steps may go", not "the history is gone" — and why a conservative operator may
 * still want a retention window's grace before making the cut.
 */
object FloorRetirement {

    sealed interface Verdict {

        /**
         * Steps whose `fromVersion` is **below** [uptoVersion] may be deleted; the
         * derived floor then becomes [uptoVersion] on its own.
         */
        data class Retirable(val uptoVersion: Int) : Verdict

        /** [reasons] are phrased for a human deciding whether to cut. */
        data class Blocked(val reasons: List<String>) : Verdict
    }

    /**
     * @param currentFloor what the registered chain yields today. When it already
     *   equals [rosterMinVersion] there is nothing left to retire.
     * @param rosterMinVersion the lowest version any non-revoked device runs, or
     *   null when the roster is not fully known ([RosterSchemaFloor.Verdict.Unknown]).
     *   ⚠ Null blocks — an unknown roster is never evidence of safety.
     * @param receiptVersion the version the newest compaction receipt names, or
     *   null if this service has never been compacted here.
     */
    fun evaluate(
        serviceId: String,
        currentFloor: Int,
        rosterMinVersion: Int?,
        receiptVersion: Int?,
    ): Verdict {
        val reasons = mutableListOf<String>()

        if (rosterMinVersion == null) {
            reasons += "the device roster is incomplete — at least one device has not " +
                "recorded what it runs, so it may still be producing older blocks"
        }
        if (receiptVersion == null) {
            reasons += "'$serviceId' has never been compacted here, so history below the " +
                "current floor is still the only copy of that state"
        }
        if (reasons.isNotEmpty()) return Verdict.Blocked(reasons)

        // Both known: the cut can go no higher than either allows.
        val safeUpto = minOf(rosterMinVersion!!, receiptVersion!!)

        if (safeUpto <= currentFloor) {
            // Not a failure — the ordinary state. Say WHICH condition pins it, so
            // the answer is actionable rather than a bare "no".
            reasons += when {
                rosterMinVersion < receiptVersion ->
                    "a device still runs '$serviceId' at v$rosterMinVersion, at or below the " +
                        "current floor v$currentFloor — it must update, or be revoked (ADR 0007)"

                receiptVersion < rosterMinVersion ->
                    "'$serviceId' was last compacted to v$receiptVersion, at or below the " +
                        "current floor v$currentFloor — run compaction at the current version first"

                else ->
                    "nothing to retire: '$serviceId' is already at its floor v$currentFloor"
            }
            return Verdict.Blocked(reasons)
        }

        return Verdict.Retirable(uptoVersion = safeUpto)
    }
}

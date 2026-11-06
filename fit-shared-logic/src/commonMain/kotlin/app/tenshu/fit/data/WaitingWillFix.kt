package app.tenshu.fit.data

import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Whether **waiting** will fix a failed read — the question an empty state is implicitly answering
 * when it says *"Still trying…"*.
 *
 * ## ⚠⚠ Why the copy could not stay unconditional
 *
 * `HomeUnavailableState`'s KDoc justifies having no retry button on the grounds that *"the read is
 * a reactive `flow()` subscription that retries itself"*. True — and it makes *"Still trying…"* a
 * correct thing to say about a transport outage, which does recover on its own.
 *
 * It is a **lie** about the two schema cases and about an uninstalled Control. A refused announce
 * ([UnreachableReason.Skew]) rejects the session until the user updates something; retrying
 * accomplishes nothing, however long they wait. A fenced device ([UnreachableReason.Fenced]) is
 * not pulling at all. Found on the first device pass of the skew banner: the shell said *"Update
 * Control to keep your health data syncing"* while the body directly beneath it said *"Still
 * trying…"* — two contradictory claims on one screen, the specific one correct and the reassuring
 * one false.
 *
 * ⚠ `null` (nothing known, or Control is fine) maps to **true**, deliberately. The retry claim is
 * the honest default: absent a known schema cause, the subscription really is retrying, and the
 * failure is the kind that clears itself.
 */
fun FitRepository.waitingWillFix(): Flow<Boolean> =
    controlUnreachableReason().map(::waitingWillFix)

/**
 * The decision itself, split out from the flow so it is directly testable — the drift risk lives in
 * the `when`, not in the plumbing around it, and the alternative was a hand-rolled fake of a
 * repository interface with a dozen unrelated abstract members.
 *
 * ⚠ The `when` now lives in `:app-reachability`, hoisted when Photos needed the same answer for its
 * feed's **Try again** button. Kept as a Fit-named function so this module's call sites and tests
 * are unchanged — the point of the move is that there is one `when`, not that it is spelled here.
 */
fun waitingWillFix(reason: UnreachableReason?): Boolean =
    app.tenshu.reachability.waitingWillFix(reason)

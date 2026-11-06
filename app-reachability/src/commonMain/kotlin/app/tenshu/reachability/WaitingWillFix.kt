package app.tenshu.reachability

/**
 * Whether **waiting** will fix a failed read — the question an empty state is implicitly answering
 * when it says *"Still trying…"*, and the one a **Try again** button is implicitly answering just
 * by being there.
 *
 * ## ⚠⚠ Why it is not "is there an error"
 *
 * A reactive `flow()` read retries itself, which makes *"still trying"* a correct thing to say
 * about a transport outage. It is a **lie** about every other reason. A refused announce
 * ([UnreachableReason.Skew]) rejects the session until the user updates something; a fenced device
 * ([UnreachableReason.Fenced]) is not pulling at all; and an uninstalled Control
 * ([UnreachableReason.NotInstalled]) is not going to become installed by anyone tapping *retry* —
 * nothing on the device is going to become Control.
 *
 * ⚠ `null` (nothing known, or Control is fine) maps to **true**, deliberately. The retry claim is
 * the honest default: absent a known cause, the subscription really is retrying and the failure is
 * the kind that clears itself.
 *
 * ⚠⚠ Lives here rather than in an app because the judgement is entirely about
 * [UnreachableReason] and nothing else. It was written in Fit (`fit-app.md`, after a device pass
 * found the shell saying *"Update Control"* while the body beneath said *"Still trying…"* — two
 * contradictory claims on one screen, the specific one correct and the reassuring one false), and
 * hoisted the moment a second app needed the same answer, rather than after they had drifted.
 */
fun waitingWillFix(reason: UnreachableReason?): Boolean = when (reason) {
    is UnreachableReason.Skew, is UnreachableReason.Fenced -> false
    UnreachableReason.NotInstalled -> false
    // ⚠ Nothing on the device creates a realm by waiting (ADR 0025 §Decision 8).
    UnreachableReason.NotSetUp -> false
    // Transient: the realm read is in flight and retried on a backoff.
    UnreachableReason.Checking -> true
    UnreachableReason.Transport, null -> true
}

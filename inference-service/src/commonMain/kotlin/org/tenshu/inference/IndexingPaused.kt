package org.tenshu.inference

import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope

/**
 * "Stop indexing on **this** device" — IN-D6's *pausable* (the 1e-2 slice).
 *
 * ⚠⚠ **Device-scoped, not realm-scoped, and that is the same argument `deviceConsent` makes.**
 * A first index is a multi-hour, battery-visible run; a phone the user is about to travel with and
 * a plugged-in desktop are legitimately different answers. Pausing on a laptop must not stop the
 * hub that was happily working.
 *
 * ⚠ **A preference rather than an in-memory flag**, because a pause has to survive the proxy dying
 * — which on Android it does routinely. A pause that forgets itself on the next launch is one the
 * user has to keep re-asserting, and they will conclude it does not work.
 *
 * ⚠⚠ **Distinct from consent, and deliberately not folded into it.** Withdrawing consent means
 * *"do not index my library"* and IN-D5 implies the derived rows should eventually go; pausing
 * means *"not right now"* and keeps everything. Reusing `deviceConsent` as a pause button would
 * make "my phone is hot" and "I changed my mind about this feature" the same act — and would make
 * un-pausing look like fresh consent.
 *
 * ⚠ Default **false**: silence is "not paused". Unlike consent, where silence must mean no, the
 * safe reading here is the one that leaves the user's explicit consent doing what they asked.
 */
val IndexingPaused: Preference<Boolean> = Preference(
    id = "inference.device.indexingPaused",
    scope = PreferenceScope.DEVICE,
    default = false,
    adapter = BooleanAdapter,
)

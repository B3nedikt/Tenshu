package org.tenshu.core.common.app

/**
 * Where the user's *"which app opens this?"* choice is stored
 * (`control-cross-app-open.md` CO-D2, TT-4).
 *
 * ⭐⭐ **The choice is a re-ordering, not an override.** CO-D2 says the compiled-in table becomes
 * the *fallback* under the user's primary-handler choice — and `HandlerTable.handlersFor` already
 * returns an **ordered candidate list** for exactly this reason. A preference therefore moves one
 * candidate to the front; it does not replace the list. That matters because the installed-here
 * check still runs afterwards, so a preferred app that is not on *this* device degrades to the next
 * candidate instead of failing.
 *
 * ⚠⚠ **A preference naming a non-candidate is ignored, deliberately.** The table is a claim that an
 * app has a receiving route (§5f.2); honouring an arbitrary appId would let a stale or hand-edited
 * setting send a row to an app that cannot show it — trading an honest refusal for an app that
 * opens and displays nothing.
 *
 * ⚠ **Realm-scoped, not device-scoped.** *"I prefer Timeline for places"* is a statement about the
 * user, not about a device, and the per-device part is already handled by the installed-here check
 * degrading gracefully. Choose once, apply everywhere it can.
 *
 * ⚠ The id is derived from the type so no table of ids has to be maintained alongside the table of
 * handlers — the two would drift, and the drift would be silent.
 */
object PrimaryHandler {

    /** The realm preference id holding the chosen `appId` for [type]. */
    fun preferenceId(type: String): String = "$PREFIX$type"

    /** ⚠ Namespaced under `tenshu/app` because the *platform* owns this, not any one service. */
    private const val PREFIX = "tenshu/app/primaryHandler/"
}

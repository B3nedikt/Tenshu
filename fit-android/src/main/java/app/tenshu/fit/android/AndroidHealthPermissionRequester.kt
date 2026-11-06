package app.tenshu.fit.android

import app.tenshu.fit.data.HealthPermissionRequester

/**
 * The Android [HealthPermissionRequester] — a **holder**, because the thing that can actually launch
 * Health Connect's permission contract is an `ActivityResultLauncher`, which only exists while
 * [MainActivity] is alive, whereas the DI singleton that view models resolve from outlives it.
 *
 * The Activity lends its launcher at `onCreate` and takes it back at `onDestroy`. That handover is
 * **keyed by owner**: Activity lifecycles overlap (a new instance's `onCreate` runs *before* the old
 * instance's `onDestroy`), so an unconditional `detach()` would let a dying Activity clear the live
 * one's launcher — leaving the banner's "Try again" a permanent, silent no-op. A stale detach is
 * therefore ignored.
 *
 * A request with nothing attached is likewise a no-op rather than a crash: the app is backgrounded,
 * there is no UI to show a system prompt over, and Android would reject the call anyway.
 */
class AndroidHealthPermissionRequester : HealthPermissionRequester {

    private var owner: Any? = null
    private var launch: (() -> Unit)? = null
    private var launchFullHistory: (() -> Unit)? = null
    private var openSettings: (() -> Unit)? = null

    /**
     * Attaches [owner]'s launchers, displacing any previous one. Three, because they are different
     * acts at different moments: the required read permissions, the optional deep-history grant
     * (separate consent sheets — see [HealthPermissionRequester.requestFullHistory]), and plain
     * navigation to Health Connect for a grant that is not ours to ask for
     * ([HealthPermissionRequester.openHealthSettings]).
     */
    fun attach(
        owner: Any,
        launch: () -> Unit,
        launchFullHistory: () -> Unit = {},
        openSettings: () -> Unit = {},
    ) {
        this.owner = owner
        this.launch = launch
        this.launchFullHistory = launchFullHistory
        this.openSettings = openSettings
    }

    /** Releases [owner]'s launchers — ignored if a newer owner has since attached its own. */
    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        this.launch = null
        this.launchFullHistory = null
        this.openSettings = null
    }

    override fun request() {
        launch?.invoke()
    }

    override fun requestFullHistory() {
        launchFullHistory?.invoke()
    }

    override fun openHealthSettings() {
        openSettings?.invoke()
    }
}

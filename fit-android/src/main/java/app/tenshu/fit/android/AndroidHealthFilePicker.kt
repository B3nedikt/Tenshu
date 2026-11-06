package app.tenshu.fit.android

import app.tenshu.fit.data.HealthFilePicker

/**
 * The Android [HealthFilePicker] — a holder, for the same reason as [AndroidHealthPermissionRequester]:
 * the Storage Access Framework launcher belongs to an `Activity`, while the DI singleton the view
 * models resolve from outlives it.
 *
 * The handover is keyed by owner, so a dying Activity cannot clear a newer one's launcher and leave
 * the "Import from Google Takeout" row silently doing nothing.
 */
class AndroidHealthFilePicker : HealthFilePicker {

    private var owner: Any? = null
    private var launch: (() -> Unit)? = null

    fun attach(owner: Any, launch: () -> Unit) {
        this.owner = owner
        this.launch = launch
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        this.launch = null
    }

    override fun pick() {
        launch?.invoke()
    }
}

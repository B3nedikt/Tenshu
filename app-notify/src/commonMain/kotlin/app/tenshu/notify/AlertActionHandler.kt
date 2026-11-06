package app.tenshu.notify

/**
 * Handles a button press on an already-posted [AppAlert] (CA-5f).
 *
 * ⚠⚠ **Runs in the process that posted, with the app closed and no UI.** On
 * Android that is a `BroadcastReceiver`'s few seconds; the point of an action is
 * that it resolves without the user unlocking anything, so an implementation
 * that needs a screen has misunderstood the seam.
 *
 * ⚠ Keyed on the alert's `tapPayload` rather than a typed route, for the same
 * reason the payload is opaque: this module must not learn any app's vocabulary.
 * A handler that does not recognise a payload returns `false` and the next one
 * gets a look — so several apps can share one registration point.
 *
 * ⚠ `suspend`, unlike `NotificationPresenter.post`. Posting is a synchronous
 * hand-off to the OS; *handling* is a write through the proxy, which is not.
 */
fun interface AlertActionHandler {

    /**
     * @return true when this handler owned [tapPayload] and dealt with [actionId].
     */
    suspend fun handle(tapPayload: String, actionId: String): Boolean
}

/** ⚠ Tries each in order; the first to claim the payload wins. */
class CompositeAlertActionHandler(
    private val handlers: List<AlertActionHandler>,
) : AlertActionHandler {

    override suspend fun handle(tapPayload: String, actionId: String): Boolean =
        handlers.any { it.handle(tapPayload, actionId) }
}

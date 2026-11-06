package org.tenshu.core.common.app.proxy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.common.app.OpenTargetIntents
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.AndroidClientConnection
import org.tenshu.core.common.proxy.ClientConnection

/**
 * Android's [TargetDelivery] — the half of TT-1b that runs inside Control
 * (`control-cross-app-open.md` §5c).
 *
 * ⚠⚠ **Control does not launch anything, and that is measured rather than chosen.** A background
 * proxy calling `startActivity` is aborted by the framework *silently* — `startActivity` returns
 * without throwing — so a delivery that trusted it would report success on every blocked launch
 * (CO-D3's amendment). Control therefore does the two things it is allowed to do: it pushes the
 * request straight in when the handler is already connected, and otherwise mints an immutable
 * [PendingIntent] the **calling** app fires from its own foreground.
 *
 * ⭐ **The split keeps CO-D1 enforced rather than agreed.** The caller never learns which app was
 * resolved and never names a package: a `FLAG_IMMUTABLE` ticket is opaque, so what it fires is
 * exactly what Control built. Handing back a package name instead (§5c Option A) would make
 * Control's routing advisory and push `<queries>` into every app that can cite.
 */
internal class AndroidTargetDelivery(
    private val context: Context,
    private val serializer: BinarySerializer,
    private val connectionFor: (clientId: String) -> ClientConnection?,
) : TargetDelivery {

    override suspend fun deliver(
        appId: String,
        request: OpenTargetRequest,
        caller: ClientConnection,
    ): TargetDelivery.Delivered? {
        val packageName = androidPackageFor(appId)

        // 1. Already running and connected? Push the request straight in, so the handler has
        //    navigated before it is ever shown and the payload never depends on the OS.
        //
        // ⚠⚠ **This is NOT sufficient on its own, and CO-D3 was wrong to imply it was.** Measured
        // 2026-08-21: a warm handler receives the push and navigates perfectly — *in the
        // background*. The user taps a citation and watches nothing happen, because delivering
        // bytes and changing which app is in FRONT are different powers, and a background proxy
        // has neither. So the ticket below is minted either way; what the warm path saves is the
        // cold start, not the launch.
        val handler = connectionFor(packageName)
        val pushed = if (handler is AndroidClientConnection && handler.isConnected) {
            handler.send(request)
            true
        } else {
            false
        }

        // 2. Control builds the launch; the caller performs it. ⚠ For a warm handler the same
        //    `ACTION_MAIN` intent RESUMES the existing task rather than starting a second one, and
        //    the extra reaches `onNewIntent` — which is why the receiving side reads it there too.
        val androidCaller = caller as? AndroidClientConnection ?: run {
            Log.w(TAG, "Caller cannot receive a launch ticket: ${caller::class.simpleName}")
            // ⚠ A push with no way to raise the app is still better than nothing — the row is
            // there when the user next opens it — but it is not an open, so say so.
            return if (pushed) TargetDelivery.Delivered(launchTicketId = null) else null
        }

        val launch = Intent(Intent.ACTION_MAIN)
            .setPackage(packageName)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .putExtra(OpenTargetIntents.EXTRA, serializer.serialize(request))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val activity = context.packageManager
            .resolveActivity(launch, 0)
            ?.activityInfo
            ?: run {
                // ⚠ Not a launch failure — nothing to launch. `resolveActivity` returns null both
                // when the app is absent and when Control's `<queries>` does not name it, and the
                // second is a manifest bug that must not read as the user's app being missing.
                Log.w(TAG, "No launcher activity visible for $packageName")
                return if (pushed) TargetDelivery.Delivered(launchTicketId = null) else null
            }
        launch.setClassName(activity.packageName, activity.name)

        val ticketId = ticketIdFor(appId)
        val ticket = PendingIntent.getActivity(
            context,
            ticketId.hashCode(),
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val handedOver = androidCaller.sendLaunchTicket(ticket, ticketId.encodeToByteArray())
        if (!handedOver) {
            Log.w(TAG, "Could not hand the launch ticket to the caller")
            return if (pushed) TargetDelivery.Delivered(launchTicketId = null) else null
        }
        return TargetDelivery.Delivered(launchTicketId = ticketId)
    }

    /**
     * The Android rendering of a realm-wide `appId` (CO-D4).
     *
     * ⚠ Mechanical on purpose: `app.tenshu.calendar` → `app.tenshu.calendar.android`, which is how
     * every first-party `applicationId` is already built. A per-app table here would be a second
     * identity to keep in step with the first, and the compiled-in `HandlerTable` is already the
     * one place a mapping lives.
     */
    private fun androidPackageFor(appId: String): String = "$appId.android"

    internal companion object {
        private const val TAG = "AndroidTargetDelivery"

        /**
         * ⚠ Keyed by the handler, not by the request: a second tap while the first launch is still
         * in flight should replace the ticket rather than accumulate one, which is also why the
         * `PendingIntent` uses `FLAG_UPDATE_CURRENT` over the same request code.
         */
        fun ticketIdFor(appId: String): String = "launch:$appId"
    }
}

/**
 * Android's [TargetDelivery], for Control's per-platform proxy wiring.
 *
 * ⚠ A factory rather than a public class: nothing outside this module should be able to name the
 * delivery's shape, and the wiring only ever needs one.
 */
fun androidTargetDelivery(
    context: Context,
    serializer: BinarySerializer,
    connectionFor: (clientId: String) -> ClientConnection?,
): TargetDelivery = AndroidTargetDelivery(
    context = context,
    serializer = serializer,
    connectionFor = connectionFor,
)

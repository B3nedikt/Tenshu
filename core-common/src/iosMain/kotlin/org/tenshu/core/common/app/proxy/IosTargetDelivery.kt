package org.tenshu.core.common.app.proxy

import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.common.app.OpenTargetUrl
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ClientConnection

/**
 * iOS's [TargetDelivery] (`control-cross-app-open.md` §5i).
 *
 * ⚠⚠ **It delivers nothing, and that is structural rather than unfinished.** Under route 1 each
 * first-party iOS app runs its *own* in-process proxy, so the proxy resolving the handler is the
 * **caller's own**, and it has no channel to any other app. There is no connected-handler case to
 * push to, no cold-start case to launch into — there is a URL, and the app must open it.
 *
 * ⚠⚠⚠ **CO-D1's enforcement is architecturally unavailable here, and pretending otherwise would be
 * worse than saying so.** On Android the ticket is a `FLAG_IMMUTABLE` `PendingIntent`: opaque, so
 * what the caller fires is exactly what Control built. Here the "ticket" is a URL string handed
 * back inside the caller's own process — it can read it, and therefore learns which app was
 * resolved. Wrapping it in a handle would be theatre: the map would live in the same process as the
 * code it was hiding from. **On iOS "the address is the data, never the app" survives as a
 * convention, not as an enforced property**, and the honest place to record that is here.
 *
 * ⭐ What *does* survive is everything else: resolution, the installed-here check, the five typed
 * refusals, and the `DataId` as the only address a caller ever names.
 */
internal class IosTargetDelivery(
    private val serializer: BinarySerializer,
) : TargetDelivery {

    override suspend fun deliver(
        appId: String,
        request: OpenTargetRequest,
        caller: ClientConnection,
    ): TargetDelivery.Delivered? {
        // ⚠ Null when the appId yields no scheme (CO-D4) — reported as LAUNCH_FAILED rather than
        // handing back an address nothing can possibly answer.
        val url = OpenTargetUrl.encode(appId, request, serializer) ?: return null
        return TargetDelivery.Delivered(launchTicketId = url)
    }
}

/**
 * iOS's [TargetDelivery], for the per-app route-1 proxy wiring.
 *
 * ⚠ Every app gets one. On Android and desktop a single Control instance holds the delivery for the
 * whole device; on iOS the delivery is per-app because the *proxy* is.
 */
fun iosTargetDelivery(serializer: BinarySerializer): TargetDelivery =
    IosTargetDelivery(serializer = serializer)

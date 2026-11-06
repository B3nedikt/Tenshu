package org.tenshu.sdk.ios

import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.common.app.OpenTargetUrl
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.DataId
import org.tenshu.core.get

/**
 * The row a `tenshu-<app>://open?...` URL names, in a shape Swift can switch on
 * (`control-cross-app-open.md` §5i).
 *
 * ⚠⚠ **Kotlin owns the contract; Swift owns the navigation.** That split is not a compromise — it
 * is how every other iOS surface in this repo already works. What must not drift between platforms
 * is the *scheme derivation, the encoding and the type discrimination*, and all three live in
 * Kotlin where one test covers every app. Which screen a type opens is per-app UI, and on iOS that
 * is SwiftUI.
 *
 * @param type the owning schema's type id — `tenshu/file`, `tenshu/calendar/eventOccurrence`, …
 * @param key the single key for a simply-addressed row; **empty for a multi-part id**, which is why
 *   [parts] exists rather than a lossy join.
 * @param parts every key part in order — one entry for a `StringDataId`, two for an occurrence.
 */
data class OpenedTarget(
    val type: String,
    val key: String,
    val parts: List<String>,
)

/**
 * Decode a URL iOS handed the app, or `null` for anything that is not one of ours.
 *
 * ⚠⚠ Null is the ordinary case, not an error: a URL scheme is not exclusive, any app on the device
 * may `openURL` at us, and iOS also delivers URLs from entirely unrelated senders. A throwing
 * decoder would let anyone terminate the app with one call.
 */
fun decodeOpenTargetUrl(url: String): OpenedTarget? {
    val target = OpenTargetUrl.decode(url, OpenTargetDecoder.parser) ?: return null
    return when (target) {
        is DataId.StringDataId -> OpenedTarget(
            type = target.type.get(),
            key = target.keyValue.get(),
            parts = listOf(target.keyValue.get()),
        )

        is DataId.CompositeEntityDataId -> {
            val parts = target.keyValues.toList()
                .filterIsInstance<DataId.StringDataId>()
                .map { part -> part.keyValue.get() }
            OpenedTarget(
                type = target.type.get(),
                // ⚠ Deliberately empty: a joined key would need a separator no key may contain,
                // and a silent collision there is the bug `OccurrenceId` already refused to risk.
                key = "",
                parts = parts,
            )
        }

        else -> null
    }
}

/**
 * Holds a cross-app-open URL that arrived before the app's runtime was up
 * (`control-cross-app-open.md` §5i).
 *
 * ⚠⚠⚠ **This exists because the common case is the broken one.** On iOS a cross-app open arrives
 * as a launch URL — which is precisely the moment the route-1 runtime is *least* likely to be
 * installed: Drive's shipping shell shows `ConnectToControlView` until a Control session is found,
 * and installs nothing until then. A decoder that simply returned null when the graph was absent
 * therefore failed on every cold open and worked only on the warm one nobody was testing. Measured
 * on a simulator, not reasoned about: the URL fired, the app was running, and nothing happened.
 *
 * ⭐ It is the iOS shape of `OpenTargetInbox`'s `replay = 1`, which the other platforms already
 * needed for the identical ordering problem — the launch payload always beats the composition.
 *
 * ⚠ One slot, last-wins, never durable: a second open supersedes an unclaimed first, and nothing
 * survives the process. That is CO-D8 holding — the request belongs to a gesture.
 */
object PendingOpenUrl {

    private var url: String? = null
    private var onDecoded: ((OpenedTarget) -> Unit)? = null

    /** Stash a URL the runtime could not decode yet. */
    fun hold(rawUrl: String) {
        url = rawUrl
    }

    /**
     * Called when a sink appears: decode anything held and hand it over.
     *
     * ⚠ Clears the slot whether or not it decoded. A URL that is not ours must not sit in the
     * buffer shadowing the next real one.
     */
    fun drain() {
        val held = url ?: return
        val sink = onDecoded ?: return
        url = null
        decodeOpenTargetUrl(held)?.let(sink)
    }

    /**
     * Swift installs the sink that navigates, and anything already held is delivered immediately.
     *
     * ⚠⚠ **Draining here rather than only from the runtime is the fix for the ordering trap.** A
     * launch URL reaches `onOpenURL` before the app root has installed anything, so a hold that
     * waited for the *runtime* waited for something that may never come — an unprovisioned app
     * installs no runtime at all. The sink is the only thing the delivery actually needs.
     */
    fun setSink(sink: (OpenedTarget) -> Unit) {
        onDecoded = sink
        drain()
    }

    internal fun clearForTest() {
        url = null
        onDecoded = null
    }
}

/**
 * Builds a real cross-app-open URL for a UI test to fire at this app
 * (`control-cross-app-open.md` §5i.8).
 *
 * ⚠⚠ **Built by the same code the delivery uses**, so the test exercises the production encoder
 * rather than a hand-written string that could drift from it. A URL the test *composed itself*
 * would pass while the real `OpenTargetUrl.encode` was broken, which is the failure mode the whole
 * round-trip is meant to rule out.
 *
 * ⚠ Harness-facing. Nothing in the shipping path calls it — a receiving app has no reason to build
 * an address for itself.
 */
fun probeOpenTargetUrl(appId: String, type: String, key: String): String? {
    val context = OpenTargetDecoder.context
    val request = OpenTargetRequest.create(
        context = context,
        target = DataId.StringDataId.create(context = context, type = type, keyValue = key),
    )
    return OpenTargetUrl.encode(appId, request, BinarySerializer())
}

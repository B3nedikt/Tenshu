package org.tenshu.core.common.app

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.tenshu.core.Data
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.DataId

/**
 * iOS's carrier for a cross-app open (`control-cross-app-open.md` CO-D4, TT-3).
 *
 * ⚠⚠ **On iOS the URL is not one carrier among several — it is the only one.** Under route 1 every
 * first-party app runs its *own* in-process proxy, so there is no channel between two apps for a
 * request to be pushed down and CO-D3's cheap path simply does not exist. Android has two carriers
 * and desktop has one plus a launch; iOS has this.
 *
 * ⭐ **The scheme is DERIVED, never authored** (CO-D4): `app.tenshu.drive` → `tenshu-drive`. A
 * per-app scheme written by hand would be a second identity to keep in step with the first, and the
 * one that drifts is the one nobody tests. The shipped precedent is `tenshu-control://setup`
 * (ADR 0022), whose scheme is spelled out in `control-ios/project.yml` — ⚠ that is the one place a
 * scheme is declared, and `Info.plist` is generated and gitignored, so `project.yml` is the source.
 *
 * ⚠ The payload is the **same serialised `OpenTargetRequest`** the Android extra carries, base64url
 * encoded. Reusing the wire shape rather than inventing a query-parameter encoding is what keeps
 * `CompositeEntityDataId` — a calendar occurrence's `(eventId, recurrenceId)` — surviving the trip
 * (CO-D5).
 */
object OpenTargetUrl {

    /** The host every cross-app open URL uses, so an app can tell one from a future second use. */
    const val HOST: String = "open"

    /** The query parameter carrying the base64url payload. */
    const val PARAM: String = "t"

    /**
     * `app.tenshu.drive` → `tenshu-drive`, or `null` when [appId] is not a Tenshu app id.
     *
     * ⚠ Returns null rather than guessing: a scheme built from junk would be registered by nobody
     * and `openURL` would report "nothing handles this", which reads to the user as *"the app is
     * not installed"* rather than *"we built a nonsense address"*.
     */
    fun schemeFor(appId: String): String? {
        val suffix = appId.removePrefix(PREFIX)
        if (suffix == appId || suffix.isEmpty() || suffix.contains('.')) return null
        return "tenshu-$suffix"
    }

    /** The URL that opens [request] in [appId]'s app, or `null` if [appId] has no derivable scheme. */
    @OptIn(ExperimentalEncodingApi::class)
    fun encode(appId: String, request: OpenTargetRequest, serializer: BinarySerializer): String? {
        val scheme = schemeFor(appId) ?: return null
        val payload = Base64.UrlSafe.encode(serializer.serialize(request))
        return "$scheme://$HOST?$PARAM=$payload"
    }

    /**
     * The target [url] names, or `null` for any URL that is not one of ours.
     *
     * ⚠⚠ Returns null rather than throwing on anything malformed. A handling app receives whatever
     * the OS hands it — including URLs from other senders entirely, since a scheme is not
     * exclusive — so a parse failure is an ordinary event, not an error condition. Crashing here
     * would let any app on the device kill ours with one `openURL`.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(url: String, parser: BinaryParser): DataId? {
        val marker = "://$HOST?$PARAM="
        val at = url.indexOf(marker)
        if (at <= 0) return null
        val encoded = url.substring(at + marker.length)
        if (encoded.isEmpty()) return null
        val bytes = runCatching { Base64.UrlSafe.decode(encoded) }.getOrNull() ?: return null
        val parsed = runCatching { parser.parse<Data>(bytes) }.getOrNull()
        return (parsed as? OpenTargetRequest)?.target
    }

    private const val PREFIX = "app.tenshu."
}

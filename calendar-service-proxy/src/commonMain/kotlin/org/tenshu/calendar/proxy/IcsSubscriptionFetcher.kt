package org.tenshu.calendar.proxy

/**
 * What a fetch came back with.
 *
 * ⚠ `NotModified` is a distinct outcome, not an empty `Fetched`. A 304 means the
 * feed is unchanged and its events must be **left alone** — treating it as an
 * empty calendar would delete every holiday the user has.
 */
sealed interface IcsFetchOutcome {
    data class Fetched(val text: String, val etag: String = "") : IcsFetchOutcome
    data object NotModified : IcsFetchOutcome
    data class Failed(val reason: String) : IcsFetchOutcome
}

/**
 * Fetches a remote `.ics` (CA-7).
 *
 * ⚠⚠ **Behind an interface for the same reason `RegionDownloader` is**: the op
 * and its tests have to be independent of the network stack. Every decision worth
 * testing in a subscription refresh — what replaces what, what a 304 means, what
 * happens when one feed of three fails — lives above this line.
 *
 * ⚠⚠ **The fetch runs in the proxy (Control), never in the app.** Calendar's
 * manifest strips `INTERNET`; this is what keeps that true while a subscription
 * still works. The `RegionDownloader` precedent, and the same reason: all egress
 * is Control's.
 *
 * ⚠ [etag] is the caller's last known one, sent as `If-None-Match`. A feed that
 * ignores it simply answers 200 and nothing breaks.
 */
fun interface IcsSubscriptionFetcher {

    suspend fun fetch(url: String, etag: String): IcsFetchOutcome
}

/**
 * The default when no platform binds one: every fetch fails, loudly enough to
 * show in the UI and quietly enough not to crash a refresh.
 *
 * ⚠ Deliberately not a silent success. A host with no HTTP stack that reported
 * "fetched, nothing changed" would leave a subscription looking healthy forever.
 */
object NoopIcsSubscriptionFetcher : IcsSubscriptionFetcher {
    override suspend fun fetch(url: String, etag: String): IcsFetchOutcome =
        IcsFetchOutcome.Failed("No network access on this device")
}

/**
 * Rewrites the schemes a calendar URL is handed over in.
 *
 * ⚠⚠ `webcal://` is what every publisher's *Subscribe* button emits, and it is
 * not a real protocol — it is `https` with a different word in front, invented so
 * a browser would hand the link to a calendar app. Refusing it would reject the
 * exact link most users arrive with.
 *
 * ⚠ Plain `http://` is **refused**, not upgraded. A silent upgrade would claim a
 * guarantee we cannot make if the host does not serve TLS, and a calendar fetched
 * in the clear discloses its content to every hop — which is a stronger statement
 * than the IP disclosure the user consented to.
 */
fun normalizeIcsUrl(raw: String): String? {
    val text = raw.trim()
    return when {
        text.isEmpty() -> null
        text.startsWith("webcal://", ignoreCase = true) -> "https://" + text.substring(9)
        text.startsWith("webcals://", ignoreCase = true) -> "https://" + text.substring(10)
        text.startsWith("https://", ignoreCase = true) -> text
        else -> null
    }
}

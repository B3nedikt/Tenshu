package org.tenshu.calendar.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * The real fetch (CA-7), over the proxy's HTTP client.
 *
 * ⚠⚠ **Runs in the proxy, never in the app's own UI code.** On Android that means
 * Control, never Calendar — the whole reason the seam exists: Calendar's manifest
 * strips `INTERNET`, and a subscription still works because the proxy does the
 * reaching (the `DefaultRegionDownloader` precedent). ⚠ On iOS route 1 every
 * first-party process assembles the same proxy graph and registers the refresh
 * schedule (control-shared-proxy iosMain `PlatformProxyModule`), so the fetch runs
 * in whichever process drains it — Calendar, Control or another Tenshu app. Either
 * way it is a direct request to the publisher that the relay never sees (threat
 * model A5, calendar subscriptions).
 *
 * ⚠ **HTTPS only** — [normalizeIcsUrl] rejects plain `http` rather than upgrading
 * it. A calendar fetched in the clear discloses its *content* to every hop, which
 * is a much stronger statement than the IP disclosure the user consented to.
 *
 * ⚠ No credentials, no cookies, no redirect to a different scheme. A calendar
 * feed is a public document; anything that needed a secret to fetch would be a
 * different feature with a different consent conversation.
 */
class KtorIcsSubscriptionFetcher(
    private val client: HttpClient,
    /** ⚠ Bounded so one enormous feed cannot exhaust the proxy's heap. */
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) : IcsSubscriptionFetcher {

    override suspend fun fetch(url: String, etag: String): IcsFetchOutcome {
        val normalized = normalizeIcsUrl(url)
            ?: return IcsFetchOutcome.Failed("Only https:// and webcal:// addresses are supported")

        return runCatchingCancellable {
            val response = client.get(normalized) {
                // ⚠ Conditional when we have one: a 304 transfers no calendar and
                // tells the publisher strictly less than a full GET would.
                if (etag.isNotBlank()) header("If-None-Match", etag)
                header("Accept", "text/calendar, text/plain;q=0.5")
            }
            when {
                response.status == HttpStatusCode.NotModified -> IcsFetchOutcome.NotModified
                !response.status.isSuccess() ->
                    IcsFetchOutcome.Failed("The publisher answered ${response.status.value}")
                else -> {
                    val text = response.bodyAsText()
                    // ⚠ Checked after reading rather than by a streaming cap: a
                    // calendar is text and the bound exists to stop the absurd
                    // case, not to make a 10 MB feed work half way.
                    if (text.length > maxBytes) {
                        IcsFetchOutcome.Failed("That calendar is too large to subscribe to")
                    } else {
                        IcsFetchOutcome.Fetched(
                            text = text,
                            etag = response.headers["ETag"].orEmpty(),
                        )
                    }
                }
            }
        }.getOrElse { error ->
            // ⚠ The message is shown to the user, so it must not be a stack trace
            // — and it must not be blank either, or the UI says "failed" and
            // nothing else.
            IcsFetchOutcome.Failed(error.message?.takeIf { it.isNotBlank() } ?: "Could not reach it")
        }
    }

    companion object {
        /** ⚠ 8 MiB of text — far past any real feed, well short of an OOM. */
        const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024
    }
}

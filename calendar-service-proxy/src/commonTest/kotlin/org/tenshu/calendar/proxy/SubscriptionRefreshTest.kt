package org.tenshu.calendar.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.CalendarSubscription
import org.tenshu.calendar.Event
import org.tenshu.calendar.calendarId
import org.tenshu.calendar.deleteSubscription
import org.tenshu.calendar.etag
import org.tenshu.calendar.eventId
import org.tenshu.calendar.lastFetchedEpochMs
import org.tenshu.calendar.refreshSubscriptions
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.saveSubscription
import org.tenshu.calendar.subscriptionId
import org.tenshu.calendar.title
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get
import org.tenshu.calendar.test.calendarRow
import org.tenshu.calendar.test.environmentFor

/**
 * Subscription refresh, end to end through storage (CA-7).
 *
 * ⚠⚠ **The assertions that matter are about *deletion*.** A refresh is a sync,
 * not an import: an event the feed stopped listing has to disappear. Everything
 * else here is the CA-6 import path, already proven.
 */
class SubscriptionRefreshTest {

    private val now = 1_785_000_000_000L

    private class RecordingFetcher(
        var outcome: IcsFetchOutcome = IcsFetchOutcome.Failed("unset"),
    ) : IcsSubscriptionFetcher {
        val calls = mutableListOf<Pair<String, String>>()
        var throwOn: String? = null

        override suspend fun fetch(url: String, etag: String): IcsFetchOutcome {
            calls += url to etag
            throwOn?.let { if (url.contains(it)) throw IllegalStateException("boom") }
            return outcome
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.proxy(fetcher: IcsSubscriptionFetcher) =
        subscriptionProxy(fetcher, now)

    private suspend fun service(proxy: TestProxy): CalendarService =
        environmentFor(proxy).getService(CalendarService.ID)

    private suspend fun subscribe(
        proxy: TestProxy,
        url: String = "https://example.com/holidays.ics",
        calendarId: String = "holidays",
    ) {
        val service = service(proxy)
        service.saveCalendar(calendarId, "Holidays", 0xF4B400)
        service.saveSubscription(
            subscriptionId = CalendarSubscription.idFor(url),
            calendarId = calendarId,
            url = url,
        )
    }

    private suspend fun titles(proxy: TestProxy, calendarId: String = "holidays"): List<String> =
        service(proxy).events
            .select { fields(Event.Field.EVENT_ID, Event.Field.TITLE, Event.Field.CALENDAR_ID) }
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            .orEmpty()
            .filter { it.calendarId.get() == calendarId }
            .map { it.title.get() }
            .sorted()

    private suspend fun subscriptions(proxy: TestProxy) =
        service(proxy).subscriptions
            .select {
                fields(
                    CalendarSubscription.Field.SUBSCRIPTION_ID,
                    CalendarSubscription.Field.CALENDAR_ID,
                    CalendarSubscription.Field.URL,
                    CalendarSubscription.Field.ENABLED,
                    CalendarSubscription.Field.LAST_FETCHED_EPOCH_MS,
                    CalendarSubscription.Field.ETAG,
                )
            }
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            .orEmpty()

    private fun feed(vararg summaries: String): String = buildString {
        append("BEGIN:VCALENDAR\r\n")
        summaries.forEach { summary ->
            append("BEGIN:VEVENT\r\nUID:$summary\r\nSUMMARY:$summary\r\n")
            append("DTSTART;VALUE=DATE:20260803\r\nEND:VEVENT\r\n")
        }
        append("END:VCALENDAR\r\n")
    }

    // --- fetching ------------------------------------------------------------

    @Test
    fun a_refresh_fills_the_subscribed_calendar() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr", "Ostern")))
        val proxy = proxy(fetcher)
        subscribe(proxy)

        service(proxy).refreshSubscriptions()

        assertEquals(listOf("Neujahr", "Ostern"), titles(proxy))
    }

    /**
     * ⚠⚠ **The decision this whole class exists for.** CA-6's import is additive;
     * a feed is authoritative. A fixture cancelled upstream has to disappear.
     */
    @Test
    fun an_event_the_feed_stopped_listing_is_deleted() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr", "Ostern")))
        val proxy = proxy(fetcher)
        subscribe(proxy)
        service(proxy).refreshSubscriptions()

        fetcher.outcome = IcsFetchOutcome.Fetched(feed("Neujahr"))
        service(proxy).refreshSubscriptions()

        assertEquals(listOf("Neujahr"), titles(proxy))
    }

    /** ⚠ …and the sweep is scoped to the subscribed calendar, not the whole store. */
    @Test
    fun the_sweep_leaves_other_calendars_alone() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr")))
        val proxy = proxy(fetcher)
        subscribe(proxy)
        val service = service(proxy)
        service.saveCalendar("work", "Work", 0x4285F4)
        service.saveEvent("standup", "Standup", "2026-08-03T09:00", calendarId = "work")

        service.refreshSubscriptions()

        assertEquals(listOf("Standup"), titles(proxy, "work"))
    }

    // --- two calendars, one UID (calendar-external-sources.md §7) --------------

    /** Answers each URL with its own feed, so two subscriptions can disagree. */
    private class FeedsByUrl(private val feeds: Map<String, String>) : IcsSubscriptionFetcher {
        override suspend fun fetch(url: String, etag: String): IcsFetchOutcome =
            feeds.entries.firstOrNull { url.contains(it.key) }
                ?.let { IcsFetchOutcome.Fetched(it.value) }
                ?: IcsFetchOutcome.Failed("no feed for $url")
    }

    private suspend fun eventIds(proxy: TestProxy, calendarId: String): List<String> =
        service(proxy).events
            .select { fields(Event.Field.EVENT_ID, Event.Field.CALENDAR_ID) }
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            .orEmpty()
            .filter { it.calendarId.get() == calendarId }
            .map { it.eventId.get() }
            .sorted()

    /**
     * ⚠⚠ **Two feeds that share a `UID` each keep their own copy.** Keyed by bare `UID`, one row
     * took turns between the two calendars — the event was in one of them at a time (hide that
     * calendar and it was gone) and every refresh rewrote it. A meeting invite carries the same
     * `UID` in every attendee's published calendar, so this is ordinary, not contrived.
     */
    @Test
    fun two_feeds_that_share_a_uid_each_keep_the_event() = runTest {
        val proxy = proxy(
            FeedsByUrl(
                mapOf(
                    "anna.ics" to feed("Offsite"),
                    "ben.ics" to feed("Offsite"),
                ),
            ),
        )
        subscribe(proxy, url = "https://example.com/anna.ics", calendarId = "anna")
        subscribe(proxy, url = "https://example.com/ben.ics", calendarId = "ben")

        service(proxy).refreshSubscriptions()
        service(proxy).refreshSubscriptions()

        assertEquals(listOf("Offsite"), titles(proxy, "anna"))
        assertEquals(listOf("Offsite"), titles(proxy, "ben"))
    }

    /**
     * ⚠⚠ **The decision §7 records: CA-7a's *imported-then-subscribed converges* gives way.**
     * A subscription always gets a calendar of its own, so that "convergence" was a *move* — the
     * imported events left the user's calendar for the feed's, where unsubscribing deleted them.
     * The calendar the user imported into keeps its events through subscribe, refresh and
     * unsubscribe. (The bare-`UID` row here is what CA-6's import wrote before the fix.)
     */
    @Test
    fun subscribing_to_a_feed_never_takes_events_from_a_calendar_the_user_imported() = runTest {
        val proxy = proxy(RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr"))))
        val service = service(proxy)
        service.saveCalendar("mine", "Mine", 0x4285F4)
        service.saveEvent("Neujahr", "Neujahr", "2026-08-03", calendarId = "mine", allDay = true)
        subscribe(proxy)

        service.refreshSubscriptions()
        assertEquals(listOf("Neujahr"), titles(proxy, "mine"))
        assertEquals(listOf("Neujahr"), titles(proxy))

        service.deleteSubscription(subscriptions(proxy).single().subscriptionId.get())
        assertEquals(listOf("Neujahr"), titles(proxy, "mine"))
    }

    /**
     * ⚠ **A row stored before the fix keeps its id** (`ExternalCopyIds`' adoption rule), so
     * upgrading needs no re-keying migration: the first refresh neither duplicates it nor
     * deletes-and-recreates it — which would drop its reminders and dismissals.
     */
    @Test
    fun a_bare_uid_row_already_in_the_subscribed_calendar_is_adopted() = runTest {
        val proxy = proxy(RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr", "Ostern"))))
        subscribe(proxy)
        service(proxy).saveEvent("Neujahr", "Neujahr", "2026-08-03", calendarId = "holidays", allDay = true)

        service(proxy).refreshSubscriptions()

        assertEquals(listOf("Neujahr", "holidays~Ostern"), eventIds(proxy, "holidays"))
    }

    /**
     * ⚠⚠ **304 must touch nothing but the timestamp.** Treating it as an empty
     * calendar would delete every event the feed holds — the single most
     * destructive way to get this wrong.
     */
    @Test
    fun a_not_modified_response_keeps_every_event() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr", "Ostern"), etag = "v1"))
        val proxy = proxy(fetcher)
        subscribe(proxy)
        service(proxy).refreshSubscriptions()

        fetcher.outcome = IcsFetchOutcome.NotModified
        service(proxy).refreshSubscriptions()

        assertEquals(listOf("Neujahr", "Ostern"), titles(proxy))
    }

    @Test
    fun the_etag_from_a_fetch_is_sent_back_on_the_next_one() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr"), etag = "\"abc\""))
        val proxy = proxy(fetcher)
        subscribe(proxy)

        service(proxy).refreshSubscriptions()
        service(proxy).refreshSubscriptions()

        assertEquals("", fetcher.calls[0].second, "nothing known on the first fetch")
        assertEquals("\"abc\"", fetcher.calls[1].second)
        assertEquals("\"abc\"", subscriptions(proxy).single().etag.get())
    }

    /** ⚠ A 304 keeps the etag rather than blanking it, or every fetch is full. */
    @Test
    fun a_not_modified_response_keeps_the_etag() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr"), etag = "\"abc\""))
        val proxy = proxy(fetcher)
        subscribe(proxy)
        service(proxy).refreshSubscriptions()

        fetcher.outcome = IcsFetchOutcome.NotModified
        service(proxy).refreshSubscriptions()

        assertEquals("\"abc\"", subscriptions(proxy).single().etag.get())
    }

    @Test
    fun a_successful_fetch_stamps_the_time() = runTest {
        val proxy = proxy(RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr"))))
        subscribe(proxy)

        service(proxy).refreshSubscriptions()

        assertEquals(now, subscriptions(proxy).single().lastFetchedEpochMs.get())
    }

    /**
     * ⚠ A failure leaves the timestamp alone, so the UI can say how stale the
     * calendar really is rather than claiming it just refreshed.
     */
    @Test
    fun a_failed_fetch_does_not_stamp_the_time() = runTest {
        val proxy = proxy(RecordingFetcher(IcsFetchOutcome.Failed("503")))
        subscribe(proxy)

        service(proxy).refreshSubscriptions()

        assertEquals(0L, subscriptions(proxy).single().lastFetchedEpochMs.get())
        assertTrue(titles(proxy).isEmpty())
    }

    /** ⚠ One publisher being down must not stop the others. */
    @Test
    fun one_failing_feed_does_not_stop_the_rest() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr")))
        fetcher.throwOn = "broken"
        val proxy = proxy(fetcher)
        subscribe(proxy, url = "https://broken.example/x.ics", calendarId = "broken")
        subscribe(proxy, url = "https://good.example/y.ics", calendarId = "good")

        service(proxy).refreshSubscriptions()

        assertTrue(titles(proxy, "broken").isEmpty())
        assertEquals(listOf("Neujahr"), titles(proxy, "good"))
    }

    /** ⚠ A paused subscription must not reach the network at all. */
    @Test
    fun a_paused_subscription_is_not_fetched() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr")))
        val proxy = proxy(fetcher)
        subscribe(proxy)
        val url = "https://example.com/holidays.ics"
        service(proxy).saveSubscription(
            subscriptionId = CalendarSubscription.idFor(url),
            calendarId = "holidays",
            url = url,
            enabled = false,
        )

        service(proxy).refreshSubscriptions()

        assertTrue(fetcher.calls.isEmpty())
    }

    /** ⚠⚠ …and pausing keeps the events, which is the whole difference from deleting. */
    @Test
    fun pausing_keeps_the_events_it_already_fetched() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr")))
        val proxy = proxy(fetcher)
        subscribe(proxy)
        service(proxy).refreshSubscriptions()
        val url = "https://example.com/holidays.ics"

        service(proxy).saveSubscription(
            subscriptionId = CalendarSubscription.idFor(url),
            calendarId = "holidays",
            url = url,
            enabled = false,
        )

        assertEquals(listOf("Neujahr"), titles(proxy))
    }

    /**
     * ⚠ A user edit supplies neither the etag nor the timestamp, and must not
     * blank them — or the next refresh re-downloads a calendar that had not
     * changed.
     */
    @Test
    fun editing_a_subscription_keeps_its_fetch_state() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr"), etag = "\"abc\""))
        val proxy = proxy(fetcher)
        subscribe(proxy)
        service(proxy).refreshSubscriptions()
        val url = "https://example.com/holidays.ics"

        service(proxy).saveSubscription(
            subscriptionId = CalendarSubscription.idFor(url),
            calendarId = "holidays",
            url = url,
            enabled = false,
        )

        val row = subscriptions(proxy).single()
        assertEquals("\"abc\"", row.etag.get())
        assertEquals(now, row.lastFetchedEpochMs.get())
    }

    // --- webcal --------------------------------------------------------------

    /** ⚠⚠ `webcal://` is what every publisher's Subscribe button emits. */
    @Test
    fun a_webcal_url_is_fetched_over_https() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr")))
        val proxy = proxy(fetcher)
        subscribe(proxy, url = "webcal://example.com/holidays.ics")

        service(proxy).refreshSubscriptions()

        assertEquals("https://example.com/holidays.ics", fetcher.calls.single().first)
    }

    /** ⚠ Plain `http` is refused, never silently upgraded — and never fetched. */
    @Test
    fun a_plain_http_url_is_never_fetched() = runTest {
        val fetcher = RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr")))
        val proxy = proxy(fetcher)
        subscribe(proxy, url = "http://example.com/holidays.ics")

        service(proxy).refreshSubscriptions()

        assertTrue(fetcher.calls.isEmpty())
    }

    @Test
    fun url_normalisation_accepts_what_publishers_emit_and_refuses_the_rest() {
        assertEquals("https://x/y.ics", normalizeIcsUrl("webcal://x/y.ics"))
        assertEquals("https://x/y.ics", normalizeIcsUrl("WEBCAL://x/y.ics"))
        assertEquals("https://x/y.ics", normalizeIcsUrl("webcals://x/y.ics"))
        assertEquals("https://x/y.ics", normalizeIcsUrl("  https://x/y.ics  "))
        assertNull(normalizeIcsUrl("http://x/y.ics"), "never silently upgraded")
        assertNull(normalizeIcsUrl("file:///etc/passwd"))
        assertNull(normalizeIcsUrl("javascript:alert(1)"))
        assertNull(normalizeIcsUrl(""))
    }

    // --- deleting ------------------------------------------------------------

    /**
     * ⚠⚠ Deleting a subscription takes the whole calendar. Leaving a frozen copy
     * would be a calendar that looks live and silently never updates again.
     */
    @Test
    fun deleting_a_subscription_takes_its_calendar_and_events() = runTest {
        val proxy = proxy(RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr"))))
        subscribe(proxy)
        service(proxy).refreshSubscriptions()
        val id = subscriptions(proxy).single().subscriptionId.get()

        service(proxy).deleteSubscription(id)

        assertTrue(subscriptions(proxy).isEmpty())
        assertTrue(titles(proxy).isEmpty())
        assertNull(proxy.calendarRow("holidays"))
    }

    @Test
    fun deleting_an_unknown_subscription_is_harmless() = runTest {
        val proxy = proxy(RecordingFetcher(IcsFetchOutcome.Fetched(feed("Neujahr"))))
        subscribe(proxy)

        service(proxy).deleteSubscription("sub-nope")

        assertEquals(1, subscriptions(proxy).size)
        assertFalse(proxy.calendarRow("holidays") == null)
    }
}

/**
 * ⚠ A proxy whose factory carries a real fetcher. The shared
 * `createCalendarProxy` takes one now, so this is a named alias rather than a
 * second fixture — one place still assembles the graph.
 */
internal fun kotlinx.coroutines.CoroutineScope.subscriptionProxy(
    fetcher: IcsSubscriptionFetcher,
    nowEpochMs: Long,
): TestProxy = org.tenshu.calendar.test.createCalendarProxy(
    coroutineScope = this,
    nowEpochMs = { nowEpochMs },
    fetcher = fetcher,
)

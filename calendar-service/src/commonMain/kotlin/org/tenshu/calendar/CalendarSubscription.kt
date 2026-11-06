package org.tenshu.calendar

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A remote `.ics` a [Calendar] is kept in step with — holiday feeds, sports
 * fixtures, school terms (`calendar-app.md` §2.4, Q9; CA-7).
 *
 * ⚠⚠ **The first time Calendar reaches outside the realm.** Fetching a
 * third-party URL discloses the user's IP and their interest in *that* calendar,
 * to that publisher, on a schedule. It is not an HR#1 problem — our relay never
 * sees it, and the fetch is Control's, so the app stays INTERNET-free — but it is
 * a real disclosure and the threat model records it (A5's CA-7 amendment).
 * **Creating a subscription IS the consent**, per-subscription as Q9 settled;
 * there is no global toggle because there is no global act to consent to.
 *
 * ⚠ **The row syncs; the fetch does not.** A subscription added on the phone
 * appears on the laptop, which is what a user expects — but each device fetches
 * for itself. Nominating one device to fetch would need an election, and the
 * events converge through the ordinary sync plane anyway. ⚠ The cost is that two
 * devices each disclose their own IP to the publisher, which the copy has to say.
 *
 * ⚠ **A subscribed calendar is read-only, and that is *derived*, not stored.** A
 * `Calendar` gains no field: "some subscription points at this id" is the whole
 * test, and reshaping `Calendar` for a flag that is already computable would cost
 * a real migration for nothing.
 */
interface CalendarSubscription : Composite {

    override val schema: CompositeSchema get() = Schema

    /**
     * The row key, **derived from the URL**.
     *
     * ⭐ Not random, following [Reminder]: two devices that subscribe to the same
     * feed while offline converge on one row rather than fetching it twice
     * forever. A subscription's identity really is its URL.
     */
    val subscriptionId: TString get() = getField(Field.SUBSCRIPTION_ID)

    /** The [Calendar.calendarId] this feed fills. Owned by the subscription. */
    val calendarId: TString get() = getField(Field.CALENDAR_ID)

    /**
     * Where to fetch from.
     *
     * ⚠ Stored as given, including a `webcal://` scheme — rewriting to `https`
     * happens at fetch time, so what the user typed is what the UI shows back.
     */
    val url: TString get() = getField(Field.URL)

    /**
     * Paused by the user.
     *
     * ⚠ Distinct from deleting: pausing stops the egress and *keeps the events*,
     * which is what someone who wants the holidays but not the traffic means.
     * Deleting takes the calendar with it.
     */
    val enabled: TBoolean get() = getField(Field.ENABLED)

    /** When it last fetched successfully; `0` for never. */
    val lastFetchedEpochMs: TInteger get() = getField(Field.LAST_FETCHED_EPOCH_MS)

    /**
     * The `ETag` the last successful fetch returned, or blank.
     *
     * ⚠⚠ Not an optimisation — a **politeness and privacy** measure. A
     * conditional request that 304s transfers no calendar and tells the publisher
     * strictly less than a full GET would. Feeds that ignore `ETag` simply keep
     * returning 200 and nothing breaks.
     */
    val etag: TString get() = getField(Field.ETAG)

    companion object {
        const val ID = "tenshu/calendar/subscription"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                CalendarSubscriptionDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(Field.SUBSCRIPTION_ID)
                string(Field.CALENDAR_ID)
                string(Field.URL)
                boolean(Field.ENABLED)
                int(Field.LAST_FETCHED_EPOCH_MS)
                string(Field.ETAG)
            }
        )

        fun create(
            context: TContext,
            subscriptionId: String,
            calendarId: String,
            url: String,
            enabled: Boolean = true,
            lastFetchedEpochMs: Long = 0L,
            etag: String = "",
        ): CalendarSubscription = context.create(
            type = ID,
            fields = listOf(subscriptionId, calendarId, url, enabled, lastFetchedEpochMs, etag),
        )

        /**
         * The stable id for [url].
         *
         * ⚠ Trimmed and lower-cased before hashing, so the same feed typed with a
         * trailing space or a capitalised host is one subscription. ⚠ Not
         * canonicalised further (no scheme rewrite, no query sorting) — that would
         * make two genuinely different URLs collide, and a duplicate subscription
         * is a smaller problem than a feed the user cannot add.
         */
        fun idFor(url: String): String =
            "sub-" + url.trim().lowercase().hashCode().toUInt().toString(16)
    }

    object Field {
        const val SUBSCRIPTION_ID = "$ID/subscriptionId"
        const val CALENDAR_ID = "$ID/calendarId"
        const val URL = "$ID/url"
        const val ENABLED = "$ID/enabled"
        const val LAST_FETCHED_EPOCH_MS = "$ID/lastFetchedEpochMs"
        const val ETAG = "$ID/etag"
    }
}

interface CalendarSubscriptionDataIdProvider : CompositeDataIdProvider<CalendarSubscription> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/calendar/subscription/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = CalendarSubscription.Field.SUBSCRIPTION_ID,
        )
    }
}

val Entity<out CalendarSubscription>.subscriptionId: TString
    get() = getFieldValue(CalendarSubscription.Field.SUBSCRIPTION_ID)

val Entity<out CalendarSubscription>.calendarId: TString
    get() = getFieldValue(CalendarSubscription.Field.CALENDAR_ID)

val Entity<out CalendarSubscription>.url: TString
    get() = getFieldValue(CalendarSubscription.Field.URL)

val Entity<out CalendarSubscription>.enabled: TBoolean
    get() = getFieldValue(CalendarSubscription.Field.ENABLED)

val Entity<out CalendarSubscription>.lastFetchedEpochMs: TInteger
    get() = getFieldValue(CalendarSubscription.Field.LAST_FETCHED_EPOCH_MS)

val Entity<out CalendarSubscription>.etag: TString
    get() = getFieldValue(CalendarSubscription.Field.ETAG)

package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * macOS EventKit, in-process over the FFM Objective-C bridge (`calendar-external-sources.md` CX-2c).
 *
 * ⚠ Copies fields and decides nothing: every value comes back plain (strings, epoch millis, raw
 * EventKit enum numbers), and Calendar's common `AppleConversion` folds occurrences into series —
 * the same split as the iOS reader. This module depends on nothing but the stdlib, so it cannot
 * name Calendar's types.
 *
 * ⚠⚠ **TCC aborts a process that touches EventKit without a usage description in its bundle.** An
 * unbundled JVM (`gradle run`) has none, so [isUsable] checks the running bundle first, and every
 * entry point refuses without it. Only the packaged `.app` can read calendars.
 */
object MacEventKit {

    private val ptr = ValueLayout.ADDRESS

    /** `EKEntityTypeEvent`. */
    private const val ENTITY_EVENT = 0L

    /** `EKAuthorizationStatus` — 3 is `Authorized` before macOS 14 and `FullAccess` from it. */
    const val STATUS_NOT_DETERMINED = 0L
    const val STATUS_FULL_ACCESS = 3L

    private val storeClass: MemorySegment by lazy {
        if (ObjC.framework("EventKit") == null) MemorySegment.NULL else ObjC.cls("EKEventStore")
    }

    /**
     * Whether calendars can be read here at all: macOS, FFM, EventKit loaded, and — ⚠⚠ the part
     * that matters — a calendar usage description in the running bundle, without which TCC kills
     * the process on first use.
     */
    val isUsable: Boolean by lazy {
        runCatching {
            ObjC.isAvailable && !ObjC.isNull(storeClass) &&
                (hasInfoKey("NSCalendarsFullAccessUsageDescription") || hasInfoKey("NSCalendarsUsageDescription"))
        }.getOrDefault(false)
    }

    /** The current `EKAuthorizationStatus` for events; never prompts. */
    fun authorizationStatus(): Long {
        if (!isUsable) return -1
        val handle = ObjC.msgSend(FunctionDescriptor.of(ValueLayout.JAVA_LONG, ptr, ptr, ValueLayout.JAVA_LONG))
            ?: return -1
        return runCatching {
            handle.invokeWithArguments(storeClass, ObjC.sel("authorizationStatusForEntityType:"), ENTITY_EVENT) as Long
        }.getOrDefault(-1)
    }

    fun hasFullAccess(): Boolean = authorizationStatus() == STATUS_FULL_ACCESS

    private val pending = AtomicReference<CompletableFuture<Boolean>?>(null)
    private val lock = Any()

    /** ONE reply block for the process (a global block has no per-call state) — see [ObjC.globalBlock]. */
    private val replyBlock: MemorySegment by lazy {
        ObjC.boolErrorBlock { _, granted, _ ->
            // ⚠ Never throw out of an upcall: it takes the JVM down.
            try {
                pending.get()?.complete(granted.toInt() != 0)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Asks for full access to events if not already held, blocking until the user answers.
     *
     * ⚠ `requestFullAccessToEventsWithCompletion:` from macOS 14, `requestAccessToEntityType:` before
     * it. ⚠ Once refused, macOS answers at once without a prompt; the caller routes to System
     * Settings. Call off the UI thread.
     */
    fun requestFullAccess(timeoutSeconds: Long = 300): Boolean {
        if (!isUsable) return false
        if (hasFullAccess()) return true
        return synchronized(lock) {
            runCatching { request(timeoutSeconds) }.getOrDefault(false)
        }
    }

    private fun request(timeoutSeconds: Long): Boolean = ObjC.autoreleasePool {
        val block = replyBlock
        if (ObjC.isNull(block)) return@autoreleasePool false
        val store = ObjC.send(ObjC.send(storeClass, "alloc"), "init")
        if (ObjC.isNull(store)) return@autoreleasePool false
        val future = CompletableFuture<Boolean>()
        pending.set(future)
        try {
            if (ObjC.sendBool(store, "respondsToSelector:", ObjC.sel("requestFullAccessToEventsWithCompletion:"))) {
                ObjC.sendVoid(store, "requestFullAccessToEventsWithCompletion:", block)
            } else {
                val legacy = ObjC.msgSend(FunctionDescriptor.ofVoid(ptr, ptr, ValueLayout.JAVA_LONG, ptr))
                    ?: return@autoreleasePool false
                legacy.invokeWithArguments(store, ObjC.sel("requestAccessToEntityType:completion:"), ENTITY_EVENT, block)
            }
            runCatching { future.get(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false) && hasFullAccess()
        } finally {
            pending.compareAndSet(future, null)
            ObjC.release(store)
        }
    }

    // ── Reading ───────────────────────────────────────────────────────────────────

    /** One `EKCalendar`. [sourceType] is the raw `EKSourceType`. */
    data class Calendar(
        val identifier: String,
        val title: String,
        val sourceTitle: String,
        val sourceType: Long,
        val colorArgb: Int?,
    )

    /** `EKRecurrenceRule`, raw. [daysOfTheWeek] are (`EKWeekday`, Sunday = 1; week number). */
    data class Recurrence(
        val frequency: Long,
        val interval: Long,
        val daysOfTheWeek: List<Pair<Int, Int>>,
        val daysOfTheMonth: List<Int>,
        val monthsOfTheYear: List<Int>,
        val setPositions: List<Int>,
        val daysOfTheYear: List<Int>,
        val weeksOfTheYear: List<Int>,
        /** `EKWeekday` numbering, 0 when unset. */
        val firstDayOfTheWeek: Int,
        val count: Int,
        val untilMs: Long?,
    )

    /** What a series master says about the whole series. */
    data class Master(
        val title: String,
        val notes: String,
        val location: String,
        val allDay: Boolean,
        val timeZoneId: String?,
        val startMs: Long,
        val endMs: Long,
        val recurrence: Recurrence?,
        /** Relative alarms only, seconds; negative is before. */
        val alarmOffsetsSeconds: List<Long>,
    )

    /** One occurrence a predicate returned, plus its series' master when one resolved. */
    data class Occurrence(
        val eventIdentifier: String,
        val externalIdentifier: String?,
        val title: String,
        val notes: String,
        val location: String,
        val startMs: Long,
        val endMs: Long,
        val allDay: Boolean,
        val timeZoneId: String?,
        val occurrenceMs: Long,
        val detached: Boolean,
        val master: Master?,
    )

    /** Every event calendar. Empty without access. */
    fun calendars(): List<Calendar> {
        if (!isUsable || !hasFullAccess()) return emptyList()
        return withStore { store ->
            ObjC.framework("AppKit")
            items(ObjC.sendWithLong(store, "calendarsForEntityType:", ENTITY_EVENT)).map { calendar ->
                val source = ObjC.send(calendar, "source")
                Calendar(
                    identifier = string(calendar, "calendarIdentifier").orEmpty(),
                    title = string(calendar, "title").orEmpty(),
                    sourceTitle = if (ObjC.isNull(source)) "" else string(source, "title").orEmpty(),
                    sourceType = if (ObjC.isNull(source)) -1 else ObjC.sendLong(source, "sourceType"),
                    colorArgb = argbOf(ObjC.send(calendar, "color")),
                )
            }
        }.orEmpty()
    }

    /**
     * The occurrences of calendar [identifier] starting in `[startMs, endMs)`, each with its
     * series' master. ⚠⚠ EventKit returns occurrences, never series, and caps a predicate at four
     * years. Empty when the calendar is gone or access is not held.
     */
    fun occurrences(identifier: String, startMs: Long, endMs: Long): List<Occurrence>? {
        if (!isUsable || !hasFullAccess()) return null
        return withStore { store ->
            val calendar = ObjC.send(store, "calendarWithIdentifier:", ObjC.nsString(identifier))
            if (ObjC.isNull(calendar)) return@withStore null
            val calendars = ObjC.send(ObjC.cls("NSArray"), "arrayWithObject:", calendar)
            val predicate = ObjC.send(
                store, "predicateForEventsWithStartDate:endDate:calendars:",
                date(startMs), date(endMs), calendars,
            )
            val masters = HashMap<String, Master?>()
            items(ObjC.send(store, "eventsMatchingPredicate:", predicate)).map { event ->
                val id = string(event, "eventIdentifier").orEmpty()
                // ⚠ A moved occurrence's own id carries `/RID=`, and looks itself up rule-less:
                // the master is found by the series id (`AppleConversion.seriesIdentifier`).
                val seriesId = id.substringBefore("/RID=")
                val master = masters.getOrPut(seriesId) {
                    val m = ObjC.send(store, "eventWithIdentifier:", ObjC.nsString(seriesId))
                    if (ObjC.isNull(m)) null else masterOf(m)
                }
                Occurrence(
                    eventIdentifier = id,
                    externalIdentifier = string(event, "calendarItemExternalIdentifier"),
                    title = string(event, "title").orEmpty(),
                    notes = string(event, "notes").orEmpty(),
                    location = string(event, "location").orEmpty(),
                    startMs = ms(ObjC.send(event, "startDate")),
                    endMs = ms(ObjC.send(event, "endDate")),
                    allDay = ObjC.sendBool(event, "isAllDay"),
                    timeZoneId = zoneOf(event),
                    occurrenceMs = ObjC.send(event, "occurrenceDate").let { if (ObjC.isNull(it)) ms(ObjC.send(event, "startDate")) else ms(it) },
                    detached = ObjC.sendBool(event, "isDetached"),
                    master = master,
                )
            }
        }
    }

    private fun masterOf(event: MemorySegment): Master {
        val rule = items(ObjC.send(event, "recurrenceRules")).firstOrNull()
        return Master(
            title = string(event, "title").orEmpty(),
            notes = string(event, "notes").orEmpty(),
            location = string(event, "location").orEmpty(),
            allDay = ObjC.sendBool(event, "isAllDay"),
            timeZoneId = zoneOf(event),
            startMs = ms(ObjC.send(event, "startDate")),
            endMs = ms(ObjC.send(event, "endDate")),
            recurrence = rule?.let(::recurrenceOf),
            alarmOffsetsSeconds = items(ObjC.send(event, "alarms"))
                .filter { ObjC.isNull(ObjC.send(it, "absoluteDate")) }
                .map { double(it, "relativeOffset").toLong() },
        )
    }

    private fun recurrenceOf(rule: MemorySegment): Recurrence {
        val end = ObjC.send(rule, "recurrenceEnd")
        val endDate = if (ObjC.isNull(end)) MemorySegment.NULL else ObjC.send(end, "endDate")
        return Recurrence(
            frequency = ObjC.sendLong(rule, "frequency"),
            interval = ObjC.sendLong(rule, "interval"),
            daysOfTheWeek = items(ObjC.send(rule, "daysOfTheWeek")).map { day ->
                ObjC.sendLong(day, "dayOfTheWeek").toInt() to ObjC.sendLong(day, "weekNumber").toInt()
            },
            daysOfTheMonth = numbers(ObjC.send(rule, "daysOfTheMonth")),
            monthsOfTheYear = numbers(ObjC.send(rule, "monthsOfTheYear")),
            setPositions = numbers(ObjC.send(rule, "setPositions")),
            daysOfTheYear = numbers(ObjC.send(rule, "daysOfTheYear")),
            weeksOfTheYear = numbers(ObjC.send(rule, "weeksOfTheYear")),
            firstDayOfTheWeek = ObjC.sendLong(rule, "firstDayOfTheWeek").toInt(),
            count = if (ObjC.isNull(end)) 0 else ObjC.sendLong(end, "occurrenceCount").toInt(),
            untilMs = if (ObjC.isNull(endDate)) null else ms(endDate),
        )
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────────

    /** A fresh store per read: one made before the grant can go on listing nothing. */
    private fun <T> withStore(block: (MemorySegment) -> T): T? = runCatching {
        ObjC.autoreleasePool {
            val store = ObjC.send(ObjC.send(storeClass, "alloc"), "init")
            if (ObjC.isNull(store)) return@autoreleasePool null
            try {
                block(store)
            } finally {
                ObjC.release(store)
            }
        }
    }.getOrNull()

    private fun hasInfoKey(key: String): Boolean {
        val bundle = ObjC.send(ObjC.cls("NSBundle"), "mainBundle")
        if (ObjC.isNull(bundle)) return false
        return !ObjC.isNull(ObjC.send(bundle, "objectForInfoDictionaryKey:", ObjC.nsString(key)))
    }

    private fun items(array: MemorySegment): List<MemorySegment> {
        if (ObjC.isNull(array)) return emptyList()
        val count = ObjC.sendLong(array, "count")
        return (0 until count).map { ObjC.sendWithLong(array, "objectAtIndex:", it) }
    }

    private fun numbers(array: MemorySegment): List<Int> = items(array).map { ObjC.sendLong(it, "longValue").toInt() }

    private fun string(obj: MemorySegment, selector: String): String? {
        val value = ObjC.send(obj, selector)
        return if (ObjC.isNull(value)) null else ObjC.kotlinString(value)
    }

    private fun zoneOf(event: MemorySegment): String? {
        val zone = ObjC.send(event, "timeZone")
        return if (ObjC.isNull(zone)) null else string(zone, "name")
    }

    private val doubleGetter by lazy { ObjC.msgSend(FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ptr, ptr)) }

    private fun double(obj: MemorySegment, selector: String): Double =
        runCatching { doubleGetter?.invokeWithArguments(obj, ObjC.sel(selector)) as Double? }.getOrNull() ?: 0.0

    private fun ms(date: MemorySegment): Long =
        if (ObjC.isNull(date)) 0L else (double(date, "timeIntervalSince1970") * 1000).toLong()

    private val dateFactory by lazy { ObjC.msgSend(FunctionDescriptor.of(ptr, ptr, ptr, ValueLayout.JAVA_DOUBLE)) }

    private fun date(ms: Long): MemorySegment =
        dateFactory?.invokeWithArguments(ObjC.cls("NSDate"), ObjC.sel("dateWithTimeIntervalSince1970:"), ms / 1000.0)
            as MemorySegment? ?: MemorySegment.NULL

    /** `NSColor` → sRGB ARGB; null when it has no RGB representation. */
    private fun argbOf(color: MemorySegment): Int? {
        if (ObjC.isNull(color)) return null
        val srgb = ObjC.send(color, "colorUsingColorSpace:", ObjC.send(ObjC.cls("NSColorSpace"), "sRGBColorSpace"))
        if (ObjC.isNull(srgb)) return null
        fun channel(selector: String) = (double(srgb, selector) * 255.0).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel("redComponent") shl 16) or (channel("greenComponent") shl 8) or channel("blueComponent")
    }
}

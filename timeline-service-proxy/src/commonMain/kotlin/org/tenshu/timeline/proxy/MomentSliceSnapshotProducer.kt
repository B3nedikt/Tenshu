package org.tenshu.timeline.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.EncodedSlice
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SliceSnapshotProducer
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.readOrderedWindow
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.time.clock
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService
import org.tenshu.timeline.common.slice.MomentSliceEntry

/**
 * Produces `MOMENTS` slice entries — Timeline's half of the joiner snapshot
 * (`sync-device-catch-up.md` §5.7.3, C9), and the **first producer over a realm-wide singleton
 * relation**.
 *
 * ## ⚠⚠ Why this is harder than `Folder.FILES`, in one sentence
 *
 * A folder's children are bounded by the user's own tree, so C8 got its partition for free; a
 * five-year feed is one unbounded collection with no sub-key inside it, so the partition has to be
 * *invented* — and `Sliceable(SliceKey.Day)` is Timeline saying which invention it wants.
 *
 * ## What is bounded, and by what
 *
 * Two independent bounds, because a chronological feed has no natural end:
 *
 *  - **[RECENT_WINDOW] rows** is what any single call reads (`readOrderedWindow`, newest first,
 *    thin projection, plus one row to tell a full window from a store of exactly that size). The store is not enumerable by day, so the window *is* the enumeration.
 *  - **[RECENT_DAYS] buckets** is what a pass will seal. A joiner's first screen is today and the
 *    days just behind it; sealing 2019 buys nothing a log replay does not already give, and it
 *    would make the pass grow without limit as a realm ages.
 *
 * ⚠ Both are deliberately small. The pass is on the idle+charging path, is never
 * correctness-bearing, and a bucket it skips simply costs a joiner an ordinary relation read.
 *
 * ⚠ Cost noted rather than optimised: `sliceKeys` and each `encodeSlice` re-read the same window,
 * so a pass is O(days × window). At the current constants that is bounded and cheap; if either
 * grows, the seam wants a "produce all slices in one call" shape rather than a cache here — a
 * producer that memoises across calls would be lying about being stateless.
 */
class MomentSliceSnapshotProducer(
    /** Rows any single call reads. Injectable so a test can exercise the truncation branch. */
    private val recentWindow: Int = RECENT_WINDOW,
    /** Day buckets a pass will seal. */
    private val recentDays: Int = RECENT_DAYS,
) : SliceSnapshotProducer {

    override val relationIds: Set<String> = setOf(TimelineService.Relations.MOMENTS)

    /**
     * The recent day buckets this device actually holds moments for, newest first.
     *
     * ⚠ Derived from the window rather than from a date range: a device with a three-day gap
     * should seal three buckets, not seven, and an empty bucket is not worth an address.
     */
    override fun sliceKeys(executionContext: ExecutionContext, relationId: String): List<String> {
        if (relationId !in relationIds) return emptyList()
        return recentMoments(executionContext).rows
            .mapNotNull { row -> timestampMillis(row)?.let { MomentSliceEntry.dayKey(it) } }
            .distinct()
            .take(recentDays)
    }

    /**
     * **Today and yesterday**, in UTC — the seed a device can name before it holds any state.
     *
     * ⚠⚠ Two buckets rather than one, and that is a direct consequence of keying in UTC while the
     * feed renders in local time: for most of the world "today" straddles two UTC days, so seeding
     * only the current one would leave a joiner's first screen half empty at exactly the hours
     * people look at it.
     */
    override fun bootstrapSliceKeys(
        executionContext: ExecutionContext,
        relationId: String,
    ): List<String> {
        if (relationId !in relationIds) return emptyList()
        val now = executionContext.context.clock().nowEpochMillis()
        return listOf(
            MomentSliceEntry.dayKey(now),
            MomentSliceEntry.dayKey(now - MILLIS_PER_DAY),
        ).distinct()
    }

    /**
     * The moments in one UTC day.
     *
     * ⚠ An empty day yields an **empty** slice, not `null` — the same distinction C8 relies on:
     * empty renders "nothing that day", `null` means "I cannot produce this" and sends the reader
     * back to an ordinary relation read.
     *
     * ⚠⚠ **Truncation is the only reason to answer `null`, and getting that rule right took a
     * failing test.** The first attempt refused any day older than the oldest row it had seen —
     * which also refuses every genuinely quiet day, the normal case for a feed. What actually
     * matters is whether the window *reached the end of the store*: an untruncated window has
     * seen everything this device holds, so a day it does not contain really is empty. Only a
     * **full** window leaves older days unknowable, and only then is `null` the honest answer —
     * for the window's oldest day as well, since the rows past the window may belong to it.
     *
     * ⚠ "Full" means the store holds **more** than the window, which is why one row past it is
     * read: a store of exactly [RECENT_WINDOW] moments is wholly seen, oldest day included.
     *
     * ⚠ So a full window seals a day only when an older day is in it too, which bounds every
     * `MOMENTS` slice at [RECENT_WINDOW] entries at most; the writer's byte budget
     * (`RelationSliceSnapshot.MAX_ENCODED_BYTES`) is the bound on their size.
     *
     * ⚠ "This device holds nothing that day" is a safe proxy for "the realm holds nothing that
     * day" **because the writer refuses to publish at all until catch-up is complete** — the
     * same gate that makes `asOfSeq` a promise rather than a guess.
     */
    override fun encodeSlice(
        executionContext: ExecutionContext,
        relationId: String,
        sliceKey: String,
    ): EncodedSlice? {
        if (relationId !in relationIds) return null
        val read = recentMoments(executionContext)
        val window = read.rows
        val truncated = read.truncated
        val oldestSeen = window.mapNotNull { timestampMillis(it) }.minOrNull()
        // ⚠⚠ `<=`, not `<`: the oldest day a full window reaches is cut too — its earlier moments
        // are the rows just past the window — so it is no more complete than the days before it
        // (§11.4.4). Sealing it would hand a joiner part of a day as the whole day.
        if (truncated && oldestSeen != null && sliceKey <= MomentSliceEntry.dayKey(oldestSeen)) {
            return null
        }

        val entries = window.mapNotNull { row ->
            val millis = timestampMillis(row) ?: return@mapNotNull null
            if (MomentSliceEntry.dayKey(millis) != sliceKey) return@mapNotNull null
            val subject = subjectOf(row)
            MomentSliceEntry(
                momentId = string(row, Moment.Fields.MOMENT_ID),
                timestampMillis = millis,
                kind = string(row, Moment.Fields.KIND),
                title = string(row, Moment.Fields.TITLE),
                subjectKind = subject?.kind?.get()?.ifEmpty { SubjectKind.NONE } ?: SubjectKind.NONE,
                subjectId = subject?.id?.get().orEmpty(),
                durationSec = int(row, Moment.Fields.DURATION_SEC),
                supersededBy = string(row, Moment.Fields.SUPERSEDED_BY),
            )
        }
        return EncodedSlice(entryCount = entries.size, entries = MomentSliceEntry.encode(entries))
    }

    /**
     * Materialise a fetched day into the local store.
     *
     * ⚠⚠ **Create-only**, exactly as `FileSliceSnapshotProducer.applySlice` — an entry is a
     * projection, and this one deliberately omits `sources`, so writing it over a real row would
     * discard a mergeable set that `MomentMerger` maintains. It writes no `SyncMetadata` either,
     * so the first real block wins as an update to the same moment id.
     *
     * ⚠ The relation membership is not written here: saving a `Moment` fires
     * `MomentRelationMaintainer`, which is what adds it to `MOMENTS` — the same reliance
     * `RecordMoment` already has.
     */
    override fun applySlice(
        executionContext: ExecutionContext,
        relationId: String,
        sliceKey: String,
        entries: ByteArray,
        isDeleted: (DataId) -> Boolean,
    ): Int {
        if (relationId !in relationIds) return 0
        val decoded = MomentSliceEntry.decode(entries) ?: return 0

        var created = 0
        decoded.forEach { entry ->
            if (entry.momentId.isEmpty()) return@forEach
            val id = DataId.StringDataId.create(
                context = executionContext.context,
                type = Moment.ID,
                keyValue = entry.momentId,
            )
            if (executionContext.database.getData(id) != null) return@forEach
            // ⚠⚠ Deleted since the slice was sealed: a tombstone already ingested will not come
            // again, so a moment recreated here would stay for good (§11.4.4).
            if (isDeleted(id)) return@forEach
            executionContext.saveData(
                Moment.create(
                    context = executionContext.context,
                    momentId = entry.momentId,
                    timestamp = executionContext.context.dataFactory()
                        .createValue(UnixInstant.ID, entry.timestampMillis),
                    kind = entry.kind,
                    title = entry.title,
                    subject = SubjectRef.create(
                        context = executionContext.context,
                        kind = entry.subjectKind.ifEmpty { SubjectKind.NONE },
                        id = entry.subjectId,
                    ),
                    durationSec = entry.durationSec,
                    // ⚠ Empty by construction — see the class KDoc. A provisional row must not
                    // claim provenance it did not observe.
                    sources = emptyList(),
                    supersededBy = entry.supersededBy,
                ),
                origin = WriteOrigin.SYNC_INGEST,
            )
            created++
        }
        return created
    }

    /** The newest [rows], and whether the store holds any moment older than them. */
    private class Window(val rows: List<Data>, val truncated: Boolean)

    /**
     * The newest [RECENT_WINDOW] moments, thinly projected — read with one row more, so that a store
     * holding exactly the window is not mistaken for one cut by it.
     *
     * ⚠ Returns empty rather than throwing when the timeline service is not registered in this
     * proxy — `relationReference` fails loudly on an undeclared relation, and a wiring without
     * Timeline is legitimate (the same guard `MomentReconciliationPass.readPlaceCoords` uses).
     */
    private fun recentMoments(executionContext: ExecutionContext): Window = runCatching {
        executionContext.readOrderedWindow(
            reference = executionContext.relationReference(TimelineService.Relations.MOMENTS),
            orderByFieldId = Moment.Fields.TIMESTAMP,
            limit = recentWindow + 1,
            descending = true,
            keepFields = setOf(
                Moment.Fields.MOMENT_ID,
                Moment.Fields.KIND,
                Moment.Fields.TITLE,
                Moment.Fields.SUBJECT,
                Moment.Fields.DURATION_SEC,
                Moment.Fields.SUPERSEDED_BY,
            ),
        )
    }.getOrElse { emptyList() }.let { rows ->
        Window(rows = rows.take(recentWindow), truncated = rows.size > recentWindow)
    }

    /**
     * ⚠ A projected row arrives as an [Entity] or a [Composite] depending on how it was
     * materialised, so every field read has to branch — the same three-arm shape
     * `MomentReconciliationPass` uses over this identical window.
     */
    private fun timestampMillis(row: Data): Long? = when (row) {
        is Entity<*> -> row.getFieldValue<UnixInstant>(Moment.Fields.TIMESTAMP).get()
        is Composite -> row.getField<UnixInstant>(Moment.Fields.TIMESTAMP).get()
        else -> null
    }

    private fun subjectOf(row: Data): SubjectRef? = when (row) {
        is Entity<*> -> row.getFieldValue(Moment.Fields.SUBJECT)
        is Composite -> row.getField(Moment.Fields.SUBJECT)
        else -> null
    }

    private fun int(row: Data, fieldId: String): Long = when (row) {
        is Entity<*> -> row.getFieldValue<TInteger>(fieldId).get()
        is Composite -> row.getField<TInteger>(fieldId).get()
        else -> 0L
    }

    private fun string(row: Data, fieldId: String): String = when (row) {
        is Entity<*> -> row.getFieldValue<TString>(fieldId).get()
        is Composite -> row.getField<TString>(fieldId).get()
        else -> ""
    }

    companion object {
        /** How many recent moments a single call reads. The window IS the enumeration. */
        const val RECENT_WINDOW = 2_000

        /** How many day buckets a pass will seal, newest first. */
        const val RECENT_DAYS = 7

        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

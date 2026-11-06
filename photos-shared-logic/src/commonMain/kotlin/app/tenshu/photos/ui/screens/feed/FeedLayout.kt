package app.tenshu.photos.ui.screens.feed

import app.tenshu.photos.model.FeedItem
import app.tenshu.photos.model.Photo
import kotlin.math.roundToInt

/**
 * One line of the feed grid, addressed by the paged indices it draws (`photos-app.md` §32).
 *
 * ⚠ Indices into the presenter's list — headers and placeholders included — never positions among
 * photographs, so a line can be drawn with nothing but `LazyPagingItems[index]`.
 */
sealed interface FeedLine {
    /** The paged index of the line's first item, which is also its top-left cell. */
    val start: Int

    /** How many consecutive paged indices the line draws, starting at [start]. */
    val size: Int

    /** A section header, alone on its line. */
    data class Header(override val start: Int) : FeedLine {
        override val size: Int get() = 1
    }

    /**
     * Up to `columns` photographs side by side. ⚠ Narrower only where a section, or the whole
     * list, ends — the rest of the line stays empty. ⚠ May draw placeholders: the loaded window's
     * last row runs on into the unloaded indices after it rather than stopping short.
     */
    data class Row(override val start: Int, override val size: Int) : FeedLine

    /**
     * Photographs over two lines' height, in [blocks] blocks of three columns side by side: in each,
     * one drawn across 2×2 cells and the other two stacked in the remaining column. [heroFirst] puts
     * the first block's hero on the left and makes it [start]; otherwise it is `start + 1` on the
     * right, and `start` and `start + 2` stack on the left. The blocks after it alternate.
     *
     * ⚠⚠ Either way the reading order — top-left, top-right, then down — is the paged order, so the
     * newest photograph stays top-left and the video rotation's document order is untouched.
     *
     * ⚠ More than one block only on a grid wider than a phone's (`gridWidthScale`, §33): a feature
     * must take exactly a row's photographs, and a block is the only shape that does.
     */
    data class Feature(override val start: Int, val heroFirst: Boolean, val blocks: Int = 1) : FeedLine {
        override val size: Int get() = BLOCK * blocks

        /** Whether block [block]'s hero is on its left — alternating across the line from [heroFirst]. */
        fun heroLeftIn(block: Int): Boolean = heroFirst == (block % 2 == 0)

        /** Whether the photograph at [index] is one of this line's heroes. */
        fun isHero(index: Int): Boolean {
            val offset = index - start
            return offset % BLOCK == if (heroLeftIn(offset / BLOCK)) 0 else 1
        }

        /**
         * Which of the feature's two rows the photograph at [index] sits in — `0` or `1` — or
         * `null` for a hero, which spans both.
         *
         * ⚠ For anything that asks what is ON SCREEN: a feature is two rows tall, so the line
         * intersecting the viewport does not mean every photograph in it does.
         */
        fun rowOf(index: Int): Int? = when {
            isHero(index) -> null
            (index - start) % BLOCK == 2 -> 1
            else -> 0
        }

        companion object {
            /** The columns one block spans, and the photographs it draws: a 2×2 hero beside two. */
            const val BLOCK: Int = 3
        }
    }
}

/**
 * The feed's paged items cut into [FeedLine]s — what makes a 2×2 photograph drawable at all.
 *
 * ⚠⚠ **Why lines, rather than spans in a grid of photographs.** `LazyVerticalGrid` spans columns
 * and never rows, and the staggered grid spans one lane or all of them; neither can draw a
 * photograph two cells tall beside two that are one. So the grid's items are these lines, and each
 * line places its own cells.
 *
 * ⚠⚠ **Nothing may move when a page lands.** The list is paged with placeholders, so most of it is
 * unknown at any moment and pages land in any order — above the viewport as well as below. Two rules
 * keep the lines still:
 *
 * - **A [FeedLine.Feature] takes the same three items a full [FeedLine.Row] would**, so a row that
 *   becomes a feature when its last photograph loads moves no other line.
 * - **Whether a line is a feature depends only on its own photographs and their position in the
 *   whole library** — never on where the loaded window happens to start. `placeholdersBefore` is
 *   the source's count of unloaded PHOTOGRAPHS (the separator pass adds headers between loaded rows
 *   only), so the first loaded photograph's position is known exactly, and a page landing above
 *   changes it for nobody. ⚠⚠ Counting lines from the window's first item instead, a scrubber
 *   jump into a long day followed by the 60-photo page above it moves every loaded line 20 slots
 *   through the rhythm, and features on screen turn into rows.
 *
 * ⚠ So the rhythm runs through the library, not per day: a day does not necessarily open on a
 * feature. The reference's day view does not either.
 *
 * ⚠ The loaded window always starts a fresh line, and the unloaded run before it is cut into rows
 * from its END. A section whose header is still unloaded above the window is therefore cut from the
 * window's edge, and its lines re-phase when the header lands — which the flat grid this replaced
 * did too, since a header arriving there shifted every photograph after it by its own width. ⚠ A
 * page of the same day landing above re-phases them only when the page size is not a multiple of
 * the column count; the feed's page is 60, so the three-column mosaic never re-phases that way.
 *
 * ⚠ The heroes are chosen by POSITION, not by quality. The reference app picks its best photographs
 * with a model; the feed row carries a path, a time and a shape, and nothing here pretends otherwise.
 */
class FeedLayout private constructor(
    val columns: Int,
    /**
     * The grouping this layout was cut at. ⚠ Carried HERE, not looked up: a shell can hold a layout
     * while any number of newer ones are emitted, and its labels must be formatted for its own
     * density (a bounded cache of recent layouts would give a Day row a Month label).
     */
    val density: FeedDensity,
    private val placeholdersBefore: Int,
    private val items: List<FeedItem>,
    private val loadedLines: List<FeedLine>,
    /**
     * For each loaded line, the library position of its first photograph — for a header, of the
     * photograph after it. Ascending, which is what [lineOfPhoto] searches.
     */
    private val loadedLinePhotos: IntArray,
    /** How many photographs the loaded window holds, headers not counted. */
    private val loadedPhotos: Int,
    /** The first unloaded index after the window that no loaded line has already drawn. */
    private val trailingStart: Int,
    private val totalItems: Int,
) {
    private val leadingLines = ceilDiv(placeholdersBefore, columns)
    private val trailingLines = ceilDiv(totalItems - trailingStart, columns)

    val lineCount: Int = leadingLines + loadedLines.size + trailingLines

    /**
     * How many lines of placeholders come before the loaded window's, and how many lines that
     * window is — the rest of [lineCount] are placeholders after it. ⚠ What lets a shell measure
     * the loaded lines alone: an unloaded line is always a plain row, so each run is its count.
     */
    val leadingLineCount: Int get() = leadingLines
    val loadedLineCount: Int get() = loadedLines.size

    operator fun get(line: Int): FeedLine = when {
        line < leadingLines -> {
            // Cut from the END, so the loaded window below starts a fresh line.
            val first = placeholdersBefore - (leadingLines - 1) * columns
            if (line == 0) FeedLine.Row(0, first)
            else FeedLine.Row(first + (line - 1) * columns, columns)
        }

        line < leadingLines + loadedLines.size -> loadedLines[line - leadingLines]

        else -> {
            val start = trailingStart + (line - leadingLines - loadedLines.size) * columns
            FeedLine.Row(start, minOf(columns, totalItems - start))
        }
    }

    /** The item at a paged index as this layout saw it — `null` for a placeholder. */
    fun itemAt(index: Int): FeedItem? = items.getOrNull(index - placeholdersBefore)

    /**
     * The list positions of every loaded photograph in THIS layout, in feed order. ⚠ From the same
     * snapshot [itemAt] reads — a caller pairing positions with items must never take them from two
     * sources that update at different moments.
     */
    fun loadedPhotoPositions(): List<Int> =
        items.indices.filter { items[it] is FeedItem.Tile }.map { it + placeholdersBefore }

    /**
     * The library position ([photoAt]'s coordinate) of the photograph at [path] in THIS layout, or
     * `null` when it is not loaded here.
     *
     * ⚠⚠ The PATH is a photograph's identity — the platform's own rule
     * (`file-content-addressing.md` §3.3.1: a file is keyed by its path; a move is a new identity,
     * migrated by `DataIdMigrator`; there is no stable `fileId`). Not `contentId`: it names the
     * BYTES, a copy shares it and an edit changes it, so copies are not "unique among loaded rows".
     * Not the ordinal either: photographs arriving or leaving ahead shift it, so anything kept
     * across layouts keeps the path and resolves it here.
     */
    fun photoOrdinalOf(path: String): Int? = ordinalWhere { it.path == path }

    /**
     * Whether this layout holds the WHOLE library — no placeholders on either side. ⚠⚠ Only then may
     * anything be concluded from what is ABSENT: a photograph "not in the loaded
     * window" says nothing about the unloaded rest — it may simply be pages further down.
     */
    val isComplete: Boolean get() = placeholdersBefore == 0 && items.size == totalItems

    /** The loaded photograph at library position [ordinal], or `null` for a placeholder — its identity. */
    fun photoOfOrdinal(ordinal: Int): Photo? {
        var at = placeholdersBefore
        for (item in items) {
            if (item is FeedItem.Tile) {
                if (at == ordinal) return item.photo
                at++
            }
        }
        return null
    }

    private inline fun ordinalWhere(matches: (Photo) -> Boolean): Int? {
        var ordinal = placeholdersBefore
        for (item in items) {
            if (item is FeedItem.Tile) {
                if (matches(item.photo)) return ordinal
                ordinal++
            }
        }
        return null
    }

    // ── library positions — what survives a density change ───────────────────────────────────

    /**
     * The library position of [line]'s first photograph (for a header, of the one after it), or
     * `null` past the end.
     *
     * ⚠⚠ The only coordinate two densities share. A line index names a different photograph at
     * three, five and seven columns, and a paged index a different one again once the headers
     * change; the position of a photograph in the library is the same at all of them. See the class
     * KDoc for why placeholders make it exact.
     */
    fun photoAt(line: Int): Int? {
        if (line !in 0 until lineCount) return null
        val loaded = line - leadingLines
        return when {
            line < leadingLines -> this[line].start
            loaded < loadedLines.size -> loadedLinePhotos[loaded]
            else -> placeholdersBefore + loadedPhotos + (this[line].start - placeholdersBefore - items.size)
        }
    }

    /**
     * The library position of the first photograph of [line] **still on screen**, given how much of
     * the line has already scrolled past the top ([scrolledPast], 0 to 1).
     *
     * ⚠⚠ A feature is two rows tall, so past its half its upper small photograph is gone and the
     * lower one leads what is visible. Anchoring a density change on the line's first photograph
     * there scrolls backwards by half a feature. ⚠ The hero spans both rows and is
     * visible either way; the two small ones are what the position has to follow.
     */
    fun photoAt(line: Int, scrolledPast: Float): Int? {
        val first = photoAt(line) ?: return null
        if (this[line] !is FeedLine.Feature) return first
        return if (scrolledPast > 0.5f) first + 2 else first
    }

    /**
     * The line that draws the photograph at library position [photo], clamped to the list.
     *
     * ⚠ With [header], the section header directly above that line when the photograph opens its
     * section: a header and the row under it share one position, and a re-anchor that began on the
     * header must land on it rather than scroll it away (the feed as it opens).
     */
    fun lineOfPhoto(photo: Int, header: Boolean = false): Int {
        if (lineCount == 0) return 0
        val line = when {
            photo < placeholdersBefore -> {
                val first = placeholdersBefore - (leadingLines - 1) * columns
                if (photo < first) 0 else 1 + (photo - first) / columns
            }

            photo < placeholdersBefore + loadedPhotos -> {
                // The LAST line starting at or before it, so a header resolves to the row below.
                var low = 0
                var high = loadedLinePhotos.size - 1
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    if (loadedLinePhotos[mid] <= photo) low = mid else high = mid - 1
                }
                leadingLines + low
            }

            else -> {
                val index = placeholdersBefore + items.size + (photo - placeholdersBefore - loadedPhotos)
                if (index < trailingStart) leadingLines + loadedLines.size - 1
                else leadingLines + loadedLines.size + (index - trailingStart) / columns
            }
        }
        val clamped = line.coerceIn(0, lineCount - 1)
        val above = clamped - 1
        return if (header && above >= 0 && this[above] is FeedLine.Header && photoAt(above) == photo) above
        else clamped
    }

    /** How many photographs the library holds — loaded or not, headers never counted. */
    val photoCount: Int = totalItems - items.size + loadedPhotos

    /**
     * How far through the library [line] is, as a fraction of its photographs — plus [withinLine],
     * the part of the line already scrolled past — for the scrubber's thumb.
     *
     * ⚠⚠ PHOTOGRAPHS, not lines, because the year pills are placed by photographs
     * (`trackFractions`). A day header is a whole line and three photographs are one, so a track in
     * lines puts a year dozens of lines from its pill wherever photographs-per-day changes between
     * years. ⚠ A header holds none, so the thumb rests while one scrolls past.
     */
    fun progressAt(line: Int, withinLine: Float): Float {
        if (photoCount <= 1) return 0f
        val first = photoAt(line) ?: return 1f
        val photos = when (val it = this[line]) {
            is FeedLine.Header -> 0
            else -> it.size
        }
        return ((first + withinLine * photos) / (photoCount - 1)).coerceIn(0f, 1f)
    }

    /** The line a seek to [progress] of the track lands on — the inverse of [progressAt]. */
    fun lineAtProgress(progress: Float): Int =
        lineOfPhoto((progress.coerceIn(0f, 1f) * (photoCount - 1)).roundToInt())

    /** Whether the photograph at library position [photo] is in the loaded window. */
    fun isLoaded(photo: Int): Boolean = photo - placeholdersBefore in 0 until loadedPhotos

    /**
     * A key for [line] that survives the line's neighbours changing.
     *
     * ⚠⚠ The key of the line's FIRST item, never the line's position: a page landing above the
     * viewport renumbers every line below it, and a positional key would lose the user's place on
     * each one. ⚠ A `String` in every case — Android keeps lazy keys in a `Bundle`.
     */
    fun key(line: Int): String {
        val first = this[line].start
        return when (val item = itemAt(first)) {
            is FeedItem.DayHeader -> "day-" + item.dayStartMs
            is FeedItem.Tile -> item.photo.path
            null -> "placeholder-$first"
        }
    }

    // ── line identity — what lets a shell apply the DIFFERENCE between two layouts ─────────────

    /**
     * What a shell drawing [old] must do to draw THIS layout instead — the lines to delete, insert
     * and redraw — so it applies a difference rather than redrawing every row (`photos-ios.md` §8.20).
     *
     * A line is the same line in both layouts when its identity is ([lineIds]): a header is its
     * section, a line of photographs the PATH of its first one ([key]'s rule), and a placeholder
     * line its distance from the library's nearer end. A kept line is [FeedLineChanges.changed] when
     * what it draws differs ([lineStamps]).
     *
     * ⚠⚠ **The work is the two loaded windows', never the library's** — the class's own rule
     * ("linear in the loaded window only"). Placeholder lines are interchangeable and counted from
     * the ends, so a run that grew or shrank is a RANGE beside the window, worked out by
     * arithmetic; nothing here walks, names or allocates per unloaded line: an identity per line
     * costs a 50 000-photograph library ~110 ms a draw on the simulator for a 60-photograph
     * window.
     *
     * ⚠ [FeedLineChanges.reload] instead of a difference when there is none worth applying: another
     * grouping or column count, nothing drawn before, kept lines in another order, or more than
     * [MAX_LINE_CHANGES] lines coming and going (a jump down a long library).
     */
    fun changesFrom(old: FeedLayout): FeedLineChanges {
        if (old.lineCount == 0 || lineCount == 0 || old.density != density || old.columns != columns) {
            return FeedLineChanges.RELOAD
        }
        val lead = leadingLines.toLong() - old.leadingLines
        val tail = trailingLines.toLong() - old.trailingLines
        if (kotlin.math.abs(lead) + kotlin.math.abs(tail) > MAX_LINE_CHANGES) return FeedLineChanges.RELOAD

        val oldIds = old.loadedLineIds()
        val newIds = loadedLineIds()
        val oldAt = HashMap<String, Int>(oldIds.size * 2)
        oldIds.forEachIndexed { at, id -> oldAt[id] = at }
        val kept = BooleanArray(oldIds.size)
        val deleted = ArrayList<Int>()
        val inserted = ArrayList<Int>()
        val changed = ArrayList<Int>()

        // The top placeholder line is kept whenever both layouts have one; it is the short one.
        if (leadingLines > 0 && old.leadingLines > 0 && this[0].size != old[0].size) changed += 0
        if (lead > 0) for (line in old.leadingLines until leadingLines) inserted += line

        var last = -1
        for (at in newIds.indices) {
            val was = oldAt[newIds[at]]
            if (was == null) {
                inserted += leadingLines + at
                continue
            }
            // ⚠ A batch of deletes and inserts cannot express a move; kept lines out of order
            // (a capture time edited) are rare enough to redraw.
            if (was < last) return FeedLineChanges.RELOAD
            last = was
            kept[was] = true
            if (old.loadedStamp(was) != loadedStamp(at)) changed += leadingLines + at
        }

        if (lead < 0) for (line in leadingLines until old.leadingLines) deleted += line
        for (was in kept.indices) if (!kept[was]) deleted += old.leadingLines + was
        val oldTailStart = old.leadingLines + old.loadedLines.size
        val tailStart = leadingLines + loadedLines.size
        if (tail < 0) for (line in oldTailStart until oldTailStart - tail.toInt()) deleted += line
        if (tail > 0) for (line in tailStart until tailStart + tail.toInt()) inserted += line
        // And the bottom one, the trailing run's short line.
        if (trailingLines > 0 && old.trailingLines > 0 && this[lineCount - 1].size != old[old.lineCount - 1].size) {
            changed += lineCount - 1
        }

        if (deleted.size + inserted.size > MAX_LINE_CHANGES) return FeedLineChanges.RELOAD
        return FeedLineChanges(reload = false, deleted = deleted, inserted = inserted, changed = changed)
    }

    /**
     * Every line's identity, in line order — distinct within this layout.
     *
     * ⚠⚠ **The SPECIFICATION of [changesFrom], for tests — never a per-draw call**: it names every
     * line, unloaded ones included, so it costs the library.
     *
     * - ⚠ A line's shape is deliberately NOT part of its identity: a row that becomes a feature
     *   when its last photograph loads is the same line with new content ([lineStamps]).
     * - ⚠⚠ A placeholder line is counted from the library's nearer END — `lead-<n>` down from the
     *   top, `tail-<n>` up from the bottom — never by the index it draws. Unloaded lines are
     *   interchangeable, so the only thing a numbering can do is thrash: by index, one photograph
     *   arriving at the head renames every placeholder line in the library, and a page that shifts
     *   the cut's phase does too. Counted from the ends, a landing page removes the lines beside
     *   the window and renames none.
     */
    fun lineIds(): List<String> =
        List(leadingLines) { "lead-$it" } + loadedLineIds() + List(trailingLines) { "tail-${trailingLines - 1 - it}" }

    /**
     * The loaded lines' identities. ⚠ Distinct even over a list that repeats a path (a row served
     * twice across a page seam): the later one is suffixed, because two lines sharing a name would
     * be one line to [changesFrom], and a shell's batch update traps on a miscount.
     */
    private fun loadedLineIds(): List<String> {
        val seen = HashSet<String>(loadedLines.size * 2)
        return List(loadedLines.size) { at ->
            val id = key(leadingLines + at)
            if (seen.add(id)) id
            else {
                var n = 1
                while (!seen.add("$id\u0001$n")) n++
                "$id\u0001$n"
            }
        }
    }

    /**
     * What each line DRAWS, in line order — equal for two lines exactly when a row drawn for one
     * is right for the other. ⚠ Like [lineIds], the specification and not a per-draw call.
     *
     * ⚠ The shape and every photograph in the line, loaded or not: a placeholder filling in, a row
     * turning into a feature and a hero changing sides are all content. ⚠ A header's is its
     * grouping, since its label is formatted for it; a placeholder line's is how many slots it
     * has. ⚠ Not the tile's edge — that is the shell's own measurement, and the shell's to compare.
     */
    fun lineStamps(): List<String> = List(lineCount) { line ->
        val loaded = line - leadingLines
        if (loaded in loadedLines.indices) loadedStamp(loaded) else "p" + this[line].size
    }

    private fun loadedStamp(at: Int): String {
        val line = loadedLines[at]
        val shape = when (line) {
            is FeedLine.Header -> return "h" + density.name
            is FeedLine.Feature -> if (line.heroFirst) "f" else "g"
            is FeedLine.Row -> "r"
        }
        return buildString {
            append(shape)
            for (index in line.start until line.start + line.size) {
                val photo = (itemAt(index) as? FeedItem.Tile)?.photo
                append('|')
                // ⚠ The whole row, not the path: a photograph replaced in place keeps its path.
                if (photo != null) append(photo.path).append('#').append(photo.hashCode())
            }
        }
    }

    /**
     * The paged index of the photograph at [path] in THIS layout, or `null` when it is not loaded
     * here — [photoOrdinalOf]'s rule, in the coordinate a viewer opens on.
     */
    fun positionOf(path: String): Int? {
        val at = items.indexOfFirst { it is FeedItem.Tile && it.photo.path == path }
        return if (at < 0) null else at + placeholdersBefore
    }

    companion object {
        /**
         * Of every [FEATURE_CYCLE] lines, the first and the [SECOND_FEATURE]th are features — two
         * plain rows, then three, so the rhythm does not read as a stencil. The first puts its hero
         * on the left and the second on the right, so consecutive heroes alternate.
         */
        private const val FEATURE_CYCLE = 7

        /**
         * More lines than this coming and going and a shell reloads instead: a batch update that
         * size costs more than the rows it would spare. ⚠ Also what bounds [changesFrom]'s own work.
         */
        const val MAX_LINE_CHANGES: Int = 400
        private const val SECOND_FEATURE = 3

        /**
         * Cut a snapshot of the paged list — [placeholdersBefore], the loaded [items], then
         * [placeholdersAfter] — into lines at [density], [columns] wide.
         *
         * ⚠ [columns] is the density's own count times the grid's width scale (`columnsAt`). A
         * feature is cut only where it is a whole number of blocks; see [FeedLine.Feature].
         *
         * ⚠ Linear in the loaded window only. The placeholders on either side are arithmetic, so a
         * library of fifty thousand photographs with one page loaded costs one page.
         */
        fun of(
            placeholdersBefore: Int,
            items: List<FeedItem>,
            placeholdersAfter: Int,
            density: FeedDensity,
            columns: Int = density.columns,
        ): FeedLayout {
            val features = density.features && columns % FeedLine.Feature.BLOCK == 0
            val lines = ArrayList<FeedLine>(items.size / columns + 1)
            val linePhotos = ArrayList<Int>(items.size / columns + 1)
            var borrowed = 0
            // The position in the whole library of the next photograph — see the class KDoc for
            // why it starts at the placeholder count and why that is stable.
            var photo = placeholdersBefore

            var i = 0
            while (i < items.size) {
                if (items[i] is FeedItem.DayHeader) {
                    lines += FeedLine.Header(placeholdersBefore + i)
                    linePhotos += photo
                    i++
                    continue
                }

                var run = 1
                while (run < columns && i + run < items.size && items[i + run] is FeedItem.Tile) run++
                val start = placeholdersBefore + i

                when {
                    // The window's last row: it runs on into the unloaded indices after it.
                    run < columns && i + run == items.size -> {
                        borrowed = minOf(columns - run, placeholdersAfter)
                        lines += FeedLine.Row(start, run + borrowed)
                    }

                    // ⚠ `photo / columns` advances by exactly one per full line within a section,
                    // whatever phase the section started on, so consecutive lines take consecutive
                    // slots — at any width, which is what keeps the rhythm when the window grows.
                    features && run == columns && isFeatureSlot(photo / columns) ->
                        lines += FeedLine.Feature(
                            start = start,
                            heroFirst = (photo / columns) % FEATURE_CYCLE == 0,
                            blocks = columns / FeedLine.Feature.BLOCK,
                        )

                    else -> lines += FeedLine.Row(start, run)
                }
                linePhotos += photo
                photo += run
                i += run
            }

            val loadedEnd = placeholdersBefore + items.size
            return FeedLayout(
                columns = columns,
                density = density,
                placeholdersBefore = placeholdersBefore,
                items = items,
                loadedLines = lines,
                loadedLinePhotos = linePhotos.toIntArray(),
                loadedPhotos = photo - placeholdersBefore,
                trailingStart = loadedEnd + borrowed,
                totalItems = loadedEnd + placeholdersAfter,
            )
        }

        private fun isFeatureSlot(slot: Int): Boolean =
            slot % FEATURE_CYCLE == 0 || slot % FEATURE_CYCLE == SECOND_FEATURE

        private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
    }
}

/**
 * The date a feed line describes — its header's, or its first photograph's — for the sticky header
 * and the scrubber's bubble, which must agree with each other and with the headers in the grid.
 * Shared so the Compose feed and the SwiftUI one cannot drift.
 *
 * ⚠ `null` for a line of placeholders or one past the end: nothing is known, and a held label would
 * be another photograph's date presented as this one's. ⚠ Only peeks at the window, so
 * resolving a label never tells Paging a row was displayed.
 */
fun FeedLayout.labelAt(
    line: Int,
    density: FeedDensity,
    sectionLabel: (FeedDensity, Long) -> String,
): String? {
    if (line !in 0 until lineCount) return null
    return when (val item = itemAt(this[line].start)) {
        is FeedItem.DayHeader -> sectionLabel(density, item.dayStartMs)
        // ⚠ `dayLabel` reads only the DATE part of what it is given, so a capture time formats
        // identically to its day start — no day-start arithmetic is needed here.
        is FeedItem.Tile -> sectionLabel(density, item.photo.capturedAtMs)
        null -> null
    }
}

/**
 * What turns one drawn [FeedLayout] into the next ([FeedLayout.changesFrom]).
 *
 * ⚠ [deleted] are line numbers in the OLD layout; [inserted] and [changed] in the NEW one — the
 * convention of a batch update, which deletes before it inserts. All ascending. With [reload],
 * the three are empty and the shell redraws everything.
 */
class FeedLineChanges internal constructor(
    val reload: Boolean,
    val deleted: List<Int>,
    val inserted: List<Int>,
    val changed: List<Int>,
) {
    internal companion object {
        val RELOAD = FeedLineChanges(reload = true, deleted = emptyList(), inserted = emptyList(), changed = emptyList())
    }
}

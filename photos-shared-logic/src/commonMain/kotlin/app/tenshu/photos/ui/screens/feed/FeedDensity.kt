package app.tenshu.photos.ui.screens.feed

/**
 * How coarsely the feed groups and how tightly it packs (`photos-app.md` S1).
 *
 * ⚠⚠ **This is not only layout, and the spec's own line says otherwise.** S1 calls the zoom control
 * *"the standard idiom [that] costs only layout"*. It does not: the density decides what a section
 * *is*, so it changes the separator rule the view model applies upstream of `cachedIn` — the same
 * pass that decides where day headers go. Changing it rebuilds the paged stream. Treating it as a
 * column count would give a grid of 2026 photographs under headers that still said *17 December*.
 *
 * ⚠ Ordered coarsest-last so `next()` reads as zooming out.
 */
enum class FeedDensity {
    /**
     * One section per day in three columns, with some photographs drawn over 2×2 cells — the
     * reference app's day view (`photos-app.md` §32), and the default.
     *
     * ⚠⚠ Sections exactly as [Day] does: the mosaic is a property of the LINES ([FeedLayout]), not
     * of the grouping, so every function that decides what a section is must treat the two alike.
     */
    Mosaic,

    /** One section per day, every photograph the same size. */
    Day,

    /** One section per month. */
    Month,

    /** One section per year. */
    Year;

    /**
     * The density control's accessibility label. ⚠ Names the CURRENT grouping, not the next one: a
     * control that describes what it will become reads as a statement about what is on screen.
     * Shared so the Compose button and the SwiftUI one say the same thing (PIO-2).
     */
    val description: String
        get() = when (this) {
            Mosaic -> "Grouped by day, some photos larger"
            else -> "Grouped by " + name.lowercase()
        } + ". Change grouping."

    /** ⚠ Wraps, because the control is one button: four states and a single tap between them. */
    fun next(): FeedDensity = entries[(ordinal + 1) % entries.size]

    /**
     * How many columns the grid uses at this density on a phone-width grid — the base count that a
     * wider grid multiplies (`columnsAt`, §33).
     *
     * ⚠ Lives here rather than in the screen so the two halves cannot drift: a density that groups
     * by year while still drawing three fat columns is a year of photographs in a very long strip.
     */
    val columns: Int
        get() = when (this) {
            Mosaic, Day -> 3
            Month -> 5
            Year -> 7
        }

    /**
     * Whether some lines draw one photograph over 2×2 cells ([FeedLine.Feature]).
     *
     * ⚠ Only at a multiple of three columns: a feature block is two columns of hero beside one column
     * of two photographs, which fills a line only when the line is whole blocks wide (`FeedLayout`).
     */
    val features: Boolean
        get() = this == Mosaic
}

package app.tenshu.calendar.model

/**
 * The colours a calendar can be (CA-5f).
 *
 * ⚠ A fixed palette rather than a colour picker. A calendar colour is a *label*
 * — its whole job is to be distinguishable at a glance in a dense grid — and an
 * arbitrary picker lets a user choose two near-identical shades, or one that
 * makes the event text unreadable. Every stock calendar ships a palette for the
 * same reason.
 *
 * ⚠ ARGB `Int`s with no alpha byte set to anything but opaque: the row stores an
 * `Int` and the grid draws it directly, so a translucent colour would render
 * differently on every background it lands on.
 */
object CalendarPalette {

    val COLORS: List<Int> = listOf(
        0xFF4285F4.toInt(),   // blue
        0xFF0F9D58.toInt(),   // green
        0xFFDB4437.toInt(),   // red
        0xFFF4B400.toInt(),   // amber
        0xFF9C27B0.toInt(),   // purple
        0xFF00ACC1.toInt(),   // teal
        0xFFFF7043.toInt(),   // deep orange
        0xFF757575.toInt(),   // grey
    )

    /** ⚠ Matches the colour `ensureDefaultCalendar` seeds "Personal" with. */
    val DEFAULT: Int = COLORS.first()

    /**
     * The next colour not already in [taken], for a new calendar.
     *
     * ⚠ Falls back to the first colour once the palette is exhausted rather than
     * refusing to create a ninth calendar — a duplicate colour is a cosmetic
     * problem, and the user can change it. ⚠ Compares RGB only ([sameColour]), so
     * an alpha-0 row counts as its opaque twin; a colour from outside this
     * palette simply does not match and costs nothing.
     */
    fun nextAfter(taken: List<Int>): Int =
        COLORS.firstOrNull { colour -> taken.none { sameColour(it, colour) } } ?: DEFAULT

    /**
     * Whether two stored colours are the same colour, **ignoring the alpha byte**.
     *
     * ⚠⚠ Every renderer forces the alpha opaque (Compose: `colorArgb or 0xFF000000`, iOS:
     * `CalendarDayDot.alpha`), because rows with alpha 0 exist: the first calendar used to be
     * seeded as `0x4285F4`. Comparing raw `Int`s would leave that calendar with no colour selected
     * in its edit sheet and let a new calendar take the same blue.
     */
    fun sameColour(a: Int, b: Int): Boolean = (a or OPAQUE) == (b or OPAQUE)

    private const val OPAQUE: Int = -0x1000000 // 0xFF000000
}

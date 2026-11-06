package app.tenshu.control.ui.screens.offlinemaps

/**
 * Search and grouping for Control's *Offline maps* list — pure, so the rules are decided in a test
 * rather than by scrolling a screen.
 *
 * ⚠⚠ **The catalog carries no continent, no country, and no hierarchy.** A `RegionEntry` is
 * `regionId, name, coverage, byteSize, sha256, tileSchemaVersion, path`, and `coverage` is a bare
 * `west,south,east,north` box used for *coverage resolution*. So the grouping here is **derived from
 * that box**, not read from data: it costs no catalog reshape, no re-sign, no ops deploy, and — since
 * `RegionEntry` is a positionally-serialised composite — no House-Rule-#9 version bump. The alternative,
 * curated `continent`/`country` fields, is a wire reshape; worth doing when the catalog is large enough
 * for the derivation's edges to matter, and not before.
 */

/** The section a region is filed under. Order here is the order the list renders in. */
internal enum class RegionGroup(val label: String) {
    Worldwide("Worldwide"),
    Africa("Africa"),
    Asia("Asia"),
    Europe("Europe"),
    NorthAmerica("North America"),
    Oceania("Oceania"),
    SouthAmerica("South America"),
    Antarctica("Antarctica"),

    /**
     * ⚠ Its own bucket, never folded into a neighbour. A region whose box we cannot place — no coverage
     * at all (the install index carries none, so every resident-only row lands here), or a centroid in
     * open ocean — is *unplaced*, which is not the same as belonging to whichever continent is nearest.
     * Guessing would put a region under a heading that is simply wrong, and the user has no way to tell.
     */
    Elsewhere("Elsewhere"),
    ;
}

/** One rendered section: a heading and the regions under it. */
internal data class RegionSection(
    val group: RegionGroup,
    val regions: List<OfflineMapRegion>,
)

/**
 * A region's group, from its `west,south,east,north` coverage box.
 *
 * **Planet-spanning first.** The `global` archive's centroid is `(0, 0)` — the Gulf of Guinea — so a
 * plain centroid rule would file "World" under *Africa*. A box covering essentially the whole planet is
 * [RegionGroup.Worldwide] before any continent is considered.
 *
 * ⚠ Otherwise this is a **coarse centroid-in-box test**, and it is meant to make a long list scannable,
 * not to be a geography reference: a transcontinental country lands wholly on one side (Turkey and
 * western Russia read as Europe), and the boxes are ordered so the ambiguous cases fall where someone
 * scanning for that region would look. `null` coverage — or a centroid in no box — is
 * [RegionGroup.Elsewhere], never a nearest-guess.
 */
internal fun regionGroupOf(coverage: String?): RegionGroup {
    val box = coverage?.let(::parseCoverageBox) ?: return RegionGroup.Elsewhere

    // Spans most of the planet ⇒ not a continent. Checked before the centroid, which for a world box is
    // an arbitrary point in the ocean.
    if (box.width >= 350.0 && box.height >= 150.0) return RegionGroup.Worldwide

    val lng = box.centreLng
    val lat = box.centreLat

    // First match wins; the order resolves the known overlaps deliberately (Europe before Africa so the
    // Mediterranean's north shore reads as Europe; Europe before Asia so Moscow and Istanbul do too).
    return when {
        lat < -60.0 -> RegionGroup.Antarctica
        lng in -31.0..40.0 && lat in 34.0..72.0 -> RegionGroup.Europe
        lng in -26.0..52.0 && lat in -36.0..37.0 -> RegionGroup.Africa
        lng in 25.0..180.0 && lat in -12.0..82.0 -> RegionGroup.Asia
        lng in -172.0..-12.0 && lat in 7.0..84.0 -> RegionGroup.NorthAmerica
        lng in -93.0..-32.0 && lat in -57.0..13.0 -> RegionGroup.SouthAmerica
        lat in -50.0..0.0 && (lng >= 110.0 || lng <= -140.0) -> RegionGroup.Oceania
        else -> RegionGroup.Elsewhere
    }
}

private class CoverageBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    val width get() = east - west
    val height get() = north - south
    val centreLng get() = (west + east) / 2.0
    val centreLat get() = (south + north) / 2.0
}

/** `west,south,east,north`, the exact form the catalog emits; null for anything malformed. */
private fun parseCoverageBox(coverage: String): CoverageBox? {
    val parts = coverage.split(',')
    if (parts.size != 4) return null
    val n = parts.map { it.trim().toDoubleOrNull() ?: return null }
    return CoverageBox(n[0], n[1], n[2], n[3])
}

/**
 * Whether [region] matches [query].
 *
 * Matches the display name **and** the region id, because the id is what the catalog, the logs and this
 * repo's docs all call a region (`de-bayern`), while the name is what the screen shows (`Bayern`) — a
 * search that knew only one of them would fail whichever the user happened to have in mind.
 *
 * ⚠ Diacritics are folded, so `osterreich` finds `Österreich`. Without it the search box cannot find one
 * of the catalog's real regions by its ordinary spelling on a keyboard that has no umlaut — a defect, not
 * a nicety. The fold is a small explicit table, not a general Unicode normalisation: `commonMain` has no
 * `java.text.Normalizer`, and the alternative of pretending is worse than a table that names its scope.
 */
internal fun matchesQuery(region: OfflineMapRegion, query: String): Boolean {
    val needle = foldForSearch(query)
    if (needle.isEmpty()) return true
    return foldForSearch(region.name).contains(needle) || foldForSearch(region.regionId).contains(needle)
}

/** Lower-cases and strips the Latin diacritics the catalog's languages actually use. */
internal fun foldForSearch(text: String): String = buildString(text.length) {
    for (ch in text.lowercase().trim()) append(DIACRITICS[ch] ?: ch)
}

private val DIACRITICS: Map<Char, Char> = buildMap {
    "àáâãäåā".forEach { put(it, 'a') }
    "èéêëē".forEach { put(it, 'e') }
    "ìíîïī".forEach { put(it, 'i') }
    "òóôõöøō".forEach { put(it, 'o') }
    "ùúûüū".forEach { put(it, 'u') }
    "ýÿ".forEach { put(it, 'y') }
    "ñ".forEach { put(it, 'n') }
    "çć".forEach { put(it, 'c') }
    "šś".forEach { put(it, 's') }
    "žź".forEach { put(it, 'z') }
}

/**
 * [regions] filtered by [query] and split into sections.
 *
 * Regions keep the catalog's order within a section — it is the order the publisher chose, and a
 * secondary sort by name would only shuffle it for no gain. Empty sections are dropped, so a search
 * never renders a heading with nothing under it.
 *
 * ⚠ Pass `grouped = false` when the catalog could not be read: the install index carries **no coverage**,
 * so every resident row would file under *Elsewhere* — a heading that says less than no heading at all.
 */
internal fun groupRegions(
    regions: List<OfflineMapRegion>,
    query: String = "",
    grouped: Boolean = true,
): List<RegionSection> {
    val matching = regions.filter { matchesQuery(it, query) }
    if (!grouped) {
        return if (matching.isEmpty()) emptyList() else listOf(RegionSection(RegionGroup.Elsewhere, matching))
    }
    return RegionGroup.entries
        .map { group -> RegionSection(group, matching.filter { regionGroupOf(it.coverage) == group }) }
        .filter { it.regions.isNotEmpty() }
}

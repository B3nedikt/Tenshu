package app.tenshu.control.ui.screens.offlinemaps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Search and grouping for Control's *Offline maps* list. Both are pure, so the rules — including the
 * derivation's known edges — are pinned here rather than discovered by scrolling a screen.
 */
class OfflineMapsGroupingTest {

    private fun region(
        id: String,
        name: String = id,
        coverage: String? = null,
        installed: Boolean = false,
    ) = OfflineMapRegion(id, name, 1_000_000L, installed, coverage = coverage)

    // --- the group derivation ----------------------------------------------------------------

    @Test
    fun theRealCatalogLandsWhereYouWouldLookForIt() {
        // The boxes in deploy/tiles/regions.json, verbatim — the derivation has to be right for the
        // regions that actually exist before its edges are worth arguing about.
        assertEquals(RegionGroup.Worldwide, regionGroupOf("-180,-90,180,90"))          // global
        assertEquals(RegionGroup.Europe, regionGroupOf("8.98,47.27,13.84,50.56"))      // de-bayern
        assertEquals(RegionGroup.Europe, regionGroupOf("11.27,51.36,14.77,53.56"))     // de-berlin-brandenburg
        assertEquals(RegionGroup.Europe, regionGroupOf("9.53,46.37,17.16,49.02"))      // at
        assertEquals(RegionGroup.Europe, regionGroupOf("5.96,45.82,10.49,47.81"))      // ch
        assertEquals(RegionGroup.Europe, regionGroupOf("11.14,43.73,11.35,43.83"))     // test-firenze
        assertEquals(RegionGroup.Europe, regionGroupOf("10.6,43.3,12.0,44.3"))         // test-toscana
    }

    @Test
    fun aWorldSpanningBoxIsNotFiledUnderAfrica() {
        // ⚠ The centroid of the whole planet is (0,0) — the Gulf of Guinea. A plain centroid rule would
        // put "World" under Africa, which is exactly the sort of confidently-wrong heading this guards.
        assertEquals(RegionGroup.Worldwide, regionGroupOf("-180,-90,180,90"))
        assertEquals(RegionGroup.Africa, regionGroupOf("-1,-1,1,1"), "a genuinely tiny box there IS Africa")
    }

    @Test
    fun otherContinentsResolve() {
        assertEquals(RegionGroup.Asia, regionGroupOf("139,35,140,36"))          // Tokyo
        assertEquals(RegionGroup.Africa, regionGroupOf("31,29,32,31"))          // Cairo
        assertEquals(RegionGroup.NorthAmerica, regionGroupOf("-75,40,-73,41"))  // New York
        assertEquals(RegionGroup.SouthAmerica, regionGroupOf("-47,-24,-46,-23")) // São Paulo
        assertEquals(RegionGroup.Oceania, regionGroupOf("150,-34,151,-33"))     // Sydney
        assertEquals(RegionGroup.Antarctica, regionGroupOf("0,-80,10,-70"))
    }

    @Test
    fun anUnplaceableBoxIsElsewhereNotANearestGuess() {
        // ⚠ The case that actually occurs: a row from the install index, which carries no coverage field
        // at all. "Unknown is not elsewhere-on-a-map" — filing it under the nearest continent would print
        // a heading that is simply wrong, with no way for the user to tell.
        assertEquals(RegionGroup.Elsewhere, regionGroupOf(null), "no coverage at all")
        assertEquals(RegionGroup.Elsewhere, regionGroupOf(""), "malformed")
        assertEquals(RegionGroup.Elsewhere, regionGroupOf("1,2,3"), "too few components")
        assertEquals(RegionGroup.Elsewhere, regionGroupOf("a,b,c,d"), "non-numeric")
        // Open South Pacific — in no box, so it falls through rather than being attributed.
        assertEquals(RegionGroup.Elsewhere, regionGroupOf("-121,-31,-119,-29"))
    }

    @Test
    fun openOceanInsideAContinentSpanIsAttributedToIt_aKnownAndAcceptedEdge() {
        // ⚠ Documented, not aspired to: the boxes are spans, so a point in open water inside one takes
        // that continent (mid-Pacific around Hawaii reads as North America — which for Hawaii is right,
        // and for empty ocean is harmless). It is accepted because **the catalog only ever contains land
        // extracts**; tightening the boxes to exclude water would add error for regions that don't exist.
        assertEquals(RegionGroup.NorthAmerica, regionGroupOf("-160,10,-150,20"))
    }

    // --- search ------------------------------------------------------------------------------

    @Test
    fun searchMatchesTheNameAndTheId() {
        val bayern = region("de-bayern", "Bayern")
        assertTrue(matchesQuery(bayern, "bay"), "display name")
        assertTrue(matchesQuery(bayern, "de-"), "the id, which is what the catalog and logs call it")
        assertFalse(matchesQuery(bayern, "tirol"))
    }

    @Test
    fun searchIsCaseAndDiacriticInsensitive() {
        val at = region("at", "Österreich")
        assertTrue(matchesQuery(at, "Öster"))
        assertTrue(matchesQuery(at, "öster"))
        // ⚠ The one that matters: without folding, the search box cannot find a real catalog region by
        // its ordinary spelling on a keyboard with no umlaut.
        assertTrue(matchesQuery(at, "osterreich"))
        assertTrue(matchesQuery(at, "OSTER"))
    }

    @Test
    fun anEmptyOrBlankQueryMatchesEverything() {
        val ch = region("ch", "Schweiz")
        assertTrue(matchesQuery(ch, ""))
        assertTrue(matchesQuery(ch, "   "), "whitespace is not a filter")
    }

    // --- sectioning --------------------------------------------------------------------------

    private val catalog = listOf(
        region("global", "World", "-180,-90,180,90"),
        region("de-bayern", "Bayern", "8.98,47.27,13.84,50.56"),
        region("at", "Österreich", "9.53,46.37,17.16,49.02"),
        region("jp", "Japan", "139,35,140,36"),
    )

    @Test
    fun sectionsAreOrderedAndEmptyOnesDropped() {
        val sections = groupRegions(catalog)
        assertEquals(
            listOf(RegionGroup.Worldwide, RegionGroup.Asia, RegionGroup.Europe),
            sections.map { it.group },
            "declaration order, and no heading for a continent with nothing under it",
        )
        assertEquals(listOf("de-bayern", "at"), sections.first { it.group == RegionGroup.Europe }.regions.map { it.regionId })
    }

    @Test
    fun searchNarrowsSectionsAndDropsTheOnesLeftEmpty() {
        val sections = groupRegions(catalog, query = "japan")
        assertEquals(listOf(RegionGroup.Asia), sections.map { it.group })
        assertEquals(listOf("jp"), sections.single().regions.map { it.regionId })
    }

    @Test
    fun aQueryMatchingNothingYieldsNoSectionsAtAll() {
        // The screen keys its "No regions match" state off exactly this, and it must not be confusable
        // with "the catalog is empty" — which is a different, already-distinct state.
        assertTrue(groupRegions(catalog, query = "atlantis").isEmpty())
    }

    @Test
    fun ungroupedKeepsOneSectionAndStillSearches() {
        // ⚠ The catalog-unavailable case: install-index rows carry no coverage, so grouping them would
        // file every one under "Elsewhere". One section, no heading rendered, search still working.
        val resident = listOf(region("at", "Österreich", coverage = null, installed = true))
        val all = groupRegions(resident, grouped = false)
        assertEquals(1, all.size)
        assertEquals(listOf("at"), all.single().regions.map { it.regionId })

        assertTrue(groupRegions(resident, query = "bayern", grouped = false).isEmpty())
        assertEquals(1, groupRegions(resident, query = "oster", grouped = false).size)
    }
}

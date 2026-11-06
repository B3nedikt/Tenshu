package app.tenshu.maps.data

import app.tenshu.maps.model.Coord
import app.tenshu.maps.model.GeoPoint
import app.tenshu.maps.model.Place
import app.tenshu.maps.model.PlaceCategory
import app.tenshu.maps.model.PlacePhoto
import app.tenshu.maps.model.PlaceSection
import app.tenshu.maps.model.Reason
import app.tenshu.maps.model.ReasonKind
import app.tenshu.maps.model.SavedList
import kotlinx.datetime.LocalDate

/**
 * Ported mock dataset for Maps — Places (slim) (Ph2, docs/proposals/maps-app.md §6), a
 * faithful transcription of the design handoff's `mockup/maps-data.js` (Lisbon). This is
 * *structure*, not a wire format — the real store is the on-device `:place-service`, which
 * replaces [MockPlaceRepository] behind [PlaceRepository]. Provenance is always modelled
 * (`section` / `reason`), per the handoff's trust-story rule.
 */
object MapsMockData {

    /** "Today" anchor the mock's relative visit dates are written against. */
    val today: LocalDate = LocalDate.parse("2026-07-06")

    /** Muted, low-chroma photo tones (never photoreal — a `PhotoFrame` gradient seed). */
    private object Tone {
        const val STONE = "#b7b1a6"
        const val SAGE = "#a9b3a0"
        const val DUSK = "#a6a3b6"
        const val CLAY = "#c2a89a"
        const val SLATE = "#9aa4ad"
        const val MOSS = "#9fac97"
        const val SAND = "#cbbfa6"
        const val PLUM = "#b0a1b4"
    }

    private fun d(iso: String): LocalDate = LocalDate.parse(iso)
    private fun photos(vararg tones: String) = tones.map { PlacePhoto(it) }
    private fun visits(vararg iso: String) = iso.map { d(it) }

    /**
     * The Firenze (Florence) bounding box the mock dataset lives in (WGS84 degrees). [projectToGeo]
     * maps a normalized [Coord] into it, standing in for the real `latE7`/`lngE7` the `:place-service`
     * store carries (Ph9). Chosen to sit inside the bundled demo basemap extract
     * (`ui-map .../tenshu_basemap.pmtiles`, a Firenze clip) so every place snippet centres on real
     * tile data — the addresses stay fictional. Also the coverage box a "Firenze" catalog region
     * would advertise, so `RegionResolver` resolves the mock places to it once a region is installed.
     */
    val FIRENZE_MIN_LAT = 43.760
    val FIRENZE_MAX_LAT = 43.782
    val FIRENZE_MIN_LNG = 11.240
    val FIRENZE_MAX_LNG = 11.272

    /**
     * Projects a schematic [Coord] (0..1) into a plausible Firenze [GeoPoint]. `my` runs
     * north→south (screen-y down), so it maps to latitude inverted. This is the mock analogue of
     * the Ph9 `latE7`/`lngE7` → [GeoPoint] edge conversion.
     */
    fun projectToGeo(coord: Coord): GeoPoint = GeoPoint(
        latitude = FIRENZE_MAX_LAT - coord.my * (FIRENZE_MAX_LAT - FIRENZE_MIN_LAT),
        longitude = FIRENZE_MIN_LNG + coord.mx * (FIRENZE_MAX_LNG - FIRENZE_MIN_LNG),
    )

    /** Raw transcription of `mockup/maps-data.js`; [places] projects [Place.location] onto it. */
    private val rawPlaces: List<Place> = listOf(
        // ── Your places ──────────────────────────────────────────────────────
        Place(
            id = "home", name = "Home", category = PlaceCategory.Home,
            section = PlaceSection.Yours, label = "Home", address = "Rua das Flores 22",
            coord = Coord(0.44, 0.58), seed = 7, saved = true, visitCount = 812,
            lastVisit = today, firstVisit = d("2023-09-01"),
            recentVisits = visits("2026-07-06", "2026-07-05", "2026-07-05", "2026-07-04", "2026-07-03"),
            photos = photos(Tone.CLAY, Tone.SAND),
            note = "Your home. Used to label visits across Timeline and Photos.",
        ),
        Place(
            id = "studio", name = "Studio", category = PlaceCategory.Work,
            section = PlaceSection.Yours, label = "Work", address = "Travessa do Norte 4",
            coord = Coord(0.63, 0.36), seed = 12, saved = true, visitCount = 391,
            lastVisit = d("2026-07-04"), firstVisit = d("2024-02-12"),
            recentVisits = visits("2026-07-04", "2026-07-03", "2026-07-02", "2026-07-01", "2026-06-30"),
            photos = photos(Tone.SLATE),
        ),
        Place(
            id = "mums", name = "Mum's", category = PlaceCategory.Home,
            section = PlaceSection.Yours, label = "Family", address = "Largo da Sé 9",
            coord = Coord(0.30, 0.30), seed = 21, saved = true, visitCount = 64,
            lastVisit = d("2026-06-28"), firstVisit = d("2023-09-14"),
            recentVisits = visits("2026-06-28", "2026-06-14", "2026-05-31"),
            photos = photos(Tone.SAND, Tone.STONE),
        ),

        // ── Saved places ─────────────────────────────────────────────────────
        Place(
            id = "aurora", name = "Café Aurora", category = PlaceCategory.Cafe,
            section = PlaceSection.Saved, savedList = "Coffee", address = "Rua da Prata 51",
            coord = Coord(0.52, 0.49), seed = 3, saved = true, visitCount = 23,
            lastVisit = d("2026-07-02"), firstVisit = d("2025-11-08"),
            recentVisits = visits("2026-07-02", "2026-06-25", "2026-06-18", "2026-06-09"),
            photos = photos(Tone.CLAY, Tone.SAND, Tone.STONE),
        ),
        Place(
            id = "norte", name = "Livraria Norte", category = PlaceCategory.Store,
            section = PlaceSection.Saved, savedList = "Coffee", address = "Rua Garrett 12",
            coord = Coord(0.58, 0.55), seed = 9, saved = true, visitCount = 6,
            lastVisit = d("2026-06-20"), firstVisit = d("2026-01-30"),
            recentVisits = visits("2026-06-20", "2026-04-11"),
            photos = photos(Tone.SAND),
        ),
        Place(
            id = "rio", name = "Tasca do Rio", category = PlaceCategory.Restaurant,
            section = PlaceSection.Saved, savedList = "To try", address = "Cais do Sodré",
            coord = Coord(0.49, 0.70), seed = 15, saved = true, visitCount = 0,
            lastVisit = null, firstVisit = null, recentVisits = emptyList(),
            photos = photos(Tone.DUSK, Tone.PLUM),
            note = "Saved from a friend — not visited yet.",
        ),
        Place(
            id = "sol", name = "Miradouro do Sol", category = PlaceCategory.Viewpoint,
            section = PlaceSection.Saved, savedList = "Weekend", address = "Alfama",
            coord = Coord(0.71, 0.62), seed = 4, saved = true, visitCount = 3,
            lastVisit = d("2026-05-24"), firstVisit = d("2025-09-15"),
            recentVisits = visits("2026-05-24", "2026-03-02", "2025-09-15"),
            photos = photos(Tone.DUSK, Tone.SLATE, Tone.SAGE),
        ),
        Place(
            id = "botanico", name = "Jardim Botânico", category = PlaceCategory.Park,
            section = PlaceSection.Saved, savedList = "Weekend", address = "Rua da Escola",
            coord = Coord(0.37, 0.44), seed = 18, saved = true, visitCount = 8,
            lastVisit = d("2026-06-07"), firstVisit = d("2024-05-19"),
            recentVisits = visits("2026-06-07", "2026-04-26", "2026-03-14"),
            photos = photos(Tone.MOSS, Tone.SAGE),
        ),

        // ── Recommended places (system-proposed) ─────────────────────────────
        Place(
            id = "padaria", name = "Padaria Central", category = PlaceCategory.Cafe,
            section = PlaceSection.Recommended, address = "Rua da Prata 88",
            coord = Coord(0.54, 0.46), seed = 6, saved = false, visitCount = 14,
            lastVisit = d("2026-07-01"), firstVisit = d("2026-02-03"),
            recentVisits = visits("2026-07-01", "2026-06-27", "2026-06-24", "2026-06-20"),
            photos = photos(Tone.SAND, Tone.CLAY),
            reason = Reason(ReasonKind.Timeline, "You pass here most mornings"),
        ),
        Place(
            id = "verde", name = "Restaurante Verde", category = PlaceCategory.Restaurant,
            section = PlaceSection.Recommended, address = "Príncipe Real",
            coord = Coord(0.40, 0.24), seed = 11, saved = false, visitCount = 0,
            lastVisit = null, recentVisits = emptyList(),
            photos = photos(Tone.MOSS, Tone.SAGE, Tone.STONE),
            reason = Reason(ReasonKind.Contact, "Saved by Inês C."),
        ),
        Place(
            id = "museu", name = "Museu da Cidade", category = PlaceCategory.Culture,
            section = PlaceSection.Recommended, address = "Campo Grande",
            coord = Coord(0.26, 0.52), seed = 2, saved = false, visitCount = 0,
            lastVisit = null, recentVisits = emptyList(),
            photos = photos(Tone.SLATE, Tone.DUSK),
            reason = Reason(ReasonKind.Curated, "Curated · City museums"),
        ),
        Place(
            id = "estacao", name = "Estação Norte", category = PlaceCategory.Transit,
            section = PlaceSection.Recommended, address = "Av. da Liberdade",
            coord = Coord(0.66, 0.72), seed = 14, saved = false, visitCount = 41,
            lastVisit = d("2026-07-05"),
            recentVisits = visits("2026-07-05", "2026-07-03", "2026-06-30"),
            photos = photos(Tone.SLATE),
            reason = Reason(ReasonKind.Timeline, "A frequent stop on your Timeline"),
        ),
    )

    /** The dataset, each place carrying a projected SI [Place.location] (see [projectToGeo]). */
    val places: List<Place> = rawPlaces.map { it.copy(location = projectToGeo(it.coord)) }

    /** Saved lists — the organizing layer for the Saved tab. */
    val savedLists: List<SavedList> = listOf(
        SavedList(id = "Coffee", name = "Coffee & books", count = 2),
        SavedList(id = "Weekend", name = "Weekend", count = 2),
        SavedList(id = "To try", name = "To try", count = 1),
    )
}

package app.tenshu.timeline.data

import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.Movement
import app.tenshu.timeline.model.MovementModes
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.PlaceVisit
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripEntry
import app.tenshu.timeline.model.TripSuggestion
import kotlinx.datetime.LocalDate

/**
 * The Timeline sample dataset (Ph2, docs/proposals/timeline-app.md §2), ported from the
 * gitignored design handoff `mockup/timeline-data.js`. Berlin today/yesterday/friday, a
 * Hamburg trip suggestion, the recursive Lisbon⊃Sintra trip, seven places (Mogg with full
 * visit history), and six activity roll-ups.
 *
 * The mock's per-photo CSS `gradient` is dropped — `dominantColor` is kept (personalisation
 * + the Ph4 `PhotoFrame` placeholder synthesise a gradient from it). Presentational `meta`
 * strings are not ported (composed at render, §5).
 */
internal object TimelineMockData {

    private fun photo(dominantColor: String, label: String) = Photo(dominantColor = dominantColor, label = label)

    // ── Photos ────────────────────────────────────────────────────────────
    private val parkRun = photo("#7a9a6d", "leafy path")
    private val parkCloseup = photo("#8eaa75", "tree canopy")
    private val coffee = photo("#b08968", "latte art")
    private val lunchHero = photo("#c08552", "sandwich on plate")
    private val lunchTable = photo("#a87b4a", "table with glasses")
    private val lunchDetail = photo("#cb9a6e", "side dish")
    private val bookshop = photo("#a4b094", "bookshop interior")

    private val brunch = photo("#d4b88a", "pancake stack")
    private val brunchInt = photo("#bd9a6a", "cafe interior")
    private val concert1 = photo("#4a3850", "stage lights")
    private val concert2 = photo("#5a4060", "crowd silhouette")
    private val concert3 = photo("#2a1f30", "detail shot")
    private val concert4 = photo("#7a5680", "final encore")
    private val dinner = photo("#a05a3a", "plated dish")

    private val hamburgCover = photo("#6e8aa0", "harbour grey-blue")

    private val lisbonCover = photo("#d8a878", "tiled facade golden hour")
    private val lisbonBelem = photo("#e8c98f", "belém tower golden hour")
    private val lisbonAlfama = photo("#c87646", "alfama rooftops warm")
    private val lisbonTram = photo("#d8a838", "yellow tram")
    private val lisbonAzulejos = photo("#5c84a8", "blue tiles")
    private val lisbonDinner = photo("#a04830", "wine and prawns")

    private val sintraCover = photo("#cc8a4a", "pena palace yellow")
    private val sintraTrain = photo("#7a8a98", "train window")
    private val sintraPena = photo("#dd9648", "palácio da pena")
    private val sintraGarden = photo("#4a6a3a", "regaleira moss")
    private val sintraLunch = photo("#a05a3a", "sintra petisco")

    // ── Home days ─────────────────────────────────────────────────────────
    private val today = Day(
        date = LocalDate.parse("2026-05-24"),
        dayLabel = "Today",
        dateLong = "Sunday, May 24",
        place = "Berlin",
        weather = "22°",
        movement = Movement(distanceKm = 5.2, steps = 8431),
        moments = listOf(
            Moment("morning-run", "09:14", "Morning run", "Volkspark Friedrichshain",
                MomentKind.Activity, durationMin = 32, photos = listOf(parkRun, parkCloseup)),
            Moment("coffee", "11:32", "Coffee", "Bonanza Coffee Heroes",
                MomentKind.Visit, durationMin = 18, photos = listOf(coffee)),
            Moment("lunch", "13:45", "Lunch at Mogg", "Mogg",
                MomentKind.Visit, durationMin = 82, photos = listOf(lunchHero, lunchTable, lunchDetail)),
            Moment("bookshop", "15:10", "do you read me?!", "Auguststraße",
                MomentKind.Visit, durationMin = 4, photos = listOf(bookshop), isCurrent = true),
        ),
    )

    private val yesterday = Day(
        date = LocalDate.parse("2026-05-23"),
        dayLabel = "Yesterday",
        dateLong = "Saturday, May 23",
        place = "Berlin",
        movement = Movement(distanceKm = 8.1, steps = 12504),
        moments = listOf(
            Moment("brunch", "10:00", "Brunch", "House of Small Wonder",
                MomentKind.Visit, durationMin = 90, photos = listOf(brunch, brunchInt)),
            Moment("concert", "14:00", "Berghain Klassik", "Berghain",
                MomentKind.Event, durationMin = 180,
                photos = listOf(concert1, concert2, concert3, concert4), cover = concert1),
            Moment("dinner", "19:30", "Dinner", "Industry Standard",
                MomentKind.Visit, durationMin = 90, photos = listOf(dinner)),
        ),
    )

    private val friday = Day(
        date = LocalDate.parse("2026-05-22"),
        dayLabel = "Friday",
        dateLong = "Friday, May 22",
        place = "Berlin",
        movement = Movement(distanceKm = 2.4, steps = 5102),
        moments = listOf(
            Moment("evening-home", "18:30", "Evening at home", "Home", MomentKind.Quiet),
        ),
    )

    val days: List<Day> = listOf(today, yesterday, friday)

    // ── Auto-detected suggestion (Interlude) ────────────────────────────────
    val tripSuggestion = TripSuggestion(
        id = "hamburg-suggested",
        title = "Hamburg",
        dateRange = "May 17 – 19",
        daysSpan = 3,
        placesCount = 6,
        photosCount = 18,
        cover = hamburgCover,
        confidence = 0.86,
    )

    // ── Sintra (nested day-trip inside Lisbon) ──────────────────────────────
    private val sintraTrip = Trip(
        id = "sintra-2026-04-14",
        title = "Sintra",
        dateRange = "Apr 14",
        daysSpan = 1,
        placesCount = 3,
        photosCount = 14,
        momentsCount = 4,
        cover = sintraCover,
        tags = listOf("day trip", "palaces", "Portugal"),
        contents = listOf(
            TripEntry.MomentItem(Moment("sintra-train", "09:12", "Train to Sintra", "Rossio → Sintra",
                MomentKind.Visit, durationMin = 50, photos = listOf(sintraTrain))),
            TripEntry.MomentItem(Moment("sintra-pena", "11:30", "Palácio da Pena", "Palácio Nacional da Pena",
                MomentKind.Visit, durationMin = 150, photos = listOf(sintraPena, sintraPena, sintraPena))),
            TripEntry.MomentItem(Moment("sintra-lunch", "14:30", "Lunch", "Tascantiga",
                MomentKind.Visit, durationMin = 75, photos = listOf(sintraLunch))),
            TripEntry.MomentItem(Moment("sintra-regaleira", "16:30", "Quinta da Regaleira", "Quinta da Regaleira",
                MomentKind.Visit, durationMin = 120, photos = listOf(sintraGarden, sintraGarden))),
        ),
    )

    // ── Lisbon trip — recursive: contains the Sintra day-trip ───────────────
    private val lisbonTrip = Trip(
        id = "lisbon-2026-04",
        title = "Lisbon",
        dateRange = "Apr 12 – 17",
        year = "2026",
        daysSpan = 5,
        placesCount = 14,
        photosCount = 47,
        cover = lisbonCover,
        tags = listOf("Portugal", "city", "spring"),
        contents = listOf(
            TripEntry.DayEntry(1, "Day 1", "Sat · Apr 12", listOf(
                Moment("lis-arrival", "14:30", "Arrival", "Aeroporto de Lisboa",
                    MomentKind.Visit, durationMin = 60),
                Moment("lis-dinner1", "20:00", "Dinner", "Cervejaria Ramiro",
                    MomentKind.Visit, durationMin = 90, photos = listOf(lisbonDinner)),
            )),
            TripEntry.DayEntry(2, "Day 2", "Sun · Apr 13", listOf(
                Moment("lis-belem", "11:00", "Belém", "Belém Tower",
                    MomentKind.Visit, durationMin = 180, photos = listOf(lisbonBelem)),
                // The one MOVEMENT moment in the dataset — the moment detail's route hero had
                // nothing to render before it (`RouteSketchCard`, and now the iOS map hero, were
                // both unreachable through the mock). Its trace resolves through [movementRoutes],
                // sited inside the bundled demo extract for the same reason [Trip.route] is.
                Moment("lis-riverwalk", "15:00", "Walk along the river", "Oltrarno",
                    MomentKind.Movement, durationMin = 26,
                    movementMode = MovementModes.WALK, distanceM = 1_640,
                    originPlace = "Ponte Vecchio", destinationPlace = "Oltrarno",
                    traceId = riverWalkTraceId),
                Moment("lis-alfama", "17:30", "Alfama walk", "Alfama",
                    MomentKind.Activity, durationMin = 120, photos = listOf(lisbonAlfama, lisbonTram)),
            )),
            TripEntry.NestedTrip(3, "Day 3", "Mon · Apr 14", sintraTrip),
            TripEntry.DayEntry(4, "Day 4", "Tue · Apr 15", listOf(
                Moment("lis-azulejos", "10:30", "Museu Nacional do Azulejo", "Museu Nacional do Azulejo",
                    MomentKind.Visit, durationMin = 90, photos = listOf(lisbonAzulejos)),
            )),
            TripEntry.Collapsed(daysRemaining = 2, label = "2 more days · Apr 16 – 17"),
        ),
        // The trip's ordered place coordinates → the route-over-basemap surface (TripRouteMap).
        // A compact central-Firenze walk (Santa Maria Novella → Duomo → Signoria → Ponte Vecchio →
        // Oltrarno), sited inside the bundled Firenze extract so the offline/streamed basemap renders
        // real tiles under the line (mirrors the Maps demo's Firenze siting; projector derivation is a
        // follow-up).
        route = listOf(
            GeoPoint(43.7745, 11.2490),
            GeoPoint(43.7731, 11.2560),
            GeoPoint(43.7697, 11.2557),
            GeoPoint(43.7680, 11.2531),
            GeoPoint(43.7660, 11.2505),
        ),
    )

    /** Trips reachable by id — the recursive lookup flattens nested trips too. */
    val trips: List<Trip> = listOf(lisbonTrip, sintraTrip)

    // ── Movement traces ─────────────────────────────────────────────────────
    /** The trace `lis-riverwalk` carries; a constant so the moment and the route cannot drift apart. */
    const val riverWalkTraceId: String = "trace-riverwalk"

    /**
     * Decoded movement paths by trace id — what [MockTimelineRepository.movementRoute] serves, and
     * therefore the only thing that makes the movement detail's route hero reachable without a live
     * store. Sited inside the bundled Firenze extract (an Arno-bank stretch from Ponte Vecchio west
     * past Ponte Santa Trinita), for the same reason [Trip.route] is: a route outside the demo
     * archive's extent frames onto empty tiles, which looks like a broken map rather than an honest
     * "no data here".
     */
    val movementRoutes: Map<String, List<GeoPoint>> = mapOf(
        riverWalkTraceId to listOf(
            GeoPoint(43.7680, 11.2531),
            GeoPoint(43.7677, 11.2515),
            GeoPoint(43.7673, 11.2498),
            GeoPoint(43.7669, 11.2482),
            GeoPoint(43.7668, 11.2466),
            GeoPoint(43.7671, 11.2451),
        ),
    )

    /** Home shows Lisbon as the bookmarked bound-chapter card. */
    val boundTrips: List<Trip> = listOf(lisbonTrip)

    // ── Places ──────────────────────────────────────────────────────────────
    val places: List<Place> = listOf(
        Place(
            id = "mogg", name = "Mogg", neighborhood = "Augustraße 11–13, Mitte", city = "Berlin",
            tags = listOf("restaurant", "pastrami"), visitsCount = 8, photosCount = 24,
            firstVisit = "Jan 2024", lastVisit = "Today", avgDuration = "1 h 30 min", cover = lunchHero,
            history = listOf(
                PlaceVisit("mogg-today", "Today, May 24", "13:45", "Lunch", "1 h 22 min", 3,
                    listOf(lunchHero, lunchTable, lunchDetail)),
                PlaceVisit("mogg-apr7", "Apr 7", "20:00", "Dinner with K", "2 h 10 min", 2, listOf(dinner)),
                PlaceVisit("mogg-mar14", "Mar 14", "13:30", "Lunch", "1 h 45 min", 4, listOf(lunchTable, lunchDetail)),
                PlaceVisit("mogg-feb22", "Feb 22", "13:00", "Lunch with M", "2 h", 1, listOf(lunchHero)),
                PlaceVisit("mogg-jan15", "Jan 15", "14:00", "Lunch", "1 h 20 min", 0),
                PlaceVisit("mogg-jan8", "Jan 8 · 2025", "20:00", "Dinner", "2 h", 5, listOf(dinner)),
            ),
        ),
        Place("bonanza", "Bonanza Coffee Heroes", "Mitte", "Berlin", tags = listOf("coffee"),
            visitsCount = 47, photosCount = 12, firstVisit = "Aug 2022", lastVisit = "Today", cover = coffee),
        Place("volkspark", "Volkspark Friedrichshain", "Friedrichshain", "Berlin", tags = listOf("park", "running"),
            visitsCount = 23, photosCount = 18, firstVisit = "Sep 2023", lastVisit = "Today", cover = parkRun),
        Place("berghain", "Berghain", "Friedrichshain", "Berlin", tags = listOf("venue", "events"),
            visitsCount = 6, photosCount = 18, firstVisit = "Nov 2024", lastVisit = "Yesterday", cover = concert1),
        Place("small-wonder", "House of Small Wonder", "Mitte", "Berlin", tags = listOf("brunch", "café"),
            visitsCount = 4, photosCount = 7, firstVisit = "Mar 2024", lastVisit = "Yesterday", cover = brunch),
        Place("do-you-read-me", "do you read me?!", "Augustraße, Mitte", "Berlin", tags = listOf("bookshop"),
            visitsCount = 11, photosCount = 9, firstVisit = "Jul 2023", lastVisit = "Today", cover = bookshop),
        Place("industry-standard", "Industry Standard", "Neukölln", "Berlin", tags = listOf("restaurant"),
            visitsCount = 3, photosCount = 4, firstVisit = "Feb 2025", lastVisit = "Saturday", cover = dinner),
        // ⚠ The ONE mock place carrying coordinates, and deliberately so. The place-detail hero is a
        // map when a place has a coordinate and a flat colour when it does not, so a dataset where
        // every place has one (or none) can only ever demonstrate half of that. This is the trip's
        // own landmark, sited inside the bundled demo extract like [Trip.route]; every Berlin place
        // above keeps `latE7 = lngE7 = 0` and therefore keeps exercising the no-coordinate fallback.
        Place("ponte-vecchio", "Ponte Vecchio", "Centro Storico", "Firenze",
            tags = listOf("landmark", "bridge"), visitsCount = 2, photosCount = 6,
            firstVisit = "Apr 2026", lastVisit = "Apr 2026", cover = lisbonBelem,
            latE7 = 437_680_000, lngE7 = 112_531_000),
    )

    // ── Activities ────────────────────────────────────────────────────────
    val activities: List<ActivityRollup> = listOf(
        ActivityRollup("run", "Running", "run", 32, "sessions", "184 km · 33 h total", "Today · 5.2 km", "#7a9a6d"),
        ActivityRollup("coffee", "Coffee visits", "coffee", 47, "visits", "11 h 04 min total", "Today · Bonanza", "#b08968"),
        ActivityRollup("dining", "Dining", "fork", 38, "meals", "52 h · 12 places", "Today · Mogg", "#c08552"),
        ActivityRollup("event", "Events", "music", 6, "events", "14 h · 4 places", "Yesterday · Berghain", "#4a3850"),
        ActivityRollup("walk", "Walks", "pin", 12, "walks", "38 km · 6 neighborhoods", "May 22", "#a4b094"),
        ActivityRollup("book", "Bookshops", "book", 11, "visits", "6 shops · 14 photos", "Today", "#a4b094"),
    )
}

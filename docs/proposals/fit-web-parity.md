# Fit on the web — catching up with native, charts first

**Status:** F1–F4 BUILT (2026-11-15, maintainer: "F1–F4 in order"); F5 and the two open questions (§4) remain.
**Scope:** `web/fit` (React/TS), its bridge, the apex gateway + allowlist, and `tenshu-sdk-js`'s `Fit`.
**Reads first:** `fit-app.md` §Web scope (the 2026-10-28 decisions, which still stand: no recorder,
no GPS recording, GPX/TCX only, data sources read-only, no edit / onboarding / auto-detect) and its
"As built (run B)".

## 1. Where the web stands (the sweep)

| Area | Native (Android + iOS) | Web today |
|------|------------------------|-----------|
| **Charts — bars** | `TrendBars`: today's bar in full accent, the rest at 24 %; press-and-drag scrubs with a "value · date" pill and guide line; selected bar highlighted; weekday letter under every bar; dashed goal line | One `<rect>` per day, all one colour; **no axis, no labels, no selection, no readout** beyond the browser's `<title>` tooltip; Year draws 365 slivers |
| **Charts — HR** | Line over a tinted area fill, dashed average line, "avg · max" header | Bare polyline, average line, min/avg/max caption; no time axis, no readout |
| **Charts — samples** | `SampleTrend` (weight, resting HR, SpO₂, respiration): line + dots + area | — |
| **Home / Dashboard** | Hero active-minutes ring with "N min to go" + WHO line; steps/distance/energy strip (each opens its metric); four 7-day cards (energy, steps, active minutes, distance); latest-workout card | Two small rings + four numbers; ONE chart with a metric switcher; range buttons in a section *below* the cards; no latest workout |
| **Metric detail** | Day/Week/Month/Year switcher, big "daily average", the chart, an entries list with source badges | — (the dashboard's switcher is the only way in) |
| **History** | Week summary (count, distance, time), day headers, source badges | One flat list, no headers, no summary |
| **Workout detail** | Tinted hero band; stat grid; route map (inline + full-screen) or route sketch; ascent chip; HR chart; **per-km laps** (best highlighted); provenance footer | Stat grid, HR chart, route map; no ascent, no laps, no sketch fallback, no provenance |
| **Browse** (sleep, body, vitals, nutrition, cycle, medical) | Seven categories, teasers, detail screens | — (decision 6e: "later, read-only") |
| **Recording, edit, onboarding** | Yes | Out of scope by decision — unchanged here |

The SDK is not the bottleneck for most of this: every gateway method is already used, and the
route stream already carries `altM` and `tSec`, enough to derive **elevation, pace and per-km
splits in the tab**. What the SDK lacks is the Browse reads (sleep, samples, nutrition, cycle,
medical) — all of which `FitProjection` (JS-compiled already) computes (`sleeps`, `samples`,
`latestPerMetric`, `nutritionDays`, `cycleDays`, `medicalRecords`, `categories`).

## 2. Design

### 2.1 One chart component, better than native's, built for a pointer (F1)

The web has width and a mouse; native's charts are phone-sized and touch-first. One hand-rolled
SVG component, `<TrendChart>`, replaces the dashboard bars everywhere (no chart library — the
bundle budget, and native's `ChartMath` is already ported in `lib/chart.ts`):

- **Axes the phone leaves out:** three light gridlines with y labels in the metric's unit
  (`2k · 4k · 6k steps`), and x labels sized to the range — weekday for Week, every fifth day for
  Month, month initials for Year.
- **Native's emphasis:** today's bar in full accent, the rest at 24 %; the goal as a labelled dashed
  line ("Goal 8,000"); the period's daily average as a second, quieter line.
- **Hover and keyboard scrubbing** (native's press-and-drag, for a pointer): the bar under the
  pointer lights up and a pill reads "6,412 steps · Tue 11 Nov"; ←/→ walk the bars when the chart
  has focus (`role="slider"`-like semantics with an `aria-valuetext`), so the readout is not
  mouse-only.
- **Year is 12 monthly bars** (daily average per month, "Ø 7,204 steps/day · March"), never 365
  slivers — the 400-day window already gives a full year.
- **Sizing:** real pixel geometry from a measured width (`ResizeObserver`), not a stretched
  `viewBox` — so strokes, labels and rounded bar caps stay crisp.

The **HR chart** gets the same treatment: a time axis (mm:ss / h:mm), a bpm axis, the line over a
tinted area, the average line labelled, and a hover readout ("142 bpm · 18:40"). **HR zones** (a
band shading, from max HR = 220 − age when the profile has a birth date) is the one place the web
would go beyond native — optional, off when no birth date.

### 2.2 Workout detail: what the stream already holds (F2)

- **Elevation profile** (`altM`) and **pace** chart (from `lat/lng/tSec`, smoothed over ~30 s),
  stacked with HR on one shared time axis; **hovering any of them marks the spot on the map** — the
  web-only payoff of having chart and map side by side.
- **Per-km splits** computed in the tab (native's lap table: #, km, time, pace, avg HR; best full
  km highlighted; the partial last km marked) and the **ascent chip** from the same stream.
- The tinted **hero band** and the **provenance footer** (source, imported when) as native.
- No route: native's abstract route sketch is irrelevant without GPS — show nothing, as now.

### 2.3 Dashboard = native's Home, laid out for a wide screen (F3)

- Hero **active-minutes ring** ("12 min to go" / "Goal reached", WHO line), the steps ring beside it.
- A stat strip (steps, distance, energy) whose cells open the **metric detail**.
- A **2×2 grid of 7-day cards** (energy, steps, active minutes, distance), each a `<TrendChart>`
  with its week total and "±N % vs last week"; a card opens its metric detail.
- The **latest-workout card** (opens the workout).
- **Metric detail** (new route `/metric/<id>`): native's Day/Week/Month/Year switcher, the big
  daily average, the full-width chart, the entries list. This replaces the dashboard's metric
  switcher and its misplaced range buttons.

### 2.4 History as native (F4)

Week summary at the top of each week (count, distance, time), day headers, source badges; the
existing keyset paging and change-driven head refresh stay.

### 2.5 Browse, read-only (F5 — decision 6e's "later")

Sleep (duration chart; stages as a stacked bar per night — native charts duration only), Body
(weight `SampleTrend`), Vitals (resting HR / SpO₂ / respiration), Nutrition (energy), Cycle (the
cycle day + log) and Medical (read-only lists). Needs the SDK reads above (`Fit.sleeps`,
`Fit.samples`, `Fit.latest`, `Fit.nutrition`, `Fit.cycle`, `Fit.medical`) + gateway + allowlist —
thin, since `FitProjection` already computes each. ⚠ Medical records are the most sensitive rows
in the realm; listing them on the web is a maintainer decision of its own, so it is split out.

## 3. Phases and size

| Phase | What | Touches | Size |
|-------|------|---------|------|
| **F1** | `<TrendChart>` + HR chart rework | `web/fit` only | M |
| **F2** | Workout detail: elevation, pace, splits, ascent, linked map hover | `web/fit` only | M |
| **F3** | Dashboard as Home + metric detail route | `web/fit`, apex registry sections | M |
| **F4** | History week summary + day headers | `web/fit` | S |
| **F5** | Browse (read-only) | SDK + gateway + allowlist + `web/fit` | L |

F1–F4 need no SDK change and no schema change. F5 adds SDK reads only (no write, no reshape).

## 4. Open questions for the maintainer

1. **Order:** F1 → F2 → F3 → F4, then F5? (Recommended: the charts are the complaint, and F2/F3
   reuse them.)
2. **HR zones** (§2.1): build them, given the web would then show more than native?
3. **Medical on the web** (§2.5): in F5, or held back?

## 5. As built — F1–F4 (2026-11-15)

- **F1** `TrendChart` (bars) and `SeriesChart` (a series against elapsed time), own SVG at the
  measured width (`useMeasuredWidth`); pure arithmetic in `lib/trend.ts` (`niceAxis`, `rangeAxis`,
  `monthBuckets`, `slotAt`, `labelledSlots`, `nearestIndex`). `MetricTrend` says what a metric's bars
  and readouts SAY. ⚠ The axis is laid out in the units SHOWN (kcal, km / mi), not the stored kJ / m —
  nice steps in kJ read as "39 / 78 kcal" (found in the browser). ⚠ Tone classes are written out in
  full (`TONES`): a runtime-built `stroke-${tone}` is never generated by Tailwind, and the HR and
  elevation lines drew nothing (found in the browser, invisible to jsdom). The readout is announced
  (`aria-describedby` + a polite live region), and ←/→/Home/End/Escape walk it.
- **F2** `lib/streams.ts`: haversine distance, per-km (per-mile) splits with interpolated boundaries
  and a ≥ 0.1-unit partial, ascent with 3 m hysteresis both ways (GPS noise is not a hill), pace over
  a 30 s trailing window with pauses as GAPS — slower than 20 min/km or than twice the workout's median
  (the slow metres around a stop stretched a 5 min/km axis to 20). One cursor (seconds) for every chart
  and a `cursor` pin on the map; the map's camera and line are memoised on the route, so it never
  re-fits under the pointer. Wide: charts left, the map sticky on the right.
- **F3** Home = native `HomeScreen`: hero active-minutes ring ("N min to go", WHO line), the steps
  ring, a stat strip opening its metric, four 7-day cards (energy, steps, active minutes, distance;
  the change vs last week on each — native shows it on active minutes only), the latest workout
  (under History's query keys, so its change signal refreshes it). ONE 14-day read for the page.
  The metric page is a route, `/metric#m=<metric>&r=<range>` (registry section `metric`; the sidebar
  keeps Dashboard lit; a range replaces in place, the page itself is a history entry).
- **F4** `lib/historyRows.ts`: a week summary (Monday-start, LOADED workouts) and a day header
  (`startLocal`'s date — the reader's zone, as the bridge asks) between the rows, each kind at its own height; the window is
  a binary search over the rows' tops (`lib/virtual.ts` retired).
- Verified in a browser on a generated library (a harness over `lib/proxy`): Home, the metric page's
  year, a workout's charts with the shared cursor, splits, History's grouping. Not on a real realm.
- **Review fixes (one subagent):** ⚠ map-kit updates its pins and polylines sources in SEPARATE
  effects — one effect re-uploaded a whole route's GeoJSON on every pointer move of the chart cursor
  (`TenshuMap.test`: a pin change sets the pins source only); `RouteMap` rebuilds its pins only when
  the cursor reaches another route point. The series charts share the WORKOUT's duration as their time
  axis (HR outlasting GPS put one moment at two x's); the average is always on the axis (pace's,
  stops included, fell below the plot); a pause breaks the line (`gapSec`) rather than bridging it; a
  flat series gets distinct gridlines; a split's average HR counts a boundary sample once; History's
  day headers are read again, and each row carries its date for a screen reader.
  Known: pace and elevation axes in imperial are nice steps in s/km and m, converted (7:30 / 8:02 /mi).


# Place imagery — a representative photo for a place that has none of yours

**Status:** v0.1 — **design, no code (2026-07-19).** How a `Place` gets a picture when the user has
taken none of their own. Companion to [`place-geocoding.md`](place-geocoding.md) (which turns a
coordinate into a *name* — this doc turns it into a *picture*), [`timeline-services.md`](timeline-services.md)
(the `Place` spine both Maps and Timeline share), and [`timeline-movement-capture.md`](timeline-movement-capture.md)
§11.3 P1 (the map hero this layers on top of). Reuses the **proxy-mediated egress** pattern P3
established (Control does the network, the app has no `INTERNET`).

**Trigger (2026-07-19):** the place-detail hero was a flat colour; P1 replaced it with a map of the
place. The follow-up question — *"can we show an actual photo of the venue, like Foursquare / Google
Maps, when we don't have a user photo? Both Maps and Timeline would benefit."* — is a real gap, but
the *provider* choice is load-bearing and must be settled before code.

---

## 1. The one framing that drives everything: license, not API

Just like place **naming** (`timeline-services.md` §5), place **imagery** splits by **license + ToS**,
and that split — not which API is nicest — decides the whole design:

| Family | Examples | Storable? | Fit |
|---|---|---|---|
| **Open, redistributable** | **Mapillary** (CC-BY-SA street-level), **Wikimedia Commons** (CC POI photos), Overture-linked `wikimedia_commons` / `image` OSM tags | ✅ store freely (sealed blob) | **Tier 1 — the default** |
| **Restricted, no-store** | **Google Places Photos**, **Foursquare** place photos | ❌ ToS forbids caching the media; billed per request | **Tier 2 — opt-in, transient only** |

This is the same line the platform already drew for names: *"OSM/Overture/Foursquare is the only
license family that lets you store the answer freely; Google/Apple ride as isolated foreign keys,
never their content"* (threat-model A35, `timeline-services.md` §5). **Storability is the crux**,
because an image you cannot store cannot be sealed, cannot go offline, and re-fetches (and re-bills)
every view — so it can never be the *default*.

### 1.1 Coverage is the honest tension
Open sources and restricted sources have **opposite coverage**:
- **Wikimedia Commons** is dense for **landmarks** (the Frauenkirche, a famous café) and empty for an
  everyday venue (a random coworking space, a corner Döner).
- **Mapillary** has **street-level** coverage in cities — a photo *of the street outside* the place,
  not a curated venue shot, but present far more often than Commons.
- **Google/Foursquare** have **dense curated venue photos** — the actual coworking lobby — precisely
  where the open sources are empty.

So the tiers are complementary, and **the map hero (P1) remains the always-available floor** for the
common case where no photo exists at all. Place imagery is an *enhancement*, never a dependency.

---

## 2. The resolution ladder (what the cover shows)

For a place's cover / hero, in priority order:

1. **The user's own photos** — Drive file refs (`coverPhoto`/`photos`, the planned Photos-app linking).
   Always wins; never overwritten (mirrors the geocoding blank-only rename rule).
2. **Tier 1 — an open, storable image** (Mapillary / Wikimedia), fetched via Control, **cached as a
   content-plane blob** → offline, no re-fetch. On by default *if* place-enrichment consent is granted.
3. **Tier 2 — a restricted image** (Google / Foursquare), **only behind an explicit extra opt-in**,
   **proxied transiently** (fetch-on-view, never persisted — honours the no-store ToS). Richer venue
   coverage where Tier 1 is empty.
4. **The map hero (P1)** — the offline, license-clean default when nothing above resolves.
5. **A per-place tint** — the final fallback (a colour derived from the placeId), never the same blue.

Both **Maps and Timeline** get this for free: they render the *same* `:place-service` place detail
(`maps-app.md` §4 — "one place, one record; Timeline & Photos embed the same view"), so the cover
resolves in one shared place.

---

## 3. Architecture — reuse the P3 proxy-mediated seam

The app has **no `INTERNET`** (deliberately). So, exactly like P3 geocoding, imagery egresses from
**Control**:

- A proxy-mediated op on `:map-region-service` (the "Control-does-map-egress" service), e.g.
  **`FetchPlaceImage(latE7, lngE7, externalRefs?) → { contentRef | bytes | none }`** — mirroring
  `ReverseGeocode`. Control resolves + fetches; the app never touches the provider.
- **Tier 1 (storable):** Control fetches the CC image, the app stores it in the **content plane**
  (sealed blob, like `MovementTrace.polyline` / `Workout.Streams` / file content — [`file-content-addressing.md`](file-content-addressing.md)),
  referenced from the `Place` (a new `Place.coverImage` content ref, or a sibling `PlaceImage` record).
  Cached → offline, one fetch per place.
- **Tier 2 (no-store):** Control fetches and returns the bytes **transiently** — the app displays,
  **never persists**. A `transient = true` flag on the op result; no content-plane write.

**Provider resolution (coords/ref → image):**
- *Wikimedia:* the OSM/Overture `wikimedia_commons` / `image` tag on the nearest feature, or a
  Commons geo-search; needs the place's `externalRefs` (the GERS/OSM id) — the same foreign-key the
  geocoding rail already wants (`place-geocoding.md` §"`externalRefs` storage", currently deferred —
  **this doc gives that deferred field a second consumer**).
- *Mapillary:* nearest image to the coordinate via the Mapillary API (coords only, no ref needed).
- *Google/Foursquare (Tier 2):* by their place id (from `externalRefs`) → their Photos endpoint.

---

## 4. Privacy & threat model (House Rule #2)

- **Egress via Control, never the relay** (Control ≠ relay; the relay never learns a location — HR #1),
  under a **place-enrichment consent** — reuse/extend `PlaceGeocodingControl` (it already gates the
  coordinate→name egress) into a shared control, or a sibling `PlaceImageControl`. Off by default.
- **Tier 2 is a *separate, explicit* opt-in** — it is a stronger commitment: it sends the place to
  Google/Foursquare *and* (see §5) costs money. Consent copy must say what leaves the device and to whom.
- **Cached images are open-license content** (Tier 1) — safe to seal on the content plane like any
  file. Tier 2 images are **never** written to disk (ToS + privacy).
- Threat-model **A35 amendment** lands with the code: place-imagery egress (Tier 1 cached, Tier 2
  transient), consent-gated, off the relay. Mirrors the P3 geocoding amendment.

---

## 5. The two decisions that gate Tier 2 (⚖️ need a call, not code)

Tier 1 is clean and buildable. **Tier 2 (Google/Foursquare) carries two unresolved commitments:**

1. **ToS.** Google Places Photos ToS restricts caching (no-store — handled by transient proxy) but may
   also restrict **server-side proxying / non-Maps display / require attribution+branding**. Foursquare
   has its own terms. Whether a proxied, transient, attributed display is *permitted* is a **counsel /
   ToS-review question** — the transient-proxy shape is the *intended* design, gated on it being allowed.
2. **Billing.** Google Places Photos is **billed per request**. Who pays?
   - *Operator-pays:* Tenshu foots every user's image fetches → cost scales with usage.
   - *Bring-your-own-key:* an advanced setting where the user supplies a Google/Foursquare API key —
     zero operator cost, keeps it strictly opt-in, but niche.
   - Recommendation: **BYO-key first** (no operator cost, honest opt-in), operator-pays only if it
     becomes a funded product decision.

Because of both, **Tier 2 is explicitly a later, gated phase — not a launch requirement.**

---

## 6. Attribution

All candidate sources require attribution: **Mapillary** CC-BY-SA, **Wikimedia** CC-BY/BY-SA (per
file), **Google/Foursquare** their branding. The cover must carry an unobtrusive attribution +
license line (author + license for CC; provider badge for Tier 2). Store the attribution string with
the cached Tier-1 blob so it stays offline.

---

## 7. Phasing (each independently shippable)

1. **PI-1 — Tier 1 open imagery, cached (no device secrets).** `FetchPlaceImage` op (Control egress),
   Wikimedia-first (landmarks) + Mapillary (street-level) resolution, content-plane cache +
   `Place.coverImage` ref, attribution line, place-enrichment consent, the §2 resolution ladder in the
   shared place-detail cover. Threat-model amendment. **This is the buildable, license-clean win.**
2. **PI-2 — Tier 2 opt-in restricted imagery.** Transient proxy for Google/Foursquare behind a
   separate opt-in, **BYO-key** default, attribution/branding. **Gated on the §5 ToS + billing calls.**
3. **PI-3 — refinements.** Photo picking (which of N Commons/Mapillary images), a "wrong photo /
   remove" control (mirrors rename), Photos-app user-photo linking as Tier 0.

PI-1 makes landmarks and street-level places show a real image; PI-2 fills everyday-venue coverage for
users who opt in; the map hero covers the rest, always.

## 8. Open questions

- **`externalRefs` first.** Wikimedia/Google/Foursquare resolution needs the place's foreign-key id,
  which `place-geocoding.md` deferred (ref-identity decision). PI-1 can ship **Mapillary-only** (coords,
  no ref) to unblock, adding Wikimedia once `externalRefs` lands.
- **Where the cover ref lives** — a `Place.coverImage` content field vs a sibling `PlaceImage` record
  (the `MovementTrace`-vs-field call again; §2.1 of the movement doc). Lean sibling record (keeps the
  heavy blob off the `Place` row + a spot for attribution/source/license).
- **Refresh / staleness** — a cached Tier-1 image is effectively permanent; do we ever re-fetch? (A
  venue closes, Commons gets a better photo.) Probably manual "refresh photo," not automatic.
- **Content-plane for Timeline** — Timeline runs standalone; the content-plane seam it would cache into
  is the same one movement-capture §4 flagged as not-yet-wired. PI-1 caching may need that seam first,
  or fetch-transient (no cache) as an interim on Timeline.

## 9. Version

Place-imagery v0.1 · design 2026-07-19. Companion to `place-geocoding.md` (naming), `timeline-services.md`
(the `Place` spine), `timeline-movement-capture.md` §11.3 (the map hero this layers on), and
`file-content-addressing.md` (the content plane Tier-1 caches into). Reuses the P3 proxy-mediated
egress + `PlaceGeocodingControl` consent. No code.

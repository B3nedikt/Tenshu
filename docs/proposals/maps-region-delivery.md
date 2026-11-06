# Maps region-bundle delivery — the "tile server" that deliberately isn't one

**Status:** v0.4 — **largely BUILT** (was "proposal, no code" at v0.1–v0.4 authoring, 2026-07-10/12).
The backend is deployed and serving, and **Ph0–Ph3c have landed and are on-device verified**: all three
`:ui-map` consumers (Maps place pins, Timeline trip routes, Fit workout routes) render the **D10 streamed
default** — fetching only the covering tiles into a sparse `.pmtiles` over `streamRegionRange`
(app → Control → CDN), with House Rule #1 intact (no map app holds `INTERNET`). Control's *Offline maps*
management surface has shipped. **→ For what is actually left, read [§7.1 Remaining work](#71-remaining-work--consolidated-backlog-2026-07-14)** — the single consolidated backlog; the per-phase
"Outstanding" notes in §7 are frozen history.

Opened from a maintainer discussion on productionising the basemap delivery pinned (but not specified) by
[`maps-app.md`](maps-app.md) §3 as "can ride the existing content-delivery machinery" — a phrase no doc
actually defines. This doc *is* that machinery: it specifies how offline PMTiles region bundles are built,
published, gated, and downloaded. (v0.2 folded in the client-side discussion: **proxy-mediated delivery,
D6**, resolving the v0.1 egress question Q6.) Threat-model deltas in §5 are proposal-level and land with
the implementing phase per House Rule #2.

**v0.4 (2026-07-12) reframes the delivery default** on maintainer UX grounds: a multi-GB
whole-region download before first use turns users away, so **streaming becomes the default and
whole-region prefetch the privacy/offline opt-in** — one mechanism (the range-provider), two ends
of a spectrum. This adds **D10** and *supersedes the D1 anti-decision* against live remote range
sources; it revises **R1** and the §5 threat model (a new viewport-pattern surface + a
streamed-vs-full integrity delta). See D10 for the reversal and its named cost.

Companion reading: [`maps-app.md`](maps-app.md) (§2 D1 sequencing, §3 basemap architecture,
§6 Ph7b as-built + Ph9), [`timeline-app.md`](timeline-app.md) §"Map surfaces" (`:ui-map`
consumers), [`commerce-billing-plane.md`](commerce-billing-plane.md) (§3 D3 token privacy
gradient, §3 D5 entitlement-is-the-only-notion-of-paid, §4 token mechanics, §8 phasing),
[ADR 0019](../adr/0019-storage-capacity-gating.md) (entitlement vs provision split),
[`sync/threat-model.md`](../sync/threat-model.md),
[`drive-streaming-preview.md`](drive-streaming-preview.md) §3.5 (the pull range-source —
the app↔proxy bulk-transfer contract D6 reuses), whitepaper §"Terminology" + §"Direct /
External Proxy Connection" (the component model D6 instantiates).

---

## 1. Goal & scope

Ship the **production delivery path for offline basemap region bundles**: the pipeline that
turns OpenStreetMap data into per-region PMTiles extracts, the hosting that serves them, the
manifest the client browses, and the client-side download/storage/update flow behind
`:ui-map`. This unblocks the two things Ph7b explicitly deferred (`maps-app.md` §6 Ph7b
as-built decision 4): real per-place centring over a region the user actually lives in, and
replacing the bundled ~1.3 MB Firenze demo extract with user-chosen regions.

**In scope:**

- Build pipeline: Planetiler planet build → per-region `pmtiles extract` set → publish.
- Hosting: static object storage + HTTP range serving, CDN-fronted (§3 D1).
- A signed **region catalog manifest** the proxy fetches and apps render as a region picker.
- Client-side delivery: **proxy-mediated download** (D6), the app↔proxy `RegionService`
  interface, region store, verification, update, evict (D5/D6, §4).
- **Entitlement gating** via an audience-scoped, ownerless download voucher (§4) — the
  commerce plane's blinding-ready token machinery reused, not a new auth system.

**Out of scope (unchanged from `milestones.md` → *Out of Scope → Maps full*):** live tile
serving to a panning client, routing, turn-by-turn, POI/search depth, privacy-preserving
prefetch. Also out of scope here: geocoding (P4, [`timeline-services.md`](timeline-services.md)),
delta/differential region updates (v2, §7 phase 5), and the world-overview bundle sizing (§8 Q2).

**The one-sentence architecture:** there is still **no bespoke tile server** — serving stays
static object storage + CDN range requests, zero serving compute (D1). But delivery is now a
**streamed-default spectrum (D10):** by default the proxy range-fetches tiles on demand and caches
them; "make available offline" prefetches the whole region (the original Organic Maps model, now
the privacy/offline opt-in). The app still never gains `INTERNET` — the egress is the proxy's (D6).
The honest cost of the reframe: streaming lets the tile host observe a coarse viewport pattern by
IP (D10, §5), which whole-region-only avoided; low-zoom prefetch, local caching, and offline mode
are the mitigations.

---

## 2. Requirements

R1. **App boundary stays House-Rule-#1-clean; egress is the proxy's.** The OS-boundary property
    Ph7b enforces (`tools:node="remove"` on `INTERNET` in `maps-android`) must survive delivery in
    *both* modes: map apps never gain `INTERNET` (D6), and MapLibre only ever opens a local fd.
    What changed in v0.4 (D10): the *proxy* now has ongoing tile egress by default (streamed on
    demand + a low-zoom prefetch), where v0.1–v0.3 had a single whole-region fetch. **Offline mode**
    (opt-in prefetch) restores the old property — zero further egress once a region is resident.
    Streaming egress is never the *app's*; the `INTERNET`-stripped render check stays a standing
    test (§5).

R2. **No account ↔ region join at infra.** A region download is a coarse location-interest
    signal. Neither the tile host alone, nor commerce alone, nor (target state) both joined
    may link *person → region* (§4; mirrors `commerce-billing-plane.md` D3).

R3. **Abuse-resistant.** The bundles are ODbL open data, but the egress is ours. Gate
    downloads on a subscription-derived voucher + per-voucher rate limits so the bucket
    can't be farmed as free PMTiles hosting.

R4. **Verifiable.** Region bundles and the manifest are integrity-protected end to end
    (sha256 in a Tenshu-signed manifest), so a compromised CDN cannot serve tampered tiles.

R5. **Cheap and boring.** Static files, no compute on the serving path, ops cost in the
    tens of dollars per month at current scale (§6).

R6. **Consumable by every `:ui-map` client.** Maps (place detail, map-of-all-places),
    Timeline (trip polyline, visit moments), Photos (place linking) — one network download
    into one canonical (proxy-owned) store, consumed by every embedder (D2 in `maps-app.md`
    §4 already establishes the one-place-one-record posture; this extends it to
    one-region-one-download).

---

## 3. Decisions

### D1. Serving = static object storage + HTTP range requests behind a CDN. No serving compute.

PMTiles is designed for this: a bundle is one file, resumable via range requests, cacheable
by any CDN. The serving path is a bucket + CDN + (only for gating, §4) a thin stateless
gateway that never touches tile bytes.

- **Provider choice is now cost-profile-driven (v0.4).** Whole-region-only delivery (v0.1–v0.3) was a
  few large, edge-cacheable downloads — DO Spaces' $5/mo (250 GB + 1 TB egress), S3-compatible, matched
  the existing footprint (`deploy/digitalocean-commerce/`, relay how-tos) with ample headroom. **Streaming
  (D10) changes the profile:** a *public* endpoint serving many small ranged GETs is exposed to **economic
  denial-of-wallet** — crawlers, bots, and hotlinkers running up **egress**. Not data theft (it's open
  data) and not availability (the CDN absorbs volumetric floods); the *bill* is the risk, and on a
  per-egress-billed origin it is unbounded.
- **So the streaming-era default is Cloudflare R2 + Cloudflare CDN** ($0.015/GB-mo storage, **zero
  egress**). This collapses the denial-of-wallet threat: bytes a crawler pulls cost ~nothing, the edge
  cache serves immutable dated bundles so origin is barely touched, and Cloudflare's per-IP rate-limiting +
  bot rules blunt crawlers **without** account gating (Phase-3 gating is fairness, not the cost answer —
  and D10 notes it can't cleanly gate *streamed* tiles anyway). DO Spaces stays valid for a
  whole-region-only or low-traffic deployment. The client contract (signed catalog + ranged GET) is
  provider-neutral by construction, so this is an ops choice, not a client change.
- **2026-09-14 — built on R2, and chosen over Scaleway.** The backend was leaving DigitalOcean for
  Scaleway, and Scaleway **Edge Services** was the obvious like-for-like. It lost on the two things
  this decision turns on. First, it is **per-GB billed**: the Starter plan is €0.99/mo with 100 GB of
  cache egress, then €0.0135/GB, and Object Storage egress is €0.01/GB past 75 GB. The
  denial-of-wallet exposure above comes straight back. Second, its **`Range` behaviour is
  undocumented**, and PMTiles is unusable without ranges. R2 is zero-egress, and Cloudflare documents
  206s for ranged requests. Module + copy: [`deploy/cloudflare-tiles`](../../deploy/cloudflare-tiles/).
  Runbook: [`how-to/move-tiles-to-cloudflare.md`](../how-to/move-tiles-to-cloudflare.md).
  The costs, named:
  - ⚠⚠ **`tenshu.app`'s nameservers move to Cloudflare.** An R2 custom domain needs the zone in the
    same account, and the CNAME-only (partial) setup is Business-plan.
  - ⚠ **The edge cache is mostly not in the path.** The Free plan caches nothing over 512 MB (four of
    the six 2026-08 bundles), and caches by extension, which leaves out `.pmtiles`. The bullet above
    says *"the edge cache serves immutable dated bundles so origin is barely touched"*; on Free that
    is false. Ranged reads go to R2 at zero egress, billed as Class B operations ($0.36/M past 10M/mo).
    The upside: the mutable `catalog.json` is never stale at the edge.
  - ⚠ **A two-level name.** `tiles.service.tenshu.app` is outside Universal SSL's `*.tenshu.app`, and
    R2's docs don't say whether a custom domain gets its own certificate. The runbook measures it on
    a sibling name before the swap.
  - A third provider. The hostname stays the same, so there is no client change and no release.
- **Host + hotlink posture.** Host: `tiles.service.tenshu.app` per the `*.service.tenshu.app` convention
  ([`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md) §4.5). Hotlink deterrence
  (Referer/Origin rules) is compatible with the whitepaper's "anyone can mirror" value (D6): the open data
  being *mirrorable* is a feature; *your endpoint* hotlinked as someone else's free CDN is the abuse. A
  mirror-runner pulls the bundles once to seed their own store (cheap, fine); continuous pan-time
  hotlinking of your endpoint is what the edge rate-limits.

Anti-decision worth recording: **no live `pmtiles://https://…` sources in clients.** Range
requests against a remote planet file at pan time is a de-facto tile server — every viewport
move leaks position to infra. The remote archive is download-only; MapLibre only ever opens
`pmtiles://file://…` (the Ph7b pattern).

> **Partly superseded by D10 (v0.4).** The maintainer reversed the *default* on UX grounds:
> streaming is now the default. The narrow form of this anti-decision survives — **the map apps
> still never open a remote source**; MapLibre still only sees a local `pmtiles://file://` fd.
> What changed is that the *proxy* may satisfy that fd's range reads from a remote on-demand
> source instead of a fully-downloaded file (D10). The "de-facto tile server" concern it named is
> real and now *accepted as a named cost* (the viewport-pattern leak), mitigated by low-zoom
> prefetch + offline mode — not avoided outright as v0.1–v0.3 chose.

### D2. Build pipeline = Planetiler → planet PMTiles → `pmtiles extract` per region, published as immutable dated builds.

- A scheduled batch job (rented large instance, hours, ~monthly to quarterly — cadence is an
  ops dial, §8 Q3) runs Planetiler against the OSM planet, producing one planet PMTiles
  (~100–120 GB at the Protomaps basemap profile). Initial builds may seed from Protomaps'
  published planet builds to decouple the client work from pipeline work.
- Per-region extracts are cut with `pmtiles extract` against a **region definition file**
  checked into the repo (bbox/polygon + id + display name + locale names). Granularity:
  country-level, with sub-country splits only where the country file is unreasonably large
  (Germany, France, US states…) — the Organic Maps precedent, §8 Q1.
- Builds are **immutable and dated**: `regions/2026-07/de-bayern.pmtiles`. The manifest
  (D3) points at the current build per region. Old builds are retained one cycle for
  clients mid-download, then GC'd (storage is the cheap dimension, but planet-scale
  duplicates add up).
- The extract keeps the **full zoom range of the basemap profile within the region**
  (z0–z15-ish); the z12–14 clip that made the Firenze demo 1.3 MB was a demo-only trick —
  production surfaces (pannable map-of-all-places, Timeline trips) need the whole band.
  Expected sizes: big city tens of MB, mid country hundreds of MB, large country a few GB.

### D3. A Tenshu-signed region catalog manifest is the client's only discovery surface.

One JSON document (`catalog.json`), fetched unauthenticated (it is public metadata; gating
it buys nothing and breaks the region picker for lapsed users who should still see what
exists), containing: schema version, build date, and per region — id, names, coverage
polygon (coarse, for "which region am I looking at" UX), byte size, sha256, tile schema
version (Protomaps v4 today), and path.

- **Signed** (detached signature, Tenshu release key — same trust anchor family as app
  artifacts) so a CDN/bucket compromise cannot redirect clients to tampered bundles; the
  client verifies the manifest signature, then the bundle sha256 after download (R4).
- Tile **schema version is load-bearing**: the runtime style JSON in `:ui-map` is built
  against Protomaps v4; a schema-bumping build must not be picked up by clients whose style
  doesn't speak it. The client filters the catalog on `schemaVersion <= supported`.

### D4. Gating = an audience-scoped, ownerless download voucher; blinding is the upgrade path, not the blocker.

The maintainer call (2026-07-10): **yes, gate region downloads on the subscription** —
included in every paid tier (and a one-active-region starter allowance on Free, D9), enforced primarily as
abuse prevention, since the marginal cost is low (§6). The design constraint is R2: the
naive "present your entitlement token" leaks *ownerId → region* to the tile host, exactly
the join `commerce-billing-plane.md` D3 spends its strong variant preventing.

Mechanics, mirroring the D3 gradient:

- **Ship (minimal): the ownerless voucher.** The client asks commerce (authenticated by its
  commerce session, exactly like the billing-plane §4 token pull) for a **tiles voucher**: a short-lived
  signed token, `aud=tiles`, asserting only `tier` + `expiry` + a random voucher id —
  **no `ownerId`**. Nothing at the tile host needs owner binding: there is no per-owner
  quota to meter (unlike relay storage, ADR 0019); rate limits key off the voucher id.
  The tile gateway verifies signature/expiry/audience and answers with a time-limited
  presigned URL (or `302`) for the requested bundle; the download itself is a plain ranged
  GET against storage/CDN. Result: commerce knows "a subscriber asked for a tiles voucher"
  (not which region); the tile host knows "a valid voucher fetched region X" (not which
  account). One plane alone learns nothing linkable.
- **Honest limit, stated up front:** if commerce logs voucher-id ↔ account and the gateway
  logs voucher-id ↔ region, a subpoena reaching *both* planes joins them. Two mitigations
  ship with the minimal variant: (a) **voucher ids are not written to durable logs** on
  either side (rate-limit state is in-memory/TTL'd), and (b) the token format is already
  blinding-ready (`commerce-billing-plane.md` §8 phase 1), so the strong variant below is
  an issuance-only upgrade.
- **Target (strong): blinded voucher issuance**, riding whatever blinding lands for the
  storage entitlement (D3 strong). When commerce signs vouchers blind, not even both planes
  colluding can join person → region. This upgrades token *issuance* only; the gateway and
  client contract don't change.
- **Enforcement posture mirrors ADR 0019 / billing-plane §8:** stand the gateway up
  verifying but **gate nothing at first** (accept a permanent free-tier voucher), then turn
  on enforcement with typed errors (`402 subscription_required`, `429 rate_limited`) once
  the client flow ships. A lapsed subscription degrades gracefully per billing-plane D5:
  already-downloaded regions keep working forever (they're on-device files; revoking them
  would be both hostile and impossible), only *new* downloads require a live voucher.

IP-level traffic correlation remains out of scope, same caveat as billing-plane §7 —
say it, don't solve it in v1.

### D5. Client ownership: the proxy owns the canonical region store + download manager; `:ui-map` is the app-side terminus; Maps owns the picker UX.

- **The proxy (Control / the Tenshu App) owns the canonical region store and the download
  manager** (resumable ranged GET, wifi-only default, background-capable via the
  [`core-background-processing.md`](core-background-processing.md) machinery), plus the
  catalog cache and all signature/sha256 verification. Rationale and protocol shape in D6 —
  the short version: the proxy is the component that already has egress, so map apps never
  need `INTERNET` at all.
- **`:ui-map`** (already the shared basemap engine, Ph7b) grows the **app-side terminus**:
  the `RegionService` client (D6), receipt of a bundle over the IPC pull range-source into
  the app's `filesDir` (MapLibre needs a real `pmtiles://file://` path — the Ph7b pattern),
  and per-lookup resolution: bundled demo extract (dev/fallback) → local region copy. The
  Ph7a schematic remains the universal no-region fallback, so no surface is ever blank — a
  place detail without its region renders the schematic + a "download {region} for a real
  map" affordance.
- **Maps owns the region-picker UX** (a Settings/onboarding surface listing the catalog,
  sizes shown honestly, one-tap download/delete) — consistent with Maps owning the canonical
  place surfaces (D2, `maps-app.md` §4). Timeline/Photos never ship their own picker; if a
  trip needs a region the user lacks, they deep-link into Maps' picker. The picker is pure
  UI over the `RegionService` — the network state, progress, and store live proxy-side, so
  every embedder sees the same install state.

  > **Amended (v0.4).** With Timeline **and** Fit now sharing the basemap (three `:ui-map`
  > consumers, not just Maps), map regions are **shared infrastructure with no single owning
  > app**. So the **canonical offline-maps management moves to Control** — the store owner (D6),
  > always-present and neutral across apps: a dedicated **Offline maps / cached data** surface
  > (list resident regions + sizes + stream-vs-offline default + delete). That is a
  > **data-availability** surface, *distinct* from the account/entitlement **Services** surface
  > ([`control-services-surface.md`](control-services-surface.md)) — two Control surfaces, two
  > axes. Apps keep **contextual download triggers** over the same shared store (Maps "download
  > the region you're viewing" + its onboarding picker; Timeline "make this trip's regions
  > offline"), but Maps is no longer the *privileged* owner, and a Fit-only user manages map data
  > in Control without ever opening Maps.

**As-built (slice 2c — terminus + picker + client graph, 2026-07-11; desktop-verified, on-device pending).**
The app-side of D5, over the shipped 2a service surface (`catalog`/`installed`/`deleteRegion`):

- **`:ui-map` terminus** — `OfflineBasemap` gained an additive `regionBundlePath: String?` +
  a pure `resolveBasemapSource(...)` seam (region → bundled demo → schematic; never blanks).
  Android opens a downloaded `.pmtiles` via `pmtiles://file://` (Ph7b staging pattern);
  desktop stays schematic. `ResolveBasemapSourceTest` (5) green.
- **Maps' first client graph** — new **`:maps-shared-data`** with `InProcessMapRegionProxy`
  (mirrors Timeline's `InProcessTimelineProxy`, P3b): hosts `:map-region-service` in-process over
  a **`MockRegionCatalogSource`** (DACH dev set, D7) + an in-memory store, exposing a
  `RegionRepository`. No inter-app IPC / control proxy yet — the pragmatic bring-up graph; the
  on-device swap points the same seam at the real Control proxy over IPC (D6). `ClientRegionRepository`
  joins `catalog()` × `installed()`; `RegionPickerPipelineTest` drives it end-to-end (green).
- **Picker UX** — `RegionPickerViewModel` (`:maps-shared-logic`) + `RegionPickerScreen`
  (`:maps-shared-ui`), reached from the Places top-bar settings button (a new `Regions`
  destination). Browse with honest sizes, installed badge, one-tap **delete**; **download is a
  documented seam** (`RegionRepository.download` returns `false` → a "coming soon" snackbar)
  because the download op is not in the current base — it wires to `downloadRegion` when that
  lands. Whole Maps app compiles on desktop.
- **Smoke gate** — `scripts/android-smoke.sh` now covers `:maps-android` launching with
  `INTERNET` stripped (the Ph7b standing regression, §5/§7/§9).

**Deferred:** the real proxy-side DI wiring (construct `HttpRegionCatalogSource`/`DefaultRegionStore`
in Control's `PlatformProxyModule` + IPC to the map app) and the threat-model egress amendment —
both gated on Phase 1 (a published `catalog.json` + a dev signing key to point at); the download op
itself; and the on-device render pass (desktop/emulator).

**As-built (2026-07-11→12, since 2c).** The deferred proxy-side wiring landed: `mapRegionComponents`
(real `HttpRegionCatalogSource`/`DefaultRegionStore`/`DefaultRegionDownloader`) wired into Control's
`ProxyModule` and the region path switched to real client→Control **IPC** (D6) — Maps' first
cross-process client graph. **Second `:ui-map` consumer:** Timeline's `RouteBasemap` now
renders a geographically-precise trip route over the offline basemap — reusing `OfflineBasemap`'s
internals, still on the bundled Firenze demo (`regionBundlePath = null`). Two consequences for the D10
terminus (§7): the region source must thread through **both** `OfflineBasemap` and `RouteBasemap`; and
a Timeline trip route can span multiple regions, which promotes a **viewport→region resolver** into the
terminus from the start (not a later refinement).

### D6. Delivery is proxy-mediated; the Tenshu binary format is used on the app↔proxy leg only.

This instantiates the whitepaper's component model (client apps reach external data **only
through the proxy**; a platform capability that bypasses it is a red flag) — and it is what
makes the Ph7b hardening durable: `maps-android` / a future Timeline shell keep `INTERNET`
stripped at the OS boundary forever, because they never talk to the network. The v0.1 open
question "which component performs downloads" is resolved: the proxy does.

Per-leg protocol, deliberately non-uniform:

| Leg | Carrier | Why |
|---|---|---|
| app ↔ proxy | **Tenshu schema/IDL binary** over the existing IPC (AIDL / UDS, 4-byte framing) | A `RegionService` is a service interface like `:place-service`: typed calls (`catalog()`, `download(regionId)`, `installed()`, `delete(regionId)`), progress as a stream, gated by a semantic permission ("may request map regions"). Bulk bytes ride the **pull range-source** ([`drive-streaming-preview.md`](drive-streaming-preview.md) §3.5) — backpressured + seekable, built for exactly this |
| proxy ↔ commerce (voucher) | HTTPS + JSON, commerce-session auth | Follows the shipped precedent (the Ph4 issuer at `commerce.service.tenshu.app` speaks JSON; the relay speaks HTTPS + JWT). An IDL manifest with an HTTP binding is fine conceptually; a new binary session protocol for one GET buys nothing |
| proxy ↔ tile host (bundle) | **Plain HTTPS ranged GET** against presigned URLs (D1) | **Explicitly not the Tenshu binary format.** PMTiles *is already the binary format*; wrapping it would forfeit range/resume, CDN cacheability, presigned URLs, and zero-compute serving (R5). Not a whitepaper violation: the IDL exists to put typed, permission-checked interfaces around *user data flowing between components* — the tile host carries no user data and exposes no per-user interface. It is content distribution (the app-artifact category), not an external *service*; trust comes from the signed manifest + sha256 (R4), not the channel format |

Two consequences worth recording:

- **The alternative-provider promise gets cheaper, not weaker.** The whitepaper lets a user
  pick alternative external service providers; a boring open contract (signed catalog +
  static ranged HTTP) means anyone can run a region mirror without adopting the Tenshu
  stack. A binary wire here would raise the bar for exactly the interoperability the
  whitepaper wants.
- **iOS deviates, by design.** Proxy mediation assumes a *local* proxy (Android Control;
  the desktop user-session daemon). Where the proxy sits across an EPC network hop,
  hairpinning a GB-scale bundle through it is a non-starter — on iOS the app fetches
  directly from the CDN with a **proxy-issued voucher**, and enforcement falls back from
  OS-level permission stripping (which iOS doesn't offer anyway) to the semantic-permission
  manifest declaring the tile-host egress. Consistent with the whitepaper's direct-vs-external
  connection split; carries its own threat-model note when the iOS `:ui-map` actual lands
  (§7 phase 5).

### D7. Region granularity = country-level with sub-country splits; **borrow a proven polygon set, don't maintain our own** (resolves Q1).

Granularity is country-level (D2), with sub-country splits only where a country's extract is
unreasonably large (Germany by Bundesland, US by state, large countries subdivided) — the
Organic Maps precedent.

The polygon-curation call (2026-07-11): **seed from Organic Maps' `borders/` `.poly` files**,
do not hand-maintain a boundary set. Reasoning:

- OM's borders are Apache-2.0 (boundaries derived from OSM / ODbL) and are **tuned for exactly
  our use case** — whole-region offline download at reasonable sizes, the model D2 copies —
  and they already carry the sub-country splits above. Maintaining our own set is an open-ended
  boundary-tracking burden that buys nothing over a proven one.
- **Vendor the specific `.poly` files we cut into `deploy/tiles/regions/`** as a checked-in
  snapshot with provenance + attribution, rather than depending on a live OM/Geofabrik endpoint
  at build time — reproducible builds, no build-time external dependency. Geofabrik's polygons
  are the named fallback for any region OM doesn't split the way we want.
- **Starter set (phase 1):** DACH (DE split by Bundesland where large, AT, CH) plus one small
  city extract, chosen to exercise the small-→-large size band (tens of MB → low GB) against
  real `pmtiles extract` output before committing the region list.
- **Ongoing cost is near-zero:** the vendored snapshot is refreshed only when adding or
  re-splitting regions, never per build.

**Browsing that list — search + grouping (2026-08-11).** With the starter set the list is a flat
seven rows; the moment it is a real country set it is not browsable, so Control's *Offline maps*
gained a search field and continent sections. ⚠⚠ **The grouping is derived, not curated:** a
`RegionEntry` carries no continent, no country and no hierarchy, only the `coverage` box, so
`regionGroupOf` files a region by that box's **centroid**. That costs no catalog reshape, no re-sign,
no ops deploy and — since `RegionEntry` is a positionally-serialised composite — no House-Rule-#9
version bump, which is why it is the right first move. As built:

- ⚠ **Planet-spanning is checked before the centroid.** `global`'s centroid is `(0, 0)` — the Gulf of
  Guinea — so a plain centroid rule files *World* under **Africa**. Test-pinned, because it is exactly
  the kind of heading that is confidently wrong.
- ⚠ **Unplaceable is its own bucket, never a nearest-guess.** A row from the install index carries no
  coverage at all, so a catalog-unavailable list is rendered **ungrouped** rather than filing every
  region under *Elsewhere*.
- ⚠ Transcontinental countries land wholly on one side (Turkey and western Russia read as Europe), and
  open water inside a continent's span is attributed to it. Both are accepted and test-documented: the
  catalog only ever contains **land extracts**, so tightening the boxes would add error for regions
  that do not exist.
- ⚠ Search folds diacritics (`osterreich` finds `Österreich`) and matches the **id as well as the
  name** — the id is what the catalog, the logs and these docs call a region. The fold is a small
  explicit table, not Unicode normalisation, which `commonMain` has no access to.

**When to curate instead.** Adding real `continent`/`country` fields is a wire reshape of
`RegionEntry`: an owning-service version bump plus a `SchemaMigration`, per HR #9. Worth paying when
the catalog is large enough for the derivation's edges to matter — a country-level set where users
look for *Türkiye* under Asia, say — and not before.

### D8. Build cadence = quarterly, plus an on-demand manual trigger (resolves Q3).

**Quarterly** scheduled full builds. A basemap with no POI depth (POI/search + geocoding is
P4 — [`timeline-services.md`](timeline-services.md) §3.3 — a *separate* track) changes slowly:
roads, coastlines, and land use move on a scale where quarterly freshness is imperceptible,
and quarterly is ~3× cheaper than monthly (§6). An **on-demand manual trigger** covers the
rare "a region needs a correction now" case without standing up a faster schedule.

Builds stay immutable + dated (`regions/2026-07/…`, stamped with the build month); the prior
build is retained one cycle for clients mid-download, then GC'd (D2). Clients always resolve
the newest build per region through the catalog (D3); an update is the §4 atomic
download-then-rename.

### D9. Free-tier allowance = one *active* region free; the cap is enforced **proxy-side, not at the gateway** (resolves Q4).

**One active region free on Free tier** ("your home region works out of the box"); paid tiers
unlimited (subject to rate limits). Gating is a fairness/abuse control, not cost recovery
(§6) — egress lands in tens-of-dollars/month even at thousands of users — so the marginal
cost of one free region is negligible against the onboarding win, and the voucher's existing
`tier` claim (D4) already carries what's needed.

**The R2 tension, confronted (not papered over).** "One region on Free" is, on its face, a
per-owner quota — exactly the person↔region join D4/R2 spend their design preventing. The
gateway *must not* learn it, so the count is **not enforced at the gateway**. Resolution:

- **The proxy owns the canonical region store (D5) and is the only component that knows the
  install set — so the Free cap is a proxy-side check.** Before requesting a Free-tier voucher
  for a *new* region, the proxy verifies it is under the allowance locally. Commerce still
  gates voucher issuance on a live session; the gateway still verifies signature/expiry/`aud`
  and rate-limits by voucher id — it stays **ownerless and region-blind** (R2 intact).
- This is a *soft* limit for Free tier, and that is the correct strength given §6: a user who
  tampers with their own proxy to sideload a second free region incurs a cost of pennies, and
  Free is precisely where the incentive to cheat is lowest and the marginal cost smallest.
  Paid tiers are unlimited, so the cap only exists where hardening it would buy the least.
  **Accepted residual, stated:** the Free active-region count is client-enforced; the gateway
  cannot and deliberately does not corroborate it.
- **"Active" = currently installed:** deleting a region frees the slot. Already-downloaded
  regions keep working forever regardless of tier (D4 graceful degradation) — the allowance
  gates *new downloads* only, never on-device files.

### D10. Delivery is a streamed-default spectrum; whole-region prefetch is the privacy/offline opt-in. (Supersedes the D1 anti-decision.)

**The reversal, stated plainly (maintainer call, 2026-07-12).** v0.1–v0.3 mandated whole-region
download only, and D1 recorded an anti-decision against live remote range sources, because
per-viewport fetches leak position to infra. That default fails on UX: a multi-GB download before a
region is usable turns users away — including the privacy-conscious ones it was meant to serve. So the
**default flips to streaming**, and whole-region download becomes an explicit **privacy/offline opt-in**.
Crucially this is *not* a new mechanism — it is the two ends of the **range-provider** (the byte-transfer
decision, reused from Drive's streaming substrate, `drive-streaming-preview.md` S3/S4):

- **Streamed (default).** MapLibre's range reads hit a local fd (an app-internal `content://`→fd
  provider). The **proxy** resolves each covering range from its **tile cache**; on a miss it
  range-fetches the bytes from the CDN (a plain ranged GET into the *same* dated region PMTiles object,
  D2/D3), caches them, and returns them. Nothing downloads until the user looks; the map works immediately.
- **Offline (opt-in per region).** "Make available offline" prefetches the whole bundle — every range,
  eagerly — so all subsequent reads are served from disk with zero further egress. This is the old
  whole-region model, now a choice, reusing the **Drive offline-keeper verb** + the metered-network
  `ConstraintSyncGate` (wifi-only prefetch).
- **One mechanism.** The proxy's range source is either remote-on-demand or local-complete; MapLibre is
  unaware (it always opens a local `pmtiles://file://` fd). **The app still never gains `INTERNET`** — the
  egress is the proxy's, exactly as D6. R1's OS-boundary property survives in both modes.

**Low-zoom prefetch is on by default (settled 2026-07-12).** On first touch of a region the proxy
eagerly prefetches the low-zoom band (z0–~8 — a few MB) so the surface paints populated instantly and
only high-zoom detail streams on demand. Holds with *or* without the onboarding choice (§7).

**The cost this reverses D1 to accept (named, not hidden).** Streaming reintroduces the viewport-pattern
leak the D1 anti-decision avoided: the tile host can observe, by IP over time, which tiles the proxy
fetches — a coarse "where you're looking." Bounded — the egress is the proxy's not the app's; the tile
host is a separate public, zero-user-data plane (D6/R2) that never sees account or content; the observer
is a CDN, not the sync relay — and mitigated by low-zoom prefetch (coarsens precision), aggressive caching
(re-views make no request; a streamed user accrues a partial offline cache), and offline mode as the
zero-per-viewport-egress escape hatch. IP correlation stays out of scope (billing-plane §7). Threat-model
surface in §5; the amendment lands with the streaming phase (House Rule #2).

**Integrity delta.** The signed catalog's per-region sha256 (R4) verifies a *complete* file — it still
gates **offline** downloads, but cannot verify individual **streamed** tiles (PMTiles has no per-tile
hashes; these tiles are public plaintext, not AEAD-sealed like Drive chunks). Streamed integrity rests on
TLS + the signed catalog pinning *which* object is ranged into. Residual: a tampering CDN can serve a
wrong (MapLibre-parsed) tile while streaming; offline users keep the full-file guarantee. Accepted for
public render-only data; stated (§5).

**Interaction with gating (D4), flagged not solved.** Gating *whole-region downloads* is clean (one
voucher per bundle). Gating *streamed tiles* is not — a per-tile voucher check at the edge would make the
gateway see the viewport (worse than D4's region-blind posture), and v1 tiles are public/ungated anyway
(gating is Phase 3). Resolution deferred to the gating phase (§7 phase 3): likely gate the *offline
prefetch* and a streaming *session/rate*, never per-tile. This is also why a public streaming endpoint's
abuse/cost surface needs its own treatment (§6 + the ops phase).

**Onboarding + settings are later phases (§7).** v1 ships streamed-only with low-zoom prefetch and **no
new onboarding UI**. A later phase adds the two-choice onboarding (streaming preselected) and the "make
available offline" affordance (Maps settings first, then a Control manage-maps screen). The cross-service
"account ↔ connected services" surface is **out of scope here** — its own note.

### D11. The pipeline splits at the signature: bundle production is automatable, catalog signing never is. (Resolves "where does the publish actually run?", 2026-07-21.)

D2 defines *what* the pipeline produces and D8 *how often*; neither says **where it runs or which
machine holds which secret**. The v0.1 how-to answered by implication — one linear flow, on a droplet,
with the release key `scp`'d in — and that is wrong in a way worth recording, because it quietly
violates the release-key regime it cites (§5: "keep it offline").

**The split is at the signature, and it is nearly free because signing does not need the bundles.**
`catalog.json` is a few KB of region metadata + sha256 hashes; the signature is over those bytes. So the
expensive, automatable half and the trust-critical half touch different inputs and can live on
different machines:

| Stage | Inputs | Secret held | Where |
|---|---|---|---|
| Extract + upload bundles | planet PMTiles (GB–TB), `regions.json` | Spaces key — rotatable, scopeable to one bucket, blast radius = "overwrite a public bucket" | Anywhere with the disk + bandwidth (laptop for regions, in-region VM for the planet, CI later) |
| Sign the catalog | ~2 KB of hashes | **`TILES_SIGNING_KEY`** — a client-pinned trust anchor; compromise ⇒ clients accept attacker bundles that MapLibre parses | **Maintainer's machine, offline. Never in CI, never on a rented VM, never in a secret store.** |
| Publish `catalog.json[.sig]` | 2 files | Spaces key | Same as row 1 |

These are not two grades of the same secret. The Spaces key is an *operational* credential; the signing
key is in the app-signing-key family, and the standing rule for that family is that it does not enter
automation at any strength of secret storage. Hardware custody (a PIV/OpenPGP token holding the Ed25519
key) is the natural hardening step, but is not required for v1 — offline-on-one-machine is the bar.

**No DigitalOcean secret store is the answer, because there isn't one.** DO has no Vault/KMS equivalent.
What exists — App Platform encrypted env vars, DOKS secrets, droplet `user_data` — either doesn't apply
to a batch job or isn't a secret store at all (`user_data` is readable from the metadata endpoint by
anything running on the box). Terraform state is a *third* copy: `deploy/tiles/terraform.tfstate` holds
the Spaces keys in plaintext (gitignored, still on disk). So the resolution is not "find a better vault",
it is **have fewer secrets in fewer places**: one rotatable scoped key for the automatable half, one
offline key that never moves.

**Execution profile is per region class, not per pipeline.** The two cases have genuinely different
constraints and the how-to should stop presenting them as one procedure:

- **Regional extracts — run locally.** `pmtiles extract` accepts a **remote URL** and range-fetches only
  the covering tiles, so `PLANET_PMTILES=https://…/planet.pmtiles` cuts Bayern in minutes over an
  ordinary connection with no planet on disk. Outputs are tens of MB to a few GB. This needs no VM, ever.
- **The `global` planet publish — in-region VM, sized by RAM.** `tiles-build.sh` links the planet in
  as-is (D10 streamed coverage), so the whole artifact must be materialised *and* pushed to the bucket:
  15–25 GB zoom-capped, ~100 GB full. On a fra1 droplet the fetch is fast and the upload to Spaces is
  in-region; on a home uplink it is a multi-day job that you do not want to have to restart.
  ⚠ **The binding constraint is memory, not disk** — `pmtiles extract` holds the output archive's tile
  directory resident, and a z14 planet has tens of millions of entries. Measured 2026-07-21: a 1 GB
  droplet OOM-killed the process at **7.68 GB of requested address space** while 70 GB of disk sat
  unused. Budget ≥ 8 GB and treat disk as the *second* constraint. Regional extracts are unaffected
  (Bayern ≈ 270 k entries, comfortable in 1 GB), which is exactly why procedure A runs on a laptop.

**CI is the button, not the muscle (the long-term shape).** A GitHub-hosted runner cannot host the data
path: standard runners document ~14 GB of SSD and cap a job at 6 hours — a zoom-capped planet does not
fit, let alone a full one. The half that *would* fit (regional extracts) is also the half that is already
painless locally, so putting it in CI automates the easy problem. The shape that pays off is a
`workflow_dispatch` that **provisions an ephemeral in-region droplet, runs build + bundle-upload from a
pinned builder image, and destroys it** — CI contributes the trigger, the audit trail, and independence
from the maintainer's laptop being awake; the droplet contributes disk and in-region bandwidth. The
workflow ends with bundles uploaded and an **unsigned** catalog; the maintainer signs and publishes the
pointer. On a quarterly cadence (D8) that human gate costs minutes a year and is what keeps the trust
anchor offline. Secrets in CI are then exactly: DO API token + bucket-scoped Spaces key.

**A pinned builder image is worth it independently** (`pmtiles` + `aws` + `jq` + JRE): it deletes the
"install these five tools" preamble that otherwise rots in the how-to, and it matches the existing
prebuilt-image deploy pattern (`relay-image.yml`, `apex-image.yml`, `commerce-image.yml`). It is useful
whether the run is triggered from CI or by hand on a droplet.

**Two implementation consequences, both landed with this decision.**

1. *The signer could not have published a `global` bundle at all.* `CatalogBuilder` hashed each bundle
   via `File.readBytes()` + `core-common`'s `sha256(ByteArray)`, and a JVM array is `Int`-indexed — any
   bundle past 2 GiB fails before memory pressure is even reached, which by D2's own size table includes
   "large country" as well as the planet. Fixed with a streamed digest local to `:tiles-signer`
   (`sha256File`), pinned by test to agree with the `core-common` primitive the client re-runs — the hash
   contract is unchanged, only the build-side computation is.
2. *The split needed a carrier, or it was unimplementable.* Signing needs each bundle's `{byteSize,
   sha256}`; for `global` those bytes are on the build box and will never be on the maintainer's laptop.
   So `tiles-build.sh` now writes `build/hashes.json` next to the extracts, and `build-and-sign` takes
   `--hashes <file>`: a locally-present extract is still hashed for real, anything absent is read from
   the digest file, and a region with neither is a hard error rather than a silently-wrong catalog. The
   digest file is public (a subset of what `catalog.json` publishes) so moving it needs no protection —
   which is precisely what makes "key never leaves home" cost nothing. Build dates must agree between
   the two files or signing refuses. Without this the release key would have had to travel to whichever
   rented box happened to hold the planet, and D11 would be advice rather than a procedure.

---

## 4. The request flow, end to end

1. Maps' picker calls `RegionService.catalog()` over the binary IPC. The **proxy** fetches
   `catalog.json` + signature from the CDN (unauthenticated), verifies the signature,
   filters on tile schema version, returns the typed listing.
2. User picks a region; the app calls `download(regionId)`; progress arrives as a stream.
3. The **proxy** requests a tiles voucher from commerce (HTTPS + JSON, commerce-session
   auth; commerce learns only "wants tiles" — never which region).
4. The **proxy** presents the voucher to the tile gateway with the region path; the gateway
   verifies (signature, expiry, `aud`, rate limit) and returns a presigned URL. The proxy
   downloads via resumable range requests from storage/CDN, verifies sha256 against the
   manifest, and lands the bundle in the canonical region store.
5. The app pulls the bundle over the IPC **pull range-source** into its own `filesDir`
   (D5/D6) and points MapLibre at `pmtiles://file://…`. One network download; at most one
   cheap local copy per consuming app.
6. Update = same flow when the catalog shows a newer build for an installed region; the old
   bundle is replaced atomically (download-then-rename), never patched in place (v1).

(iOS: steps 3–4 collapse to "app fetches directly with a proxy-issued voucher" — D6.)

Failure surfaces are typed and honest: no voucher → the picker shows the subscription
affordance (Control's checkout flow, `control-subscription-ux.md`), not a broken download.

### 4a. Streaming (the v0.4 default) — no upfront download

The flow above is now the **offline-prefetch** path (opt-in, D10). The **default** read path has no
"download" step: MapLibre issues range reads against the local fd (D5/D6 provider); the proxy resolves
each covering range from its **tile cache**, and on a miss range-fetches it from the CDN (plain ranged
GET into the dated region object, D2/D3), caches it, and returns it. On first touch of a region the proxy
eagerly prefetches the low-zoom band (D10) so the surface paints immediately. No voucher, no store install,
nothing gated in v1 (gating is Phase 3, and gating *streamed* tiles has its own tension — D10 / §7 phase
3). "Make available offline" is simply the §4 flow run to completion, promoting the region from
cache-of-what-you-viewed to complete-on-disk.

---

## 5. Threat-model deltas (proposal-level; land with code per House Rule #2)

- **New asset — region-interest signal at infra.** The gateway/CDN observes that *some
  voucher* fetched region X at time T. Mitigations: ownerless vouchers (D4), no durable
  voucher-id logs, blinded issuance as target state. Residual: IP correlation (accepted,
  documented, out of scope — billing-plane §7 posture).
- **New surface (v0.4) — streamed viewport pattern at the tile host.** Streaming (D10) lets the CDN
  observe, by IP over time, which tiles the *proxy* fetches — a coarse "where you're looking," the exact
  signal the D1 anti-decision avoided by mandating whole-region download. Bounded: the egress is the
  proxy's (not the app's), the tile host is a separate public plane carrying no account or user content
  (D6/R2), and the observer is a CDN, not the sync relay. Mitigations (D10): low-zoom prefetch coarsens
  precision; local caching means re-views make no request and a streamed user accrues a partial offline
  cache; **offline mode is the zero-per-viewport-egress escape hatch.** IP correlation stays out of scope
  (billing-plane §7). This is the amendment the streaming phase (§7) must land in `threat-model.md` per
  House Rule #2.
- **Integrity delta (v0.4) — streamed tiles are not sha-verified.** The signed catalog's per-region
  sha256 (R4) verifies a *complete* file, so it still gates **offline** downloads; it cannot verify
  individual **streamed** tiles (no per-tile hashes; basemap tiles are public plaintext, not AEAD-sealed
  like Drive chunks). Streamed integrity rests on TLS + the catalog pinning *which* object is ranged into.
  Residual: a tampering CDN can serve a wrong (MapLibre-parsed) tile in streamed mode; offline users keep
  the full-file guarantee. Accepted for public, render-only data; stated.
- **New trust anchor — the manifest signing key.** Compromise ⇒ clients accept attacker
  bundles (rendered by MapLibre — a parsing attack surface, not just wrong maps). Key
  handling follows the release-key regime; the client pins the public key.
- **No new egress path in map apps** (v0.2, via D6). Downloads are proxy-mediated, so
  `maps-android` / Timeline shells keep `INTERNET` stripped; the proxy — already a
  networked component — gains one new egress destination (`tiles.service.tenshu.app`) plus
  the commerce voucher call it substantially already makes. Verified by keeping the Ph7b
  emulator check (map app renders with `INTERNET` removed) as a standing test. **Exception:
  iOS direct fetch (D6)** — the map app itself egresses to the CDN with a voucher; its
  semantic-permission manifest declares this, and a dedicated threat-model note lands with
  the iOS `:ui-map` actual.
- **No change** to relay assets: the relay is entirely uninvolved (tiles never ride sync).

---

## 6. Costs (why "included in the subscription" is safe)

Sized 2026-07: planet PMTiles ≈ 100–120 GB; keeping two dated builds ≈ 2.5× planet ≈
300 GB with all region extracts. DO Spaces: $5/mo covers the first 250 GB + 1 TB egress
(overage ~$0.02/GB storage, ~$0.01/GB egress). R2: ~$4.5/mo at 300 GB with **zero** egress.
The build job: a few hours on a rented 64 GB+ instance per cycle, single-digit dollars
(or $0 while seeding from Protomaps' published planet builds). Egress is the only
user-scaling dimension — a region download is one-time per user per region (hundreds of MB
typical), so even thousands of active users land in tens-of-dollars-per-month territory,
and serving from a zero-egress host (R2 + Cloudflare, now the streaming-era default — D1) removes the
egress dimension almost entirely. Public reference points: the Protomaps
docs' cost-model for static PMTiles hosting and the Lat×Long global-dataset estimate
(a few $/mo on R2). **Conclusion: gating is an abuse/fairness control, not cost recovery** —
which is exactly how the UX should present it. (The v0.4 streaming default holds this only on a
zero-egress host with edge rate limits + a spend cap; on a per-egress origin, a public streaming
endpoint is exposed to denial-of-wallet — D1 / §7 phase 4.)

---

## 7. Phasing (each independently shippable)

> **v0.4 reshape (D10).** The streamed-default reframe reorders the value: the v1 goal is a map that
> **works with nothing downloaded** (streaming + low-zoom prefetch), not the whole-region picker. Phase 2
> below still lands the `RegionService` + terminus, but the terminus is the **range-provider** (an
> app-internal `content://`→fd provider proxying MapLibre's range reads over the pull-range ops, reused
> from Drive's streaming substrate) serving **both** `OfflineBasemap` (Maps) **and** the new `RouteBasemap`
> (Timeline — landed, still on the bundled demo). Streaming-from-a-remote-source is the new
> proxy capability; whole-region download becomes the opt-in "make available offline." A shared
> **viewport→region resolver** (which streamable/resident region covers the camera — a Timeline trip route
> can cross region boundaries) is part of the terminus from the start. New phases 6–7 (offline-mode +
> onboarding) are explicitly post-v1; phase 8 flags the out-of-scope account↔services surface.

1. **Pipeline + publish.** Region definition file (starter set: DACH + a handful of test
   regions), extract job (seeded from a published planet build), bucket + CDN + manifest +
   signing. No gateway, no client — verify with `curl` + a manual sideload into the Ph7b
   region path. Deliverable: `catalog.json` live on `tiles.service.tenshu.app`.
2. **Proxy `RegionService` + client terminus + picker (ungated).** Proxy-side: catalog
   fetch/verify, download manager, canonical store, the `RegionService` IPC interface (D6).
   App-side: the `:ui-map` terminus (pull range-source receipt into `filesDir`) + Maps'
   picker over the live catalog; gateway stubbed by direct CDN paths. Ph9 interaction: real
   per-place centring starts working the moment the user's region is installed. Threat-model
   amendment for the proxy's new egress destination (§5) lands here; the Ph7b
   `INTERNET`-stripped render check becomes a standing test.
   **As-built (slice 2a shipped 2026-07-11 — `:map-region-service` scaffold + read/verify surface).**
   Resolves Q7 the default way: the five `:map-region-service*` modules exist, mirroring the
   `:place-service` P1 scaffold. Wire types `RegionEntry`/`RegionCatalog`/`InstalledRegion`
   (schema-first, House Rule #4) — deliberately **not** `Syncable` (ops-only service, `service(id)`
   with no relations; these are operation results, not synced entities). Ops shipped:
   `catalog()` (returns the verified, tile-schema-filtered `RegionCatalog`), `installed()`
   (`TList<InstalledRegion>` from the store), `deleteRegion(id)` — all value-returning
   `AsyncOperationImplementation`s over two injectable seams: `RegionCatalogSource` (fetch+verify)
   and `RegionStore` (canonical store). The manifest trust check is a pure `RegionCatalogVerifier`
   over a `SignatureVerifier` SAM (production wires `Ed25519Backend::verify`; no crypto backend
   leaks into `commonTest`), plus `renderableRegions(…)` filtering to `SUPPORTED_TILE_SCHEMA_VERSION`
   = 4 (D3). Green: `RegionCatalogVerifierTest` (4) + `MapRegionServiceTest` (3, client→proxy→client
   round-trip over an in-process `TestProxy`) on JVM. **Deferred to slice 2b** (explicitly, like
   place-service P1's wiring): the `HttpRegionCatalogSource` (Ktor GET + JSON parse), the
   filesystem/`SecureStorageDelegate`-backed `RegionStore`, `download(regionId)` (resumable ranged
   GET + progress stream + sha256), the app-graph DI wiring (`ProxyModule`/`TenshuClientModule`/3×
   `PlatformProxyModule`), the `:ui-map` terminus, the threat-model amendment, and the smoke-gate
   regression — none of which the read/verify surface needs to land in isolation.
   **As-built (slice 2b shipped 2026-07-11 — proxy-side delivery data path, stacked on 2a).**
   The proxy ↔ tile-host leg (D6), all headlessly testable: `HttpRegionCatalogSource` (Ktor GET of
   `catalog.json` + `catalog.json.sig`, `RegionCatalogVerifier` gate, `parseRegionCatalog` JSON →
   `RegionCatalogData`), a real `DefaultRegionStore` (install index JSON in `SecureStorageDelegate` +
   bundle bytes via `FileDatabase` — device-local, **not** the synced realm, R2), a `RegionDownloader`
   (resolve in the verified catalog → fetch bundle → **sha256 gate** against the manifest, R4 → land
   in the store), and the `downloadRegion(regionId)` op returning the landed `InstalledRegion`
   (one-shot; the incremental progress stream + resumable ranged GET are noted follow-up
   optimisations, not correctness). Green on JVM (`:map-region-service-proxy:jvmTest`): the 2a suite
   plus `RegionCatalogSourceTest` (parse + MockEngine fetch/verify incl. tamper-rejects),
   `DefaultRegionStoreTest` (add/installed/remove + index-survives-restart + update-replaces),
   `DefaultRegionDownloaderTest` (real `sha256` gate over MockEngine: happy / sha-mismatch /
   unknown-region), and the `downloadRegion` op through the client. **Still deferred to slice 2c**
   (the integration + on-device slice): the app-graph DI wiring (constructing the source/store/
   downloader from the injected `HttpClient`/`SecureStorageDelegate`/`FileDatabase` + a `named`
   tiles-host URL + pinned key), the `:ui-map` terminus, the threat-model amendment, and the smoke
   gate — grouped together because they verify on an emulator, not headlessly.
   **As-built (render-hop slice shipped — the `:ui-map` terminus + byte transport, whole-region
   drain-to-file).** Closes the render hop that the proxy-DI + IPC wiring left open (downloaded bytes sat in
   Control's store; `ClientRegionRepository` dropped the path, so both `:ui-map` consumers rendered
   the bundled Firenze demo). **Scope call (D10 reality-check):** the streamed-default range fd is a
   distinct, MapLibre-fd-gated follow-up — the reusable **range op** ships now, consumed the
   deterministic, headlessly-verifiable way (drain the whole bundle into the app sandbox on open).
   The existing sync-service `ReadContentRange` streaming substrate is **not** reusable here — it is
   bound to synced, AEAD-encrypted `File` entities (realm routing + `FileChunkDecryptor`), whereas
   region bundles are public plaintext, ops-only, non-`Syncable`, in Control's `FileDatabase`. So a
   **new `readRegionRange(regionId, offset, length): TByteArray`** op on `:map-region-service`
   (stateless positional read — no decrypt / handle / session, since a bundle is an immutable
   device-local file; unknown-region → typed failure, EOF → empty), backed by `RegionStore.readRange`
   (`DefaultRegionStore` → `FileDatabase.readBytesRange` positional seek, `InMemory`/`Fake` hold
   bytes). App terminus: a client-side `RegionBundleStager` (JVM impl over app-private dir — Android
   `filesDir`, desktop cache — bound per platform in `platformClientModule`) that `stageRegionBundle`
   drains ≤256 KiB windows into, promoting a temp→final atomically; `ClientRegionRepository.
   renderableRegionPath()` picks the single installed region (real viewport→region-by-coverage waits
   on real place coords, `:place-service` Ph9) and returns the staged `.pmtiles`. `MapSnippet` +
   `PlaceDetailViewModel.Loaded.regionBundlePath` thread it into `OfflineBasemap(regionBundlePath=…)`
   (which already stages + opens an arbitrary `.pmtiles`, or falls back demo→schematic — the terminus
   `resolveBasemapSource` seam was pre-built). `RouteBasemap` gained the matching `regionBundlePath`
   param (Android threads it through `resolveTilePath`; desktop ignores — schematic); its Timeline
   consumer `TripRouteMap` still passes null (Timeline's region-client wiring is a follow-up).
   **No threat-model amendment:** whole-region transport adds no new egress (the download already
   happens proxy-side, sha256-gated) and no new relay/crypto shape — only a second local copy of
   already-verified public map data in the app sandbox; §5's deltas remain reserved for the *remote*
   streaming path. Green: `readRegionRange` op round-trip + `DefaultRegionStore.readRange`
   (`:map-region-service-proxy:jvmTest`), the drain loop across windows / re-drain short-circuit /
   mid-stream-failure / premature-EOF (`:maps-shared-data`), the VM region wiring
   (`:maps-shared-logic`); Android smoke gate (Control + Drive + Maps launch, no crash/ANR).
   **On-device (2026-07-12, live backend + signed `test-firenze` region):** the full delivery chain
   is CONFIRMED working end-to-end on hardware — catalog fetch + Ed25519 verify vs the pinned key,
   `downloadRegion` (ranged fetch + sha256 gate + store), and the render-hop drain into the Maps
   app's own sandbox. **The delivered `pmtiles extract` basemap DOES draw on Android** — confirmed
   2026-07-12 via on-device range-server instrumentation (MapLibre reads header → metadata → root
   directory → covering tiles and renders them). An earlier "MapLibre can't render extracts" claim
   (and the now-**closed-as-invalid** [maplibre-native#4398](https://github.com/maplibre/maplibre-native/issues/4398))
   was a false alarm: the snippet camera sat on a **Lisbon** mock place, outside the **Firenze**
   extract, so MapLibre correctly culled to empty. **No pipeline change needed** — `pmtiles extract`
   stays. The only fix is demo self-consistency (bundle a Lisbon extract, or move the `MapsMockData`
   bbox onto Firenze). See [`ui-map/ui-map-agent.md`](../../ui-map/ui-map-agent.md) "Gotcha — blank
   basemap = camera outside tile extent".
   **Follow-ups:** streamed-default remote-range fd (spike MapLibre fd/`content://` support
   first); Timeline `RouteBasemap` region-client wiring; viewport→region-by-coverage once real coords
   land.
   **As-built (shared terminus extraction + Timeline render seam — whole-region-first track, 2026-07-13; desktop-green + Android smoke, on-device pending).**
   The app-side render terminus was Maps-private (`RegionBundleStager` + `stageRegionBundle` +
   `JvmRegionBundleStager` + the drain-single-installed-region logic lived in `:maps-shared-data` /
   `:maps-shared-ui`). Lifted into a new **Compose-free, maplibre-free** module **`:map-region-app`**
   (`app.tenshu.mapregion.app`) so every `:ui-map` consumer reuses one render hop instead of
   duplicating it — the elegant consequence being that Timeline consumes it **without any dependency
   on Maps modules**. Shape:
   - **`RegionTerminus(client, stager)`** holds the drain-and-stage logic; `renderableRegionPath()`
     (first installed region) delegates to a new **`renderableRegionPathFor(point: LatLng?)`** —
     coverage-aware selection (the tightest *installed* region covering the point, via the catalog's
     bbox + `RegionResolver`), falling back to the first installed region. This is the
     "viewport→region belongs in the terminus from the start" call, now realised for Timeline's
     border-crossing trips.
   - **`RegionResolver` + `GeoBounds` + `ResolvableRegion`** moved out of `:maps-shared-logic` into
     `:map-region-app` over a **neutral `LatLng`** (was `GeoPoint`-typed, Maps-private); `RegionResolverTest`
     moved with them. Maps was resolver-agnostic in prod (only tested), so blast radius = the test + two comments.
   - Maps repoints: `ClientRegionRepository.renderableRegionPath()` now delegates to `RegionTerminus`;
     picker methods (`regions`/`download`/`delete`) + `RegionItem` stay Maps-side. **Behaviour-preserving**
     (Maps' render path unchanged); Android smoke gate green (Control + Drive + Maps launch).
   - **Timeline render seam:** a thin `RegionBundleResolver` seam in `:timeline-shared-logic` (over the
     trip's own `GeoPoint`, no `:map-region` dep → VM stays decoupled + fakeable); `TripDetailViewModel`
     resolves it **best-effort** *after* emitting `Loaded` (trip shows immediately on the demo, upgrades
     to the real region once staged); `TripDetailScreen` → `TripRouteMap` → `RouteBasemap(regionBundlePath=…)`
     threaded. Absent resolver (standalone graph) → null → demo, **zero regression to Timeline's
     standalone startup**. `TimelineViewModelsTest` covers the thread-through.
   - **Ph1 step 2 — Timeline's best-effort Control region client (LANDED, on-device tile render pending).**
     The connective step: Timeline now reaches the real `MapRegionService` in Control. Timeline had **no**
     Control client (purely `InProcessTimelineProxy`); this adds a **second** `TenshuClient` reaching Control
     over IPC purely for regions, alongside the in-process data proxy — mirroring Maps' `mapsClientModule` +
     per-platform `PlatformClientModule` (Android Binder / desktop UDS). `timelineClientModule` binds
     `RegionBundleResolver` → `TerminusRegionBundleResolver` (trip-route centroid → `RegionTerminus`.
     `renderableRegionPathFor`, heavy drain on `Dispatchers.Default`). Both shells add
     `[timelineClientModule, platformClientModule]` to Koin; `timeline-android` manifest gains
     `<queries>`(control package) + the reverse-channel `ClientCallbackService`, and — **HR#1 / R1** — strips
     the maplibre-merged `INTERNET` (Timeline's data is in-process, capture is local, map egress is Control's;
     it keeps location for its own visit capture). **Graceful by construction (confirmed in the transport
     code, not just asserted):** unreachable Control → `bind()` swallows the connect failure →
     `RemoteBinaryTaskExecutor` returns `TResult.Failure` → `getOrNull()` → null → the trip route keeps the
     bundled demo; **Timeline still launches standalone (no crash).** Green: `:map-region-app` +
     Maps + Timeline desktop suites, both desktop shells + `:timeline-android` compile,
     `:timeline-desktop:run` boots clean with Control absent (DI graph resolves), Android smoke gate PASSED.
     **Pending:** on-device real-tile render (Timeline-android ↔ Control Binder + a downloaded region), the
     same maintainer-device ceiling Maps' render hop had.
   - **Ph2 — Fit workout routes on the offline basemap (LANDED, on-device tile render pending).** Fit was the
     third `:ui-map` consumer-to-be — it drew workout routes as a schematic 0..1 polyline (`RoutePath`),
     **never a map**, discarding the real GPS. Now it renders them over the offline basemap. Two parts,
     mirroring Timeline: **(step 1, render seam)** a new `Workout.geoRoute` (real WGS84) carried by
     `FitProjection.projectGeoRoute` from the same `WorkoutStreams` E7 samples `projectRoute` normalises away;
     Fit adopts `:ui-map`; new `WorkoutRouteMap` (`RouteBasemap`) renders in `WorkoutDetailScreen`'s route
     section when the workout has GPS (`geoRoute ≥ 2`), else the schematic `RoutePath` stays (the "No map
     tiles" note shows only there — the basemap carries its own attribution); a best-effort
     `RegionBundleResolver` seam (over Fit's own `GeoCoord`) threaded into `WorkoutDetailViewModel` after
     emitting `Loaded`. **(step 2, region client)** `fitClientModule` + per-platform `PlatformClientModule`
     (Binder/UDS) bind `RegionBundleResolver` → `TerminusRegionBundleResolver` over `RegionTerminus`; both
     shells add the modules; `fit-android` manifest strips the maplibre-merged `INTERNET` (**HR#1/R1**; keeps
     location for the recorder) + `<queries>`(control) + `ClientCallbackService`. Same graceful degradation
     (Control absent → demo, no crash). Green: `projectGeoRoute` + VM region-path threading tests, all Fit
     desktop suites, `:fit-desktop:run` boots clean with Control absent (DI resolves), `:fit-android`
     assembles + **installs & launches on the emulator** (running, no FATAL/ANR/Koin errors). **Pending:**
     on-device real-tile render. **All three `:ui-map` consumers (Maps, Timeline, Fit) now share one render
     terminus + region client pattern.**
   - **Ph3 — proxy-side streaming range engine (D10; LANDED headless, app-side fd spike pending).** The
     verifiable heart of the streamed-default reframe: the proxy can now serve a region range **without a
     full download**. New `:map-region-service-proxy` components — `RegionRangeFetcher` /
     `HttpRegionRangeFetcher` (an HTTP `Range: bytes=…` GET, `206`), `StreamingRegionCache` /
     `InMemoryStreamingRegionCache` (bounded, 256 KiB chunk-aligned), and `StreamingRegionSource`
     (**installed-first**, else cache-first, else CDN range-fetch + cache; assembles across chunk
     boundaries; EOF-safe; `prefetchLowZoom` warms the low-zoom prefix). Exposed as a new
     **`streamRegionRange(regionId, offset, length)`** op (mirrors `readRegionRange`), constructed in
     `mapRegionComponents` and dispatched via the factory. **This is the "one mechanism" (D10):** the same
     op serves local-complete (offline) or remote-on-demand (streamed) transparently. Threat-model amended
     (`sync/threat-model.md` §8.18 **T-MR-03** streamed viewport pattern + **T-MR-04** streamed-tile
     integrity delta, HR#2). Green: `StreamingRegionSource` (cache hit/miss, cross-chunk, EOF, prefetch,
     installed-first, unknown-region) + `HttpRegionRangeFetcher` (MockEngine 206) + `streamRegionRange`
     client round-trip (`:map-region-service-proxy:jvmTest`); Control desktop + the new op compile; Control
     + Maps **install & launch on the emulator** (the streaming-source DI resolves). **Deferred to the
     on-device spike (the doc's stated gate):** the app-side `content://`→fd `ProxyFileDescriptor` MapLibre
     reads through (`pmtiles://file:///proc/self/fd/N`), wiring `streamRegionRange` to those reads +
     first-touch `prefetchLowZoom`, and **flipping the default from whole-region to streaming**. Until then
     the shipped whole-region path stays the render default; the streaming engine is dormant (no consumer
     calls `streamRegionRange`), so **no behaviour change** to Maps/Timeline/Fit.

     **⛔ MapLibre-fd spike RESULT (2026-07-13): NEGATIVE — the `ProxyFileDescriptor`/`/proc/self/fd/N`
     approach does NOT work; streaming via a proxy fd is DEAD for maplibre-native 13.0.2.** Tested
     on-device (emulator) by wrapping the bundled demo `.pmtiles` in a `StorageManager.openProxyFileDescriptor`
     and handing MapLibre `pmtiles://file:///proc/self/fd/N` (a real `ParcelFileDescriptor`, `onRead(offset,
     size)` = the lazy byte source; instrumented). Decisive logs: the fd opened fine (`opened proxy fd 141
     over tenshu_basemap.pmtiles`), the style set + finished loading, then
     `E Mbgl: Failed to load source protomaps: Error fetching PMTiles header: **Cannot read file
     /proc/self/fd/141**`, and **`onRead` was never called**. Root cause: maplibre-native's PMTiles reader
     does its **own `open()` on the path string** (it does not read through the fd you pass), and opening the
     AppFuse-backed `ProxyFileDescriptor`'s `/proc/self/fd/N` symlink from that native code fails. Combined
     with the pre-existing `pmtiles://asset://` SIGABRT (only `file://` on a real path is safe), **there is no
     "local path backed by lazy bytes" form maplibre-native will accept.** ⇒ The one range-provider mechanism
     (D10) **cannot be delivered to maplibre via a fd.**
     **The remaining viable streaming path = a local loopback HTTP range server** (`127.0.0.1:port`, serving
     `streamRegionRange` bytes with `Range`/`206`) → `pmtiles://http://127.0.0.1:port/…` (maplibre's REMOTE
     pmtiles path works — the common Android offline-map trick). **BUT it needs `INTERNET` back on the map
     apps** (restricted to loopback via `networkSecurityConfig`) — a direct **HR#1 tension** and a
     maintainer decision + threat-model note, exactly the option flagged in the earlier blank-basemap
     investigation.

     **✅ PARTIAL-SPARSE-FILE spike RESULT (2026-07-13): POSITIVE — this is the HR#1-clean streaming path;
     no loopback / no `INTERNET` needed.** The insight: MapLibre insists on a **real `file://`** + its own
     random-access `pread`, BUT for a **static snippet** (fixed camera — every `:ui-map` surface) the tiles
     it reads are a **small, computable set**. So build a **sparse full-size `.pmtiles`** populated with only
     the ranges MapLibre reads (header + directory + metadata + covering tiles), leaving the rest as **holes**
     (unwritten → zero, culled to the viewport so never read). A real `file://` → MapLibre renders it.
     **Proven on-device (screenshot):** a Kotlin PMTiles-v3 directory reader (`ui-map/.../PmtilesDirectory.kt`
     — header + columnar-varint dir + Hilbert `zxyToTileId` + `coveringRanges`, unit-tested incl. the real
     Firenze id `(14,8704,5972)=317221125`) computed the covering ranges; a sparse file **79% populated (21%
     holes)** over the 1.3 MB demo rendered the full snippet, no `Mbgl` error. For a real GB region a static
     snippet is a tiny fraction. **⇒ the D10 streamed default is VIABLE; the loopback server is NOT needed.**
     **Productionisation (LANDED — see Ph3b as-built below):** the app-side builds the sparse file,
     fetching each covering range over `streamRegionRange`, and hands the path to `OfflineBasemap`.
     Outstanding: disk-backed cache of fetched ranges + leaf-directory descent on real leaf-bearing extracts.

   - **Ph3b — streaming render productionised end-to-end (LANDED; desktop-green + ON-DEVICE
     LIVE-CATALOG VERIFIED — the streamed, non-fallback render works against the deployed CDN).** The
     partial-sparse-file path is now the wired D10 default, not a spike branch. Threading, top to bottom:
     - `:ui-map` gains a **streaming API**: `RegionStreamSource(id, totalSize, readRange)` +
       a `streamSource` param on the `expect fun OfflineBasemap(...)`. The Android actual's
       `buildStreamedPartial(context, source, lon, lat)` fetches only the covering ranges (header → gunzip'd
       root dir → leaf dirs → `coveringRanges`) via `source.readRange`, writes them into a **sparse full-size
       `.pmtiles`** (temp + atomic rename), and hands MapLibre a real `file://`; a `LaunchedEffect` keyed on
       `(regionBundlePath, streamSource.id, cameraPos)` runs it. Desktop actual ignores `streamSource`
       (schematic).
     - App seam: `RegionStreamHandle(regionId, totalSize, readRange)` (`:maps-shared-logic`, Compose-/`:ui-map`-free)
       + `RegionRepository.renderableStream(): RegionStreamHandle?` (default null). `ClientRegionRepository`
       (`:maps-shared-data`) binds `readRange` to the proxy's **`streamRegionRange`** op over the resolved
       catalog region.
     - VM: `PlaceDetailViewModel` **prefers `renderableStream()`** (fetch-only-covering-tiles), falling back to
       `renderableRegionPath()` (whole-region file) only when the stream is null, then the bundled demo —
       combined into `Loaded(place, regionBundlePath, regionStream)` over three flows.
     - UI: `MapSnippet` / `PlaceDetailContent` thread `regionStream` through, converting the handle to
       `:ui-map`'s `RegionStreamSource`.
     - **HR#1 intact** — the app still fetches nothing; `readRange` rides `streamRegionRange` (the proxy owns
       the egress) and the render is a local `file://`. Green: all maps + `:ui-map` desktop targets compile,
       `:ui-map`/`maps-shared-*` `desktopTest` pass (incl. the `PmtilesDirectory` decoder suite), Android
       `compileAndroidMain`.
     - **ON-DEVICE LIVE-CATALOG VERIFICATION (2026-07-13, emulator + deployed CDN).** With fresh Control
       (prod DI: `tilesHostUrl = https://tiles.service.tenshu.app`, pinned manifest key) + Maps installed, a
       place detail drove the streamed render end-to-end: Control fetched + Ed25519-verified the **live**
       `catalog.json`, and the Maps snippet logged
       `MapStream: region=test-firenze archive=11351199 populated=4350547 (38%) ranges=55` — i.e. it resolved
       the live catalog's region (`archive` = the catalog's exact `byteSize`, **not** the 1.3 MB bundled demo),
       computed 55 covering ranges for the Firenze camera, fetched **only 38 %** of the 11.3 MB region via
       `streamRegionRange` (IPC → Control → CDN `Range`/`206`), wrote the sparse `.pmtiles`, and MapLibre
       rendered real Firenze streets + the Arno with **no `Mbgl` error**. **HR#1 confirmed on-device:** the
       Maps package holds **no `android.permission.INTERNET`** (only `ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE`
       for maplibre's connectivity check) — every region byte came through the proxy. (The empty-catalog
       graceful fallback — `renderableStream()` null → bundled Firenze demo, never blank — was verified
       separately on the same emulator before Control was serving a catalog.)
     - **Outstanding (unchanged from Ph3):** disk-backed range cache; leaf-descent on real leaf-bearing
       extracts (the `test-firenze` extract is root-dir-only, so leaf descent is still exercised only by unit
       tests); a maintainer pass on a phone against a bigger (leaf-bearing) region.

   - **Ph3c — route streaming parity for Timeline + Fit (LANDED; desktop-green + ON-DEVICE LIVE-CATALOG
     VERIFIED for both).** Brings the other two `:ui-map` consumers onto the D10 streamed default. A route
     spans an area, not a single pin, so the covering-tile computation generalises:
     - `:ui-map` — `PmtilesDirectory.coveringRangesForRoute(points)` unions the covering tiles around every
       route vertex across the zoom band (deduped by globally-unique tile id → a dense polyline costs a
       handful of tiles per zoom, tracking the corridor, not the whole bbox). The sparse-file builder is
       extracted to a shared `buildStreamedPartial(cacheKey, selectRanges)` core in `StreamedPartial.android.kt`
       (point + route wrappers); `RouteBasemap` gains a `streamSource` param (expect + android actual keyed on
       `(regionBundlePath, streamSource.id, routePoints)`; desktop ignores it). Unit-tested.
     - `:map-region-app` — a shared neutral `RegionStreamHandle` + `RegionTerminus.renderableStream(point)`
       (mirrors Maps' `ClientRegionRepository.renderableStream()`, on the terminus every consumer already uses;
       coverage-aware via the catalog boxes, streaming needs no install).
     - Timeline + Fit — each gains a local neutral `RouteRegionStream` (keeping `:*-shared-logic` free of
       `:map-region`/`:ui-map`), `RegionBundleResolver.streamHandle(route)` (default null), a
       `TerminusRegionBundleResolver` override, VM preference (`streamHandle` → `bundlePath` → demo), and the
       screen/`RouteBasemap` threading.
     - **Demo data → Firenze** (so the streamed render is visible, mirroring the Maps mock-places siting): Timeline's
       mock trip route becomes a compact central-Firenze walk; Fit's "Morning run" mock workout gains a Firenze
       Arno-loop `geoRoute` (the only GPS-bearing mock workout — the rest stay schematic, matching the
       real-world "Health Connect rarely exposes per-fix tracks" case). Trip title/tags stay Lisbon-flavoured
       (cosmetic, accepted for a throwaway demo).
     - **ON-DEVICE VERIFICATION (2026-07-13, emulator + deployed CDN).** Opening a Timeline trip detail and a
       Fit workout detail each drove the streamed render end-to-end:
       `MapStream: region=test-firenze archive=11351199 populated=… (47%/46%) ranges=75` — the live catalog's
       region, ~75 covering ranges for the route corridor (vs Maps' 55 for a single pin), ~46-47% of the 11.3 MB
       region fetched via `streamRegionRange` (IPC → Control → CDN), the route line rendered over real Firenze
       streets + the Arno, no `Mbgl` error (screenshots). **HR#1 confirmed:** neither `app.tenshu.timeline.android`
       nor `app.tenshu.fit.android` holds `INTERNET`. Maps re-verified unchanged after the builder refactor
       (38%, 55 ranges). Android smoke gate PASSED.
     - **Outstanding:** same as Ph3b (disk-backed cache, leaf-descent on a bigger region); a `maxTilesPerZoom`
       guard on the route corridor for very large routes (bounded fine for city-scale demo routes today).
3. **Voucher gating.** Commerce mints `aud=tiles` vouchers (reuses §4 token mechanics /
   Ph4-era commerce service); thin gateway (verify → presign) deployed alongside commerce;
   enforcement flag default-off, then on with typed errors + the picker's subscription
   affordance. No-durable-voucher-logs rule lands as a deploy-time check.
4. **Ops hardening.** **Abuse/cost controls for the public streaming endpoint (D1/D10):** serve from a
   zero-egress host (R2 + Cloudflare), edge **rate limits** (per-IP, accountless) + bot rules, **hotlink
   deterrence** (Referer/Origin), and a **hard egress/budget alarm + spend cap** so a crawl can't become a
   surprise bill. Plus CDN cache config (immutable dated bundles long-TTL; `catalog.json` short-TTL +
   flush), cost/egress dashboards (aggregate counts only — per-region popularity is fine, per-voucher
   history is not), build-cadence automation, old-build GC.
5. **Later (explicitly v2+):** blinded voucher issuance (rides storage-token blinding),
   delta updates, world-overview bundle, desktop parity when MapLibre's desktop offline
   path lands (Ph7b caveat), iOS `:ui-map` actual + the D6 direct-fetch variant (with its
   threat-model note).
6. **Offline-mode + settings (post-v1, D10 / D5-amended).** "Make available offline" per region —
   prefetch the whole bundle, reusing the Drive offline-keeper verb + the metered-network
   `ConstraintSyncGate`. Canonical management is a **Control "Offline maps"** surface (the shared store
   owner, D5-amended): list resident regions + sizes + stream-vs-offline default + delete — a
   *data-availability* screen, distinct from the account/entitlement *Services* surface
   ([`control-services-surface.md`](control-services-surface.md)). Apps add **contextual triggers** over
   the same store (Maps "download the region you're viewing"; Timeline "make this trip's regions offline").
   Staleness/refresh for resident regions (per-region **sha256** vs the catalog, *not* the catalog-wide
   `buildDate`, which would false-flag every region on any new build) lands here; the store's
   overwrite-in-place becomes a real tmp+rename atomic swap for large bundles.
   **As-built (Control "Offline maps" surface — Ph4, 2026-07-13; desktop-green + Android smoke).** The
   canonical management surface landed: **Settings → Offline maps** in Control lists the catalog regions
   with install state + honest sizes, "make available offline" (downloads the whole bundle via the existing
   `downloadRegion` op), and delete (`deleteRegion`, frees the disk + the Free-tier slot). **Pure UI over
   Control's own in-process client — no new client / IPC / proxy change:** Control already registers
   `mapRegionServiceClientModule` on its `TenshuClient` (it hosts the proxy), so a small
   `ClientOfflineMapsRepository(client)` (mirrors Maps' `ClientRegionRepository`) + `OfflineMapsViewModel`
   + `OfflineMapsScreen` + a `Destination.OfflineMaps` + a Settings row + one `viewModel {}`/`single {}`
   binding is the whole change (all in `:control-shared`). Reached from Settings (a data-availability
   surface, sibling to the account/entitlement *Services* tab, D5-amended).
   ⚠⚠ **Android-shaped, and it does not port as-is** — see
   [`ios-control-offline-maps.md`](ios-control-offline-maps.md). "Control owns the shared region store"
   describes a device with *one* proxy. Under iOS route 1 **every first-party app is its own proxy** and
   registers its own `MapRegionService`; the stores are one directory only when the App Group resolves
   (paid team / simulator) and were **N independent stores on every real device** until device builds gained the group on 2026-10-02 (`ios-background-scheduling.md` §4.1.1). Control-on-iOS
   also links no basemap, so a naive port would manage regions nothing renders from. Green: `OfflineMapsViewModelTest`
   (load / download-marks-installed / download-failure-message / delete-evicts / size-format) + `formatBytes`;
   Control desktop + android compile; `:control-desktop:run` boots clean (DI resolves the new binding);
   Android smoke gate. **Deferred (this phase's remaining scope):** the stream-vs-offline *default toggle*
   (premature — streaming isn't the render default until the fd spike lands); per-region **sha256** staleness
   refresh; the store's tmp+rename atomic swap; and the in-app **contextual download triggers** (Maps
   "download the region you're viewing", Timeline "make this trip's regions offline"). Strings are inline
   (matching the Settings "Errors" entry) — localisation of this expert surface is a later pass.
7. ~~**Onboarding choice (post-v1, D10).**~~ — ✅ **DONE (2026-08-12), but NOT as designed.**
   ⚠⚠ **The two-option screen this phase specified is obsolete, and shipping it would have been a
   regression.** It was designed while streaming was *not* the render default: *"Start now: your maps
   download as you go"* vs *"Download maps: available offline, more private"*. D10 then made streaming
   the default and Ph3b/3c productionised it — so the map already works on first launch with nothing
   chosen. Presenting that choice now would demand a decision that no longer needs making, at the worst
   moment, and ask for hundreds of megabytes before the user has seen a map at all.

   What was actually missing is not a choice but **discovery**: a user who installs Fit never learns
   that offline regions exist, or that Control is where they live. So Home gained a dismissible
   suggestion card (`OfflineMapsSuggestion`, mirroring `RecoveryAffordance`) that offers offline use
   rather than asking for a mode, and routes to the existing *Settings → Offline maps*. As built:

   - ⚠ **The copy opens by saying maps already work.** A card implying they are broken until something
     is downloaded would be false, and would push a large download for a problem the user does not have.
   - ⚠⚠ **The "does this user have a map app" gate is an explicit app-id allowlist, because no runtime
     signal answers it.** The obvious candidate — `AppDetail.services`, the service ids an app's
     *declared permissions* touch — does not: Maps declares `mapRegion/service` (`downloadRegion`,
     `deleteRegion`) and Timeline does (`reverseGeocodeCandidates`, `railLinesIn`), but **Fit declares
     none at all** while still drawing its workout routes, because those manifests list *sensitive*
     capabilities rather than every op an app calls. Gating on them would have silently excluded Fit —
     the exact app a maps-curious user might be the only one to have. ⚠ The cost is maintenance: a new
     map-rendering app must be added to `MAP_CAPABLE_APP_IDS`, and forgetting is quiet (its users simply
     never see the hint; the feature stays reachable in Settings).
   - ⚠ **An unknown region state hides the card** — `installed == false`, never `!= true`. A failed
     store read is not "no regions", the same rule `RecoveryAffordanceViewModel` documents as
     "zero means we have not looked".
   - Dismissal is **permanent on that device**, not timed like the recovery affordance: this is an
     optional convenience, not a safety warning, and re-offering something already declined is nagging.

   **Not done:** the **package picker with a locale-derived default**. It needs a catalog worth picking
   from — with DACH plus two test extracts, "the region for your locale" is either the one obvious answer
   or absent entirely — so it is sequenced behind the ops work that grows `regions.json` (track 5a).

8. **Out of scope here — the cross-service "account ↔ connected services" surface.** As Tenshu accretes
   External Services (relay/sync, storage, tiles, …), per-service gating UIs won't scale; a unified Control
   "Services" registry (each service self-describes status: Free / Connected / Needs account / Quota X of Y,
   in the grain of the RealmData card walker) is the better shape, but it governs Storage + Maps + future
   services and belongs in its **own note** near the commerce plane, not here. Now specified as a draft in
   [`control-services-surface.md`](control-services-surface.md).

### 7.1 Remaining work — consolidated backlog (2026-07-14)

The per-phase **Outstanding** notes above are the historical record of *when* each item was deferred; this
subsection is the **single current list** of what is actually left, merged and sequenced. When an item lands,
tick it here (the per-phase notes stay frozen as history).

**Where we are.** Ph0–Ph3c are done and on-device verified: all three `:ui-map` consumers (Maps pins,
Timeline trips, Fit workouts) render the **D10 streamed default** — fetch only the covering tiles into a
sparse `.pmtiles`, over `streamRegionRange` (IPC → Control → CDN), HR#1-clean (no app holds `INTERNET`).
Control's *Offline maps* surface (whole-region download/delete) shipped. Sizes below: `XS` ≈ hours,
`S` ≈ ½–1 day, `M` ≈ 2–4 days, `L` ≈ 1–2 weeks.

**Track 1 — streaming hardening** ✅ **DONE (2026-07-14)** — see the as-built note below.
- ~~**1a. Disk-backed range cache.**~~ **LANDED.** Was: every open rebuilt the sparse file, re-fetching every
  covering range.
- ~~**1b. `maxTilesPerZoom` guard on the route corridor.**~~ **LANDED.** Was: `coveringRangesForRoute` was
  unbounded in principle — a cross-country route could select a huge tile set.
- ~~**1c. First-touch `prefetchLowZoom` wiring.**~~ **RETIRED — superseded by 1a, and it would now cost more
  than it saves.** The idea (Ph3) was to warm the low-zoom band so the surface paints immediately. But
  (i) `coveringRanges`/`coveringRangesForRoute` already walk `minZoom..maxZoom`, so the camera's low-zoom
  tiles are *already* in the covering set — there is nothing missing to warm; (ii) `prefetchLowZoom` fetches
  a **blind 4 MB byte prefix** (≈35 % of the 11 MB test region) on the assumption that the low-zoom band sits
  at the start of the archive — eager egress far larger than a snippet's actual covering set; and (iii) it
  warms Control's **in-memory** chunk cache, which dies with the process, whereas 1a's on-disk cache survives
  reboots. `StreamingRegionSource.prefetchLowZoom` stays in the proxy (tested, unused) in case a *panning*
  surface ever wants it; a static snippet does not. **Nothing to build.**

  **As-built (track 1, 2026-07-14; on-device verified).** The per-camera throwaway sparse file is replaced by
  **one accumulating sparse `.pmtiles` per region** — `RegionRangeCache` (`:ui-map` androidMain): every range
  ever fetched (header, directories, metadata, tiles) is written into it and recorded in a `ranges.txt`
  manifest (a manifest is *required*: a hole and a legitimately-zero byte are indistinguishable on disk).
  MapLibre opens that file directly. Cache dirs are keyed by region id **+ archive size**, and stale builds of
  the same region are evicted on open; writers are serialised by a mutex; the manifest is only appended after
  an `fsync`, so it can never claim bytes the file lacks. `coveringRangesForRoute` gained
  `maxTilesPerZoom` (default 256): a zoom whose corridor exceeds it is dropped **whole** — MapLibre then
  overzooms the parent tiles, so the route still draws — and the drop is **reported to the caller, which logs
  it** (a silently-truncated zoom would strand parts of the route on holes, reading as a render bug).
  **Measured on-device (emulator, live CDN, `test-firenze`):** cold open of a Maps place fetched 4.35 MB;
  **re-opening it fetched 0 bytes**; a *different* place in the same region fetched only a **462 KB** delta
  (89 % saved — the ~21 KB prelude and the overlapping tiles were already resident); and after a
  **force-stop** (process death) the re-open still fetched **0** — the map now renders with no network at all.
  Timeline's route corridor behaves identically (cold 5.40 MB → warm 0). The sparse file reports an *apparent*
  11,351,199 bytes but occupies **4.6 M** on disk — the holes cost nothing. No `Mbgl` errors; renders verified
  by screenshot for both the point and route surfaces.
  ~~**Still open (small):** the cache has no size cap~~ — ✅ **capped 2026-08-11.** Sparseness kept a *static*
  camera cheap, but that was a property of the viewing pattern, not a bound: a heavy panner over a large region
  had no ceiling at all. `RegionRangeCache.enforceCeiling` now trims the cache root to
  `REGION_CACHE_CEILING_BYTES` (**128 MiB per app**), evicting **whole region directories, coldest first** —
  partial eviction would mean punching holes in a sparse archive and rewriting its manifest, for a cache that is
  re-fetchable by construction. The policy is a pure function (`regionCacheEvictions`, `commonMain`) so the
  rules are decided in `commonTest` rather than on a device; the platform side only gathers facts and deletes.
  As built:
  - ⚠⚠ **Enforced when a cache is opened, never while one is streaming.** A directory with a live instance is
    exempt: its in-memory manifest would go on claiming ranges the file no longer has, and `read` would serve
    the holes as **zeroes** — silent corruption, where exceeding the ceiling until the next open is merely
    untidy. So a long panning session can still overshoot; the next open reclaims it. Bounding *within* a
    session needs the live instance to be trimmable under a renderer holding the file open, which is a larger
    change than a budget.
  - ⚠ **Recency is a `used` marker touched on every open**, not the directory mtime — mtime only moves when
    bytes are *fetched*, so a region served entirely from cache would look cold precisely because the cache was
    working. Unknown ages sort **newest** (evicted last): a device that cannot report a time must not have its
    whole cache classed as coldest and wiped at once.
  - ⚠ Sizes are the manifest's **logical** bytes (the same rule `load` applies, keyed by start so a re-fetched
    range counts once) — so the budget and the `MapStream` log line can never disagree. Slightly under true
    disk use, which is the safe direction for a cache.
  - ⚠ This budgets the **streamed range cache only**. It must never reach a *downloaded* region — those are a
    deliberate user action living in Control's canonical store (D5). The two are in different sandboxes, so the
    distinction is structural rather than a rule to remember.

  **Filed as a cache, and therefore visible (2026-08-12).** ⚠⚠ It lived in **`filesDir`** — app *data* —
  which had three consequences, all wrong: the OS could not reclaim it under storage pressure, it did not
  appear as **Cache** in the system's per-app storage screen, and the user's only "clear this" button
  (which clears `cacheDir`) did not reach it. It is now in **`cacheDir`**, where the platform's own
  storage UI both sizes it and clears it — **no new UI and no new IPC**, which matters because the cache
  is in *each app's* sandbox and Control structurally cannot manage it. The move is safe by construction:
  `load()` already starts clean whenever the data file *or* its manifest is missing, and OS reclamation
  removes whole files — that branch must therefore never be "optimised" into trusting the manifest alone.
  A one-shot purge removes the old `filesDir` copy so the move does not strand it forever.
  ⚠ Backups were **not** a factor either way — the map apps set `allowBackup="false"`.

  ⚠ Control's *Offline maps* screen now says what it does and does not manage, because a list of
  downloaded regions otherwise reads as "all the map data on this device".

  **Still open:** staleness is keyed on archive **size**, so a rebuild that preserved the exact byte size would
  go undetected (the robust fix is the catalog's per-region **sha256**, which track 3b threads through anyway).
  And ⚠ the cache still lives in **each consuming app's** sandbox (Maps/Timeline/Fit each hold their own), so
  Control's *Offline maps* surface cannot enumerate or clear it — that is a platform fact, not a missing
  feature. What closed the gap was **reclassifying it as a cache** rather than building a cross-sandbox
  reporting protocol: in `cacheDir` the OS sizes it, reclaims it and clears it per app. An in-Control
  aggregate would need each app to report its usage and honour a deferred clear — eventually-consistent
  machinery whose "cleared" would routinely be a lie until the app next launched.

**Track 2 — real content + coverage** (ops + small code)
- **2a. Publish a bigger, leaf-bearing region** — **BUILT + SIGNED, publish pending** (needs the Spaces
  credentials; one command, below). `test-firenze` is **root-dir-only** (422 tile entries — they all fit in
  the root directory), so PMTiles *leaf-directory descent* has only ever been exercised by unit tests.
  **`test-toscana`** (`bbox 10.6,43.3,12.0,44.3`, added to `deploy/tiles/regions.json`) is the fix:
  **22,018 tile entries, 140 MB, z0–15** — and its **root directory is 62 bytes**, i.e. almost nothing but
  leaf pointers, so essentially *every* tile lookup descends through a leaf. It also **contains the Firenze
  demo camera**, so the existing demo data renders over it unchanged. Cut with a remote-range
  `pmtiles extract` off the Protomaps v4 planet (26 s; no planet download). Catalog rebuilt + signed with
  the real key (public half matches the one pinned in Control); the hosted `test-firenze` bundle keeps its
  exact sha256, so it needs no re-upload. **To publish** (as written then, against DigitalOcean Spaces):
  `SPACES_BUCKET=tenshu-tiles AWS_ACCESS_KEY_ID=… AWS_SECRET_ACCESS_KEY=… ./scripts/tiles-publish.sh`
  (dry-run verified). ⚠ Since 2026-09-15 tiles is R2-only and the script has no default endpoint, so this
  exact line now stops at its guard. Publish with `r2publish` from
  [`tiles-pipeline.md`](../how-to/tiles-pipeline.md) A4 instead. **Unblocks 2b + 2c.**
- **2a′. `coverage` was shipped empty — FIXED here (a real bug, found doing 2a).** `CatalogBuilder`
  hard-coded `coverage = ""` while `regions.json` carried each region's `bbox` all along. Every
  `GeoBounds.parseBbox("")` therefore returned null, so `RegionResolver.coveringRegion` **always** returned
  null and the terminus silently fell back to "the first catalog/installed region". Invisible with one
  region — **wrong the moment there are two** (a Firenze place could resolve to a Bayern bundle). Now emitted
  as `west,south,east,north`, the exact form the client parses. This is the substance of what 2d needed.
- ~~**2b. On-device leaf-descent verification.**~~ ✅ **DONE (2026-07-14) — and it found a real bug, which is
  precisely what it was for.** `test-toscana` is published and live (catalog + 140 MB bundle serving `206`
  ranges; the live catalog's bytes are byte-identical to the signed ones, so Control's pinned key verifies
  them). With a demo place inside Toscana but outside the Firenze box, Maps resolved `test-toscana` — and the
  first run rendered **nothing but errors**: `ranges=5` (the 3-range prelude + *two* tiles) and 28 ×
  `Mbgl … invalid tag exception`.
  **Root cause — `PmtilesDirectory.resolveTile` dropped every tile after the first behind a shared leaf.**
  It kept a `leafSeen` set and did `if (!leafSeen.add(entry.offset)) return null` — intended as "don't fetch
  the same leaf twice", but it *returned null for the tile*. A big archive's root directory is almost entirely
  leaf pointers (`test-toscana`'s root is **62 bytes** for 22,018 tiles), so nearly every tile in a viewport
  descends through the **same one or two leaves** — meaning only the first tile per leaf ever resolved. The
  sparse file then had holes where the directory advertised tiles, and MapLibre, reading zeros, failed them
  with `invalid tag exception`. **`test-firenze` could never expose this: it has no leaves at all.** Fixed by
  memoising the *decoded leaf* by offset (`leafCache`) instead of blocking repeat visits — the fetch-once
  property is kept, no tile is dropped. Regression test added (many tiles behind one leaf → all resolve, leaf
  decoded exactly once).
  **After the fix, on-device:** `region=test-toscana ranges=66 fetched=5,151,280 … (3 % of archive)` — **zero**
  `Mbgl` errors, Siena renders (screenshot). **This is the streamed default's whole thesis in one line: a
  140 MB region nobody would ever download, usable after fetching 3 % of it.** The leafless `test-firenze`
  path is unchanged (`ranges=55`, 0 errors), and it is still chosen over Toscana for a Firenze place —
  coverage-aware resolution (2d) confirmed against the live two-region catalog.
- **2c. Physical-phone pass** `S`. The streaming track was verified on the emulator; one maintainer run on
  hardware (ideally against `test-toscana` — a 140 MB region nobody would download whole is the honest demo
  of the streamed default).
- ~~**2d. Coverage-aware region pick in Maps.**~~ ✅ **DONE (2026-07-14).** `RegionRepository`'s
  `renderableStream` / `renderableRegionPath` now take the **place's coordinate**, and
  `ClientRegionRepository` delegates both to the shared coverage-aware `RegionTerminus` (deleting its
  duplicated catalog/read plumbing) — so all three `:ui-map` consumers resolve regions by **one rule**:
  tightest covering box, falling back to the first region when the point is null or outside every box (the
  basemap never blanks). `PlaceDetailViewModel` now **waits for the place** before resolving, since the
  coordinate is the input. ⚠ It deliberately waits (`mapNotNull { … }.first()`) rather than sampling the
  first emission: the projected repository emits an *empty list* before its store loads, so a plain `.first()`
  would conclude "no such place", skip resolution, and strand the snippet on the demo basemap forever — even
  once the place arrived. The dev-graph `MockRegionCatalogSource` also carried `coverage = ""`, so the dev
  graph could never exercise this either; it now carries the real DACH boxes. Proven by
  `RegionPickerPipelineTest`: Bern→`ch`, Wien→`at`, München→`de-bayern` (all three would have returned
  `de-bayern` — "the first region" — under the old rule), and an uncovered point still gets a basemap.

**Track 3 — offline-mode remainder** (§7 phase 6; Control + apps)
- **3a. Stream-vs-offline default toggle** `S`. **Now unblocked** — it was deferred as "premature until the
  render default is streaming", and that landed in Ph3b/Ph3c. ⚠ Worth re-scoping before building: now that
  an *installed* region is served locally **through the same `streamRegionRange` op** (the proxy's
  `StreamingRegionSource` is installed-first), "offline" already works transparently and the toggle is no
  longer about *rendering*. What it would actually control is **privacy**: whether the proxy may
  range-fetch tiles for a region that is *not* resident — i.e. whether the per-viewport request pattern
  (T-MR-03) reaches the CDN at all. That is a real switch, but it is a different one from the one this line
  originally described.
- ~~**3b. Per-region sha256 staleness refresh.**~~ ✅ **DONE (2026-07-14).** Control's *Offline maps* rows now
  read **"Update available"** with a refresh action when the resident bytes' `sha256` differs from the
  catalog's. Keyed on the **per-region sha256**, deliberately *not* the catalog-wide `buildDate`: every tiles
  build bumps `buildDate` for every region, so a `buildDate` comparison would flag *all* resident regions as
  stale on any build — even ones whose bytes are byte-identical — and ask for pointless multi-hundred-MB
  re-downloads. Updating *is* re-downloading (the downloader re-fetches + sha256-gates, and 3c makes the
  overwrite safe), so it reuses the existing `downloadRegion` op — no new op. The rule is a pure function
  (`offlineMapRegion`), unit-tested for all three states.
- ~~**3c. Store tmp+rename atomic swap.**~~ ✅ **DONE (2026-07-14) — and 3b is what made it load-bearing.**
  `DefaultRegionStore.add` wrote bytes straight over the live bundle. That was survivable while regions were
  only ever installed once; a *staleness refresh* re-downloads a region **that is already resident and being
  served** (`StreamingRegionSource` reads installed regions straight from the store), so for the whole write
  the file is neither the old bundle nor the new one — and a crash mid-write strands a **truncated file the
  index still vouches for**, with a sha it no longer has. Bytes now land on a `.part` path and are
  **renamed** in (atomic: `AndroidFileDatabaseImpl` → `File.renameTo`; `InMemoryFileDatabase` → map-key move;
  ⚠ the `FileDatabase` *interface default* is a non-atomic copy+delete, so a future impl that skips the
  override silently loses this). `remove` also had its ordering inverted — **index first, then bytes**:
  a crash between now leaks an unreferenced file (harmless; the next `add` overwrites it) instead of leaving
  an index entry vouching for a file that is gone, i.e. a region that reports itself installed and serves
  nothing.
  ⚠ **Verified by unit tests, not on-device**: driving Control's Offline-maps UI needs the realm passphrase
  (Control cold-starts locked), which the agent does not have. The rename itself is `AndroidFileDatabaseImpl`
  code already exercised by Drive's chunk reassembly.
- **3d. In-app contextual download triggers** — ✅ **Maps DONE (2026-07-14); Timeline still open.**
  `RegionTerminus.offerFor(point)` answers "which catalog region covers this, how big, already resident?"
  and `RegionTerminus.download(regionId)` keeps it — both on the terminus all three apps share, so
  Timeline/Fit inherit the mechanism. Maps' place detail shows the row under the map, and
  `PlaceDetailViewModel.keepOffline()` drives it (no-op when resident or already downloading — the bundle
  is hundreds of MB, a double-tap must not start it twice; a failed download clears the flag and leaves the
  offer standing).
  **⚠ Wording matters here and the row is deliberately not phrased as a fix:** the map is *already
  rendering* — it streams (D10). So the row reads **"Keep {region} offline · {size} · then the map works
  with no network at all"**, and names the size up front. What the download buys is availability without a
  network and **no per-viewport request pattern reaching the tile host** (T-MR-03) — not a working map. Once
  resident it states "{region} is available offline" and stops being an action. Only a region that genuinely
  *covers* the place is offered (no "first region" fallback here — that would invite downloading a bundle
  that does not contain your place).
  **On-device (emulator, live catalog):** the row offered `Firenze (test)` — the *tightest* covering region,
  not Toscana — tapping it downloaded 11,351,199 bytes into Control's store (exactly the catalog size, so the
  sha256 gate passed) and the row flipped to "available offline". **This incidentally gave 3c its on-device
  proof**: the bundle landed with **zero `.part` files left behind**, i.e. the staging + rename swap works on
  a real Android filesystem (Control's own Offline-maps UI is unreachable to the agent — the realm
  cold-starts locked).
  ✅ **Timeline DONE too (2026-07-14).** A trip is a *journey*, so unlike a place it can cross **several**
  regions: `RegionTerminus.offersForRoute(points)` returns the whole covering **set** (each point resolved to
  its tightest region, deduplicated — a dense polyline in one city yields one region, not one per vertex),
  `TripOfflineOffer` prices it as the **sum of the ones not yet resident** (a region already on disk is not
  charged for twice), and `keepOffline()` downloads **all** of them, one at a time, keeping partial success so
  a retry only re-fetches what is still missing. Offering just the centroid's region would leave the far end
  of the trip needing the network — precisely the case Timeline exists for.
  **On-device (emulator, live catalog):** a trip crossing Firenze (resident) → Siena (Toscana, not resident)
  offered exactly **"Keep Toscana (test) offline · 133 MB"** — Firenze correctly not charged for.

- ~~**⛔ 3e (found by 3d — HIGH). Whole-region download OOMs on any real region.**~~ ✅ **FIXED (2026-07-14).**
  **The bug:** `DefaultRegionDownloader` fetched the **entire bundle into a single `ByteArray`**, sha256'd
  that array, and handed it to `RegionStore.add`. Peak heap ≥ region size, against an Android growth limit of
  ~192 MB — so Control died with `OutOfMemoryError: Failed to allocate a 140165400 byte allocation` on the
  first real (140 MB) region. **"Make available offline" was broken for every region that isn't a toy**; it
  only ever worked because `test-firenze` is 11 MB. It predated the contextual triggers (Control's own
  *Offline maps* screen has the same path — its dev catalog lists a 210 MB Bayern); 3d just made it reachable.
  **The fix — stream it.** The body is now read a chunk at a time (1 MiB), and each chunk is *immediately*
  (a) fed to a new **incremental `Sha256Digest`** (`:core-common`; only a one-shot `sha256(ByteArray)` existed,
  which is what forced the whole bundle into memory in the first place) and (b) appended to a **staging file**
  via the store's new `beginStaging`/`writeStaged`/`commitStaged`/`abortStaging` surface. The bundle is never
  materialised. Only once the streamed digest matches the signed catalog's is the staging file promoted — by
  the atomic rename 3c already built. Consequences worth keeping:
  - **A half-finished download can never be served.** The canonical bundle is untouched until the commit, so a
    region that was already resident **stays resident and servable throughout a refresh**, and a failed
    attempt deletes its staging file rather than leaving a truncated bundle the index would vouch for.
  - **The R4 integrity gate is unchanged — and strengthened**: the digest is now over exactly the bytes that
    landed on disk, not a separate in-memory copy.
  **On-device proof (emulator, the exact download that OOM'd):** the 140 MB `test-toscana` now completes —
  the `.part` file was observed growing (29 MB → …) while the resident `test-firenze` sat untouched, then the
  final `test-toscana.pmtiles` landed at **140,165,388 bytes** (the catalog's exact size, so the streamed
  sha256 gate passed) with no `.part` left behind. **Control's Java heap peaked at 26 MB** for a 140 MB
  download (it previously tried to allocate 140 MB in one array and died), **zero `OutOfMemoryError`**, and the
  row flipped to "Toscana (test) is available offline".
- ~~**3f. Download UX — silent failure, invisible progress.**~~ ✅ **DONE (2026-07-14).** A multi-minute,
  hundreds-of-MB action that shows nothing and says nothing when it fails is the worst part of a feature that
  otherwise works.
  **Progress is a reactive push, and the download is background-live** *(reworked 2026-07-15 — the first
  cut polled a one-shot op; the maintainer rejected that shape because it reinvents two platform mechanisms)*.
  Two changes converge:
  - **Progress is a push flow, not a poll.** `downloadProgress(regionId)` is now a **stream op** (carries the
    `StreamOperation` trait) that reuses the framework's `StreamCallback`/`StreamElement` wire — the same
    push transport `.flow()` uses — but sourced from a **device-local, in-memory `RegionDownloadProgressHolder`
    `StateFlow`** the streamed downloader ticks per chunk, **not** a `select{}` over the synced object store.
    That is deliberate: region install/progress is a coarse-location signal kept off the synced realm (D5), so
    it must never become a synced `DynamicProperty` (a download bar must not appear on the user's other
    devices). The apps `.collect` it; the stream *ends* when the download finishes, and success/failure is read
    off `downloadRegion`'s own return. Both rows show a **determinate** ring + a live "…N %", Timeline's over
    the **whole set** (a trip crossing two regions does not snap back to 0 % when the second starts).
  - **The download survives backgrounding.** `DefaultRegionDownloader` runs the fetch on a **process-lifetime
    scope** (not the RPC scope, which `ProxyService.onDestroy` tears down) and **raises the shared
    `BackgroundLiveness`** under its own key for the download's duration — the identical mechanism the sync bulk
    drain uses, so Control's foreground service stays up and a 140 MB fetch is no longer killed when the app
    backgrounds. Repeated `downloadRegion` for the same region **joins** the in-flight job rather than starting
    a second fetch, so a client that reconnects after backgrounding awaits the download it kicked off.
    `BackgroundLiveness` gained a per-source key so the map download and the sync drain can hold it at once
    without clobbering.
  **Failure is surfaced, and retryable.** The row turns to "Couldn't download {region} — tap to try again",
  with the honest sub-line *"the map keeps working; it just still needs the network"* — because that is
  exactly the situation (the surface streams). Timeline retries only the regions that actually failed.
  ⚠ **Gotcha (same family as the apostrophe one):** compose-resources does **not** collapse the XML-escaped
  `%%` — it renders literally as `53%%`. Use a bare `%`.
  ~~**Still open:** **resume**~~ — ✅ **DONE (2026-08-11).** A re-attempt now `Range`-requests the tail
  instead of re-downloading hundreds of MB. `RegionStore.beginStaging(regionId, expectedSha256)` returns
  the reusable byte count and the downloader continues from there. As built:
  - ⚠⚠ **The digest argument is what makes it safe.** A `.part` is otherwise just "some bytes for this
    region id", and bundles are immutable dated *builds* — bytes staged before a rebuild belong to
    different content, and appending to them produces a file that fails the sha gate only after the whole
    remainder has been paid for. The store records the expected digest beside the staging file and
    discards bytes staged for anything else.
  - ⚠⚠ **A 200 answering a `Range` request carries the WHOLE bundle, not the tail.** Appending it would
    silently double the prefix. Servers may ignore `Range` legitimately, so this is a supported path, not
    an error: the staged bytes are dropped and the body is taken from the top.
  - ⚠ **The running sha256 is rehydrated by re-reading the staged bytes** — streaming digest state does
    not survive the process, and the R4 integrity gate is over the *whole* bundle. A local disk read, not
    a re-download, which is the entire point.
  - ⚠ **Failure handling had to split.** A **sha mismatch** discards the partial (the bytes are
    demonstrably wrong; keeping them would poison every later resume with the same gate failure), while a
    dropped connection / killed process / cancelled job **keeps** it — that is what there is to resume
    from. A corrupt partial therefore costs one wasted resume, then a clean restart.
  - Progress resumes from the staged byte count rather than snapping to 0 and re-climbing ground the user
    already paid for.

  **Still open: the Wi-Fi-only gate (D5).** Not built, and it is a design fork rather than a missing
  line — see the note below.

- ~~**3g. Wi-Fi-only gate (D5) — design fork**~~ — ✅ **DONE (2026-08-11), option (b): a per-call
  override.** `downloadRegion` refused nothing, so a tap could spend a mobile-data allowance on a 2 GB
  region without a word. The gate now lives in `DownloadRegionImplementation` and every caller is
  covered — Control's list and Maps'/Timeline's contextual rows alike.

  **Why (b) and not a preference.** The alternatives both failed the same test — *what happens when the
  user genuinely wants this download on mobile?* A preference-gated refusal answers "go and turn a
  setting off", which is a poor reply to a deliberate tap (and would have needed a **new** preference:
  reusing background-sync's `WifiOnly` would let a background setting silently block a foreground
  action). A UI-only check answers it well but must be rebuilt per app and covers no third-party caller,
  putting the policy in three places and enforcing it in none. As built:

  - ⚠ **The parameter is optional and the client sends it only when `true`** (`DownloadRegionRo`, the
    `AwaitInviteAckRo` shape), so an ordinary download puts the *same bytes on the wire* as before it
    existed. The new field travels only on the deliberate override.
  - ⚠⚠ **The refusal is a typed `MeteredNetworkRefusedError`**, not a generic failure — that is the whole
    point. A caller cannot offer "download anyway" if "you are on mobile data" is indistinguishable from
    "the network died"; it would show *"check your connection"* to someone whose connection is fine.
    Registered in the **common** module with a `dtoFactory`, since the proxy *builds* it — the omission
    that left `reverseGeocodePlace` throwing for a day (`GeocodedPlace` unregistered), and test-pinned here by reading a field
    off the error after a real client round-trip.
  - ⚠ **The gate is in the executor, not the downloader.** `startOrJoin` means a second call *joins* an
    in-flight job, and joining a download the user already consented to must not be refused. This is
    about *starting* one.
  - ⚠ **`MeteredNetworkSignal.Unknown` is the default and fails OPEN.** A proxy with no signal wired
    downloads exactly as before. Turning "we cannot tell what network this is" into "you may not
    download" would be a worse failure than the one the gate guards, and a silent one.
  - Control asks with a dialog naming the region **and its size** — the size is the reason to hesitate —
    and a confirmation retries the same region with the override.

  **Still open:** ⚠ only **Android** has a real signal. `DesktopNetworkStateProvider` and
  `IosNetworkStateProvider` both hardcode `UNMETERED`, so the gate is inert there — the same known gap
  the Path-A sync constraint gate has, and `IosNetworkStateProvider` remains the single place to
  implement `NWPathMonitor`. ⚠ Maps and Timeline get the *refusal* but no prompt yet: their contextual
  rows surface it as a failed offer, so on mobile the way through is Control's list. Their own dialogs
  are UI work in two more apps, deliberately not bundled here.

**Track 4 — platform parity** (own tracks)
- **4a. Desktop live basemap** — **externally blocked** on MapLibre's desktop offline path; desktop stays
  schematic-only (`OfflineBasemap.desktop.kt` / `RouteBasemap.desktop.kt` ignore both region params). Watch
  upstream; nothing to build here.
- **4b. iOS `:ui-map` actual** `L`. The native renderer plus the D6 direct-fetch variant and its threat-model
  note. A track of its own.
- **4c. iOS region *data* path** ✅ **done (2026-08-01, "C3")** — and deliberately **independent of 4b**: the
  Fit/Timeline iOS surfaces hardcoded `regions = null`, so `regionBundlePath`/`regionStream` were structurally
  null on the platform no matter what a renderer did, and Timeline's `TripOfflineRow` could never appear.
  As built:
  - **`IosRegionBundleStager`** (`:map-region-app` `iosMain`) — the Darwin twin of `JvmRegionBundleStager`:
    positional `NSFileHandle` writes into `<id>.pmtiles.part`, atomic `moveItemAtPath` on commit.
  - **Location decision: `<Application Support>/map-regions`, excluded from iCloud backup.** Application
    Support (never Caches) is both the repo's iOS precedent (`iosRecordedWorkoutOutbox`, `IosOpenMoveJournal`,
    `IosRecordingJournal`) and the direct analogue of the Android binding this class mirrors
    (`filesDir/map-regions`, never `cacheDir`) — a staged bundle is the file the renderer holds open, so the
    OS reclaiming it mid-render is exactly the failure the atomic commit exists to prevent. Backup exclusion
    because the bundle is a re-drainable derivative of the proxy's canonical store.
  - **`IosRegionSupport`** (`:tenshu-sdk-ios`) adapts the shared `RegionTerminus` to each app's
    `RegionBundleResolver` seam; `{Fit,Timeline}Surface` gained an installed resolver, cleared on the
    mock/not-connected tiers, and it is now passed to `tripViewModel` / `momentViewModel` / `placeViewModel` /
    `workoutViewModel`. Installed by both tiers that own a live client — `{Fit,Timeline}LocalRuntime.install()`
    (route 1) and `installRealClient` (route-2 EPC).
  - **Reachability verified, not assumed:** the route-1 graph already hosts `MapRegionService` —
    `buildProxyModule()` registers its schema modules and the iosMain `platformImplementationFactory` binds
    `mapRegionServiceProxyImplementationFactory`. `IosRegionResolverWiringTest` stands up the real production
    proxy graph, lands a region in its store, and drains it through the new stager end to end (no catalogue
    fetch ⇒ network-free).
  - ⚠ **The download half was not durable on iOS — CLOSED 2026-08-03.** `DefaultRegionStore` writes bundle
    bytes through `FileDatabase`, and the iOS `platformProxyModule` bound `InMemoryFileDatabase`, so a
    *downloaded* region lived in RAM and died with the process. `IosFileDatabase`
    (`ios-fit-timeline-maps-parity.md` §3.2.1) makes it durable; the streamed read path (D10, what the detail
    VMs prefer) was never affected. ⚠ What is still owed before the offline-offer UI ships is *verification*,
    not a store: nothing has watched a real region download survive a relaunch, and the region picker's
    "downloads last for this session only" copy stays until something has.
  - Follow-up (not done): the Compose-side `TerminusRegionBundleResolver` twins live in the `-shared-ui`
    modules, which iOS cannot depend on, so `IosRegionSupport` re-implements ~10 lines of delegation per app.
    Single-sourcing means relocating them into `-shared-data` and giving `:map-region-app` a `js` target
    (`:fit-shared-data` has one) — a cross-platform build change, not iOS wiring.

**Track 5 — commerce + ops hardening** (§7 phases 3–4; infra)
- **5a. Ops hardening** `L` — ⚠️ **the one gating item before any wider/public exposure.** The public
  streaming endpoint is a **denial-of-wallet** surface (D1 / §7 phase 4): serve from a zero-egress host
  (R2 + Cloudflare), edge rate limits + bot rules, hotlink deterrence, a hard egress/budget alarm + spend cap,
  CDN cache config (immutable dated bundles long-TTL; `catalog.json` short-TTL + flush), aggregate-only
  cost dashboards, build-cadence automation, old-build GC. Not needed for the current demo; **required**
  before real-world rollout.
- **5b. Voucher gating** `M`. `aud=tiles` vouchers + a verify→presign gateway + default-off enforcement flag.
  Depends on the commerce plane. Note (D10/§7 phase 3): gating *streamed* tiles has its own tension — gating
  likely lands on the *offline prefetch*, not the per-viewport stream.
  ⚠ **A browser can never present a voucher** (§Web scope W.4, decided 2026-10-28): a voucher is minted
  against a commerce session, and the web is always a session-less device. Streamed tiles on web are
  ungated today, as on native. If this phase ever gates the *stream* (a session or a rate), the web needs
  its own credential path, or it loses the map.

**Track 6 — later / v2+** (roadmap)
- Onboarding two-option screen (§7 phase 7); blinded voucher issuance; delta/differential region updates;
  the world-overview bundle (§8 Q2).
- **Cosmetic:** demo self-consistency — Timeline's mock trip still reads "Lisbon" / tagged Portugal while its
  route (and Maps' place addresses) sit on the Firenze extract. Accepted for a throwaway demo; tidy when real
  content lands.

**Recommended sequence.** ~~Track 1~~ ✅ done → **2a next** (cheap, unblocks all leaf/hardware verification) →
2b–2d → Track 3 (user-facing polish; 3a is nearly free now) → **5a before any real-world exposure** →
4b / 5b as scoped follow-on tracks → Track 6 as roadmap.

---

## Web scope

**Status:** DECIDED 2026-10-28 (see "Decided" below). Was a question PR (`web-app-suite.md` §8.1) opened by the W-MAP slice (Wave 3); the feature PRs cite this section.

The shape the web inherits from native D10, stated once so the questions are about the deltas only:

- ⚠⚠ **On web, the "proxy" is the apex runtime, not the app frame.** A map app (`maps.tenshu.app`, …) is an
  own-origin page that reaches data only through the bridge (`web/sdk/src/bridge.ts`), exactly as a native
  map app reaches Control over IPC (D6). MapLibre GL JS runs in the app frame and asks for bytes through a
  MapLibre `addProtocol` source; the app frame posts `mapRegion.streamRange` to the apex; the **apex
  runtime** (`:tenshu-sdk-js`, the in-process proxy) serves it from `StreamingRegionSource` — the same
  `commonMain` class Control runs — and only the apex would ever open a connection to the tile host. So
  every question below is about what the **apex origin** may fetch, and the app frame's own network
  surface stays "its own origin only" (glyphs + its bundle).
- The style, glyphs and archive format are the native ones: `:basemap-core`'s style JSON, the shipped
  `Noto Sans` `.pbf` ranges (served from each map app's own origin, not a font CDN), Protomaps v4 PMTiles.
  Nothing in W-MAP fetches a third-party tile, glyph, font or sprite.

### W.1 How the browser reaches tiles (the transport)

**What the browser can do.** `fetch()` with a `Range: bytes=a-b` header. A single byte range is a
CORS-safelisted request header (Fetch standard, "CORS-safelisted request-header", `range`), so a
cross-origin ranged GET needs **no preflight**, but the response is readable only if it carries
`Access-Control-Allow-Origin` for the page's origin, and `Content-Range` is readable only if listed in
`Access-Control-Expose-Headers`. A 206 body arrives as an `ArrayBuffer` (`Response.arrayBuffer()`).
**What it cannot do.** Read a cross-origin response the server did not opt into (CORS); reach the tile
host without the user's own IP showing; hide its `Origin` header on a cross-origin fetch.

Today none of this works: `deploy/cloudflare-tiles/main.tf` sets **no CORS, deliberately** ("tiles are read
by Control's HTTP client, never by a browser page"), so a browser `fetch` of
`https://tiles.service.tenshu.app/catalog.json` is unreadable. W-MAP therefore routes every tile-host byte
through **one seam** (`WebTileTransport` in `:tenshu-sdk-js`, §W.6) whose shipped implementation refuses
with a typed `error_map_transport_unconfigured`, and the renderer is exercised against a fixture.

| | (a) CORS on the R2 bucket for the apex origin | (b) Same-origin passthrough on the apex (`reverse_proxy /tiles/*`) | (c) Others |
|---|---|---|---|
| Change | A `cloudflare_r2_bucket_cors` rule: `GET`/`HEAD` from `https://tenshu.app` (+ `https://tenshu.xyz` for the dev lab), exposing `Content-Range`, `Content-Length`, `ETag`. **Never** an app origin — the app frames never fetch tiles. | A Caddy route on the apex site that forwards `/tiles/*` to `tiles.service.tenshu.app` (`header_up Host`), so the runtime fetches `/tiles/catalog.json` same-origin. | (c1) A Cloudflare Worker / Transform Rule adding the CORS headers at the edge: privacy as (a), more moving parts. (c2) A relay-side passthrough: **reject** — it would put the viewport pattern on the sync relay, the one plane D10 promised never sees it (T-MR-03 "the observer is a CDN, not the sync relay"). (c3) No streaming on web (render only a bundled extract): honest but useless outside the demo city. |
| Who sees the viewport pattern | Cloudflare, by the **user's IP** — the same observer and the same signal as native D10, where the CDN sees Control's IP, which *is* the user's device IP. ⚠ One addition: the request carries `Origin: https://tenshu.app`, so the CDN can tell a web reader from a native one. | **Our apex box**, by the user's IP (Caddy's `access_log` snippet would record every tile path — it would have to be excluded for `/tiles/*`). Cloudflare sees only the apex box's IP: the CDN loses the user's IP, but our own server gains IP + viewport, which is a plane we operate and could be compelled to log. | (c1) as (a). |
| Cost / ops | Zero egress (R2), unchanged. Hotlinking: CORS restricts *reading in a browser*, not fetching, so the denial-of-wallet posture (D1, §7.1 5a) is unchanged. | Every streamed byte transits the apex box: **per-GB egress on Scaleway** comes back (the D1 denial-of-wallet argument, now on our own box), an extra hop of latency on every tile, and the apex becomes a load-bearing tile path. | — |
| CSP | ⚠ The apex has a CSP (`web/apex/csp/csp.ts`): `connect-src 'self' blob:` plus `connectOrigins(env)`. The W.1 PR must add `https://tiles.service.tenshu.app` there, or every tile fetch is CSP-refused. App frames are unaffected. | `connect-src 'self'` on the apex keeps holding. App frames unaffected. | — |
| Threat model | T-MR-03 amendment: "a browser device's viewport stream reaches the CDN from the user's IP, labelled as web by `Origin`". No new observer. | T-MR-03 amendment naming **the apex host** as a new viewport observer, plus a deploy-checked "no access log on `/tiles/*`" (the D4 "make it a checked deploy assertion" pattern). | — |

**Recommendation: (a)**, scoped to the apex origins only. It reproduces native D10 exactly — the CDN, and
only the CDN, sees the device's IP and viewport — and keeps both our servers and the relay out of the
location-signal path; (b) moves that signal onto infrastructure we operate and re-introduces per-GB egress.
The one-line threat-model amendment (the `Origin` label) lands with the PR that wires the transport.
Owed by the maintainer, not a cloud run: the Terraform change and the comment in `main.tf` that says no
browser reads the bucket.

### W.2 Catalog signature verification in the JS runtime

**What the browser can do: verify, with the native code.** `RegionCatalogVerifier` is `commonMain`
(`:map-region-service-proxy`), and the JS runtime already has an Ed25519 backend
(`NobleEd25519Backend`, `core-common/src/jsMain`, the one `ShareViewer` uses). W-MAP wires exactly that:
the catalog is fetched through the transport seam, verified against the **same pinned key** Control pins
(moved to `:map-region-service-proxy` so it is spelled once), parsed, and filtered to tile schema ≤ v4 —
the native `HttpRegionCatalogSource` path with only the byte fetch swapped.

**If it were not available,** the only honest answer would be to fail closed: no verified catalog ⇒ no
region ⇒ the map shows "Map unavailable", never an unverified region. W-MAP fails closed today (a bad
signature is `error_map_catalog_unverified`, never an empty catalog).

⚠ **What the signature does and does not buy on web.** It defends T-MR-02 (a compromised bucket/CDN cannot
redirect the runtime to a tampered archive) exactly as on native. It does **not** defend against a
compromised **apex** server: on web the verifier, the pinned key and the code that calls it are all served
by the apex, so whoever controls the apex controls the check — the standing web-crypto caveat
(`docs/web/architecture.md`), not a new one. Streamed tiles stay unverified per T-MR-04 on both platforms.

**Recommendation:** keep verification (built); no decision needed unless the maintainer wants a different
key for web, which nothing argues for.

### W.3 Caching

**What the browser can do.**
- *Memory*: `StreamingRegionSource`'s `InMemoryStreamingRegionCache` — lost on reload.
- *HTTP cache*: whether the browser's HTTP cache serves a repeated `Range` request from disk is
  engine-specific (Chromium stores 206 bodies as sparse entries; others are less predictable), and depends
  on the headers R2 returns for `.pmtiles` objects — not something to build a guarantee on.
- *Cache API / IndexedDB*: a persistent partial archive, by quota.

**What it cannot do.** Keep anything it is told to keep: storage is best-effort and **browsers evict an
origin whole** (`drive-web-parity.md` §1.2d). Every byte of tile cache W-MAP adds to the apex origin is
quota pressure on the *same* origin that holds the realm identity, the master keys and the sync store —
a tile cache that grows to tens of MB raises the odds that the browser deletes the device itself.

Privacy: a persistent tile cache is an on-disk record of where the user looked, readable by anyone with
the browser profile — the native cache has the same property, but a browser profile is more often shared.

**Recommendation: memory only, bounded**, for v1. W-MAP ships the native cache class with a tab-sized bound
(64 chunks × 256 KiB = 16 MiB, against native's 1024 × 256 KiB = 256 MiB) and no persistent cache, plus
whatever the HTTP cache gives for free. Region **download** — "make available offline" — is out of scope
on web (`web-app-suite.md` §2, Maps row), so offline mode, the native zero-per-viewport-egress escape
hatch of T-MR-03, has **no web equivalent**; the web's only mitigation is the in-memory cache. Revisit
persistence only with `navigator.storage.persist()` (which nothing on web calls yet).

### W.4 Gating — the D4 voucher and the D9 one-free-region cap

- **D4 voucher.** Gates whole-region *downloads* only; streamed tiles are ungated in v1 natively (§4a,
  "no voucher … nothing gated in v1"). Web has no download, so D4 has nothing to gate. ⚠ And a browser
  **could not present one anyway**: the voucher is minted against a commerce session, and the web is
  always a session-less device (`JsRuntimePlugin.kt`, `RuntimeContext.entitlementCredentialProvider` KDoc —
  the commerce session is device-local and never syncs). If the gating phase (§7 phase 3; D10's "a
  streaming *session/rate*") ever gates *streaming*, the web needs its own credential path — flag it there.
- **D9 cap.** Counts **installed** regions in the proxy's canonical store. The web runtime has no store
  (W-MAP wires an always-empty one) and no download, so the cap is vacuous on web.

**Recommendation:** neither applies to streamed tiles on web; nothing to build. Record the
session-less-voucher constraint in §7 phase 3 so the gating phase does not discover it late.

### W.5 Other questions found while building

1. **Low-zoom prefetch (D10, "on by default").** Native warms ~4 MB on first touch of a region, partly as a
   privacy measure (it coarsens T-MR-03). In a tab it is unprompted bulk egress; W-MAP does **not** call
   `prefetchLowZoom` (the brief bans prefetch in a tab). *Recommendation:* keep it off on web; MapLibre
   already asks only for visible tiles.
2. **One region per map.** W-MAP renders the tightest catalog region covering the camera centre (native
   `RegionTerminus.renderableStream` does the same). A Timeline trip or Fit route that crosses a region
   boundary shows tiles only for the centre's region; the rest of the line draws over the background.
   Multiple region sources (one per covering region, sharing the style's layers) is buildable later.
   *Recommendation:* accept for v1; revisit when the first cross-border trip is reported.
3. **Chunk size.** The source fetches 256 KiB-aligned chunks (`StreamingChunkBytes`), so a single small
   tile miss costs a 256 KiB range from the CDN and a 256 KiB postMessage at most. Kept identical to
   native so the two platforms produce the same CDN pattern; smaller chunks would mean more requests.
4. **Transit lines, rail matching, reverse geocoding** (`linesServing`, `railLinesIn`,
   `reverseGeocode*`) are not wired on web; the first needs the same transport, the last needs Photon
   egress from a tab (a consent question of its own). Out of W-MAP; the Maps run raises them if it needs them.
5. **ODbL attribution.** Native maps carry "© OpenStreetMap contributors" (`BasemapAttributionTest`);
   `<TenshuMap>` carries the same credit as MapLibre `customAttribution`. Not a question — recorded so no
   consumer turns it off.

### W.6 What W-MAP builds regardless of the answers

The SDK + bridge for catalog lookup and `streamRegionRange` (behind the one transport seam, refusing with a
typed error until W.1 is answered), `@tenshu/map-kit` (MapLibre GL JS + `pmtiles`, a custom `Source` whose
`getBytes` calls the bridge), and a dev harness that renders the Firenze extract through the same `Source`
over a fixture fetcher. No background work in the tab: no download, no prefetch, no update check.

### Decided (maintainer, 2026-10-28)

- **W.1 (a): CORS on the R2 bucket, apex origins only** (`https://tenshu.app`, and `https://tenshu.xyz`
  for the dev lab); `GET`/`HEAD`, exposing `Content-Range`, `Content-Length`, `ETag`. Never an app origin.
  The CDN stays the one observer of IP + viewport, as native D10. Lands as one local PR: the
  `cloudflare_r2_bucket_cors` rule plus the corrected `main.tf` comment, the transport wired to the tile
  host, and the T-MR-03 amendment (the `Origin: https://tenshu.app` label). Applied by the maintainer.
- **W.2** Keep catalog verification as built (same pinned key, fail closed).
- **W.3** Memory-only, bounded (16 MiB); no persistent tile cache.
- **W.4** Neither D4 nor D9 applies to streamed tiles on web; record the session-less-voucher constraint
  in §7 phase 3.
- **W.5** No low-zoom prefetch in a tab; one region per map for v1; 256 KiB chunks as native; ODbL
  credit stays on. ⚠ Because the region is looked up at ONE point, a `bounds` camera may carry an
  `anchor` (map-kit): the anchor picks the region and the camera fits only the points
  inside its region box — else the middle of Berlin + New York (the Atlantic) picks none. Without an
  anchor the box's middle picks it, as before; either way a box across ±180° is 2° wide, not 358°.

**W.1 as built (2026-10-28).** `cloudflare_r2_bucket_cors.tiles` (`deploy/cloudflare-tiles`, applied
2026-10-28) admits `GET`/`HEAD` from `cors_origins` = the two apex origins, exposing `Content-Range`,
`Content-Length`, `ETag`. The runtime's transport is `FetchTileTransport` on `TILES_HOST_URL` (now
spelled once in `:map-region-service-proxy`, Control's `tilesHostUrl` reads it too): `credentials:
omit`, `referrerPolicy: no-referrer`, one `Range` per read; a `200` to a ranged read is sliced, any
other status throws. T-MR-03 carries the web paragraph. Verified with curl: the apex origins get
`Access-Control-Allow-Origin`, `drive.tenshu.xyz` and a foreign origin get none.

---

## 8. Open questions

- **Q1 — Region granularity + starter set. RESOLVED by D7 (v0.3):** country-level with
  sub-country splits; borrow Organic Maps' `.poly` boundary set (vendored into
  `deploy/tiles/regions/`), Geofabrik as fallback; starter set = DACH + one city extract.
  Remaining empirical check (not a blocker): confirm the split sizes against real
  `pmtiles extract` output once the pipeline runs.
- **Q2 — World overview.** Ship a low-zoom (z0–z6?) whole-world bundle in the app (or as a
  first auto-download) so the map-of-all-places never renders schematic-only for travel
  pins? Needs a sizing spike; deferred until the pipeline exists.
- **Q3 — Build cadence. RESOLVED by D8 (v0.3):** quarterly scheduled builds + an on-demand
  manual trigger. (Revisit only if real pipeline cost or a freshness complaint argues
  otherwise.)
- **Q4 — Free-tier allowance. RESOLVED by D9 (v0.3):** one *active* region free on Free tier,
  paid tiers unlimited; the cap is enforced **proxy-side** (the store owner, D5), keeping the
  gateway ownerless/region-blind (R2). Soft limit by design — consistent with §6 (fairness,
  not cost recovery).
- **Q5 — Cross-app copies (downgraded in v0.2).** D6 makes the network download happen
  once, proxy-side; what remains is at most one *local* copy per consuming app's sandbox
  (MapLibre needs a real file path). Acceptable for v1 (Timeline map surfaces are still
  deferred); revisit in-place sharing (fd/content-URI tricks per platform) only if the disk
  cost bites.
- **Q6 — The egress component. RESOLVED by D6 (v0.2):** the proxy performs all downloads;
  map apps stay `INTERNET`-stripped; iOS uses the declared direct-fetch variant.
- **Q7 — `RegionService` home.** Does the proxy-side implementation live as a new
  `:map-region-service` module (full service-module pattern: `-client`/`-common`/`-proxy`),
  or as a capability of an existing proxy service (it is closer to content delivery than to
  a domain service)? Decide at phase-2 scaffolding; the app-facing interface is the same
  either way.

---

## 9. Implementation notes (for Claude Code sessions)

Repo-specific pointers so an implementing session starts from the right seams instead of
rediscovering them. Read first: this doc; `maps-app.md` §3 + §6 Ph7b as-built;
[`ui-map/ui-map-agent.md`](../../ui-map/ui-map-agent.md); `commerce-billing-plane.md` §4;
`drive-streaming-preview.md` §3.5. House rules that bite here: **#2** (threat-model
amendment in the same PR for phases 2/3/5), **#3** (`RegionService` ops are non-suspend —
`IoOperation` + `TaskInteractor`, ADR 0012), **#4** (catalog/region types that cross the
IPC wire go in the schema DSL, not ad-hoc data classes), **#7** (tests over screenshots),
**#8** (anything touching `maps-android`/`:control` runs `scripts/android-smoke.sh`).

**Phase 1 — pipeline.** No Kotlin. Home: `deploy/tiles/` (infra lives in `deploy/`, shell
in `scripts/` — follow the `deploy/digitalocean-*` Terraform + `redeploy-*.sh` pattern for
the bucket/CDN). Tools: Planetiler (JVM) for the planet build, the `go-pmtiles` CLI for
`extract`/`show`/`verify` — the exact workflow that produced the Ph7b Firenze extract
(`ui-map-agent.md`). Region definitions are a checked-in JSON/GeoJSON file; **do not check
in `.pmtiles` outputs** (the ~1.3 MB bundled demo in `ui-map/src/androidMain/assets/` stays
the only binary in-tree). Manifest signing: reuse the in-tree Ed25519 primitives
(`org.tenshu.core.common.security.Ed25519Backend`) via a small JVM signer tool so the
client-side verifier is code that already exists — don't introduce a second signature
stack. Pin `schemaVersion` to Protomaps **v4**: the runtime style JSON in
`OfflineBasemap.android.kt` is built against it, and a silent v5 build would render blank.

**Phase 2 — `RegionService` + terminus.** Default answer to Q7: scaffold
`:map-region-service` (+`-client`/`-common`/`-proxy`) by analogy to the `:place-service`
P1 scaffold (`timeline-services.md` §3.1) — revisit only if it turns out to be two files.
Progress is a stream over the existing Stream subsystem; bulk bytes ride the **pull
range-source** (S2a `StreamingContentSession`) — note it is currently coupled to
`FileChunkDecryptor`; region bundles are public plaintext, so extract/bypass the decrypt
stage rather than inventing a parallel range op (the `drive-streaming-preview.md` §3.0
"two modes over one transport" analysis is the map). App terminus in `:ui-map`: mirror the
Ph7b one-time-copy path in `OfflineBasemap.android.kt` (copy to `filesDir`, open
`pmtiles://file://…`; **never** `pmtiles://asset://` — native SIGABRT in sdk 13.0.2 that
`onMapLoadFailed` can't catch, already documented in `maps-app.md` Ph7b). Desktop keeps
the schematic `actual`; build the store desktop-side anyway so the swap is ready when
MapLibre's desktop offline path lands. The standing regression: the Ph7b emulator check
(map surface renders with `INTERNET` `tools:node="remove"`d) must stay green — fold it
into the smoke gate rather than a one-off.

**Phase 3 — voucher + gateway.** The token is 90% built: `EntitlementTokenClaims` /
`encodeEntitlementToken` in `:core-common` (`org.tenshu.core.common.security`), and
`EntitlementTokenMinter.mint(ownerId = null, …)` **already supports the unbound
(blinding-ready) form** — the voucher is that, plus a **new audience claim** (`aud`).
Adding `aud` is a small compatible claims migration: absent `aud` ⇒ relay (legacy), and
the relay verifier must reject `aud=tiles` so vouchers can't be replayed against storage
enforcement (and vice versa). Mint endpoint lives in `:commerce-service` behind the
existing `SessionGate`, mirroring the token-pull route. The gateway is a verifier-only
Ktor app with `RelayEntitlementVerifier` as the template (stateless, configured public
key, no callback to commerce on the hot path) whose only job is verify → S3-presign
against Spaces → `302`/URL. Typed errors follow the relay's precedent
(`402`/`403`/`429`); enforcement ships behind a default-off flag exactly like
`ENTITLEMENT_ENFORCE`. Rate limiting: Ktor `RateLimit` plugin keyed on voucher id,
in-memory/TTL only — the "no durable voucher-id logs" rule (D4) is a deploy-time
property, so make it a checked deploy assertion, not a comment.

**Verification per phase** (House Rule #7): phase 1 is `curl -r` range checks + `pmtiles
verify` + signature round-trip in CI; phase 2 is unit tests on catalog parse / sha256 /
store lifecycle + the emulator smoke; phase 3 is verifier unit tests (expiry, wrong `aud`,
wrong key, replay) + an end-to-end against commerce in Stripe test mode
(`scripts/run-control-desktop-commerce.sh` is the harness precedent). A real-region
on-device pass (download DACH on hardware, airplane mode, pan) closes each release.

---

## 10. Version

**Web scope (question) · 2026-10-28 — adds [§Web scope](#web-scope).** The W-MAP slice's question PR
(`web-app-suite.md` §8.1): how a browser reaches tiles, catalog verification in JS, caching, gating, and the
smaller deltas. Open until the maintainer answers; no code.

**Pipeline execution model · 2026-07-21 — adds [D11](#d11-the-pipeline-splits-at-the-signature-bundle-production-is-automatable-catalog-signing-never-is-resolves-where-does-the-publish-actually-run-2026-07-21).**
Answers the question D2/D8 left open — *where* the publish runs and *which machine holds which secret*.
The pipeline splits at the signature: bundle production is automatable behind a rotatable bucket-scoped
Spaces key, catalog signing stays offline on the maintainer's machine (signing needs only the ~2 KB of
hashes, never the bundles). Records that no DO secret store is the answer because none exists worth
building on, that execution profile is per *region class* (regional extracts run locally against a
**remote** planet URL; only the `global` planet publish needs an in-region VM), and that CI's role is the
trigger and the ephemeral droplet, never the data path (hosted runners: ~14 GB disk, 6 h cap). Ships with
the streamed-digest fix that made a `global` publish possible at all (`sha256File`) and a rewritten
[`tiles-pipeline.md`](../how-to/tiles-pipeline.md). No client or wire change.

**Backlog consolidation · 2026-07-14 (no design change).** The status header now reflects reality (the doc
was still marked "proposal, no code" while Ph0–Ph3c were built and on-device verified), and the follow-ups
that had accumulated as per-phase **Outstanding** notes are merged into one sequenced list —
**[§7.1 Remaining work](#71-remaining-work--consolidated-backlog-2026-07-14)** (six tracks: streaming
hardening; real content + coverage; offline-mode remainder; platform parity; commerce + ops hardening;
later/v2+). The per-phase notes stay as frozen history. Two things worth surfacing from the merge: PMTiles
**leaf-descent is still only unit-tested** (the `test-firenze` extract is root-dir-only — publishing a
bigger region unblocks it), and **ops hardening (5a) is the one item gating any wider exposure** (the public
streaming endpoint is a denial-of-wallet surface).

**Region-delivery v0.4 · 2026-07-12** reframes delivery to a **streamed-default spectrum (D10):**
streaming on demand is the default (low-zoom prefetch on), whole-region download becomes the "make
available offline" privacy/offline opt-in; both ride one range-provider serving `OfflineBasemap` +
`RouteBasemap`. Supersedes the D1 anti-decision against remote range sources, revises R1, and adds the §5
viewport-pattern surface + streamed-vs-full integrity delta. Onboarding + Control manage-maps are explicit
post-v1 phases (§7 phases 6–7); the cross-service account↔services surface is carved to its own note (§7
phase 8). Prior:

Region-delivery v0.3 · 2026-07-11 (v0.2 2026-07-10, v0.1 same day). Specifies the previously-undefined
"content-delivery machinery" for `maps-app.md` §3/Ph9 and `timeline-app.md`'s `:ui-map`
surfaces. Locks D1 (static range-served storage, no tile server, no live remote pmtiles
sources), D2 (Planetiler → immutable dated per-region extracts), D3 (signed public
catalog), D4 (subscription-gated via ownerless `aud=tiles` vouchers, blinding-ready,
download-forever once fetched), D5 (proxy-owned canonical store, `:ui-map` terminus, Maps
owns the picker), D6 (proxy-mediated delivery per the whitepaper component model; Tenshu
binary IPC on the app↔proxy leg only — plain HTTPS+JSON to commerce, plain ranged HTTPS to
the tile host; iOS direct-fetch variant). v0.2 resolves Q6 and downgrades Q5, and adds §9
implementation notes for Claude Code sessions. **v0.3 resolves the three implementation-shaping
opens: D7 (region granularity + borrow OM's `.poly` set, don't self-maintain — Q1), D8
(quarterly build cadence + manual trigger — Q3), D9 (one free active region, proxy-side cap
keeping the gateway ownerless — Q4).** Remaining opens: Q2 (world overview), Q5 (cross-app
copies, downgraded), Q7 (`RegionService` home, decided at phase-2 scaffolding). No code; phases §7.

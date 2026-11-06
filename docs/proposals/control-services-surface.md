# Control Services surface — one account, many connected services

**Status:** Draft v0.1 — design note, no code (2026-07-12). Carved out of
[`maps-region-delivery.md`](maps-region-delivery.md) §7 phase 8: the tiles/maps service is the
forcing function — it is the *second* Tenshu backend service, with a **different gating model**
than storage (public/free in v1, possibly metered later; storage is account + quota), and that
mismatch reveals that the current single-card Services screen won't scale as more "External Tenshu
Services" land. This note is the home for the cross-cutting **account ↔ connected-services** UX
question, kept out of the maps doc because it governs Storage, Maps, and every future service.

Companion reading: [`whitepaper.adoc`](../whitepaper.adoc) (§"external Tenshu services" + the
component/manifest terminology + "the user can select an alternative service provider … for each"),
[`commerce-billing-plane.md`](commerce-billing-plane.md) §2 (three planes) + D5 (entitlement is the
relay's only notion of "paid"), [`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md)
(the account lifecycle this surface *reads*), [ADR 0019](../adr/0019-storage-capacity-gating.md)
(storage gating is UX; the relay enforces), [`core-realm-data.md`](core-realm-data.md) (the generic
card-walker this note proposes to reuse), [`onboarding-low-friction.md`](onboarding-low-friction.md)
§6.1 (the Services tab).

No code. Threat-model interplay (blinded entitlements) is called out in §5/§7 and lands with any
implementing phase per House Rule #2.

---

## 1. The problem

Today Control has exactly one `ServicesScreen`
(`control-shared/.../ui/screens/services/ServicesScreen.kt`, VM in `control-shared-logic`). It
renders **a single card** — the Tenshu Relay — in three states (`TenshuRelayEmptyCard` → *Sign up*;
`TenshuRelayCard` → plan + storage used/limit + identity + *Get more storage* / *Manage subscription*
/ *Change identity* / *Disconnect*; `TenshuRelaySyncedCard` → the 2nd-device "storage active" card
(its figures re-derive when the owner resolves). Two things are **fused** in that one card:

- **the account** — plan (`Free`/`Rei`), identity method (Email / ReiKey), manage-subscription; and
- **one service** — storage quota (used/limit bytes, progress bar).

Tenshu is now accreting *External Tenshu Services* (whitepaper: services "provided outside of the
user's device … by a web server"): relay/sync + storage (commerce-gated: account + quota), the
tiles/maps host (public + free in v1, possibly metered later —
[`maps-region-delivery.md`](maps-region-delivery.md) D4/D10), and future ones (geocoding/search P4,
publishing/Rei — the developer plane). **Each has a different gating model.** If every new service
either bolts more rows onto the storage card or grows its own bespoke gating UI, the surface stops
scaling and the user loses a single mental model of *"my account, and what it is connected to."*

The maps discussion surfaced the confusion directly: *"make a region available offline"* felt like it
belonged in a Services/account surface — but it doesn't. That is a **data-availability preference**
(structurally identical to Drive's "make folder available offline"), available to everyone, gated on
nothing. Untangling that from *account ↔ entitlement* is half the design.

## 2. The two axes (the core distinction)

- **Axis A — identity / account (singular, cross-service).** The commerce account (email-OTP or
  ReiKey), the recovery anchor, the subscription/billing relationship. There is **one** of these; it
  lives in the commerce plane — "the only PII-bearing component in the otherwise zero-knowledge stack"
  (`CommercePublisherClient`). Manage-subscription / billing-portal / change-identity / disconnect are
  Axis-A actions.
- **Axis B — per-service connection / entitlement (plural).** Each service's status *against that one
  account*: `Free` / `Connected` / `Needs account` / `Lapsed` / `Unavailable`, an optional quota, an
  optional provider choice. Storage is one instance; a future metered maps host is another.

Today Axis A and Axis B are collapsed into `TenshuRelayCard`. **This note separates them:** the account
becomes a header/section (one place), and services become a list beneath it. Storage is then *card #1 of
a list*, not *the screen*.

**Explicit non-goal — data-availability preferences are not this surface.** "Make folder available
offline" (Drive), "make region available offline" / streamed-vs-offline (Maps) are **data-availability
preferences**, not account/entitlement state — available to everyone, gated on nothing. The rule for
*where they live* is **with the data's owner, not "the app"**: a Drive folder is Drive-owned, so its
offline toggle is in Drive; a **map region is shared** across Maps/Timeline/Fit and its store is
**Control-owned** ([`maps-region-delivery.md`](maps-region-delivery.md) D5/D6), so its offline management
is a **distinct Control surface** — an *Offline maps / cached data* screen, a **sibling** to this Services
surface, not part of it. Two Control surfaces, two axes: **Services** = account ↔ entitlement (gated on
account); **Offline maps** = data availability (gated on nothing). Collapsing them is exactly the category
error the maps discussion nearly made.

## 3. Decisions (draft)

### D1. Generalize the single card into a **generic service-card registry** — reuse the Data-section walker shape.

Control's Data section is already the precedent: `DataScreen.kt` iterates `uiState.typeIds.forEach { …
realmDataPresentation(typeId) … DataTypeCard(…) }` — *"a generic walker: one card per realm-data type …
no hard-coded list,"* where **enumeration is schema-side** (`realmDataTypeIds()` over the
`SchemaRegistry`) and **presentation is consumer-side** (`realmDataPresentation(typeId)` →
`RealmDataPresentation(name, icon, sortOrder)`, with a generic fallback so *"a new type appears with zero
changes to the screen."*).

Mirror that for services: a `ControlServiceRegistry` enumerates the connectable services; a consumer-side
`serviceCardPresentation(serviceId)` supplies display name / icon / ordering with a generic fallback. A new
service appears in the list with **zero** changes to `ServicesScreen`. (Naming echoes the error-channel
convention of a stable per-service **id namespace** — `tenshu/<service>/…` — as the enumeration key; note
that convention is slash-namespaced ids, *not* a `.v<n>` version suffix.)

### D2. Separate the account axis from the service axis in the layout.

Account (identity + subscription + recovery) is a header/section; services are the list. Axis-A CTAs
(manage subscription, change identity, disconnect) move to the account section; Axis-B CTAs live on each
card. Nuance to resolve (Q1): storage's *Get more storage* is a subscription upsell (Axis A) that
*surfaces* on the storage card (where the quota is) — likely "quota on the card, action deep-links to the
account/subscription flow."

### D3. Each card is a small self-describing contract, not bespoke per service.

Sketch: `ServiceCard { serviceId; displayName; icon; status ∈ {Free, Connected, NeedsAccount, Lapsed,
Unavailable}; quota?: {usedBytes, limitBytes}; provider?: {current, alternativesAvailable}; primaryAction?;
secondaryActions? }`. Each service maps *its own* state into this shape. Storage → `Connected` + quota +
*Get more*; maps/tiles v1 → `Free` (public, no account, no quota); maps/tiles metered (future) →
`Connected`/`NeedsAccount` (+ a note that streaming-vs-offline is an app preference, not a card control).

### D4. The registry is Control-side (control plane), never per-app.

Individual apps (Drive/Maps/Timeline) never grow account/services UI. This is the whitepaper component
model: client apps reach external services only through the proxy, and Control owns the account +
entitlement relationship. The Services surface is where that relationship is legible.

### D5. Entitlement stays the only cross-plane notion of "paid"; this surface *reads*, it does not gate.

Per `commerce-billing-plane.md` D5 and ADR 0019, enforcement lives with the relay (data plane) and the
storage-gating UX; the Services surface is **visibility only**. It renders account + entitlement + synced
`StorageStatus` — it does not become a new gating authority, and it never joins person→service beyond what
each plane already knows.

### D6. Provider choice is a first-class per-service field (the whitepaper's alternative-provider promise).

The whitepaper: *"the user can select an alternative service provider … for each … external Tenshu
service."* Today every service has only the default provider (the App Console / `tenshu.dev`). The card's
`provider` field gives that promise a home: v1 shows *"Provider: Default (Tenshu)"* with the hook for
alternatives, wired for real only when a second provider exists. (Maps' R2+CF-vs-DO host choice is an *ops*
decision, not a user-facing provider choice — don't surface it here.)

### D7. Free / substrate services — show or hide? (open, Q2/Q3)

The always-on relay/sync substrate has nothing to configure on its own; its user-visible facet is
*storage*. A public/free service (maps v1) *could* show a `Free` card (advertises the capability) or be
hidden until it needs an account (less clutter). Leaning: show storage (as today), gate the rest on "has a
user-relevant status." Decide when the second card actually lands.

## 4. Worked examples — current & near services as cards

| Service | Plane | v1 card | Later |
|---|---|---|---|
| **Storage (relay/sync)** | data + commerce | `Connected` + quota (used/limit) + *Get more* / *Manage subscription*; `NeedsAccount` empty-state → *Sign up*; 2nd-device `synced` state | blinded-entitlement note (§5) |
| **Maps / tiles** | (its own host, D6 of maps doc) | *not shown, or* `Free` (public, no account) — "make offline" is an app preference, **not** a card control | `Connected`/`NeedsAccount` if metered (maps Phase 3 vouchers) |
| **Geocoding / search (P4)** | future service | — | card when the service exists |
| **Publishing / Rei (developer plane)** | commerce/dev | — | likely its own card once Rei lands |

## 5. Relationship to existing pieces / non-goals

- **Not a new account system.** `commerce-accounts-and-sessions.md` owns account lifecycle; this surface
  reads `PublisherClient`/`PublisherAccount` + synced `StorageStatus`.
- **Not a gating authority** (D5). Relay + storage-gating own enforcement.
- **Not app-local data preferences** (§2 non-goal).
- **Web parity:** the Data walker already has a web-apex twin (`/control/data` over
  `TenshuSdk.realmDataTypeIds()`); a services registry would follow the same "each platform owns
  presentation over a shared enumeration seam" split if/when Control-on-web needs it.
- **Blinded-entitlement interplay:** as entitlements move toward blinded issuance
  (`commerce-billing-plane.md` D3 strong), the surface must show per-service status *without*
  re-introducing a person→service join it isn't supposed to be able to make (Q6).

## 6. Phasing (draft, each independently shippable)

1. **Split account from services (behavior-preserving).** Refactor `ServicesScreen` so the account is a
   header/section and storage is *card #1 of a list*. Introduce the `ServiceCard` contract (D3) and a
   `ControlServiceRegistry` enumeration seam mirroring `realmDataTypeIds()`. Still one real card; the list
   is a list of one.
2. **Consumer-side presentation registry + generic fallback** (mirror `RealmDataPresentation`): an unknown
   service renders a generic card, so new services need zero screen changes.
3. **Second real card proves the registry** — the natural trigger is maps going metered (or a deliberate
   `Free` maps card). Until then Ph1–2 stand on their own as a cleanup + extensibility seam.
4. **Provider-choice field surfaced** (D6) — "Default (Tenshu)" only, wiring the whitepaper
   alternative-provider path.
5. **Later:** web-apex twin; blinded-entitlement-aware status rendering (Q6).

## 7. Open questions

- **Q1.** Does *Get more storage* live on the account section (it's a subscription action) or the storage
  card (where the quota is)? Proposed: quota on the card, action deep-links to the subscription flow.
- **Q2.** Does the always-on relay/sync substrate appear as its own card, or only "storage" (its
  user-visible facet)?
- **Q3.** Are Free services shown (maps v1) or hidden until they need an account? Trade advertisement vs
  clutter.
- **Q4.** One account only, or per-service provider/identity binding? The whitepaper allows per-service
  provider selection, which *could* imply per-service identity. v1: one account, provider = default
  everywhere.
- **Q5.** Naming — keep "Services", or "Account & Services" / "Connected services"?
- **Q6.** How do blinded entitlements (billing-plane D3 strong) constrain what the surface can truthfully
  show per service without re-introducing a person↔service join?

## 8. Version

Control-services-surface v0.1 · 2026-07-12. Design note only; opened from
[`maps-region-delivery.md`](maps-region-delivery.md) §7 phase 8. Establishes the two-axis framing
(account vs per-service entitlement), the "generalize the single Services card into a Data-walker-style
service-card registry" direction (D1), the Control-plane-not-per-app placement (D4), and the
visibility-not-gating boundary (D5). No decisions locked; no phases shipped.

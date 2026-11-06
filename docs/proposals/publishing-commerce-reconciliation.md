# Publishing / Commerce plane — reconciliation note

**Status:** Decisions locked 2026-06-13 (maintainer discussion). Records the disposition of two
overlapping drafts and the six decisions that reconcile them. The reconciliation produces no new code
itself, but the foundation (the synced-record carrier) is **already partly built** — see §0.1. This
note is the canonical handoff for the next person standing up the real publishing/commerce service.

**Owner:** Benedikt

**Reconciles:** [`commerce-billing-plane.md`](commerce-billing-plane.md) (the newer three-plane
framing) and [`tenshu-publisher.md`](tenshu-publisher.md) (the original monolithic "Publisher"
framing). **Consumes:** [`storage-entitlement-sync.md`](storage-entitlement-sync.md),
[ADR 0019](../adr/0019-storage-capacity-gating.md), [`control-subscription-ux.md`](control-subscription-ux.md).
**Adjacent:** [`tenshu-rei.md`](tenshu-rei.md) (the developer plane — out of scope here beyond the boundary).

> **Read §0.1 (as-built baseline) and §10 (Implementation Notes for Claude Code) first if you are
> about to write code.** The decision sections (§1–§9) are the rationale; §10 is the canonical
> file-level handoff. The state in §0.1 was verified against the tree on 2026-06-13 — the
> `storage-entitlement-sync.md` header still says "no code yet," but its slices have largely landed.

---

## 0. Why this note exists

The term "Publishing Service" referred to two different designs. `tenshu-publisher.md` (v0.1)
sketched a single Publisher that knows email↔`ownerId`, talks to Stripe/IAP, mints a
Publisher-signed `Entitlement`, and **writes it into the user's realm as a synced record** that
publishes through "Rei". `commerce-billing-plane.md` (drafted 2026-06-13) argued that single
service conflates **three planes** — data (the relay), commerce (paid-storage accounts +
billing), developer (app publishing → Rei) — and that storage billing should bridge to the relay
via a **signed entitlement the relay verifies**, decoupled from Rei.

The two agree on the core (a signed entitlement; zero-trust data plane preserved) but diverged on
delivery mechanism, the privacy binding, and Rei coupling. This note settles those.

---

## 0.1 As-built baseline (verified against the tree, 2026-06-13)

More is built than the docs imply. The **synced-record model** (tenshu-publisher §10 +
storage-entitlement-sync slices 1–4a) is substantially landed; what does **not** exist is any
server-side publisher/commerce module. Concretely:

| Piece | State | Where |
|---|---|---|
| `Entitlement` composite — `Syncable(Eager)`, keyed by `ownerId`, fields `ownerId/publisherId/productId/validFrom/validUntil/publisherSignature` | **Built** | `identity-service/.../identity/Entitlement.kt` |
| `EntitlementDto` (wire) | **Built** | `identity-service-common/.../common/EntitlementDto.kt` |
| `EntitlementSignature` — canonical message + **issuer-key registry** `publicKeyFor(publisherId)` + committed dev key `tenshu.dev` | **Built (dev key)** | `identity-service/.../identity/EntitlementSignature.kt` |
| Verify-on-merge against the registry | **Built** | `sync-service-proxy` (`EntitlementVerifyOnMergeTest`) |
| `EntitlementProducts` catalogue + `entitlementToFigures` + `EntitlementStorageProvider` (replaces `MockStorageProvider`) | **Built** | `control-shared/.../data/EntitlementStorageProvider.kt` |
| `initEntitlementStorage` — reader (DataObserver) + writer (signs canonical message, upserts into realm) | **Built** | `control-shared/.../data/EntitlementStorageInitialization.kt` |
| `StorageSyncGate` + `personalRealmSyncAllowed` + **joiner-bootstrap** (`joinedFundedRealm`) | **Built** | `control-shared/.../data/StorageSyncGate.kt` |
| `:storage-service` (+ `-common`/`-client`/`-proxy`/`-test-utils`) — the `hasStorage && fits` gate | **Built** (ADR 0019) | `storage-service*` modules |
| `PublisherClient` / `MockPublisherClient` / `PublisherAccount` (`Free`/`Lifetime`/`Rei`) | **Built, mock only** | `control-shared/.../data/PublisherClient.kt` |
| Server-side publisher / commerce service (mint, payments, key custody) | ⚠ **Built** (this row said "Does not exist" until 2026-08-11) | `include(":commerce-service")` is `settings.gradle.kts:207`; accounts, email-OTP, sessions, Stripe money path and the admin console all landed — see `commerce-accounts-and-sessions.md` §9 |
| Relay-side entitlement verification / quota enforcement | **Does not exist** | relay stores ciphertext only; gating is client-side (honour-system) |

Two consequences for the decisions below:

1. **The issuer-key registry D4 wants already exists in stub form** — `EntitlementSignature.publicKeyFor`
   dispatches on `publisherId` and holds one dev key. Multi-issuer (publisher + future `rei-service`)
   is adding keys to this lookup, not new machinery.
2. **The "Publisher" is in-process today.** Control's `initEntitlementStorage` writer *is* the minting
   path, signing with the committed `tenshu.dev` key. "The real Publishing Service" = lifting that
   minting + signing out of the device into a real server-side issuer.

---

## 1. Disposition (D1 — decided)

The **three-plane decomposition is canonical.** `commerce-billing-plane.md` is the architecture
doc for the commerce plane. `tenshu-publisher.md` is **superseded-in-part**:

- **Retired:** the monolithic "one Publisher does everything" framing; the assumption that the
  entitlement is a *Rei* record; the "writes the entitlement into the realm as a synced record"
  as the *enforcement* carrier (it survives only as a device-side convenience, see D2); the stale
  threat-model numbering (`§A11`).
- **Kept:** its §10 mock-first slice as **consumer-side scaffolding** — `MockPublisherClient`,
  `MockStorageProvider`, the `Entitlement` composite shape, and the Control subscription UI surface
  are all still valid and already partly landed (see ADR 0019 "what existed at the start").
- **Renamed mental model:** "Publishing Service" → the **commerce plane** (a.k.a. the *publishing
  service* as an **entitlement issuer**). The relay/storage stays the **data plane**. Rei is the
  **developer plane** only.

Follow-up (not done in this note): add a superseded-in-part banner to `tenshu-publisher.md`, make
`commerce-billing-plane.md` carry these decisions, and update the in-flight table in
`AGENTS.md`.

---

## 2. Delivery model (D2 — decided, refined against as-built §0.1)

**Two carriers, two consumers — they coexist.** The original framing pitted "synced realm record"
against "bearer token" as if one had to win. As-built reality (§0.1) makes the cleaner split obvious:
they answer different questions and the verifier seam is the same registry either way.

- **Synced `Entitlement` record → device convergence + client-side gating.** This is the **built**
  path (§0.1) and it **stays**. It is how every paired device *learns its current tier* with **zero
  commerce roundtrip** (it rides normal sync), and what drives the Drive banner / `:storage-service`
  `hasStorage && fits` gate. `storage-entitlement-sync.md`'s cross-device problem is already solved by
  this record + the joiner-bootstrap gate; we do **not** replace that with per-device token pulls.

- **Signed bearer token → relay-side enforcement (D5).** When the relay starts actually gating bytes
  (D6 / Phase 5), Control presents a short-lived signed token the relay verifies. This is the carrier
  that did **not** exist before and that the decision adds.

The verify step in **both** cases is an **offline signature check** against the issuer's published
public key via the `EntitlementSignature.publicKeyFor(publisherId)` registry — **not** a per-request
callback to the publishing service. That is the answer to the roundtrip concern: there is no commerce
call on the enforcement hot path. The only network roundtrip is Control periodically pulling a **fresh,
short-lived** token from the publishing service, off the enforcement path. Short token lifetime
(commerce §4: ~24–72 h) doubles as revocation — a lapsed subscription simply stops getting reissued;
no online revocation list.

What this **supersedes** from `tenshu-publisher.md` §3.2 is the idea that *the relay reads the synced
entitlement record to enforce quota*. Enforcement uses the **token** (blinding-ready per D3), not the
realm record — so the record can stay strictly device-facing and the relay's enforcement input can
later become blinded without touching the realm schema.

---

## 3. Privacy binding (D3 — decided)

**Pseudo-anonymous / minimal (`ownerId`-bound) for v1; token format blinding-ready.**

The enforcement authority learns the **tier**, not the person. Under an honest, non-logging relay this
is pseudo-anonymous: the relay sees "some `ownerId` presented a valid tier-T token," and the commerce
plane separately knows "this email paid for tier T." This is commerce-billing's **minimal variant** —
the residual is the **A30 `account↔ownerId` link** (and relay-side metadata correlation), documented
as a known de-anonymisation vector, *not* claimed away.

The token format must be **blinding-ready** so the later upgrade to blinded/anonymous tokens (commerce
**strong variant**, where neither plane nor both colluding can link person→realm) is an **issuance-side
change, not a verifier rewrite**. Do not bake a plaintext-`ownerId` assumption into the verifier such
that blinding becomes a rewrite (commerce D3).

---

## 4. Multi-issuer verifier (D4 — decided)

**Decouple storage entitlement from Rei by default; the verifier accepts entitlements from any
trusted issuer.**

The entitlement is **issuer-agnostic**: the authority verifies the signature against a **registry of
trusted issuer public keys**, not one hardcoded publisher key (generalises `tenshu-publisher.md` §3.4's
"publisher-key registry"). Consequences:

- **v1 issuer #1** = the publishing service (commerce plane). No Rei dependency — storage billing ships
  without a concrete Rei ledger existing.
- **Future issuer #2** = a separate `rei-service` (the second route, outside the publisher service).
  A Rei-minted entitlement is accepted by the **same verifier with no format change** — it is just
  another trusted issuer key in the registry.

So: decoupled from Rei by default, but Rei-minted entitlements are first-class once `rei-service` exists.

---

## 5. Enforcement authority (D5 — decided)

**The binding check is relay-side (server); the on-device `:storage-service` is for gating/UX only.**

Two things share the word "storage" and must not be conflated:

- **`:storage-service`** (ADR 0019, proxy-side, on the user's own device) — consumes the entitlement to
  derive `StorageStatus` and gate `hasStorage && fits` for upload / Mode-A link / Mode-B share. It runs
  on hardware the user controls, so it **cannot be trusted to enforce against that same user**. It is a
  UX and local-gating layer.
- **The relay** (`:sync-relay-server`, server-side) — is where the signed token actually gates bytes,
  because it holds the ciphertext and is outside the user's control. Real per-owner quota enforcement
  is a **relay** responsibility.

v1 ships **client-side gating only** (current state); relay-side verification + quota accounting is a
later phase (D6).

---

## 6. Quota enforcement timing + bootstrap accounts (D6 — decided)

**Relay-side quota enforcement is a late phase; v1 unblocks itself with a whitelist.**

- Real per-owner quota enforcement on the relay folds into the abuse-resistance work
  (commerce §8 Phase 2 / the `deploy-relay-digitalocean.md` per-owner quota track). Not v1.
- **v1 bootstrap ("debug / founder accounts"):** the publishing service holds a configured
  **whitelist of email addresses**; a whitelisted account gets a **top-tier entitlement minted
  instantly, no payment**. This lives entirely in the commerce plane (which legitimately knows email);
  the relay never sees the email, only the resulting signed entitlement. This is the smallest thing
  that lets the end-to-end mint→present→verify path be exercised before Stripe/IAP is wired.

---

## 7. Revised phasing

Merges commerce §8, `tenshu-publisher.md` §10/§7, the decisions above, and the as-built state (§0.1).
Each phase independently shippable. **Phase 0 is mostly already done.**

0. **Entitlement format + verifier seam — ✅ largely landed (§0.1).** Signed `Entitlement` composite,
   canonical message, issuer-key registry (`publicKeyFor`), verify-on-merge, device-side derivation,
   storage gate + joiner bootstrap. Remaining within this phase: nothing blocking — it is the
   foundation the rest builds on.
1. **Server-side publishing/commerce service + whitelist bootstrap (D6).** New `:commerce-service`
   (Ktor on Netty, `:sync-relay-server` shape) that owns the **real signing key** and **mints** signed
   entitlements — lifting minting out of Control's in-process `initEntitlementStorage` writer.
   **Whitelist → instant top-tier**, no real payment. This is the first real new module.
2. **Swap the in-process minter for the service.** Control stops self-signing with the `tenshu.dev`
   key; it requests an entitlement (and, from Phase 4, a token) from the service behind the existing
   `PublisherClient` seam. Keep `MockPublisherClient` for dev builds (`TENSHU_BILLING_MOCK`). The
   `EntitlementSignature` dev key becomes dev-only; release rejects it.
3. **Real payment.** Stripe (web/desktop) + IAP (mobile) webhooks → entitlement issuance,
   merchant-of-record-light (commerce D4: processor holds card data).
4. **Bearer-token issuance + relay verifier (D2/D5).** Service issues a short-lived, blinding-ready
   token; relay verifies it offline against the issuer public key (reuse the device-JWT verifier
   posture, commerce D2).
5. **Relay-side quota enforcement (D5/D6).** Relay reads the token tier and enforces per-owner quota on
   writes; typed over-quota error (`core-error-channel.md`). Folds into abuse-resistance.
6. **Strong privacy (optional, later).** Swap issuance to blinded tokens (commerce strong variant);
   relay verifier unchanged if Phase 4 kept it `ownerId`-claim-agnostic. Retires A30.
7. **Developer plane / `rei-service` (separate track).** Second issuer — adds its key to
   `publicKeyFor`; gated on a real Rei spec.

Phases 1–3 are the near-term path (stand up the real issuer; keep the built convergence/gating path).
The service's production deployment rides `sync-backend-relay-plan.md` Phase 4 infra, same as the relay.

---

## 8. Threat-model implications (proposal-level; amendments land per phase — House Rule #2)

Adopt commerce-billing's asset numbering; retire `tenshu-publisher.md`'s stale `§A11`:

- **A28 — commerce identity & payment set** (email, processor customer id, subscription/entitlement
  state, invoices). First real PII in the system; commerce plane only.
- **A29 — issuer signing keypair(s)** (publishing service now; `rei-service` later). Relay holds public
  keys only. Compromise = forged entitlements (a billing/DoS problem, **not** a confidentiality break).
- **A30 — the `account↔ownerId` link** (minimal variant only). The documented de-anonymisation residual
  D3 keeps until the strong variant (Phase 6) eliminates it.
- **New surface:** the commerce↔relay bridge — issuance endpoint, the relay's offline verifier, the
  client token-fetch path.
- **Invariant I-CB-1 (plane unlinkability):** minimal variant holds against compromise of *either*
  plane alone, **not both** (the A30 carve-out); strong variant holds against both. Written against the
  strong target.

Full amendment to `docs/sync/threat-model.md` lands with the implementing phase, not speculatively.

---

## 9. Still open

- **Q1 (commerce).** Blind-token scheme for Phase 6 — Privacy Pass / blind RSA / anonymous credentials.
  Drives the strong variant; not on the v1 path.
- **Q3 (commerce).** Token delivery — confirmed **client-pull** by D2's roundtrip reasoning; the only
  remaining detail is refresh cadence vs. token lifetime / offline-grace window (commerce Q5).
- **Relay verifier shape.** Reuse the existing device-key JWT verification path (commerce D2 says
  entitlement is "one more such credential") vs. a distinct verifier — sized in Phase 1.
- **Whitelist mechanics (D6).** Where the whitelist lives (config vs. small store), and how a
  whitelisted account's `ownerId` is captured at mint time (mirrors `tenshu-publisher.md` §5
  "`ownerId` capture at checkout").
- **Issuer-key custody/rotation** (A29) — KMS/HSM, rotation, compromise response. Standard key
  management; sized when Phase 2 is concrete.

---

## 10. Implementation Notes for Claude Code

The canonical file-level handoff. Paths verified against the tree 2026-06-13. Earlier sections are
rationale; this is the build guide. Each numbered phase ≈ one PR unit.

### 10.1 Keep / don't touch (the built convergence + gating path, §0.1)

These implement D2's *device-facing* carrier and must keep working — do not rip them out when adding
the server-side issuer:

- `identity-service/.../identity/Entitlement.kt` — the `Syncable` composite. Schema is **wire
  contract**; field order in `EntitlementSignature.message(...)` is the signing contract. Don't reorder.
- `identity-service/.../identity/EntitlementSignature.kt` — `message(...)`, `fieldsOf(...)`, and the
  **issuer-key registry** `publicKeyFor(publisherId)`. This is the D4 seam.
- `identity-service-common/.../common/EntitlementDto.kt`; verify-on-merge in `:sync-service-proxy`
  (`EntitlementVerifyOnMergeTest`).
- `control-shared/.../data/EntitlementStorageProvider.kt` (`EntitlementProducts`, `entitlementToFigures`),
  `EntitlementStorageInitialization.kt` (`initEntitlementStorage`), `StorageSyncGate.kt`.
- `storage-service*` modules (ADR 0019).

### 10.2 Phase 1 — `:commerce-service` skeleton + whitelist (first real new module)

New Gradle module(s), `:sync-relay-server` shape (Ktor on Netty). Register in `settings.gradle.kts`
next to the relay; **do not** co-locate with `:sync-relay-server` (House Rule #1 — separate trust
boundary, separate store).

| Add | Purpose |
|---|---|
| `:commerce-service` module + `build.gradle.kts` + `settings.gradle.kts` include | the service |
| `MintEntitlement` route | `(ownerId, productId) → signed Entitlement bytes`, signing with the **real** key (env/KMS, not `DEV_SEED`) |
| `Whitelist` (config-backed set of emails) | whitelisted email → instant top-tier mint, no payment (D6). Email lives **only** here — never reaches relay/realm |
| `IssuerKeypair` loading (env var v1, KMS later) | A29 custody; public half published for `publicKeyFor` |

Reuse `EntitlementSignature.message(...)` **verbatim** for the signed bytes so the existing
verify-on-merge accepts service-minted records unchanged. Add the service's `publisherId` + public key
to `publicKeyFor` (alongside, not replacing, `tenshu.dev`).

Tests: a service-minted entitlement passes `EntitlementSignature` verify with the new key; a
non-whitelisted email gets no top-tier mint.

### 10.3 Phase 2 — move minting off-device

- `control-shared/.../data/EntitlementStorageInitialization.kt`: the **writer** half currently signs
  in-process with `DEV_SEED`. Change it to fetch the signed entitlement from `:commerce-service` (via a
  real `PublisherClient` impl) and write *that* into the realm. The reader half is unchanged.
- `control-shared/.../data/PublisherClient.kt`: add the real impl behind the existing interface; keep
  `MockPublisherClient` gated on `TENSHU_BILLING_MOCK` (dev only).
- `EntitlementSignature.DEV_*` becomes dev-only; release builds must reject `publisherId == "tenshu.dev"`
  (add a build-variant guard + test).

### 10.4 Phases 4–5 — bearer token + relay enforcement

- New `EntitlementToken` (short-lived, **blinding-ready** — keep the relay verifier `ownerId`-claim-
  agnostic per D3 so Phase 6 is issuance-only). Mint route on `:commerce-service`; pull + cache in
  Control off the hot path.
- Relay verifier in `:sync-relay-server`: verify token signature + expiry against the configured issuer
  public key — **reuse the device-key JWT verification path** (commerce D2 treats this as "one more
  credential"), don't build a parallel auth stack.
- Per-owner quota accounting on writes; over-quota → typed error from the `core-error-channel.md`
  taxonomy, not a silent drop.

### 10.5 House rules that bite here

- **#1 (relay sees ciphertext only).** `:commerce-service` is a separate trust boundary — no relay
  credentials beyond the token public key, no read path into the relay, and vice versa. Email/payment
  PII (A28) never crosses into the data plane.
- **#2 (threat-model amendment ships with the code).** Phase 4 (the relay verifier / new bridge surface)
  is the first change touching the relay's auth shape → amend `docs/sync/threat-model.md` (A28/A29/A30,
  the new surface, I-CB-1) **in that PR**.
- **#3 (ops stay non-suspend at the interface).** Any new proxy `Operation` for entitlement/token
  fetch uses the `IoOperation` + `TaskInteractor` pattern, not `suspend` on the interface.
- **#4 (schema-first).** `EntitlementToken` is wire-serialisable → add it to the schema DSL, not an
  ad-hoc data class.
- **#7 (tests over claims).** Run `./gradlew :commerce-service:test :sync-service-proxy:test
  :control-shared:test` for the affected slices; the `*-test-utils` fixtures already exist for storage.

### 10.6 Stale-doc cleanup (do alongside)

- `storage-entitlement-sync.md` header says "Draft v0.1 … no code yet" — **stale**; slices 1–4a have
  landed (§0.1). Update its status block.
- Add a superseded-in-part banner to `tenshu-publisher.md` (D1) and fold these decisions into
  `commerce-billing-plane.md`; add a row to the `AGENTS.md` in-flight table.

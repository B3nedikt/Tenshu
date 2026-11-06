# Retention & Quota Model — a design pass

**Status:** Draft v0.5 (2026-08-04) — **P1 (usage surface) + P1b-1 (80 % banner) + P2
(local-cache ceiling, §4.11) + P3a/P3b (reclamation mechanism §3.1, credential §3.2, producer +
route + sweep §3.3) IMPLEMENTED — and since 2026-10-24 switched ON at both ends in production**;
decisions D1–D9 settled (§6). The maintainer opened the flags ahead of ⚖️ counsel's review of the
retention/closure policy clause (**drafted 2026-08-04** — `acceptable-use-and-limits.md` §4, ToS/privacy
versions bumped), which is still owed. Opened off the tech-debt entry *"No end-to-end retention / quota model"* and the
content-GC correctness fix (time-based blob GC disabled, `file-content-addressing.md` §4.2).
⚠⚠ **Per-file reclamation is built (2026-10-23, §3.4).** Tier 2 is no longer "account-lifecycle
only": since CR-2 B5/B6 the relay frees a deleted file's content itself, T after its last edge, when
`CONTENT_SWEEP_MODE=armed`. ⚠ `armed` is the compose **default**, not a verified production fact —
confirm with `GET /ops/content-sweep/status` or the boot line. It has real gaps (§3.4). Account closure
(P3) is LIVE since 2026-10-24 (§3.3).

**P1 as-built (2026-07-18).** Relay `GET /sync/usage` (authed, caller-`ownerId`-scoped,
`{ "usedBytes": <long> }` = `ContentBlobStore.bytesUsed(ownerId)`) in `ContentRouting.kt` +
route test; client `RelayUsageClient.fetchUsage(): Long?` (best-effort, never throws) in
`:sync-service-proxy`; wired into `EntitlementStorageProvider` via a `suspend () -> Long?`
seam threaded `initEntitlementStorage` → `initEntitlementStorageReader` (`usedBytes = 0L` mock
replaced) — `:storage-service-proxy` stays free of a `:sync-service-proxy` dependency (the seam
is a plain lambda; the cross-module wire is in Control's DI where both are visible). **Live on
desktop**; Android + iOS Control DI is the identical one-line follow-up (a `RelayUsageClient`
single + `usageReader = { get<RelayUsageClient>().fetchUsage() }`), deliberately deferred to keep
P1 off `androidMain` (HR #8 smoke gate). D9 (5 GiB) doc fix applied. T-R-09 usage-surface note
added. **Next: P1b banners** (now unblocked).
⚠ Since corrected: the seam was live but only ever *fetched* on entitlement-shaped events, so the
figure it delivered was a start-of-process snapshot — see the D5 amendment. All four platforms
(desktop, Android, iOS, JS) now wire `usageReader`, so the refresh is not desktop-only.

**Problem in one line:** the system has **no coherent story for how much data is kept,
where, and when it is reclaimed** — retention was decided ad hoc per subsystem, and one
of those decisions (a time-based content GC) was outright wrong for durable data and
shipped a latent data-loss bug. This doc names the model, so the three missing pieces get
built against one design instead of three more ad-hoc ones.

---

## 1. Why this exists

Three separate symptoms, one missing model:

1. **Relay content plane was time-reaped.** A 30-day untouched-window GC was wired live
   against durable user data → a file unopened for a month lost its bytes — the time-reap data-loss bug. Fixed
   defensively (GC off), but the *correct* reclamation mechanism — account-lifecycle —
   is unbuilt.
2. **No local-cache ceiling.** A device caches decrypted plaintext with no total-footprint
   bound; the offline-keeper governs *which paths* stay local, not *how much*.
3. **No quota UX.** Running out of storage surfaces only as a hard-block dialog at upload time
   (`StorageGateDialog`), and the relay's own quota gate (`507 over_quota`) is **opt-in and
   off by default** (`ENTITLEMENT_ENFORCE=false`) — so today the user gets neither a proactive
   warning *nor*, in most deployments, a real wall. `StorageStatus.usedBytes` is mock-zero on
   every client, so even the fill level is invisible.

These are three faces of one question: **what is durable, what is a cache, and what is
transport — and who reclaims each, when?**

**A note on maturity.** Much of the *substrate* exists (tiers, the synced `Entitlement`, the
token + `507`/`402` gate, the offline-keeper's download half, a `StorageStatus` display). What
is missing is (a) the lapse→reclaim connection, (b) relay→client usage reporting, (c) a local
footprint ceiling, and (d) proactive quota warnings. This doc's job is to fit those four into
one model rather than four more ad-hoc slices. Concrete surface names per face below.

---

## 2. The retention taxonomy (the load-bearing idea)

The time-reap data-loss bug (§1 item 1; pinned by `ContentBlobStoreTest.defaultStore_isDurable_neverTimeReaps`)
came from conflating storage tiers with *opposite* retention semantics: durable content was aged out
like the delivery log.
Tenshu has **three tiers, three policies**. Getting this table right is 80 % of the design.

| Tier | What it is | Durability | Reclamation trigger | Losing it means |
|---|---|---|---|---|
| **1. Ephemeral delivery log** | `SyncBlock`s in the relay mailbox (ordered metadata plane) | Transient — a delivery buffer | **Quorum-ack + retention window** (every realm device collected it, then age out) | Nothing (already delivered) |
| **2. Durable content** | Relay content plane — opaque AEAD chunk + manifest blobs, keyed `(ownerId, ref)` | **Master copy.** The one durable home for file bytes | **The user's own delete** (the relay frees content T after its last edge, CR-2 G2, §3.4) **or account closure + grace** (§3.3, live since 2026-10-24) — never time, inactivity, lapse or over-quota | **Data loss** — this may be a file's only copy |
| **3. Local cache** | Decrypted plaintext on a device (`FileDatabase`) | **Cache of tier 2** | **Size/LRU eviction** under a per-device ceiling; pinned paths (offline-keeper) exempt | Nothing (re-fetch from tier 2 on demand) |

Three rules fall straight out of the table:

- **Tier 2 is never time-reaped.** "Untouched for N days" is a *cache* policy (tier 3), and
  applying it to the durable master is the time-reap data-loss bug. Tier 2 persists while the
  account is in good standing; what removes content is the user deleting what references it
  (§3.4) or the account going away. ⚠ CR-2's T is **not** a time-reap: its clock starts at the
  observed one-to-zero edge transition, never at last access (G4: content with no edge history is
  never freed).
- **Tier 3 is *always* evictable and *never* authoritative.** A device may hold none, some,
  or all of tier 2. Eviction is free (re-fetch on `OnDemand` access). So the local ceiling
  is a pure UX/space knob, not a correctness one.
- **Tier 1's quorum-GC is correct and unchanged** — it is a transport buffer, not storage.

Everything below is just "make tiers 2 and 3 have real policies, and surface tier 2's
fill level (quota) to the user."

---

## 3. Face A — Relay content reclamation (account-lifecycle)

**Goal:** reclaim a lapsed/over-quota owner's tier-2 content *without* the relay ever
reading a manifest (House Rule #1) and *without* time-reaping live data.

> ⚠ *Superseded in part (2026-10-23):* under CR-2 G1 the relay **does** learn which content an opaque
> holder references (the `(holder, property, contentId)` edges on every push) and which refs a content
> has (the describe) — never a name, path or plaintext. That is what per-file reclamation (§3.4) runs
> on. The account-lifecycle argument below is unchanged.

**Why `ownerId`-keyed is the whole trick.** The relay stores content as `(ownerId, ref)`
and is content-blind: it cannot learn which blobs a *file* references (manifests are
ciphertext; a delete tombstone exposes only an opaque entity hash). But it does not need
to — account-lifecycle reclamation operates on a **whole owner's keyspace**, which is
exactly the granularity `ownerId` gives for free. "Reclaim owner X" = drop every
`(X, *)` blob. No manifest reading, HR#1 intact.

**Triggers (to design against the entitlement/commerce plane):**
- **Non-renewal / entitlement lapse.** The owner's entitlement expires and isn't renewed.
  After a **grace period**, their content keyspace is reclaimed.
- **Over-quota.** The owner exceeds their tier's byte budget. Uploads are *refused* at the
  seam (the `507 over_quota` path — the brake), but existing content is **not** reclaimed for
  going over; over-quota is an upload gate, not a delete trigger. (Reclaim-for-overage would
  be a data-loss footgun; the brake is refusal + a warning, per Face C.)

**What "reclaim" is, mechanically.** The `ContentBlobStore.gcSweep` *mechanism* already
exists (retained as a seam when time GC was disabled); reclamation is a **per-owner** call —
`reclaimOwner(ownerId)` drops `(ownerId, *)` — driven by the entitlement signal, **not** the
nightly `CONTENT_GC_SWEEP_INTERVAL_MS` time-sweep. ⚠ **`reclaimOwner` now exists** and is
wired through `POST /sync/account/closure`; it is flag-gated at both ends (default off, both ON in
production since 2026-10-24 — see §3.1's as-built and P3 below).

**The lapse signal (what drives the reclaim).** As-built, an entitlement *lapse* is
"stop reissuing": `commerce-service` `CommerceStore.currentEntitlement` / `pickCurrentEntitlement`
pick the highest-quota **valid** row and fall back to Free when a paid window ends; the token's
`exp` **is** the revocation (`core-common/.../security/EntitlementToken.kt`, verified by
`RelayEntitlementVerifier`), and the client refreshes/backs-off via `EntitlementBackoff`. There
is a **7-day offline grace** (`commerce-billing-plane.md` §4 / §9 Q5), today realized *only* as
the token TTL + client backoff — there is **no relay-side grace-band or reclaim code**.
`AccountStatus` is `Active`-only (suspension/closure "arrive with payment"). So Face A must
add: an entitlement-plane signal — "owner X's account is closed / fully lapsed past grace" —
that the relay consumes (keyed on `ownerId`) to call `reclaimOwner` (**D4**). The **grace
window is a config value, not hardcoded** — content is the *operational* half of the commerce
**operational-vs-statutory retention split** (`commerce-accounts-and-sessions.md` §5.2:
invoices are WORM ~10-year and survive account erasure; content is operational and reclaimed on
closure), and a configurable window lets that operational timeline stay consistent with GDPR /
statutory obligations. Token expiry is **not** a reclaim trigger — it only refuses writes.

⚠⚠ **Reclaim's cost is a function of the blob backend, and that changed under it.**
`reclaimOwner` drops a whole owner's keyspace one blob at a time — free when `blobs.delete` was a
local unlink, an HTTP round-trip each once `S3BlobStore` landed
(`hosting-provider-economics.md` D10). At the 2 MiB content chunk that is ~2.5 k deletes for a Free
owner and ~1M for a maxed Premium one, serially, inside the closure request handler. Since
2026-09-02 all four GC paths go through `BlobStore.deleteAll`, which S3 serves with `DeleteObjects`
at 1,000 keys per request; the same change fixed the **already-live** `POST /sync/content/delete`
(capped at 512 refs, so ~10 s per request before it). Reclaim is still synchronous in the handler —
for a large owner that is a long request, and making it a background job is unbuilt.

### 3.1 P3a as built (2026-08-04) — the mechanism, deliberately unwired

**`ContentBlobRegistry.reclaimOwner(ownerId): ReclaimResult`** now exists on both backends
(in-memory `ContentBlobStore` + durable `SqliteContentBlobStore`). It drops every `(ownerId, *)`
blob and reports `{blobsDropped, bytesFreed}` measured *inside* the delete, so an operator's audit
line cannot drift from what actually happened. This is the "single missing mechanism" this section
names.

⚠⚠ **Nothing calls it, on purpose.** No route, no scheduler hook, no commerce signal. This is the
one relay primitive that permanently destroys user content — and for most owners the relay holds
the *only* durable copy, since content is `OnDemand` and the local plaintext is an evictable cache
(Face B). Landing the destructive primitive on its own keeps it small enough to actually review,
and kept it inert until the trigger (below) existed. (The trigger landed in §3.3; the flags opened
2026-10-24.)

Four properties are load-bearing, and each is pinned by a test:

- ⚠⚠ **Independent of `CONTENT_RETENTION_WINDOW_MS`.** `gcSweep` short-circuits to `0` when the
  window is `Long.MAX_VALUE` — which is the time-reap fix and the production default. Reclaim is a
  *different trigger*, so sharing that guard would make it a silent no-op exactly where it is meant
  to work. (`worksWithTimeGcDisabled` on both backends.)
- ⚠ **Owner-scoped, not ref-scoped.** The keyspace is `(ownerId, ref)` and the same `ref` legitimately
  appears under two owners (the cross-realm share copy, T-R-09). Matching on `ref` would delete a
  stranger's file.
- ⚠ **A blank `ownerId` throws** rather than degrading into "reclaim everything".
- ⚠ **Bodies, not just index rows** — on the durable backend an orphaned blob body would consume
  disk forever with nothing left to name it. Deleted *after* the index commits, matching `gcSweep`:
  a crash in between leaves recoverable orphans rather than rows pointing at absent bytes.

**Content only** — tier-1 mailbox blocks keep their own quorum+retention GC and device rows belong
to the ADR-0007 revocation path. Conflating the three planes is what made the time-reap a data-loss bug.
⚠ *Superseded for closure (F12, §3.3a):* the primitive stays content-only, but closure no longer is —
it erases every owner-scoped table, because tier 1's GC never runs for a realm nobody polls.

### 3.2 P3b-1 as built (2026-08-04) — the credential and the decision

The trigger splits into **what authorises a reclaim** and **what delivers it**. This slice builds the
first half; the second is P3b-2.

**`AccountClosureClaims`** (`core-common/.../security/EntitlementToken.kt`) — a commerce-signed
assertion *"owner O's account was closed at time C"*, verified against the **existing**
`ENTITLEMENT_ISSUER_PUBKEY`. Deliberately the same alg-pinned EdDSA JWT as the entitlement token, in
the same file: **no new trust anchor, no new secret, no new verifier posture**, and the relay keeps
treating commerce as an issuer of credentials it does not itself mint.

**`AccountClosureReclaimer`** (relay) turns a verified record into a `reclaimOwner`, or refuses.

Four decisions, each with a test:

- ⚠⚠ **`ownerId` is mandatory here, unlike the entitlement token's optional/blinded `sub`.**
  Reclamation cannot be blinded — the relay must know *whose* keyspace to drop, and the keyspace *is*
  the `ownerId`. This is a real, recorded exception to D3's blinding-readiness (threat-model
  amendment), not an oversight: a content-blind relay cannot be told "delete this account's data"
  without being told the account.
- ⚠ **The record expires and is re-issued** while the account stays closed. Expiry is not a property
  of the closure (a closure is durable) — it is what makes *reopening* work: commerce stops
  re-issuing and a stale record stops authorising anything. Without it, a leaked record would be a
  permanent delete-this-owner capability that could fire against a reopened account.
- ⚠ **Grace runs from the recorded closure time, never from delivery.** A retried or delayed record
  must not restart the clock, or a flaky delivery path silently extends retention past what the
  published terms promise. A future-dated record fails *closed*.
- ⚠ **No idempotency state.** A repeated delivery re-runs a no-op (§3.1's primitive is safe to
  repeat), so there is no dedupe bookkeeping for the relay to keep correct.

⚠⚠ **Production: both flags ON since 2026-10-24** (commerce `ACCOUNT_CLOSURE_RELAY_URL`, relay
`ACCOUNT_CLOSURE_RECLAIM=true`, grace = the 30-day default), opened by the maintainer ahead of the
counsel review below, at a time production held one account and none closed. A closed account's
content is now actually deleted after the grace window. ⚠ "Deleted 30 days later" means **at least**
30 days plus up to one sweep interval (`ACCOUNT_CLOSURE_SWEEP_MINUTES`, 60 by default): the relay acts
only when commerce's next post arrives. And after grace the closed realm keeps being re-posted every
sweep, so anything a still-paired device uploads afterwards is deleted within the hour. ⚠ Commerce
backdates the record's `iat` by 30 s (`AccountClosureMinter.IAT_BACKDATE_SEC`), because the relay
refuses a future `iat` with no leeway and a persistently fast commerce clock would otherwise fail every
post; grace runs from `closed`, so the backdate brings no deletion forward. Each sweep logs its
outcomes by HTTP status (e.g. `2 delivered (1×200 reclaimed, 1×202 within grace), 1 failed (1×401)`),
counts only, so a run of `401`s is visible.

`ACCOUNT_CLOSURE_RECLAIM` defaults **`false`** and `ACCOUNT_CLOSURE_GRACE_MS` defaults to 30 days —
⚠ a *default*, not a policy; D4 requires it stay configurable so the operational window can match
whatever the terms commit to.

**What P3b-1 still needed** was a producer and a route — both landed in §3.3.
⚖️ The legal prerequisite below gates shipping *any* of it.

> ⚖️ **Legal / release prerequisite (D4) — DRAFTED 2026-08-04, counsel review still owed.**
> Reclaiming user content on account closure is a data-retention commitment, so before
> enforcement (**D8**) or reclamation ships it must be written into the user-facing policy + ToS.
> The single source of truth is
> [`docs/policy/acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md), which
> feeds the **versioned ToS-acceptance record** (`commerce-accounts-and-sessions.md` §5.1
> `acceptance(account_id, tos_version, privacy_version, …)`).
> **§4 "Data retention & account closure" now exists** (v0.2) — what we keep and for how long, a
> published **30-day** closure grace measured from the recorded closure date, plain-language
> irreversibility, the two structural limits (no per-file server-side deletion — ⚠ superseded by
> policy v0.6 §4.4, see §3.4 — no reach into the user's own devices), the never-deletes list (lapse / downgrade / over-quota / inactivity), and
> erasure = closure + operational-store deletion with invoices retained per §5.2. `TOS_VERSION` /
> `PRIVACY_VERSION` are bumped to `2026-08-04`.
> ⚠ **Still owed before the flags open:** counsel sign-off (it remains explicitly *not legal
> advice*), and a decision on **notice to existing accounts** — acceptance is recorded once at
> registration and there is **no re-acceptance flow**, so accounts registered earlier hold
> `2026-06-01`. Harmless while reclamation is off; not harmless the day it is switched on.
> ⚠ **The published grace is now a floor on the code**: `ACCOUNT_CLOSURE_GRACE_MS` must never be
> configured below what §4.3 promises.

**Where the relay learns quota (matters for the brake).** The relay does *not* store a
per-owner limit; it reads `quotaBytes` from the **verified entitlement token's claims** on each
write (`routing/EntitlementEnforcement.kt` `resolveEntitlement` → `Allowed(quotaBytes)`), and
enforces via `ContentBlobStore.putWithinQuota` (`bytesUsed(ownerId) + size > quota` → `507
Insufficient Storage` `over_quota`; distinct from `413` oversized-blob and `402`
entitlement-required). ⚠ Two caveats that weaken today's brake: enforcement is **opt-in**
(`ENTITLEMENT_ENFORCE=false` by default), and the `signed-record.v1` *proof* path carries
**no quota** (`quotaBytes=null` → unmetered). Turning the brake on for real is part of Face A/C.

> ⚠⚠ **Superseded (2026-10-23): the paragraph below is the 2026-07 position.** Per-file reclamation
> shipped twice since: ST-3b (client-driven, `POST /sync/content/delete`, removed in CR-2 B6) and
> CR-2 candidate B (relay-driven, the edge set riding the push, live). The "release refs" leak it
> rejects was accepted in the reduced form B needs — opaque `(holder, property, contentId)` edges, no
> names or paths (threat model §8.3, B3c/B5r amendments). Current facts: §3.4.

**The deleted-file-blob residual (bounded by quota, not reclaimed per-file).** Inside a
*live* account, a deleted file's unique blobs cannot be reaped per-file (HR#1 — the relay
can't tell which blobs the delete orphaned; a delete tombstone exposes only an opaque entity
hash). They **linger, counted against `bytesUsed(ownerId)`** — quota is the brake (same
principle as `drive-folder-link-sharing.md` D7). Worst case: the owner "pays" storage for
deleted files until they (a) free space / upgrade, or (b) lapse and get the whole keyspace
reclaimed. A per-file reclaim would need a client "release refs" signal that leaks the deleted
file's chunk-ref set + size to the relay — **rejected** (T-R-09; the metadata leak is worse
than the storage cost). *Optional future (P4):* client-driven **compaction / re-pack** — the
owner's own device, holding the manifests, re-PUTs only live content into a fresh keyspace and
abandons the old — reclaims deleted-blob space HR#1-safely.


### 3.3 P3b-2 as built (2026-08-04) — the producer, the route, and the sweep

The half P3b-1 left open: something that *mints* a closure record, something that *accepts* one, and
the loop between them. All three land together — a delete-capable inbound route with no producer is
risk without function, and a producer with no route is dead code.

**Commerce grows a closure state.** `AccountStatus.Closed` + `Account.closedAt` (Postgres migration
v3: a nullable `closed_at` column, no backfill — every existing account is open, which is true),
`closeAccount(accountId, closedAt)` / `reopenAccount(accountId)` /
`closedAccountsWithOwners()`, on both the in-memory and Postgres backends.

- ⚠ **Closure is idempotent and keeps the *original* stamp.** In Postgres this is a database
  invariant (`WHERE id = ? AND closed_at IS NULL`), not a read-then-write the caller could race —
  because re-closing must never restart the grace clock and quietly extend retention past the
  published terms.
- ⚠ **Only the *live* owner binding is minted for** (`account_owner … superseded_at IS NULL`, an
  INNER JOIN so a bindingless account is skipped by construction). Reclaiming a superseded
  `ownerId` would delete a keyspace the account no longer owns — one that after a recovery re-bind
  may belong to nobody, or later to someone else.
- ⚠ **`reopenAccount` clears `closedAt`,** so a later re-closure starts a *fresh* grace window. The
  person had their account back in between.

**`AccountClosureMinter`** — the third thing signed by the A29 issuer key, beside the synced
entitlement record and the bearer token. ⚠ `closedAtSec` rides as the **recorded** closure time,
never "now": re-minting the same closure on every sweep must not push the relay's window forward.

**`AccountClosureDelivery` + the sweep** (commerce, opt-in via `ACCOUNT_CLOSURE_RELAY_URL`,
`ACCOUNT_CLOSURE_SWEEP_MINUTES` default 60).

- ⚠⚠ **Delivery is a repeating sweep, not an event.** Posting once at closure cannot work: the
  record is deliberately short-lived, so a single delivery would expire long before the grace window
  it starts. Re-posting *is* the mechanism — and it is what makes **reopening** free: the sweep stops
  finding the account, nothing is re-minted, the last record expires. **Nothing has to be un-said**,
  which is precisely why the relay stores no closure state (a durable "closed" fact would need a
  reopen message, and a missed one would delete a live account's files).
- ⚠ **A failed delivery is not worth remembering.** No queue, no retry schedule, no dead-letter: the
  next sweep re-mints from the store, which is the durable truth. One failure does not abandon the
  rest of the sweep either — the accounts are independent, and an unreachable relay would otherwise
  starve whichever owner sorts first.
- ⚠ Only counts are logged. A record is a delete capability and never belongs in a log line.

**`POST /sync/account/closure`** (relay) — the only inbound surface that can destroy user content.

- ⚠⚠ **The request body *is* the credential.** Safety does not rest on the transport: an attacker who
  reaches the endpoint without the issuer's private key can do nothing. Same posture as the
  entitlement token, applied to a heavier verb.
- ⚠ **The route does not exist unless switched on.** `ACCOUNT_CLOSURE_RECLAIM=false` (the default) or
  no issuer key ⇒ nothing is registered, and a probe gets a plain **404** — not a 403 that would
  confirm the feature exists here.
- ⚠ **One opaque 401 for every rejection** — bad signature, expired, wrong `kid`, malformed, missing
  owner. Distinguishing them would tell an attacker which of the four brakes they had cleared.
- **202 `within_grace`** (understood, deliberately not acted on) vs **200 `reclaimed`**
  (`{blobsDropped, bytesFreed}`). ⚠ Typed response classes, not `mapOf(...)`: a heterogeneous map has
  no kotlinx serializer and 500s at runtime, which on this route reads as "the relay is broken"
  rather than "not yet due" — caught by the route tests, not by the compiler.

⚠⚠ **Two independent opt-ins, one on each side.** Commerce needs `ACCOUNT_CLOSURE_RELAY_URL`; the
relay needs `ACCOUNT_CLOSURE_RECLAIM`. Content is deleted only when **both** are set, so the decision
to delete is made at deploy time rather than by code review — and either flag alone deletes nothing.
Both are set in production since 2026-10-24.

**Tests:** `AccountClosureMinterTest` (6) · `AccountClosureDeliveryTest` (5) ·
`AccountClosureRoutingTest` (9) · `AccountClosureReclaimerTest` (P3b-1). ⚠ Almost every route case
asserts a *refusal*, and each also asserts the blobs are still there — a route that answered
correctly while deleting anyway would pass a status-only test.

**Still open:** ⚖️ counsel review of the now-drafted policy §4 (below); and an end-to-end pass
against a live commerce↔relay pair. ~~No UI closes an account yet~~ — the admin console closes
(CE-5), and since 2026-10-23 **users delete their own account** (`commerce-accounts-and-sessions.md`
§9.7), which closes it and rides this path unchanged. ⚠⚠ That raised the stakes of the flags: a
user can now *ask* for the content deletion this section describes, and since 2026-10-24 gets it.
⚠⚠ **`closedAccountsWithOwners` skips a realm an open account still holds** (2026-10-23). Erasure
removes the email, so the same person signing in again from the same device gets a *new* account
bound to the *same* `ownerId`; minting for the erased one would reclaim the files the new one uses.
So "signing in again within the grace window keeps the files" is the self-service form of reopening.

### 3.3a Closure erases the whole owner, not only content — F12 as built (2026-10-27)

⚠⚠ **Until this slice, P3's "deletion" deleted content bytes and nothing else.** The route ran
`reclaimOwner`, which drops `content_blob` rows and bytes; the ordered log (`sync_block`,
`delete_block`), the device roster, HLC tips and cursors, the CR-2 edge/ref/freed/clock tables, the
recovery blob, share descriptors *and their chunk bytes*, invites, grants, EPC sessions, pairing
offers and the `realm` row survived closure indefinitely (`relay-metadata-minimisation.md` F12). §3.1's
"content only" scoping was right for the *primitive* and wrong for the *closure*: tier 1's quorum GC
never runs for a realm whose devices stop polling, so for a closed account "tier 1 has its own GC"
meant "tier 1 is kept for ever".

**As built.** `AccountClosureReclaimer` now calls `AccountErasure.eraseOwner(ownerId)`:

1. **Rows, one transaction.** Every owner-scoped store implements `OwnerScopedStore.eraseOwner`, and
   on SQLite they all run inside one `RelayDatabase.inTransaction` — a failure halfway rolls every
   store back. The store list is `ownerScopedStoresOf(...)`, one required parameter per store, shared by
   `Application.module` and the tests.
2. **Bytes, from a durable ledger, after the commit.** Content index rows move into
   `content_free_queue` (the ledger `ContentReclaimJob` already drains) in step 1; shares are *revoked*
   in step 1 and their rows kept as the ledger of `share/<id>/<n>` keys. Step 2 deletes bytes, then
   the ledger, batch by batch under the store lock. ⚠ A crash or failed object-store delete leaves
   ledger rows, and the next closure post (hourly) finishes them — where `reclaimOwner` used to leave
   bytes nothing would ever name again. `reclaimOwner` itself now runs the same two phases.
3. **Re-runnable.** A second post on an erased owner reports zeros; the 200 body gains `rowsErased`.

**What survives, deliberately** — one row: `erased_realm(owner_id, erased_at)`
(`AccountErasure.KEPT_TABLES`). The three revocation fences (`removed_device`,
`admission_revocation`, `member_revocation`) are erased with everything else. ⚠ F12's first draft
kept them instead, because the owner could re-create the realm after erasure (genesis is
master-self-signed; `reassertGenesis` self-heals) and every capability they revoke is a master
signature that would verify again there. The merged change closes that more strongly: `POST
/sync/realm` answers 410 Gone for an erased owner, so the realm is never re-created and the old
signatures have nothing to verify against. Full keep/delete table: `AccountErasure`'s KDoc and the
threat-model amendment (A6, F12).

**The P3 guard is unchanged and stays in commerce.** The relay erases exactly the `ownerId` it is
posted; commerce never mints for a realm an open account still holds (`closedAccountsWithOwners`).
A shared realm is its own `ownerId`, so erasing a closed member or owner never reaches it —
`AccountErasureSchemaTest` seeds a held shared realm in every table and asserts it untouched.

**Pinned by** `AccountErasureSchemaTest` (6): tables are enumerated from `sqlite_master` and seeded
generically from `PRAGMA table_info`, so a table a later migration adds is checked without editing the
test, and a table with no owner column fails until someone decides what closure does with it — plus
idempotency, a byte-phase failure that the next run finishes, and a row-phase failure that rolls back.
`AccountErasureInMemoryTest` (1) holds the in-memory twins to the same table.

**Still open.**
- ⚠ **Shared realms a closed person created are never erased** — nothing posts their realm id, and
  members may still rely on them. Which account "owns" a shared realm for retention is undecided.
- ⚠ **Pre-existing orphans** — bytes a crash left with no index row before any of this ran — are not
  reached: `BlobStore` cannot be enumerated by owner. An owner-prefix sweep on S3 (`<prefix><ownerId>/`)
  would close it; the file backend base64-encodes keys and would need a full scan.
- ⚠ The relay's 30-day database backups and the weekly blob mirror still hold erased rows / bytes for
  their own retention (policy §4.4).
- ⚖️ The published privacy notice still says account-level deletion of change records and device
  entries is "not yet in operation"; it has been since 2026-10-24 for content, and now is for the rest.
  Counsel owns that text.

⚖️ **Reviewed by the maintainer as counsel on 2026-10-28** under
[`policy/privacy-review-checklist.md`](../policy/privacy-review-checklist.md). P3 and F12 are accepted
as built. The notice was corrected to match, and `erased_realm` is now described as
pseudonymous, Art. 6(1)(f), and kept while the service runs. (*What survives* above was corrected
to match on the same day.) **Open, the maintainer's call:** blob-mirror pruning (`MIRROR_PRUNE`) is still off, so a closed
account's mirrored bytes stay indefinitely, and the notice says so. Restores do not re-apply erasures
(`tech-debt.md`). The one-off lawyer review before the paid launch still covers all of it.

### 3.4 Per-file reclamation, as it stands (2026-10-23)

Established for `acceptable-use-and-limits.md` §4.4, whose *"server-side deletion is per account, not
per file"* had gone stale. The mechanism is CR-2 candidate B (`content-plane-cr2-quorumless.md` §10.2,
G1–G4), built in B3–B6 (`1919cb67b`); the specs are `content-plane-cr2-b5-spec.md` and
`content-plane-cr2-b6-spec.md`. **No code changed for this section**, only docs. Line numbers are as of
`0d72a9fd6`.

**Which mechanism.** Neither the client-declared refcount's sweeps nor the reclamation barrier: both
were removed in B6 (`ContentReclamationSweep`, `ContentEnumerationSweep`, `ReclamationBarrier`,
`POST /sync/content/delete|reclaim`; B6 spec §7.1–§7.2). What frees content is **the relay's own sweep
over the edge registers the push maintains**:

1. **The reference arrives with the write (G1).** A content-bearing push carries its
   `(holder, property, contentId | none)` edges, computed on the client by `contentEdgesOf`
   (`core-common/.../content/ContentEdges.kt:199`) from the schema's `ContentReferencing.CONTENT_ID`
   declarations: `File.ContentId` (`FileServiceCommonModule.kt:837-841`) and `Workout`
   (`HealthServiceCommonModule.kt:131-135`). The relay commits the block and its edges in one
   transaction (`SyncRouting.kt:332-340`).
2. **A delete removes them in the tombstone's transaction.** `push-tombstone` carries the delete's
   cascade, and the relay drops the target's and every cascaded holder's edges with the tombstone row
   (`SyncRouting.kt:477-496`); a reap elsewhere sends its own removal (`ContentRouting.kt:443`). An
   overwrite moves the edge to the new content id (`SqliteContentEdgeStore.kt:28-53`).
3. **The clock starts at the one-to-zero transition.** When a content loses its last live edge, the
   same transaction writes `content_unreferenced.since = now` (`SqliteContentEdgeStore.kt:183-189`); a
   new edge clears it (`:47`).
4. **The sweep frees after T.** Every `CONTENT_SWEEP_INTERVAL_MS` (5 min) the job selects rows older
   than `CONTENT_GRACE_MS` (15 min) (`ContentSweepJob.kt:153`), frees each in a transaction that
   re-checks zero live edges (`SqliteContentFreedStore.kt:26-74`: refs moved to the owner-keyed
   `content_free_queue`, `content_ref` dropped, `content_freed` recorded), then drains the queue
   (`ContentSweepJob.kt:217-227`) through `ContentReclaimJob.drain`, which deletes index rows and bytes
   under the store lock (`ContentReclaimJob.kt:160-228`, `blobs.deleteAll` at `:205`). Launched only
   when the mode is not `off` (`Application.kt:664-677`). Defaults: `RelayConfig.kt:287-289`,
   `:344-347`.

**Flags and config.** `CONTENT_SWEEP_MODE = off | dry_run | armed` (`RelayConfig.kt:324-331`).
`RelayConfig`'s default is `off`, but the deploy's compose file defaults it to **`armed`**
(`sync-relay-server/docker-compose.yml:114`), and nothing under `deploy/` overrides it (B6 spec §7.4;
`git grep CONTENT_SWEEP_MODE -- deploy` finds only a README). `CONTENT_GRACE_MS` and
`CONTENT_SWEEP_INTERVAL_MS` are forwarded empty, so the relay defaults apply (`docker-compose.yml:116-117`).

**Delay.** A content is freed between T and T + interval after its last edge goes, i.e. **15–20 min**
after the delete's tombstone is accepted, and its bytes are drained in the same run — **for described
content only** (below). Two bounds can
stretch that: at most 5,000 frees per run and 50 drain batches of 200 refs per owner (B5 spec §6), and
the drain **pauses for the whole owner** while any content is edge-only with a stored manifest (a
declaration in flight; `ContentReclaimJob.kt:179-187`). ⚠ That pause has no age bound: a content stuck
in that state would hold every later drain for its owner. Logged as `pausedOnEdgeOnly`. ⚠⚠ And that
state is **not transient**: it is exactly a content whose describe never landed (next paragraph), which
persists. The owners with leftover drain work are kept only in memory (`pendingDrain`,
`ContentSweepJob.kt:35-37`, `:109`), so after a restart a paused owner is drained again only on its next
free. ⇒ one undescribed file can hold back byte deletion for its whole account, indefinitely.

**⚠⚠ Content whose describe never landed is neither freed nor uncharged.** The describe is best-effort
and does not self-heal (`SyncObserver.kt:2806-2807`, `:2829-2837`); only the backfill re-describes, and
it latches once per `ContentBackfillMarker.BACKFILL_FORMAT` (`ContentBackfillMarker.kt:106`). `free()`
queues only `content_ref` rows (`SqliteContentFreedStore.kt:43-56`), so such a content is marked freed
with nothing queued and its blobs stay; and `heldBytesIn` joins through `content_ref`
(`SqliteContentFreedStore.kt:204-221`), so they stay charged too. A leak, never a loss —
`docs/tech-debt.md` *"An undescribed content is never freed on delete"*.

**Quota.** In `armed`, held (unreferenced or queued) bytes of **described** content are excluded from
`/sync/usage` and the PUT quota check at once (`ContentRouting.kt:96-97`, `:180`, `:465-470`), so the
user normally sees the space back as soon as the delete lands.

**Dedup / shared blocks.** A content id is minted per content write, random, not a hash of the bytes
(`file-content-addressing.md` §8.12), so two uploads of identical bytes are two contents; there is no
cross-file or cross-user chunk dedup. Where several holders do name one content id, the edge set counts
them: `free()` refuses while **any** live edge remains (`SqliteContentFreedStore.kt:29-37`), and the
drain spares any ref still described by another content (`ContentReclaimJob.kt:189-195`). The keyspace
is `(ownerId, ref)` throughout, so a cross-realm share copy under another owner is never touched.

**Offline and never-foregrounded devices.** No device is consulted (threat model §8.3, B5r: "no device
can delay a free"). The deleting device's tombstone has to reach the relay, so an offline delete takes
effect when that device next pushes; a peer that is offline, fenced or never opened delays nothing. The
2026-09-21 and 2026-09-26 stalls (memory `reclamation-barrier-needs-every-device-foreground`,
`content-plane-cr2-quorumless.md` §1) were ST-3b's barrier, which B6 deleted. A peer holding an unpushed
reference older than T meets `content_freed` and re-uploads or shows the loss (G3, B4).

**Overwrites and versions.** An overwrite mints a new content id (`file-content-addressing.md` §8.12)
and the push moves the edge, so the old content enters T and is freed like a delete. Nothing retains
old versions: `FileModification`'s `manifestId` (`file-service/.../FileModification.kt:180-182`, "for
future restore to version") is not a `ContentReferencing` slot, so it holds no edge, and no
version-history or trash feature exists.

**Production: UNVERIFIED.** Not connected to. The relay image deployed 2026-10-13 (`579024ce`)
contains B3–B6 (`1919cb67b`), but the mode comes from the compose file and `deploy.env` **on the box**,
not from the image: `/opt/tenshu/repo` may predate `1919cb67b` (`deploy/redeploy.sh`'s
`git pull --ff-only || true` fails silently), and `deploy.env` is edited by hand. `armed` is therefore
only the **deploy default**. The authoritative checks are `GET /ops/content-sweep/status` (operator
token, loopback) or the boot line `Content sweep: armed, T=900000 ms` (`Application.kt:478-479`); with
the mode `off` or absent, no line is logged and nothing is freed.

**What per-file reclamation does not reach** (each is a statement §4.4 of the policy makes):

- ⚠⚠ **The additive-only mirror.** `tenshu-relay-blobs-mirror`, filled weekly by
  `deploy/scaleway-relay-backups/mirror-blobs.py` (`tenshu-blob-mirror.timer`), **never deletes**, by
  design (`deploy/scaleway-relay-backups/main.tf`, `mirror-blobs.py` docstring, as of `0d72a9fd6`). Every blob the sweep
  frees that had been mirrored stays there indefinitely; `--prune-report` lists them, and a prune is a
  manual decision. It applies to account closure too.
  ⚠ Since 2026-10-23 the script **can** prune (caveat 1 below, built): a first-seen-missing ledger in
  the mirror, a 30-day window counted by clock *and* weekly sightings, source-identity / clock /
  mass-deletion guards with a sticky hold, and **off until `MIRROR_PRUNE=on`** — a deploy-time
  decision, like P3's flags. Once on, a deleted blob normally leaves the mirror 35–42 days after it
  leaves the content bucket (never under 30); ⚠ any failed, late or held run postpones it, so there
  is no hard upper bound to publish.
  `docs/how-to/back-up-the-relay-database.md` *Pruning*.
- **Tier-1 records.** The tombstone GC iterates tombstones only (`MailboxStore.kt:183-205`) and drops
  the bodies of blocks whose target hash **equals** the tombstone's target (`SqliteSyncMailbox.kt:342-355`),
  once **every** realm device has acknowledged it and `DEFAULT_RETENTION_WINDOW_MS` (30 d) has passed
  (`RelayConfig.kt:366`) — so here a never-opened device *does* delay deletion. ⚠⚠ That reaches only a
  file deleted **on its own**: a folder delete emits **one** tombstone, so the change records of the
  files deleted through it are never dropped, nor are the parent folder's listing blocks. And
  `DeleteFileImplementation` saves a synced `File`/`FolderDeletedNotification` carrying the deleted
  **path** (`DeleteFileImplementation.kt:106-122`), which nothing drops. Compaction (E4) would, and
  never runs (`core-schema-evolution.md` §8.3.7). `delete_block` rows, holder watermarks and
  `content_freed` rows (opaque ids and times) are never deleted. Relay DB snapshots (index and log, no blob bytes) are kept 30 days
  (`deploy/scaleway-relay-backups/variables.tf:39-47`).
- **Shared-realm content.** `SharedFile` emits no edges (B2 audit Q6, `content-plane-cr2-quorumless.md`
  §10.12 `:1243`), so G4 keeps it out of the sweep: a leak, never a loss.
- **Content with no edge history** (G4): the losing chunk sets of duplicate emits (one content emitted repeatedly under fresh keys), legacy entity-keyed
  emits, and relation-slice snapshots — `SliceSnapshotWriter` declares no edge, and the ref is
  deterministic (`RelationSliceSnapshot.kt:101`), so a re-seal overwrites it in place
  (`ContentInventory.kt:23`); until then a snapshot can still list a deleted file's encrypted row. Only
  the B0 operator reclaim (`docs/how-to/relay-content-reclaim.md`) frees them.
- **Share-link copies.** A share link stores its own ciphertext (`ShareLinkImplementation.kt:229` →
  `SqliteShareStore.putChunk`). Deleting the file does not revoke the link, and the share store
  collects a share only 30 days (`DEFAULT_RETENTION_WINDOW_MS`) after revoke or expiry
  (`SqliteShareStore.kt:109-157`, `ShareStore.kt:194-208`), so a non-expiring link keeps the content
  stored **and viewable** indefinitely.
- **Peers' local plaintext.** A peer that had downloaded the file keeps it on disk; the fix
  (`OrphanedBytesSweep`) is built and off (`ORPHANED_BYTES_SWEEP_ENABLED = false`,
  `control-shared-proxy/src/desktopMain/.../PlatformProxyModule.kt:207`; `docs/tech-debt.md` *"A delete
  frees the deleting device's disk and no one else's"*).

**What would make the policy's caveats go away — each a deploy-time or product decision, not built
here:**

1. **Mirror retention.** Prune mirror objects the source has lacked for longer than a published
   window, which needs a first-seen-missing ledger (the mirror cannot otherwise tell when the source
   deleted a key), or put a lifecycle rule on the mirror. ⚠ This removes the mirror's protection
   against a sweep bug after that window, which is the reason it never deletes; the window is the
   trade. Then §4.4's backup bullet becomes "kept up to N days". ⭐ **Built, off** (2026-10-23):
   ledger + window + breaker in `mirror-blobs.py`; turning it on is the operator's step.
2. **`ORPHANED_BYTES_SWEEP_ENABLED`**, plus the Android and iOS wiring (desktop-only today), for the
   peer-plaintext bullet. §10.9 of the CR-2 doc calls it safe under B.
3. **Shared-realm edges** (B2 audit W9/Q6) for the shared-folder bullet.
4. ~~**Account closure (P3)** for §4.3~~ — both flags ON in production since 2026-10-24 (§3.3);
   ⚖️ counsel review of the text still owed.
5. **The defects behind the remaining caveats**, each in `docs/tech-debt.md`: undescribed content never
   freed or uncharged; the drain's unbounded account-wide pause and in-memory `pendingDrain`; folder
   deletes leaving cascaded files' change records, and deletion notifications keeping paths; a file
   delete not revoking its share links.


---

## 4. Face B — Client local-cache ceiling ("max local data")

**Goal:** bound how much decrypted plaintext a device holds, evicting cold content, without
ever evicting content the user asked to keep offline.

**The model:** tier-3 plaintext is a **cache of tier 2** with a **per-device byte ceiling**
(a setting: *"keep at most N GB on this device"*). When the cache exceeds the ceiling, evict
**coldest-first (LRU)** among *evictable* content until under it.

**What exists (the download half; evict half is the gap).** The offline-keeper already
classifies paths and *downloads* pins, but never evicts:
- Model: `file-service/.../file/sync/SyncPolicy.kt` — `SyncPolicy { KEEP_OFFLINE, ONLINE_ONLY }`,
  `PathSyncOverride(pathPrefix, policy)`, `SyncRuleSet.effectivePolicy(path)` (longest-prefix).
  Persisted as `FileSyncPreferences` device settings.
- Keeper: `file-service-proxy/.../impl/OfflineKeeperImplementation.kt`
  (`selectKeepOfflineDownloads`) — download-only, its KDoc literally says *"Never evicts."*
- Eviction primitive already exists: `EvictFileBytesImplementation` (`EvictFileBytes` op) drops
  on-disk bytes but keeps the `File` entity + `Syncable(OnDemand)` data, so a later open
  re-fetches (deliberately **not** a `DeleteFile` — must not converge to a tombstone). Plaintext
  SPI: `core-common/.../proxy/FileDatabase.kt` (`writeRawByteArray`/`readBytes`/`fileSize`/…).

So the two halves this face needs are: (a) a **byte ceiling** + accounting, and (b) an **LRU
policy** driving `EvictFileBytes` — the "evict half" `drive-offline-online.md` §4.4 already
names as the outstanding, **bgproc-gated** keeper track (the `OFFLINE_ALL` device default =
"keep everything, evict LRU under a storage budget", the iCloud model — collapsed to
`KEEP_OFFLINE` at the model level, with eviction a keeper property, not a resolved-policy one).

**Pinned vs evictable.**
- **Pinned** (`KEEP_OFFLINE` path/override): guaranteed local, **exempt** from ceiling
  eviction — and (D6 as corrected) **not counted against the ceiling either**. The two go
  together: content that cannot be spent must not be measured, or the budget is one the
  device can be permanently over.
- **Evictable** (`ONLINE_ONLY` / unpinned OnDemand cache): the ceiling's entire domain.
  Evicted coldest-first under pressure via the existing `EvictFileBytes` primitive.
- ⚠ Total footprint is therefore `pinned + ≤ ceiling`, and the **free-disk floor** is what
  bounds the whole. A user who pins more than their device holds is a case only the floor and
  the Face-C usage gauge can speak to; the ceiling is not the place to discover it.

**Distinct from `bounded-store-cache.md`.** That work bounds the **metadata object-store's
RAM** (`SqliteObjectStore` cache-aside holding every row resident — the `Workout.Streams` /
`File.Thumbnail` OOM); this bounds the **content plaintext's disk** (`FileDatabase`). Different
resource, different plane — but they should share the "evict-coldest-under-a-ceiling"
vocabulary.

**Defaults + tiering — Decided (D6).** A **fixed, user-settable default**
(platform-tuned — larger on desktop, smaller on mobile) **plus a hard free-disk floor**
("always leave ≥ Y GB free" so we never fill the device). **Never tier-coupled** — the local
cache is a *device* concern, not a billing one, and coupling it would evict a downgraded user's
data (a time-reap-class footgun).

### 4.11 As built (2026-08-03)

⚠⚠ **The "bgproc-gated" premise was already false when this doc was written, and it is worth
recording why the check was cheap.** Three claims had to hold for the gate to be real, and none
did: `registerOfflineKeeperSchedule` is called at proxy init on Android, desktop **and** iOS;
`SyncProxyInitialization` constructs the `ScheduleDrainLoop` that dispatches it; and the whole
thing has been running on a 6-hour period since the `PlatformScheduler` seam landed. What Face B
was actually missing was a **budget policy** and a **storage-pressure signal** — not a scheduler.
Same shape as this track's other stale-gate finds; the lesson is that a doc naming a dependency
is a claim to verify, not a fact to plan around.

**Where it lives.** The evict half is a second phase of the *same* `OfflineKeeperImplementation`
pass that already downloads pins — one tree walk, two halves. ⚠ They cannot fight: the download
half only fetches `KEEP_OFFLINE` content and the evict half never touches it, so the ordering is
safe by construction rather than by luck.

**Two pressures, whichever demands more** (`selectCacheEvictions`, pure and unit-tested):

- **the ceiling** — `FileSyncPreferences.MaxLocalBytes`, "keep at most N bytes of *cached*
  content here" (pinned content is outside it entirely);
- **the free-disk floor** — `FileSyncPreferences.MinFreeSpaceBytes` (1 GiB) against
  `FileDatabase.availableSpaceBytes()`. A cache's correct answer to a nearly-full *device* is to
  yield, whatever the user's own limit says.

**⚠ The ceiling ships at `0` = unlimited; only the floor is on by default.** D6 asks for a
platform-tuned non-zero default, but a default-on ceiling with no screen to see or change it
would start silently deleting cached content on upgrade — which is precisely the objection that
deferred this work in `drive-offline-online.md` §3.2 ("it silently removes local data"). The
floor addresses the actual harm (filling the disk); the ceiling turns on with its settings UI.
`aCeilingOfZeroMeansUnlimited` and `evicts nothing under the shipped defaults` pin this.

**⚠⚠ Pinned content is neither evicted nor counted — the ceiling scopes to the cache** (D6 as
corrected; the first cut of this slice counted it). Counting content that cannot be spent makes
the setting unsatisfiable as soon as pins exceed it: the only observable effect is that the
*evictable* cache is wiped forever while the total stays over. Scoping to evictable content makes
the ceiling always satisfiable, which is why `selectCacheEvictions` returns a plain path list —
there is no shortfall left to report. ⚠ The remaining unsatisfiable case is the **floor**, when a
disk is full of other apps' data; there the sweep evicts everything it may and stops, and
warning would be wrong because the cause is not ours. "Your pinned content is larger than you
expect" is a *usage-gauge* statement (Face C), not an eviction-plan output.

**⚠ Recency is a *write* time, not an access time.** New `FileDatabase.lastModifiedMs` (JVM /
Android / iOS actuals; `InMemoryFileDatabase` keeps a monotonic write counter for deterministic
tests). A real filesystem's `atime` is unreliable (`relatime`/`noatime`) and not portable, so the
order is least-recently-**fetched**, which for a write-once content cache is the same thing until
something starts touching files on read. Unknown recency sorts **last**, so a platform that
cannot report times does not thereby classify its whole cache as maximally cold.

**No schema or wire change** — the two settings are ordinary `DeviceSetting` rows over the
existing `genericData` value slot, so no composite reshaped (`SchemaShapeBaseline` green) and
House Rule #9 is untriggered.

### 4.12 The settings screen (2026-08-03)

**Shipped**, so the ceiling is now reachable: Drive → *Sync preferences* → **Cache size limit**.
`FileSyncPreferences.MaxLocalBytes` → `fileSyncMaxLocalBytes`/`setFileSyncMaxLocalBytes`
(`:file-service-client`) → `SyncRulesPreferences` (Drive) → `SyncPreferencesViewModel` → a list row
plus a radio dialog.

- ⚠ **The copy says "cache", per D6** — the supporting line spells out that files kept offline do
  not count towards it. That is the correction made *visible* rather than only documented; it is
  the one piece of this UI that is a correctness requirement, not a wording preference.
- **A fixed short list** (`CacheCeilingChoices`: No limit / 1 / 5 / 10 / 25 / 50 GB) rather than a
  slider or a text field: the value only has to be roughly right, and a list is the one shape that
  cannot produce a nonsense number (0 bytes, 3 KB, larger than the disk). ⚠ `nearest()` snaps a
  stored value **downward** to the offer at or below it, so opening the screen can never silently
  *raise* someone's limit — a value written by a future slider or another build survives a visit.
- ⚠ **"No limit" (`0`) is a first-class choice, not an unset state.** It is the shipped default and
  the user can return to it.
- ⚠ **Nothing is evicted at the moment of the write.** Enforcement is the keeper's periodic sweep,
  so a lowered ceiling takes effect on its next pass; the dialog copy does not promise otherwise.

**The iOS twin SHIPPED the same day**, and it really was SwiftUI only: a `Picker` in
`drive-ios`'s `SyncPreferencesView`, over the *same* `SyncPreferencesUiState.maxLocalBytes` and
`setMaxLocalBytes` the Compose screen uses — no Kotlin change at all beyond what P2 already
exported. Two bridging details are worth keeping:

- ⚠ **The offered values come from shared Kotlin** (`CacheCeilingChoices`), not a Swift copy, so the
  platforms cannot drift into offering different limits. `values` bridges as `[KotlinLong]`, so it is
  unwrapped to `Int64` to match what `nearest(bytes:)` returns and what the `Picker` tags.
- ⚠ **A Kotlin `object` and a Kotlin top-level function export differently.** `CacheCeilingChoices`
  is an object → `CacheCeilingChoices.shared.…`; `formatByteSize` is a *top-level function*, which
  lands on a file-facade class whose members are Swift **statics** → `ByteSizeFormatKt.formatByteSize(…)`
  with no `.shared`. Getting this wrong is a compile error, not a runtime surprise, but it costs a
  build cycle to discover.
- ⚠ The label is formatted through that shared function rather than interpolated into a `Text`:
  SwiftUI locale-groups an interpolated integer, which is how a 2.1 GB region once printed as
  "2.100 MB" (`ios-fit-timeline-maps-parity.md` §3.3.1).

⚠ **Not visually verified.** `xcodebuild` is green, but Drive's iOS shell gates on
`DriveLocalRuntime.isProvisioned()`, so reaching Settings at all needs a Control-genesis A/B session
— the same tail every other iOS surface in that proposal carries. The type bridging (the one
genuinely iOS-specific risk here) is compiler-checked; the binding logic is the shared, unit-tested
view-model.

---

## 5. Face C — Quota UX (usage + proactive warnings)

**Goal:** the user knows their tier-2 fill level *before* they hit the wall, and understands
a lapse before content is at risk.

**The display exists; its `usedBytes` is fake.** `storage-service/.../StorageStatus.kt`
(`{hasStorage, usedBytes, limitBytes}`, locally derived, single-row, **not** Syncable) is
already rendered — Control Settings → Storage (`ServicesViewModel.syncedStorage`), and web
read-only (`web-storage-status.md`). `EntitlementStorageProvider.entitlementToFigures` derives
`limitBytes` **real** from the synced `Entitlement.productId` (via `EntitlementProducts.quotaBytes`)
but sets **`usedBytes = 0L` (mock)**. So the fill gauge is already on screen — it just always
reads empty.

**The one missing wire: relay → client usage.** `bytesUsed(ownerId)` is **relay-internal only**
(no `GET /usage`-style route; agent gap #2). Every "near quota" UX is blocked on this. Design a
**usage surface** — relay-reported `used` for the requesting owner — delivered as a lightweight
authed endpoint (scoped to the caller's `ownerId`; no cross-owner peek) feeding
`StorageProvider.usedBytes`, replacing the `0L` mock. ADR 0019 *When to revisit* #1 already
names this as the swap-in point, so there is **one** source of truth to reconcile, not two.
**Decided (D5): a dedicated `GET /sync/usage` endpoint**, not a sync-protocol piggyback —
usage stays orthogonal to the sync hot path and is fetched on natural triggers.

**Proactive signals (escalating), once `usedBytes` is real:**
- **Near-quota** (≥ 80 %): the one-time banner already **designed** in `control-subscription-ux.md`
  §2.3 ("You're using 80% of your Tenshu storage") — DESIGNED, unbuilt, and blocked precisely on
  the usage signal above. ADR 0019 Decision 5: it stays a *warning*, never relaxes the gate.
- **Over-quota**: uploads blocked. Today this is the hard-block `StorageGateDialog`
  (`AddFileActionsViewModel`, `StorageGateDecision.Full`) + the relay `507`; the banner makes it
  a *warned* wall, not a surprise. The no-subscription case already has its own persistent
  `SyncBannerState.LocalOnly` banner (ADR 0019 Ph4).
- **Entitlement lapse approaching**: "Renew before {date} to keep your files" — the 7-day grace
  clock made visible, so Face-A reclamation is never a surprise.

**Where:** Drive + Control status surfaces (native + web), all over the existing `StorageStatus`
/ `StorageGate` plumbing. The relay-side gate stays as-is; this is the human layer above it.

> ⚠ **Fix in passing (D9): Free tier = 5 GiB** (iCloud parity). The canonical
> `EntitlementProducts.FREE_LIMIT_BYTES` (+ mirrored commerce `ProductCatalog`) already says
> 5 GiB; the stale web-genesis mint KDoc (`web-storage-status.md` Bucket 3, "1 GiB … like
> native's free") is wrong — correct it so the gauge and the mint agree. (Tiers: **Free 5 GiB ·
> Basic 50 GiB · Premium 2 TiB**.)

---

## 6. Decisions (Q1–Q6 settled 2026-07-18)

- **D1 — Tier 2 is durable; only account-lifecycle reclaims it.** Locked (this doc's spine;
  ratified by the time-reap fix). ⚠⚠ *Superseded in part (2026-10-23):* "durable" and "never time-reaped" stand;
  "only account-lifecycle" does not — the user's own delete also reclaims it (CR-2 G2, §3.4).
- **D2 — Reclamation is `ownerId`-keyed, entitlement-signal-driven, never a relay timer.**
  Locked (HR#1). ⚠⚠ *Superseded in part (2026-10-23):* true of account-lifecycle reclamation only.
  Per-file reclamation runs on a relay timer (T after the last edge, §3.4) — but its clock starts at
  an observed one-to-zero edge transition, never at age or last access, so it is not the time-reap.
- **D3 — Deleted-blob space is quota-bounded, not per-file reclaimed** (client-release-refs
  rejected; optional future client compaction). ⚠⚠ **REVERSED by ST-3b**, which is per-file
  client-driven reclamation over `POST /sync/content/delete` and is live on desktop and
  Android — the same doc calls it "already-live" in §2. Quota remains the brake for blobs
  ST-3b does not name. See ADR 0017 D6's amendment. ⚠⚠ **And ST-3b is itself gone (CR-2 B6,
  2026-10-04):** the relay's G2 sweep is the only freer now, with no device barrier (§3.4).
- **D4 (was Q1) — Reclaim trigger = an explicit commerce-plane "account closed past grace"
  signal, with a CONFIGURABLE grace window.** The relay never derives a delete from token
  expiry (frequent + benign) — it reclaims only on a deliberate, signed commerce event keyed
  on `ownerId`. The **grace period is a config value, not hardcoded**, so the operational
  retention window stays consistent with — and adjustable for — GDPR / statutory obligations.
  Until that signal is built, **no auto-reclaim** (dead-account bytes accumulate; harmless
  pre-revenue). ⚠ **Release prerequisite (legal):** the configurable grace + reclamation
  policy MUST be reflected in the user-facing policy + ToS *before* enforcement/reclamation
  ships — see the §3 legal note. Distinct from the 7-day *offline-token* grace (a different
  clock — token tolerance, not reclamation).
- **D5 (was Q2) — Usage surface = a dedicated authed `GET /sync/usage` endpoint**, scoped to
  the caller's `ownerId`, feeding `StorageProvider.usedBytes` (replaces the `0L` mock). Not a
  sync-protocol piggyback (keeps billing/usage orthogonal to the sync hot path); usage is
  approximate/eventually-consistent, so on-demand fetch on natural triggers is fine.
  - ⚠⚠ **CORRECTED 2026-09-06 — "natural triggers" did not include any byte moving.** The fetch
    was reachable only from `refreshFigures`, whose triggers are an `Entitlement` save, an owner
    transition and the not-yet-valid retry. An upload writes none of them, a reclaim writes none of
    them, and a *peer* doing either writes nothing locally at all — so usage was read once at proxy
    start and then frozen for the life of the process. The gauge could not move, in either
    direction, while Control ran.
  - ⭐ Fixed by a bounded refresh (`USAGE_REFRESH_INTERVAL_MS`, 60 s) on the reader's existing
    owner-resolution backstop, guarded by an in-flight flag. A poll rather than a content-plane
    callback **on purpose**: a peer's upload changes this owner's usage with no local event to hook,
    so an event-driven fix would still leave every *other* device's gauge stale.
- **D6 (was Q3) — Local ceiling:** a **fixed, user-settable "max local data" default**
  (platform-tuned) **plus a hard free-disk floor** ("always leave ≥ Y GB free"). **Never
  tier-coupled** (a downgrade must not evict local data — a time-reap-class footgun).
  - ⚠⚠ **CORRECTED 2026-08-03 — the ceiling scopes to *evictable* content, not total
    footprint.** As originally written it counted pinned bytes too, which makes the setting
    **unsatisfiable by construction** the moment pins exceed it: pin 50 GB under a 10 GB
    ceiling and every opportunistically cached byte is evicted forever while the total stays
    40 GB over, with no eviction able to close the gap. The visible effect would be that the
    setting silently stops working, precisely for the users who care most about disk. So it
    is a **cache** ceiling — the device holds `pinned + ≤ N` — and bounding the *total* is the
    free-disk floor's job. ⚠ The setting's label must therefore say **cache**, not "total
    data"; a label promising a total we do not enforce would be the worse failure.
  - ⚠ **The "Dropbox model" framing is dropped — it was not Dropbox's model.** Dropbox has no
    user-set byte cap at all: Selective Sync picks folders (our pin) and Smart Sync leaves the
    rest as online-only placeholders, so *what you chose to sync is the budget* and this
    conflict cannot arise; automatic eviction there is age-based, not budget-based. The
    products that do ship a real byte cap are Google Drive for Desktop (streaming cache size)
    and media apps (Spotify, Netflix); iCloud "Optimise Storage" and OneDrive + Storage Sense
    are pressure/age-based, i.e. closer to our **floor** than to our ceiling. Keeping a
    byte ceiling is still the right call — it is the knob a user can reason about — but it
    should be justified on its own terms, not by an appeal to a precedent that isn't one.
- **D7 (was Q4) — Over-quota refuses new uploads, NEVER deletes existing content** (incl. the
  downgrade-over-limit case: preserve content, refuse uploads until back under). Delete-to-fit
  is a hard-no anti-pattern.
- **D8 (was Q5) — Enforcement stays the LAST flip, and rides the EXISTING `ENTITLEMENT_ENFORCE`
  flag — no new config knob.** During testing, enforce is off (as today). The end-state is:
  usage gauge (P1) → banner (P1b) → meter the `signed-record.v1` proof path → **flip
  `ENTITLEMENT_ENFORCE=true`** (the single, existing switch) → reclamation (P3). Never enforce
  a limit the user can't see.
  - **Proof-path metering IMPLEMENTED.** `RelayEntitlementVerifier`'s `signed-record.v1` proof
    now derives its quota from the record's `productId` (`quotaForProduct`, a lockstep third copy
    of the tier constants) instead of returning `null` — so a second device (which authorises
    writes with the proof, not a token) is metered against its tier exactly like a token write,
    closing the unlimited-storage bypass. Unknown tier → Free floor (never unlimited, never a
    false `507`). This makes `ENTITLEMENT_ENFORCE` *consistent* to flip; the flip itself stays a
    deferred deployment decision (nothing is enforced while it's off).
- **D9 (was Q6) — Free tier = 5 GiB** (iCloud parity). Fix `EntitlementProducts.FREE_LIMIT_BYTES`
  and the stale web-genesis mint KDoc (1 GiB) to agree.

---

## 7. Phasing (each independently shippable)

- **P1 — Relay usage reporting + the gauge goes live (Face C, read-only).** Cheapest, highest
  UX value, zero reclamation risk. Add the authed `used`-for-my-`ownerId` surface, swap
  `EntitlementStorageProvider`'s `usedBytes = 0L` mock for the real figure (ADR 0019 revisit
  #1), and the existing `StorageStatus` gauges light up. Unblocks everything by making fill
  level visible. *(Also the prerequisite for the designed 80 % banner.)*
- **P1b — Escalating banners (Face C).** The 80 % near-quota banner (`control-subscription-ux.md`
  §2.3, now unblocked) + lapse-approaching warning. Small once P1 lands.
  - **P1b-1 (80 % soft-limit banner) IMPLEMENTED.** A one-time, permanently-dismissable Control
    home banner (§2.3: "the only marketing prompt", no re-prompts) — `QuotaSoftLimitViewModel` +
    `QuotaSoftLimitBanner` over the now-real `StorageStatus` (free-tier + usage ≥ 80 %), a
    device-scoped `ControlPreferences.QuotaSoftLimitBannerDismissed` flag (both *dismiss* and
    *See plans* set it), `[See plans]` → `Destination.Services`. Mirrors the `RecoveryAffordance`
    home-banner pattern. *(Kept one-time, not sticky — the maintainer confirmed §2.3's
    dismiss-once framing over a persistent banner.)*
  - **P1b-2 (lapse-approaching warning) remains** — needs the entitlement `validUntil` + grace
    window, so it lands with the D4 lapse plumbing (P3-adjacent).
- **P2 — Local-cache ceiling (Face B). IMPLEMENTED 2026-08-03** (as-built §4.11). A "max local
  data" setting + free-disk floor + coldest-first sweep over non-pinned content, run by the
  offline keeper's existing periodic pass. Purely client-side, no relay/wire/schema change.
  ⚠ **The "bgproc-gated" note this carried was wrong** — see §4.11.
- **P3 — Account-lifecycle reclamation (Face A, D4). CODE COMPLETE 2026-08-04, LIVE 2026-10-24** —
  P3a the `reclaimOwner(ownerId)` mechanism (§3.1), P3b-1 the signed closure credential + grace
  decision (§3.2), P3b-2 the commerce closure state, minter, delivery sweep and relay route (§3.3).
  Amends T-R-09. ⚠⚠ Content is deleted only while **both** `ACCOUNT_CLOSURE_RELAY_URL` (commerce) and
  `ACCOUNT_CLOSURE_RECLAIM` (relay) are set — and both are, in production, since 2026-10-24. The
  maintainer opened them ahead of ⚖️ counsel's review of the retention/closure clause in
  `acceptable-use-and-limits.md` (drafted, ToS version bumped; review still owed). Production then
  held a single account and none closed.
- **P4 (optional) — Client compaction / re-pack** to reclaim deleted-blob space within a live
  account, HR#1-safely. Sized, not scheduled.

**Enforcement flip (D8)** is not its own phase — it's a one-line env change on the *existing*
`ENTITLEMENT_ENFORCE` flag (no new knob), sequenced *after* P1/P1b make usage visible and the
`signed-record.v1` proof path is metered (**done** — `quotaForProduct`), and *before* P3. The two
code prerequisites (usage visible, proof metered) are now met; the flip itself is the deferred
deployment decision.

Dependency order: P1 → (P1b, P2 independent). P3 wants the enforcement flip + the commerce
lapse signal + the legal clause first. ⚠ P2's "wants bgproc Phase 6" was stale and is struck
(§4.11).

---

## 8. Threat-model deltas (per phase, House Rule #2)

- **Face A reclamation:** the relay reclaims by `ownerId` (already its keying); no new
  content-reading, HR#1 intact. Amends T-R-09 (the reclamation model, already noted 2026-07-18).
- **Face C usage surface:** the relay revealing an owner's `used/limit` to *that owner* is
  not a new leak (it's their own data over an authed channel), but pin down that usage is
  scoped to the requesting `ownerId` (no cross-owner peek). Minor A6 note.
- **Face B:** purely local plaintext lifecycle (tier 3) — amends A5 in the same shape the
  content-preview temp-file amendments did (transient decrypted plaintext on device); no
  relay/wire change.

---

## 9. Relationship to existing docs

This doc is the **umbrella model**; it does not supersede the pieces, it fits them together.

- `file-content-addressing.md` §4.2 + T-R-09 — tier 2 (durable content) + the time-reap fix; this
  doc's Face A is that doc's "designed, not-yet-built" reclamation mechanism, promoted here.
- `drive-offline-online.md` — the offline-keeper; this doc's Face B is its outstanding "evict
  half" + budget question, framed as tier 3.
- `storage-capacity-gating.md` + ADR 0019 + `web-storage-status.md` — the `StorageStatus` /
  `StorageGate` display + gates; this doc's Face C makes their `usedBytes` real and adds the
  proactive layer (ADR 0019 revisit #1).
- `control-subscription-ux.md` §2.3 — the designed 80 % banner; unblocked by Face C P1.
- `commerce-billing-plane.md` / `commerce-accounts-and-sessions.md` — the entitlement/quota +
  grace + retention split that Face A's lapse signal rides.
- `bounded-store-cache.md` — the *sibling* ceiling on the metadata plane (RAM), not content
  (disk); shares vocabulary, not scope.

- `docs/policy/acceptable-use-and-limits.md` — the versioned user-facing policy (feeds ToS
  acceptance, commerce §5.1); **D4's legal prerequisite** adds its missing retention/closure
  clause before P3 ships.

**Next:** build **P1** (the `GET /sync/usage` endpoint + swap the `usedBytes` mock → the gauge
goes live). Decisions D1–D9 are settled; P1 needs no further input.

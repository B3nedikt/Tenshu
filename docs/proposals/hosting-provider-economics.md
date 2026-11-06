# Hosting provider economics — what a provider move would and would not buy

**Status:** Analysis + decisions; **D10 has shipped** (`S3BlobStore` 2026-09-01, the production
cutover 2026-09-13 — see its as-built notes). Records D1–D10 below so the next person does not
re-derive them. Arose from a maintainer question about how Hetzner compares with DigitalOcean on
price, which unfolded into latency, CDN applicability and multi-region — the same lineage as
[`content-plane-transfer-concurrency.md`](content-plane-transfer-concurrency.md), which came from
the adjacent question about whether the Frankfurt relay serves US/Asia users acceptably.

The short version: **the provider is a cost decision, not a latency decision**, and the work that
makes any provider decision cheap — `sync-relay-persistence.md` §8 Phase 4 — is worth doing on its
own merits first. Nothing here recommends a migration.

⚠ **Every price in this doc is a point-in-time figure gathered 2026-09-01 from search summaries,
not from the providers' own pricing pages** (outbound network was unavailable in the session that
wrote it). Hetzner moved prices at least twice in 2026 and OVH changes its model on 2026-10-01.
Treat the *shapes* as durable and re-check the *numbers* before committing spend.

---

## 1. What we actually run

⚠ **This table is the footprint as analysed on 2026-09-01, not the current one.** The relay has since
moved to `deploy/scaleway-relay` (fr-par), and `deploy/digitalocean` is the retired box. The rest are
moving to `deploy/scaleway-*`, with tiles going to Cloudflare R2. Take the current inventory from
`deploy/`, and re-derive any cost figure below that assumes DO pricing.

Four Terraform modules, all DigitalOcean, all `fra1` (2026-09-01):

| Module | Resources | Default sizing |
|---|---|---|
| `deploy/digitalocean` (relay) | droplet + block volume + reserved IP + firewall + DNS | `s-2vcpu-4gb`, volume **50 GB** in prod |
| `deploy/digitalocean-commerce` | droplet + volume (bundled Postgres) + reserved IP + firewall | `s-2vcpu-4gb`, 10 GB |
| `deploy/digitalocean-apex` | droplet + reserved IP + firewall (stateless) | `s-2vcpu-4gb` |
| `deploy/tiles` | Spaces bucket + **DO CDN** + managed LE cert + DNS | no compute by design (D1 there) |

⚠ Since superseded: the backend moved to Scaleway, except **tiles, which goes to Cloudflare R2**
(`deploy/cloudflare-tiles`, 2026-09-14). On a public streaming endpoint zero egress beats any per-GB
price, and Scaleway's CDN does not document `Range`. The reasoning is in
[`maps-region-delivery.md`](maps-region-delivery.md) D1.

The `s-2vcpu-4gb` default exists only because cloud-init builds the fat jar on-box; each module's
`terraform.tfvars.example` notes that `s-1vcpu-2gb` / `s-1vcpu-1gb` suffice with the prebuilt GHCR
images `.github/workflows/*-image.yml` already produce. **Sizing down to the images we already
build is the cheapest available saving and needs no migration at all.**

---

## 2. The cost comparison

Like-for-like on the footprint above:

| | DigitalOcean | Hetzner (EU) | OVHcloud |
|---|---|---|---|
| 2 vCPU / 4 GB | **$24** | CX23 **€3.99** + €0.50 IPv4 | D2-4 **€17.81** |
| Block storage | $0.10/GiB-mo | €0.0572/GB-mo | ~€0.11/GB-mo (Gen-3 local) |
| Egress | 4 TB incl./droplet, then $0.01/GiB | 20 TB incl., then €1/TB | **unmetered** (EU/NA) |
| Object storage | Spaces $5 → 250 GiB + 1 TiB egress | €6.49 → 1 TB + 1 TB egress | per-GB |
| CDN | yes (used by `deploy/tiles`) | **none** | yes |
| **Our footprint, as configured** | **≈$83/mo** | **≈€23/mo** | higher than both |
| **Our footprint, lean sizing** | ≈$41/mo | ≈€18/mo | — |

### D2 — Hetzner's cost advantage is EU-only

⚠⚠ **CX and CAX exist only in Falkenstein, Nuremberg and Helsinki.** Ashburn, Hillsboro and
Singapore carry **CPX/CCX only** — precisely the lines that rose **113–176%** on 15 June 2026 — and
their included traffic *inverts* the EU advantage: **1–8 TB in the US**, **0.5–8 TB in Singapore
with €7.40/TB overage**, against 20 TB and €1/TB in the EU. Any cost case built on CX pricing is a
cost case for a European deployment only, and does not transfer to a US or APAC box.

### D3 — OVH is an egress/sovereignty alternative, not a budget one

OVH is ~4.5× Hetzner on compute and roughly DO-priced. What it sells is *unmetered outbound in
EU/NA* plus EU sovereignty. At our current traffic that is not worth the compute premium. It
becomes a real lever at the volumes [`drive-folder-link-sharing.md`](drive-folder-link-sharing.md)
D9 contemplates (a maxed Premium link ≈ $300/mo at ~$10/TB) — link-sharing egress is the scenario
where "included" beats "cheap per GB". ⚠ OVH's APAC region is exactly where its egress benefit does
*not* apply (1 TB/project, then charged), and its pricing model changes on 2026-10-01.

### D4 — No plausible provider has a Japan region

DigitalOcean, Hetzner and OVH all reach Asia via **Singapore** (OVH also Sydney/Mumbai). A Tokyo
user is ~70–90 ms from Singapore and ~230–250 ms from Frankfurt on any of the three. Serving Japan
well means a *different* provider (Vultr / Akamai-Linode / AWS / GCP all have Tokyo) — which is an
argument for backend portability (D9), not for a migration.

---

## 3. Latency: geography is architecture, not provider

### D1 — The relay's geography is set by its persistence, not its provider

`deploy/digitalocean/main.tf` says the module "deliberately provisions exactly one droplet", and
[`content-plane-transfer-concurrency.md`](content-plane-transfer-concurrency.md) §2 states it
flatly: **"You cannot simply add a region."** Relay persistence is a single writer. So every
customer on earth talks to one box in Frankfurt, and Hetzner Falkenstein is ~250 km away —
**a provider swap moves the numbers by single-digit milliseconds.**

The RTT baseline is that doc's §1: ~10–40 ms Western Europe, ~90 ms US-East, ~150 ms US-West,
~170–280 ms Asia/Oceania. What those cost us, per plane:

| Plane | Sensitivity to RTT |
|---|---|
| Sync deltas (`/sync/push`, `/sync/pull`) | low — small and batched; "250 ms is invisible to someone editing metadata" |
| File content | was the problem; fixed in code by Ph1's `mapChunksBounded` (N=4), not by geography |
| Sync freshness ("has my other device updated?") | **poll-interval-bound, not RTT-bound** — `sync-relay-api.md` Ph3/Ph5 long-poll is outstanding |
| Apex SPA / web bundle | high — several serial round trips, and it is the first thing any user touches |
| Map tiles | already edge-served (`deploy/tiles`) |

---

## 4. The content plane is structurally un-CDN-able

### D5 — A CDN helps apex, the web bundle and tiles. It does not help personal content.

Three independent reasons, all by design rather than by accident:

1. **Per-owner keyspace.** `SqliteContentBlobStore` keys blobs `blobKey(ownerId, ref)`, and
   `ContentRouting.kt`'s `GET /{ref}` resolves `ownerId` from the verified JWT — "a device can only
   read blobs in its own realm because `ownerId` is the verified-token owner, never a
   client-supplied value."
2. **No cross-user dedup, ever** ([`file-content-addressing.md`](file-content-addressing.md) §
   "Dedup is the one place to diverge deliberately"). Two users holding byte-identical files hold
   two distinct objects. Cross-user cache hits are *impossible*, not merely rare.
3. **Every content GET is authenticated**, so an edge cannot serve one from cache without
   presigned URLs.

For a solo user the fan-out is 1. Fan-out exceeds 1 only for: a user's N devices, an M-member
shared realm, and — genuinely — Mode-A folder links, which
[`drive-folder-link-sharing.md`](drive-folder-link-sharing.md) D9 already identifies as "the R2/CDN
relief valve (immutable version-keyed chunks are cacheable, traded against revocation latency)".
**That is an egress-cost argument, not a latency one.**

⚠ And a CDN never helps a **write**. Drive is a sync app; `putChunk` is half the traffic and gets
nothing. Ph1's chunk concurrency was the right lever precisely because it helps both directions.

The residual benefit for uncached traffic — edge TLS termination saving ~3 RTT of handshake — is
real but small, and amortises to nearly nothing across the 100 chunks of a 200 MB file, because the
client reuses the connection.

### D6 — A CDN over the content plane owes a threat-model amendment

House Rule #1 survives (it is ciphertext either way), but an edge in the content path sees
IP ↔ owner ↔ blob-ref ↔ timing. [`maps-region-delivery.md`](maps-region-delivery.md) R2 spent real
design effort keeping the tiles plane "ownerless and region-blind" and structurally separate from
the account plane; extending a CDN over content deliberately crosses that line and is a House
Rule #2 amendment. Not worth it for a transport win that small. **The apex/tiles case carries none
of this** — `web/apex/Caddyfile` is explicit that it "serves only PUBLIC static assets (the
open-source apex build) — no user data".

---

## 5. Multi-region here is sharding, not consensus

### D7 — The relay is already shardable by owner

`sync-relay-persistence.md` §2 classifies every store, and every one of them is keyed by owner:
`KnownDevicesStore` `(ownerId, deviceId)`, `RealmStore` `ownerId`, `SyncMailbox` a per-owner append
log, `HlcTipStore` `(ownerId, deviceId)`, `RecoveryBlobStore` `ownerId`, `ContentBlobStore`
`(ownerId, ref)`. The doc's own verdict: the workload "is **not relational** — there are no joins,
no queries *inside* payloads, no analytics."

So multi-region for us is **"each owner has a home region"**, not "a globally consistent database".
No cross-owner query exists to break. The known asymmetry: a shared realm has one home region and
members elsewhere pay the RTT — which is what every consumer sync product does.

### D8 — Commerce stays single-region EU regardless

A28 PII plus the A29 signing seed plus self-MoR EU billing. The commerce box is never part of a
multi-region story, whatever the relay does.

### D9 — No multi-cloud before the portable backends exist

Not because it is wrong in principle, but because **the thing that makes multi-cloud cheap is a
prerequisite we have not built**. `sync-relay-persistence.md` §7 already specifies the backends as
portable by construction: plain JDBC (any Postgres or SQLite) and an "AWS SDK v2 S3 client pointed
at a configurable endpoint — works against MinIO, S3, Cloudflare R2, Backblaze B2", all selected by
env. Ph4 is "pure additions behind the SPI — no rewrite."

With that in place, "Hetzner for Germany, something-with-Tokyo for Japan" is a **deployment
topology decision, not a code change**. Doing it first instead buys a second Terraform surface on
top of the four modules in `deploy/`, a second cloud-init path, a second secret-custody story and a
second runbook — against a user base that does not exist yet. ⚠ We already carry one operational
sharp edge on the single box we run (the `ignore_changes = [user_data]` trap and the 2026-08-15
near-outage documented in `deploy/digitalocean/main.tf`). Two providers is two of those.

---

## 6. What to do, in order

### D10 — Split Ph4: `S3BlobStore` before Postgres

`sync-relay-persistence.md` §8 lists them as one phase, but they buy different things and only one
of them is urgent.

1. **`S3BlobStore`.** Primarily a **reliability** fix, not a cost one. Content blobs live on the
   50 GB volume that `deploy-relay-digitalocean.md` says fills "because users upload content and
   **nothing reclaims it**" — and `retention-quota-model.md` P3 was then built but off (both flags; on since 2026-10-24).
   ⚠⚠ **On a volume, running out of space is an outage** (2026-08-14: the relay answered 500 to
   every push while serving reads and reporting `200` on `GET /`, per `BlobStore.probe`'s own
   comment). **On object storage it is a bill.** Same underlying gap, hugely different blast
   radius. It also drops storage cost ~15×, decouples storage from compute, and makes every option
   in this doc reversible.
   **As built (S1–S3 landed 2026-09-01):** `BlobStoreContractTest` (one suite per backend),
   `S3BlobStore` behind the existing SPI selected by env, and the health fix below. ⚠⚠ Two traps
   the phasing found, neither of which the old one-line Ph4 bullet could have warned about:
   **`chunkedEncodingEnabled(false)` is not implied by the checksum setting** — left on, `PutObject`
   frames the body as `aws-chunked` and a store that does not decode the framing *stores* it, so a
   2 MiB blob round-trips as 2,098,678 bytes of silent corruption rather than a failed write; and
   **`/health` loses its disk coverage the moment the blobs leave the volume**, because it probed
   the blob store on the reasoning that "a full volume breaks the SQLite write too, so probing the
   disk covers both" — true only while they shared one. `/health` now runs a **list** of probes, the
   database's own volume among them, each a lock-free file write so it never queues behind
   `RelayDatabase`'s single connection.

   **S4 (cutover) landed as a two-pass copy, not a composite store.** Bulk copy with the relay
   still on `FileBlobStore` (nothing reads the bucket, so a partly-filled one is unobservable), then
   stop, delta copy, flip. Runbook: [`how-to/migrate-relay-blobs-to-s3.md`](../how-to/migrate-relay-blobs-to-s3.md).
   ⚠⚠ The rejected alternative is recorded in `BlobMigration`'s KDoc because it is the spec if
   zero-downtime moves ever become worth building: a read-through composite must fall through on
   **`exists`** (else `SqliteShareStore.isCollectible` reads a volume-resident share as "never got
   chunk 0" and the GC deletes its record — on a delay, against the oldest links, irreversibly) and
   on **`delete`** (else `gcSweep` reclaims from the primary and the next `get` resurrects it from
   the secondary), and `BlobStoreContractTest` **cannot** catch either, because it runs against one
   store and a composite passes it trivially.

   **The production cutover ran 2026-09-13** — DigitalOcean volume → Scaleway Object Storage
   (`fr-par`). 15,090 objects / 5,353,874,850 bytes, `failed: 0`, matching the source file count and
   the relay's own `SUM(byte_len)` exactly; the delta pass copied 0. Verified from both directions:
   a pre-migration blob fetched out of the bucket byte-identical to the volume copy (sha256 matching
   its own ref), and the first real upload's chunks landing in the bucket at their exact indexed
   lengths **with the volume file count frozen**. ⚠ The volume is untouched and is the rollback.

   ⚠⚠ **The window was lost once to a trap that has nothing to do with S3.** `docker compose
   --env-file` feeds compose-file *substitution*; a container receives only what its `environment:`
   block declares, and that block had no `S3_*` lines. The relay read a `deploy.env` full of S3
   settings, came back on `FileBlobStore`, and answered `{"status":"ok","durable":true}` — both
   backends serve reads identically, so nothing announced it. Fixed; the same fix also found
   `ACCOUNT_CLOSURE_RECLAIM` and `ACCOUNT_CLOSURE_GRACE_MS` inert the same way, the latter
   contradicting its own KDoc's D4 *"configurable, never hardcoded"*. The relay now prints
   `Relay blobs: S3 (…)` / `filesystem (…)` at startup, because a backend that does not say which
   branch it took cannot be verified — only inferred from an AWS SDK debug line.

   **S5 (test against a real S3) landed as an in-JVM S3, not MinIO.** ⚠⚠ And it surfaced the most
   counter-intuitive thing in this whole track: **a *more* faithful S3 moves away from the risk, not
   toward it.** The stores that break our client — Hetzner, R2, older MinIO — break by being *less*
   capable than S3, so the `aws-chunked` framing bug is invisible against real S3, against MinIO and
   against S3Mock alike. The dumb hand-written fake caught it precisely by being dumb: it stored the
   framing instead of decoding it. So the layers are complementary and none of them is the deploy —
   the fake proves payload fidelity, the real implementation proves our *usage* (SigV4, path-style,
   error mapping), and only the target bucket settles compatibility, which is why that is a
   pre-window runbook step rather than a test. MinIO-in-compose exists at
   `sync-relay-server/docker-compose.minio.yml` and the same contract points at it via
   `S3_TEST_ENDPOINT`, but CI cannot pull it: every container registry's blob CDN is unreachable.

2. **CDN for apex + the web bundle + tiles.** Cheap, high hit rate, no threat-model change (D5/D6).
3. **Postgres backend.** The actual multi-node unlock — when there is a reason, not before.
4. **Provider / region choice, last**, when there is a customer distribution to point at. By then
   it is config rather than a migration.

⚠ `S3BlobStore` alone does **not** enable multi-region: the single-writer constraint lives on the
SQL side. It buys reliability, cost and portability — not a second relay.

---

## 7. Sources

Point-in-time, 2026-09-01, via search summaries (see the status caveat above):
[Hetzner locations](https://docs.hetzner.com/cloud/general/locations/) ·
[Hetzner price adjustment 15 June 2026](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/) ·
[OVH region availability](https://www.ovhcloud.com/en/public-cloud/regions-availability/) ·
[OVH public cloud prices](https://www.ovhcloud.com/en/public-cloud/prices/) ·
[OVH pricing model change](https://blog.ovhcloud.com/en/posts/public-cloud-pricing-model-update-2026/) ·
[DO droplet pricing](https://www.digitalocean.com/pricing/droplets) ·
[DO Spaces pricing](https://docs.digitalocean.com/products/spaces/details/pricing/)

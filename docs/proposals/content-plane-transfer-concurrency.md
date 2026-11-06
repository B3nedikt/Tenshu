# Content-plane transfer concurrency — chunk uploads that aren't round-trip-bound

**Status:** **Phase 1 IMPLEMENTED 2026-08-12.** Bounded concurrent chunk transfer on all three
sequential upload loops (`SyncObserver.emitFileContentNow`, `SyncObserver.shareContentToRealm`,
`ShareLinkImplementation.uploadShare`). Phases 2–3 are infrastructure and **not started**; they
are recorded here so the next person does not re-derive the ordering. Arose from a maintainer
question about whether the Frankfurt DigitalOcean relay serves US/Asia users acceptably.

---

## 1. The problem

The relay is one box in one region. (Written 2026-08-12 against a DigitalOcean droplet in `fra1`;
since 2026-09-13 it is one Scaleway instance in `fr-par`, `deploy/scaleway-relay/`. The RTT figures
below were measured against `fra1`; the single-region shape, which is what the reasoning rests on, is
unchanged.) Droplets do nothing automatic about geography:
there is no anycast, no geo-routing, no read replication, and the reserved IP is itself
region-scoped. Typical RTTs to `fra1` are ~10–40 ms from Western Europe, ~90 ms from US-East,
~150 ms from US-West and ~170–280 ms from Asia/Oceania.

Raw RTT is not uniformly a problem. Sync deltas are small and batched, so a push/pull is a
couple of round trips and 250 ms is invisible to someone editing metadata. **File content was
the problem, and it was an architecture issue rather than a geography one.** Every content
upload loop was strictly serial — one 2 MiB `PUT`, awaited, then the next — which makes
throughput `RTT`-bound rather than bandwidth-bound:

- a 200 MB file is 100 sequential chunks, so at 250 ms RTT that is ~25 s of pure stall on top
  of transfer;
- a single TCP stream at that RTT cannot fill a fat pipe regardless of either endpoint's
  bandwidth (bandwidth-delay product).

A European maintainer barely notices at ~20 ms. The same code from Asia reads as broken. That
asymmetry is why this went unnoticed: the metric that matters is invisible where the code is
written.

## 2. Why the fix is a client change, not a region

**You cannot simply add a region.** Relay persistence is SQLite on one block volume — a single
writer. A second droplet splits the device registry and mailbox across volumes and corrupts
state; `sync-relay-persistence.md` §8 Phase 4 (Postgres + S3, designed, not built) is the
prerequisite. So the ordering is forced:

| Phase | Change | Touches infra? | Status |
|-------|--------|----------------|--------|
| 1 | Overlap chunk transfers on the client | no | **shipped** |
| 2 | Move blobs to Spaces + CDN | yes, additive | not started |
| 3 | Multi-region relay | needs Postgres first | blocked |

Phase 1 is both the cheapest and the largest single lever, and it helps every user rather than
only distant ones.

## 3. Phase 1 as built

`mapChunksBounded` + `DefaultChunkTransferConcurrency`
(`:core-common`, `org.tenshu.core.common.util.BoundedConcurrency`). Each of the three loops now
produces chunks sequentially and fans their transfers out through it.

### 3.1 Decisions

**D1 — Production stays sequential; only transfer fans out.** Producing a chunk (a positional
read, the `IncrementalSha256` fold, the offset advance) is order-dependent and cheap;
transferring it is order-*independent* and latency-dominated. Splitting there means the
order-sensitive work needs no synchronisation at all: the whole-file hash-chain is order- **and**
boundary-sensitive, and `offset` advances by the bytes actually read, so a parallel producer
would corrupt both. Callers get in-order side effects for free by putting them in the producer.

**D2 — The permit is acquired before producing, not before transferring.** This is the entire
memory bound. A chunk buffer is only allocated once a slot exists to carry it, so at most `N`
chunks are resident. Acquiring after production would let the producer race ahead and read the
whole file into heap while transfers drained — the exact unbounded buffering that
[ADR 0017](../adr/0017-file-content-two-plane-channel.md) exists to prevent.

**D3 — `N = 4`.** Chosen to sit *under* every platform's default per-host connection cap
(OkHttp `maxRequestsPerHost` = 5, Darwin `HTTPMaximumConnectionsPerHost` = 6) so the fan-out is
genuinely concurrent on the wire instead of silently queueing inside the HTTP client, and no
shared `HttpClient` needs reconfiguring. Raising `N` past those caps buys nothing until they are
raised too. Cost is ~16 MiB peak (`N` × plaintext + ciphertext).

**D4 — The residency guarantee moves from `O(CHUNK)` to `O(N × CHUNK)`, deliberately.** The
load-bearing property was never the constant: it was **independence from file size and from
backlog depth**, which is what fixed the Control OOM. A bounded permit count preserves both — a
200-chunk file still peaks at `N`. `chunkTransferConcurrency = 1` restores the original path
exactly, which is what makes it a usable kill-switch rather than an approximation.
`design.md` §2 and ADR 0017's consequences are amended accordingly.

**D5 — Failure throws rather than returns.** A `TResult.Failure` return can only end the *one*
transfer that saw it; siblings would keep pushing bytes for a manifest that is already doomed.
Throwing cancels them through the enclosing `coroutineScope` and stops production, preserving
the sequential loop's "first failure ends the upload" behaviour. `ChunkPutFailed` is
deliberately a plain `Exception` and not a `CancellationException` — the caller converts it back
to the `false` its contract promises, and a cancellation subtype would be swallowed as ordinary
cooperative cancellation.

### 3.2 What did not change

Wire format, ref derivation, AAD binding, manifest shape, relay behaviour, and the metered
content gate are all untouched. `index` passed to the transfer is the *production* index, so the
`(fileId, chunkIndex, chunkCount)` AAD binding is unaffected by completion order. **No
threat-model amendment is required** (House Rule #2): this changes only the client's scheduling
of transfers whose contents, addressing and authorisation are identical.

### 3.3 Coverage

`BoundedConcurrencyTest` (`:core-common`, 9 tests) pins the helper's contract: the residency
bound, result ordering under reordered completion, actual overlap (asserted on `runTest`'s
virtual clock — 6 × 100 ms at `N=3` must take 200 ms, so a regression to a sequential loop
fails), abort-on-failure, and `N=1` being exactly sequential.
`FileContentEmitConcurrencyTest` (`:sync-service-proxy`, 4 tests) pins the same three properties
through `SyncObserver` itself, including that the manifest's chunk refs stay in file order while
the fake content store completes transfers in reverse.

### 3.4 Deliberately left sequential

`SliceSnapshotWriter.writeAll` also loops `putChunk`. It runs at most once a day on the
idle+charging maintenance path, seals small best-effort slices, and is explicitly never
correctness-bearing — no user waits on it, so there is no latency to recover.

## 4. Phase 2 — blobs to Spaces + CDN (not started)

`RelayConfig.blobDir` already names the seam ("The future S3 backend swaps in here"). Moving
content blobs to a Spaces bucket fronted by `digitalocean_cdn` gets bulk bytes off the single
Frankfurt box entirely, leaving only small metadata operations RTT-bound. It does **not** touch
the single-writer constraint, so it is independent of the Postgres work. `deploy/tiles/main.tf`
is the working template — the tiles plane already serves ranged GETs from the edge this way.

Sequencing within the phase:

1. **The anonymous share path first** — `GET /share/{shareId}/chunk/{index}` (`ShareRouting.kt`)
   takes no JWT and serves opaque ciphertext, so it is trivially edge-cacheable with nothing to
   leak.
2. **Authenticated `/sync/content/*` second** — needs auth-aware caching or presigned URLs.

⚠ A TLS-terminating CDN in front of the *authenticated* routes sees request metadata and device
JWTs. That is a step away from the zero-trust posture and **would** need a threat-model
amendment (House Rule #2); the deploy how-to's "DDoS & abuse resistance" section already frames
the same decision. Step 1 dodges it entirely, which is the other reason to start there.

## 5. Phase 3 — multi-region relay (blocked)

Requires the Postgres + S3 backend (`sync-relay-persistence.md` §8 Phase 4). Not worth designing
before Phase 2 lands, since moving blobs to a CDN removes most of the geographic pressure that
would motivate it.

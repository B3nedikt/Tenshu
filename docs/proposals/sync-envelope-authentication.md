# Sync envelope authentication — tombstone MACs and SyncBlock envelope binding

**Status:** v1 **built** — decisions D-a…D-g taken (maintainer, 2026-10-27); as-built notes in §8,
measurements in §7. **Part of the release baseline** — relay protocol 1, sync service v1 (folded into
the release-cut reset, §5); first exercised by the reset's fresh-volume redeploy.
Closes finding **Fx** (`relay-metadata-minimisation.md` §5, D4, **D13 item 6**). Threat model:
T-T-02, T-T-03, T-K-05 (first amended to say tombstones were unauthenticated; residuals lowered here).

## 1. Problem

Two envelope gaps let the relay — or anything between a device and the relay, since there is no
certificate pinning — change what devices apply:

1. **`DeleteBlock` is unauthenticated.** No ciphertext, signature or MAC. A forged tombstone with an
   HLC above the entity's last write deletes the row on every device; content reclaim can then free
   the bytes, and the relay is usually the only durable copy. **High.**
2. **A `SyncBlock`'s envelope is outside its AEAD.** `blockId`, `hlc`, `targetEntityIdHash` and
   `ownerId` are clear, and the merge orders by `block.hlc`. A relay can re-stamp or replay a
   genuine ciphertext and roll an entity back to any value it ever held. **Medium.**

Out of scope by design: availability (a relay can still drop or withhold, T-T-04) and an admitted
key holder authoring anything (T-K-01 / T-K-05, bounded by revocation).

## 2. Code facts that shaped the design (master `108785b84`)

| Fact | Consequence |
|---|---|
| `SyncCryptoManager.encryptWithAad/decryptWithAad` already exist: the AAD is length-prefix-framed inside the Poly1305-authenticated plaintext; no backend is touched (TA3 / T-RC-02 frozen) | No new AEAD backend surface is needed (D-f) |
| Tombstones are stamped with the **personal** `ownerId` and drained to the personal relay (`SyncObserver.enqueueTombstone`); `pullSharedRealms` ingests **blocks only** | Tombstones are tagged and verified under the personal key, which never rotates. Retired keys concern shared-realm `SyncBlock`s only |
| The tombstone's wire form is produced at **drain** (`TombstoneWireType.forWire`, EI-4); the local copy keeps the concrete type | The tag is computed at drain, over the wire form — this also tags anything queued before the upgrade |
| `SyncProcessor.decryptAndParse` called `observe(block.hlc)` **before** decrypting | Moved after a successful open: an unverifiable envelope moves neither clock nor last-seen |
| Relay stores the envelope verbatim (`codec.serialize` → `delete_block.envelope`) | A new optional field rides with no relay schema change |
| Body-dropped blocks (`EnvelopeOnlySyncBlock`) reach clients with an empty payload | They cannot be verified, so they no longer advance the clock. Intended: nothing else was read from them |

## 3. Design

### 3.1 Tombstone MAC (D-g)

```
K_tomb  = HKDF-SHA256(ikm = sharedIdentityKey, salt = ∅, info = "tenshu/sync/tombstone-auth/v1", L = 32)
authTag = HMAC-SHA256(K_tomb, tombstoneCanonical(wireBlock))
```

- `authTag` is a new **optional** `byteArray` field on `DeleteBlock`: absent on the local and queued
  copies, present on every wire copy. **Ingest treats absence as invalid.**
- A MAC, not an AEAD over an empty plaintext: deterministic, no nonce, 32 bytes. `hmacSha256` and
  `hkdfSha256` are pure Kotlin over the `sha256` expect, so every platform computes the same bytes
  by construction (pinned by vectors anyway).
- A **child key**, so a tag can never be confused with any other use of `sharedIdentityKey` (B3's
  property-tag key is the precedent). `K_tomb` is cached per identity key — never re-derived per
  row (the HKDF-per-row slowdown documented on `SyncProcessor.tombstoneRegistry`).
- Compared in constant time.
- Every relay-read field (`targetEntityType == Device.ID`, `revokedDeviceId`, `hlc`) stays **clear**
  (`relay-metadata-minimisation.md` §6). Binding them stops the relay *altering* them; it still reads them.

### 3.2 SyncBlock envelope binding (D-f, option A)

```
payload = encryptWithAad(BlockPayloadEnvelope, sharedIdentityKey, aad = syncBlockAad(envelope))
syncBlockAad = SHA-256(syncBlockCanonical(ownerId, blockId, hlc, targetEntityIdHash?))
```

- Uses the existing framed construction; costs **+36 bytes per block** (4-byte length + 32-byte
  digest), under 1 % of the ~5 KB mean envelope. No field is added, but the ciphertext semantics
  change, so it is still a cutover.
- Option B (true AEAD AAD on BouncyCastle, @noble/ciphers and libsodium) saves those 36 bytes at the
  cost of touching three frozen backends; rejected for v1. Option C (a second MAC field) was a
  reshape plus a second primitive.
- Content-plane manifest blobs are **not** `SyncBlock`s and stay unbound by an envelope (they are
  addressed by `contentId` and their CEK wrap is AAD-bound to it).

### 3.3 Canonical encodings

Fixed order, every variable field length-prefixed (4-byte big-endian), optionals with a presence
byte — never the composite serialisation, which can change shape (the `HlcTipCanonicalMessage`
precedent, but length-prefixed so field boundaries cannot shift):

```
syncBlockCanonical  = lp("tenshu/sync/block-envelope/v1") ‖ lp(ownerId) ‖ lp(blockId)
                      ‖ be64(hlc.physicalTime) ‖ be64(hlc.logicalCounter) ‖ lp(hlc.deviceId)
                      ‖ opt(targetEntityIdHash)
tombstoneCanonical  = lp("tenshu/sync/tombstone/v1") ‖ lp(ownerId) ‖ lp(blockId)
                      ‖ be64(hlc.physicalTime) ‖ be64(hlc.logicalCounter) ‖ lp(hlc.deviceId)
                      ‖ lp(targetEntityIdHash) ‖ lp(targetEntityType /* wire */) ‖ opt(revokedDeviceId)
opt(x)              = 0x00 | 0x01 ‖ lp(x)
```

The two labels give domain separation between message types; `/v1` lets F9 (D-a) move the
encoding without ambiguity.

### 3.4 Ingest

- `processDeleteBlock`: verification is the **first** statement — before `observe(hlc)` and before
  `tombstoneRegistry.record`. A forged Device tombstone therefore cannot move the client realm epoch,
  and a forged HLC cannot move the clock.
- `decryptAndParse(block)`: open with the bound AAD; only a successful open calls `observe`.
- Failure (D-d): drop, never apply; a rate-limited log line and a process-wide counter
  (`EnvelopeRejections`, `:sync-service-proxy`) the diagnostics/health surface can read. No user
  banner in v1. ⚠ Not yet surfaced in `SyncHealth` — doing so reshapes a persisted composite and is
  left to the follow-up that decides the UX.
- ⚠ A body-dropped block (`EnvelopeOnlySyncBlock`, empty payload) is skipped without being counted
  and no longer advances the clock or the roster's last-seen.

### 3.5 Retired keys (D-c) — shared-realm SyncBlocks only

A block that opens only under a **retired** `Kr` is accepted iff
`hlc.physicalTime < retiredAt + 5 min` (skew allowance). `RetainedRealmKey.retiredAt` existed but
`RealmKeyring` dropped it; it is now carried into `RealmContext` beside each historical key.
Residual: a removed member can still author a *backdated* block, which loses LWW to every later
write. Recorded against T-GR / A26.

⚠⚠ **Every** clock the payload merges at must pass the cutoff, not only the envelope's: a
`DeltaBatchPayload` applies each delta at its own inner HLC, so a backdated container could otherwise
carry post-retirement deltas (`retiredKeyDeltaBatch_*` tests).

### 3.6 No key-id hint (D-e)

Tombstones verify under exactly one key, so a forgery flood costs one HMAC each. Blocks already
trial-decrypt across retired keys and Poly1305 rejects fast. The envelope therefore carries no key
generation hint (nothing new for A6). Revisit if the retired-key count per realm grows large.

## 4. Decisions

| # | Question | Decision |
|---|---|---|
| D-a | Bundle F9 (`hlc.deviceId` inside the ciphertext)? | **No.** Own PR; the HLC tie-break and tombstones' lack of a ciphertext make it a separate design. Another pre-release reset is cheap and the encodings are versioned |
| D-b | HLC drift bound? | **Separate, earlier client-only PR** (clamp > 10 min ahead + log). Not in this PR |
| D-c | Retired-key rule | Accept only if `hlc.physicalTime < retiredAt + 5 min` (§3.5) |
| D-d | On failure | Drop + diagnostic + counter; no banner in v1 |
| D-e | Key-id hint | **No** (§3.6) |
| D-f | SyncBlock binding | Framed `encryptWithAad`, AAD = SHA-256(canonical envelope) (§3.2) |
| D-g | Tombstone tag | HMAC-SHA256 under an HKDF child key (§3.1) |

## 5. Rollout

- **Folded into the release baseline** (maintainer, 2026-10-27). The release-cut reset restarted every
  version at 1 and requires a fresh relay volume and cleared devices anyway; this change merged
  after it and before anything ran the baseline, so it rides that one redeploy: **no**
  `RELAY_PROTOCOL_VERSION` bump, **no** sync-service bump, **no** migration step.
  `SchemaShapeBaseline` pins `DeleteBlock` with `authTag` at v1.
  ⚠⚠ The consequence to respect: a build from between the reset commit and this one also speaks
  protocol 1 but sends untagged tombstones and unbound blocks, which every peer drops. Never run one
  against the post-reset relay (the same rule `RelayProtocol.kt` already states for pre-reset builds).
- The relay rejects (`400`) a tombstone push whose `authTag` is missing or not 32 bytes — a format
  check, not security; it cannot verify (HR 1).
- ⚠ **Post-release, a reshape of `DeleteBlock` is not this easy.** It is embedded in the
  `PushTombstone` operation, so `SchemaMigrationRegistry.verify` rejects a per-type migration of it
  (nested reshape). An explored escape hatch for a trailing optional: `BinaryParser` reads an
  optional field starting at end-of-buffer as absent, so a stored queued row written without it
  decodes as "absent" — verified on the pre-reset branch, but not pinned by a test in the baseline.
  Whoever reshapes it next must re-prove that and bump with an empty step, or build the overlay.

## 6. Residuals (recorded, not fixed)

- The `CascadedTombstonePush` holder list is relay-only and unauthenticated — availability only,
  since the relay can delete content anyway.
- Shared-realm tombstones still travel on the personal stream (pre-existing gap, unchanged).
- An admitted device can still author anything (T-K-01); envelope authentication proves *realm-key
  holder*, not *which device*.

## 7. Performance

Measured 2026-10-27 on the maintainer's machine (Apple Silicon, 14 cores). Tests:
`EnvelopeAuthBenchmarkTest` (`:sync-service-common`, JVM/iOS) and `EnvelopeAuthJsTest`
(`:tenshu-sdk-js`, ChromeHeadless). They print and assert correctness only — no timing threshold.

### 7.1 Measurements

| Measurement | JVM | JS (ChromeHeadless 156) | iOS (simulator, debug K/N) |
|---|---|---|---|
| Verify 10 000 tombstone tags (a fresh device's backlog) | **57 ms** (5.7 µs each) | **314 ms** (31 µs each) | **445 ms** (44 µs each) |
| Forged 5 KB block, current key only | 137 µs¹ | 98 µs | 60 µs |
| Forged 5 KB block, + 4 retired keys | 581 µs | 220 µs | 307 µs |
| Forged 5 KB block, + 16 retired keys | 708 µs | 658 µs | 1.0 ms |

¹ includes JIT warm-up of the first loop. iOS numbers are a debug Kotlin/Native test binary on the
simulator — an upper bound; the pure-Kotlin HMAC is the cost there, not libsodium.

Reading:
- **Bulk tombstone ingest is a non-issue**, including in the browser: a third of a second for
  10 000, spread over the pages it arrives in (500 rows per page). No off-main-thread batching needed.
  The HMAC and SHA-256 are pure Kotlin over the `sha256` expect, so this is the slow path already.
- **Forged tombstones cost one HMAC each** (no keyring is ever tried, §3.6) — the flood D-e worried
  about is bounded by the tombstone row above.
- **Forged blocks scale with the retired-key count**, as expected for trial-open: ~40 µs per extra
  key. At 16 retired keys a forged 5 KB block costs < 1 ms. That supports D-e (no key-id hint) at
  realistic keyring sizes; revisit if a realm ever accumulates hundreds of rotations.
- Genuine blocks: no extra AEAD call; +36 bytes of framed digest inside the ciphertext.

## 8. As built

| Piece | Where |
|---|---|
| Canonical encodings, tag, seal/open | `sync-service-common/.../EnvelopeAuth.kt`; vectors `EnvelopeAuthTest` (cross-checked against an independent Python implementation), JS parity `EnvelopeAuthJsTest` |
| `DeleteBlock.authTag` | `sync-service/.../DeleteBlock.kt`; part of the v1 baseline (no step) |
| Drain-time tag over the wire form | `TombstoneWireType.forWire(context, block, identityKey)` = `narrowed` then `tagTombstone`; key by `ownerId` in `SyncImplementationFactory` |
| Bound emit | `SyncObserver` (`sealBatch`, `generateSyncBlock`, legacy manifest block), `PendingBlockRestamper` reseal (iOS `RelayBlockProbe` / `RelayFolderProbe` were deleted 2026-11-05) |
| Verify-before-observe ingest | `SyncProcessor.processDeleteBlock` / `openBoundPayload`; `EnvelopeRejections` |
| D-c | `RealmContext.retiredKeys: List<RetiredRealmKeys>` (retirement time carried from `RetainedRealmKey.retiredAt` by `buildRealmKeyring`); `RETIRED_KEY_SKEW_MS` |
| Relay format check | `SyncRouting` push-tombstone (400 without a 32-byte tag); `RelayProtocol.kt` v1 history entry |
| Proof | `SyncProcessorEnvelopeAuthTest`, `RetainedKeyringTrialDecryptTest` (D-c), `TombstoneWireTypeTest`, `ForgedTombstoneSystemTest` (relay-forged tombstones through two paired devices). The processor and system tests were mutation-checked: with verification disabled they go red. |

⚠ `:sync-service-common`'s commonTest does **not** run on JS (its JS test compilation is disabled
because `:core-test-utils` has no JS target); the JS lane's vector coverage is `EnvelopeAuthJsTest`.
⚠ Nor is it part of `iosTestCheck`. The iOS vectors were run directly:
`./gradlew :sync-service-common:iosSimulatorArm64Test --tests '*EnvelopeAuth*'` (green, 2026-10-27).

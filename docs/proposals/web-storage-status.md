# Storage status on the web — apex `StorageService` wiring

**Status:** **All three buckets shipped 2026-06-25** (Buckets 1 and 2;
Bucket 3 in flight on `storage-web-bucket3`). This is **apex / control-plane work**, parallel
to the entitlement-sync + commerce-service tracks — **not** a `drive-web-app` (Drive-UI) task.
Originally parked as "B2c" in [`drive-web-app.md`](drive-web-app.md) §4; re-homed here because
it is not Drive-UI.

**As-built (Buckets 1+2):**
- **Bucket 1**: `StoragePlugin : JsRuntimePlugin` produces `StorageStatus` in the apex
  `JsRuntime`; `Storage.status()` @JsExport op + `@tenshu/sdk/storage` boundary + apex
  `storage.status` handler + drive `storageStatus()` proxy + read-only Settings Storage section.
- **Bucket 2**: `EntitlementProducts` (the generic `productId → quota` half), `entitlementToFigures`,
  `EntitlementStorageProvider`, and the **reader half** of `initEntitlementStorage`
  (`initEntitlementStorageReader`) relocated out of `:control-shared` into **`:storage-service-proxy`**
  (downward `api` dep on `:identity-service` for `Entitlement` — no cycle). The commerce-side
  `plan → productId` mapping (`productIdForPlan`) + the writer (mint on subscribe) stay in
  `:control-shared`. The apex `StoragePlugin` now wires `EntitlementStorageProvider` +
  `initEntitlementStorageReader`, so a web device that joins a funded realm reflects the synced
  `Entitlement` with no further work.
- **Bucket 3** (lean default-free auto-mint — maintainer call 2026-06-25, vs. porting Control's
  full `MockPublisherClient` account UX the web has no surface for): the `EntitlementMintClient`
  interface + in-process `DevSeedEntitlementMintClient` + the writer (`mintEntitlement` /
  `initEntitlementWriter`, decoupled from `PublisherAccount` via `EntitlementMintIntent`) relocated
  to `:storage-service-proxy`. Control's `initEntitlementStorage` now delegates the writer (mapping
  its `PublisherAccount` flow → intent); the off-device `CommerceEntitlementMintClient` stays in
  `:control-shared`. The apex `Realm.create` mints a default-FREE entitlement on genesis with a
  dev-key minter (`syncCryptoManager` + `EntitlementSignature.DEV_SEED`) — so a web-originated realm
  gets the Free tier's 5 GiB (`EntitlementProducts.FREE_LIMIT_BYTES`) exactly like native's "free on
  signup", and the Syncable `Entitlement` reaches the owner's other devices.
  (Corrected 2026-07-18 per `retention-quota-model.md` D9: the canonical Free limit is **5 GiB**, not
  the "1 GiB" this note originally said — the mint has always derived the real limit from `productId`;
  only this prose was stale.)
- **Outstanding:** upload/share *gating* consumption (now safe to wire — figures are real for both
  joiners and web-genesis; a focused follow-up); the off-device commerce minter on the apex (the web
  uses the dev-key path in v1, same posture as Control under `TENSHU_BILLING_MOCK`). No threat-model
  surface change (the apex runs the same proxy code).

---

## 1. What this is

`StorageStatus` = `{ hasStorage, usedBytes, limitBytes }` — the Tier-1 storage signal the
upload / share entry points gate on ([`storage-capacity-gating.md`](storage-capacity-gating.md)).
On the web it is currently **absent**: `client.storageService.status()` returns `null`
because nothing in the apex's in-process runtime (`JsRuntime`) *produces* the `StorageStatus`
row.

## 2. Two halves — consumption vs production

- **Consumption is identical to file-service.** `client.storageService.status()` runs in the
  apex `JsRuntime` (the in-process proxy) and is surfaced to the `drive.tenshu.app` subdomain
  over the postMessage bridge exactly like `FileSystem.list`. The Drive-side boundary — a
  `Storage.status()` op + a `storage.status` gateway handler + a `storageStatus()` proxy
  wrapper + UI gating — is the *trivial* part and is the only piece that touches Drive-web.
- **Production is the real work, and it is apex-side.** Unlike files (whose data is the
  user's actual files in the local DB), `StorageStatus` must be **derived and written** by a
  `StorageProvider` running in the apex runtime. Today nothing runs it, so the `statuses`
  relation is empty and `status()` is `null` even if the schema were registered.

## 3. Key finding — the entitlement substrate is already web-reachable

`Entitlement` lives in **`:identity-service`** (registered in `IdentityServiceCommonModule`),
and `:tenshu-sdk-js` **already depends on identity-service**. So a synced `Entitlement` record
**already arrives and deserializes in the apex runtime today**. The acquisition path is the
same as every other client — the publisher mints a signed `Entitlement` which syncs. There is
no "web can't get an entitlement" product gate; this is **wiring**, not a product decision.

## 4. The substrate that already exists (just unwired on web)

- `StorageProvider` SPI — `:storage-service-proxy/.../StorageProvider.kt`:
  `fun observe(): Flow<StorageFigures>`; `StorageFigures = { hasStorage, usedBytes, limitBytes }`.
- `ExecutionContext.initStorageService(scope, provider, holder)` —
  `:storage-service-proxy/.../StorageProxyInitialization.kt`: collects the provider's flow
  and **upserts the single-row `StorageStatus`** that `status()` reads.
- `StorageCapacityHolder` + `ensureWithinQuota` / `ensureCapacity` —
  `:storage-service-proxy/.../StorageCapacityHolder.kt`: Tier-2 enforcement latch (already
  consumed by upload/share ops).
- `EntitlementStorageProvider` + `entitlementToFigures` + `EntitlementProducts` +
  `initEntitlementStorage` — **currently `:control-shared`/.../data/**: the real provider —
  derives figures from the synced `Entitlement` (reader) and mints via the mock publisher when
  an account binds (writer).
- The `JsRuntimePlugin` system — `tenshu-sdk-js/.../internal/FirstPartyPlugins.kt`: the
  extension point. A storage plugin slots in identically to `FilesPlugin` / `SyncPlugin`.

## 5. The three buckets

### Bucket 1 — Storage plugin (small): register the service in the apex runtime
- `tenshu-sdk-js/build.gradle.kts`: add `:storage-service`, `-common`, `-client`, `-proxy` deps.
- `FirstPartyPlugins.kt`: a `StoragePlugin : JsRuntimePlugin` —
  `schemaConfigs = [storageServiceCommonModule, storageServiceClientModule]`;
  `operationFactory = { id -> storageServiceProxyImplementationFactory(id, ctx.storageCapacityHolder) }`;
  `initialize { initStorageService(serviceScope, provider, holder) }`. Register it in
  `JsRuntimeBuilder.orderedPlugins`.
- `RuntimeContext` (`JsRuntimePlugin.kt`) + `JsRuntime`: add a
  `storageCapacityHolder: StorageCapacityHolder` member (instantiated in `JsRuntime`, like
  `fileDatabase`) so the plugin's factory + `initialize` can reach it.
- **Drive boundary** (the deferred-from-B2 trivial part): `Storage.status()` Kotlin op
  (`client.storageService.status().first()` → `{ hasStorage, usedBytes, limitBytes }` via the
  `js("{}")` idiom); `native.d.ts` typing; `storage.status` apex gateway handler; drive
  `storageStatus()` proxy; consume in upload/share gating + the Settings view.

### Bucket 2 — Provider relocation (structural): feed it from the synced entitlement
- Relocate `EntitlementStorageProvider`, `entitlementToFigures`, `EntitlementProducts`, and the
  **reader half** of `initEntitlementStorage` (the `DataObserver` that recomputes figures on
  every `Entitlement` save/delete) **out of `:control-shared`** into a shared lower module both
  Control and the SDK depend on. Candidate home: **`:storage-service-proxy`** (it owns
  `StorageProvider`; a downward dep on `:identity-service` for `Entitlement` is clean). Split
  off the `PublisherAccount.Plan` → product mapping if it must stay commerce-side; the
  `productId` → quota part is generic.
- **Outcome:** a web device that **joins a funded realm** gets a correct status with no further
  work — the `Entitlement` syncs in, the relocated provider reads it. (Buckets 1+2 are
  non-regressing: status only ever *upgrades* from the `hasStorage:false` default when an
  entitlement is present.)

### Bucket 3 — Mint / account path on the apex (medium): web-*originated* realms
- Port Control's `ProxyModule` storage block into the apex DI: bind `MockPublisherClient` (the
  account source), `EntitlementStorageProvider` (writer), and call `initEntitlementStorage(...)`
  — the same wiring Control's `PlatformProxyModule.platformProxyInitialization` runs. Needs a
  JS `EntitlementMintClient` (mirror `desktopEntitlementMintClient`).
- **Outcome:** a web realm created from scratch (web onboarding genesis, Phase 6) mints + syncs
  its entitlement exactly like Android/desktop — "the mock backend works the same on web."

## 6. Sequencing

The maintainer chose **all three**. Recommended implementation order: **1 → 2 → 3**, one PR
each. 1+2 unblock joiner devices immediately and never regress UX; 3 completes web-genesis.

## 7. Open sub-questions (resolve during implementation)

- Exact relocation target for the provider (`:storage-service-proxy` vs a small new module) —
  decide from the dep graph; **never** introduce a `storage → control` dependency.
- Where the web account/plan originates: does the apex onboarding seed a default plan (e.g.
  free), and is there a web subscription UX, or is the mock account defaulted like native?
  Mirror whatever Control does (`control-subscription-ux.md`, `MockPublisherClient`).
- Whether the apex must also register any cross-device-entitlement-sync gate
  (`joinedFundedRealm`) — check those slices ([memory `project_storage_entitlement_sync`]).

## 8. Threat model

Mirrors native — the web apex runs the same proxy code. The relay enforces via the bearer
entitlement token; the synced `Entitlement` is signed (offline-verifiable via
`EntitlementSignature.publicKeyFor`). No new model surface expected; confirm the A-asset
coverage for the entitlement (per `commerce-billing-plane.md` / `publishing-commerce-reconciliation.md`)
still holds when the mint path lands on web (House Rule #2).

---

## 9. Implementation Notes for Claude Code (start here next session)

1. **Read first:** this doc, then
   `storage-service-proxy/.../{StorageProvider,StorageProxyInitialization,StorageCapacityHolder}.kt`,
   `control-shared/.../data/EntitlementStorageProvider.kt` + `EntitlementStorageInitialization.kt`,
   `control-shared/.../di/ProxyModule.kt` (the storage block, ~lines 78–101 + 252–260) and
   `control-shared/.../di/PlatformProxyModule.kt` (`platformProxyInitialization`, the
   `initStorageService` / `initEntitlementStorage` calls), and
   `tenshu-sdk-js/.../internal/{JsRuntime,JsRuntimePlugin,FirstPartyPlugins}.kt`.
2. **Start with Bucket 1** in an isolated worktree. The plugin compiles green once schema +
   factory + init are wired. Verify with a system test that a hand-seeded `Entitlement` →
   non-null `StorageStatus` read over the bridge.
3. **Web workflow (from the drive-web-app session):** isolated worktree per slice; rebuild
   `:tenshu-sdk-js:jsBrowserDistribution` + `pnpm install` in the worktree; gates are
   `pnpm -F drive build`/`test` + `pnpm -F apex build`/`test` (+ a Kotlin compile for the op);
   push **by commit SHA** to a **non-`feat/`** remote ref, open the PR, **merge-verify by file
   content** (not the MERGED flag). The primary checkout is shared with a concurrent agent —
   see memory `feedback_verify_branch_base_before_pr`.
4. **Bundle hygiene:** the `web/drive` subdomain imports **only types** from `@tenshu/sdk`
   (a value import pulls the whole Kotlin runtime — 230 KB → 1.7 MB). Only `@tenshu/sdk/bridge`
   is value-imported. See `drive-web-app.md` §7.
5. The Drive-side consumption (`storageStatus()` proxy + UI gating in upload/share + Settings)
   is ~1 hour once the apex produces the status — do it **last**.

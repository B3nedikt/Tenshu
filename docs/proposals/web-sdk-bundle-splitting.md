# Web SDK Bundle Splitting & Composition

**Status:** Draft v0.1.
**Scope:** `:tenshu-sdk-js` Gradle module, `web/sdk/` pnpm workspace, `web/apex/` first-paint
bundle topology, and the `JsRuntime` composition surface in
[`tenshu-sdk-js/jsMain/.../internal/JsRuntime.kt`](../../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/internal/JsRuntime.kt).
Closes out [`docs/tech-debt.md`](../tech-debt.md) §"Kotlin-side Bundle Splitting" and amends
[`docs/web/architecture.md`](../web/architecture.md) §12.11. **Prerequisite:**
[`core-module-composition.md`](core-module-composition.md) — clean up `ModuleConfiguration`
and `include()` semantics first so the plugin model has a solid foundation. **Out of scope:**
Dukat output quality (separate tech-debt entry), iOS SDK shape (deferred until iOS app uses
the SDK), and the third-party-service publishing protocol itself — that's sized as a Phase 4
follow-up under a separate proposal once Phases 1–3 land.

This proposal extends the half-finished bundle-splitting work documented in tech-debt to its
real conclusion. The current state — `useEsModules()` + per-service TS exports map — gives
the *contract* (`@tenshu/sdk/{core,identity,notifications,files,onboarding}`) without the
underlying bundle separation. The previous attempt at per-module ESM consumption was reverted
because Rolldown lacks Kotlin-aware optimizations. This proposal commits to an
architecturally larger but substantively-different approach: decompose `JsRuntime` into a
plugin-composed shape, then split the Gradle module along the same plugin seams, then carve
apex's first paint to match.

**Motivating constraint.** The current architecture assumes a fixed first-party set of four
services. Tenshu's milestone roadmap projects many more first-party services (places,
photos, calendar, …) and a third-party-service publishing model where apps installed via
TenshuStore appear in the web Tenshu surface. Both break against the current monolithic
`JsRuntime`: each added service grows the apex first-paint bundle linearly, and third-party
services have no surface to register themselves at all.

---

## Phase 2 revision (2026-05-24) — spike result: §5.2 externals is a NO-GO; pivot to Path A

> **Read this before §5 and §8 Phase 2.** A throwaway 2-module spike disproved the
> per-service-webpack-bundle + ESM-externals mechanism this proposal's §5.2 / §8 Phase 2
> are built on. Those sections are **superseded** by the Path A plan below; they're kept
> for design history.

**What was spiked.** Two additive modules — `:tenshu-sdk-js-core` (`@JsExport object SdkCore`
exposing a value + a BIP-39-backed function) and `:tenshu-sdk-js-notifications` (calling into
`SdkCore`) — each its own `js(IR)` ESM webpack bundle, with notifications externalizing core
per §5.2.

**Result — the slicing works, the *link* doesn't:**

- With externals on, the notifications bundle dropped **34 KB → 10.7 KB** and the BIP-39
  wordlist (`abandon`/`zoo`) left it and stayed in core. So webpack externals *do* remove
  duplicated core code — the dedup half is real.
- But notifications emits `import { SdkCore_instance1zymdmy8ubriw … } from "./tenshu-sdk-js-core.js"`
  (Kotlin's **internal mangled** symbol), while the core bundle exports only
  `export{lt as SdkCore}` (the **minified `@JsExport`** name). Kotlin/JS inter-module
  linkage does not go through `@JsExport`; a separate webpack "library" bundle never exports
  the internal symbols a sibling bundle imports. The externalized import resolves to
  `undefined` at load.

This is risk §9.1 (cross-Webpack symbol drift) **confirmed as a hard blocker**, not a
tail risk. Making it work would require core to re-export its full internal symbol surface
with DCE/minification disabled — fragile (names drift every Kotlin upgrade), un-tree-shaken,
and impractical to enumerate across stdlib/coroutines/compose. Rejected.

**Decision: Path A — per-module ESM + route-level lazy loading.** Stop trying to link two
separately-built webpack bundles. Instead ship Kotlin's per-module `.mjs` (the
`compileSync` output) and let the apex's own bundler (Vite/Rolldown) bundle, tree-shake, and
code-split. One bundler sees every `.mjs` and links internally, so the symbol-mismatch wall
never appears. This revives the approach in §3.2 (prototyped + reverted earlier) — its only
recorded downside was a ~20 KB regression *on full import*, which never occurs once first
paint is core-only and routes lazy-load (the point of Phase 3).

**Weight-distribution caveat (reframes the goal).** The earlier §3.2 measurement showed
core-only was only ~19 KB smaller than importing all four services — i.e. **core + the
in-process runtime + crypto + coroutines dominate the weight; per-service feature code is
small by comparison.** So per-service slicing alone barely moves first paint. The real
first-paint lever is keeping the **runtime behind a dynamic import** so the first-paint path
(`Session.detect` → active/locked/firstTime, IDB-only) doesn't transitively construct
`JsRuntime`/`DefaultSyncCryptoManager`. Per-module ESM is the *enabler* (lets Vite DCE the
runtime); the win is realized by that restructure + Phase 3.

**Revised phase plan (supersedes §8 Phase 2):**

- **2a — enabling plumbing.** Point the `web/sdk` TS seam at the per-module ESM output
  instead of the single webpack bundle; import named symbols directly (no `import *`, which
  defeats tree-shaking). Add the `@noble/*` Vite `resolve.alias` + apex `package.json` dep
  (the §3.2 bare-specifier wrinkle). Verify the apex builds and per-service imports tree-shake
  (notifications-only < files-only). No behaviour change.
- **2b — measure.** Record real current numbers (full import, per-service, and the
  `Session.detect` first-paint path) — the runtime has grown a lot since the §3.2 numbers.
  These numbers decide how much of the first-paint restructure is needed.
- **2c — runtime behind a dynamic import.** Restructure so first paint doesn't pull
  `JsRuntime`. This is where the `Session.detect` carve-out (§4.3 / §9 risk #2) and the
  "lazy-load JsRuntime" tech-debt option converge. Likely overlaps Phase 3; sequence at 2b.

The plugin seam from Phase 1 (`JsRuntimeBuilder` + `JsRuntimePlugin`) still pays off here:
lazy registration on dynamic import is exactly how 2c keeps the runtime out of first paint.

---

## 1. The current state

The `:tenshu-sdk-js` module emits one Webpack bundle (`tenshu-sdk-js.js`, ~505 KB minified
after `useEsModules()`). Inside the bundle:

- `JsRuntime` is the in-process runtime that the apex's operation surfaces (`FileSystem.list`,
  `Notifications.observe`, …) dispatch through. It hard-codes per-service wiring at four
  sites:
    1. `schemaRegistry`'s `extraConfigs`: lists all four `*ServiceCommonModule` +
       `*ServiceClientModule`.
    2. `OperationImplementationFactory`: chains all four `*ProxyImplementationFactory` arms
       via elvis.
    3. `clientRegistry.initialization`: calls `initFileService()`, `initIdentityService()`,
       `initSyncService(...)`.
    4. `mergerRegistry`: registers `FolderMerger()` (file-service-specific) at construction.

- The per-service TS files (`web/sdk/src/files.ts` etc.) all import from
  `web/sdk/src/_bundle.ts`, which re-exports a single Webpack output. Vite's tree-shaker
  can't slice through it because the bundle's IIFE has cross-namespace side effects (per
  architecture §12.11).

- Apex (`web/apex/`) imports the per-service TS paths and ends up with the entire Kotlin
  bundle in its first-paint payload, regardless of which surfaces it actually uses.

**Apex bundle today: ~505 KB Kotlin + apex JS = ~555 KB at first paint.**

The TS contract at `@tenshu/sdk/{core,identity,notifications,files,onboarding}` is API-stable
and consumers should not need to change import paths.

---

## 2. Goals

1. **Per-service bundle separation at the Kotlin level.** A consumer that imports only
   `@tenshu/sdk/notifications` should not pay for `:file-service-proxy`,
   `:identity-service-proxy`, the FolderMerger, or the IndexedDB file-store backend.

2. **Apex first-paint bundle below ~200 KB.** First paint is `Session.detect()` →
   "active / locked / firstTime"; that legitimately needs only core + an IndexedDB session
   lookup. Today first paint loads the union; route-level dynamic imports defer everything
   else.

3. **Composable runtime with stable third-party surface.** Adding a service — first-party or
   third-party — is a registration step on a published `JsRuntimePlugin` interface. No core
   changes. Mirrors Koin's module-composition model on JVM/Android.

4. **Verifiable third-party loading.** When apex dynamically imports a TenshuStore-distributed
   service bundle, it must verify the bundle's integrity before evaluating. The existing
   zero-trust posture (threat model §"Trust Roots") extends to executable code, not just
   data.

5. **No regression on first-party apex behaviour.** The four shipped services keep their
   current behaviour, contracts, and on-disk shapes. The migration is internal.

**Non-goals (deferred):**

- Reducing the *cumulative* code size on apex if every route is visited. The lever is at-
  first-paint and on-route-entry, not the union.
- Service-to-service operation isolation (a notification op can still dispatch a file op).
  Capability sandboxing happens at the iframe / postMessage boundary, not inside apex's
  runtime.
- Hot-unloading services. Once registered, plugins stay registered for the page lifetime.

---

## 3. Why naïve approaches don't get there

Three approaches were considered and rejected. Each captures a real prior or proposed shape
of the work.

### 3.1 Webpack `splitChunks` only

`splitChunks` operates within a single Webpack invocation. The current `:tenshu-sdk-js`
builds one Webpack output; `splitChunks` could carve vendor code into a side chunk *within*
that output, but the consumer (Vite) sees the whole result either way. Doesn't change
apex's first-paint behaviour.

### 3.2 Per-module ESM consumption (the prior attempt)

Pointing `_bundle.ts` at the per-module Kotlin ESM output
(`tenshu-sdk-js/build/compileSync/.../*.mjs`) lets Vite/Rolldown bundle and tree-shake.
Tested on this branch and reverted; findings in tech-debt §"Kotlin-side Bundle Splitting":

- ~19 KB savings on a core-only import; ~20 KB *regression* on full-import vs. the current
  Webpack output, because Rolldown lacks `kotlin-web-helpers`'s Kotlin-aware optimizations.
- npm-dep references in the Kotlin output (`@noble/hashes`) needed Vite alias + duplicated
  `package.json` deps. Net negative on operational complexity.

This path isn't viable. The Webpack-Kotlin pairing has to stay.

### 3.3 Multiple Webpack outputs without externals

Splitting `:tenshu-sdk-js` into per-service Gradle modules, each with its own Webpack
invocation, *would* produce per-service Webpack outputs. But each invocation is independent:
shared dependencies (`:core`, `:core-common`, kotlinx-coroutines, ktor) duplicate across all
four bundles. Total cumulative download grows by ~3× core-shared-code size. `splitChunks`
doesn't span Webpack invocations.

Real per-service slicing requires *Webpack externals*: each per-service module marks core's
symbols as externals referencing a separately-shipped vendor bundle. That works but
presupposes the vendor bundle is loaded first and exports the right namespace. Implementation
detail in §5.

---

## 4. Design: plugin-composed runtime

Restructure `JsRuntime` from a fixed set of services to a plugin registry.

### 4.1 The plugin interface

```kotlin
// :tenshu-sdk-js-core (new module)

/**
 * A unit of service composition for the JS in-process runtime. Each
 * first-party service ships a plugin; third-party services register via
 * the published @tenshu/sdk/core plugin contract.
 */
interface JsRuntimePlugin {

    /**
     * Stable identifier. Used for ordering, deduplication, and for the
     * runtime to surface "service X is registered" to higher-level
     * consumers (e.g. the apex app launcher).
     */
    val id: String

    /**
     * Schema/Koin module contributions: the *ServiceCommonModule and
     * *ServiceClientModule the existing JsRuntime hard-codes today.
     */
    val schemaConfigs: List<Module> get() = emptyList()

    /**
     * Operation factory contribution. The runtime composes all
     * registered factories into a single OperationImplementationFactory
     * via chained elvis; first non-null wins. Order is registration order.
     */
    fun operationFactory(ctx: RuntimeContext): OperationImplementationFactory? = null

    /** Semantic mergers this service contributes. */
    val mergers: List<SemanticMerger<*>> get() = emptyList()

    /**
     * Initialization run inside ClientRegistry.initialization for this
     * plugin. Service-side setup (e.g. file-service's root-folder entity
     * creation) lives here.
     */
    suspend fun initialize(ctx: ExecutionContext) {}

    /**
     * Plugin IDs this plugin requires to register *before* it. Used to
     * order initialization and to fail fast if a prerequisite is missing.
     * Most first-party plugins won't declare any. The cross-service
     * exception is files/notifications/identity declaring sync as a
     * prereq because they publish through its tombstone emitter — see
     * §6.3 for why we instead promote the emitter to a core concern.
     */
    val dependsOn: Set<String> get() = emptySet()
}
```

`RuntimeContext` exposes the runtime resources a plugin's factory needs. Today these are:
`fileDatabase`, `syncRelay`, `syncManager`, `syncCryptoManager`, `tombstoneEmitter`. New
services may add more — specifically, third-party services may need IndexedDB / network
primitives we currently expose only to first-party.

### 4.2 The runtime builder

```kotlin
@JsExport
object JsRuntimeBuilder {
    private val plugins = mutableListOf<JsRuntimePlugin>()
    private var built: JsRuntime? = null

    /**
     * Register a plugin. May be called before or after first runtime use.
     *
     * Pre-build: queued; runtime is built from the accumulated set on
     * first access.
     *
     * Post-build: the plugin is integrated into the running runtime via
     * SchemaRegistry mutation, factory-chain append, and an inline
     * initialize() call. Throws if the plugin's schema configs conflict
     * with an already-registered schema.
     */
    fun register(plugin: JsRuntimePlugin) {
        val existing = plugins.firstOrNull { it.id == plugin.id }
        if (existing != null) {
            check(existing === plugin) {
                "Conflicting plugin registered for id=${plugin.id}"
            }
            return  // idempotent
        }
        plugins += plugin
        built?.augment(plugin)
    }

    val runtime: JsRuntime get() {
        if (built == null) {
            val ordered = topologicalSort(plugins)
            built = JsRuntime(ordered)
        }
        return built!!
    }
}
```

Important property: `register(...)` is idempotent and ordering-tolerant. Re-registering the
same plugin is a no-op (so HMR / repeated test setup don't trip the check). Cross-plugin
ordering is resolved via `dependsOn` topological sort at first build.

### 4.3 Lazy registration — the dynamic-import path

When apex dynamically imports `@tenshu/sdk/files` in a route component, the per-service
ESM module's top-level statement fires:

```kotlin
// :tenshu-sdk-js-files (new module)

object FilesPlugin : JsRuntimePlugin { /* ... */ }

// Top-level Kotlin/JS expression — runs at module load.
@Suppress("unused")
private val filesRegistration: Unit = JsRuntimeBuilder.register(FilesPlugin)
```

If the runtime is already built (apex already entered another runtime-using route),
`register(...)` calls `runtime.augment(plugin)`. The augment path:

1. Adds the plugin's `schemaConfigs` to the existing `SchemaRegistry`. *(See §6.1 — requires
   a small SchemaRegistry change.)*
2. Appends the plugin's `operationFactory(ctx)` to the live factory chain. The chain is
   reified as a `MutableList<OperationImplementationFactory>` looked up dynamically per
   dispatch.
3. Adds mergers to the existing `SemanticMergerRegistry`.
4. Runs `plugin.initialize(executionContext)` once on the existing context.

Apex code that dispatches an op against the augmented service sees it work the first call
after registration completes. There's no rebuild barrier and no apex-side awareness of the
augmentation.

### 4.4 Why plugins, not factories

A factory-style "compose explicitly at startup" API would also work and would be more
functional / explicit. We prefer plugins-with-side-effect-imports because:

- Apex code's existing import pattern (`import { Files } from '@tenshu/sdk/files'`) keeps
  working unchanged.
- Third-party services don't need a coordinated bootstrap — they register on import.
- "Compose at startup" collides with route-level dynamic imports: services discovered later
  need a re-compose step, which forces apex to know the entire service set up front.

The cost is import-side-effect dependency: per-service packages must be marked `sideEffects`
correctly so bundlers don't drop the registration call. Documented in §6.4.

---

## 5. Bundle topology

### 5.1 Gradle modules

```
:tenshu-sdk-js-core
    deps: :core, :core-common, :core-client
    npm:  kotlinx-coroutines, ktor-client-core, ktor-client-js,
          @noble/curves, @noble/ciphers, @noble/hashes
    contains:
        - JsRuntimeBuilder, JsRuntimePlugin, JsRuntime, RuntimeContext
        - Internal IDB helpers: IndexedDbObjectStore, IndexedDbFileDatabase,
          IndexedDbSessionStore, IndexedDbSecureStorageDelegate,
          IndexedDbMasterKeyStore
        - Pure-value @JsExport: Bip39, TenshuSdk
        - schema walker / CompositeSnapshot / TErrorJs / canvas preview
        - WebAuthnVerifier (used by Session)
        - PairingUri parsing helper

:tenshu-sdk-js-identity
    deps: :tenshu-sdk-js-core (compileOnly via Webpack externals),
          :identity-service{,-common,-client,-proxy}
    contains:
        - Devices.kt
        - IdentityPlugin (registers identityService{Common,Client}Module +
          identityProxyImplementationFactory + initIdentityService)
        - top-level register() call

:tenshu-sdk-js-files
    deps: :tenshu-sdk-js-core, :file-service{,-common,-client,-proxy}
    contains:
        - Files.kt, FileSystem.kt, Folders.kt
        - FilesPlugin (file*Module + fileServiceProxyImplementationFactory +
          initFileService + FolderMerger)

:tenshu-sdk-js-notifications
    deps: :tenshu-sdk-js-core, :notification-service{,-common,-client,-proxy}
    contains:
        - Notifications.kt
        - NotificationsPlugin

:tenshu-sdk-js-onboarding
    deps: :tenshu-sdk-js-core, :tenshu-sdk-js-identity,
          :sync-service{,-common,-client,-proxy}
    contains:
        - Session.kt, Realm.kt, Pairing.kt, Recovery.kt
        - SyncPlugin (sync*Module + syncProxyImplementationFactory +
          initSyncService)
        - dependsOn = setOf("identity")
```

Each Gradle module declares
`js(IR) { browser(); binaries.executable(); useEsModules() }`. Each emits its own Webpack
ESM bundle into `build/dist/js/productionExecutable/`. Per-service Webpack configs declare
`:tenshu-sdk-js-core`'s exported namespaces as externals — see §5.2.

### 5.2 Webpack externals

> **SUPERSEDED (spike 2026-05-24).** This mechanism does not link across bundles — see
> "Phase 2 revision" at the top. A separate webpack bundle exports only the minified
> `@JsExport` surface, but Kotlin inter-module linkage imports internal mangled symbols.
> Kept for design history.

Per-service Webpack configs add `webpack.config.d/externals.js`:

```javascript
// tenshu-sdk-js-files/webpack.config.d/externals.js
//
// Maps Kotlin module IDs to a global namespace exported by the core
// bundle's UMD wrapper. The core bundle's webpack config sets
// output.library = { type: 'umd', name: 'tenshuSdkCore' }; per-service
// bundles reference its symbols via tenshuSdkCore.<name>.
config.externals = config.externals || []
config.externals.push({
    'core': 'tenshuSdkCore',
    'core-common': 'tenshuSdkCore',
    'core-client': 'tenshuSdkCore',
    'kotlinx-coroutines-core': 'tenshuSdkCore',
    '@noble/curves/ed25519': 'tenshuSdkCore',
    // ...etc
})
```

The exact map depends on what symbols the core bundle re-exports. Two viable shapes:

1. **Re-export everything from the dependent klibs as one flat namespace.** Simple;
   per-service externals point to one name. Risks symbol collisions.
2. **Re-export per-klib namespaces.** Externals map per-klib-id → per-klib-namespace.
   More config but cleaner.

Recommend (1) for v1; revisit if collisions surface.

### 5.3 npm package shape

`web/sdk/package.json` `exports` map is unchanged. The implementation behind each path
swaps:

```json
{
  "exports": {
    ".":               "./src/index.ts",
    "./core":          "./src/core.ts",
    "./identity":      "./src/identity.ts",
    "./notifications": "./src/notifications.ts",
    "./files":         "./src/files.ts",
    "./onboarding":    "./src/onboarding.ts"
  },
  "sideEffects": [
    "./src/identity.ts",
    "./src/notifications.ts",
    "./src/files.ts",
    "./src/onboarding.ts"
  ]
}
```

`sideEffects` must list every path whose import has registration side effects. `core.ts` is
**not** listed — importing core builds nothing; the runtime is built lazily on first
runtime-use, and core's own contents are pure value.

Per-service TS files become per-service `_bundle/<name>.ts`:

```typescript
// web/sdk/src/_bundle/files.ts
/// <reference path="../native.d.ts" />
export {
  Files, Folders, FileSystem, FilesPlugin,
} from '../../../tenshu-sdk-js-files/build/dist/js/productionExecutable/tenshu-sdk-js-files.js'

// web/sdk/src/files.ts
import * as bundle from './_bundle/files'

export const Files = bundle.Files.getInstance()
export const Folders = bundle.Folders.getInstance()
export const FileSystem = bundle.FileSystem.getInstance()
```

### 5.4 Apex consumption

Apex's first-paint route imports `@tenshu/sdk/core` only:

```typescript
// web/apex/src/App.tsx
import { Session, TenshuSdk } from '@tenshu/sdk/core'
```

(Note: `Session.detect` is currently in `@tenshu/sdk/onboarding` because all
onboarding/session machinery is bundled together. Phase 1 of this proposal can keep that,
but Phase 3's first-paint reduction needs `Session.detect` accessible without loading the
onboarding bundle. That likely means splitting `Session.detect` — IDB-only — into core,
while `Realm` / `Pairing` / `Recovery` stay in onboarding. Sized as part of Phase 3.)

Route-level lazy imports:

```typescript
// web/apex/src/routes/onboarding/index.tsx
const { Realm, Pairing, Recovery } = await import('@tenshu/sdk/onboarding')

// web/apex/src/routes/control/devices.tsx
const { Devices } = await import('@tenshu/sdk/identity')
```

Vite emits route-level chunks. First paint loads core + apex shell; route entry loads the
appropriate per-service chunk.

---

## 6. Implementation challenges

### 6.1 SchemaRegistry mutation post-build

`createDefaultSchemaRegistry(extraConfigs = ...)` is called once at `JsRuntime` construction.
For the augment path to work, it needs to support adding configs after construction.

Inspecting `SchemaRegistry` shows this is **already supported**: `registerConfig(config)`
just appends to the `configs: MutableList<ModuleConfiguration>`, and every lookup walks
the list freshly via `configs.asReversed().firstNotNullOfOrNull { ... }`. There's no cache
keyed on "the set of registered configs" — only `materializationCache` keyed by
`LazyDataCreator` identity, which doesn't invalidate when new schemas are added (and
doesn't need to, since previously-materialized schemas remain consistent).

So the augment path doesn't need any new SchemaRegistry method — it calls
`registerConfig(plugin.schemaConfigs)` directly, the same way Phase 1's initial-build path
does.

What this requires from `:core-common` is the orthogonal cleanup spec'd in
[`core-module-composition.md`](core-module-composition.md): idempotent registration (so
re-imports are safe) and prerequisite-based `include()` (so plugins that depend on other
plugins' schemas don't double-merge). Those land as the precursor PR before Phase 1 of
this proposal.

### 6.2 `OperationImplementationFactory` chain reification

Today's `JsRuntime` builds the operation-dispatch factory as a chain of `?:` (Kotlin's
elvis operator) fallbacks, hard-coded into a single lambda at runtime construction:

```kotlin
private val operationImplementationFactory: OperationImplementationFactory =
    OperationImplementationFactory { id ->
        implementationFactory(id)
            ?: fileServiceProxyImplementationFactory(id, fileDatabase)
            ?: notificationServiceProxyImplementationFactory(id)
            ?: syncProxyImplementationFactory(id, syncRelay, syncManager, /* ... */)
            ?: identityProxyImplementationFactory(id)
    }
```

Each `?:` falls through to the next factory if the previous returned null; the first
non-null wins. The whole chain is captured into the `val` once at `JsRuntime` construction
and never changes — the *shape* of the fallback sequence is fixed at compile time. That
works while the service set is fixed at four, but the plugin model needs to add factories
after construction (e.g. apex dynamically imports `@tenshu/sdk/photos`; from that moment
on, dispatching a photos op must hit the photos factory).

Replace the hard-coded chain with a `MutableList<OperationImplementationFactory>` looked
up per dispatch:

```kotlin
class JsRuntime(initialPlugins: List<JsRuntimePlugin>) {
    private val factoryChain = mutableListOf<OperationImplementationFactory>()

    val operationImplementationFactory: OperationImplementationFactory =
        OperationImplementationFactory { id ->
            implementationFactory(id) ?: factoryChain.firstNotNullOfOrNull { it.create(id) }
        }

    fun augment(plugin: JsRuntimePlugin) {
        plugin.operationFactory(runtimeContext)?.let { factoryChain += it }
        // ...schemaConfigs, mergers, initialize...
    }
}
```

Now `augment(plugin)` appends to `factoryChain` and the next dispatch sees the new factory.
Per-dispatch list lookup is O(N services) — at the four-services scale, negligible. At the
hundred-services-and-third-party scale, may want a hash map keyed by op-id-prefix; defer.

### 6.3 Cross-service shared state (tombstone emitter)

`TombstoneEmitterHolder` is shared mutable state today. Sync's init produces the emitter;
files/notifications/identity factories read it from the holder. Plugin model must preserve
this without forcing every service to declare `dependsOn = setOf("sync")`.

Recommend: **promote the tombstone emitter to a core runtime concern.** It's not a
sync-service-private detail — it's a sync-protocol primitive that other services emit
through. Move `TombstoneEmitterHolder` to `:tenshu-sdk-js-core`, expose it via
`RuntimeContext.tombstoneEmitter`. Sync plugin populates it on init; other plugins read it
from `RuntimeContext` in their factory.

Same pattern applies to any future cross-service primitive (e.g. realm-state metadata).

### 6.4 Side-effect imports

Per-service modules' top-level `register(plugin)` call must survive bundling. Three
precautions:

1. `web/sdk/package.json` `sideEffects` lists all per-service paths (not core).
2. Per-service Kotlin code wraps the registration in `private val ... = run { ... }` so
   it's a top-level property initialization, not a top-level statement (Kotlin/JS compiles
   the latter into module-init code that some bundlers eliminate more aggressively).
3. CI test: a fresh apex build that imports `@tenshu/sdk/files` and dispatches
   `FileSystem.list` must not throw "FilesPlugin not registered". Catches accidental DCE.

### 6.5 Webpack externals correctness

The `externals` map between per-service modules and the core bundle is the critical
correctness boundary. If the core bundle's exported namespace shape changes (a Kotlin
module ID renames, a class moves), every per-service bundle silently breaks at runtime
rather than at build time.

Mitigations:

- Pin the externals map to specific symbol names, not wildcards.
- CI test: per-service bundle should pass a runtime sanity check (`FilesPlugin.id === "files"`)
  when loaded against the core bundle.
- Document the core bundle's public Kotlin surface (everything imported by per-service
  plugins) in `:tenshu-sdk-js-core/jsMain/.../api.kt` as an explicit re-export list.
  Per-service plugins import only from there.

### 6.6 The five-Gradle-module multiplier

Five new Gradle modules with KMP `js(IR)` toolchains add CI cost: each needs its own
`webpack.config.d/`, its own `build.gradle.kts`, its own dependency declarations. Build
time grows. A trial build will measure.

Mitigation: share Gradle config via `buildSrc/` convention plugins. Each per-service module
declares one line: `apply(plugin = "tenshu-sdk-js-service-conventions")`. The convention
plugin sets up `js(IR)`, the externals webpack config, the standard dependency on
`:tenshu-sdk-js-core`.

---

## 7. Third-party service verification

Apex must verify integrity of dynamically-loaded service bundles before evaluating. The
threat model already specifies zero-trust posture for data; this extends it to executable
code.

### 7.1 Verification surface

Three layers, used together:

**Layer 1 — Subresource Integrity (browser-native).** `<script integrity="sha384-..."
crossorigin>` for static `<script>` tags. For dynamic `import()` URLs the spec is in flux
(`import.source` proposal). Interim: fetch + hash + Blob URL + `import()`.

```typescript
async function verifyAndImport(url: string, expectedSha384: string): Promise<unknown> {
    const response = await fetch(url)
    if (!response.ok) throw new ServiceLoadError('fetch failed')
    const bytes = await response.arrayBuffer()
    const actualHash = await crypto.subtle.digest('SHA-384', bytes)
    const actualB64 = base64Url(new Uint8Array(actualHash))
    if (actualB64 !== expectedSha384) {
        throw new ServiceLoadError('integrity mismatch')
    }
    const blob = new Blob([bytes], { type: 'application/javascript' })
    const blobUrl = URL.createObjectURL(blob)
    try {
        return await import(/* @vite-ignore */ blobUrl)
    } finally {
        URL.revokeObjectURL(blobUrl)
    }
}
```

**Layer 2 — Application-level signature.** Apex hosts a *service manifest* signed by
Tenshu's official publisher key (or, for self-hosted realms, by the realm master key).
Manifest entries:

```typescript
type ServiceManifestEntry = {
    serviceId: string                  // e.g. "store-photos"
    bundleUrl: string
    bundleSha384: string
    publisher: string                  // pubkey of the service publisher
    signature: Int8Array               // Tenshu-official signature over
                                       // (serviceId | bundleUrl | bundleSha384 | publisher)
}
```

Apex fetches the manifest, verifies the official signature (pinned key), then verifies
each bundle's hash matches. The manifest update flow is covered in
[`tenshu-publisher.md`](tenshu-publisher.md) — this proposal does not redefine it.

**Layer 3 — CSP `script-src` allowlist.** Apex's CSP restricts script sources to known
origins (`tenshu.app`, `cdn.tenshu.app`, the per-realm relay's bundle CDN). Browser-enforced
defense in depth against MITM.

Plus — and this is in scope of [`web-extension.md`](web-extension.md) rather than this
proposal — third-party services run their UI in iframes on subdomains. Even a
malicious-but-signed third-party service can only post messages; it can't reach apex's
IndexedDB or other apps' data.

### 7.2 First-party vs. third-party

For first-party services (the four shipped with `@tenshu/sdk`), verification is build-time:
apex's build pipeline pins their hashes. No runtime verification infrastructure is needed.

For third-party (TenshuStore-installed) services, the publisher / Rei / signature
infrastructure described in [`tenshu-publisher.md`](tenshu-publisher.md) is the analogous
trust root. Bundle signatures use the same key hierarchy. **This proposal does not specify
the third-party manifest format.** That's Phase 4 and lives in a separate proposal.

### 7.3 Threat-model touchpoints

This work doesn't change A1–A4 / A9 invariants. It does extend the trust boundary to
include "executable code apex evaluates," which is a new asset category. Suggested addition
to [`docs/sync/threat-model.md`](../sync/threat-model.md):

- **A11 (new):** *Service bundle integrity.* Third-party service bundles loaded into apex
  have published-and-pinned SHA-384 hashes; apex refuses to evaluate any bundle whose hash
  doesn't match. Compromise mode: a malicious bundle modifying apex behaviour. Mitigated by
  SRI + signed manifest + CSP allowlist.

The threat-model amendment lands with Phase 4 (the third-party path), not Phase 1 (which
stays first-party-only).

---

## 8. Phased plan

Each phase is a separately landable PR (or a small PR cluster).

### Phase 0 (precursor): module-composition cleanup (~half-day, separate PR)

Spec'd in [`core-module-composition.md`](core-module-composition.md). Lands ahead of
Phase 1.

- `ModuleConfiguration.prerequisites` + `include()` from eager-merge to lazy declaration.
- `SchemaRegistry.registerConfig` dedups + walks prerequisites.
- Rename `CoreParserConfiguration` → `coreCommonModule`.

### Phase 1: JsRuntime decomposition (~1–2 days, single PR) — **LANDED 2026-05-22**

In-place refactor of `:tenshu-sdk-js`. **No build-pipeline change.** Builds on Phase 0's
clean module composition.

- Add `JsRuntimePlugin` interface + `JsRuntimeBuilder` in `:tenshu-sdk-js`. ✅
- Promote `TombstoneEmitterHolder` to a `RuntimeContext` member (move from sync's
  exclusive owning to runtime-shared). ✅
- Each existing service becomes a `JsRuntimePlugin` (`IdentityPlugin`, `FilesPlugin`,
  `NotificationsPlugin`, `SyncPlugin`). All four still live inside `:tenshu-sdk-js` for
  now; the plugin interface is the seam. ✅
- Replace `JsRuntime`'s hard-coded service wiring with iteration over `plugins`. ✅
- All existing tests pass. ✅
- Apex bundle size unchanged (no bundle-topology change yet). ✅ (+0.36% on the raw
  pre-minify webpack output — just the new plugin/builder code; topology unchanged.)

**Exit criteria:** apex flows work identically; jsTest green; no apex bundle regression.
All met — `:tenshu-sdk-js:jsTest` full suite green (the FileSystem / Notifications /
Pairing / Realm / Session / Devices integration suites dispatch real ops through the
composed runtime), plus a new `JsRuntimePluginTest` (ordering / idempotency / conflict /
`dependsOn` topo-sort / augment-hook). No change to the `@JsExport` surface, the TS
contract, or `web/` — only `internal/` + `jsTest/`.

**As-built deviations from the design above** (each documented inline in the code):

1. **`JsRuntime` stays an `object`, not the §4.2 `class JsRuntime(plugins)`.** The
   `class` form would churn ~14 `JsRuntime.foo` call sites across the SDK surface for no
   Phase-1 benefit. `JsRuntimeBuilder` carries the registration surface instead; the
   four first-party plugins are seeded eagerly in its `init`, and `JsRuntime` (the sole
   `RuntimeContext`) iterates `JsRuntimeBuilder.orderedPlugins`. The §4.3 augment path is
   implemented (a hook from `register(...)` into `JsRuntime.augment(...)`, gated on each
   facet's `Lazy.isInitialized()`); in Phase 1 it's unexercised since all plugins are
   seeded before first use, but it's the seam Phase 2 relies on.
2. **`initialize(executionContext, runtime)` is 2-arg and non-suspend**, not §4.1's
   `suspend fun initialize(ctx: ExecutionContext)`. `ClientRegistry.initialization` is a
   plain `ExecutionContext.() -> Unit` and the underlying `initFileService()` /
   `initSyncService(...)` are synchronous, so suspend isn't needed; sync init additionally
   needs `syncManager` / `cryptoManager` / `mergerRegistry` / `serviceScope`, which live on
   `RuntimeContext`, hence the second arg.
3. **`RuntimeContext` exposes `mergerRegistry` + `serviceScope`** beyond §4.1's list
   (`fileDatabase` / `syncRelay` / `syncManager` / `syncCryptoManager` / tombstone), for
   the same sync-init reason. Mergers are contributed per-plugin (`mergers`) and consumed
   by sync's init via `runtime.mergerRegistry`, decoupling contribution from consumption
   per §6.3. Plugin order `[files, notifications, identity, sync]` reproduces the legacy
   schema/factory/init orderings; sync is last so file/notification ops short-circuit
   before the relay's lazy fires.

### Phase 2: per-service Gradle modules + Webpack externals (~3–4 days, PR cluster) — SUPERSEDED

> **SUPERSEDED by the Path A plan in "Phase 2 revision" (top of doc), 2026-05-24.** The
> spike proved the Webpack-externals link doesn't work. Phase 2 is now per-module ESM +
> route-level lazy loading (2a/2b/2c). The text below is retained for design history.

- New Gradle modules `:tenshu-sdk-js-core`, `-identity`, `-files`, `-notifications`,
  `-onboarding`.
- Convention plugin in `buildSrc/` for per-service module config.
- Move per-service Kotlin sources into the new modules. Each module's plugin registers
  itself at top level.
- Webpack externals config for each per-service module pointing at `:tenshu-sdk-js-core`'s
  exported namespace.
- Update `web/sdk/src/_bundle.ts` → `_bundle/<name>.ts` per service, each pointing at its
  own Gradle output.
- Decommission the monolithic `:tenshu-sdk-js` module.
- CI: per-service bundle integrity test (load against core, verify plugin registers).

**Exit criteria:** apex importing all four services has bundle size within 5% of pre-Phase-2
(no regression). Apex importing only `@tenshu/sdk/core` produces a bundle <100 KB. Apex
importing `@tenshu/sdk/notifications` only produces a bundle materially smaller than one
importing `@tenshu/sdk/files`.

### Phase 3: route-level lazy loading in apex (~2 days, single PR)

- Carve `Session.detect` out of `:tenshu-sdk-js-onboarding` into `:tenshu-sdk-js-core`
  (IDB-only, no relay).
- Convert apex routes to dynamic imports per §5.4.
- Vite chunk-naming config so per-route chunks are predictably named.
- CI bundle-size budget: first-paint chunk ≤ 200 KB; per-route chunks each ≤ 150 KB.

**Exit criteria:** apex first-paint Network panel shows core + shell only (~150 KB).
Entering an onboarding route loads the onboarding bundle. Entering control/devices loads
the identity bundle.

### Phase 4: third-party service-bundle protocol (sized separately)

A new proposal, `docs/proposals/web-third-party-services.md`, covering:

- Service manifest format (signed by Tenshu's official key)
- Bundle integrity verification
- TenshuStore distribution / discovery
- iframe sandbox surface for third-party UI

Not architecturally blocked on Phases 1–3, but starts to make sense to write up after
Phase 3 since the apex side of the loading flow is then concrete.

---

## 9. Risks and open questions

1. **Cross-Webpack symbol drift.** If a Kotlin compiler upgrade changes per-symbol output
   names in the core bundle, every per-service externals map silently breaks. Mitigated by
   CI integration tests; not eliminated. Pinning Kotlin version is the secondary defense.

2. **First-paint Session.detect carve-out.** `Session.detect` reads from
   `tenshu-sdk-session` IDB only — but the `IndexedDbSessionStore` impl currently lives in
   the runtime's `internal/`, alongside the rest of the IDB plumbing. Carving it out so it
   can run without loading the whole runtime is doable but needs care; sized inside
   Phase 3.

3. **Plugin registration order vs. third-party plugin dependencies.** What happens if a
   third-party plugin declares `dependsOn = setOf("photos")` but `photos` isn't installed?
   Today the spec says: registration throws. That's the wrong UX for "service installed
   lazily on user request" — should be deferred-registration. Defer the
   deferred-registration mechanism to Phase 4.

4. **Bundle versioning.** When a per-service bundle is hot-updated (CDN refresh), what
   guarantees consistent state with apex? Today's mental model is "everything ships on
   every page load." Stale-bundle handling is a Phase 4 concern; flagged here so it isn't
   forgotten.

5. **Why Webpack at all?** Once the runtime is plugin-composed it becomes tempting to
   switch the bundling tool entirely (Rollup, esbuild, swc). The previous attempt
   (Rolldown) showed Kotlin-aware optimizations matter. This proposal stays on Webpack
   until someone produces evidence that another tool's Kotlin support has caught up.

6. **Apex can't shrink to zero.** Even with route-level lazy loading, the apex `App.tsx`
   shell + React + React Router + Tailwind runtime + a minimal core bundle still floor the
   first-paint at maybe 100 KB. The sub-200 KB target is aspirational; if the floor turns
   out to be 220 KB after measurement, that's still a 60% reduction from today.

7. **JVM/Android analogue — deferred, not denied.** The same elvis-chained
   `(String) -> OperationImplementation` factory pattern that §1 calls out exists in the
   JVM Koin wiring too — `control-shared/.../desktopMain/.../di/PlatformProxyModule.kt`
   composes `implementationFactory(...) ?: syncProxyImplementationFactory(...) ?: ...`
   the same way, and the Android + test fixtures mirror it. Generalising the plugin model
   (§4) to JVM is a natural follow-up — §2 goal 3 ("Mirrors Koin's module-composition
   model on JVM/Android") explicitly hints at it. Deferred for two reasons: JVM doesn't
   have the bundle-size driver that forced this proposal, and the factory chain is still
   churning often enough that locking it down behind a plugin protocol is premature.
   Recorded here so a future reader doesn't re-discover the JVM analogue independently.

---

## 10. Backwards compatibility

- The `@tenshu/sdk/{core,identity,notifications,files,onboarding}` TS contract is unchanged.
  Apex code does not need modification for Phases 1–2.
- Phase 3 changes apex internal route code, not the SDK contract.
- The Kotlin `internal` shape of `JsRuntime` changes, but no external (apex / SDK consumer)
  reaches into internal.
- The Kotlin `@JsExport` surface (`Bip39`, `Files`, `Notifications`, `Devices`, `FileSystem`,
  `Folders`, `Session`, `Realm`, `Pairing`, `Recovery`, `TenshuSdk`) is unchanged.

---

## 11. References

- [`docs/web/architecture.md`](../web/architecture.md) §12.6.1 (bundling pipeline), §12.11
  (per-service exports map), §12.12 (in-process runtime construction), §13 (subdomain /
  phishing / verification model).
- [`docs/tech-debt.md`](../tech-debt.md) §"Kotlin-side Bundle Splitting" — closed by this
  proposal.
- [`docs/web/roadmap.md`](../web/roadmap.md) Phase 3.3 (per-service exports) — closed in
  spirit by Phase 2 here.
- [`docs/proposals/tenshu-publisher.md`](tenshu-publisher.md) — third-party publishing
  protocol (the trust root for §7).
- [`docs/sync/threat-model.md`](../sync/threat-model.md) §"Asset Catalogue" — A11 candidate
  addition.
- [`docs/proposals/web-extension.md`](web-extension.md) — capability sandbox for third-party
  UI.

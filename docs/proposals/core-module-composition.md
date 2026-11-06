# Module Composition Cleanup

**Status:** Fully landed (2026-05-13). Phases 1–4 + cycle detection (open Q §5.3);
package-level reorg (§5.5) — `parser/` split into `parser/` (binary
IO) + `module/` (composition).
**Scope:** `:core-common`'s [`parser/`](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/parser/)
package — specifically `ModuleConfiguration`, `ModuleConfigurationImpl`, `ModuleDsl`, and
`CoreParserConfiguration`. Plus the four `*ServiceCommonModule` / `*ServiceClientModule`
call sites that use `include(...)`. **Prerequisite for**
[`web-sdk-bundle-splitting.md`](web-sdk-bundle-splitting.md) — the plugin-decomposed
`JsRuntime` model assumes clean module composition; this proposal fixes the rough edges
first so bundle-splitting builds on a solid foundation.

This is a small, focused cleanup of the existing module-composition mechanism. **It is not
a redesign.** The module-as-unit-of-composition model — modules contribute schemas, traits,
operations, relations to a shared registry; modules can extend other modules' types
(e.g. files registering a `Syncable` trait on `File.Schema` referencing the core-defined
`Syncable`) — is correct and is preserved exactly. What this proposal changes is *how
modules express dependencies on each other*, plus a naming inconsistency.

The motivating use case for keeping the model open: a service module must be able to
register a trait, relation, or operation against a Composite defined by another service
module without those two services needing bidirectional knowledge of each other. That's a
property worth protecting, and the proposal is careful to preserve it.

---

## 1. The current state

### 1.1 `ModuleConfiguration` is a flat bag of maps

[`ModuleConfigurationImpl`](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/module/ModuleConfigurationImpl.kt)
is a collection of mutable maps — `schemaMapping`, `compositeFactories`, `valueFactories`,
`constantFactories`, `requestObjectFactories`, `traitFactories`, `operations`,
`serviceDataRelations`, `relations`, `referenceMapping`, `typeMapping`, `classToTypeMapping`.

[`tModule { ... }`](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/module/ModuleDsl.kt)
is the DSL that builds one. Inside the block, `composite(...)`, `value(...)`, `trait(...)`,
`operation(...)`, `service(...)`, `serviceDataRelation(...)`, etc. populate the maps.

[`SchemaRegistry`](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/SchemaRegistry.kt)
holds a `MutableList<ModuleConfiguration>` (`configs`) and every lookup is
`configs.asReversed().firstNotNullOfOrNull { ... }`. Last-registered wins on collision.

### 1.2 `include()` is eager-merge

`ModuleConfigurationImpl.include(other)`
([line 367](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/module/ModuleConfigurationImpl.kt#L367))
performs `putAll` of every one of the included module's maps into the current one. After
`fileServiceCommonModule.include(notificationServiceCommonModule)`, files's module *is* a
single `ModuleConfiguration` whose maps contain the union of files's content and
notifications's content.

Today's call graph:

- `fileServiceCommonModule` `include(notificationServiceCommonModule)`
- `fileServiceClientModule` `include(notificationServiceClientModule)` + `include(fileServiceCommonModule)`

So when [`JsRuntime`](../../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/internal/JsRuntime.kt#L293)
registers all eight first-party modules in `extraConfigs`:

```
configs:
  [0] CoreParserConfiguration               <- registered by createDefaultSchemaRegistry
  [1] fileServiceCommonModule               <- already contains notifications-common content
  [2] fileServiceClientModule               <- already contains notifications-{common,client} + files-common
  [3] notificationServiceCommonModule       <- duplicate of part of [1]
  [4] notificationServiceClientModule       <- duplicate of part of [2]
  [5] identityServiceCommonModule
  [6] identityServiceClientModule
  [7] syncServiceCommonModule
  [8] syncServiceClientModule
```

Notifications's content is in two-to-three places. First-match-wins lookup means it works,
but it's wasted memory and obscures provenance.

### 1.3 No deduplication on `registerConfig`

[`SchemaRegistry.registerConfig`](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/SchemaRegistry.kt#L77)
is just `configs.add(config)`. Calling it twice with the same module appends twice. Lookup
still works, but it's a smell — masks ordering bugs and inflates lookup cost.

### 1.4 `CoreParserConfiguration` naming is an outlier

The instance name is PascalCase + "ParserConfiguration":

```kotlin
// core-common/.../parser/CoreParserConfiguration.kt:158
val CoreParserConfiguration = tModule { /* ... */ }
```

Every other instance follows camelCase + "Module":

```kotlin
val fileServiceCommonModule = tModule { /* ... */ }
val notificationServiceCommonModule = tModule { /* ... */ }
val identityServiceClientModule = tModule { /* ... */ }
// ...
```

The "Parser" prefix is historical — early on, `ModuleConfiguration` was conceived as
"configure the binary parser/serializer for these schemas", and the package
(`core-common/.../parser/`) reflects that. The scope has since grown well beyond parsing —
it now carries operations, traits, relations, services. The name no longer matches.

---

## 2. Goals

1. **Eliminate the duplicate-content-via-include problem** without breaking
   open-extension semantics.
2. **Make `registerConfig` idempotent on duplicate registration**, so plugin
   re-registration (HMR, dynamic re-import, tests) is safe.
3. **Rename `CoreParserConfiguration` to match the service-module convention.**
4. **Preserve every open-extension capability of today's system.** Modules can still
   register traits, relations, operations, and types against Composites defined by other
   modules. Schemas do not "declare their own dependencies" — that would couple the
   producer to its consumers, which we explicitly do not want.

**Non-goals:**

- Moving things between packages (`parser/` → `module/` would be a bigger churn; keep it
  as-is for this work).
- Type-system overhaul. `ModuleConfiguration` keeps its interface shape; the maps stay
  mutable; the DSL keeps its callable surface.
- Scoped registration / unregister / hot-reload.
- Migration of test fixtures in `:sync-service-proxy/commonTest` to a new helper —
  optional follow-up; not blocking.

---

## 3. Design

Three changes, each independently landable.

### 3.1 `include()` as prerequisite declaration

Replace eager-merge with a lazy prerequisite list. The interface gains one read-only
property; the impl tracks it:

```kotlin
// ModuleConfiguration.kt
interface ModuleConfiguration {
    /**
     * Other modules whose contributions this module assumes are present in
     * the registry. Resolved depth-first at SchemaRegistry.registerConfig
     * time, deduplicated by referential identity. Replaces the eager-merge
     * semantics of the old include().
     */
    val prerequisites: List<ModuleConfiguration>

    fun include(config: ModuleConfiguration)
    // ...rest unchanged
}

// ModuleConfigurationImpl.kt
class ModuleConfigurationImpl : ModuleConfiguration {
    private val _prerequisites = mutableListOf<ModuleConfiguration>()
    override val prerequisites: List<ModuleConfiguration> get() = _prerequisites

    override fun include(config: ModuleConfiguration) {
        _prerequisites += config  // no more putAll
    }
    // ...rest unchanged
}
```

`SchemaRegistry.registerConfig` resolves prerequisites depth-first, deduplicated:

```kotlin
fun registerConfig(config: ModuleConfiguration) {
    if (config in configs) return  // §3.2
    config.prerequisites.forEach { registerConfig(it) }  // depth-first, recursive
    configs.add(config)
}
```

After the change, the `JsRuntime`-registered set produces:

```
configs:
  [0] CoreParserConfiguration               (renamed in §3.3)
  [1] notificationServiceCommonModule       (transitive prereq of fileServiceCommonModule)
  [2] fileServiceCommonModule
  [3] notificationServiceClientModule       (transitive prereq of fileServiceClientModule)
  [4] fileServiceClientModule               (also depends on fileServiceCommonModule, deduped)
  [5] identityServiceCommonModule
  [6] identityServiceClientModule
  [7] syncServiceCommonModule
  [8] syncServiceClientModule
```

Each module is its own atomic entry. No content duplication. `firstNotNullOfOrNull(asReversed())`
lookup semantics are preserved — last-registered still wins on collision. Behaviour is
identical to today for any well-formed module set; only the duplication goes away.

#### Why this preserves open extension

A trait registration like file's `trait(File.Schema, Syncable.Schema, ...)` works because:

- `Syncable.Schema` is referenced as a Kotlin `val` from the core module (a static reference,
  resolved by Kotlin's compiler).
- The trait registration stores the *factory* keyed by `(File.Schema.id, Syncable.Schema.id)`
  in file's own `traitFactories` map.
- At trait lookup, `SchemaRegistry.getTrait(type, traitId)` walks all `configs` and finds
  whichever module registered that key.

A future hypothetical `photosServiceCommonModule` could register
`trait(File.Schema, PhotoMetadata.Schema, ...)` to extend file's schema with a
photos-specific trait, with photos depending on files (declares it as a prerequisite to
ensure `File.Schema` is registered) but without files knowing photos exists. **Both ways
of cross-referencing types — declaring something against another module's schema, and
referencing another module's schema as a Kotlin val — keep working unchanged.**

### 3.2 Idempotent registration

The `if (config in configs) return` check above (§3.1) covers this. Duplicate registrations
no-op. Registration order is preserved by first-occurrence: registering A then B then A
again leaves `configs = [A, B]`.

This matters for the plugin model in
[`web-sdk-bundle-splitting.md`](web-sdk-bundle-splitting.md): when a per-service ESM module
fires its top-level `JsRuntimeBuilder.register(plugin)` on import, that registers the
plugin's `schemaConfigs` into the registry. HMR re-imports, test setup, and dynamic
re-imports all become safe.

### 3.3 Rename `CoreParserConfiguration` → `coreCommonModule`

Rename the instance to match the service-module convention:

```kotlin
// core-common/.../parser/CoreParserConfiguration.kt
val coreCommonModule = tModule { /* ... */ }
```

The file itself can be renamed to `CoreCommonModule.kt` for consistency, though that's
cosmetic.

The package `parser/` stays put. `parser/` mixing binary parser/serializer with
module-composition is mildly confusing but not load-bearing; leave it for a future
package-level reorganization.

`createDefaultSchemaRegistry` updates from `registerConfig(CoreParserConfiguration)` to
`registerConfig(coreCommonModule)`. Test sites (`:sync-service-proxy/commonTest` and
`:sync-relay-server` test files) update their imports.

---

## 4. Migration plan

One PR, four ordered commits.

### Commit 1: Add `ModuleConfiguration.prerequisites`, keep `include()` doing both

- Add `prerequisites: List<ModuleConfiguration>` to the interface.
- `ModuleConfigurationImpl.include()` now does *both*: appends to `_prerequisites` AND
  the existing `putAll` merge.
- `SchemaRegistry.registerConfig` doesn't change yet.
- All existing tests pass; behaviour is strictly the same.

This commit is a no-op stepping stone; it lets us flip the registration semantics next
without flipping the include semantics simultaneously.

### Commit 2: Switch `SchemaRegistry.registerConfig` to dedup + walk prerequisites

```kotlin
fun registerConfig(config: ModuleConfiguration) {
    if (config in configs) return
    config.prerequisites.forEach { registerConfig(it) }
    configs.add(config)
}
```

Tests still pass — the prerequisite walk produces extra registrations, but they're deduped
against the eager-merge results so the lookup behaviour is unchanged.

### Commit 3: Drop the eager-merge from `include()`

`ModuleConfigurationImpl.include()` now only appends to `_prerequisites`; the `putAll`
calls are deleted. From this point on, modules are atomic; their content lives only in
their own maps.

Tests must still pass at this commit. Any test that depends on eager-merge behaviour
(e.g. directly inspects `fileServiceCommonModule.schemaMapping` and expects to see
notification entries) needs migration. Likely none — but worth a careful search.

### Commit 4: Rename `CoreParserConfiguration` → `coreCommonModule`

Pure rename. IDE-driven. Updates ~30 import sites across the codebase (mostly tests). No
behaviour change.

---

## 5. Open questions

1. **Is there a test that depends on eager-merge behaviour?** **Resolved.** Grep for
   direct map access on the service modules (`fileServiceCommonModule.schemaMapping`,
   etc.) found zero call sites. Eager-merge was only load-bearing for the lookup path,
   which the Phase 2 prerequisite-walk covers atomically.

2. **Should `prerequisites` be a `Set` or `List`?** **Resolved: `List`.** Declaration
   order matters when one prerequisite transitively declares another; DFS over a list
   produces the right registration order. Idempotence is handled at the `include()`
   level (skip if already present) and at the `registerConfig()` level (`if (config in
   configs) return`).

3. **Should the registry detect cycles?** **Yes — landed alongside Phase 2.**
   `registerConfigImpl` carries an `inProgress: MutableSet<ModuleConfiguration>` and
   throws an actionable error naming the cycle path. Five lines; protects against the
   third-party-plugin future where cross-imports could otherwise produce a stack
   overflow.

4. **`fileServiceClientModule`'s `include(fileServiceCommonModule)` — is it still
   needed?** Yes: the client module references types whose schemas live in the common
   module (e.g. `FileAddedNotification.Schema` for the `type(...)` calls), so it must
   declare common as a prerequisite. The semantic is the same as today; only the merge
   doesn't happen.

5. **What about `:core-common/.../parser/`'s package-level confusion?** **Resolved
   2026-05-13.** Split into `parser/` (binary IO: `BinaryParser`,
   `BinarySerializer`, `LinearSerializer`, `Token`, `TokenString`) + `module/`
   (composition: `ModuleConfiguration`, `ModuleConfigurationImpl`, `ModuleDsl`,
   `coreCommonModule`). 88 import statements rewritten across the codebase.

---

## 6. References

- [`docs/proposals/web-sdk-bundle-splitting.md`](web-sdk-bundle-splitting.md) — the
  downstream consumer; Phase 1 of bundle-splitting builds on the cleaned-up module
  composition this proposal lands.
- [`core-common/.../parser/`](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/parser/) — the
  package this proposal touches.
- [`core-common/.../proxy/SchemaRegistry.kt`](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/SchemaRegistry.kt) — registration + lookup.
- [`core-common/.../proxy/DefaultSchemaRegistry.kt`](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/DefaultSchemaRegistry.kt) — the bootstrap helper.

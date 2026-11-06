# Chain DSL — Entity-aware Static Typing for `search` / `select`

**Status:** §2.1, §2.2, §2.3 **landed** (`feat/core-chain-entity-typing-redesign`). §2.4 (SHM-aware sync AIDL reply) **Phase 1 landed** (`ClientConnection.tokenize`,
Android SHM/PFD); **Phase 2 deferred** (desktop UDS, JS) — see §2.4.
Author: Benedikt + Claude (after the SHM-aware `getRemoteFile`, lazy per-row previews and `TByteArray.getOrNull`).

**Scope:** `:core` (`collection/list/Search.kt`, `collection/list/Select.kt`), `:core-common`
(`impl/array/ListSelectRo.kt`, `impl/maybe/MaybeSelectRo.kt`, `impl/entity/EntitySelectRo.kt`,
`proxy/SelectImplementation.kt`, `module/CoreCommonModule.kt`,
`proxy/executor/ImplementationFactory.kt`), `:drive-shared`
(`data/TenshuFilesRepository.kt`).

**Wire format:** unchanged at the chain-step level (each new `*Select.Schema` is structurally
identical to `Select.Schema` modulo `receiver` / `returnType`). Storage shape: unchanged.
Semantics: preserved — the engine `SelectImplementation` already dispatched on
`caller: Maybe<*>` (no-op when caller was `Entity<*>` was the bug the §2.2 work fixed).
The user-visible change is **static-typing only**: chain expressions that used to require
`as? Entity<*>` casts at the consumer site now type as `Entity<T>` end-to-end.

---

## 1. Motivation

### 1.1 The cast smell

Pre-redesign, `TenshuFilesRepository.getRemoteFile` looked like this:

```kotlin
val result = … .searchAll<File> { … }
    .select { fields(File.Field.PATH); relations(File.Relation.DataProperty) }
    .flow()
    .first()

return when (result) {
    is TResult.Success<*> -> {
        val list = result.value as? TList<*>
        val entity = list?.toList()?.firstOrNull() as? Entity<*>     // ← cast 1
            ?: return RepositoryResult.Error(…)
        val data = entity.getProperty<TByteArray>(File.Relation.Data.id.get())
        …
    }
    …
}
```

Two narrowing casts (`TList<*>` and `Entity<*>`) at every byte-extraction site. The casts
were necessary because the chain DSL's static return types were too loose to express what
the engine actually hands back. Without the casts, the chain compiled but the consumer
couldn't read fields; with the casts, the consumer worked but a thirty-line comment block
had to explain *why* (we landed one in `0b9fa7de` — see `TenshuFilesRepository.kt:354-366`
on `master` before this redesign).

The underlying mismatch:

- `TList<R>.search { … }` declared a return type of `Maybe<R>` (R = `File`).
- The engine's `OnDemandMaterialiser` + `SelectImplementation.selectItem` produce an
  `EntityDto<*>` — a `Composite` implementing `Entity<*>` but **not** the concrete `File`
  interface.
- So `Maybe<R>` was a lie at runtime: the wrapped value implemented `Entity<File>` but
  *not* `File`. Smart-casting from the static `Maybe<File>` to read `File.path` would have
  thrown `ClassCastException` at the field access; the consumer's only safe path was to
  narrow back to `Entity<*>` and use `getProperty<TByteArray>(…)` / `getFieldValue(…)`.

The redesign retypes the chain to match the runtime: `search` returns `Maybe<Entity<E>>`,
`select` preserves the wrapper shape, and consumers stop casting.

### 1.2 Three secondary fixes

While we were in the chain DSL the redesign also fixed:

- **§2.3 — `TByteArray.get()` threw `IllegalStateException` on cross-process SHM cache miss.**
  Consumers had to `try/catch` at every byte read. Landed earlier, separately (`TByteArray.getOrNull`,
  default `try/catch → null`, override on `ParcelFileDescriptorFileByteArrayDto` to
  short-circuit). Not technically part of this redesign but conceptually adjacent.
- **`Maybe<T>.select` had been removed** in a prior cleanup (Phase 3 of the
  operation-schema-typing work — [ADR 0009](../adr/0009-operation-schema-typing.md))
  with the rationale that callers should
  `.ifEmptyError().select(…)` instead. That's the throw-on-empty path. The empty-as-value
  path is real (`.search.select.get()` for "look up if present" semantics) and the redesign
  brings it back as a typed chain step — §2.2.
- **`SelectImplementation` had no `Entity<*>` branch.** Passing an Entity caller funneled
  through `resolveList(caller)` → null → empty `TList`, silently dropping the value. §2.2
  adds the branch alongside `Maybe<*>`.

### 1.3 The Binder reply parcel cap (the §2.4 motivation)

A subtler problem surfaces when chains return byte-bearing payloads. The sync
`.search.get()` path uses `BinderTaskTransport.call`, whose reply rides the Binder reply
parcel — capped at ~1 MB per call on Android. A 12 MB JPEG inlines into the parcel and
kills the Drive process at `parcel.writeByteArray`. We caught this at runtime when `getRemoteFile`
moved to the SHM-aware stream transport (the original `getRemoteFile` used `.search.get()`, blew the cap on the first real
photo).

The fix was to route through `.searchAll → .select → .flow().first()` — the
streaming path *already* wraps byte payloads in `ParcelFileDescriptor` + SharedMemory via
the `ClientCallbackService` transport. Stream subscribe → wait for first emit → unsubscribe
gives caller-visible "one-shot" semantics with a SHM-aware transport.

§2.4 would lift the stream transport's SHM wrapping into `BinderTaskTransport.call`'s
reply path, letting `.search.get()` carry bytes safely and collapsing the workaround. It's
**not implemented** in this redesign — see §2.4 below for the parked design + ROI argument.

---

## 2. Design

The redesign has four phases. §2.1, §2.2, §2.3 landed. §2.4 is parked.

### 2.1 `search` returns `Maybe<Entity<E>>` (landed)

Two overloads — default (inferred) and narrowing (explicit `<T>`):

```kotlin
// Default: TList<File>.search { … } → Maybe<Entity<File>>
@JvmName("searchInferred")
inline fun <reified T : Data> TList<T>.search(
    noinline predicate: (T).() -> TBoolean
): Maybe<Entity<T>> = searchInternal(predicate)

// Narrowing: TList<*>.search<Folder> { … } → Maybe<Entity<Folder>>
@JvmName("searchNarrowed")
inline fun <reified T : Data> TList<*>.search(
    noinline predicate: (T).() -> TBoolean
): Maybe<Entity<T>> = searchInternal(predicate)
```

`@JvmName` disambiguates the two erased signatures on JVM. Kotlin's "more specific receiver
wins" rule routes `TList<File>.search { … }` to the default (inferred receiver-element);
explicit `<T>` ≠ the receiver element forces the narrowing overload.

`searchAll` is **unchanged** — it's the bulk path and stays `TList<*>.searchAll<T> { … }:
TList<T>` (the next step is usually `.select` anyway, which retypes appropriately).

Engine impact: none. The `SearchImplementation` already returns the same runtime values;
only the static signature changed.

### 2.2 `select` overloads on `TList` / `Maybe` / `Entity` (landed)

Three shape-preserving overloads:

```kotlin
fun <R : Data> TList<R>.select(…): TList<Entity<R>>      // original
fun <T : Data> Maybe<T>.select(…): Maybe<T>              // empty-as-value
fun <T : Data> Entity<T>.select(…): Entity<T>            // throw-on-empty
```

Each chain DSL extension calls `findOperation<XSelect<T>>(XSelect.ID)` and dispatches
through its respective RO (`ListSelectRo`, `MaybeSelectRo`, `EntitySelectRo`). Each RO
builds the same `Select`-shaped `OperationDto` onto the chain — only the *next* RO's
declared type differs (TList-shaped / Maybe-shaped / Entity-shaped via
`createRequestObject(type = …)`).

**Single engine impl**, three IDs. `ImplementationFactory` routes all three
(`Select.ID`, `MaybeSelect.ID`, `EntitySelect.ID`) to `SelectImplementation`.
`SelectImplementation.execute` dispatches on caller shape:

```kotlin
return when (caller) {
    is Maybe<*>  -> selectFromMaybe(executionContext, caller, selectorList)
    is Entity<*> -> selectItem(executionContext, caller as Data, selectorList)  // ← NEW
    else         -> sequence-of-items via resolveList(caller)
}
```

The `Entity<*>` branch is new in this redesign. Without it an Entity caller funneled into
`else → resolveList → null → emptyList()`, silently dropping the value.

**Shared `Select.Params.SELECTORS` key.** Initial attempts gave each schema its own
`$ID/selectors` param-id; at runtime `SelectImplementation` reads under
`Select.Params.SELECTORS`, the lookup missed, the impl returned
`Failure(InvalidCallerError)`, and `.fold(onFailure = { null })` silently masked it as
`null`. Fixed by sharing the param-id across all three schemas — found by tracing through
`ExecutionContext.execute` debug printlns. See `5bb41043` for the fix.

### 2.3 `TByteArray.getOrNull()` (landed separately)

```kotlin
interface TByteArray : TValue {
    fun get(): ByteArray
    fun getOrNull(): ByteArray? = try { get() } catch (_: IllegalStateException) { null }
}
```

Default routes through `get()` with a scoped catch. The cross-process consumer-side impl
(`ParcelFileDescriptorFileByteArrayDto`) overrides to short-circuit cache-miss without the
throw-then-catch round-trip.

Why a separate method instead of changing `get()`'s contract: the producer-side path
(inline `SimpleTByteArray`, the bytes are always present) should fail-fast on a real bug;
the consumer-side path (SHM handle evicted from the cache) needs the "this is normal,
return null" semantics. Two methods, two contracts, no behavioural surprises.

Consumed by `TenshuFilesRepository.getRemoteFile` / `getPreview` (replaces the
`try/catch IllegalStateException` blocks).

### 2.4 SHM-aware sync AIDL reply

⚠⚠ **This section said "parked / not implemented" until 2026-08-11, and that was wrong —
Phase 1 shipped.** Three code sites cite "`core-chain-entity-typing.md` §2.4 **Phase 1**"
(`ClientConnection.kt:28`, `AndroidClientConnection.kt:176`, `ProxyGateway.kt:153`) against a
section that defined no phases at all, so the cross-reference resolved to nothing. The split is
now written down.

**Phase 1 — LANDED.** `ClientConnection.tokenize(value: TValue): TokenString?` is the OOB seam
(`core-common`, default `null`), overridden in `AndroidClientConnection` to wrap byte payloads in
`ParcelFileDescriptor` + `SharedMemory`. `ProxyGateway.call`'s sync reply path consults the same
method, so byte payloads returned by a `.search.get()` chain no longer risk the parcel cap.

**Phase 2 — deferred, gated on observed heap pressure.** Desktop UDS (`SCM_RIGHTS` FD-passing or
a temp-file mmap) and JS (`postMessage` transferables). ⚠ The gate is deliberately *observed*
cost, not theoretical: build these when multi-GB payloads on those transports become a measured
problem. Also outstanding: the on-device 12 MB JPEG `.search.get()` smoke.

⚠ **The "Why parked" argument below is retained as the historical record of the Phase-2 decision,
but its premise no longer holds for Phase 1** — it argued the Binder-reply asymmetry should stay
asymmetric, and Phase 1 removed that asymmetry on Android. Do not read it as advice on which
chain shape to write today.

**Problem.** `BinderTaskTransport.call`'s reply parcel inlines `TByteArray` bytes. The
~1 MB Binder cap kills any sync chain that returns real file bytes. The stream transport
already solves this by wrapping byte payloads in `ParcelFileDescriptor` + `SharedMemory`
before they cross the IPC boundary.

**Sketch.** Lift the stream transport's PFD/SHM wrapping into `BinderTaskTransport.call`'s
reply encoder:

1. Reply-side encoder (proxy → client) walks the reply payload, replaces `TByteArray`
   inline-bytes values with `ParcelFileDescriptor` references backed by `SharedMemory`
   allocated from the proxy's per-client SHM pool.
2. Reply-side decoder (client) materialises each PFD reference as a
   `ParcelFileDescriptorFileByteArrayDto` (the existing impl from `:core-common/androidMain`)
   so consumer-side `.get()` / `.getOrNull()` behaviour is unchanged.
3. Lifecycle: the PFD is owned by the reply; the SHM region's reference count drops to
   zero when the client `.close()`s the PFD. Today's stream transport already has this
   machinery — `BinderTaskTransport` would borrow it.

**Threat-model touch points.** None expected — SharedMemory between the same paired
processes is no weaker than the Binder reply parcel they already share. A T-K-XX
amendment would document the new code path, but no new attack surface.

**Why parked.** The consumer-contract win from this redesign is the static typing — that
landed. The Binder-reply asymmetry stays asymmetric:

- Sync `.search.get()` is the right shape for "look up a single value" chains over
  *metadata* (small payloads, fits in the parcel).
- `.searchAll → .select → .flow().first()` is the right shape for "look up a single
  byte-bearing value" chains — the SHM-aware transport is exactly what we want.

The chain-shape distinction is documentation, not a smell — the 30-line comment block in
`TenshuFilesRepository.getRemoteFile` earns its keep. Collapsing to `.search.get()`
is a quality-of-life nicety that doesn't justify a Binder protocol change until a second
consumer wants it.

**Reopening criterion.** When a second byte-bearing chain wants the `.search.get()`
ergonomics, or when we want a streaming-free byte transport for the desktop / JS
runtimes (where the Binder workaround is meaningless anyway and the inline-bytes path
*would* work), reopen this section with a real ROI argument.

---

## 3. Consumer migration

One real consumer: `TenshuFilesRepository.getRemoteFile` + `getPreview` (`:drive-shared`).
The pre-redesign cast cascade collapses to direct member access:

```kotlin
// Before
is TResult.Success<*> -> {
    val list = result.value as? TList<*>
    val entity = list?.toList()?.firstOrNull() as? Entity<*> ?: return …
    val data = entity.getProperty<TByteArray>(File.Relation.Data.id.get())
    val bytes = data?.getOrNull()
    …
}

// After
is TResult.Success -> {
    val entity = result.value?.toList()?.firstOrNull() ?: return …
    val data = entity.getProperty<TByteArray>(File.Relation.Data.id.get())
    val bytes = data?.getOrNull()
    …
}
```

`firstOrNull()` now yields `Entity<File>?` statically, matching the runtime
`EntityDto<*>` class. The `Entity<*>` narrowing is gone.

**`control-shared` audit.** No equivalent pattern. `PairingWizardViewModel:186` casts to
`TBoolean`, not Entity — unrelated.

**Other Entity casts surveyed.** `MoveFileImplementation`, `DeleteFileImplementation`,
`JvmGetPreviewImplementation`, the `:file-service-proxy` test suite — none chain through
the redesign's path. They cast inside implementations that work on raw `Composite` data
already; leaving them alone is correct.

---

## 4. Static-typing pin test

`core-common/src/commonTest/.../ChainEntityTypingPinTest.kt`. Compile-time assertions on
the chain DSL's return shapes. Each `pin*` function declares the expected return type and
assigns the chain expression to it; if any step's static type regresses, the test binary
doesn't link.

Pinned shapes:

| Chain                                            | Return                          |
|--------------------------------------------------|---------------------------------|
| `TList<E>.search { … }`                          | `Maybe<Entity<E>>`              |
| `TList<*>.search<T> { … }`                       | `Maybe<Entity<T>>`              |
| `TList<*>.searchAll<T> { … }`                    | `TList<T>` (unchanged)          |
| `TList<R>.select { … }`                          | `TList<Entity<R>>`              |
| `Maybe<T>.select { … }`                          | `Maybe<T>`                      |
| `Entity<T>.select { … }`                         | `Entity<T>`                     |
| `searchAll → select`                             | `TList<Entity<…>>`              |
| `search → Maybe.select`                          | `Maybe<Entity<…>>`              |

Verified the pin catches regressions — temporarily mis-typing one pin's return reproduces
`Return type mismatch: expected 'X', actual 'Y'` from the compiler.

The single `@Test fun typingsPinnedAtCompileTime()` is a no-op marker so the pin surfaces
in the test report.

---

## 5. Test coverage

- **`ChainEntityTypingPinTest`** — compile-time pin (above).
- **`MaybeIntegrationTest`** (`:drive-shared:desktopTest`) — rewritten to exercise
  `search → Maybe.select → get`, reading the projected path via
  `Entity.getFieldValue<TString>`. Covers §2.1 + §2.2 runtime end-to-end.
- **`:file-service-proxy:jvmTest`** — existing chain integration tests
  (`ProxyFileServiceUnitTest`, `SearchIntegrationTest`, `ExecutionContextTest`) all green
  against the new typing; their internal `as Entity<out File>` casts inside test
  assertions are unchanged (different concern — tests on the engine internals, not the
  chain DSL).

---

## 6. Commit map

`feat/core-chain-entity-typing-redesign`:

| Commit       | Section | Summary                                                            |
|--------------|---------|--------------------------------------------------------------------|
| `8d1cb709`   | §2.1    | search overloads + searchInternal                                  |
| `fb37d7d3`   | §2.2    | `Maybe<T>.select` + `MaybeSelectRo`                                |
| `1a05ad7f`   | §2.2    | `Entity<T>.select` + `EntitySelectRo` + engine `is Entity<*>` branch |
| `5bb41043`   | §3      | `TenshuFilesRepository` migration + `Select.Params.SELECTORS` fix  |
| `d7aa8151`   | §4      | static-typing pin test                                             |

Separate, earlier change: §2.3 `TByteArray.getOrNull`.

---

## 7. Open / deferred

- **§2.4 SHM-aware sync AIDL reply** — parked. See §2.4 for sketch + reopening criterion.
- **Engine-side `tenshu/core/entity` RO factory.** The earlier attempt at
  `.search<Entity<File>> { … }` (referenced in pre-redesign comments) would have required
  registering a request-object factory for `tenshu/core/entity`. The redesign sidesteps
  this by returning `Entity<T>`-shaped ROs from the operation-specific RO factories
  (`MaybeSelectRo`, `EntitySelectRo`) rather than asking the engine to construct standalone
  Entity ROs. If a future chain operation needs to *originate* an Entity (not just preserve
  one mid-chain), the entity-factory registration would become necessary. Out of scope here.

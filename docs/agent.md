# Tenshu Platform: Architectural Conventions & Agent Knowledge

This document captures the core high-level architectural patterns of the Tenshu platform. Detailed
conventions are distributed across module-specific agent files.


## Errors: the proxy emits codes, the app writes the copy

**No proxy code ever creates a localised string.** A user-facing string is created on the app side
of the proxy boundary — at the layer where the ViewModels live, or above it. Below that line only
typed values travel. ([ADR 0016](adr/0016-localisation.md) anchor #4;
[`core-error-copy-catalogue.md`](proposals/core-error-copy-catalogue.md) §5.0.)

⚠ On Android this is a **process and package** fact, not a layering preference. Control is the
proxy; a feature app talks to it over Binder. The `*-service-proxy` modules load into *Control's*
process while `drive-shared-logic` renders in *Drive's* — so a sentence authored below this line is
one app writing another app's copy, across a package boundary, with two `strings.xml` catalogues
that can never agree. It is also invisible to us: only `InternalError.internalDetail` survives into
a recorded `ErrorEvent`, so freetext in a `GenericError` is simultaneously untranslatable and
unlogged.

The reviewer's test: **does this string cross a process/package boundary before a user sees it?**
If yes, it must be a typed value instead. If no, the proxy may localise it against its own
catalogue — Control's foreground-service notifications and `AndroidPendingRequestNotifier` do
exactly that, legally (anchor #5).

In practice:

- **Throwing.** Return a typed `TError`. ⭐ Check `InvalidParameter` first — it carries a parameter
  `name` and a wire-stable `reason` from a closed set, and covers most "an impl body rejected this"
  cases without a new composite or a House Rule 9 bump. A new composite is for a failure that
  genuinely is not a bad parameter (`PairingFailedError` is the one that earned it).
- **Carrying.** A ViewModel or repository puts an `ErrorCopyKey` in its `UiState`, never a
  `String`. `TError.errorCopyKey()` derives it from the error's schema id. Both live in
  **`:app-error-handling`** — an `app-*` family module, not `:core` (it is presentation
  vocabulary) and not `:ui-core` (the VM layer must stay Compose-free, and `:ui-core` has no `js`
  target).
- **Rendering.** `:ui-core`'s `ErrorCopyKey.label()` for the core taxonomy; an app chains its own
  keys in front (`ContactsErrorKeys` / `contactsLabel()` is the worked example). ⚠⚠ A catalogue
  miss renders a generic sentence, **never** the error's own message.
- **Three renderers.** Compose (`:ui-core`), SwiftUI (`tenshu-sdk-ios-core`'s `ErrorCopySurface`) and
  the web SDK all resolve the same derived key against their own catalogue. Adding an error means
  adding copy in each; `ErrorCopyParityTest` fails if they drift and if any `TError` in the tree has
  no entry at all.
- **`GenericError` is developer-only.** It stays for diagnostics and must never reach a user-visible
  surface. Same for `TError.messageOrNull()`, which feeds the IPC failure logs.

## 1. Module-Specific Knowledge

### Infrastructure Layers

* **[:ui-core](../ui-core/ui-agent.md)**: Common UI components, themes, layout utilities, and KMP
  navigation.
* **[:core](../core/core-agent.md)**: Type System, DSL Schemas, Composite Patterns, Identity.
* **[:core-common](../core-common/common-agent.md)**: DTOs, Binary Serialization & Parsing.
  See [Binary Protocol Specification](../core-common/binary-protocol.md) and
  its [Improvement Roadmap](proposals/protocol-improvements.md) for details.
* **[:core-client](../core-client/client-agent.md)**: Request Objects (ROs), RequestChain, Reactive
  Streams & Paging.
* **[:core-proxy](../core-proxy/proxy-agent.md)**: ExecutionContext, Task Execution, Dependency
  Management.
* **[:ui-map](../ui-map/ui-map-agent.md)**: Map surface composables and renderer election.
* **[:basemap-core](../basemap-core/basemap-core-agent.md)**: Basemap tile stack shared by the
  map surfaces.

### Feature Services

⚠ The platform has **20** feature services today. The four below are the ones with their own
`*-agent.md` conventions file; the rest follow the same layered pattern and are documented by
their `Service` KDoc plus the design doc it names. The orientation table for all of them is in
the [repository README](../README.md#feature-services); `settings.gradle.kts` is the full list.

* **[:notification-service](../notification-service/notification-agent.md)**: System events,
  alerting,
  and broadcast mechanisms.
* **[:file-service](../file-service/file-agent.md)**: Virtual file system, previews, and data
  transfers.
* **[:identity-service](../identity-service/identity-agent.md)**: Realm identity — the device
  roster, shared-realm members and memberships, pending grants, retained realm keys, and
  Publisher-signed entitlement records. ⚠ **Not user authentication**: there is no user
  directory, no password, and no OAuth anywhere in the platform. Identity is a keypair a device
  holds, acquired by pairing (own realm) or by the invitation handshake (someone else's).
* **[:sync-service](../sync-service/sync-agent.md)**: Data block synchronization between
  devices — push/pull of sync blocks, plus pairing, recovery, shared-realm grants and the
  background schedule. The module agent file maps the sync doc cluster
  ([Threat Model](sync/threat-model.md), [Sync Design](sync/design.md),
  [Pairing Flow](sync/pairing-flow.md), the relay proposals) and pins sync-specific
  conventions.

---

## 2. Service & Operation Pattern

The platform uses a strict separation between interfaces (definitions), DTOs (data transfer), and
ROs (Request Objects for client-side proxying).

* **Internal Implementations**: `OperationImplementation` implementations MUST always be marked as
  `internal`.
  They MUST NOT implement the domain `Operation` interface itself. They should be registered via an
  `OperationImplementationFactory`.

### Core Types

* **`TResult<T>`** (in `:core`): the **wire and executor** shape — a `UnionSchema` with two
  members:
    * **`TResult.Success<T>`**: Holds an optional `value: T?`.
    * **`TResult.Failure<T>`**: Holds an `error: Error`.
      Use the extension functions `fold()`, `get()`, `getOrNull()`, `onSuccess()`, and `onFailure()`
      to handle results ergonomically. `TaskExecutor.execute` / `executeAsync` return it, and it is
      what a cross-process caller sees on the reply path.

  ⚠⚠ **It is *not* what an operation implementation returns.** Phase 4 of
  [`core-error-channel.md`](proposals/core-error-channel.md) (Shape A, shipped 2026-06-02 across
  60+ impls) flipped that contract: **impls return `T` directly and throw a `TenshuException`
  carrying a typed [`TError`](proposals/core-error-channel.md) on failure.** A single catch in
  `LocalTaskExecutor` unwraps it into `TResult.Failure(error)` at the wire boundary, which is why
  the wire payload is unchanged and only the impl-side contract moved. Writing a new impl that
  returns `TResult.Failure` instead of throwing is the stale pattern — the executor will wrap your
  `Failure` in a `Success`.

* **`RequestChain`** (in `:core`): A builder interface used by RO implementations to assemble the
  operation callstack before it is serialized and sent over the transport. Use `addReference()`,
  `addArray()`, and `addNestedArray()` to describe the chain; `getCallstack()` returns the
  serialized `List<Data>`. Every RO factory receives a `RequestChain` and a `TContext` as
  parameters.

### Shortcut Operations

* **Shortcut Operations**: A special category of operations (marked with the `ShortcutOperation`
  trait, e.g., `ifEmptyError()`) that can halt the evaluation of a callstack.
    * **Behavior**: If the operation produces a `TResult.Failure` (e.g., because a `Maybe` was
      empty),
      the evaluation of the entire chain stops immediately and returns that failure. If it succeeds,
      evaluation continues to the next protocol in the callstack.
    * **Relationship to Terminality**: Shortcut operations are typically **Non-Terminal** when used
      within reactive flows, allowing the stream to "shortcut" dynamically based on the current
      state of the data.

### Composite Property Accessors

To ensure consistency across DTO and RO implementations, all property accessors using `getField`
MUST live in the interface definition itself. DTO and RO classes should be simple wrappers
around the raw field values or request chains.

**Rule**: Every `Composite` MUST be defined in its own file. Do NOT nest `Composite` interfaces
within other interfaces or classes.

**Rule**: Every `RequestObject` (RO) implementation MUST be defined in its own file. Do NOT nest
RO classes within other files. For operations belonging to a specific type (e.g., `Maybe` or
`TList`), ROs should be organized into a subpackage named after that type (e.g.,
`org.tenshu.core.common.impl.maybe`).

**Rule**: Operation ROs construct chains via `requestChain.addArray(...)` and MUST wrap the
resulting chain through `context.roRegistry().createRequestObject(...)` before casting to
`RequestObject`. `RequestChain` is **not** a `RequestObject`; casting one to the other is a
runtime error. See `:sync-service-client/.../ApprovePairingRequestRo.kt` for the canonical
shape.

**Rule**: All properties in a `Composite` interface MUST return Tenshu types (`TString`,
`TByteArray`, etc.) rather than primitive Kotlin types (`String`, `ByteArray`, etc.). The `.get()`
conversion should only happen at the consumption layer (e.g., ViewModels or UI).

Property accessors should be formatted with the `get()` on a new line for better readability:

```kotlin
interface MyType : Composite {
    val myField: TString
        get() = getField(Fields.MY_FIELD)
}
```

### ModuleConfiguration & Schema DSL

The Tenshu platform uses a self-describing type system defined via a custom DSL.

* **Conventions**: Detailed DSL patterns are documented in **[Schema DSL](schema-dsl.md)**.
* **Central Registry**: `ModuleConfiguration` (in `:core-common`) is the central registry for
  everything in a module. It is built using the `tModule { }` DSL and passed to the
  `SchemaRegistry` during initialization.

The DSL follows a **Semantic Overload** pattern, where the same function name is used for both
structural registration (blueprinting) and implementation attachment.

#### 1. Structural Registration (Blueprinting)

Used to inform the platform about a type's structure. These overloads take only a `Schema` object.

* `composite(schema)` — Registers a composite schema.
* `operation(schema)` — Registers an operation schema.
* `value(schema)`, `constant(schema)`, `union(schema)`, `service(schema)`, `trait(schema)`,
  `reference(schema)` — Category-specific metadata registration.
* `type(Data.Schema)` — Registers base types.

#### 2. Implementation Registration

Used to attach logic (DTO/RO factories) to a schema.

* `composite(interface, schema, dtoFactory, [roFactory])` — Attaches factories for data objects.
* `operation(interface, schema, [dtoFactory], [roFactory])` — Attaches factories for operations.
* `operation(baseTypeId, interface, schema, roFactory)` — **Discovery Registration**: Registers an
  operation as being available on a specific base type (e.g., `TList`). This also automatically
  registers the `roFactory` for direct creation.
* `value(...)`, `constant(...)`, `service(...)`, `trait(...)` — Attaches implementations for their
  respective categories.

#### 3. Composition & Relations

* `include(config)` — Composes another `ModuleConfiguration` into this one.
* **Properties** — declared as typed `Property<T>` handles: a typed handle from a
  composite to a value, the single source of truth across *declare / write / read /
  project / sort*. Static handles (`File.Size`, `File.Category`) wrap stored scalars
  or constants; dynamic handles (`File.Data`, `File.Preview`) are chain-computed,
  optionally parameterised. Rationale:
  [ADR 0013](adr/0013-property-authoring-unification.md).
* **Service-binding relations** — `serviceDataRelation(...)` binds a service to realm
  data (single entity or collection). Rationale:
  [ADR 0010](adr/0010-relation-system.md).

The full authoring reference — handle declaration/registration tables,
`serviceDataRelation` vs the lower-level `propertyRelation` helpers, the
`relations(...)` projection call sites, and the pinned renames
(`CallstackLinearizer` → `CallstackMaterializer`, `CallStack<T>` →
`ChainTemplate<T, R>`) — lives in **[Schema DSL §7 "Properties &
Relations"](schema-dsl.md)**.

**Note**: Registration order does NOT matter. The system uses a weighting mechanism to ensure the
most specific reference type is preserved regardless of registration sequence.

Each layer of a service (common, client, proxy) typically provides its own `tModule { }` block
that registers only the artifacts relevant to that layer (e.g. the client module registers RO
factories; the proxy module registers implementation factories).

### `ExecutionContext` and its collaborators

`ExecutionContext` (`:core-common/proxy/ExecutionContext.kt`) is the per-session container
every `OperationImplementation` receives. After the 2026-05-13 decomposition
([ADR 0008](adr/0008-execution-context-decomposition.md)), it's a thin orchestrator
(~200 lines) holding four constructor-injected collaborators — `ReferenceResolver`,
`StreamRegistry`, `ObserverRegistry`, `Database` — plus the operation-dispatch +
lifecycle methods.

The collaborator reference (what each owns, `withEvaluationScope` sharing semantics, the
`arrayReferenceOrNull` helper, and the decoupled outbound sync emit per
[ADR 0015](adr/0015-loop-db-stream-unification.md)) lives in
**[`core-proxy/proxy-agent.md` §2](../core-proxy/proxy-agent.md)**.

#### `TContext`'s two jobs — keep Job A, don't grow Job B

`TContext` does two distinct things on the proxy side:

- **Job A — uniform parameter shape.** Every `OperationImplementation.execute(...)` receives
  a `TContext` so operation code reads the same regardless of which platform's wiring
  constructed it (Koin on JVM, manual on tests, `JsRuntime` container on web). Schema DSL,
  request-object construction, and DTO materialisation all key off `TContext`. Load-bearing.
- **Job B — string-keyed DI map.** `TContext.components: Map<TComponent.ID, TComponent>` is
  a homegrown service locator: register by string id, look up by string id, runtime crash
  if missing. Doing what Koin already does.

**Rule**: keep growing Job A (uniform parameter for operation code, schema DSL, RO
construction). **Don't grow Job B.** New collaborators are constructor-injected — that's how
`ReferenceResolver` / `StreamRegistry` / `ObserverRegistry` are wired today, and how any
future proxy-side service should be wired unless code outside its wiring graph needs to look
it up by name. The bar for "should this be in `components`?" is "does foreign-module code
need to look me up by name without a wiring relationship with me?" If no, prefer constructor
injection.

The existing `components` entries (`OnDemandResolution`, `ExternalPropertyWriter`,
`SyncCryptoManager`, `BackgroundScopeProvider`, `ParcelFileDescriptorCache`,
`SharedMemoryCache`) stay for now and migrate opportunistically.

## 3. Trait Pattern

The platform uses a decoupled trait system to provide cross-cutting concerns (like data
identification) without hardcoding logic in core interfaces.

* **Split**: `TraitSchema` (metadata) lives in the API module; `Trait` (implementation) lives in
  the `-common` module. This mirrors the DTO split and lets client/proxy layers each register
  only what they need.
* **Dynamic Resolution**: Use `TContext.traitResolver.getTrait(typeId, traitId)` to retrieve a
  trait implementation. Use `DataSchema.hasTrait(traitId)` to check schema membership.

Schema definition (DSL rules for `trait(ID)` / `stringDataId(...)`), factory-based registration,
and the anonymous-object conventions for specialized trait requirements live in
**[`core/core-agent.md` §6–§7](../core/core-agent.md)** — the canonical home for trait-schema
authoring.

## 4. Client & Environment Strategy

The platform uses a strategy pattern to allow the same `TenshuClient` API to be used for both
local (in-process) and remote (IPC/Network) communication.

* **Concrete Gateway**: `TenshuClient` is a thin wrapper around `TenshuEnvironment`.
* **Execution Delegation**: `TenshuClient` delegates all task execution to a `TaskExecutor` provided
  by the environment.
* **Sync vs Async are two execution domains, not just function colors.** Design rationale +
  alternatives: [ADR 0011](adr/0011-task-executor-split.md). Two segregated interfaces:
    * **`TaskExecutor.execute(task): TResult<Data>`** — synchronous. Throws
      `SynchronousViolationException` if evaluation hits an interruption (IO gap). The "Sync-Safety"
      guard: logic marked as sync never triggers IO.
    * **`AsyncTaskExecutor.executeAsync(task): TResult<Data>`** (suspend, extends `TaskExecutor`)
      — drives the `AsyncReference` interruption loop, cooperates with the caller's
      `CoroutineContext` for cancellation.
* **TaskExecutor Types**:
    * **`LocalTaskExecutor`**: Used by proxy-side apps (e.g., `control`) to execute operations via
      their `OperationImplementation` against the `ExecutionContext`. Implements
      `AsyncTaskExecutor` — `execute` calls `evaluateCallstack(allowDeferring = false)` and throws
      `SynchronousViolationException` on an `AsyncReference`; `executeAsync` is the full loop that
      catches `AsyncReference` and `await()`s before resuming.
    * **`RemoteTaskExecutor`**: Base class for remote executors. Defines the `bind()`/`destroy()`
      lifecycle used by `TenshuClient`.
    * **`RemoteBinaryTaskExecutor`**: Subclass of `RemoteTaskExecutor`. Used by client-side apps
      (e.g., `drive`) to handle communication over a binary `TaskTransport` (e.g. Android Binder,
      Unix Sockets). Implements `AsyncTaskExecutor`. `executeAsync` serialises the task and sends
      it over IPC; `execute` attempts the task locally via `PureOperationRunner` — if every
      operation in the stack is a `PureOperation`, the call succeeds with zero IPC latency, else
      it throws `SynchronousViolationException`. Side-effect-free chains on remote clients
      short-circuit the round trip.
* **`SynchronousViolationException`** carries the operation ID that hit the async gap and an
  actionable message: either mark the operation with `IoOperation` (and use it via
  `TaskInteractor`), or the logic is incorrectly performing IO. The trait + interactor split is
  documented in §3 (Trait Pattern) and [ADR 0012](adr/0012-interactor-pattern.md) (Interactor Pattern, largely
  landed).
* **Transport Concurrency**: Binary transport implementations MUST ensure atomic request-response
  handling. For shared channels like Unix Sockets, the `call()` operation MUST be synchronized to
  prevent concurrent requests from interleaving data, which would cause threads to receive
  mismatched responses.
* **Side Channels**: Out-of-band data alongside the request stream (e.g. inbound stream elements
  proxy → client; outbound file uploads client → proxy) MUST be modelled as their own typed
  interface, never threaded through `TaskExecutor` or `RequestChain`. Two side channels exist
  today and set the pattern: `StreamElementDispatcher` (inbound; implemented by
  `DefaultInteractorProvider`, bridged from `RemoteBinaryTaskExecutor.streamElementHandler`)
  and `FileUploader` (outbound; production wrapper `TransportFileUploader` delegates to
  `TaskTransport.sendFile`). Operation ROs that need a side channel call it directly from their
  RO body before constructing the chain — see `:file-service-client/.../UploadSourceFileRo.kt`.
* **`TComponent` vs plain DI for those side-channel interfaces** — and for any platform service
  more broadly: make it a `TComponent` (registered on `TContext.components`, with a `COMPONENT_ID`
  and a `TContext.foo()` accessor extension) **only if a DSL extension, RO, operation
  implementation, or other code with no Koin reach needs to consume it**. If the only consumers
  are DI-module wiring code (`PlatformClientModule`, `ProxyModule`, `coreCommonModule`),
  register it as a plain Koin `single<>` with no `TComponent` ceremony. Concrete examples:
  `FileUploader` is a `TComponent` because `UploadSourceFileRo.uploadFile` calls
  `context.fileUploader()` from RO code; `StreamElementDispatcher` is *not* a `TComponent`
  because only the three transport-wiring modules ever resolve it. Don't cargo-cult the
  pattern — the components-map plumbing only earns its keep when there's a context-only
  consumer.

## 5. Remote Proxy Connection

To ensure a stable communication channel between client apps and the Tenshu Proxy:

* **Bidirectional Binding**: The connection follows a mutual-binding pattern.
    1. **Client -> Proxy**: The client app connects to the `ProxyService` (Android) or Unix Socket
       (Desktop).
    2. **Proxy -> Client**: The Proxy automatically connects back to the client's callback listener.
* **Standardized Android Service**: All Android client apps MUST expose exactly
  `org.tenshu.core.common.ClientCallbackService` in their manifest.
* **Lifecycle Management**:
    * **Initialization**: `TenshuClient.init()` triggers the `bind()` operation on the
      `RemoteTaskExecutor`, which establishes the transport and prepares background listeners for
      reactive streams.
    * **Destruction**: `TenshuClient.destroy()` explicitly unbinds/disconnects the service.
    * **Android Process Priority**: Mutual binding ensures both processes are given high priority by
      the OS, preventing the system from killing the client while active tasks are running.
* **Application Context Binding**: (Android) Service binding MUST be performed using the
  `ApplicationContext` to prevent memory leaks.

## 6. Shared App Module Conventions

Conventions that apply to the app modules above `:ui-core` — `:[app]-shared-ui` (Compose UI),
`:[app]-shared-logic` (the Compose-free view-model/repository layer, which is what makes an app
iOS-capable), and Control's older `:control-shared` / `:control-shared-logic` pair. UI
primitives (theme, components, layout, navigation host) live in `:ui-core`; see
**[`ui-core/ui-agent.md`](../ui-core/ui-agent.md)** for those. This section covers the patterns
that belong one layer up, where ViewModels, repositories, platform delegates, and screens live.

* **ADR**: Design system direction is in **[ADR 0001](adr/0001-design-system-direction.md)**.
* **Roadmap**: Execution plan is
  in **[Design System Improvement Plan](proposals/design-improvement-plan.md)
  **.
* **Audit**: Current state analysis is in **[Design System Audit](design/audit.md)**.

### "Screen vs. Content" Pattern (State Hoisting)

Every major UI screen MUST be split into two components:

1. **The "Screen" Composable (e.g., `FilesScreen`)**:
    * Responsible for Dependency Injection (receiving ViewModels and passing state down).
    * Collects state from ViewModels using `collectAsState()`.
    * Handles navigation side-effects and one-time events via `LaunchedEffect`.
    * Delegates all rendering to the corresponding "Content" composable.
    * **Rule**: ViewModels MUST be passed as parameters — do NOT call `koinViewModel()` inside
      Screen composables. The `koinViewModel()` call belongs in the platform entry point (e.g.,
      Activity/Fragment or a thin platform-specific Screen wrapper). This keeps Screen composables
      JVM-testable.
2. **The "Content" Composable (e.g., `FilesScreenContent`)**:
    * Stateless and pure.
    * Takes all necessary state (usually via a `UiState` DTO) and event callbacks as parameters.
    * **Rule**: Never inject ViewModels or perform side-effects here. This component must be easily
      usable in Previews and Tests.

### Compose Previews

Every "Content" composable and significant standalone UI component MUST have a `@Preview`. Use
`PreviewBox` from `ui-core/ui/preview/` rather than wrapping `TenshuTheme` + `Surface` by hand;
it handles theme, background, and size-class overrides in one helper.

See **[`ui-core/ui-agent.md` §5 and §6](../ui-core/ui-agent.md)** for the KDoc + `@Preview`
requirement, coverage expectations (light + dark + size-class variants), and the `PreviewBox`
API. Import the annotation as `androidx.compose.ui.tooling.preview.Preview` — but keep the
**JetBrains** `compose.preview` dependency; §10 below explains why those two halves disagree.

Previews live in the same file as the composable they visualize; provide realistic mock data
covering each meaningful state (default, selection, empty, error).

### Testing

Conventions for UI, integration, and operation tests (plus the `*-test-utils` pattern) live in
**[Testing Conventions](testing.md)**. That doc is the source of truth for test tiers, scoping,
and isolation rules.

### KMP Navigation Standard

Navigation uses `KmpNavHost` from `:ui-core`. Consumer API (`kmpEntryProvider` DSL,
`KmpNavEntry`, back-stack ownership) is documented in
**[`ui-core/ui-agent.md` §9](../ui-core/ui-agent.md)**.

Platform-level architecture, which stays in this doc because it affects how feature modules depend
on the navigation layer:

* **`expect`/`actual` split**: `KmpNavHost` is an `expect` composable with a platform-specific
  `actual` in each target.
    * **Android**: wraps Jetpack `NavDisplay` (Navigation3) to integrate with system back handling
      and lifecycle.
    * **Desktop**: custom `AnimatedContent`-based dispatcher for lightweight transitions without
      Android-specific dependencies.
* **JVM Testability Rule**: feature modules MUST use `KmpNavHost` — not `NavHost` or `NavDisplay`
  directly — so Screen composables stay JVM-testable without Android runtime shims.
* **Back-Stack Ownership**: the `backStack` is owned at the `App` level and passed into
  `KmpNavHost`. Hosts do not own navigation state; callers do. This makes back-stack behaviour
  trivially testable and consistent across platforms.

### Platform Delegate Pattern

To avoid over-reliance on `expect`/`actual` for platform-specific logic within shared components (
like Repositories), the platform uses a **Delegate Pattern** combined with Dependency Injection.

1. **Delegate Interface**: Defined in `commonMain` (e.g., `FilesRepositoryPlatformDelegate`).
2. **Platform Implementations**: Concrete classes in `androidMain` and `desktopMain` (e.g.,
   `AndroidFilesRepositoryPlatformDelegate`).
3. **DI Injection**: The common component (e.g., `TenshuFilesRepository`) receives the interface via
   its constructor.
4. **Platform Module Registration**: Each platform's Koin module (e.g., `platformClientModule`)
   registers its specific implementation of the delegate.

**Benefits**:

* Clearer dependency graph.
* Easier to mock in unit tests (just provide a fake delegate).
* Avoids "header" files (`expect`) and spread-out "implementations" (`actual`), keeping the core
  logic central and the platform specificities contained.

### Error Handling in Repositories

When mapping from `TResult` to `RepositoryResult`:

* **DTO Pattern**: Thanks to the DTO pattern, check for specific errors using `is` (e.g.,
  `error is AlreadyExistsError`).
* **Manual ID Check**: Manual schema ID checks (e.g.,
  `error.schema.id.get() == AlreadyExistsError.ID`) are generally NOT necessary if the error types
  are correctly registered in the module configuration.

## 7. Dependency Injection (Koin) Conventions

To maintain a clean and platform-independent dependency graph:

### ⚠⚠ An app must announce at **launch**, not on first use

A first-party app becomes visible and manageable in Control when its client opens a session: the
announce is what Control's `appRegistrationTrigger` fires on. But `wireSchemaAnnounce` only
*installs* `RemoteBinaryTaskExecutor.sessionPreflight`, which rides the session's **first call** — so
an app whose opening screen issues no operation never announces, never registers, and is **invisible
in Control** while its transport sits there connected, looking healthy from every outside angle.

Mia shipped exactly that: installed, signed, resolved by the catalogue and answering correctly, yet
absent from Control's Apps list until its first message
([`tech-debt.md`](tech-debt.md), 2026-08-14; the contract is
[`control-app-registration.md`](proposals/control-app-registration.md)).

**So a new app owes one of these**, and `scripts/check-launch-registration.py` enforces it:

- pass `launchScope` to `wireSchemaAnnounce` — it announces once, fire-and-forget and swallowing
  (an unreachable proxy must not fail a launch, and the preflight still covers the first call);
- or announce from something the app builds at launch, as Mia does from its root view model.

⚠ Registration is about the app's **identity and permissions**, not about whether the user has got
round to using it — which is why the trigger must not be gated behind a screen, an onboarding branch,
or a first tap.

⚠ Note the seam is *per app*: `wireSchemaAnnounce`'s `appId` must match the `BundledAppCatalogue`
entry, and on desktop the catalogue pins the **code-signing identifier**, never the Android package.

### Modular DI Pattern

DI modules MUST be split into common and platform-specific parts to avoid `expect`/`actual` for
simple dependency provision.

1. **Common Module (e.g., `tenshuClientModule`)**:
    * Defined in `commonMain`.
    * Contains logic that is identical across all platforms.
    * Retrieves platform-specific components via `named` qualifiers or shared interfaces provided by
      the platform module.
2. **Platform Module (e.g., `platformClientModule`)**:
    * Defined separately in `androidMain` and `desktopMain` (and other platform source sets).
    * Provides the concrete implementations for transport, persistence, or platform-specific
      services.
    * Registered manually during Koin initialization in the Application class or Test setup.

### Example Initialization

```kotlin
// In Android Application class
startKoin {
    androidContext(this@MyApplication)
    modules(commonModule, platformModule, ...)
}

// In JVM Integration Test
startKoin {
    modules(commonModule, mockPlatformModule, ...)
}
```

## 8. Modularization & Project Structure

The platform is modularized to separate core logic, service definitions, and side-specific
implementations.

### Module Hierarchy

#### Platform Core

* **`:core`**: Absolute root interfaces (`Data`, `Composite`, `Service`) and schema DSL. No
  side-specific dependencies.
* **`:core-common`**: Common DTO base classes and basic serialization logic.
* **`:core-client`**: Client-side specific extensions and configuration.
* **`:core-proxy`**: Proxy-side specific extensions and configuration.
* **`:core-paging`**: Paging primitives shared by the client and UI layers.
* **`:core-aidl`**: Android Binder interface definitions.
* **`:core-test-utils`**: Shared testing infrastructure.
* **`:viewmodel-test-utils`**: `OwnedViewModels` — the fixture every view-model test builds through,
  so no scope outlives its test. Conventions and traps: [Testing](testing.md) §11.
* **`:diagnostics`**: The device-local, single-stream diagnostic log (breadcrumbs and crashes on
  one stream). Distinct from `:logging-service`, which is the synced typed-error surface.

#### Feature Services (Concrete Examples)

Three of the twenty, to show the shape — the full table is in the
[repository README](../README.md#feature-services).

* **`:notification-service`**: Service-specific interfaces and schemas.
* **`:file-service`**: Core file system logic.
* **`:sync-service`**: Data block synchronization between devices, plus pairing, recovery,
  shared-realm grants and the background schedule. Operations include `PushBlock`, `GetChanges`,
  `PushTombstone`, `SyncNow`, `InitiatePairing`, `RecoveryClaim`, `GrantSharedRealmMembership`.
  ⚠ `AcknowledgeBlock` and `FetchOnDemandProperty` no longer exist — on-demand content is served
  through the `OnDemandResolution` component and the relay's content plane
  ([`sync-relay-api.md`](proposals/sync-relay-api.md)), not an operation.

#### Layered Implementations (Per Service)

* **`:[service]-common`**: Service DTOs (depends on `:[service]` and `:core-common`).
* **`:[service]-client`**: Service ROs (depends on `:[service]-common` and `:core-client`).
* **`:[service]-proxy`**: Service Implementations (depends on `:[service]-common` and
  `:core-proxy`).
* **`:[service]-test-utils`**: Service-specific test helpers.

## 9. Package & Namespace Conventions

* **DTOs**: `org.tenshu.[service].common.impl.[type]`
* **ROs**: `org.tenshu.[service].client.impl.[type]`
* **Implementations**: `org.tenshu.[service].proxy.impl` or `org.tenshu.[service].proxy.executor`
* **Test Utils**: `org.tenshu.core.common` or `org.tenshu.[service].test`
* **Android Resources**: Use unique namespaces in `build.gradle.kts` (e.g.,
  `app.tenshu.drive.android`).

## 10. Environment & Versioning

To ensure build stability and consistent test execution across JVM and Android:

Versions are centralised in `gradle/libs.versions.toml`; that file is the source of truth and
this section only records the constraints behind it.

* **Compose Multiplatform (CMP) Version**: **1.12.0**, paired with `compose-bom` **2026.08.00**
  (bumped 2026-08-26). The two move together: BOM 2026.08.00 carries `androidx.compose` 1.12.0 and
  CMP 1.12.0 wraps that same release, so bumping one alone splits the Android Compose runtime from
  the multiplatform one. The old 1.7.3 pin was lifted on 2026-07-08 once AGP 9 support landed in
  CMP 1.9.3 ([`cmp-version-upgrade.md`](proposals/cmp-version-upgrade.md)).
    * ⚠⚠ **Do NOT re-introduce the 11 Compose `force()` / `exclude()` resolutionStrategy blocks
      or the 6 `fixComposeResourcesForAndroid` sync tasks.** They were the 1.7.3 workaround and
      are actively harmful at 1.9.x+, where they strip the real `androidx.compose.*` classes.
      This is also a AGENTS.md build-section rule.
* **Material3 Alignment**: a *narrow* `resolutionStrategy` in each Android app module still
  pins `androidx.compose.material3:material3{,-android}` to `libs.versions.material3`, so the
  mangled method signatures match between shared-code compilation and the target runtime. ⚠ This
  is the one surviving `force()` and is unrelated to the banned Compose-graph blocks above —
  don't delete this one while removing those, and don't cite it as licence to add more.
* **Preview Annotation**: import **`androidx.compose.ui.tooling.preview.Preview`** (flipped
  across all 37 call sites in the 2026-08-10 dependency sweep).
    * ⚠⚠ **The annotation namespace and the artifact deliberately disagree, and the obvious
      "fix" breaks the build.** The dependency stays the JetBrains
      `compose.preview` (`org.jetbrains.compose.ui:ui-tooling-preview`), *not*
      `androidx.compose.ui:ui-tooling-preview` — the androidx artifact publishes no
      iOS/js/wasm targets, so it cannot back a `commonMain` preview; it compiles on Android and
      breaks every other target. There is no duplicate-class risk: the JetBrains artifact's
      android variant ships zero files and just depends on the androidx one. The two halves are
      one change — see [Tech Debt](tech-debt.md), *"CMP Version Pin"*.
* **Kotlin Version**: **2.4.10**, with AGP **9.3.1** and Gradle **9.7.0**. Synchronize Kotlin
  versions across all modules to prevent `LinkageError` during Koin initialization or Compose
  compiler execution.
    * ⚠ **A Kotlin bump is gated on SKIE**, the Swift/Kotlin bridge applied to every
      `:tenshu-sdk-ios-<app>` framework. It is compiler-version-coupled and the repo pin sits at the top
      of its supported range, so the next Kotlin feature release needs a SKIE release first. See
      [Tech Debt](tech-debt.md), *"SKIE ⨯ Kotlin version pin"*.
* **Android SDK**: `compileSdk` **37**, `targetSdk` **36**, `minSdk` **24**. The compileSdk bump
  was forced by `androidx.core` 1.19.0 / `androidx.lifecycle` 2.11.0 declaring a 37 floor in
  their AAR metadata; without SDK Platform 37 installed, Android modules fail at
  `checkDebugAarMetadata` naming a *dependency* rather than the missing platform.

---

## 11. Core Interface Purity & Context Isolation

To maintain a clean and platform-independent core, we follow a strict isolation policy for context
objects:

* **No Context in Core Interfaces**: Interfaces defined in `:core` (e.g., `Operation`,
  `DataIdProvider`, `Trait`) MUST NOT include `TContext` or its `components` (TComponents) in
  their method signatures. This prevents core domain logic from becoming coupled to the platform's
  execution state.
* **TComponents and Service Managers**: `TComponent` is intended for *internal* platform
  infrastructure (e.g., `DataFactory`, `TraitResolver`). Domain-level service managers (like
  `SyncManager` or `FileManager`) SHOULD NOT be exposed as `TComponents`. Instead, they should be
  internal to their respective proxy modules and accessed only via `Operation`s through the
  `TenshuClient`. The positive test for whether something *should* be a `TComponent` lives in
  §4 ("`TComponent` vs plain DI") — that section is canonical; don't restate the test here.
* **Constructor Injection via Factories**: If an implementation requires access to platform services
  (TComponents) or the execution context, it should receive them via its constructor. This is
  typically handled by Trait or Implementation factories which are provided with `TContext` during
  instantiation.
* **Execution strategies & scopes live with the proxy.** The trait-driven execution modes
  (`SyncOperationImplementation` / `IoOperation` / `BackgroundOperation`), the
  `evaluationScope` / `sessionScope` structured-concurrency split, the Pure-vs-Proxy
  implementation distinction (favor Pure wherever possible), and `PureOperationRunner` are
  documented in **[`core-proxy/proxy-agent.md` §3](../core-proxy/proxy-agent.md)**.
* **DSL Extension Functions**: `TContext` and `TComponents` are primarily intended to be used
  within DSL-style extension functions or higher-level orchestration logic. These functions act as
  the bridge between pure domain objects and the platform's runtime environment.
    * **Example**:
      `fun File.getData() = (operations.first { it.id == GetData.ID } as GetData).getData(this)`
* **Encapsulation**: Domain objects should expose what they *can* do (via interfaces and operations)
  without exposing *how* the platform executes those actions or what context is required to
  perform them.
* **TList for Component Properties**: Properties of Tenshu components (like schemas or traits)
  that represent collections MUST return `TList<T>` instead of Kotlin `List<T>`.
    * **Rationale**: This ensures that the collections are themselves part of the Tenshu type
      system and can be handled uniformly by the platform's serialization and proxying mechanisms.
    * **Usage**: Callers who need a standard Kotlin list should explicitly call `.toList()` on the
      property (e.g., `schema.traitRequirements.toList()`).
* **TContext Component Access**: Accessing core components from `TContext` MUST always be done via
  the provided extension functions (e.g., `tContext.dataFactory()`) instead of manual map access
  (e.g. `components[...]`). This ensures type safety and a cleaner API.

## 12. Project-wide Naming Conventions

* **`T`-Prefix for Core Types**: The platform uses a `T` prefix for core types that clash with
  standard Kotlin/Java classes. This is strictly limited to:
    * **`TString`** (Tenshu version of `String`)
    * **`TInteger`** (Tenshu version of `Integer` / `Long`)
    * **`TBoolean`** (Tenshu version of `Boolean`)
    * **`TByteArray`** (Tenshu version of `ByteArray`)
* **No `T` prefix for other types**: Do NOT use the `T` prefix for other domain types (e.g.,
  `UnixInstant`, `File`, `Notification`) unless they also clash with standard language primitives or
  built-ins.
* **Implementation Naming**: DTO and RO implementations MUST always follow the pattern:
  `[InterfaceName]Dto` or `[InterfaceName]Ro`. If the interface has a `T` prefix, the implementation
  MUST also carry it (e.g., `TStringDto`, `TBooleanRo`). This ensures perfect parity between the
  domain definition and its transport/proxy variants.

## 13. Explicit Context Injection

* **No Global State**: All operations requiring platform components (like creating a `DataId` or
  `Entity`) MUST receive a `TContext` explicitly.
* **Context Inheritance**: Most client-side proxy objects (like `RequestObject` or `TList`
  implementations) implement `TContext`. DSL extensions should retrieve context via an internal cast
  (`this as TContext`) to keep the public API clean.
* **Stateless Fallbacks**: Core extension functions (like `toTString()`) and reference factories
  now return stateless `Simple...` implementations if the provided context is null.

## 14. Entity vs. Snapshot Pattern

The platform distinguishes between **DTOs** (plain `Composite`s carrying schema fields) and
**Entities** (wrappers that add a `properties` map for dynamically-fetched extras). Whether a list
emits DTOs or entities is determined by the schema's item type — `isEntity = true` iff the item
type is a subtype of `Entity.ID`.

Cross-cutting rules:

* `Select` is the canonical gateway to entities. Its schema returns `TList<Entity<Data>>`.
* Field access on entities uses `getFieldValue(fieldId)` for domain data and `getField(fieldId)`
  for entity-metadata fields. Domain extensions SHOULD be declared on both `T` (DTO) and
  `Entity<out T>` (wrapper) — the Dual Extension Pattern.
* `ArrayDatabaseReference.isEntity` MUST match the schema's item type; a mismatch silently breaks
  database updates.

Full detail — `isEntity` heuristic, DTO preservation rules, Dual Extension Pattern, and
`getProperty()` for wrapper-only properties — lives in
**[`core/core-agent.md` §8](../core/core-agent.md)**.

## 15. Identity Rooting

All entities within a shared identity space MUST use a unified "Identity Root" type ID for their
`DataId`s (e.g., both `File` and `Folder` use `File.ID`). See
**[`core/core-agent.md` §9](../core/core-agent.md)** for rationale and concrete rules.

## 16. Field Access Conventions

To enforce the schema's nullability contract at runtime:

* **`getField(fieldId)`**: mandatory (non-optional) fields only. Returns non-nullable `T`; throws
  if the field is missing or marked optional.
* **`getOptionalField(fieldId)`**: optional fields only. Returns nullable `T?`. Calling on a
  mandatory field is prohibited.

Use these rules on every `Composite` — this keeps the schema contract load-bearing and prevents
over-reliance on null-checking for data that is guaranteed by the contract.

## 17. Self-Describing Type System (Bootstrap)

Schemas are themselves `Composite` objects, which creates a "Bootstrap Problem" during
initialization (a schema needs its schema to resolve fields). The load-bearing rule:

* **`LazyComposite` is stateless; `SchemaRegistry` owns per-context caching.** New
  `LazyDataCreator` implementations MUST NOT cache materialised state on the instance.
  The canonical way to obtain a fully materialised schema is
  `schemaRegistry.getOrCreate(schema)`, NOT `schema.materialize(context)` directly.

The full reference — bootstrap recursion-breaking rules (fast-path metadata, index-based
schema DTOs, specific Type IDs, registration parity), the `lazyFields` map contract, and
the schemas-as-composites rules — lives in
**[`core/core-agent.md` §10](../core/core-agent.md)**.

## 18. Union Types & Virtual Sum-Types

The platform supports formal sum-types through `UnionType` (generic variants in field
definitions) and `UnionSchema` (named dispatcher schemas like `TResult` = `Success` |
`Failure`; the union's own ID is never written to the wire). Serialization/dispatch
behaviour and the reified `parse<T>` API live in
**[`core-common/common-agent.md` §4](../core-common/common-agent.md)**.

---

## 19. Localisation

User-facing strings are localised. The rationale and the long-form alternatives are in
[ADR 0016](adr/0016-localisation.md); this section is the authoring reference.

### Catalogue ownership

Strings live where the UI that renders them lives. **Service modules are stringless**
(`:identity-service*`, `:file-service*`, `:notification-service*`, `:sync-service*`,
`:preference-service*`) — they produce typed values that higher layers render.

| Surface                                | Catalogue location                                          |
|----------------------------------------|-------------------------------------------------------------|
| Shared UI primitives (CMP)             | `:ui-core` → `composeResources/values/strings.xml`          |
| App-specific UI (CMP)                  | `:[app]-shared-ui` → `composeResources/values/strings.xml`  |
| Proxy-rendered UI (Control FGS etc.)   | `:control-shared` → `composeResources/values/strings.xml`   |
| Web apex / per-app web packages        | `web/[package]/locales/<lang>/*.json`                       |

Each pnpm-workspace package under `web/` initialises its own `react-i18next` instance.
No shared JS i18n provider across packages — the i18n boundary follows the SDK boundary.

### KMP authoring

```kotlin
@Composable
fun CancelButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(stringResource(Res.string.action_cancel))
    }
}
```

**Rule**: Every new user-facing string in a `@Composable` MUST be authored via
`stringResource(Res.string.<key>)`. Hard-coded English literals are caught in review and
extracted before merge.

**Rule**: Each module that owns strings adopts the
`org.jetbrains.compose.components.resources` Gradle plugin and places its catalogue in
`commonMain/composeResources/values/strings.xml`. Per-locale variants land in
`composeResources/values-<lang>/strings.xml` at the time the second locale is added — not
pre-emptively.

⚠ **Escape gotcha.** Write a bare `'` in a Compose `strings.xml`, **not** the escaped `\'` that
Android resource files normally require, and `“…”` (or a bare `"`) rather than `\"` — the Compose
Multiplatform pipeline decodes only `\n`, `\t`, `\uXXXX` and `\\`; every other escape reaches the
screen as a literal backslash. Verified against the CMP 1.12.0 plugin source
(`PrepareComposeResources.handleSpecialCharacters`) after Drive's "File already exists" dialog
shipped rendering `\"report.pdf\"`. ⚠ **Specifier gotcha**, same pipeline: the runtime substitutes
only `%N$s` and `%N$d` — a bare `%s`/`%d` or any other conversion is shown verbatim. Both rules are
gated by `StringResourceFormatTest` in `:ui-core`, which scans every `composeResources` catalogue
in the tree; Android `res/values` files are exempt (they go through `aapt` + `String.format`).

### Web authoring

```tsx
import { useTranslation } from "react-i18next";

export function CancelButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation();
  return <button onClick={onClick}>{t("action.cancel")}</button>;
}
```

**Rule**: Every new user-facing string in a React component MUST be authored via `t(...)`.
JSX literals are caught in review.

**Rule**: Each `web/` package owns a `locales/<lang>/<namespace>.json` tree and
initialises its own i18next instance at package entry (`apex/src/i18n.ts` and analogues).

### String keys and ICU MessageFormat

**Rule**: Keys are lowercase dot-separated, scoped by surface and then function:
`action.cancel`, `error.recovery.invalid_phrase`, `onboarding.step.intro.title`. KMP
`strings.xml` uses the same key shape (translated to snake_case where the XML schema
forces it), so the two trees line up for the eventual shared-catalogue migration
([ADR 0016](adr/0016-localisation.md) anchor #8).

**Rule**: Plural / select / number formatting uses ICU MessageFormat (or the closest
dialect each side supports). Even at n=1 locale, structural information lost to a bare
string cannot be recovered without re-authoring every call site.

### Cross-process error rule

**Rule (load-bearing)**: Anything that crosses the proxy ↔ client boundary as a
user-facing error or status MUST travel as a **typed code**, not a free-text `String`.
Use a `TError` subtype, a named operation result, or a typed status composite (existing
platform pattern — see `InvalidCallerError` and `MissingParameter` in `:core`,
`RelayRouteUnsupportedError` in `:sync-service`, `EntitlementUnverifiedError` in
`:identity-service`). ⚠ There are no types called `IdentityError` or `SyncError`; the taxonomy
is per-error, namespaced under `tenshu/<realm>/error/…`
([`core-error-channel.md`](proposals/core-error-channel.md)).

The client module that catches the typed value maps it to a localised string via its own
catalogue. The proxy does not know the user's locale; the client does not inherit the
proxy's catalogue. This preserves the existing proxy-client isolation boundary.

Free-text `String` fields are permitted only for developer-facing diagnostics that a
user will never see (log lines, internal trace data). The test is *"will a user see
this?"* — if yes, it's a code.

### Proxy-rendered UI catalogue

Surfaces rendered by the proxy process itself (Control's foreground-service notification
is the canonical case) are localised by the proxy's own catalogue, hosted alongside the
proxy module's UI surface (today `:control-shared`'s
`composeResources/values/strings.xml`). The service modules stay stringless even for
proxy-emitted surfaces; the rendering module owns the catalogue.

### Locale source

Day one, every platform reads the OS / browser locale (Android `Configuration`, Compose
Desktop `Locale.getDefault()`, browser `navigator.languages` via i18next's
`LanguageDetector`). An in-app locale override, when built, is a `DeviceSetting` on
`:preference-service` (per-device, not synced — locale is a UI preference, not data).
Don't build the override surface until somebody asks for it.

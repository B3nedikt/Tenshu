# Tenshu Platform: Client & Proxy Object Conventions

This document captures the conventions for Request Objects (ROs) and reactive streams.

## 1. Pattern Reference

See **[How-To: Adding Composites and Operations](../docs/how-to/add-composites-and-operations.md)**
for conventions on defining Request Objects (ROs).

## 2. Reactive Stream Mechanism

### Lifecycle of a Stream

1. **Initiation**: Calling `.flow()` triggers a terminal `stream()` operation. `StreamRo` generates
   a `streamId` and registers a `StreamInteractorImpl` with the `InteractorProvider`
   (interface in `:core`, `DefaultInteractorProvider` in `:core-common`). ⚠ There is no
   `StreamInteractorProvider`; that name resolves to nothing.
2. **Proxy Setup**: `ListStreamImplementation` receives the request and sets a database listener.
3. **Data Transmission**: The proxy sends `StreamElement.Value` (addition/update) or
   `StreamElement.Removed`.
4. **Client-Side Multiplexing**: `InteractorProvider` MUST reuse existing
   `StreamInteractorImpl` instances for identical `streamId` values.
5. **Reconstruction**: The client-side `.flow()` helper maintains a local list synchronized with the
   proxy's state.
6. **Termination**: Can be client-initiated (ref count zero) or proxy-initiated (End element).

## 3. Reactive Paging (Paging 3 Integration)

* **Granular Invalidation**: `TenshuPagingSource` (in **`:core-paging`**, not `:core-client`)
  should open a specific reactive `flow()` for each requested page range (`drop(n).take(m)`).
* **Proxy Efficiency**: The `ExecutionContext` on the proxy side is responsible for diffing and only
  emitting if that specific range changes.
* **Lifecycle**: `PagingSource` manages its own `CoroutineScope`. Use `registerInvalidatedCallback`
  to cancel background jobs immediately.
* **Scroll restoration**: `cachedIn` is permitted again (`core-paging-scroll-restoration.md`,
  SR-1…SR-5). ⚠⚠ Three reactivity guards are the live contract —
  `FilesPagingReactivityUiTest`, `HistoryFeedReactivityTest`, `EventsFeedReactivityUiTest`.
  If one goes red, put `cachedIn` away rather than weakening the test.

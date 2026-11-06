# Implementation Tracks — Control Apps / Data / Shared-Realm Sharing

Three self-contained tracks, written to be picked up by Claude Code and run **in parallel**.
Each track says exactly what to build in Kotlin (`control-shared` + the relevant feature
service), what to render on web (`web/apex`), and which **`tenshu-rei.md`** edits land in the
*same* PR (per House Rule #2: doc amendments ship with the code).

| Track | File | Theme | Core dep |
|-------|------|-------|----------|
| **A** | [`track-a-control-apps.md`](track-a-control-apps.md) | Control → **Apps** UI (list + detail + permissions + uninstall) | `control-app-registration.md`, `tenshu-rei.md` §2.2/§3.3 |
| **B** | [`track-b-control-data.md`](track-b-control-data.md) | Control → **Data** UI + `core-realm-data` trait machinery | `core-realm-data.md`, `tenshu-rei.md` §2.4 |
| **C** | [`track-c-shared-realm-sharing.md`](track-c-shared-realm-sharing.md) | Control UI for **shared-realm file sharing** (shares + members + revoke) | [`docs/file-sharing.md`](../file-sharing.md) + [ADR 0018](../adr/0018-file-sharing-mode-a.md), `tenshu-rei.md` §2.3/§5.C |

⚠ **A second track lives here and does not parallelise:**
[`track-identity-and-reference.md`](track-identity-and-reference.md) sequences the entity-identity
and content-plane-refcount cluster, where each phase removes a reason the next one is unsafe. Read
it as an ordering, not a division of labour.

## How they parallelise

```
        ┌─────────────────────────────────────────────────────────┐
        │  Shared foundation (do once, any track may land it)      │
        │  • Control IA section scaffold pattern (Destination +    │
        │    entry<> + tab/back-stack wiring in App.kt)            │
        └─────────────────────────────────────────────────────────┘
                 │                  │                   │
        ┌────────▼──────┐  ┌────────▼───────┐  ┌────────▼─────────┐
        │  TRACK A      │  │  TRACK B       │  │  TRACK C         │
        │  Apps         │  │  Data          │  │  Shared sharing  │
        │  (own service │  │  (core trait + │  │  (file-service   │
        │   + schema)   │  │   File decl)   │  │   share/* )      │
        └───────────────┘  └────────────────┘  └──────────────────┘
```

The three tracks touch **disjoint** Kotlin packages and **disjoint** web routes, so they can
run in separate worktrees and merge independently:

- **A** → `*/control/.../screens/apps/**`, new `:rei-service*` (or `:core` schema for
  `AppRegistration`), `web/apex/src/control/apps/**`, route `/control/apps`.
- **B** → `:core` + `:core-common` trait files, `:file-service` File decl,
  `*/control/.../screens/data/**`, `web/apex/src/control/data/**`, route `/control/data`.
- **C** → `:file-service*` `share/**` (continues the `drive-file-sharing-p1` worktree),
  `*/control/.../screens/sharing/**` (or Data → Shares subsection),
  `web/apex/src/control/.../shares/**`.

### The only shared edit points (coordinate / expect trivial merge conflicts)

1. `control-shared/.../ui/navigation/Destination.kt` — each track adds its own
   `@Serializable data object` destinations. Append-only; conflicts are mechanical.
2. `control-shared/.../ui/App.kt` — each track adds `entry<Destination.X>{}` blocks and
   (Track A & B) a top-level tab. Append-only.
3. `control-shared/.../di/ViewModelsModule.kt` + `di/ProxyModule.kt` — each track adds its
   own `viewModel { }` / `single<X> { }` lines. Append-only.
4. `docs/proposals/tenshu-rei.md` — A edits §2.2/§3.3, B edits §2.4 (small), C edits
   §2.3/§5.C. Disjoint sections.
5. `docs/sync/threat-model.md` — A adds a T-C amendment, C amends A12/A13 + T-SR-*. Disjoint.

> Recommendation: land the **shared scaffold pattern** (the "add a Control section"
> cheat-sheet, already established by `settings/backgroundsync`) as the first commit in
> whichever track starts first, then the other two rebase on it.

## Conventions every track follows

- **Screen split:** `XScreen(vm, callbacks…)` → `XScreenContent(state, callbacks…)` so the
  content is testable with `createComposeRule()` and no Koin.
- **VM:** `data class XUiState`, `MutableStateFlow` → `asStateFlow()`, optimistic update then
  `viewModelScope.launch { … }`. No `suspend` on VM-facing client interfaces.
- **Operations stay non-suspend at the interface** (House Rule #3) — IO goes through
  `IoOperation` + `TaskInteractor`.
- **Schema-first** for any wire-serialisable type (House Rule #4).
- **Relay sees ciphertext only** (House Rule #1) — load-bearing for Track C.
- **Verify with tests, not prose** (House Rule #7). Each track lists its gradle gates.
- Build env has **no Android SDK** — verify on `:control-shared:desktopTest` +
  `:*-proxy:jvmTest` + `:control-desktop:compileKotlinJvm`; mark Android wiring "unverified".

# ADR 0016 — Localisation: CMP resources + react-i18next, ICU MessageFormat, proxy emits codes

- **Status:** Accepted; **implemented**. ⚠ This said "no code yet" until 2026-08-11 while the
  rollout had long since landed: `web/apex` + the KMP shared modules are fully localised at n=1 —
  Phases 1 / 1b / 1c (apex) + 2a / 2b (KMP) + 2c (typed VM errors in four control-shared
  ViewModels), all on master. The **render** half of anchor #4 — a typed
  error becoming localised copy — was built in 2026-08 by
  [`core-error-copy-catalogue.md`](../proposals/core-error-copy-catalogue.md): derived
  `ErrorCopyKey`s, a catalogue in `:ui-core` / `tenshu-sdk-ios-core` / the web SDK, and every
  app-module read of `GenericError.message` removed. The authoring rule now lives in
  [`docs/agent.md`](../agent.md). The n=2
  trigger (ICU JSON catalogue + Gradle codegen) is planned but unbuilt — see *When to revisit*.
- **Date:** 2026-05-31
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —
- **Depends on:** house rule #5 (web is TypeScript + React, not CMP Web —
  [`docs/web/architecture.md`](../web/architecture.md) §12.5); the proxy ↔ client
  isolation rule that already forbids client-supplied UI in proxy-rendered
  surfaces (Control's FGS notification)

## Context

Tenshu has no localisation today; every user-facing string is hard-coded
English. The platform spans two execution surfaces that don't share a UI
toolkit:

- **KMP apps** — Android + Desktop today; iOS planned. UI is Compose
  Multiplatform (pinned to 1.7.3 — see `docs/tech-debt.md` "CMP Version Pin").
- **Web** — TypeScript + React, per house rule #5. The KMP → JS SDK
  (`:tenshu-sdk-js` / `@tenshu/sdk`) crosses the proxy boundary but does **not**
  carry UI; the React layer above it is a separate world.

Three forces push the decision now rather than later:

1. **String count is small and the surface is well-known.** Drive (file
   management UI), Control (settings + onboarding + recovery), `web/apex`
   (marketing + control plane). Wrapping each new string in a localisation
   call at authoring time is cheap; retrofitting hundreds of literals later
   is archaeology.
2. **Cross-process error surfaces will calcify.** Sync, identity, and
   notification services return errors over the proxy boundary. If those
   errors carry free-text English strings today, switching to codes later
   means breaking every wire-level error type — exactly the kind of change
   the schema-first philosophy is meant to make rare. The decision *now* is
   whether errors travel as codes or as text.
3. **The web side already exists.** `web/apex` is live and gaining marketing
   copy. Without a convention, that copy lands as JSX literals that future
   localisation has to extract one component at a time.

## Decision

Eight anchor points.

1. **KMP side uses Compose Multiplatform resources.** Strings live in
   `composeResources/values/strings.xml` per module; call sites read them via
   `stringResource(Res.string.foo)`. This is the idiomatic CMP path, works
   across Android / Desktop / future iOS without per-platform branching, and
   the resources plugin already supports per-module catalogues — fitting the
   pattern we already use for schemas (each module owns its own).

2. **Web side uses `react-i18next` with per-package JSON catalogues.** Each
   pnpm-workspace package under `web/` (today `web/apex`; tomorrow
   `web/drive`, `web/control`, etc.) owns its own `locales/<lang>/*.json` tree
   and initialises a package-local i18next instance. No shared JS i18n
   provider — the boundary follows the SDK boundary.

3. **ICU MessageFormat is the string-content format from day one.** Both
   `strings.xml` plural/select forms and the i18next JSON catalogues use ICU
   syntax (or the closest dialect each supports). We only ship English at
   first; the format choice still matters because plural/gender/number
   formatting needs structural information that bare strings lose. Cheap to
   do now, painful to retrofit.

4. **The proxy emits codes, not localised strings.** Any user-facing
   error or status that crosses the proxy boundary travels as an enum-ish
   schema (sealed `Error` subtype, named operation result, typed status
   composite) — *never* as a free-text `String` the client is expected to
   render. The client module that catches the typed value is responsible
   for mapping it to a localised string via its own `strings.xml` /
   `i18next` catalogue. This preserves the existing proxy ↔ client isolation
   boundary (the proxy doesn't know the user's locale, the client doesn't
   need to inherit the proxy's catalogue), and it keeps the proxy bundle's
   resource weight bounded.

5. **The proxy gets its own catalogue, used only for proxy-rendered UI.**
   Control's foreground-service notification is the canonical case: it is
   rendered by the proxy process per the existing isolation rule, so the
   proxy must localise its own copy. The catalogue lives in
   `:control-shared` (or whichever module hosts the proxy's UI surface),
   *not* in the service modules. Service modules stay pure-logic and
   stringless.

6. **Locale source: OS by default, in-app override via `:preference-service`
   later.** Day one, every platform reads the OS / browser locale: Android
   `Configuration`, Compose Desktop `Locale.getDefault()`, browser
   `navigator.languages` (via i18next's `LanguageDetector`). An in-app
   override is a `DeviceSetting` on `:preference-service` (per-device, not
   synced — locale is a UI preference, not data). Don't build the override
   surface until somebody asks for it.

7. **Module ownership.** Strings live where the UI lives: each `*-shared`
   module owns app-specific strings; `:ui-core` owns strings for shared UI
   primitives (generic error dialogs, loading states, recovery snippets that
   `:control-shared` and `:drive-shared` both consume); per-app JS packages
   own their own. The service modules (`:identity-service`,
   `:file-service`, `:sync-service`, `:notification-service`,
   `:preference-service`) are stringless — they produce typed values that
   higher layers render.

8. **No shared cross-language catalogue yet — but committed as the n=2
   destination.** Option (2) from the recommendation conversation — a
   single ICU JSON catalogue with Gradle codegen to `strings.xml` and a
   direct i18next resource feed — is **the** answer at n=2; we are not
   keeping the question open. We are not building option (2) now because
   the build plumbing (Gradle task, pnpm-side resource loader, dual-tree
   IDE story) is real work and the value at n=1 locale is zero. The
   pre-commitment matters because it lets authoring conventions chosen
   *now* (string-key naming, per-module file layout, ICU MessageFormat
   from day one per anchor #3) be picked to make the eventual migration
   mechanical — string keys and catalogue trees in the two worlds should
   line up such that the codegen target at n=2 is "merge, not rewrite."

## Consequences

- **Every new user-facing string gets wrapped at authoring time.**
  `stringResource(Res.string.foo)` on KMP, `t('foo')` on web, from day one.
  This is the load-bearing habit; everything else can be retrofitted but
  string-by-string extraction cannot.

- **Cross-service error types must be enum-ish.** Existing `Error` subtypes
  that carry free-text English `String` fields (sync errors, identity
  errors, notification errors) need an audit. The path is mechanical: turn
  the free-text into a sealed code (existing platform pattern — see
  `IdentityError`, `SyncError`), localise at render. Some sites will be
  fine as English-only forever (developer-facing log strings, internal
  diagnostics) — the test is *"will a user see this?"*

- **Resource plugin per module across KMP.** Each `*-shared` and `:ui-core`
  adopts `org.jetbrains.compose.components.resources`. One-line Gradle
  change per module; the per-module catalogue keeps incremental builds
  fast.

- **`web/apex` and future web packages adopt `react-i18next` and a
  `locales/` tree.** Per-package i18next instance; no shared JS i18n
  provider across packages (each package's catalogue is independent, just
  like its bundle).

- **No proxy-side translation infrastructure.** The proxy modules do not
  gain a locale parameter on any RPC. The locale lives at render time on
  the client side, full stop.

- **English-text duplication is accepted.** A button label saying "Cancel"
  may appear in `:ui-core`, `:control-shared`, `:drive-shared`, and
  `web/apex` catalogues independently. Until n>1 locale, duplication is
  cheaper than the codegen+sync infrastructure that would deduplicate it.

- **RTL on web rides with the first non-English locale.** The Phase T+C
  entry in `CLAUDE.md` already flags RTL as deferred. No separate ADR; the
  decision is "do it alongside the second locale's introduction."

## When to revisit

Reopen when one of these is true:

1. **A second locale is committed to.** The trigger, not the
   decision — the decision is pre-committed at anchor #8 (option (2):
   shared ICU JSON catalogue + Gradle codegen to `strings.xml` + direct
   i18next feed). What still needs revisiting at that moment is the
   *implementation plan*: how the codegen task plugs into the build, how
   the i18next loader consumes the shared tree, and whether the per-module
   `strings.xml` catalogues already lined up well enough to merge cleanly
   — or whether anchor #8's pre-commitment failed and there's authoring
   debt to clean up first. The RTL pass on web rides with this milestone.

2. **An error category grows past ~10 enum variants per category.** At
   that size, the schema overhead of "one variant per surfaced message"
   starts to outweigh the locale-routing benefit. The right escape valve
   is per-category, not platform-wide: a specific `Error` type may carry a
   localisation-key `String` parameter that the client looks up. Don't
   apply this pre-emptively — it's a per-type decision.

3. **iOS Compose strings become a constraint.** CMP resources on iOS are
   newer than on Android/Desktop; if the iOS app surfaces edge cases (asset
   catalogue integration, dynamic-type interactions), the decision to use
   CMP resources may need an escape valve to native string files. The
   contained surface (only iOS) means it's a per-platform exception, not a
   reversal of this ADR.

4. **The proxy gains a locale-aware surface.** If a future feature
   genuinely requires the proxy to emit user-facing localised text (e.g. a
   server-side template the client cannot reconstruct from a code), the
   "proxy emits codes" decision needs revisiting on that surface only.
   Today no such case exists.

## Alternatives considered

- **CMP Web for the frontend.** Would unify the resource catalogue across
  KMP and web. Rejected by house rule #5 long before this ADR — the
  TypeScript + React choice is load-bearing for other reasons
  (`docs/web/architecture.md` §12.5).

- **Single ICU catalogue with codegen on day one.** Considered. Rejected
  for now because the build-system plumbing (Gradle task that walks JSON
  to emit `strings.xml`; pnpm-side resource loader to feed i18next from
  the same tree; IDE rename support across two trees) is real work, and
  the value at n=1 locale is zero. Becomes the right answer at n=2.

- **Proxy emits localised strings using a client-supplied locale tag.**
  Considered for ergonomics: callers receive ready-to-render text. Rejected
  because (a) it puts a catalogue inside every service module, ballooning
  the proxy bundle and forcing strings into the wire shape; (b) it
  violates the existing client-isolation rule (the proxy now has UI copy
  *of behalf of* the client); (c) it forces a locale to travel on every
  RPC, which is exactly the kind of cross-cutting parameter the schema
  philosophy works hard to avoid.

- **Free-text English errors over the wire, localised at render via
  lookup-by-content.** Considered as a no-codes shortcut. Rejected — the
  lookup table effectively *is* the code mapping, just brittle (typos in
  the source string silently fall through to English) and
  un-grep-pable from the rendering side.

- **Per-app JS i18n provider shared across packages.** Considered for
  apex+drive+control sharing one catalogue. Rejected because the SDK
  boundary is the natural seam — each web package gets its own bundle, its
  own deployment, and its own catalogue. Sharing would couple packages
  that have no other reason to be coupled.

- **kotlinx-resources / Moko Resources instead of CMP resources.** Both
  are viable KMP i18n libraries. CMP resources wins on integration: it's
  in the Compose Multiplatform plugin we already depend on, no extra
  Gradle plumbing, no separate plugin version pin alongside the 1.7.3
  CMP pin.

## References

- `docs/web/architecture.md` §12.5 — house rule #5 (TypeScript + React,
  not CMP Web). Sets the constraint that makes this ADR's split necessary.
- `docs/agent.md` §19 — the practical authoring conventions
  (module ownership, code-not-string error policy, OS-locale default).
- ⚠ This used to delegate rollout status to `CLAUDE.md`'s "In-flight architectural work" table.
  That row was removed 2026-08-11 (the track is landed, and the table is for cross-cutting work
  in flight); the Status block above now carries the rollout itself.
- `:preference-service` modules — the eventual home for the in-app locale
  override (`DeviceSetting`); see `docs/proposals/core-preference-service.md`
  for the preference-service shape.
- `feedback_proxy_client_isolation_ui` (memory) — the existing rule that
  forces the proxy to render its own UI (e.g. Control's FGS notification),
  which is what makes anchor decision #5 a separate catalogue rather than a
  client-side concern.

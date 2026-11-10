# Tenshu — Documentation Index

Hand-curated map of this folder. If you're new, start with **Read first**. If you're looking
for something specific, scan the section that matches what you want.

⚠ **Complete for the small, stable sets; a curated subset for proposals.** *Read first*,
*Architecture*, *ADRs*, *Conventions & how-to* and the folder map below list everything they
cover. The **Proposals** section does not: it summarises **59 of the 167** docs in
`proposals/`, and the summaries are written by hand, so they lag the docs they describe. When
you need the state of a track, the doc's own `Status:` header is the source of truth, and
`ls docs/proposals/` is the complete list. AGENTS.md's two tables route to the ~40 tracks that
are live or that left a standing constraint.

Status convention used in design docs:

- **Spec'd** — design agreed, no code yet.
- **In progress** — code landing in branches.
- **Largely landed** — main body shipped; trailing items in tech-debt.
- **Landed** — done; doc kept for historical reference.
- **Superseded** — replaced by another design; doc kept for context.

---

## Read first

Always-relevant references. Read these before changing any non-trivial code.

- [`agent.md`](agent.md) — platform conventions: module map, Service & Operation
  pattern, schema DSL, ViewModel and DI patterns, naming.
- [`sync/threat-model.md`](sync/threat-model.md) — the security contract. Every
  change touching sync, pairing, recovery, or the relay must preserve the
  invariants here.
- [`milestones.md`](milestones.md) — what's in development, what's planned, and
  the dependencies between them.
- [`tech-debt.md`](tech-debt.md) — known issues, sized engineering tasks, and
  things to be careful about across the codebase.

Module-specific conventions live in `*-agent.md` files inside each module
(e.g. `core/core-agent.md`, `ui-core/ui-agent.md`).

---

## Conventions & how-to

Always-relevant guidance for writing platform-shaped code.

- [`agent.md`](agent.md) — top-level platform conventions (also in *Read first*).
- [`schema-dsl.md`](schema-dsl.md) — the schema DSL: composite/operation/service
  builders, field-helper conventions, when to use `Type` vs `DataSchema`.
- [`testing.md`](testing.md) — testing tiers (unit / proxy / integration), the
  `TestProxy` fixture, conventions per module.
- [`models.md`](models.md) — the models Tenshu ships, and the review surface for adding one.
  ⚖️ The model table is deliberately empty until model choice and licence/corpus provenance
  are settled.

### Recipes (`how-to/`) — complete list

*Building & authoring*

- [`how-to/add-composites-and-operations.md`](how-to/add-composites-and-operations.md) —
  step-by-step recipe for adding a new domain type and the operations on it.
- [`how-to/claude-web-environment.md`](how-to/claude-web-environment.md) — configure a
  Claude Code on the web environment so `./gradlew` works (Android SDK, JDK 17, `dl.google.com`).
- [`how-to/new-web-app.md`](how-to/new-web-app.md) — scaffolding a web app on `@tenshu/app-kit` (`scripts/new-web-app.sh`), the steps left by hand, and shipping it to the dev lab.
- [`how-to/ios-xcode-project-generation.md`](how-to/ios-xcode-project-generation.md) — regenerating the iOS Xcode projects.
- [`how-to/tiles-pipeline.md`](how-to/tiles-pipeline.md) — building & publishing offline map-region bundles.
- [`how-to/sweep-stale-comments.md`](how-to/sweep-stale-comments.md) — running a stale-comment
  sweep: the module split, how much parallelises, the exit conditions every lane owes, and the
  hazards (a comment-only diff is not automatically a *safe* diff).

*Signing & store*

- [`how-to/ios-signing.md`](how-to/ios-signing.md) — on-device signing for the iOS apps (company team since 2026-10-02; App Group on device builds, §6).
- [`how-to/ios-testflight.md`](how-to/ios-testflight.md) — archiving an iOS app and uploading it to TestFlight (Control and Drive; Control uploaded 2026-10-14).
- [`how-to/store-screenshots.md`](how-to/store-screenshots.md) — App Store screenshots for Control and Drive (6.9" iPhone, 13" iPad) from UI tests over a seeded fixture; replacing the placeholder images.
- [`how-to/store-listings.md`](how-to/store-listings.md) — the App Store listing text as files in `store/`, previewed and pushed with fastlane deliver (`scripts/store-push.sh`); framed screenshots in Apple's bezels (`scripts/store-frame`); what stays manual in App Store Connect.
- [`how-to/app-store-release.md`](how-to/app-store-release.md) — the per-app checklist from a TestFlight build to "submitted for App Store review": files, App Store Connect by hand, ⚖️ counsel, production infrastructure; the order (Control first), the traps, the review notes and the demo-account question.
- [`how-to/macos-signing.md`](how-to/macos-signing.md) — macOS code signing for Control + Drive.
- [`how-to/macos-release.md`](how-to/macos-release.md) — notarized Developer ID `.dmg` per desktop app (`scripts/macos-release.sh`); `--pipeline-test` without the certificate.
- [`how-to/android-release.md`](how-to/android-release.md) — building Play-uploadable bundles; where the signing key lives and which key signs what.
- [`how-to/windows-store-submission.md`](how-to/windows-store-submission.md) — Control + Drive to the Microsoft Store: Partner Center, the Store-mode `packageMsix` (unsigned, Store publisher compiled into the peer gate), the listing, `winget --source msstore`.
- [`how-to/play-store-declarations.md`](how-to/play-store-declarations.md) — Play Console declarations owed before any listing.
- [`how-to/play-console-answers.md`](how-to/play-console-answers.md) — the Play Console answers per app, task by task, and the Data safety CSVs.
- [`oss-licences.md`](oss-licences.md) — the open-source notices every app and the web ship (AboutLibraries, generated per binary), the map-data credits, and the licence audit. ⚠⚠ The basemap chip is a map's only OSM credit.
- [`proposals/store-presence-and-support-pages.md`](proposals/store-presence-and-support-pages.md) — everything a store listing needs, listing text as files, generated screenshots, and the public support page per app, platform and language.
- [`proposals/desktop-macos-distribution.md`](proposals/desktop-macos-distribution.md) — getting the macOS apps to users: Developer ID + notarization first, one suite `.pkg`, lockstep updates; the Mac App Store assessed and deferred. ⚠ Every Mach-O must be re-signed by our team, including dylibs inside jars.
- [`proposals/desktop-windows-port.md`](proposals/desktop-windows-port.md) — Control on Windows: DPAPI key store, peer gate, tray, autostart, toasts/reminders; the Microsoft Store (MSIX) first, which also serves `winget --source msstore`, and a SignPath-signed per-machine MSI later. ⚠⚠ The transport is AF_UNIX, not named pipes. ⚠⚠ A per-user install cannot be protected by any certificate — the gate pins package identity or signer + `%ProgramFiles%`, never a signer alone.

*Deploy & operate*

- [`../deploy/scaleway-relay/README.md`](../deploy/scaleway-relay/README.md) — **the production relay** (Scaleway, Terraform), incl. why a rebuild is an outage procedure.
- [`how-to/move-the-relay-to-another-provider.md`](how-to/move-the-relay-to-another-provider.md) — the relay's provider move as run (DigitalOcean → Scaleway), data first.
- [`how-to/back-up-the-relay-database.md`](how-to/back-up-the-relay-database.md) — the relay's nightly database snapshot, the content mirror, and restore.
- [`how-to/relay-storage-ceiling.md`](how-to/relay-storage-ceiling.md) — the cost brakes: a relay-wide stored-bytes ceiling (`STORAGE_CEILING_GIB`) and the commerce signup cap (`SIGNUP_LIMIT_*`), how to size and set them.
- [`how-to/take-down-a-share-link.md`](how-to/take-down-a-share-link.md) — notice-and-action: taking one share link down by `shareId` (`deploy/share-takedown.sh`), verifying it, the audit row, telling the owner.
- [`../deploy/scaleway-commerce/README.md`](../deploy/scaleway-commerce/README.md) — **the production commerce issuer** (Scaleway, Terraform).
- [`how-to/move-commerce-to-scaleway.md`](how-to/move-commerce-to-scaleway.md) — commerce's provider move, incl. the one correct `docker compose` invocation.
- [`how-to/back-up-the-commerce-database.md`](how-to/back-up-the-commerce-database.md) — commerce's nightly Postgres backup and restore.
- [`how-to/box-health.md`](how-to/box-health.md) — disk checks and backup pings to a healthchecks.io dead man's switch on the three production boxes (`deploy/box-health/install.sh`); why: a full disk silently stopped commerce's backups for 23 days.
- [`how-to/deploy-apex-digitalocean.md`](how-to/deploy-apex-digitalocean.md) — deploying the apex web frontend (Terraform); production until [`how-to/move-apex-to-scaleway.md`](how-to/move-apex-to-scaleway.md) runs.
- [`how-to/move-tiles-to-cloudflare.md`](how-to/move-tiles-to-cloudflare.md) — moving the tiles bucket to Cloudflare R2 on the hostname clients already use.
- [`how-to/deploy-commerce-digitalocean.md`](how-to/deploy-commerce-digitalocean.md) — ⚠ RETIRED box; its relay-enforcement section still applies, on the Scaleway relay.
- [`how-to/deploy-relay-digitalocean.md`](how-to/deploy-relay-digitalocean.md) — ⚠ RETIRED box; kept for its DDoS & abuse notes, which code cites. Never `apply` the module.
- [`how-to/deploy-relay-fly.md`](how-to/deploy-relay-fly.md) — deploying the sync relay to Fly.io.
- [`how-to/deploy-dev-lab-scaleway.md`](how-to/deploy-dev-lab-scaleway.md) — the isolated dev plane
  on Scaleway: the seven manual steps (separate Project, ⚠⚠ SSH key *before* first boot, API key,
  DNS delegation, PAT) and the Scaleway failures that are silent rather than loud.
- [`how-to/hub-pi-deploy.md`](how-to/hub-pi-deploy.md) — deploying the Tenshu Hub (H1) on a Raspberry Pi 5.
- [`how-to/set-up-otp-email.md`](how-to/set-up-otp-email.md) — set up OTP email delivery.
- [`how-to/server-logging.md`](how-to/server-logging.md) — relay/commerce/Caddy logging: INFO by default + `LOG_LEVEL`, what is never logged (emails, OTPs, secrets, query strings, full ids, IPs), and how long each log is kept.
- [`how-to/move-dns-to-cloudflare.md`](how-to/move-dns-to-cloudflare.md) — moving the `tenshu.app` nameservers to Cloudflare, DNS-only, with a pre-switch proof and rollback.

*Verifying end to end*

- [`how-to/commerce-dry-run.md`](how-to/commerce-dry-run.md) — walk the whole commerce flow once, locally.
- [`how-to/reset-and-commerce-test.md`](how-to/reset-and-commerce-test.md) — the production-reset runbook: wipe every plane and every client in order, then verify (+ the commerce/enforcement test).
- [`how-to/pio7-phone-session.md`](how-to/pio7-phone-session.md) — PIO-7's one iPhone session before `TENSHU_PHOTOS_ON_DEVICE_AI` flips: IN-3m-iOS, then every phone-only item of `ios-on-device-inference.md` §14, destructive steps last.

### Doc gates

Docs have no tests, so every way they rot is covered by a script rather than by convention.

- [`../scripts/check-doc-refs.py`](../scripts/check-doc-refs.py) — the gate on **references**,
  across every `.md` in the repo: dead links, stale filename mentions, and two files claiming one
  `NNNN-` number. Run it after renaming, moving or graduating a doc. ⚠⚠ Its hard part is what
  it *doesn't* report: of ~50 mentions the first audit flagged, ~14 were correct as written
  (docs deliberately not written yet, gitignored design handoffs, proposals named in the
  sentence that graduated them). Those live in an allowlist **with a reason each**, and the
  allowlist is itself checked — an entry that stops matching fails the run and must be deleted.
  Known-unresolved links print every run under *KNOWN GAPS* rather than being silenced.

- [`../scripts/check-proposal-status.py`](../scripts/check-proposal-status.py) — the gate on
  **state**: every `proposals/*.md` must say near the top what state it is in. It exists because
  CLAUDE.md's rows stopped carrying status on 2026-09-07 and now delegate it here, which is only
  safe if the field is actually present. ⚠⚠ It checks *presence, never truth* — a doc whose status
  line says "not yet implemented" over shipped code passes, and that is exactly the failure the
  sweep found four times. What it buys is one obvious place to look and to correct. ⚠ The spelling
  is deliberately loose (six variants exist in the tree); normalising them would be a 170-file diff
  for no reader benefit. `*-handover.md` files are allowlisted with a reason — they are session
  handoffs, and their state belongs to the track they hand over.

- [`../scripts/check-comment-claims.py`](../scripts/check-comment-claims.py) — the gate on
  **claims made in code comments**, the counterpart to `check-doc-refs.py`'s docs-to-docs pass.
  Three checks: a comment citing a `.md` that does not exist; two `/** … */` blocks in a row
  (the first documents *nothing*, because Kotlin binds only the adjacent one); and a backticked
  `SomeClass` that exists nowhere in the tree. All three were found by hand, repeatedly, during
  the 2026-09-07 sweep.
  ⚠⚠ **It is a baseline gate, not a clean-tree gate.** The tree has ~674 known findings, banked
  in `comment-claims-baseline.json`; only *new* ones fail, because a gate that reports 674
  problems on day one teaches everyone to skip it. Burn the baseline down per module and
  `--update` to bank the progress — an entry that stops reproducing also fails, so the list
  cannot quietly rot. ⚠⚠ Entries are keyed by **identity, not line number**: the first version
  keyed on `path:line`, so fixing one finding shifted the rest and they reported as
  regressions — a gate that punishes editing is worse than none. ⚠ Presence only: a resolving path and an existing symbol say nothing about
  whether the sentence around them is true.

The next two are specific to AGENTS.md (which CLAUDE.md imports), loaded into every agent
session before the user says anything. Its two failure modes — growing without bound, and losing
a claim when it shrinks — get one gate each. Run both when you edit a row.

- [`../scripts/check-agents-md-size.py`](../scripts/check-agents-md-size.py) — the one that
  runs on every edit: total byte budget (⚠ 32 KiB, where Codex silently truncates), a per-row
  cap, table well-formedness, and CLAUDE.md staying the bare `@AGENTS.md` import. Between
  2026-08-11 and 2026-08-12 the file went ~37 KB → ~52 KB and 29 of 30 commits only added, a
  row having quietly become a changelog; this is what stops that recurring.
- [`../scripts/check-doc-claims.py`](../scripts/check-doc-claims.py) — the one for *shrinking*
  a row. Run `scripts/check-doc-claims.py --base master` to check the *pre-edit* rows against
  the *post-edit* docs; anything it reports as an orphan exists nowhere but AGENTS.md and must
  be relocated before the row is cut. ⚠ Necessary, not sufficient — it tests whether a claim's
  tokens appear in the target doc, not whether the claim is still true there.

Those two are complementary: `check-agents-md-size.py` checks a row stayed *short*,
`check-doc-claims.py` checks that what left it landed *somewhere*. `check-doc-refs.py` then
checks that wherever it landed can still be *reached*, `check-proposal-status.py` checks the
doc it reached still says what state it is in, and `check-comment-claims.py` checks the same
three ways from the code side.

---

## Architecture (timeless)

How the platform is structured, today. Not time-bounded; updated when the
architecture genuinely changes, not when a single feature lands.

- [`whitepaper.adoc`](whitepaper.adoc) — Tenshu's foundational architecture
  document: data privacy & security principles, zero-trust backend,
  proxy/client split, layered chain of trust.
- [`sync/threat-model.md`](sync/threat-model.md) — assets, threats,
  mitigations, and security invariants for the sync subsystem (also in *Read
  first*).
- [`sync/design.md`](sync/design.md) — sync subsystem architecture:
  HLC, blocks, push/pull semantics, conflict resolution.
- [`sync/pairing-flow.md`](sync/pairing-flow.md) — the realm-device pairing
  protocol: handshake, SAS verification, master-key handoff.
- [`web/architecture.md`](web/architecture.md) — web-Tenshu architecture: apex
    + per-app subdomains, KMP→JS SDK, schema-walker pattern, in-process
      runtime.
- [`protocol/desktop-ipc.md`](protocol/desktop-ipc.md) — Unix-socket IPC
  protocol used by the desktop Drive ↔ Control transport.
- [`protocol/ios-ipc.md`](protocol/ios-ipc.md) — iOS IPC protocol (App Group
    + XPC) for the iOS Drive client ↔ proxy transport.
- [`protocol/relay-api.openapi.yaml`](protocol/relay-api.openapi.yaml) — OpenAPI 3.1
  reference of every relay route **as of CR-2 B3** (end state); binary bodies are
  annotated with their `x-tenshu-composite`. Ends with a known-issues list.
- [`design/audit.md`](design/audit.md) — design-system audit (snapshot from
  the design-system rollout).
- [`design/landing-illustrations.md`](design/landing-illustrations.md) — the style contract
  the apex landing's code-drawn illustrations share, and what each must communicate.

---

## Roadmaps & status

Where we are; what's shipping when.

- [`milestones.md`](milestones.md) — the milestone roadmap (also in *Read first*).
- [`tech-debt.md`](tech-debt.md) — sized engineering tasks (also in *Read first*).
- [`proposals/pre-release-checklist.md`](proposals/pre-release-checklist.md) — living
  checklist of work that must land before the final/public release (counsel-review-gated
  legal & policy items, distribution placeholders, gathered "before launch" notes).
- [`sync/roadmap.md`](sync/roadmap.md) — ⚠ **retired**; a redirect to the docs that
  superseded it. The phase checklist it held had gone stale silently.
- [`web/roadmap.md`](web/roadmap.md) — web-track implementation roadmap (KMP→JS
  phases, apex content, etc.).
- [`tracks/README.md`](tracks/README.md) — the three Control implementation tracks, and how
  they divide up: [Track A](tracks/track-a-control-apps.md) Control → Apps UI,
  [Track B](tracks/track-b-control-data.md) Control → Data UI + `core-realm-data`,
  [Track C](tracks/track-c-shared-realm-sharing.md) Control UI for shared-realm file sharing.
- [`tracks/track-identity-and-reference.md`](tracks/track-identity-and-reference.md) — the
  **sequencing** for the identity/reference cluster: entity wire ids, the content-plane refcount,
  within-realm dedup and CR-6. ⚠ Unlike A/B/C these do **not** parallelise — each phase removes a
  reason the next is unsafe, and the doc says which reason.

---

## Policy & user-facing rules

Commitments made to users, not to the compiler. ⚖️ Several are counsel-review-gated — check
the doc before changing behaviour it describes.

- [`policy/acceptable-use-and-limits.md`](policy/acceptable-use-and-limits.md) — acceptable use
  & service limits, the single source of truth. Link sharing, quota and abuse rules point here.
- [`policy/privacy-review-checklist.md`](policy/privacy-review-checklist.md) — ⚖️ the maintainer acts as counsel until launch; privacy-notice / stored-data PRs pass this checklist.
- [`policy/erasure-request-runbook.md`](policy/erasure-request-runbook.md) — handling an emailed erasure/closure request: verify by code, deadline, what is not erased, verification queries, reply templates.
- [`file-sharing.md`](file-sharing.md) — the user-facing shape of file sharing across modes.

---

## Architectural Decision Records (ADRs)

One-shot decisions, recorded with context and consequences. Complete list — an ADR is cheap to
name and expensive to rediscover, so this section is kept exhaustive even though the proposals
section is not.

⚠ **The number is an identifier, not a date.** It is roughly allocation order and has never
been strictly chronological — ADR 0005 (2026-05-06) predates 0003 and 0004 (both 2026-05-09).
Read the `Date:` header, not the number. A number is also **never reused or re-pointed** once
references exist: ADR 0023 is a 2026-05-09 decision that was filed as a second 0004 and
renumbered in 2026-08-13 to break the collision.

- [`adr/0001-design-system-direction.md`](adr/0001-design-system-direction.md) — consolidate Drive + Control on a shared design system.
- [`adr/0002-retire-indigo-jade.md`](adr/0002-retire-indigo-jade.md) — retire the Indigo and Jade palettes.
- [`adr/0003-mark-set-v6.md`](adr/0003-mark-set-v6.md) — mark set v6. **Superseded stub**; ADR 0004 restates everything still in force. Kept only because ADR 0023 cites it by number.
- [`adr/0004-mark-set-v7-ears-reinstated.md`](adr/0004-mark-set-v7-ears-reinstated.md) — mark set v7: ears reinstated; supersedes ADR 0003 on the ears decision only.
- [`adr/0005-composite-schema-typing.md`](adr/0005-composite-schema-typing.md) — composite schema typing via `typeParams`.
- [`adr/0006-sync-tombstones.md`](adr/0006-sync-tombstones.md) — deletion via `DeleteBlock` tombstones.
- [`adr/0007-sync-device-management.md`](adr/0007-sync-device-management.md) — synced device management + ephemeral pairing relay.
- [`adr/0008-execution-context-decomposition.md`](adr/0008-execution-context-decomposition.md) — `ExecutionContext` decomposition.
- [`adr/0009-operation-schema-typing.md`](adr/0009-operation-schema-typing.md) — operation schema typing via `receiver`, `typeParams`, `TypeVariable`.
- [`adr/0010-relation-system.md`](adr/0010-relation-system.md) — relation system rework: `ServiceDataRelation`, materialised dynamic chains.
- [`adr/0011-task-executor-split.md`](adr/0011-task-executor-split.md) — `TaskExecutor` split: sync vs async as distinct execution domains.
- [`adr/0012-interactor-pattern.md`](adr/0012-interactor-pattern.md) — Interactor pattern: a unified execution adapter for the request DSL.
- [`adr/0013-property-authoring-unification.md`](adr/0013-property-authoring-unification.md) — one `Property<T>` handle, `Relation` as marker.
- [`adr/0014-proxy-session-lifecycle-teardown.md`](adr/0014-proxy-session-lifecycle-teardown.md) — proxy session lifecycle: owned scope + deterministic suspend teardown.
- [`adr/0015-loop-db-stream-unification.md`](adr/0015-loop-db-stream-unification.md) — event-driven array listeners + channel-based sync emit.
- [`adr/0016-localisation.md`](adr/0016-localisation.md) — CMP resources + react-i18next, ICU MessageFormat, proxy emits codes.
- [`adr/0017-file-content-two-plane-channel.md`](adr/0017-file-content-two-plane-channel.md) — file-content sync via a two-plane channel (manifest + on-demand chunks).
- [`adr/0018-file-sharing-mode-a.md`](adr/0018-file-sharing-mode-a.md) — file sharing Mode A: anonymous link sharing via capability URL.
- [`adr/0019-storage-capacity-gating.md`](adr/0019-storage-capacity-gating.md) — storage capacity gating: account-and-fits, enforced at the op.
- [`adr/0020-web-app-structure.md`](adr/0020-web-app-structure.md) — apps are own-origin packages, apex hosts them in an iframe.
- [`adr/0021-timeline-editorial-serif.md`](adr/0021-timeline-editorial-serif.md) — Timeline uses an editorial italic serif for headings, scoped to the app.
- [`adr/0022-ios-forward-to-control-surface.md`](adr/0022-ios-forward-to-control-surface.md) — every first-party iOS app forwards to Control through one shared screen. ⚠ Superseded in scope by ADR 0025; still the reference for the iOS mechanics.
- [`adr/0023-density-single-mode.md`](adr/0023-density-single-mode.md) — single density mode for v0.1, no user-selectable density. ⚠ Decided 2026-05-09 and originally filed as a second ADR 0004; renumbered, not re-decided.
- [`adr/0024-merger-field-resolution.md`](adr/0024-merger-field-resolution.md) — a `SemanticMerger` reads a collection field with `resolveListField`, never `getField`. Closes the long-standing lazy-vs-materialised seam.
- [`adr/0025-forward-to-control-surface.md`](adr/0025-forward-to-control-surface.md) — every first-party app offers one route back to Control, on every platform. Supersedes 0022 by widening it off iOS; the posture follows the *reason*, not the platform.
- [`adr/0026-app-icon-system.md`](adr/0026-app-icon-system.md) — **app icons: the whole contract.** The 64-unit grid, the five colour tokens, the five output sets, the Android and macOS recipes, and the five gates that catch a bad mark. Self-contained — this is the file to hand a design session. ⚠ Not the brand cat mark; that is ADR 0004.

---

## Proposals (in-flight & landed design docs)

Time-bounded design docs — each proposes a specific change, lands or is
superseded, then sticks around as historical record. See each doc's `Status:`
header for current state.

Filename convention: `<area>-<topic>.md`, which is what makes the directory listing usable:
`ls docs/proposals/ | grep '^core-'` finds every platform-core proposal whether or not it is
summarised below.

⚠⚠ **This section is a curated subset, and the summaries lag.** It covers **59 of the 167**
docs in `proposals/`; hand-written summaries of 167 moving documents were never going to stay
current, and these have not. Treat an entry here as a pointer and a hint at the area, never as
the status. Three places carry more reliable answers:

- the doc's own `Status:` header — **the** source of truth for one track;
- AGENTS.md's two tables — the ~40 tracks that are live or that left a standing constraint,
  each capped at one line and gated by `check-agents-md-size.py`;
- [`milestones.md`](milestones.md) and [`tech-debt.md`](tech-debt.md) — what is scheduled, and
  what is owed.

Adding a proposal does **not** oblige you to add an entry here. Add one when the area is
hard to find by filename, or when the doc supersedes something a reader would otherwise trust.

### `core-` — platform-core changes

- **Interactor pattern** — largely landed; authoring reference in
  [`schema-dsl.md`](schema-dsl.md) §5 + design rationale in
  [ADR 0012](adr/0012-interactor-pattern.md). Sealed `Interactor<T>`
  hierarchy + `InteractorProvider` for unified DSL execution across
  sync / IO / background / stream modes. One open item: `EvaluateCallstack`
  line 51's string-literal stream check should migrate to the
  `StreamOperation` trait.
- **Dual-domain `TaskExecutor` split** — landed; authoring reference in
  [`agent.md`](agent.md) §4 + design rationale in
  [ADR 0011](adr/0011-task-executor-split.md). `TaskExecutor.execute` is
  sync (throws `SynchronousViolationException` on async gap);
  `AsyncTaskExecutor.executeAsync` (suspend) drives the `AsyncReference`
  loop. `PureOperationRunner` short-circuits IPC for pure chains on
  remote clients.
- **Operation schema typing** — landed; authoring reference in
  [`schema-dsl.md`](schema-dsl.md) §3 + design rationale in
  [ADR 0009](adr/0009-operation-schema-typing.md). `OperationSchema` carries
  `receiver` + `typeParams`; `OperationTypeProvider` deleted.
- **Error channel** — landed (all six phases, 2026-06-02);
  [`proposals/core-error-channel.md`](proposals/core-error-channel.md). Typed
  `TError` taxonomy + `TenshuException`; operation impls return `T` directly and
  throw typed errors, with the catch-all confined to `LocalTaskExecutor`. Added
  `:logging-service` + the Settings → Errors screen. Outstanding orthogonal items
  (retention, JS bridge, App Console, freetext cleanup) are in
  [`tech-debt.md`](tech-debt.md). ⚠ It built the **throw** side only — the render
  side is [`proposals/core-error-copy-catalogue.md`](proposals/core-error-copy-catalogue.md).
- **Error copy catalogue** — **landed v1.0** (EC-0…EC-5, 2026-08-29);
  [`proposals/core-error-copy-catalogue.md`](proposals/core-error-copy-catalogue.md).
  [ADR 0016](adr/0016-localisation.md) anchor #4 already decided that the proxy emits
  codes and the client localises them; the error channel shipped the typed throw and
  nothing shipped the render, so proxy code writes English that is neither translatable
  nor logged. Scoped by the **nine** expressions that read a `TError`'s message in an app
  module, not the 125 that throw one. ⚠⚠ The invariant is §5.0: **no proxy code creates a
  localised string** — on Android the proxy modules load into *Control's* process while
  `drive-shared-logic` renders in *Drive's*, so today's freetext has one app authoring another
  app's copy. Copy is created app-side at the ViewModel layer or above; the one carve-out is UI
  the proxy renders itself (anchor #5). Phased as **vertical slices** — one flow from throw site
  to rendered pixel (Recovery → membership → contacts → pairing), so the invariant is true for a
  whole flow each time one lands, rather than only after the last phase. ⚠ The lint is dropped
  for now — §8.
- **Client resubscription after a proxy restart** — landed (R1–R6);
  [`proposals/core-client-resubscription.md`](proposals/core-client-resubscription.md).
  Reactive reads held over IPC used to die silently when the proxy process
  restarted, recoverable only by restarting the *client*. Now the reference for
  `TaskTransport` connection semantics across Binder / UDS / EPC, and for why the
  fix is client-side (§3.4).
- **Relation system rework** — largely landed; authoring reference in
  [`schema-dsl.md`](schema-dsl.md) §7 "Properties & Relations" + design rationale in
  [ADR 0010](adr/0010-relation-system.md). Unified `ServiceDataRelation`,
  materialised dynamic chains, `CallstackMaterializer` /
  `ChainTemplate<T, R>` renames, and the call-site relation composites (renamed
  `*PropertyRelation` by ADR 0013). Phase 5.2
  (`DynamicPropertyRelationSchema.callstack` deletion) deferred — see ADR.
- **Property authoring unification** — landed; authoring reference in
  [`schema-dsl.md`](schema-dsl.md) §7 "Properties & Relations" + design rationale in
  [ADR 0013](adr/0013-property-authoring-unification.md). One typed `Property<T>`
  handle (`Value` / `Dynamic`) across declare / write / read / project / sort;
  `Relation` is now a fieldless marker (`from`/`to` belong to
  `ServiceDataRelation`) and a property *is* a `Relation`; wire selectors renamed
  `*PropertyRelation`. Graduated the `core-value-property.md` +
  `core-property-relation-unification.md` proposals.
- [`proposals/core-sql-persistence.md`](proposals/core-sql-persistence.md) —
  Spec'd. Durable SQLite blob-KV `ObjectStore` (raw `androidx.sqlite`, no
  ORM, no SQL migrations) behind a sync write-through cache; `InMemoryObjectStore`
  kept for tests/quick-swap, JS keeps IndexedDB. Independent of
  `core-background-processing.md` modulo the frozen observer seam.
- [`proposals/core-logging-facade.md`](proposals/core-logging-facade.md) —
  Draft. A level-gated `Log` facade in `:core` in front of `:logging-service`:
  the service becomes one sink (alongside a console sink), call sites depend only
  on the facade. Adds the production "don't log in prod" guard (release drops
  `debug`/`info`) and a second, freetext `DiagnosticLog` stream — kept separate
  from the PII-free typed `ErrorEvent` stream — for per-app, user-reviewed log
  export. Phase 0 (SyncScheduler drain-spam collapse) done; facade not built.
- [`proposals/core-schema-evolution.md`](proposals/core-schema-evolution.md) —
  v1.0 — **E0–E4 all shipped** (E2 through E2j; E3-1 §9.3.1; E4-1…E4-5 §8.3.1–§8.3.7).
  ⚠ Compaction is built but never runs — it has no production DI line (§8.3.7).
  The post-release replacement for the lockstep + wipe + 426 posture: one per-service version vector enforced at two
  boundaries — an **Announce operation** at IPC session start (client declares its
  expected vector, proxy admits/refuses with a symmetric typed reply; frozen-forever
  envelope; re-announced on every reconnect) and a realm **epoch vector** (E2E metadata,
  write-triggered, pull-fence/push-allow) for cross-device skew. Blocks get a version
  stamp inside the ciphertext (**E0 — the one irreversible debt accruing today**); local
  stores migrate eagerly at open, relay history upcasts lazily at read and is never
  rewritten, and compaction advances the readable floor — which *is* the data cut-off
  mechanism. Both seams run one artifact: a `SchemaMigration` = per-type
  `CompositeMigration`s carrying frozen `from`/`to` schema snapshots (positional decode
  needs the producing field list), field-mapped by id with forced accounting and **no
  default values**. Versions live on the `ServiceSchema`; floors derive from the
  migration chain; dependencies derive from `include(...)` edges. The relay's 426 gate is
  **kept but re-keyed** onto a separate `RELAY_PROTOCOL_VERSION` accepting a range
  (E2e, as built §7.8) — payload versions have left the relay entirely, so **a
  `TENSHU_STORAGE_FORMAT_VERSION` bump no longer owes a relay redeploy**. Op cut-off = proxy-side deregistration + typed error; polyfills = per-session
  adapters, deferred until third-party/SDK pressure exists.
- [`proposals/core-schema-skew-surface.md`](proposals/core-schema-skew-surface.md) —
  v0.1 — design, no code. The user-facing half that `core-schema-evolution.md` §7.3
  deferred. Audit finding: skew is detected **twice** (the `AnnounceSession` app↔proxy
  verdict, which already names its own direction, and the `SchemaFenceStatus`
  device↔realm fence) and rendered **zero** times — so this is presentation work, not
  detection work. ⚠⚠ The app to tell the user to update is **not** the app the banner
  appears in: on Android/desktop one Control proxy serves every app, so a fence means
  "update Control"; under iOS route-1 each app owns its proxy, so it means "update this
  app". Resolved by having the proxy stamp `owningAppId` onto the fence row rather than
  letting clients infer topology. Recommends **against** a blocking "update to continue"
  gate (§5): it has no target on desktop and would aim at the wrong app on Android.
  SK-1 (surface the announce verdict) stands alone.
- [`proposals/core-deterministic-invalidation.md`](proposals/core-deterministic-invalidation.md) —
  v0.4 — design, no code; re-verified against master 2026-09-23, corrected under review over two
  passes 2026-09-24 (originally drafted 2026-08-05). Reactive reload signals are collapsed by a tuned
  400 ms debounce, where the sync plane collapses the identical fan-out deterministically — by
  lane/task identity and `WriteOrigin`, both of which reach the proxy and are discarded. ⚠⚠ **Read
  §0.0: the diagnosis stands, every proposed remedy is down, and P0 is the only unblocked phase.**
  The lane half is contradicted by shipped design (§0.2): `drive-sorted-folder-paging.md` B4
  added a 3 s *ceiling* because a listing must rebuild **during** a long burst. The origin half is
  withdrawn (§0.6): `WriteOrigin.SYNC_INGEST` marks *"do not re-broadcast"*, not *"my own echo"* — it
  covers a peer's change, this device's echo and a joiner's first-ever snapshot rows alike. ⚠⚠ And
  the *corrected* design took two further P1s (§0.7): `hlc.deviceId` proves **authorship, not
  redundancy** — a device rehydrating from its own relay history authors its own first-time rows, so
  suppressing on it would hold a listing empty for a whole store recovery, and nothing today proves
  "this exact write is already materialised" (`SyncMetadata` records only the wire, by construction);
  and the coalescing rule "any non-`SYNC_INGEST` member wins" would re-lose peer writes at
  aggregation, corrected to *suppress only when every contributing write is proven redundant*.
  ⚠ §2's measurements are carried forward from 2026-08 and were probed against the unprojected chain
  Drive no longer observes (§0.4); the ~280 ms wave the constant is sized for still has **never**
  been observed. Useful now as a record of what was checked and what turned out wrong.
- [`proposals/core-operation-task-return.md`](proposals/core-operation-task-return.md) —
  v0.2 — direction settled (§8), no code, not scheduled. Terminal operations declare
  their return as the existing sealed `Interactor` family (`TaskInteractor<TList<E>>`)
  instead of an opaque `RequestObject`, so the RO-factory wiring gap that crashed
  streaming video (and re-fired twice after its first fix) becomes unrepresentable and the
  caller-asserted `asTaskInteractor<T>()` disappears from ~113 sites.
  ⚠ This entry previously described a `Task<out T>` **schema** type with
  `TerminalTask`/`StreamTask`/`AsyncTask` subtypes — that was the v0.1 shape and §8.2
  **rejected** it (lattice growth, a TS-3 name collision, and the container never
  crosses the wire so it is not a `Data`). Schema and wire are untouched.

### `sync-` — sync subsystem changes

- [`proposals/retention-quota-model.md`](proposals/retention-quota-model.md) —
  v0.5; P1/P1b-1/P2/P3 built, P3 behind two per-plane flags, both ON in production since 2026-10-24. The umbrella
  model for three storage tiers with three *opposite* policies: an ephemeral
  delivery log, durable content (reclaimed only on account lifecycle), and an
  evictable local cache. ⚠ §2's taxonomy is the load-bearing part — conflating
  tiers 1 and 2 was the time-based blob GC data-loss bug. ⚖️ P3 owes counsel sign-off.
- [`proposals/relay-metadata-minimisation.md`](proposals/relay-metadata-minimisation.md) —
  v0.4, audit + phased plan. What the relay learns from envelopes and how to shrink it.
  Phases 1 (EI-4 one-bit tombstone type), 3 (`fetch-entity-blocks` by wire id) and 5 (keyed
  property tags) shipped with CR-2 B3–B6; call logging is narrowed (no personal data in relay logs), but path ids are still logged.
  ⚠ The `Device` tombstone is the **only** relay revocation path, so it stays until EI-4 §5b's
  prerequisites hold. D13 is the pre-release cut; its item 6 (Fx) shipped as
  `sync-envelope-authentication.md`.
- [`proposals/sync-envelope-authentication.md`](proposals/sync-envelope-authentication.md) —
  v1, built, part of the protocol-1 release baseline. Tombstones carry a realm-key HMAC and a
  `SyncBlock`'s envelope is bound into its AEAD; ingest verifies **before** it observes the HLC.
  ⚠ The tag covers the **wire** form, so it is computed at drain, never at enqueue. ⚠ `DeleteBlock`
  cannot be migrated per-type (it is embedded in an op — nested reshape), §5.
- [`proposals/sync-entity-identity.md`](proposals/sync-entity-identity.md) —
  v0.1, design only. ⚠⚠ The relay-visible entity key is a 32-bit, unsalted
  `String.hashCode()` of type + key: a confirmation oracle for every path- or
  vocabulary-keyed type, and a collision at ~2 % of realms with 14 k rows. EI-1 is a
  realm-keyed HMAC wire id (generic; keeps convergence, which a random id would lose);
  EI-2 is a stable `File.fileId` on `Syncable.identity` so a move is metadata on the wire
  and drops no content edge. Not `RealmData`, which is a fieldless marker by design.
- [`proposals/content-plane-cr2-quorumless.md`](proposals/content-plane-cr2-quorumless.md) —
  CR-2 without a quorum. ⭐ Chose candidate B (§10): pushes carry their content edges in the relay's
  transaction, and the relay frees content T after its last edge, on its own clock. A late reference
  gets `content_freed`, then a re-upload or "deleted on another device". Content-bearing deltas are
  never batched. Candidate A (a device-progress bounded wait) is kept as the record. Supersedes
  `content-plane-refcount.md` §9.14's option 1.
- [`proposals/content-plane-cr2-b2-audit.md`](proposals/content-plane-cr2-b2-audit.md) —
  CR-2 phase B2: which content-bearing writers can name their edges at push time, and how content
  properties converge. Not clean: eleven gaps; seven of its eight questions answered (2026-10-01/02, Q5 open).
- [`proposals/content-plane-cr2-b3-wire-spec.md`](proposals/content-plane-cr2-b3-wire-spec.md) —
  CR-2 B3's wire: edges on the push (`push-edged`), cascades on the tombstone, reaps at the folder
  tombstone's HLC, and one HLC-ordered register per `(holder, property)` at the relay. v0.2,
  decided 2026-10-02: a hard cutover with a relay reset (no migration machinery); gates B3c/B3d.
- [`proposals/content-plane-cr2-b4-spec.md`](proposals/content-plane-cr2-b4-spec.md) — CR-2 B4: the FREED
  state, `content_freed` refusal, the atomic REVIVE, local retire with a notice, adds before removes.
- [`proposals/content-plane-cr2-b5-spec.md`](proposals/content-plane-cr2-b5-spec.md) — CR-2 B5: the
  unreferenced clock, the G2 sweep (off / dry_run / armed), legacy free routes closed when armed, quota exclusion.
- [`proposals/content-plane-refcount.md`](proposals/content-plane-refcount.md) —
  v1.0 — CR-0…CR-5 shipped, CR-2 the only phase left. Reopens the content-plane refcount
  that ADR 0017 D6 closed (and ST-3b silently overrode): a relay-*derived* refcount is
  still HR#1-impossible, a client-*declared* one is not. ⭐ The primitive is a
  **holder-keyed edge set** declared at the sync layer's emit and tombstone seams, so no
  `OperationImplementation` participates. ⚠⚠⚠ The sweep is **live on Android and desktop**
  and frees only content the relay has a *description* for — so it delivers *no new
  orphans* and does **not** reach the existing pile (§9.9); that is CR-6. ⚠⚠ §6c.1: under
  ADR 0006's recreate semantics a naive free-at-zero is data loss, so a recreate must
  re-declare.
- [`proposals/bounded-store-cache.md`](proposals/bounded-store-cache.md) —
  kernel slice shipped. Bounded write mailbox + size-tiered read-through in
  `SqliteObjectStore`, closing the ~8k–12k-file thumbnail ceiling by
  construction. ⚠ The JS twin is deliberately forked (it cannot do synchronous
  read-through at all).
- [`proposals/drive-folder-files-dangling-member.md`](proposals/drive-folder-files-dangling-member.md) —
  investigation (2026-09-13): a folder's `files` list row referenced two member
  rows that do not exist. The **mechanism is established** — two writer batches whose
  `COMMIT` failed and were dropped silently, which the fix closes (retry, then a loud
  drop); the two mechanisms the report named are ruled out by test, and the members are
  named from their hashes. ⚠ The **trigger** — what took the write lock — is open and
  probably unrecoverable. The store may now be repaired or resynced (§7.2 (6)).
- [`proposals/core-paging-scroll-restoration.md`](proposals/core-paging-scroll-restoration.md) —
  ✅ complete (SR-1…SR-5). A paged listing used to lose its place across a
  detail round-trip; the fix is `cachedIn`, one line per view model. ⚠⚠ SR-1
  turned the standing ban on `cachedIn` into a test and **it no longer
  reproduced**; ⭐⭐ SR-4 found the ban had never even been universal (§4.3).
  The three reactivity guards are the live contract — if one goes red the ban
  is back. ⭐ Q1 (retention) measured and closed: 308 B/row, 1.5 MB for 5 000
  rows — and the question had been written on a false premise (§5).

- [`proposals/sync-backend-relay-plan.md`](proposals/sync-backend-relay-plan.md) —
  Spec'd. Sync relay engineering plan: device-key JWT auth, `SyncMailbox` fixes,
  recovery flow, Phase 4 deployable relay. **§3.3 (Phase 3.3) and parts of §4.2 are
  superseded by `sync-relay-api.md` once the device-management refactor lands.**
- **Synced device management** — landed; protocol semantics in
  [`sync/design.md`](sync/design.md) §2.2 + [`sync/pairing-flow.md`](sync/pairing-flow.md),
  design rationale in [ADR 0007](adr/0007-sync-device-management.md).
  `Device` is a `Syncable` entity; the relay shrinks to block + ephemeral
  pairing mailbox + master-public-key store.
- [`proposals/sync-relay-api.md`](proposals/sync-relay-api.md) — Spec'd. The
  future relay HTTP API after device-management lands: realm bootstrap, block sync,
  ephemeral pairing with master delegation, long-poll, recovery. Pairs with
  the synced-device-management mechanism above.
- [`proposals/sync-pairing-flow-improvements.md`](proposals/sync-pairing-flow-improvements.md) —
  Companion to `sync/pairing-flow.md`; sized improvements.
- [`proposals/sync-pairing-inverted-direction.md`](proposals/sync-pairing-inverted-direction.md) —
  **Graduated** (P1–P6). `tenshu://offer`: the new device hosts the QR and an existing device scans
  it (the natural web flow — laptop displays, phone scans). Now the timeless spec in
  [`sync/pairing-flow.md`](sync/pairing-flow.md) §4; retained as the design record.
- [`proposals/bip39-decode.md`](proposals/bip39-decode.md) — `Bip39.decode()`
  primitive (recovery-flow blocker).
- [`proposals/sync-device-catch-up.md`](proposals/sync-device-catch-up.md) — Draft.
  ⚠ §11.10–§11.13 are four defects found in one afternoon, all in the walk's *bookkeeping* rather
  than its fetching; **§12 records the options for making catch-up verifiable** (a gap detector,
  relay-computed deltas, a jumpable whole-realm snapshot) and why a detector should come first.
  How a new or wiped device reconstructs state, opened by an incident where a wiped iPhone was
  **write-dead for ~13 h** replaying relay history. Measures the three separable problems (log
  length, ingest throughput, and function-gating rows queued behind bulk history) and **rejects TWO
  index-over-the-log designs** for different reasons — ⚠ read §4 before proposing a third. Surveys
  Kafka / CouchDB / git / Datomic / Cassandra / Yjs / Matrix / SPV (§3), whose consistent lesson is
  that indexes over a log are **derived and rebuildable, not embedded in it**. The primary answer is
  **E4 compaction**, already designed in
  [`proposals/core-schema-evolution.md`](proposals/core-schema-evolution.md) §8.3; the cheap lever is
  **newest-first catch-up via a reverse cursor** (§5.4).

### `commerce-` — paid storage, billing, entitlements

- [`proposals/commerce-billing-plane.md`](proposals/commerce-billing-plane.md) —
  v0.2; §8 Phase 1 + 2a/2b/2c landed. The three-plane decomposition and the
  signed entitlement token that bridges commerce to the relay, so the relay's
  only notion of "paid" is the entitlement (D5) and House Rule #1 holds.
- [`proposals/publishing-commerce-reconciliation.md`](proposals/publishing-commerce-reconciliation.md) —
  decisions D1–D6 locked; reconciles the billing-plane and publisher drafts.
  Two coexisting entitlement carriers: the synced record and a bearer token.
- [`proposals/commerce-accounts-and-sessions.md`](proposals/commerce-accounts-and-sessions.md) —
  v0.7; through Phase 4 incl. client UI, with Ph4d WC-1…WC-6 complete. Account
  lifecycle, email-OTP sessions, self-MoR Stripe money path, admin console, and
  the OSS VAT export. The money-path invariants are in §9.4d.
- [`proposals/commerce-store-link-out.md`](proposals/commerce-store-link-out.md) — decided
  2026-10-23: store builds sell through Apple IAP and Play Billing like OneDrive, Stripe stays for
  the web, desktop and a non-Play APK. Plus the research behind it: link-out terms (2026-10) and
  Apple 3.1.3(f).
- [`proposals/commerce-end-to-end-dry-run.md`](proposals/commerce-end-to-end-dry-run.md) —
  CE-1…CE-7 shipped; walks the whole flow locally before real Stripe. Procedure
  in [`how-to/commerce-dry-run.md`](how-to/commerce-dry-run.md).
  ⚠ `TENSHU_BILLING_MOCK` defaults **true** in non-release builds, so an
  ordinary run exercises a fixture, not `:commerce-service`.
- [`proposals/commerce-otp-delivery-feedback.md`](proposals/commerce-otp-delivery-feedback.md) —
  v0.1; §2 shipped (typed `send_failed` / `rate_limited` instead of one generic
  message), §3 design for the bounce channel. ⚠⚠ A send the relay **accepts and
  later bounces** — a full mailbox — is invisible to every synchronous path. ⚠ `/claim` — the one route that had
  to swallow its send failures or become an enumeration oracle — was removed 2026-10-13.

### `web-` — web track changes

- [`proposals/web-extension.md`](proposals/web-extension.md) — Browser
  extension specification: install, master-key holding, content-script bridge.
- [`proposals/web-onboarding-ux.md`](proposals/web-onboarding-ux.md) —
  Web onboarding flow specification: screen-by-screen UX.
- [`proposals/web-about-tenshu-public.md`](proposals/web-about-tenshu-public.md) —
  Public-narrative content design for `tenshu.app` home route.
- [`proposals/web-storage-status.md`](proposals/web-storage-status.md) — **Decided,
  no code.** Wiring `StorageService` into the apex in-process runtime so `StorageStatus`
  is produced on web (consumption is identical to file-service; production needs a storage
  `JsRuntimePlugin` + the entitlement-backed provider). Decision: build all three buckets.
  The Drive subdomain only *consumes* it — see `drive-web-app.md` B2c.

### `control-` — Control app changes

- [`proposals/control-background-work-off-fgs.md`](proposals/control-background-work-off-fgs.md) —
  Option B for Play's FGS policy: Control's `dataSync` FGS only for user-started work (a flag over
  IPC), everything automatic as Control's own WorkManager jobs. Carries the 2026-10-24 API 37
  measurements: standby buckets, network under a client's job vs Control's own, EJ quota, and the
  promotion-refusal storm.
- [`proposals/control-onboarding-ux.md`](proposals/control-onboarding-ux.md) —
  Control onboarding & recovery UX (Android + Desktop counterpart to
  `web-onboarding-ux.md`).
- [`proposals/control-app-registration.md`](proposals/control-app-registration.md) —
  Spec'd. How an app becomes *installed in the realm* and how it is removed: the
  synced `AppRegistration` record (per `(appId, deviceId)`), proxy-written silent
  first-run registration on the IPC handshake, and per-device-default removal +
  *remove from all devices* + OS-uninstall hint. Backs the Apps section in
  `control-ia.md` §4; layers on `tenshu-rei.md` §3.6 + `desktop-ipc.md` §3.
- [`proposals/control-cross-app-open.md`](proposals/control-cross-app-open.md) —
  Design, no code. The missing verb on the *other* side: *show me this row in the
  app that owns it*. `OpenTarget(DataId, locator)` mediated by Control — the
  address is the **data**, never the app, so the handler stays Control's
  resolution today and the user's choice once `tenshu-rei.md` §2.2's primary
  handler lands. Unblocks Mia's inert citations and `timeline-photos.md` §10.5.
- [`proposals/control-data-reset.md`](proposals/control-data-reset.md) —
  Draft. The missing verb: *delete the data*. A generic `DeleteRealmData` over
  `RealmData` types, surfaced in Control's Data section, with App Detail linking
  into it. Decides that the unit of reset is a data **type**, not an app (apps
  genuinely share rows — Timeline writes Fit's workouts), and records the
  `deleteData`-does-not-tombstone trap.
- [`proposals/control-subscription-ux.md`](proposals/control-subscription-ux.md) —
  Subscription, checkout, and entitlement-arrival UX inside Control (Android Play
  Billing + Desktop Stripe). Slots between local-unlock setup and the
  recovery-manifest picker on the new-realm onboarding path. Consumes the Phase 1
  mock surface in [`tenshu-publisher.md`](proposals/tenshu-publisher.md) §10.

### `drive-` — Drive app changes

- [`proposals/drive-sorted-folder-paging.md`](proposals/drive-sorted-folder-paging.md) —
  **complete** (SP-1…SP-6, 2026-09-15). Drive has one folder listing: always sorted, folders
  always first, Name ascending by default, case-insensitive — natural order is gone (D5/D6). It
  used to load the whole folder as one page under any sort; it pages now, and on a phone first
  paint went 2.8 s → 1.1 s and stopped growing with the folder (§10.7). ⚠⚠ A new `:core` op is
  fenced by a storage-format bump, not by dispatch — an older proxy fails *decoding* a nested op
  (§10.2). ⚠ The projected change observer is now the dominant per-open cost (§10.7).
  ⭐ Folders-first needs no second stream — it is a type-test first sort key, and a `groupBy`
  cannot page. ⚠⚠ Four blockers, not the one `core-ordered-window-fusion.md` recorded: no
  folder predicate, value-property keys refused on `File` rows, multi-step keys refused, and
  offset drift under newest-first (a duplicate `path` key crashes the list). Recommended: widen
  `classifySortKey` by three narrow key kinds (type test, value property on a composite row,
  `path.lowercase()`) rather than add a paging primitive or a look-behind — decided.
- [`proposals/drive-upload-stop-and-reclamation.md`](proposals/drive-upload-stop-and-reclamation.md) —
  v0.3; ST-0b + ST-1 shipped, the rest designed and unscheduled. One spine: the
  write path has no brakes at either end — a backup upload cannot be stopped, and
  nothing ever reclaims what it wrote. ⚠⚠ §4.4 is the load-bearing part —
  per-file reclamation is *forbidden* by House Rule #1, not merely unbuilt, so a
  client-driven delete (ST-3b) is weighed before the time-based sweep (ST-3d).
  ⚠ §4.6: two coupled disk-full failures, neither alerting.
- [`proposals/drive-shared-realm-file-model.md`](proposals/drive-shared-realm-file-model.md) —
  landed through Phase 3d.2b. Folder sharing's two-link model: `SharedFile` is
  content *inside* a realm, `Placement` is the per-member realm mount. The read
  surface is dedicated ops, not the chain DSL (§6 records why).
- [`proposals/drive-folder-link-sharing.md`](proposals/drive-folder-link-sharing.md) —
  v0.5, design settled, no code. Account-less Mode A for whole trees via one
  `K_share` plus an encrypted manifest, which also closes the plaintext-filename
  leak to the relay. Sequenced after `file-content-addressing.md` Ph0–2.
  ⚖️ Counsel review is a launch prerequisite.
- [`proposals/contact-invitation-handshake.md`](proposals/contact-invitation-handshake.md) —
  CI-1…CI-4 shipped. The sealed-box invitation protocol behind *Manage access*;
  identity is acquired only by mutual handshake, never a directory. `K_link`
  never reaches the relay in any form. Threat model: A24/A25, §8.13.
- [`proposals/shared-realm-membership-grant.md`](proposals/shared-realm-membership-grant.md) —
  GR-1…GR-7 complete. Cross-user join: capability chain, sealed grant mailbox,
  explicit accept, and Kr rotation on removal. Security posture lives in the
  threat model (A26/A27, surface #12, §8.14), not here.

- [`proposals/admission-revocation-names-the-capability.md`](proposals/admission-revocation-names-the-capability.md)
  — **v0.3 — decided (F), first slice shipped.** A revocation names a *device at an epoch*, not the
  *capability* it revokes, even though every admission mints a fresh A33 key — so a security decision
  rides a counter that is not monotonic with the events it separates (the born-revoked re-admission bug).
  ⚠⚠ `>=` cannot become `>`: it is protecting a *policy* ("a delegated admitter must not undo a
  revocation") with a counter as proxy. **Option F chosen 2026-08-19 (§4.3): a revoked identity never
  returns**, on the finding (§4.1) that F and the key-scoped option E are **security-equivalent** —
  an attacker with A1 need not re-admit the old identity at all — so E's machinery was bought purely
  with convenience. F makes the ordering bug *unrepresentable*: §7 F1 replaces the epoch comparison
  with set membership. ⚠⚠ F's prerequisite is the **iOS reinstall gate**
  ([`ios-identity-reinstall-gate.md`](proposals/ios-identity-reinstall-gate.md) RG-1): the Keychain
  outlives the app, so a revoked iPhone returns with the *same* `deviceId` and is refused forever —
  today that path silently self-heals. ⚠ An earlier draft called an *orphan reaper* the prerequisite;
  that was **wrong** and is corrected in §4.3 — `RevokeDevice` deletes the row. Shipped: the revoke
  dialog's acknowledgement checkbox, and copy that no longer promises a re-add. ⚠ §5 is the lifecycle half — the stuck cell is
  *revoked × identity survives*, which is **iOS-only** (Android's uninstall takes the identity), and
  it is why this needs [`ios-identity-reinstall-gate.md`](proposals/ios-identity-reinstall-gate.md)
  to be humane.

- [`proposals/drive-web-app.md`](proposals/drive-web-app.md) — **In progress.**
  Bringing Drive to feature parity on the web (`drive.tenshu.app`). Locks the
  thin-SDK + TypeScript-rebuild architecture (no `drive-shared-logic` in the JS
  bundle), triages which native features apply to web, inventories the per-feature
  SDK boundaries to add, and phases the work A (subdomain shell + postMessage IPC)
  → B (SDK boundaries) → C (React UI). Executes Phase 8 of [`web/roadmap.md`](web/roadmap.md).
- [`proposals/drive-web-mode-b-sharing.md`](proposals/drive-web-mode-b-sharing.md) —
  **Decided 2026-06-25, no code.** The web form factor for Mode-B folder sharing +
  membership grant (resolves `drive-web-app.md` §5 Q2): v1 = deep-link invite →
  `UNVERIFIED` (TOFU) contact → explicit grant showing the fingerprint, no camera/QR/SAS;
  camera-QR + 6-digit SAS is an additive verify-your-contact v2. Phasing MB-1..MB-5.
- [`proposals/drive-folder-upload.md`](proposals/drive-folder-upload.md) —
  Phase 1 + Overwrite implemented (2026-05-15). Client-orchestrated recursive
  folder upload (forced by SAF); Skip/Keep-both/Overwrite conflict
  resolution (Overwrite = atomic `ReplaceFile` op, not delete+reupload, §9);
  ambient status bar. Phases 2–3 outstanding; Phase 3 defers to
  `core-background-processing.md`.
- [`proposals/cross-app-file-access.md`](proposals/cross-app-file-access.md) — **P3 built
  (2026-11-01): Drive is a read-only root in the Android system file dialogs, hosted by Control
  (§9).** Decisions D-A–D-H locked 2026-08-02; the rest has no code. Opened off `contacts-app.md` §5. Two opposite
  gaps under one name: **inward**, no Tenshu app can pick a file *from* Drive (live instance —
  Fit's Takeout import sends you to SAF even when the zip is in your Drive); **outward**, Drive
  does not appear in the OS file dialogs the way Google Drive/OneDrive do. ⭐ Most of the
  substrate already exists: the data path needs **no new IPC** (any app may link
  `:file-service-client`), `tenshu-rei.md` §2.2 already defines a **`Picker` capability class**
  as "default-grant per-invocation through a system-mediated picker in Control's UI" (= pick *is*
  the grant), and Drive already ships a `ContentProvider` serving a **streaming proxy fd**
  (`StreamingVideoProvider`) — which is exactly what `DocumentsProvider.openDocument` returns.
  ⚠⚠ **But nothing is enforced**: `CapabilityClass` never reaches the dispatch path and Track A
  A4 never writes an `AppRegistration`, so any app linking the file client already reads every
  file — **shipping a picker adds convenience, not security**, and the doc keeps picker UX and
  grant enforcement on separate tracks so the distinction stays honest. **v0.2 resolves D-A–D-H.**
  ⭐ The decisive finding for D-A: **reading files is `Ambient`, not `Picker`**, in Rei's own
  model — so a read-picker is *not* a permission boundary today and cannot be one until Q12's
  capability token lands; hence a shared in-process UI module with a swappable seam (Control has
  no file browser, and iOS route-1 has no Control in the loop). D-B returns a `PickedFile`
  carrying the move-immune `contentId`. D-C hosts the `DocumentsProvider` in **Control** — one
  rule, *the provider lives with the proxy*. D-D corrects v0.1: `queryChildDocuments` runs in the
  **system picker process**, so there is **no bulk filename leak** to apps and Android's picker is
  already the mediator Rei wanted; SAF grants + one master switch. D-E defers iOS behind
  replica scoping (⚠ 107k rows cannot fit an extension). D-F closes a v0.1 guess — **Synced
  Folders does not solve desktop** (ingest only). D-H keeps v1 read-only, since `uploadFiles` is
  `Picker`-class, and ⭐ saving then arrives free via `ACTION_CREATE_DOCUMENT` against P3.

### First-party apps

- [`proposals/calendar-app.md`](proposals/calendar-app.md) — **v2.2, CA-0…CA-8 built** (requirements
  settled at v0.4, 2026-08-05; Q1–Q14 all answered). Opened off `milestones.md`
  §Calendar. Scopes "80 % parity
  with the stock apps" as a claim about the *daily loop* (§2), settles the milestone's named
  schema question by precedent (§3 D1 — `Event` native + a lossless `EventSource` sidecar, the
  `ContactSource`/vCard shape), and sizes the UI (§5). ⭐ The alarm/punctuality machinery is real
  and reusable but is **not the Hub's** — it is `:sync-service-proxy`'s `punctual` class, born in
  RE-D5. ⚠⚠ What must *not* be reused is the part that looks most relevant: `ExecutorClaim`
  elects **one** device to fire a routine exactly once, and a reminder must fire on **every**
  device. ⚠⚠ Reminders do not work on two of three platforms yet — desktop/iOS/hub all bind
  `NoOpPlatformScheduler`, and there is **no OS-notification presenter anywhere in the repo**
  (`:notification-service` stores *synced in-app rows*); Calendar forces both gaps open, and both
  are reusable platform slices, not Calendar internals. ⚠ iOS needs the opposite mechanism
  entirely — pre-armed `UNUserNotificationCenter` notifications (⚠ 64-pending cap), so: *Android
  wakes then decides; iOS decides in advance*. ⭐ The sharpest finding is a permission in the
  wrong process — `PunctualAlarmScheduler` declines `USE_EXACT_ALARM` because Play restricts it
  to alarm/calendar apps, and Calendar **is** that app, but *Control* holds the `AlarmManager`
  (Q6). UI: ~70 % of the event editor is free from M3 (`DatePicker` already proven in
  `:fit-shared-ui`), but the month grid and week/day time-grid have no component on any platform
  and get written twice (B-1: no CMP UI ships on iOS) — ⭐⭐ mitigated because the expensive
  half, **overlap layout, is pure math** and belongs in Compose-free `:calendar-shared-logic`
  (the `:basemap-core` **D7** precedent). ⚠ No RRULE or iCal library exists in the repo; we write
  the expander. Estimate **~9–12 k LOC ≈ 1.5× Contacts**; phasing CA-0…CA-8; Q1–Q14 open.
  **v0.2 resolves the one open ambiguity** — the second consumer is the **Mia assistant**, and
  the driving gesture is *recording* an event by utterance, i.e. a **write** path (§6). ⭐ It
  needs **no new primitive**: MQ-D2 already puts the query layer proxy-side, so the app-to-app
  invocation gap (`contacts-app.md` Q1 → `tenshu-rei.md` Q12/Q16, also wanted by
  `cross-app-file-access.md` D-A/D-B) is **not on this path**; and the hook is **CA-1**, not
  CA-8 — ordinary CRUD ops over a native schema *are* the integration, which promotes D1 from
  tidiness to a requirement. ⚠⚠ But Calendar forces four things open in the Mia design: MQ-D4's
  intent table is closed with no calendar entry; ⭐ `CreateEvent` is the intent **most exposed to
  MQ-D4's own warning** that time expressions are per-language and localisation-bearing (a
  recording intent is *almost entirely* time expressions — though §3's `(LocalDateTime, tzid)` +
  RRULE model gives the grammar a **typed target that already exists**); MQ-D6 is explicitly
  **read**-scoped and "may read Calendar" ≠ "may add to Calendar"; and MQ-D1's *"Mia cannot
  hallucinate"* is a **read** property — a misheard slot writes a row that persists, syncs and
  fires a reminder three weeks later, so MQ-D7's confirmation cell becomes non-negotiable
  (⭐ an event is *reversible*, which is what makes it the right first write intent; ⚠ `DeleteEvent`
  is not). Adds one Calendar-side requirement: the ops must be **complete headlessly** (default
  calendar / duration / reminder as `:preference-service` rows, designed in at CA-1, §6.4).
  ✅ All four landed the same day in [`proposals/mia-query-layer.md`](proposals/mia-query-layer.md)
  **v0.2** — new **MQ-D4a** (both intents + the grammar seam: grammar in `:mia-service`, types in
  `:calendar-service-common`), **MQ-D6** grown to `(service, access)` with `read` the default and
  write opt-in asked at the first `CreateEvent`, **MQ-D1** annotated as a read property, **MQ-D7**
  requiring the cell to confirm the *resolved* row rather than the utterance, plus MQ-2/MQ-3
  hooks and a new threat-model duty.
  **v0.3 settles the remaining questions** (§8 is now a decision table): D1 + the `EventSource`
  sidecar confirmed; recurrence stored as an **RRULE string** (nothing queries by rule, so
  future rule features cost expander code, never a schema reshape); `SCHEDULE_EXACT_ALARM` in
  Control asked contextually, ⚠ `USE_EXACT_ALARM` stays undeclared everywhere; ✅ `UICalendarView`
  **verified** available (all eight iOS shells pin `deploymentTarget: iOS "16.0"`); ICS
  subscription in at CA-7 per-subscription opt-in; lock-screen reminders **show details** with a
  per-calendar `sensitive` flag. ⚠⚠ **Q8 corrected a v0.2 error**: desktop Control *is* a
  resident daemon (a LaunchAgent autostarted hidden at login, the OneDrive model), so residency
  was never the issue — the issue is that `ScheduleDrainLoop` **counts ticks instead of
  consulting a clock** (deliberate, for virtual-time tests), so a pending one-shot loses to
  machine sleep *and* re-waits its full delay after any Control restart, since `accumulatedMs` is
  an in-memory map. ⇒ Android + desktop ship together.
  ⭐⭐ **v0.4 (§4.6) then superseded that fix**, after asking whether macOS has an official
  mechanism: it has **two**, split on the same line as iOS. For the *notification* —
  `UNUserNotificationCenter` + `UNCalendarNotificationTrigger`, which ⭐⭐ **is already running in
  this repo**: `control-desktop/macos/TrayAgent/PendingRequestNotifier.swift` authorizes, posts
  with stable ids and diffs add/remove against a known set, today with `trigger: nil` — a
  scheduled reminder is the same call with a trigger, and even the bundle-identity trap is
  already guarded (`Bundle.main.bundleIdentifier != nil`, "lights up in the signed `.app`"). For
  punctual *code* execution — launchd `StartCalendarInterval`, which coalesces missed intervals
  and runs at next wake, over the `LaunchAgentManager` plist machinery that already exists. ⚠
  Desktop packages **Dmg/Pkg only**, so the desktop answer *is* the macOS answer. ⇒ the "iOS
  decides in advance" principle generalises to **Apple platforms**; the hand-rolled `dueAtEpochMs`
  path stops gating Q8 and stays only as the fix for the generic in-process punctual class. ⚠
  Verify the 64-pending cap on macOS; cancellation becomes a real duty once the OS holds the
  schedule. ⚖️ Only the two permission-prompt *copy* decisions are left to a human.
- [`proposals/calendar-external-sources.md`](proposals/calendar-external-sources.md) — **v0.3
  (2026-10-13): *Copy once* from Android's calendar store built (CX-0 + CX-1a, §11).** One-way
  import of the calendars a user already has, as *Copy once* or *Keep in sync*. Maintainer direction: the OS calendar store first on every platform (⭐ on Android that
  already includes Google, Outlook-with-sync and DAVx5 — no sign-in of ours), a direct Google
  sign-in only where there is no OS store, Microsoft later. ⭐ A mirror is a CA-7 subscription with a
  different fetcher. ⚠⚠ The device binding is the election; mirror ids must be namespaced (invites
  share a `UID`); a refresh writes only what changed; the all-day trap runs in opposite directions
  on Android and EventKit. ⚠ The reader is the **app's**, not Control's (Q1, §4.4).
- [`proposals/contacts-app.md`](proposals/contacts-app.md) — **v0.2, pre-design, no app code,
  all questions answered (2026-08-02).** Decision record seeding the Contacts design handoff — the **v2** of
  `contact-service-identity-model.md`. ⚠ Corrects a common premise: Drive's sharing is already
  functionally complete (CI-1–4 + GR-1–7), so Contacts does not unblock sharing from *working* —
  it makes it **maintainable** (today a contact can never be renamed, forgotten, or verified
  after the handshake window) and gives the three surfaces `drive-manage-access-ux.md` deferred
  to it a home. ⭐ D4's re-verification turned out to need **no new crypto**: `deriveContactSas`
  is already session-independent (both public keys, not the handshake secret — the Signal
  safety-number shape), so it reduces to one op + a screen. Locks D1 avatars (content-addressed;
  ⚠ v0.1's refcount gate struck by Q3, see below) / D2 parity scope
  (⚠ no *directory* search ≠ no local search) / D3 one badged list / D5 manual contact = a
  source without a key / D7 merge stays user-assisted and last. §5 flags a **new end-user parity
  gap**: nothing lets another app pick a file *from* Drive, and Drive does not appear in the OS
  file dialogs (Android `DocumentsProvider` / iOS File Provider) — its own track.
  **v0.2 resolves Q1–Q7** and, in doing so, corrects three v0.1 errors: ⚠ D1's content-refcount
  gate **does not reach Contacts** — a declared refcount shipped 2026-09-09
  (`content-plane-refcount.md`), but only `Syncable(OnDemand)` properties declare and no avatar is
  one, so a Drive delete still *cannot* break an avatar and the Keep/Move/Delete prompt stays an
  informational note; ⚠⚠ the derived
  **checkmark rule is buggy** — a rotated key is a *new* keyring row, so "≥1 identity VERIFIED"
  leaves a checkmark describing a key the contact no longer uses (now demotes and says so); and
  ⚠ orphaning identities on delete was **reversed to delete-everything**, because Q2a made
  re-verification a purely local six-digit comparison. Also ⭐ Q1 found that `tenshu-rei.md` Q16 +
  Q12 already own app-to-app invocation and already deferred it to v2 — Contacts Q1 and
  `cross-app-file-access.md`'s D-A/D-B are three faces of that one unbuilt primitive.
- [`proposals/maps-app.md`](proposals/maps-app.md) — **Pre-design, no code (2026-07-06).**
  Decision record seeding the Maps — Places (slim) design handoff: locks list-first Places +
  single-pin place detail (routes belong to Timeline), inherits the `milestones.md` basemap
  decisions, and carries D2–D4 (detail-screen ownership / Contribute scope / search) into design.
- [`proposals/drive-xxlarge-thumbnail-rung.md`](proposals/drive-xxlarge-thumbnail-rung.md) —
  A 2048 px `XXLARGE` thumbnail rung and a progressive large
  preview, for viewers on devices without the original (retina desktops, phones in portrait, the
  web lightbox). ⚠⚠ A new rung touches three hand-written lists or silent `else` branches (§3), and
  nothing evicts stored thumbnails locally (§4.7), so the plan starts with a single rung table (X-0)
  and an eviction bound (X-2).
- [`proposals/photos-ios.md`](proposals/photos-ios.md) — **v0.1 — sweep + phasing (PIO-0…PIO-9).**
  The SwiftUI Photos app on route 1, at parity with Android, AI included. ⚠⚠ Nothing iOS compiles
  in the cloud, so every phase puts its decisions in tested `commonMain` and hands the rest to a Mac
  through a `HANDOFF Hn` draft PR (§6). ⚠⚠ Two apps binding an `InferenceBackend` would append to
  one App-Group embeddings log with only an in-process lock, so iOS inference has one owner (§3.3).
- [`proposals/ios-on-device-inference.md`](proposals/ios-on-device-inference.md) — **plan for PIO-7
  (AI-L on iOS); PR-1 (the Core ML session, cache, decoder and the IN-3m-iOS harness) is built and bound nowhere (§9); its status line carries the version.** Native Core ML fp16 through Kotlin/Native's `platform.CoreML`, no ONNX Runtime on
  iOS: on the iPhone it ran ~19 × faster than ORT's CPU provider and its retrieval matches fp32. The model is a
  second catalog entry for the same checkpoint (runtime `coreml`), one tar per `.mlpackage` tower, compiled on
  the phone after the download. IN-3m-iOS (the latency harness) comes first. ⚠⚠ The inference stores
  leave the App Group for Photos' private container (PIO-D10), and every other app binds a store that
  never writes. ⚠ Whether an OS update changes the vectors is not known, so the OS build is in the store's
  fingerprint.
- [`proposals/file-streaming-upload.md`](proposals/file-streaming-upload.md) — **Design, nothing
  built.** Every upload path ends in one `ByteArray` in the proxy's heap, so a file above 2 GiB cannot
  be uploaded and a large video costs its size in memory; the emit and the download already stream.
  Proposes an `UploadSource` seam, one write-and-hash pass into a temp sibling, and desktop hand-off
  by staging. ⚠ The one user-visible change is a decode budget for stills (open question 1).
- [`proposals/media-processing-pipeline-sweep.md`](proposals/media-processing-pipeline-sweep.md) —
  **Decisions made 2026-10-02; the doc's status line carries the version and the count.** ⚠⚠ F0: a deleted
  photo's face template is never removed, on master today (P0f ships first). Everything after a
  photo/video upload: rungs, MOTION,
  backfill, the index pass. ⚠⚠ Each upload decodes the original 4–12× at full size, inline, before
  the next file starts, and the scheduled index pass barely runs. Proposes one bounded decode, a
  device-local derive queue for on-upload indexing (pets slot in there), one media CPU budget;
  phases split ☁/📱/🖥.
- [`proposals/photos-people-and-pets.md`](proposals/photos-people-and-pets.md) — **v0.1 — design,
  no code.** IN-6: a People section in Photos, opt-in face clustering over the user's own library.
  ⚠⚠ The central move is IN-D5's and it is counter-intuitive — people are named **per photo, never
  per cluster**, so clusters stay a local UI convenience and **no biometric template ever becomes
  realm data**. ⚠⚠ Ships the platform's **first** threat-model asset entry for derived inference
  data (§8 drafts it); ⚖️ carries the counsel sign-off `camera-service.md` CD-D7 and IN-D3 both name.
  ⚠ The clustering threshold is this slice's uncalibrated vocabulary (§4) — measured, never a
  literature default. Pets are a later phase with their own model.
- [`proposals/photos-app.md`](proposals/photos-app.md) — **v1.4 — decisions SETTLED 2026-08-21;
  every phase BUILT — PH-1…PH-7, S2 and the shared index (§9–§20).** Screens (three tabs + viewer, place detail, trash) and the gap
  list for `milestones.md` §Photos. ⭐ The headline is that the milestone entry is stale in the
  app's favour: **three of its four platform pieces already arrived by other routes** — EXIF GPS +
  capture time + `File.Category` via `timeline-photos.md` T-P4, Places well past "lite", and
  "search" delivered as three narrow ops (`photosCapturedBetween`, `photosNear`, `photosLike`)
  rather than a full-text service, which exists nowhere in the tree. ⭐ Background ingest is
  half-built too: `:ingest-service`'s `IngestSource` registry is shared and already has two
  registrants (Drive, Fit) — only the scheduler binding (bgproc Ph6/7) is missing. ⚠⚠
  `photosCapturedBetween`/`photosNear` are self-declared **O(files-in-realm) scans with no
  paging**, so the feed rides the chain DSL's keyset page (`inRange` + `sortByDescending` + `take`,
  fused by `core-ordered-window-fusion.md` F2) — **measure before committing**; that is the one
  genuinely blocking gap. ⚠⚠ `ExifGps.kt`'s "iOS has no gallery backup" KDoc is **stale** —
  PhotoKit ingest shipped with `ios-drive-parity.md` P6b and the parser is JPEG-only, so every
  iPhone photo lands with no capture coordinate. Decisions: albums in a new **`:photo-service`**
  (D1, the `MediaLabel`/`PlaceRole` precedent); duplicates shown truthfully, skipped at ingest
  (default on) and reviewed in a Duplicates album — the Apple/Google shape, and the reason the
  `ContentHash` index became v1 (D2); the source picker **duplicated per app** over the shared
  ingest engine, with a `:ui-files` extraction (D3); delete **moves to a realm `/Trash`** with a
  permanent-delete checkbox (D4) — ⚠⚠ which creates two cross-app obligations, since the shipped
  scan ops would keep serving trashed photos to Timeline and Mia (PH-G16) and permanent delete
  reopens the re-ingest loop (PH-G17); ship before the AI features (D5), with §7 mapping IN-3b /
  IN-5 / IN-6 / MQ-5 / cross-app-open / PI-1 to the phases they unlock. **PH-1a as built (§9):**
  the feed page is a chain — `traverse().filter{category}.filter{createdAt.inRange}
  .sortByDescending(CreatedAt).take(n)`, pinned by `PhotosFeedChainTest`; the scan ops are not used.
  ⚠⚠ It found that **`File.Category` was never written by anything** — Drive's own comment called
  it "a vestigial property" and three places re-derived it from the filename — so it is a *derived*
  dynamic property now, resolved by the new `FileCategoryOf` op, needing no backfill and giving
  `ControlFileBridge` a category for the first time (`FileService` 5 → **6**, empty migration, no
  positional reshape). Two primitives fell out: the `File.createdAt` / `File.category` chain
  accessors (⭐ **no predicate in the repo had ever referenced a stored value property**) and
  `TConstantRo` (⚠ typed `TConstant`, never the sealed family — a placeholder subtype would break
  three exhaustive `when`s). **PH-1b as built (§10):** four modules (`:photos-shared-logic` Compose-free, `:photos-shared-ui`,
  `:photos-desktop`, `:photos-android` — no iOS shell yet, its own slice), the day-sectioned feed
  and the viewer. ⚠⚠ The feed distinguishes loading / unreachable / failed / empty in both the
  state machine and the screen, and the render test asserts the empty-library copy is **nowhere on
  screen** in either error state — "no photos yet" for an unreachable proxy tells the user their
  library is gone. ⚠ Photos joins the smoke gate's built set on day one (HR #8) and Control's
  `BundledAppCatalogue` in the same commit — an app missing from that list is refused at the
  desktop socket exactly as hard as an untrusted one, and the only symptom is a UI that spins
  forever. **PH-2 (§11–§12):** `:photo-service` — five modules, `Album` / `AlbumEntry` / `PhotoFlags`, five
  ops — plus the albums UI. ⚠⚠ Membership is keyed on `File.ContentId`, **not** `DataId`: this doc
  claimed a move would be survivable via the reference-migration primitive and the build falsified
  it (a `File`'s `DataId` *is* its path; `DataIdMigrator` only retires the old key). ⚠⚠ Wired into
  Control's `ProxyModule` + all three `PlatformProxyModule`s in the same slice — `inference-service.md`
  §17's rule, which Calendar and inference each learned the hard way. **PH-3 (§13):** places, years
  and videos as *queries* over one feed read — ⚠ never one `photosNear` per place, which would walk
  the realm once per section — with haversine distance (a flat one is right at the equator and wrong
  in Europe). ⚠⚠ The photo→`Place` geocoding hook did **not** ship and is not a Photos slice:
  `ReverseGeocoder` lives in a Timeline *app* module, so Photos groups only by places Timeline
  already named. **PH-4a (§14):** a Settings tab over the shared ingest registry, plus `:app-ingest` — the registry
  client and `DeviceIdentity`, **moved** out of `:drive-shared-logic` so one copy of the
  startup-race fix exists. ⭐ The switch is real: the drain reads `IngestSource.enabled` before every
  pass, so off genuinely stops a source. ⚠⚠ It is a *kill switch*, not the master toggle (the read
  is fail-open), and the header says so rather than implying symmetry. ⚠ `:ui-files` deliberately
  **not** created — one consumer is a detour, not an extraction. **PH-5 (§15):** the trash — deleting is a **move** to `/Trash` (so the bytes, the content id and
  the history stay put and restore is another move), with a `TrashedPhoto` row remembering where it
  came from. ⚠⚠ **PH-G16 closed**: the realm-wide photo reads Timeline and Mia make now skip the
  trash, or a deleted photo would keep illustrating a moment with nothing failing. ⭐⭐ **PH-G17 was
  falsified** — the backup cursor is a monotonic watermark over MediaStore `_ID`, so an uploaded
  asset is never revisited and permanent delete does *not* re-ingest; the gap is rewritten, not
  carried. That is the **third** claim in this doc the build has disproved (§11.2, §13.2, §15.3).
  ⚠ Still owed: **PH-4b** (move the drain behind a narrow `BackupDataPlane` seam so Photos can *add*
  a source), auto-purge (bgproc), the 20 k-row measurement, the iOS shell, the map header, and
  reorder/cover picker.
- [`proposals/place-imagery.md`](proposals/place-imagery.md) — **Design, no code.** A photo for a
  place when the user has none. ⚠⚠ **License, not API, drives the tiering**: open/storable imagery
  is cached like any other asset (Tier 1), restricted/no-store imagery is opt-in and transient and
  may never be persisted (Tier 2) — so the same picture is a different feature depending on its
  terms. ⚖️ Tier 2 is gated on a ToS read and the billing calls. PI-1…PI-3 unstarted.
- [`proposals/place-roles.md`](proposals/place-roles.md) — **Design, no code (2026-07-30).**
  Home / Work as a user-confirmable marker on a `Place`, shared by Timeline and Maps. Infer a
  candidate → the user confirms → the user's answer is final (the `labelSource` precedent
  generalised). ⚠⚠ Shape = a sibling keyed row. `Place` cannot gain a *field* (it is a `RecordPlace`
  operation parameter), but the load-bearing reason is §2.2: a *property* would dodge that
  constraint and still be **silently wiped**, because `RecordPlaceImplementation` saves the bare
  composite and replaces the whole row — the `MoveFile` value-property loss class, on a type three callers
  rewrite wholesale. ⭐ Derives over a **bounded recent window** rather than an
  all-history rollup — recency is what Home/Work actually means, which removes the attribution /
  generation / backfill apparatus and the non-idempotent-aggregate drift class with it.
  ⚠ Corrects a wrong steer: this is **not** an `:inference-service` concern (that seam is
  content-ref shaped), it is a proxy-side `DataObserver`.

- [`proposals/ios-sync-activity-indicator.md`](proposals/ios-sync-activity-indicator.md) —
  **P1 built (2026-10-06), P2–P4 outlines.** One small shared "Syncing…" / "Uploading N of M" capsule for
  the first-party iOS apps, Control included, with the full-width bar kept for problems. Built: Photos shows a
  pull in progress (§3.1, as-built §10). ⚠⚠ Every pull path applies its page through
  `SyncScheduler.applyPage`, or its rows are invisible. ⚠⚠ The reader takes its own runtime's row
  only, so on a store several apps share a sibling's pull shows no capsule (§10.3). ⚠⚠ On iOS the
  photo backup runs in Drive's process, so Photos cannot show an upload count until Drive's figures
  are in the shared store. ⚠ The new status row owes no version bump.

- [`proposals/ios-background-scheduling.md`](proposals/ios-background-scheduling.md) —
  ⚠⚠ The App Group build is not safe to install until S0b is built (§4.1.1). iOS binds
  `NoOpPlatformScheduler`, so every registry item runs only while an app is on screen. ⚠⚠ Until S0 a
  device build had **no App Group**, so there was no shared replica on a real iPhone; every route-1
  process still runs every job under one device id. ⚠⚠ A device build that cannot resolve the
  container now **throws** rather than opening an app-local store. Proposes a **per-item lease**
  rather than an owner app (S1), an
  `IosPlatformScheduler` over `BGAppRefreshTask` / `BGProcessingTask` (S1–S2), background re-arm for
  reminders (S3) and content-free APNs wakes (S4, with a threat-model amendment).

- [`proposals/ios-scheduler-lease.md`](proposals/ios-scheduler-lease.md) —
  Phase S1 of the doc above. ⚠⚠ **Its §5 (the shared store) is superseded by
  `ios-shared-store-sqlite.md`**; read §3, §4 and §6–§9 there. A **run lease** per `dedupKey`, a
  record with a heartbeat written in the store's own transaction, never a lock held for the run; and `IosPlatformScheduler` with one Kotlin wake
  runner over `BGAppRefreshTask` replacing the three hand-written drivers. ⚠ On iOS the sync drain
  is not a registry item, so the lease also wraps `SyncScheduler`'s tick. Reshapes no stored or
  wire type; D5 needs no Keychain change. ⚠⚠ **Its §5 is superseded by the next entry.**

- [`proposals/ios-shared-store-sqlite.md`](proposals/ios-shared-store-sqlite.md) —
  Sub-phase S0b: the iOS shared replica as one SQLite database in the App Group container.
  ⚠⚠ `data_version` never reports this process's own commits, so every own-connection commit (a
  live wipe above all) must update its own cache in the same critical section. ⚠⚠ SQLite does not
  lift `0xdead10cc`: the lock gate and quiesce stay, and quiesce closes every connection. ⚠⚠ Never
  replace or unlink the database file under open connections: a wipe deletes rows. ⚠⚠ **Every
  wipe is chunked** behind `meta.wipe` (D13, 2026-10-06): a resumable job of bounded transactions
  that takes seconds; never an unconditional `DELETE FROM objects`, which cannot be interrupted.
  ⚠⚠ It ships only on a SQLite with the WAL-reset fix (≥ 3.51.3 or a fixed backport); the
  androidx bundled build is 3.50.1 in every release so far, so the iOS store builds its own from
  the vendored amalgamation and depends on no `androidx.sqlite` artifact (D12, §2.1.2).
  ⚠ No build touches a store stamped newer than itself, and an open reads the frozen bootstrap
  marker before any DDL. ⚠⚠ The HLC watermark is bound to the device identity, not the store: a
  same-identity wipe and deleting every app both keep its floor (`meta` and an identity-bound Keychain
  copy), and a new identity voids it. ⚠ It orders computed tips, not their arrival at the relay:
  two apps can still send out of order (pre-existing; relay follow-up, §15.1 R4).
  ⚠⚠ On in every build since B.22 (2026-11-05): no switch, and an old file store is reset at
  launch, never drained; S0b-4's cutover (B.22.8), the file store and the launch reset (B.22.9) are deleted.
  Measurements: [`proposals/ios-shared-store-sqlite-q0-results.md`](proposals/ios-shared-store-sqlite-q0-results.md)
  (Q0 gate zero passed on the phone; the simulator half of the rest is run; of the device-only
  half, the cold wipe, `quick_check`, opens and Q0 (b) are run, §13; §9 lists what is pending). ⚠⚠ A second SQLite in an iOS link is silent, not a
  duplicate-symbol error; only the driver's source-id gate catches it. ⚠⚠ `sqlite3_interrupt`
  does not reach an unconditional `DELETE` of a table (why the one-shot wipe was removed), and a
  damaged `-wal` loses commits with no error (results doc §11): ⚠⚠ an **accepted residual** (R5,
  2026-10-06), detected and logged only (no recovery is designed yet), and only when the loss crossed a
  published tip (`meta.hlc_watermark` below the maximum over the apps' Keychain items, checked before a publish heals it, §5.4).

- [`proposals/ios-store-sqlite-spike.md`](proposals/ios-store-sqlite-spike.md) —
  **A measurement, not a design (2026-10-02).** Answers §15.2 of the entry above: the Android/JVM
  driver `sqlite-bundled` 2.7.1 builds and runs on iOS unchanged, and on an iPhone 17e with 150k
  synthetic rows SQLite beat `FileObjectStore` at every operation measured except reads served
  from the file store's memory. ⚠ The point-read fear inverted: a file read is 212–460 µs, a SQLite
  read 4 µs, though flash-cold (simulator only) the gap is ~3×. ⚠ Not measured: flash-cold reads on the device, two processes, the App Group
  container. ⚠ The phone dropped off during the third pass; the benchmark app is still installed.

- [`proposals/sync-push-wake.md`](proposals/sync-push-wake.md) —
  **v0.2 (2026-10-02), design only; every decision PW-D1–D13 taken (visible notifications: V0 now).** S4 of the entry above:
  the relay asks Apple to wake a realm's idle iPhones with one constant payload, and the woken app
  pulls through the ordinary routes. ⚠⚠ The relay newly holds a **push token** — the first
  relay-held identifier a third party can resolve to a person — and an **APNs signing key** that can
  also send a *visible* alert under the app's name. ⚠⚠ It cannot wake for a shared-folder invitation
  or a shared-realm change without giving up T-GR-05. ⚠ Additive routes (`/sync/wake/token`), so
  **no `RELAY_PROTOCOL_VERSION` bump**. Recommends one token held by Control (a per-app set is an
  app inventory, A37) and a 20-minute floor between wakes. The threat-model amendment is drafted in
  its Appendix A and lands with the relay code.

- [`proposals/ios-per-app-frameworks.md`](proposals/ios-per-app-frameworks.md) —
  **v0.8 (2026-10-15), P0 to P5 built.** One Kotlin framework per iOS app (`:tenshu-sdk-ios-<app>`)
  instead of one `TenshuSdk` for all ten; the umbrella `:tenshu-sdk-ios` is deleted, so Control stops linking HealthKit, location, Bluetooth and Photos (ITMS-90683).
  ⚠⚠ Every app's in-process proxy must keep the **full** service graph (D4): a runtime without a
  service drops its blocks and advances the shared cursor past them.

- [`proposals/ios-route1-replica-scope.md`](proposals/ios-route1-replica-scope.md) —
  **Draft v0.2 (2026-08-02), no code, no decisions.** Every route-1 iOS app keeps a **full copy of
  the whole realm**: Drive holds **107,055 rows** — ~10.4k Fit workouts against **13 file rows** —
  because each app registers every service module. The iOS launch-watchdog fix (an indexed store) bounded what a launch *reads*; nothing
  bounds what a device *keeps*. ⭐ The shape: keep what the **installed apps** need — a device-scoped
  union, which composes with a shared App-Group replica instead of fighting it. Three mechanisms
  already exist: unknown-service blocks are **already** dropped at ingest, `AppRegistration` is the
  synced per-device app roster, and `AnnounceSession` already carries each app's service set.
  ⚠⚠ The real cost is **cursor destructiveness** — widening the keep-set (user installs Fit) needs a
  `since=0` re-sync, which must ship *with* the filter, not after. ⚠ Filtering cannot save
  *bandwidth* (type ids legible only post-decrypt); §6 sketches the protocol-level track that could
  (one manifest block per folder), flagged as likely over-optimisation. **⚠ Phased
  investigate → decide → build, and I1 is a genuine go/no-go**: if ~100k small files cost nothing
  measurable, the right outcome is to do nothing and say so.

- [`proposals/ios-fit-timeline-maps-parity.md`](proposals/ios-fit-timeline-maps-parity.md) —
  **v0.1, P1 landed (2026-08-01).** Companion to `ios-drive-parity.md` for Fit, Timeline and Maps.
  The sweep's finding: **almost none of the gap was view-model logic** — the shared VMs already
  exposed every missing action, so the cheap half was unwritten SwiftUI and closed quickly.
  ⚠⚠ What remains is structural: **no Compose Multiplatform UI ships on iOS at all**, so adding
  `:ui-map` to the umbrella would pull Compose + Skia into every iOS app — the framework-export
  shape belongs to the *first consumer*, and **nothing has drawn a tile yet**. (iOS also bound
  `InMemoryFileDatabase`, so a downloaded region died with the process — **closed 2026-08-03** by
  `IosFileDatabase`, so keep-offline is now buildable rather than blocked.)
  ⚠⚠ **MapKit is never the renderer** (A35: Apple tiles disclose the route's region on every
  detail open) — MapLibre over offline PMTiles is what every user gets.
  ⚠ **Narrowed 2026-08-13**: [`ios-apple-maps-option.md`](proposals/ios-apple-maps-option.md) is
  **accepted**, so an off-by-default, revocable, per-device election may render the three heroes on
  iOS — behind the consent gate only, never as the default.

- [`proposals/ios-control-notifications-and-shares.md`](proposals/ios-control-notifications-and-shares.md) —
  **v0.1, design, no code (2026-08-12).** The other two Compose Control screens with no iOS
  sibling. ⭐ **The inverse of the offline-maps gap:** every view model, mapper, repository and
  test already lives in `:control-shared-logic` (iOS targets, SDK-exported), so these genuinely
  are unwritten SwiftUI — and Home's *ambient* event feed already ships on iOS, so what's
  missing is the **actionable** surface, not notifications-in-general. One catch each.
  **Notifications:** ⚠⚠ iOS binds `NoopNotificationPresenter` *on purpose* —
  `UNUserNotificationCenter` is per-app and every route-1 app runs the same proxy graph, so
  binding `IosNotificationPresenter` in Control would post every Calendar reminder twice.
  Elevation is an ownership decision, not a missing class. **Shares:** ⚠⚠
  `TenshuShareManager.sharedFolders()` still returns `emptyList()` although **GR-1…GR-7 landed
  2026-06-14** — half the screen has been dead on Android and desktop since June, and an empty
  section is indistinguishable from "you have none".

- [`proposals/ios-apple-maps-option.md`](proposals/ios-apple-maps-option.md) —
  **v1.6, ACCEPTED + BUILT 2026-08-13 (AM-1…AM-5a).** MapKit is a *user-elected* renderer on
  iOS, off by default; the rule it narrows rejected MapKit **as the renderer** —
  the option shape was never argued. ⚠⚠ It is licensed **only** behind the
  `MapRendererControl` election, for the three heroes (D6), and the election is per **app**,
  not per device (D9 — route-1 sandboxes). The A35 amendment landed with AM-2: sub-clause (f),
  T-MR-05, surface #18. ⭐ Two facts reopened it: **D10 made our own tile plane
  egress per-view too** (streamed-by-default; the categorical "one download, then zero egress"
  property is now the *opt-in*, not the default), and **no downloaded region means no
  geographic map at all** — ⚠⚠ which turned out to be true of the *design* and false of the
  *code* (§8.3): the resolver asked only whether an archive existed, so a hero outside the demo
  extract drew a route on **empty tiles**, on Android too, until AM-4b taught it coverage.
  ⚠ The consent shape is the
  load-bearing part: `PlaceGeocodingControl`'s off-by-default revocable toggle, **not** an
  onboarding fork (§4). ⭐ §6.5 corrects the reflex that Compose/JVM desktop "can't reach
  MapKit": `macos-helpers/ffi/ObjC.kt` already drives AppKit from the JVM, and the repo's
  pattern (`MacCameraSession`) is *pull the pixels, draw them in Compose* — so a **static**
  map hero is `MKMapSnapshotter` → bitmap, with no window embedding at all. ⭐ **AM-5a probed
  the Maps entitlement and there isn't one to get**: a snapshot renders from a bundle carrying
  no entitlements, while *claiming* `com.apple.developer.maps` without a profile is an instant
  SIGKILL — the hazard is real and entirely self-inflicted (§8.1). §10 keeps the case *against*,
  deliberately: the coverage fix is still a bigger catalog, and MapKit must not become the
  reason it stops.

- [`proposals/ios-identity-reinstall-gate.md`](proposals/ios-identity-reinstall-gate.md) —
  **draft v0.1, design, no code (2026-08-17).** The iOS Keychain outlives the app, so a "fresh
  install" silently resumes an identity — and Control never says so, offers no alternative, and comes
  up **locked** with no explanation. ⚠⚠ **The standard remedy does not transfer:** Signal-shaped apps
  clear leftover Keychain state on first launch because the *account lives server-side*; here the
  recovery phrase **is** the account and may have no other copy, so clear-on-reinstall would destroy
  a realm. Proposes detect-and-**ask** (`UserDefaults` sentinel — it dies with the app, the Keychain
  does not) with *keep* as the default. ⚠ A discard must also purge the **outbound queues**: a stale
  `Device` tombstone surviving an identity wipe is what silently un-paired a freshly paired device
  (2026-08-17). ⚖️ **D2 open** — how hard to gate the discard.

- [`proposals/ios-control-offline-maps.md`](proposals/ios-control-offline-maps.md) —
  **v0.1, design, no code (2026-08-12).** Compose Control ships Settings → *Offline maps*;
  Control-on-iOS ships nothing. ⚠⚠ **Not a port question.** `maps-region-delivery.md` D5 —
  *"Control owns the shared region store"* — is a statement about a device with **one** proxy.
  Under iOS route 1 **every first-party app is its own proxy** and registers its own
  `MapRegionService`; the stores collapse to one directory only when the App Group resolves, and
  were **N independent stores on every real device** until the device entitlements gained the
  group on 2026-10-02 (`ios-signing.md` §6). ⚠ One directory, but each app still keeps its own
  install index in its Keychain. Control-on-iOS also links no basemap — so a naive port ships a download
  manager for hundreds of MB that render nothing. Recommends per-app management (Maps' picker,
  already shipped) plus an honest Control empty state now; the real surface when one store backs
  every app. §4 is the unanalysed part: N proxies writing one install index.

- [`proposals/fit-data-sources-restructure.md`](proposals/fit-data-sources-restructure.md) —
  **v0.1, design, no code (2026-08-13).** Profile → *Data sources* reads as clutter because it
  renders four categorically different things — a source, two actions and a diagnostic — as
  identical rows, and the actions sit at top level only because the Health Connect row has **no
  `onClick`** to hold them. ⚠⚠ **The finding under all of it: nothing about an import is
  persisted.** `ImportStatus` is a process-scoped `MutableStateFlow`, so *"165 items imported"*
  is a fact about the process and dies on restart; the other rows are **derived by scanning
  workouts' `source` field**, which is why showing Apple Health on Android is a **schema** gap
  and not a UI one — no amount of Compose or SwiftUI closes it. Two new `Syncable(Eager)`
  composites keyed per device (`HealthSource`, `HealthImportRun`) on `IngestSource`'s shape;
  ⚠ `ingest-service` itself was rejected as the home (D2 — a file-watching registry, foreign
  version chain, for one boolean). ⭐ D11 keeps DS-1 small: only a **standing connection** gets a
  source row, so Takeout derives from its runs and Tenshu Health from `Workout` rows, and the
  three backings converge in the shared projection (D10) rather than in either UI. ⚠⚠ D7 + D8 —
  no-op refreshes write no run, runs are capped per (kind, device) and **only the originating
  device trims** — ship in the same slice that starts recording, because
  `HealthImportBootstrap` fires on **every foreground** and this is otherwise the derived-broadcast-loop shape.
  ⚠ D4 corrects the premise that a remote source cannot be switched off: `autoImport` is ours
  and it syncs; what is unavailable remotely is *triggering* and *fixing*, so a non-local source
  renders readiness **as of a timestamp**, never as live truth. HR #9: `:health-service` is at
  implicit v1 with **no chain**, so DS-1 owes `version = 2` *plus* an empty
  `schemaMigration(fromVersion = 1)` — a bump without one **wipes the store**. ⚠⚠ Fit is a
  deliberately un-built `android-smoke.sh` canary, so a green gate does **not** cover DS-4.
  Phases DS-1…DS-7 (§9), handoff §10.

- [`proposals/fit-automatic-activity-detection.md`](proposals/fit-automatic-activity-detection.md) —
  **v0.3, design + file-level implementation plan (§14), no code (2026-08-09).** Logging a run the user never pressed start on — the
  Google Fit auto-detection gap. ⭐ **The detector already exists and is in Timeline**
  (`MovementSegmenter`/`LegMode`, S1–S8); Fit has none and, per `milestones.md`, may never grow
  background location — so this is a **bridge plus a promotion policy**, not a sensing stack.
  ⚠⚠ **The prerequisite nobody had noticed: a `Workout` cannot be deleted.** `:health-service`
  is nine ops, all upserts or blob reads; the only delete is whole-type realm-wide
  `DeleteRealmData`, whose `createdBefore` narrowing provably matches nothing for workouts
  (`Workout.Start` is a field alias `getProperty` can't read). D1's whole argument for *not*
  prompting is "auto-logging is reversible" — so **`deleteWorkout` is FA-0, a premise rather than
  a follow-up**, and it is independently overdue (a user cannot delete a mis-recorded workout
  today). D1 **ask once, then never again**; D2 promotion is a **deterministic, content-addressed
  function of the trace**, so multi-device promotion converges instead of re-creating the
  derived-broadcast loop (⭐ and the route is *not* copied — `traceId` is recoverable from
  `workoutId`). D3 auto-writing makes dedup an **ordering** problem (the watch syncs the same run
  hours later) that a write-time check cannot fix → **supersession**. D5 **one sensor**: Timeline
  senses live, **Fit detects retrospectively**, which removes the race so a read-check replaces
  process coordination — and works identically on iOS route-1, where Control-owned capture could
  not; Timeline does *not* promote when Fit is absent (a later install backfills the history).
  D6 extract the **classifier and promotion policy, not the capture engine** — ⚠ extraction
  solves *code* duplication, never *runtime* duplication. D4 **two tiers**, and ⭐ Tier 1 is
  nearly free: both importers **already read intraday step samples and collapse them in the same
  expression** (`groupBy { …day() }.sumOf`), while `HealthSample` can carry them with no schema
  change. ⚠⚠ A third of Google's efficiency is **cross-app amortization** a de-Googled device
  structurally cannot have — but it is not where the power goes. Net schema cost of the shippable
  unit: **two new string values, zero positional reshapes, one new op.** §14 carries the per-phase
  file tables and the traps a compile would not catch: ⚠⚠ `healthServiceProxyImplementationFactory`
  has **no `tombstoneEmitter` parameter** (5 call sites), ⚠⚠ a delete op owes its **own tombstone**
  (none ⇒ the next pull resurrects the row — `DeleteRoutine` shipped that way; follow `DeleteFile`), and
  ⚠⚠ a new op needs a `BundledAppManifestSource` **permission entry or Fit cannot call it**.

### `protocol-` — protocol changes

- [`proposals/protocol-improvements.md`](proposals/protocol-improvements.md) —
  Binary protocol weaknesses and improvement plan.

### `design-` / `ui-` — design system

- [`proposals/design-improvement-plan.md`](proposals/design-improvement-plan.md) —
  Largely landed; tracks remaining items. ⚠ Phase 7 still reads "blocked on the
  Compose 1.7.3 → 1.8+ bump" — that bump landed 2026-07-08; see below.
- [`proposals/ui-adaptive-navigation.md`](proposals/ui-adaptive-navigation.md) —
  **v0.4, N1–N5 SHIPPED (2026-08-11).** The desktop sidebar was 300 dp wide
  whatever you put in it — measured, and content-independent by construction,
  because `PermanentDrawerSheet`'s `widthIn` cannot beat `NavigationDrawerItem`'s
  internal `fillMaxWidth()`. Material 3 has since **deprecated the navigation
  drawer** in favour of the collapsed/expanded navigation rail, and the breakpoint
  model grew from three classes to five. Now one `WideNavigationRail` for every
  non-compact class: 96 dp collapsed (Medium/Expanded), 220–360 dp content-sized
  (Large/ExtraLarge), menu-toggle from Expanded up whose choice persists per
  desktop app, and the body pane finally carries the Material 24 dp window margin.
  ⚠⚠ The rail is width-*ranged*, not width-*fitting* — labels shorter than the
  220 dp floor do not shrink it. ⚠ Compact margin stays 0: the 16 dp figure
  everyone quotes is **unverified** (Q2). Remaining: N6 iOS `NavigationSplitView`.
  ⚠ `NavigationSuiteScaffold` **never auto-selects the expanded rail**, so its
  default would trade one wrong fixed width for another. Owes an amendment to
  [ADR 0001](adr/0001-design-system-direction.md) §3.

### `ops-` — deployment & operator concerns

- [`proposals/ops-secret-custody.md`](proposals/ops-secret-custody.md) — Where
  deploy-time secrets live. Sorts them by *recovery* cost into trust anchors
  (never automated) vs operational credentials (Keychain-able), states that
  Terraform state is secret-bearing, and flags that `issuer_seed` currently
  rides Terraform despite being an anchor. Draft, no code.
- [`proposals/ops-dev-lab.md`](proposals/ops-dev-lab.md) — An isolated dev
  plane (relay + commerce + apex) on **Scaleway**, plus Android devices an agent
  can drive and a human can watch. No cloud VM exposes nested virtualisation, so
  the Android answer is a *container* (redroid), not an emulator; iOS has no
  cheap remote-device answer at all. ⚠ v0.3 moved off Oracle's free tier — §1.
  DL-1 + DL-2 built, never applied.

---

## Folder map

```
docs/
  README.md                       # this index
  agent.md                        # platform conventions (read first)
  milestones.md                   # roadmap (read first)
  tech-debt.md                    # sized issues (read first)
  whitepaper.adoc                 # foundational architecture
  schema-dsl.md                   # DSL conventions
  testing.md                      # testing conventions

  sync/                           # sync architecture & spec (timeless)
    threat-model.md, design.md, pairing-flow.md, roadmap.md
  web/                            # web architecture (timeless)
    architecture.md, roadmap.md
  protocol/                       # IPC protocol specs
    desktop-ipc.md, ios-ipc.md
  design/                         # design-system snapshot
    audit.md
  adr/                            # architectural decision records
    0001-...
  how-to/                         # recipe-style guidance
    add-composites-and-operations.md

  proposals/                      # time-bounded design docs (subject-prefixed)
    core-*.md
    sync-*.md
    web-*.md
    control-*.md
    drive-*.md
    protocol-*.md
    design-*.md
    bip39-decode.md
```

---

## Conventions

- **Where to put a new doc.** Architecture or convention that's true today and
  expected to stay → top-level or subject sub-folder. A specific change being
  proposed/implemented → `proposals/<area>-<topic>.md`. ADR → `adr/`.
- **Status header.** Every proposal opens with `**Status:**` per the
  convention at the top of this file. Update on landing rather than deleting.
- **Cross-references.** Use relative paths from the file's own location. The
  in-flight refactors table in [`../AGENTS.md`](../AGENTS.md) is the canonical
  pointer for the small set of cross-cutting refactors that everyone needs to
  know about; this index is the comprehensive view.

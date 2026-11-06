# Handover — iOS proxy architecture, Phase 1 (K/N core + CI gate)

> **Historical — Phase 1 shipped, as did Phases 2–4a.** For the next pickup see
> [`ios-proxy-phase6-handover.md`](ios-proxy-phase6-handover.md). This file is kept as the record of
> how the track was kicked off.

> Paste the **Kickoff prompt** below into a fresh Claude Code session at the repo root to start
> Phase 1. The rest of this file is context for a human picking the work up.

---

## Context in one paragraph

iOS can't do the on-device IPC the Android/Desktop proxy model relies on (App Groups are
single-Team-ID; re-signing third-party apps under one team violates Apple's rules), so iOS uses
the whitepaper's **External Proxy Connection (EPC)**: every app pairs with one proxy — on another
device *or* a co-resident Control on the same iPhone — over a network channel. The full decision
record is [`ios-proxy-architecture.md`](ios-proxy-architecture.md). The first phase is purely
about proving the Kotlin/Native core builds and tests on iOS and adding a CI gate so it stops
rotting — it's the forcing function everything else (EPC, relay rendezvous, Control-on-iOS)
depends on, and it also de-risks the JVM-less macOS path.

## Status going in

- Decision doc written and consistent with `milestones.md`; `ios-ipc.md` demoted to
  first-party-only.
- iOS targets declared in `:core`/`:core-common`/service modules with `src/iosMain`, **but no
  XCFramework and no iOS CI lane** — verified 2026-06-16.
- Open decisions O1–O5 (proposal §7) are **not** Phase-1 concerns; don't resolve them here.

---

## Kickoff prompt (paste into Claude Code)

> Implement **Phase 1** of `docs/proposals/ios-proxy-architecture.md` (see its Appendix →
> "Phase 1 — first PR"). Scope is build + CI only; no product code.
>
> **Read first:** `docs/proposals/ios-proxy-architecture.md` (Appendix especially),
> `docs/tech-debt.md` (the iOS-targets entry), `core/build.gradle.kts` and
> `core-common/build.gradle.kts` (existing iOS target declarations + the "expose a proper
> XCFramework" comment), `tenshu-sdk-js/build.gradle.kts` (the umbrella-SDK pattern to mirror),
> and House Rules #1–#7 in `AGENTS.md`.
>
> **Do:**
> 1. Add one umbrella iOS framework module (mirror `:tenshu-sdk-js`; e.g. `:tenshu-sdk-ios`) that
>    exports the client-facing shared surface (`:core-client` + service `-client`/`-common`) and
>    assembles an `XCFramework`. No per-module frameworks.
> 2. Add `.github/workflows/ios.yml` on a `macos-*` runner running the iOS compile + test +
>    `assembleXCFramework` tasks; wire it as a required check.
> 3. Re-enable the disabled iOS tests; get `iosSimulatorArm64Test` green for the shared modules.
> 4. Update the iOS-targets entry in `docs/tech-debt.md` to reflect the now-gated target.
>
> **Acceptance (must verify by running, not asserting — House Rule #7):**
> - `./gradlew :core:iosSimulatorArm64Test :core-common:iosSimulatorArm64Test` (+ other shared
>   modules) pass.
> - The umbrella `assembleXCFramework` task produces a framework artifact.
> - `ios.yml` runs green on the PR.
>
> **Stop and ask — do NOT silently:**
> - resolve any of open questions O1–O5 (proposal §7);
> - start EPC, relay rendezvous, attestation, or Control-on-iOS work (those are Phases 2–6);
> - add `suspend` to any `Operation` interface (House Rule #3 — use `IoOperation` +
>   `TaskInteractor`);
> - touch threat-model assets (no Phase-1 reason to).
>
> **When done:** open a PR; if you made any non-trivial build-graph decision (e.g. exactly which
> modules the umbrella exports), record it in the proposal's Appendix per the "decisions land in
> docs" house rule.

---

## Why this ordering (for the human)

Phase 1 is the cheapest thing that retires the biggest unknown: whether the shared Kotlin is
actually K/N-viable end to end. Until an XCFramework links and tests pass on a simulator, every
later phase (EPC, relay, Control-on-iOS) is built on an unproven base — and the same K/N layer is
what a future JVM-less macOS target reuses, so the investment isn't iOS-only. Phases 2→6 and their
module targets are in the proposal's Appendix; pick them up in order, each as its own PR with the
threat-model amendments noted there.

# Tenshu Pre-Release Checklist

**Status:** Living checklist (opened 2026-07-23). The single place to track work that must
land **before the final / public release** but has no natural home in a single feature
proposal — most importantly the **legal & policy** items that need counsel review, plus
distribution placeholders that are safe to ship internally but not to real users.

Each item links its source doc. Check items off (`- [x]`) as they land; add new ones here
rather than leaving a lone "before launch" note buried in a feature proposal. This list is
**not exhaustive yet** — it consolidates what's known today; sweep the proposals for stray
"before launch" / "counsel review" notes as they appear and fold them in here.

> ⚠️ Nothing in the **Legal & Policy** section is legal advice. Every user-facing legal text
> (ToS, Privacy Policy, DPA, acceptable-use, DSA notices) is a **counsel-review gate** before
> the public release — the product-side drafts exist to be reviewed, not to ship as-is.

---

## 1. Legal & Policy (counsel-review-gated)

- [ ] **Acceptable Use & Limits policy** — finalize + counsel review.
      [`docs/policy/acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md) is
      **Draft v0.2, explicitly "not yet counsel-reviewed, not legal advice."** It single-sources
      the user-facing rules that feed ToS acceptance, share-creation copy, the share viewer
      footer, and the abuse report form, and it owns the DSA notice-and-action posture and the
      per-jurisdiction reporting duties. Counsel sign-off here gates every surface that quotes it.
      ⚠ **§4 (data retention & account closure, added 2026-08-04) is a hard gate on a switch, not
      just on copy:** the account-closure reclamation path is built and, since 2026-10-24, switched on
      at both ends (`retention-quota-model.md` §3.3). ⚠ **The maintainer opened both
      `ACCOUNT_CLOSURE_RELAY_URL` and `ACCOUNT_CLOSURE_RECLAIM` on 2026-10-24, before this review**
      (production held one account, none closed), so the review now checks a running process. Two specifics for the
      reviewer: whether per-**account**-only deletion satisfies erasure for a service that
      structurally cannot delete per file, and whether the change needs **notice to existing
      accounts** (acceptance is recorded once at registration; there is no re-acceptance flow).
- [ ] **Commerce legal pack** — before taking **real** payments.
      [`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md) §§ requires a
      **Privacy Policy + DPA naming Stripe as a subprocessor**, a Record of Processing, SCCs
      where applicable, and EU **OSS/VAT** treatment confirmed with a tax advisor — all
      "reviewed by counsel before taking real payments." The **Free-tier limit** discrepancy
      this line used to carry is closed: code is 5 GiB everywhere (`StorageTiers.FREE_BYTES` in
      `:core-common`, used by commerce and the relay; the apex's stale 1 GiB was fixed too).
      Displays format it with binary steps (`formatStorageBytes`), so it reads "5 GB".
- [ ] **Folder link-sharing policy text + DSA posture** — counsel review is an explicit
      **launch prerequisite**.
      [`drive-folder-link-sharing.md`](drive-folder-link-sharing.md) §§8–9: the acceptable-use /
      transfer-limit / takedown copy (DSA-shaped notice-and-action, report-by-`shareId`, the
      report form that strips `#k=` client-side) all trace back to the policy doc above and need
      the same review before the account-less share flow is public.
- [ ] **ToS + Privacy Policy pages published** on the public web (apex legal routes) and wired
      into the onboarding acceptance step. (Depends on the drafts above clearing counsel.)
      ⚠ **Privacy policy: published 2026-10-23, NOT yet counsel-reviewed** — the maintainer chose to
      publish before review; counsel reads it once the product has traction. Built at
      `/legal/privacy/<lang>` from `web/apex/support-pages/privacy.ts` (German binding; English and
      French translations `reviewed: false`), linked from every footer. Still owed: the counsel review,
      the ToS page, the link from the sign-up acceptance step, and the operator duties and open
      questions in [`store-presence-and-support-pages.md`](store-presence-and-support-pages.md) §1.4.
      `PRIVACY_VERSION` (`CommercePublisherClient`, `web/apex/.../commerceApi.ts`) now reads
      `2026-10-23`, the published policy's date (it used to name the unpublished `2026-08-04` draft);
      `commerceApi.test.ts` pins the web copy to `PRIVACY_POLICY_DATE`.
- [x] **Terms of use: publish the page, then align the versions and the sign-up step.** Done
      2026-10-28: `/legal/terms/<lang>` (`web/apex/support-pages/terms.ts`, German binding, en/fr
      translations) beside the privacy policy and in every footer; `TOS_VERSION` = `TERMS_DATE`
      = `2026-10-28` in **both** clients, pinned by `commerceApi.test.ts` and
      `TermsVersionParityTest`; the sign-up step (Control, iOS Control, the web) accepts the terms,
      confirms 16+ and links both pages. Drafted by the maintainer acting as counsel.
- [ ] **Terms of use: the pre-paid-launch legal review.** The ⚖️ list is in
      [`acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md) "Terms of use". The
      list is grouped by when it is owed: before the Play beta (DSA platform question and a
      revoke-by-`shareId` tool, minors, termination), before the first real Stripe sale (the § 312f
      confirmation e-mail, a § 312k cancellation page without sign-in, the § 312j button check, the
      Art. 246c/246a information, WC-1, withdrawal on portal upgrades), and what needs a lawyer. The
      checkout tick was reworded to the terms' pro-rata reading on 2026-10-28.
      ✅ The revoke-by-`shareId` tool shipped 2026-10-29; the DSA platform question, minors
      and termination are still owed before the Play beta.

## 2. Distribution / App Store

- [x] **Android — pick the decided app signing key at each app's first upload.** It is fixed
      there, per app. Decided 2026-10-22: "Let Google generate" for every app, ten separate keys
      (revising 2026-10-18's "same key as Control" for the other nine); other channels get a
      separate side-channel key of our own
      ([`how-to/android-release.md`](../how-to/android-release.md)). Done 2026-10-22: all ten
      first uploads made in the Console, each with its own Google-generated key (digests in the
      guide's table).
- [x] **Android — set `tenshu.control.playUrl`** once Control's listing exists; until then every
      other app says Control "isn't in the store yet"
      ([ADR 0025](../adr/0025-forward-to-control-surface.md)). Control therefore goes first.
      ✅ Set in `gradle.properties` on 2026-10-28, when the apps went to Play's open test.
- [ ] **Android — Play Console declarations + Data safety**, per app
      ([`how-to/play-store-declarations.md`](../how-to/play-store-declarations.md)).
- [x] **Android — a user-facing account-deletion route.** Play requires one in the app and on the
      web for any app that creates accounts. Built 2026-10-23
      ([`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md) §9.7): Control's
      Services → *Delete account* (Android/desktop) and the public page `tenshu.app/account/delete`,
      both re-confirmed by an emailed code; deletion erases the email and sessions at once, stops
      Stripe billing and closes the account. The Data safety URL now points there.
      ⚠⚠ **Still owed before real users see it:** (1) ~~the content half~~ — both
      `ACCOUNT_CLOSURE_*` flags opened in production 2026-10-24 (maintainer decision; counsel's
      review of the §4 text stays owed under the AUP item above);
      (2) ~~Google Play cancellation~~ — wired (store-billing closure path + the shared `SubscriptionCanceller`); (3) iOS Control's SwiftUI Services view has no button yet (the web page
      covers it); (4) ⚖️ counsel on the retention text and on whether Stripe's customer object must
      also be deleted; (5) the apex deployed with the page, and native review of the de/fr copy.
- [ ] **iOS — finalize `Control.appStoreURL`.** Drive's first-party setup screen
      (`ConnectToControlView`, the reworked "Set Up Tenshu Control" screen) sends a user who
      doesn't have Control installed to the App Store via `Control.appStoreURL` in
      [`drive-ios/Sources/ControlSession.swift`](../../drive-ios/Sources/ControlSession.swift).
      That URL is currently a **PLACEHOLDER product id (`id000000000`)** — it must be swapped
      for the real `apps.apple.com/app/id…` once **Tenshu Control-on-iOS ships to the App Store**
      (see [`ios-drive-parity.md`](ios-drive-parity.md) P7 TestFlight → Store). Until then the
      deep-link / "Open Tenshu Control" path is the working one and the "Get Tenshu Control"
      button won't resolve.
- [ ] **iOS App Store submission** — App Attest, real-device signing, TestFlight, then Store
      review ([`ios-drive-parity.md`](ios-drive-parity.md) P7).
- [ ] **macOS App Store (MAS)** parity build, if pursued — sandboxed build + Apple-Distribution
      signing (see `docs/milestones.md` → *Desktop distribution channels*). Optional / not a hard
      gate for the first release.

## 3. Product & copy (gathered "before launch" notes)

Non-legal items that individual proposals flagged as needing resolution before the public
release — collected here so they don't get lost:

- [ ] **Apex landing composition** decision — [`web-apex-landing.md`](web-apex-landing.md) §9
      (placeholder visuals + the embedded-vs-separate rendering call).
- [ ] **Public narrative copy audit** — [`web-about-tenshu-public.md`](web-about-tenshu-public.md)
      (placeholder screenshots/marks + demo copy) before the About page goes public.
      ⚠⚠ **The landing page calls Tenshu open source while the repository is private** (hero lede,
      FAQ, founder block), and the header's Docs link, the footer's whitepaper / threat-model /
      GitHub links and the FAQ's reading list all 404 for the public. Either publish the repository
      first (a handoff brief for the pre-publication cleanup exists, unmerged) or reword the copy and drop those links;
      `generate.test.ts` checks only the static pages' footers for "open source", never the
      landing. Same audit: Block 6 says "Windows · macOS · Linux" but only macOS is built, and the
      FAQ's "compare" answer names competitors (the support pages' D6 rule forbids it).
- [ ] **Sign-up's "Tell me more" link** (`NewUserScreens.tsx`) points at `https://tenshu.app/about`,
      which no route serves: it loads the whole runtime, then redirects to `/`. Point it at
      `/vision` or `/roadmap`. And the social preview is `og.svg`, which most link previews do not
      render: add a 1200×630 PNG.
- [x] **Public roadmap page** — `/roadmap/<lang>` (`web/apex/support-pages/roadmap.ts`), a static
      page beside the legal ones, linked from the header and the FAQ. Built 2026-10-29 from the
      sweep below. Features only, no dates, no bug fixes; keep it in step as items ship.
- [ ] **Recovery-phrase input UX** — [`control-onboarding-ux.md`](control-onboarding-ux.md) §7
      (24-word autocomplete) needs to land before launch.
- [ ] **Web onboarding** outstanding pre-launch item — [`web-onboarding-ux.md`](web-onboarding-ux.md).

## 4. Schema & migrations — the last *free* reset ⚠ one-way door

Everything in this section is only possible **while pre-release**, and stops being possible the
moment real users hold real data. Do it as part of the release cut, not after.

> ✅ **Code-side reset merged 2026-10-27**, **re-cut once more for 1.0.1 on 2026-11-05**
> (`core-schema-evolution.md` Appendix A.6: Calendar/Contacts back to v1, relay and commerce SQL
> squashed under marker `2026-11-reset`, old-build bridges deleted). ⚠ The operational reset is **planned, not done**
> (date TBD; recorded in `core-schema-evolution.md` Appendix A.2 when done) — runbook:
> [`how-to/reset-and-commerce-test.md`](../how-to/reset-and-commerce-test.md). What it owes:
> - The relay must redeploy onto a **fresh volume** *before* any build from this change syncs.
>   The old volume's `RealmSchemaEpoch` rows name pre-reset versions (e.g. File 17), and a v1
>   client would fence itself on them permanently.
> - The squashed relay schema does not upgrade a pre-reset database: `schema_version` above 1 is
>   left as-is.
> - Every dev device clears its app data or reinstalls, then onboards fresh.
> - Reshapes merged before it (workstreams A–D) ride protocol 1 with no bump, and Play
>   `versionCode` 6 is the first protocol-1 build to reach testers (5 never goes live) —
>   Appendix A.2.

- [x] **Collapse the accumulated pre-release migration chains to nothing.** — done 2026-10-27:
      about 45 steps deleted across 12 services and core. Every `service(…)`,
      `TENSHU_STORAGE_FORMAT_VERSION`, `RELAY_PROTOCOL_VERSION` and its minimum are now 1. The relay's
      25 SQL migrations were squashed into v1, with a schema dump of old chain vs squash compared
      identical. The retired numbering is in `core-schema-evolution.md` Appendix A. Every
      `SchemaMigration` registered during development exists only to carry *our own* dev stores and
      dev relay history forward. At the release cut there is no history worth carrying, so the
      whole accumulated chain can be **deleted** and every service's version reset to a clean
      baseline (`service(id, version = 1)`), with `TENSHU_STORAGE_FORMAT_VERSION` reset/bumped once
      more, a store wipe, and a relay redeploy on a fresh volume.
      Mechanically this is *just deletion*: per
      [`core-schema-evolution.md`](core-schema-evolution.md) §4/§8.4 the readable **floor is
      derived** from the registered chain, so removing the steps *is* the cut-off — there is no
      separate floor constant to edit and nothing can drift. Registering an unbroken chain again
      afterwards starts the real, permanent record.
      **Why it must happen at the cut:** after release each step is load-bearing for somebody's
      installed data, and the only way to shorten the chain again is **E4 compaction** advancing the
      floor behind roster-verified re-emission (§8.3) — i.e. a real distributed mechanism instead of
      a delete. Shipping the dev chain would also mean shipping our development archaeology (dozens
      of frozen `from` snapshots) to every user forever.
- [ ] **Confirm the release build's chain is verified-and-empty (or verified-and-intentional).**
      `SchemaMigrationRegistry.verify` runs on every configured store open (E2b) and fails proxy
      start on a broken chain, so this is a *positive* check, not a hope: launch a release-mode
      Control and confirm it starts, then confirm the store's `schema_service_versions` rows read
      the reset baseline.
- [x] **This is also the last free `TENSHU_STORAGE_FORMAT_VERSION` bump-and-wipe.** — House Rule #9
      amended 2026-10-27: post-release a wipe is data loss, and every core bump registers its core step. Post-release a
      bump may no longer mean "wipe and resync" — House Rule #9's text is superseded by
      "ship the schema Δ + its `SchemaMigration` in the same PR"
      ([`core-schema-evolution.md`](core-schema-evolution.md) §11). Amend House Rule #9 in
      `AGENTS.md` at the cut so the wipe wording cannot outlive the window in which it was true.
- [x] **Verify every post-release block is stamped.** — checked 2026-10-27: every production
      `BlockPayloadEnvelope.seal` call (`SyncObserver` ×4, `PendingBlockRestamper`) passes a stamp.
      ⚠ The "dropped by decision" net is **inactive until the first chain registers**
      (`PayloadUpcaster`: no chains ⇒ a core-only stamp parses as current). That is correct at the
      baseline, where no older shape exists, and it switches on with the first post-release step. E0/E1's in-ciphertext version stamp is what
      makes mixed-version relay history decodable at all; an unstamped or below-floor block can
      only ever be wiped or guessed at (§5). The stamp has shipped since fmt v13, so the check is
      simply that no code path emits a hand-built block without
      `BlockPayloadEnvelope.stampOf(...)` — from E2c on, an unstamped payload is dropped **by
      decision** once any chain is registered, which makes a regression loud rather than silent.
- [x] **Decide the relay's `minSupported` floor at the cut** — pinned 2026-10-27 at **1** = the
      release baseline, which includes epoch fencing, so the fencing floor of 1 holds.
      ⚠ A *pre-reset* build announcing protocol 1 would pass the gate; none exists outside our
      cleared dev devices (`RelayProtocol.kt`, Appendix A.2). — the ⚠ **permanent** rule from
      §7.5: once the relay-gate split (E2e) ships, `RELAY_PROTOCOL_VERSION.minSupported` must
      **never** fall below the protocol version at which client-side epoch fencing shipped, because
      older clients don't know about epochs and for them the relay gate is the only fence. Pin the
      release's value explicitly rather than inheriting whatever development left behind.

## 5. From the 2026-10-29 proposal sweep

A read of every proposal for what must land before the reset and versionCode 6, and what before
1.0. Classification and evidence per doc were in the sweep's session; only what is not tracked
above is listed. ⚠ Claims were spot-checked against code on 2026-10-29; re-check before building.

**Before the reset and v6** — one-way doors, money, security, and data loss in shipped features:

- [ ] **Any installed Android app can bind Control's `ProxyService`** — exported with no
      `android:permission`, and nothing authorizes the attested caller before `ProxyGateway.call`;
      the client apps' `ClientCallbackService` takes forged callbacks the same way.
      `tech-debt.md` *"The IPC surfaces authenticate a peer but never authorize it"* #1; no Android
      entry in the threat model. ⚠ Play generated a separate key for each app, so a plain
      `signature` permission cannot cover the first-party set.
- [ ] **Version compatibility in the v6 build** — [`core-version-compatibility.md`](core-version-compatibility.md)
      VC-0's client half (R1–R3) must ride v6, since installed v6 apps cannot be retrofitted;
      otherwise the proxy-side R6 backstop is mandatory before the first post-release core bump.
      VC-7 (refuse, never wipe, a store newer than the build) before iOS ships more than one app.
- [ ] **Free-now reshapes, or a written "keep"**: drop `peerCursors` and CR-2 B6's dead seams
      ([`sync-retention-horizons.md`](sync-retention-horizons.md), B6 §7.6); delete or keep
      `DeviceSyncStatus` / `MeteredExempt` ([`sync-metered-plane-split.md`](sync-metered-plane-split.md) P2);
      answer [`sync-entity-identity.md`](sync-entity-identity.md) Q5 (does an overwrite drop a photo
      from its albums?); accept or fix null-vs-empty optionals ([`protocol-improvements.md`](protocol-improvements.md));
      close [`file-content-addressing.md`](file-content-addressing.md) Q4 and retire its "a reset
      is the migration" banner. Optional: FA-4's `cadence` field, `NearestTransitStop` tiles-only.
- [ ] **Play billing has never run on a device**, and an open-test purchase is real money
      ([`commerce-store-link-out.md`](commerce-store-link-out.md)): buy as a licence tester on v6
      against the reset commerce, or hide paid plans in the beta. Play builds also lack "Billed by
      Google Play" and a manage-subscription link.
      Once billing is proven, the public roadmap (`web/apex/support-pages/roadmap.ts`, *In
      testing*) may name paid plans; until then it deliberately does not.
- [ ] **A Stripe renewal re-binds the account to the realm it was bought for**
      (`StripeWebhookRouting.kt`, the `owner_id` from subscription metadata), silently undoing a
      recovery to a new realm, which then answers 402. Not in `tech-debt.md`.
- [ ] **Shared-folder deletes never reach the other members** — the tombstone drain authenticates
      only as the personal realm ([`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md),
      `tech-debt.md` *"A shared-realm tombstone has no path to its own relay"*); content repair
      declines every `SharedFile`.
- [ ] **Deleting a file does not revoke its share links**, so the content stays stored and viewable.
- [ ] **Calendar seeds its default calendar before the first sync**, resetting a renamed calendar
      and its lock-screen privacy setting on every newly paired device (`tech-debt.md`).
- [ ] **A contact edit drops imported vCard types** (cell, fax …) and syncs the loss (`tech-debt.md`).
- [ ] **A large video upload reads the whole file into Control's heap**
      (`AndroidFileUploadImplementation`, [`media-processing-pipeline-sweep.md`](media-processing-pipeline-sweep.md) P2):
      confirm with a 200 MB video, then stream above a bound.
- [ ] **Backup shows "Up to date" while switched off**, plus the four review follow-ups of the WorkManager photo backup (Play D7)
      ([`drive-synced-folders-phase2.md`](drive-synced-folders-phase2.md), `tech-debt.md`).
- [ ] **Reset operations**: commerce's compose drops ~18 env vars it reads, `ADMIN_TOTP_SECRET`
      among them (`tech-debt.md`), so production has no admin console or operator erase; after
      the reset check `CONTENT_SWEEP_MODE=armed`, the release-mode chain (§4), and deploy relay,
      apex, clients and hub together ([`relay-metadata-minimisation.md`](relay-metadata-minimisation.md)).

**Before 1.0** — fine for the open beta:

- [ ] **iOS critical path**: the S0b SQLite cutover before the first iOS user (after it, a
      migration; today Drive and Photos die of `0xdead10cc` on a phone), then in-app Delete account
      (App Review 5.1.1(v)), the full-disk crash, the P7 device matrix, export-compliance codes,
      TestFlight, Control submitted first ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md),
      [`ios-drive-parity.md`](ios-drive-parity.md)). App Attest is not on it (§19.3).
- [ ] Recovery-phrase escalation Levels 1–3 ([`onboarding-low-friction.md`](onboarding-low-friction.md) §7.2).
- [ ] An erasure ledger and a re-apply step after a database restore (`tech-debt.md`).
- [ ] A bound on a received HLC: one far-future clock drags every realm clock forward for good.
- [ ] Background sync re-measured on a real OEM phone ([`control-background-work-off-fgs.md`](control-background-work-off-fgs.md)).
- [ ] Mac Developer ID signing + notarization ([`desktop-macos-distribution.md`](desktop-macos-distribution.md)).
- [ ] French onboarding and the recovery screens' accessibility audit ([`web-onboarding-ux.md`](web-onboarding-ux.md)).
- [ ] Timeline's native merge / unmerge / trip-accept bugs; Takeout holding every file in memory;
      `RoutineRun` pruning; the People counsel packet; relay capacity vs sold entitlements (ST-3c);
      a typed refusal for a removed device ([`admission-revocation-names-the-capability.md`](admission-revocation-names-the-capability.md) F6).

---

## Notes

- This doc deliberately lives alongside the proposals so it's discoverable in review; it is a
  *tracking* doc (like `milestones.md` / `tech-debt.md`), not a design proposal — no threat-model
  amendment or schema change rides here.
- When a linked item lands, check it off and, if useful, leave the one-line "done in #PR"
  breadcrumb so the history is legible.

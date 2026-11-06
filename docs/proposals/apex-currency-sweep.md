# Apex currency sweep — what drifted while nobody ran it

**Status:** **F1, F2, F3, F4, F5 all shipped 2026-08-09**.
The sweep ran against `d9c79dc`, prompted by "is the apex actually still up to date with
recent changes?"; the findings are kept in the present tense below as the record of what
was wrong, with a resolution line on each. **F6 and the ⚠⚠ in §1 are the live remainder.**

| | finding | outcome |
|---|---|---|
| F1 | Web CI watched 11 of 33 bundled modules | **fixed** — all 33 listed, and the list is now *derived* |
| F2 | Services surface a commerce generation behind | **fixed** — real OTP + checkout + portal + redeem |
| F3 | Account card printed invented storage figures | **fixed** — reads `Storage.status()` |
| F4 | `formatBytes` capped at GB | **fixed** — folded into F3 |
| F5 | Landing page claimed two apps ship | **fixed** — seven, honestly tiered |
| F6 | Small stuff (free-tier disagreement, last-seen, CLAUDE.md) | free-tier + FAQ fixed with F5; the rest open |

**Scope.** `web/apex` (the `tenshu.app` landing surface + web Control plane), the
`@tenshu/sdk` TS layer it consumes, and the `:tenshu-sdk-js` Kotlin bundle underneath.
`web/drive` is touched only where it contradicts the apex.

⚠⚠ **None of the five fixes were verified by CI, because CI cannot run.** See §1.

---

## 0. The short answer

**Mechanically the apex is fine. Editorially it is a generation behind, and in one place
it prints a number it makes up.**

`tsc -b` is clean, `eslint` is clean, 205 tests across 41 files pass, and
`:tenshu-sdk-js:compileKotlinJs` still compiles green against today's platform. Nothing in
the 49 commits since the apex was last touched (`0e517b4`, 2026-08-06) has broken it.

What has drifted is *content*: the Services surface still describes a Free-1 GB /
Lifetime / Rei Key world that the commerce plane left behind three phase-series ago, the
landing page says two apps ship when **seven** do, and the account card renders invented
storage figures that the apex's **own runtime** already knows to be wrong.

⚠ "Seven" corrected from the first pass's "nine": Home and Mia have shells but not apps,
and F5 checked each against the code rather than against `milestones.md`.

One structural cause was worth fixing first: **Web CI's path filter covered 11 of the 33
modules compiled into the bundle**, so most platform work that could break the apex never
triggered a web build. ⚠⚠ It later turned out the workflow behind that filter had not
successfully run since 2026-08-05 for an unrelated reason — see §1.

---

## 1. Mechanical health (measured, not assumed)

### ⚠⚠ GitHub Actions has had no runners since 2026-08-05

Found while merging the fixes, and it outranks every finding below.

**Every workflow run on every branch — including `master` — has failed since 2026-08-05.**
Over the 30 most recent Web CI runs: 29 failures, 1 cancellation, **zero successes**. Each
job reports `runner_id: 0`, `runner_name: ""`, no steps, completes in ~2 seconds, and
serves 404 for its logs. That is a job that never got a runner, not a job that ran and
failed. It hits `ios` and `test` identically, so it is not web-specific.

This is an Actions capacity or billing condition at the account level — nothing in the
repository can fix it, and nothing in the repository caused it.

⚠⚠ **Two consequences worth stating plainly:**

1. **The five fixes above were merged with no CI coverage whatsoever.** Their verification
   is local runs only (recorded per-PR). That was a deliberate call — waiting for green
   would have meant waiting indefinitely — but it is not the same as having been gated.
2. **F1's fix buys nothing until runners return.** Widening a trigger only matters if the
   workflow behind it can execute. The sweep's framing — "Web CI is a good gate with a
   filter hole" — was true about the *configuration* and wrong about the *gate*, which had
   already stopped running four days before the sweep began. Checking whether a gate is
   green is not the same as checking whether it *ran*, and this sweep initially made that
   mistake.

### The gates themselves

Run from a fresh clone at `d9c79dc`:

| Gate | Result |
|---|---|
| `pnpm -F apex lint` | clean |
| `pnpm -F apex build` (`tsc -b`) | typecheck clean |
| `pnpm -F apex test` | **205 passed** across 41 files |
| `./gradlew :tenshu-sdk-js:compileKotlinJs` | **BUILD SUCCESSFUL** (80 tasks) |

⚠ 8 further suites fail to *load* in a fresh clone, all with the identical error: the
Kotlin bundle at `tenshu-sdk-js/build/dist/js/productionExecutable/tenshu-sdk-js.js` does
not exist. That is the documented build order (`docs/web/architecture.md` §12.11 — run
`./gradlew :tenshu-sdk-js:jsBrowserDistribution` first), not a regression. Vite's build
fails for the same single reason. The Kotlin *compile* of all 33 bundled modules is green,
which is the half of that gate this sweep could measure.

⚠ Environment notes for anyone reproducing this in a sandboxed container:

- The Gradle build needs **JDK 17** (`:tenshu-sdk-js`) *and* **JDK 25**
  (`:control-desktop` configures even for a JS-only task). `--configure-on-demand` is not
  a way around the second one — it makes `:rootPackageJson` fail, because aggregating
  every project's `package.json` requires every project to be configured.
- `jsBrowserDistribution` additionally needs `codeload.github.com` (Kotlin's `karma` fork
  is fetched as a GitHub tarball, not from npm). An egress policy that allows
  `registry.npmjs.org` but not `codeload.github.com` fails `:kotlinNpmInstall` with a bare
  `yarn … returns 1`. `compileKotlinJs` needs neither and is the useful fallback signal.

---

## 2. Findings

### F1 — Web CI does not watch most of what it builds ⚠⚠

**Resolved.** All 33 modules listed, both trigger blocks kept identical, and the
list is now *derived*: `web/apex/src/test/webCiPaths.test.ts` reads the
`api(project(":…"))` declarations out of `tenshu-sdk-js/build.gradle.kts` and fails when
the workflow stops covering them. The circularity resolves in the right direction —
adding a dependency always edits the file that always matches `tenshu-sdk-js/**`, so the
run that would fail the test is the run the new dependency triggers. Mutation-checked.
⚠ Inert until the §1 runner problem is resolved.


`.github/workflows/web.yml` is a genuinely good gate: it rebuilds the Kotlin bundle, then
lints, typechecks, builds and tests **both** apex and drive. The problem is upstream of
all that — the `paths:` filter that decides whether it runs at all.

`:tenshu-sdk-js` `api`-depends on **33** modules. The filter names **11** of them
(`core`, `core-common`, `core-client`, `identity-service`, `file-service`,
`notification-service`, `sync-service`, `timeline-service*`). The other **22** are
invisible:

```
contact-service{,-client,-common,-proxy}     storage-service{,-client,-common,-proxy}
file-service-{client,common,proxy}           sync-service-{client,common,proxy}
identity-service-{client,common,proxy}       notification-service-{client,common,proxy}
ingest-service, ingest-service-client
```

The workflow's own comment already flags half of this ("the entries above have the same
gap … pre-existing, left alone here"). The consequence is measurable: of the 49 commits
since the apex was last touched, **4 changed a module that is compiled into the apex
bundle and did not trigger a web build**:

- `f19762b` — `sync-service-proxy`
- `e3f6900` — `storage-service-proxy`
- `30e194a` — `storage-service-proxy`
- `50f2a7d` — `storage-service-proxy`

⚠ The concentration is not random. `sync-service-*` is where the whole schema-evolution
programme lives, and `storage-service-proxy` is where the entitlement→figures mapping the
apex's storage gauge depends on lives. The filter's blind spot sits exactly on top of the
apex's two most load-bearing dependencies.

**Fix:** widen the filter to the 33 modules the bundle actually declares, or — better,
since the list will drift again — trigger on the union and let the Gradle build's own
up-to-date checks make the common case cheap. This is one file and it is the only finding
here that makes the *others* harder to notice.

### F2 — The Services surface describes a commerce plane that no longer exists ⚠⚠

**Resolved.** ⚠⚠ **Three blockers, none of them in the apex** — which is why this
looked like a UI problem and sat unfixed:
1. `:commerce-service` had **no CORS at all** (correct until now: every prior caller was a
   native HTTP client, which the same-origin policy does not apply to). Now opt-in per
   deployment via `WEB_APP_ORIGIN`, exactly one origin, credentials off.
2. Stripe's return URLs were `tenshu://` deep links, so a paying web customer landed
   nowhere. Now a `client` discriminator over **server-derived** pairs — never
   request-supplied, which would be an open redirect carrying someone who has just paid.
3. **`ownerId` was not reachable from the web at all**, so no commerce call was even
   possible. New `Realm.ownerId()` on the SDK.
Threat model amended in the same PR (T-CB-09, T-CB-10) per House Rule #2.


`web/apex/src/control/services/publisherClient.ts` is a TS mirror of a Kotlin
`MockPublisherClient` that has since been replaced by a real `CommercePublisherClient`.
It never followed.

| | apex today | platform today |
|---|---|---|
| Tiers | `'Free' \| 'Lifetime' \| 'Rei'` | `SyncedPlanTier { Free, Basic, Premium }` |
| Free quota | `1 GiB` | **5 GiB** (`EntitlementProducts.FREE_LIMIT_BYTES`) |
| 50 GiB tier | "Lifetime — one-time payment" | **Basic**, €2.99/mo subscription |
| Top tier | *absent* | **Premium** 2 TiB, €9.99/mo |
| Sign-up | "we sent a verification **link** … tap it" | 6-digit **OTP code**, body-only |
| Sign-in to an existing account | *absent* | shipped (`FreeAccountSignupScreen(signIn = true)`) |
| Checkout | *absent* | real Stripe rail + **withdrawal acknowledgement** |
| Billing portal / cancel | *absent* | shipped (Ph4 client) |
| Redeem a code | "Rei Key … Rei is coming soon" | `/redeem`, binds a real tier |

Notes that matter for whoever fixes this:

- `Lifetime` was dropped at Ph1 tier simplification; the `"rei"` planKey was
  retired in CE-4 ("it was an identity, never a plan"). Both are still first-class
  concepts in the apex router (`/control/services/lifetime`, `/control/services/rei`),
  the picker, and the copy catalogue.
- The `1 GiB` free tier is **the exact stale value** `retention-quota-model.md` D9 called
  out and corrected everywhere else ("fix stale web-genesis 1 GiB KDoc"). The web-genesis
  *mint* was corrected; this copy of the number was not.
- ⚠⚠ If a web checkout is ever built, the withdrawal acknowledgement is **not optional
  and not a UI concern**: `/commerce/checkout` answers `400 acknowledgement_required`
  without `withdrawalAcknowledged=true` plus a non-blank `tosVersion`. A web tier picker
  that posts to it without those fields simply fails. The apex has no `TOS_VERSION`
  constant at all today.

### F3 — The account card prints figures the apex itself knows are wrong ⚠⚠

**Resolved.** `ServicesCardState` now mirrors native's CE-4 split — storage
(synced, always shown) and identity (session-only). The `account === null` gate is gone,
and the two fields are **deleted** from `PublisherAccount` rather than left unread.


This is CE-4's defect, still live on the web, and it is the one I would fix first on
merit.

The apex mints a **real** Free entitlement on genesis (`Realm.kt` →
`mintEntitlement(productId = EntitlementProducts.FREE)`, i.e. 5 GiB), `StoragePlugin`
wires `EntitlementStorageProvider` + `initEntitlementStorageReader` + the relay usage
reader, and `Storage.status()` therefore returns real `usedBytes` / `limitBytes`.
`drive.tenshu.app` → Settings → Storage renders exactly that.

`web/apex/src/control/services/ServicesPage.tsx` renders none of it. It reads
`account.storageUsedBytes` / `account.storageLimitBytes` off the mock, which are written
once as literals at signup (`0` and `1 GiB`) and never revised. So:

> **Drive's Settings and the apex's Services card, in the same browser, in the same
> realm, disagree about how much storage you have — and only Drive is right.**

Two details make this the same bug CE-4 named rather than merely a stale mock:

- ⚠⚠ The card is gated on `account === null`. That gate **is** the defect: a device that
  has never signed in would show the truth, and signing in replaces it with the literals.
  CE-4's fix was to split `ServicesCardState` into a `storage` half (synced, always shown)
  and an `identity` half (session-only), because the descriptor-vs-session boundary was
  always about *actions*, never about the number.
- CE-4 **deleted** `storageUsedBytes` / `storageLimitBytes` from the native
  `PublisherAccount` rather than leaving them unread, on the grounds that a field nothing
  populates correctly invites the next reader to trust it. The TS mirror still carries
  both, and the next reader did trust them.

### F4 — Two byte formatters, neither of which can print the top tier

**Resolved**, folded into F3 — fixing F3 is what makes a 2 TiB limit reachable on
that screen, so shipping the GB ceiling alongside it was not an option. `formatBytes`
(file sizes) deliberately left alone.


`ServicesPage.tsx` carries a local `formatBytes` that stops at GB, so Premium's 2 TiB
would render as **"2048 GB"** — the exact defect CE-6's manual pass found on native, on
the exact screen we ask people to buy from. `src/lib/format.ts` has a *second*, unrelated
formatter that stops at **MB**. `web/drive/src/lib/paths.ts` has a third that goes to TB
and is correct.

⚠ The apex cannot currently reach Premium (F2), so this is latent rather than visible —
but it is latent precisely on the path F2's fix would open.

### F5 — The landing page's app roster is two years of work behind

**Resolved, and it was worse than recorded here.** Not just Timeline mislabelled:
**Calendar, Contacts, Fit and Maps were not on the page at all.** Seven apps run on real
data; the page claimed two. Now three tiers (lead / more / planned), each claim checked
against the code rather than `milestones.md` — which is stale in the same direction, still
filing five shipping apps under *Future Apps*. Home and Mia stay below the line.


`locales/en/landing.json` block 7 says *"Two apps ship today"* and lists **Timeline** and
**Mia** under "In planning". Timeline ships on Android, desktop and iOS with movement
capture, route rendering and Wi-Fi place recognition. **Fit, Calendar, Contacts, Maps and
Home** are not mentioned anywhere on the page — all five have shipping
`*-android` / `*-desktop` / `*-ios` shells in `settings.gradle.kts`.

The FAQ's "currently a few GB of encrypted storage" is now under-selling a 5 GB free
tier, and vaguer than the number the product already commits to.

### F6 — Smaller things

- **The apex contradicts itself on the free tier.** `onboarding/screens/TailScreens.tsx`
  hardcodes `FREE_TIER_GB = 5` (right); the services picker copy says `1 GB` (wrong).
- **No last-seen in the web device roster.** Native has a locally-derived
  `DeviceLastSeen` because a duplicate, identically-named roster row was undiagnosable
  without it. The web roster has the same name-collision exposure (it identifies "this
  device" by *name match*, per its own comment) and no equivalent signal. Revoke itself
  *is* wired (`Devices.revoke` / `revokeSelf`), so this is a diagnosis gap, not a
  capability gap.
- **`CLAUDE.md` is stale about `Recovery.revealPhrase()`**, which it describes as
  "`unavailable`/coming soon until the Kotlin actual lands". The Kotlin actual is in
  `Recovery.kt:102` and `web/sdk/src/onboarding.ts` wires it.

---

## 3. What is *not* stale (parked on purpose)

Worth stating so the next sweep doesn't re-raise them:

- **The Apps section listing only Drive.** `control/apps/manifest.ts` says so in its own
  header: manifest-driven app data is deferred to the cross-origin work in web roadmap
  Phase 8, and the v1 entry is hard-coded so the IA renders without a manifest fetch that
  has no source origin yet. This is a documented deferral, not drift.
- **No Calendar / Fit / Maps / Home on the web at all.** The JS runtime registers Files,
  Notifications, Identity, Storage, Contact, Timeline and Sync. The other services are
  simply not in the bundle's dependency graph — a scoping decision, and the schema fence
  is self-scoping about services a device doesn't know. ⚠ Not in tension with F5: the
  landing page describes what the *project* ships across platforms, not what the web
  runtime hosts.
- **The construction gate.** Pre-launch curtain, documented as polish and explicitly not
  security.

---

## 4. What is left

The order this section originally proposed (F1 → F3 → F5 → F2+F4) was followed, and all
five shipped the same day. What remains is not more findings — it is verification the
sandbox could not do:

1. ⚠⚠ **Restore Actions runners** (§1). Until then nothing merged here has been gated, and
   F1's widened trigger is inert.
2. ⚠⚠ **Walk the commerce flow once, for real** — `docs/how-to/commerce-dry-run.md` §3b.
   Nobody has watched a euro move through the web path: CORS admitting the browser, Stripe
   redirecting to `/control/services/checkout-result`, the entitlement syncing to the card.
   Every layer is tested in isolation; the composition is not.
3. **Look at the rendered pages.** The Services card, the checkout acknowledgement and the
   re-tiered landing block are all pinned by assertions and none have been seen in a
   browser. ⚠ CE-6's two real defects were both found by *looking at a render*, and one of
   them was a price on a purchase screen — precisely the surface F2 added.
4. **F6's remainder** — the web device roster has no `DeviceLastSeen` equivalent, and
   `CLAUDE.md` is still stale about `Recovery.revealPhrase()`.

⚖️ Item 2 is a product-risk item, not a chore: it is the difference between "the code is
right" and "the money path works".

---

## 5. How this was verified

- Fresh `pnpm install --frozen-lockfile`; `lint` / `build` / `test` run and read.
- `:tenshu-sdk-js` compiled from source to separate "apex is broken" from "the bundle was
  never built here". Full `jsBrowserDistribution` was blocked by sandbox egress policy
  (see §1), so the claim rests on `compileKotlinJs`, not on a packaged bundle.
- CI coverage computed by diffing `tenshu-sdk-js/build.gradle.kts`'s `project(":…")`
  declarations against `web.yml`'s `paths:` list, then replayed against the 49 commits in
  `0e517b4..HEAD` to count real misses.
- Tier and quota claims read from `ProductCatalog.kt`,
  `EntitlementStorageProvider.kt` and `ServicesViewModel.kt`, not from proposal prose.

# Android APKs from tenshu.app: the phased plan

**Status:** v0.5, 2026-11-06. Phase 0 decided by the maintainer 2026-10-31. Phase 1 started: item 1
carries the side-channel pin slot, `--direct`, the website install route, **D2 built**
(arm64-only direct APKs, checked by `--direct`) and the D4-consistent update text. **Items 3, 4 and 9
built (2026-11-04)**: the release bucket's Terraform (not applied), the publish step and
`store/android-release.json`'s schema, and the threat-model amendment. **Items 5, 6 and the code half
of 7 built (2026-11-04)**: the download pages, the Caddy 302 and the privacy policy (reviewed under the
maintainer-as-counsel checklist 2026-11-04, not material). Item 11 (the skew fence's **Update** action)
is built too. **Item 2, first half (2026-11-06):** the side-channel key exists in the maintainer's
Keychain and is pinned in source and in `assetlinks.json` (live on tenshu.app). **The pin reached Play
production in 1.0.2 on 2026-11-06** (D1; code 9, Fit 10), so a website APK is admitted by a Play copy
once that copy has updated. The offline backup of the key is still owed. The rest of Phase 1 is not built.
**Builds on:** item 1 (side-channel pin slot in `FirstPartySigning`, `scripts/android-release.sh
--direct`, the website "Get Control" route, and the runbook in `docs/how-to/android-release.md`
*Releasing outside Play* / *The side-channel key* / *Updates outside Play*);
[`commerce-store-link-out.md`](commerce-store-link-out.md) D3 (a non-Play APK sells through Stripe and
runs without Google Play services); [`store-presence-and-support-pages.md`](store-presence-and-support-pages.md)
§4.1 (`/apps/<app>/download/android/<lang>` is reserved) and §4.8 (pricing page).

**Goal:** a "Download APK" link on the website for phones without Google Play, that installs, talks to
the other Tenshu apps and buys storage through Stripe. Updating means downloading the latest APK again.

---

## Where things stand (checked 2026-10-31)

| Area | State | Evidence |
|---|---|---|
| Google services in the apps | **Ready.** None: location is `LocationManager`, QR is ZXing, maps MapLibre; the only Play library (Billing) links on the `play` rail only | `control-shared/build.gradle.kts` rail switch; `AndroidLocationCaptureSource.kt` |
| Billing | Stripe rail exists, **default is `play`**; a direct build must pass `-Ptenshu.android.billingRail=stripe` | `control-shared/build.gradle.kts:9-19` |
| Signing | **Blocker.** Play App Signing gives each Play app its own Google-held key; the apps admit each other by certificate (`FirstPartySigning`), so a website copy and a Play copy refuse each other until the side-channel pin reaches the device. **Key created and pinned in source 2026-11-06**; it still has to ship in a Play release (D1) | `android-release.md` *The side-channel key* |
| Release build | Item 1 adds `--direct` (APKs, checks, `SHA256SUMS`, `release.json`) | `scripts/android-release.sh`, `android-release.md` *Releasing outside Play* |
| Size | Universal: Control 131 MB, all ten 313 MB. arm64-only (D2, built in item 1): ~35 MB / ~114 MB estimated; Home measured 6.1 → 3.0 MB | `android-release.md` *Universal or arm64-only* |
| Updates | **Decided (D4):** download the latest APK again. House Rule 9 lockstep means a stale app gets fenced; the fence's **Update** button opens the download page (item 11) | `core-schema-skew-surface.md` §8.5 |
| Hosting | The release bucket is `releases.tenshu.app` (item 3, not applied). `assets.tenshu.app` is an R2 custom domain (no Worker): immutable `sha256/` objects, `accept-ranges`, Range → 206 (resumable downloads) — verified live | `deploy/cloudflare-assets/main.tf` |
| Website | **Built 2026-11-04:** `/apps/<app>/download/android/<lang>` for all ten apps, "not available yet" until `store/android-release.json` exists; `/apps/<app>/download/android` 302s by language. Before that deploy, the URL item 1 compiles into the apps returned the SPA (200, wrong page) | `web/apex/support-pages/androidDownload.ts`, `web/apex/Caddyfile` |
| Play | Control's and Drive's Play listings are not public yet (404) | live `curl` |

## Phase 0: decisions (maintainer, 2026-10-31)

1. **D1. The side-channel key is pinned in the second Play release.** The first release is already in
   review without it. Accepted consequence: a Play install still on that first release refuses a
   website copy (and the reverse) until it updates. The direct channel is for advanced users who run the
   newest version anyway, so no compatibility work for that window.
2. **D2. arm64-only APKs** (one `abiFilters` line, `android-release.md` *Universal or arm64-only*). Other ABIs only if
   someone asks; each then needs its own versionCode.
3. **D3. A release bucket of its own on Cloudflare R2, with its own publish key**, not `tenshu-assets`.
   R2 for the free egress and full control. Own bucket because R2 does not check that a key matches its
   hash: a leaked assets key could overwrite `sha256/<hex>.apk`, and a *fresh* install trusts whatever
   key signed it. Same per-bucket isolation the assets README already applies.
4. **D4. The direct channel has no update mechanism.** Updating means downloading the latest APK from
   the download page and installing it over the old one. No self-updater, no
   `REQUEST_INSTALL_PACKAGES`, no F-Droid client, no update check in the app. House Rule 9 still holds,
   so a user who updates one app and not another hits the existing schema-skew fence; its message must
   send them to the download page (Phase 1 item 11).
   - **F-Droid is a separate, later channel** (Phase 3), with its own build. It is not how the website
     APKs update.
5. **D5. Google's developer verification: register the side-channel key in Play Console.** Checked
   2026-10-31 against developer.android.com/developer-verification and Play Console Help 16761053:
   - Identity: done. Our verified Play organisation account meets it; no new action.
   - Package names: Play registers each package automatically when the app is created in Play Console,
     linked to the Play App Signing key.
   - ⚠ The side-channel key is a **second key** on packages that already exist, so each needs
     *Adding additional keys*: build an APK with that package name, put the account's snippet in
     `assets/adi-registration.properties`, sign it with the side-channel key, upload it in Play
     Console. Without it, a website APK will not install on certified devices once enforcement
     reaches the user's country. ⭐ Ownership is proven **once per developer account**: Google's docs
     say a key already verified for one package skips the APK on the others. **Done 2026-11-06**:
     all ten packages answer `REGISTERED` for the side-channel digest
     (`scripts/lib/check_developer_verification.py`, the Android Developer ID Status API).
   - Not verified: the enforcement dates. A third-party source names 2026-09-30 for Brazil, Indonesia,
     Singapore and Thailand and 2027+ globally; Google's pages do not state them.

## Phase 1: the first public APK

| # | Item | Owner |
|---|---|---|
| 1 | Merge the side-channel pin slot, `--direct` and the install route (review pipeline, gate, smoke). ⚠ First drop its F-Droid-as-update-route advice (`android-release.md` *Updates outside Play*, the `core-schema-skew-surface.md` §8.5 amendment): it contradicts D4 | code |
| 2 | `android-release.sh --init-key --direct`, offline backup, pin the digest, ship in the **second** Play release (D1). Key created and pinned 2026-11-06 (also in `assetlinks.json`); shipped to Play production in 1.0.2 the same day. ⚠ Offline backup owed | maintainer |
| 3 | Terraform the release bucket + custom domain. Each PUT sets `Content-Type: application/vnd.android.package-archive`, `Cache-Control: public, max-age=31536000, immutable`, `Content-Disposition: attachment; filename="tenshu-<app>-<versionCode>.apk"` (else the download is named after its hash). **Built, not applied:** `deploy/cloudflare-releases/` (`tenshu-releases` at `releases.tenshu.app`, r2.dev off; README has the key-minting steps). ⚠ Applying it and minting the bucket-scoped `r2-releases-*` key is the maintainer's | ops |
| 4 | A publish step (model: `scripts/store-publish.sh`): refuses `build/direct-unpinned/`, uploads objects first, then writes a committed `store/android-release.json` (version, size, SHA-256, cert fingerprint per app). **Built:** `scripts/android-direct-publish.sh` (`--dry-run`, `--verify` downloads and hashes every APK); also refuses a signer other than the pin (read back from each APK), a lower versionCode and a key change. Manifest schema 1: `android-release.md` *Publishing a direct release* — item 5 reads it | code |
| 5 | ✅ Static pages `/apps/<app>/download/android/<lang>`, rendered from that manifest at build time (no runtime fetch, no CSP change), with the install and update guide: Control first; update = download again and install over; allow the browser to install apps; the Play Protect prompt; never mix Play and website copies; verify with `apksigner verify --print-certs`. Built: `support-pages/androidDownload.ts`, reading the publish step's schema 1 (below) | code |
| 6 | ✅ ⚠ A language-neutral 302 for **`/apps/control/download/android`**: that exact URL is compiled into the apps (`CONTROL_ANDROID_DOWNLOAD_URL`). Built as `@androidDownloadNeutral` for all ten apps (item 11 links each app's own page), checked against a real Caddy 2.11 | code |
| 7 | Privacy policy: Stripe also for APK purchases; Cloudflare also serves app downloads; bump `PRIVACY_POLICY_DATE`. Terms and pricing already cover "Android apps from outside Google Play". Code ✅ (`2026-11-04`, with `PRIVACY_VERSION` in Kotlin and `commerceApi.ts`; the Google Play paragraph now says only a Play copy asks Google for purchases); ⚖️ signed off 2026-11-04 under the maintainer-as-counsel checklist (`docs/policy/privacy-review-checklist.md`), not material | code + ⚖️ counsel |
| 8 | Device checks never run: install on an emulator image **without** Play services; Stripe checkout on the APK; Play Control + website Drive, and the reverse | maintainer |
| 9 | Threat model: the release bucket as an install trust root; the side-channel key under ops custody (`ops-secret-custody.md` D1/D5). **Built:** assets A43 (the key) / A44 (bucket, key, manifest), attack surface #20, §8.23 T-DA-01–05, a TA4 note | code |
| 10 | ⚖️ Play policy: neither the Play listing nor a Play build links the APK (the install route already sends Play installs to Play) | counsel |
| 11 | ✅ **Built 2026-11-04, widened by the maintainer to every store channel.** The skew fence has an **Update** action (`ControlBannerAction.Kind.UPDATE`): a downloaded copy opens the stale binary's tenshu.app page, a Play copy its Play listing (`tenshu.<app>.playUrl`), iOS its App Store page (`TENSHU_APP_STORE_ID`); empty config = no button. It is Control's page for a Control verdict, and each page follows that copy's own installer. Rules: `core-schema-skew-surface.md` §8.5. ⚠ Not seen on a device yet | code |
| 12 | Register the side-channel key for all ten packages in Play Console (D5). ✅ 2026-11-06, all ten `REGISTERED` (`scripts/lib/check_developer_verification.py`) | maintainer |

### The download pages read the release manifest

`store/android-release.json` is the contract between the publish step (item 4) and the download pages
(item 5). Its schema is the publish step's, schema 1, in `docs/how-to/android-release.md`
*Publishing a direct release*. The pages' reader, `loadAndroidRelease`
(`web/apex/support-pages/androidDownload.ts`), re-checks what a page depends on, and a file it rejects
**fails the website build**:

- `url` must equal `base + sha256 + ".apk"` and `filename` `tenshu-<app>-<versionCode>.apk`; a per-app
  `abi` / `signingCertSha256` must equal the top-level one (D2, one key for all ten).
- ⚠ No file, or an app missing from `apps`, renders "not available yet" and links nothing.
- Each page shows its app's own version (publishing one app leaves the others' entries as they were),
  the file's SHA-256 and size, and the certificate fingerprint twice: as stored, and as
  `apksigner verify --print-certs` prints it (lowercase, no colons).
- ⚠ A schema change moves `direct_publish.py check_manifest` and `loadAndroidRelease` in the same PR.
- Committing the file is a website redeploy (the pages are built from it), not an app release.

## Phase 2: convenience

- **A real channel-mismatch message.** Today a wrongly signed Control reads as *absent*
  (`BinderTaskTransport.kt` ~327), so the user is told to install what they have. Say "installed from a
  different source" instead (ADR 0025's tri-state rule).
- **Switching channels means uninstalling** (`allowBackup="false"`): the guide says sync first, then
  remove the old device in Control, so its orphaned identity does not stall reclamation.
- Block 6 on the landing page gets "or download the APK".

## Phase 3: later

- **An F-Droid channel**, its own build, independent of the website APKs (D4). Open: whether F-Droid
  signs it (then its key is a third channel for `FirstPartySigning` and needs registering under D5 via
  the delegated-key path) or ships our signature through a reproducible build.
- A signed `release.json` (Ed25519, the `model-signer` pattern, its own key).
- A changelog page (`/apps/<app>/changelog/<platform>/<lang>`, reserved in §4.1).

## Not verified

Cloudflare's default caching of `.apk`; any rate limit on the assets domain (none configured;
R2 egress is free); Google's enforcement dates (D5); Play's rule on mentioning the APK; any install on a
real device.

# How-to: notarized Developer ID releases of the desktop apps

`scripts/macos-release.sh` builds one notarized, stapled `.dmg` per desktop app. It is the
off-store channel of [`desktop-macos-distribution.md`](../proposals/desktop-macos-distribution.md)
§2.12: the Homebrew cask and the tenshu.app download both install these files. The Mac App Store
builds of Control and Drive are a separate pipeline (`scripts/macos-mas-archive.sh`).

It builds and checks. It does **not** upload anything, write a cask or touch `releases.tenshu.app`;
those are later steps (§2.12.6 H4–H7 of the proposal).

## 1. Once: the certificate and the key

1. **Developer ID Application certificate, team `752LWAZMKZ`.** Only the Account Holder can create
   it: developer.apple.com → Certificates, IDs & Profiles → Certificates → **+** → *Developer ID
   Application*. Create the CSR in Keychain Access on the release Mac, download the certificate,
   double-click it into the **login** keychain, then export certificate + private key as a `.p12`
   and keep it offline. ⚠ A lost private key cannot be recovered, and Apple limits how many of these
   a team may hold; a revoked one makes Gatekeeper refuse what it signed.
   Check: `security find-identity -v -p codesigning` lists
   `"Developer ID Application: Cajetan UG … (752LWAZMKZ)"`.
   The *Developer ID Installer* certificate is not needed: the release is a `.dmg`, not a `.pkg`.
2. **App Store Connect API key.** The one `ios-archive.sh` and `macos-mas-archive.sh` use:
   `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_PATH` (`how-to/ios-testflight.md` §2.1). Not verified
   that `notarytool` accepts it without a Developer role; the first submission answers it.

## 2. Run it

```bash
scripts/macos-release.sh --dry-run            # identity, credentials, tools, the plan; builds nothing
scripts/macos-release.sh                      # all ten apps
scripts/macos-release.sh photos maps          # a subset
```

Output, only when every check passed: `build/macos-release/<Bundle>-<version>.dmg` (e.g.
`TenshuPhotos-1.0.1.dmg`), `SHA256SUMS`, and `logs/` with every `notarytool` reply. On a rejected
submission the script fetches `notarytool log` into `logs/<app>-…-notary-log.json`; that file is the
only useful explanation Apple gives.

It refuses, before building:

- no Developer ID Application identity for the team (the message says how to create one), or more
  than one (pick with `--identity <sha1>`), or one whose certificate is for another team. ⚠ The
  team is read from the certificate's subject OU, not from the "(…)" at the end of its name, which
  for an Apple Development certificate is the person's id;
- missing `ASC_*` variables or key file;
- a tree with uncommitted changes (`--allow-dirty` to override). A release is built from a commit.

## 3. What it does, per app

Steps 1–2 run for **every** app before anything goes to Apple; the D9 version check (step 6)
runs there too. Steps 3–5 then write to `build/macos-release.staging/`, which replaces
`build/macos-release/` only after the last app passed. A failed run leaves no partial release.

1. Builds with `-Ptenshu.developerIdRelease=true` (`gradle/desktop-app-signing.gradle.kts`), from a
   fresh distributable, with one `CFBundleVersion` for the run (the UTC minute, or
   `TENSHU_BUILD_NUMBER`). That mode:
   - pre-extracts the arm64 ONNX Runtime and SQLite dylibs into `Contents/app/native`, points the
     loaders there and strips every native entry from the jars (the MAS staging task, reused);
   - finds Mach-O in jars by **magic bytes**: the three helper executables that ride in jars as
     classpath resources (`qr-scan-helper`, `image-decode-helper`, `map-snapshot-helper`) are
     dropped once `embedJarHelpers` has put a copy in `Contents/Helpers` (every packaged build does
     that), and any other Mach-O fails the build;
   - signs each helper with its own entitlements: the App Group, plus the camera for
     `qr-scan-helper` only, and never the host's `allow-jit`;
   - signs every Mach-O inside-out with `--options runtime` and a secure `--timestamp`;
   - gives the host `application-groups`, `cs.allow-jit`, and one hardened-runtime resource
     entitlement per `NS…UsageDescription` the bundle declares (camera for Control and Drive,
     calendars for Calendar). No `disable-library-validation`.
2. Checks the bundle, failing on any of:
   - `CFBundleIdentifier` is not the app's iOS bundle id (C4), or `CFBundleVersion` is not the
     run's;
   - `LSMinimumSystemVersion` is not `tenshu.macosDeploymentTarget` (D10, 14.0). ⚠ A module that does
     not set `minimumSystemVersion` gets jpackage's 10.13: eight of ten did in the first notarized
     1.0.1, which would have offered to open on macOS 11–13;
   - the nested-helper count differs from `scripts/lib/app-catalogue.sh`;
   - **any** Mach-O in the bundle without our team, the hardened runtime flag, a secure timestamp,
     the run's signing identity, or with a slice other than arm64;
   - native code left inside any archive in the bundle, found by magic bytes and recursing into
     archives inside archives (⚠ an extension check passed the three helpers Apple rejected);
   - `get-task-allow` or `disable-library-validation` on the host or a nested bundle, or no
     `allow-jit` on the host;
   - `codesign --verify --strict --deep`.
3. Notarizes the app (zipped with `ditto`) and staples it, so it passes Gatekeeper offline after
   it is dragged out of the image.
4. Builds the `.dmg` (the app and an `/Applications` link, UDZO), signs it, notarizes and staples it.
5. Gatekeeper's verdict: `spctl -a -vv -t exec` on the app must say `source=Notarized Developer ID`;
   `spctl -a -t open --context context:primary-signature` on the `.dmg`; `stapler validate` on
   both.
6. One `CFBundleShortVersionString` across the run (D9, checked with step 2), then `SHA256SUMS`.

## 4. Without the certificate: `--pipeline-test`

```bash
scripts/macos-release.sh --pipeline-test             # all ten
scripts/macos-release.sh --pipeline-test --dry-run
```

Everything above except notarization, stapling and the Gatekeeper verdicts. It signs with the
Developer ID identity if there is one, else with the team's Apple Development identity, and writes
to `build/macos-release-test/`. ⚠ Nothing there is installable on another Mac: it is not notarized,
and an Apple Development signature is accepted only on the team's own devices.

## 5. After a C4 build: reinstall every app together

Since C4 (2026-11-05) every desktop app's bundle id is its iOS one (`app.tenshu.<app>.ios`), and
Control's IPC gate admits only those identifiers. A Mac that still has a pre-C4 copy of an app
(`app.tenshu.photos`, …) next to a new Control finds that app refused at the socket, so it shows
*Control not running*. Reinstall all ten together (`scripts/reinstall-all-apps.sh`). macOS
treats the renamed apps as new: privacy grants (calendar, camera) and notification permissions
are asked again, once. Their settings in the group container are keyed by an internal name and
survive.

## 6. Verified, and what is not yet

**Notarization is verified** (2026-11-06, build `202611061041`): all ten apps and all ten `.dmg`s
were accepted, and every `.dmg` passes `spctl … --context context:primary-signature` as
`Notarized Developer ID` and `stapler validate`. The first submission (05 Nov) was rejected for
the three unsigned helpers inside jars, which is what §3.1–§3.2's magic-byte scan now catches
locally.

⚠ Do not commit to (or edit) `scripts/macos-release.sh` while a run is going: bash reads the
script as it executes, so the edit moves its read position and runs a fragment of a line.

**A quarantined launch is verified** (2026-11-06, Photos): a copy taken from the `.dmg` with
`com.apple.quarantine` set showed the notarized-app prompt (*"Apple checked it for malicious
software and none was detected"*), and after *Open* ran from App Translocation with the JVM, JIT and
the bundled dylibs under the hardened runtime and library validation, and drew its window. ⚠ The
prompt needs a click, so this cannot run on a locked screen or in automation.

Not verified yet:

- On that launch: the QR camera (whether TCC wants the entitlement on the helper, the JVM or both;
  the build gives it to both), Calendar's EventKit read, the File Provider extension without a
  profile, and Control serving a quarantined client end to end.
- **Touch ID**: `keychain-helper.app` is opt-in and not in these builds. A Developer ID build that
  embeds it needs a Developer ID provisioning profile for `keychain-access-groups`
  (proposal §2.3), and the check "every Mach-O signed by the run's identity" refuses it until then.

## 7. Publishing: the bucket, the manifest, the casks

`scripts/macos-direct-publish.sh` puts the ten `.dmg` of `build/macos-release/` into the Android
release bucket (`tenshu-releases`, `releases.tenshu.app`) at `sha256/<hex>.dmg`, and writes the
committed `store/macos-release.json` last. Why that bucket and not a `downloads.tenshu.app`: proposal
§2.7 *Hosting*. Threats: threat model §8.24.

### 7.1 Once

1. **The bucket.** `deploy/cloudflare-releases/` (`./tf.sh init && ./tf.sh plan && ./tf.sh apply`,
   from the checkout that holds the other Cloudflare modules' state; `terraform.tfvars` there). ⚠ The
   maintainer's call: it creates the public bucket and `releases.tenshu.app`. Wait for
   `./tf.sh output custom_domain_status` to say `ssl = active`.
2. **The publish key**, after the bucket exists. Cloudflare dashboard → **R2 Object Storage** →
   **Manage API tokens** (*API* menu on the R2 overview) → **Create API token**:
   - Token name: `tenshu-releases publish`;
   - Permissions: **Object Read & Write**;
   - Specify bucket(s): **Apply to specific buckets only** → `tenshu-releases`, and nothing else;
   - TTL: forever (rotate by hand, `deploy/cloudflare-releases/README.md`); no IP filter.

   Create, then store the *Access Key ID* and *Secret Access Key* (shown once; `-w` with no value
   prompts, so nothing reaches shell history):

   ```bash
   security add-generic-password -U -a tenshu -s r2-releases-access-key-id -w
   security add-generic-password -U -a tenshu -s r2-releases-secret-access-key -w
   ```

   The Android direct publish uses the same pair.
3. **The tap.** A GitHub repository `b3nedikt/homebrew-tenshu` (the name `brew tap b3nedikt/tenshu`
   expects), ⚠ **public**: `brew` clones it without credentials. It holds only `Casks/*.rb` and a
   README. The maintainer's call.

### 7.2 Each release

```bash
scripts/macos-direct-publish.sh --dry-run --casks /tmp/tap   # checks + diff + casks; no network
scripts/macos-direct-publish.sh                              # upload, read back, hash back, manifest
scripts/macos-direct-publish.sh --verify                     # every .dmg from the public URL
scripts/macos-direct-publish.sh --casks ../homebrew-tenshu   # the tap's Casks/ from the manifest
```

Then commit `store/macos-release.json` here, and the casks in the tap. ⚠ Casks rendered by
`--dry-run` name the planned manifest: never push those before the real publish.

The publish refuses, before any network: anything but exactly the ten apps at one version and one
build; a `.dmg` whose bytes differ from `SHA256SUMS`; one that `stapler validate` or `spctl -t open
--context context:primary-signature` does not accept as Notarized Developer ID; an app inside (read
from a read-only mount) whose bundle id, version or minimum macOS is off, or that is not signed by the
team's Developer ID; a version lower than the published one; and a published version with other
bytes. A version is published once.

**The manifest** (`store/macos-release.json`, schema 1): `version`, `build`, `minimumMacOS`, `arch`,
`teamId`, `published`, `base`, and per app (catalogue order) `bundle`, `bundleId`, `file`, `url`
(= base + sha256 + `.dmg`), `sha256`, `bytes`. `scripts/lib/macos_publish.py check` enforces it.

**The casks** follow proposal §2.12.6 and pass `brew style`: `depends_on arch: :arm64` and
`macos: :sonoma` (derived from the manifest's `minimumMacOS`, which must equal
`tenshu.macosDeploymentTarget`), no
`depends_on cask: "tenshu-control"` (T3) and no `auto_updates` (T4). Control's removes its two
LaunchAgents (C1) and its `zap` removes the group container, the wrapping key and the folder grants.
Every other app's names Control in `caveats`. `livecheck` reads `tenshu.app/apps/<app>/download/macos.json`,
which the website build writes from the committed manifest (`web/apex/support-pages/macosDownload.ts`):
⚠ so a release is complete only after the apex is redeployed with the new manifest.

Tests: `python3 scripts/test_macos_publish.py` (no network: the Gatekeeper and mount step is faked;
the catalogue, every refusal, the manifest and the casks run for real, and `ruby -c` checks each
cask when Ruby is installed).

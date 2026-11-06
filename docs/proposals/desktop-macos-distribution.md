# Distributing the macOS desktop apps: Developer ID first, Mac App Store assessed

**Status:** Draft v0.4, 2026-11-06. D2 (no Intel), D3 (no "Designed for iPad" route), D11 (try the
Mac App Store, spike first) and D12 (reuse the iOS bundle IDs) are decided by the maintainer. **Route
A is live:** Tenshu 1.0.1 for Mac is published (2026-11-06) as ten notarized Developer ID `.dmg` on
`releases.tenshu.app` (D5 and D7 revised, §2.7), with the Homebrew tap `b3nedikt/homebrew-tenshu`
and the download page (H7, live from the next apex redeploy). Not built: R1 in the publish script
(H6), the updater (D8). Route B:
Control and Drive 1.0.1 are uploaded to App Store Connect. §2.12 answers whether the apps can come
from different channels on one Mac (yes, one copy per app) and holds the Homebrew plan H1–H7.

**Scope:** how a person who is not the maintainer gets Tenshu Control and Tenshu Drive onto an Apple
silicon Mac, keeps them updated, and removes them. Control and Drive first; the other eight desktop
apps (Calendar, Contacts, Fit, Home, Maps, Mia, Photos, Timeline) join the same pipeline later (§6).
Windows and Linux are out of scope.

**Relates to:** [`milestones.md`](../milestones.md) *Desktop distribution channels* (the earlier
sketch this doc turns into a plan); [`how-to/macos-signing.md`](../how-to/macos-signing.md) (local
signing; its §4 stub is what this doc fills in);
[`desktop-macos-integration.md`](desktop-macos-integration.md) (tray, File Provider, App Group
socket); [`desktop-masterkeystore.md`](desktop-masterkeystore.md) (Keychain wrapping key, Touch ID
helper); [`desktop-ipc-client-identity.md`](desktop-ipc-client-identity.md) (the Team-ID-pinned peer
gate); [`core-schema-skew-surface.md`](core-schema-skew-surface.md) and
[ADR 0025](../adr/0025-forward-to-control-surface.md) (update routes, store-channel detection);
[`store-presence-and-support-pages.md`](store-presence-and-support-pages.md) (the
`/apps/<app>/<page>/<platform>/<lang>` pattern, R2 assets);
[`how-to/ios-testflight.md`](../how-to/ios-testflight.md) and `scripts/ios-archive.sh` (the iOS
release pipeline this one mirrors); [`ios-per-app-frameworks.md`](ios-per-app-frameworks.md) §6.3
(version lockstep between installed apps).

**How to read the evidence.** *Measured*: observed on this Mac on 2026-10-15 (macOS 26.6.2,
Xcode 27.0) with read-only commands. *Read*: taken from the cited file at `a6d2ef126`.
*Apple*: Apple documentation or policy I am confident of, with a link. *Not verified*: inference or
general knowledge; each one names what must be checked, and the phase that checks it.

---

## 0. Decisions

| # | Decision | Recommendation | Why, in one line |
|---|---|---|---|
| D1 | First distribution route | **A: direct download, Developer ID + notarization** (§2) | It ships the apps as they are built today; the Mac App Store (B) needs a sandboxed Control, which is a redesign (§4). |
| D2 | Intel Macs | ✅ **Decided 2026-10-15: no. Apple silicon (arm64) only.** | Intel Macs are effectively gone from the target audience (§3). |
| D3 | Route C: the iOS apps on Apple silicon Macs ("Designed for iPad") | ✅ **Rejected by the maintainer.** | It would put the phone app on a Mac: no menu-bar tray, no Finder File Provider domain, no resident Control. |
| D4 | Signing team for desktop | **The company team `752LWAZMKZ` for every desktop build**, the personal team `FAYT82GWD7` dropped from the compiled trust anchor | The installed apps are already signed by it (measured, §1.1); Developer ID needs the paid team anyway. |
| D5 | Installer format | **One suite `.pkg` ("Tenshu for Mac") holding every app in the release**, signed with Developer ID Installer, notarized, stapled. No per-app `.dmg`. | One install puts every app at one commit, which is what the schema needs (§2.9); a `.dmg` per app invites Drive-without-Control and skew. ⚠ **Revised 2026-11-06, as built: a notarized `.dmg` per app** (`scripts/macos-release.sh`), because with Control in the store the suite would install a second Control over it (§2.12.5). |
| D6 | Hardened-runtime entitlements | **The smallest set that launches, measured in M1.** Start from `allow-jit` alone, with every Mach-O in the bundle re-signed by our team; add `disable-library-validation` or `allow-unsigned-executable-memory` only if a measured failure needs them. | Each one weakens the hardened runtime; today's `disable-library-validation` exists only because the bundled runtime is left ad-hoc (§2.3). |
| D7 | Where the downloads live | ⚠ **Revised 2026-11-06: the Android release bucket `tenshu-releases` at `releases.tenshu.app`, content-addressed (`sha256/<hex>.dmg`)**, with the version in the committed `store/macos-release.json`. Was: a new `tenshu-downloads` bucket with versioned paths. | One install trust root and one key instead of two; content keys are immutable by construction; the cask pins URL and sha256 per version anyway (§2.7). |
| D8 | Updates | **Suite-level and lockstep by construction.** M2: Control checks the signed manifest and says "a newer Tenshu is available" with a link. M3: an in-place updater in Control, **own code rather than Sparkle** (§2.8). | Sparkle updates one bundle; the requirement is that all installed apps move together. |
| D9 | Version numbers | **One version for the whole suite**, the iOS `MARKETING_VERSION`; `CFBundleVersion` the UTC minute like `ios-archive.sh`; injected into the launcher so the apps can report it. | `packageVersion = "1.0.0"` is hard-coded in all ten modules (read) and an updater needs a number that grows. |
| D10 | Minimum macOS | ✅ **Implemented 2026-11-04: 14.0**, one property (`tenshu.macosDeploymentTarget` in `gradle.properties`) feeding `LSMinimumSystemVersion` and every `swiftc -target`. Still to confirm on a macOS 14 machine (M1). | Measured floors: ONNX Runtime 13.3, login item and File Provider 13.0. App Store Connect refuses an arm64-only app below 12.0 (ITMS-90869, measured). ⚠⚠ `swiftc` without `-target` stamps the **build** Mac's version as the minimum: the helpers came out at 27.2 and would not launch on any older macOS. |
| D11 | Mac App Store | ✅ **Decided 2026-10-17: try it, spike first.** Route A (D1) stays the first route. The submission spike (§4.3.1) runs before any of §4.2's sandbox rework is built. If the JVM cannot pass, the fallback is the JVM-less macOS app (milestones.md), considered then, not now (§4.6). | The maintainer wants to at least attempt the store for the native (JVM) desktop apps; the spike is the cheapest experiment that can say no before §4.2's redesign is paid for. |
| D12 | Bundle ids for Apple apps | ✅ **Decided 2026-11-03: reuse the iOS bundle IDs on macOS** (`app.tenshu.control.ios`, `app.tenshu.drive.ios`) to preserve one App Store Connect record and Universal Purchase (§4.4) | Internal Tenshu app IDs remain platform-neutral; the desktop LaunchServices seam maps them to the shared Apple bundle IDs. Linux has no Apple bundle-ID constraint. |
| D13 | Uninstall | **"Remove Tenshu from this Mac" in Control**, plus a documented manual list (§2.10) | Dragging the apps to the Bin leaves two LaunchAgents, the group container, a Keychain item and a File Provider domain. |

---

## 1. Where things stand

### 1.1 What is installed on the maintainer's Mac (measured 2026-10-15)

- Ten bundles in `/Applications`: `TenshuControl.app` … `TenshuTimeline.app`. The first-party
  Apple bundle IDs are shared with iOS where a product record exists (Control and Drive use
  `app.tenshu.control.ios` and `app.tenshu.drive.ios`). Internal app IDs remain `app.tenshu.<app>`.
- Every executable is `Mach-O 64-bit executable arm64`. jpackage builds for the host architecture.
- **Signed by the company team.** `TeamIdentifier=752LWAZMKZ`, authority
  `Apple Development: <maintainer> (<identity id>)`, entitlement
  `com.apple.security.application-groups = [752LWAZMKZ.app.tenshu.shared]`, `flags=0x0` (no hardened
  runtime), signed 2026-10-13. The launcher cfg pins `-Dtenshu.ipc.teamId=752LWAZMKZ`.
  ⚠ The brief for this doc, `how-to/ios-signing.md`'s header ("`local.properties` still carries
  `tenshu.teamId=FAYT82GWD7`, which the macOS desktop build is pinned to") and
  `desktop-ipc-client-identity.md` (which names `FAYT82GWD7` throughout) are stale on this point:
  the switch happened on 2026-10-02 when `local.properties` moved to the company team. Only
  `752LWAZMKZ.app.tenshu.shared` exists under `~/Library/Group Containers`.
- **No Developer ID identity exists.** `security find-identity -v -p codesigning` lists two Apple
  Development identities and nothing else.
- **The bundled runtime is ad-hoc signed.** `Contents/runtime` and its `libjli.dylib` report
  `flags=0x10002(adhoc,runtime)`, `TeamIdentifier=not set`. ⚠ The header of
  `gradle/desktop-app-signing.gradle.kts` says `libjli.dylib` "keeps its own (Adoptium) Team ID";
  on this build it has none. The consequence is the same (library validation would refuse it), the
  cause is not, and it changes D6: once we sign the runtime ourselves the team IDs match.
- **Native code hides in jars.** Control's `Contents/app` has 196 jars. Three carry Mach-O dylibs:
  `onnxruntime-1.20.0` (macOS arm64 *and* x64 dylibs plus `.dSYM`s, and Linux/Windows libraries),
  `skiko-awt-runtime-macos-arm64` and `sqlite-bundled-jvm`. Skiko is also extracted next to the jars
  (`Contents/app/libskiko-macos-arm64.dylib`, with `-Dskiko.library.path=$APPDIR`); the other two are
  unpacked at run time by their libraries (not verified where to: M1).
- **Nested code.** Control: `Contents/Helpers/{tray-agent, applock-helper, qr-scan-helper,
  image-decode-helper}` and `runtime/…/lib/jspawnhelper`; the Touch ID `keychain-helper.app` is
  opt-in and not in this build. Drive: two helpers, `Contents/MacOS/tenshu-domain` and
  `Contents/PlugIns/TenshuFileProvider.appex` (sandboxed, App Group, no embedded profile).
- **Size.** Control 301 MB (79 MB runtime, 221 MB jars), Drive 199 MB, each of the other eight
  189–195 MB: about 2.1 GB for all ten, because every app carries its own JVM and its own copy of the
  shared jars.
- `CFBundleShortVersionString` and `CFBundleVersion` are both `1.0.0`; `LSMinimumSystemVersion` is
  `10.13`.

### 1.2 What the build does today (read)

- `compose.desktop.nativeDistributions { targetFormats(Dmg, Pkg) }`, `packageVersion = "1.0.0"` in
  all ten modules. Control: `LSUIElement` (menu-bar app), `NSCameraUsageDescription`.
- `packageDesktopApp` (shared, `gradle/desktop-app-signing.gradle.kts`) embeds the helpers, pins the
  Team ID into the launcher cfg, signs inside-out without `--deep`, leaves any nested bundle that
  carries its own provisioning profile untouched, and asserts the entitlements survived. It signs
  with `--timestamp=none` and, by default, **without** hardened runtime; `-Ptenshu.hardenedRuntime`
  adds `--options runtime` plus `disable-library-validation`. It does not sign `Contents/runtime`,
  `Contents/app/*.dylib` or anything inside a jar.
- The IPC peer gate admits a peer whose running code satisfies `anchor apple generic and certificate
  leaf[subject.OU] = <team>` (threat model A14). A Mac App Store / TestFlight copy carries Apple's leaf
  certificate instead, so it is admitted by the store marker plus its signed team identifier
  (`desktop-ipc-client-identity.md` §10). The accepted team set is compiled in
  (`generatePeerTrustAnchor`) from `tenshu.teamId` plus `tenshu.additionalTeamIds`.
- Login start: `LaunchAgentManager` writes two plists into `~/Library/LaunchAgents`
  (`app.tenshu.control`, hidden start, `KeepAlive=false`; `app.tenshu.control.tray`, `KeepAlive=true`)
  and loads them with `launchctl`.
- Secrets: `secrets.enc` (AES-256-GCM) in the group container; its wrapping key is a login-Keychain
  generic password, service `app.tenshu.control.desktop.master-key-store`, created by
  `/usr/bin/security` (threat model A21). Touch ID: the Xcode-built `keychain-helper.app` with a
  `keychain-access-groups` entitlement and an embedded development profile.

---

## 2. Route A: direct download, Developer ID and notarization

### 2.1 What it takes, end to end

1. Developer ID Application and Developer ID Installer certificates for the company team (§2.2).
2. Every Mach-O in each bundle signed by that identity, with hardened runtime and a secure
   timestamp, inside-out (§2.3, §2.4).
3. Each app notarized and stapled, then the suite `.pkg` built, signed, notarized and stapled (§2.5).
4. A release script that does all of it and checks the result (§2.6).
5. The `.pkg` on `downloads.tenshu.app` and a download page (§2.7).
6. An update path that keeps the installed apps on one version (§2.8, §2.9).
7. A way out (§2.10).

Gatekeeper is why this is not optional. A downloaded app is quarantined; on macOS 15 and later the
Control-click "Open anyway" shortcut is gone and an unnotarized app needs a trip to System Settings
(Apple, [Gatekeeper and runtime protection](https://support.apple.com/guide/security/gatekeeper-and-runtime-protection-sec5599b66df/web);
the macOS 15 change is not verified against an Apple page here). For a normal user an unnotarized
Tenshu does not install.

### 2.2 The team move, and what it moves

D4 is mostly already true (§1.1). What a desktop team change moves, so that it is written down
before the next one:

| Bound to the team | Where | What happens to an existing install |
|---|---|---|
| Group container `<TEAM>.app.tenshu.shared` | the store, `secrets.enc`, the sockets, window and settings files (`DesktopAppPaths`) | **A new, empty container.** The device starts without a realm, re-pairs, and is a new device identity. Nothing adopts another team's container (nor `~/.tenshu` — that adoption was removed before the production reset). The old one stays on disk until deleted. |
| IPC gate trust anchor | compiled into Control | Old-team clients are refused by a new-team Control (and the reverse) unless the build carries both teams via `tenshu.additionalTeamIds`. Every app must move together. |
| Touch ID item | data-protection Keychain, access group `<TEAM>.app.tenshu.control.keychainhelper` | Orphaned. The user re-enrols; `desktop-masterkeystore.md` §6 already treats `not_found` as re-enrol. |
| Login-Keychain wrapping key | service `app.tenshu.control.desktop.master-key-store` | **Not team-bound**: its ACL names `/usr/bin/security`, not our code (read). It survives, but the `secrets.enc` it wraps is in the old container, so it wraps nothing. |
| LaunchAgents, File Provider domain | paths and bundle ids | Unaffected. |

Moving from *Apple Development* to *Developer ID Application* under the same team changes none of
these: the container is keyed by team, and the gate's requirement matches any Apple-anchored leaf
with our OU (read: A14). Not verified that `anchor apple generic` admits the Developer ID chain in
the running gate: M1 checks it with one attested connection.

For users there is nothing to migrate: no external desktop installs exist. The move is the
maintainer's own Mac, and it has happened.

**What the maintainer must do (M0).** Only the Account Holder can create Developer ID certificates
(Apple, [Create Developer ID certificates](https://developer.apple.com/help/account/create-certificates/create-developer-id-certificates/)).
Create one *Developer ID Application* and one *Developer ID Installer* certificate for
`752LWAZMKZ`, export them with their private keys to the release Mac's login keychain, and keep an
offline backup. ⚠ Apple limits how many Developer ID certificates a team may hold (not verified:
the number) and a lost private key cannot be recovered; a revoked Developer ID Application
certificate makes Gatekeeper refuse every app signed with it after the revocation date (not
verified: the exact rule for already-notarized builds). Treat the private key as a trust anchor in
the sense of [`ops-secret-custody.md`](ops-secret-custody.md).

### 2.3 Hardened runtime and entitlements per binary

Notarization requires hardened runtime on every executable, a Developer ID signature, a secure
timestamp and no `com.apple.security.get-task-allow` (Apple,
[Notarizing macOS software before distribution](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)).
So the repo's standing rule "do not add `--options runtime`" (signing script header) ends for
release builds. That rule was right for its reason: hardened runtime turns on library validation,
which refuses a dylib not signed by our team, and the runtime is left ad-hoc. Once the release
signer re-signs the runtime and every dylib with our identity, that reason is gone.

| Binary | Hardened runtime | Entitlements (proposed, M1 measures) |
|---|---|---|
| `TenshuControl.app` launcher (the JVM) | yes | `application-groups` `752LWAZMKZ.app.tenshu.shared`; `cs.allow-jit` (HotSpot on Apple silicon maps its code cache `MAP_JIT`; not verified that this alone suffices); `device.camera` only if the camera stays in-process (not verified: the QR helper is a separate process today, and whether TCC asks for the entitlement on the helper or the app is open). No sandbox. |
| `TenshuDrive.app` launcher | yes | `application-groups`; `cs.allow-jit`. No sandbox. |
| `Contents/runtime/**` (`libjli.dylib`, `libjvm.dylib`, `jspawnhelper`, …) | yes | none; re-signed by us, so library validation passes. |
| `Contents/app/libskiko-*.dylib` | yes | none; re-signed. |
| dylibs inside jars (onnxruntime, sqlite-bundled, skiko) | — | ⚠ see §2.4. |
| `tray-agent`, `qr-scan-helper`, `image-decode-helper`, `map-snapshot-helper`, `applock-helper`, `appgroup-claim`, `tenshu-domain` | yes | ✅ Built 2026-11-06 (`-Ptenshu.developerIdRelease`): each signed with its own plist, `application-groups` plus `device.camera` for `qr-scan-helper` only; never the host's `allow-jit`. Development builds still give them the host's plist. |
| `keychain-helper.app` | yes | `keychain-access-groups`. ⚠ A restricted entitlement: under Developer ID it needs an embedded **Developer ID provisioning profile** (Apple, [Developer ID](https://developer.apple.com/developer-id/); not verified for this exact entitlement). The comment in `control-desktop/build.gradle.kts` says Developer-ID inside-out signing "produces … a full-capability bare binary"; that contradicts the AMFI SIGKILL `macos-signing.md` §3b measured for a profile-less binary. M1 must settle it, or Touch ID is silently dormant in every shipped build. |
| `TenshuFileProvider.appex` | yes | `app-sandbox`, `application-groups` (as today). Not verified whether a Developer ID File Provider extension needs a profile; today's Apple Development build runs without one (measured). |

If M1 shows the JVM still needs `disable-library-validation` (for example because a library
unpacks a dylib at run time that we cannot pre-sign, §2.4), it goes on the launcher only and is
written down with the measured failure. ⚠ `disable-library-validation` on the process that holds the
realm keys lets any same-user process that can write a dylib path the JVM loads inject code into
it; that is exactly the attacker A14 exists for (AD3). It is a threat-model line, not a default.

### 2.4 Signing every nested binary

Inside-out, in this order: dylibs and executables in `Contents/runtime`, `Contents/app/*.dylib`,
each helper with its own entitlements, nested bundles (`.appex`, `keychain-helper.app` keeps its
own signature), then the app. Never `--deep` (the signing script already refuses it for good
reason). Always `--timestamp` and `--options runtime`.

⚠⚠ **Dylibs inside jars are the trap.** The notary service opens archives, jars included, and
rejects an unsigned Mach-O it finds there (Apple,
[Resolving common notarization issues](https://developer.apple.com/documentation/security/resolving-common-notarization-issues),
"Ensure … binaries are signed", which covers nested archives; not verified that the service treats
`.jar` this way today, M1). And under hardened runtime, a dylib a library unpacks to a temp
directory at run time and `dlopen`s must also carry our team's signature, or library validation
refuses it.

⚠⚠ **Measured 2026-11-06, the first real submission:** Control came back `Invalid` (submission
`df5b282b-…`), with nine issues on three entries. Each one was not Developer ID-signed, had no
secure timestamp and no hardened runtime, and none of them was a dylib. They were the
**extension-less helper executables** `qr-scan-helper` (`:ui-qr-scanner`), `image-decode-helper`
and `map-snapshot-helper` (`:macos-helpers`). They ride in jars as classpath resources for dev runs
(`HelperBinary`), and the staging audit only knew native code by its file extension. So the service
does open `.jar`s. Fixes: every packaged app gets signed copies in `Contents/Helpers`
(`embedJarHelpers`; eight apps had only the jar copy, and no app had `map-snapshot-helper`), and a
release build drops the jar copies and refuses any other Mach-O, found by magic bytes. The same
three unsigned copies are in the 2026-11-05 Mac App Store uploads of Control and Drive. The upload
validator accepted them, and neither app runs them (§4.3.2).
 Three options, cheapest first, decided in M1 by measurement:

1. **Strip what the Mac never loads.** onnxruntime's jar carries x64, Linux and Windows libraries
   and `.dSYM` directories; a release build repacks it with `osx-aarch64` only. This shrinks the
   download too.
2. **Sign in place.** Unpack each remaining Mach-O from its jar, sign it with our identity and
   hardened runtime, put it back. The library still extracts it at run time, but the extracted copy
   carries our signature. Not verified that each library preserves the bytes when extracting.
3. **Pre-extract.** Ship the dylib signed in `Contents/app` and point the library at it, as Skiko
   already does (`-Dskiko.library.path`). sqlite-bundled and onnxruntime each have a system
   property for this (not verified: the names).

### 2.5 Notarization and stapling

With the App Store Connect API key the iOS pipeline already uses (`ASC_KEY_ID`, `ASC_ISSUER_ID`,
`ASC_KEY_PATH`, `how-to/ios-testflight.md` §2.1):

```bash
ditto -c -k --keepParent TenshuControl.app TenshuControl.zip
xcrun notarytool submit TenshuControl.zip --key "$ASC_KEY_PATH" --key-id "$ASC_KEY_ID" \
  --issuer "$ASC_ISSUER_ID" --wait --output-format json
xcrun stapler staple TenshuControl.app           # then build the .pkg from the stapled apps
xcrun notarytool submit Tenshu-<v>.pkg … --wait
xcrun stapler staple Tenshu-<v>.pkg
```

- Apps are stapled before they go into the `.pkg`, so an installed app passes Gatekeeper offline;
  the `.pkg` is stapled so the installer does too. Not verified that the second submission is
  needed when every app inside is already notarized; it is cheap, so the script does it.
- ⚠ On a rejection, `notarytool log <id>` is the only useful output; the script saves it next to
  the artifact, as `ios-archive.sh` saves `upload.log`.
- The key is the Admin-role team key created for TestFlight. Not verified that `notarytool` accepts
  it without a separate Developer role; M1's first submission answers it.

### 2.6 `.pkg`, not `.dmg`, and the LaunchAgent

| | Suite `.pkg` (recommended) | `.dmg` per app |
|---|---|---|
| Apps per download | all in the release | one |
| Lockstep on first install | yes, by construction | only if the user downloads all of them |
| Signing | Developer ID Installer (extra certificate) | Developer ID Application only |
| Admin password | yes: Installer asks for it to write `/Applications` (not verified for every macOS version) | no, for an admin user dragging the app |
| User trust | lower; a `.pkg` can run scripts as root | higher; drag-and-drop is familiar |
| Build | `pkgbuild --component` per app, `productbuild --distribution` for the suite | `packageDmg` exists today |

The deciding row is the second. Drive without Control is useless (Control is the proxy), and Drive
at a different commit from Control is schema skew (§2.9). A suite `.pkg` makes the right state the
only state an install can produce.

**The LaunchAgent stays Control's job, not the installer's.** A `postinstall` script runs as root;
writing into a user's `~/Library/LaunchAgents` from it is the classic installer mistake (wrong
owner, wrong user on a shared Mac). Control already installs both agents when the user turns on
"Start at login". The `.pkg` carries no scripts at all, which also keeps the "this package will run
a program" warning off the install. On macOS 13 and later the system shows a "Background item
added" notice naming the signer when an agent appears (not verified: the exact wording and which
name it shows).

Optional, Route-A-compatible: register the two agents with `SMAppService.agent(plistName:)` from a
plist inside the bundle instead of writing `~/Library/LaunchAgents` (Apple,
[SMAppService](https://developer.apple.com/documentation/servicemanagement/smappservice), macOS
13+). The plist then moves with the app, and it is the path Route B would need anyway (§4.2). Not
needed for M2.

### 2.7 `scripts/macos-release.sh`, hosting and the download page

**As built (2026-11-05):** [`scripts/macos-release.sh`](../../scripts/macos-release.sh), runbook
[`how-to/macos-release.md`](../how-to/macos-release.md). It makes one notarized, stapled `.dmg`
per app rather than the suite `.pkg` below. That revises D5; the case for it is §2.12.5 in the
channel-mixing note. Checks 1–6 are built: the bundle id (C4), the version, every Mach-O's team,
hardened runtime, secure timestamp, signer and arm64-only slice, the entitlements, `--verify
--strict`, then `spctl` and `stapler validate` after notarization. Check 7 (the service vector)
is not built. The build mode is `-Ptenshu.developerIdRelease=true`: hardened runtime, `allow-jit`,
resource entitlements from the bundle's usage strings, and the MAS native staging reused, so no
`disable-library-validation`. Upload and the manifest are built (`scripts/macos-direct-publish.sh`, 2026-11-06); the page below is not. All
ten apps notarize (2026-11-06, `how-to/macos-release.md` §6). `--pipeline-test` runs everything up to
the notary with the team's Apple Development identity.

Shape mirrors `scripts/ios-archive.sh`: refuses a dirty tree for `--publish`, takes the apps from
`scripts/lib/app-catalogue.sh` so it cannot quietly cover a subset, and fails before upload on any
of these checks:

1. Each bundle id, `CFBundleShortVersionString` and `CFBundleVersion` is the run's (D9), and the
   launcher cfg carries the version and `-Dtenshu.ipc.teamId=752LWAZMKZ`.
2. **Every Mach-O** in the bundle (found with `file`, jar contents included) carries
   `TeamIdentifier=752LWAZMKZ`, the `runtime` flag and a secure timestamp. One ad-hoc dylib fails
   the run.
3. `lipo -archs` on every Mach-O says `arm64` only (D2): a stray x64 slice is dead weight.
4. Entitlements per binary equal the table in §2.3, read back with `codesign -d --entitlements`; no
   `get-task-allow` anywhere.
5. The nested-helper count equals the catalogue's (the existing guard against a tray-less Control).
6. `codesign --verify --deep --strict` on each app, `spctl -a -vv -t exec` on each app after
   stapling, `spctl -a -vv -t install` on the `.pkg`, `stapler validate` on all of them.
7. The suite's declared service vector, written into each app's `Info.plist` (a key such as
   `TenshuServiceVersions`, as `ios-per-app-frameworks.md` §6.3 proposes for iOS), is identical
   across the apps.

**Hosting (D7, revised 2026-11-06).** ⚠ **As built: the Android release bucket, content-addressed.**
The `.dmg` go into `tenshu-releases` at `releases.tenshu.app/sha256/<hex>.dmg`
([`deploy/cloudflare-releases/`](../../deploy/cloudflare-releases/README.md)), published by
[`scripts/macos-direct-publish.sh`](../../scripts/macos-direct-publish.sh), which writes the committed
`store/macos-release.json` (version, build, minimum macOS, and per app the URL, sha256, size and
bundle id) and renders the Homebrew casks from it (runbook: `how-to/macos-release.md` §7).
The design below (`tenshu-downloads` at `downloads.tenshu.app`, versioned paths) was not built. Why:

- **One trust root, not two.** Both buckets would be install trust roots (threat model A44, T-MD-01),
  published by the same maintainer from the same Mac. R2 scopes a key to a bucket, not a prefix, so a
  second bucket adds a key to custody without separating who can write what.
- **Content addressing makes immutability true by construction.** A versioned key is immutable only
  as long as the script refuses to overwrite it, and a re-notarized build of an unpublished version
  (which happened on 2026-11-06) would have to take another name anyway. Under `sha256/` it simply is
  another object, and the manifest says which one is the release. The publish step still refuses a
  published version with other bytes.
- **The version argument does not need the path.** The cask carries its URL and sha256 per version,
  the download is saved under `Tenshu<App>-<version>.dmg` (`Content-Disposition`), and support and
  release notes quote the manifest's version. A signed manifest (`manifest.json.sig` below) is not
  built; as for Android, the manifest is committed and reviewed, and a cask install pins the bytes.
- **It is ready.** The module exists and plans clean (3 to add), and the publish code mirrors
  `android-direct-publish.sh` line for line, so the two paths cannot drift in what they check.

The original design, kept for the record:

> A new public R2 bucket `tenshu-downloads` behind `downloads.tenshu.app`, a sibling of
> `deploy/cloudflare-assets`, with `/macos/<version>/Tenshu-<version>.pkg` (immutable by refusing
> overwrites), a `.sha256` beside it, and a mutable `/macos/manifest.json` with an Ed25519 signature.
> Users, Homebrew and support quote a version, not a hash.

⚠ The download host is not the relay (threat model T-R-07: binary distribution must not ride the
relay).

**The page.** `tenshu.app/apps/control/download/macos/<lang>` and the same under `drive`, as
`store-presence-and-support-pages.md` §4.1 anticipates. Both offer the same suite `.pkg`, its
version, size, sha256, the minimum macOS (D10), "Apple silicon only" (D2), and a link to the
support page. Generated statically from `manifest.json` like the support pages (that doc's D3). The
`SameChannelAsThisApp` copy in ADR 0025 can then link this page instead of saying nothing.

### 2.8 Updates

**What is needed.** A user with Tenshu 1.3 learns that 1.4 exists, gets every installed app to 1.4
together, and is never left with half the suite on each version.

**Sparkle 2** ([sparkle-project.org](https://sparkle-project.org/documentation/)) is the standard
answer for a direct-download Mac app: an appcast feed, EdDSA (Ed25519) signatures over each update,
a check that the new bundle's code signature matches the old one's, an installer that copes with
permissions and relaunch, delta updates. Its unit is one bundle. Our unit is the suite.

| | Sparkle 2 | Own updater in Control |
|---|---|---|
| Lockstep across N apps | ⚠ No. N feeds or one feed per app, each app updating on its own schedule; or one Sparkle instance installing the suite `.pkg`, which needs the admin prompt on every update (not verified) | yes: one manifest, one transaction |
| Integration | an Objective-C framework in a JVM app: host it in the Swift tray agent with `SPUUpdater(hostBundle:applicationBundle:…)` pointed at `TenshuControl.app` (not verified that this is supported for a bundle other than the host's own) | Kotlin in Control plus the Swift tray to swap bundles while the JVM is down |
| Signature and integrity | EdDSA per item + Apple signature match | the same two checks, written by us: Ed25519 over the manifest (key compiled in), sha256 per artifact, and `codesign --verify -R '<A14 requirement>'` on every new bundle before it replaces the old |
| Hard parts done for you | atomic replace, quarantine, relaunch, permissions, deltas | ours to get right; deltas skipped |

**Recommendation (D8):** own code, in two steps. M2 ships *notify only*: Control fetches
`manifest.json`, verifies the signature, and when the version is newer shows a banner with a link to
the download page; the user installs the suite `.pkg` over the old one. M3 adds the in-place
updater: Control downloads and verifies, asks every connected Tenshu app to quit over IPC, the tray
agent (which survives Control quitting) swaps the bundles and relaunches Control. If M3 turns out
harder than it looks, the fallback is Sparkle in the tray installing the suite `.pkg`, and the cost
is an admin prompt per update. ⚠ An updater is a new way for code to arrive on the device: it amends
the threat model (TA4 names "signed installers"; the manifest signing key becomes a trust anchor
next to A11) in the PR that builds it.

### 2.9 Version lockstep and schema skew

House Rule 9: a reshape bumps a service version, and clients move across a bump in lockstep. On
desktop, Control is the only proxy and the other apps keep no store of their own (ADR 0025,
"no local store at all"; Drive and the others hold only settings in the group container, measured).
So skew on desktop is not the iOS "one slot behind wipes the store" hazard
(`ios-per-app-frameworks.md` §6.3); it is the fence of
[`core-schema-skew-surface.md`](core-schema-skew-surface.md): Drive newer than Control is told
"Update Control to use Drive", Drive older is told to update itself. Still a broken app from the
user's point of view, so:

- One version for the suite (D9), one `.pkg`, one manifest, one updater transaction. An app is never
  released alone.
- The release script refuses a suite whose apps disagree on the declared service vector (§2.7
  check 7).
- The skew banner's action can at last point somewhere: the download page (`core-schema-skew-surface.md`
  §4 says desktop "has no target"; this doc gives it one).
- `RuntimeIdentity.appVersion` on desktop is `UNKNOWN_VERSION` because "nothing injects a constant
  into the packaged bundle" (read: `PlatformProxyModule.kt`). The launcher already passes
  `-Djpackage.app-version` (measured, cfg); D9 makes that value real and the proxy can read it.

### 2.10 Uninstall

Dragging the apps to the Bin leaves (read: `reset-local-device-state.sh`, `LaunchAgentManager`):

- `~/Library/LaunchAgents/app.tenshu.control.plist` and `app.tenshu.control.tray.plist` (launchd
  then fails to start a missing executable at every login, silently);
- `~/Library/Group Containers/752LWAZMKZ.app.tenshu.shared` (the store, `secrets.enc`, sockets);
- the login-Keychain item `app.tenshu.control.desktop.master-key-store` and, if enrolled, the Touch
  ID item;
- the File Provider domain and `~/Library/CloudStorage/Tenshu…` (not verified what Finder shows
  once the extension is gone).

D13: Control gets **Remove Tenshu from this Mac** (unload and delete both agents, remove the File
Provider domain while the extension still exists, delete the Keychain items, then the container
and the apps). ⚠ It must say first that unsynced changes on this Mac are lost and that the device
leaves the realm's roster only if it is removed from another device. The support page lists the
same steps for someone who already dragged the apps away. `reset-local-device-state.sh` is the
developer twin and already knows most of this list.

### 2.11 Copies from more than one channel

Control can arrive four ways: a development build, the Developer ID suite `.pkg` (§2.6), a Homebrew
cask (M4) and the Mac App Store (§4). This section says what it means when a Mac has more than one.

**Same data, by design.** Every channel signs with team `752LWAZMKZ` and the App Group entitlement,
so every copy resolves `~/Library/Group Containers/752LWAZMKZ.app.tenshu.shared` (read:
`SocketPathResolver.appGroupContainer`). Since D12 they also share the bundle ID
`app.tenshu.control.ios`. Switching from Homebrew to the store keeps the store, the device identity
and the settings. The exception is the wrapping key; see *The keychain* below.

**One copy runs at a time.** Measured 2026-11-04: a development Control
(`/Applications/TenshuControl.app`, old bundle ID `app.tenshu.control`) and a TestFlight Control
(`/Applications/TenshuControl 2.app`) ran at once. `lsof` showed both with `objects.db` (+ `-wal`,
`-shm`, `objects.db.lock`) and `ipc.sock` open. `objects.db.lock` is a diagnostic: the TestFlight
copy wrote `store_lock_contended` beside the store and opened it anyway, and
`UnixSocketServer.start` deletes a live socket file before it binds. That same log line shows the OS
lock works across the sandbox boundary: the sandboxed copy saw the unsandboxed copy's lock.

The fix is `ControlInstanceGuard`, the first thing `main()` runs:

1. It takes `control.instance.lock` at the data root with `FileChannel.tryLock` (exclusive,
   non-blocking) and holds it for the life of the process. The OS drops it on any exit, so a killed
   Control never blocks the next start. The file holds the holder's pid and path, for people.
2. It also connects to `ipc.sock`. A Control from before the guard holds no lock but answers there;
   a stale socket file refuses the connect and does not count.
3. A refused copy asks the running one to show its window (`openControlWindow` over `bridge.sock`,
   the tray's *Open Control* call) and exits 0. The bridge admits Control's own identifier for this
   (`FirstPartyDesktopPeers.CONTROL_IDENTIFIERS`); `ipc.sock` does not. A copy started hidden by a
   login item exits without asking, so two login items (a LaunchAgent and an `SMAppService` item
   from different channels) never pop a window up. ⚠ A Control that is quitting releases the lock
   last, after its sockets are gone, so a launch during that window (seconds, while the proxy
   drains) is refused with nothing to hand off to and shows nothing. Launching again works.
4. If the lock file cannot be opened at all, Control starts unguarded and says so on stdout. It
   still defers to a Control answering on `ipc.sock`.

Which copy runs is whichever started first. After a channel switch, the old copy keeps winning
until it is removed or stops starting at login.

**Both channels install to `/Applications/TenshuControl.app`.** So which one wins depends on the
order they were installed in.

| Installed second | What happens to the copy already there |
|---|---|
| Homebrew cask | Not verified: `brew install --cask` refuses when an app already exists at the target unless `--force`, which replaces it. |
| Suite `.pkg` | Not verified: a component package is relocatable by default, so Installer may update a copy with the same bundle ID *where it finds it* rather than write `/Applications`. Set `BundleIsRelocatable = false` in the component plist (§2.7) so the path is always the one written. |
| Mac App Store | Measured once, with **different** bundle IDs: TestFlight installed beside the development copy as `TenshuControl 2.app`. Not verified with the same bundle ID: the store may replace, refuse, or install beside it. Check this in M5. |

**Updates follow the receipt.** The store updates only a copy it installed, which carries
`Contents/_MASReceipt` (Apple; not verified that it ignores a same-ID copy without one). A
Developer ID or Homebrew copy at the same path is never updated by the store. In the other
direction, the M3 updater (§2.8) and the cask must never touch a bundle that has `_MASReceipt`: the
store owns it, and replacing it would also invalidate the receipt. ADR 0025 already offers a store
link only to a copy that came from that store; the updater needs the same test.

**The keychain (not verified, owed before M5).** A Developer ID or development Control keeps the
wrapping key for `secrets.enc` in the login keychain via `/usr/bin/security` (§4.2 table;
`KeychainWrappingKeyStore`). Whether a sandboxed Control can read that item is not verified. If it
cannot, `loadOrCreate` tries to make a new key, and a switch to the store build loses the device's
secrets. Measured 2026-11-04 on the double-run Mac: the keychain item's `mdat` was still
2026-10-29, but `secrets.enc` was rewritten at 23:21:25, the second the TestFlight copy started. So
the sandboxed copy rewrote the file without replacing the keychain item. Whether it read the old key
or encrypted under something else is not known. The test: pair a Developer ID Control, quit it,
start the store build over the same container, and check that it unlocks without re-pairing and
that the Developer ID copy can still read `secrets.enc` afterwards.

### 2.12 Mixing channels per app: Mac App Store, Homebrew and the tenshu.app download (2026-11-05)

§2.11 is about two copies of **Control**. This section is about one copy of each app, with the apps
from different channels: Control from the Mac App Store, Photos from Homebrew or from a tenshu.app
download. Only Control and Drive are in the store today (`scripts/macos-mas-archive.sh` builds
those two). The other eight ship only as Developer ID builds, so anyone with the store Control who
wants Photos already has a mixed Mac.

**Answer: mixing works, one copy per app, with the traps below.** No code change is needed for the
1.0.1 apps to talk to each other across channels. A channel *switch* for Control has two traps
that need code (C1, C2). Evidence is *read* at `066f6f363` unless marked otherwise.

#### 2.12.1 Why it works

| Layer | What it pins | Across channels |
|---|---|---|
| IPC peer gate (`MacPeerAttestation.decide`, threat model A14) | Route 1: `anchor apple generic and certificate leaf[subject.OU] = "<TEAM>" and identifier "<id>"`. Route 2 (commit `36943d4f9`): the Mac App Store / TestFlight leaf marker + `identifier "<id>"`, **and** the signed `kSecCodeInfoTeamIdentifier` is in the compiled team set. It pins **team + identifier**, never the designated requirement, the certificate type or the bundle path. | A Developer ID leaf has `OU=752LWAZMKZ` and chains to Apple, so route 1 admits a Homebrew or download peer. (Apple: the standard Developer ID designated requirement is this clause plus the Developer ID marker OIDs. The doc's earlier "not verified" for Developer ID, §2.2, still wants one attested connection in M1.) A store peer goes through route 2. The gate does not care which channel *Control* came from: both routes are compiled into every Control built from `36943d4f9` on. ⚠⚠ The 2026-11-04 uploads (build `202611041720`, from `37615c8`, §4.3.1) were **not**: that store Control refuses a store Drive, its own tray agent and the File Provider. Check any later upload with `git merge-base --is-ancestor 36943d4f9 <build commit>` before it goes to review. |
| Identifier allowlist (`FirstPartyDesktopPeers`, `BundledAppCatalogue`) | One `MacCodeSignature` identifier per app, plus `tray-agent` and `app.tenshu.drive.ios.fileprovider` | The same in every channel, because every channel builds from the same `bundleID` in the module's `build.gradle.kts`. ⚠ See trap T1 for the day another app joins the store. |
| Data root | `~/Library/Group Containers/752LWAZMKZ.app.tenshu.shared`, from `SocketPathResolver.appGroupContainer` | Keyed by **team**, not channel. Every packaged app runs `Contents/Helpers/appgroup-claim` before it touches the path (the "SQLite 14 = App Group refusal" fix, `tech-debt.md`), so a Developer ID app claims it as a store app does. Inside the sandbox `containerURL` returns the same canonical path (the code refuses to fall back there). |
| Sandbox boundary | MAS builds sandboxed (`-Ptenshu.masSandbox=true`), Developer ID builds not | Unix sockets in the group container work in both directions: the group entitlement covers the sandboxed side, and the unsandboxed side needs only the claim. *Measured* 2026-11-04 (§2.11): a sandboxed TestFlight Control and an unsandboxed development Control both held `ipc.sock` and saw each other's lock file. ⚠ *Not measured*: a store Control serving a store or Homebrew client end to end (the §10 caveat in `desktop-ipc-client-identity.md`). |
| File bytes | Uploads | The client reads the file and sends the bytes (`DesktopFilesRepositoryPlatformDelegate.uploadPlatformFile` → `readFileBytes`). No path crosses to Control, so a sandboxed Control serving an unsandboxed Drive needs nothing. |
| Synced folders | Grants | Drive always asks Control to pick (`ControlOwnedBackupFolderGrants` → `pickSecurityScopedFolder`). Control creates and keeps the bookmark, so the client's channel does not matter. |
| Launching an app | `open -b <bundleId>` (`ControlDataBridge`, `PlatformProxyModule`) | Bundle IDs are the same in every channel, so LaunchServices finds whichever copy is installed. ⚠ Whether a sandboxed Control may spawn `/usr/bin/open` is §4.2's open row. It does not depend on the client's channel. |
| Keychain | Only Control holds a key (the `secrets.enc` wrapping key) | A client has no Keychain item, so a client's channel never matters. Control's channel switch is §2.11 and C3. |
| File Provider | One domain, owned by the Drive bundle (`app.tenshu.drive.ios`) | Drive's extension talks to Control's `bridge.sock` through the same gate (route 1 or 2). Drive comes from one channel at a time, so there is one extension. |

#### 2.12.2 The compatibility matrix

Rows: where Control came from. Columns: where an app came from. ✅ works by the table above. ⚠ works
with the trap named.

| Control ↓ / app → | Mac App Store (Drive) | Homebrew / tenshu.app (any of the ten) | Development build |
|---|---|---|---|
| **Mac App Store** | ✅ route 2 | ✅ route 1. ⚠ T2: the store Control may lag the Homebrew app's version | ✅ if signed by the team; ad-hoc refused (A14) |
| **Homebrew / tenshu.app** | ✅ route 2. ⚠ needs a Control built from `36943d4f9` on | ✅ route 1 | ✅ |
| **Two copies of one app, any channels** | ❌ not supported: same bundle ID, same `/Applications/<name>.app`, one File Provider domain. Control refuses the second copy (`ControlInstanceGuard`, §2.11). Two Drives would fight over one domain and one settings subdir, with no guard. | | |

#### 2.12.3 Traps

- **T1. A ninth or tenth store app will break the identifier, unless its bundle ID is the same in
  every channel.** D12 gave Control and Drive their iOS bundle IDs (`…control.ios`, `…drive.ios`)
  in *every* channel. The other eight are `app.tenshu.<app>` on desktop (`photos-desktop` etc.),
  but their iOS records are `app.tenshu.<app>.ios`. If Photos joins the store under the iOS ID
  (for Universal Purchase, D12's reason), a store Photos and a Homebrew Photos are two different
  apps to macOS: they install side by side and sign with different identifiers, and the catalogue
  admits only one of them. Both would still open the same data subdir, because `DesktopAppPaths`
  keys it by an internal name (`app.tenshu.photos.desktop`), not the bundle ID, and nothing stops
  two copies of a client app from writing it at once. ⚠⚠ **Choose each app's macOS bundle ID
  once, before its first public Developer ID release**: after that, a rename turns every install
  into a different app for LaunchServices, Homebrew (`uninstall quit:`) and the gate's
  identifier. Settings survive it, for the reason above. The recommendation (C4) is to move all
  eight to their `.ios` IDs now, while no public desktop install exists.
- **T2. The store adds review latency to Control alone, and the skew fence turns that into a broken
  app.** Homebrew and the download publish when we upload; Apple publishes a store build when review
  passes (hours to days). A service-version bump (House Rule 9) ships a Photos that is newer than a
  store Control still in review. That Photos is fenced with *"Update Control to use Photos"*
  (`core-schema-skew-surface.md`), and the user cannot act on it. Only a release with a bump does
  this; most releases change no service version. Release rule (R1): **a release that bumps a
  service version publishes the Developer ID artifacts only after the store Control and Drive of
  that version are live.** The release script checks this (H6). The other direction, store app
  newer than a Homebrew Control, cannot happen for Drive under R1, and `brew upgrade` fixes it.
- **T3. A cask must never reinstall Control or Drive for someone who has the store copy.** The
  natural `depends_on cask: "tenshu-control"` in the Photos cask would ask Homebrew to install a
  second Control over the store one. Homebrew refuses when an app already exists at the target,
  unless `--force` (*Homebrew*, not re-verified; the Cask Cookbook does not say), and with `--force`
  it replaces the store copy and its `_MASReceipt`. So **no cask depends on the Control cask**.
  Each app's cask states the need in `caveats`, and the app's own "Control not running" or "Install
  Control" screen (ADR 0025) points to every channel.
- **T4. Updates follow the installer** (§2.11, *Updates follow the receipt*). The store never
  updates a Homebrew copy; a cask marked `auto_updates true` is skipped by `brew upgrade` unless
  `--greedy`. Until the M3 updater exists, the casks do **not** declare `auto_updates`, so `brew
  upgrade` is the update path. The M3 updater and every cask must leave a bundle with
  `Contents/_MASReceipt` alone.

#### 2.12.4 Switching one app's channel

For a client app (not Control) a switch is: uninstall the old copy, install the new one. Its
settings live in the group container under its internal package name (`DesktopAppPaths.dataDir`,
e.g. `app.tenshu.photos.desktop`), which is the same in every channel, so they survive. Nothing else is bound to the app. For Drive, removing the app removes the File
Provider extension. The domain then disappears from Finder until the new Drive registers it again
(`tenshu-domain` at start); the files are in Control's store, not in the domain.

For **Control** a switch keeps the store, the device identity and the settings (§2.11). Three things
need work first:

- **C1. A Developer ID → store switch leaves the old LaunchAgents running the store copy.**
  `LaunchAgentManager` writes `~/Library/LaunchAgents/app.tenshu.control{,.tray}.plist` with the
  **absolute** executable path, `/Applications/TenshuControl.app/Contents/MacOS/TenshuControl`
  (tray: `…/Contents/Helpers/tray-agent`, `KeepAlive=true`). The store copy lands at the same path,
  so launchd keeps starting it at login, outside the `SMAppService` item the store build uses
  (*not verified* that a sandboxed binary started by launchd from a user agent gets its sandbox the
  same way; Apple applies it from the entitlements at exec, so it should). The store Control cannot
  undo this. `DesktopStartAtLoginManager` in the sandbox only looks at `MacLoginItemManager`, so
  Settings reports "Start at login: off" while launchd starts it, and the switch cannot turn it off.
  The sandboxed `user.home` is the app container, so `LaunchAgentManager` would look in the wrong
  `Library/LaunchAgents` even if it were asked. **Fix**: removing the agents is the old copy's job,
  and both the cask (`uninstall launchctl:` + `delete:` of the two plists) and D13's *Remove Tenshu
  from this Mac* must do it. Also show the user, from the store Control, when
  `~/Library/LaunchAgents/app.tenshu.control.plist` exists. The real home is
  `getpwuid(getuid())->pw_dir` via FFM, not `user.home`; whether the sandbox lets Control *read*
  that path is *not verified*. If it cannot, say it in the store build's first-run screen instead.
- **C2. Folder grants do not survive the switch.** `SecurityScopedGrantStore.defaultDir()` is
  `user.home/Library/Application Support/TenshuControl/folder-grants`. Unsandboxed, that is the
  real `~/Library`; sandboxed, it is `~/Library/Containers/app.tenshu.control.ios/Data/…`. The
  store Control finds no bookmark for a grant id Drive still holds, and the source reports `ERROR`.
  That is loud and correct, but the user must re-pick every synced folder. A bookmark is valid only
  for the app that made it (*Apple*, and the class doc says the same), so copying the files would
  not help. Accept it, and have the `ERROR` row tell the user to pick the folder again rather than
  showing a bare error. *Not verified*: whether a store Control resolves a bookmark made by the
  Developer ID copy (same bundle ID, same team, different signature). Measure it in M5; if it
  does, move the store into the group container and the loss goes away.
- **C3. The wrapping key read across the sandbox, re-read.** §2.11 leaves open whether a sandboxed
  Control can read the item a Developer ID Control wrote through `/usr/bin/security`. The
  2026-11-04 measurement answers more than §2.11 says: `DesktopMasterKeyStore.persist()` returns
  early when it has no wrapping key, so a rewrite of `secrets.enc` proves the sandboxed copy *had*
  one. `KeychainWrappingKeyStore.loadOrCreate` gets a key either by `find-generic-password`, or, if
  that fails, by `add-generic-password -U`, which would have changed the item's `mdat`. The `mdat`
  stayed 2026-10-29. So the sandboxed copy most likely **read** the existing key. That is still an
  inference; §2.11's round trip is the test. ⚠ The failure mode to guard is the `-U`: if a sandboxed
  `find` fails but `add -U` succeeds, it **replaces** the Developer ID copy's key and that copy's
  `secrets.enc` stops decrypting (it re-pairs). A `find` that fails while an item exists should
  not create one. A fix is to make `store()` add without `-U` and treat "already exists" as a read
  error.

#### 2.12.5 Recommended channel plan

1. **Every app from Homebrew and the tenshu.app download; Control and Drive also from the store.**
   Same notarized Developer ID artifact for both off-store channels (Homebrew downloads the
   tenshu.app file and checks its sha256), so there are two builds per app, not three.
2. ⚠ **Per-app artifacts, revising D5.** D5's suite `.pkg` assumed one channel. With Control in the
   store, the suite would install a second Control over the store one (T3), and a Homebrew user
   wants one cask per app. Ship each app as its own notarized, stapled `.dmg` (drag install, no admin
   prompt, no Installer certificate; Homebrew also takes a `.zip`). Lockstep is then not enforced
   by the installer. It is held by one version for every app (D9), R1, and the skew fence, as on
   Android, where every app is its own APK. The suite `.pkg` can come back later as a convenience,
   if it never carries Control or Drive over a store copy.
3. **Store Control is the recommended Control** once it passes review: updates without us, and it
   is the copy we can tell users to install. Homebrew Control stays for people who want it, and for
   machines without an Apple ID.
4. **Hosting: reuse the Android release bucket**, `tenshu-releases` at `releases.tenshu.app`
   (`deploy/cloudflare-releases/`), instead of D7's new `downloads.tenshu.app`. It is already an
   install trust root (threat model A44) with its own publish key and immutable,
   content-addressed keys (`sha256/<hex>.dmg`). The version lives in a committed
   `store/macos-release.json` beside `store/android-release.json`, and the download page
   `/apps/<app>/download/macos` is generated from it as the Android page is
   (`web/apex/support-pages/androidDownload.ts`). D7's argument for versioned paths was the cask
   URL, but the cask carries its URL and sha256 per version anyway (H4). **Built 2026-11-06** (§2.7); the
   bucket's README and threat model A44 cover the `.dmg` (§8.24).

#### 2.12.6 Homebrew: own tap, then perhaps `homebrew/cask`

**Own tap now, `homebrew/cask` when notability allows.** (Updated 2026-11-06.)

1. ✅ **A notarized Developer ID artifact.** Done: all ten apps and their `.dmg` notarize and staple
   (`scripts/macos-release.sh`, `how-to/macos-release.md` §6), and a quarantined copy opens. This
   was the blocker for both routes; it no longer is.
2. **Notability, for `homebrew/cask` only — never for our own tap.** Homebrew,
   [Package Acceptance Policy](https://docs.brew.sh/Package-Acceptance-Policy) (read 2026-11-05):
   a GitHub project needs **30 forks, 30 watchers or 75 stars**, and **90 / 90 / 225** when the
   repository owner submits it. A self-submission without a public repository is "not
   automatically rejected" but must argue notability in the PR. On 2026-11-05 `B3nedikt/Tenshu` is
   **private**, 0 stars, 0 forks, so a submission would be refused. It stops being a blocker
   once the repository is public and over the threshold, or a user submits the cask at the lower
   bar.

What is **not** a blocker: R1. Homebrew's version bumps follow the cask's `livecheck`, so if the
generated `macos.json` names a version only once the store copies of that version are live (H6
writes it last), `homebrew/cask` holds R1 as well as our tap does. Apple silicon only
(`depends_on arch: :arm64`), macOS 14+ and a non-`skip` `livecheck`, which a new cask must have,
are all accepted.

So: an own tap, `github.com/b3nedikt/homebrew-tenshu`, used as `brew tap b3nedikt/tenshu` and
`brew install --cask tenshu-<app>` (Control first). It has no admission gate, needs only a public
tap repository (`brew` clones it without credentials), and can go live the day the first `.dmg` is
published. Moving to `homebrew/cask` is one PR of the same cask files once notability allows; the
tap then keeps a `tap_migrations.json` entry pointing to it, so existing installs follow.

**What a cask needs** (Homebrew, [Cask Cookbook](https://docs.brew.sh/Cask-Cookbook)). As built, the
casks are generated from `store/macos-release.json` by `scripts/macos-direct-publish.sh --casks`
(`scripts/lib/macos_publish.py`) and pass `brew style`; Photos':

```ruby
cask "tenshu-photos" do
  version "1.0.1"
  sha256 "<sha256 of the .dmg>"

  url "https://releases.tenshu.app/sha256/<sha256>.dmg"
  name "Tenshu Photos"
  desc "Encrypted photo library"
  homepage "https://tenshu.app/apps/photos"

  depends_on arch: :arm64          # D2
  depends_on macos: :sonoma        # D10, from the manifest's minimumMacOS

  # No depends_on cask: "tenshu-control" (T3). No auto_updates until M3 (T4).

  app "TenshuPhotos.app"

  uninstall quit: "app.tenshu.photos.ios"

  zap trash: "~/Library/Group Containers/752LWAZMKZ.app.tenshu.shared/app.tenshu.photos.desktop"

  caveats <<~EOS
    Tenshu Photos needs Tenshu Control, from the Mac App Store or:
      brew install --cask b3nedikt/tenshu/tenshu-control
  EOS
end
```

Each cask also has a `livecheck` reading `tenshu.app/apps/<app>/download/macos.json` (`strategy
:json`, the `version` field), which the website build writes from the committed manifest (H7).

Control's cask adds `uninstall launchctl: ["app.tenshu.control", "app.tenshu.control.tray"]` and
`delete:` of the two plists (C1). Its `zap` names the whole group container, the login-Keychain
item `app.tenshu.control.desktop.master-key-store` (by a `script` running `/usr/bin/security
delete-generic-password`, since `zap` has no Keychain stanza), and
`~/Library/Application Support/TenshuControl`. ⚠ `zap` on Control removes the device's store: only
with `brew uninstall --zap`, which is what the flag is for. ⚠ Drive's cask should remove the File
Provider domain before the app goes, or Finder keeps a dead location (§2.10). The generated cask has no
step for it yet.

⚠ **Notarization is required, not optional, for a cask.** Acceptable Casks requires that an app
does not need Gatekeeper disabled or bypassed and passes Homebrew's Gatekeeper checks. Homebrew's
plan was to drop `--no-quarantine` and stop supporting casks that fail Gatekeeper from 2026-09-01
([Homebrew/brew#20755](https://github.com/Homebrew/brew/issues/20755); *not re-verified* what
shipped). An own tap has no reviewers, but quarantine still applies on install, so an unnotarized
cask does not open for a normal user (§2.1).

**Steps, in order** (state as of 2026-11-06):

- **H1. ✅ M0's two items, minus one.** The Developer ID Application certificate exists (team
  `752LWAZMKZ`). The Installer certificate is not needed for the cask or the download (§2.12.5 item 2).
- **H2. ✅ M1, per app.** Release signing with hardened runtime, `allow-jit`, every Mach-O signed
  including the helpers that ride in jars, then notarize and staple the `.app` and the `.dmg`.
- **H3. ✅ `scripts/macos-release.sh`**: §2.7's checks (minus the suite ones), the `.dmg`'s `spctl`
  and `stapler` verdicts, the catalogue bundle id (T1/C4), the minimum macOS (D10). Output in
  `build/macos-release/` with `SHA256SUMS` (not `build/macos-direct/` as first sketched).
- **H4. ✅ `scripts/macos-direct-publish.sh`**, the twin of `android-direct-publish.sh` (headers
  `application/x-apple-diskimage`, immutable, `Content-Disposition`), writing
  `store/macos-release.json`; it refuses a step backwards and anything not Notarized Developer ID from
  team `752LWAZMKZ`. First run 2026-11-06: 1.0.1, build `202611061517`, `--verify` clean.
- **H5. ✅ The tap**, `b3nedikt/homebrew-tenshu` (public): `Casks/tenshu-<app>.rb` rendered by
  `--casks <dir>` from the manifest and pushed by hand. `brew tap` + `brew install --cask` verified
  with Photos on 2026-11-06. Not built: the tap's CI (`brew audit --cask --strict --online`, an
  install on a clean account).
- **H6. Not built: R1 in the publish script.** A release whose service vector differs from the last
  published one refuses to publish until the store Control and Drive of the same version are live
  (read from App Store Connect with the existing API key). `--allow-ahead-of-store` overrides it.
  Until then R1 is a rule the maintainer follows by hand.
- **H7. Built 2026-11-06: the download page.** `/apps/<app>/download/macos/<lang>`
  (`web/apex/support-pages/macosDownload.ts`): the `.dmg`, version, size, sha256, macOS 14+, Apple
  silicon, the `brew install` line, how to check the signer, and "keep a Mac App Store Control".
  Generated from `store/macos-release.json` like the Android page, plus `macos.json` per app for the
  casks' `livecheck`; the app pages' Mac button links it. ⚠ No store badge yet: Control and Drive
  are not live in the Mac App Store; add it (§2.12.5 item 3) when they are.

#### 2.12.7 Code changes, collected

| # | Change | Needed for | Size |
|---|---|---|---|
| C1 | Control's cask and *Remove Tenshu* unload and delete both LaunchAgents; the store Control tells the user when it finds them | a Developer ID → store switch | small |
| C2 | The `ERROR` row for a lost grant asks the user to pick the folder again; measure whether the store copy can resolve the Developer ID copy's bookmark | a Control channel switch | small |
| C3 | `KeychainWrappingKeyStore.store` no longer overwrites an existing item (`-U`) | a sandboxed Control that cannot read the item must not destroy the key | small |
| C4 | ✅ Done 2026-11-05: the eight non-store apps moved to their `.ios` bundle IDs on macOS, catalogue and LaunchServices mapping in the same PR | the first public Developer ID release, before anyone installs one (T1) | — |
| — | None for the gate, the data root, uploads or grants | mixing channels | — |

---

## 3. Intel Macs

✅ **Decided 2026-10-15 (D2): no.** Apple silicon only; the download page says so. For the record:
a jpackage image is one architecture, so Intel support would mean a second x64 build on an x64 JDK
(a universal app cannot be made by `lipo` alone, because the runtime and the jar-embedded libraries
are per-architecture trees, not single binaries).

---

## 4. Route B: the Mac App Store

### 4.1 What the store demands

Mac App Store apps must be sandboxed, packaged with Apple's tools, update only through the store,
not install code or auto-launch at login without consent, and not ask for root (Apple,
[App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) §2.4.5; reread at
submission time, the list changes). Signing is *Apple Distribution* for the app and *Mac Installer
Distribution* for the `.pkg` built with `productbuild`, with a Mac App Store provisioning profile
embedded in every bundle that has entitlements; review replaces notarization (not verified: whether
the older "3rd Party Mac Developer" certificate names are still issued; Xcode's automatic signing
creates what it needs, as it did for iOS on 2026-10-13). Digital goods sold inside the app go
through in-app purchase (§3.1.1), which meets the commerce plane head-on (§4.5).

### 4.2 What the sandbox breaks in Control, item by item

Control is unsandboxed on purpose (`control.entitlements`: "the JVM daemon needs broad host
access"). Under the sandbox:

| Part of today's design | Under the sandbox | What would have to change | Size |
|---|---|---|---|
| Login start: `LaunchAgentManager` writes `~/Library/LaunchAgents` and calls `launchctl` | Not allowed: no write outside the container, no `launchctl` | `TenshuControlLoginItem.app` is embedded at `Contents/Library/LoginItems`, signed with its own MAS profile, and registered through `SMAppService.loginItem`; `DesktopStartAtLoginManager` selects it only in the sandbox. MAS builds do not register it on first launch; the Settings switch is explicit opt-in. | small (implemented) |
| Daemon vs window in one JVM, started hidden by launchd | A login item starts the app; "hidden" needs the same flag passed another way | rework of `TENSHU_CONTROL_START_HIDDEN` | small |
| Tray agent: a bare executable in `Contents/Helpers`, its own LaunchAgent with `KeepAlive` | A sandboxed helper launched by launchd needs its own sandbox entitlements; a bare binary cannot embed a profile | make it a login-item `.app` with its own profile, or fold the tray into Control (`MenuBarExtra` needs SwiftUI, so not in the JVM) | medium |
| Group-container sockets | Allowed for every member of `752LWAZMKZ.app.tenshu.shared` (milestones.md item 2) | none; this is why the sockets moved there | none |
| Login-Keychain wrapping key via `/usr/bin/security` | A child process inherits the sandbox; whether `security` can then read or write the login keychain is not verified, and its ACL names the `security` binary, not the app | move the wrapping key into the data-protection Keychain under the app's own access group, in-process via FFM or in the keychain helper. Existing installs re-key. A channel switch over one container meets the same question (§2.11). | medium |
| Spawned helpers (`qr-scan-helper`, `image-decode-helper`, `map-snapshot-helper`, `applock-helper`, `keychain-helper`, `tenshu-domain`) | Each must be signed with `app-sandbox` + `inherit`, or be its own sandboxed bundle with a profile | entitlement wiring per helper; the keychain helper becomes the main test | medium |
| `launchApp` (`open -b` via `ProcessBuilder`) and the ADR 0025 presence probe (`mdfind`, `/Applications` read) | Spawning system tools from the sandbox is restricted (not verified per tool) | `NSWorkspace` calls via FFM; probe by `NSWorkspace.urlForApplication(withBundleIdentifier:)` | small–medium |
| Synced folders: Control watches and reads user-chosen folders (`drive-synced-folders-phase2.md` §6: on desktop Control holds the grant) | Only user-selected files and folders, kept across launches by security-scoped bookmarks; a bookmark belongs to the app that made it (not verified that one app cannot resolve another's) | Control must own the folder picker, or the picking app must hand access over by some means the sandbox allows. ⚠ This is a design change to a shipped feature, not wiring. | large |
| Uploads from arbitrary paths (Drive's `FileDialog`) and File Provider hand-off by temp file (`desktop-macos-integration.md` §5b) | Open-panel picks are fine for the picking app; the bytes then cross to Control by a path Control may not read | pass file descriptors or copy into the group container first; the tech-debt entry on FFM `SCM_RIGHTS` is the start | medium–large |
| Camera for QR | `com.apple.security.device.camera` | entitlement | small |
| Network | `com.apple.security.network.client` | entitlement | small |
| JVM: JIT, temp files, `~/.java` prefs, dylibs unpacked from jars | The JIT question is the gating unknown in milestones.md (item 3). Unpacked dylibs land in the container and must be signed by us; loading code not in the bundle is close to what §2.4.5 forbids | §2.4 option 3 (pre-extract everything into the bundle) becomes mandatory | medium |
| Drive's File Provider extension | already sandboxed | none | none |

Six of these rows are more than wiring. The synced-folders row alone is a redesign of who holds the
grant.

### 4.3 JVM in the sandbox

Milestones.md already names the one thing that can kill B outright: whether App Review accepts a
bundled JVM's code-generation entitlements. Hardened runtime is not required in the store (not
verified), but HotSpot still maps executable memory; whether the sandbox alone lets it do that on
Apple silicon without `cs.allow-jit` is not verified. Several JVM apps have shipped in the Mac App
Store over the years (not verified which, or with which entitlements). The milestones plan stands:
answer it with a submission spike (a sandboxed hello-world Compose app through TestFlight for Mac),
before any of §4.2 is built; §4.3.1 records it. The fallback, also in milestones.md, is a JVM-less
macOS app on the Kotlin/Native path iOS already uses, which is a third UI tree.

### 4.3.1 Spike result (2026-10-17)

`spikes/mas-sandbox/` (deleted 2026-11-05 once the production Control and Drive builds were uploaded; last version at `2e3783216`, the App Store Connect record removed too — a new spike takes a new bundle id, e.g. `app.tenshu.mas-spike2`): a standalone Gradle build, not in `settings.gradle.kts`. One Compose window
(Kotlin 2.4.20, Compose 1.12.1, the bundled JDK 25.0.3 runtime jlink'd by jpackage, as
`control-desktop` ships), bundle id `app.tenshu.mas-spike`. Every Mach-O re-signed inside-out by
`sign.sh` with the company team's Apple Development identity (picked by SHA-1), entitlements
`app-sandbox` + `network.client`, and `app-sandbox` + `inherit` on `jspawnhelper`. Measured on this
Mac (macOS 26.6.2, Apple silicon), launched both from the shell and by `open`:

| Variant | Launch | Notes |
|---|---|---|
| sandbox, no hardened runtime, **no** `allow-jit` | ✅ | JIT compiles (C1/C2 active) |
| sandbox, no hardened runtime, `allow-jit` | ✅ | same |
| sandbox + hardened runtime, **no** `allow-jit` | ❌ SIGTRAP in `pthread_jit_write_protect_np` from `Threads::create_vm` | HotSpot uses `MAP_JIT` on Apple silicon |
| sandbox + hardened runtime, `allow-jit` | ✅ | |

- **JIT.** The sandbox alone does not stop `MAP_JIT`; only the hardened runtime does. The store does
  not require the hardened runtime (not verified for distribution signing; the upload validator
  will say), so the store build needs **no code-generation entitlement**. The Developer ID build
  (hardened runtime, notarized) needs `allow-jit`, which is D6's starting point.
- **What works under the sandbox, every variant:** the window renders; `user.home` is
  `~/Library/Containers/app.tenshu.mas-spike/Data`; a file write in it; `java.io.tmpdir` is the
  container's `tmp/`; `java.util.prefs` lands in the container's `Library/Preferences`, not
  `~/.java`; HTTPS GET (HTTP 200); `ProcessBuilder` through `jspawnhelper` runs `/bin/echo`. A write
  to `/Users/Shared` fails with EPERM, so the sandbox is enforced. `log show` reported no sandbox
  denial for the spike, not even the intended `/Users/Shared` one, so the log is no proof of
  absence; the probes are the evidence.
- **Skiko needs nothing.** Compose 1.12.1 already pre-extracts `libskiko-macos-arm64.dylib` into
  `Contents/app` and sets `-Dskiko.library.path=$APPDIR`; `~/.skiko` is never created. It leaves
  the **x64** dylib inside the jar, an unsigned Mach-O never loaded; `package-mas.sh` deletes it.
- ⚠⚠ **A dylib extracted into the container at run time cannot be loaded, even when it carries our
  own signature.** Files a sandboxed app writes get `com.apple.quarantine`; `System.load` of a
  team-signed runtime dylib copied to the container failed with *"library load disallowed by system
  policy"*, with and without the hardened runtime. So §2.4 option 2 (sign in place, extract at run
  time) does not work for the store build. **Option 3 (pre-extract into the bundle) is mandatory**
  for every jar-embedded native library Control loads (sqlite, onnxruntime, …).
- **Two fix-ups jpackage does not do** (both in `package-mas.sh`): the runtime's nested bundle
  carries the app's `CFBundleIdentifier` (renamed to `….runtime`, since the store rejects duplicate
  ids, not verified for this case); and the bundle needs `embedded.provisionprofile` plus
  `com.apple.application-identifier` / `com.apple.developer.team-identifier` entitlements.
- **Not measured:** a screenshot (the shell has no Screen Recording permission); behaviour under
  Apple Distribution signing; upload validation; review.

**Current setup (2026-11-03).** The distribution identities are installed locally:
Apple Distribution and Mac Installer Distribution. App Store Connect already contains the
production Control and Drive records under the shared iOS bundle IDs. The Mac App Store
profiles were created through the App Store Connect API for:

- `app.tenshu.control.ios`
- `app.tenshu.drive.ios`
- `app.tenshu.drive.ios.fileprovider` (with App Groups enabled; child of the shared Drive host ID)
- `app.tenshu.control.loginitem` (with App Groups enabled)

The profiles are downloaded under `build/macos-profiles/` and are ready for a production
packaging script. The File Provider profile must be regenerated whenever its child bundle ID
changes; the archive script validates that it is for `app.tenshu.drive.ios.fileprovider` before
building Drive. The host and File Provider receive separate embedded profiles and matching
application/team identity entitlements.

⚠⚠ **A record is not a macOS app until the macOS platform is added to it** (measured
2026-11-04). Both records were created as iOS apps, and `altool --validate-app -t macos` refuses
both packages before looking inside them: *"Unable to find Apple ID for Bundle ID
'app.tenshu.control.ios' on platform 'macOS App'"* (error 19), the same for `app.tenshu.drive.ios`.
The bundle ID, App ID and Mac App Store profile all existing does not make the record a Mac app.
Add the platform in App Store Connect (the app → *Add Platform* → macOS) for each record before
`scripts/macos-mas-archive.sh --validate` can say anything about the packages. Don't pass
`--apple-id` to get past the error: that is the iOS app's id, and the record still has no macOS
platform.

✅ **Validated 2026-11-04.** With the macOS platform added to both records,
`scripts/macos-mas-archive.sh --app all --validate` reports *"VERIFY SUCCEEDED with no errors"* for
Control and Drive (production bundles, Apple Distribution signing, App Sandbox on). The one
rejection on the way was the minimum macOS (D10). **Uploaded the same day**, build `202611041720`
of each, from `37615c8`: Control delivery `40ee56da-fb42-4621-b27b-6cd06edb49e6`, Drive delivery
`f9e4ccb7-7543-4f91-8f5c-84b0ae073971`. (altool's *"Failed to create URL for
'CDUploaderStateInfoResumeFilePath'"* ERROR line is noise: it has no resume file; the upload
succeeds.) Not yet done: processing, TestFlight install, behaviour under the sandbox.

**Still open: production sandbox rework.** The spike proved that a bundled JVM can run in a
sandboxed, Apple Distribution-signed Mac App Store build. The production Control and Drive
bundles are not sandboxed today, because Control still owns unsandboxed operations such as
launch-agent management, arbitrary folder access, and subprocess-based helpers. App Store
validation should not be attempted until the §4.2 changes are implemented and tested.

**Done 2026-10-17.** The setup (1–5) exists: the App ID and the Mac App Store profile were created through
the App Store Connect API by a script the maintainer ran (`Tenshu MAS Spike (Mac App Store)`).
`package-mas.sh` built and signed the `.pkg`, the upload succeeded (delivery
`85da7ab1-c06a-4463-9291-7e513da86032`, 60.5 MB), processing accepted it, and the build **installed and
ran from TestFlight for Mac** (Apple Distribution signing, sandboxed, App Store profile). Measured in
the installed app:

| Probe | Result under distribution signing, installed from TestFlight |
|---|---|
| JVM | 25.0.3 aarch64, HotSpot tiered JIT active (295 ms compile time) with **no** `allow-jit` |
| Sandbox | `APP_SANDBOX_CONTAINER_ID=app.tenshu.mas-spike`; home, tmpdir and prefs in the container |
| File write in the container / outside it | ok / `Operation not permitted` (`/Users/Shared`) |
| HTTPS | 200 |
| Skiko | loaded from the bundle; `~/.skiko` never created |
| Spawning a child (`/bin/echo`) | exit 0 |
| **A dylib copied into the container at run time** | **loaded**, unlike the dev-signed local run above |

⚠ **The dylib row contradicts the local finding.** Locally a copied library failed with "library load
disallowed by system policy"; installed from TestFlight it loads. The likeliest difference is the
quarantine flag on the locally launched build (not verified). **The rule stays: pre-extract every
native library into the bundle (§2.4 option 3)**, because App Review §2.4.5 objects to loading code from
outside the bundle whether or not it runs. It is now a review rule, not a technical blocker.

**Still open: App Review.** The build is to be submitted to TestFlight external testing for beta
review. That verdict on a bundled JVM is the last thing this spike can answer: a pass unlocks §4.2, and a
rejection sends us to the JVM-less fallback. ⚠ *Superseded 2026-11-05: the spike was deleted
with no beta-review verdict. The production Control and Drive MAS builds (§4.3.2) now carry the App
Review question, and a rejection of those still sends us to the fallback.*

### 4.3.2 What happens after the successful spike

The spike removed the JVM/runtime unknown. It did not make the production apps MAS-ready:
Control and Drive are still unsandboxed. The first implementation slice is now landed:
Control's MAS bundle carries `Contents/Library/LoginItems/TenshuControlLoginItem.app`, and
`DesktopStartAtLoginManager` selects its `SMAppService` registration in a sandboxed build while
retaining `LaunchAgentManager` for unsandboxed development and Developer ID builds. The second
implementation slice is also landed: `packageDesktopApp` now accepts
`-Ptenshu.masSandbox=true` plus a repository-relative or absolute Mac App Store profile, embeds the
profile, applies host App Sandbox entitlements, and signs profile-bearing nested apps with their own
entitlement templates. Control's MAS packaging path has been verified locally with the Apple
Distribution identity. The first security primitive for that migration is now landed in
`:macos-helpers`: `MacSecurityScopedBookmark` creates and resolves Foundation security-scoped
bookmarks in-process, starts a scoped access, and releases it idempotently. Its macOS round-trip
contract is tested. `MacSecurityScopedFolderPicker` now provides the AppKit single-folder picker
primitive, with UI-thread ownership left to the sandboxed app. The synced-folder grant flow is
also wired: Drive asks Control over the existing binary IPC, Control activates itself (a menu-bar
app's panel otherwise opens behind Drive), presents the picker off Swing's EDT and creates the
bookmark. ⚠⚠ **The bookmark bytes never leave Control**: `SecurityScopedGrantStore` keeps them in
Control's own container under a random 128-bit grant id, and Drive's selection (hence
`IngestSource.watchedPaths`) carries only the handle `tenshu.security-scoped-folder.v2:<id>:<name>`.
Control resolves the bookmark for the duration of each scan and, when macOS reports it stale,
re-mints it **in the store under the same id**. An earlier cut rewrote `watchedPaths` instead,
and Drive's re-registration of its selection on every launch put the stale bookmark back. A grant
Control cannot open reports `ERROR` on the source rather than an empty pass. Unsandboxed
development keeps raw paths as a compatibility path. Not yet built: pruning a store entry when Drive
removes the folder (entries are a few hundred bytes; the removal has no IPC route to Control yet).

MAS signing reads `com.apple.application-identifier` and `com.apple.developer.team-identifier`
from each bundle's own embedded profile and adds them to that bundle's signature (host, login item,
File Provider), after checking the profile is for the bundle's `CFBundleIdentifier`. ⚠ The macOS
key is `com.apple.application-identifier`; the bare `application-identifier` is iOS's. They are not
written into the templates because the File Provider template is shared with Developer ID builds,
where a restricted key with no profile behind it gets the extension killed.

The remaining §4.2 migration is:

1. Transfer Drive/File Provider data through the App Group container or file descriptors,
   rather than handing Control arbitrary host paths.
2. The helper and native-library audit is landed for MAS builds: directly spawned
   Control/Drive helpers use App Sandbox inheritance entitlements, while the File Provider and
   login-item bundles keep their own profile-bearing entitlement templates. The MAS packaging task
   pre-extracts arm64 ONNX Runtime and SQLite dylibs into `Contents/app/native`, points each loader
   at that bundle-local directory, strips platform-foreign native entries from jars, and fails closed
   when a new jar introduces an unaudited native entry. The signing task renames and re-signs the nested runtime bundle after signing its
   Mach-O payload, then seals the outer bundle. The File Provider receives its own nested MAS profile
   and child bundle ID. The unsandboxed
   development/Developer ID path retains its previous helper signing.
3. Build the Control and Drive MAS bundles, then use
   [`scripts/macos-mas-archive.sh`](../../scripts/macos-mas-archive.sh) to create the production Mac
   Installer Distribution packages, validate them, and upload them to App Store Connect/TestFlight.
   The script assigns one UTC-minute build number to both products (override with
   `MAS_BUILD_NUMBER`) and validates the host, login-item, and File Provider profile identifiers.
   The script reuses the iOS archive's API-key convention (`ASC_KEY_ID`, `ASC_ISSUER_ID`,
   `ASC_KEY_PATH`) and keeps Control/Drive as separate App Store Connect uploads.
4. Exercise the installed TestFlight build: login startup, folder access, File Provider,
   pairing, sync, previews, QR scanning, and uninstall/reinstall.

**The 2026-11-05 uploads carry unsigned helpers in jars (checked 2026-11-06).** The Control and
Drive 1.0.1 packages (build `202611051609`) each carry ad-hoc-signed `qr-scan-helper`,
`image-decode-helper` and `map-snapshot-helper` inside `ui-qr-scanner-desktop-*.jar` /
`macos-helpers-*.jar`. Apple's validator passed them, and neither app runs them: both have signed
`Contents/Helpers` copies of the first two, and neither ships `ui-map`, which is the only caller of
the third. They are dead executable code inside the bundle, which App Review guideline 2.4.5 may
object to; it is not a runtime defect. No re-upload is needed for function. Any later upload
carries the fix, and if review cites code outside the bundle's signature, this is the cause.

Do not upload the current production desktop bundles. They are useful for the Developer ID
route, but App Store validation and runtime behaviour are gated on this sandbox work.

### 4.4 Bundle ids and universal purchase

The macOS Control and Drive targets reuse their iOS bundle IDs:
`app.tenshu.control.ios` and `app.tenshu.drive.ios`. This permits one App Store Connect record
per product and preserves Apple's Universal Purchase option. The File Provider extension keeps
its own extension identifier under the Drive App ID.

Tenshu Commerce remains authoritative for storage subscriptions and entitlements; that does not
make separate Apple app identities preferable. Shared Apple IDs reduce distribution metadata and
keep a future Apple-managed cross-platform purchase available without an identity migration.

**C4 (2026-11-05): the other eight follow.** Calendar, Contacts, Fit, Home, Maps, Mia, Photos and
Timeline are signed on macOS with their iOS bundle IDs (`app.tenshu.<app>.ios`) in every channel,
before any public desktop build. A later rename would make every install a different app to
LaunchServices, Homebrew and the IPC gate's identifier allowlist. `BundledAppTablesTest` reads each
`<app>-desktop/build.gradle.kts` and `<app>-ios/project.yml` and fails when the catalogue, the
build and the iOS id disagree. Data subdirectories are keyed by internal names
(`app.tenshu.photos.desktop`), so settings survive the rename.

Internal Tenshu app IDs remain platform-neutral (`app.tenshu.control`, `app.tenshu.drive`). The
macOS LaunchServices seam maps those internal IDs to the shared Apple bundle IDs; the mapping is
also used by the IPC/app-launch tests. The App Group remains
`752LWAZMKZ.app.tenshu.shared`, independent of the bundle ID.

D12 supersedes the 2026-11-02 `.mac` decision. The MAS and Developer ID builds use the same shared
Apple bundle IDs.

### 4.5 Commerce

The store's in-app purchase rule (§3.1.1) applies to the Mac App Store as on iOS. The commerce plane
is self-merchant-of-record through Stripe ([`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md));
its iOS in-app purchase phase (Ph4b) is already gated on counsel and Apple. A MAS Control inherits
that whole question. A Developer ID Control does not: no store rule applies to it.

### 4.6 Is B worth it, and when

✅ **Try it, spike first (D11, decided 2026-10-17).** The maintainer wants to at least attempt the
store for the native (JVM) desktop apps. B still costs the sandbox redesign of §4.2 (one large row,
several medium), a second signing and packaging pipeline, the JVM review risk of §4.3, and the
in-app purchase question of §4.5; it buys discovery in the store, store-managed updates and the
trust of the store badge. Route A (D1) stays first, so B never blocks the first release.

The order is now: (1) the spike is complete; (2) implement §4.2's sandbox rework,
starting with the synced-folders grant; (3) finish the MAS packaging and upload pipeline; and
(4) resolve §4.5 with counsel before enabling commerce in the store build. If the production
sandbox work exposes a blocker, the JVM-less macOS app remains the fallback.

---

## 5. Risks

1. **Native code in jars and the hardened runtime (§2.4).** The first notarization will likely be
   rejected for unsigned dylibs inside jars, and a build that notarizes can still fail at launch if
   a library unpacks a dylib our team did not sign. M1 exists to hit this first.
2. **Touch ID under Developer ID (§2.3).** If `keychain-access-groups` needs a Developer ID
   provisioning profile, the current build does not have one, and the failure is silent (the UI
   offers enrolment that never succeeds, as on 2026-08-24). M1 must prove `has-enrolled → yes` in a
   notarized install.
3. **Lockstep in the field (§2.8, §2.9).** Until M3, updating is manual. A user who installs Drive
   1.4 from a stale download link next to Control 1.3 sees a skew banner. The suite `.pkg` and the
   versioned, never-overwritten URLs limit this; only the updater ends it.

Smaller: the 2 GB suite download for ten apps (each carries a JVM; a shared runtime is out of
scope); the Developer ID private key as a single point of failure (§2.2); the `.pkg` admin prompt.

---

## 6. The other eight desktop apps

They are the same shape as Drive minus the File Provider: a JVM launcher, Skiko, two or three
helpers, the App Group entitlement, no sandbox. Each needs: its row in the release script's catalogue
(already in `scripts/lib/app-catalogue.sh`), the version injection (D9), its helpers' entitlements in
the §2.3 table, its jars' native code handled (§2.4; Maps and Timeline carry their own native
dependencies, not verified which), and a download page only if it should have one (the suite `.pkg`
is the same file). They join the suite all at once rather than one by one, for the lockstep reason.
Which apps the first public suite contains is an open question (Q1).

---

## 7. Phases

| Phase | What | Needs from the maintainer |
|---|---|---|
| **M0** decisions and accounts | Take D1, D4–D13. Create the two Developer ID certificates (Account Holder only). Confirm the ASC key's role. Create the R2 bucket and DNS (`deploy/` Terraform). | Account Holder action; the existing $99/yr program; R2 storage at cents per month (not verified) |
| **M1** signing spike, Control alone | Release signing in `gradle/desktop-app-signing.gradle.kts` behind a flag: runtime and dylibs re-signed, jar dylibs handled, hardened runtime, timestamps. Notarize, staple, install on a second macOS user account with quarantine set, launch, pair, sync, Touch ID enrol, open the tray. Record the minimal entitlement set (D6) and the minimum macOS (D10). | a second Mac or user account; one afternoon of on-device checking |
| **M2** first release | Drive joins. `scripts/macos-release.sh` with every §2.7 check, the suite `.pkg`, upload, `manifest.json` + signature, the download page, the notify-only banner, D9 versions, "Remove Tenshu from this Mac". A release runbook under `docs/how-to/`, written with the script. | the manifest signing key (generated once, custody per `ops-secret-custody.md`) |
| **M3** updater | In-place suite update (§2.8) with the threat-model amendment in the same PR. | review of the amendment |
| **M4** the other eight; Homebrew | §6; a cask per app in an own tap, steps H1–H7 (§2.12.6); C1–C4 (§2.12.7). | which apps ship (Q1); D5/D7 revisions (§2.12.5) |
| **M5** MAS production path | Spike complete. Implement the §4.2 sandbox rework, then build the production MAS packager, validate, upload, and exercise the installed TestFlight build. Route A's phases keep priority (D1). | App Review's verdict on the production MAS builds (the spike was deleted without one, §4.3.1); counsel for §4.5; device testing |

---

## 8. Open questions

- **Q1.** Which apps are in the first public suite: Control and Drive only, or all ten? Shipping
  fewer is less to support; shipping all ten avoids a later suite that grows under existing users.
- **Q2.** Is the Developer ID Installer certificate wanted (D5), or is a per-app `.dmg` acceptable
  with lockstep left to the updater?
- **Q3.** Is "Start at login" on by default after install? Today it is a Settings toggle; a sync
  app that does not start at login does not sync.
- **Q4.** Export rules: the apps ship their own cryptography (libsodium). Apple asks nothing for a
  Developer ID app, but the legal question from `how-to/ios-testflight.md` §6 is the same. ⚖️
- **Q5.** Should a beta channel exist (a second manifest), the way TestFlight serves iOS?
- **Q6.** Stale docs found on the way (§1.1): `how-to/ios-signing.md`'s header and
  `desktop-ipc-client-identity.md` still name `FAYT82GWD7` as the desktop team. Fix in M0, with D4.

---

## Version

- v0.1, 2026-10-15: first draft. D2 (arm64 only) and D3 (no "Designed for iPad") recorded as the
  maintainer's decisions.
- 2026-11-05: §2.11, copies from more than one channel: one data root, one running Control
  (`ControlInstanceGuard`), which install wins at `/Applications`, updates by `_MASReceipt`, the
  unverified keychain read across the sandbox.
- 2026-11-05: §2.12, mixing channels per app: the cross-channel matrix (works, one copy per app),
  traps T1–T4, Control-switch fixes C1–C3, the bundle-ID decision C4, the channel plan (per-app
  `.dmg`, the Android release bucket, release rule R1) and the Homebrew tap steps H1–H7.
- 2026-11-06: v0.4. Route A built up to publishing: ten notarized `.dmg` (after the jar-helper
  rejection and an LSMinimumSystemVersion fix), `scripts/macos-direct-publish.sh`, generated casks; D5
  and D7 revised as built; §2.12.6 brought up to date (notarization done, notability gates only
  `homebrew/cask`); threat model §8.24.

# How-to: macOS code signing for Control + Drive

Practical signing guide for the macOS desktop surfaces in
[`../proposals/desktop-macos-integration.md`](../proposals/desktop-macos-integration.md):
the Control tray agent and the Drive File Provider extension.

There are three distinct levels of signing, needed at different points:

| Level | When you need it | Account |
|-------|------------------|---------|
| **None / ad-hoc** | Demo the **tray** locally (§1) | Free / no account |
| **Development profile + App Group** | Anything sandboxed — the **File Provider**, or a sandboxed signed `Control.app` (§2) | **Paid** Apple Developer Program ($99/yr) |
| **Developer ID + notarization** | Distribute builds to other people (§4) | Paid |

The hard gate is **App Groups**: it is unavailable on a free personal team, so the
moment you touch the App Group socket (everything sandboxed) you need paid enrollment.
See the proposal's §8 and the account-tier reasoning below.

---

## 1. Tray-only quickstart — no Apple account

The Control tray agent runs **unsandboxed**, so it can reach the daemon over the
`~/.tenshu/bridge.sock` dev socket (the `SocketPathResolver` fallback when no Team ID is
set). No provisioning profile, no App Group, no entitlements — and a locally built
binary runs without Gatekeeper getting in the way.

```bash
# 1. Start the Control daemon (unsigned dev build). With no $TENSHU_TEAM_ID set,
#    SocketPathResolver uses the ~/.tenshu fallback. Leave this running.
./gradlew :control-desktop:run

# --- in a second shell, from the repo root ---

# 2. (sanity) confirm the JSON bridge socket exists
ls -l ~/.tenshu/bridge.sock

# 3. (optional) prove the bridge round-trips with the Phase-0 de-risk client
cd control-desktop/macos/HelloClient
swiftc -O -o hello-client main.swift
./hello-client                 # prints ping + getSyncState replies
cd -

# 4. Build the tray agent
cd control-desktop/macos/TrayAgent
swiftc -O -o tray-agent main.swift BridgeClient.swift

# 5. (optional) ad-hoc sign — not required to run a locally built binary,
#    but harmless hygiene
codesign --force --sign - tray-agent

# 6. Run it — appears in the menu bar (.accessory app, no Dock icon)
./tray-agent
```

Click the menu-bar icon: the popover shows live sync state, recent notifications, an app
list, *Open Control*, and *Quit*. This exercises Phase 1 end to end with zero signing
infrastructure. (`launchApp` returns `not_supported` until Phase 4 packaging.)

> "Agent" here means the **macOS menu-bar binary** (`control-desktop/macos/TrayAgent`),
> not anything else.

---

## 2. Development signing with App Groups (paid account)

Needed for the File Provider (forced-sandboxed) and for a sandboxed signed `Control.app`.
You only need an **Apple Development** certificate for local runs — *not* Developer ID,
*not* notarization (those are §4).

### 2a. Easy route — let Xcode manage it (recommended)

1. Enroll in the Apple Developer Program, then Xcode → Settings → Accounts → add your
   Apple ID. Note your **Team ID** (Membership details).
2. Open (or create) the Swift target's Xcode project. Enable **Automatically manage
   signing** and select your team — Xcode creates the *Apple Development* certificate and
   registers this Mac as a development device.
3. Signing & Capabilities → **+ Capability → App Groups**, add
   `TEAMID.app.tenshu.shared`. Xcode registers the App ID + App Group on the portal and
   regenerates the development provisioning profile automatically.

You now have the three reusable artifacts: dev cert (login keychain), registered App
Group, dev provisioning profile. Reuse them in the Gradle `codesign` step (§2c).

### 2b. Manual route — developer.apple.com

At **Certificates, Identifiers & Profiles**:

1. **Certificate.** Keychain Access → Certificate Assistant → *Request a Certificate from
   a CA* → "Saved to disk". Then Certificates → **+** → *Apple Development*, upload the
   CSR, download the `.cer`, double-click to install.
2. **Identifiers.** Reuse the production Apple App IDs (`app.tenshu.control.ios`,
   `app.tenshu.drive.ios`) for the macOS Control and Drive targets. Register the Drive File
   Provider extension under the Drive App ID. Register the **App Group**
   `TEAMID.app.tenshu.shared` and enable it on the relevant targets. Shared IDs preserve one
   App Store Connect record and Universal Purchase across iOS and macOS.
3. **Device.** Devices → **+** → add this Mac's Hardware UUID (development profiles are
   device-scoped).
4. **Profile.** Create a *macOS App Development* profile per App ID, selecting your cert +
   device. Download it (`*.provisionprofile`).

### 2c. Entitlements + codesign

App Group entitlements (one file per signed binary;
`control-desktop/macos/entitlements/control.entitlements` already scaffolds this):

```xml
<key>com.apple.security.application-groups</key>
<array><string>TEAMID.app.tenshu.shared</string></array>
```

Add `<key>com.apple.security.app-sandbox</key><true/>` **only** to the File Provider
extension's entitlements (`drive-desktop/macos/entitlements/drive-fileprovider.entitlements`)
— it is forced-sandboxed. Keep the **daemon unsandboxed in dev** to avoid JVM keychain /
socket pain; it can still carry the App Groups entitlement so it shares the container.

Sign, embedding the dev profile:

```bash
cp dev.provisionprofile "Control.app/Contents/embedded.provisionprofile"
codesign --force --deep --options runtime \
  --entitlements control-desktop/macos/entitlements/control.entitlements \
  --sign "Apple Development: Your Name (TEAMID)" \
  "Control.app"

# verify
codesign -dv --entitlements :- "Control.app"
```

A normal **developer-signed packaged** app needs none of the manual steps above:
`packageDesktopApp` signs with the App Group entitlement and pins the Team ID into the launcher cfg
(`gradle/desktop-app-signing.gradle.kts`), so it always uses the group container. The MAS path is
explicit and embeds the Mac App Store profile plus host App Sandbox entitlements:

```bash
./gradlew :control-desktop:packageDesktopApp \
  -Ptenshu.masSandbox=true \
  -Ptenshu.teamId=TEAMID \
  -Ptenshu.signingIdentity="Apple Distribution: Company (TEAMID)" \
  -Ptenshu.provisioningProfile=build/macos-profiles/TenshuControl-MacAppStore.provisionprofile
```

For the production Control + Drive upload, use the archive wrapper. It validates that each profile
belongs to the shared Apple bundle ID (and that the profile is not a development profile), builds
both `.app` bundles through `packageDesktopApp`, signs one Mac Installer Distribution package per
app, and optionally validates/uploads with the App Store Connect API key:

```bash
export TENSHU_TEAM_ID=752LWAZMKZ
export MAS_APP_IDENTITY='Apple Distribution: Company (752LWAZMKZ)'
export MAS_INSTALLER_IDENTITY='3rd Party Mac Developer Installer: Company (752LWAZMKZ)'
export MAS_CONTROL_PROFILE="$PWD/build/macos-profiles/TenshuControl-MacAppStore.provisionprofile"
export MAS_DRIVE_PROFILE="$PWD/build/macos-profiles/TenshuDrive-MacAppStore.provisionprofile"
export MAS_CONTROL_LOGIN_PROFILE="$PWD/build/macos-profiles/TenshuControl-LoginItem-MacAppStore.provisionprofile"

# Build packages only, then validate or upload when ready.
scripts/macos-mas-archive.sh --app all
scripts/macos-mas-archive.sh --app all --validate
scripts/macos-mas-archive.sh --app all --upload
```

`--validate` and `--upload` require `ASC_KEY_ID`, `ASC_ISSUER_ID`, and `ASC_KEY_PATH`, using the
same `AuthKey_<KEYID>.p8` and `API_PRIVATE_KEYS_DIR` convention as `scripts/ios-archive.sh`.
Keep
those credentials in the shell environment; never commit them.

Only an unsigned dev run needs the export below, and only if it should share state with packaged apps:

```bash
export TENSHU_TEAM_ID=TEAMID      # → ~/Library/Group Containers/TEAMID.app.tenshu.shared/
```

⚠⚠ **The entitlement alone does not open the container.** macOS refuses a development-signed app
its App Group container, silently, until the app has asked for it through
`containerURL(forSecurityApplicationGroupIdentifier:)`. `packageDesktopApp` therefore embeds
`Contents/Helpers/appgroup-claim` in every app, and the app runs it at startup. A bundle without it
fails with `EPERM` in the container (Control: SQLite error 14 at start). See `docs/tech-debt.md`
*"A packaged desktop app is refused its own App Group container"*.

The real test that the entitlement is wired correctly: the **sandboxed File Provider
extension** can reach the daemon's socket in the group container.

---

## 3. Verifying the File Provider domain (Phase 2)

```bash
# after installing a signed Drive.app and registering the domain:
pluginkit -m | grep -i tenshu          # extension registered with the system
ls -la ~/Library/CloudStorage/         # Tenshu mount appears here + in Finder sidebar
fileproviderctl dump                    # inspect provider/domain state
log stream --predicate 'subsystem == "com.apple.FileProvider"' --level debug
```

---

## 3b. Keychain helper signing (desktop `MasterKeyStore` Phase 2)

The Touch ID / login-password unlock path
([`../proposals/desktop-masterkeystore.md`](../proposals/desktop-masterkeystore.md) §4) uses a
small Swift binary, `control-desktop/macos/KeychainHelper/`, spawned by the JVM. It stores a
`.userPresence`-gated item in the **data-protection keychain**, which carries a *restricted*
entitlement and therefore needs an **embedded provisioning profile** — not just a signature.

> ⚠ A loose `swiftc` + `codesign --entitlements` binary does **not** work: with no profile, AMFI
> kills it on launch (`SIGKILL`, exit 137). The binary must live in an `.app` bundle that carries
> a profile. On a **free (personal) team** only Xcode can mint that profile — so the helper is
> built as a minimal Xcode app target (`KeychainHelper.xcodeproj`). A free team is enough; App
> Groups (not this) is the capability that requires paid enrollment.

**First time — mint the profile in the Xcode GUI** (CLI `xcodebuild -allowProvisioningUpdates`
errors `No Account for Team …` for a free team — it can't reach the interactive account session):

1. `open control-desktop/macos/KeychainHelper/KeychainHelper.xcodeproj`
2. **keychain-helper** target → **Signing & Capabilities** → **Team** = your `(Personal Team)`.
   Xcode registers App ID `app.tenshu.control.keychainhelper` + creates the dev profile. Build once (⌘B).

**Then the scripted build works from the CLI:**

```bash
cd control-desktop/macos/KeychainHelper
TEAM=XXXXXXXXXX ./build.sh        # 10-char Team ID; see: security find-identity -v -p codesigning
export TENSHU_KEYCHAIN_HELPER="$PWD/build/Release/keychain-helper.app/Contents/MacOS/keychain-helper"
./gradlew :control-desktop:run    # DesktopBiometricUnlockDelegate.isAvailable() now true → biometric CTA shows
```

The entitlement uses `$(AppIdentifierPrefix)app.tenshu.control.keychainhelper`, expanded by Xcode
automatic signing (no `@TEAM_ID@` substitution).

> The `export TENSHU_KEYCHAIN_HELPER=…` above only lights up biometric in a Control you launch from
> *that* shell. A tray cold-launch or the login LaunchAgent has no shell env — for those you need the
> **packaged** flow below, where the helper ships inside the bundle and is found bundle-relative.

### Installed `Control.app` (Slice 2) — biometric with no env var

The helper ships at `Control.app/Contents/Helpers/keychain-helper.app` (beside the tray agent);
`DesktopBiometricUnlockDelegate.bundledKeychainHelperFor` resolves it relative to the running
launcher, so biometric works in every launch context. Build it into a dev distributable:

```bash
# mint the profile in the Xcode GUI once (above), then:
./gradlew :control-desktop:embedKeychainHelper :control-desktop:embedTrayAgent -Ptenshu.teamId=XXXXXXXXXX
# → build/compose/binaries/main/TenshuControl.app  (run it; the embedded helper is Xcode-signed)
```

> **Team ID config.** The `macos` Gradle tasks resolve the team id in order: `-Ptenshu.teamId`
> → `$TENSHU_TEAM_ID` → **`local.properties`** (`tenshu.teamId=…`, gitignored). Set it once in
> `local.properties` and drop the `-P`:
> ```properties
> # local.properties (not committed)
> tenshu.teamId=ABCDE12345
> tenshu.signingIdentity=Developer ID Application: You (ABCDE12345)   # for distribution signing
> ```
> ⚠ The Team ID is the **`OU`** of your Apple Development cert, **not** the value in the cert-name
> parens (those can differ for a personal team):
> `security find-certificate -c 'Apple Development' -p | openssl x509 -noout -subject`

> ⚠⚠ **The shared signer handles profile-carrying helpers by type — and verifies the result.**
> A nested bundle already signed by Xcode (such as the Touch ID helper) is left untouched because
> re-signing without its restricted entitlement set would silently break it. A profile-bearing
> bundle built by `swiftc` (the MAS login item) is signed with its explicit nested entitlement
> template while preserving `Contents/embedded.provisionprofile`. The signer then checks the
> entitlements on every profile-bearing nested bundle and fails if they are absent. The log reports
> whether profile-bearing bundles were left pre-signed or signed from a template.
>
> ⚠ `embedKeychainHelper` had carried the comment *"never re-sign it here"* since it was written. It
> remains correct for the Xcode-built helper; the MAS login item is the separate, explicitly signed
> exception because it has no Xcode signature to preserve. A rule is only as good as the place it is
> enforced.

`embedKeychainHelper` runs `buildKeychainHelper` (xcodebuild, offline against the GUI-minted profile)
then copies the **whole signed `.app`** into `Contents/Helpers/` — it is **not** re-signed (that would
strip the data-protection-keychain entitlement). The surrounding dev `Control.app` is unsigned, which
is fine for local runs; only the nested helper needs to be properly signed.

### Distribution (Developer ID + notarization)

A **Developer ID Application** cert needs **no** provisioning profile, so production signs inside-out
from Gradle: sign the embedded `keychain-helper.app` with its entitlements + `--options runtime`,
sign the tray + other nested code, then sign `Control.app` **without `--deep`** (which would clobber
the helper's entitlements), and notarize. This is the paid-account on-device step; the
`signControlAppForAppGroup` task is the seam to extend.

---

## 4. Developer ID + notarization (later — distribution only)

Built: [`macos-release.md`](macos-release.md) (`scripts/macos-release.sh`,
`-Ptenshu.developerIdRelease=true`). It signs every Mach-O with a *Developer ID Application*
certificate, hardened runtime and a secure timestamp, notarizes, staples and produces one `.dmg`
per app. `--pipeline-test` runs it without the certificate.

---

## Why free won't do (account-tier reference)

- **App Groups** cannot be enabled on a free personal team — signing fails with
  "provisioning profile doesn't include the App Groups entitlement". Free teams also
  expire profiles every 7 days. → the File Provider needs **paid**.
- **Notarization / Developer ID** require an active paid membership. → distribution needs
  **paid**.
- A **development certificate** ("debug" cert) is sufficient for local runs — but it must
  come from a paid account, because the App Group capability it carries is paid-gated. The
  cert *type* isn't the blocker; the account *tier* is.

Net cost to validate the full macOS surface: one $99/yr enrollment. The tray alone (§1)
needs nothing.

Sources: [Choosing a Membership](https://developer.apple.com/support/compare-memberships/),
[App Groups Entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.application-groups),
[Developer ID](https://developer.apple.com/support/developer-id/).

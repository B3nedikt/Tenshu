# Windows — submitting Control and Drive to the Microsoft Store

**Status:** written 2026-11-10 for phase W4 of [`desktop-windows-port.md`](../proposals/desktop-windows-port.md)
(§5.2, D1: the Store is the first Windows channel; D4: no certificate of ours — the Store re-signs). The build half
is done and checked; **nothing has been submitted**, because every Partner Center step needs the maintainer's
account.

Legend: 🔑 **maintainer only** (needs the Partner Center account or a decision) · ⚖️ needs counsel · 🛠 anyone with
the repo · ❓ *not verified* — stated from Microsoft's documentation or general knowledge; check it when you get there.

**Order:** Control first, then Drive. Drive does nothing useful without Control, and both share one publisher.

---

## 1. 🔑 The Partner Center developer account

- Register at `https://storedeveloper.microsoft.com` (it leads into Partner Center).
- **Individual or company** — decide which entity publishes. It sets the **PublisherDisplayName** users see under
  the app name, and it should be the same entity as on the other stores (App Store, Play).
  - Individual: sign in with a personal Microsoft account; identity verification.
  - Company: a Microsoft Entra ID work account or a personal one, plus business documents (formation papers or a
    licence) for verification.
- **Fee** ❓: Microsoft announced free individual registration in 2025 (it was a one-time ~US$19); a company account
  was a one-time ~US$99. Check the current terms on the registration page — this is general knowledge, not
  something this repo can check.
- Payout / tax profiles are only needed for paid apps or in-Store purchases; Control and Drive are free downloads
  (TODO 🔑: confirm pricing = free).

## 2. 🔑 Reserve the two product names

Apps and games → New product → **MSIX or PWA app** (not "EXE or MSI": that is the unsigned-by-us listing D1
rejected). Reserve:

| Product | Name to reserve | Must equal |
|---|---|---|
| Control | **Tenshu Control** | `tenshu.msix.displayName` in `control-desktop/build.gradle.kts` |
| Drive | **Tenshu Drive** | `tenshu.msix.displayName` in `drive-desktop/build.gradle.kts` |

⚠ The manifest's `DisplayName` must be one of the product's reserved names, or the upload is rejected ❓. If a name
is taken and you reserve another, change `tenshu.msix.displayName` to match (and `store/<app>/<lang>/name.txt`).
TODO 🔑: if you want a localized (de-DE) name, reserve it too.

## 3. 🔑 → 🛠 Read the identity, and put it in the code

For each product: Product management → **Product identity**. Copy three values:

- **Package/Identity/Name** — assigned by Partner Center (typically a numeric prefix + publisher + app name ❓).
- **Package/Identity/Publisher** — `CN=<GUID>`. ⚠⚠ **Must be the same for Control and Drive** — it is the account's,
  so it will be if both products live in one account. Two accounts ⇒ two publishers ⇒ the peer gate needs both ids
  and the tray agent's app launcher (which assumes its own publisher) breaks. Use one account.
- **Package/Properties/PublisherDisplayName**.
- Also note the **Store ID** (`9N…`) — for the Store link and `winget` (§9).

Then the code change (🛠, one commit, both apps):

1. The **identity name is pinned in Kotlin**, because the peer gate admits a package by
   `<identity name>_<publisher id>` (`desktop-windows-port.md` §3.2). Replace the provisional names everywhere they
   are spelled:
   - Control: `extra["tenshu.msix.identityName"]` in `control-desktop/build.gradle.kts`,
     `FirstPartyDesktopPeers.WINDOWS_CONTROL_PACKAGES` (`control-shared-logic/.../FirstPartyDesktopPeers.kt`) and
     `ControlServerCheck.WINDOWS_CONTROL_PACKAGE` (`ui-core/.../ControlServerCheck.kt`).
   - Drive: `extra["tenshu.msix.identityName"]` in `drive-desktop/build.gradle.kts` and
     `ClientBinding.WindowsPackage("Tenshu.Drive")` in `control-shared-logic/.../BundledAppCatalogue.kt`.
   - Tests that assert the real name: `WindowsDesktopPeersTest` (`"Tenshu.Drive" to "app.tenshu.drive"`),
     `ControlDataBridgeTest`. The `windows-helpers` tests use `Tenshu.Drive` as a fixture and can stay.
   - `packageMsix` refuses to build when a module's identity name is missing from its pin files
     (`tenshu.msix.identityPins`), so a half-done rename fails the build instead of shipping a package nothing
     admits.
2. Set `extra["tenshu.msix.identityReserved"] = true` in both modules. Until then a Store build refuses.
3. Run the gate (`scripts/run-gate.sh`) and the Windows lane.
4. ⚠ The side-loaded test packages change family too (the identity name is shared by both modes). Uninstall the old
   `Tenshu.Control_…` / `Tenshu.Drive_…` side-loads before installing the renamed ones, or you have two Controls.

## 4. 🛠 The Store build

Prerequisites on the Windows machine (none of them repo state; same as the side-load build,
`desktop-windows-port.md` §5.2 as-built): a JDK 25 **with `jpackage`** (`-Ptenshu.jpackageJavaHome=…`, e.g. in
`~/.gradle/gradle.properties`), `makeappx`/`makepri` (Windows SDK or the NuGet `Microsoft.Windows.SDK.BuildTools`,
`-Ptenshu.windowsSdkBin=…`), and the .NET 10 SDK (the tray agent). No certificate.

**Step 1 — the publisher id.** Run the command below once *without* `-Ptenshu.windowsPublisherIds`. It stops at
configuration time (seconds, before anything compiles) and prints the id to add:

```
Store MSIX for :control-desktop refused:
 - the peer gates must be compiled with exactly this publisher's id, … pass -Ptenshu.windowsPublisherIds=<id>
```

The id is deterministic (13 characters, the half of the package family name after `_`) and public — it is not a
secret.

**Step 2 — build both apps, in ONE invocation** (PowerShell):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'   # any JDK that runs Gradle
.\gradlew.bat :control-desktop:packageMsix :drive-desktop:packageMsix `
  -Ptenshu.msix.store=true `
  "-Ptenshu.msix.publisher=CN=<GUID from Product identity>" `
  "-Ptenshu.msix.publisherDisplayName=<PublisherDisplayName from Product identity>" `
  -Ptenshu.windowsPublisherIds=<id from step 1>
```

(Env equivalents: `TENSHU_MSIX_STORE`, `TENSHU_MSIX_PUBLISHER`, `TENSHU_MSIX_PUBLISHER_DISPLAY_NAME`,
`TENSHU_WINDOWS_PUBLISHER_IDS`.)

Output — the files to upload:

- `control-desktop/build/msix/store/TenshuControl_<version>_x64.msix`
- `drive-desktop/build/msix/store/TenshuDrive_<version>_x64.msix`

What Store mode does, and why:

- ⚠⚠ **It refuses unless the peer gates are compiled with exactly the Store publisher's id** — not missing it, and
  nothing beside it. In particular never the local test publisher's id (`asmrhn1bb0j6y`, from `CN=Tenshu Local
  Test`): the test certificate's PFX sits on developer disks and is trusted machine-wide where it is installed, and
  its packages report the DeveloperSigned origin the gate accepts (`desktop-windows-port.md` §3.2). Control's
  anchor (`PeerTrustAnchor`) and every app's (`ClientPeerTrust` in `:ui-core`) are generated from the same
  `tenshu.windowsPublisherIds` in the same build — a Store Control compiled without the Store id would refuse the
  Store Drive, and Drive would refuse Control. That is why both apps are built in one invocation: one property, one
  publisher, both anchors. ⚠ `-P` on the command line overrides a `tenshu.windowsPublisherIds` in
  `~/.gradle/gradle.properties` (where a side-load setup may keep the test id); the environment variable does not.
- **Unsigned.** The Store replaces any signature with Microsoft's (Microsoft Learn, *App package requirements for
  MSIX app*). A configured test certificate is ignored in Store mode.
- **A plain `.msix`, not `.msixupload`.** Partner Center accepts `.msix`, `.msixbundle`, `.msixupload` (same page,
  and *Upload MSIX app packages*). An `.msixupload` only adds an `.appxsym` — native PDB symbols for crash analytics
  — which a JVM app does not have; Microsoft's "upload the .msixupload" advice is for Visual Studio UWP builds. One
  architecture (x64), so no bundle either.
- The manifest is the side-load one with the three Partner Center values swapped in: `runFullTrust`, the Windows App
  Runtime 1.8 dependency and the toast activator + startup task on Control, logos + `resources.pri`, languages
  `en-us` and `de-de`.
- `-Ptenshu.msix.storeDryRun=true` builds while the identity is still provisional, for checking a package; the file
  is named `…-DRYRUN.msix` and **must never be uploaded** (the identity would not match the product).

Side-load builds (`packageMsix` without `tenshu.msix.store`) are unchanged: signed with the local test certificate,
publisher `CN=Tenshu Local Test`, `build/msix/<exe>.msix`.

## 5. 🛠 Versions

- The MSIX version is the suite's `desktopMarketingVersion` (`control-desktop/build.gradle.kts`,
  `drive-desktop/build.gradle.kts` — lockstep, HR #9) plus a fourth part `.0`: `1.0.1` → `1.0.1.0`.
- Store rules (Microsoft Learn, *Package version numbering*): four parts, each 0–65535, the **first not 0**, the
  **fourth 0** (reserved for the Store, which may change it). Store mode checks this and refuses otherwise.
- Customers get the highest version that applies to their device, and two packages may not share version +
  architecture. So **every upload that should reach users needs a higher `desktopMarketingVersion`**, for both apps
  together. ❓ Whether a submission that *failed* certification may reuse its version is not verified — bump anyway,
  it is free.

## 6. Validate before uploading

- 🛠 **Done by the build** (verified 2026-11-10 with a dry-run package of each app, publisher
  `CN=3F2A9C1E-7B4D-4E8A-9C21-5D6E7F8A9B0C` → id `5sbvtj090acq8`): `makeappx pack` validates the manifest against
  the schema and printed no warnings (the task now surfaces any); `makeappx unpack` + reading the manifest;
  `makepri dump` shows the 32 logo candidates; `Get-AuthenticodeSignature` reports `NotSigned`; the generated
  `PeerTrustAnchor.kt` and `ClientPeerTrust.kt` both hold exactly the Store id; no `.pdb` in the package. Sizes:
  Control ~311 MB (of which the self-contained .NET tray agent ~116 MB unpacked), Drive ~121 MB — far under the
  Store's limit, but a download users notice.
- 🔑 **Windows App Certification Kit** — not installed on the build machine (no Windows SDK at all, only the NuGet
  build tools), so **not run**. It is part of the Windows SDK installer (feature "Windows App Certification Kit"),
  an elevated install; the NuGet build tools do not carry it.
  ❓ WACK installs the package to test it, so it cannot take the unsigned Store file — run it on the **side-load**
  build (same content, signed with the trusted test certificate):

  ```powershell
  & "${env:ProgramFiles(x86)}\Windows Kits\10\App Certification Kit\appcert.exe" reset
  & "${env:ProgramFiles(x86)}\Windows Kits\10\App Certification Kit\appcert.exe" test `
      -appxpackagepath control-desktop\build\msix\TenshuControl.msix -reportoutputpath $env:TEMP\wack-control.xml
  ```

  ⚠ It installs and launches the package — not on a machine where another side-loaded copy is in use.
- 🔑 Partner Center validates on upload and names any manifest problem at once; that is the authoritative check.
- Manifest points checked against Microsoft's requirements:
  - `TargetDeviceFamily Windows.Desktop`, **MinVersion 10.0.17763.0** (Windows 10 1809, the Windows App SDK's
    floor; it must equal `TargetPlatformMinVersion` in `TrayAgent.csproj`, so CA1416 flags an agent API newer than
    the package promises), **MaxVersionTested 10.0.26100.0** (Windows 11 24H2) — raise it when tested on newer.
    ❓ Nothing has run on Windows 10: the Store will offer the package there, so test one 1809+ machine or raise
    MinVersion (both places) before the first submission.
  - `Resource Language`: `en-us`, `de-de` — the apps ship German strings and `store/<app>/de-DE` exists. ❓ The
    Store derives listing languages from the package, so a listing language not declared here cannot be added.
  - Logos: `StoreLogo`, `Square44x44Logo` (with `targetsize` + `altform-unplated`), `Square150x150Logo`, scaled
    100–400 (ADR 0026 §6b). Wide310x150, Square310x310, Square71x71 and a SplashScreen are tile/UWP assets: Windows
    11 has no live tiles and a full-trust app shows no MSIX splash, so they are not required ❓ — add them only if
    certification asks.

## 7. 🔑 The submission, per app

Create the submission (Control first). Sections:

**Pricing and availability** — TODO 🔑: free; markets (all, or the ones the other stores use); release: manual
publish after certification is the safe choice for the first one.

**Properties**
- Category: Control → *Utilities & tools*; Drive → *Productivity* (`store/<app>/primary_category.txt`).
- ⚖️ **Privacy policy URL: required** — both apps transmit personal data (end-to-end encrypted sync, an email
  address for sync sign-in). Use the one the other stores use: `https://tenshu.app/legal/privacy`
  (`store/shared/en-US/privacy_url.txt`; it 302s by browser language). ⚖️ The policy is **not yet counsel-reviewed**
  (`store-listings.md`), and it must cover the Windows apps — counsel and maintainer confirm before submitting.
- Website `https://tenshu.app`. Support contact: TODO 🔑 — the existing support URLs are the iOS pages
  (`https://tenshu.app/apps/<app>/support/ios/en`); a Windows support page, or a generic one, is owed.
- System requirements: Windows 10 2004 or later, x64. Optional.

**Age ratings** — the IARC questionnaire. The answers for the other stores are in `store/<app>/age_rating.json`
(no objectionable content, no ads, no chat, no user-generated content). ❓ IARC's questions differ in wording; answer
"shares user data / allows sharing" carefully for Drive (share links). Expected result: everyone / 3+.

**Packages** — upload the `.msix` from §4. Device family: Windows 10/11 Desktop only (the manifest targets
`Windows.Desktop`).

**Store listings** (en-US and de-DE) — text from `store/<app>/<lang>/` (`description.txt`, `keywords.txt` →
search terms, `release_notes.txt` → What's new). Needed:
- ❓ **Screenshots: at least one desktop screenshot** (PNG, 1366×768 or larger recommended), up to 10. None of the
  Windows apps has screenshots yet — TODO 🛠: capture Control and Drive on Windows (`store-screenshots.md` is the
  macOS/iOS pipeline and does not cover Windows).
- Store logos (1:1 box art / poster art) are optional ❓; the package's `StoreLogo` is used otherwise.
- TODO 🔑: listing copy review for Windows (the iOS text mentions iPhone-only features in places).

**Submission options**
- **Restricted capability `runFullTrust` — justification** (draft, 🔑 to confirm):

  > Tenshu Control is a desktop (Win32) application packaged with MSIX; `runFullTrust` is the capability every
  > packaged desktop app needs to run. It is written in Kotlin and runs on a Java runtime bundled in the package.
  > Control stays resident to sync the user's end-to-end-encrypted data between their own devices, shows a
  > notification-area icon through a small companion process in the same package, and serves the other Tenshu apps
  > (such as Tenshu Drive) over a local socket in the user's profile. All user data is encrypted on the device
  > before it leaves it; our servers cannot read it. The app does not install drivers or services, does not require
  > administrator rights, and writes only to the user's profile.

  For Drive, the same with: "Tenshu Drive is a desktop (Win32) file-management app … It connects to Tenshu Control
  on the same PC over a local socket in the user's profile and requires Control to be installed."
- **Notes for certification** — adapt `store/<app>/review/notes.txt` (the App Store notes: how to start without an
  account, the demo sign-in for sync). Add for Drive: *"Install Tenshu Control first (link); Drive connects to it."*
  ⚠ The certification tester installs from the Store, so Control must be **published (or in the same certification
  window)** before Drive's notes make sense — submit Drive after Control is live.

## 8. The Windows App Runtime dependency

Control's package declares `Microsoft.WindowsAppRuntime.1.8` (min `8000.994.2142.0`) because the tray agent is
framework-dependent on the Windows App SDK (`TrayAgent.csproj`, `desktop-windows-port.md` §3.9). Microsoft Learn
(*Windows App SDK deployment guide for framework-dependent packaged apps*): the `PackageDependency` "ensures that the
Framework package is installed when your app is deployed" — the Store installs it with Control. Drive has no
dependency.

- The *Main* and *Singleton* runtime packages are not declared (Windows does not allow it) and not deployed: the
  agent does not call `DeploymentManager.Initialize`. Singleton is for push notifications, which Tenshu does not use;
  Main lets the Store auto-update the framework ❓. Toasts and scheduled toasts are in the framework.
- ❓ Verify on a clean VM (the W4 exit gate): install Control from the Store, check the tray icon and a toast.
- ⚠ Bumping `Microsoft.WindowsAppSDK` in the csproj means bumping `tenshu.msix.windowsAppRuntime` in
  `control-desktop/build.gradle.kts` in the same change.

## 9. 🔑 After publication — the W4 exit gate

On a **clean Windows VM** (no side-loaded copies, no developer mode):

```powershell
winget search "Tenshu" --source msstore
winget install --id <Control Store ID> --source msstore --accept-package-agreements --accept-source-agreements
winget install --id <Drive Store ID>   --source msstore --accept-package-agreements --accept-source-agreements
```

Then open Control, start fresh, open Drive, and check `%USERPROFILE%\.tenshu\…\diagnostics\ipc-gate.log` for
`peer attested: <Drive identity name> (publisher <id>)`. A Drive that cannot connect, with a "not attested" line, is
the publisher-id coupling of §4 — the build was not made with the Store id.

The public Store link is `https://apps.microsoft.com/detail/<Store ID>` — the value `tenshu.control.microsoftStoreUrl`
will take (ADR 0025 row, still owed: `desktop-windows-port.md` §5.2).

## 10. ⚖️ Commerce

Control sells storage through Stripe. Microsoft Store policy has allowed non-game apps to use their own payment
rail ❓; check the current policy text and get counsel sign-off before the listing — the same gate as the other
stores (`desktop-windows-port.md` §5.2, `commerce-store-link-out.md`).

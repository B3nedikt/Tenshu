# Windows — the direct download (Control and Drive from tenshu.app)

**Status:** written 2026-11-17 for D6 of [`desktop-windows-port.md`](../proposals/desktop-windows-port.md) (§5.1
as-built). Everything below is built and tested off Windows; **nothing has been minted, built on Windows or
published yet**. The Store path is the separate [`windows-store-submission.md`](windows-store-submission.md).

Legend: 🪟 on the Windows machine · 🍎 on the Mac that holds the release bucket key · ❓ *not verified*.

**The shape.** The packages are the same MSIX as the side-load and Store builds, signed with **our own
certificate** (`CN=Tenshu Beta`, threat model A45). Users trust that certificate once, then open the `.msix`.
Built and signed on Windows; published from the Mac, like the macOS `.dmg`, to the same bucket
(`releases.tenshu.app/sha256/<hex>`, [`deploy/cloudflare-releases/`](../../deploy/cloudflare-releases/README.md)).
The bucket and its key already exist — no Cloudflare change.

---

## 0. 🪟 Prerequisites (once)

The same as the Store build (`windows-store-submission.md` §4): a JDK 25 **with `jpackage`**
(`tenshu.jpackageJavaHome` in `~/.gradle/gradle.properties`), the .NET 10 SDK (tray agent), and the Windows SDK
tools — ⚠ **`signtool.exe` too**, not only `makeappx`/`makepri`. The NuGet `Microsoft.Windows.SDK.BuildTools`
package carries all three (`tenshu.windowsSdkBin`).

⚠ If `~/.gradle/gradle.properties` holds `tenshu.windowsPublisherIds` for side-load testing, leave it: the release
script passes `-P`, which overrides it.

## 1. 🪟 The release certificate (once, then every three years)

In a normal (not elevated) PowerShell, from the repo:

```powershell
.\scripts\windows\msix-release-cert.ps1 -BackupPfx E:\tenshu-release-key.pfx -TrustHere
```

- It asks for a backup password (12+ characters). **The `.pfx` it writes is the only copy of the private key
  that can leave this PC**: move it to offline storage now (USB stick in a drawer, password manager attachment).
  Never into the repo (the script refuses), never into OneDrive or another synced folder.
- Then it deletes the key from the store and re-imports it **non-exportable**, and proves it: a later run refuses
  if the key has become exportable.
- It prints the **thumbprint** — the value for §2 — and writes the public certificate to
  `%USERPROFILE%\.tenshu-release\TenshuBeta.cer`.
- `-TrustHere` trusts it on this PC (needs an elevated shell; without one it prints the command). Needed to
  install your own build here.

Running it again later finds the certificate and prints the same facts; it never mints a second one.

**Renewal** (it warns 60 days before expiry): delete the old certificate
(`Remove-Item Cert:\CurrentUser\My\<thumbprint> -DeleteKey`) and run §1 again with the same subject. The publisher
id depends only on the subject string, so MSIX upgrades and the peer gates carry over; users must import the new
`.cer` once. ⚠ A **different** subject is a publisher switch (§5).

## 2. 🪟 Build, sign and check

From a **clean, committed** tree, at the commit you will publish:

```powershell
.\scripts\windows\windows-release.ps1 -Thumbprint <thumbprint from §1>
```

It deletes each app's `build\msix\direct\` first (an unchanged re-run would otherwise be UP-TO-DATE and
rewrite nothing), computes the publisher id (`xs68hk207yka0` for `CN=Tenshu Beta`), runs one Gradle invocation for both apps
(`packageMsix -Ptenshu.msix.direct=true …`: both peer gates are compiled for exactly that publisher), then checks
each package — signed by that thumbprint, timestamped, `Identity` name/publisher/version/x64 as expected — and
leaves `build\windows-release\`:

```
TenshuControl-<version>.msix   TenshuDrive-<version>.msix   TenshuBeta.cer   release.json   SHA256SUMS
```

The version is the suite's `desktopMarketingVersion` (`control-desktop/build.gradle.kts`, lockstep with Drive),
packed as `x.y.z.0`. Windows refuses to install a lower version over a higher one; every release that should reach
users needs a bump of both apps.

⚠ The timestamp server (`http://timestamp.digicert.com`, override `-Ptenshu.msix.timestampUrl`) must be reachable.
Without a timestamp the signature dies with the certificate, and the package with it.

## 3. 🍎 Publish

Copy `build\windows-release\` to `build/windows-release/` in the Mac's checkout, **at the same commit** (the
publish step refuses another commit, so the manifest names the source of the bytes). Then:

```bash
scripts/windows-direct-publish.sh --dry-run
```

```bash
scripts/windows-direct-publish.sh
```

```bash
scripts/windows-direct-publish.sh --verify
```

The dry run checks everything without the network and shows the manifest diff. The publish uploads what is
missing (content type `application/msix` / `application/pkix-cert`, `immutable`, a `Content-Disposition`
filename), hashes every object back, and only then writes `store/windows-release.json`. `--verify` downloads
every object from the public URL. Then commit the manifest and deploy apex: the download pages and the app pages'
Windows buttons read it at build time.

What the dry run refuses (`scripts/lib/windows_publish.py`, tests `scripts/test_windows_publish.py`): a dirty or
foreign-commit build; `SHA256SUMS` that is not exactly the packages + certificate, or bytes that differ from it; a
package that is unsigned, whose signature does not carry the published certificate, or whose `AppxManifest.xml`
names another identity, publisher, version or architecture; packages that disagree on `MinVersion`; a certificate
whose subject is not the publisher, or the local test one, or expired; a downgrade; a published version with other
bytes; a publisher switch.

**`store/windows-release.json`, schema 1:**

| Field | Meaning |
|---|---|
| `base` | `https://releases.tenshu.app/sha256/`; every `url` is `base + sha256 + .msix` / `.cer` |
| `version`, `packageVersion` | the suite version `x.y.z` and the MSIX version `x.y.z.0` |
| `arch`, `minimumWindows` | `x64`; the manifest's `MinVersion` (`10.0.17763.0` = Windows 10 1809) |
| `publisher`, `publisherId` | the certificate subject = MSIX Publisher, and the id the peer gates pin |
| `certificate` | `file`, `url`, `sha256`, `bytes`, `thumbprint` (SHA-1, what Windows shows), `notAfter` |
| `commit`, `published` | the commit the packages were built from; the first publish date of this version |
| `apps.<app>` | `identityName`, `file` (`Tenshu<App>-<version>.msix`), `url`, `sha256`, `bytes` |

## 4. 🪟 Before the first publish: a clean machine

On a **clean** Windows VM (no developer mode, no side-loaded copies, no Store copy), as a user would:

1. Download the `.cer` and run the page's line in an elevated PowerShell: it checks the thumbprint, then imports.
   Also try it with a wrong `.cer` (any other certificate renamed): it must warn and add nothing.
2. Open `TenshuControl-<version>.msix` → Install. ❓ **Does it install without the Windows App Runtime 1.8
   already there?** The Store installs that dependency; a direct install may fetch it from the Store or may fail
   with a missing-dependency error. If it fails, the page needs an "install the Windows App Runtime first" step
   (Microsoft's redistributable) before anyone is told to download.
3. Control starts; the tray icon appears; start fresh or pair; a toast shows.
4. Install Drive the same way; it connects. `%USERPROFILE%\.tenshu\…\diagnostics\ipc-gate.log` says
   `peer attested: Tenshu.Drive (publisher xs68hk207yka0)`.
5. Install the next version over it (after a bump): it upgrades in place, data stays.
6. ❓ Windows 10 1809+: nothing has run there yet (`windows-store-submission.md` §6). Test one, or raise
   `MinVersion` (manifest **and** `TrayAgent.csproj`).

## 5. Later

- **A publisher switch** (to SignPath's subject, a Certum certificate, or away from the Store's) is a **new package
  family**: it installs beside the old copy, and the two Controls' gates do not admit each other's apps. The publish
  step refuses it. Doing it on purpose needs: a page note ("uninstall the old version first"), the new id in the
  manifest, and a decision on whether one build may trust both ids during the overlap (Direct mode allows exactly
  one today). Write that down in `desktop-windows-port.md` §5.1 first.
- **Auto-update**: an `.appinstaller` file lets App Installer update on its own, but it needs one **mutable** URL
  beside the content-addressed objects. Not built.
- **winget-pkgs**: supports MSIX installers; needs the publicly trusted signer first (winget's validation will not
  trust A45).
- **The other eight apps** (W5): `packageMsix` for each module, their identity names in
  `scripts/lib/windows_publish.py` `APPS`, `windows-release.ps1` `$apps` and `windowsDownload.ts` `IDENTITY`.

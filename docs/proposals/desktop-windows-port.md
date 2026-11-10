# Porting Control (and the desktop suite) to Windows

**Status:** v0.3, 2026-11-10. The plan (W0–W7, X0–X4) is **agreed with the maintainer**, and the
distribution decision is taken: **Microsoft Store first** (§0, D1–D4). W0 measured on the maintainer's
Windows 11 machine; W1 done (socket-directory DACL moved to W2), with the `windows-desktop` CI lane in
`android.yml`; W2's DPAPI store built (§3.1). No Windows build ships.

**Scope:** what it takes for a person who is not the maintainer to install Tenshu Control on Windows 11,
pair it, and have it stay resident and safe — then the other desktop apps over it. Covers the platform
layer (key storage, IPC gate, tray, autostart, scheduling, notifications), packaging, and the two later
distribution channels the maintainer asked for: **winget** and the **Microsoft Store**.

**Relates to:** [`desktop-ipc.md`](../protocol/desktop-ipc.md) §3/§4 (transport + peer gate);
[`desktop-macos-integration.md`](desktop-macos-integration.md) §7 (the earlier Windows sketch);
[`desktop-masterkeystore.md`](desktop-masterkeystore.md) Phase 3 (DPAPI);
[`desktop-macos-distribution.md`](desktop-macos-distribution.md) (the macOS twin this mirrors — suite
lockstep, `releases.tenshu.app`, version numbers); [`core-background-processing.md`](core-background-processing.md)
§5.3 (the resident daemon); [`control-central-notifications.md`](control-central-notifications.md);
[ADR 0025](../adr/0025-forward-to-control-surface.md) (install-channel detection);
[`commerce-store-link-out.md`](commerce-store-link-out.md) (rails per store);
[`threat-model.md`](../sync/threat-model.md) A14/A15/A16/A20/A21.

**How to read the evidence.** *Measured*: observed on Windows 11 Pro 10.0.26200 on 2026-11-09.
*Read*: taken from the cited file. *Not verified*: inference or general knowledge about Windows / Microsoft
Store policy; each names what must be checked, and the phase that checks it.

---

## 0. Decisions

| # | Decision | Why, in one line |
|---|---|---|
| D1 | ✅ **Decided 2026-11-10: the Microsoft Store (MSIX) is the first and, for now, only channel.** | One upload gives both the Store and the command line (`winget install --source msstore …`), Microsoft signs it for free, and it is the only shape that is sealed (§4.1) and carries package identity (§3.5, §3.7). |
| D2 | ✅ **Decided: the side channel comes later** — a **per-machine** MSI, signed through **SignPath Foundation** (free for OSI-licensed projects; Tenshu is Apache-2.0), on `releases.tenshu.app` and `microsoft/winget-pkgs`. | Free, CI-native signing; per-machine is what makes a shared signer acceptable (§4.2). ⚠ SignPath's acceptance of the project is *not verified*; checked when W6 starts. |
| D3 | ✅ **Decided: no per-user install, on any channel.** | A per-user install directory is writable by the same user, so a validly signed launcher can be handed someone else's jars — the peer gate cannot hold (§4.1). |
| D4 | ✅ **Decided: no paid certificate.** Microsoft's cloud signing, OV and EV certificates were weighed and set aside. | D1 needs none; D2 is free; EV no longer buys SmartScreen reputation (*not verified*, and moot). |

---

## 1. The short version

Control is a Compose-Desktop JVM app, so the **logic already runs**: sync, the schedule drain loop, routines,
the sync gate and the AF_UNIX IPC transport are all in-process and portable (§2). What is missing is every
place the desktop layer leans on macOS for *being a resident, trusted background app*: key storage, the IPC
peer gate, the tray, autostart, OS notifications/reminders, and power/idle probes (§3). Then packaging and
signing (§4), then the stores (§5).

⚠⚠ **Two defects make a Windows build worse than "missing features" today (§3.4):** closing the window
hides Control with no way back (no tray, and the default is close-to-tray), and the Start-at-login switch
shows **on** while nothing will autostart.

---

## 2. W0 — what already works (measured 2026-11-09)

- `./gradlew :control-desktop:run` builds and runs; the *Tenshu Control* window opens, the log has no
  exceptions, and both `ipc.sock` and `bridge.sock` are bound in `%USERPROFILE%\.tenshu`. *Measured.*
  (Needs `JAVA_HOME`; Android Studio's bundled JBR 25 works.)
- `:core-common:desktopTest`: 1003 tests. Every `UnixSocket*` suite, `DesktopIpcIntegrationTest` and the
  `RemoteBinaryTaskExecutor*` suites pass. *Measured.*

⚠⚠ **The transport is AF_UNIX, not named pipes.** `desktop-ipc.md` §4 planned named pipes "through the same
`java.nio` API" — the JDK has no named-pipe `SocketChannel`, but it does support
`StandardProtocolFamily.UNIX` on Windows 10 1803+ (JEP 380). The existing `UnixSocketServer` /
`UnixSocketTaskTransport` therefore run unchanged. Consequence for the peer gate: the Windows peer-pid
call is `SIO_AF_UNIX_GETPEERPID` (a `WSAIoctl`), **not** `GetNamedPipeClientProcessId` as the threat
model and `desktop-ipc.md` say.

**Test portability (done 2026-11-09).** 22 failures were tests, not product: `/`-joined path expectations
(`SocketPathResolverTest`, `DesktopAppPathsTest`), POSIX-mode cases (`SocketDirectoryTest`) and
`#!/bin/sh` fake helpers (`AppGroupContainerClaimTest`). The first now compare through `File`; the other
two **skip** on Windows (JUnit `Assume`) — ⚠ a skip, not a pass: the POSIX socket-directory gate has no
Windows equivalent yet (§3.3). `SocketPathResolver`'s `~/.tenshu` fallback now joins with the host
separator (identical output on macOS/Linux).

**Four tests were red on Windows because they were real behaviour (§3.3):** `SingleInstanceLockTest` ×2 and
`SocketFileOwnershipTest` ×2 — fixed in W1.

**W1's wider run** (`:control-shared`, `:drive-shared-ui`) found two more Windows facts:
- ⚠⚠ `File.renameTo` never replaces an existing file on Windows. `FileProviderIds`' journal compaction used it,
  so it never compacted there and a crash-torn tail glued the next record onto the fragment. Now `Files.move`
  with `REPLACE_EXISTING`. Any new desktop code that rewrites a file must do the same.
- The File Provider staging directory **fails closed** without POSIX permissions (it cannot make itself
  owner-only), so every staging call answers `unavailable` on Windows. Correct posture; §7.2's design does not
  stage. Those tests skip off POSIX and `stagingFailsClosedWithoutPosixPermissions` pins the refusal.
- Socket tests used `Path.of("/tmp")`, which on Windows is `\tmp` on the current drive — present on the
  maintainer's machine, absent on a CI runner. `shortTempDirectory()` (`:core-test-utils` jvmMain) replaces it.

---

## 3. The platform layer

### 3.1 Master key at rest — DPAPI (ship-blocking)

Off macOS, `PlatformProxyModule` picks `NoWrappingKeyStore` (`control-shared-proxy/.../PlatformProxyModule.kt`,
the `wrappingKeyStore` selection), so A1/A2/A9 secrets live in memory and **the device re-pairs after every
restart**. Threat model A15 says Windows DPAPI is *required*.

- **Work:** `DpapiWrappingKeyStore` (`CryptProtectData` / `CryptUnprotectData`, user scope, over FFM — no
  helper binary needed) beside `KeychainWrappingKeyStore`; the `DesktopMasterKeyStore` seam is unchanged.
- ⚠ DPAPI user scope binds to the Windows logon credential: a password *reset* (not change) by an admin
  loses the key ⇒ re-pair. Same failure class as A21's "Keychain unavailable"; document, don't engineer.
- Threat-model amendment: A15/A21 gain a Windows row (HR #2).

> **As built (W2, 2026-11-10).** `WindowsDpapi` (new module `:windows-helpers`, the Win32-over-FFM twin of
> `:macos-helpers`, same JDK-25-compile / JVM-17-emit split) wraps `CryptProtectData` /
> `CryptUnprotectData`, user scope, `CRYPTPROTECT_UI_FORBIDDEN`, with a fixed app entropy; it answers
> `null` for anything it cannot do, and wipes the native copies of key material. `DpapiWrappingKeyStore`
> (`control-shared-proxy`) seals the 32-byte wrapping key into `wrap-key.dpapi` beside `secrets.enc`
> (atomic replace), and `PlatformProxyModule` selects it on Windows.
> - ⚠⚠ **A blob that will not open is never replaced** (wrong user, admin password reset, corruption):
>   minting a fresh key would orphan `secrets.enc` while looking healthy. It falls back to in-memory
>   secrets — a re-pair, the same outcome as an unreadable `secrets.enc` — and leaves the blob on disk.
> - ⚠ DPAPI keeps no store, unlike the Keychain: the sealed key is a file, so "Remove Tenshu from this PC"
>   (§4) must delete `wrap-key.dpapi` with `secrets.enc`.
> - ⚠ A15 says DPAPI is *required*; nothing enforces that yet — without DPAPI, Windows falls back to
>   in-memory secrets like Linux.
> - ⚠⚠ **Every `:windows-helpers` binding must load on a JVM without `java.lang.foreign`.** The shared
>   modules' tests run on JDK 17, and the Control DI graph builds the key store on any Windows host: a
>   struct layout in `WindowsDpapi`'s initialiser failed class-init (`NoClassDefFoundError`) and took down
>   all 12 Control integration suites. FFM types now live in a private `Bindings` class loaded inside
>   `runCatching`; follow the same shape for the next binding.
> - Tests: `WindowsDpapiTest` (real DPAPI, hard-fails if the binding does not resolve on Windows) and
>   `DpapiWrappingKeyStoreTest` (fake DPAPI, every host). Threat model: A15, A21, T-C-17's Windows paragraph.

### 3.2 IPC peer gate (ship-blocking)

Off macOS every peer resolves `Unattested`; with `strict = true` a packaged build admits **no one** (Drive
cannot connect), a dev run admits everyone (`desktop-ipc.md` §3, threat model A14/A20 carve-out (b)).

- **Work:** a `WindowsPeerAttestation` behind the existing `PeerCredentialPolicy` chokepoint: peer pid via
  `SIO_AF_UNIX_GETPEERPID` → open the process → verify the **running image's** signer, and pin it to a
  compiled-in trust anchor (as macOS pins the Team ID).
- ⚠⚠ **The pid race has no audit-token answer on Windows** (`tenshu-rei.md` §5.F.49). Holding a process
  handle opened *before* the check narrows it (a held handle keeps the pid from being reused) but does not
  bind the handle to the connecting socket. Needs a design answer and a threat-model entry, not a shrug.
- ⚠⚠ **What the gate pins follows D1–D3 — and it is never a signer alone** (§4.1):
  - **Store (MSIX), first:** the peer process's **package family name** (`GetPackageFamilyName` on the peer
    process) must be one of ours. It is derived from our Partner Center publisher, not from Microsoft's
    re-signing certificate, and the package's files sit in the OS-protected `WindowsApps` folder with
    integrity enforced — so identity and integrity come together.
  - **Side channel (later, D2):** the running image's Authenticode signer must be SignPath Foundation's
    **and** the image must live under `%ProgramFiles%` (admin-writable only). ⚠⚠ The signer alone proves
    nothing: SignPath signs many projects with the same certificate. Pin subject + issuer, never a
    thumbprint (certificates renew).
  - A peer that is neither is unattested. Mixed installs (a Store Control, a side-channel Drive) are allowed,
    as on macOS (`desktop-macos-distribution.md` §2.12).
- Per-method authorization inside the bridge (the 2026-08-21 audit item) is platform-independent and not
  re-opened here.

> **As built (W2, 2026-11-10) — the Store arm.** `WindowsPeerProcess` (`:windows-helpers`) reads the peer's
> pid with `WSAIoctl(SIO_AF_UNIX_GETPEERPID)`, opens it and **holds the handle** while it reads creation time,
> liveness, package family name and image path. `WindowsPeerDecision` (pure, tested on every host) attests
> only a live process created before verification began whose family name is `<one of our package names>_<a
> publisher id from the anchor>`. `WindowsPeerCredentialVerifier` adapts it to the existing
> `PeerCredentialVerifier` seam; `Main.kt` selects it on Windows behind the same strict policy.
> - Package names: `ClientBinding.WindowsPackage` in `BundledAppCatalogue` (Drive) and
>   `FirstPartyDesktopPeers.WINDOWS_CONTROL_PACKAGES` (bridge only). ⚠⚠ Provisional — the Store assigns them
>   at the Partner Center name reservation, and they must then be replaced.
> - Anchor: `-Ptenshu.windowsPublisherIds=<id>[,…]` (or `TENSHU_WINDOWS_PUBLISHER_IDS`) at build time →
>   `PeerTrustAnchor.FIRST_PARTY_WINDOWS_PUBLISHER_IDS`. **Empty by default: nothing attests**, a strict Control
>   admits no Windows peer, and a developer-mode `run` admits everyone (as before).
> - Verified on Windows 11: the real lookup over an AF_UNIX socket returns the peer's pid, a live process
>   created before the accept, and no family name for an unpackaged `java.exe` — so it is refused.
>   ✅ **The positive path is verified (2026-11-10):** a side-loaded `Tenshu.Drive_asmrhn1bb0j6y` connected to a
>   strict side-loaded `Tenshu.Control_asmrhn1bb0j6y` and Control's `diagnostics/ipc-gate.log` recorded
>   `peer attested: Tenshu.Drive (publisher asmrhn1bb0j6y)` — the W2 exit gate.
> - ⚠⚠ **The gate has two halves, and W2 first built only one.** Every app also checks the *server*
>   (`ControlServerCheck` in `:ui-core`, `desktop-ipc-client-identity.md` §11) before sending a byte, strictly,
>   and a packaged app can never use developer mode. That check knew only macOS signatures, so a packaged
>   Windows app refused the genuine Control. It now has a Windows arm — the listening process must be the
>   `Tenshu.Control` package from an anchored publisher — fed by the same `tenshu.windowsPublisherIds`
>   property (`ClientPeerTrust.FIRST_PARTY_WINDOWS_PUBLISHER_IDS`). ⚠ `Tenshu.Control` is spelled in two
>   places (`ControlServerCheck.WINDOWS_CONTROL_PACKAGE`, `FirstPartyDesktopPeers.WINDOWS_CONTROL_PACKAGES`);
>   they must move together.
> - The gate's decisions are also written to `<data dir>/diagnostics/ipc-gate.log`: an MSIX has no stdout,
>   so without it a refused app is invisible. Its own file, because `FileDiagnosticSink` rewrites its file to
>   enforce the cap and two sinks on `control.log` would race.
> - Tests: `WindowsPeerDecisionTest` (8), `WindowsPeerProcessTest` (real lookup), `WindowsDesktopPeersTest`,
>   `FirstPartyPublisherPolicyTest`. Threat model: A14, T-C-11's Windows paragraph.
> - ⚠ `:control-shared-logic` joined the Windows CI lane: its `DesktopAppLockTest` /
>   `DesktopBiometricUnlockDelegateTest` faked macOS-only helpers with `#!/bin/sh` scripts, which Windows cannot
>   run (9 failures, now skipped there — the same class as W1's `AppGroupContainerClaimTest`).

### 3.3 Transport leftovers (small)

| Item | Today on Windows | Fix |
|---|---|---|
| Socket-directory gate (`SocketDirectory`) | silent no-op (no POSIX modes) ⇒ nothing stops a squatter | owner-only DACL check/repair on `%USERPROFILE%\.tenshu` — **still open** |
| `BoundSocketFile` | `fileKey()` is always `null` on Windows ⇒ by its own fail-safe rule the server **never** removes its socket on quit ⇒ stale `ipc.sock` | ✅ **W1:** an owner token in `<socket>.owner`, written **before** the unlink, used only where the directory reports no file key. ⚠⚠ Not creation time: NTFS *tunnels* a deleted file's creation time onto a same-named file re-created soon after — exactly the successor-rebinds case |
| `SingleInstanceLock` after a crash | a force-killed holder's lock is still reported held immediately after (*measured*); Windows documents lock release on termination as asynchronous | ✅ **W1:** retry for up to 5 s **only while the recorded holder pid is dead**; a live holder is still answered at once, so a second launch hands off without waiting |
| `SingleInstanceLock` holder text | `"held by null"`: Windows locks are mandatory, so the loser cannot read the locked file | ✅ **W1:** also written to an unlocked `<lock>.holder`, read when the lock file is unreadable |
| Socket path length | ~108-byte `sun_path` | `~\.tenshu` is fine; never move sockets under a deep `%LOCALAPPDATA%\Packages\…` path |

### 3.4 Window, tray and the two live defects

There is **no** AWT `SystemTray`, `TrayIcon` or Compose `Tray` in the code; the macOS tray is a separate
Swift `NSStatusItem` agent (`control-desktop/macos/TrayAgent/`) speaking the JSON bridge, and
`ensureTrayRunning` returns early off macOS (*read*).

⚠⚠ **Defect 1 — closing the window strands Control.** `onCloseRequest` hides the window when
`closeToTray()` (`control-desktop/.../Main.kt`), which **defaults to `true`**
(`DesktopControlSettings.closeToTray`). On Windows that leaves a resident process with no window, no tray
icon and no taskbar entry. The only way back is a second launch handing off over `bridge.sock`
(`ControlInstanceGuard.requestOpenWindow`) — which the strict peer gate (§3.2) refuses in a packaged build.
**Interim fix (W1):** off macOS, `closeToTray` is unavailable and close quits, until a tray exists.

⚠⚠ **Defect 2 — the Start-at-login switch lies.** Off macOS `DesktopStartAtLoginManager` falls through to
`LaunchAgentManager.forDaemon()`, which writes a plist into `%USERPROFILE%\Library\LaunchAgents` and calls a
missing `launchctl`; `isAutostartEnabled()` then reads *installed && !disabled* = **on**. **Interim fix
(W1):** hide the switch off macOS until §3.5 lands.

> **As built (W1, 2026-11-09).** `DesktopShellSupport` (`control-shared` lifecycle) is the one place that
> says whether this OS has a tray and a start-at-login mechanism — both `true` on macOS only. The window
> hides on close only when a tray exists; Settings shows each switch only where it works; and
> `DesktopStartAtLoginManager` is inert off macOS (reports `false`, writes no plist). Pinned by
> `DesktopShellSupportTest`. ⚠ Linux had both defects too, and gets the same fix. When W3 lands a Windows
> tray or autostart, it flips its flag in `DesktopShellSupport.forOs`.

**The tray (W3).** Unlike macOS, Windows needs **no separate agent**: the Control process itself can own the
notification-area icon (`Shell_NotifyIcon`), so it calls `WindowController` / `SyncPauseState` in-process
rather than over the bridge. Recommendation: Compose `Tray` (AWT underneath) for v1 — menu-only, but enough
for Open / Pause / Quit and a state icon; bind `Shell_NotifyIcon` over FFM only if the AWT tray's HiDPI
icon or menu proves inadequate (*not verified* on Windows 11; check in W3). `desktop-macos-integration.md` §7
said "bind directly, no DorkBox" — still true; the question is only AWT-vs-FFM.

### 3.5 Autostart and the resident daemon

The daemon contract (`core-background-processing.md` §5.3) is "one resident process"; on Windows that needs
only *start at logon, hidden*. The docs disagree on the mechanism (`tenshu-rei.md`: Run key;
`core-background-processing.md`: Task Scheduler).

- **First (D1, Store/MSIX):** declare `windows.startupTask` in the package manifest and toggle it through
  `StartupTask` (WinRT) — the `SMAppService` analogue: the OS owns the entry and the user sees it in
  Settings → Startup. ⚠⚠ Not a Run value: a packaged app's HKCU writes are virtualized into the package, so
  a Run value it writes is invisible to Explorer (*not verified*; W4 spike).
- **Later (D2, side-channel MSI):** an `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` value launching
  `TenshuControl.exe --hidden` (a Run value cannot set the environment). So `WindowsStartAtLoginManager`
  grows two arms, selected by "has package identity" — the same split macOS has between `SMAppService` and
  the LaunchAgent.
- Task Scheduler only if a measured need appears (e.g. restart-on-failure); it costs a COM/`schtasks` surface.

### 3.6 Scheduling, alarms and background wake

**Already portable (in-process, *read*):** the desktop `PlatformScheduler` is `NoOpPlatformScheduler`; the
real driver is `ScheduleDrainLoop` (offline keeper, thumbnail backfill, media index, contact-photo sweep,
subscription refresh), plus `SyncPauseState` + `ConstraintSyncGate`, plus routines (a resident timer with a
90 s catch-up after sleep, `routines-engine.md`). All of these work on Windows **as long as the process is
alive** — which is why §3.4/§3.5 are the real scheduling work, not a Windows scheduler.

**OS-specific, needs a Windows arm:**

| Seam | macOS | Windows today | Windows equivalent |
|---|---|---|---|
| Calendar reminders | the Swift tray reads `PendingAlertSchedule` over the bridge and hands each to `UNCalendarNotificationTrigger`, so they fire with Control closed | **never delivered** — nothing reads the set | WinRT `ScheduledToastNotification` via `ToastNotifier.AddToSchedule`; ⚠ needs an AUMID (§3.7) |
| Indexing conditions (`DesktopIndexingConditions`) | `MacPowerProbe` (IOKit battery, CG idle) | probe null ⇒ scheduled indexing **never runs** | `GetSystemPowerStatus` + `GetLastInputInfo` over FFM |
| Metered network (`DesktopNetworkStateProvider`) | — (always `UNMETERED`, every OS) | same | `NetworkInformation.GetInternetConnectionProfile().GetConnectionCost()` — the first desktop OS that actually exposes metering well |
| Sleep/resume | nothing wired | nothing wired | `WM_POWERBROADCAST`/`PBT_APMRESUMEAUTOMATIC`, or rely on the drain loop's own catch-up (measure first) |

⚠ **Reminders are the one alarm that must survive a closed Control.** Everything else is "runs while
resident". Scheduled toasts are held by the OS, which is exactly the property the macOS design chose
(`calendar-app.md` §4.6: "the OS holds the trigger") — keep that shape, don't reintroduce a hand-rolled
desktop alarm.

### 3.7 Notifications

On macOS the Swift tray polls `pendingRequests` / `pairingApproval` / `calendarReminders` and posts through
`UNUserNotificationCenter`, because the JVM has no bundle identity (`control-central-notifications.md`).
On Windows there is no tray and nothing posts.

- **Work (W3):** a JVM-side poller over the *same* in-process sources (no bridge needed) posting WinRT
  toasts; activation → `WindowController.show()`.
- ⚠⚠ **Toasts need an AppUserModelID.** An MSIX has one by construction — one more reason for D1. The
  later side-channel MSI must register one (a Start-menu shortcut carrying the AUMID). Without it toasts
  silently do not show (*not verified*; W3). ⚠ So W3's toasts and reminders can only be verified from a
  packaged MSIX, not a `:control-desktop:run` (no identity) — test through a locally side-loaded MSIX.
  `TrayIcon.displayMessage` is the identity-free fallback but cannot schedule (reminders) or carry actions.

### 3.8 Degrades gracefully — no work required to ship

QR pairing falls back to the manual code; image decode and video fall back to AWT and the OS's
default-app hand-off; the app lock (`DesktopAppLock`) is a pass-through (Windows Hello via
`UserConsentVerifier` = masterkeystore Phase 3); `DesktopControlPresence` answers `null` (correct per ADR
0025); launching another app (`open -b`) returns `not_found` (Windows: the Start-menu AUMID, or
`shell:AppsFolder\<AUMID>` under MSIX). Explorer integration (Cloud Files API) is its own track — §7.

---

## 4. Signing and install location — why D1–D3

### 4.1 The certificate is not what protects the peer gate; the install directory is

On macOS `codesign` seals the **whole bundle**, and the audit-token check verifies the running code against
it. On Windows an Authenticode signature covers **one file**. Our apps are a jpackage launcher `.exe` plus
jars, a `.cfg` and a bundled runtime — and the launcher's signature says nothing about the jars it loads.

- ⚠⚠ **Per-user install (`%LOCALAPPDATA%`): unsafe under any certificate.** The same user — AD3, the very
  adversary the peer gate exists for — can replace the jars or the `.cfg` under a validly signed launcher.
  Hence D3.
- **MSIX (`WindowsApps`): sealed.** The folder is OS-protected and package integrity is enforced; the identity
  is our package family name. The strongest posture available, and free (D1).
- **Per-machine MSI (`%ProgramFiles%`): acceptable.** Only an administrator can write there, so "signed by the
  expected signer **and** running from under `%ProgramFiles%`" holds against a same-user adversary — which is
  also what makes a *shared* signer such as SignPath Foundation usable (D2).

### 4.2 How each channel is signed

| Channel | Signed by | Android analogue |
|---|---|---|
| **Microsoft Store, MSIX** (D1) | **Microsoft re-signs** the uploaded package; we hold no key. The Partner Center account is the upload credential. | Play App Signing |
| Store CLI (`winget --source msstore`) | the same Store package — nothing extra to build or sign | — |
| Side-channel MSI + `winget-pkgs` (D2, later) | **SignPath Foundation**, from public CI, on every `.exe`/`.dll` and the MSI (the macOS lesson "every Mach-O is re-signed" applies to the bundled runtime too) | the side-channel key |

⚠ Unlike Android, a Windows certificate is **not** update continuity: an MSI upgrade follows its UpgradeCode
and an MSIX update its package identity. Changing signer later is routine, not a key-loss event.

### 4.3 Packaging common to both

- **Lockstep:** the suite moves together (HR #9; `desktop-macos-distribution.md` D8/D9): one version for every
  app. On the Store, each app is its own package; Control's update prompt and the schema-skew surface
  (`core-schema-skew-surface.md`) cover an app the Store has not updated yet.
- **Uninstall:** Control needs "Remove Tenshu from this PC" for what it created (`~\.tenshu`, `secrets.enc`
  **and** `wrap-key.dpapi`, the startup entry, scheduled toasts) — the twin of macOS D13. An MSIX uninstall
  removes only the package's own files.
- **Side-channel MSI only:** `targetFormats(Msi)` (jpackage + WiX), **per-machine** (D3), a Start-menu
  shortcut (the AUMID, §3.7), an UpgradeCode fixed forever per app. ⚠ The UpgradeCode is the Windows twin of
  a bundle id: change it and the next version installs *beside* the old one.
- Gate coverage: the `windows-desktop` lane in `android.yml` (W1) runs the desktop suites on Windows.

---

## 5. Distribution — the Store first, the side channel later

Order per D1/D2: **§5.2 (Store) is W4**, the first channel; **§5.1 (side-channel MSI + winget-pkgs) is W6**.

### 5.1 Side channel: SignPath-signed MSI + winget-pkgs (W6 — later, D2)

The Store package is already reachable from the command line (`winget install --source msstore`), so this
channel is for people who will not, or cannot, use the Store. winget-pkgs is the Homebrew-cask analogue: a
manifest pointing at **our** per-machine MSI by URL + SHA-256.

- **Signing (D2):** SignPath Foundation, from GitHub Actions on the public repo. ⚠ Their acceptance criteria
  (OSI licence, public CI build, active maintenance, a review of the project) are *not verified* against
  Tenshu; apply when W6 starts. ⚠ The certificate is issued to SignPath Foundation, so the publisher users see
  is theirs, and the peer gate must not trust the signer alone (§3.2, §4.1).

- One manifest set per app (`Tenshu.Control`, `Tenshu.Drive`, …) submitted as PRs to
  `microsoft/winget-pkgs`, which runs automated validation (installs the MSI in a sandbox, checks silent
  install, hash, signature) and a moderator review per new package (*not verified* in detail; W6).
- Needs: immutable versioned URLs (the content-addressed bucket already is), silent install
  (MSI gives `/quiet` for free), the MSI's ProductCode/UpgradeCode in the manifest so `winget upgrade` works.
- Automate the bump PR from the release script (`wingetcreate update`), as `desktop-macos-distribution.md`'s
  H-steps do for the cask.
- ⚠ winget-pkgs installs the **same artifact** as the direct download, so it is the same channel for ADR 0025
  (`SameChannelAsThisApp`) and the same identity for the peer gate. Nothing new in either.
- ⚠ Lockstep: winget upgrades packages independently (`winget upgrade Tenshu.Drive` alone is possible). A
  `Tenshu.Suite` meta-package with dependencies, or Control's own update prompt, must cover skew — the
  schema-skew surface (`core-schema-skew-surface.md`) is the backstop either way.

### 5.2 Microsoft Store, MSIX (W4 — the first channel, D1)

Two submission shapes exist (*not verified* against current Partner Center rules; W4 starts by checking).
**D1 chose MSIX**; the MSI/EXE listing is recorded for completeness, and would reintroduce our own signing:

| | MSI/EXE listing (unpackaged) | MSIX package |
|---|---|---|
| Artifact | our signed MSI, hosted by us, Store links it | MSIX built from the jpackage app-image (`makeappx`), uploaded |
| Signed by | us (Authenticode) | **Microsoft re-signs** for Store distribution |
| Identity | none — same as direct | package family name, AUMID, `StartupTask`, scheduled toasts by construction |
| Updates | ours | Store-managed |
| Cost | near zero once W4 exists | the second build variant |

MSIX, because package identity is what §3.5 (autostart), §3.7 (toasts/reminders) and §3.2 (pin by package
family) want anyway, and because it is the sealed shape (§4.1). Still **spike first**, mirroring macOS D11:
package Control from the jpackage app-image, side-load it locally, then submit.

- **Partner Center account:** individual registration has been free since 2024 and a company account is a
  one-time fee (*not verified*; check the current terms when registering). Which entity publishes decides the
  publisher name users see.

> **As built (W2, 2026-11-10) — the local side-load build.** `gradle/desktop-msix.gradle.kts` adds
> `packageMsix` to Control and Drive: the jpackage app-image plus a generated `AppxManifest.xml` (full trust,
> `runFullTrust`) and placeholder logos, packed with `makeappx` and signed with `signtool`. It prints the
> package family name and the **publisher id** to compile into Control (`-Ptenshu.windowsPublisherIds=…`).
> `scripts/windows/msix-test-cert.ps1` mints the matching throwaway certificate (`CN=Tenshu Local Test`).
> - The publisher id is derived exactly as Windows does (`MsixPublisherId`), pinned by a known answer:
>   Microsoft's publisher gives `8wekyb3d8bbwe`. ⚠ The first attempt was one bit off in the grouping and the
>   known answer caught it — keep that test; the Gradle script also self-checks against it before use.
> - Prerequisites on a dev machine, none of them repo state:
>   - ⚠⚠ **A full JDK 25 with `jpackage`.** Where Android Studio's bundled JBR is the only JDK 25, toolchain
>     detection picks it, and the JBR has **no `jpackage`** — `checkRuntime` fails. Install one (e.g.
>     Temurin 25) and pass `-Ptenshu.jpackageJavaHome=<it>`; the override is opt-in so CI and macOS releases
>     embed what they did (Control and Drive only; the other eight `*-desktop` modules follow in W5).
>   - `makeappx.exe` / `signtool.exe`: the Windows SDK, or the NuGet package `Microsoft.Windows.SDK.BuildTools`
>     (`-Ptenshu.windowsSdkBin=<dir>`).
>   - The test certificate trusted in `LocalMachine\TrustedPeople` — an **elevated** step, so the maintainer's.
> - ⚠⚠ **`packageMsix` must declare the app-image as an input.** It first declared only the identity
>   strings, so after a code change Gradle called it UP-TO-DATE and kept the old package: the side-loaded
>   apps were not the source being debugged. Check the `.msix` timestamp before trusting a side-load test.
> - Observed in the packaged Control: `wrap-key.dpapi` beside `secrets.enc` (§3.1 works under MSIX),
>   `~\.tenshu` written un-virtualized by a packaged app (§5.2's assumption holds), and the instance lock's
>   `.holder` naming `C:\Program Files\WindowsApps\Tenshu.Control_…\TenshuControl.exe`.
> - ⚠ The MSIX identity names (`tenshu.msix.identityName` in each module) must equal the catalogue's
>   `ClientBinding.WindowsPackage` / `WINDOWS_CONTROL_PACKAGES`; nothing checks that yet, and a mismatch fails
>   closed (the package is never admitted).

What MSIX changes, all *not verified* until the W4 spike:

- ⚠⚠ **File-system and registry virtualization.** Writes to `%APPDATA%`/`%LOCALAPPDATA%` and HKCU are
  redirected per package. Control and Drive are **separate packages**, so anything they must both see — the
  socket directory, shared settings — must live outside the virtualized locations. `~\.tenshu` (profile
  root) is not virtualized; keep it there and test it.
- ⚠⚠ **Peer gate signer** — see §3.2: Store builds carry Microsoft's signature; pin by package family.
- **AF_UNIX between two full-trust packages** — expected to work (full-trust MSIX is not an AppContainer);
  the spike must prove Drive-MSIX ↔ Control-MSIX over `ipc.sock`.
- **Bundled JVM** — no JIT entitlement problem as on the MAS (Windows has no equivalent), but the package
  size and the `runFullTrust` restricted capability need review sign-off.
- **ADR 0025 channel detection:** today Windows always answers `SameChannelAsThisApp`. A Store build can
  tell it is one (package identity + the Store as installer source) and offer the Store link —
  `tenshu.control.microsoftStoreUrl` beside the existing `playUrl`/`macAppStoreUrl` keys, and a new row in
  ADR 0025 §6's table (amend the ADR in the same change).
- ⚖️ **Commerce.** Microsoft Store policy has allowed non-game apps to use their own payment rail; if that
  still holds, Store Control keeps Stripe (`commerce-store-link-out.md` D3 would need no Microsoft rail).
  Check the current policy text and get counsel sign-off before the listing — same gate as the other stores.

---

## 6. Phases

| Phase | Content | Gate to leave it |
|---|---|---|
| **W0** | Measure: run Control, run the suite (§2) | ✅ done 2026-11-09 |
| **W1** | Defects §3.4 (close quits, hide autostart switch off macOS); §3.3 transport leftovers; Windows `desktopTest` CI lane; correct `desktop-ipc.md` §4 and the threat model's named-pipe wording | the four red tests green on Windows — ✅ 2026-11-09, plus the lane; socket-directory DACL moved to W2 (it is part of the peer-gate posture) |
| **W2** | DPAPI key store (§3.1) ✅; Windows peer gate by **package family name**, both halves (§3.2) ✅; socket-directory DACL (§3.3) — open; threat-model amendment (A14/T-C-11) ✅; certificate decision ✅ D1–D4 | ✅ a side-loaded Drive MSIX connects to a strict side-loaded Control MSIX (2026-11-10). ⚠ "pair, restart, still paired" not yet run end to end |
| **W3** | Tray (§3.4), `StartupTask` autostart (§3.5), toasts + scheduled reminders (§3.7/§3.6), power/idle + metered probes (§3.6) | closed-window Control reachable from the tray; a reminder fires with Control quit — verified from a side-loaded MSIX |
| **W4** | Microsoft Store: spike, then MSIX per app, lockstep version, ADR 0025 Store row, uninstall (§4.3, §5.2) | the Store build passes certification; `winget install --source msstore` installs it on a clean VM |
| **W5** | The other desktop apps over the same pipeline | each passes the W4 check |
| **W6** | Side channel: SignPath-signed per-machine MSI, `releases.tenshu.app`, winget-pkgs manifests + automation (§5.1); the gate's SignPath + `%ProgramFiles%` arm (§3.2) | `winget install Tenshu.Control` (winget-pkgs) on a clean VM; a per-user copy is refused by the gate |
| **W7** | (merged into W4 — kept so phase numbers in other docs stay valid) | — |

Out of scope here: Windows Hello app lock (masterkeystore Phase 3), ARM64 Windows (decide at W4, like
macOS D2). Explorer integration is sketched in §7 and phased separately (X0–X4).

---

## 7. Drive in File Explorer — the Cloud Files API

The Windows twin of the Finder File Provider (`desktop-macos-integration.md` §5–§6a) is the **Cloud Files
API** (`cldapi.dll`, "CfAPI") plus `StorageProviderSyncRootManager` — the stack OneDrive, Dropbox and iCloud
use on Windows. It gives the same user-facing result: a *Tenshu Drive* entry in Explorer's navigation pane,
online-only placeholders that download on open, "Always keep on this device" / "Free up space", and sync
status overlays. The **shape underneath is different**, and the differences decide the design.

### 7.1 How the two models differ

| | macOS File Provider (as built) | Windows Cloud Files |
|---|---|---|
| Who serves requests | a sandboxed `.appex` the **system** launches on demand | an ordinary process that **connects** to the sync root (`CfConnectSyncRoot`) and gets callbacks while connected; ⚠ disconnected ⇒ opening a placeholder fails ("the cloud file provider is not running") |
| Where items live | the system's own item database; we answer "enumerate" / "changes since anchor" | a **real NTFS folder** (e.g. `%USERPROFILE%\Tenshu Drive`); we create, update and delete placeholder files in it ourselves |
| Remote changes | pulled: the system asks, nothing pushes (§5a D2's limit) | **pushed by us**: `CfCreatePlaceholders` / `CfUpdatePlaceholder` / delete, at any time |
| Local changes | callbacks: `createItem` / `modifyItem` / `deleteItem` | rename/delete arrive as callbacks (vetoable); ⚠⚠ **new files and content edits do not** — the provider must watch the folder (`ReadDirectoryChangesW`) and upload, then convert to a placeholder and mark in-sync |
| Item identity | daemon-minted ids (`FileProviderIds`, §5a D1) | a per-placeholder **FileIdentity blob** we choose (≤ 4 KB) — the same minted id fits; the path is NTFS's |
| Contents | hand-off through `fileprovider-staging/` (§5b D5) | `FETCH_DATA` callback → `CfExecute(TRANSFER_DATA)` in offset/length chunks — **ranged by design**, so it maps onto the seekable content stream with no staging file |
| Eviction | `.allowsEvicting` | pin states + `CfDehydratePlaceholder`; Explorer's "Free up space" menu comes for free |
| Progress / cancel | staging-file size; cancel on its own connection | `CfReportProviderProgress` (Explorer shows it); `CANCEL_FETCH_DATA` callback |
| Thumbnails, badges, menu items | `NSFileProviderThumbnailing`, decorations | COM handlers (`IThumbnailProvider`, custom states, `IExplorerCommand`) registered for the sync root — ⚠ a native DLL, not JVM code |

### 7.2 Recommendation — Control hosts the sync root, in-process

On macOS the bridge and the staging directory exist because the server is a Swift extension in another
sandbox. On Windows the CfAPI core is a **C API callable over FFM** (callbacks as upcall stubs), so the
provider can live **inside the Control daemon** — already resident (§3.5), already holding the file-service
and `FileProviderChangeFeed` — with no bridge on the content path at all.

- ⭐ This dissolves macOS's two weakest points: remote changes appear **without** an Explorer window open
  (D2's "nothing pushes" limit is gone, because we push), and there is no plaintext staging hand-off
  (T-C-16's staging half).
- It matches the standing desktop rule that the resident process is Control, not the app
  (`milestones.md`, *Background-service story*). The sync root is still registered under Drive's name and icon;
  Drive's UI and the Explorer view read the same realm.
- ⚠ If Control is not running, placeholders cannot hydrate. That is the same dependency every Tenshu app
  already has on Control, but here a third-party program (any editor opening a file) hits it — the error text
  is Windows', not ours.
- Rejected: hosting it in Drive (Drive is not resident, so hydration would fail whenever Drive is closed);
  reusing the bridge for the core path (an IPC hop and a staging file that buy nothing in one process).

The bridge stays the seam for the **COM handlers** only (thumbnails, later badges/menu items): a small native
DLL that Explorer loads, which asks Control over `bridge.sock` — so §3.2's peer gate must admit it.

### 7.3 What carries over unchanged

- **Ids and rename safety** (§5a D1): `FileProviderIds` supplies the FileIdentity blob. A move made in
  Explorer is a real NTFS rename we are told about; a move made on another device is still delete + create,
  as on macOS.
- **Thumbnails** (§5a D4): only the stored classes (`File.Thumbnail` / `ThumbnailLarge`), 256 px ceiling.
  ⚠⚠ **Never `File.Preview`** — and on Windows the stakes are higher: without a thumbnail handler Explorer
  **hydrates the file to draw its icon**, so a folder of online-only photos downloads itself on first view.
  The handler is therefore part of the MVP, not polish.
- **Upload memory ceiling** (§5b): the file-service still takes one `ByteArray`; > 2 GiB is `too_large`.
- **Permission posture** (§6 task 2): still deferred to the rei/AppGrant track.
- **Online-only detection when *uploading* from another provider's folder**
  (`drive-folder-upload.md` §11): `FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS` is the Windows probe, as that doc
  already says.

### 7.4 Security posture (threat-model amendment owed, HR #2)

Hydrated files are **plaintext in a normal NTFS folder** under the user's profile — the same exposure class
as a materialised Finder file, but readable by any same-user process without going through any gate, and
persistent until evicted. A20/T-C-16 gain a Windows row; disk encryption is BitLocker's job and out of scope.
The FileIdentity blob and the folder's names are plaintext metadata on disk, as `fileprovider-ids.tsv` is.

### 7.5 Unknowns — a spike answers them before anything is built

- ⚠ Whether `StorageProviderSyncRootManager.Register` (navigation-pane entry, Explorer's Cloud UI) works
  **without package identity**, or needs MSIX / a sparse package. The C `CfRegisterSyncRoot` alone does not
  make the navigation-pane entry (*not verified*). If identity is required, §7 moves after §5.2's
  identity decision, or uses a sparse package beside the MSI.
- ⚠ CfAPI callbacks as FFM upcalls: the callbacks arrive on CfAPI's own threads and must return
  quickly; that has to be measured, not assumed.
- ⚠ COM handler registration (thumbnails) is manifest-declared under MSIX; the unpackaged route is
  registry-based (*not verified*).

### 7.6 Phases

| Phase | Content | Gate |
|---|---|---|
| **X0** | Spike: register a sync root from the JVM, one placeholder, hydrate it via FFM; answer §7.5 | a placeholder created by Control opens in Notepad |
| **X1** | Read path: placeholders from the realm listing, hydration from the content stream, push updates from `FileProviderChangeFeed`, pin/evict | a realm browses in Explorer; open downloads, "Free up space" evicts; hashes match |
| **X2** | Thumbnail handler DLL over the bridge (§7.3) | a folder of online-only photos shows thumbnails **and stays online-only** |
| **X3** | Write path: folder watcher → upload, rename/delete callbacks, conflicts | create / edit / rename / delete in Explorer round-trips to another device |
| **X4** | Polish: progress, sync-state overlays, "Remove Tenshu" unregistering the root (§4) | uninstall leaves no orphaned sync root |

X0 can run any time after W1. X1 onwards needs W2 (paired, restart-stable Control) and W3 (autostart), because
hydration needs Control resident.

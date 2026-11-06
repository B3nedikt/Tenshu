# Tenshu Keychain helper (desktop `MasterKeyStore` Phase 2)

A tiny Swift binary that gates the 32-byte unlock **wrap-key** behind macOS
**Touch ID / login password**, for the desktop biometric unlock path
(`docs/proposals/desktop-masterkeystore.md` §4). It is spawned by
`DesktopBiometricUnlockDelegate` (Kotlin) — the JVM can't call
`Security.framework` / `LocalAuthentication` directly.

It stores a single `userPresence`-gated generic-password item in the
**data-protection keychain**; reading it triggers the OS auth sheet (Touch ID,
falling back to the login password via `deviceOwnerAuthentication`).

## ⚠⚠ Two builds of this one source — and only one of them needs signing

Since RA-5 (`docs/proposals/control-keep-device-unlocked.md` §6.1) `main.swift` is built **twice**:

| Build | Verbs it can serve | Signing | Ships |
|---|---|---|---|
| `keychain-helper.app` (Xcode, below) | all — incl. `enroll` / `unwrap` | provisioning profile, GUI-minted once | opt-in (`-Ptenshu.withKeychainHelper`) |
| `applock-helper` (`swiftc`, ad-hoc) | `is-available`, `authenticate` only | **none** — ad-hoc `codesign -s -` | **every** macOS bundle, by default |

`is-available` and `authenticate` are pure `LAContext.evaluatePolicy(.deviceOwnerAuthentication)`
calls: they touch no keychain item, so they need no entitlement. They were only ever gated on
signing because they shared a binary with the verbs that do. The soft app-lock and the Ph-reauth
gates use those two verbs alone, so they now work on any Mac with no Apple account.

⚠⚠ **The distinct name is load-bearing.** `bundledKeychainHelperFor` accepts a bare
`keychain-helper` (Developer-ID inside-out signing produces exactly that, at full capability). If
the ad-hoc build reused that name the biometric delegate would resolve it, `is-available` would
answer `yes` and `has-enrolled` `no`, and the UI would offer an enrolment that can never succeed.
Never rename `applock-helper` to `keychain-helper`.

## Why the full-capability build is an Xcode target (and not a loose `swiftc` binary)

The data-protection keychain + `kSecAttrAccessControl` require the binary to be
signed with an **embedded provisioning profile** (it carries a *restricted*
entitlement, `keychain-access-groups`/`application-identifier`). A bare
`swiftc` + `codesign` binary has no profile, so **AMFI kills it on launch with
`SIGKILL` (exit 137)** — verified. The profile is what an `.app` bundle carries,
and on a **free (personal) Apple team** only Xcode can mint it. So the helper is
built as a minimal Xcode app target; we exec the inner `Contents/MacOS/keychain-helper`
binary directly (it's a CLI — it just lives inside a signed `.app` for the profile).

A free team is enough — App Groups is the capability that needs paid enrollment,
not this.

## Protocol

One status token on stdout (exit 0 unless it crashes); the JVM keys off the
token, never the exit code:

| Command | stdin | stdout |
|---|---|---|
| `is-available` | — | `yes` \| `no` |
| `has-enrolled` | — | `yes` \| `no` (never prompts) |
| `enroll [reason]` | `base64(wrapKey)` | `ok` \| `cancelled` \| `unavailable` \| `failure:<msg>` |
| `unwrap [reason]` | — | `ok:<base64>` \| `cancelled` \| `not_found` \| `invalidated` \| `unavailable` \| `failure:<msg>` |
| `clear` | — | `ok` |

## Build + sign (free or paid team)

**First time — create the provisioning profile in the Xcode GUI** (the CLI
`xcodebuild -allowProvisioningUpdates` can't mint a free-team profile without the
interactive account session — it errors `No Account for Team …`):

1. `open KeychainHelper.xcodeproj`
2. Select the **keychain-helper** target → **Signing & Capabilities** →
   **Team** = your team (e.g. your `(Personal Team)`). Xcode registers the App ID
   `app.tenshu.control.keychainhelper` + creates the development profile.
3. Build once (⌘B), or just let step 2 generate the profile.

**After the profile exists, the scripted CLI build works:**

```bash
TEAM=XXXXXXXXXX ./build.sh        # 10-char Team ID = the OU of your signing cert (NOT the cert-name
                                  # parens — they can differ on a personal team):
                                  # security find-certificate -c 'Apple Development' -p | openssl x509 -noout -subject
```

(The Gradle tasks instead read `tenshu.teamId` from `local.properties` / `-P` / `$TENSHU_TEAM_ID`;
see `docs/how-to/macos-signing.md` §3b.)

It prints the inner binary path. Point the JVM at it:

```bash
export TENSHU_KEYCHAIN_HELPER="…/keychain-helper.app/Contents/MacOS/keychain-helper"
./gradlew :control-desktop:run      # biometric unlock now lights up in Control
```

Smoke-test without the GUI (`enroll` does **not** prompt; `unwrap` shows the
Touch ID sheet):

```bash
head -c32 /dev/urandom | base64 | "$TENSHU_KEYCHAIN_HELPER" enroll "Tenshu test"   # -> ok
"$TENSHU_KEYCHAIN_HELPER" unwrap "Tenshu test"                                     # Touch ID -> ok:<base64>
"$TENSHU_KEYCHAIN_HELPER" clear                                                    # -> ok
```

If `enroll` prints `failure:...MissingEntitlement` or the process is killed
(exit 137), the profile isn't attached — redo the GUI step above.

## Production (later, Slice 2)

Developer ID + notarization needs **no** provisioning profile: `codesign --sign
"Developer ID Application" --options runtime --entitlements …` then notarize —
scriptable from Gradle, embedding this helper in `Control.app/Contents/Helpers/`
next to the tray agent. The same Xcode project switches to Developer ID by
changing the signing config. See `docs/how-to/macos-signing.md` §3b.

## How the JVM finds it

`DesktopBiometricUnlockDelegate` resolves the helper in order:
1. `-Dtenshu.keychainHelper` / `$TENSHU_KEYCHAIN_HELPER` (dev override), then
2. **bundle-relative** — `Contents/Helpers/keychain-helper.app/Contents/MacOS/keychain-helper`
   (or a bare `Contents/Helpers/keychain-helper`) next to the running launcher, so a packaged
   `Control.app` needs **no** env var in any launch context (tray cold-launch, login LaunchAgent).

The Gradle `embedKeychainHelper` task puts it there. If no executable helper is found, biometric
stays **dormant** (`isAvailable=false`) and desktop behaves exactly as before.

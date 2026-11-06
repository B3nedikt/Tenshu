# Building an Android release for Google Play, and outside it

**Status:** the build path works end to end — all ten apps build, sign and pass every check. Control
is on Play's internal track (v3, uploaded by `--upload` on 2026-10-22); the other nine had their
first upload in the Console on 2026-10-22 (see *Uploading*), so later uploads can use `--upload`
once each app is in the service account's grant. The path for **outside Play** (APKs downloaded
from tenshu.app, *Releasing outside Play*) is built and dry-run with a throwaway key. The side-channel key exists and is pinned (2026-11-06);
nothing ships from it until that pin has reached Play users in a Play release (*The side-channel key*,
step 4).

The Play developer account is an **organisation** account, not a personal one, so Google's
closed-test requirement for new personal accounts (12 testers for 14 days before production
access) does not apply.

```bash
scripts/android-release.sh --init-key                 # once — generates the key into the Keychain
scripts/android-release.sh --version-code 2 control   # one app
scripts/android-release.sh --version-code 2           # all ten
scripts/android-release.sh --version-code 4 --upload internal control   # build, check, upload
```

Bundles land in `build/play/<app>-<versionCode>.aab`, and only if they passed the two checks the
script runs on every one. The Console work — permission declarations, Data safety — is
[`play-store-declarations.md`](play-store-declarations.md).

## What the script guarantees, and why it has to

Gradle reports success for both of these, and Play refuses both:

- ⚠⚠ **A bundle signed with the debug key.** The build falls back to the debug keystore whenever no
  release key is configured, so that `assembleRelease` always produces an installable APK. The
  script compares each bundle's signing certificate with the key it was asked to use.
- ⚠⚠ **A native library that is not 16 KB page-aligned.** Required for any app targeting Android 15
  or later. Ours all arrive inside dependency AARs (ONNX Runtime, MediaPipe, SQLite), so a
  version bump can reintroduce one with no change to our code. The floors are recorded beside the
  pins in `gradle/libs.versions.toml`; `scripts/lib/check_16kb_alignment.py` is the check.

## Where the key lives

In the macOS Keychain, in the same convention as `deploy/scaleway-dev/tf.sh` (account `tenshu`):

| Item | Holds |
|---|---|
| `android-upload-keystore` | the PKCS12 keystore, base64 |
| `android-upload-keystore-password` | its password |

The script writes the keystore to a temporary directory for the one Gradle run and removes it on
exit. It runs Gradle with the configuration cache off, because a cache entry would keep the
passwords under `.gradle/`.

⚠ An exported `TENSHU_RELEASE_STORE_FILE` (with `_STORE_PASSWORD`, `_KEY_ALIAS`, `_KEY_PASSWORD`)
wins over the Keychain. That is the route for CI, and for signing with a key that is deliberately
kept out of the Keychain.

⚠ The Keychain holds the only copy of a key made with `--init-key`. Export it somewhere else before
the first upload: `security find-generic-password -a tenshu -s android-upload-keystore -w | base64 -d`.

## Which key signs what

Play re-signs every app it delivers (Play App Signing is mandatory for new apps), so two keys exist:

- the **upload key** proves a bundle came from us. Google can reset it on request, which makes it an
  operational credential in the sense of [`ops-secret-custody.md`](../proposals/ops-secret-custody.md)
  D1 — the Keychain is the right home.
- the **app signing key** is the identity users' devices see. ⚠⚠ It is chosen **once per app, at
  that app's first upload**, and cannot be changed afterwards.

**Decided (2026-10-22): Google generates a separate app signing key for each of the ten apps.**
Only Google holds them. Every other channel (a download from our site; a later F-Droid channel, if F-Droid does not sign it) is
signed with a separate **side-channel key** of our own, the same for all ten.

This revises the 2026-10-18 decision, which had the other nine reuse Control's key through the
Console's "use the same key as another app in this developer account" option. All nine first
uploads (2026-10-22) got their own Google-generated key instead (table below), and on review a
shared key buys nothing here. The Console names three reasons to reuse a key: apps running in one
process, apps sharing code or data, and apps preinstalled with a given key. None applies: the apps
share no process and no files, and they reach Control over Binder, which does not compare signers.
Separate keys also keep one key's compromise to one app. The price is that
`protectionLevel="signature"` cannot gate Control's interface; the pinned digest list below can,
with ten entries per channel instead of one.

| App | Play signing key, SHA-256 (`deployment_cert`) |
|---|---|
| Control | `01:25:4D:FB:5D:67:A3:DF:F9:53:A2:6A:44:34:E7:DD:00:CB:18:C6:A8:EF:8F:93:EC:2B:BC:07:5A:CD:8D:2C` |
| Drive | `3D:9E:B2:4D:B3:51:25:A3:73:08:60:63:4A:75:4A:CA:83:AE:F0:3D:D2:76:89:EB:80:40:A0:D9:13:70:F1:39` |
| Maps | `4C:BF:59:E7:CF:DD:57:D7:2F:F5:E6:9E:9D:56:06:A9:68:01:5B:F8:29:E0:4F:EC:4C:93:7E:09:48:8A:DB:F6` |
| Timeline | `53:FE:A2:06:11:DE:19:6E:92:49:82:21:17:AD:65:8D:BA:A9:D0:18:73:52:B5:60:4C:91:9B:AD:17:22:30:97` |
| Calendar | `11:39:11:51:33:05:3C:C2:49:1E:C2:3F:F3:7B:CD:FB:1C:71:E8:F5:EE:9A:BB:74:71:1B:B8:55:9F:DA:70:76` |
| Contacts | `4C:59:1A:EF:1E:8E:42:D6:8D:67:CF:97:A7:A5:63:2A:68:1A:11:B9:C8:4E:55:D8:9A:54:5A:E4:65:87:7D:4B` |
| Fit | `93:7A:E9:AE:D3:A1:77:16:62:12:91:2B:A7:7D:6F:2C:12:31:2A:10:E0:26:66:0C:27:5E:94:2D:89:FD:71:28` |
| Photos | `43:42:15:C7:A9:7F:0C:80:C7:56:24:BA:32:7B:9B:37:46:83:D2:47:EF:BC:0B:8F:36:14:68:70:AD:73:F0:05` |
| Mia | `35:E6:05:C3:3A:5C:26:D3:7A:6B:67:5B:5A:8B:70:AA:49:68:96:E3:82:27:92:E8:C5:06:F4:B4:77:C4:15:CE` |
| Home | `52:EB:57:37:A2:25:7B:69:44:79:B0:EB:C0:F8:3E:CB:42:91:73:DF:3F:08:66:C0:F4:A9:64:BD:3B:2D:0F:65` |

Read from the Play API (`generatedApks/<versionCode>`, field `certificateSha256Hash`) — the API
route needs no Console download, and Control's value matches its `deployment_cert.der`. Re-read for
all ten on 2026-10-29 (versionCode 5): unchanged.

⚠⚠ **This table is pinned in code**, in `FirstPartySigning.PLAY_SIGNING_CERT_SHA256`
(`core-common/src/androidMain`), and `FirstPartySigningTest` fails when the two differ. A new app
needs its row in both on the day of its first upload, or Control refuses it (next section).

⚠ **App Links read the same keys from the web.** An app that declares an `android:autoVerify` link
on tenshu.app (Drive's invite link, `/i`) is verified against
`web/apex/public/.well-known/assetlinks.json`, served at `https://tenshu.app/.well-known/assetlinks.json`.
That file lists each such app with its Play key and, once pinned, the side-channel key;
`AssetLinksTest` fails when it differs from the pins or from the manifests. A key change therefore
also needs an apex redeploy, or Play reports "Deep links not working" and the links open the browser.

⚠⚠ **There is no "sign it ourselves" route on Play.** New apps must be uploaded as App Bundles, and a
bundle requires Play App Signing, so whatever Play delivers is signed by a key Google holds. The only
choice is whether Google generates that key or we upload one.

Why not upload our own key, so that one identity covers every channel:

- **Damage stays in its channel.** A leaked side-channel key reaches only off-Play users; Play's
  one-time key upgrade would not rescue Play users from a shared one (it covers new installs on
  Android 13+ only).
- **Google's reach stays in its channel.** With a shared key Google could sign builds our off-Play
  users accept. Separate, it can sign only for Play users, which it can effectively do already.
- **Custody does not get lighter.** The side-channel key must be guarded either way once anything
  ships off Play; sharing it only widens who depends on it.
- **Accepted price:** switching one app between channels means uninstall and resync (rare, and fast
  since sync), and we can never take the Play key out, so leaving Play later means users reinstall.

⚠ The side-channel key is **not** the upload key, though both are ours. The upload key is
deliberately disposable (Google resets it); the side-channel key is a permanent identity users'
devices pin. `--init-key` makes the upload key; `--init-key --direct` makes the side-channel key
(*The side-channel key*, below).

**Side-channel key, SHA-256 (all ten apps):** `C4:D4:81:6E:D4:65:6D:59:3A:6B:FA:A6:ED:35:04:AE:C3:4B:95:C8:26:16:0B:CF:2B:C2:A5:77:3B:9D:AC:F3`

⚠ There is no dual signing to reach for. APK signature scheme v3 carries one signer plus its
rotation history, not two alternative identities, and Play delivers with exactly one key.

What this gives, and what it does not:

| | |
|---|---|
| Apps from the **same** channel | on Play, ten different signers: `protectionLevel="signature"` does not work between them, `knownSigner` with all ten digests does (Android 12+) |
| Apps from **different** channels on one device | different signers, admitted by the side-channel digest pinned beside the Play keys in `FirstPartySigning` (any API level; key rotation is followed from API 28). ⚠ Refused until that digest is pinned |
| Switching one app's channel | uninstall first — and with backups off that is a fresh store and a new pairing for that app |
| Custody | the upload key (resettable) plus the side-channel key, which only off-Play users depend on ([`ops-secret-custody.md`](../proposals/ops-secret-custody.md) D1/D5) |

⚠ F-Droid's *main* repository signs with F-Droid's own key unless the build is reproducible, so it
is a further identity under either choice. Only our own repo, or a reproducible build, carries ours.

**Built (2026-10-29): Control admits only first-party callers, and the apps check Control** (threat
model T-C-20). A Binder caller is served only if its package is one of the ten above **and** it is
signed by that app's pinned Play key, by the pinned side-channel key, or by Control's own key. The
last rule is what lets debug builds (all signed by the local debug key) and two copies from the side
channel through, with no build-type branch. Every app applies the same rule to Control before binding
it. No
`protectionLevel` is involved: `signature` cannot span ten keys, and `knownSigner` needs API 31.
⚠ The check is `hasSigningCertificate` (through `PackageInfoCompat.hasSignatures`), not a comparison of
signature bytes: it follows a key's rotation history, so a Play key upgrade does not lock out existing
installs. ⚠ Play's *internal app sharing* links re-sign with a different Google key, so builds
installed that way are refused; the internal / closed / open testing tracks use the real signing key.
⚠ Mixing channels (Control from Play, an app from the side channel, or the reverse) is refused until
the side-channel key's digest is pinned **in the build on the device**. It is pinned in the source
(`SIDE_CHANNEL_SIGNING_CERT_SHA256`, 2026-11-06), so a Play copy admits website copies only from the
first Play release that carries it.

⚠⚠ **Play holds three signing keys per app, not one** (seen on Control, 2026-10-21; the Console's
App integrity page offers them as `certificates.zip`). All three are subject `CN=Android, O=Google
Inc.`, generated at the first upload:

| File | Algorithm | SHA-256 (Control) |
|---|---|---|
| `deployment_cert.der` | RSA 4096 | `01:25:4D:FB:…:5A:CD:8D:2C` |
| `hybrid_classical_cert.der` | RSA 4096 | `21:8C:69:0C:…:8E:B9:B0:44` |
| `hybrid_pqc_cert.der` | ML-DSA-65 (post-quantum) | `38:1F:D1:22:…:FB:E7:4E:02` |

This is Play's post-quantum hybrid signing from the Android 17 cycle (a classical plus an ML-DSA
signature over the APK). **Android 16 (checked 2026-10-22, Control from the internal track on a
OnePlus):** the installed APK has one signer, `deployment_cert` (`01:25:4D:FB:…`), APK Signature
Scheme v3, no rotation lineage; beside it sits Play's *source stamp* (`32:57:D5:99:…`), which marks
the install as coming from Play and is not an app identity.
**Android 17 (checked 2026-10-29, Control versionCode 5's Play-generated APK on an API 37 emulator):**
the APK carries a signing block apksigner 36 does not know (id `0x70e1c89f`, ~24 KB) holding all
three certificates. The platform reports the **ML-DSA** certificate (`38:1F:D1:22:…`) as the current
signer, and `deployment_cert` and `hybrid_classical_cert` as past signers in a rotation lineage
(`dumpsys package` → `past signatures`). `hasSigningCertificate(deployment_cert)` therefore still
matches, and a debug Drive built with the pin bound that Control. So pinning `deployment_cert` alone
is right. ⚠⚠ Anything that compares the *current* signer, or apksigner 36's single-signer view,
gets Android 17 wrong. ⚠ Play also says it will prompt for a signing key upgrade
at least every two years, so the Play key is **not** fixed for an app's lifetime: the pinned list
must expect rotation, which is one more reason to use `hasSigningCertificate`.

Optional, also not built: Play's **code transparency** lets us sign a hash list of the bundle's code
with a key of our own; the delivered APKs carry it, so anyone can verify Google shipped exactly our
code. It does not change the signer and can be added at any later release.

## Versions

`versionCode` and `versionName` are set for all ten apps in the root `build.gradle.kts`, from
`-Ptenshu.versionCode` / `-Ptenshu.versionName`. The script requires `--version-code` because Play
refuses a code it has already seen for an app, and a default would be right exactly once.
⚠ The codes are **per app** on Play and are not all equal: 1.0.2 (2026-11-06) is code 9 for nine
apps and **10 for Fit**, whose 1.0.1 already used 9. Take the next code above the highest any app
has (`play_publish.py check`), so all ten line up again: 11 for the release after 1.0.2.

## Uploading

`--upload <track>` sends every bundle that passed to that Play track (`internal`, `alpha` = closed
testing, `beta` = open testing, `production`) as the track's **only** release, rolled out to
everyone on it. `scripts/lib/play_publish.py` does the API work and can be run on its own: `check
<package>` proves the credentials and app grant and prints each track without changing anything;
`upload --track T <package> <aab>` uploads a bundle that is already built, and
`promote --from internal --to production --version-code N <package>` moves a tested release to
another track without re-uploading (Play refuses a version code twice). It refuses unless the
source track's one completed release is exactly `N`, so a newer upload since the test is never
promoted by accident. Before building, the
script runs `check` for every requested app, so a missing grant fails in seconds, not after the
build.

The credential is a Google Cloud **service account** (project `tenshu`, no organization —
`play-upload@tenshu.iam.gserviceaccount.com`) with the *Google Play Android Developer API* enabled
and no Cloud roles at all. Its JSON key sits in the Keychain item `play-service-account` (account
`tenshu`); `TENSHU_PLAY_SERVICE_ACCOUNT_FILE` overrides it. Its Play rights are granted per app in
the Console under *Users and permissions*, so **each new app must be added to its grant** before
`--upload` reaches it.

⚠⚠ **An app's first upload cannot go through the API.** The app has to exist in the Console, and
its app signing choice — "Let Google generate" for every app (above) — is made only in
the Console's first-release flow. Upload the first bundle there by hand; every later one can use
`--upload`.

⚠ An app Play still treats as a draft (never released on any track) accepts only `status: draft`;
`play_publish.py upload --status draft` is for that case. Not fastlane `supply` (which the iOS listings
use, `store-listings.md`): uploading is four API calls, and the Android release path then needs no
Ruby toolchain. Listings and screenshots for Play may still go through `supply` later.

## R8 and native code

Every app's release build runs R8 — shrink and optimize, **never rename** (`-dontobfuscate`; the
source is public). Configured once in the root `build.gradle.kts`, rules in
`gradle/android-release-rules.pro`. Control's code went from 84.9 MB to 9.7 MB.

⚠⚠ **R8 cannot see native code reaching Java by name.** ONNX Runtime and MediaPipe ship no R8 rules,
so R8 emptied the classes their `.so` files construct, Gradle reported success, and Control aborted
on *Index now* (2026-10-22, `NoSuchMethodError: ai.onnxruntime.NodeInfo.<init>`). The debug build
is not minified, so neither the smoke gate nor an emulator launch reaches it.
`scripts/lib/check_jni_classes.py` now refuses such a bundle: it reads every class name out of the
`.so` files and looks it up in the dex. A new or bumped native dependency that trips it needs a
`-keep` in the rules file.

**Native debug symbols** go into the bundle for Play's crash reports
(`debugSymbolLevel = "SYMBOL_TABLE"`). That needs the NDK pinned as `ndkVersion` in the root
`build.gradle.kts`; without it AGP skips the step silently, so the release script refuses a bundle
without symbols. ⚠ Only the bundled SQLite and ONNX Runtime's JNI glue carry symbols — the
vendors strip ONNX Runtime itself and MediaPipe, so their native frames stay unnamed.
Installing the NDK by hand (this SDK has no `sdkmanager`): download
`android-ndk-r28c-darwin.zip` from `dl.google.com/android/repository/`, check its SHA-1 against
`repository2-3.xml`, unpack it to `$ANDROID_HOME/ndk/28.2.13676358`.

## Releasing outside Play

For phones without Google Play: an APK per app, downloaded from `tenshu.app/apps/<app>/download/android`.

```bash
scripts/android-release.sh --init-key --direct                 # once, by the maintainer (below)
scripts/android-release.sh --direct --version-code 6           # all ten
scripts/android-release.sh --direct --version-code 6 drive     # one app
```

Output, written only if every APK passed: `build/direct/<app>-<versionCode>.apk`, `SHA256SUMS`
(`shasum -a 256 -c SHA256SUMS` verifies it), `release.json` (version, each file's SHA-256 and size,
the signing certificate) and `signing-cert-sha256.txt`. Publish the certificate digest on the download
page next to the checksums: it is what a user can compare against `apksigner verify --print-certs`.

What `--direct` changes, and why:

- ⚠⚠ **`assembleRelease` with `-Ptenshu.android.billingRail=stripe`.** A Play build must never offer
  Stripe and a build for outside Play must sell without Play Billing
  ([`commerce-store-link-out.md`](../proposals/commerce-store-link-out.md) D3). The script checks the
  generated `BillingRailConfig` says `STRIPE`.
- ⚠⚠ **Signed by the side-channel key, and refused unless that key is the one pinned in
  `FirstPartySigning`.** An APK signed by an unpinned key cannot talk to a Play-installed app in
  either direction, and once published it cannot be withdrawn from users' phones.
  `--allow-unpinned` is for a dry run before the key exists: it writes to `build/direct-unpinned/`,
  which must never be published.
- ⚠⚠ **No debug-key fallback, at the Gradle level too.** With the Stripe rail and no release key
  configured, the root `build.gradle.kts` fails `packageRelease` / `signReleaseBundle` instead of
  signing with the debug key. Play re-signs a bundle; nothing re-signs an APK we hand out.
- ⚠⚠ **Production endpoints only.** A `local.properties` line, a `TENSHU_*` variable or a `-P` flag
  silently repoints the relay, commerce or the trusted issuer for every later build. The script runs
  the three generators in the same Gradle invocation and refuses anything but `relay.tenshu.app`,
  `commerce.service.tenshu.app` and no extra issuer (`scripts/lib/direct_release.py endpoints`).
- **The bundle checks that apply to an APK**: signer (with `apksigner`, since an APK for minSdk 24 has
  no v1 signature for `keytool` to read), 16 KB ELF alignment, R8 and JNI, no INTERNET in a client.
  Plus the APK's **zip** alignment: an uncompressed native library is mapped straight out of the APK,
  so it must start on a 16 KB boundary in the zip. Play does that for the APKs it builds; for ours,
  AGP does it today (the dry run passed), and the check catches a build that stops. And the package name and `versionCode` read back from each APK, so a
  stale APK from an earlier build is not shipped.
- No native-symbol check: symbols travel in a bundle for Play's crash reports, and an APK has no
  such consumer.

`versionCode`: use the same number as the Play release of the same code. Android refuses to update an
installed APK to a lower code, and the two channels never update each other's copies (different
signers), so one sequence for both is simplest.

Tests: `python3 scripts/test_direct_release.py` (the checks, and the script's refusals before it
builds). **Dry run, 2026-10-31:** all ten apps built and passed every check with `--allow-unpinned`
and a throwaway key (the key was discarded; nothing was published).

### Publishing a direct release

`build/direct/` goes to the release bucket (`releases.tenshu.app`, its own R2 bucket and key:
[`deploy/cloudflare-releases/`](../../deploy/cloudflare-releases/README.md), decision D3), and the
committed `store/android-release.json` records it. The download pages are rendered from that file at
build time (the proposal's Phase 1 item 5).

```bash
scripts/android-direct-publish.sh --dry-run    # the checks and the manifest diff; no network, no writes
scripts/android-direct-publish.sh              # upload what is missing, then rewrite the manifest
scripts/android-direct-publish.sh --verify     # download every APK the manifest names and check it
```

Prerequisites, once: the bucket applied (`deploy/cloudflare-releases/tf.sh apply`, the maintainer's
call) and its own publish key in the Keychain (`r2-releases-access-key-id` / `-secret-access-key`;
the module README says how). Commit `store/android-release.json` after the run.

It refuses, before any network call:

- ⚠⚠ **`build/direct-unpinned/`**, or any path that resolves to it, and `build/direct.staging/`.
- ⚠⚠ **A signer other than the pin.** No side-channel digest pinned in
  `FirstPartySigning.SIDE_CHANNEL_SIGNING_CERT_SHA256`, or a `release.json` /
  `signing-cert-sha256.txt` that names another key, or an APK that `apksigner` reads back as signed
  by anything but the pinned key. The APK is read itself, because the file the bucket serves is what
  a fresh install trusts.
- **A directory that changed since the build**: an APK whose SHA-256 or size no longer matches
  `release.json` and `SHA256SUMS`, a missing file, an ABI other than arm64-v8a (in `release.json` or
  in the APK's `lib/`), an unknown app. ⚠ The versionCode is taken from `release.json` (the build read
  it back with `aapt2`); never hand-assemble `build/direct/`.
- **A step backwards**: a `versionCode` lower than the one the manifest offers for that app, the
  same `versionCode` with other bytes, a different signing key than the manifest's (every downloaded
  copy would have to be uninstalled), or a different host.

Then it uploads each APK to `sha256/<hex>.apk` with `Content-Type: application/vnd.android.package-archive`,
`Cache-Control: public, max-age=31536000, immutable` and `Content-Disposition: attachment;
filename="tenshu-<app>-<versionCode>.apk"`, reads each one's size and headers back, downloads each
one and checks it hashes to its key, and only then writes the manifest. ⚠⚠ Objects first, manifest
last: a page built from the manifest links every URL in it. An object already there with the wrong
headers is uploaded again (`REPAIR`); ⚠ a copy already cached as `immutable` keeps the old headers,
so purge that URL by hand in Cloudflare. A hash mismatch stops the run: someone replaced the object,
so suspect the `r2-releases-*` key.

**`store/android-release.json`, schema 1.** Read by the website's download pages; written only by
the script (`scripts/lib/direct_publish.py`, which also checks it: `python3
scripts/lib/direct_publish.py check`). Apps not in a release keep their entries, so publishing one
app leaves the other nine as they were.

```json
{
  "about": "Generated by scripts/android-direct-publish.sh — do not edit. …",
  "schema": 1,
  "base": "https://releases.tenshu.app/sha256/",
  "abi": "arm64-v8a",
  "signingCertSha256": "AB:CD:…",
  "apps": {
    "control": {
      "package": "app.tenshu.control.android",
      "versionCode": 7,
      "versionName": "1.0",
      "abi": "arm64-v8a",
      "signingCertSha256": "AB:CD:…",
      "sha256": "<64 lowercase hex>",
      "bytes": 36909875,
      "filename": "tenshu-control-7.apk",
      "url": "https://releases.tenshu.app/sha256/<sha256>.apk",
      "published": "2026-11-04"
    }
  }
}
```

| Field | Meaning |
|---|---|
| `schema` | `1`. A reader refuses any other value rather than guessing. |
| `base` | The bucket's public prefix; every `url` is `base + sha256 + ".apk"`. |
| `abi`, `signingCertSha256` (top level) | The same for every app (D2; one side-channel key signs all ten), repeated per app so an entry stands alone. The digest is in keytool's colon-separated upper-case form; `apksigner verify --print-certs` prints the same digest as lower-case hex without colons. |
| `apps` | Keyed by the short app name (`control`, `drive`, `maps`, `timeline`, `calendar`, `contacts`, `fit`, `photos`, `home`, `mia`), in that order; an app absent from the map has no download yet. |
| `versionCode`, `versionName` | From the build; the code never goes down for an app. |
| `sha256`, `bytes` | Of the APK, for the page to show beside the download (`shasum -a 256`). |
| `filename` | What the browser saves it as (the object's `Content-Disposition`). |
| `published` | UTC date this entry's bytes were first published; a republish of the same bytes keeps it. |

Tests: `python3 scripts/test_direct_publish.py` (no network: the script runs against fake `aws`,
`curl` and `apksigner`).

### The side-channel key

One key for all ten apps, ours, signing every copy that does not come from Play. Decided on
2026-10-22 (*Which key signs what*); created and pinned 2026-11-06 (steps 1 and 3). **The maintainer
holds it**:

1. `scripts/android-release.sh --init-key --direct` generates it into the Keychain (items
   `android-sidechannel-keystore` and `-password`, alias `sidechannel`) and prints its SHA-256.
2. ⚠⚠ Back it up offline before anything is signed with it. Unlike the upload key, Google cannot
   reset it: losing it means every downloaded install must be uninstalled to take an update
   ([`ops-secret-custody.md`](../proposals/ops-secret-custody.md) D1/D5).
3. Pin the digest in `FirstPartySigning.SIDE_CHANNEL_SIGNING_CERT_SHA256` **and** in the line under
   *Which key signs what*; `FirstPartySigningTest` fails while the two differ.
   Add it to `web/apex/public/.well-known/assetlinks.json` too (`AssetLinksTest` fails until you
   do) and redeploy the apex, or a downloaded Drive's invite links open the browser.
4. ⚠⚠ Ship that pin through **Play** first, to all ten apps. A Play app learns the digest only from an
   update, so the Play users who will install a downloaded app beside them must already hold it.
5. Register it for Android developer verification on all ten packages (Play Console, *Android
   developer verification*, add a key), or a website APK stops installing on certified devices once
   enforcement reaches the user's country. Done 2026-11-06. Check it any time with
   `python3 scripts/lib/check_developer_verification.py`: Google's read-only *Android Developer ID
   Status API* (API key in the Keychain, item `android-developer-id-status-api-key`, project `tenshu`),
   which must answer `REGISTERED` for every package with the pinned digest. ⚠ A key that is not
   registered answers `REGISTERED_WITH_ANOTHER_CERTIFICATE_FINGERPRINT`, not an error: the upload key
   does, because nothing installed carries it.
6. Then build with `--direct` and publish.

⚠⚠ If the key leaks, the old pin must be **replaced**, not kept beside a new one, in a Play release
to all ten apps. `hasSigningCertificate` matches any past signer in an APK's rotation lineage, which is
what keeps a rotated key working, so a kept old pin keeps admitting whatever the leaked key signs.

⚠ It is not the upload key, and must never be: Google re-signs everything it delivers, so the upload
key appears on no installed app, and making it a pinned identity would turn a resettable credential
into a permanent one.

### Universal or arm64-only

Measured 2026-10-31 on the `--direct` dry run (versionCode 900, R8 on, Stripe rail), in MB:

| App | Universal APK | Native libraries | arm64-only (estimate) |
|---|---|---|---|
| Control | 130.7 | 125.4 (ONNX Runtime, MediaPipe, SQLite) | 35.2 |
| Fit | 48.0 | 43.5 (the map renderer) | 20.0 |
| Timeline | 47.7 | 43.5 | 19.7 |
| Maps | 47.1 | 43.5 | 19.0 |
| Drive | 7.3 | 4.4 (SQLite) | 4.0 |
| Calendar | 7.1 | 4.3 | 3.9 |
| Mia | 6.7 | 4.2 | 3.5 |
| Photos | 6.4 | 4.3 | 3.2 |
| Contacts | 6.1 | 4.3 | 2.9 |
| Home | 6.1 | 4.2 | 2.9 |
| **All ten** | **313** | | **114** |

A universal APK carries four copies of every native library (arm64-v8a, armeabi-v7a, x86, x86_64);
Play strips that per device, so a Play user never pays it. The estimate is the APK minus the other
three ABIs' libraries.

**Decided (2026-10-31, D2): arm64-only.** The people who download from us run phones without Google
Play: de-Googled ROMs and Huawei devices, which are arm64. x86 and x86_64 serve emulators and
Chromebooks (which have Play), and armeabi-v7a serves 32-bit-only phones (old devices, some Android
Go). It cuts Control from 131 MB to ~35 MB and the set from 313 MB to ~114 MB (estimates; measure the
first real build). Built: `abiFilters += DIRECT_RELEASE_ABI` on the release build type when
`directDistribution` is set (root `build.gradle.kts`), and `--direct` refuses an APK that carries any
other ABI, or no native library at all (`direct_release.py abi`). `release.json` records the ABI for
the download page. Play bundles stay universal: Play splits them per device. If 32-bit demand
appears, add an armeabi-v7a APK beside it rather than going back to universal; each ABI then needs
its own `versionCode`.

### Updates outside Play

**Decided (2026-10-31, D4 in
[`android-direct-apk-distribution.md`](../proposals/android-direct-apk-distribution.md)): the direct
channel has no update mechanism.** Updating means downloading the latest APK from the download page
and installing it over the old one. Android accepts it because the signer matches; it refuses an
older `versionCode`. No self-updater, no `REQUEST_INSTALL_PACKAGES`, no F-Droid client, no update
check in the app.

House Rule 9 still moves every app across a schema bump in lockstep, so a user who updates one app
and not another hits the schema-skew fence
([`core-schema-skew-surface.md`](../proposals/core-schema-skew-surface.md) §8.5). On a downloaded copy
that fence must send them to the download page (the proposal's Phase 1 item 11; not built).

⚠ F-Droid is a **separate, later channel** with its own build (the proposal's Phase 3), not how the
website APKs update. A copy installed from the browser records the system package installer as its
installer of record, so another client updating it would ask the user once per app anyway.

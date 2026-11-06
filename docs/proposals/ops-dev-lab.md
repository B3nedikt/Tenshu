# Dev lab — an isolated cloud environment plus remotely driveable devices

**Status:** v0.4 — **DL-1 + DL-2 BUILT, on Scaleway (2026-09-14; stack reworked onto the production
composes 2026-09-23)**; ⭐ **APPLIED 2026-10-11 — the plane is live at `tenshu.xyz` (§10.10)**; the
rest design-only. ⚠ §10.4's "nothing has been applied" is history; §10.10 is what the first apply
found. ⚠⚠ §10.6 records a defect that only the rework found: as first written, this plane's
commerce could not have started at all. Runbook:
[`../how-to/deploy-dev-lab-scaleway.md`](../how-to/deploy-dev-lab-scaleway.md).

⚠ **v0.2 targeted Oracle's free tier and that was wrong within three weeks** — not because the
analysis was mistaken, but because the ground moved: the *production* plane migrated to Scaleway
while this doc sat unapplied. §1 records why the provider changed and what the
Oracle detour still buys. A second, throwaway copy of the whole
Tenshu server plane, plus Android devices an agent can drive and a human can *watch*, so work
that today ends in *"needs a maintainer's own phone"* can be exercised without touching
production. Companions: [`../how-to/move-the-relay-to-another-provider.md`](../how-to/move-the-relay-to-another-provider.md)
and the `deploy/scaleway-*` Terraform modules (the shape this copies — ⚠ the `deploy/digitalocean*`
ones it originally mirrored are now RETIRED boxes),
[`../how-to/reset-and-commerce-test.md`](../how-to/reset-and-commerce-test.md) (⭐ **the flow that
actually wants an isolated plane** — see §8's sequencing note; ⚠ *not*
[`commerce-dry-run.md`](../how-to/commerce-dry-run.md), which is local by design),
[`../testing.md`](../testing.md) §6 (the Android smoke gate this would host),
[`../tech-debt.md`](../tech-debt.md) *"CI blocked by GitHub Actions billing"* (the outage that
changes the CI recommendation below).

**Trigger (2026-08-27):** *"I would like a complete development environment as an online testing
ground which is isolated from the real one, on the Oracle Cloud free tier, ideally with remotely
usable Android and iOS devices."*

**The headline, before the detail:** two of the three halves have a good free answer and one
does not. The server plane fits the Oracle free tier comfortably. Android fits it too — but
**not** the way you would guess, because the free shape has no KVM, so the answer is a
*container*, not an emulator. **iOS has no free or cheap remote-device answer at all**; every
honest option ends at Apple hardware, and the cheapest one is a Mac you already own.

---

## 1. Why Scaleway — and what the Oracle detour was worth

**The decisive argument is not price, it is that production went there first.** Between this doc's
v0.1 and v0.3, `deploy/` grew a Scaleway module per service — relay, commerce, apex, relay-blobs,
and the two backup modules since added — and a first real deploy paid for a list of provider traps
that each fail *silently*:
cloud-init passed as a string instead of a map, security groups defaulting to ACCEPT, a required
`iops` validated only at create, SSH keys injected from the Project at first boot and never again,
a root volume that fills at 9 GB, and a zone-root record that silently becomes
`tenshu.xyz.tenshu.xyz`. A dev lab on the same provider inherits all of that for free. A dev lab
anywhere else pays for its own list.

It also makes the dev plane a **faithful** copy: same provider, same image label, same instance
family, same architecture as the production relay. A dev plane that differs from production in
its substrate can only catch the bugs the substrate does not cause.

⚠ **Two free-tier facts that killed the Oracle plan, recorded because they are not obvious.**
Always Free Ampere A1 was **halved** on 2026-06-15 — 4 OCPU / 24 GB → **2 OCPU / 12 GB**, quotas
3,000 → 1,500 OCPU-hours — with no blog post and no notification; the docs were simply edited, and
every "free 4-core 24 GB VPS" guide older than July 2026 is now wrong
([InfoQ](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)). And A1 provisioning
fails with *"Out of capacity"* in most regions most of the time, so the module needed a retry loop
to have any chance at all. €0 is a real advantage; an instance you cannot obtain is not.

⚠⚠ **What the detour still buys, and why §3 is unchanged.** The Oracle analysis turned on
`VM.Standard.A1.Flex` exposing no `/dev/kvm` — which eliminates the AVD emulator, Cuttlefish and
every emulator-in-a-container image in one line. **That conclusion survives the provider change**:
Scaleway's standard instances are ordinary KVM guests and do not expose nested virtualisation
either. Android-as-a-container is still the only option, and §3 is load-bearing rather than
historical.

**Therefore:** everything on this box is reproducible from Terraform + cloud-init and holds nothing
you would mourn. That is already the house style — every module here treats the instance as *cattle*
and keeps durable state on an attached volume — so `deploy/scaleway-dev/` is a sibling of the
production modules, not a new pattern.

---

## 2. Recommended stack

```
                        Tailscale tailnet (free Personal: 6 users, unlimited devices)
                                          │
   ┌──────────────────────────────────────┼───────────────────────────────────────┐
   │  Scaleway DEV1-M · 3 vCPU / 4 GB · Ubuntu x86_64 · 40 GB volume · own Project │
   │                                      │                                        │
   │  Caddy (TLS, public :443)            │   private, tailnet-only                │
   │   ├── relay.tenshu.xyz    → :sync-relay-server                                │
   │   ├── commerce.tenshu.xyz → :commerce-service  + Postgres  (STRIPE_MODE=fake) │
   │   ├── tenshu.xyz          → web/apex        (relay URL inlined at build time) │
   │   └── (DL-4) ws-scrcpy-web ─────────────────┬── redroid #1 (Control+Drive+…)  │
   │                                             └── redroid #2 (second "device")  │
   │  mailpit (loopback only)  ·  the whole plane resets in one command            │
   └───────────────────────────────────────────────────────────────────────────────┘

   iOS: NOT here. Apple hardware only — see §5.
```

| Layer | Choice | Why this one |
|---|---|---|
| IaC | Terraform, `scaleway/scaleway`, `deploy/scaleway-dev/` | sibling of the five production Scaleway modules, and inherits their hard-won traps (§10.3) |
| Host OS | Ubuntu, **x86_64** | matches the production relay box. A dev plane on a different architecture cannot catch an architecture-specific bug in either direction |
| Runtime | Docker Compose + Caddy | identical to production, and literally the **same files**: the box runs `sync-relay-server/docker-compose.yml` + `commerce-service/docker-compose.yml` + a dev overlay. ⚠ The "three composes would fight over :443" worry was wrong — relay's and commerce's Caddy services share the name `caddy` and MERGE (§10.5) |
| DNS | **Cloudflare, records added once by hand** | production's DNS host, free, one dashboard for both zones. ⚠ `manage_dns` (which writes to *Scaleway* DNS) stays **off** — on, with the zone elsewhere, it is a clean apply that resolves nowhere |
| Android | **redroid** containers | still the only Android that runs without nested virtualisation, which Scaleway does not expose either (§1) |
| Viewing / driving | **ws-scrcpy-web** (or **GADS**/**Sonic** if you want a farm UI) | browser tab shows the screen live; agent drives the same device over `adb` |
| Network | **Tailscale** free | `adb` and every admin UI stay off the public internet — redroid's own docs say exposing adb can compromise the *host* |
| CI | see §6 — **not** a simple "run it on the free box" |

---

## 3. Android: why redroid, and what it can and cannot prove

**The elimination is the whole argument.** No nested virtualisation on an ordinary cloud VM ⇒ the
AVD emulator is out (it needs KVM, or it is unusably slow), Cuttlefish is out (its docs require
nested virtualisation on cloud hosts), and the container images that wrap the emulator with a noVNC
UI are out with them. What is left is Android-as-a-container against the host kernel: **redroid**
(supported **Android 8.1 → 16**, **arm64 and amd64**, actively maintained) or Waydroid.

⚠ This was originally an argument about Oracle's A1 shape specifically. ~~It survives the move to
Scaleway unchanged — standard Scaleway instances are KVM guests and expose no `/dev/kvm` either.~~

⚠⚠ **Measured on the live box 2026-10-13, and the premise is false there.** The DEV1-M
(`5.15.0-190-generic`, x86_64, AMD EPYC) has `/dev/kvm`, `kvm_amd` is loaded, and the device
answers `KVM_GET_API_VERSION` (12). So the ordinary AVD emulator is *not* eliminated on this
instance type — and it is the better-trodden path (the smoke gate's own system images, no kernel
module, no privileged container). ⚠ Not proven until an emulator has booted there; nobody has
tried. The same pass answered the two questions §8 left open for redroid:

- **binder:** built as a module (`CONFIG_ANDROID_BINDER_IPC=m`, `…BINDERFS=m`) and absent from
  disk, but `linux-modules-extra-5.15.0-190-generic` is an apt candidate at the running kernel's
  exact version. An `apt install`, not DKMS. Not installed — the box is shared.
- **room:** 3.9 GB RAM with ~465 MB used by all six containers (≈2.8 GB available), 3 idle
  vCPUs, 22 GB + 35 GB of free disk. One Android instance fits; there is **no swap**, so a spike
  kills rather than slows, and a second instance does not fit.

⚠ **"The APKs carry no native libs" (§9, DL-3) is no longer true.** Control and Drive ship four
ABIs. On x86_64 Control lacks exactly one library, `libmediapipe_tasks_vision_jni.so`, so whatever
uses MediaPipe vision would fail on an x86 Android and the rest should load.

⭐ **None of this is a reason to build DL-3 now** (decided 2026-10-13). A datacentre emulator
proves little the local smoke gate does not, the flows the gate cannot cover need a real phone
either way, and a cloud session could not drive it — its sandbox speaks HTTPS only, so `adb` is out
and every option is a new remote-control surface on a shared box. Logic belongs in the JVM suites,
the dev plane is for what only a live backend shows (commerce, relay, the web client), and a
device comes last, locally.

Setup is genuinely small: `apt install linux-modules-extra-$(uname -r)`, `modprobe binder_linux
devices="binder,hwbinder,vndbinder"`, then `docker run --privileged -v ./data:/data -p 5555:5555
redroid/redroid:14.0.0_64only-latest`. ⚠ If `binder_linux` is absent from the distro kernel, the
fallback is [`redroid-modules`](https://github.com/remote-android/redroid-modules) via DKMS —
supported, but a moving part across kernel upgrades.

⭐ **x86_64 redroid is fine for this repo, and that is checkable rather than hopeful.** The obvious
worry about an amd64 host is that the APKs carry `arm64-v8a` native libraries and would need
redroid's ARM translation layer. They do not: there is **no NDK, no `externalNativeBuild`, no
`jniLibs` and not one tracked `.so`** anywhere in the build, and Android crypto is BouncyCastle —
pure JVM. libsodium enters only through a **cinterop for Apple targets** (`core-common/build.gradle.kts`).
So the APKs are architecture-independent and install on an x86_64 Android image unchanged.

**Why this fits Tenshu unusually well:** one redroid container is one Android *device*, so
Control and Drive install side by side in it and talk over **real Binder**. That is the thing no
hosted device-farm free tier gives you — Appetize and friends stream *one app*. The platform's
actual integration surface (Control as proxy, clients over AIDL, schema lockstep across installed
apps) is exactly what a single container can exercise, and `scripts/android-smoke.sh` needs
nothing but an `adb` device: point `ANDROID_SERIAL` at `127.0.0.1:5555` and it should run
unmodified.

**What it cannot prove** — state this plainly wherever the lab's output is quoted:

| Not covered | Why |
|---|---|
| GPU/render performance | `redroid_gpu_mode=guest` = SwiftShader software rendering. Compose *works*; frame rates mean nothing |
| Play Services flows | no GMS in the image (MicroG/OpenGApps needs a custom build) |
| Camera, real sensors, Bluetooth | not present |
| Metered-network / WifiOnly deferral, background drain | container networking is not a radio; the doze/background model is not a phone's |
| App Attest, real-device signing, TestFlight (`ios-drive-parity.md` P7) | iOS, and device-gated regardless |
| Timeline's classifier numbers, `IN-3m`'s inference timing | those are explicitly *"needs a phone"* and stay so |

**If you want the hardware-gated half too**, the cheap answer is not cloud: an old Android phone
on a USB hub at home, plus a Raspberry Pi or mini-PC running **GADS** or **DeviceFarmer/STF**, with
Tailscale making it reachable. GADS is the more current project (Go, real iOS *and* Android
devices, Appium execution, active); STF is the classic but its device support lags — it has
volunteer-paced updates and has not caught up to recent Android versions. That box also solves
the *"I can see stuff happening"* half for real hardware.

---

## 4. What makes it "isolated from the real one"

The interesting risk in a second environment is not that it costs money, it is that it quietly
shares something with production. The isolation contract, in the order things actually leak:

1. **Hostnames.** `dev-relay.`, `dev-commerce.`, `dev.` — never a path or a port on the
   production host. Both planes already read their own origin allow-lists
   (`RelayConfig` CORS defaults to `tenshu.app`; set them explicitly here).
2. **Keys.** A **separate** commerce entitlement issuer keypair. The relay's
   `ENTITLEMENT_ISSUER_PUBKEY` must be the dev one, so a production-signed token is worthless
   against dev and vice versa. Never copy the production seed onto this box.
3. **Stripe test mode**, and a mail sink (Mailpit) instead of a real SMTP relay — the dry-run
   runbook's ⚖️ SPF/DKIM/DMARC item does not apply to a plane whose mail never leaves the box.
4. **Storage.** Its own block volume; `deploy/reset-test-data.sh` is the reset button and should
   refuse to run against a non-dev hostname.
5. ⚠⚠ **`COMMERCE_DEV_MODE=true`, and that is forced, not chosen.** `CommerceConfig.validate()`
   refuses to start when `DEV_MODE=false` **and** `STRIPE_MODE=fake` ("a dev-only payment rail …
   illegal when DEV_MODE=false"), so item 3's fake rail and a non-dev commerce are mutually
   exclusive. What `devMode` buys in commerce is *only* the relaxation of four startup refusals —
   it enables no auth bypass at runtime, unlike the relay's `DEV_MODE`, which stays `false` here
   and is hardcoded so in the production compose. The cost is that the **empty-seed refusal goes
   with it**: the plan-time `issuer_seed` assert in `main.tf` is now the only thing between this
   plane and the committed public dev seed.
6. **Clients.** The web side is already parameterised — `VITE_TENSHU_RELAY_URL` at build time.
   Android/desktop/iOS need a build-config equivalent so a dev build cannot be pointed at
   production by accident; today the Kotlin clients carry no compiled-in production URL, which is
   in this design's favour. ⚠ Do the same for the apex *deployed* to `dev.` — a dev SPA that
   talks to the production relay is the exact failure this whole doc exists to prevent.

---

### 4.1 Isolated is not hidden — what a stranger can do here (2026-10-13)

The hostnames are public — three, and `mail.` as a fourth once `mailpit_token` is set, though that one answers only to its token — and nothing in this design hides them: the mesh of §2 is for `adb`
and admin UIs, never for the plane's own HTTPS. That is deliberate. A wall in front of the relay
and commerce has to admit every legitimate caller — the Android apps, the web app, cloud sessions,
Stripe's webhooks — and each option fails on one of them: an IP allow-list on callers with no fixed
address (and on ACME's port 80), client certificates on Stripe, a shared header on the native apps,
which would need a dev-only build setting to send it. ⭐ If this plane ever holds something that is
not throwaway, the header plus that build setting is the route.

What bounds a hostile caller is how little is here, plus the limits below:

| Attempt | Outcome |
|---|---|
| Create a usable account | Fails — the code never leaves the box, so it is never verified. ⚠ Each attempt still gets a `202` and leaves an **unverified account row** (`/register` creates the row before it sends the code); the budget below is what bounds those |
| Push data or upload content | Refused — enforcement is on (once `entitlement_issuer_pubkey` is set) and they cannot get an entitlement |
| Other owner-authenticated writes — tombstones, a recovery blob, invites | **Possible.** Entitlement gates pushes, content, shares and grants, not these; a realm minted anonymously is an owner with a valid device key. Small, and capped only by the realm budget below |
| Read data | Ciphertext, and test data |
| Flood `/register`, or `POST /sync/realm` (which needs no credential) | **Bounded** — see below |
| Flood the pairing rendezvous (`POST /sync/pairing/request`) | **Not bounded on the relay image this plane runs today.** Credential-less by design, and it held requests in memory under a caller-chosen owner id with no limit on the number of ids — production's route too. Closed in the relay (threat model T-P-15); it reaches this plane when a relay image built from that is shipped |
| Take the box down | Possible: one small instance, no swap, nothing in front. It is disposable |

⚠⚠ **The abuse budgets were OFF until 2026-10-13**, lifted on the reasoning that nothing here
sends mail. True, and beside the point: with no budget one caller could write commerce rows, inbox
messages and empty realms until the disk filled. The template now renders them **raised, not
removed**. ⚠ A template change reaches a running box only by hand (§10.11): the live plane's
`dev.env` was edited and relay + commerce recreated on 2026-10-13, and both containers were
checked to carry the new values — 300/h per
IP and 60/h per email for codes, 300/h per IP for realm bootstrap — far above what testing uses
and far below a flood. Both services key the per-IP buckets on `X-Forwarded-For`, and the front
Caddy is the edge (DNS-only records), so the address is the caller's own.

⚠ The one credential on the box worth more than the lab is the GitHub token it clones with. Keep
it read-only, on this repository alone, and short-lived.

## 5. iOS — the honest answer

There is no free remote iOS device, and there is no legal way to run iOS off Apple silicon. OCI
sells no Mac shapes, so **nothing in this plan runs iOS**. The four real options, ranked by cost:

| Option | Cost (2026-08) | What you get | Verdict |
|---|---|---|---|
| **A Mac you already own, as an always-on node** | €0 + power | full simulator + real device; `idb`/`xcrun simctl` for the agent, Appium's **MJPEG stream** (`mjpegServerPort`, ~60 fps in a browser tab) for you to watch; a self-hosted runner fixes iOS CI too | **recommended** |
| Scaleway Mac mini | from **€0.11/h**, 24 h minimum (≈ €2.70/day) | a real dedicated cloud Mac, by the day | best *rental*; use for bursts, destroy after |
| Xcode Cloud | **25 compute-hours/month free** with the Apple Developer Program | build + test only, no interactive device | good for the `ios-smoke.sh` lane, useless as a "device" |
| Appetize.io | free tier ≈ 30 min/month, **public apps only**; Starter $59/mo | one app streamed to a browser | demos only |
| Corellium | Solo $99/mo; $3/device-hour explorer tier | genuinely virtual iOS | the only true virtual iPhone, and priced like it |

Note the shape of option A: a Mac on the tailnet is simultaneously the iOS device lab **and** the
fix for `iosKotlinCheck` / `iosTestCheck` / `ios-smoke.sh` being unrunnable in a Linux container —
which CLAUDE.md already flags as making iOS changes written here *unverified*.

---

## 6. CI — the recommendation changes because CI is currently dead

⚠⚠ `docs/tech-debt.md` records that **no CI lane has run since 2026-06-30** — `android.yml`,
`ios.yml` and `web.yml` all fail in ~10 s on *"recent account payments have failed or your
spending limit needs to be increased."* So the choice is not "hosted vs self-hosted", it is
"nothing vs something".

Facts that bear on it:

- This repo is **private**, so the free allowance is **2,000 Linux minutes/month**, and macOS
  bills at **10×** — `ios.yml` on `macos-latest` spends ten minutes of quota per wall-clock
  minute. Hosted rates were cut ~39% on 2026-01-01 (Linux $0.006/min, macOS $0.062/min), so
  paid overflow is cheap in absolute terms.
- **Linux arm64 hosted runners reached private repos in Jan 2026 and are free-tier eligible** —
  worth trying before building anything self-hosted.
- **Self-hosted runners consume no minutes at all.**

⚠⚠ **But an arm64 runner cannot build the Android modules as-is.** Google ships `aapt2`, `aidl`,
`zipalign` and `split-select` for **linux-x86_64 only**, and this repo is on **AGP 9.3.2**, where
AGP no longer uses the SDK's `aapt2` but pulls `com.android.tools.build:aapt2:<agp>:linux` from
Maven — also x86_64-only. Making it work needs
[`android-arm-build-tools`](https://github.com/Commit451/android-arm-build-tools) plus
`android.aapt2FromMavenOverride` in `gradle.properties`. That is a real, documented path and also
a permanent maintenance edge on every AGP bump.

**So split build from run:**

- **Build** APKs and jars where x86_64 is free/cheap: GitHub-hosted (once billing is restored),
  or a €5/month Hetzner CX-class x86 box as a self-hosted runner if it is not.
- **Run** them on the free arm64 box: pull the APKs, install into redroid, execute
  `scripts/android-smoke.sh`. Note the APKs must carry `arm64-v8a` natives (the libsodium build)
  — true for any phone build, so this is a check, not a change.
- The `desktopTest`/`jvmTest`/`web` halves of the gate plausibly run natively on arm64 without
  aapt2 at all, but ⚠ every module applies the Android plugin, so *configuration* still needs an
  SDK — verify before relying on it.
- ⚠ **Do not size the free box for the gate.** ~1,549 actionable tasks on 2 OCPU / 12 GB, next to
  two redroid containers, is not a gate — it is a queue.

---

## 7. Cost summary

⚠ This is no longer a €0 design, and pretending otherwise was part of what went wrong with v0.2.

| Item | Monthly |
|---|---|
| Scaleway DEV1-M dev plane (3 vCPU / 4 GB) | instance list price — **read the console's estimate** |
| 40 GB block volume, 5k IOPS tier | ≈ €0.087/GB ⇒ ≈ **€3.5** |
| Scaleway DNS for a hosted zone | **€0** |
| `tenshu.xyz` | ~€10/year, already bought |
| redroid, ws-scrcpy-web, GADS/STF, Caddy, mailpit | **€0** (all OSS) |
| Tailscale (Personal: 6 users, unlimited devices) | **€0** |
| *Optional* cloud Mac for iOS bursts (Scaleway, 24 h minimum) | ≈ €2.70 **per day used** |

⚠⚠ **The instance families differ in whether the root volume is included** — the DEV1 line bundles
local storage, while PLAY2 / PRO2 / COP-ARM price compute alone and bill block storage separately.
That difference is larger than the storage line above, so the create screen's estimate is the
number to trust, not a figure quoted here. The €100 of Scaleway credits should cover this for
months rather than weeks; ⚠ check the voucher's `Incl.`/`Excl.` markers, which apply silently.

---

## 8. Phases

| Phase | Scope |
|---|---|
| **DL-1** | ✅ **Built, on Scaleway.** `deploy/scaleway-dev/` — plan-time guards, block volume, flexible IP, security group, instance, cloud-init, **and the three A records** (§10.3). Tailscale deferred; not needed until DL-3 puts `adb` on the box. |
| **DL-2** | ✅ **Built.** The `stack/` **overlay** over the two production composes — Postgres, mailpit, apex and one front Caddy on top of them, never a copy of them (§10.5) — `deploy/ship-dev-stack.sh`, and multi-arch images by both routes (§10.2) — which the amd64 decision makes insurance rather than a requirement. ⚠ The isolation *proof* — a dev-signed entitlement rejected by production and vice versa — is a runbook step, not something code can assert. |
| **DL-3** | redroid: `binder_linux` on the distro kernel (or DKMS), one **x86_64** container (§3 — the APKs carry no native libs), `adb` over the tailnet only. Install Control + Drive; confirm Binder IPC works between them. |
| **DL-4** | ws-scrcpy-web behind Caddy + tailnet auth — the "I can see it happening" surface. Second container as a second device, for pairing/sync flows that need two. |
| **DL-5** | Wire `scripts/android-smoke.sh` against the redroid serial; publish APKs from the build lane, pull + install + run on the box. Nightly. |
| **DL-6** | iOS: whichever of §5's options is chosen. If it is the owned Mac — tailnet node, self-hosted runner for `iosKotlinCheck` / `iosTestCheck` / `ios-smoke.sh --build-only`, Appium MJPEG for the watchable simulator. |

**Sequencing note (corrected 2026-09-23).** DL-1…DL-2 stand alone and are worth doing even if the
device half is never built. ⚠⚠ But **not for the reason this note used to give.** It said the plane
was what `commerce-dry-run.md`'s remaining manual pass wants; that runbook is subtitled *"walk the
whole flow once, **locally**"*, runs `scripts/local-commerce-stack.sh`, and states *"Nothing here
touches anything outward-facing."* What its manual pass needs is a real inbox, a browser, a second
device and eyes on the running app — none of which is a box in Paris. The claim survived into the
DL-1 PR description and would have justified building this plane to unblock something it does not
block.

⭐ **What does want an isolated plane** is
[`../how-to/reset-and-commerce-test.md`](../how-to/reset-and-commerce-test.md): every plane in its §0
table is a **production** hostname (`relay.tenshu.app`, `commerce.service.tenshu.app`, `tenshu.app`),
it opens *"Every step here is destructive and/or outward-facing … **Only run it against a test
instance you own**"* — and no such instance exists. That is the hole DL-1…DL-2 fill.

⚠ The other deploy-time item, commerce's still-owed **live Stripe test-mode round-trip**
(`commerce-accounts-and-sessions.md` §6), may not need a plane either: the Stripe CLI's
`stripe listen --forward-to` delivers real test-mode payloads to a local port, which is exactly what
that item asks for ("the Stripe field mapping in the webhook parser needs validating against real
payloads"). Nothing in the repo has tried it. Check that before treating this module as a
prerequisite for commerce.

DL-3…DL-5 are the part with novel risk, and their case is independent of commerce entirely.

---

## 9. Open questions

- **Q1** — does the Scaleway Ubuntu kernel carry `binder_linux` in `linux-modules-extra`, or is
  DKMS mandatory? Decides whether DL-3 is an afternoon or a recurring tax. (Provider changed; the
  question did not.)
- **Q2** — is 3 vCPU / 4 GB enough for two redroid containers *plus* the relay/commerce/apex
  stack? Almost certainly not both — DEV1-M was sized for the plane. Measure at DL-3 and expect to
  either resize or give the device lab its own box.
- **Q3** — ✅ **answered by events.** v0.2 asked whether A1 capacity was obtainable at all and noted
  that, if not, the plan relocates with no design change. Production moved to Scaleway first, so
  the dev lab followed it there — and §1 explains why that is a better answer than the one the
  question anticipated.
- **Q4** — GitHub Actions billing: restoring it is strictly cheaper than building a self-hosted
  runner. Confirm before DL-5.
- **Q5** — should the dev plane's clients be a separate Android `applicationId` suffix so a dev
  and a production Control can be installed on the same phone? Probably yes, and it interacts
  with the smoke gate's lockstep assertions.

---

## 10. As built — DL-1 + DL-2

### 10.1 The decisions, and why

| Decision | Alternative rejected |
|---|---|
| **Scaleway**, not Oracle's free tier | §1. Production went there first, so the dev lab inherits five debugged modules and a list of silent-failure traps someone else already paid for. |
| **amd64** (DEV1-M), not COP-ARM | Cheaper ARM was tempting, but a dev plane on a different architecture from production cannot catch an architecture-specific bug in either direction. ⭐ And it costs DL-3 nothing — §3's check of the build shows no native libraries at all. |
| **One combined box**, not three production-shaped modules | A third of the cost, one IP, and a reset that is one command. Fidelity is bought back by using the same provider, image and instance family as production. |
| **A separate Scaleway Project** | Free, and the cheapest isolation in the design: a `destroy` aimed at the dev lab cannot reach the production relay even from the wrong state file. |
| **A separate registrable domain** (`tenshu.xyz`) | A `dev-` prefix under the production zone is one typo from a client that silently reaches production. `main.tf` now refuses it at plan time. |
| **`docker save \| ssh docker load`**, with GHCR as the other route | The workflows publish, so pulling works now. `--ship` stays the faster loop and leaves the previous image on the box, so rollback is a tag flip. |
| **`deploy/oracle-dev/` deleted** | It was never applied. Two modules claiming to deploy the same thing, one of them unverified, is worse than git history. |

### 10.2 Why multi-arch images were still worth having

Every Dockerfile already had a **`prebuilt`** target — added when CI died so an arm Mac could ship
images without a 45-minute QEMU Gradle run. That target is the whole answer to multi-arch: a fat jar
is Java bytecode and a Vite `dist/` is static text, so **neither has an architecture**; only the base
layer does. Build the artefact natively, assemble an image whose only instructions are `COPY` and
metadata, and the other architecture emulates nothing.

The amd64 decision means the dev lab does not *need* this — but the work stands and costs nothing:
`PLATFORM=linux/arm64` on the three build scripts, and `target: prebuilt` +
`linux/amd64,linux/arm64` in the three image workflows. ⚠⚠ For apex that **moved the Vite env vars
out of the Dockerfile**: `prebuilt` has no `RUN`, so a `build-args:` entry is silently ignored and
the SPA would ship pointing at the wrong relay.

### 10.3 The Scaleway traps, inherited rather than rediscovered

Each of these fails **silently** — clean apply, box serving nothing — and each came out of the first
real deploy of `deploy/scaleway-relay`:

1. ⚠⚠ **`user_data` is a map**, keyed `cloud-init`. A bare string does not error; nothing installs.
2. **Security groups default to ACCEPT** — `inbound_default_policy = "drop"` is the whole firewall.
3. ⚠⚠ **SSH keys come from the Project, at first boot only.** No resource argument exists. Register
   before `apply` or recover via rebuild/VNC. (The production modules once had an `ssh_key_ids`
   variable that looked like the knob and granted nothing; it is gone.)
4. **`iops` is required** and tier-dependent — rejected at create, not at plan.
5. **Instance and volume must share a zone.**
6. **The root volume fills**: 30 GB here against the relay module's 20, because this box extracts
   six images to its one. The relay's 9 GB root hit 100 % on 2026-09-13.
7. ⚠⚠ **`""` is the zone root.** `trimsuffix("tenshu.xyz", ".tenshu.xyz")` returns its input, so a
   naive derivation creates `tenshu.xyz.tenshu.xyz` — invisible except as "the apex does not
   resolve". The production modules' zone-level DNS hit this; this one spells it correctly.
8. ⚠ The *preferred Project* trap from `deploy/models/` is **S3-only** and does not apply here —
   worth stating, because the obvious move is to apply a warning where it does not belong.

Two things this module does **differently** from its production siblings, both deliberate:

- `manage_dns` defaults **off**, and the zone is on **Cloudflare** — production's DNS host
  (2026-09-24). ⚠⚠ The variable creates records in *Scaleway* DNS, so leaving it on with the zone
  served elsewhere is a silent failure: clean apply, records in a zone nobody queries, nothing
  resolves. ⭐ Three A records added once in the dashboard is the whole job, because
  `scaleway_instance_ip.dev` is a standalone resource the server *references* — rebuilding the
  instance keeps the address. ⚠ It still does not use production's inventory + adapter split
  (`deploy/dns/records.json` → `deploy/cloudflare-dns`): that earns its keep for a zone serving mail
  with a rollback story, not for three static records. Reusing that module here was rejected — its
  checks hard-require MX + SPF + DKIM + DMARC + `commerce.service` and it carries `prevent_destroy`,
  so pointing it at the dev zone means loosening a production guard. Grow records worth keeping and
  it belongs in the inventory instead.
- `user_data` is **not** pinned with `ignore_changes`. ⚠⚠ That does not make a template edit a
  rebuild, as this bullet used to say — the provider updates it in place (§10.11).

⭐ **One trap this module dodged by construction, confirmed the hard way three hours after it was
written.** The first Scaleway commerce boot found that `deploy/scaleway-commerce`'s cloud-init still read DigitalOcean's
`/dev/disk/by-id/scsi-0DO_Volume_*` path: on Scaleway `readlink -f` still prints that missing path,
so `VOL_DEV` was never empty, the working fallback never ran, and `mkfs.ext4 -F` against a
nonexistent device killed the bootstrap under `set -e` before the clone. This module took
`scaleway-relay`'s root-disk derivation instead, which is the fix that landed there.

### 10.4 What is NOT verified

⚠⚠ **Nothing here has been applied.** The Oracle module was never run either, and this one inherits
that status — what changed is that its provider and its traps are now the ones a real deploy in this
repo already exercised, which is a much better place to start from than a provider nobody here has
touched.

Checked: the cloud-init renders under `templatefile` and parses as YAML, all three embedded scripts
pass `bash -n`, the HCL is `fmt`-clean, and the no-native-libs claim in §3 was verified against the
build rather than assumed. ⭐ Since §10.5 the compose check is a real one: the three files are merged
with `docker compose config` **using the env file Terraform actually renders**, and 25 assertions are
made against the result — one Caddyfile mount and it is the dev one, the three port bindings appear
once each, `caddy` lands on all three networks, commerce receives `DEV_MODE=true` +
`STRIPE_MODE=fake` + the Postgres URL + mailpit, relay keeps `DEV_MODE=false`, every bind volume
points into `/mnt/tenshu`, and apex publishes nothing.

⭐ **`terraform init` + `terraform validate` now pass** against the real provider
(`scaleway/scaleway` 2.83.1; lock file committed, like the relay-blobs module's) — the registry was unreachable from the
authoring container, not in general. A full `terraform plan` also succeeds **with placeholder
credentials**: 4 resources with `manage_dns = false`, 7 with it on, because the provider makes no
API call for a pure-create plan.

Not checked: that `ubuntu_jammy` is the right label, that `DEV1-M` exists in `fr-par-1`, that the
volume attaches, that the `iops` value is accepted, that the Project exists, or that cloud-init
succeeds. ⚠⚠ **And a plan does not test those** — it passes `type`, `image` and `iops` through
verbatim without resolving any of them, so this section's earlier claim that *"`terraform plan` is
the cheap test of the rest"* was wrong: they are apply-time, credentials or not. What a plan *does*
now test is the input guards (§10.7). ⚠ Nor has any container been **started** from the merged
config — `config` proves the shape compose computes, not that the relay and commerce images like
what they are handed. §10.6 is a reminder of the difference: a config that merges perfectly still
described a commerce that refuses to boot. The first `apply` is the real test. Expect to fix
something.

### 10.5 The stack is the production composes, not a copy of them

DL-1 first shipped a 183-line `stack/docker-compose.yml` that re-declared relay, commerce and apex.
Its header argued the fork was necessary: *"each of those publishes its OWN Caddy on :80/:443, so
merging them produces three containers fighting over the same ports."* **That is not what compose
does**, and the difference is worth writing down because the reasoning looked sound.

The relay's Caddy and commerce's Caddy are both named `caddy`. Across `-f` files compose merges
services **by name**, so the two become ONE: environments unioned, `networks` unioned, `depends_on`
unioned, and identical port bindings de-duplicated rather than repeated. The box now runs

```
docker compose -p tenshu-dev --project-directory /opt/tenshu/repo \
  -f sync-relay-server/docker-compose.yml \
  -f commerce-service/docker-compose.yml \
  -f deploy/scaleway-dev/stack/dev-overlay.yml --env-file /opt/tenshu/dev.env
```

wrapped as `/opt/tenshu/compose`, which is the only place that invocation is spelled.

**Why it matters more than 180 lines.** `relay:`'s ~25 environment entries and `commerce:`'s ~30 are
the plane's real configuration surface. A copy of them is correct exactly until production gains a
variable, and then it is wrong *silently* — the dev plane keeps booting with the old shape. The commerce-boot failure (§10.3) is
the precedent in this very module family: `scaleway-commerce`'s cloud-init was a copy of its
sibling's and still carried DigitalOcean's volume path. ⭐ The overlay cannot drift that way, because
there is nothing in it to drift.

**The four things that do not merge**, all verified with `docker compose config` rather than assumed:

1. ⚠⚠ **Relative paths resolve against the PROJECT DIRECTORY**, not against the file that spells
   them. `./Caddyfile` in the relay's compose and `./Caddyfile` in the overlay are the *same* path.
   Hence `--project-directory` plus a repo-root-relative path in the overlay; a later file's mount
   to the same container path then replaces the earlier one.
2. ⚠ **`-p tenshu-dev` is not cosmetic.** Without it the project name comes from the first `-f`
   file's directory and every container is named `sync-relay-server-*`.
3. ⚠⚠ **`--no-deps` on the certificates-first step.** The merged `caddy` inherits `depends_on`
   relay + commerce from the production files, so without it the "start Caddy before the app images
   land" step drags in the two images whose absence is the whole reason that step exists.
4. **apex is declared in the overlay, not merged in.** `web/apex/docker-compose.yml` publishes
   :80/:443 from the apex container itself — in production that container *is* the edge. Compose
   **appends** port lists across files and offers no way to remove an entry, so including that file
   would genuinely collide. Its body is three lines, so nothing is lost.

⚠⚠ And one trap that the fork was hiding: **`STRIPE_MODE`, `WEB_APP_ORIGIN` and `PUBLIC_BASE_URL`
are real `CommerceConfig` keys that `commerce-service/docker-compose.yml` did not declare** (it does
since 2026-10-30, see below). An
`--env-file` feeds compose-file *substitution*; a container only ever receives what an
`environment:` block names. Spelled in `dev.env` alone they reached nothing — which is the same
failure the relay's compose documents at its `S3_*` block, and which burned a migration window on
2026-09-13. The overlay declares all three. ⚠ Production carried the same gap, for 19 keys rather than 3: `WEB_APP_ORIGIN` among them, so
production commerce refused every browser call. Fixed 2026-10-30: the production compose now declares
every key `CommerceConfig` reads, and `:commerce-service`'s `ComposeEnvPassthroughTest` guards it. The
overlay still sets the dev values.

### 10.6 A defect the merge surfaced: the dev plane could not have booted commerce

⚠⚠ `CommerceConfig.validate()` contains

> `if (!devMode && stripeFakeRail) → "Refusing to start: STRIPE_MODE=fake is a dev-only payment rail
> and is illegal when DEV_MODE=false"`

and DL-1 set **both** `DEV_MODE=false` and `STRIPE_MODE=fake`, in the compose file and again in
`dev.env`. §4's isolation contract asked for both in the same breath. They are mutually exclusive by
an explicit guard, so commerce would have crash-looped on the first boot — a failure that only an
`apply` could have revealed, and `apply` is exactly what §10.4 says has never happened.

The plane now runs `COMMERCE_DEV_MODE=true`. ⚠ In commerce, `devMode` relaxes four *startup
refusals* and does nothing else — the committed dev seed becomes legal, the fake rail becomes legal,
SMTP becomes optional, an admin token may skip TOTP — and it gates no runtime behaviour. That is the
opposite of the relay's `DEV_MODE`, which switches on the `X-Dev-Owner` auth bypass; the relay's
stays `false` and is hardcoded so in its compose. This plane supplies its own real `ISSUER_SEED` and
a real SMTP transport (mailpit) and sets no `ADMIN_TOKEN`, so three of the four relaxations are
unused.

⚠⚠ The fourth is the one to hold on to: with `devMode` on, an **empty** `ISSUER_SEED` no longer
refuses to start — it falls back to `DEV_ISSUER_SEED`, whose private half is committed. The
`issuer_seed` assert in `main.tf` was a convenience before and is now the only guard, and it can
only fire at plan time. ⚠ Anyone hand-editing `/opt/tenshu/dev.env` on the box is past it.

### 10.7 The plan-time guards were warnings

⚠⚠ **`main.tf`'s guard block was a `check` block, and a `check` block cannot refuse anything.**
Terraform emits a **Warning** for a failed `check` assertion and lets the plan succeed; the
construct is for post-apply monitoring, not for rejecting input. Measured on this module
(2026-09-23), before the fix — each of the seven guards was fed exactly the input it exists to
reject, and **every one passed**:

```
dev_domain = dev.tenshu.app (a subdomain of production)  ⚠ PASSED
dev_domain = tenshu.app     (IS production)              ⚠ PASSED
issuer_seed = ""                                         ⚠ PASSED
postgres_password = ""                                   ⚠ PASSED
acme_email = x@example.com                               ⚠ PASSED
git_repo_url = ""                                        ⚠ PASSED
git_token = ""                                           ⚠ PASSED
```

With `dev_domain=dev.tenshu.app` and an empty seed the output was two `Warning: Check block
assertion failed` lines, then `Plan: 4 to add`, **exit code 0**. An `apply` would have built a dev
plane inside the production zone signing with the committed public dev seed — §4's isolation
contract and §10.6's seed guard, both defeated by the construct meant to enforce them. The block's
own comment read *"Fail at PLAN time, not forty lines into a boot log you can only read once SSH
works"*; it failed nothing.

They are now `lifecycle { precondition }` on the instance, which is an **Error**: the plan aborts,
nothing is created, exit 1. Verified the same way — all seven refuse, each citing its own condition,
and valid inputs still plan (`exit 0, Plan: 4 to add`). ⭐ Side by side on a throwaway module, one
condition, one input:

| construct | bad input | good input |
|---|---|---|
| `check` | `Warning`, **exit 0**, plans | exit 0 |
| `lifecycle { precondition }` | `Error: Resource precondition failed`, **exit 1**, nothing planned | exit 0, plans |

⚠ They sit on the instance rather than in `variable ... validation` blocks because a validation that
reads a **second** variable needs Terraform ≥ 1.9 while `versions.tf` allows ≥ 1.6.0; preconditions
have worked since 1.2. The instance is also the resource whose cloud-init consumes every guarded
variable.

⚠⚠ **This is not only the dev lab.** `deploy/scaleway-relay`, `deploy/scaleway-commerce` and
`deploy/scaleway-apex` all guard with `check` blocks, so the *"plan-time guards for every way
the first Scaleway deploy failed"* are warnings across the whole Scaleway tree.
`deploy/cloudflare-dns` and `deploy/cloudflare-tiles` get it right with `precondition` /
`validation`. Their stakes are lower — an empty git token or an `example.com` address fails visibly
soon after boot — but the pattern is identical; tracked in `tech-debt.md`.

### 10.8 Agent access: certificates now, a self-hosted mesh at DL-3

The dev lab exists to be driven by agents, including cloud sessions, which makes "how does something
that is not the maintainer's laptop get a shell" a design question rather than an operational detail.

⚠⚠ **The direction of risk is easy to get backwards.** Registering a *public* key on the dev Project
exposes nothing about production — a box only ever holds public halves. The hazard is a key whose
**private** half is copied into a cloud sandbox and which is *also* registered on the production
Project. So: the maintainer's own key stays theirs, and agents get **OpenSSH certificates**
(`ssh_ca_public_key`, §3b of the runbook) — short-lived, scoped to a principal, and expiring on
their own, so a session that ends leaves nothing to revoke.

⚠ **`ssh_allowed_cidrs` is not an alternative.** A cloud session's egress address is neither stable
nor knowable in advance, so narrowing it cannot admit an agent. Certificates are the lever that
exists.

⭐ **Why not Tailscale, which §2 already names as this design's network layer.** Because DL-1 has one
node, and a mesh solves a problem that does not exist yet. It also brings a dependency worth being
deliberate about: the Tailscale *client* is BSD-3, but the **coordination server is proprietary**,
and it is the component that decides which nodes may join. That is a fine trade for a dev plane and
a poor one anywhere near production — the same reasoning `sync-relay-server/Caddyfile` applies to
putting a CDN in the TLS path. ⚠ Certificates need no coordination server at all.

⚠⚠ **Certificates do not reach a cloud session — measured 2026-10-12, not assumed.** §10.8 was
written on the premise that a cloud agent could open port 22 and lacked only a credential. Its
sandbox lets out HTTPS alone, through a proxy with an allowlist: before `tenshu.xyz` was allowed
every request died at the proxy (`CONNECT tunnel failed, response 403`), and port 22 timed out
either way. The certificate path is proven and useful from anywhere with ordinary egress; for a
cloud session the lever is **HTTPS endpoints**, and the first one it needs is the inbox —
`mailpit_token` publishes three read-only routes of mailpit's API at `mail.<dev_domain>`
(runbook §10). Logs, stack status and shipping stay behind SSH on purpose: each would be a new
write path into a box that other sessions share.

The mesh earns its place at **DL-3**, when `adb` and a watchable UI must come off the public
internet and there is a second node. At that point prefer a **self-hosted control plane** —
Headscale (BSD-3, official clients) or Nebula (MIT, self-hosted lighthouse) — over a hosted service.
⚠ Verify current licensing before committing; this note was written 2026-09-24.

⚠⚠ **This says nothing about production, deliberately.** All three production Scaleway modules
expose `:22` to `0.0.0.0/0` with no CA and no sshd hardening; that is a real gap and it is tracked in
`tech-debt.md` rather than folded into the dev lab, because a change to production SSH is its own
decision with its own lock-out risk.

### 10.9 Re-checked against master, 2026-10-10 — and the runbook's own password broke commerce

The branch sat for sixteen days and 146 commits. The stack is an overlay over the production
composes (§10.5), so that is exactly the kind of drift it inherits silently; §10.4's compose check
was re-run on the merged tree, against the env file Terraform renders. It still holds. What master
added underneath:

- **`CONTENT_SWEEP_MODE` defaults to `armed`** in the relay's compose (CR-2 B5/B6). ⚠ The dev relay
  therefore *frees* content unreferenced for the grace window, like production. That is the intent
  — same code path — but it means a dev relay is not a place where deleted bytes linger for
  inspection. Set `CONTENT_SWEEP_MODE=dry_run` in `dev.env` when a test needs them to.
- `RELAY_OPERATOR_TOKEN` is forwarded and empty, so the operator reclaim routes do not exist here
  until someone sets it.

⚠⚠ **The password the runbook told you to mint could not have connected.** §6 generates
`postgres_password` with `base64`, whose alphabet includes `+`, `/` and `=`; `dev.env` spliced it
raw into `DATABASE_URL`'s query, and pgjdbc percent-decodes that — `+` becomes a space. Postgres
would have been initialised with one password and commerce would have presented another: a restart
loop on about two first applies in five — the odds of a `+` among 32 base64 characters. The URL
copy is now `urlencode`d in `main.tf`; the `POSTGRES_PASSWORD` copy stays raw, and a precondition
refuses the characters a compose env file does not pass through verbatim (`$`, `#`, quotes,
backslash, whitespace). Verified on a plan with `ab+cd/ef==`: the `db` service receives it as
written, commerce receives `ab%2Bcd%2Fef%3D%3D`.

Also closed, both in `ship-dev-stack.sh`: an unknown `--only` name built nothing and still cut over
and printed *Shipped*; and the dirty-tree refusal missed untracked files, which are in the build
context too.

Still true: **nothing has been applied** (§10.4), and no container has been started from the merged
config.

### 10.10 The first apply, 2026-10-11 — what it found

The plane is live: `relay.tenshu.xyz`, `commerce.tenshu.xyz`, `tenshu.xyz` on one `DEV1-M` in a
separate Scaleway Project, DNS on Cloudflare, enforcement on. §10.4 said *"expect to fix
something"*. Four things:

1. ⚠⚠ **The box boots from `git_ref`, which defaults to `master` — and the overlay it needs is not
   on `master` until this PR merges.** Cloud-init finished clean and the stack never started
   (`dev-overlay.yml: no such file`). The box was put on the PR branch by hand; ⚠ a rebuild before
   the merge repeats it. Nothing in §10.4's checks could see this: every one ran against a tree
   that had the file.
2. ⚠⚠ **The first apex image talked to PRODUCTION commerce.** `VITE_TENSHU_COMMERCE_URL` is inlined
   at build time with production as its fallback, and `build-apex-image.sh` set only the relay URL
   — so the bundle carried `relay.tenshu.xyz` beside `commerce.service.tenshu.app`. §4's isolation
   contract was about seeds and hostnames on the box; this hole was in the *artefact*. Fixed in the
   build script and re-shipped. ⚠ The bundle still contains the string `relay.tenshu.app` (the SPA's
   and the JS SDK's fallbacks); whether any path reaches them is unverified until a browser session
   is watched.
3. ⚠ **Cloudflare creates records proxied by default**, and all three were. The runbook's warning
   is right and easy to miss in the dashboard; check with `dig` that the answer is the box's own
   address.
4. ⚠ **The git token is part of cloud-init**, so rotating it in the Keychain reaches the box only
   on a rebuild — and a rebuild has to be asked for (§10.11; this item first claimed the apply
   would replace the instance, which it does not). Rotate it on the box by hand.

**The isolation proof, and its limit.** A real token was minted through the dev plane's own flow
(register → OTP from mailpit → verify → `/commerce/entitlement/token`) and handed to the relay's
own `RelayEntitlementVerifier`, built once with the dev issuer key and once with the key read off
the **live** production relay:

| verifier key | result |
|---|---|
| dev (`N3wP7kIU…`) | `Valid`, tier `tenshu.drive.free` |
| production (`nbzQjcsp…`) | `Invalid(SignatureMismatch)` |

⚠ That is the dev→production direction, on the exact code path, but **not over HTTP**: presenting
it to the production relay needs a device registered there. ⚠ The production→dev direction was
**not** run — no production-minted token was at hand, and minting one means a real account. It
rests on the two keys being different, which is observed, not on a rejected request.

### 10.11 The first rebuild, 2026-10-12 — a template change is not a rebuild

After DL-1 + DL-2 merged the box was rebuilt so that it boots from `master`. ⚠⚠ **The plan for the
changed cloud-init was `0 to add, 1 to change`** — an in-place `user_data` update, where §10.3, the
runbook, `main.tf` and §10.10 all said *"plans a rebuild"*. Nobody had run it. Applied as planned it
would have reported success and left the box exactly as it was, since cloud-init runs once per
instance. The rebuild is `-replace=scaleway_instance_server.dev`: one instance destroyed and
created, IP and volume untouched.

What a rebuild keeps and loses, observed:

| survives (data volume / separate resource) | lost (root disk) |
|---|---|
| the address, so DNS | the three shipped images — re-ship |
| commerce's Postgres (the account from §10.10 was still there) | `dev.env` — re-rendered, so **enforcement was off again** (fixed below) |
| the relay's SQLite + blobs | the SSH host key |
| Caddy's certificates — no new ACME order | |

⚠⚠ **Enforcement resetting was the dangerous one, because it is silent**: the relay is healthy,
sync works, and tier or quota tests pass for the wrong reason. Closed the same day — the issuer key
is a pure function of `issuer_seed`, so the module takes the public half as
`entitlement_issuer_pubkey` and renders `ENTITLEMENT_ENFORCE=true` with it. Empty keeps the old
behaviour, which a brand-new plane still needs for its first apply. ⚠ Terraform cannot derive an
Ed25519 public key from a raw seed without contortions, hence a second input rather than a computed
one; a seed rotated without it fails loudly (every token refused). ⚠ Rendered-and-merged, not yet
exercised by a rebuild.

⭐ Pin the image tags in `terraform.tfvars` to a sha that is **not in GHCR** before a rebuild. With
`latest`, first boot pulls the public relay and apex images — and the public apex has production's
URLs inlined (§10.10 item 2). It did not start, but only because commerce's failed pull aborts the
whole `up`.

### 10.12 No client trusted the plane's issuer — closed with a compile-time variable (2026-10-12)

§4 made a fresh issuer seed the isolation property and nobody followed it to the client: the trust
set is compiled in, and it knew production's key and the committed dev seed's, both under
`tenshu.commerce`. A record signed here, under `tenshu.commerce.dev`, is unverifiable — and since
`hasStorage` is a fail-closed sync gate derived from the locally verified record, a first device
would have paired and then never synced. Found by reading, before any client was pointed here.

Closed by `TENSHU_EXTRA_ISSUER` — one more issuer, supplied at build time, additive and never in
force in a release binary. The decision and its guards live in
[`entitlement-trust-anchor-distribution.md`](entitlement-trust-anchor-distribution.md) D3a;
`ship-dev-stack.sh` reads the issuer off the box for the web bundle, and the runbook's §10 has the
client side.

⚠⚠ **A desktop client cannot yet run beside a real Control on the same Mac.** The data and socket
directories can be redirected, but the Keychain item that wraps the device's secrets is named after
the package name, which is fixed — a dev instance shares it, and a reset inside the dev instance
deletes it for the real one. Until that name follows the data directory, desktop testing against
this plane wants another machine or account.

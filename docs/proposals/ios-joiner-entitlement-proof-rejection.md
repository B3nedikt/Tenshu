# iOS-joiner `entitlement_invalid` — retire the `devBindFreeAccount` crutch

**Status: bug root-caused on-device 2026-07-18; fix identified (iOS cleanup), no code yet.**

## Symptom

Under `ENTITLEMENT_ENFORCE=true`, a paired **iPhone** joiner's `/sync/push` is rejected
`402 {"error":"entitlement_invalid"}` on every write → **one-way sync** (reads are ungated,
so inbound files arrive; outbound writes never land). Desktop (account holder) and the
Android joiner do **not** hit this — it is iOS-specific.

## Root cause (proven)

The captured `X-Entitlement-Proof` the iPhone sends decodes to a record with
**`publisherId: "tenshu.dev"`** (the dev publisher), `ownerId` matching the device, a current
window, and a signature that verifies against `DEV_PUBLIC_KEY` (`ed.verify(publicKeyFor(
"tenshu.dev"), message, sig)` = true; `commerce` = false). The relay's
`RelayEntitlementVerifier.signedRecordProofVerifier` (`…/auth/RelayEntitlementVerifier.kt:185`)
verifies against the **single configured** `ENTITLEMENT_ISSUER_PUBKEY` (= `COMMERCE_PUBLIC_KEY`),
so a `tenshu.dev`-signed proof fails — confirmed by running the relay's own `verifyProof` on the
captured header (→ `Invalid`).

**Why the iPhone presents a `tenshu.dev` record at all — the iOS-only crutch:**

1. `ControlSurface.devBindFreeAccount()` (`tenshu-sdk-ios/…/ControlSurface.kt:365`) binds a
   **mock Free account** so `hasStorage=true` and the sync gate opens. It is called only from
   iOS **debug auto-complete / sim-probe routes** (`ControlIosApp.swift:442/499/758`) — *not*
   the real onboarding (`OnboardingView.openControl → provisionDriveSession` never calls it; `provisionDriveSession` was deleted 2026-11-05 with the pre-route-1 EPC handoff (cleanup D), and its successor `ensureDriveProvisioned` doesn't call it either),
   and **Android/Desktop have no equivalent** (their `MockPublisherClient.account` starts empty
   via `loadAccount()` until a real mock-signup).
2. That bound account **persists** through the publisher storage delegate. The device-state
   wipe (`ControlSurface.wipeDeviceState`) purges only the 10 custody slots (`DEVICE_ID`,
   `OWNER_ID`, `JOINER_PAYLOAD`, …) — **not** the mock publisher account. So a stale mock Free
   account from an earlier debug run survives a wipe, `loadAccount()` reloads it, and
   `initEntitlementWriter(accountFlow = publisherClient.account, …)` **mints a `tenshu.dev`
   record for the newly-adopted `ownerId`.**
3. That local record **shadows the desktop's real `tenshu.commerce` record** (same `ownerId`
   key) — the one that *would* match the relay's configured key — so the iPhone presents the
   dev-mock proof and the relay rejects it.

Not a Native/crypto/serialization bug: the sign/verify + `message()` contract is green on
iOS-Native (`EntitlementSignatureTest` relocated `jvmTest → commonTest`). Not
the proof-path metering (only added quota). Not the real iOS `CommercePublisherClient` wiring — which, importantly, is what makes the crutch obsolete
(see below).

## Fix (iOS cleanup — the primary path)

`devBindFreeAccount` existed to open the sync gate on iOS **before a real commerce client
existed**. **The real iOS `CommercePublisherClient` is now wired**, so the crutch has
served its purpose, and Android/Desktop already demonstrate that a joiner needs no local
account at all.

1. **Retire `devBindFreeAccount` and its debug-route callers.** Remove the iOS-only auto-bind
   of a mock Free account. A real iOS build then acquires storage exactly like Android/Desktop
   (real commerce account, or none on a pure joiner).
2. **Make `wipeDeviceState` also clear the mock publisher account** (the publisher storage
   delegate's account slot), so a re-join starts genuinely fresh. This is arguably a wipe bug
   independent of everything else — a "fresh genesis" that leaves a stale account behind is
   surprising.

With both, the iOS joiner behaves like Android/Desktop: **no local mint → uses the desktop's
synced `tenshu.commerce` record → the proof verifies against the relay's commerce key → `200`.**
No relay change required.

## Optional — relay multi-issuer (`devMode` only)

Independent of the fix above: a *pure all-dev-seed* test matrix (every device signs with the
dev key, relay configured with the dev key) still can't mix dev + commerce records under the
single-issuer verifier. If that matrix is wanted, dispatch proof verification on
`publicKeyFor(record.publisherId)` **only when `devMode=true`** (or when the configured issuer
*is* the dev key). **Never unconditionally in prod** — the dev key is committed and public, so
accepting `tenshu.dev`-signed entitlements on an enforcing prod relay is a forgery vector
(anyone could mint an entitlement for any `ownerId`). Threat-model amendment (A29/A30, §8.15)
required if pursued. This is a nice-to-have for dev ergonomics, **not** part of the correctness
fix — the single-configured-issuer verify is the correct prod posture.

## Repro / debugging aids

- The full proof was captured with the `TENSHU-DBG` probes on
  `debug/pairing-logcat-instrumentation` (`push402` full-proof + body, `proofHeader`,
  `refreshFigures`), via `xcrun devicectl device process launch --console … app.tenshu.drive.ios`.
- `deploy/flip-enforce.sh` (repo-root helper) toggles `ENTITLEMENT_ENFORCE` on the relay.
- The captured proof's signature verifies `dev=true, commerce=false`; the relay's own
  `verifyProof(header)` returns `Invalid` — both reproducible as JVM unit checks.
- ⚠ `strings` cannot find Kotlin/Native string literals — verify instrumentation by running,
  not grepping the binary; and force a clean framework rebuild
  (`assembleTenshuSdkDebugXCFramework --rerun-tasks --no-build-cache`) or `ios-smoke.sh` may
  link a stale cached framework.

# `:commerce-service` Phase 1 — build spec (whitelist-bootstrap issuer)

**Status:** Implementation plan, no code (2026-06-14). Executes
[`publishing-commerce-reconciliation.md`](publishing-commerce-reconciliation.md) §10.2 (Phase 1)
against the as-built baseline in its §0.1. Scope = stand up the first real server-side
entitlement **issuer** and lift minting off the in-process `tenshu.dev` mock — **nothing
else**. No relay change, no UI change, no payment, no bearer token, no threat-model amendment
(those are Phases 3–5; House Rule #2 bites at Phase 4, not here).

**One-PR unit.** Reviewable in isolation: a new JVM module + one additive edit to a built file.

---

## 0. What this phase does and does not touch

**Adds:** a new `:commerce-service` Ktor/Netty module (the relay's shape, separate trust
boundary) that owns a real issuer keypair, holds an email whitelist, and exposes a single
`POST /commerce/entitlement/mint` route. A whitelisted email → an instantly minted top-tier
`Entitlement`, signed with the commerce issuer key, returned to the caller.

**Edits (additive only):** `EntitlementSignature.publicKeyFor` gains the commerce issuer's
`publisherId` + pinned public key *alongside* `tenshu.dev` — so the existing on-device
verify-on-merge accepts service-minted records unchanged.

**Does NOT touch** (the built §10.1 convergence/gating path — must keep working):
`identity-service/.../Entitlement.kt` schema, `EntitlementStorageInitialization.kt`,
`StorageSyncGate.kt`, the `storage-service*` modules, `PublisherClient`. Control still
self-signs with `tenshu.dev` after this phase — swapping the on-device minter for the service
is **Phase 2 (§10.3)**, a separate PR.

---

## 1. Module layout

New module `:commerce-service`, pure `kotlin("jvm")` (it is a server, like `:sync-relay-server`
— *not* a feature service, so no `-common`/`-client`/`-proxy` split).

`settings.gradle.kts` — add after line 78 (`include(":sync-relay-server")`):

```kotlin
include(":commerce-service")
```

`commerce-service/build.gradle.kts` — mirror `sync-relay-server/build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.ktor.plugin") version "3.4.3"
    alias(libs.plugins.kotlinx.serialization)
}

dependencies {
    implementation(project(":core-common"))            // Ed25519Backend, BouncyCastleEd25519Backend
    implementation(project(":identity-service"))        // EntitlementSignature.message(...) + publicKeyFor
    implementation(project(":identity-service-common")) // EntitlementDto (wire response)

    implementation("io.ktor:ktor-server-core-jvm:3.4.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.4.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.4.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.4.3")
    implementation("io.ktor:ktor-server-call-logging-jvm:3.4.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.4.3")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation(libs.kotlin.test)
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.4.3")
}

application { mainClass.set("org.tenshu.commerce.ApplicationKt") }
ktor { fatJar { archiveFileName.set("commerce-service-all.jar") } }
```

> **Dependency check:** confirm a `kotlin("jvm")` module can consume `:identity-service`
> (a KMP module) — Gradle should resolve its JVM variant. We only call the *pure* surface
> (`EntitlementSignature.message(...)`, the constants, `publicKeyFor`), not `fieldsOf` (which
> drags `Entity`/`Composite`). If pulling the full KMP module proves heavy, the fallback is to
> move the pure `message(...)` + key registry into `:identity-service-common` — but try the
> direct dependency first; do not move code unless the build forces it.

Package root: `org.tenshu.commerce`.

---

## 2. Files

### 2.1 `CommerceConfig.kt` — env-driven config (mirror `RelayConfig`)

`fromEnv()` reads:

| Env | Meaning | Dev default |
|---|---|---|
| `PORT` / `BIND_HOST` | bind | `8081` / `127.0.0.1` |
| `ISSUER_ID` | the commerce `publisherId`, e.g. `tenshu.commerce` | `tenshu.commerce` |
| `ISSUER_SEED` | 32-byte Ed25519 seed (base64), the **private** signing key (A29) | dev-only test seed |
| `WHITELIST` | comma-separated emails (or `@`-path to a file) | empty |
| `DEV_MODE` | gates the dev seed + loopback assumptions | `true` on loopback |
| `TOP_TIER_PRODUCT_ID` | product id minted for whitelist accounts, e.g. `tenshu.drive.pro` | — |
| `ENTITLEMENT_TTL_HOURS` | validity window per mint | `720` (30d) |

`validate()`:
- `ISSUER_SEED` **required** when `!devMode` (no committed production seed — A29 custody).
- reject `ISSUER_ID == "tenshu.dev"` always (that id is the dev mock; the service is a real
  issuer). Mirrors the §10.3 "release rejects the dev key" guard.

### 2.2 `IssuerKeypair.kt` — A29 signing key

Loads the seed from `CommerceConfig.issuerSeed`, derives the public key via
`Ed25519Backend.derivePublicKey`, exposes `sign(message: ByteArray): ByteArray` and
`publicKey: ByteArray`. v1 = env-supplied seed; KMS/HSM + rotation are the deferred §9
open item — leave a `// TODO(A29): KMS + rotation` and keep the load behind this one type so
the swap is local.

### 2.3 `Whitelist.kt`

Wraps the config `Set<String>` (trim + lowercase on load). `isWhitelisted(email): Boolean`.
The email lives **only** in this process — it is never put in the mint response, never sent to
the relay, never written to the realm (House Rule #1; A28 stays in the commerce plane).

### 2.4 `EntitlementMinter.kt` — the domain core

```
fun mint(ownerId: String, productId: String, nowMs: Long): SignedEntitlement
```

- `validFrom = nowMs`, `validUntil = nowMs + ttl`.
- `bytes = EntitlementSignature.message(ownerId, config.issuerId, productId, validFrom, validUntil)`
  — **reused verbatim** so the existing verifier accepts it.
- `signature = issuerKeypair.sign(bytes)`.
- returns the six signed fields (reuse `EntitlementSignature.SignedEntitlement`).

No persistence in Phase 1 (stateless mint; whitelist is the only state, and it is config). A
durable account store arrives with payment (Phase 3).

### 2.5 `routing/MintRouting.kt`

`POST /commerce/entitlement/mint`

- Request: `{ "email": String, "ownerId": String, "productId": String? }`. `ownerId` is the
  caller's master-derived id — **client-supplied** in v1 (the §9 "ownerId capture at mint
  time" open item; whitelist bootstrap makes trusting it acceptable since there is no privacy
  claim in the minimal variant yet).
- Flow: `Whitelist.isWhitelisted(email)` false → **403, no mint** (don't leak which check
  failed beyond that). True → `EntitlementMinter.mint(ownerId, productId ?: topTier, now)` →
  **200** with `EntitlementDto` (signature base64). Malformed body → **400**.
- Use the relay's content-free `StatusPages` posture: never echo exception text.
- **Exposure guard:** Phase 1 `/mint` is whitelist-gated only — there is no commerce session
  yet (that is Phase 3 with payment). Keep it loopback/dev-bound or behind a shared bootstrap
  secret header; it is a founder/debug path, not a public endpoint. Note this at the route.

### 2.6 `Application.kt` — bootstrap (mirror the relay's `main()` + `module()`)

`main()` → `CommerceConfig.fromEnv()`, build `IssuerKeypair`, `Whitelist`, `EntitlementMinter`,
`embeddedServer(Netty, port, host) { module(...) }`. `Application.module(...)` installs
`ContentNegotiation(json)`, `CallLogging`, `StatusPages` (content-free 500), and
`routing { get("/") {…}; configureMintRouting(minter, whitelist) }`. Thread collaborators as
`module()` params with test defaults, exactly like the relay, so the ktor test-host can inject
a fake whitelist / fixed-seed issuer.

### 2.7 Additive edit — `identity-service/.../EntitlementSignature.kt`

Add alongside the dev key (do **not** replace it):

```kotlin
const val COMMERCE_PUBLISHER_ID = "tenshu.commerce"
val COMMERCE_PUBLIC_KEY: ByteArray = /* pinned public half of the commerce issuer key */

fun publicKeyFor(publisherId: String): ByteArray? = when (publisherId) {
    DEV_PUBLISHER_ID      -> DEV_PUBLIC_KEY
    COMMERCE_PUBLISHER_ID -> COMMERCE_PUBLIC_KEY
    else                  -> null
}
```

The **private** seed never enters `:identity-service` — only the public half is pinned here so
on-device verify-on-merge can check service-minted records. (Pinning a public key is the v1
custody story; rotation is the deferred §9 item. The matching production private seed is an ops
secret supplied to the service via `ISSUER_SEED`.)

---

## 3. Tests (House Rule #7 — run them, don't claim)

`commerce-service/src/test/.../`:

- **`EntitlementMinterTest`** — a minted record's `signature` verifies under
  `EntitlementSignature.publicKeyFor(ISSUER_ID)` via `Ed25519Backend.verify`; `validUntil ==
  validFrom + ttl`; `publisherId == ISSUER_ID`.
- **`MintRoutingTest`** (ktor `testApplication`) — whitelisted email → 200 + a DTO that passes
  verify; non-whitelisted → 403 and *no* mint; malformed body → 400.
- **`CommerceConfigTest`** — missing `ISSUER_SEED` when `!devMode` → `validate()` throws;
  `ISSUER_ID == "tenshu.dev"` rejected.

`identity-service` (the additive edit):

- extend **`EntitlementSignatureKeyTest`** to assert `publicKeyFor(COMMERCE_PUBLISHER_ID)` is
  non-null and (if a fixed test seed is committed for the commerce key) that the pinned public
  key matches `derivePublicKey(seed)`.

**Cross-check the built path still merges** (the point of reusing `message(...)` verbatim):

```bash
./gradlew :commerce-service:test :identity-service:test :sync-service-proxy:test
```

`:sync-service-proxy`'s `EntitlementVerifyOnMergeTest` must stay green — ideally add a case
that feeds it a *service-minted* record and asserts it verifies, proving a Phase-1 mint would
be accepted on sync end-to-end.

---

## 4. Done / handoff to Phase 2

Phase 1 is done when: the module builds, `/commerce/entitlement/mint` returns a verifiable
top-tier entitlement for a whitelisted email and 403 otherwise, and the verify-on-merge path
accepts a service-minted record. **Phase 2 (§10.3)** then points Control's
`EntitlementStorageInitialization` writer at this service (real `PublisherClient` impl behind
the existing seam, `MockPublisherClient` gated on `TENSHU_BILLING_MOCK`) and makes the
`tenshu.dev` dev key dev-only. No relay code is touched until Phase 4.

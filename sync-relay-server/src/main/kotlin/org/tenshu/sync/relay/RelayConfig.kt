package org.tenshu.sync.relay

import org.tenshu.sync.relay.auth.AuthFailureLimiter

/**
 * Server-side config gate. [fromEnv] parses it from the process environment (Fly.io
 * secrets / `[env]`). See `docs/how-to/deploy-relay-fly.md`.
 *
 * ⚠⚠ **Dev mode is an explicit opt-in.** An unset (or unparseable) `DEV_MODE` means
 * `devMode = false` — the relay fails *closed*, so a deployment that forgets the variable
 * requires JWTs rather than honouring `X-Dev-Owner` for anyone. The local dev loop keeps
 * working because `./gradlew :sync-relay-server:run` sets `DEV_MODE=true` itself
 * (`sync-relay-server/build.gradle.kts`); `scripts/local-commerce-stack.sh` sets it too.
 *
 * The dev-mode interlock from `sync-backend-relay-plan.md` §4.1 is enforced at startup
 * by [validate]: combining `devMode = true` with a non-loopback bind refuses to start
 * the server. This is a debug bypass, never a second auth method.
 */
data class RelayConfig(
    val devMode: Boolean,
    val bindHost: String,
    val port: Int,
    /**
     * JDBC URL for the durable backend (e.g. `jdbc:sqlite:/data/relay.db`). `null` →
     * in-memory stores (the default; unchanged behaviour). Parsed from `DATABASE_URL` in
     * `main()`. See `docs/proposals/sync-relay-persistence.md` (Phase 1, SQLite-only v1).
     */
    val databaseUrl: String? = null,
    /**
     * Directory for the durable large-blob store (content-plane chunks; Phase 3a). `null` →
     * derived next to the SQLite file (`<db-dir>/relay-blobs`). Parsed from `BLOB_DIR` in
     * `main()`; only consulted when [databaseUrl] is set. The future S3 backend swaps in here.
     */
    val blobDir: String? = null,
    /**
     * S3-compatible object storage for the large-blob plane (`sync-relay-persistence.md` §8 Ph4;
     * `hosting-provider-economics.md` D10). **All four must be set** for the S3 backend to be
     * selected; any missing → [blobDir]'s `FileBlobStore` (the default; unchanged behaviour). The
     * endpoint is a full URL, e.g. `https://fsn1.your-objectstorage.com`.
     *
     * ⚠ Selecting S3 changes what `/health` can see: the probe moves off the volume that still
     * holds `relay.db`. See [org.tenshu.sync.relay.storage.S3BlobStore.probe].
     */
    val s3Endpoint: String? = null,
    val s3Bucket: String? = null,
    /**
     * ⚠⚠ **On Scaleway, this key must have a *preferred Project* set, and it must be the Project the
     * bucket lives in.** S3 has no Project parameter, so a Scaleway API key carries one for Object
     * Storage — chosen at key-creation time ("Will this API key be used for Object Storage?"). A key
     * without it, or with the wrong one, authenticates fine and then cannot see the bucket.
     *
     * ⚠ Creating the bucket with Terraform proves nothing about this: that goes through the Scaleway
     * API, which takes `project_id` explicitly. Only the S3 path depends on the preferred Project.
     *
     * ⚠⚠ **Skipping the choice does not leave it unset** — it silently takes whichever Project the
     * console was viewing when the key was made. So the failure is not "no Project configured", it is
     * "a Project you were not thinking about". It is editable afterwards (console → ⋯ → Edit), so a
     * wrong choice costs an edit rather than a new key.
     *
     * ⚠ To reach a bucket in a different Project, append `@<project-id>` to the access key.
     */
    val s3AccessKey: String? = null,
    val s3SecretKey: String? = null,
    /** Region label. Non-AWS stores mostly ignore it, but SigV4 requires *some* value. */
    val s3Region: String = DEFAULT_S3_REGION,
    /** Key prefix so one bucket can host more than one relay (staging beside prod). */
    val s3KeyPrefix: String = "",
    /**
     * Web origins allowed to call the relay cross-origin (the browser CORS allowlist —
     * the web SDK + the anonymous Mode-A share viewer `GET /share/...`). Each entry is a
     * full origin, `scheme://host[:port]` (e.g. `https://tenshu.app`). **Empty → the
     * built-in default** (`https://tenshu.app` + the `drive`/`control` subdomains). Parsed
     * from the comma-separated `CORS_ALLOWED_ORIGINS` in [fromEnv]; ignored in [devMode]
     * (dev opens CORS to any host for local apex iteration). Set this when the web app is
     * served from a different origin than the apex (a `*.fly.dev`, a staging/preview URL,
     * or a custom domain) — otherwise the browser blocks the share-viewer fetch.
     */
    val corsAllowedOrigins: List<String> = emptyList(),
    /**
     * Per-owner cap on live (`OPEN`, unexpired) contact invites
     * (`contact-invitation-handshake.md` §6, T-CI-05). Administrative
     * anti-griefing, not a protocol invariant; issuance is JWT-authed so the
     * cap binds to a verified owner. Constructor-settable for tests; no env
     * plumbing until a deployment needs to tune it.
     */
    val maxOpenInvitesPerOwner: Int = DEFAULT_MAX_OPEN_INVITES_PER_OWNER,
    /**
     * Per-grantor cap on live (unexpired) shared-realm membership grants in the
     * relay mailbox (`shared-realm-membership-grant.md` §4/§5, T-GR-04).
     * Administrative anti-griefing, not a protocol invariant; the drop is
     * JWT-authed so the cap binds to a verified grantor. Constructor-settable
     * for tests; no env plumbing until a deployment needs to tune it.
     */
    val maxOpenGrantsPerOwner: Int = DEFAULT_MAX_OPEN_GRANTS_PER_OWNER,
    /**
     * Per-owner cap on live EPC rendezvous sessions (`ios-proxy-architecture.md`
     * §5, T-RDV-03 — the admission gate). A session is JWT-authed to a realm
     * device, so the cap binds to a verified owner; anonymous app-side frame
     * traffic can only target an already-provisioned session. Administrative
     * anti-flooding, not a protocol invariant.
     */
    val maxOpenRendezvousSessionsPerOwner: Int = DEFAULT_MAX_OPEN_RENDEZVOUS_SESSIONS_PER_OWNER,
    /**
     * Per-(session, direction) cap on buffered EPC rendezvous frames — bounds the
     * FIFO so a leaked session id cannot grow relay storage unboundedly (T-RDV-04).
     */
    val maxRendezvousFramesPerDirection: Int = DEFAULT_MAX_RENDEZVOUS_FRAMES_PER_DIRECTION,
    /**
     * Public half of the commerce issuer's entitlement-token signing key (A29), base64. The relay
     * verifies presented entitlement tokens against this (`commerce-billing-plane.md` §8 Phase 1).
     * `null` → entitlement verification disabled (the default; the relay simply ignores any token,
     * which is the current behaviour — Phase 1 **gates nothing** regardless). Parsed from
     * `ENTITLEMENT_ISSUER_PUBKEY` in [fromEnv]. Stored as base64 (not `ByteArray`) so [RelayConfig]
     * stays a well-behaved value type (no reference-equality footgun in a data class).
     */
    val entitlementIssuerPublicKeyB64: String? = null,
    /**
     * The `keyId` (`kid`) that [entitlementIssuerPublicKeyB64] verifies, for A29 rotation. `null` =
     * the issuer's default/unkeyed key (matches a token minted with no `SIGNING_KEY_ID`). Parsed
     * from `ENTITLEMENT_ISSUER_KEY_ID`.
     */
    val entitlementIssuerKeyId: String? = null,
    /**
     * When `true` **and** an issuer key is configured ([entitlementIssuerPublicKeyB64]), the relay
     * **enforces** entitlement on write endpoints (`commerce-billing-plane.md` §8 Phase 2): a write
     * without a valid, live, owner-bound entitlement token is rejected with a typed error. `false`
     * (the default) keeps the Phase-1 verify-only posture — a presented token is parsed but **gates
     * nothing** — so existing relays, and any deployment whose clients don't yet present a token,
     * are unaffected. Flip to `true` only once clients present `X-Entitlement` on writes (the client
     * token-fetch path is a separate slice); otherwise enforcement rejects every write. Parsed from
     * `ENTITLEMENT_ENFORCE`.
     *
     * Enforcement gates **writes, not reads** (§9 Q5): uploads / `/sync/push` / content-blob `PUT` /
     * share + grant drops require a live entitlement; reads/downloads of already-stored data stay
     * ungated, so an expired token (commerce outage / past-grace cancellation) never locks a user
     * out of their own data — it only pauses new writes.
     */
    val entitlementEnforce: Boolean = false,
    /**
     * When `true` **and** an issuer key is configured, the relay accepts commerce-signed
     * **account-closure** records and reclaims that owner's content once the closure is older than
     * [accountClosureGraceMs] (`retention-quota-model.md` Face A / D4).
     *
     * ⚠⚠ **Defaults to `false`, and that is deliberate, not a convenience.** Reclamation
     * permanently destroys user content — and for most owners the relay holds the only durable copy —
     * so a relay started without the flag must never delete. Production turned it ON on 2026-10-24
     * by maintainer decision, ahead of ⚖️ counsel's review of the retention clause in
     * `docs/policy/acceptable-use-and-limits.md` (owed). Parsed from `ACCOUNT_CLOSURE_RECLAIM`.
     *
     * ⚠ Meaningless without [entitlementIssuerPublicKeyB64] — with no issuer key there is nothing to
     * verify a closure record against, so the flag is logged as a no-op exactly like
     * [entitlementEnforce].
     */
    val accountClosureReclaim: Boolean = false,
    /**
     * How long after commerce's recorded closure time the relay waits before reclaiming
     * (**D4: configurable, never hardcoded** — the operational retention window has to stay
     * adjustable against GDPR/statutory obligations). Measured from the record's `closed` claim, not
     * from delivery, so a delayed or retried record cannot shorten the window. Parsed from
     * `ACCOUNT_CLOSURE_GRACE_MS`; defaults to [DEFAULT_ACCOUNT_CLOSURE_GRACE_MS].
     */
    val accountClosureGraceMs: Long = DEFAULT_ACCOUNT_CLOSURE_GRACE_MS,
    /**
     * Shared secret for the **operator** routes (`/ops/content-reclaim/{plan,enqueue,drain}`), or null to leave them
     * unregistered, the default. Parsed from `RELAY_OPERATOR_TOKEN`.
     *
     * ⚠⚠ Those routes can permanently delete ciphertext the relay holds no description for
     * (`ContentReclaimJob`). Two brakes, both required: this token, and a **loopback** socket peer
     * (so they are reachable only from inside the container, never through the proxy). Set it for an
     * operator run and remove it afterwards.
     */
    val operatorToken: String? = null,
    /**
     * Per-IP request budget (per hour) for the **unauthenticated** realm-bootstrap endpoint
     * `POST /sync/realm` — the one write-shaped route entitlement enforcement can't cover (a
     * brand-new user has no entitlement yet, and no realm/device to bind a JWT to). Anyone can
     * `POST /sync/realm` with a freshly generated master keypair and mint a new owner; without a
     * cap that's unbounded anonymous registry/storage creation. This is the first concrete piece
     * of the layered abuse defence sketched in
     * `docs/how-to/deploy-relay-digitalocean.md` §"DDoS & abuse" (the `POST /realm` per-IP cap).
     *
     * `<= 0` disables the limiter entirely (no `RateLimit` plugin installed, route mounted
     * unwrapped). The IP is read from `X-Forwarded-For` via [io.ktor.server.plugins.forwardedheaders.XForwardedHeaders]
     * so the bucket keys on the real client behind Caddy, not Caddy's address (direct/dev binds
     * fall back to the socket peer). Parsed from `GENESIS_RATE_LIMIT_PER_HOUR`.
     *
     * Default [DEFAULT_GENESIS_RATE_LIMIT_PER_HOUR] is generous for legitimate onboarding (each
     * new device's first launch is one call) while bounding bulk creation from a single source;
     * tune up for deployments behind a shared NAT, down to harden. Not a substitute for upstream
     * scrubbing against a distributed flood — see the how-to's CDN decision.
     */
    val genesisRateLimitPerHour: Int = DEFAULT_GENESIS_RATE_LIMIT_PER_HOUR,
    /**
     * Per-IP hourly cap on `POST /sync/pairing/request`, the other credential-less write
     * (an unpaired joiner holds nothing to authenticate with); `<= 0` disables. Same keying and
     * the same caveats as [genesisRateLimitPerHour]. Parsed from `PAIRING_REQUEST_RATE_LIMIT_PER_HOUR`.
     *
     * ⚠ This is the *rate* half. What bounds the relay's memory is [PairingRequestInbox] and the
     * route's own refusals; this bounds how fast one address can churn a known owner's 32 slots,
     * which is the flood that pushes a real joiner's request out. A joiner posts once per attempt,
     * so the default leaves a shared NAT plenty of room.
     */
    val pairingRequestRateLimitPerHour: Int = DEFAULT_PAIRING_REQUEST_RATE_LIMIT_PER_HOUR,
    /**
     * Per-IP cap on `GET /sync/recovery-challenge`, per **minute** (an IPv6 client is keyed by its
     * /64); `<= 0` disables. Parsed from `RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE`. Threat model
     * T-RC-08 residual (a): F8 made the challenge issuable to anyone, into a set bounded at 10 000
     * for a 5-minute TTL, so without a per-source cap one address could fill it and stall every real
     * recovery. Same forwarded-IP keying as [genesisRateLimitPerHour], but its own bounded limiter
     * (`ClientRateLimiter`), not Ktor's plugin — that class says why.
     */
    val recoveryChallengeRateLimitPerMinute: Int = DEFAULT_RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE,
    /**
     * Per-IP hourly budget for **failed** authentications; `<= 0` disables. Past it the relay
     * answers the same `401` without doing the Ed25519 verification — see [AuthFailureLimiter],
     * which explains at length why the status must stay `401` and not become `429`.
     */
    val authFailureLimitPerHour: Int = AuthFailureLimiter.DEFAULT_AUTH_FAILURE_LIMIT_PER_HOUR,
    /**
     * CR-2 B5 (`content-plane-cr2-b5-spec.md` D2): the relay's content sweep — `off` (default),
     * `dry_run` (log what it would free) or `armed` (free, and drain the bytes). Parsed from
     * `CONTENT_SWEEP_MODE`; an unknown value refuses to start rather than guess.
     *
     * ⚠⚠ `armed` is a deploy-time decision with consequences beyond the sweep: held bytes leave the
     * quota (D5). Arm only after a `dry_run` period
     * whose would-free list the maintainer has read (spec §2).
     */
    val contentSweepMode: ContentSweepMode = ContentSweepMode.OFF,
    /** T, the grace after a content's last edge before the sweep may free it (D1). `CONTENT_GRACE_MS`. */
    val contentGraceMs: Long = DEFAULT_CONTENT_GRACE_MS,
    /** How often the sweep runs (D1). `CONTENT_SWEEP_INTERVAL_MS`. */
    val contentSweepIntervalMs: Long = DEFAULT_CONTENT_SWEEP_INTERVAL_MS,
    /**
     * Relay-wide ceiling on stored bytes (content plane + share links, over every owner); null = no
     * ceiling. Past it, new uploads and new shares get `507`, while reads, sync and re-uploads
     * still work. The operator's cost brake: per-owner quotas do not bound the number of owners.
     * Parsed from `STORAGE_CEILING_GIB`. See [org.tenshu.sync.relay.storage.StorageCeiling] and
     * `docs/how-to/relay-storage-ceiling.md`.
     */
    val storageCeilingBytes: Long? = null,
    /** How often the ceiling re-measures the stores; between measurements it adds accepted writes. */
    val storageCeilingRefreshMs: Long = org.tenshu.sync.relay.storage.StorageCeiling.DEFAULT_REFRESH_MS,
) {

    /**
     * Validates the config or throws [InvalidRelayConfigException]. Call from the server
     * bootstrap before [io.ktor.server.engine.embeddedServer] starts.
     */
    fun validate() {
        if (devMode && !isLoopback(bindHost)) {
            throw InvalidRelayConfigException(
                "Refusing to start: relay.dev_mode=true is only legal with a loopback " +
                        "bind address, got '$bindHost'. Set bindHost to 127.0.0.1 / ::1 / " +
                        "localhost, or disable dev mode."
            )
        }
    }

    /**
     * True when the debug-only `X-Dev-Owner` auth bypass ([devMode]) is active on a
     * non-loopback bind — i.e. a deployment footgun. `main()` keeps such a relay
     * *starting* (local dev binds `0.0.0.0` for emulator / LAN device reach, and
     * [validate] would otherwise refuse it) but logs a loud warning. A real
     * deployment must set `DEV_MODE=false`; see `docs/how-to/deploy-relay-fly.md`.
     */
    fun isDevModePubliclyExposed(): Boolean = devMode && !isLoopback(bindHost)

    private fun isLoopback(host: String): Boolean = when (host) {
        "127.0.0.1", "::1", "localhost", "0:0:0:0:0:0:0:1" -> true
        else -> false
    }

    /**
     * True when every S3 setting needed to build a client is present. **All four or none** — a
     * half-configured relay silently falling back to the volume is how a deploy "succeeds" while
     * writing bytes nobody intended to keep there, so [org.tenshu.sync.relay.Application] treats a
     * partial configuration as a startup error rather than a fallback.
     */
    val s3Configured: Boolean
        get() = !s3Endpoint.isNullOrBlank() && !s3Bucket.isNullOrBlank() &&
            !s3AccessKey.isNullOrBlank() && !s3SecretKey.isNullOrBlank()

    /** True when *some* S3 setting is present — the partial case worth refusing to start on. */
    val s3PartiallyConfigured: Boolean
        get() = !s3Configured && listOf(s3Endpoint, s3Bucket, s3AccessKey, s3SecretKey).any { !it.isNullOrBlank() }

    companion object {
        /**
         * Parse the runtime config from the process environment, falling back to defaults
         * (in-memory stores, `devMode=false`, bind `0.0.0.0:8080`). ⚠ Dev mode is never a
         * default: `./gradlew :sync-relay-server:run` opts in by exporting `DEV_MODE=true`
         * (overridable from the caller's shell). A deployment (e.g. Fly.io) supplies these as
         * secrets/`[env]`:
         *
         * | Var            | Default        | Notes                                            |
         * |----------------|----------------|--------------------------------------------------|
         * | `PORT`         | `8080`         | Listen port (Fly routes `internal_port` here).   |
         * | `BIND_HOST`    | `0.0.0.0`      | Bind address.                                     |
         * | `DEV_MODE`     | `false`        | `true` enables the `X-Dev-Owner` auth bypass —    |
         * |                |                | local dev only, never a shared deploy. Anything   |
         * |                |                | but `true`/`false` (incl. unset) = `false` (fail  |
         * |                |                | closed). See [isDevModePubliclyExposed].          |
         * | `DATABASE_URL` | _(unset)_      | JDBC URL → durable backend; unset = in-memory.    |
         * | `BLOB_DIR`     | _(derived)_    | Large-blob dir; derived next to the DB if unset.  |
         * | `CORS_ALLOWED_ORIGINS` | _(default apex)_ | Comma-separated web origins; empty = apex default. |
         * | `ENTITLEMENT_ISSUER_PUBKEY` | _(unset)_ | base64 commerce A29 public key; unset = verification off. |
         * | `ENTITLEMENT_ISSUER_KEY_ID` | _(unset)_ | `kid` the pubkey verifies (A29 rotation); unset = default key. |
         * | `ENTITLEMENT_ENFORCE` | `false` | Gate writes on a live entitlement (§8 Phase 2); needs the pubkey set + clients presenting `X-Entitlement`. |
         * | `ACCOUNT_CLOSURE_RECLAIM` | `false` | ⚠⚠ Accept commerce closure records and **permanently delete** that owner's content. ON in production since 2026-10-24 (⚖️ counsel review owed). |
         * | `ACCOUNT_CLOSURE_GRACE_MS` | 30 d | Wait after the recorded closure time before reclaiming (D4: configurable). |
         * | `RELAY_OPERATOR_TOKEN` | _(unset)_ | ⚠⚠ Enables the loopback-only operator routes, which can **delete** undescribed ciphertext (`ContentReclaimJob`). Unset = routes absent. |
         * | `GENESIS_RATE_LIMIT_PER_HOUR` | `20` | Per-IP cap on the unauth `POST /sync/realm` bootstrap; `<= 0` disables. See [genesisRateLimitPerHour]. |
         * | `PAIRING_REQUEST_RATE_LIMIT_PER_HOUR` | `120` | Per-IP cap on the unauth `POST /sync/pairing/request`; `<= 0` disables. See [pairingRequestRateLimitPerHour]. |
         * | `RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE` | `6` | Per-IP (IPv6: per /64) cap on `GET /sync/recovery-challenge`; `<= 0` disables. See [recoveryChallengeRateLimitPerMinute]. |
         * | `CONTENT_SWEEP_MODE` | `off` | ⚠⚠ CR-2 B5: `off` / `dry_run` / `armed`. `armed` **frees** content unreferenced for T and stops charging held bytes. ⚠ Since CR-2 B6 it is the only freeing path (no client frees), and `sync-relay-server/docker-compose.yml` deploys `armed`; this default stays `off` so a relay started without it leaks, never frees. Unknown = refuse to start. |
         * | `CONTENT_GRACE_MS` | 15 min | T: how long content stays unreferenced before the sweep may free it. |
         * | `CONTENT_SWEEP_INTERVAL_MS` | 5 min | Sweep cadence; content is freed between T and T + this. |
         * | `STORAGE_CEILING_GIB` | _(unset)_ | Relay-wide stored-bytes ceiling in GiB (cost brake); past it new uploads/shares get `507`. Unset = none; not a positive integer = refuse to start. See [storageCeilingBytes]. |
         * | `AUTH_FAILURE_LIMIT_PER_HOUR` | `600` | Per-IP budget for FAILED authentications; past it a `401` is answered without verifying. `<= 0` disables. See [authFailureLimitPerHour]. |
         *
         * [getenv] is injectable so the parsing is unit-testable.
         */
        fun fromEnv(getenv: (String) -> String? = System::getenv): RelayConfig = RelayConfig(
            // ⚠⚠ Fail CLOSED: unset/unparseable ⇒ false. Dev mode (the X-Dev-Owner bypass) is
            // only ever an explicit `DEV_MODE=true` (relay-metadata-minimisation.md D13).
            devMode = getenv("DEV_MODE")?.toBooleanStrictOrNull() ?: false,
            bindHost = getenv("BIND_HOST")?.takeIf { it.isNotBlank() } ?: "0.0.0.0",
            port = getenv("PORT")?.toIntOrNull() ?: 8080,
            databaseUrl = getenv("DATABASE_URL")?.takeIf { it.isNotBlank() },
            blobDir = getenv("BLOB_DIR")?.takeIf { it.isNotBlank() },
            s3Endpoint = getenv("S3_ENDPOINT")?.takeIf { it.isNotBlank() },
            s3Bucket = getenv("S3_BUCKET")?.takeIf { it.isNotBlank() },
            s3AccessKey = getenv("S3_ACCESS_KEY")?.takeIf { it.isNotBlank() },
            s3SecretKey = getenv("S3_SECRET_KEY")?.takeIf { it.isNotBlank() },
            s3Region = getenv("S3_REGION")?.takeIf { it.isNotBlank() } ?: DEFAULT_S3_REGION,
            s3KeyPrefix = getenv("S3_KEY_PREFIX")?.takeIf { it.isNotBlank() } ?: "",
            corsAllowedOrigins = getenv("CORS_ALLOWED_ORIGINS")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            entitlementIssuerPublicKeyB64 = getenv("ENTITLEMENT_ISSUER_PUBKEY")?.takeIf { it.isNotBlank() },
            entitlementIssuerKeyId = getenv("ENTITLEMENT_ISSUER_KEY_ID")?.takeIf { it.isNotBlank() },
            entitlementEnforce = getenv("ENTITLEMENT_ENFORCE")?.toBooleanStrictOrNull() ?: false,
            accountClosureReclaim = getenv("ACCOUNT_CLOSURE_RECLAIM")?.toBooleanStrictOrNull() ?: false,
            accountClosureGraceMs = getenv("ACCOUNT_CLOSURE_GRACE_MS")?.toLongOrNull()
                ?: DEFAULT_ACCOUNT_CLOSURE_GRACE_MS,
            operatorToken = getenv("RELAY_OPERATOR_TOKEN")?.takeIf { it.isNotBlank() },
            authFailureLimitPerHour = getenv("AUTH_FAILURE_LIMIT_PER_HOUR")?.toIntOrNull()
                ?: AuthFailureLimiter.DEFAULT_AUTH_FAILURE_LIMIT_PER_HOUR,
            genesisRateLimitPerHour = getenv("GENESIS_RATE_LIMIT_PER_HOUR")?.toIntOrNull()
                ?: DEFAULT_GENESIS_RATE_LIMIT_PER_HOUR,
            pairingRequestRateLimitPerHour = getenv("PAIRING_REQUEST_RATE_LIMIT_PER_HOUR")?.toIntOrNull()
                ?: DEFAULT_PAIRING_REQUEST_RATE_LIMIT_PER_HOUR,
            recoveryChallengeRateLimitPerMinute = getenv("RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE")?.toIntOrNull()
                ?: DEFAULT_RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE,
            contentSweepMode = getenv("CONTENT_SWEEP_MODE")?.takeIf { it.isNotBlank() }?.let { raw ->
                // ⚠ Refuse, never default: a typo for `armed` silently running `off` would hide that the
                // deploy did not do what the operator decided, and a typo for `off` must not arm.
                ContentSweepMode.parse(raw)
                    ?: throw InvalidRelayConfigException(
                        "CONTENT_SWEEP_MODE must be one of off, dry_run, armed; got '$raw'",
                    )
            } ?: ContentSweepMode.OFF,
            contentGraceMs = positiveMs(getenv("CONTENT_GRACE_MS"), "CONTENT_GRACE_MS") ?: DEFAULT_CONTENT_GRACE_MS,
            contentSweepIntervalMs = positiveMs(getenv("CONTENT_SWEEP_INTERVAL_MS"), "CONTENT_SWEEP_INTERVAL_MS")
                ?: DEFAULT_CONTENT_SWEEP_INTERVAL_MS,
            storageCeilingBytes = storageCeilingBytes(getenv("STORAGE_CEILING_GIB")),
        )

        /**
         * ⚠ Refuse, never ignore: a mistyped ceiling that silently meant "no ceiling" would leave the
         * operator believing the bill is capped when it is not.
         */
        private fun storageCeilingBytes(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            val gib = raw.trim().toLongOrNull()
            if (gib == null || gib <= 0 || gib > Long.MAX_VALUE / org.tenshu.sync.relay.storage.StorageCeiling.BYTES_PER_GIB) {
                throw InvalidRelayConfigException("STORAGE_CEILING_GIB must be a positive whole number of GiB; got '$raw'")
            }
            return gib * org.tenshu.sync.relay.storage.StorageCeiling.BYTES_PER_GIB
        }

        private fun positiveMs(raw: String?, name: String): Long? {
            if (raw.isNullOrBlank()) return null
            return raw.trim().toLongOrNull()?.takeIf { it > 0 }
                ?: throw InvalidRelayConfigException("$name must be a positive number of milliseconds; got '$raw'")
        }

        /** CR-2 B5 D1: T = 15 minutes once B4 (adds before removes + G3) is built. */
        const val DEFAULT_CONTENT_GRACE_MS: Long = 15L * 60L * 1_000L

        /** CR-2 B5 D1: the sweep runs every 5 minutes, so content is freed within T + 5 min. */
        const val DEFAULT_CONTENT_SWEEP_INTERVAL_MS: Long = 5L * 60L * 1_000L

        /**
         * SigV4 requires a region even where the store ignores it; Hetzner, Scaleway and R2 all
         * accept this placeholder. Override with `S3_REGION` where the store is region-checked.
         */
        const val DEFAULT_S3_REGION: String = "us-east-1"

        /**
         * Default tombstone-body retention window per `docs/adr/0006-sync-tombstones.md`
         * §2.2 — 30 days from the tombstone's HLC physical time. Once every realm device
         * has ack'd the tombstone (via the HLC-tip piggyback from
         * `sync-backend-relay-plan.md` §5.1) AND this window has elapsed, the GC sweep
         * drops the target body bytes. The tombstone marker itself is preserved.
         *
         * Per-owner overrides are set via `SyncMailbox.setRetentionWindowMs(ownerId, ms)`
         * — the seam where the §5.7 "where retention lives" question would later plug in
         * a real config surface (synced `Volume` entity, admin endpoint, etc.).
         */
        const val DEFAULT_RETENTION_WINDOW_MS: Long = 30L * 24L * 60L * 60L * 1_000L

        /**
         * Default grace between a recorded account closure and reclamation — 30 days.
         * ⚠ A *default*, not a policy: D4 requires this stay configurable so the operational
         * retention window can be aligned with whatever the published terms commit to.
         */
        const val DEFAULT_ACCOUNT_CLOSURE_GRACE_MS: Long = 30L * 24 * 60 * 60 * 1000

        /**
         * Content-plane retention window — **`Long.MAX_VALUE` (disabled) by default**
         * (`docs/proposals/file-content-addressing.md` §4.2 / T-R-09).
         *
         * The content plane holds **durable user data**, not an ephemeral delivery log.
         * A file nobody opens for months (a backup, an archive) is still live data: its
         * `contentId` is still referenced by a live `File`/`SharedFile` entity, and its
         * only durable copy may be the relay (content is `OnDemand`; the local plaintext
         * can be evicted, and second devices / the web client hold no local copy at all).
         * A *time-since-last-touch* retention sweep would therefore **reap live data** —
         * the "opened after two years → gone" data-loss bug. So, unlike the ordered-log
         * tombstone GC ([DEFAULT_RETENTION_WINDOW_MS], quorum + retention), the content
         * plane is **not** time-reaped.
         *
         * Legitimate content reclamation is **owner- or ref-scoped**, never time-driven, and
         * both mechanisms are built: an account-lifecycle reclaim keyed on `ownerId`
         * (`ContentBlobRegistry.reclaimOwner`, flag-gated and off by default), and the
         * client-driven per-file `dropRefs` of ST-3b, which is live on desktop and Android.
         * A blob neither names still lingers against the owner's quota (quota is the brake).
         * ⚠ A relay-**derived** manifest refcount remains House-Rule-#1-impossible; a
         * client-**declared** one shipped 2026-09-09 (`content-plane-refcount.md`) but frees
         * nothing yet — the edges are written and read by no reclaimer. The
         * relay stays content-blind throughout (it reclaims by `ownerId`, never by reading
         * a manifest). The `gcSweep` *mechanism* is retained (test hook + the seam an
         * account-lapse or admin reclaim drives with an explicit window) but is **off** on
         * the production time-sweep.
         */
        const val CONTENT_RETENTION_WINDOW_MS: Long = Long.MAX_VALUE

        /**
         * Cadence at which `Application.module` triggers `SyncMailbox.gcSweep`. Nightly
         * per `docs/adr/0006-sync-tombstones.md` ("periodic GC cadence is an
         * operational choice; nightly is fine for v1"). Each sweep is cheap when nothing
         * is eligible — it's a per-owner walk over the tombstone index with an early-out
         * on the quorum check.
         */
        const val GC_SWEEP_INTERVAL_MS: Long = 24L * 60L * 60L * 1_000L

        /**
         * Cadence at which `Application.module` triggers `ContentBlobStore.gcSweep`. With
         * [CONTENT_RETENTION_WINDOW_MS] at its `Long.MAX_VALUE` default the sweep is a
         * **no-op** (content is durable — see that constant), so this is the heartbeat of
         * a seam kept armed for a future account-lifecycle reclamation, not an active
         * time-reaper. Cheap per-owner walk; harmless when disabled.
         */
        const val CONTENT_GC_SWEEP_INTERVAL_MS: Long = 24L * 60L * 60L * 1_000L

        /**
         * Default [genesisRateLimitPerHour] — per-IP hourly budget for `POST /sync/realm`.
         * 20 comfortably covers legitimate onboarding (one call per new device's first launch,
         * even a household setting up several devices) while bounding bulk anonymous owner
         * creation from a single source. See `docs/how-to/deploy-relay-digitalocean.md`
         * §"DDoS & abuse".
         */
        const val DEFAULT_GENESIS_RATE_LIMIT_PER_HOUR: Int = 20

        /**
         * Default [pairingRequestRateLimitPerHour] — per-IP hourly budget for
         * `POST /sync/pairing/request`. A joiner posts once per pairing attempt; 120 is two a
         * minute, sustained, from one address.
         */
        const val DEFAULT_PAIRING_REQUEST_RATE_LIMIT_PER_HOUR: Int = 120

        /**
         * Default [recoveryChallengeRateLimitPerMinute]. A recovery is one challenge per attempt, so
         * 6 a minute (a burst of 6, then one every 10 s) leaves a user retrying a mistyped phrase —
         * or a household behind one NAT — plenty of room. Against the store it means one source
         * holds at most ~36 of the 10 000 slots per 5-minute TTL, so filling it takes ~280 sources.
         */
        const val DEFAULT_RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE: Int = 6

        /** Default [maxOpenInvitesPerOwner] (`contact-invitation-handshake.md` §6). */
        const val DEFAULT_MAX_OPEN_INVITES_PER_OWNER: Int = 32

        /**
         * Cadence at which `Application.module` triggers `InviteRegistry.gcSweep`.
         * Hourly rather than nightly: invites are short-lived (72 h default,
         * 7-day relay cap) and the sweep is a cheap single-table walk.
         */
        const val INVITE_GC_SWEEP_INTERVAL_MS: Long = 60L * 60L * 1_000L

        /** Default [maxOpenGrantsPerOwner] (`shared-realm-membership-grant.md` §5). */
        const val DEFAULT_MAX_OPEN_GRANTS_PER_OWNER: Int = 64

        /**
         * Cadence at which `Application.module` triggers `GrantRegistry.gcSweep` —
         * the grant-mailbox TTL sweep (`shared-realm-membership-grant.md` §4, D4:
         * an unaccepted grant expires after 30 days). Nightly: grants are long-lived
         * relative to invites and the sweep is a cheap single-table walk.
         */
        const val GRANT_GC_SWEEP_INTERVAL_MS: Long = 24L * 60L * 60L * 1_000L

        /** Default [maxOpenRendezvousSessionsPerOwner] (`ios-proxy-architecture.md` §5, T-RDV-03). */
        const val DEFAULT_MAX_OPEN_RENDEZVOUS_SESSIONS_PER_OWNER: Int = 16

        /** Default [maxRendezvousFramesPerDirection] (`ios-proxy-architecture.md` §5, T-RDV-04). */
        const val DEFAULT_MAX_RENDEZVOUS_FRAMES_PER_DIRECTION: Int = 256

        /**
         * Cadence at which `Application.module` triggers `RendezvousRegistry.gcSweep`
         * — the EPC rendezvous TTL sweep (`ios-proxy-architecture.md` §5; sessions are
         * minutes-scale, so frequent). A cheap two-table walk.
         */
        const val RENDEZVOUS_GC_SWEEP_INTERVAL_MS: Long = 60L * 60L * 1_000L

        /**
         * Serving TTL for a stored offer-flow pairing response
         * (`sync-pairing-inverted-direction.md` §3.4): the offerer's 5-minute
         * pairing window (improvements §9) plus a 2-minute grace so a poll
         * racing the window's edge still lands.
         */
        const val PAIRING_OFFER_RESPONSE_TTL_MS: Long = 7L * 60L * 1_000L

        /**
         * Serving TTL for a hub's signed ephemeral pairing offer
         * (`/sync/pairing/hub-offer`, hub proposal §11.3 H2): mirrors
         * [PAIRING_OFFER_RESPONSE_TTL_MS] — the hub's ~5-minute arming window plus
         * grace. Unlike the offer-response hash, a hub-offer hash is NOT single-use
         * forever (the printed secret is long-lived): re-arming re-posts under the
         * same hash, and a fetch consumes the entry.
         */
        const val HUB_OFFER_TTL_MS: Long = 7L * 60L * 1_000L

        /**
         * Cadence at which `Application.module` triggers
         * `PairingOfferRegistry.gcSweep` — drops consumed single-use markers
         * past their retention (`PairingOfferRegistry.CONSUMED_RETENTION_MS`).
         * Hourly, matching the invite sweep; a cheap single-table walk.
         */
        const val PAIRING_OFFER_GC_SWEEP_INTERVAL_MS: Long = 60L * 60L * 1_000L
    }
}

class InvalidRelayConfigException(message: String) : IllegalStateException(message)

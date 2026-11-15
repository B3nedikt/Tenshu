package org.tenshu.sync.relay

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.ktor.server.netty.Netty
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.hours
import org.slf4j.event.Level
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.sync.relay.auth.DeviceRegistry
import org.tenshu.sync.relay.auth.AuthFailureLimiter
import org.tenshu.sync.relay.auth.ClientRateLimiter
import org.tenshu.sync.relay.auth.JwtAuthenticator
import org.tenshu.sync.relay.auth.RelayAccountClosureVerifier
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier
import org.tenshu.sync.relay.mailbox.ContentBlobRegistry
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.ContentFreedStore
import org.tenshu.sync.relay.mailbox.ContentFreeing
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentRefRegistry
import org.tenshu.sync.relay.mailbox.ContentRefStore
import org.tenshu.sync.relay.mailbox.GrantRegistry
import org.tenshu.sync.relay.mailbox.GrantStore
import org.tenshu.sync.relay.mailbox.HlcTipRegistry
import org.tenshu.sync.relay.mailbox.HlcTipStore
import org.tenshu.sync.relay.mailbox.InviteRegistry
import org.tenshu.sync.relay.mailbox.InviteStore
import org.tenshu.sync.relay.mailbox.MembershipRegistry
import org.tenshu.sync.relay.mailbox.MembershipStore
import org.tenshu.sync.relay.mailbox.RealmRegistry
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.mailbox.RecoveryChallengeStore
import org.tenshu.sync.relay.mailbox.RendezvousRegistry
import org.tenshu.sync.relay.mailbox.HubOfferRegistry
import org.tenshu.sync.relay.mailbox.HubOfferStore
import org.tenshu.sync.relay.mailbox.PairingOfferRegistry
import org.tenshu.sync.relay.mailbox.PairingOfferStore
import org.tenshu.sync.relay.mailbox.RendezvousStore
import org.tenshu.sync.relay.mailbox.AdmissionRevocationRegistry
import org.tenshu.sync.relay.mailbox.AdmissionRevocationStore
import org.tenshu.sync.relay.mailbox.ShareRegistry
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.BlobStore
import org.tenshu.sync.relay.storage.FileBlobStore
import org.tenshu.sync.relay.storage.RelayBinaryCodec
import org.tenshu.sync.relay.storage.S3BlobStore
import org.tenshu.sync.relay.storage.VolumeWriteProbe
import org.tenshu.sync.relay.storage.SqliteContentBlobStore
import org.tenshu.sync.relay.storage.SqliteContentFreedStore
import org.tenshu.sync.relay.storage.SqliteContentEdgeStore
import org.tenshu.sync.relay.storage.SqliteContentRefStore
import org.tenshu.sync.relay.storage.SqliteDeviceRegistry
import org.tenshu.sync.relay.storage.SqliteGrantStore
import org.tenshu.sync.relay.storage.SqliteHlcTipStore
import org.tenshu.sync.relay.storage.SqliteInviteStore
import org.tenshu.sync.relay.storage.SqliteMembershipStore
import org.tenshu.sync.relay.storage.SqliteRealmStore
import org.tenshu.sync.relay.storage.SqlitePairingOfferStore
import org.tenshu.sync.relay.storage.SqliteRendezvousStore
import org.tenshu.sync.relay.storage.SqliteAdmissionRevocationStore
import org.tenshu.sync.relay.storage.SqliteShareStore
import org.tenshu.sync.relay.storage.SqliteSyncMailbox
import org.tenshu.sync.relay.mailbox.ShareStore
import org.tenshu.sync.relay.routing.configureAccountClosureRouting
import org.tenshu.sync.relay.routing.configureOperatorRouting
import org.tenshu.sync.relay.storage.ContentReclaimJob
import org.tenshu.sync.relay.storage.ContentSweepJob
import org.tenshu.sync.relay.routing.configureContentRouting
import org.tenshu.sync.relay.routing.configureEpcRendezvousRouting
import org.tenshu.sync.relay.routing.HealthProbe
import org.tenshu.sync.relay.routing.configureHealthRouting
import org.tenshu.sync.relay.routing.configureAdmissionRouting
import org.tenshu.sync.relay.routing.configureInviteRouting
import org.tenshu.sync.relay.routing.configureMembershipGrantRouting
import org.tenshu.sync.relay.routing.GenesisRateLimitName
import org.tenshu.sync.relay.routing.PairingRequestRateLimitName
import org.tenshu.sync.relay.routing.configureRealmRouting
import org.tenshu.sync.relay.routing.configureRecoveryRouting
import org.tenshu.sync.relay.routing.configureShareRouting
import org.tenshu.sync.relay.routing.configureSyncRouting
import org.tenshu.sync.relay.routing.MAX_SHARE_CHUNK_BYTES
import org.tenshu.sync.relay.storage.StorageCeiling

fun main() {
    // All runtime config comes from the environment, falling back to defaults (in-memory
    // stores, devMode OFF). Dev mode is an explicit `DEV_MODE=true` opt-in, which
    // `./gradlew :sync-relay-server:run` supplies itself. A deployment supplies
    // PORT / BIND_HOST / DATABASE_URL / BLOB_DIR (and DEV_MODE=false, explicitly) — see
    // docs/how-to/deploy-relay-fly.md. Persistence stays opt-in via DATABASE_URL
    // (docs/proposals/sync-relay-persistence.md): present (e.g.
    // `jdbc:sqlite:/data/relay.db`) → durable registry + stores, so a restart keeps
    // registrations (in memory, every paired device gets a 401 after a restart).
    val config = RelayConfig.fromEnv()
    val databaseUrl = config.databaseUrl
    // The dev-mode + non-loopback interlock (sync-backend-relay-plan.md §4.1). Dev mode is
    // only ever an explicit DEV_MODE=true now (unset fails closed), but local dev opts in
    // AND binds 0.0.0.0 for emulator / LAN device reach — so we let that start, and still
    // warn loudly: an explicit opt-in on a reachable bind is exactly the shape a
    // copy-pasted dev env takes into a deployment. Any other config is validated for real
    // (devMode=false passes on any bind — the deployment shape).
    if (config.isDevModePubliclyExposed()) {
        System.err.println(
            "WARNING: relay starting with DEV_MODE=true (explicitly set) on a non-loopback bind " +
                "(${config.bindHost}:${config.port}). The X-Dev-Owner auth bypass is ACTIVE — anyone " +
                "who can reach this port can act as any owner. Unset DEV_MODE (or set it to false) for " +
                "any shared/public deployment (see docs/how-to/deploy-relay-fly.md)."
        )
    } else {
        config.validate()
    }

    // Select the storage backend once: with a database each store below is its SQLite twin,
    // without one it is in memory. The SQLite connection lives for the process lifetime —
    // closed implicitly on shutdown.
    val database = config.databaseUrl?.let { RelayDatabase(it) }
    // Wire codec for the durable stores that hold wire types (HLC tips, the mailbox).
    val codec = database?.let { RelayBinaryCodec() }
    val knownDevices: DeviceRegistry = database?.let { SqliteDeviceRegistry(it) } ?: KnownDevicesStore()
    val realmStore: RealmRegistry = database?.let { SqliteRealmStore(it) } ?: RealmStore()
    val hlcTipStore: HlcTipRegistry =
        if (database != null && codec != null) SqliteHlcTipStore(database, codec) else HlcTipStore()
    // The holder-keyed edge set. ⚠ In-memory is not durable, so a restart forgets every
    // edge — which on a durable relay would read as "nothing references anything". The SQLite one is
    // the production path; the fallback exists only where `database` is absent (dev mode, tests).
    val contentEdgeStore: ContentEdgeRegistry =
        database?.let { SqliteContentEdgeStore(it) } ?: ContentEdgeStore()
    // The contentId -> refs hop the edge set does not carry. Same durability caveat.
    val contentRefStore: ContentRefRegistry =
        database?.let { SqliteContentRefStore(it) } ?: ContentRefStore()
    val syncMailbox: MailboxStore =
        if (database != null && codec != null) SqliteSyncMailbox(database, codec) else SyncMailbox()
    // One BlobStore for all large opaque bytes (content-plane chunks + share chunks; keys are
    // namespaced so they can't collide). Only the small index rows live in SQLite.
    //
    // S3 when fully configured (`sync-relay-persistence.md` §8 / `hosting-provider-economics.md`
    // D10), else the filesystem store next to the DB unless BLOB_DIR overrides — the default.
    //
    // ⚠⚠ A PARTIAL S3 configuration refuses to start rather than falling back. Silently writing to
    // the volume because one of four env vars was missing is a deploy that looks successful and
    // puts the bytes where the operator did not intend — and on the volume, unreclaimed growth is
    // an outage rather than a bill.
    require(!config.s3PartiallyConfigured) {
        "S3 is half-configured: set all of S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY, or none of them."
    }
    val blobStore: BlobStore? = database?.let {
        if (config.s3Configured) {
            S3BlobStore(
                client = S3BlobStore.clientFor(
                    endpoint = config.s3Endpoint!!,
                    region = config.s3Region,
                    accessKey = config.s3AccessKey!!,
                    secretKey = config.s3SecretKey!!,
                ),
                bucket = config.s3Bucket!!,
                keyPrefix = config.s3KeyPrefix,
            )
        } else {
            val blobDir = config.blobDir?.let { dir -> java.io.File(dir) }
                ?: java.io.File(databaseBlobParent(config.databaseUrl), "relay-blobs")
            FileBlobStore(blobDir)
        }
    }
    val contentBlobStore: ContentBlobRegistry =
        if (database != null && blobStore != null) SqliteContentBlobStore(database, blobStore) else ContentBlobStore()
    val shareStore: ShareRegistry =
        if (database != null && blobStore != null) SqliteShareStore(database, blobStore) else ShareStore()
    val inviteStore: InviteRegistry = database?.let { SqliteInviteStore(it) } ?: InviteStore()
    // GR-2: ephemeral grant mailbox + durable revocation/admission state.
    val grantStore: GrantRegistry = database?.let { SqliteGrantStore(it) } ?: GrantStore()
    val membershipStore: MembershipRegistry = database?.let { SqliteMembershipStore(it) } ?: MembershipStore()
    // App↔proxy EPC rendezvous mailbox (short-lived, owner-bound sessions).
    val rendezvousStore: RendezvousRegistry = database?.let { SqliteRendezvousStore(it) } ?: RendezvousStore()
    // Delegated admission (P2): durable per-owner admission revocation list.
    val admissionRevocationStore: AdmissionRevocationRegistry =
        database?.let { SqliteAdmissionRevocationStore(it) } ?: AdmissionRevocationStore()
    // Offer-flow pairing rendezvous (sync-pairing-inverted-direction.md §3.4, P2).
    val pairingOfferStore: PairingOfferRegistry =
        database?.let { SqlitePairingOfferStore(it) } ?: PairingOfferStore()
    println(
        if (database != null) "Relay persistence: SQLite (${config.databaseUrl})"
        else "Relay persistence: in-memory (set DATABASE_URL to persist)"
    )
    // ⚠ Name the blob backend out loud. Which branch above was taken is otherwise invisible at
    // runtime: both serve reads identically, so a deploy that MEANT to be on S3 and silently stayed
    // on the volume looks exactly like a successful one until the disk fills; an AWS SDK debug line
    // is not a check.
    println(
        when (val store = blobStore) {
            null -> "Relay blobs: none (no database configured)"
            is S3BlobStore -> "Relay blobs: S3 (${config.s3Endpoint}, bucket=${config.s3Bucket}, " +
                "region=${config.s3Region}${if (config.s3KeyPrefix.isNotBlank()) ", prefix=${config.s3KeyPrefix}" else ""})"
            is FileBlobStore -> "Relay blobs: filesystem (${store.root.absolutePath})"
            else -> "Relay blobs: ${store::class.simpleName}"
        }
    )

    embeddedServer(
        Netty,
        port = config.port,
        host = config.bindHost,
        module = {
            module(
                config,
                knownDevices = knownDevices,
                realmStore = realmStore,
                hlcTipStore = hlcTipStore,
                syncMailbox = syncMailbox,
                // ⚠⚠ These two were BUILT above and never passed, so `module`'s in-memory defaults
                // won silently and every declaration lived in RAM until the next restart. Nothing
                // was ever written to `content_edge` / `content_ref` on production. See the
                // `require` in `module` — omitting them again is now a boot failure, not a leak.
                contentEdgeStore = contentEdgeStore,
                contentRefStore = contentRefStore,
                // The FREED state, over the same database so a push and a free serialise.
                contentFreeing = database?.let { SqliteContentFreedStore(it, contentRefStore) },
                contentBlobStore = contentBlobStore,
                shareStore = shareStore,
                inviteStore = inviteStore,
                grantStore = grantStore,
                membershipStore = membershipStore,
                rendezvousStore = rendezvousStore,
                admissionRevocationStore = admissionRevocationStore,
                pairingOfferStore = pairingOfferStore,
                blobStore = blobStore,
            contentReclaimJob = if (database != null && blobStore != null) ContentReclaimJob(database, blobStore) else null,
                // F12: account closure erases every store's rows as ONE transaction on this database.
                relayDatabase = database,
            )
        }
    ).start(wait = true)
}

/** Parent dir of the SQLite db file — the default home for sibling blob storage. */
private fun databaseBlobParent(databaseUrl: String?): java.io.File {
    val path = databaseUrl?.removePrefix("jdbc:sqlite:")?.takeIf { it.isNotBlank() }
        ?: return java.io.File(".")
    return java.io.File(path).absoluteFile.parentFile ?: java.io.File(".")
}

/**
 * Production / test entry point. [config] gates the dev-mode auth bypass; [knownDevices]
 * is the per-owner device-key registry consulted by JWT verification.
 *
 * For tests: pass a `RelayConfig(devMode = true, ...)` to keep the existing
 * `X-Dev-Owner` fixture working; pass `devMode = false` to require JWT.
 */
fun Application.module(
    config: RelayConfig,
    knownDevices: DeviceRegistry = KnownDevicesStore(),
    realmStore: RealmRegistry = RealmStore(),
    challengeStore: RecoveryChallengeStore = RecoveryChallengeStore(),
    hlcTipStore: HlcTipRegistry = HlcTipStore(),
    contentEdgeStore: ContentEdgeRegistry = ContentEdgeStore(),
    contentRefStore: ContentRefRegistry = ContentRefStore(),
    // The FREED state. Null builds the in-memory twin over the content stores above (dev mode, tests).
    contentFreeing: ContentFreeing? = null,
    syncMailbox: MailboxStore = SyncMailbox(),
    contentBlobStore: ContentBlobRegistry = ContentBlobStore(),
    shareStore: ShareRegistry = ShareStore(),
    inviteStore: InviteRegistry = InviteStore(),
    grantStore: GrantRegistry = GrantStore(),
    membershipStore: MembershipRegistry = MembershipStore(),
    rendezvousStore: RendezvousRegistry = RendezvousStore(),
    admissionRevocationStore: AdmissionRevocationRegistry = AdmissionRevocationStore(),
    pairingOfferStore: PairingOfferRegistry = PairingOfferStore(),
    hubOfferStore: HubOfferRegistry = HubOfferStore(),
    // Null in the in-memory dev configuration — there is no durable store to probe. See
    // `configureHealthRouting`, which reports that honestly rather than guessing. ⚠ It is not
    // the only thing probed: the database's own volume is probed separately, because S3BlobStore
    // can put this one in another datacentre.
    blobStore: BlobStore? = null,
    // ⚠ Null unless durable: the operator routes it drives exist only over SQLite + a BlobStore.
    contentReclaimJob: ContentReclaimJob? = null,
    // The G2 content sweep. Null builds one from `config`'s CONTENT_SWEEP_* over the stores above; a test
    // passes its own (a test clock, a mode) and drives `runOnce` itself. ⚠ Its `mode` is also what D5's
    // quota exclusion reads, so the routes and the sweep can never disagree.
    contentSweepJob: ContentSweepJob? = null,
    // F12: the one database the durable stores share. Only account-closure erasure uses it, to run
    // every store's row deletes as a single transaction. Null in the in-memory configuration.
    relayDatabase: RelayDatabase? = null,
    // F12. Null builds one over every store above (and [relayDatabase]'s transaction); a test may pass
    // its own to observe or fail it.
    accountErasure: AccountErasure? = null,
    ed25519: Ed25519Backend = BouncyCastleEd25519Backend()
) {
    // ⚠⚠ **A durable deployment may not run an in-memory content store.** Every parameter above
    // carries an in-memory default so tests and dev mode need no wiring — which is exactly how
    // `contentEdgeStore` and `contentRefStore` came to be built in `main` and never passed: the
    // defaults won silently, every declaration lived in RAM until the next restart, and nothing was
    // ever written to `content_edge` / `content_ref` on production. Two consequences followed for
    // months — CR-5's candidate query was always empty, and the CR-6e inventory always reported
    // `describedContentCount = 0`.
    //
    // Refusing to boot is the same posture as `s3PartiallyConfigured` below, and for the same
    // reason: a deploy that looks successful while putting the data somewhere the operator did not
    // intend is worse than one that does not start. ⚠ Scoped to `databaseUrl != null`, so dev mode
    // and `testModule` (which pass in-memory stores deliberately) are untouched.
    require(config.databaseUrl == null || contentEdgeStore is SqliteContentEdgeStore) {
        "A database is configured but contentEdgeStore is ${contentEdgeStore::class.simpleName} — " +
            "declarations would be lost on every restart. Pass the SQLite store from main()."
    }
    require(config.databaseUrl == null || contentRefStore is SqliteContentRefStore) {
        "A database is configured but contentRefStore is ${contentRefStore::class.simpleName} — " +
            "ref sets would be lost on every restart. Pass the SQLite store from main()."
    }
    // F11: revocation forgets the device's tip, in one transaction with the
    // registration delete on a SQLite relay. ⚠ Over the same stores the routes read.
    val deviceRevocation = org.tenshu.sync.relay.auth.DeviceRevocation(
        knownDevices = knownDevices,
        hlcTipStore = hlcTipStore,
        transaction = relayDatabase?.let { db -> { block -> db.inTransaction { block() } } } ?: { block -> block() },
        // Both pairing flows register on delivery, so a queued admission orders tombstones too.
        pendingAdmittedAt = { owner, device ->
            listOfNotNull(
                syncMailbox.pendingPairingAdmittedAt(owner, device),
                pairingOfferStore.pendingAdmittedAt(owner, device),
            ).maxOrNull()
        },
    )
    val freeing: ContentFreeing = contentFreeing
        ?: ContentFreedStore(contentEdgeStore, contentRefStore, contentBlobStore)
    // ⚠ The same posture for B4r's FREED set: forgetting it on restart would let a refused push through.
    require(config.databaseUrl == null || freeing is SqliteContentFreedStore) {
        "A database is configured but contentFreeing is ${freeing::class.simpleName} — " +
            "the FREED set would be lost on every restart. Pass the SQLite store from main()."
    }
    // ⚠ F12, same posture: a durable relay that can erase accounts must do it as ONE transaction, or a
    // failure halfway leaves a half-erased owner (correct on the next run, but not atomic meanwhile).
    require(config.databaseUrl == null || !config.accountClosureReclaim || accountErasure != null || relayDatabase != null) {
        "ACCOUNT_CLOSURE_RECLAIM is on over a database but relayDatabase was not passed — closure would " +
            "erase store by store instead of in one transaction. Pass the database from main()."
    }

    // ⚠ Keyed on the client IP, so it is only meaningful with XForwardedHeaders installed
    // (below) — behind Caddy every request otherwise shares the proxy's address and one abuser
    // would spend everybody's budget.
    val authFailureLimiter = AuthFailureLimiter(limitPerHour = config.authFailureLimitPerHour)
    val jwtAuthenticator = JwtAuthenticator(
        knownDevices = knownDevices,
        ed25519 = ed25519,
        authFailureLimiter = authFailureLimiter,
    )
    // Commerce entitlement-token verifier (commerce-billing-plane.md §8 / D2): stood up when
    // ENTITLEMENT_ISSUER_PUBKEY is configured, else null. Verifier-only, stateless, no commerce
    // callback on the hot path — same posture as the device-key JwtAuthenticator above. Whether a
    // verified token *gates* anything is the separate ENTITLEMENT_ENFORCE flag (§8): off →
    // verify-only (gates nothing); on → write endpoints reject a write lacking a live,
    // owner-bound token. The flag is meaningless without a configured key (no verifier → no gate).
    val entitlementVerifier = RelayEntitlementVerifier.fromConfig(config, ed25519)
    log.info(
        when {
            entitlementVerifier == null ->
                "Entitlement verification disabled (set ENTITLEMENT_ISSUER_PUBKEY to enable)"
            config.entitlementEnforce ->
                "Entitlement ENFORCED on writes (§8 Phase 2): writes require a live, owner-bound token"
            else ->
                "Entitlement verification enabled, gates nothing (set ENTITLEMENT_ENFORCE=true to gate writes)"
        }
    )
    if (config.entitlementEnforce && entitlementVerifier == null) {
        log.warn(
            "ENTITLEMENT_ENFORCE=true but ENTITLEMENT_ISSUER_PUBKEY is unset — enforcement is a no-op " +
                "(no issuer key to verify against). Set the pubkey to actually gate writes."
        )
    }

    install(ContentNegotiation) {
        json()
    }
    // One line per request: status, method, matched ROUTE TEMPLATE and duration —
    // `200 OK: GET /share/{shareId} in 12ms`. Never the concrete path: share ids, grant addresses,
    // EPC session ids, invite ids and content refs travel in path segments and are capabilities
    // (relay-metadata-minimisation F4). Unmatched requests log `<unmatched>`, never their path.
    // No query string, no client IP, no headers. ⚠ Keep it that way (docs/how-to/server-logging.md).
    recordRouteTemplates()
    install(CallLogging) {
        level = Level.INFO
        format(::formatCallLogLine)
    }
    // Per-IP rate limit on the unauthenticated `POST /sync/realm` bootstrap (the first concrete
    // piece of the layered abuse defence in docs/how-to/deploy-relay-digitalocean.md §"DDoS &
    // abuse"). Installed only when configured (> 0) so the default test/in-memory path is
    // unchanged and `configureRealmRouting` can mount the route unwrapped. We key on the *client*
    // IP, not the socket peer — behind Caddy every request originates from Caddy's address, so
    // without X-Forwarded-For the bucket would be global and a single abuser would throttle
    // everyone. XForwardedHeaders makes `request.origin.remoteAddress` reflect the forwarded
    // client; direct/dev binds (no proxy) fall back to the real socket peer. Trusting XFF is safe
    // here: Caddy is the only ingress and direct :8080 access is firewalled off.
    val genesisRateLimited = config.genesisRateLimitPerHour > 0
    // The other credential-less write (`POST /sync/pairing/request`) — same keying, same reasons.
    val pairingRequestRateLimited = config.pairingRequestRateLimitPerHour > 0
    // And the recovery challenge (T-RC-08 (a)) — same keying, its own size-bounded limiter.
    val recoveryChallengeRateLimiter = config.recoveryChallengeRateLimitPerMinute
        .takeIf { it > 0 }
        ?.let { ClientRateLimiter(limit = it, periodMs = 60_000L) }
    // ⚠ Installed for EITHER limiter: both key on the forwarded client IP, and the auth-failure
    // one is enabled by default, so tying this to the genesis flag alone would silently make its
    // key the proxy's address for every request.
    if (genesisRateLimited || pairingRequestRateLimited || recoveryChallengeRateLimiter != null ||
        authFailureLimiter.enabled
    ) {
        install(XForwardedHeaders)
    }
    if (genesisRateLimited || pairingRequestRateLimited) {
        install(RateLimit) {
            if (genesisRateLimited) {
                register(GenesisRateLimitName) {
                    rateLimiter(limit = config.genesisRateLimitPerHour, refillPeriod = 1.hours)
                    requestKey { call -> call.request.origin.remoteAddress }
                }
            }
            if (pairingRequestRateLimited) {
                register(PairingRequestRateLimitName) {
                    rateLimiter(limit = config.pairingRequestRateLimitPerHour, refillPeriod = 1.hours)
                    requestKey { call -> call.request.origin.remoteAddress }
                }
            }
        }
    }
    // The web SDK calls the relay cross-origin (apex on `tenshu.app` →
    // `relay.tenshu.app`); browsers block that without CORS preflight.
    // Native clients (Android/desktop) don't care — they're not subject to
    // same-origin. Threat model: the relay is zero-trust on payloads
    // anyway (everything is ciphertext), so the security gain from
    // origin-locking is "stop a malicious page from squatting a session
    // at the user's expense"; allowing the production apex + per-app
    // subdomains covers the legitimate web surface, and dev mode opens
    // it up for `localhost` apex iteration.
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        // ⚠ The web runtime uploads content with `PUT /sync/content/{ref}`; without PUT here the
        // browser's preflight fails and every web upload is blocked before it reaches the route.
        allowMethod(HttpMethod.Put)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        // ⚠ No `X-Dev-Owner`: no browser client sends it (the dev-mode bypass is a test fixture), so a
        // preflight never needs to allow it.
        allowHeader(RelayEntitlementVerifier.ENTITLEMENT_HEADER) // commerce entitlement token (§8)
        // The signed-record proof the web runtime sends on writes (`JsRuntime`); same preflight reason.
        allowHeader(RelayEntitlementVerifier.PROOF_HEADER)
        if (config.devMode) {
            anyHost()
        } else if (config.corsAllowedOrigins.isNotEmpty()) {
            // Deployment-supplied allowlist (CORS_ALLOWED_ORIGINS): the web app is served
            // from an origin other than the apex (a `*.fly.dev`, a staging/preview URL, a
            // custom domain). Each entry is a full `scheme://host[:port]` origin; ktor's
            // allowHost takes host + scheme separately, so split them.
            config.corsAllowedOrigins.forEach { origin ->
                val scheme = origin.substringBefore("://", missingDelimiterValue = "https")
                val hostPort = origin.substringAfter("://")
                allowHost(host = hostPort, schemes = listOf(scheme))
            }
        } else {
            // Default: apex + known per-app subdomains per `docs/web/architecture.md` §3.
            // This already covers the Mode-A share viewer, which is served from the apex
            // (`tenshu.app`). ktor's `subDomains` parameter takes literal names, not
            // wildcards; expanding to user-installed third-party apps requires a dynamic
            // allowlist (probably served from `:sync-relay-server` itself once the
            // app-install registry exists).
            allowHost(
                host = "tenshu.app",
                schemes = listOf("https"),
                subDomains = listOf("drive", "control")
            )
        }
    }
    // Per `sync-backend-relay-plan.md` §4.2: never echo internal exception text to
    // clients (`"500: $cause"` would leak stack frames, file paths, library versions
    // — useful to an attacker probing for vulnerabilities). Log the cause server-side
    // via the application logger; respond with a typed, content-free error.
    val appLogger = log
    val contentSweep = contentSweepJob ?: ContentSweepJob(
        mode = config.contentSweepMode,
        graceMs = config.contentGraceMs,
        intervalMs = config.contentSweepIntervalMs,
        edges = contentEdgeStore,
        freeing = freeing,
        refs = contentRefStore,
        blobs = contentBlobStore,
        drainer = contentReclaimJob,
        log = { appLogger.info(it) },
    )
    if (contentSweep.mode != ContentSweepMode.OFF) {
        appLogger.info(
            "Content sweep: ${contentSweep.mode.wire}, T=${contentSweep.graceMs} ms, every ${contentSweep.intervalMs} ms" +
                if (contentSweep.mode == ContentSweepMode.ARMED && contentReclaimJob == null) {
                    " — ⚠ no durable store: content is freed logically and its bytes are never deleted"
                } else {
                    ""
                },
        )
    }
    // The relay-wide cost brake. Content is measured exactly; shares at the per-chunk cap, an upper
    // bound (see ShareRegistry.declaredChunkTotal).
    val storageCeiling = config.storageCeilingBytes?.let { ceiling ->
        StorageCeiling(
            ceilingBytes = ceiling,
            measure = {
                contentBlobStore.totalBytes() + shareStore.declaredChunkTotal() * MAX_SHARE_CHUNK_BYTES
            },
            refreshMs = config.storageCeilingRefreshMs,
        ).also {
            appLogger.info("Storage ceiling: ${ceiling / StorageCeiling.BYTES_PER_GIB} GiB across all owners")
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            appLogger.error("Unhandled exception serving ${call.routeTemplate()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "internal_error")
            )
        }
    }

    routing {
        get("/") {
            // ⚠ Liveness of the PROCESS only — a static string that cannot fail. "Can this relay
            // accept a write?" is /health, which probes the blob volume. Do not add a check here.
            call.respondText("Tenshu Sync Relay is running!")
        }
        // Two independent volumes once S3BlobStore is in play, and BOTH must accept a write:
        // the bucket holds the bytes, but `relay.db` and every index row still live on the
        // droplet's disk. With FileBlobStore the two probes hit the same volume — harmlessly
        // redundant, and cheaper than making the wiring conditional on which backend is active.
        configureHealthRouting(
            buildList {
                config.databaseUrl?.let {
                    val dbVolume = VolumeWriteProbe(databaseBlobParent(it))
                    add(HealthProbe("database-volume") { dbVolume.probe() })
                }
                blobStore?.let { store -> add(HealthProbe("blob-store") { store.probe() }) }
            }
        )
        configureRealmRouting(
            realmStore,
            knownDevices,
            ed25519,
            genesisRateLimit = if (genesisRateLimited) GenesisRateLimitName else null
        )
        configureRecoveryRouting(
            realmStore = realmStore,
            challengeStore = challengeStore,
            knownDevices = knownDevices,
            ed25519 = ed25519,
            challengeRateLimiter = recoveryChallengeRateLimiter,
        )
        configureSyncRouting(
            config = config,
            jwtAuthenticator = jwtAuthenticator,
            realmStore = realmStore,
            knownDevices = knownDevices,
            hlcTipStore = hlcTipStore,
            deviceRevocation = deviceRevocation,
            syncMailbox = syncMailbox,
            ed25519 = ed25519,
            entitlementVerifier = entitlementVerifier,
            admissionRevocationStore = admissionRevocationStore,
            pairingOfferStore = pairingOfferStore,
            hubOfferStore = hubOfferStore,
            // `/push` and `/push-tombstone` write the edge registers in the mailbox's
            // transaction. ⚠ In `main` both are built over the one `database`, which is what makes
            // that a single SQLite transaction.
            contentEdges = contentEdgeStore,
            // G3 and REVIVE inside that transaction.
            contentFreeing = freeing,
            pairingRequestRateLimit = if (pairingRequestRateLimited) PairingRequestRateLimitName else null,
            // The member-device guard on `Device` tombstones: the store `/sync/realm-member/admit` records into.
            membershipStore = membershipStore,
        )
        // Delegated device-admission authority (delegated-device-admission-authority.md
        // §5, P2): master-signed revocation of a device's admission capability (A33).
        // No JWT — the realm-master signature is the authorisation, the
        // /sync/realm-member/revoke posture. Zero-trust: opaque ids, epochs, and
        // master-signed tokens only (House Rule #1).
        configureAdmissionRouting(
            admissionRevocationStore = admissionRevocationStore,
            realmStore = realmStore,
            ed25519 = ed25519
        )
        // Content plane (docs/adr/0017-file-content-two-plane-channel.md P3 / sync-relay-api.md §3.7):
        // PUT/GET opaque ciphertext chunk blobs by ref, JWT-authed + per-owner
        // scoped exactly like /sync/push. Zero-trust: stored verbatim, never
        // decrypted (House Rule #1).
        configureContentRouting(
            config = config,
            jwtAuthenticator = jwtAuthenticator,
            contentBlobStore = contentBlobStore,
            entitlementVerifier = entitlementVerifier,
            contentEdges = contentEdgeStore,
            contentRefs = contentRefStore,
            contentFreeing = freeing,
            // D5's held-bytes exclusion follows the sweep's mode.
            contentSweepMode = contentSweep.mode,
            storageCeiling = storageCeiling,
        )
        // ⚠⚠ Account-closure reclamation (retention-quota-model.md Face A / D4). Registers ONLY
        // when ACCOUNT_CLOSURE_RECLAIM is on AND an issuer key is configured — otherwise the route
        // does not exist at all, so the delete-capable surface is absent rather than merely
        // refusing. Default off; ON in production (maintainer decision, ⚖️ counsel review of the
        // retention text owed).
        configureAccountClosureRouting(
            config = config,
            verifier = RelayAccountClosureVerifier.fromConfig(config, ed25519),
            reclaimer = AccountClosureReclaimer(
                erasure = accountErasure ?: AccountErasure(
                    // ⚠⚠ Every owner-scoped store, so closure leaves nothing but the `erased_realm` tombstone
                    // behind (AccountErasure's table).
                    stores = ownerScopedStoresOf(
                        knownDevices = knownDevices,
                        realmStore = realmStore,
                        hlcTipStore = hlcTipStore,
                        syncMailbox = syncMailbox,
                        contentEdgeStore = contentEdgeStore,
                        contentRefStore = contentRefStore,
                        contentFreeing = freeing,
                        contentBlobStore = contentBlobStore,
                        shareStore = shareStore,
                        inviteStore = inviteStore,
                        grantStore = grantStore,
                        membershipStore = membershipStore,
                        rendezvousStore = rendezvousStore,
                        pairingOfferStore = pairingOfferStore,
                        admissionRevocationStore = admissionRevocationStore,
                    ),
                    transaction = relayDatabase?.erasureTransaction() ?: { block -> block() },
                ),
                config = config,
            ),
        )
        // ⚠⚠ Operator routes: reclamation of undescribed ciphertext (also needs a durable
        // store) and share takedown. Register ONLY with RELAY_OPERATOR_TOKEN set, and answer only a
        // loopback socket peer — see configureOperatorRouting.
        configureOperatorRouting(
            token = config.operatorToken,
            job = contentReclaimJob,
            sweep = contentSweep,
            // Notice-and-action: POST /ops/share/takedown (docs/how-to/take-down-a-share-link.md).
            shares = shareStore,
        )
        // Mode-A link sharing (docs/file-sharing.md). Sharer-only
        // POST/PUT/DELETE are JWT-required; recipient-side GETs are anonymous
        // by design (capability-as-URL). Zero-trust: chunk bodies stored
        // verbatim, never decrypted; `K_share` is never on the wire (URL
        // fragment only — A11).
        configureShareRouting(
            config = config,
            jwtAuthenticator = jwtAuthenticator,
            shareStore = shareStore,
            entitlementVerifier = entitlementVerifier,
            storageCeiling = storageCeiling,
        )
        // Contact-invitation handshake mailbox (contact-invitation-handshake.md
        // §6). Issuance/completion are JWT-authed; redemption-side calls are
        // anonymous by design (inviteId-as-capability; the invitee stays
        // protocol-anonymous). Zero-trust: the relay stores routing metadata +
        // sealed blobs only — never K_link or any hash of it (House Rule #1).
        configureInviteRouting(
            config = config,
            jwtAuthenticator = jwtAuthenticator,
            inviteStore = inviteStore
        )
        // Shared-realm membership grant (shared-realm-membership-grant.md §4, GR-2):
        // grant mailbox (JWT-authed drop, anonymous address-as-bearer pickup) +
        // member-device admission via the Q30 capability chain + master-signed
        // revocation. Zero-trust: routing metadata, public keys, sealed blobs, and
        // master-signed tokens only — never a realm key (House Rule #1).
        configureMembershipGrantRouting(
            config = config,
            jwtAuthenticator = jwtAuthenticator,
            grantStore = grantStore,
            membershipStore = membershipStore,
            realmStore = realmStore,
            knownDevices = knownDevices,
            deviceRevocation = deviceRevocation,
            ed25519 = ed25519,
            entitlementVerifier = entitlementVerifier
        )
        // App↔proxy EPC rendezvous mailbox (ios-proxy-architecture.md §5).
        // Session provisioning is JWT-gated to a realm device (the admission gate);
        // frame post/poll are anonymous by the high-entropy session id. Zero-trust:
        // the relay stores routing metadata + opaque sealed EpcSession frames only —
        // never the pairing secret or a session key (House Rule #1).
        configureEpcRendezvousRouting(
            config = config,
            jwtAuthenticator = jwtAuthenticator,
            rendezvousStore = rendezvousStore
        )
    }

    // Tombstone GC sweep (`docs/adr/0006-sync-tombstones.md`). Drops body
    // bytes once every realm device has ack'd a tombstone via the HLC-tip piggyback
    // AND the per-volume retention window has elapsed. The job rides the application
    // coroutine scope, so `monitor.subscribe(ApplicationStopped)` cancellation is
    // automatic — the explicit subscribe here is for symmetry + a clean shutdown log.
    val gcJob = launch {
        while (isActive) {
            delay(RelayConfig.GC_SWEEP_INTERVAL_MS)
            try {
                syncMailbox.gcSweep(knownDevices, hlcTipStore)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                appLogger.error("Tombstone GC sweep failed; continuing", t)
            }
        }
    }
    monitor.subscribe(ApplicationStopped) { gcJob.cancel() }

    // G2's sweep (`content-plane-cr2-b5-spec.md` §1.2). Not launched in `off`: the clock is kept
    // by the edge stores in every mode, and there is nothing else to do. Same lifecycle as `gcJob`.
    if (contentSweep.mode != ContentSweepMode.OFF) {
        val contentSweepLoop = launch {
            while (isActive) {
                delay(contentSweep.intervalMs)
                try {
                    contentSweep.runOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    appLogger.error("Content sweep (${contentSweep.mode.wire}) failed; continuing", t)
                }
            }
        }
        monitor.subscribe(ApplicationStopped) { contentSweepLoop.cancel() }
    }

    // Content-plane sweep seam. NOTE: content is **durable user data** — the
    // production retention window is `Long.MAX_VALUE` (disabled), so this sweep
    // is a **no-op** and reaps nothing (see `RelayConfig.CONTENT_RETENTION_WINDOW_MS`
    // + `ContentBlobStore` KDoc; `file-content-addressing.md` §4.2). It is NOT a
    // time-reaper: a file unopened for months is still live and its blobs must
    // never age out. The job is kept armed only as the heartbeat for a future
    // account-lifecycle reclamation (owner over-quota / entitlement lapse →
    // reclaim that owner's content, keyed on `ownerId`). Same lifecycle hygiene
    // as `gcJob` (rides the application scope; explicit cancel for a clean log).
    val contentGcJob = launch {
        while (isActive) {
            delay(RelayConfig.CONTENT_GC_SWEEP_INTERVAL_MS)
            try {
                contentBlobStore.gcSweep()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                appLogger.error("Content-plane GC sweep failed; continuing", t)
            }
        }
    }
    monitor.subscribe(ApplicationStopped) { contentGcJob.cancel() }

    // Share-store retention sweep (`docs/file-sharing.md`
    // / §3.6). Drops revoked / expired / orphan ShareRecords + their chunks one
    // retention window past the trigger event. Same nightly cadence and
    // lifecycle hygiene as the content-plane GC.
    val shareGcJob = launch {
        while (isActive) {
            delay(RelayConfig.CONTENT_GC_SWEEP_INTERVAL_MS)
            try {
                shareStore.gcSweep()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                appLogger.error("Share-store GC sweep failed; continuing", t)
            }
        }
    }
    monitor.subscribe(ApplicationStopped) { shareGcJob.cancel() }

    // Invite-mailbox retention sweep (contact-invitation-handshake.md §6):
    // record until expiry; a posted response/ack extends it 72 h past
    // `respondedAt` so an offline party can still complete; `done` deletes
    // eagerly and this sweep is the fallback. Hourly — invites are the relay's
    // shortest-lived state (72 h default, 7-day cap).
    val inviteGcJob = launch {
        while (isActive) {
            delay(RelayConfig.INVITE_GC_SWEEP_INTERVAL_MS)
            try {
                inviteStore.gcSweep()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                appLogger.error("Invite-mailbox GC sweep failed; continuing", t)
            }
        }
    }
    monitor.subscribe(ApplicationStopped) { inviteGcJob.cancel() }

    // Grant-mailbox retention sweep (shared-realm-membership-grant.md §4, D4): an
    // unaccepted grant expires after 30 days; single-pickup deletes eagerly and this
    // sweep is the fallback for grants the grantee never picks up. Nightly — grants
    // are long-lived relative to invites; the revocation list + admission rows are
    // durable and never swept (a revocation is permanent).
    val grantGcJob = launch {
        while (isActive) {
            delay(RelayConfig.GRANT_GC_SWEEP_INTERVAL_MS)
            try {
                grantStore.gcSweep()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                appLogger.error("Grant-mailbox GC sweep failed; continuing", t)
            }
        }
    }
    monitor.subscribe(ApplicationStopped) { grantGcJob.cancel() }

    // EPC rendezvous retention sweep (ios-proxy-architecture.md §5): sessions
    // are minutes-scale (15-min TTL), so the sweep drops expired sessions + their
    // buffered frames hourly. A cheap two-table walk; same lifecycle hygiene as above.
    val rendezvousGcJob = launch {
        while (isActive) {
            delay(RelayConfig.RENDEZVOUS_GC_SWEEP_INTERVAL_MS)
            try {
                rendezvousStore.gcSweep()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                appLogger.error("EPC rendezvous GC sweep failed; continuing", t)
            }
        }
    }
    monitor.subscribe(ApplicationStopped) { rendezvousGcJob.cancel() }

    // Offer-flow pairing rendezvous retention sweep (sync-pairing-inverted-direction.md
    // §3.4): entries stop being served after minutes (PAIRING_OFFER_RESPONSE_TTL_MS) but
    // the row doubles as the single-use consumed marker (T-P3), retained for
    // CONSUMED_RETENTION_MS before this hourly sweep drops it.
    val pairingOfferGcJob = launch {
        while (isActive) {
            delay(RelayConfig.PAIRING_OFFER_GC_SWEEP_INTERVAL_MS)
            try {
                pairingOfferStore.gcSweep()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                appLogger.error("Pairing-offer GC sweep failed; continuing", t)
            }
        }
    }
    monitor.subscribe(ApplicationStopped) { pairingOfferGcJob.cancel() }

    // Hub-offer rendezvous sweep (hub proposal §11.3, H2): entries expire after
    // HUB_OFFER_TTL_MS and are consumed on fetch anyway — this just drops abandoned
    // arming windows. Same hourly cadence as the pairing-offer sweep.
    val hubOfferGcJob = launch {
        while (isActive) {
            delay(RelayConfig.PAIRING_OFFER_GC_SWEEP_INTERVAL_MS)
            try {
                hubOfferStore.gcSweep()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                appLogger.error("Hub-offer GC sweep failed; continuing", t)
            }
        }
    }
    monitor.subscribe(ApplicationStopped) { hubOfferGcJob.cancel() }
}

/**
 * Overload for tests that don't thread a `RelayConfig` through their
 * `testApplication { application { module() } }` setup. Defaults to dev-mode on a loopback bind so
 * the `X-Dev-Owner` test fixture is accepted. ⚠ That bypass is kept deliberately: no client or
 * script sends it, but ~200 relay test call sites use it as their owner fixture. Its exposure is
 * bounded — `RelayConfig.validate` refuses it on a non-loopback config,
 * and `main()` warns loudly ([RelayConfig.isDevModePubliclyExposed]) when local dev binds `0.0.0.0`.
 */
fun Application.module() = module(
    RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080)
)

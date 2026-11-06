package org.tenshu.sync.relay

import io.ktor.server.application.Application
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.StubEd25519Backend
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteContentEdgeStore
import org.tenshu.sync.relay.storage.SqliteContentFreedStore
import org.tenshu.sync.relay.storage.SqliteContentRefStore
import org.tenshu.sync.relay.mailbox.ContentBlobRegistry
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentFreeing
import org.tenshu.sync.relay.mailbox.ContentRefRegistry
import org.tenshu.sync.relay.mailbox.ContentRefStore
import org.tenshu.sync.relay.mailbox.GrantStore
import org.tenshu.sync.relay.mailbox.AdmissionRevocationStore
import org.tenshu.sync.relay.mailbox.HlcTipStore
import org.tenshu.sync.relay.mailbox.HubOfferStore
import org.tenshu.sync.relay.mailbox.InviteStore
import org.tenshu.sync.relay.mailbox.MembershipStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.mailbox.RecoveryChallengeStore
import org.tenshu.sync.relay.mailbox.PairingOfferStore
import org.tenshu.sync.relay.mailbox.RendezvousStore
import org.tenshu.sync.relay.mailbox.ShareStore

/**
 * Test-side wrapper around [module] that defaults the Ed25519 backend to
 * [StubEd25519Backend] so existing test fixtures (which mint tokens via
 * `stubEd25519Signature(...)`) keep verifying. Production wires
 * `BouncyCastleEd25519Backend` via [module]'s own default.
 *
 * Tests that want real crypto can pass `ed25519 = BouncyCastleEd25519Backend()`
 * explicitly.
 */
fun Application.testModule(
    config: RelayConfig = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
    knownDevices: KnownDevicesStore = KnownDevicesStore(),
    realmStore: RealmStore = RealmStore(),
    challengeStore: RecoveryChallengeStore = RecoveryChallengeStore(),
    hlcTipStore: HlcTipStore = HlcTipStore(),
    syncMailbox: SyncMailbox = SyncMailbox(),
    // ⚠ The REGISTRY, matching [module]'s own signature: a harness that only accepts the concrete
    // in-memory store cannot wire a decorator (a counting or failing one), and a property about what
    // a route *reads* is then untestable through the module at all.
    contentBlobStore: ContentBlobRegistry = ContentBlobStore(),
    // ⚠⚠ Null means "match what a relay with this config would really run" — see below. Passing an
    // explicit store still wins, which is what almost every test does.
    contentEdgeStore: ContentEdgeRegistry? = null,
    contentRefStore: ContentRefRegistry? = null,
    // ⚠ Null: in-memory over the stores above, or SQLite when the config names a database (as above).
    contentFreeing: ContentFreeing? = null,
    shareStore: ShareStore = ShareStore(),
    inviteStore: InviteStore = InviteStore(),
    grantStore: GrantStore = GrantStore(),
    membershipStore: MembershipStore = MembershipStore(),
    rendezvousStore: RendezvousStore = RendezvousStore(),
    admissionRevocationStore: AdmissionRevocationStore = AdmissionRevocationStore(),
    // The registry, so a test can wire a clock-shifting decorator (the route stamps wall-clock time).
    pairingOfferStore: org.tenshu.sync.relay.mailbox.PairingOfferRegistry = PairingOfferStore(),
    hubOfferStore: HubOfferStore = HubOfferStore(),
    blobStore: org.tenshu.sync.relay.storage.BlobStore? = null,
    contentReclaimJob: org.tenshu.sync.relay.storage.ContentReclaimJob? = null,
    // CR-2 B5r. Null builds the sweep from `config` (its mode drives D3/D5), as `module` does.
    contentSweepJob: org.tenshu.sync.relay.storage.ContentSweepJob? = null,
    ed25519: Ed25519Backend = StubEd25519Backend()
) = module(
    config = config,
    knownDevices = knownDevices,
    realmStore = realmStore,
    challengeStore = challengeStore,
    hlcTipStore = hlcTipStore,
    syncMailbox = syncMailbox,
    contentBlobStore = contentBlobStore,
    contentEdgeStore = contentEdgeStore ?: defaultContentEdgeStore(config),
    contentRefStore = contentRefStore ?: defaultContentRefStore(config),
    contentFreeing = contentFreeing ?: config.databaseUrl?.let { url ->
        RelayDatabase(url).let { SqliteContentFreedStore(it, SqliteContentRefStore(it)) }
    },
    shareStore = shareStore,
    inviteStore = inviteStore,
    grantStore = grantStore,
    membershipStore = membershipStore,
    rendezvousStore = rendezvousStore,
    admissionRevocationStore = admissionRevocationStore,
    pairingOfferStore = pairingOfferStore,
    hubOfferStore = hubOfferStore,
    blobStore = blobStore,
    contentReclaimJob = contentReclaimJob,
    contentSweepJob = contentSweepJob,
    ed25519 = ed25519
)

/**
 * ⚠⚠ **A harness that is laxer than production hides production's bugs.**
 *
 * These used to default to the in-memory stores unconditionally, even when the config named a
 * database — so a test could model a durable relay while exercising a map. That is precisely the
 * shape of the defect this exists to prevent: `main()` built the SQLite stores and never passed
 * them, declarations lived in RAM until the next restart, and **every route test was green** because
 * this harness wired something production did not.
 *
 * ⚠ A fresh [RelayDatabase] per store is fine here — SQLite handles concurrent connections to one
 * file, and a test's database is its own temp file.
 */
private fun defaultContentEdgeStore(config: RelayConfig): ContentEdgeRegistry =
    config.databaseUrl?.let { SqliteContentEdgeStore(RelayDatabase(it)) } ?: ContentEdgeStore()

private fun defaultContentRefStore(config: RelayConfig): ContentRefRegistry =
    config.databaseUrl?.let { SqliteContentRefStore(RelayDatabase(it)) } ?: ContentRefStore()

package org.tenshu.contact.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.contact.common.InviteRelayClient
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.contact.proxy.InviteResponsePickup
import org.tenshu.contact.proxy.PendingRedemptionStore
import org.tenshu.contact.proxy.contactServiceProxyImplementationFactory
import org.tenshu.contact.proxy.initContactService
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.sync.common.TombstoneEmitterHolder
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager

/**
 * One side of an in-process handshake test: a [TestProxy] wired with the
 * contact service's implementation factory + relation maintainer, plus the
 * pieces a test inspects directly. Mirrors `createPreferenceProxy`; the
 * stub crypto's `publicKey == privateKey` invariant carries the sealed-box
 * round trip (the real-crypto coverage is `SealToIdentityTest` + the
 * dev-relay system test).
 */
class ContactTestHarness(
    val proxy: TestProxy,
    val components: ContactProxyComponents,
    val pickup: InviteResponsePickup,
)

fun createContactProxy(
    relay: InviteRelayClient,
    identityPhrase: String,
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    cryptoManager: SyncCryptoManager = BootstrapSyncCryptoManager(),
    ackPollIntervalMs: Long = 10L,
    ackPollMaxAttempts: Int = 3,
    /** The proxy's own in-memory byte store when `null`. */
    fileDatabase: FileDatabase? = null,
    syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    tombstoneEmitterHolder: TombstoneEmitterHolder? = null,
    objectStore: ObjectStore = InMemoryObjectStore(),
    /** The store's own lock when it has one (the iOS SQLite store); the test proxy's otherwise. */
    lock: DatabaseLock? = null,
): ContactTestHarness {
    // The components need the proxy's byte store, and the proxy's factory needs the components:
    // the factory reads them lazily, at first dispatch.
    lateinit var components: ContactProxyComponents
    val factory = { id: String, _: TestProxy -> contactServiceProxyImplementationFactory(id, components) }
    val proxy = if (lock != null) {
        TestProxy(
            schemaRegistry = createContactTestRegistry(),
            coroutineScope = coroutineScope,
            objectStore = objectStore,
            lock = lock,
            implementationFactoryOverride = factory,
        )
    } else {
        TestProxy(
            schemaRegistry = createContactTestRegistry(),
            coroutineScope = coroutineScope,
            objectStore = objectStore,
            implementationFactoryOverride = factory,
        )
    }
    components = ContactProxyComponents(
        relayClient = relay,
        cryptoManager = cryptoManager,
        identityKeyPair = { cryptoManager.deriveRealmEncryptionKeyPair(identityPhrase) },
        redemptionStore = PendingRedemptionStore(InMemorySecureStorageDelegate()),
        ackPollIntervalMs = ackPollIntervalMs,
        ackPollMaxAttempts = ackPollMaxAttempts,
        fileDatabase = fileDatabase ?: proxy.fileDatabase,
        syncBlockEmitterHolder = syncBlockEmitterHolder,
        tombstoneEmitterHolder = tombstoneEmitterHolder,
    )
    proxy.executionContext.initContactService { components.fileDatabase }
    return ContactTestHarness(
        proxy = proxy,
        components = components,
        pickup = InviteResponsePickup(proxy.executionContext, components),
    )
}

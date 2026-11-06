package app.tenshu.sdk.internal

import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SemanticMerger
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.TombstoneEmitterHolder
import org.tenshu.sync.proxy.SyncSchedulerHolder
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.HlcWatermarkStore
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.sync.proxy.RealmRegistryHolder
import org.tenshu.sync.proxy.SyncProcessorHolder
import org.tenshu.storage.proxy.EntitlementRecordCredentialProvider
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.StorageProvider

// Phase 1 of `docs/proposals/web-sdk-bundle-splitting.md`: the plugin seam
// the JS in-process runtime composes services through, replacing the four
// hard-coded service-wiring sites the monolithic [JsRuntime] used to carry
// (extraConfigs / mergerRegistry / operation-factory chain / init lambda).
//
// Phase 1 is an in-place refactor only: all four first-party plugins still
// live inside `:tenshu-sdk-js` and are registered eagerly (see
// [JsRuntimeBuilder]). The interface is the seam Phase 2 splits along — at
// that point each plugin moves into its own per-service Gradle module and
// registers itself on dynamic import. Nothing about the bundle topology
// changes in Phase 1.

/**
 * A unit of service composition for the JS in-process runtime. Each
 * first-party service contributes one plugin; Phase 4's third-party services
 * will register through this same contract (see the proposal §4.1).
 */
internal interface JsRuntimePlugin {

    /**
     * Stable identifier. Used for deduplication, [dependsOn] ordering, and to
     * surface "service X is registered" to higher-level consumers.
     */
    val id: String

    /**
     * Schema/module contributions — the `*ServiceCommonModule` /
     * `*ServiceClientModule` pairs the runtime feeds into
     * `createDefaultSchemaRegistry`. Registration is idempotent and
     * prerequisite-walking (see `SchemaRegistry.registerConfig`), so listing a
     * module whose prerequisites overlap another plugin's is safe.
     */
    val schemaConfigs: List<ModuleConfiguration> get() = emptyList()

    /**
     * Operation-factory contribution. The runtime composes every registered
     * plugin's factory into one dispatch chain looked up per call; the first
     * non-null wins. Order is registration/[dependsOn] order. The returned
     * factory should read relay/manager state from [ctx] lazily inside its
     * lambda so building the chain doesn't force construction of the relay.
     */
    fun operationFactory(ctx: RuntimeContext): OperationImplementationFactory? = null

    /** Semantic mergers this service contributes (e.g. files' `FolderMerger`). */
    val mergers: List<SemanticMerger> get() = emptyList()

    /**
     * Per-plugin service initialization, run inside the runtime's
     * `ClientRegistry.initialization` hook. [executionContext] is the
     * registry-provided context (the receiver the legacy `initFileService()`
     * etc. ran against); [runtime] exposes the cross-service resources sync
     * init needs.
     *
     * Deviation from proposal §4.1's single-`ExecutionContext` signature: sync
     * init needs `syncManager` / `cryptoManager` / `mergerRegistry` /
     * `serviceScope`, which live on [RuntimeContext], not `ExecutionContext`.
     * Collapsible to one arg once sync init is decomposed further. Non-suspend
     * because `ClientRegistry.initialization` is a plain `ExecutionContext.() ->
     * Unit` and the underlying `initFileService()` / `initSyncService(...)` etc.
     * are synchronous.
     */
    fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {}

    /**
     * Plugin ids that must be ordered before this one. Empty for every Phase-1
     * first-party plugin (registration order already produces the legacy
     * wiring order); the field exists for the Phase-2 split where, e.g., the
     * onboarding/sync bundle declares the identity bundle as a prerequisite.
     */
    val dependsOn: Set<String> get() = emptySet()
}

/**
 * Cross-service runtime resources a plugin's [JsRuntimePlugin.operationFactory]
 * and [JsRuntimePlugin.initialize] reach for. [JsRuntime] is the sole
 * implementation; this interface is the narrow surface plugins see.
 *
 * Per proposal §6.3 the tombstone emitter is a runtime-shared concern, not a
 * sync-private one: sync populates [tombstoneEmitterHolder] during init and
 * files/notifications/identity read it from here in their factories.
 */
internal interface RuntimeContext {
    val fileDatabase: FileDatabase
    val syncRelay: SyncRelay
    val syncManager: SyncManager
    val syncCryptoManager: SyncCryptoManager
    val tombstoneEmitterHolder: TombstoneEmitterHolder
    val syncSchedulerHolder: SyncSchedulerHolder
    val mergerRegistry: SemanticMergerRegistry
    val serviceScope: CoroutineScope

    // Storage-service wiring (web-storage-status.md Bucket 1). The
    // [StorageProvider] feeds `StorageStatus`'s figures; the
    // [StorageCapacityHolder] is the Tier-2 enforcement latch the storage
    // operation factory reads (`CheckCapacity`). Both are consumed by the
    // storage plugin's `operationFactory` / `initialize` (FirstPartyPlugins).
    val storageProvider: StorageProvider
    val storageCapacityHolder: StorageCapacityHolder

    /**
     * Track B3 (`second-device-entitlement-enforcement.md`): holds the owner's synced signed
     * `Entitlement` and hands out the `X-Entitlement-Proof` the relay requires on writes.
     *
     * ⚠⚠ The web is **always** a session-less device: it never holds a commerce bearer token
     * (`X-Entitlement`), because the commerce session is device-local PII that by House Rule #1
     * cannot sync. So the signed-record proof is the *only* write credential a browser can ever
     * present — without it every `POST /sync/push` is `402 entitlement_required` under
     * `ENTITLEMENT_ENFORCE`, permanently, while reads stay ungated. That asymmetry is silent
     * one-way sync, which is exactly what shipped before this seam existed.
     *
     * Fed by the storage plugin (the reader's `recordSink`), read by the sync relay's
     * `entitlementProofProvider` — the same two-ended handoff as the three native platforms.
     */
    val entitlementCredentialProvider: EntitlementRecordCredentialProvider

    // retention-quota-model.md §5 / D5 (P1): the relay-reported per-owner usage seam —
    // a best-effort `GET /sync/usage` (via a `RelayUsageClient` over the same device-key
    // JWT + `HttpClient(Js)`) that feeds the real `usedBytes` into `StorageStatus`,
    // replacing `EntitlementStorageProvider`'s `0L` mock. Consumed by the storage plugin's
    // `initialize` (FirstPartyPlugins). `null` leaves usage at 0.
    /** P1b: `(usedBytes, limitBytes?)` — the relay reports the entitled limit alongside usage. */
    val relayUsageReader: (suspend () -> Pair<Long, Long?>?)?

    // Contact-service wiring (drive-web-mode-b-sharing.md MB-1). The dependency
    // bundle the contact operation factory + the inviter-side [InviteResponsePickup]
    // read: a `RestInviteRelayClient` over the same device-key JWT seam the sync
    // relay uses, the A13 identity-key seam through `syncManager`, and the
    // IndexedDB-backed redemption store. Lazy — building it constructs no relay
    // socket (Ktor `HttpClient(Js)` defers to the browser's `fetch`), so reading
    // it per contact dispatch costs nothing until a contact op actually runs.
    val contactProxyComponents: ContactProxyComponents

    // Shared-realm membership grant runtime (drive-web-mode-b-sharing.md MB-2 /
    // GR-1..GR-7). Three seams the sync plugin threads so grant/accept/poll/
    // remove resolve and a shared realm can push/pull its member roster:
    //
    //  - [realmRegistryHolder] — populated by the sync plugin's `initialize` from
    //    `SyncProxyComponents.realmRegistry`; read at dispatch time (the factory
    //    chain is built before init runs, same shape as [syncSchedulerHolder]).
    //  - [grantedRelayFactory] — `perRealmDeviceRelay`: a per-realm-device-key
    //    `SyncRelay` for a *granted* (cross-user) realm (D2 unlinkability); feeds
    //    the grant op impls + the `MemberSelfBindObserver`.
    //  - [sharedRealmRelayFactory] — an A4-signed per-shared-realm `SyncRelay`
    //    keyed by `ownerId`; the registry's `relayFactory`, so a member's accept
    //    writes a `Member` row that actually syncs back to the owner.
    val realmRegistryHolder: RealmRegistryHolder
    val grantedRelayFactory: (RealmContext) -> SyncRelay?
    val sharedRealmRelayFactory: (String) -> SyncRelay?
    val nowMs: () -> Long

    // Mode-B file read content plane (drive-web-mode-b-sharing.md MB-3). A
    // shared file's chunks live in the realm's *own* content store, AEAD'd under
    // the realm keys; `readSharedFile` fetches them lazily on open through the
    // realm runtime's `OnDemandResolution`. Two more seams the sync plugin
    // threads so the per-realm `SyncProcessor` builds a `FileContentManifestApplier`:
    //
    //  - [syncProcessorHolder] — populated by `initialize` from
    //    `SyncProxyComponents` (the personal processor); read by the
    //    `OnDemandResolutionImpl` so a personal OnDemand fetch reuses the
    //    canonical processor (a shared-realm fetch routes via the realm registry).
    //  - [sharedRealmContentBlobStoreFactory] — a per-shared-realm
    //    `ContentBlobStore` keyed by `ownerId`; with `fileDatabase` it lets the
    //    realm processor's applier decrypt + assemble the realm's file chunks.
    //    GR-7 retained-key trial-decrypt rides `RealmContext.historicalDerivedKeys`
    //    automatically once this is wired (no extra seam).
    val syncProcessorHolder: SyncProcessorHolder
    val sharedRealmContentBlobStoreFactory: (String) -> ContentBlobStore?

    /**
     * The **personal** realm's content plane (`drive-web-parity.md` W1) — the seam the
     * shared-realm factory above has had since MB-3 and the personal side never did.
     *
     * ⚠⚠ One null here severs personal file bytes in **both** directions, and neither
     * failure surfaces as an error:
     *  - **inbound** — `initSyncService` builds a `FileContentManifestApplier` only when a
     *    content store *and* a [fileDatabase] are both present, so an arriving
     *    `FileContentManifestPayload` is a clean no-op. Everything upstream already works
     *    on web (the `OnDemandResolutionImpl` fetch, the published personal processor, the
     *    terminal re-resolve) — this is the one step that writes the bytes down, and
     *    without it `File.Data` resolves `null` forever.
     *  - **outbound** — `SyncObserver.emitFileContent` opens `contentBlobStore ?: return
     *    false`, so a browser upload writes IndexedDB, emits its metadata block, and never
     *    offers the content. The row appears on the phone; the file is empty there.
     *
     * ⚠ The asymmetry is why this hid for two months: a file uploaded *in the browser*
     * opens fine *in the browser*, because those bytes are local and the plane was never
     * needed. Only cross-device reads fail. "Web shows no thumbnails" was this — web
     * renders previews from the file's own bytes, so no bytes means no thumbnail.
     *
     * ⚠ Deliberately **non-nullable**, unlike the shared-realm factory above. `initSyncService`
     * accepts a null here and degrades to metadata-only sync silently — which is exactly the
     * failure this seam exists to end, so the type makes it unrepresentable rather than
     * leaving a runtime assertion to catch it later. A wiring that genuinely wants no content
     * plane should say so at the `initSyncService` call, where it reads as a choice.
     */
    val contentBlobStore: ContentBlobStore

    /**
     * The device's durable HLC watermark (`sync-hlc-tip-device-identity.md` TC-2).
     *
     * ⚠ Non-nullable on purpose. Every tab of this origin runs its own runtime and its own
     * `HLCManager` under **one** shared device ID, while the relay keeps a single tip slot per
     * device — so a tab that publishes its own process clock instead of the shared watermark makes
     * that slot go backwards, which peers read as the T-T-04 forked-view tell. There is no
     * browser configuration where opting out is correct, and the browser already gives us the
     * cross-tab serialisation for free.
     */
    val hlcWatermarkStore: HlcWatermarkStore

    // Mode-B file write content plane (drive-web-mode-b-sharing.md MB-4). Sharing
    // a folder (`shareFolderIntoRealm`) fans its files' bytes out into the shared
    // realm's content store so members can `readSharedFile` them. The file
    // operation factory reads the `fileContentEmitter` off this holder; the sync
    // plugin's `initialize` populates it from `SyncProxyComponents` after the
    // SyncObserver is built. Null emitter ⇒ only SharedFile metadata is written
    // (a member browses names but opens nothing).
    val syncBlockEmitterHolder: SyncBlockEmitterHolder
}

package app.tenshu.sdk.internal

import org.tenshu.sync.proxy.NoOpPlatformScheduler

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.file.GetPreview
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.identity.EntitlementSignature
import org.tenshu.identity.client.identityServiceClientModule
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.identity.proxy.identityProxyImplementationFactory
import org.tenshu.identity.proxy.initIdentityService
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.contact.proxy.InviteResponsePickup
import org.tenshu.contact.proxy.contactServiceProxyImplementationFactory
import org.tenshu.contact.proxy.initContactService
import org.tenshu.contact.proxy.sweepContactPhotos
import org.tenshu.notifications.client.notificationServiceClientModule
import org.tenshu.notifications.common.notificationServiceCommonModule
import org.tenshu.notifications.proxy.notificationServiceProxyImplementationFactory
import kotlin.js.Date
import org.tenshu.storage.client.storageServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.proxy.preferenceServiceProxyImplementationFactory
import org.tenshu.storage.common.storageServiceCommonModule
import org.tenshu.storage.proxy.EntitlementStorageProvider
import org.tenshu.storage.proxy.initEntitlementStorageReader
import org.tenshu.storage.proxy.initStorageService
import org.tenshu.storage.proxy.storageServiceProxyImplementationFactory
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.initSyncService
import org.tenshu.sync.proxy.syncProxyImplementationFactory
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.proxy.initTimelineService
import org.tenshu.timeline.proxy.timelineServiceProxyImplementationFactory

// The first-party services as [JsRuntimePlugin]s. Phase 1 keeps them
// co-located in `:tenshu-sdk-js`; Phase 2 moves each into its own per-service
// Gradle module alongside the matching `@JsExport` surface (Files.kt etc.).
// Each plugin's contributions are exactly what the monolithic [JsRuntime]
// hard-coded inline, so the composed wiring is behaviourally identical.

internal object FilesPlugin : JsRuntimePlugin {
    override val id: String = "files"

    override val schemaConfigs =
        listOf(fileServiceCommonModule, fileServiceClientModule)

    // Files owns `FolderMerger`; sync consumes it via the runtime's
    // merger registry (proposal §6.3 decouples contribution from consumption).
    override val mergers = listOf(FolderMerger())

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id ->
            // Tier-2 storage enforcement (web-storage-status.md gating follow-up):
            // the same `StorageCapacityHolder` the storage plugin feeds. `UploadFile`
            // reads it via `ensureWithinQuota` (local-first — no-storage uploads still
            // proceed, only a subscribed-over-quota write is blocked) and `ShareLink`
            // via `ensureCapacity` (no storage ⇒ blocked, sharing needs the relay).
            // Now that the figures are real (Buckets 1–3) this can finally be wired;
            // before that an always-false status would have hard-blocked web sharing.
            when (id) {
                // W2b: the browser's `GetPreview`. Each native platform factory registers its
                // own; JS had none, so `UploadFileImplementation.renderThumbnail` hit
                // "No implementation found for operation" on every web upload — swallowed by
                // its own best-effort catch, which is why the ladder was silently never minted.
                // ⚠ Async by necessity (no synchronous decode in a browser); see
                // [JsGetPreviewImplementation] for the two-call-path consequence.
                GetPreview.ID -> JsGetPreviewImplementation()
                else -> fileServiceProxyImplementationFactory(
                    id,
                    ctx.fileDatabase,
                    // MB-4: `shareFolderIntoRealm` reads `fileContentEmitter` off this
                    // holder to fan a shared folder's bytes into the realm content
                    // plane (populated by SyncPlugin.initialize). Null ⇒ metadata-only.
                    syncBlockEmitterHolder = ctx.syncBlockEmitterHolder,
                    storageCapacityHolder = ctx.storageCapacityHolder,
                )
            }
        }

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        executionContext.initFileService(fileDatabase = runtime.fileDatabase)
    }
}

internal object NotificationsPlugin : JsRuntimePlugin {
    override val id: String = "notifications"

    override val schemaConfigs =
        listOf(notificationServiceCommonModule, notificationServiceClientModule)

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id ->
            notificationServiceProxyImplementationFactory(id)
        }
}

internal object IdentityPlugin : JsRuntimePlugin {
    override val id: String = "identity"

    override val schemaConfigs =
        listOf(identityServiceCommonModule, identityServiceClientModule)

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id ->
            identityProxyImplementationFactory(id)
        }

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        executionContext.initIdentityService()
    }
}

// Storage-provision service (web-storage-status.md Buckets 1+2). Registering it
// makes `client.storageService.status()` return a non-null `StorageStatus` on
// the web — without it nothing in the apex runtime *produces* the row. The
// figures are derived from the synced `Entitlement` by the relocated
// [EntitlementStorageProvider] + [initEntitlementStorageReader] (Bucket 2), so a
// web device that joins a funded realm reflects the owner's subscription; with
// no entitlement it reports `hasStorage = false`.
//
// Ordered before [SyncPlugin] (see [JsRuntimeBuilder]) so the relay's lazy is
// untouched — `initStorageService` / the reader only collect the local provider
// flow + observe `Entitlement` saves, and `storageServiceProxyImplementationFactory`
// maps `CheckCapacity`; none of them dial the relay.
/**
 * ⚠⚠ **The preference plane, which web did not have at all until now.**
 * `FirstPartyPlugins` registered seven services and this was not one, so every `DeviceSetting` read
 * or write on web failed — which is why X-5 could not ship the wanted-class declaration
 * (`drive-xxlarge-thumbnail-rung.md` §4.8, §9.7) and why wiring `notePull` alone would have failed
 * silently for ever while looking wired.
 *
 * ⚠ Safe for stores that already exist: `StoreSchemaMigrator.migrateStoreAtOpen` stamps a service
 * the recorded vector has never seen at its current version and **touches no rows** — the wipe
 * paths are `recorded < floor` and `recorded > declared`, and an unrecorded service is neither.
 *
 * ⚠ No `dependsOn` and ordered **before** `SyncPlugin`: its rows are ordinary synced data, and the
 * declaration that reads them runs off `serviceScope` after the client is built, not from here.
 */
internal object PreferencePlugin : JsRuntimePlugin {
    override val id: String = "preference"

    override val schemaConfigs =
        listOf(preferenceServiceCommonModule, preferenceServiceClientModule)

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id -> preferenceServiceProxyImplementationFactory(id) }
}

internal object StoragePlugin : JsRuntimePlugin {
    override val id: String = "storage"

    override val schemaConfigs =
        listOf(storageServiceCommonModule, storageServiceClientModule)

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id ->
            storageServiceProxyImplementationFactory(id, ctx.storageCapacityHolder)
        }

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        val provider = runtime.storageProvider
        // Collects the provider's figures into the single-row `StorageStatus`
        // (Tier-1 reactive read) and the [StorageCapacityHolder] (Tier-2 latch).
        executionContext.initStorageService(
            scope = runtime.serviceScope,
            provider = provider,
            capacityHolder = runtime.storageCapacityHolder,
        )
        // Bucket 2: recompute the provider's figures from the synced `Entitlement`
        // (reader: a DataObserver + seed). The writer (mint on subscribe) stays in
        // `:control-shared` — on the web the entitlement arrives purely via sync.
        if (provider is EntitlementStorageProvider) {
            // The web has no release/debug split — an image is built FOR a plane — so the guard
            // here is provenance: only an issuer handed to the bundle's own build (`-P` / env, as
            // `ship-dev-stack.sh` does) is in force, never one inherited from `local.properties`.
            // The line is the only visible difference between the two bundles.
            // ⚠⚠ Not a trust boundary yet: the reader below is given no `verifyRecord`, so it
            // derives figures and `hasStorage` from any stored record. The set in force here
            // feeds only `SyncProcessor`'s merge-time diagnostic (tech-debt.md, "The web
            // entitlement reader verifies nothing").
            EntitlementSignature.trustBuildConfiguredIssuer(isRelease = false, explicitOnly = true)
            console.log(
                "Entitlement trust: publishers=${EntitlementSignature.trustedPublisherIds()}" +
                    if (EntitlementSignature.buildConfiguredIssuerIgnored) {
                        " (build-configured ${EntitlementSignature.buildConfiguredIssuer?.publisherId}" +
                            " IGNORED: it came from a standing properties file)"
                    } else {
                        ""
                    }
            )
            executionContext.initEntitlementStorageReader(
                provider = provider,
                ownerId = { runtime.syncManager.currentOwnerId },
                now = { Date.now().toLong() },
                // Track B3: the same current-entitlement read the figures derive from also feeds
                // the record-credential provider, so the browser can present the synced signed
                // record as `X-Entitlement-Proof` on writes.
                //
                // ⚠⚠ Not optional on web, unlike the `null` the parameter's doc offers "web SDK /
                // tests". Reading the entitlement for the FIGURES while never presenting it as a
                // CREDENTIAL is precisely the split that made the apex look subscribed (Services
                // card showed the owner's plan) while every push was refused `402
                // entitlement_required` — a UI that says "storage active" over silent one-way sync.
                recordSink = { signed -> runtime.entitlementCredentialProvider.update(signed) },
                // retention-quota-model.md P1: real relay-reported usage → the gauge's usedBytes
                // goes live (replaces the 0L mock). The scope lets the reader kick the background
                // GET /sync/usage + re-push once it lands; best-effort, null-safe when offline.
                scope = runtime.serviceScope,
                usageReader = runtime.relayUsageReader,
            )
        }
    }
}

// Contact-service plugin (drive-web-mode-b-sharing.md MB-1). Makes
// `client.contactService.*` available on the web: the link-only contact
// invitation handshake (create / redeem / await-ack / complete) + the
// `connectedContacts()` read MB-2's grant picker will consume.
//
// `dependsOn = {"sync"}` so this plugin's `initialize` runs *after* SyncPlugin
// has published the scheduler into `syncSchedulerHolder` — the inviter-side
// [InviteResponsePickup] rides the sync cycle (`addSyncCycleHook`), exactly as
// `:control-shared`'s PlatformProxyModule wires it natively. The ordering puts
// the contact arm after sync in the factory chain too; contact op-ids are
// disjoint from every other service's, and contact is an inherently-online
// feature (the invite mailbox lives on the relay), so reaching past sync's arm
// on a contact dispatch is immaterial.
internal object ContactPlugin : JsRuntimePlugin {
    override val id: String = "contact"

    override val dependsOn: Set<String> = setOf("sync")

    override val schemaConfigs =
        listOf(contactServiceCommonModule, contactServiceClientModule)

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id ->
            contactServiceProxyImplementationFactory(id, ctx.contactProxyComponents)
        }

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        // Derive the contact relations (persons / identities / invites) from
        // every locally-known row, whether saved by an op or arriving via sync.
        executionContext.initContactService { runtime.fileDatabase }
        // Inviter-side response pickup: polls GET /sync/invite/responses on the
        // sync cycle, self-gated on locally-PENDING/REDEEMED invites so
        // steady-state traffic is unchanged. The scheduler was published by
        // SyncPlugin's initialize (guaranteed-before via `dependsOn`).
        val pickup = InviteResponsePickup(executionContext, runtime.contactProxyComponents)
        runtime.syncSchedulerHolder.scheduler?.addSyncCycleHook { pickup.checkOnce() }
        // v2 contacts (`contacts-app.md` §9.10.2): the web has no `SweepContactPhotos` schedule,
        // so superseded and deleted photo blobs are swept here — the maintenance hook, which runs
        // even while the realm is sealed. One read of the candidate file when there is nothing to do.
        runtime.syncSchedulerHolder.scheduler?.addMaintenanceHook {
            executionContext.sweepContactPhotos(runtime.contactProxyComponents)
        }
    }
}

// Timeline-service plugin — the read bridge behind `@tenshu/sdk/timeline`
// (`Timeline.kt`). Makes `client.timelineService.{moments,trips,traces}` readable
// on the web: the same three maintained relations `:control-shared-proxy`'s
// `PlatformProxyModule` lights up natively via `initTimelineService`.
//
// ⚠⚠ **Ordered BEFORE [SyncPlugin]**, and deliberately with **no `dependsOn`**.
// Two independent reasons, both of which the "put it after sync like contact"
// instinct gets wrong:
//
//  1. `initTimelineService` **registers `MomentMerger` + `TripMerger` into the
//     shared [SemanticMergerRegistry]**, and `SyncPlugin.initialize` is what hands
//     that registry to `initSyncService`. Timeline therefore has to have run
//     first, exactly as [FilesPlugin] contributes `FolderMerger` ahead of sync.
//     (Registration is via `initTimelineService`'s *required* registry parameter
//     rather than the `mergers` seam — that parameter exists precisely so
//     "forgot to register" is a compile error, see its KDoc — so it happens at
//     `initialize` time, not at registry-construction time. Hence the ordering
//     is real, not cosmetic.)
//  2. Its `operationFactory` maps only the three `Record*` op ids and never
//     touches the relay, so it belongs in the pre-sync arms of the dispatch
//     chain — the same reason [StoragePlugin] slots in before sync: sync stays
//     last so its relay `lazy` is not forced by an unrelated dispatch.
//
// [ContactPlugin]'s `dependsOn = {"sync"}` is the opposite case (it needs the
// scheduler SyncPlugin publishes); copying it here would invert (1).
//
// ⚠ `initTimelineService` would also install `PlaceRoleInferencePass`, which writes
// `roleSource = inferred` `PlaceRole` rows. Since F7 the place schema IS registered here
// (`PlacePlugin.kt`, with `PlaceRoleMerger`), so those writes would
// now land — and the maintainer decided (2026-10-23) a tab must not compute roles: it is
// background work, and a browser ahead of the released phones would raise the realm's
// place epoch by writing them. So this plugin passes `inferPlaceRoles = false`;
// `NoPlaceRoleInferenceTest` holds it there.
internal object TimelinePlugin : JsRuntimePlugin {
    override val id: String = "timeline"

    override val schemaConfigs =
        listOf(timelineServiceCommonModule, timelineServiceClientModule)

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id ->
            timelineServiceProxyImplementationFactory(id)
        }

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        // Installs the generic relation maintainer that derives
        // `TimelineService.Relations.{MOMENTS,TRIPS,TRACES}` from every locally-known
        // entity — without it every read in `Timeline.kt` returns an empty list — and
        // registers the two mergers (see the ordering note above).
        // ⚠ No place-role inference in a tab (maintainer 2026-10-23): roles sync here, they are
        // never computed here. See `initTimelineService`'s `inferPlaceRoles`.
        executionContext.initTimelineService(runtime.mergerRegistry, inferPlaceRoles = false)
    }
}

internal object SyncPlugin : JsRuntimePlugin {
    override val id: String = "sync"

    override val schemaConfigs =
        listOf(syncServiceCommonModule, syncServiceClientModule)

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id ->
            syncProxyImplementationFactory(
                id,
                ctx.syncRelay,
                ctx.syncManager,
                ctx.syncCryptoManager,
                ctx.tombstoneEmitterHolder.emitter,
                // MB-2: thread the realm registry + per-realm-device-key relay so
                // CreateSharedRealm + the GR grant ops (grant/accept/getRealmMembers
                // via identity, poll/remove/decline) resolve instead of returning
                // null. Read lazily — the holder is populated by `initialize` below
                // (which runs before any op dispatches).
                realmRegistry = ctx.realmRegistryHolder.realmRegistry,
                grantedRelayFactory = ctx.grantedRelayFactory,
                nowMs = ctx.nowMs,
            )
        }

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        // Spins up the SyncProcessor / SyncObserver / SyncScheduler chain and
        // publishes the tombstone emitter back into the shared holder so the
        // file/notification/identity factories can emit tombstones through it.
        //
        // MB-2: pass the two relay factories so (a) a registered shared realm gets
        // an A4-signed relay and can push/pull its member roster
        // (`sharedRealmRelayFactory`), and (b) the `MemberSelfBindObserver`
        // registers — a member's second device self-binds with its own per-realm
        // key (`grantedRelayFactory`). File-content factories stay null (Mode-B
        // file read is MB-3); Member rows ride the block stream, not the content
        // plane.
        // MB-3: also pass the content-plane seams so a shared realm's processor
        // builds a `FileContentManifestApplier` (`fileDatabase` +
        // `sharedRealmContentBlobStoreFactory`) and the personal processor is
        // published into `syncProcessorHolder` for OnDemand byte fetches. GR-7
        // retained-key trial-decrypt then rides `RealmContext.historicalDerivedKeys`
        // automatically.
        //
        // W1 (`drive-web-parity.md`): the **personal** `contentBlobStore` is passed too.
        // It was null from MB-3 until 2026-08-27 on the reasoning that "web Drive uploads
        // are local-first" — true of the upload itself, and wrong about everything after
        // it. A local-first upload still has to *offer* its bytes for another device to
        // ever read them, and the same null also denied this device the applier that
        // writes an *incoming* file's bytes down. See [RuntimeContext.contentBlobStore]
        // for both severed directions and why neither showed up as an error.
        val components = executionContext.initSyncService(
            syncManager = runtime.syncManager,
            cryptoManager = runtime.syncCryptoManager,
            mergerRegistry = runtime.mergerRegistry,
            coroutineScope = runtime.serviceScope,
            syncProcessorHolder = runtime.syncProcessorHolder,
            contentBlobStore = runtime.contentBlobStore,
            fileDatabase = runtime.fileDatabase,
            sharedRealmRelayFactory = runtime.sharedRealmRelayFactory,
            sharedRealmContentBlobStoreFactory = runtime.sharedRealmContentBlobStoreFactory,
            grantedRelayFactory = runtime.grantedRelayFactory,
            nowMs = runtime.nowMs,
            // TC-2: publish the *store's* HLC position rather than this tab's, so two tabs of one
            // origin stop overwriting the relay's single per-device tip slot with unrelated values.
            hlcWatermarkStore = runtime.hlcWatermarkStore,
            platformScheduler = NoOpPlatformScheduler,
            // The browser's device-local pause switch (`web-apex-home.md` §4.7): closed, no tick
            // runs and no content uploads until the user resumes.
            pauseGate = SyncPause.gate,
        )
        // Resuming should sync now, not after the rest of a tick. `notifyForegrounded` is the
        // gate-respecting wake: it also re-tries a waiting content plane and bypasses nothing.
        SyncPause.onResumed = { components.scheduler.notifyForegrounded() }
        runtime.tombstoneEmitterHolder.emitter = components.tombstoneEmitter
        // Publish the scheduler so `Devices.revokeSelf` can force a drain
        // (push the revocation tombstone) before the apex wipes local state.
        runtime.syncSchedulerHolder.scheduler = components.scheduler
        // Publish the realm registry so the operation factory above can hand it
        // to CreateSharedRealm + the grant ops at dispatch time.
        runtime.realmRegistryHolder.realmRegistry = components.realmRegistry
        // MB-4: publish the block + file-content emitters so `shareFolderIntoRealm`
        // fans a shared folder's bytes into the realm content plane (the SyncObserver
        // is both emitters; shared-realm emit routes through the realm registry's
        // per-realm content store).
        runtime.syncBlockEmitterHolder.emitter = components.syncBlockEmitter
        runtime.syncBlockEmitterHolder.fileContentEmitter = components.fileContentEmitter
        // Track A seam (SyncScheduler.notifyEntitlementCredentialChanged): a browser is always
        // session-less, so on a fresh pairing it necessarily pushes before the owner's
        // `Entitlement` has synced — that first 402 is expected. Without this edge the writes
        // then sit in the 402 backoff (up to ~2 min) after the record lands, which reads as
        // "sync is broken" long after it was fixed. Set here, post-`initSyncService`, because
        // it needs the scheduler this call returns — the same placement as the three native
        // platforms' `PlatformProxyModule`.
        runtime.entitlementCredentialProvider.onRecordUsable = {
            components.scheduler.notifyEntitlementCredentialChanged()
        }
        // W8: catch up the moment this tab is looked at again. A backgrounded tab is frozen by
        // the browser, not slowed by us, so this cannot keep it syncing — it only stops the
        // user waiting out a tick that was never going to be short. See [installForegroundWake].
        installForegroundWake(components.scheduler)
    }
}

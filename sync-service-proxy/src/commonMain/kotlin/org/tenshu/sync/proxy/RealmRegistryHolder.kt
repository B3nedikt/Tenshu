package org.tenshu.sync.proxy

/**
 * Mutable holder for the session's [RealmRegistry], populated by `initSyncService`'s
 * caller from [SyncProxyComponents.realmRegistry]. Same shape as [SyncProcessorHolder]:
 * the operation `implementationFactory` Koin singleton is constructed before
 * `initSyncService` runs, so consumers that need the registry at dispatch time (e.g.
 * threading it into `syncProxyImplementationFactory` for `CreateSharedRealm`) read this
 * field lazily rather than depending on the registry at construction time.
 */
class RealmRegistryHolder {
    var realmRegistry: RealmRegistry? = null
}

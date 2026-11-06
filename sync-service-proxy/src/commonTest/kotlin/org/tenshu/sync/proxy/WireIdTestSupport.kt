package org.tenshu.sync.proxy

import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.SyncManager

/**
 * The **wire** name of an entity, for a test that hand-builds a payload a peer would have sent
 * (`sync-entity-identity.md` EI-1).
 *
 * ⚠⚠ Resolved through the production [WireIdResolver] rather than recomputed, and that is the whole
 * point: a test that derives the expected name its own way is asserting its own arithmetic, and
 * would keep passing if production's derivation changed underneath it. Sharing the resolver means
 * the fixture cannot drift from the thing it is testing.
 *
 * ⚠ A test that is asserting **local** identity — the object store's row key, `StreamMerge` dedup —
 * should keep calling `getDataIdHash()` instead. §5 of the doc splits the callers three ways, and
 * this helper serves exactly one of them.
 */
fun DataId.wireIdIn(syncManager: SyncManager): String =
    WireIdResolver(syncManager).of(this)

package org.tenshu.identity.proxy

import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService

/**
 * Initialises the identity service's proxy-side observers.
 *
 * The six relations this service owns — [IdentityService.Relations.DEVICES],
 * `SHARED_REALM_MEMBERSHIPS`, `MEMBERS`, `PENDING_GRANTS`, `RETAINED_REALM_KEYS`
 * and `DEVICE_LAST_SEEN` — are **declared on [IdentityService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, phase
 * R1). Until R1 they were five near-identical hand-written [DataObserver]s here.
 *
 * **Why any of this exists**: relation membership is not a synced primitive
 * today. `SyncBlock`s carry entity data, not relation-mutation operations.
 * Without the maintainer, a [Device] entity that arrived via sync would land in
 * the local DB but stay invisible to `getDevices()` — which manifested as the
 * freshly-paired device being visible on the host but invisible on the new
 * device (which only saw its own self-pushed entry, never the host's). Every
 * replica must therefore derive the same relation locally from the entity saves
 * it sees, whether they come from an operation implementation (`CreateRealm`,
 * `ApprovePairingRequest`, `GetPairingStatus`) or from the sync-merge path
 * (`SyncProcessor.mergeData`).
 *
 * On `onDataDeleted` the maintainer symmetrically removes the entity from its
 * relation. Combined with `SyncProcessor.processDeleteBlock` (which deletes the
 * entity on receiving a `DeleteBlock`), revoked devices disappear from
 * `getDevices()` symmetrically.
 *
 * Idempotent on save: the array converges to exactly one entry per logical key
 * on every save, so a pre-polluted array self-heals.
 *
 * ⚠ **The maintainer is the sole writer of these arrays, and should stay that
 * way.** An earlier revision of this KDoc claimed `ApprovePairingRequest` also
 * called `addToArray` directly; that has not been true for some time — the op
 * implementations were cleaned up precisely because two writers produce
 * duplicate entries (`CreateRealmImplementation` documents the reasoning). Do
 * not reintroduce a direct writer: besides the duplicates, single-writer is the
 * precondition any future ordered-array optimisation depends on
 * (`core-relation-system.md` §11.2).
 *
 * The **synced** relation primitive remains the `core-relation-system.md`
 * proposal's later phases (R3 curated membership, R4 durability); R1 is the
 * structural half — one mechanism instead of thirty — and changes no wire, no
 * schema and no stored bytes.
 */
fun ExecutionContext.initIdentityService() {
    installRelationMaintainer()
}

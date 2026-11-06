package org.tenshu.contact.proxy

import org.tenshu.contact.ContactService
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.id

/**
 * Initialises the contact service's proxy-side observers.
 *
 * [ContactService]'s five relations (persons / tenshuIdentities / contactInvites /
 * sources / avatars) are derived views of every locally-known row — whether saved
 * by an operation implementation or arriving via the sync-merge path. Relation
 * membership is not a synced primitive; receivers derive it.
 *
 * All five are **declared on [ContactService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, R2).
 */
fun ExecutionContext.initContactService(photoStore: () -> FileDatabase) {
    installRelationMaintainer()
    // Every photo blob a row stops naming becomes a sweep candidate (`contacts-app.md` §9.10).
    // ⚠ A supplier, read at the first save: the web runtime's byte store hydrates after init.
    installContactPhotoRecorder(photoStore)
}

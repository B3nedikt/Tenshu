package org.tenshu.photo.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer

/**
 * Wires `:photo-service` into a proxy (`photos-app.md` PH-2).
 *
 * ⚠ One line, and that is the point: every relation this service declares is `maintained = true`,
 * so the generic maintainer derives the element type, the array key and each element's own key
 * from the schema. There is no observer of its own to register — the shape `core-relation-system.md`
 * §9.2.1 exists to make possible, and the reason a service with three row types needs no bespoke
 * proxy code at all.
 */
fun ExecutionContext.initPhotoService() {
    installRelationMaintainer()
}

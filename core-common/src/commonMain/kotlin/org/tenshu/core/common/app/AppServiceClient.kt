package org.tenshu.core.common.app

import org.tenshu.core.app.AppService
import org.tenshu.core.common.TenshuClient

/**
 * Typed accessor for the app-registration service, mirroring
 * `TenshuClient.preferenceService` / `.syncService`. Lives in `:core-common`
 * (not a `*-client` module) because `AppService` ships in `:core` rather than a
 * dedicated feature-service module — see `control-app-registration.md` §7.
 */
val TenshuClient.appService: AppService
    get() = getService(AppService.ID)

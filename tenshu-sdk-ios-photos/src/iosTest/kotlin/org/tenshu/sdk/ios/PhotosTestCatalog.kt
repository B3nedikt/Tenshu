package org.tenshu.sdk.ios

import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * ⚠⚠ The overrides every admitted Photos graph of this test source set is built with: [overrides]
 * after a graph that pins **no catalog key**, so its catalog source answers *no catalog* without a
 * request (`HttpModelCatalogSource.configured`).
 *
 * The production binding is the real CDN (`models.service.tenshu.app`, whose `coreml` entry
 * verifies). A graph over it asks the CDN whenever a model source is collected or a download is
 * pressed, and a case then answers whatever the network did (`ios-on-device-inference.md`
 * §14.18.17: `Accepted`, `StillStarting`, or the expected refusal).
 * ⚠ The key and not `modelCatalogUrl`: the URL is also the artifacts' base, which a relaunch's
 * restarted transfer is asked from.
 * ⚠ A case may still give its graph a catalog: [overrides] are bound after this.
 */
internal fun noCatalogThen(overrides: Module?): Module =
    module { includes(listOfNotNull(module { single(named("modelCatalogPublicKeyHex")) { "" } }, overrides)) }

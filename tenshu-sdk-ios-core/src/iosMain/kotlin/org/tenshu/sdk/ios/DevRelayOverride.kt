package org.tenshu.sdk.ios

import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import platform.Foundation.NSProcessInfo
import kotlin.experimental.ExperimentalNativeApi
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Dev-relay override for the sim harnesses (ios-drive-parity.md P1c): `TENSHU_DEV_RELAY` in the
 * process environment (set via `SIMCTL_CHILD_TENSHU_DEV_RELAY=… simctl launch …`) points the
 * in-process runtimes at a dev `:sync-relay-server:run` instead of the production relay the
 * `platformProxyModule` binding defaults to. **Debug binaries only** — a release build ignores
 * the variable entirely, so no shipped app can be re-pointed through its environment.
 */
@OptIn(ExperimentalNativeApi::class, ExperimentalObjCRefinement::class)
@HiddenFromObjC
fun devRelayOverride(): String? {
    if (!kotlin.native.Platform.isDebugBinary) return null
    return NSProcessInfo.processInfo.environment["TENSHU_DEV_RELAY"] as? String
}

/** A last-wins module overriding `syncRelayUrl`, or null when no override is set. */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
fun devRelayOverrideModule(): Module? = devRelayOverride()?.let { url ->
    module { single(named("syncRelayUrl")) { url } }
}

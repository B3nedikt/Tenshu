package app.tenshu.control.data

/**
 * Whether this is a production (release) build — DI-provided per platform
 * (`commerce-accounts-and-sessions.md` §9.3 / `publishing-commerce-reconciliation.md` §10.3). The
 * commerce plane needs this for exactly one thing in Phase 3: a **release build must never
 * self-sign a storage entitlement with the committed dev key** (`tenshu.dev`). There was no
 * build-variant signal in the client modules before, so this is the seam.
 */
data class BuildVariant(val isRelease: Boolean)

/**
 * The dev-key release guard, as a pure function so it's unit-testable independent of Koin/platform.
 *
 * The in-process [org.tenshu.storage.proxy.DevSeedEntitlementMintClient] signs as `tenshu.dev` —
 * fine for local dev, a hard no in production. So the dev-seed minter may be selected **only** when
 * the billing mock is requested *and* this is not a release build. In a release build the result is
 * always false regardless of `TENSHU_BILLING_MOCK`: the selector falls through to the real commerce
 * client, which simply no-ops until a commerce session exists (fail-closed — no dev entitlement is
 * ever minted in release).
 */
object EntitlementMinterSelection {
    fun useDevSeed(billingMock: Boolean, isRelease: Boolean): Boolean = billingMock && !isRelease
}

/**
 * The runtime-environment override guard, as a pure function for the same reason
 * [EntitlementMinterSelection] is one — the platform seams that read it are untestable off-device.
 *
 * A **debug** binary may be re-pointed at a local stack through its process environment (the
 * simulator's `SIMCTL_CHILD_*` / an Xcode scheme variable); a **release** binary may not, so a
 * shipped app always talks to the origin baked into it at build time. That is the discipline
 * `DevRelayOverride` already applies to the relay URL — *"no shipped app can be re-pointed through
 * its environment"* — lifted here so the commerce origin gets it too.
 *
 * ⚠ [default] is the build-time constant, not a literal: the production origins are spelled once,
 * in `control-shared-proxy/build.gradle.kts`, and reach both platforms through the generated
 * `RelayConfig`.
 */
object ProxyOriginSelection {
    fun resolve(envOverride: String?, isRelease: Boolean, default: String): String =
        envOverride?.takeIf { it.isNotBlank() && !isRelease } ?: default
}

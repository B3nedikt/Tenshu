package org.tenshu.core.common.app.proxy

import org.tenshu.core.Data
import org.tenshu.core.app.AppIdentityResolver
import org.tenshu.core.app.RegisterApp
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/**
 * Builds the once-per-new-session callback
 * [org.tenshu.core.common.proxy.ClientRegistry] fires when an app first
 * connects to the proxy — the handshake-triggered writer that was the one
 * outstanding piece of the App Registration track
 * (`docs/tracks/track-a-control-apps.md` A4, `control-app-registration.md`).
 * Without it no [org.tenshu.core.app.AppRegistration] row is ever created, so
 * no app (incl. Drive) appears in Control's Apps screen.
 *
 * On each new session it:
 *  1. resolves the connecting `clientId` to a canonical `appId` via
 *     [appIdentityResolver] — an unknown client (`null`) is skipped, so the
 *     allowlist is the gate;
 *  2. reads the proxy's device id from [deviceIdProvider] — `null` means no
 *     realm is active yet (registration writes realm-synced data), so the
 *     trigger is a no-op and a later re-bind retries (the documented v1
 *     limitation for a client that stays connected across realm creation);
 *  3. otherwise performs the **proxy-only, idempotent** [RegisterApp] write.
 *
 * The write runs synchronously — it is a single cheap, idempotent local DB
 * write, and `ClientRegistry` fires this on the binder/socket thread, so it must
 * not leak a coroutine that outlives the call nor throw into the transport. Any
 * failure is swallowed ([runCatching]): registration is best-effort surfacing
 * for the Apps screen and must never break a client's connection.
 *
 * v1 always registers with `verified = false`: there is no code-signing check
 * yet (it is a later Rei phase), so the registration is "unverified" until the
 * verifier lands. The `verified` flag is plumbed end-to-end so that flip is a
 * one-line change. See A4.3 / threat-model T-C-14 for the gate's true posture.
 */
fun appRegistrationTrigger(
    appIdentityResolver: AppIdentityResolver,
    deviceIdProvider: () -> String?,
    verifiedProvider: (clientId: String) -> Boolean = { false },
): (clientId: String, context: ExecutionContext) -> Unit = { clientId, context ->
    val appId = appIdentityResolver.resolveAppId(clientId)
    val deviceId = deviceIdProvider()
    if (appId != null && deviceId != null) {
        runCatching {
            context.registerAppIdempotently(appId, deviceId, verifiedProvider(clientId))
        }
    }
}

/**
 * Reports whether a client's identity was **attested** by the platform rather than merely
 * accepted — the backing for [org.tenshu.core.app.AppRegistration]'s `verified` flag.
 *
 * Supplied per platform, because what counts as attestation differs: on desktop it is a peer
 * code-signature check against a Team-ID-pinned requirement
 * (`desktop-ipc-client-identity.md`); on Android it would be the `Binder.getCallingUid()` →
 * `PackageManager` lookup, which is unforgeable by the caller. Absent ⇒ `false`, so a
 * platform that has not made the claim never over-states it.
 *
 * ⚠ `TenshuAppRegistry` aggregates the per-device rows with `all { it.verified }`, so a single
 * device that cannot attest keeps the whole app unverified. That conservatism is deliberate:
 * "verified" should mean *everywhere*, not *somewhere*.
 */
fun interface PeerAttestationProbe {
    fun isAttested(clientId: String): Boolean
}

/**
 * Invokes [RegisterApp]'s proxy-side implementation directly against [this]
 * session context — no IPC round-trip, since the proxy is registering on its
 * own behalf. The implementation is idempotent (any existing row, Active or
 * Removed, short-circuits), so racing re-binds are safe.
 */
private fun ExecutionContext.registerAppIdempotently(
    appId: String,
    deviceId: String,
    verified: Boolean,
) {
    val impl = implementationFactory(RegisterApp.ID) as? SyncOperationImplementation ?: return
    impl.execute<Data?>(
        executionContext = this,
        implementationFactory = implementationFactory,
        params = mapOf(
            RegisterApp.Params.APP_ID to appId.toTString(context),
            RegisterApp.Params.DEVICE_ID to deviceId.toTString(context),
            RegisterApp.Params.VERIFIED to verified.toTBoolean(context),
        ),
    )
}

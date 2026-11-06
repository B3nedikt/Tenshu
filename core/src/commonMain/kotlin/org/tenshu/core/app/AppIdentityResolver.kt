package org.tenshu.core.app

/**
 * Resolves a transport-level `clientId` (the identity a connecting app presents
 * to the proxy over IPC — an Android package name, the desktop socket client
 * id, …) to the **canonical `appId`** an [AppRegistration] is keyed under
 * (`docs/tracks/track-a-control-apps.md` A4.1).
 *
 * This is the seam the handshake's app-registration trigger consults: the
 * `clientId` is *not* the `appId` (e.g. Android's `app.tenshu.drive.android`
 * package vs. the canonical `app.tenshu.drive`), so registering the raw
 * `clientId` would make the manifest join in Control's Apps UI miss.
 *
 * **v1 is an allowlist.** The bundled implementation maps a small, compiled-in
 * set of known first-party client ids to their canonical app ids and returns
 * `null` for everything else — an unknown / unrecognised client is *not*
 * registered. Until the Rei verifier lands (full peer-credential + code-signing
 * identity, `docs/protocol/desktop-ipc.md` §3 / `tenshu-rei.md` §5.F) this
 * allowlist, together with Android's kernel-trustworthy caller-UID→package
 * resolution, is the only gate on registration; the registration it produces is
 * always `verified = false`. It is the `ReiSource`-shaped drop-in the verifier
 * replaces, exactly like [AppManifestSource].
 */
fun interface AppIdentityResolver {
    /**
     * The canonical `appId` for [clientId], or `null` if the client is not on
     * the allowlist (⇒ do not register it).
     */
    fun resolveAppId(clientId: String): String?
}

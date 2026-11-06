package app.tenshu.control.data

import org.tenshu.core.app.AppIdentityResolver

/**
 * v1 [AppIdentityResolver]: a compiled-in allowlist mapping the transport
 * `clientId` a first-party app presents over IPC to its canonical `appId`
 * (`docs/tracks/track-a-control-apps.md` A4.1). This is the `ReiSource`-shaped
 * seam the future verifier-backed resolver replaces (it will derive identity
 * from the peer's code-signing publication rather than a static table).
 *
 * The `clientId` is OS- or signature-attested on both platforms — never self-asserted:
 *  - **Android** — the calling package name (`admittedCallerPackage()` in
 *    `ProxyService`), resolved from `Binder.getCallingUid()`, e.g.
 *    `app.tenshu.drive.android`.
 *  - **Desktop** — the peer's **code-signing identifier**, attested against a
 *    Team-ID-pinned requirement by the IPC peer-credential gate
 *    (`desktop-ipc-client-identity.md`), e.g. `app.tenshu.drive`. An unattested peer
 *    arrives under a unique `unattested-…` id that is deliberately in no table.
 *
 * Desktop ids therefore *coincide* with the canonical `appId` (both are the bundle id),
 * while Android's carry an `.android` suffix — so there is no collision between the two
 * columns. Any client not in the table resolves to `null` and is never registered: the
 * allowlist is the second, independent gate. The signature says *which* app is calling;
 * this table says whether we recognise it.
 */
class BundledAppIdentityResolver : AppIdentityResolver {

    // Derived from the one declaration (control-app-catalogue.md C1): every entry here comes
    // with its manifest attached, so an allowlisted app that cannot be described is no longer a
    // state the code can reach.
    override fun resolveAppId(clientId: String): String? =
        BundledAppCatalogue.appIdByClientId[clientId]

    // ⚠ The client-id constants that used to live here are gone: the ids are declared once, in
    // BundledAppCatalogue, alongside the manifest they belong to. Duplicating them as constants
    // here would reintroduce exactly the two-places-joined-by-a-string arrangement this
    // replaced — the drift would just have moved from the table to the constant.
}

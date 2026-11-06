// Hosts a first-party app that lives on its own origin (`drive.tenshu.app`, `contacts.tenshu.app`,
// or each app's Vite dev server locally) inside the apex control-plane chrome, via an iframe.
//
// Why an iframe and not an in-apex re-implementation: each app is its own origin
// (architecture.md §3 — Same-Origin Policy is the only real trust boundary in a browser). The apex
// is the specialised host: it owns the in-process runtime + postMessage gateway and lends its
// chrome (top bar + app launcher). The embedded app dials back to this same apex origin for the
// bridge, so the session is shared — no second sign-in.
//
// ⚠⚠ Embedding is not authorisation. What the framed app may CALL is its origin's allowlist in
// `lib/gatewayPermissions.ts` (F5); `origin` here must be one of the origins listed there, or the
// app will load and every call will be ignored.
//
// Generalised from `DriveEmbed` (web-app-suite.md F3); Drive's behaviour is unchanged. Third-party
// apps will use the same host once Rei is operational (architecture.md §7), additionally with a
// `sandbox` attribute + Rei verification. First-party apps are trusted, so no sandbox.
//
// See docs/adr/0020-web-app-structure.md for the per-app structure convention.

export interface AppEmbedProps {
  // The app's exact origin — the iframe `src`.
  readonly origin: string
  // The iframe's accessible title.
  readonly title: string
  // Permissions-Policy for the frame. Default: what a first-party app legitimately needs from
  // inside an iframe (clipboard for share/invite links, WebAuthn for re-auth).
  readonly allow?: string
}

export const FIRST_PARTY_ALLOW = 'clipboard-read; clipboard-write; publickey-credentials-get *'

export function AppEmbed({ origin, title, allow = FIRST_PARTY_ALLOW }: AppEmbedProps) {
  return (
    // Break out of the shell's content padding so the app fills the area below
    // the 3.5rem header edge-to-edge, the way a hosted app should feel.
    <div className="-mx-6 -my-8 md:-mb-8">
      <iframe
        title={title}
        src={origin}
        allow={allow}
        className="block h-[calc(100vh-3.5rem)] w-full border-0 bg-surface"
      />
    </div>
  )
}

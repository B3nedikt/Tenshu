/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Pre-launch construction-gate code (see ConstructionGate). When set at build time, the apex
   * is curtained until a visitor enters this 6-digit code. Unset → no gate. Polish, not security.
   */
  readonly VITE_CONSTRUCTION_CODE?: string
  /**
   * Pre-launch signup gate (see `lib/signupGate.ts`). Any non-empty value closes the door behind
   * "Get started" — the marketing site stays public and existing realms in this browser keep
   * working. Unset → signup open. A curtain, not security.
   */
  readonly VITE_SIGNUP_DISABLED?: string
  // Each hosted app's `VITE_<ID>_ORIGIN` is declared in its own registry file
  // (`apps/registry/apps/<id>.ts`), so adding an app edits nothing here.
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

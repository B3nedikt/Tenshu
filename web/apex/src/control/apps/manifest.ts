// v1 apps manifest registry per `web-control-plane.md` §4.2 +
// `control-ia.md` §4. The IA commits to manifest-driven app data
// (icon, name, install date, version, declared permissions, services
// consumed); the wire-level manifest fetch from each per-subdomain
// app's `manifest.json` (per `web/architecture.md` §6) is a follow-up
// that lands with cross-origin Drive (web roadmap Phase 8). For Phase B,
// the apex hard-codes the v1 entries — one per hosted app, in the app's
// registry file — so the IA renders end-to-end without depending on a
// manifest fetch that doesn't have a real source origin yet.
//
// IA §4.1: apex / Tenshu itself is NOT listed under Apps. The launcher
// includes Tenshu for navigation, but Apps section omits it — mental-
// model overhead without a real configuration target.

import { HOSTED_APPS } from '../../apps/registry'

export type PermissionState = 'granted' | 'denied' | 'askEachTime'

export interface AppPermission {
  readonly id: string
  readonly label: string
  readonly description: string
  readonly state: PermissionState
  // Wall-clock ms when the permission was last exercised; `null` when
  // the app hasn't used it yet on this device. v1 hard-codes plausible
  // values; production reads from the apex's permission-grant audit log.
  readonly lastUsedAtMs: number | null
}

export interface AppServiceLink {
  readonly id: string
  readonly label: string
  // Short reason this app needs the service; surfaces in Detail under
  // each entry. Independent of the service's own description (which
  // lives in §6 Services).
  readonly reason: string
}

export interface AppManifest {
  readonly id: string
  readonly label: string
  readonly description: string
  readonly version: string
  // Path the apex routes to when the user opens the app. Pre-Phase 8
  // this stays under `/drive/*`; once cross-origin lands the manifest
  // entry switches to `https://drive.tenshu.app/...`.
  readonly launchPath: string
  readonly permissions: readonly AppPermission[]
  readonly services: readonly AppServiceLink[]
}

// Each app's entry lives in its registry file (`apps/registry/apps/<id>.ts`, web-app-suite.md F4)
// beside its origin and gateway allowlist, so adding an app edits no list here.
const APPS: readonly AppManifest[] = HOSTED_APPS.map(app => app.manifest)

export function listApps(): readonly AppManifest[] {
  return APPS
}

export function findApp(id: string): AppManifest | null {
  return APPS.find(a => a.id === id) ?? null
}

// Overview entry point: the apex overview's synced settings — today the user's shown apps
// (docs/proposals/web-apex-home.md §3.5). Backed by a REALM `Preference` on the Kotlin side
// (`tenshu-sdk-js/.../Overview.kt`), so the list is shared by every browser of the realm.

import * as bundle from './_bundle'

export type ShownAppsResult =
  | { readonly ok: true; readonly appIds: readonly string[] }
  | { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }

export const Overview = bundle.Overview.getInstance()

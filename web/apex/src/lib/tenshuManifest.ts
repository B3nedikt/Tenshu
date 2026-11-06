// Wire-level Tenshu app-manifest validation (docs/web/architecture.md §6).
//
// Distinct from `control/apps/manifest.ts`, which is the apex's in-realm app
// *registry* (the IA's installed-apps catalogue). This validates the
// `manifest.webmanifest` a per-subdomain app actually serves from its own
// origin — the artifact the apex fetches during the §6 verification flow
// before loading the app. That file (whose own comment defers the fetch to
// "cross-origin Drive, web roadmap Phase 8") consumes this.
//
// Phase A scope: structural validation of the required `tenshu.*` fields.
// Rei publication-signature verification and the bundle `versionHash`
// compare are gated on Rei (architecture §6 steps 3–4) and land later;
// `reiPublication` / `versionHash` are therefore optional here.

export interface TenshuSemanticPermission {
  readonly id: string
  readonly humanDescription: string
  readonly scope?: readonly string[]
}

export interface TenshuManifest {
  readonly name: string
  readonly appId: string
  readonly version: string
  readonly proxyOrigin: string
  readonly reiPublication?: string
  readonly versionHash?: string
  readonly semanticPermissions: readonly TenshuSemanticPermission[]
}

export type ManifestValidation =
  | { readonly valid: true; readonly manifest: TenshuManifest }
  | { readonly valid: false; readonly errors: readonly string[] }

// Validates a parsed `manifest.webmanifest` object. Returns the typed
// manifest on success or the list of every structural problem found (we
// collect all errors rather than failing on the first so a manifest author
// sees the full picture).
export function validateTenshuManifest(raw: unknown): ManifestValidation {
  const errors: string[] = []

  if (!isRecord(raw)) {
    return { valid: false, errors: ['manifest is not an object'] }
  }

  const name = raw.name
  if (typeof name !== 'string' || name.length === 0) {
    errors.push('missing required PWA field: name')
  }

  const tenshu = raw.tenshu
  if (!isRecord(tenshu)) {
    errors.push('missing required field: tenshu')
    return { valid: false, errors }
  }

  requireString(tenshu, 'appId', errors)
  requireString(tenshu, 'version', errors)

  const proxyOrigin = tenshu.proxyOrigin
  if (typeof proxyOrigin !== 'string' || proxyOrigin.length === 0) {
    errors.push('missing required field: tenshu.proxyOrigin')
  } else if (!isValidOrigin(proxyOrigin)) {
    errors.push(`tenshu.proxyOrigin is not a valid origin: ${proxyOrigin}`)
  }

  const perms = tenshu.semanticPermissions
  const validatedPerms: TenshuSemanticPermission[] = []
  if (!Array.isArray(perms)) {
    errors.push('missing required field: tenshu.semanticPermissions (array)')
  } else {
    perms.forEach((perm, i) => {
      if (!isRecord(perm)) {
        errors.push(`tenshu.semanticPermissions[${i}] is not an object`)
        return
      }
      const id = perm.id
      const humanDescription = perm.humanDescription
      if (typeof id !== 'string' || id.length === 0) {
        errors.push(`tenshu.semanticPermissions[${i}].id is missing`)
      }
      if (typeof humanDescription !== 'string' || humanDescription.length === 0) {
        errors.push(`tenshu.semanticPermissions[${i}].humanDescription is missing`)
      }
      const scope = perm.scope
      if (scope !== undefined && !isStringArray(scope)) {
        errors.push(`tenshu.semanticPermissions[${i}].scope must be a string[]`)
      }
      if (typeof id === 'string' && typeof humanDescription === 'string') {
        validatedPerms.push({
          id,
          humanDescription,
          ...(isStringArray(scope) ? { scope } : {}),
        })
      }
    })
  }

  // Optional pre-Rei fields — validated for type only when present.
  const reiPublication = optionalString(tenshu, 'reiPublication', errors)
  const versionHash = optionalString(tenshu, 'versionHash', errors)

  if (errors.length > 0) {
    return { valid: false, errors }
  }

  return {
    valid: true,
    manifest: {
      name: name as string,
      appId: tenshu.appId as string,
      version: tenshu.version as string,
      proxyOrigin: proxyOrigin as string,
      semanticPermissions: validatedPerms,
      ...(reiPublication !== undefined ? { reiPublication } : {}),
      ...(versionHash !== undefined ? { versionHash } : {}),
    },
  }
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

function isStringArray(v: unknown): v is string[] {
  return Array.isArray(v) && v.every((x) => typeof x === 'string')
}

function requireString(obj: Record<string, unknown>, key: string, errors: string[]): void {
  const v = obj[key]
  if (typeof v !== 'string' || v.length === 0) {
    errors.push(`missing required field: tenshu.${key}`)
  }
}

function optionalString(
  obj: Record<string, unknown>,
  key: string,
  errors: string[],
): string | undefined {
  const v = obj[key]
  if (v === undefined) return undefined
  if (typeof v !== 'string' || v.length === 0) {
    errors.push(`tenshu.${key} must be a non-empty string when present`)
    return undefined
  }
  return v
}

function isValidOrigin(value: string): boolean {
  try {
    const url = new URL(value)
    return `${url.protocol}//${url.host}` === value
  } catch {
    return false
  }
}

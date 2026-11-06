// The third-party packages apex serves, with their licence files, read from node_modules at build
// time — the data behind the third-party licences page, `/legal/licenses/<lang>` (licenses.ts,
// generate.ts buildLicenses).
//
// How the set is chosen: apex's production `dependencies`, followed transitively through every
// package's own `dependencies` and `optionalDependencies` (peers are the host's to bring, and
// they are in the graph through it). Workspace packages (`@tenshu/*`) are walked through but not
// listed — they are this repo's own code. Two kinds of entry are adjusted by hand, below:
//   - BUILD_ONLY: listed in `dependencies` but never in what the browser gets (build plugins);
//     neither they nor their dependencies are listed.
//   - BUNDLER_RUNTIME: build tools whose own runtime code is emitted INTO the bundle.
// What the Kotlin/JS SDK bundle carries inside it is not in node_modules at all: sdkBundle.ts.
//
// ⚠ The walk can over-include (a dependency tree-shaken away still gets a notice — harmless), but
// it must never under-include. vitePlugin.ts holds it to that: in `vite build` every module of the
// real bundle that comes from node_modules has to belong to a package listed here, or the build
// fails (uncoveredBundleModules).
//
// ⚠ Pure Node, no import from `src/`: plain Node runs it for `pnpm -F apex build:support`.
import { existsSync, readdirSync, readFileSync, realpathSync } from 'node:fs'
import { dirname, join, sep } from 'node:path'

/** In apex's `dependencies` for the build only — Tailwind's Vite plugin and its compiler. */
export const BUILD_ONLY: readonly string[] = ['@tailwindcss/vite', '@tailwindcss/node']

/**
 * Build tools that write some of their own code into the served JavaScript: Vite's
 * modulepreload polyfill and preload helper, and Rolldown's runtime helpers. Each entry is
 * resolved from the package before it (`rolldown` is Vite's dependency, not apex's). Their own
 * dependencies are not walked — those stay in the build.
 */
export const BUNDLER_RUNTIME: readonly string[] = ['vite', 'rolldown']

export interface LicenseFile {
  /** The file's name in the package, e.g. `LICENSE.md`, `NOTICE`. */
  readonly name: string
  readonly text: string
}

export interface ThirdPartyPackage {
  readonly name: string
  readonly version: string
  /** The SPDX expression from package.json, or `UNKNOWN` when it declares none. */
  readonly license: string
  /** Where to read more: the repository, else the homepage; '' when the package names neither. */
  readonly url: string
  /** package.json `author`, for the copyright line when the package ships no licence file. */
  readonly author: string
  /** LICENSE / LICENCE / COPYING / NOTICE / COPYRIGHT files at the package's root, as shipped. */
  readonly files: readonly LicenseFile[]
  /** The package's real directory (pnpm's store path): the identity a bundle module maps to. */
  readonly dir: string
}

interface PackageJson {
  readonly name?: string
  readonly version?: string
  readonly license?: string | { readonly type?: string }
  readonly licenses?: readonly { readonly type?: string }[]
  readonly repository?: string | { readonly url?: string }
  readonly homepage?: string
  readonly author?: string | { readonly name?: string }
  readonly dependencies?: Record<string, string>
  readonly optionalDependencies?: Record<string, string>
}

const LICENSE_FILE = /^(licen[cs]e|copying|notice|copyright)([.\-_].*)?$/i

function readJson(file: string): PackageJson {
  return JSON.parse(readFileSync(file, 'utf8')) as PackageJson
}

/** Node's lookup: `node_modules/<name>` in [fromDir] and every directory above it. */
function resolvePackageDir(name: string, fromDir: string): string | null {
  for (let dir = fromDir; ; dir = dirname(dir)) {
    const candidate = join(dir, 'node_modules', name, 'package.json')
    if (existsSync(candidate)) return realpathSync(dirname(candidate))
    if (dirname(dir) === dir) return null
  }
}

function licenseOf(pkg: PackageJson): string {
  if (typeof pkg.license === 'string' && pkg.license) return pkg.license
  if (pkg.license && typeof pkg.license === 'object' && pkg.license.type) return pkg.license.type
  const legacy = pkg.licenses?.map((l) => l.type).filter(Boolean)
  return legacy && legacy.length > 0 ? legacy.join(' OR ') : 'UNKNOWN'
}

function urlOf(pkg: PackageJson): string {
  const repo = typeof pkg.repository === 'string' ? pkg.repository : pkg.repository?.url
  if (repo) {
    const url = repo
      .replace(/^git\+/, '')
      .replace(/^git:\/\//, 'https://')
      .replace(/^ssh:\/\/git@/, 'https://')
      .replace(/\.git$/, '')
    if (/^https?:\/\//.test(url)) return url
    // The npm shorthand: `user/repo`, `github:user/repo`.
    const short = /^(?:github:)?([\w.-]+\/[\w.-]+)$/.exec(url)
    if (short) return `https://github.com/${short[1]}`
  }
  return pkg.homepage ?? ''
}

function licenseFilesOf(dir: string): LicenseFile[] {
  return readdirSync(dir, { withFileTypes: true })
    .filter((e) => e.isFile() && LICENSE_FILE.test(e.name))
    .map((e) => e.name)
    .sort()
    .map((name) => ({ name, text: readFileSync(join(dir, name), 'utf8').replace(/\r\n?/g, '\n').trim() }))
}

const isWorkspace = (dir: string) => !dir.split(sep).includes('node_modules')

/**
 * Every third-party package in [appDir]'s production graph (see the file comment), sorted by
 * name then version. Throws when a declared, non-optional dependency is not installed: a notice
 * page built from a half-installed tree would be silently incomplete.
 */
export function collectThirdParty(appDir: string): ThirdPartyPackage[] {
  const found = new Map<string, ThirdPartyPackage>()
  const seen = new Set<string>()

  const visit = (dir: string, walkDeps: boolean) => {
    if (seen.has(dir)) return
    seen.add(dir)
    const pkg = readJson(join(dir, 'package.json'))
    if (!isWorkspace(dir)) {
      found.set(dir, {
        name: pkg.name ?? dir,
        version: pkg.version ?? '0.0.0',
        license: licenseOf(pkg),
        url: urlOf(pkg),
        author: (typeof pkg.author === 'string' ? pkg.author : pkg.author?.name) ?? '',
        files: licenseFilesOf(dir),
        dir,
      })
    }
    if (!walkDeps) return
    const required = Object.keys(pkg.dependencies ?? {})
    const optional = Object.keys(pkg.optionalDependencies ?? {})
    for (const name of [...required, ...optional]) {
      if (BUILD_ONLY.includes(name)) continue
      const depDir = resolvePackageDir(name, dir)
      if (depDir) visit(depDir, true)
      else if (!optional.includes(name)) throw new Error(`thirdParty.ts: ${pkg.name ?? dir} depends on ${name}, which is not installed — run pnpm install`)
    }
  }

  const root = realpathSync(appDir)
  visit(root, true)
  let from = root
  for (const name of BUNDLER_RUNTIME) {
    const dir = resolvePackageDir(name, from)
    if (!dir) throw new Error(`thirdParty.ts: cannot resolve the bundler runtime ${name} from ${from}`)
    visit(dir, false)
    from = dir
  }

  return [...found.values()].sort((a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version, undefined, { numeric: true }))
}

/** The package directory a module file belongs to: the nearest directory above it with a named package.json. */
export function packageDirOf(file: string): string | null {
  for (let dir = dirname(file); ; dir = dirname(dir)) {
    const manifest = join(dir, 'package.json')
    if (existsSync(manifest) && readJson(manifest).name) return realpathSync(dir)
    if (dirname(dir) === dir) return null
  }
}

/**
 * The modules of a real bundle that come from a package the notices do not cover — `[]` when the
 * page is complete. [moduleIds] are Rollup/Rolldown module ids; virtual ones (`\0…`) and this
 * repo's own files are skipped, and the Kotlin/JS SDK bundle is covered by sdkBundle.ts instead.
 */
export function uncoveredBundleModules(moduleIds: Iterable<string>, packages: readonly ThirdPartyPackage[]): string[] {
  const covered = new Set(packages.map((p) => p.dir))
  const out = new Set<string>()
  for (const raw of moduleIds) {
    if (raw.startsWith('\0')) continue
    const id = raw.split('?')[0]!
    if (!id.split(/[\\/]/).includes('node_modules') || !existsSync(id)) continue
    const dir = packageDirOf(id)
    if (dir && !covered.has(dir)) out.add(dir)
  }
  return [...out].sort()
}

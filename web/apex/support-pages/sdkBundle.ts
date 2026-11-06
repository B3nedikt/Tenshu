// What the Kotlin/JS SDK bundle carries inside it — HAND-MAINTAINED, for the third-party licences
// page (licenses.ts).
//
// `@tenshu/sdk` (web/sdk) re-exports one file, the webpack bundle Gradle builds with
// `./gradlew :tenshu-sdk-js:jsBrowserDistribution`
// (tenshu-sdk-js/build/dist/js/productionExecutable/tenshu-sdk-js.js). It is not an npm package:
// pnpm and thirdParty.ts never see what is inside it, and the support pages must build where
// Gradle does not (`pnpm -F apex build:support`, generate.ts). So the list is written out here.
//
// How it was established (2026-10-23, re-check the same way when the SDK's graph changes):
//   - Kotlin libraries: the external modules of `./gradlew :tenshu-sdk-js:dependencies
//     --configuration jsRuntimeClasspath`, cross-checked against the bundle's source map
//     (`tenshu-sdk-js.js.map` `sources`) and its strings. ⚠ That classpath also carries Compose
//     (runtime, ui, foundation, animation), skiko, Koin, AndroidX lifecycle/savedstate/collection,
//     Stately and kotlinx-browser, pulled in through shared modules — the Kotlin/JS IR compiler's
//     dead-code elimination drops all of them, and none of their code is in the bundle, so they
//     are not listed. Should one start to ship, list it here. This repo's own modules are not listed.
//   - npm packages: the `dependencies` of the Kotlin plugin's generated
//     build/js/packages/Drive-tenshu-sdk-js/package.json (core-common's `npm(...)` @noble/*, Ktor's
//     `ws`, kotlinx-datetime's @js-joda/core, Calendar's @js-joda/timezone), which webpack inlines; the bundle's
//     `tenshu-sdk-js.js.LICENSE.txt` (Terser's extracted banners) names noble and js-joda too.
//     Their licence files are copied verbatim into licenseTexts/ from build/js/node_modules.
//   - webpack's own runtime, which every webpack bundle starts with.
//
// ⚠ licenses.test.ts holds every entry with `catalogVersion` to gradle/libs.versions.toml, and —
// whenever a Gradle build has left build/js/packages/Drive-tenshu-sdk-js/package.json behind —
// every npm package there to an entry here at the same version (or to SDK_BUNDLE_NOT_BUNDLED). A
// version bump cannot drift silently; a NEW Kotlin library still needs a hand here.
//
// ⚠ Pure: no imports. Plain Node runs it for the support-page build.

export interface SdkBundleLibrary {
  /** Maven `group:artifact` (one entry may stand for a family, named in [name]), or the npm name. */
  readonly name: string
  readonly version: string
  /** SPDX id. */
  readonly license: string
  readonly url: string
  /** The copyright holder, for the licence's standard text (licensesPage.ts standardText). */
  readonly copyright: string
  /** The package's own licence file, copied verbatim into licenseTexts/; else the standard text. */
  readonly textFile?: string
  /** A line shown above the licence text. */
  readonly note?: string
  /**
   * The gradle/libs.versions.toml `[versions]` key this version comes from. The resolved version
   * may carry a suffix the catalogue's does not (kotlinx-datetime's `-0.6.x-compat`).
   */
  readonly catalogVersion?: string
}

const KOTLIN_COPYRIGHT = 'JetBrains s.r.o. and Kotlin Programming Language contributors'

export const SDK_BUNDLE_LIBRARIES: readonly SdkBundleLibrary[] = [
  {
    name: 'org.jetbrains.kotlin:kotlin-stdlib, kotlin-dom-api-compat, kotlinx-atomicfu-runtime',
    version: '2.4.20',
    license: 'Apache-2.0',
    url: 'https://github.com/JetBrains/kotlin',
    copyright: KOTLIN_COPYRIGHT,
    catalogVersion: 'kotlin',
  },
  {
    name: 'org.jetbrains.kotlinx:kotlinx-coroutines-core',
    version: '1.11.0',
    license: 'Apache-2.0',
    url: 'https://github.com/Kotlin/kotlinx.coroutines',
    copyright: KOTLIN_COPYRIGHT,
    catalogVersion: 'kotlinx-coroutines',
  },
  {
    name: 'org.jetbrains.kotlinx:kotlinx-serialization-core, kotlinx-serialization-json, kotlinx-serialization-json-io',
    version: '1.11.0',
    license: 'Apache-2.0',
    url: 'https://github.com/Kotlin/kotlinx.serialization',
    copyright: KOTLIN_COPYRIGHT,
    catalogVersion: 'kotlinxSerialization',
  },
  {
    name: 'org.jetbrains.kotlinx:kotlinx-datetime',
    version: '0.8.0-0.6.x-compat',
    license: 'Apache-2.0',
    url: 'https://github.com/Kotlin/kotlinx-datetime',
    copyright: KOTLIN_COPYRIGHT,
    catalogVersion: 'kotlinx-datetime',
  },
  {
    name: 'org.jetbrains.kotlinx:atomicfu',
    version: '0.33.0',
    license: 'Apache-2.0',
    url: 'https://github.com/Kotlin/kotlinx-atomicfu',
    copyright: KOTLIN_COPYRIGHT,
    catalogVersion: 'kotlinx-atomicfu',
  },
  {
    name: 'org.jetbrains.kotlinx:kotlinx-io-core, kotlinx-io-bytestring',
    version: '0.9.2',
    license: 'Apache-2.0',
    url: 'https://github.com/Kotlin/kotlinx-io',
    copyright: KOTLIN_COPYRIGHT,
    catalogVersion: 'kotlinx-io',
  },
  {
    name: 'io.ktor:ktor-client-core, ktor-client-js, ktor-client-content-negotiation, ktor-serialization-kotlinx-json, ktor-serialization-kotlinx, ktor-serialization, ktor-http, ktor-http-cio, ktor-io, ktor-utils, ktor-events, ktor-websockets, ktor-websocket-serialization, ktor-sse, ktor-openapi-schema',
    version: '3.6.1',
    license: 'Apache-2.0',
    url: 'https://github.com/ktorio/ktor',
    copyright: KOTLIN_COPYRIGHT,
    catalogVersion: 'ktor',
  },
  {
    name: '@noble/hashes',
    version: '1.7.1',
    license: 'MIT',
    url: 'https://github.com/paulmillr/noble-hashes',
    copyright: '2022 Paul Miller (https://paulmillr.com)',
    textFile: 'noble-hashes.txt',
  },
  {
    name: '@noble/curves',
    version: '1.8.1',
    license: 'MIT',
    url: 'https://github.com/paulmillr/noble-curves',
    copyright: '2022 Paul Miller (https://paulmillr.com)',
    textFile: 'noble-curves.txt',
  },
  {
    name: '@noble/ciphers',
    version: '1.2.1',
    license: 'MIT',
    url: 'https://github.com/paulmillr/noble-ciphers',
    copyright: '2022 Paul Miller (https://paulmillr.com)',
    textFile: 'noble-ciphers.txt',
  },
  {
    name: '@js-joda/core',
    version: '3.2.0',
    license: 'BSD-3-Clause',
    url: 'https://github.com/js-joda/js-joda',
    copyright: '2016, Philipp Thürwächter & Pattrick Hüper',
    textFile: 'js-joda-core.txt',
    note: 'Its source files also carry: Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos (BSD-3-Clause).',
  },
  {
    // The IANA zone database `Calendar` resolves event zones with (tenshu-sdk-js/build.gradle.kts).
    name: '@js-joda/timezone',
    version: '2.26.0',
    license: 'BSD-3-Clause',
    url: 'https://github.com/js-joda/js-joda',
    copyright: '2016, Philipp Thürwächter & Pattrick Hüper',
    textFile: 'js-joda-timezone.txt',
    note: 'It bundles the IANA Time Zone Database (tzdb 2026a), which is in the public domain.',
  },
  {
    // Only ws's browser stub (`browser.js`, which throws "ws does not work in the browser") reaches
    // the bundle: Ktor's JS engine asks for `ws` on Node only.
    name: 'ws',
    version: '8.20.1',
    license: 'MIT',
    url: 'https://github.com/websockets/ws',
    copyright: '2011 Einar Otto Stangvik <einaros@gmail.com>',
    textFile: 'ws.txt',
  },
  {
    name: 'webpack (runtime)',
    version: '5.108.1',
    license: 'MIT',
    url: 'https://github.com/webpack/webpack',
    copyright: 'JS Foundation and other contributors',
    textFile: 'webpack.txt',
  },
]

/**
 * npm packages the SDK's Kotlin graph declares that are NOT in the bundle, with why — so the
 * drift check in licenses.test.ts can tell "left out on purpose" from "forgotten". Empty today.
 */
export const SDK_BUNDLE_NOT_BUNDLED: Readonly<Record<string, string>> = {}

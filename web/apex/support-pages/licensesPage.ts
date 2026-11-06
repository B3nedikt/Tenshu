// The body of the third-party licences page (licenses.ts): the npm packages thirdParty.ts finds,
// then what the Kotlin/JS SDK bundle carries (sdkBundle.ts), each with its licence text in full.
// generate.ts buildLicenses wraps it in the site chrome.
//
// One <details> per package, so the page reads as a list with JavaScript off and still carries
// every text (it is long: some packages ship long LICENSE files, Vite's lists what its own build
// bundles). ⚖️ The texts are reproduced as shipped, never translated or shortened.
//
// ⚠ Pure Node, no import from `src/`: plain Node runs it for `pnpm -F apex build:support`.
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { LicensesCopy } from './licenses.ts'
import type { SdkBundleLibrary } from './sdkBundle.ts'
import type { LicenseFile, ThirdPartyPackage } from './thirdParty.ts'

const TEXTS_DIR = join(dirname(fileURLToPath(import.meta.url)), 'licenseTexts')

const escape = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

/** A licence file kept in licenseTexts/, verbatim. */
const readText = (file: string) => readFileSync(join(TEXTS_DIR, file), 'utf8').replace(/\r\n?/g, '\n').trim()

/** A licence's standard text, for a package that ships no file of its own. */
export function standardText(spdx: string, copyright: string): string | null {
  const holder = copyright ? `Copyright (c) ${copyright}` : ''
  switch (spdx) {
    case 'MIT':
      return [
        'MIT License',
        holder,
        'Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:',
        'The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.',
        'THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.',
      ]
        .filter(Boolean)
        .join('\n\n')
    case 'Apache-2.0':
      return readText('Apache-2.0.txt')
    default:
      return null
  }
}

interface Entry {
  readonly name: string
  readonly version: string
  readonly license: string
  readonly url: string
  readonly files: readonly LicenseFile[]
  /** Set when [files] is empty: the copyright line for the standard text. */
  readonly fallbackHolder: string
  readonly note?: string
}

function entryHtml(entry: Entry, copy: LicensesCopy): string {
  const texts =
    entry.files.length > 0
      ? entry.files.map((f) => (entry.files.length > 1 ? `<p class="licenses__file">${escape(f.name)}</p>` : '') + `<pre>${escape(f.text)}</pre>`)
      : [
          `<p>${escape(copy.noLicenseFile)}</p>`,
          `<pre>${escape(standardText(entry.license, entry.fallbackHolder) ?? entry.license)}</pre>`,
        ]
  const source = entry.url ? ` · <a href="${escape(entry.url)}">${escape(copy.source)}</a>` : ''
  return [
    '<details class="licenses__entry">',
    `<summary><span class="licenses__name">${escape(entry.name)}</span> <span class="licenses__version">${escape(entry.version)}</span> — <span class="licenses__license">${escape(entry.license)}</span></summary>`,
    `<p>${escape(copy.license)}: ${escape(entry.license)}${source}</p>`,
    entry.note ? `<p>${escape(entry.note)}</p>` : '',
    ...texts,
    '</details>',
  ]
    .filter(Boolean)
    .join('\n')
}

/** The article's HTML for one language. Trusted copy, escaped data. */
export function licensesHtml(copy: LicensesCopy, packages: readonly ThirdPartyPackage[], sdk: readonly SdkBundleLibrary[]): string {
  const web: Entry[] = packages.map((p) => ({ ...p, fallbackHolder: p.author }))
  const sdkEntries: Entry[] = sdk.map((l) => ({
    name: l.name,
    version: l.version,
    license: l.license,
    url: l.url,
    files: [{ name: 'LICENSE', text: l.textFile ? readText(l.textFile) : (standardText(l.license, l.copyright) ?? l.license) }],
    note: l.note,
    fallbackHolder: l.copyright,
  }))
  return [
    copy.intro,
    `<h2 id="web">${escape(copy.webHeading)}</h2>`,
    `<p>${escape(copy.webIntro)} ${escape(copy.count(web.length))}.</p>`,
    ...web.map((e) => entryHtml(e, copy)),
    `<h2 id="sdk">${escape(copy.sdkHeading)}</h2>`,
    `<p>${escape(copy.sdkIntro)} ${escape(copy.count(sdkEntries.length))}.</p>`,
    ...sdkEntries.map((e) => entryHtml(e, copy)),
    `<h2 id="assets">${escape(copy.assetsHeading)}</h2>`,
    `<p>${escape(copy.assetsText)}</p>`,
  ].join('\n')
}

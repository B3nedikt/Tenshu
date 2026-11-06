# AboutLibraries config — licence texts the build does not fetch

Read by the open-source-licences convention in the root `build.gradle.kts` (`collect.configPath`);
see `docs/oss-licences.md`.

The plugin runs in **offline mode**, so every licence *text* that ends up in an app's notices comes
from `licenses/` here, matched by `hash` (the key the licence has in the generated
`aboutlibraries.json` — the SPDX id for the common ones, an MD5 for a POM licence without one).

- The SPDX texts are verbatim copies of `spdx/license-list-data` `text/<id>.txt`.
- A licence with no file here still appears in the notices by **name and URL**, without its text.
  When a new dependency brings one in, the build prints it (`strictMode = WARN`): add its text here
  if it is a licence whose terms require the text to travel with the binary.

## Copyright lines and NOTICE files (`docs/oss-licences.md` §1.1)

The build gives every MIT / BSD / ISC library its own licence text with the real copyright line, and
attaches every Apache-2.0 §4(d) NOTICE file it finds inside a resolved artifact. Two hand-kept inputs
cover what an artifact does not carry:

- `copyrights.json` — `uniqueId` (or `group:prefix*`) → the copyright line(s), copied from the
  project's own LICENSE file; note the source under `_sources`. A template-licensed library with no
  line here and no LICENSE in its artifact **fails the build** with its name.
- `notices/<group>__<artifact>@<version>.txt` — a NOTICE the artifact omits, copied verbatim from that
  release. Pinned by the version in the filename: a bump fails the build until it is re-copied.

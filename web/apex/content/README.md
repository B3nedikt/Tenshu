# apex content — static public pages

Static pages built beside the SPA, outside React. The design is
[`docs/proposals/store-presence-and-support-pages.md`](../../../docs/proposals/store-presence-and-support-pages.md) §4.
It holds the support pages and the support hub's Getting started page (§4.7).

```
content/apps/<app>/support/<lang>.md            →   dist/apps/<app>/support/<platform>/<lang>/index.html
content/support/getting-started/<lang>.md       →   dist/apps/support/getting-started/<lang>/index.html
(support-pages/hub.ts, no Markdown)             →   dist/apps/support/<lang>/index.html  (the hub)
```

⚠ Nothing is written at `/support`: it is reserved for a possible support app.

## Adding an app to the hub

The hub lists all ten apps; those without pages are greyed out as "Coming soon". Once
`content/apps/<app>/support/` exists, set `support: true` on its line in `HUB_APPS`
(`support-pages/hub.ts`), and list its carousel captures in `screens`. The build fails while the
flag and the content disagree.

- **Build:** `pnpm -F apex build` emits these pages through the Vite plugin in `support-pages/vitePlugin.ts`.
  `pnpm -F apex build:support [outDir]` builds only these pages, with plain Node. It needs no
  Gradle-built SDK.
- **Test:** `pnpm -F apex exec vitest run support-pages`. A new page must be added to `EXPECTED` there.
- **Serving:** the Caddyfile tries `{path}/index.html` before the SPA fallback. A page is
  reachable at its slash-less URL, and no Caddy change is needed to add one.

## Writing a page

The front matter holds `app`, `name`, `lang`, `title`, `description` (`{platform}` is replaced),
`platforms` and `defaultPlatform`.

- **Sections:** the five `## Heading {#id}` sections, in this order: `get-started`, `privacy`,
  `problems`, `requirements`, `contact`. The build fails on any other order.
- **Platform blocks:** `::: ios android` … `:::` keeps the lines between them only on the listed
  platforms. Blocks do not nest.
- **Screenshot slots:** `![alt text describing the screen](shot:NN-screen)` — or
  `shot:<app>/NN-screen` for another app's screen — resolves through
  `store/<app>/screenshots.manifest.json` to the published capture; until the manifest lists it the
  page shows a labelled placeholder. ⚠ The name must be in `store/<app>/screens.json`: on iOS a
  screen the pipeline captures, on other platforms also a `planned` one
  (`support-pages/screens.test.ts`; `docs/how-to/store-screenshots.md` §5). Write the alt text
  from what that capture shows.
- **Sources:** `{source: docs/path.md | Label}` renders a visible source link. ⚠ Every item in
  the privacy section needs at least one, or the build fails. The test checks that each path
  exists in the repo.
  - A claim may say no more than the cited doc says.
  - Name no competitor (D6).
  - ⚖️ Counsel reads the section before it goes public.

## `/apps/<app>/support` with no platform or language

The build also writes `apps/<app>/support/index.html` and `apps/<app>/support/<platform>/index.html`.
Each is a `noindex` page whose inline script does two things:

- It picks the platform from `navigator.userAgent`. An iPad that reports a Mac counts as iOS.
  An unknown platform falls back to the app's `defaultPlatform`.
- It picks the language from `navigator.languages`, the same list the browser sends as
  `Accept-Language`, and falls back to `en`.

It then calls `location.replace`. Without JavaScript, the page is a list of links.

This is done in the browser and not in Caddy because only the build knows which pages exist.
A Caddy rule would have to repeat that list.

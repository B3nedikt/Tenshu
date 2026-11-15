# Web apps — use the window: one layout, real routes, desktop pointer rules

**Status:** **Design accepted (2026-11-14); L1–L7 built (§4.1–§4.7).** §2 is verified
fact; §3 is the design; §4 the phases; §6 records the decisions.

**Owner:** Benedikt

**Relationship:** follows [`web-apex-home.md`](web-apex-home.md) (the overview and the glass top bar,
§3.7 *Inside an app*). Builds on [`drive-web-parity.md`](drive-web-parity.md) (W5 listing prefs) and
[`photos-app.md`](photos-app.md) §42 (what Photos on web may show — §42.6 is binding here). House
Rule 5 holds: every change below is TypeScript + React in `web/<app>/`, plus SDK ops where §4 says so.

---

## 1. The problem

The apex now gives every app a glass top bar with its icon and name. Inside, the apps still look like
the phone build rendered in a browser:

- **Each app repeats its name** as a page headline under the top bar ("Drive / Your end-to-end
  encrypted files.").
- **Seven of nine apps cap their content at 768 px** (`max-w-3xl`; Photos and Calendar at
  `max-w-6xl`, 1152 px). On a 1440 px window almost half the width is empty.
- **Navigation is a tab strip** copied from the phone's bottom bar, and the phone's **bottom sheets**
  (file actions, multi-select, add file) have no desktop shape: no right-click, no hover, no
  double-click.
- **Nothing has a URL.** Every app keeps its tab and folder in React state, so reload, back and a
  shared link all land on the app's first screen.

## 2. Ground facts (verified 2026-11-14)

- **Shells.** Each app's `web/<app>/src/App.tsx` is a `<main className="mx-auto max-w-…">` holding an
  `<h1>` + tagline, a tab `<nav>`, `RuntimeBanner` / `SyncBanner`, and an `ErrorBoundary` keyed by tab.
  Drive hard-codes its strings; the others read `app.title` / `app.tagline`.
- **Drive already has** grid/list and sort (`lib/listingPrefs.ts`), a details panel
  (`FileDetails`, opened by selecting a row), multi-select with a count bar, Move, Share, Manage
  access and a preview overlay (`FilePreview`, `FileOpenerOverlays`).
- **Photos already has** the capture-date feed with month sections and a scrubber (`FeedView`,
  `Scrubber` — replaced by the browser's scrollbar in §4.9), multi-select (`SelectionBar`), a viewer with stored details (`Viewer` → `DetailsPanel`),
  albums, favourites and trash. Its allowlist is exact names (`apps/registry/apps/photos.ts`), and
  §42.6 keeps faces, labels, semantic search, upload, Places (until Timeline exposes place reads) and
  duplicates off web v1.
- **The browser runtime has a preference service.** `tenshu-sdk-js`'s `Overview.kt` and `Calendar.kt`
  read and write `RealmSetting` / `DeviceSetting` keys through `preferenceService`. ⚠ The header of
  Drive's `lib/listingPrefs.ts` says the JS runtime registers no preference service — that is
  **stale**; it was true when W5 shipped.
- **Routing.** The apex already routes each app with a splat (`drive/files/*`, via the registry's
  `routePath`), but `AppEmbed` sets the iframe `src` to the bare `origin()`: the splat reaches the apex
  and stops there. Inside the frames there is no router at all.
- ⚠⚠ **A URL path reaches the server.** A reload sends the apex path to the apex host and the iframe
  path to the app host — both ours, both plaintext, both in access logs. A **fragment** (`#…`) is never
  sent. Folder names, file names and album titles are user data.

## 3. Design

### 3.1 One layout for every app

No headline. The top bar already names the app. Below it, one shared layout (a new
`@tenshu/app-kit/ui` → `AppLayout`, §4.1):

```
┌ top bar (apex) ───────────────────────────────────────────────┐
├ sidebar 240 ┬ toolbar (breadcrumb · sort · view · ⓘ) ──┬ pane 320 ┤
│ + New       │                                         │ details  │
│ sections    │ content — fills the width               │ (sticky, │
│ …           │                                         │ optional)│
│ storage     │                                         │          │
└─────────────┴─────────────────────────────────────────┴──────────┘
```

- **Sidebar** replaces the tab strip: the app's sections, its primary action on top, app-specific
  footer content (Drive: the storage meter). **Settings** moves to the sidebar foot.
- **Content** has no `max-w` cap. Grids use `auto-fill` so columns follow the width; lists become
  tables with real columns (Drive: Name · Size · Modified · Shared with).
- **Pane** is optional per app (Drive, Photos, Contacts' detail, Mia's sources).
- **Narrow fallback, below ~900 px** (phone browsers, a half-screen window): the sidebar collapses
  behind a menu button, the pane becomes a bottom sheet, and the selection bar docks at the bottom —
  the native shapes come back, from the same components.

Per app (the design canvas has a board each): **Calendar** — mini month + calendar visibility in the
sidebar, a full-width grid. **Contacts** — list and person side by side. **Fit** — a wide dashboard and
a week table. **Timeline** — the day feed beside the map. **Maps** — the map fills the window with a
floating places panel. **Home** — rooms across the width, read-only. **Mia** — history, conversation,
sources.

### 3.2 Pointer rules (Drive first, Photos the same)

| Input | Effect |
|---|---|
| Click | Select. If the pane is open, it shows the selection. |
| Double-click | File → viewer. Folder → go into it. |
| Space | Viewer (Quick Look); Space again closes. |
| Hover | Shows the tile's ⋯ and its select circle. |
| Right-click / ⋯ | The item menu (§3.3). |
| ⌘/Ctrl-click, Shift-click | Add to / extend the selection. |
| ⓘ or ⌘I | Open or close the pane. **It stays as you left it** (D2). |
| Enter · F2 · ⌫ | Open · Rename · Delete (with the existing confirm). Enter opens, as on the web (Google Drive), not Finder's rename (§4.3). |
| Esc | Close the viewer → the menu → clear the selection. |

**The viewer** covers the content area (the top bar stays): a back button, the name, date and
"n of N", Share · Download · Delete · ⋯ on the right, prev/next arrows, and a filmstrip of the
folder's (or feed's) neighbours along the bottom. ⓘ opens the same pane beside it. Drive's viewer is
`FilePreview` grown into this shape; Photos' is its existing `Viewer` with a filmstrip added.

### 3.3 Where each bottom sheet goes

| Native sheet | Web | Notes |
|---|---|---|
| File actions (`FileActionsBottomSheet`) | Right-click / ⋯ menu: Open · Download / Share · Copy link · Manage access / Move · Rename / Delete | *Details & activity* becomes the pane, not a menu row. *Make available offline* stays hidden — the browser keeps no bytes. |
| Multi-select (`MultiSelectBottomSheet`) | Selection bar replacing the toolbar: "3 selected · 10.1 MB", Share · Copy link · Move · Download · Delete | Drive's existing count bar, promoted. |
| Add file (`AddFileBottomSheet`) | **+ New** dropdown: Create (folder, text file) / Upload (files, folder) — plus drop anywhere | *Upload images* folds into *Upload files*. |

Photos' item menu: Favourite · Download · Add to album · Move to trash. (No Hide: §42.6 preserves the
flag but never sets it.)

### 3.4 Routes — sections in the path, user data in the fragment

Each app gets a router (`react-router`, as the apex uses). The app's **section** is a fixed
vocabulary and goes in the path; anything the **user named or that identifies their data** goes in
the fragment:

```
tenshu.app/drive/files#/Documents/Taxes        tenshu.app/photos/albums#a=<albumId>
tenshu.app/drive/recents                       tenshu.app/photos/library#view=year
```

- The apex forwards its splat + fragment into the iframe `src` (`origin + /files#/Documents/Taxes`);
  `AppEmbed` builds it, so the frame's origin is still the registry's.
- The app reports navigation back with one `postMessage` (`{ type: 'nav', path, hash }`); the apex
  checks the sender's origin, accepts only a path from the app's own section list and a fragment, and
  `history.replaceState`s (push for section changes — and for opening a Drive folder — so back works).
- ⚠⚠ **Never a folder name, file name or album title in a path segment** — the apex and app hosts
  would log them. A test pins it (§5).

This unblocks deep links from the bell, from Mia's sources and from cross-app open
([`control-cross-app-open.md`](control-cross-app-open.md)).

### 3.5 Photos — option B

Sidebar: **Library** · *Collections* — **Years**, **Videos**, *Places* (§42.4: shown only once
Timeline exposes place reads), *People* (§42.1: hidden on v1) · *Albums* (each album, + New album) ·
**Favourites** · **Trash**. Search sits in the top bar.

Library view modes: **Mosaic / Day / Month / Year** — densities of the same feed, laid out client-side
from `photo.feedPage` + `photo.captureTimes` (the month sections exist; day and year are the same
grouping at other keys). Years is the year grouping as a collection page.

### 3.6 Remembered settings

The pane's open state, Drive's sort and layout, and Photos' view mode are **per app, stored with the
preference service**, through typed SDK functions in that app's own module — the
`Overview.kt` / `Calendar.kt` pattern — and the app's allowlist names them exactly. Not a generic
`preference.*` gateway method: it would let any framed app read every app's settings.
Until an app's slice lands, it keeps `localStorage` (D2).

## 4. Phases

Each phase is one PR; L1 can be split per app.

| Phase | Scope | Needs |
|---|---|---|
| **L1** | `AppLayout` in `@tenshu/app-kit/ui` (§4.1); every app: headline out, tab strip → sidebar, `max-w` gone, narrow fallback. | Web only |
| **L2** | Routes (§3.4): a router per app, the apex forwards splat + fragment and accepts `nav` messages. | Web only (apex + apps) |
| **L3** | Drive pointer rules (§3.2), item menu, selection bar, + New menu, viewer with filmstrip, sticky pane. | Web only |
| **L4** | Photos B (§3.5): sidebar, view modes, Years; item menu and filmstrip on the existing viewer. | Web only |
| **L5** | Photos **Videos** collection: a media-type filter on the feed page. | SDK + gateway param — **no** reshape (§4.5) |
| **L6** | Remembered settings (§3.6): Drive and Photos prefs SDK functions + allowlist entries; `listingPrefs.ts` reads them, `localStorage` becomes the first-run fallback. | SDK + allowlist |
| **L7** | Photos search by name/date. | SDK walk + gateway + allowlist (§4.7); content search stays §42.1 |

### 4.1 As built — L1 (2026-11-14)

- `AppLayout` lives in **`@tenshu/app-kit/ui`**, not `web/shared/ui`: the app-kit's `styles.css`
  already `@source`s `src/ui`, so every app's Tailwind sees its classes.
- Eight apps render through it; **Maps** has no sections, so it gets a bare full-height shell (no
  one-item sidebar), as does the app template (`web/app-kit/template`). Footer sections: Drive
  Settings, Calendar Import & export, Contacts You, Fit Profile, Mia *What Mia can read*.
- **Contacts** opens a person in the pane beside the list (a sheet below 900 px).
- ⚠⚠ **The scroll container is `<main>`, not the window.** Two things depended on window scroll and
  were re-pointed with tests that fail on revert: Maps' Back-restores-position (now a ref on
  `<main>`), and Maps' virtualised `PlacesView` window, which listened for `scroll` on the window —
  it now listens with `{ capture: true }`, since a scroll event does not bubble. Any later
  window-scroll code is the same bug.
- **Not done in L1, by design** (each is a feature, not a re-arrangement): Calendar's visibility
  toggles in the sidebar (the calendars list stays its own section); Timeline's day feed beside a map
  (the day list deliberately loads no MapLibre); Maps' floating panel over a full-window map.
- Not pinned: the narrow fallback is CSS-only; jsdom cannot see the 900 px breakpoint.

### 4.2 As built — L2 (2026-11-14)

- **App half:** `useAppRoute(sections, home)` in `@tenshu/app-kit/route`. The home section is `/`,
  the rest `/<id>`; the fragment is decoded for the app. Inside the frame it only ever
  `replaceState`s — ⚠ an iframe `pushState` lands in the tab's *joint* history, behind the apex's
  back — and reports `{ type: 'tenshu.nav', path, hash, push }` to the apex origin.
- **Apex half:** `HostedAppEmbed` + `apps/appNav.ts`. The frame's `src` is fixed at mount from the
  apex URL (a new `src` would reload the app); reports from the frame's window *and* origin move the
  apex URL (push or replace); a move the app did not report (Back, the launcher) is sent in as
  `tenshu.route`. A `known` ref stops the echo both ways.
- ⚠ **A route sent while the app boots is lost**, so the app posts `tenshu.ready` once its listener
  is installed and the apex re-sends its current place.
- ⚠ **The embed is keyed by app** (`HostedAppEmbed` → `HostedAppFrame key={app.id}`): react-router
  renders sibling routes unkeyed, so Drive → Photos would otherwise reuse one instance — Photos
  opening at Drive's place with Drive's fragment, and the `src` change pushing into the frame's own
  history.
- A fragment the apex will not carry (over 8 KiB, malformed) drops only the fragment; the section
  move and its history entry still happen.
- **D6 — history follows "did the page change?"** (maintainer, 2026-11-14). A view that TAKES OVER
  the content is navigation and pushes, so browser Back closes it: a viewer (double-click / Space,
  L3/L4), a Drive folder, Fit's workout, Timeline's details, Maps' place — `open(fragment)`. A
  SELECTION shown beside the content replaces: the details pane, Contacts' person —
  `setFragment`. The detail's own Back button is `back(fallback)`: `history.back()` when this app
  pushed the entry, else a replace — ⚠ never `history.back()` from a reloaded or deep-linked detail,
  which would leave the app. Contacts' pane stays a replace even as the narrow bottom sheet
  (width-dependent history is worse); it closes on Escape, a tap outside or its back button.
- ⚠⚠ **The registry's `sections` is the path allowlist.** Each `apps/registry/apps/<id>.ts` lists its
  section ids (`''` = home); a reported path outside it is dropped, and so is an apex path the
  frame would open at. `HostedAppEmbed.test.tsx` walks folder-shaped paths and fails if any reaches
  the apex pathname (verified to fail with the check removed). An app's section ids must equal its
  registry list — a renamed section silently stops updating the URL.
- The message shapes are duplicated (`app-kit/src/route.ts`, `apex/src/apps/appNav.ts`): the apex
  does not depend on the app-kit.
- **Drive** moved from `drive/files/*` to `drive/*` (launch path `/drive`, its Home); `/drive/files`
  is still the files section, so old links land in the right place. The open folder is the
  fragment (`/drive/files#/Documents/Taxes`); opening one **pushes**, so Back walks up the folders
  you came through.
- **Fragments:** Drive the folder path; Contacts `p=<personId>`; Fit `w=<workoutId>`; Timeline
  `place|moment|trip=<id>` (only the innermost detail round-trips); Maps `place=<id>`. Photos,
  Calendar, Home and Mia carry sections only. Ids are opaque; no name or title is put in the URL.
- Checked in a real browser across origins (apex `:5183` framing Drive `:5174`): entry at
  `/files#/Docs`, reports on click, an apex `tenshu.route` applied with no echo. The full signed-in
  apex flow was not exercised locally (it needs a relay).

### 4.3 As built — L3 (2026-11-14)

- **Pointer rules** live in `FileBrowser` (§3.2): a click selects (it never opens), double-click /
  Enter open, Space opens a file, ⌘/Ctrl- and Shift-click extend, ⌘A selects all, Escape clears,
  F2 renames, ⌫ deletes. Right-click also selects the row it points at (a selected set is kept).
  The checkbox stays (keyboard and touch), shown on hover or while anything is selected.
- **Item menu:** one `MenuEntry[]` feeds both the right-click menu (`ContextMenu` in
  `@tenshu/web-tokens/ui`, Radix `react-context-menu@2.3.7` — pinned to the release on the same
  `react-menu` 2.1.24 as the dropdown) and the row's ⋯ dropdown, so the two cannot disagree.
- **Selection bar** shows for two or more items, or one picked as a set (checkbox, ⌘, Shift) — a
  plain click never flashes it. Download (files only, one save each), Move, Delete.
- **+ New** (`NewMenu`) in the sidebar: New folder / Upload files / Upload folder, into the open
  folder (outside My files: My files' root, then shows it). Drop anywhere uploads files; a dropped
  *folder* is skipped — the browser hands over no bytes for it, Upload folder is the way.
  `useUploader` carries the W7 folder-tree rules unchanged.
- **Details pane:** `FileDetails` is pane content now (no drawer, no backdrop, Escape does not
  close it); the open state is `localStorage` `tenshu-drive-pane` until L6. A folder shows a
  summary. Downloads go through one `lib/downloadFile.ts` (W7 stream-to-disk first).
- **Viewer** (`FileViewer` + `lib/usePreview.ts`): the files fragment is `view:<path>` — a path
  always starts with `/`, so the prefix can never be a folder. Opening pushes (D6), ←/→ and the
  filmstrip replace, closing is `back`. ⚠⚠ The X-5 stand-in rule holds: a rung on screen means
  Download fetches the original. `useFileOpener` (Home, shared folders) runs on the same loader.
- ⚠⚠ **Keys belong to the row that has focus.** The handler is on the `<ul>`, skips anything
  already `defaultPrevented` or inside a `[role=menu]`, and Enter/Space/F2/⌫ act on the focused
  row — never on "the selection". Radix menus are portalled but React still bubbles their keys to
  the list; acting on the selection is how Enter on a folder's *Delete* used to also open it.
  The viewer's window listener likewise skips handled keys and keys from outside itself (its
  Share/delete dialogs are portalled), and leaves Space/←/→ to a focused video.
- The selection is pruned against the listing on every render; closing the viewer refocuses the
  last file's row; a viewer whose file is gone closes; drops classify folders by
  `webkitGetAsEntry()` (not size/type) and say so; images are not draggable (a dragged `<img>`
  carries `Files`).
- ⚠ Dev-only, pre-existing: `FileThumbnail` mints its blob URL in `useMemo` and frees it in an
  effect, so under StrictMode a thumbnail that mounts with cached data (the filmstrip) shows
  broken in `vite dev`. Production runs effects once.
- Checked in a real browser over a scratch fake bridge (a throwaway Vite config aliasing
  `lib/proxy`): pane, right-click menu, viewer + URL, selection bar, + New.
- **Marquee** (2026-11-14, Photos' rules, §4.4): the listing area (`data-testid="file-listing"`, the
  list plus the space below it — the browser grows to fill `<main>`, `min-h-32`) bands from a
  ≥ 4 px drag; ⌘/Ctrl/Shift at the press adds, else replaces; a click on empty space clears.
  ⚠ **A row's blank stretch and its size/date band too** (Finder's list view); only its icon and
  name (`data-row-grab`), checkbox and ⋯ are the row's own. That press is `preventDefault`ed (no
  text selection) and focuses the row's button by hand; the pointer is captured only once the drag
  passes 4 px, so a still press stays the row's click and double-click. Hits are the rows'
  `getBoundingClientRect()` (the list is not virtualised), re-measured on every move, edge-scroll
  frame and `<main>` scroll; the press is held in the area's own coordinates, which scroll with the
  content. A band takes focus to the `<ul>` (`tabIndex=-1`) — on a row's button, ⌫ would act on
  that row — and sets `bulk`, so even a band of one shows the selection bar. Its first hit (display
  order) is the Shift anchor. Neutral band: `border-text/40 bg-text/10` (grey on both themes).
- Marquee review fixes: ⚠ a press is ended by a window-level release/cancel/blur too — capture is
  only taken at 4 px, so a release outside the area (or in another app) left a live press that a
  later HOVER turned into a band; the click that follows a band's release is swallowed (it would
  collapse the band to the row it ended on); Escape cancels a band in progress.
- Grid view (found with the marquee): tiles are opaque (the grid's 1px gaps are the line colour
  BEHIND them, so a translucent tile was tinted all over), and a tile stacks its size and date under
  the name (beside it, a 13rem tile left the name no width).

### 4.4 As built — L4, Photos option B (2026-11-14)

- **Sidebar:** Library · *Collections* Years, Albums · Favourites; Trash at the foot. `AppNavItem`
  gained `heading` (a group label drawn above an item, wide only). Places and People stay hidden
  (§42.4/§42.1); Videos came in L5 (§4.5).
- **Densities** Days / Months / Years (`lib/layout.ts` `sectionsFor`, `TILE_TARGET`,
  `HEADER_FORMAT`): the same capture-time index grouped by local day/month/year and sized
  220/160/72 px. The board's "Mosaic" (2×2 heroes) was not built — a hero breaks the virtualised
  grid's fixed row geometry. Density is a view setting: `setFragment`, no history entry.
- **Years** (`YearsView`): a card per year from `yearMarks`, its newest photo the cover (paged in
  when the card is on screen). A card opens the Library at its first photo (`at=`, a history entry).
- **Interaction** (`PhotoBrowser`, shared by the library, an album and favourites): Drive's rules —
  click selects, double-click/Enter/Space open, ⌘/Shift, Escape, ⌘A (loaded rows); the old iOS
  "Select" mode is gone, the bar shows whenever something is selected. One `MenuEntry[]` for
  right-click and the tile's ⋯: Open, Download, Favourite/Unfavourite, Add to album…, Move to
  trash (no Hide, §42.6). Keys act on the focused tile only, skip handled and menu keys (§4.3).
  Trash is a list and keeps its own Restore / Delete forever.
- **Viewer in the URL** (D6): Library `view=…&at=…&open=<feed index>`, Favourites `open=<index>`,
  Albums `a=<albumId>&open=<index>` (opening an album now pushes). A filmstrip of ±30 around the
  current photo pages in through `need`. Closing refocuses the last photo's tile.
- ⚠ **A requested scroll waits for the grid's measured box** (`PhotoGrid` `box.measured`): applied
  at the 960 px fallback it landed rows away once the real width reflowed the columns.
- Review fixes: ⚠ a viewer opened from a Radix menu re-takes focus after the menu's own
  `setTimeout(0)` focus return (else every viewer key is dead) — Drive's `FileViewer` too; a view
  whose list empties closes its viewer (`useCloseViewerWhenEmpty`: the empty state unmounts the
  viewer, so its own guard never runs); ⚠⚠ Years pages a cover in only within one page of the rows
  held (the keyset pager walks forward — an old year's cover was the whole library); a density
  switch keeps `at=` the top index (no remount, no jump, selection kept); a photo trashed from the
  viewer leaves the selection; a playing video takes focus (Space pauses).
- ⚠ The bundle's 500 kB tripwire (for a leaked Kotlin runtime) is now 700 kB: L4 brought the Radix
  menus in (459 → 525 kB, no Kotlin); a leaked runtime adds ~1.7 MB.
- Checked in a real browser over a fake bridge (240 generated photos): densities, Years + covers,
  opening a year, right-click menu (Unfavourite for a favourite), viewer + filmstrip + URL, focus
  return.

**Selection model revised** (maintainer bug report, 2026-11-14 — iCloud Photos / Finder, not a mode):

- ⚠⚠ **A selected tile is a ring and nothing else** (3 px inset `ring-primary`, drawn over the
  image). No grid-wide "selecting" state: a click changes the clicked tile only. The check circle
  is **touch-only** (`pointer-coarse:`) — a mouse has ⌘/Shift-click and the marquee.
- **Toolbar, not a bar:** "n selected" + glyph actions (Add to album, Add to favourites, Remove
  from album in an album, Move to trash — a batch, each success leaves the selection) + ✕, right of
  the view's controls in the row that was always there (`h-9`, reserved), so nothing shifts. The
  view's controls scroll when the row is short; the selection's actions and ⓘ never do.
- **Info pane** (Drive's D2): ⓘ / ⌘I, AppLayout's `pane`, `localStorage` `tenshu-photos-pane`. The
  grid reports the one selected photo by path + count through `lib/pane` (context, not props, so
  every grid view gets it); the pane's actions call the grid's own handlers. `PhotoDetails` is the
  one stored-details list, shared with the viewer's panel.
- **Marquee:** a press on empty space (not a tile, a control, or a portalled menu/dialog — DOM
  containment, not React's tree) and a ≥ 4 px drag bands; ⌘/Ctrl/Shift at the press adds, else it
  replaces; a click without a drag clears. ⚠ Hits are geometry (`marqueeHits`: cell k of a row at
  x = k × (cell + GAP) from the rows' left edge, y = row `top` … `top + cell`), never DOM
  measurement, so a band across unmounted rows still selects — loaded rows only. Edge auto-scroll
  within 40 px; touch pointers never band.
- **Band from the padding** (2026-11-15, as iCloud Photos): a press anywhere empty in the content
  pane (`AppLayout`'s `<main>`: its padding, the gap under the toolbar) bands too — the pane's own
  `pointerdown` listener hands presses *outside* the scroller to the same start, which pulls the
  press onto the grid's visible edge; presses on the scroller still come through React. (The
  scrubber this guarded is gone — §4.9.)
  ⚠ Review: a press above or below the grid has its height pinned to the grid's edge until the
  pointer is level with the scroller (`Marquee.level`) — else a jitter read as a drag out of view
  and the edge scroll ran away. A press on another scrollbar in the pane (the toolbar row's) is
  that scrollbar's (`onScrollbar`). Any other non-control text in the pane is "empty" (bands,
  clears on a plain click), as in iCloud Photos.
- **One position indicator** (same day): both the scrubber and the native scrollbar showed with
  "always show scrollbars". ⚠ Superseded by §4.9: the scrubber is gone and the browser's scrollbar
  is the one indicator.

- Review fixes: the band is clamped to the content (dragged onto the Info pane it gave the grid
  sideways overflow); a press on empty space focuses the grid (`tabIndex=-1`) so its keys work
  after a band; ⚠ focus on a selected tile is a white inner outline (the ring is painted over the
  button and hid it) and the ring has a white inner edge for photos of the same hue; rows paged in
  under a still band join (on load and on release); the band's first photo is the Shift anchor.
- Keyboard: Shift+Space on a focused photo adds it to (or takes it out of) the selection — the
  keyboard's ⌘-click, since the check circles are touch-only.

### 4.5 As built — L5, the Videos collection (2026-11-14)

- ⚠ **Not a reshape after all.** §4's table assumed a feed filter was an operation parameter (a
  positional composite, so a service bump). The feed is not a service op: `Photos.feedPage` and
  `Photos.captureTimes` are chain walks in the JS SDK (`tenshu-sdk-js` `Photos.kt`), and the bridge
  carries plain JSON. So: `FeedKind` (`all` | `videos`) on `feedWindow` — the Videos feed is the
  same walk with `category equalTo FileCategory.Video` in place of `isMedia()`, same order, same
  cursor — an optional `kind` on `photo.feedPage` / `photo.captureTimes`, and nothing in any schema.
  An unknown kind is `error_photos_bad_kind`, never "everything". ⚠ The apex and the app must
  ship in one image: an apex older than L5 drops `kind`, and would answer Videos with the whole
  library, silently.
- ⚠ The library's refresh invalidates the `['captureTimes']` **prefix** (every kind); only Videos'
  own refresh is `exact`. A trash or restore anywhere changes the Videos feed too.
- ⚠ `captureTimes` must get the **same** `kind` as the pages it maps: index i of the times is index
  i of that feed.
- Photos: a `FeedProvider kind="videos"` nested in the library's (its refresh refreshes the library
  too — a trashed video leaves both), the library's call and query key unchanged; Videos is a
  Collections section (`/videos`, registry `sections`), by month, viewer `open=<index>`.
- Pinned by `PhotosLibraryContractTest` (real runtime, headless Chrome: only videos, feed order,
  times match; unknown kind fails), the gateway pass-through tests, and App tests.

### 4.6 As built — L6, remembered view settings (2026-11-14)

- ⚠⚠ **DEVICE scope, like native** (maintainer, 2026-11-14 — supersedes §3.6's "synced"): a
  listing's display is a per-device choice, so each browser keeps its own in its own sync device's
  rows — it survives cleared site data, and does not follow you to another browser or the phone.
- Drive's sort and list/grid are **native Drive's rows** (`drive.listing.sort` `"<KEY>:<asc|desc>"`,
  `drive.listing.viewMode` `LIST|GRID`) for this browser's device id. `drive-shared-logic` has no JS
  target, so `tenshu-sdk-js` `ViewSettings.kt` re-declares them; ⚠ `WebDriveSettingsMatchNativeTest`
  (drive-shared-logic desktopTest) reads that file and fails on drift — and declares it as a task
  input, or an edit there would leave the test UP-TO-DATE. Web-only rows: `drive.web.detailsPane`,
  `photos.web.density`. Keys on existing `DeviceSetting` rows: no reshape.
- Typed per app (`driveSettings.*`, `photoSettings.*`), each in only its own app's allowlist — no
  generic `preference.*`. A value outside the vocabulary is refused before the SDK is called.
- `no_device` (no sync identity yet) or a failed write keeps `localStorage`, which stays the first
  paint and fallback; a choice made this session wins over the stored value arriving later.
- Review fixes: ⚠⚠ `get()` reports whether each row EXISTS (`sortStored`, `layoutStored`,
  `paneStored`) — a missing row reads as the default, and on a browser's first L6 load taking it
  overrode and erased the pre-L6 localStorage choice; now a missing row keeps the local value and
  writes it up once. ⚠ Every write patches the cached settings at once (the listing remounts per
  section, and a stale cache brought the old choice back; Photos' Library nav likewise). Writes
  are serialised per object (a `Mutex`: each is a read-then-write). ⚠ `run-gate.sh --quick` may not
  select `drive-shared-logic` for an SDK-only edit — the full gate runs the pin test.
- ⚠ A new `@tenshu/sdk/*` entry point (`viewSettings`) must be mocked in every `proxyGateway.*`
  test (they mock all SDK modules), and `RuntimeLaziness.test` loads the REAL bundle — rebuild it
  (`:tenshu-sdk-js:jsBrowserDistribution`) or the new export is `undefined` at import. The gateway's
  one-namespace-per-module rule makes Drive's and Photos' settings two modules.
- ⚠ Photos' fragment now always names its density (`view=months` too): with a remembered default,
  an absent `view=` means "the default", not Months.

### 4.7 As built — L7, Photos search (2026-11-14)

- **SDK:** `Photos.search(name, fromMs, toMs)` — the feed's own membership and order (`searchWindow`
  in `PhotosLibrary.kt`: media, not trashed, newest first) narrowed by the photo's NAME
  (case-insensitive, as Drive's search: the name, not the path) and/or a capture range, both ends
  inclusive; at most `MAX_SEARCH_RESULTS` (500) with `truncated`. ⚠ An empty or backwards search is
  `error_photos_bad_search`, never "everything". Not a service op: no schema, no bump (as L5).
- ⚠ **Dates are parsed in the browser** (`web/photos/src/lib/searchQuery.ts`): "May 2025" is
  language-specific and its boundaries are LOCAL midnights. Understood: `2025`, `2025-05`,
  `2025-05-03`, a month name + year in either order, in the UI language or English, long, short or an
  unambiguous ≥3-letter prefix (`Sept.`, `Mär`); an impossible date (`2025-02-30`) or anything else
  is a name search.
- **UI:** a search field atop the sidebar (AppLayout `primaryAction`); the `search` section is
  reached from it, not a nav item; the query is `q=` in the FRAGMENT (§3.4). From another section a
  search pushes, refining it replaces. ⚠ The field is never keyed by the query (a remount drops
  focus mid-typing); it follows the URL only when `q=` moves to something it did not type (Back).
  Results are a `PhotoListView` (selection, menu, viewer `open=`, Info pane).
- Review fixes: ⚠ a search still waiting out its pause is cancelled by any move (a section, the
  URL's `q=`) — its timer held the old render's `onSearch`, so it pulled the user back into search or
  wrote `q=` into another section's URL; the Info pane is on for search results; the field stops at
  200 characters (the gateway's limit). A four-digit query is always a year (by design).
- ⚠ Content search (labels, faces, semantics) stays off the web (photos-app.md §42.1).

Later, by the sweep (needs a capability first): Fit laps/Browse/Medical ID; Home writes (threat-model
amendment first); Mia writes behind a confirm (A39 amendment); offline keep-on-this-browser; address
search (Photon egress on web).
- **Emptying the field closes the search** (2026-11-15, as iCloud's): deleting the text (or
  Escape) goes back to where the search was opened from — the entry it pushed — or, after a reload
  or deep link, to the Library (`back('', 'photos')`; app-kit's `back` gained a fallback section).
  An empty search page was a dead end with no results and no way back but the sidebar.

## 5. Tests the build owes

- **L1:** each app renders no `<h1>` with its own name; its root has no `max-w-*` class; under 900 px
  the pane renders as a sheet.
- **L2:** a route test per app (path → section, fragment → folder/album); the apex rejects a `nav`
  message from a foreign origin and a path outside the app's section list; ⚠⚠ a test that walks a
  nested Drive folder and asserts the apex `location.pathname` contains none of its names.
- **L3:** click selects without opening the viewer; double-click on a folder navigates, on a file
  opens the viewer; Esc unwinds in order; the pane's open state survives a remount.
- **L4:** each view mode groups a fixed `captureTimes` fixture into the right sections (day/month/year
  boundaries in the user's zone, not UTC).

## 6. Decisions (maintainer, 2026-11-14)

- **D1 — Sidebar layout (option B) for every app**, over a full-width toolbar.
- **D2 — The details pane stays open once opened**; ⓘ / ⌘I closes it. Stored per browser until L6,
  then synced through the app's preferences.
- **D3 — Pointer rules as §3.2**: click selects (and fills the pane), double-click or Space opens the
  viewer, iCloud-style.
- **D4 — Bottom sheets map as §3.3**: item menu, selection bar, + New dropdown; the native shapes
  return under ~900 px.
- **D5 — Photos: option B** (sidebar with Collections), §42.6 limits unchanged.
- **D6 — Back follows what changed on screen** (§4.2): a view that takes over the content pushes,
  a selection beside it replaces.

### 4.8 As built — Retina tiles and the web preview ceiling (2026-11-15)

- **Why tiles were soft:** every tile asked for `LARGE` (256 px). A tile is a square drawn with
  `object-cover`, so the photograph's *short* side must cover it in **device** pixels: Months is
  160 CSS px = 320 device px on a 2× screen, and a 4:3 `LARGE` is 192 px short — stretched 1.7×
  (Days 2.3×). Years (72 px) is covered.
- **`planTile`** (`web/photos/src/lib/tileSize.ts`): `ceil(cssPx × devicePixelRatio)` against
  `LARGE`'s short side (aspect from the stored dimensions, orientation-free, 4:3 when unknown) with
  10 % slack; above it, `XLARGE`. `useDevicePixelRatio` follows a `(resolution: Ndppx)` change (a
  window moved to another screen). ⚠ `srcset` cannot do this: the bytes come over the bridge, not
  from a URL.
- **Progressive:** `LARGE` shows first; the sharp tile (`SharpTileCacheContext`, at most 3 in
  flight) replaces it when ready. A stand-in answer is no upgrade; a relay-confirmed `absent` is
  remembered for the session, `unknown` never.
- **Scaled in a worker** (`downscale.worker.ts`): decode, centre square of the short side, high
  smoothing, JPEG q0.9 at the tile's device size — ~0.4 MB of bitmap a tile instead of ~3 MB.
  Without `Worker`/`OffscreenCanvas` the rung is drawn as it came.
- ⚠⚠ **`fs.getTilePreview`, not `fs.getLargePreview`:** the JS store hydrates every byte into memory,
  so a grid that kept each ~140 KB rung would grow the heap per row scrolled. The tile read pulls,
  reads and **drops** a class that was not already here; a class this browser minted (maybe still
  owed to the relay) is served and never dropped. The scaled copy lives only in the app's bounded
  LRU. Photos' allowlist names it; Drive needs none (its tiles are 20 px icons on `LIST`).
- **The web preview ceiling** (`drive-xxlarge-thumbnail-rung.md` §9.2, parity W6 carve-out): the
  keeper's thumbnail pass runs in the browser alone, 64 MiB, every 30 min. It bounds what the
  *viewer* keeps — roughly the last 130–450 opened photographs.
- ⚠ **No setting yet, deliberately:** a size picked today would be RAM, not disk. Revisit once the
  JS store reads through instead of hydrating (`bounded-store-cache.md`, JS twin) — then the ceiling
  could match native's 1 GiB and become a Drive setting.
- **Review fixes:** ⚠⚠ a tile drops a pulled rung only if the file's content did not change during
  the read, the viewer has not read that path this session, and the rung is **not queued for the
  relay** — a browser upload lists its row before it stores its rungs, so "not here before the pull"
  alone could drop this browser's own unsent `XLARGE` (gone for good in a web-only realm). No
  emitter ⇒ never drops. The scaled square steps by 64 px (a resize by a few pixels re-pulled every
  visible rung). A relay-`absent` memo expires after 10 min (absent is often a wait for a backfill).
  A worker that fails or dies settles every waiting request and falls back to unscaled.
- ⚠ Known, not fixed: the web pass walks the whole tree on the main thread once per 30 min (in
  memory, but a 10k library may jank briefly), and each web eviction leaves a ~13-byte `.evicted`
  sentinel that hydrates and is never reclaimed.

### 4.9 As built — the browser's scrollbar, Mosaic and whole photos (2026-11-15)

**The position indicator is the browser's own scrollbar.** iCloud Photos looks native because it
draws nothing: on a Mac the browser's scrollbar *is* macOS's overlay scrollbar (thin, grey, fading),
and on Windows it is Windows'. There is no API for "look native" beyond not replacing it. So the
purple scrubber (PH-17's port) is deleted and the scroller shows its scrollbar again.
- A drag of the scrollbar to anywhere in the library loads what lands on screen: the grid asks the
  pager for the rows it draws (`need`), which is what the scrubber's seek did too.
- What the scrubber added that a scrollbar lacks is *when* you are: `DateBubble` shows the month at
  the top while scrolling and fades a second after (as the overlay scrollbar does). ⚠ It is placed by
  the scroll FRACTION, not on the thumb — a page cannot read where the browser drew the thumb — and
  is `aria-hidden`: the section headers already say the date to a screen reader. Lost: the year
  pills while dragging, and the slider's PageUp/End keys (the focused scroller's own keys remain).

**Mosaic** — native's default `FeedDensity.Mosaic`, as a switch beside Days / Months / Years with
native's two icons (Material `AutoAwesomeMosaic` when on, `GridView` when off — the icon is the
current arrangement, as on the phone). Ported from `FeedLayout.of`:
- Of every 7 full lines the 1st and 4th are features: one photograph per three columns over 2×2
  cells, two stacked beside it, heroes alternating sides. ⚠⚠ The slot is the line's first
  photograph's library position ÷ columns, never a count of lines drawn — a header or a short day
  above changes nothing, pages landing re-phase nothing, and the web picks the lines a phone would
  at the same column count (`layout.test.ts` pins this; the line-count variant passes every other
  test).
- ⚠ Only at a multiple of three columns, and only full lines. `columnsFor(…, mosaic)` rounds UP to
  the next block from 90 % of the plain count: rounded to the nearest, a desktop Days grid fell to
  three columns and a hero filled two thirds of the screen (found in the browser, not by a test).
- ⚠ Not for Years (the switch is disabled there): its tiles are a contact sheet.
- One geometry, `cellRects`, is what the grid draws AND what `marqueeHits` hits, so a band can
  never select a tile drawn elsewhere. Hits come back in index order (the band's anchor).
- Turning it on or off re-lays every line, so the photograph at the top is scrolled back to the top
  (`shownTop`), as a density switch keeps its place.

**Whole photos** — iCloud's aspect-ratio mode: each picture uncropped (`object-contain`) inside its
square at 90 %, so two landscape photographs side by side never touch. The square-crop default is
iCloud's other mode; its gap is now 2 px (was 4) to read as a seam, as there.
- ⚠ Retina sizing follows (`planTile(…, fit)`): a contained picture needs its LONG side to cover
  the tile, not its short side, so `LARGE` covers more; the sharp copy is scaled whole (the worker's
  `fit`), and the cache key carries the mode (`sharpKey`), or a crop would be served to a fit tile.
- Applies to the Library and the Videos collection; albums and favourites keep square crops.

Both switches are remembered per browser (`photos.web.mosaic`, `photos.web.fit`, DEVICE scope,
`photoSettings.setLayout`) — preference keys on existing rows, no reshape (§3.6). Off until chosen.
An apex older than the app answers without them: both read as off.

Review fixes: ⚠ `indexAtTop` read 0 (the newest photograph) at an EXACT row top — where every
programmatic scroll lands — because it asked `visibleRows` for an empty window; so after one mosaic
switch the next density change opened at the top and the bubble named the newest month. It now
searches for the last row starting at or above the scroll. A switch made before the remembered
settings load is merged into the cache (never resets the density) and survives the read landing.

Known: a video's duration badge sits at the square's corner, not the contained picture's. The
bubble also shows on programmatic scrolls (a viewer's return, a mosaic switch).

### 4.10 As built — the size slider (2026-11-15)

iCloud's `− ○ +` slider in the Library toolbar: **photos per row, six steps from 8 to 3** (default 5),
for Days and Months in both square and whole-photo modes, and the Videos collection. The range runs
small → large left to right; `−` adds a column, `+` takes one away. Remembered per browser
(`photos.web.columns`, an `IntAdapter` preference key on the existing rows, `photoSettings.setColumns`,
validated 3…8 in the gateway and the SDK).
- ⚠ The slider names a COUNT, not a pixel size, so it holds as the window changes; the densities'
  pixel targets (`TILE_TARGET`) now size only Years. A narrow window draws fewer columns than asked
  (never a tile under 96 px, never fewer than 3 — `columnsAt`).
- ⚠ With Mosaic the count rounds to whole three-column blocks (8 → 9, 4 → 3), then down a block
  while it would not fit.
- ⚠ Not for Years (disabled, as Mosaic): its contact sheet is the point of it.
- Moving it keeps the photograph at the top, as the mosaic switch does — one `arrangement` key in
  `PhotoGrid` of the DRAWN arrangement (`mosaic:layout.columns`), so a step that draws the same grid
  leaves the scroll alone, and a resize across a column count keeps its place too. (Not before the
  box is measured — the first real width is the scroll request's; jsdom cannot show that guard.)
- ⚠ Review: the label (`title`, `aria-valuetext`) names the count DRAWN (`onColumnsDrawn`), never
  the step — with Mosaic several steps draw the same blocks, and a narrow window caps them, and
  "8 per row" over nine columns is a false statement. Those steps simply change nothing.
- ⚠ Review: a sharp tile asks for at most what `XLARGE` covers (its short side cropped, its long
  side whole — 768 / 1024 px for 4:3): at 3 per row on a wide Retina screen a tile can be ~1700
  device px, and above the rung every 64 px step was a new cache key for the same pixels. Such
  tiles are a little soft; pulling `XXLARGE` for them is a later step.
- Videos follows the Library's slider (it has no slider of its own).
- Chosen with the maintainer from three renders of whole-photo mode at their window with the Info
  pane open: 5 per row (then default sizing), 4 (≈ iCloud's picture size there), 3 ("huge").


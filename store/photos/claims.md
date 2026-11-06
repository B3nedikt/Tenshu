# Tenshu Photos — the source of every claim in the store description

Design §4.4: a claim with no source is not published. `scripts/store-listing.py check` fails when a
sentence under the description's `HOW … PRIVATE` heading is not quoted here (`> ` line, verbatim) with
at least one `- source:` whose path exists. The wording follows Drive's reviewed claims
(`store/drive/claims.md`) and this app's privacy-label draft (`store/photos/privacy-label.md`);
Photos' support page (`web/apex/content/apps/photos/support/`) makes the same claims with the same
sources. ⚠ A threat-model amendment that weakens one of these has to change
the listing too (House Rule 2) — it is a public promise.

## Privacy sentences (checked)

> Your photos are encrypted on your device before they are uploaded.
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data)
- source: docs/proposals/photos-ios.md — PIO-D5: Drive runs the backup drain, Photos reads the realm

> Tenshu's servers store only encrypted data, and they don't have the keys to read it.
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> Your albums and favorites are encrypted too.
- source: docs/sync/threat-model.md — asset A5: albums and favorites are realm entities, synced inside encrypted blocks like any Syncable (House Rule 1)

> The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync.
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> Without a storage plan, nothing leaves your device.
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

> Your photos only go to Tenshu's servers, encrypted, once you turn on sync in Tenshu Control.
- source: docs/adr/0019-storage-capacity-gating.md — a subscription ⇒ relay sync
- source: docs/sync/threat-model.md — invariant I1

## Feature claims (reviewed by hand, not by the checker)

Checked against the iOS code on 2026-10-17 (`photos-ios/Sources`, `tenshu-sdk-ios-photos`).

| Claim | Evidence |
|---|---|
| Needs Tenshu Control on the same device | route 1: `photos-ios/project.yml`; the connect-to-Control wall in `photos-ios/Sources/PhotosIosApp.swift` |
| Photos does not read the photo library; Drive backs up | no `NSPhotoLibraryUsageDescription`, no PhotoKit (`photos-ios/project.yml`); `photos-ios/README.md`; `docs/proposals/photos-ios.md` PIO-D5 |
| Mosaic, day, month, year; scrubber | `photos-ios/Sources/LibraryScreen.swift`, `FeedScrubberView.swift` |
| Full screen, zoom, swipe; date, details, albums | `PhotoViewerScreen.swift`, `ZoomableImage.swift`, `AboutSheetView.swift` |
| Favorites; albums create, rename, delete, add | `PhotoViewerScreen.swift`, `CollectionsScreen.swift`, `CollectionGrids.swift`, `FeedSelection.swift` |
| Exact duplicates, keep one, rest to trash | `DuplicatesScreen.swift` (exact only) |
| Trash: restore, delete for good | `TrashScreen.swift` |
| Search by file name | `SearchScreen.swift`; `PhotoSearchViewModel.kt` |
| iOS 16, iPhone and iPad | `photos-ios/project.yml` (`deploymentTarget` 16.0, device family 1,2) |

**Left out on purpose — the iOS app does not do it today:** backing up or importing from inside
Photos (Drive does), video playback in the viewer (stills and short muted grid clips only; PIO-6),
search by description, face grouping and People, model downloads (all behind
`TENSHU_PHOTOS_ON_DEVICE_AI = NO`), sharing, exporting and editing photos (deliberately absent until
PH-G11), a Settings screen.

## Deutsch (de-DE)

The German listings' privacy sentences: Google Play's (`play/de-DE/`) and the App Store's (`de-DE/`),
one claim serving both where the sentence is the same. Each names the English claim it renders in
its `- translates:` line and cites that claim's sources. `check` holds the mechanics: every German
claim names an English claim that exists, the Play section renders the English Play listing's claims
one for one and in order (a dropped caveat fails), and a changed English claim flags its translation.
The App Store pair (`de-DE/` against `en-US/`) is held the same way: one for one and in order.
Whether the German says the same thing is review's job. The wording follows the German support page
(`web/apex/content/apps/photos/support/de.md`).

> Deine Fotos werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.
- translates: "Your photos are encrypted on your device before they are uploaded."
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data)
- source: docs/proposals/photos-ios.md — PIO-D5: Drive runs the backup drain, Photos reads the realm

> Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.
- translates: "Tenshu's servers store only encrypted data, and they don't have the keys to read it."
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> Auch deine Alben und Favoriten sind verschlüsselt.
- translates: "Your albums and favorites are encrypted too."
- source: docs/sync/threat-model.md — asset A5: albums and favorites are realm entities, synced inside encrypted blocks like any Syncable (House Rule 1)

> Einige Fakten, die der Server zum Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren.
- translates: "The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync."
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> Ohne Speicherplan verlässt nichts dein Gerät.
- translates: "Without a storage plan, nothing leaves your device."
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

> Erst wenn du in Tenshu Control die Synchronisierung einschaltest, gehen deine Fotos verschlüsselt an die Server von Tenshu.
- translates: "Your photos only go to Tenshu's servers, encrypted, once you turn on sync in Tenshu Control."
- source: docs/adr/0019-storage-capacity-gating.md — a subscription ⇒ relay sync
- source: docs/sync/threat-model.md — invariant I1

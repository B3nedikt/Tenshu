# Tenshu Contacts — the source of every claim in the store description

Design §4.4: a claim with no source is not published. `scripts/store-listing.py check` fails when a
sentence under the description's `HOW … PRIVATE` heading is not quoted here (`> ` line, verbatim) with
at least one `- source:` whose path exists. The wording follows Drive's and Control's reviewed claims
and this app's privacy-label draft (`store/contacts/privacy-label.md`); Contacts's support page
(`web/apex/content/apps/contacts/support/`) makes the same claims with the same sources. ⚠ A
threat-model amendment that weakens one of these has to change the listing too (House Rule 2) — it
is a public promise.

## Privacy sentences (checked)

> Your contacts are encrypted on your device before they are uploaded.
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data): contacts and identity keys (A24) are realm Syncables, sealed before they reach the relay
- source: AGENTS.md — House Rule 1

> Tenshu's servers store only encrypted data, and they don't have the keys to read it.
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync.
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> Tenshu has no directory, so nobody can look you up.
- source: docs/proposals/contact-invitation-handshake.md — no directory: identity only through a mutual invitation
- source: docs/sync/threat-model.md — A24/A25, §8.13

> You connect with someone only when you both accept an invitation.
- source: docs/proposals/contact-invitation-handshake.md — the handshake needs both sides; the inviter confirms in Control (control-ios/Sources/NotificationCenterView.swift)
- source: docs/sync/threat-model.md — A25: K_link never reaches the relay

> Contacts does not read the address book on your device.
- source: contacts-ios/project.yml — no Contacts framework, no contacts usage description
- source: store/contacts/privacy-label.md — "Contacts does not read the system address book"

> Without a storage plan, your contacts stay on this device.
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

## Feature claims (reviewed by hand, not by the checker)

Checked against the iOS code on 2026-10-17 (`contacts-ios/Sources` and the shared modules). Paths are
under the repo root; a bare file name is in `contacts-ios/Sources/`.

| Claim | Evidence |
|---|---|
| Needs Tenshu Control on the same device | `contacts-ios/Sources/ContactsIosApp.swift` (the Set Up Tenshu Control gate) |
| Shows contacts added on other devices and people who accepted; cannot add or edit on iOS | no add/edit/delete in `contacts-ios/Sources`; the accepted-invitation row in `ContactsListView.swift` |
| Search by name, phone, email, organization | `ContactsListView.swift` (`.searchable`); `contacts-shared-logic/.../model/Contact.kt` |
| Phones, emails, organization, notes | `ContactDetailView.swift` (read-only) |
| Verify with a six-digit code; key-changed warning | `ContactDetailView.swift` (Verify / Un-verify); `ContactsListView.swift` ("Key changed") |
| Duplicates: choose, merge | `ContactsDuplicatesView.swift` (never automatic) |
| Set your display name | `ContactsProfileView.swift` |
| iOS 16, iPhone and iPad | `contacts-ios/project.yml` (`deploymentTarget` 16.0; XcodeGen's default device family 1,2) |

**Left out on purpose — the iOS app does not do it today:** adding, editing or deleting a contact (no UI on iOS; the list's empty state says contacts arrive from the user's
other Tenshu devices); creating or accepting an invitation, an invitation link or QR code, and showing your
own identity code (`ContactsProfileView.swift`: "not available on this device yet"); vCard import/export;
reading the system address book.

## Google Play only (store/contacts/play/) — Android can import from the address book

> Contacts reads your address book only when you choose to import from it, and it never writes to it.
- source: contacts-android/src/main/AndroidManifest.xml — READ_CONTACTS only (for the whole-address-book import), no WRITE_CONTACTS, no sync adapter
- source: contacts-shared-ui/src/androidMain/kotlin/app/tenshu/contacts/ui/screens/importing/ContactImporter.android.kt — the system contact picker and vCard reads, started from the import sheet

## Deutsch (de-DE)

The German listings' privacy sentences: Google Play's (`play/de-DE/`) and the App Store's (`de-DE/`),
one claim serving both where the sentence is the same. Each names the English claim it renders in
its `- translates:` line and cites that claim's sources. `check` holds the mechanics: every German
claim names an English claim that exists, the Play section renders the English Play listing's claims
one for one and in order (a dropped caveat fails), and a changed English claim flags its translation.
The App Store pair (`de-DE/` against `en-US/`) is held the same way: one for one and in order.
Whether the German says the same thing is review's job. The wording follows the German support page
(`web/apex/content/apps/contacts/support/de.md`) where it makes the same claim.

> Deine Kontakte werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.
- translates: "Your contacts are encrypted on your device before they are uploaded."
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data): contacts and identity keys (A24) are realm Syncables, sealed before they reach the relay
- source: AGENTS.md — House Rule 1

> Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.
- translates: "Tenshu's servers store only encrypted data, and they don't have the keys to read it."
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> Einige Fakten, die der Server zum Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren.
- translates: "The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync."
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> Tenshu hat kein Verzeichnis, deshalb kann niemand dich nachschlagen.
- translates: "Tenshu has no directory, so nobody can look you up."
- source: docs/proposals/contact-invitation-handshake.md — no directory: identity only through a mutual invitation
- source: docs/sync/threat-model.md — A24/A25, §8.13

> Du verbindest dich mit jemandem nur, wenn ihr beide eine Einladung annehmt.
- translates: "You connect with someone only when you both accept an invitation."
- source: docs/proposals/contact-invitation-handshake.md — the handshake needs both sides; the inviter confirms in Control (control-ios/Sources/NotificationCenterView.swift)
- source: docs/sync/threat-model.md — A25: K_link never reaches the relay

> Contacts liest dein Adressbuch nur, wenn du einen Import daraus startest, und schreibt nie hinein.
- translates: "Contacts reads your address book only when you choose to import from it, and it never writes to it."
- source: contacts-android/src/main/AndroidManifest.xml — READ_CONTACTS only (for the whole-address-book import), no WRITE_CONTACTS, no sync adapter
- source: contacts-shared-ui/src/androidMain/kotlin/app/tenshu/contacts/ui/screens/importing/ContactImporter.android.kt — the system contact picker and vCard reads, started from the import sheet

> Ohne Speicherplan bleiben deine Kontakte auf diesem Gerät.
- translates: "Without a storage plan, your contacts stay on this device."
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

### App Store only (de-DE/)

> Contacts liest das Adressbuch auf deinem Gerät nicht.
- translates: "Contacts does not read the address book on your device."
- source: contacts-ios/project.yml — no Contacts framework, no contacts usage description
- source: store/contacts/privacy-label.md — "Contacts does not read the system address book"

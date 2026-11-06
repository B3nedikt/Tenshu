# Tenshu Drive — the source of every privacy sentence in the store description

Design §4.4: a claim with no source is not published. `scripts/store-listing.py check` fails when a
sentence under the description's `HOW … PRIVATE` heading is not quoted here (`> ` line, verbatim) with
at least one `- source:` whose path exists. The wording follows the reviewed support page
(`web/apex/content/apps/drive/support/en.md`, "How Tenshu Drive keeps your files private"), so the
two make the same promise. ⚠ A threat-model amendment that weakens one of these has to change the
listing too (House Rule 2) — it is a public promise.

> Your files are encrypted on your device before they are uploaded.
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data)
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only

> Tenshu's servers store only encrypted data, and they don't have the keys to read it.
- source: AGENTS.md — House Rule 1
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> File and folder names are encrypted too, except the name of a file you share by link (see below).
- source: docs/sync/threat-model.md — asset A6 (metadata): names are inside the encrypted payload; T-S-02: a share link stores its file's name, type and size on the relay

> The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync.
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> A share link carries its own key, in the part of the link after #k=.
- source: docs/adr/0018-file-sharing-mode-a.md — "the URL fragment is the capability"
- source: docs/file-sharing.md — §2 URL shape

> Your browser never sends that part to our server, so the server can deliver the file but cannot open it.
- source: docs/adr/0018-file-sharing-mode-a.md — the fragment is never sent in an HTTP request
- source: docs/file-sharing.md — §11 threat-model summary

> When you share a file by link, the server stores that file's name, type and size with it.
- source: docs/sync/threat-model.md — T-S-02 (share links) and the I1 carve-out for Mode-A link sharing

> Without a storage plan, nothing leaves your device.
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

> Your files only go to Tenshu's servers, encrypted, once you turn on sync in Tenshu Control.
- source: docs/adr/0019-storage-capacity-gating.md — a subscription ⇒ relay sync
- source: docs/sync/threat-model.md — invariant I1

## Deutsch (de-DE)

The German listings' privacy sentences: Google Play's (`play/de-DE/`) and the App Store's (`de-DE/`),
one claim serving both where the sentence is the same. Each names the English claim it renders in
its `- translates:` line and cites that claim's sources. `check` holds the mechanics: every German
claim names an English claim that exists, the Play section renders the English Play listing's claims
one for one and in order (a dropped caveat fails), and a changed English claim flags its translation.
The App Store pair (`de-DE/` against `en-US/`) is held the same way: one for one and in order.
Whether the German says the same thing is review's job. The wording follows the German support page
(`web/apex/content/apps/drive/support/de.md`).

> Deine Dateien werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.
- translates: "Your files are encrypted on your device before they are uploaded."
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data)
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only

> Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.
- translates: "Tenshu's servers store only encrypted data, and they don't have the keys to read it."
- source: AGENTS.md — House Rule 1
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> Auch Datei- und Ordnernamen werden verschlüsselt, außer dem Namen einer Datei, die du per Link teilst (siehe unten).
- translates: "File and folder names are encrypted too, except the name of a file you share by link (see below)."
- source: docs/sync/threat-model.md — asset A6 (metadata): names are inside the encrypted payload; T-S-02: a share link stores its file's name, type and size on the relay

> Einige Fakten, die der Server zum Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren.
- translates: "The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync."
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> Ein Freigabelink trägt seinen eigenen Schlüssel, im Teil des Links nach #k=.
- translates: "A share link carries its own key, in the part of the link after #k=."
- source: docs/adr/0018-file-sharing-mode-a.md — "the URL fragment is the capability"
- source: docs/file-sharing.md — §2 URL shape

> Dein Browser sendet diesen Teil nie an unseren Server, deshalb kann der Server die Datei ausliefern, sie aber nicht öffnen.
- translates: "Your browser never sends that part to our server, so the server can deliver the file but cannot open it."
- source: docs/adr/0018-file-sharing-mode-a.md — the fragment is never sent in an HTTP request
- source: docs/file-sharing.md — §11 threat-model summary

> Wenn du eine Datei per Link teilst, speichert der Server Name, Typ und Größe dieser Datei mit ihr.
- translates: "When you share a file by link, the server stores that file's name, type and size with it."
- source: docs/sync/threat-model.md — T-S-02 (share links) and the I1 carve-out for Mode-A link sharing

> Ohne Speicherplan verlässt nichts dein Gerät.
- translates: "Without a storage plan, nothing leaves your device."
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

> Erst wenn du in Tenshu Control die Synchronisierung einschaltest, gehen deine Dateien verschlüsselt an die Server von Tenshu.
- translates: "Your files only go to Tenshu's servers, encrypted, once you turn on sync in Tenshu Control."
- source: docs/adr/0019-storage-capacity-gating.md — a subscription ⇒ relay sync
- source: docs/sync/threat-model.md — invariant I1

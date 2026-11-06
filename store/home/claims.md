# Tenshu Home — the source of every privacy sentence in the Google Play description

Design §4.4: a claim with no source is not published. `scripts/store-listing.py check` fails when a
sentence under the Play description's `HOW … PRIVATE` heading is not quoted here (`> ` line, verbatim)
with at least one `- source:` whose path exists. Home has no App Store copy yet; these are the Android
listing's (store/home/play/). ⚠ A threat-model amendment that weakens one of these has to change the
listing too (House Rule 2).

> Home has no internet access of its own: it reaches your Hub and your other devices only through Tenshu Control.
- source: home-android/src/main/AndroidManifest.xml — INTERNET `tools:node="remove"`; `<queries>` names Control
- source: home-shared-ui/src/androidMain/kotlin/app/tenshu/home/di/HomePlatformClientModule.kt — every read and command goes over Binder to Control

> Your devices, automations and their activity are encrypted on your device before they are uploaded.
- source: docs/sync/threat-model.md — invariant I1 (confidentiality): realm data is sealed on the device

> Tenshu's servers store only encrypted data, and they don't have the keys to read it.
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1

> The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync.
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

## Deutsch (de-DE)

The German listing's privacy sentences (`play/de-DE/`). Each names the English claim it renders in
its `- translates:` line and cites that claim's sources. `check` holds the mechanics: every German
claim names an English claim that exists, the section renders the English Play listing's claims one
for one and in order (a dropped caveat fails), and a changed English claim flags its translation.
Whether the German says the same thing is review's job.

> Home hat keinen eigenen Internetzugang: Es erreicht deinen Hub und deine anderen Geräte nur über Tenshu Control.
- translates: "Home has no internet access of its own: it reaches your Hub and your other devices only through Tenshu Control."
- source: home-android/src/main/AndroidManifest.xml — INTERNET `tools:node="remove"`; `<queries>` names Control
- source: home-shared-ui/src/androidMain/kotlin/app/tenshu/home/di/HomePlatformClientModule.kt — every read and command goes over Binder to Control

> Deine Geräte, Automationen und ihre Aktivität werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.
- translates: "Your devices, automations and their activity are encrypted on your device before they are uploaded."
- source: docs/sync/threat-model.md — invariant I1 (confidentiality): realm data is sealed on the device

> Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.
- translates: "Tenshu's servers store only encrypted data, and they don't have the keys to read it."
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1

> Einige Fakten, die der Server zum Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren.
- translates: "The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync."
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

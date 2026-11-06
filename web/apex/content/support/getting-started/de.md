---
# Getting started with Tenshu, German — a translation of en.md (store-presence-and-support-pages.md §4.7).
# ⚠ Machine-translated, pending native review: flip `reviewed` once a native speaker has read it.
# Reuses the wording of the German Control and Drive pages and locales/de/landing.json; the app
# labels in bold are as those pages quote them (Control's onboarding still shows English).
lang: de
reviewed: false
title: Erste Schritte mit Tenshu
description: So beginnst du mit Tenshu: zuerst Tenshu Control einrichten, dann Apps wie Tenshu Drive und deine anderen Geräte hinzufügen. Wie deine Daten privat bleiben und wo du Hilfe bekommst.
---

## Was Tenshu ist {#what-is-tenshu}

Tenshu hält deine Daten verschlüsselt und auf deinen Geräten synchron. Deine Dateien, deine
Fotos, deine Geräte: All das lebt in einem privaten Raum, den Tenshu dein *Realm* nennt. Dein
Realm wird zwischen deinen Geräten synchronisiert, aber nur deine Geräte haben die Schlüssel.

## Richte zuerst Tenshu Control ein {#control}

Mit Tenshu Control beginnt Tenshu. Control erstellt dein Realm, den privaten Bereich, den sich
deine Tenshu-Apps teilen. Es verwahrt deine Schlüssel und verbindet deine Geräte. Andere
Tenshu-Apps wie Tenshu Drive brauchen Control, eingerichtet auf demselben Gerät.

- **Installiere Tenshu Control**, öffne es und tippe auf **Start fresh on this device** (Auf
   diesem Gerät neu beginnen). Schreib deine Wiederherstellungsphrase auf und bewahre sie
   offline an einem sicheren Ort auf. Schalte dann die Synchronisierung ein, um deinen
   Speicherplan zu bekommen. Bis dahin bleibt alles auf diesem Gerät.
   ![Willkommensbildschirm von Tenshu Control, mit den Schaltflächen „Start fresh on this device“, „I have Tenshu on another device“ und „Recover from a recovery phrase“](shot:control/01-welcome)

Die Support-Seite von Control zeigt jeden Schritt mit Screenshots, für
[iPhone und iPad](/apps/control/support/ios/de), [Android](/apps/control/support/android/de),
[Mac](/apps/control/support/macos/de) und das [Web](/apps/control/support/web/de).

## Füge Apps und deine anderen Geräte hinzu {#apps-and-devices}

- **Apps hinzufügen.** Installiere die Tenshu-Apps, die du möchtest, auf demselben Gerät, zum
  Beispiel Tenshu Drive. Drive findet Control von selbst. Die Support-Seite von Drive zeigt, wie
  du deine erste Datei hochlädst, für [iPhone und iPad](/apps/drive/support/ios/de),
  [Android](/apps/drive/support/android/de), [Mac](/apps/drive/support/macos/de) und das
  [Web](/apps/drive/support/web/de).
- **Deine anderen Geräte hinzufügen.** Installiere dort Tenshu Control und wähle **I have Tenshu
  on another device** (Ich habe Tenshu auf einem anderen Gerät). Ein Gerät zeigt einen Code an,
  das andere scannt ihn. Prüfe, dass beide Geräte denselben Bestätigungscode anzeigen.

## Wie deine Daten privat bleiben {#privacy}

- **Die Server von Tenshu können deine Daten nicht lesen.** Sie leiten nur verschlüsselte Daten
  weiter und speichern nur solche, und nur deine Geräte haben die Schlüssel. {source: docs/sync/threat-model.md | Bedrohungsmodell, Invariante I1 und Asset A5 (Nutzerdaten)}
  {source: AGENTS.md | House Rule 1: Das Relay sieht nur Chiffretext}
- **Einige Fakten, die sie zum Arbeiten brauchen, sehen sie aber:** wie viel du speicherst, wie
  viele Geräte du hast und wann sie synchronisieren. {source: docs/sync/threat-model.md | Bedrohungsmodell, Asset A6 (Metadaten) und T-T-07}
- **Deine Wiederherstellungsphrase gehört nur dir.** Niemand sonst hat eine Kopie, deshalb kann
  niemand, auch wir nicht, dein Realm ohne sie wiederherstellen. {source: docs/sync/threat-model.md | Bedrohungsmodell, Asset A9 (Wiederherstellungscode), Invariante I7 und T-RC-01}

## Hilfe bekommen {#help}

Schreib an [support@tenshu.app](mailto:support@tenshu.app). Sag uns, welche App und welches Gerät
du nutzt und was du auf dem Bildschirm siehst.

**Der Support wird dich nie nach deiner Wiederherstellungsphrase fragen.** Niemand bei Tenshu
braucht sie, niemals. Wer sie hat, kann alles in deinem Konto öffnen.

---
# Tenshu Drive support, German — a translation of en.md (store-presence-and-support-pages.md §4).
# ⚠ Machine-translated, pending native review: flip `reviewed` once a native speaker has read it.
# ⚖️ The privacy section follows the English one, which is under counsel review: any change counsel
# makes there must be carried over here.
# App labels: as the German apps show them (drive-ios / control-ios Localizable.xcstrings,
# drive-shared-ui + control-shared values-de, the web app's locales/de). Where the app still shows
# English (Control's iOS onboarding, a few iOS Drive messages, web Drive), the English label stays
# in bold with a German gloss in parentheses on first use.
app: drive
name: Tenshu Drive
lang: de
reviewed: false
title: Tenshu Drive – Support
description: Hilfe zu Tenshu Drive auf {platform}: wie du loslegst, wie deine Dateien privat bleiben, Antworten auf häufige Probleme und wie du den Support erreichst.
platforms: ios, android, macos, web
defaultPlatform: web
---

## Erste Schritte {#get-started}

Tenshu Drive hält deine Dateien verschlüsselt und auf all deinen Geräten synchron. Drive arbeitet
mit Tenshu Control zusammen, das deine Schlüssel verwahrt und deine Geräte verbindet. Richte
zuerst Control ein.

::: ios
1. **Installiere Tenshu Control** aus dem App Store und öffne es.
   ![Willkommensbildschirm von Tenshu Control auf dem iPhone, mit den Schaltflächen „Start fresh on this device“, „I have Tenshu on another device“ und „Recover from a recovery phrase“](shot:control/01-welcome)
2. **Richte Control ein.** Tippe auf **Start fresh on this device** (Auf diesem Gerät neu
   beginnen) und dann auf **Continue** (Weiter). Schreib deine Wiederherstellungsphrase auf und
   bewahre sie an einem sicheren Ort auf. Tippe dann auf **Turn on sync** (Sync einschalten) und
   gib deine E-Mail-Adresse ein, um einen Speicherplan zu erhalten. Ohne Plan bleiben deine
   Dateien auf diesem iPhone.
   ![Tenshu Control mit „You're set up“ (Alles eingerichtet) und einem Link „Turn on sync“ über den 24 Wörtern der Wiederherstellungsphrase](shot:control/03-recovery-phrase)
3. **Installiere Tenshu Drive** aus dem App Store und öffne es. Drive findet Control von selbst.
   Wenn es dich bittet, Tenshu Control einzurichten, schließe zuerst Schritt 2 ab.
   ![Die leere Dateiliste von Tenshu Drive mit „Noch keine Dateien“](shot:07-empty)
4. **Lade eine Datei hoch.** Tippe auf **+** und dann auf **Datei auswählen** oder **Foto
   auswählen**.
   ![Das Hinzufügen-Menü von Tenshu Drive, mit „Neuer Ordner“, „Datei auswählen“ und „Foto auswählen“](shot:05-add)
5. **Sieh sie auf einem anderen Gerät.** Richte Tenshu Control auf deinem anderen Gerät ein und
   wähle **I have Tenshu on another device** (Ich habe Tenshu auf einem anderen Gerät). Öffne auf
   diesem iPhone Control und gehe zu **Einstellungen** → **Gerät hinzufügen**. Prüfe, dass beide
   Geräte denselben Code anzeigen. Öffne Drive auf dem anderen Gerät. Deine Datei erscheint
   innerhalb weniger Minuten.
   ![Bildschirm „Gerät hinzufügen“ von Tenshu Control auf „Show a code“ (Code zeigen), mit einem QR-Code und einem Kopplungscode](shot:control/09-add-device)
:::

::: android
1. **Installiere Tenshu Control** aus Google Play und öffne es.
   ![Willkommensbildschirm von Tenshu Control auf Android, mit den Schaltflächen „Auf diesem Gerät neu beginnen“, „Ich habe Tenshu auf einem anderen Gerät“ und „Mit Wiederherstellungsphrase wiederherstellen“](shot:control/01-welcome)
2. **Richte Control ein.** Tippe auf **Auf diesem Gerät neu beginnen**. Öffne dann **Dienste**
   und wähle einen Speicherplan, damit deine Dateien deine anderen Geräte erreichen können. Ohne
   Plan bleiben deine Dateien auf diesem Telefon. Schreib deine Wiederherstellungsphrase unter
   **Einstellungen** → **Kontowiederherstellung einrichten** auf und bewahre sie an einem sicheren
   Ort auf.
   ![Bildschirm „Plan wählen“ von Tenshu Control mit dem Plan Free](shot:control/10-services)
3. **Installiere Tenshu Drive** aus Google Play und öffne es. Drive verbindet sich von selbst mit
   Control. Wenn es **Tenshu Control einrichten** anzeigt, schließe zuerst Schritt 2 ab.
   ![Die leere Dateiliste von Tenshu Drive mit „Noch keine Dateien hier“](shot:07-empty)
4. **Lade eine Datei hoch.** Tippe auf **+** und dann unter **Hochladen** auf **Datei**.
   ![Das Hinzufügen-Menü von Tenshu Drive, mit „Ordner“ und „Datei“ unter „Erstellen“ sowie „Datei“, „Ordner hochladen“ und „Bilder“ unter „Hochladen“](shot:05-add)
5. **Sieh sie auf einem anderen Gerät.** Richte Tenshu Control auf deinem anderen Gerät ein und
   wähle **Ich habe Tenshu auf einem anderen Gerät**. Öffne auf diesem Telefon Control und gehe zu
   **Einstellungen** → **Synchronisierung & Geräte** → **+**. Prüfe, dass beide Geräte denselben Code
   anzeigen. Öffne Drive auf dem anderen Gerät. Deine Datei erscheint innerhalb weniger Minuten.
   ![Bildschirm „Gerät hinzufügen“ von Tenshu Control auf „Code zeigen“, mit einem QR-Code und einem manuellen Code](shot:control/09-add-device)
:::

::: macos
1. **Installiere Tenshu Control** auf deinem Mac und öffne es. Control lebt in der Menüleiste,
   oben auf deinem Bildschirm.
   ![Willkommensfenster von Tenshu Control auf einem Mac, mit den Schaltflächen „Auf diesem Gerät neu beginnen“, „Ich habe Tenshu auf einem anderen Gerät“ und „Mit Wiederherstellungsphrase wiederherstellen“](shot:control/01-welcome)
2. **Richte Control ein.** Klicke auf **Auf diesem Gerät neu beginnen**. Öffne dann **Dienste**
   und wähle einen Speicherplan, damit deine Dateien deine anderen Geräte erreichen können. Ohne
   Plan bleiben deine Dateien auf diesem Mac. Schreib deine Wiederherstellungsphrase auf, wenn
   Control sie anzeigt, und bewahre sie an einem sicheren Ort auf.
   ![Bildschirm „Dienste“ von Tenshu Control mit den Plänen Free, Basic und Premium](shot:control/10-services)
3. **Installiere Tenshu Drive** auf deinem Mac und öffne es. Drive verbindet sich von selbst mit
   Control. Wenn es **Tenshu Control einrichten** anzeigt, schließe zuerst Schritt 2 ab.
   ![Die leere Dateiliste von Tenshu Drive auf einem Mac mit „Noch keine Dateien hier“](shot:07-empty)
4. **Lade eine Datei hoch.** Klicke auf **Hochladen** und dann auf **Datei**.
   ![Das Hochladen-Menü von Tenshu Drive, mit „Datei“, „Ordner hochladen“ und „Bilder“](shot:05-add)
5. **Sieh sie auf einem anderen Gerät.** Richte Tenshu Control auf deinem anderen Gerät ein und
   wähle **Ich habe Tenshu auf einem anderen Gerät**. Öffne auf diesem Mac Control und gehe zu
   **Realm-Geräte** → **Neues Gerät hinzufügen**. Prüfe, dass beide Geräte denselben Code
   anzeigen. Öffne Drive auf dem anderen Gerät. Deine Datei erscheint innerhalb weniger Minuten.
   ![Fenster „Gerät hinzufügen“ von Tenshu Control, mit „Code scannen“ und „Code zeigen“](shot:control/09-add-device)
:::

::: web
1. **Öffne [tenshu.app/start](/start)** in deinem Browser. Im Browser ist Tenshu Control Teil der
   Website, es gibt also nichts zu installieren.
   ![Die Seite „Willkommen bei Tenshu“, mit „Kostenlos registrieren“, „Ich habe Tenshu schon“ und „Erst mal lokal ausprobieren“](shot:control/01-welcome)
2. **Richte Tenshu ein.** Wähle **Kostenlos registrieren**. Sichere deine Wiederherstellungsphrase:
   Schreib die 24 Wörter auf Papier oder bewahre sie in einem Passwortmanager auf. Mach keinen
   Screenshot.
   ![Die Seite „Speichere deine Wiederherstellungsphrase“ mit 24 nummerierten Wörtern](shot:control/03-recovery-phrase)
3. **Öffne Drive.** Gehe zu [tenshu.app/drive](/drive).
   ![Tenshu Drive im Browser, mit einer leeren Dateiliste und der Schaltfläche „Upload“](shot:07-empty)
4. **Lade eine Datei hoch.** Klicke auf **Upload** (Hochladen) und wähle eine Datei.
   ![Tenshu Drive im Browser, während eine Datei hochgeladen wird](shot:08-uploading)
5. **Sieh sie auf einem anderen Gerät.** Richte Tenshu Control auf deinem Telefon oder Computer
   ein und wähle **Ich habe Tenshu auf einem anderen Gerät**. Öffne im Browser **Geräte** →
   **Neues Gerät koppeln**. Prüfe, dass beide denselben Code anzeigen. Öffne Drive auf dem anderen
   Gerät. Deine Datei erscheint innerhalb weniger Minuten.
   ![Die Seite „Geräte“ im Browser beim Hinzufügen eines neuen Geräts](shot:control/09-add-device)
:::

## Wie Tenshu Drive deine Dateien privat hält {#privacy}

- **Deine Dateien werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.** Die
  Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu
  lesen. Nur deine eigenen Geräte können deine Dateien lesen. {source: docs/sync/threat-model.md | Bedrohungsmodell, Invariante I1 und Asset A5 (Nutzerdaten)}
  {source: AGENTS.md | House Rule 1: Das Relay sieht nur Chiffretext}
- **Auch Datei- und Ordnernamen werden verschlüsselt.** Einige Fakten, die der Server zum
  Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele Geräte du hast und
  wann sie synchronisieren. Wie deine Dateien heißen oder was in ihnen steht, sieht er nicht. Eine
  Ausnahme: Wenn du eine Datei per Link teilst, speichert der Server Name, Typ und Größe dieser
  Datei mit ihr, weil die Person, mit der du sie teilst, sie ohnehin sieht. {source: docs/sync/threat-model.md | Bedrohungsmodell, Asset A6 (Metadaten), T-T-07 und T-S-02 (Freigabelinks)}
- **Ein Freigabelink trägt seinen eigenen Schlüssel.** Der Schlüssel ist der Teil des Links nach
  `#k=`. Dein Browser sendet diesen Teil nie an unseren Server, deshalb kann der Server die Datei
  ausliefern, sie aber nicht öffnen. Jeder mit dem Link kann die Datei ansehen, bis du ihn
  widerrufst. Wer sie bereits geöffnet hat, behält seine Kopie. {source: docs/adr/0018-file-sharing-mode-a.md | ADR 0018, Dateifreigabe: Das URL-Fragment ist die Berechtigung}
  {source: docs/file-sharing.md | Dateifreigabe: was das Relay sieht und was Widerrufen bewirkt}
- **Wenn du einen Freigabelink löschst, löscht der Server, was er dazu speichert.** Öffne
  **Freigegeben**, dann **Meine Freigaben**, und tippe beim Link auf **Widerrufen**. Der Link
  funktioniert sofort nicht mehr, und die Tenshu-Server löschen Name, Typ und Größe der Datei sowie
  ihre verschlüsselte Kopie 30 Tage später. Ein abgelaufener Link wird genauso gelöscht. {source: docs/file-sharing.md | Dateifreigabe: was das Relay sieht und was Widerrufen bewirkt}
- **Ohne Speicherplan verlässt nichts dein Gerät.** Drive funktioniert auf einem einzelnen Gerät
  ohne Konto. Deine Dateien gehen erst an die Server von Tenshu, verschlüsselt, wenn du die
  Synchronisierung einschaltest.
  {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, Speicherkapazität: ohne Abo kein Relay-Verkehr}

## Häufige Probleme {#problems}

### Drive zeigt „Tenshu Control einrichten“

Drive bewahrt deine Dateien in Tenshu Control auf, deshalb muss Control zuerst auf demselben Gerät
installiert und eingerichtet sein. Installiere Tenshu Control, öffne es und wähle **Auf diesem
Gerät neu beginnen** oder, wenn du Tenshu schon nutzt, **Ich habe Tenshu auf einem anderen
Gerät** (auf dem iPhone: **Start fresh on this device** bzw. **I have Tenshu on another device**).
Öffne dann Drive erneut.

::: ios
Sobald Control eingerichtet ist, muss es nicht geöffnet bleiben. Drive funktioniert eigenständig.
:::

::: web
Im Browser richtest du Tenshu auf [tenshu.app/start](/start) ein. Es gibt nichts zu installieren.
:::

::: ios
### Drive zeigt „Tenshu Control ist nicht erreichbar“

Drive zeigt die Dateien, die es schon hat, kann aber keine neuen laden. Tippe auf **Einrichten**,
folge den Schritten in Tenshu Control und kehre dann zu Drive zurück.
:::

::: ios
### Meine Dateien bleiben auf diesem iPhone

Öffne Tenshu Control und gehe zu **Einstellungen**. Steht dort **Local only** (Nur lokal), hat
dieses Realm noch keinen Speicherplan, und nichts verlässt dieses iPhone. Öffne **Speicher** und
wähle einen Plan. Der kostenlose Plan braucht eine bestätigte E-Mail-Adresse. Ein Einlösecode
funktioniert auch ohne.
:::

::: android macos
### Drive zeigt „Keine Synchronisierung – dieses Gerät hat keinen Speicherplan“

Deine Dateien sind auf diesem Gerät sicher, können deine anderen Geräte aber noch nicht erreichen.
Öffne Tenshu Control und wähle unter **Dienste** einen Speicherplan. Der kostenlose Plan braucht
eine bestätigte E-Mail-Adresse. Ein Einlösecode funktioniert auch ohne.
:::

::: web
### Meine Dateien bleiben in diesem Browser

Ohne Speicherplan bewahrt Tenshu alles in diesem Browser auf. Öffne **Dienste** in Tenshu und
wähle einen Plan. Der kostenlose Plan braucht eine bestätigte E-Mail-Adresse.
:::

### Mein anderes Gerät zeigt meine Dateien nicht

Dateien erreichen deine anderen Geräte erst, nachdem das Gerät, auf dem sie liegen, sie
hochgeladen hat. Prüfe der Reihe nach:

1. Beide Geräte sind im selben Realm. In Tenshu Control sollte die Geräteliste beide zeigen.
2. Das Gerät, auf dem die Dateien liegen, hat einen Speicherplan (siehe oben).
3. Beide Geräte sind online. Öffne Drive auf beiden und gib ihm ein paar Minuten Zeit, bei großen
   Dateien länger.

::: ios
### Die Foto-Sicherung sichert nichts

Drive braucht Zugriff auf deine Fotomediathek. Wenn es **Drive cannot see your photos** (Drive
kann deine Fotos nicht sehen) anzeigt, tippe auf **Einstellungen öffnen** und erlaube den
Zugriff. Wenn du nur ausgewählte Fotos freigegeben hast, sichert Drive nur diese. iOS lässt Drive
nur ab und zu im Hintergrund sichern. Öffne Drive, um aufzuholen, oder tippe unter
**Fotosicherung** auf **Jetzt sichern**.
:::

::: android
### Die Foto-Sicherung startet nicht

Die Sicherung braucht Speicher. Wenn Drive **Sicherung braucht Speicher** anzeigt, füge in Tenshu
Control Speicher hinzu. Wenn der Sicherungsstatus **Pausiert** anzeigt, folgt der Grund: **kein
Platz mehr**, **mobile Daten**, **lädt nicht**, **keine Verbindung** oder **du hast die Sicherung
gestoppt**. Wenn du sie selbst gestoppt hast, tippe auf **Fortsetzen**. Einen einzelnen Ordner
einzuschalten hebt die Pause nicht auf.
:::

::: android macos
### Drive zeigt „Speicher voll“

Deine Dateien bleiben auf diesem Gerät und nichts wird gelöscht, aber neue Änderungen können nicht
synchronisiert werden. Lösche Dateien, die du nicht mehr brauchst, oder wähle in Tenshu Control
unter **Dienste** einen größeren Plan.

### Drive zeigt, dass dieses Gerät nicht akzeptiert wird

Öffne Tenshu Control und füge dieses Gerät erneut zu deinem Realm hinzu. Verwende nicht zuerst
**Dieses Gerät vergessen**: Alles, was noch auf den Upload wartet, ginge sonst verloren.
:::

::: web
### Ich habe meine Browserdaten gelöscht

Tenshu bewahrt die Schlüssel dieses Browsers in seinen Websitedaten auf. Wenn du sie löschst,
gehört dieser Browser nicht mehr zu deinem Realm. Dateien, die du synchronisiert hast, sind
weiterhin auf deinen anderen Geräten. Wähle **Ich habe Tenshu schon** auf
[tenshu.app/start](/start), um diesen Browser erneut hinzuzufügen.
:::

## Voraussetzungen {#requirements}

::: ios
- iOS 16 oder neuer, auf iPhone oder iPad.
- Tenshu Control, installiert und eingerichtet auf demselben Gerät.
:::

::: android
- Android 7.0 oder neuer.
- Tenshu Control, installiert und eingerichtet auf demselben Gerät.
:::

::: macos
- Ein Mac mit macOS 13 Ventura oder neuer.
- Tenshu Control, installiert und eingerichtet auf demselben Mac.
:::

::: web
- Ein aktueller Webbrowser.
- Tenshu bewahrt seine Schlüssel in den Websitedaten deines Browsers auf, lösche sie also nicht
  für tenshu.app.
:::

- Zum Synchronisieren zwischen Geräten: ein Speicherplan. Der kostenlose Plan bietet 5 GB.

## Kontakt {#contact}

Schreib an [support@tenshu.app](mailto:support@tenshu.app). Sag uns, welche App und welches Gerät
du nutzt und was du auf dem Bildschirm siehst.

**Der Support wird dich nie nach deiner Wiederherstellungsphrase fragen.** Niemand bei Tenshu
braucht sie, niemals. Wer sie hat, kann alles in deinem Konto öffnen.

---
# Tenshu Control support, German — a translation of en.md (store-presence-and-support-pages.md §4).
# ⚠ Machine-translated, pending native review: flip `reviewed` once a native speaker has read it.
# ⚖️ The privacy section follows the English one, which is under counsel review: any change counsel
# makes there must be carried over here.
# App labels: as the German app shows them (control-shared values-de, the web app's locales/de).
# Where the app still shows English (Control's iOS onboarding, the macOS menu bar), the English
# label stays in bold with a German gloss in parentheses on first use.
app: control
name: Tenshu Control
lang: de
reviewed: false
title: Tenshu Control – Support
description: Hilfe zu Tenshu Control auf {platform}: Tenshu einrichten, deine Geräte hinzufügen, deine Wiederherstellungsphrase sicher aufbewahren und häufige Probleme lösen.
platforms: ios, android, macos, web
defaultPlatform: web
---

## Erste Schritte {#get-started}

Mit Tenshu Control beginnt Tenshu. Control erstellt dein Realm, den privaten Bereich, den sich
deine Tenshu-Apps teilen. Es verwahrt deine Schlüssel und verbindet deine Geräte. Andere
Tenshu-Apps wie Tenshu Drive brauchen Control, eingerichtet auf demselben Gerät.

::: ios
1. **Installiere Tenshu Control** aus dem App Store und öffne es.
   ![Willkommensbildschirm von Tenshu Control auf dem iPhone, mit den Schaltflächen „Start fresh on this device“, „I have Tenshu on another device“ und „Recover from a recovery phrase“](shot:01-welcome)
2. **Erstelle dein Realm.** Tippe auf **Start fresh on this device** (Auf diesem Gerät neu
   beginnen) und dann auf **Continue** (Weiter). Tenshu schützt deine Schlüssel mit dem sicheren
   Speicher dieses iPhones, du musst dir also kein Passwort merken.
   ![Bildschirm „Protected by your device“ (Durch dein Gerät geschützt) von Tenshu Control, mit der Schaltfläche „Continue“](shot:02-protected)
3. **Sichere deine Wiederherstellungsphrase.** Control zeigt jetzt **You're set up** (Alles
   eingerichtet) und deine Wiederherstellungsphrase. Schreib die 24 Wörter auf und bewahre sie
   offline an einem sicheren Ort auf.
   ![Tenshu Control mit „You're set up“, einem Link „Turn on sync“ über den 24 Wörtern der Wiederherstellungsphrase und der Schaltfläche „I've saved it“](shot:03-recovery-phrase)
4. **Schalte die Synchronisierung ein.** Tippe auf demselben Bildschirm auf **Turn on sync**
   (Sync einschalten) und gib deine E-Mail-Adresse ein, um deinen Speicherplan zu erhalten; ein
   neues Konto startet mit dem kostenlosen Plan. Bis dahin bleibt alles auf diesem iPhone. Tippe
   dann auf **I've saved it** (Ich habe sie gesichert). Du kannst die Synchronisierung auch später
   einschalten, unter **Einstellungen** → **Speicher**.
   ![Bildschirm „Sign in or create your free Tenshu account“ (Melde dich an oder erstelle dein kostenloses Tenshu-Konto) von Tenshu Control, mit einem E-Mail-Feld](shot:08-sign-in)
5. **Füge dein anderes Gerät hinzu.** Installiere dort Tenshu Control und wähle **I have Tenshu
   on another device** (Ich habe Tenshu auf einem anderen Gerät). Gehe auf diesem iPhone zu
   **Einstellungen** → **Gerät hinzufügen**. Ein Gerät zeigt einen Code an, das andere scannt ihn.
   Prüfe, dass beide denselben Bestätigungscode anzeigen, und bestätige dann.
   ![Bildschirm „Gerät hinzufügen“ von Tenshu Control auf „Show a code“ (Code zeigen), mit einem QR-Code und einem Kopplungscode](shot:09-add-device)
:::

::: android
1. **Installiere Tenshu Control** aus Google Play und öffne es.
   ![Willkommensbildschirm von Tenshu Control auf Android, mit den Schaltflächen „Auf diesem Gerät neu beginnen“, „Ich habe Tenshu auf einem anderen Gerät“ und „Mit Wiederherstellungsphrase wiederherstellen“](shot:01-welcome)
2. **Erstelle dein Realm.** Tippe auf **Auf diesem Gerät neu beginnen** und dann auf **Weiter**.
   Tenshu schützt deine Schlüssel mit dem sicheren Speicher dieses Telefons, du musst dir also
   kein Passwort merken.
   ![Bildschirm „Durch dein Gerät geschützt“ von Tenshu Control, mit der Schaltfläche „Weiter“](shot:02-protected)
3. **Sichere deine Wiederherstellungsphrase.** Gehe zu **Einstellungen** →
   **Kontowiederherstellung einrichten** und schreib die 24 Wörter auf. Bewahre sie offline an einem sicheren Ort auf.
   ![Bildschirm „Wiederherstellungsphrase“ von Tenshu Control](shot:03-recovery-phrase)
4. **Schalte die Synchronisierung ein.** Öffne **Dienste**, tippe auf **Anmelden oder Konto
   erstellen** und wähle einen Speicherplan. Bis dahin bleibt alles auf diesem Telefon.
   ![Bildschirm „Plan wählen“ von Tenshu Control mit dem Plan Free](shot:10-services)
5. **Füge dein anderes Gerät hinzu.** Installiere dort Tenshu Control und wähle **Ich habe Tenshu
   auf einem anderen Gerät**. Gehe auf diesem Telefon zu **Einstellungen** →
   **Synchronisierung & Geräte** und tippe auf **+**. Ein Gerät zeigt einen Code an, das andere
   scannt ihn. Prüfe dann, dass beide Geräte denselben Bestätigungscode anzeigen.
   ![Bildschirm „Gerät hinzufügen“ von Tenshu Control auf „Code zeigen“, mit einem QR-Code und einem manuellen Code](shot:09-add-device)
:::

::: macos
1. **Installiere Tenshu Control** auf deinem Mac und öffne es. Control lebt in der Menüleiste,
   oben auf deinem Bildschirm. Zum Beenden wähle **Quit** (Beenden) in seinem Menü.
   ![Willkommensfenster von Tenshu Control auf einem Mac, mit den Schaltflächen „Auf diesem Gerät neu beginnen“, „Ich habe Tenshu auf einem anderen Gerät“ und „Mit Wiederherstellungsphrase wiederherstellen“](shot:01-welcome)
2. **Erstelle dein Realm.** Klicke auf **Auf diesem Gerät neu beginnen** und dann auf **Weiter**.
   ![Fenster „Durch dein Gerät geschützt“ von Tenshu Control, mit der Schaltfläche „Weiter“](shot:02-protected)
3. **Sichere deine Wiederherstellungsphrase.** Öffne **Wiederherstellungsphrase** und schreib die
   24 Wörter auf. Bewahre sie offline an einem sicheren Ort auf.
   ![Fenster „Wiederherstellungsphrase“ von Tenshu Control](shot:03-recovery-phrase)
4. **Schalte die Synchronisierung ein.** Öffne **Dienste** und wähle einen Speicherplan. Bis
   dahin bleibt alles auf diesem Mac.
   ![Fenster „Dienste“ von Tenshu Control mit den Plänen Free, Basic und Premium](shot:10-services)
5. **Füge dein anderes Gerät hinzu.** Installiere dort Tenshu Control und wähle **Ich habe Tenshu
   auf einem anderen Gerät**. Gehe auf diesem Mac zu **Realm-Geräte** → **Neues Gerät
   hinzufügen**. Prüfe, dass beide Geräte denselben Bestätigungscode anzeigen.
   ![Fenster „Gerät hinzufügen“ von Tenshu Control mit einem Kopplungscode](shot:09-add-device)
:::

::: web
1. **Öffne [tenshu.app/start](/start)** in deinem Browser. Im Browser ist Tenshu Control Teil der
   Website, es gibt also nichts zu installieren.
   ![Die Seite „Willkommen bei Tenshu“, mit „Kostenlos registrieren“, „Ich habe Tenshu schon“ und „Erst mal lokal ausprobieren“](shot:01-welcome)
2. **Erstelle dein Realm.** Wähle **Kostenlos registrieren**. **Erst mal lokal ausprobieren**
   geht auch, dann liegen deine Daten aber nur in diesem Browser.
   ![Die Registrierungsseite](shot:11-sign-up)
3. **Sichere deine Wiederherstellungsphrase.** Schreib die 24 Wörter auf Papier oder bewahre sie
   in einem Passwortmanager auf. Mach keinen Screenshot: Deine Foto-Sicherung ist unter Umständen
   für andere sichtbar. Tenshu prüft danach, ob du sie gesichert hast.
   ![Die Seite „Speichere deine Wiederherstellungsphrase“ mit 24 nummerierten Wörtern](shot:03-recovery-phrase)
4. **Schalte die Synchronisierung ein.** Wenn du noch keinen Plan gewählt hast, öffne **Dienste**
   und wähle einen.
   ![Die Seite „Dienste“ mit den Plänen Free, Basic und Premium](shot:10-services)
5. **Füge dein anderes Gerät hinzu.** Installiere dort Tenshu Control und wähle **Ich habe Tenshu
   auf einem anderen Gerät**. Öffne im Browser **Geräte** → **Neues Gerät koppeln**. Der
   Kopplungscode läuft nach 5 Minuten ab. Prüfe, dass beide Geräte denselben Bestätigungscode
   anzeigen.
   ![Der Dialog „Neues Gerät koppeln“ mit einem Kopplungscode](shot:09-add-device)
:::

## Wie Tenshu Control deine Daten privat hält {#privacy}

- **Deine Schlüssel entstehen auf deinen Geräten und verlassen sie nie unverschlüsselt.** Wenn
  du ein Gerät hinzufügst, verschlüsselt dein vorhandenes Gerät die Schlüssel für das neue, bevor
  sie unseren Server passieren. Der Server sieht immer nur Daten, die er nicht entschlüsseln kann. {source: docs/sync/threat-model.md | Bedrohungsmodell, Assets A1 und A4 (Schlüssel)}
  {source: docs/whitepaper.adoc | Whitepaper, ein Gerät hinzufügen}
- **Die Server von Tenshu können deine Daten nicht lesen.** Sie leiten nur verschlüsselte Daten
  weiter und speichern nur solche. Einige Fakten, die sie zum Arbeiten brauchen, sehen sie aber:
  wie viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren. {source: docs/sync/threat-model.md | Bedrohungsmodell, Invariante I1, Asset A6 (Metadaten) und T-T-07}
  {source: AGENTS.md | House Rule 1: Das Relay sieht nur Chiffretext}
- **Deine Wiederherstellungsphrase gehört nur dir.** Deine Schlüssel werden aus ihren 24 Wörtern
  abgeleitet. Niemand sonst hat eine Kopie, deshalb kann niemand, auch wir nicht, dein Realm ohne
  sie wiederherstellen. Wer sie hat, kann alles öffnen, also bewahre sie offline auf. {source: docs/sync/threat-model.md | Bedrohungsmodell, Asset A9 (Wiederherstellungscode), Invariante I7 und T-RC-01}
- **Du kannst Tenshu ohne Konto nutzen.** Auf einem einzelnen Gerät funktioniert Tenshu ohne
  Konto und sendet nichts an unsere Server. Zum Synchronisieren wählst du einen Speicherplan: Der
  kostenlose Plan braucht eine E-Mail-Adresse, ein Einlösecode braucht keine. {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, Speicherkapazität: ohne Abo kein Relay-Verkehr}
  {source: docs/proposals/commerce-accounts-and-sessions.md | Commerce-Konten: Einen Code einzulösen braucht keine E-Mail-Adresse}

## Häufige Probleme {#problems}

### Ich habe mein Telefon oder alle meine Geräte verloren

Wenn du noch ein Gerät in deinem Realm hast, füge damit dein neues Gerät hinzu. Wenn du keins
mehr hast, installiere Tenshu Control auf einem neuen Gerät und wähle **Mit
Wiederherstellungsphrase wiederherstellen** (auf dem iPhone: **Recover from a recovery phrase**).
Gib deine 24 Wörter ein. Das wiederhergestellte Gerät wird dein einziges Gerät; füge die anderen
von dort aus erneut hinzu.

Ohne die Wiederherstellungsphrase und ohne eines deiner Geräte kann niemand deine Daten
wiederherstellen, auch wir nicht.

### Tenshu zeigt „Dieses Gerät hat deine Wiederherstellungsphrase nicht“

Das ist auf einem Gerät, das du durch Koppeln hinzugefügt hast, so vorgesehen. Nur das Gerät, das
dein Realm erstellt hat, oder eines, das mit der Phrase eingerichtet wurde, behält eine Kopie.
Öffne **Wiederherstellungsphrase** auf diesem Gerät.

### Die Bestätigungscodes stimmen beim Hinzufügen eines Geräts nicht überein

Halte an und bestätige nicht. Brich auf beiden Geräten ab, stell sicher, dass auf beiden die
neueste Version von Tenshu Control läuft, und beginne von vorn.

### Ich habe ein Gerät hinzugefügt, aber nichts wird synchronisiert

Wenn Tenshu Control **Noch wird nichts synchronisiert** anzeigt (auf dem iPhone: **Nothing will
sync yet**), hat dein Realm keinen Speicherplan, Änderungen bleiben also auf dem jeweiligen
Gerät. Es geht nichts verloren. Wähle einen Plan unter **Dienste**, auf dem iPhone unter
**Speicher**.

::: ios
In den **Einstellungen** wird ein Realm ohne Plan als **Local only** (Nur lokal) angezeigt.
:::

::: ios android macos
### Ich habe meine Passphrase vergessen

Wenn du eine Passphrase hinzugefügt hast, lässt sie sich nicht zurücksetzen. Deine
Wiederherstellungsphrase stellt dein Realm wieder her: Wähle auf diesem Gerät **Mit
Wiederherstellungsphrase wiederherstellen** (auf dem iPhone: **Recover from a recovery phrase**).
:::

::: web
### Ich habe meine Browserdaten gelöscht

Tenshu bewahrt die Schlüssel dieses Browsers in seinen Websitedaten auf. Wenn du sie löschst,
gehört dieser Browser nicht mehr zu deinem Realm. Deine anderen Geräte haben deine Daten weiterhin.
Wähle **Ich habe Tenshu schon** auf [tenshu.app/start](/start), um diesen Browser erneut
hinzuzufügen, oder stelle mit deiner Wiederherstellungsphrase wieder her.
:::

## Voraussetzungen {#requirements}

::: ios
- iOS 16 oder neuer, auf iPhone oder iPad.
:::

::: android
- Android 7.0 oder neuer.
:::

::: macos
- Ein Mac. Tenshu Control läuft in der Menüleiste.
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

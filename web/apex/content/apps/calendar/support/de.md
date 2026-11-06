---
# Tenshu Calendar support, German — a translation of en.md (store-presence-and-support-pages.md §4).
# ⚠ Machine-translated, pending native review: flip `reviewed` once a native speaker has read it.
# ⚖️ The privacy section follows the English one, which is under counsel review: any change counsel
# makes there must be carried over here.
# App labels: as the German apps show them (calendar-ios Localizable.xcstrings, calendar-shared-ui
# values-de, Control's labels as on Fit's German page). The iOS tab names are German
# (**Monat**, **Agenda**, **Kalender**: AdaptiveAppShell's titles are LocalizedStringKeys). ⚠ Some of
# Calendar still shows English on a German device: the iOS subscription menu's Pause/Resume, and on
# Android everything except the Control walls and banner (calendar-shared-ui App.kt: the rest
# follows in the i18n rollout). Those labels stay in English, in bold, with a German gloss in
# parentheses on first use. ⚠ So the Calendars screen is **Kalender** on iOS but **Calendars** on
# Android: a section shared by both platforms names both.
app: calendar
name: Tenshu Calendar
lang: de
reviewed: false
title: Tenshu Calendar – Support
description: Hilfe zu Tenshu Calendar auf {platform}: wie du loslegst, wie deine Termine privat bleiben, Antworten auf häufige Probleme und wie du den Support erreichst.
platforms: ios, android
defaultPlatform: ios
---

## Erste Schritte {#get-started}

Tenshu Calendar hält deine Termine und Erinnerungen verschlüsselt und auf all deinen Geräten
synchron. Calendar arbeitet mit Tenshu Control zusammen, das deine Schlüssel verwahrt und deine
Geräte verbindet. Richte zuerst Control ein.

::: ios
1. **Installiere Tenshu Control** aus dem App Store und öffne es.
   ![Willkommensbildschirm von Tenshu Control auf dem iPhone, mit den Schaltflächen „Start fresh on this device“, „I have Tenshu on another device“ und „Recover from a recovery phrase“](shot:control/01-welcome)
2. **Richte Control ein.** Tippe auf **Start fresh on this device** (Auf diesem Gerät neu
   beginnen) und dann auf **Continue** (Weiter). Schreib deine Wiederherstellungsphrase auf und
   bewahre sie an einem sicheren Ort auf. Tippe dann auf **Turn on sync** (Sync einschalten) und
   gib deine E-Mail-Adresse ein, um einen Speicherplan zu erhalten. Ohne Plan bleiben deine
   Termine auf diesem iPhone.
   ![Tenshu Control mit „You're set up“ (Alles eingerichtet) und einem Link „Turn on sync“ über den 24 Wörtern der Wiederherstellungsphrase](shot:control/03-recovery-phrase)
3. **Installiere Tenshu Calendar** aus dem App Store und öffne es. Calendar findet Control von
   selbst. Wenn es dich bittet, Tenshu Control einzurichten, schließe zuerst Schritt 2 ab.
   Calendar öffnet sich mit **Monat**. Tippe auf einen Tag, um seine Termine unter dem
   Monat zu sehen.
   ![Der Monat in Tenshu Calendar, mit einem farbigen Punkt an jedem belegten Tag und den heutigen Terminen darunter](shot:01-month)
4. **Leg einen Termin an.** Tippe unter **Monat** oder **Agenda** auf **+** (Neuer Termin). Gib
   einen Titel ein und, wenn du möchtest, Ort, Notizen, Kalender, eine Uhrzeit oder
   **Ganztägig**, eine Wiederholung und Erinnerungen. Tippe dann auf **Speichern**.
   ![Ein neuer Termin in Tenshu Calendar: Titel, Ort, Kalender, Uhrzeit, Wiederholung und Erinnerungen](shot:04-new-event)
5. **Sieh, was ansteht.** **Agenda** zeigt die Termine der nächsten Tage aus all deinen
   Kalendern. Tippe auf einen Termin, um zu sehen, wann und wo er ist, und tippe auf
   **Bearbeiten**, um ihn zu ändern. Bei einem wiederkehrenden Termin wählst du, ob du nur diesen
   Termin oder alle änderst.
   ![Die Agenda in Tenshu Calendar: die Termine der nächsten Tage aus fünf Kalendern](shot:02-agenda)
6. **Schalte Erinnerungen ein.** Öffne **Kalender** und tippe unter **Erinnerungen**
   auf **Erinnerungen einschalten**. Erlaube dann Mitteilungen. iOS stellt deine Erinnerungen
   zu, auch wenn Calendar geschlossen ist.
   ![Die Kalenderliste in Tenshu Calendar: Personal, Work, Family, Fitness und Travel in ihren Farben](shot:05-calendars)
7. **Ordne deine Kalender.** Tippe unter **Kalender** auf **Neuer Kalender**, um einen Kalender
   mit eigenem Namen und eigener Farbe anzulegen. Tippe auf den Kreis neben einem Kalender, um
   ihn auf diesem Gerät ein- oder auszublenden. Tippe auf **…** neben einem Kalender für
   **Bearbeiten**, **Als Standard festlegen** (der Kalender, in den neue Termine kommen),
   **Importieren oder exportieren…** und **Löschen**.
   ![Das Blatt eines Kalenders in Tenshu Calendar: sein Name, der Schalter „Details auf dem Sperrbildschirm ausblenden“ und die Farben](shot:06-edit-calendar)
:::

::: android
1. **Installiere Tenshu Control** aus Google Play und öffne es.
   ![Willkommensbildschirm von Tenshu Control auf Android, mit den Schaltflächen „Auf diesem Gerät neu beginnen“, „Ich habe Tenshu auf einem anderen Gerät“ und „Mit Wiederherstellungsphrase wiederherstellen“](shot:control/01-welcome)
2. **Richte Control ein.** Tippe auf **Auf diesem Gerät neu beginnen** und dann auf **Weiter**.
   Tippe unter **Alles eingerichtet** auf **Sync einschalten** und gib deine E-Mail-Adresse ein,
   um den kostenlosen Speicherplan zu erhalten, damit deine Termine deine anderen Geräte erreichen
   können. Tippe dann auf **Weiter**. Ohne Plan bleiben deine Termine auf diesem Telefon; einen Plan
   kannst du später unter **Dienste** wählen. Schreib deine Wiederherstellungsphrase unter
   **Einstellungen** → **Kontowiederherstellung einrichten** auf und bewahre sie an einem sicheren
   Ort auf.
   ![Bildschirm „Plan wählen“ von Tenshu Control mit dem Plan Free](shot:control/10-services)
3. **Installiere Tenshu Calendar** aus Google Play und öffne es. Calendar verbindet sich von
   selbst mit Control. Wenn es **Tenshu Control einrichten** anzeigt, schließe zuerst Schritt 2
   ab. Oben wechselst du zwischen **Month** (Monat), **Week** (Woche), **Day** (Tag) und
   **Agenda**; **Today** (Heute) bringt dich zu heute zurück.
   ![Der Monat in Tenshu Calendar, mit einem farbigen Punkt an jedem belegten Tag und den Terminen des Tages darunter](shot:01-month)
4. **Leg einen Termin an.** Tippe auf **+**. Gib einen Titel, ein Datum und eine Uhrzeit oder
   **All day** (Ganztägig) ein und, wenn du möchtest, eine Wiederholung, Erinnerungen, einen
   Kalender, einen Ort und Notizen. Tippe dann auf **Save** (Speichern). Um einen Termin zu ändern,
   tippe auf ihn. Bei einem wiederkehrenden Termin wählst du, ob du nur diesen oder alle änderst.
   ![Ein neuer Termin in Tenshu Calendar: Titel, Ort, Kalender, Uhrzeit, Wiederholung und Erinnerungen](shot:04-new-event)
5. **Ordne deine Kalender.** Tippe oben auf das Kalendersymbol (**Calendars**, Kalender); auf einem
   breiten Bildschirm stattdessen in der Seitenleiste auf **Manage calendars** (Kalender verwalten).
   Tippe auf
   **+**, um einen Kalender mit eigenem Namen und eigener Farbe anzulegen, oder auf einen Kalender,
   um ihn zu ändern. Das Kästchen neben einem Kalender blendet ihn auf diesem Gerät ein oder aus.
   Tippe auf **⋮** neben einem Kalender für **Make default** (Als Standard festlegen – der
   Kalender, in den neue Termine kommen), **Import or export…** (Importieren oder exportieren) und
   **Delete** (Löschen).
   ![Die Kalenderliste in Tenshu Calendar: Personal, Work, Family, Fitness und Travel in ihren Farben](shot:05-calendars)
6. **Erhalte Erinnerungen.** Tenshu Control zeigt deine Terminerinnerungen an, auch wenn Calendar
   geschlossen ist. Wenn dein Telefon fragt, ob Tenshu Control Benachrichtigungen senden darf,
   erlaube es.
:::

### Einen Kalender abonnieren

Du kannst einem Kalender folgen, den jemand im Web veröffentlicht, etwa Feiertagen oder einem
Teamplan.

::: ios
Öffne **Kalender**, tippe auf **Kalender abonnieren**, füge seine Adresse ein, gib ihm einen
Namen und tippe auf **Abonnieren**. Er wird etwa alle 6 Stunden aktualisiert, in jedem
Netz, solange Calendar, Tenshu Control oder eine andere Tenshu-App läuft, und **Jetzt
aktualisieren** im Menü **…** aktualisiert ihn sofort.
**Pause updates** (Aktualisierungen pausieren) hält ihn an und behält seine Termine. **Abo
beenden** entfernt ihn.
:::

::: android
Öffne **Calendars**, tippe auf die kleine Schaltfläche über **+** (**Subscribe to a calendar**,
Kalender abonnieren), füge seine Adresse ein und tippe auf **Subscribe** (Abonnieren). Er wird
etwa alle 6 Stunden im WLAN aktualisiert, und **Refresh now** (Jetzt aktualisieren) im Menü **⋮**
aktualisiert ihn sofort. **Pause updates** (Aktualisierungen pausieren) hält ihn an und behält
seine Termine. **Unsubscribe** (Abo beenden) entfernt ihn.
:::

Es funktionieren nur Adressen, die mit https:// oder webcal:// beginnen. Ein abonnierter Kalender
ist eine Kopie des Kalenders seines Herausgebers: Jede Aktualisierung ersetzt die Termine darin.

### Eine .ics-Datei importieren oder exportieren

Öffne **Kalender** (auf Android: **Calendars**), dann das Menü neben dem gewünschten Kalender,
und wähle **Importieren oder exportieren…** (auf Android: **Import or export…**). **Aus Datei
importieren** (**Import from a file**) fügt die Termine einer .ics-Datei zu diesem Kalender hinzu.
**In Datei exportieren** (**Export to a file**) speichert den Kalender als .ics-Datei, die du in
anderen Kalender-Apps öffnen kannst. Importierst du dieselbe Datei noch einmal, werden ihre Termine
aktualisiert statt doppelt angelegt.

::: android
Du kannst eine .ics-Datei auch mit Tenshu Calendar öffnen oder an die App teilen und dann den
Kalender wählen, in den ihre Termine kommen.
:::

## Wie Tenshu Calendar deine Termine privat hält {#privacy}

- **Deine Termine und Kalender werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen
  werden.** Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die
  Schlüssel, um sie zu lesen. Nur deine eigenen Geräte können deine Termine lesen. Einige Fakten,
  die der Server zum Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele
  Geräte du hast und wann sie synchronisieren.
  {source: docs/sync/threat-model.md | Bedrohungsmodell, Invariante I1, Asset A5 (Nutzerdaten), Asset A6 (Metadaten) und T-T-07}
  {source: AGENTS.md | House Rule 1: Das Relay sieht nur Chiffretext}
- **Wenn du einen Kalender abonnierst, lädt dein Gerät ihn direkt von seinem Herausgeber.** Der
  Herausgeber sieht deine IP-Adresse und dass du seinem Kalender folgst. Jedes deiner Geräte lädt
  ihn selbst. Die Server von Tenshu sehen den Abruf nie.
  {source: docs/sync/threat-model.md | Bedrohungsmodell, Asset A5, Kalender-Abos: Der Abruf ist direkt, einer pro Gerät, und das Relay sieht ihn nie}
  {source: calendar-service-proxy/src/commonMain/kotlin/org/tenshu/calendar/proxy/KtorIcsSubscriptionFetcher.kt | Der Abruf: eine direkte HTTPS-Anfrage an den Herausgeber, ohne Cookies oder Zugangsdaten}
- **Calendar hat keine Werbung und keine Analyse-Tools.**
  {source: store/calendar/privacy-label.md | Datenschutzangaben von Calendar: kein Tracking, kein Werbe- oder Analyse-SDK}
  {source: calendar-ios/Resources/PrivacyInfo.xcprivacy | Datenschutz-Manifest von Calendar für iOS: kein Tracking, nichts erhoben}
- **Ohne Speicherplan bleiben deine Termine auf diesem Gerät.** Deine Termine gehen erst an die
  Server von Tenshu, verschlüsselt, wenn du die Synchronisierung einschaltest.
  {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, Speicherkapazität: ohne Abo kein Relay-Verkehr}

::: ios
- **Calendar liest die anderen Kalender auf deinem Gerät nicht.**
  {source: calendar-ios/project.yml | Kein EventKit und keine Begründung für Kalenderzugriff}
  {source: store/calendar/privacy-label.md | „Calendar liest den Systemkalender nicht (kein EventKit)“}
:::

::: android
- **Auf Android liest Calendar den Kalender deines Telefons nicht, und es kann selbst das
  Internet nicht nutzen.** Deine Termine erreichen deine anderen Geräte, und abonnierte Kalender
  erreichen Calendar, über Tenshu Control.
  {source: calendar-android/src/main/AndroidManifest.xml | Android-Manifest: Die Berechtigung INTERNET ist entfernt, und keine Kalenderberechtigung ist deklariert}
  {source: docs/sync/threat-model.md | Bedrohungsmodell, Asset A5, Kalender-Abos: Der Abruf gehört Control, nie der App}
:::

## Häufige Probleme {#problems}

### Calendar bittet dich, Tenshu Control einzurichten

Calendar bewahrt deine Termine in Tenshu Control auf, deshalb muss Control zuerst auf demselben
Gerät installiert und eingerichtet sein. Installiere Tenshu Control, öffne es und wähle **Auf
diesem Gerät neu beginnen** oder, wenn du Tenshu schon nutzt, **Ich habe Tenshu auf einem anderen
Gerät** (auf dem iPhone: **Start fresh on this device** bzw. **I have Tenshu on another
device**). Öffne dann Calendar erneut.

::: ios
Sobald Control eingerichtet ist, muss es nicht geöffnet bleiben. Calendar funktioniert
eigenständig.
:::

### Calendar zeigt „Tenshu Control ist nicht erreichbar“

::: ios
Calendar zeigt, was bereits vorhanden ist, kann aber nichts Neues laden. Tippe auf
**Einrichten**, folge den Schritten in Tenshu Control und kehre dann zu Calendar zurück.
:::

::: android
Calendar kann deine Termine nicht laden. Tippe auf **Control öffnen** und prüfe, dass Tenshu
Control installiert und eingerichtet ist. Kehre dann zu Calendar zurück.
:::

::: ios
### Calendar zeigt „Not syncing — this device has no storage plan“

(„Keine Synchronisierung – dieses Gerät hat keinen Speicherplan“.) Der Hinweis oben in Calendar
sagt, dass deine Dateien auf diesem Gerät bleiben: Auf dem iPhone und iPad steht er in jeder
Tenshu-App gleich da, und hier sind deine Termine gemeint. Sie sind sicher, können deine
anderen Geräte aber noch nicht erreichen. Tippe auf **Speicher verwalten**, um Tenshu Control zu
öffnen, und wähle dort einen Speicherplan (siehe unten).

### Calendar zeigt „Storage full“

(„Speicher voll“.) Der Hinweis zeigt, wie viel deines Plans belegt ist. Deine Termine
bleiben auf diesem Gerät und nichts wird gelöscht, aber neue Änderungen können erst wieder
synchronisiert werden, wenn du Platz schaffst oder mehr Speicher holst. Tippe auf **Speicher
verwalten**, um Tenshu Control zu öffnen, und wähle unter **Speicher** einen größeren Plan.

### Meine Termine bleiben auf diesem iPhone

Öffne Tenshu Control und gehe zu **Einstellungen**. Steht dort **Local only** (Nur lokal), hat
dieses Realm noch keinen Speicherplan, und deine Daten bleiben auf diesem iPhone. Öffne
**Speicher** und wähle einen Plan. Der kostenlose Plan braucht eine bestätigte E-Mail-Adresse.
Ein Einlösecode funktioniert auch ohne.
:::

::: android
### Calendar zeigt „Not syncing — this device has no storage plan“

(„Keine Synchronisierung – dieses Gerät hat keinen Speicherplan“.) Deine Termine sind auf diesem
Gerät sicher, können deine anderen Geräte aber noch nicht erreichen. Tippe auf **Set up storage**
(Speicher einrichten) oder öffne Tenshu Control und wähle unter **Dienste** einen Speicherplan.
Der kostenlose Plan braucht eine bestätigte E-Mail-Adresse. Ein Einlösecode funktioniert auch
ohne.

### Calendar zeigt „Storage full“

(„Speicher voll“.) Deine Termine bleiben auf diesem Gerät und nichts wird gelöscht, aber neue
Änderungen können nicht synchronisiert werden. Schaffe Platz oder wähle in Tenshu Control unter
**Dienste** einen größeren Plan.

### Calendar sagt, es sei neuer oder älter als Tenshu Control

Calendar und Control müssen auf demselben Gerät zueinander passende Versionen haben, damit sie
weiter synchronisieren. Aktualisiere beide über Google Play. Alles, was bereits auf dem Gerät
ist, bleibt erhalten.
:::

### Ich bekomme keine Erinnerungen

::: ios
Öffne **Kalender**. Siehst du **Erinnerungen einschalten**, tippe darauf und erlaube
Mitteilungen. Siehst du **Öffne die Einstellungen, um Benachrichtigungen zu erlauben**, tippe
darauf und schalte die Mitteilungen für Calendar ein.

Calendar richtet Erinnerungen ein, während es geöffnet ist. Eine Erinnerung für einen Termin, den
du auf einem anderen Gerät angelegt hast, wird eingerichtet, sobald du Calendar auf diesem iPhone
oder iPad das nächste Mal öffnest.
:::

::: android
Deine Terminerinnerungen zeigt Tenshu Control an, nicht Calendar. Öffne die **Einstellungen**
deines Telefons, suche **Tenshu Control** unter deinen Apps und prüfe, dass seine
Benachrichtigungen erlaubt sind.
:::

Prüfe außerdem, ob der Termin eine Erinnerung hat: Bearbeite ihn und sieh unter **Erinnerungen**
(auf Android: **Reminders**) nach.

### Eine Erinnerung zeigt nur „Reminder“

Der Termin liegt in einem Kalender, bei dem **Details auf dem Sperrbildschirm ausblenden** (auf
Android: **Hide details on the lock screen**) eingeschaltet ist. Seine Erinnerungen zeigen dann
nur „Reminder“, ohne Titel und Ort. Um das zu ändern, bearbeite den Kalender unter **Kalender**
(auf Android: **Calendars**) und schalte den Schalter aus.

### Ein abonnierter Kalender wird nicht aktualisiert

Prüfe, dass er nicht pausiert ist: Bietet sein Menü **Resume updates** (Aktualisierungen
fortsetzen) an, tippe darauf. Tippe dann auf **Jetzt aktualisieren** (auf Android: **Refresh
now**). Auf Android aktualisiert Calendar von selbst nur im WLAN. Wird er immer noch nicht aktualisiert,
prüfe, ob sich seine Adresse noch in einem Webbrowser öffnen lässt: Der Herausgeber hat ihn
vielleicht verschoben oder entfernt.

### Mein anderes Gerät zeigt meine Termine nicht

Termine erreichen deine anderen Geräte erst, nachdem das Gerät, auf dem sie liegen, sie
hochgeladen hat. Prüfe der Reihe nach:

1. Beide Geräte sind im selben Realm. In Tenshu Control sollte die Geräteliste beide zeigen.
2. Das Gerät, auf dem die Termine liegen, hat einen Speicherplan (siehe oben).
3. Beide Geräte sind online. Öffne Calendar auf beiden und gib ihm ein paar Minuten Zeit.
4. Der Kalender ist auf diesem Gerät nicht ausgeblendet. Das Ausblenden eines Kalenders betrifft
   nur dieses Gerät.

### Calendar zeigt „Nothing in that file could be read as an event“

(„Nichts in dieser Datei konnte als Termin gelesen werden“.) Die Datei ist keine Kalenderdatei,
die Calendar lesen kann. Exportiere sie aus deiner anderen Kalender-App noch einmal als
.ics-Datei. Konnten nur einige Termine nicht gelesen werden, importiert Calendar den Rest und
sagt dir, wie viele es ausgelassen hat.

## Voraussetzungen {#requirements}

::: ios
- iOS 16 oder neuer, auf dem iPhone oder iPad.
- Tenshu Control, installiert und eingerichtet auf demselben Gerät.
:::

::: android
- Android 7.0 oder neuer.
- Tenshu Control, installiert und eingerichtet auf demselben Gerät.
:::

- Zum Synchronisieren zwischen Geräten: ein Speicherplan. Der kostenlose Plan bietet 5 GB.

## Kontakt {#contact}

Schreib an [support@tenshu.app](mailto:support@tenshu.app). Sag uns, welche App und welches Gerät
du nutzt und was du auf dem Bildschirm siehst.

**Der Support wird dich nie nach deiner Wiederherstellungsphrase fragen.** Niemand bei Tenshu
braucht sie, niemals. Wer sie hat, kann alles in deinem Konto öffnen.

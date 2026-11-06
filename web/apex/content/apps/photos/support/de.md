---
# Tenshu Photos support, German — a translation of en.md (store-presence-and-support-pages.md §4).
# ⚠ Machine-translated, pending native review: flip `reviewed` once a native speaker has read it.
# ⚖️ The privacy section follows the English one, which is under counsel review: any change counsel
# makes there must be carried over here.
# App labels: as the German apps show them. On iOS, photos-ios Localizable.xcstrings localizes the
# tabs (**Mediathek**, **Sammlungen**, **Suche**) and the menus (Zum Album hinzufügen, In den
# Papierkorb legen, Mehr, Info, Albumoptionen, Speicher verwalten, Einrichten); the rest of the iOS
# app and almost all of Android Photos (photos-shared-ui values-de holds only the Control walls)
# still show English: those labels stay in English, in bold, with a German gloss in parentheses on
# first use — the Android storage banner (RealmStorageBannerHost) included. Control's labels as on Contacts' German page. ⚠ The iOS screenshots are English
# captures, so their alt texts name the English labels.
app: photos
name: Tenshu Photos
lang: de
reviewed: false
title: Tenshu Photos – Support
description: Hilfe zu Tenshu Photos auf {platform}: wie du loslegst, wie deine Fotos privat bleiben, Antworten auf häufige Probleme und wie du den Support erreichst.
platforms: ios, android
defaultPlatform: ios
---

## Erste Schritte {#get-started}

Tenshu Photos zeigt deine Fotos und Videos in einer Mediathek, auf jedem deiner Geräte gleich.
Tenshu Drive sichert deine Fotos, und Photos zeigt, was gesichert wurde, von diesem Gerät und von
deinen anderen. Photos arbeitet mit Tenshu Control
zusammen, das deine Schlüssel verwahrt und deine Geräte verbindet. Richte zuerst Control ein.

::: ios
Auf dem iPhone und iPad sichert Photos selbst nichts: Das macht Tenshu Drive.

1. **Installiere Tenshu Control** aus dem App Store und öffne es.
   ![Willkommensbildschirm von Tenshu Control auf dem iPhone, mit den Schaltflächen „Start fresh on this device“, „I have Tenshu on another device“ und „Recover from a recovery phrase“](shot:control/01-welcome)
2. **Richte Control ein.** Tippe auf **Start fresh on this device** (Auf diesem Gerät neu
   beginnen) und dann auf **Continue** (Weiter). Schreib deine Wiederherstellungsphrase auf und
   bewahre sie an einem sicheren Ort auf. Tippe dann auf **Turn on sync** (Sync einschalten) und
   gib deine E-Mail-Adresse ein, um einen Speicherplan zu erhalten. Ohne Plan bleiben deine
   Daten auf diesem iPhone. Nutzt du Tenshu schon auf einem anderen Gerät, tippe stattdessen auf
   **I have Tenshu on another device** (Ich habe Tenshu auf einem anderen Gerät), damit deine
   Fotos dieses iPhone erreichen.
   ![Tenshu Control mit „You're set up“ (Alles eingerichtet) und einem Link „Turn on sync“ über den 24 Wörtern der Wiederherstellungsphrase](shot:control/03-recovery-phrase)
3. **Sichere deine Fotos mit Tenshu Drive.** Damit Fotos von diesem iPhone in deine Mediathek
   kommen, installiere Tenshu Drive aus dem App Store und sichere deine Fotos dort. Drive fragt
   nach Zugriff auf deine Fotomediathek. Photos fragt nie danach. Sind deine Fotos schon von einem
   anderen Gerät gesichert, kannst du diesen Schritt überspringen.
4. **Installiere Tenshu Photos** aus dem App Store und öffne es. Photos findet Control von
   selbst. Wenn es dich bittet, Tenshu Control einzurichten, schließe zuerst Schritt 2 ab. Photos
   öffnet sich mit dem Tab **Mediathek**. Dort steht **No Photos Yet** (Noch keine Fotos), bis
   deine gesicherten Fotos ankommen.
   ![Die Mediathek von Tenshu Photos auf dem iPhone: ein Mosaik aus Fotos, nach Tagen gruppiert](shot:01-library)
5. **Stöbere in deiner Mediathek.** Die Schaltfläche oben in der **Mediathek** ändert die
   Gruppierung: ein Mosaik oder nach Tag, Monat oder Jahr. Zieh den Scrubber am Rand, um durch
   die Jahre zu springen.
   ![Die Mediathek von Tenshu Photos, nach Monaten gruppiert](shot:05-months)
6. **Öffne ein Foto.** Tippe darauf, um es im Vollbild zu sehen. Zieh es mit zwei Fingern auf, um
   zu zoomen, und wische zum nächsten. Tippe auf das Herz, um es zu deinen **Favourites**
   (Favoriten) hinzuzufügen. Unter **Mehr** zeigt **Info** sein Datum, Details und Alben, und du
   kannst es **Zum Album hinzufügen** oder **In den Papierkorb legen**.
   ![Ein Foto im Vollbild in Tenshu Photos, mit dem Herz als Favorit markiert](shot:04-viewer)
7. **Lege Alben an.** Öffne **Sammlungen** und tippe auf **New album** (Neues Album). Um Fotos
   hinzuzufügen, halte in der **Mediathek** eines gedrückt, tippe die anderen an, die du willst,
   und tippe auf **Zum Album hinzufügen**. In einem Album benennt die Schaltfläche
   **Albumoptionen** es um oder löscht es. Wenn du ein Album löschst, bleiben seine Fotos erhalten.
   ![Ein Album in Tenshu Photos, mit seinen Fotos in einem Raster](shot:03-album)
8. **Finde Duplikate und leere den Papierkorb.** In **Sammlungen** findest du auch deine
   **Favourites**, deine Jahre, **Duplicates** (Duplikate) und den **Trash** (Papierkorb).
   **Duplicates** findet exakte Kopien desselben Fotos. Über jedem
   Satz steht **N identical copies — tap the one to keep** (N identische Kopien – tippe auf die,
   die bleiben soll): Tippe auf diese Kopie und bestätige **Move the other copies to the trash?**
   (Die anderen Kopien in den Papierkorb legen?) mit **Move to Trash**. Ist der Schalter **Delete
   permanently** (Endgültig löschen) an, werden die anderen Kopien stattdessen endgültig gelöscht
   (**Delete for Good**). Im **Trash** kannst du ein Foto
   wiederherstellen, endgültig löschen oder den ganzen Papierkorb leeren.
   ![Sammlungen in Tenshu Photos: Favoriten, Duplikate, Alben mit ihren Titelbildern, die Jahre und der Papierkorb](shot:02-collections)
9. **Suche.** Tippe auf **Suche** und gib einen Teil eines Dateinamens ein, etwa `IMG_74`.
:::

::: android
1. **Installiere Tenshu Control** aus Google Play und öffne es.
   ![Willkommensbildschirm von Tenshu Control auf Android, mit den Schaltflächen „Auf diesem Gerät neu beginnen“, „Ich habe Tenshu auf einem anderen Gerät“ und „Mit Wiederherstellungsphrase wiederherstellen“](shot:control/01-welcome)
2. **Richte Control ein.** Tippe auf **Auf diesem Gerät neu beginnen** und dann auf **Weiter**.
   Tippe unter **Alles eingerichtet** auf **Sync einschalten** und gib deine E-Mail-Adresse ein,
   um den kostenlosen Speicherplan zu erhalten, damit deine Fotos deine anderen Geräte erreichen
   können. Tippe dann auf **Weiter**. Ohne Plan bleiben deine Daten auf diesem Telefon; einen Plan
   kannst du später unter **Dienste** wählen. Schreib deine Wiederherstellungsphrase unter
   **Einstellungen** → **Kontowiederherstellung einrichten** auf und bewahre sie an einem sicheren
   Ort auf.
   ![Bildschirm „Plan wählen“ von Tenshu Control mit dem Plan Free](shot:control/10-services)
3. **Sichere deine Fotos mit Tenshu Drive.** Damit Fotos von diesem Telefon in deine Mediathek
   kommen, installiere Tenshu Drive aus Google Play und richte dort die Sicherung ein. Sind deine
   Fotos schon von einem anderen Gerät gesichert, kannst du diesen Schritt überspringen.
4. **Installiere Tenshu Photos** aus Google Play und öffne es. Photos verbindet sich von selbst mit
   Control. Wenn es **Tenshu Control einrichten** anzeigt, schließe zuerst Schritt 2 ab. Photos
   öffnet sich mit **Photos**, deiner Mediathek.
5. **Stöbere in deiner Mediathek.** Die Schaltfläche oben ändert die Gruppierung: ein Mosaik oder
   nach Tag, Monat oder Jahr. Zieh den Scrubber am Rand, um durch die Jahre zu springen. Tippe auf
   ein Foto, um es im Vollbild zu sehen, zieh es mit zwei Fingern auf, um zu zoomen, und wische
   zum nächsten. Tippe auf den Stern, um es zum Favoriten zu machen.
6. **Lege Alben an.** Öffne **Collections** (Sammlungen) und tippe auf **New album** (Neues
   Album). Um Fotos hinzuzufügen, halte in deiner Mediathek eines gedrückt, tippe die anderen an,
   die du willst, und tippe auf das Symbol **+** (**Add to album**, Zum Album hinzufügen). Das
   Papierkorb-Symbol legt sie in den Papierkorb. In einem Foto bietet **More** (Mehr) dieselben
   beiden.
7. **Finde Duplikate und leere den Papierkorb.** In **Collections** findest du auch deine
   Favoriten, deine Jahre, **Duplicates** (Duplikate) und den **Trash** (Papierkorb).
   **Duplicates** findet exakte Kopien desselben Fotos. Über jedem
   Satz steht **N identical copies — tap the one to keep** (N identische Kopien – tippe auf die,
   die bleiben soll): Tippe auf diese Kopie und bestätige **Move the other copies to the trash?**
   (Die anderen Kopien in den Papierkorb legen?) mit **Move to Trash**. Ist der Schalter **Delete
   permanently** (Endgültig löschen) an, werden die anderen Kopien stattdessen endgültig gelöscht
   (**Delete for Good**). Im **Trash**, den auch **Settings**
   (Einstellungen) öffnet, kannst du ein Foto wiederherstellen, endgültig löschen oder den ganzen
   Papierkorb leeren.
8. **Suche.** Tippe oben in deiner Mediathek auf die Lupe und gib einen Teil eines Dateinamens ein.
9. **Sichere aus Photos, und sieh, was gesichert wird.** Sobald dieses Telefon mindestens einen
   Ordner sichert, listet **Settings** sie unter **Backed up from this device** (Von diesem Gerät
   gesichert), mit dem, was jeder gerade tut. Schaltest du einen aus, wird er nicht mehr gesichert.
   Darunter:
   - **Back up a folder** (Einen Ordner sichern) listet die Fotoordner dieses Telefons. Einen
     Ordner, den du einschaltest, sichert Photos oder Drive, je nachdem, welche App läuft: Die
     beiden teilen sich eine Sicherung. Photos fragt selbst nicht nach Zugriff auf deine Fotos.
     Ist die Liste leer, erlaube ihn in den **Einstellungen** deines Telefons → **Apps** →
     **Photos** → **Berechtigungen** → **Fotos und Videos**.
   - **Skip photos already in Tenshu** (Fotos überspringen, die schon in Tenshu sind) lädt kein
     Foto hoch, dessen exakte Kopie schon gespeichert ist, auch nicht von einem anderen Gerät.
   - **Play videos in the grid** (Videos im Raster abspielen) lässt Videos in deiner Mediathek
     stumm abspielen, eines nach dem anderen.

   Wird von diesem Telefon noch nichts gesichert, zeigt **Settings** nur den **Trash** und sagt,
   dass du die Sicherung in Tenshu Drive einrichten sollst.
10. **Gruppiere Fotos nach Personen (optional).** Auf Android kann Photos die Personen in deinen
    Fotos unter **Collections** → **People** (Personen) zeigen. Das ist aus, bis du es
    einschaltest: Öffne in Tenshu Control **Einstellungen** → **Models** (Modelle), lade die
    Gesichtsmodelle herunter und schalte **Group photos by people** (Fotos nach Personen
    gruppieren) für **This device** (Dieses Gerät) ein. Personen erscheinen, nachdem das Telefon
    deine Fotos durchgesehen hat; das tut es, wenn es nicht benutzt wird und lädt.
:::

## Wie Tenshu Photos deine Fotos privat hält {#privacy}

- **Deine Fotos werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.** Die Server
  von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.
  Nur deine eigenen Geräte können deine Fotos lesen. Auch deine Alben und Favoriten sind
  verschlüsselt. Einige Fakten, die der Server zum Synchronisieren braucht, sieht er trotzdem: wie
  viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren.
  {source: docs/sync/threat-model.md | Bedrohungsmodell, Invariante I1, Assets A5 (Nutzerdaten) und A6 (Metadaten), T-T-07}
  {source: AGENTS.md | House Rule 1: Das Relay sieht nur Chiffretext}
- **Photos hat keine Werbung und keine Analyse-Tools.**
  {source: store/photos/privacy-label.md | Datenschutzangaben von Photos: kein Tracking, kein Werbe- oder Analyse-SDK}
  {source: photos-ios/Resources/PrivacyInfo.xcprivacy | Datenschutz-Manifest von Photos für iOS: kein Tracking, nichts erhoben}
- **Ohne Speicherplan bleiben deine Fotos auf diesem Gerät.** Deine Fotos gehen erst an die
  Server von Tenshu, verschlüsselt, wenn du die Synchronisierung einschaltest.
  {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, Speicherkapazität: ohne Abo kein Relay-Verkehr}

::: ios
- **Photos liest die Fotomediathek auf deinem Gerät nicht.** Tenshu Drive sichert die Fotos, die
  du auswählst, und Photos zeigt sie aus deiner verschlüsselten Mediathek.
  {source: photos-ios/project.yml | Kein PhotoKit und keine Begründung für den Zugriff auf die Fotomediathek}
  {source: store/photos/privacy-label.md | „Photos selbst liest keine Fotomediathek“}
:::

::: android
- **Auf Android kann Photos selbst das Internet nicht nutzen.** Deine Fotos erreichen deine
  anderen Geräte über Tenshu Control, verschlüsselt. Erlaubst du Photos, deine Fotos und Videos
  zu sehen, nutzt es das nur, um die Ordner dieses Telefons für **Back up a folder** aufzulisten,
  und sichert nur die Ordner, die du einschaltest.
  {source: photos-android/src/main/AndroidManifest.xml | Android-Manifest: Die Berechtigung INTERNET ist entfernt; READ_MEDIA_IMAGES und READ_MEDIA_VIDEO, um die Ordner des Telefons aufzulisten}
  {source: photos-shared-ui/src/commonMain/kotlin/app/tenshu/photos/ui/screens/settings/SettingsScreen.kt | Einstellungen: Einen Ordner sichern, eine mit Drive geteilte Sicherung}
- **Das Gruppieren nach Personen geschieht auf deinem Telefon, und erst, wenn du es
  einschaltest.** Tenshu Control findet die Gesichter auf dem Telefon. Die dafür verwendete
  Bibliothek MediaPipe sendet nichts an Google oder sonst jemanden. Die Gruppen liegen in deiner
  verschlüsselten Mediathek, wie deine Alben.
  {source: docs/proposals/inference-service.md | Inferenzdienst: Modelle auf dem Gerät, Zustimmung mit zwei Schaltern, ohne Antwort gilt Nein}
  {source: control/src/main/AndroidManifest.xml | Android-Manifest von Control: Das Upload-Backend von Googles datatransport ist entfernt}
:::

## Häufige Probleme {#problems}

### Photos bittet dich, Tenshu Control einzurichten

Photos bewahrt deine Mediathek in Tenshu Control auf, deshalb muss Control zuerst auf demselben
Gerät installiert und eingerichtet sein. Installiere Tenshu Control, öffne es und wähle **Auf
diesem Gerät neu beginnen** oder, wenn du Tenshu schon nutzt, **Ich habe Tenshu auf einem anderen
Gerät** (auf dem iPhone: **Start fresh on this device** bzw. **I have Tenshu on another
device**). Öffne dann Photos erneut.

::: ios
Sobald Control eingerichtet ist, muss es nicht geöffnet bleiben. Photos funktioniert eigenständig.
:::

### Photos zeigt „Tenshu Control ist nicht erreichbar“

::: ios
Photos zeigt, was bereits vorhanden ist, kann aber nichts Neues laden. Tippe auf **Einrichten**,
folge den Schritten in Tenshu Control und kehre dann zu Photos zurück.
:::

::: android
Deine Fotos erscheinen, sobald Control wieder läuft. Tippe auf **Control öffnen** und prüfe, dass
Tenshu Control installiert und eingerichtet ist. Kehre dann zu Photos zurück.
:::

### Meine Mediathek ist leer

Photos zeigt nur, was Tenshu Drive gesichert hat, auf diesem Gerät oder einem anderen in deinem
Realm. Prüfe der Reihe nach:

1. Tenshu Drive sichert auf mindestens einem deiner Geräte Fotos.
2. Kommen die Fotos von einem anderen Gerät, hat das sichernde Gerät einen Speicherplan (siehe
   unten).
3. Beide Geräte sind online. Öffne Photos auf beiden und gib ihm ein paar Minuten Zeit.

::: ios
### Ein Foto zeigt „Dieses Foto wurde noch nicht auf dieses Gerät heruntergeladen“

Das Foto ist in deiner Mediathek, aber sein Bild hat dieses iPhone noch nicht erreicht. Es kommt
an, sobald das Gerät, das es gesichert hat, es hochgeladen hat und beide Geräte online sind. Jenes
Gerät braucht einen Speicherplan (siehe unten).

### Photos zeigt „Not syncing — this device has no storage plan“

(„Keine Synchronisierung – dieses Gerät hat keinen Speicherplan“.) Der Hinweis oben in Photos
sagt, dass deine Dateien auf diesem Gerät bleiben: Auf dem iPhone und iPad steht er in jeder
Tenshu-App gleich da, und hier sind deine Fotos gemeint. Sie sind sicher, können deine anderen
Geräte aber noch nicht erreichen. Tippe auf **Speicher verwalten**, um Tenshu Control zu öffnen,
und wähle dort einen Speicherplan (siehe unten).

### Photos zeigt „Storage full“

(„Speicher voll“.) Der Hinweis zeigt, wie viel deines Plans belegt ist. Deine Fotos bleiben auf
diesem Gerät und nichts wird gelöscht, aber neue Fotos können erst wieder synchronisiert werden,
wenn du Platz schaffst oder mehr Speicher holst. Tippe auf **Speicher verwalten**, um Tenshu
Control zu öffnen, und wähle unter **Speicher** einen größeren Plan.

### Meine Fotos bleiben auf diesem iPhone

Öffne Tenshu Control und gehe zu **Einstellungen**. Steht dort **Local only** (Nur lokal), hat
dieses Realm noch keinen Speicherplan, und deine Daten bleiben auf diesem iPhone. Öffne
**Speicher** und wähle einen Plan. Der kostenlose Plan braucht eine bestätigte E-Mail-Adresse.
Ein Einlösecode funktioniert auch ohne.

### People bleibt auf meinem iPhone leer

Photos auf dem iPhone und iPad gruppiert Gesichter noch nicht selbst. **People** (Personen) zeigt
die Personen, die ein Android-Telefon in deinem Realm gruppiert hat, sobald sie synchronisiert
sind. **Places** (Orte) zeigt die Orte, die Tenshu Timeline synchronisiert hat.
:::

::: android
### Photos zeigt „Not syncing — this device has no storage plan“

(„Keine Synchronisierung – dieses Gerät hat keinen Speicherplan“.) Deine Fotos sind auf diesem
Gerät sicher, können deine anderen Geräte aber noch nicht erreichen. Tippe auf **Set up storage**
(Speicher einrichten) oder öffne Tenshu Control und wähle unter **Dienste** einen
Speicherplan. Der kostenlose Plan braucht eine bestätigte E-Mail-Adresse. Ein Einlösecode
funktioniert auch ohne.

### Photos zeigt „Storage full“

(„Speicher voll“.) Deine Fotos bleiben auf diesem Gerät und nichts wird gelöscht, aber neue Fotos
können nicht synchronisiert werden. Schaffe Platz oder tippe auf **Manage storage** (Speicher
verwalten) und wähle in Tenshu Control unter **Dienste** einen größeren Plan.

### Photos sagt, es sei neuer oder älter als Tenshu Control

Photos und Control müssen auf demselben Gerät zueinander passende Versionen haben, damit sie
weiter synchronisieren. Aktualisiere beide über Google Play. Alles, was bereits auf dem Gerät
ist, bleibt erhalten.

### People ist leer

Die Zeile unter **People** (Personen) sagt, warum:

- **To see people here, turn on "Group photos by people"** (Schalte „Fotos nach Personen
  gruppieren“ ein): Öffne Tenshu Control, **Einstellungen** → **Models**, und schalte es für
  **This device** ein.
- **To see people here, download the face models** (Lade die Gesichtsmodelle herunter): Lade sie
  in Tenshu Control unter **Einstellungen** → **Models** herunter.
- **People appear after this device indexes your photos** (Personen erscheinen, nachdem dieses
  Gerät deine Fotos durchgesehen hat): Das Telefon sieht deine Fotos durch, wenn es nicht benutzt
  wird und lädt. Lass es eine Weile am Ladegerät.
:::

### Ich kann ein Foto nicht teilen oder bearbeiten

Photos kann Fotos noch nicht teilen, exportieren oder bearbeiten.

## Voraussetzungen {#requirements}

::: ios
- iOS 16 oder neuer, auf dem iPhone oder iPad.
- Tenshu Control, installiert und eingerichtet auf demselben Gerät.
- Tenshu Drive, um Fotos von diesem Gerät zu sichern.
:::

::: android
- Android 7.0 oder neuer.
- Tenshu Control, installiert und eingerichtet auf demselben Gerät.
- Tenshu Drive, um Fotos von diesem Gerät zu sichern.
:::

- Zum Synchronisieren zwischen Geräten: ein Speicherplan. Der kostenlose Plan bietet 5 GB.

## Kontakt {#contact}

Schreib an [support@tenshu.app](mailto:support@tenshu.app). Sag uns, welche App und welches Gerät
du nutzt und was du auf dem Bildschirm siehst.

**Der Support wird dich nie nach deiner Wiederherstellungsphrase fragen.** Niemand bei Tenshu
braucht sie, niemals. Wer sie hat, kann alles in deinem Konto öffnen.

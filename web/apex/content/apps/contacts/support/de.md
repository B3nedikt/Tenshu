---
# Tenshu Contacts support, German — a translation of en.md (store-presence-and-support-pages.md §4).
# ⚠ Machine-translated, pending native review: flip `reviewed` once a native speaker has read it.
# ⚖️ The privacy section follows the English one, which is under counsel review: any change counsel
# makes there must be carried over here.
# App labels: as the German apps show them (contacts-ios Localizable.xcstrings, contacts-shared-ui
# values-de, Control's labels as on Fit's German page). The iOS tab names are localized too
# (AdaptiveAppShell's titles are LocalizedStringKeys): **Kontakte** and **Ich**, "Ich" on both
# platforms (contacts_nav_you). Where the app still shows English (Control's iOS onboarding), the
# English label stays in bold with a German gloss in parentheses on first use. ⚠ The iOS screenshots
# are English captures, so their alt texts name the English labels.
app: contacts
name: Tenshu Contacts
lang: de
reviewed: false
title: Tenshu Contacts – Support
description: Hilfe zu Tenshu Contacts auf {platform}: wie du loslegst, wie deine Kontakte privat bleiben, Antworten auf häufige Probleme und wie du den Support erreichst.
platforms: ios, android
defaultPlatform: ios
---

## Erste Schritte {#get-started}

Tenshu Contacts hält die Menschen, die du kennst, verschlüsselt und auf all deinen Geräten
synchron. Contacts arbeitet mit Tenshu Control zusammen, das deine Schlüssel verwahrt und deine
Geräte verbindet. Richte zuerst Control ein.

::: ios
Auf dem iPhone und iPad zeigt Contacts die Kontakte, die du in Tenshu Contacts auf deinen anderen
Geräten hinzugefügt hast, und die Personen, die deine Einladung angenommen haben. Kontakte
hinzufügen, bearbeiten oder importieren kannst du auf dem iPhone und iPad noch nicht.

1. **Installiere Tenshu Control** aus dem App Store und öffne es.
   ![Willkommensbildschirm von Tenshu Control auf dem iPhone, mit den Schaltflächen „Start fresh on this device“, „I have Tenshu on another device“ und „Recover from a recovery phrase“](shot:control/01-welcome)
2. **Richte Control ein.** Tippe auf **Start fresh on this device** (Auf diesem Gerät neu
   beginnen) und dann auf **Continue** (Weiter). Schreib deine Wiederherstellungsphrase auf und
   bewahre sie an einem sicheren Ort auf. Tippe dann auf **Turn on sync** (Sync einschalten) und
   gib deine E-Mail-Adresse ein, um einen Speicherplan zu erhalten. Ohne Plan bleiben deine
   Daten auf diesem iPhone. Nutzt du Tenshu schon auf einem anderen Gerät, tippe stattdessen auf
   **I have Tenshu on another device** (Ich habe Tenshu auf einem anderen Gerät), damit deine
   Kontakte dieses iPhone erreichen.
   ![Tenshu Control mit „You're set up“ (Alles eingerichtet) und einem Link „Turn on sync“ über den 24 Wörtern der Wiederherstellungsphrase](shot:control/03-recovery-phrase)
3. **Installiere Tenshu Contacts** aus dem App Store und öffne es. Contacts findet Control von
   selbst. Wenn es dich bittet, Tenshu Control einzurichten, schließe zuerst Schritt 2 ab.
   Contacts öffnet sich mit dem Tab **Kontakte**. Dort steht **Noch keine Kontakte**,
   bis deine Kontakte von deinen anderen Geräten ankommen.
   ![Die Kontaktliste von Tenshu Contacts, darüber eine Zeile mit möglichen Duplikaten](shot:01-contacts)
4. **Finde jemanden.** Gib einen Namen, eine Telefonnummer, eine E-Mail-Adresse oder eine
   Organisation in das Suchfeld oben in der Liste ein. Siehst du es nicht, zieh die Liste nach
   unten. Tippe auf einen Kontakt, um seine Telefonnummern, E-Mail-Adressen, Organisation und
   Notizen zu sehen.
   ![Tenshu Contacts sucht nach einer Organisation und findet drei Kolleginnen und Kollegen](shot:03-search)
5. **Verifiziere eine Person, mit der du verbunden bist.** Öffne ihren Kontakt und tippe unter
   **Schlüssel** auf **Verifizieren**. Lies ihr die sechs Ziffern am Telefon oder persönlich vor.
   Sie sieht auf ihrer Seite dieselben sechs. Tippe nur dann auf **Stimmen überein**, wenn das so
   ist.
   ![Ein Kontakt in Tenshu Contacts: Telefonnummern, E-Mail-Adressen, Adresse, Organisation, Notiz und Schlüssel](shot:02-contact)
6. **Führe Duplikate zusammen.** Findet Contacts Personen, die wie dieselbe Person aussehen, zeigt
   die Liste eine Zeile wie **2 mögliche Duplikate**. Tippe darauf, tippe auf ein Paar und wähle,
   welcher Kontakt bleiben soll. Der andere wird mit ihm zusammengeführt, samt Schlüsseln, Karten
   und Foto. Das kann nicht rückgängig gemacht werden.
   ![Mögliche Duplikate in Tenshu Contacts, jeweils mit dem Grund für den Vorschlag](shot:04-duplicates)
7. **Leg deinen Namen fest.** Öffne **Ich** und tippe auf **Bearbeiten**. Diesen Namen sehen
   Personen, die du einlädst, und die anderen Tenshu-Apps zeigen ihn für dich an.
   ![Der Tab „You“ in Tenshu Contacts, mit dem Namen, den Personen sehen, die du einlädst](shot:06-you)
:::

::: android
1. **Installiere Tenshu Control** aus Google Play und öffne es.
   ![Willkommensbildschirm von Tenshu Control auf Android, mit den Schaltflächen „Auf diesem Gerät neu beginnen“, „Ich habe Tenshu auf einem anderen Gerät“ und „Mit Wiederherstellungsphrase wiederherstellen“](shot:control/01-welcome)
2. **Richte Control ein.** Tippe auf **Auf diesem Gerät neu beginnen** und dann auf **Weiter**.
   Tippe unter **Alles eingerichtet** auf **Sync einschalten** und gib deine E-Mail-Adresse ein,
   um den kostenlosen Speicherplan zu erhalten, damit deine Kontakte deine anderen Geräte erreichen
   können. Tippe dann auf **Weiter**. Ohne Plan bleiben deine Daten auf diesem Telefon; einen Plan
   kannst du später unter **Dienste** wählen. Schreib deine Wiederherstellungsphrase unter
   **Einstellungen** → **Kontowiederherstellung einrichten** auf und bewahre sie an einem sicheren
   Ort auf.
   ![Bildschirm „Plan wählen“ von Tenshu Control mit dem Plan Free](shot:control/10-services)
3. **Installiere Tenshu Contacts** aus Google Play und öffne es. Contacts verbindet sich von
   selbst mit Control. Wenn es **Tenshu Control einrichten** anzeigt, schließe zuerst Schritt 2
   ab.
   ![Die Kontaktliste von Tenshu Contacts, darüber eine Zeile mit möglichen Duplikaten](shot:01-contacts)
4. **Füge Kontakte hinzu oder importiere sie.** Tippe auf die Schaltfläche **+**. Wähle **Kontakt
   hinzufügen**, um einen Namen einzugeben, oder **Kontakte importieren**, um Personen von diesem
   Telefon zu übernehmen:
   - **Eine Person auswählen** übernimmt einen einzelnen Kontakt. Tenshu sieht den Rest deines
     Adressbuchs nie.
   - **.vcf-Datei importieren** liest eine vCard-Datei. Jedes Adressbuch kann eine exportieren.
   - **Ganzes Adressbuch importieren** fragt zuerst nach der Berechtigung, deine Kontakte zu
     lesen.

   Alle, die du importierst, werden als neue Kontakte hinzugefügt, auch wenn du sie schon hast.
   Wie du Duplikate zusammenführst, steht in Schritt 7.
5. **Finde jemanden.** Gib einen Namen, eine Telefonnummer, eine E-Mail-Adresse oder eine
   Organisation in **Kontakte durchsuchen** oben in der Liste ein. Tippe auf einen Kontakt, um
   seine Angaben zu sehen. Ändern kannst du sie mit den Symbolen oben: der Kontaktkarte (**Kontaktinfos
   bearbeiten**), dem Stift (**Umbenennen**) oder dem Papierkorb (**Kontakt löschen**).
   ![Tenshu Contacts sucht nach einer Organisation und findet drei Kolleginnen und Kollegen](shot:03-search)
6. **Lade jemanden ein.** Öffne **Ich** und tippe auf **Jemanden einladen**, dann noch einmal auf
   **Jemanden einladen**. Lass die Person den QR-Code scannen, oder tippe auf **Link kopieren** und
   schick ihr den Link. Der Link funktioniert nur einmal. Sie nimmt ihn in Tenshu Drive unter
   **Freigegeben** an. Danach zeigt deine Liste **„…“ hat deine Einladung angenommen**: Tippe
   darauf, lies der Person die sechs Ziffern am Telefon oder persönlich vor, setze das Häkchen bei
   **Die Codes stimmen überein**, wenn das so ist, und tippe auf **Kontakt speichern**.
   ![Ein Kontakt in Tenshu Contacts: Telefonnummern, E-Mail-Adressen, Adresse, Organisation, Notiz und Schlüssel](shot:02-contact)
7. **Führe Duplikate zusammen.** Findet Contacts Personen, die wie dieselbe Person aussehen, zeigt
   die Liste eine Zeile wie **2 mögliche Duplikate**. Tippe darauf, tippe auf ein Paar, wähle,
   welcher Kontakt bleiben soll, und tippe auf **Zusammenführen**. Der andere wird mit ihm
   zusammengeführt, samt Schlüsseln, Karten und Foto. Das kann nicht rückgängig gemacht werden.
   ![Mögliche Duplikate in Tenshu Contacts, jeweils mit dem Grund für den Vorschlag](shot:04-duplicates)
:::

## Wie Tenshu Contacts deine Kontakte privat hält {#privacy}

- **Deine Kontakte werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.** Die
  Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu
  lesen. Nur deine eigenen Geräte können deine Kontakte lesen. Einige Fakten, die der Server zum
  Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele Geräte du hast und
  wann sie synchronisieren.
  {source: docs/sync/threat-model.md | Bedrohungsmodell, Invariante I1, Assets A5 (Nutzerdaten), A24 (Kontakte und Schlüssel), A6 (Metadaten) und T-T-07}
  {source: AGENTS.md | House Rule 1: Das Relay sieht nur Chiffretext}
- **Tenshu hat kein Verzeichnis, deshalb kann niemand dich nachschlagen.** Du verbindest dich mit
  jemandem nur, wenn ihr beide eine Einladung annehmt. Eine Einladung funktioniert nur einmal, und
  der Schlüssel darin erreicht nie die Server von Tenshu. Der Server sieht aber, wann eine
  Einladung erstellt und genutzt wird, und die IP-Adresse der Person, die sie annimmt.
  {source: docs/proposals/contact-invitation-handshake.md | Kontakt-Einladung: kein Verzeichnis, Identität nur über eine gegenseitige Einladung}
  {source: docs/sync/threat-model.md | Bedrohungsmodell, Asset A25 (einmalige Einladungen), T-CI-02 und T-CI-06 (Metadaten der Einladung)}
- **Contacts hat keine Werbung und keine Analyse-Tools.**
  {source: store/contacts/privacy-label.md | Datenschutzangaben von Contacts: kein Tracking, kein Werbe- oder Analyse-SDK}
  {source: contacts-ios/Resources/PrivacyInfo.xcprivacy | Datenschutz-Manifest von Contacts für iOS: kein Tracking, nichts erhoben}
- **Ohne Speicherplan bleiben deine Kontakte auf diesem Gerät.** Deine Kontakte gehen erst an die
  Server von Tenshu, verschlüsselt, wenn du die Synchronisierung einschaltest.
  {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, Speicherkapazität: ohne Abo kein Relay-Verkehr}

::: ios
- **Contacts liest das Adressbuch auf deinem Gerät nicht.**
  {source: contacts-ios/project.yml | Kein Contacts-Framework und keine Begründung für Kontaktzugriff}
  {source: store/contacts/privacy-label.md | „Contacts liest das System-Adressbuch nicht“}
:::

::: android
- **Auf Android liest Contacts das Adressbuch deines Telefons nur, wenn du daraus importierst,
  und es kann selbst das Internet nicht nutzen.** Wählst du eine einzelne Person aus, sieht
  Contacts nur diese Person. Deine Kontakte erreichen deine anderen Geräte über Tenshu Control,
  verschlüsselt.
  {source: contacts-android/src/main/AndroidManifest.xml | Android-Manifest: Die Berechtigung INTERNET ist entfernt; READ_CONTACTS nur für den Import des ganzen Adressbuchs}
  {source: contacts-shared-ui/src/androidMain/kotlin/app/tenshu/contacts/ui/screens/importing/ContactImporter.android.kt | Der Android-Import: die Kontaktauswahl des Systems, eine .vcf-Datei oder das Adressbuch nach Rückfrage}
:::

## Häufige Probleme {#problems}

### Contacts bittet dich, Tenshu Control einzurichten

Contacts bewahrt deine Kontakte in Tenshu Control auf, deshalb muss Control zuerst auf demselben
Gerät installiert und eingerichtet sein. Installiere Tenshu Control, öffne es und wähle **Auf
diesem Gerät neu beginnen** oder, wenn du Tenshu schon nutzt, **Ich habe Tenshu auf einem anderen
Gerät** (auf dem iPhone: **Start fresh on this device** bzw. **I have Tenshu on another
device**). Öffne dann Contacts erneut.

::: ios
Sobald Control eingerichtet ist, muss es nicht geöffnet bleiben. Contacts funktioniert
eigenständig.
:::

### Contacts zeigt „Tenshu Control ist nicht erreichbar“

::: ios
Contacts zeigt, was bereits vorhanden ist, kann aber nichts Neues laden. Tippe auf
**Einrichten**, folge den Schritten in Tenshu Control und kehre dann zu Contacts zurück.
:::

::: android
Deine Kontakte erscheinen, sobald Control wieder läuft. Tippe auf **Control öffnen** und prüfe,
dass Tenshu Control installiert und eingerichtet ist. Kehre dann zu Contacts zurück.
:::

::: ios
### Contacts zeigt „Not syncing — this device has no storage plan“

(„Keine Synchronisierung – dieses Gerät hat keinen Speicherplan“.) Der Hinweis oben in Contacts
sagt, dass deine Dateien auf diesem Gerät bleiben: Auf dem iPhone und iPad steht er in jeder
Tenshu-App gleich da, und hier sind deine Kontakte gemeint. Sie sind sicher, können deine
anderen Geräte aber noch nicht erreichen. Tippe auf **Speicher verwalten**, um Tenshu Control zu
öffnen, und wähle dort einen Speicherplan (siehe unten).

### Contacts zeigt „Storage full“

(„Speicher voll“.) Der Hinweis zeigt, wie viel deines Plans belegt ist. Deine Kontakte
bleiben auf diesem Gerät und nichts wird gelöscht, aber neue Änderungen können erst wieder
synchronisiert werden, wenn du Platz schaffst oder mehr Speicher holst. Tippe auf **Speicher
verwalten**, um Tenshu Control zu öffnen, und wähle unter **Speicher** einen größeren Plan.

### Meine Kontakte bleiben auf diesem iPhone

Öffne Tenshu Control und gehe zu **Einstellungen**. Steht dort **Local only** (Nur lokal), hat
dieses Realm noch keinen Speicherplan, und deine Daten bleiben auf diesem iPhone. Öffne
**Speicher** und wähle einen Plan. Der kostenlose Plan braucht eine bestätigte E-Mail-Adresse.
Ein Einlösecode funktioniert auch ohne.
:::

::: android
### Contacts zeigt „Keine Synchronisierung – dieses Gerät hat keinen Speicherplan“

Deine Kontakte sind auf diesem Gerät sicher, können deine anderen Geräte aber noch nicht
erreichen. Tippe auf **Speicher einrichten** oder öffne Tenshu Control und wähle unter **Dienste**
einen Speicherplan. Der kostenlose Plan braucht eine bestätigte E-Mail-Adresse. Ein Einlösecode
funktioniert auch ohne.

### Contacts zeigt „Speicher voll“

Deine Kontakte bleiben auf diesem Gerät und nichts wird gelöscht, aber neue Änderungen können
nicht synchronisiert werden. Schaffe Platz oder wähle in Tenshu Control unter **Dienste** einen
größeren Plan.

### Contacts sagt, es sei neuer oder älter als Tenshu Control

Contacts und Control müssen auf demselben Gerät zueinander passende Versionen haben, damit sie
weiter synchronisieren. Aktualisiere beide über Google Play. Alles, was bereits auf dem Gerät
ist, bleibt erhalten.
:::

::: ios
### Ich kann auf meinem iPhone keinen Kontakt hinzufügen

Kontakte hinzufügen, bearbeiten und importieren geht auf dem iPhone und iPad noch nicht. Füge den
Kontakt in Tenshu Contacts auf einem Android-Telefon im selben Realm hinzu, dann erscheint er auf
deinem iPhone.
:::

### Mein anderes Gerät zeigt meine Kontakte nicht

Kontakte erreichen deine anderen Geräte erst, nachdem das Gerät, auf dem sie liegen, sie
hochgeladen hat. Prüfe der Reihe nach:

1. Beide Geräte sind im selben Realm. In Tenshu Control sollte die Geräteliste beide zeigen.
2. Das Gerät, auf dem die Kontakte liegen, hat einen Speicherplan (siehe oben).
3. Beide Geräte sind online. Öffne Contacts auf beiden und gib ihm ein paar Minuten Zeit.

### Ein Kontakt zeigt „Schlüssel geändert“

Die Person verwendet einen Schlüssel, den du nicht verifiziert hast. Das passiert, wenn sie die
App neu installiert oder ein neues Gerät einrichtet. Öffne ihren Kontakt, tippe unter
**Schlüssel** auf **Verifizieren** und vergleiche die sechs Ziffern noch einmal mit ihr, am
Telefon oder persönlich.

::: android
### Contacts zeigt „Tenshu konnte deine Kontakte nicht lesen“

Contacts darf das Adressbuch deines Telefons nur mit deiner Erlaubnis lesen. Tippe auf **+** →
**Kontakte importieren** → **Ganzes Adressbuch importieren** und erlaube den Zugriff, wenn Android
fragt. Fragt Android nicht mehr, öffne die **Einstellungen** deines Telefons → **Apps**, wähle
die App **Contacts** von Tenshu und erlaube ihr die Berechtigung für Kontakte. Oder exportiere
deine Kontakte als .vcf-Datei und nutze **.vcf-Datei importieren**; dafür braucht es keine
Berechtigung.
:::

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

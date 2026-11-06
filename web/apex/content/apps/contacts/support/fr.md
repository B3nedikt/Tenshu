---
# Tenshu Contacts support, French — a translation of en.md (store-presence-and-support-pages.md §4).
# ⚠ Machine-translated, pending native review: flip `reviewed` once a native speaker has read it.
# ⚖️ The privacy section follows the English one, which is under counsel review: any change counsel
# makes there must be carried over here.
# App labels: the apps have no French translation, so a French device shows them in English. Every
# label stays in English, in bold, with a French gloss in parentheses on first use.
app: contacts
name: Tenshu Contacts
lang: fr
reviewed: false
title: Assistance Tenshu Contacts
description: Aide pour Tenshu Contacts sur {platform} : bien démarrer, comment vos contacts restent confidentiels, réponses aux problèmes courants et comment contacter l’assistance.
platforms: ios, android
defaultPlatform: ios
---

## Premiers pas {#get-started}

Tenshu Contacts garde les personnes que vous connaissez chiffrées et synchronisées sur tous vos
appareils. Contacts fonctionne avec Tenshu Control, qui conserve vos clés et relie vos appareils.
Configurez d’abord Control.

::: ios
Sur iPhone et iPad, Contacts affiche les contacts que vous avez ajoutés dans Tenshu Contacts sur
vos autres appareils, et les personnes qui ont accepté votre invitation. Vous ne pouvez pas encore
ajouter, modifier ni importer de contact sur iPhone ou iPad.

1. **Installez Tenshu Control** depuis l’App Store, puis ouvrez-le.
   ![Écran d’accueil de Tenshu Control sur iPhone, avec les boutons « Start fresh on this device », « I have Tenshu on another device » et « Recover from a recovery phrase »](shot:control/01-welcome)
2. **Configurez Control.** Touchez **Start fresh on this device** (Repartir de zéro sur cet
   appareil), puis **Continue** (Continuer). Notez votre phrase de récupération et gardez-la en
   lieu sûr. Touchez ensuite **Turn on sync** (Activer la synchronisation) et saisissez votre
   adresse e-mail pour obtenir une offre de stockage. Sans offre, vos données restent sur cet
   iPhone. Si vous utilisez déjà Tenshu sur un autre appareil, touchez plutôt **I have Tenshu on
   another device** (J’ai Tenshu sur un autre appareil), pour que vos contacts puissent atteindre
   cet iPhone.
   ![Tenshu Control affichant « You're set up » (Tout est prêt), avec un lien « Turn on sync » au-dessus des 24 mots de la phrase de récupération](shot:control/03-recovery-phrase)
3. **Installez Tenshu Contacts** depuis l’App Store, puis ouvrez-le. Contacts trouve Control tout
   seul. S’il vous demande de configurer Tenshu Control, terminez d’abord l’étape 2. Contacts
   s’ouvre sur **Contacts**, qui affiche **No contacts yet** (Aucun contact pour l’instant)
   jusqu’à ce que vos contacts arrivent de vos autres appareils.
   ![La liste de contacts de Tenshu Contacts, avec au-dessus une ligne pour les doublons possibles](shot:01-contacts)
4. **Trouvez quelqu’un.** Saisissez un nom, un numéro de téléphone, une adresse e-mail ou une
   organisation dans le champ de recherche en haut de la liste. Si vous ne le voyez pas, tirez la
   liste vers le bas. Touchez un contact pour voir ses numéros de téléphone, ses adresses e-mail,
   son organisation et ses notes.
   ![Tenshu Contacts recherche par organisation et trouve trois collègues](shot:03-search)
5. **Vérifiez une personne avec qui vous êtes connecté.** Ouvrez son contact et, sous **Keys**
   (Clés), touchez **Verify** (Vérifier). Lisez-lui les six chiffres par téléphone ou en personne.
   Elle voit les six mêmes de son côté. Touchez **They match** (Ils correspondent) seulement si
   c’est le cas.
   ![Un contact dans Tenshu Contacts : numéros de téléphone, adresses e-mail, adresse, organisation, note et clés](shot:02-contact)
6. **Fusionnez les doublons.** Si Contacts trouve des personnes qui semblent être la même, la
   liste affiche une ligne comme **2 possible duplicates** (2 doublons possibles). Touchez-la,
   touchez une paire et choisissez celle à conserver. L’autre y est fusionnée, avec ses clés, ses
   fiches et sa photo. Cette action est irréversible.
   ![Les doublons possibles dans Tenshu Contacts, chacun avec la raison de la suggestion](shot:04-duplicates)
7. **Indiquez votre nom.** Ouvrez **You** (Vous) et touchez **Edit** (Modifier). C’est le nom que
   voient les personnes que vous invitez, et celui que les autres apps Tenshu affichent pour vous.
   ![L’onglet « You » de Tenshu Contacts, avec le nom que voient les personnes que vous invitez](shot:06-you)
:::

::: android
1. **Installez Tenshu Control** depuis Google Play, puis ouvrez-le.
   ![Écran d’accueil de Tenshu Control sur Android, avec les boutons « Start fresh on this device », « I have Tenshu on another device » et « Recover from a recovery phrase »](shot:control/01-welcome)
2. **Configurez Control.** Touchez **Start fresh on this device** (Repartir de zéro sur cet
   appareil), puis **Continue** (Continuer). Sur **You're set up** (Tout est prêt), touchez **Turn
   on sync** (Activer la synchronisation) et saisissez votre adresse e-mail pour obtenir l’offre de
   stockage gratuite, pour que vos contacts puissent atteindre vos autres appareils. Touchez ensuite
   **Continue**. Sans offre, vos données restent sur ce téléphone, et vous pourrez en choisir une plus
   tard dans **Services**. Notez votre phrase de récupération sous **Settings** (Paramètres) →
   **Set up account recovery** (Configurer la récupération du compte), et gardez-la en lieu sûr.
   ![Écran « Pick a plan » (Choisir une offre) de Tenshu Control avec l’offre Free](shot:control/10-services)
3. **Installez Tenshu Contacts** depuis Google Play, puis ouvrez-le. Contacts se connecte à
   Control tout seul. S’il affiche **Set up Tenshu Control** (Configurer Tenshu Control), terminez
   d’abord l’étape 2.
   ![La liste de contacts de Tenshu Contacts, avec au-dessus une ligne pour les doublons possibles](shot:01-contacts)
4. **Ajoutez ou importez vos contacts.** Touchez le bouton **+**. Choisissez **Add a contact**
   (Ajouter un contact) pour saisir un nom, ou **Import contacts** (Importer des contacts) pour
   reprendre des personnes de ce téléphone :
   - **Pick one person** (Choisir une personne) choisit un seul contact. Tenshu ne voit jamais le
     reste de votre carnet d’adresses.
   - **Import a .vcf file** (Importer un fichier .vcf) lit un fichier vCard. Tout carnet
     d’adresses peut en exporter un.
   - **Import your whole address book** (Importer tout votre carnet d’adresses) demande d’abord
     l’autorisation de lire vos contacts.

   Chaque personne importée est ajoutée comme nouveau contact, même si vous l’avez déjà. Pour
   fusionner les doublons, voir l’étape 7.
5. **Trouvez quelqu’un.** Saisissez un nom, un numéro de téléphone, une adresse e-mail ou une
   organisation dans **Search your contacts** (Rechercher vos contacts) en haut de la liste.
   Touchez un contact pour voir ses informations. Pour les modifier, utilisez les icônes en haut : la fiche de contact
   (**Edit contact info**, Modifier les informations), le crayon (**Rename**, Renommer) ou la
   corbeille (**Delete contact**, Supprimer le contact).
   ![Tenshu Contacts recherche par organisation et trouve trois collègues](shot:03-search)
6. **Invitez quelqu’un.** Ouvrez **You** (Vous) et touchez **Invite someone** (Inviter quelqu’un),
   puis à nouveau **Invite someone**. Faites-lui scanner le code QR, ou touchez **Copy link**
   (Copier le lien) et envoyez-le-lui. Le lien ne fonctionne qu’une fois. La personne l’accepte
   dans Tenshu Drive, sous **Shared** (Partagés). Ensuite, votre liste affiche **"…" accepted your
   invitation** (« … » a accepté votre invitation) : touchez cette ligne, lisez-lui les six
   chiffres par téléphone ou en personne, cochez **The codes match** (Les codes correspondent)
   s’ils correspondent, puis touchez **Save contact** (Enregistrer le contact).
   ![Un contact dans Tenshu Contacts : numéros de téléphone, adresses e-mail, adresse, organisation, note et clés](shot:02-contact)
7. **Fusionnez les doublons.** Si Contacts trouve des personnes qui semblent être la même, la
   liste affiche une ligne comme **2 possible duplicates** (2 doublons possibles). Touchez-la,
   touchez une paire, choisissez celle à conserver et touchez **Combine** (Fusionner). L’autre y
   est fusionnée, avec ses clés, ses fiches et sa photo. Cette action est irréversible.
   ![Les doublons possibles dans Tenshu Contacts, chacun avec la raison de la suggestion](shot:04-duplicates)
:::

## Comment Tenshu Contacts protège vos contacts {#privacy}

- **Vos contacts sont chiffrés sur votre appareil avant d’être envoyés.** Les serveurs de Tenshu
  ne stockent que des données chiffrées, et ils n’ont pas les clés pour les lire. Seuls vos
  propres appareils peuvent lire vos contacts. Le serveur voit tout de même certains faits dont il
  a besoin pour synchroniser : la quantité de données que vous stockez, le nombre de vos appareils
  et le moment où ils se synchronisent.
  {source: docs/sync/threat-model.md | Modèle de menace, invariant I1, actifs A5 (données utilisateur), A24 (contacts et clés), A6 (métadonnées) et T-T-07}
  {source: AGENTS.md | House Rule 1 : le relais ne voit que du texte chiffré}
- **Tenshu n’a pas d’annuaire : personne ne peut vous rechercher.** Vous ne vous connectez avec
  quelqu’un que si vous acceptez tous les deux une invitation. Une invitation ne fonctionne qu’une
  fois, et la clé qu’elle contient n’atteint jamais les serveurs de Tenshu. Le serveur voit
  cependant quand une invitation est créée et utilisée, ainsi que l’adresse IP de la personne qui
  l’accepte.
  {source: docs/proposals/contact-invitation-handshake.md | Invitation de contact : pas d’annuaire, identité uniquement par invitation mutuelle}
  {source: docs/sync/threat-model.md | Modèle de menace, actif A25 (invitations à usage unique), T-CI-02 et T-CI-06 (métadonnées de l’invitation)}
- **Contacts n’a ni publicité ni outil d’analyse.**
  {source: store/contacts/privacy-label.md | Étiquette de confidentialité de Contacts : aucun suivi, aucun SDK de publicité ou d’analyse}
  {source: contacts-ios/Resources/PrivacyInfo.xcprivacy | Manifeste de confidentialité iOS de Contacts : aucun suivi, rien de collecté}
- **Sans offre de stockage, vos contacts restent sur cet appareil.** Vos contacts ne partent vers
  les serveurs de Tenshu, chiffrés, qu’une fois la synchronisation activée.
  {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, capacité de stockage : sans abonnement, aucun trafic vers le relais}

::: ios
- **Contacts ne lit pas le carnet d’adresses de votre appareil.**
  {source: contacts-ios/project.yml | Pas de framework Contacts ni de justification d’accès aux contacts}
  {source: store/contacts/privacy-label.md | « Contacts ne lit pas le carnet d’adresses du système »}
:::

::: android
- **Sur Android, Contacts ne lit le carnet d’adresses de votre téléphone que lorsque vous en
  importez, et il ne peut pas utiliser Internet lui-même.** Si vous choisissez une seule personne,
  Contacts ne voit que celle-ci. Vos contacts atteignent vos autres appareils via Tenshu Control,
  chiffrés.
  {source: contacts-android/src/main/AndroidManifest.xml | Manifeste Android : l’autorisation INTERNET est retirée ; READ_CONTACTS uniquement pour l’import du carnet d’adresses complet}
  {source: contacts-shared-ui/src/androidMain/kotlin/app/tenshu/contacts/ui/screens/importing/ContactImporter.android.kt | L’import Android : le sélecteur de contacts du système, un fichier .vcf ou le carnet d’adresses après autorisation}
:::

## Problèmes courants {#problems}

### Contacts vous demande de configurer Tenshu Control

Contacts conserve vos contacts dans Tenshu Control : Control doit donc d’abord être installé et
configuré sur le même appareil. Installez Tenshu Control, ouvrez-le et choisissez **Start fresh on
this device** (Repartir de zéro sur cet appareil), ou **I have Tenshu on another device** (J’ai
Tenshu sur un autre appareil) si vous utilisez déjà Tenshu. Ouvrez ensuite Contacts à nouveau.

::: ios
Une fois Control configuré, il n’a pas besoin de rester ouvert. Contacts fonctionne de façon
autonome.
:::

### Contacts affiche « Can't reach Tenshu Control »

::: ios
(« Impossible de joindre Tenshu Control ».) Contacts affiche ce qu’il a déjà, mais ne peut rien
charger de nouveau. Touchez **Set up** (Configurer) et suivez les étapes dans Tenshu Control, puis
revenez dans Contacts.
:::

::: android
(« Impossible de joindre Tenshu Control ».) Vos contacts apparaîtront dès que Control fonctionnera
à nouveau. Touchez **Open Control** (Ouvrir Control) et vérifiez que Tenshu Control est installé
et configuré, puis revenez dans Contacts.
:::

::: ios
### Contacts affiche « Not syncing — this device has no storage plan »

(« Pas de synchronisation — cet appareil n’a pas d’offre de stockage ».) Le bandeau en haut de
Contacts indique que vos fichiers restent sur cet appareil : sur iPhone et iPad, il est formulé de
la même façon dans toutes les apps Tenshu, et ici il s’agit de vos contacts. Ils sont en
sécurité, mais ne peuvent pas encore atteindre vos autres appareils. Touchez **Manage storage**
(Gérer le stockage) pour ouvrir Tenshu Control, puis choisissez une offre de stockage (voir
ci-dessous).

### Contacts affiche « Storage full »

(« Stockage plein ».) Le bandeau indique la part de votre offre déjà utilisée. Vos contacts
restent sur cet appareil et rien n’est supprimé, mais les nouvelles modifications ne peuvent pas
être synchronisées tant que vous n’avez pas libéré de l’espace ou obtenu plus de stockage.
Touchez **Manage storage** pour ouvrir Tenshu Control, et choisissez une offre plus grande sous
**Storage** (Stockage).

### Mes contacts restent sur cet iPhone

Ouvrez Tenshu Control et allez dans **Settings** (Réglages). S’il indique **Local only** (Local
uniquement), ce realm n’a pas encore d’offre de stockage : vos données restent donc sur cet
iPhone. Ouvrez **Storage** (Stockage) et choisissez une offre. L’offre gratuite demande une
adresse e-mail vérifiée. Un code d’activation fonctionne sans.
:::

::: android
### Contacts affiche « Not syncing — this device has no storage plan »

(« Pas de synchronisation — cet appareil n’a pas d’offre de stockage ».) Vos contacts sont en
sécurité sur cet appareil, mais ne peuvent pas encore atteindre vos autres appareils. Touchez
**Set up storage** (Configurer le stockage), ou ouvrez Tenshu Control et choisissez une offre de
stockage dans **Services**. L’offre gratuite demande une adresse e-mail vérifiée. Un code
d’activation fonctionne sans.

### Contacts affiche « Storage full »

(« Stockage plein ».) Vos contacts restent sur cet appareil et rien n’est supprimé, mais les
nouvelles modifications ne peuvent pas être synchronisées. Libérez de l’espace, ou choisissez une
offre plus grande dans Tenshu Control, sous **Services**.

### Contacts indique qu’il est plus récent ou plus ancien que Tenshu Control

Contacts et Control doivent avoir des versions compatibles sur le même appareil pour continuer à
se synchroniser. Mettez les deux à jour depuis Google Play. Tout ce qui se trouve déjà sur
l’appareil reste en sécurité.
:::

::: ios
### Je ne peux pas ajouter de contact sur mon iPhone

L’ajout, la modification et l’import de contacts ne sont pas encore disponibles sur iPhone et
iPad. Ajoutez le contact dans Tenshu Contacts sur un téléphone Android du même realm : il
apparaîtra sur votre iPhone.
:::

### Mon autre appareil n’affiche pas mes contacts

Les contacts n’atteignent vos autres appareils qu’une fois que l’appareil qui les contient les a
envoyés. Vérifiez ces points, dans l’ordre :

1. Les deux appareils sont dans le même realm. Dans Tenshu Control, la liste des appareils doit
   afficher les deux.
2. L’appareil qui contient les contacts a une offre de stockage (voir ci-dessus).
3. Les deux appareils sont en ligne. Ouvrez Contacts sur les deux et laissez-lui quelques minutes.

### Un contact affiche « Key changed »

(« Clé modifiée ».) La personne utilise une clé que vous n’avez pas vérifiée. Cela arrive après
une réinstallation ou la configuration d’un nouvel appareil. Ouvrez son contact, touchez
**Verify** sous **Keys**, et comparez à nouveau les six chiffres avec elle, par téléphone ou en
personne.

::: android
### Contacts affiche « Tenshu could not read your contacts »

(« Tenshu n’a pas pu lire vos contacts ».) Contacts ne peut lire le carnet d’adresses de votre
téléphone qu’avec votre autorisation. Touchez **+** → **Import contacts** → **Import your whole
address book** et autorisez l’accès quand Android le demande. Si Android ne le demande plus,
ouvrez les **Paramètres** de votre téléphone → **Applis**, choisissez l’app **Contacts** de
Tenshu et autorisez-lui l’accès aux contacts. Vous pouvez aussi exporter vos contacts dans un
fichier .vcf et utiliser **Import a .vcf file**, qui ne demande aucune autorisation.
:::

## Configuration requise {#requirements}

::: ios
- iOS 16 ou version ultérieure, sur iPhone ou iPad.
- Tenshu Control, installé et configuré sur le même appareil.
:::

::: android
- Android 7.0 ou version ultérieure.
- Tenshu Control, installé et configuré sur le même appareil.
:::

- Pour synchroniser entre appareils : une offre de stockage. L’offre gratuite comprend 5 Go.

## Contact {#contact}

Écrivez à [support@tenshu.app](mailto:support@tenshu.app). Indiquez-nous l’appli et l’appareil
que vous utilisez, et ce que vous voyez à l’écran.

**L’assistance ne vous demandera jamais votre phrase de récupération.** Personne chez Tenshu n’en
a besoin, jamais. Quiconque la possède peut tout ouvrir dans votre compte.

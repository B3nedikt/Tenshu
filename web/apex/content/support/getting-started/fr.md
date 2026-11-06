---
# Getting started with Tenshu, French — a translation of en.md (store-presence-and-support-pages.md §4.7).
# ⚠ Machine-translated, pending native review: flip `reviewed` once a native speaker has read it.
# Reuses the wording of the French Control and Drive pages and locales/fr/landing.json. The apps
# have no French translation, so app labels stay in English, in bold, with a French gloss.
lang: fr
reviewed: false
title: Premiers pas avec Tenshu
description: Comment commencer avec Tenshu : configurez d’abord Tenshu Control, puis ajoutez des applis comme Tenshu Drive et vos autres appareils. Comment vos données restent privées, et où trouver de l’aide.
---

## Ce qu’est Tenshu {#what-is-tenshu}

Tenshu garde vos données chiffrées et synchronisées entre vos appareils. Vos fichiers, vos
photos, vos appareils : tout cela vit dans un espace privé que Tenshu appelle votre *realm*.
Votre realm se synchronise entre vos appareils, mais seuls vos appareils détiennent les clés.

## Configurez d’abord Tenshu Control {#control}

Tout commence avec Tenshu Control. L’appli crée votre realm, l’espace privé que partagent vos
applis Tenshu. Elle conserve vos clés et relie vos appareils. Les autres applis Tenshu, comme
Tenshu Drive, ont besoin que Control soit configuré sur le même appareil.

- **Installez Tenshu Control**, ouvrez-le et touchez **Start fresh on this device** (Repartir de
   zéro sur cet appareil). Notez votre phrase de récupération et gardez-la en lieu sûr, hors
   ligne. Activez ensuite la synchronisation pour obtenir votre offre de stockage. D’ici là, tout
   reste sur cet appareil.
   ![Écran d’accueil de Tenshu Control, avec les boutons « Start fresh on this device », « I have Tenshu on another device » et « Recover from a recovery phrase »](shot:control/01-welcome)

La page d’assistance de Control détaille chaque étape, avec des captures d’écran, pour
[iPhone et iPad](/apps/control/support/ios/fr), [Android](/apps/control/support/android/fr),
[Mac](/apps/control/support/macos/fr) et le [Web](/apps/control/support/web/fr).

## Ajoutez des applis et vos autres appareils {#apps-and-devices}

- **Ajouter des applis.** Installez les applis Tenshu de votre choix sur le même appareil, comme
  Tenshu Drive. Drive trouve Control tout seul. La page d’assistance de Drive montre comment
  envoyer votre premier fichier, pour [iPhone et iPad](/apps/drive/support/ios/fr),
  [Android](/apps/drive/support/android/fr), [Mac](/apps/drive/support/macos/fr) et le
  [Web](/apps/drive/support/web/fr).
- **Ajouter vos autres appareils.** Installez-y Tenshu Control et choisissez **I have Tenshu on
  another device** (J’ai Tenshu sur un autre appareil). Un appareil affiche un code et l’autre le
  scanne. Vérifiez que les deux appareils affichent le même code de confirmation.

## Comment vos données restent privées {#privacy}

- **Les serveurs de Tenshu ne peuvent pas lire vos données.** Ils ne transmettent et ne stockent
  que des données chiffrées, et seuls vos appareils détiennent les clés. {source: docs/sync/threat-model.md | Modèle de menace, invariant I1 et actif A5 (données utilisateur)}
  {source: AGENTS.md | House Rule 1 : le relais ne voit que du texte chiffré}
- **Ils voient toutefois certains faits dont ils ont besoin pour fonctionner :** la quantité de
  données que vous stockez, le nombre de vos appareils et le moment où ils se synchronisent. {source: docs/sync/threat-model.md | Modèle de menace, actif A6 (métadonnées) et T-T-07}
- **Votre phrase de récupération n’appartient qu’à vous.** Personne d’autre n’en détient de copie :
  personne, pas même nous, ne peut restaurer votre realm sans elle. {source: docs/sync/threat-model.md | Modèle de menace, actif A9 (code de récupération), invariant I7 et T-RC-01}

## Obtenir de l’aide {#help}

Écrivez à [support@tenshu.app](mailto:support@tenshu.app). Indiquez-nous l’appli et l’appareil
que vous utilisez, et ce que vous voyez à l’écran.

**L’assistance ne vous demandera jamais votre phrase de récupération.** Personne chez Tenshu n’en
a besoin, jamais. Quiconque la possède peut tout ouvrir dans votre compte.

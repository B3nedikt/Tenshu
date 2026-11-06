# Tenshu Mia — the source of every privacy sentence in the store descriptions

Design §4.4: a claim with no source is not published. `scripts/store-listing.py check` fails when a
sentence under the Play description's `HOW … PRIVATE` heading is not quoted here (`> ` line, verbatim)
with at least one `- source:` whose path exists. The Google Play listing (store/mia/play/) and the App
Store listing (store/mia/<locale>/, iOS) share a claim where the sentence is the same; the App Store's
own sentences say what the iOS app does — no voice input, the questions answered in Mia's own
process (route 1, not by Control), and no data-source choice in its Settings yet.

⚠ Not claimed: "Mia has no internet access". Mia's source manifest strips INTERNET as every other
client does, and `scripts/lib/check_client_no_internet.py` fails the Android smoke gate if a
dependency brings it back — so the claim is sourceable (mia-android/src/main/AndroidManifest.xml),
should the listing want it.

> Your questions are answered on your phone, by Tenshu Control, and are not sent to a server or an AI service.
- source: mia-service/src/commonMain/kotlin/org/tenshu/mia/plan/QueryPlanner.kt — a deterministic grammar, no model
- source: docs/proposals/mia-query-layer.md — the planner runs in Control over the realm's local store

> Voice input uses your phone's on-device speech recognition, and no audio is sent anywhere.
- source: mia-android/src/main/java/app/tenshu/mia/android/AndroidVoiceInput.kt — `SpeechRecognizer.createOnDeviceSpeechRecognizer`, never an online recogniser

> Your chats are not saved: they are gone when you close Mia.
- source: mia-shared-logic/src/commonMain/kotlin/app/tenshu/mia/data/TenshuConversationRepository.kt — history is in memory only
- source: tenshu-sdk-ios-mia/src/iosMain/kotlin/org/tenshu/sdk/ios/MiaLocalRuntime.kt — iOS installs the same in-memory repository

> You choose which of your Tenshu data Mia can read, in Settings.
- source: mia-service/src/commonMain/kotlin/org/tenshu/mia/MiaSource.kt — one grant per source

### App Store (iOS) only

> Your questions are answered on your iPhone or iPad, and are not sent to a server or an AI service.
- source: mia-service/src/commonMain/kotlin/org/tenshu/mia/plan/QueryPlanner.kt — a deterministic grammar, no model
- source: tenshu-sdk-ios-mia/src/iosMain/kotlin/org/tenshu/sdk/ios/MiaLocalRuntime.kt — route 1: planner, validator and reads run in Mia's own process
- source: docs/proposals/mia-query-layer.md — MQ-D2, in-process on iOS route 1

> Mia has no voice input and does not use the microphone.
- source: mia-ios/project.yml — no NSMicrophoneUsageDescription or NSSpeechRecognitionUsageDescription, so iOS refuses the microphone
- source: scripts/ios-archive.sh — `allowed_sensitive_for mia`: AVFoundation only, no Speech framework

## Deutsch (de-DE)

The German listing's privacy sentences (`play/de-DE/`). Each names the English claim it renders in
its `- translates:` line and cites that claim's sources. `check` holds the mechanics: every German
claim names an English claim that exists, the section renders the English Play listing's claims one
for one and in order (a dropped caveat fails), and a changed English claim flags its translation.
Whether the German says the same thing is review's job.

> Tenshu Control beantwortet deine Fragen auf deinem Handy; sie gehen an keinen Server und keinen KI-Dienst.
- translates: "Your questions are answered on your phone, by Tenshu Control, and are not sent to a server or an AI service."
- source: mia-service/src/commonMain/kotlin/org/tenshu/mia/plan/QueryPlanner.kt — a deterministic grammar, no model
- source: docs/proposals/mia-query-layer.md — the planner runs in Control over the realm's local store

> Die Spracheingabe nutzt die Spracherkennung auf deinem Handy, und es wird kein Audio irgendwohin gesendet.
- translates: "Voice input uses your phone's on-device speech recognition, and no audio is sent anywhere."
- source: mia-android/src/main/java/app/tenshu/mia/android/AndroidVoiceInput.kt — `SpeechRecognizer.createOnDeviceSpeechRecognizer`, never an online recogniser

> Deine Chats werden nicht gespeichert: Sie sind weg, wenn du Mia schließt.
- translates: "Your chats are not saved: they are gone when you close Mia."
- source: mia-shared-logic/src/commonMain/kotlin/app/tenshu/mia/data/TenshuConversationRepository.kt — history is in memory only

> Du wählst in den Einstellungen, welche deiner Tenshu-Daten Mia lesen darf.
- translates: "You choose which of your Tenshu data Mia can read, in Settings."
- source: mia-service/src/commonMain/kotlin/org/tenshu/mia/MiaSource.kt — one grant per source

> Deine Fragen werden auf deinem iPhone oder iPad beantwortet und gehen an keinen Server und keinen KI-Dienst.
- translates: "Your questions are answered on your iPhone or iPad, and are not sent to a server or an AI service."
- source: mia-service/src/commonMain/kotlin/org/tenshu/mia/plan/QueryPlanner.kt — a deterministic grammar, no model
- source: tenshu-sdk-ios-mia/src/iosMain/kotlin/org/tenshu/sdk/ios/MiaLocalRuntime.kt — route 1: planner, validator and reads run in Mia's own process

> Mia hat keine Spracheingabe und nutzt das Mikrofon nicht.
- translates: "Mia has no voice input and does not use the microphone."
- source: mia-ios/project.yml — no NSMicrophoneUsageDescription or NSSpeechRecognitionUsageDescription

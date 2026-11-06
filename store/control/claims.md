# Tenshu Control — the source of every privacy sentence in the store description

Design §4.4: a claim with no source is not published. `scripts/store-listing.py check` fails when a
sentence under the description's `HOW … PRIVATE` heading is not quoted here (`> ` line, verbatim) with
at least one `- source:` whose path exists. The wording follows the reviewed support page
(`web/apex/content/apps/control/support/en.md`, "How Tenshu Control keeps your data private"), so the
two make the same promise. ⚠ A threat-model amendment that weakens one of these has to change the
listing too (House Rule 2) — it is a public promise.

> Your keys are made on your devices and never leave them unencrypted.
- source: docs/sync/threat-model.md — §5 assets A1 (master identity key) and A4 (device keys); invariant I1
- source: docs/whitepaper.adoc — adding a device

> When you add a device, your existing device encrypts the keys for the new one before they pass through our server.
- source: docs/whitepaper.adoc — adding a device
- source: docs/sync/threat-model.md — A1/A4, pairing (the relay carries sealed key material only)

> Tenshu's servers cannot read your data.
- source: docs/sync/threat-model.md — invariant I1 (confidentiality)
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only

> They pass on and store only encrypted data.
- source: AGENTS.md — House Rule 1
- source: docs/sync/threat-model.md — invariant I1

> They do see some facts they need to work: how much you store, how many devices you have, and when they sync.
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> Your recovery phrase is yours alone.
- source: docs/sync/threat-model.md — asset A9 (recovery code)

> Nobody else holds a copy, so nobody, including us, can restore your realm without it.
- source: docs/sync/threat-model.md — asset A9, invariant I7 (recoverability needs the code) and T-RC-01

> Anyone who has it can open everything, so keep it offline.
- source: docs/sync/threat-model.md — asset A9: the code re-derives A1, the root of the realm

> You can use Tenshu without an account.
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ a fully local app

> On one device, Tenshu works with no account and sends nothing to our servers.
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic
- source: docs/proposals/commerce-accounts-and-sessions.md — commerce is reached only to sign in or redeem

## Deutsch (de-DE)

The German listings' privacy sentences: Google Play's (`play/de-DE/`) and the App Store's (`de-DE/`),
one claim serving both where the sentence is the same. Each names the English claim it renders in
its `- translates:` line and cites that claim's sources. `check` holds the mechanics: every German
claim names an English claim that exists, the Play section renders the English Play listing's claims
one for one and in order (a dropped caveat fails), and a changed English claim flags its translation.
The App Store pair (`de-DE/` against `en-US/`) is held the same way: one for one and in order.
Whether the German says the same thing is review's job. The wording follows the German support page
(`web/apex/content/apps/control/support/de.md`) where it makes the same claim.

> Deine Schlüssel entstehen auf deinen Geräten und verlassen sie nie unverschlüsselt.
- translates: "Your keys are made on your devices and never leave them unencrypted."
- source: docs/sync/threat-model.md — §5 assets A1 (master identity key) and A4 (device keys); invariant I1
- source: docs/whitepaper.adoc — adding a device

> Wenn du ein Gerät hinzufügst, verschlüsselt dein vorhandenes Gerät die Schlüssel für das neue, bevor sie unseren Server passieren.
- translates: "When you add a device, your existing device encrypts the keys for the new one before they pass through our server."
- source: docs/whitepaper.adoc — adding a device
- source: docs/sync/threat-model.md — A1/A4, pairing (the relay carries sealed key material only)

> Die Server von Tenshu können deine Daten nicht lesen.
- translates: "Tenshu's servers cannot read your data."
- source: docs/sync/threat-model.md — invariant I1 (confidentiality)
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only

> Sie leiten nur verschlüsselte Daten weiter und speichern nur solche.
- translates: "They pass on and store only encrypted data."
- source: AGENTS.md — House Rule 1
- source: docs/sync/threat-model.md — invariant I1

> Einige Fakten, die sie zum Arbeiten brauchen, sehen sie aber: wie viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren.
- translates: "They do see some facts they need to work: how much you store, how many devices you have, and when they sync."
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> Deine Wiederherstellungsphrase gehört nur dir.
- translates: "Your recovery phrase is yours alone."
- source: docs/sync/threat-model.md — asset A9 (recovery code)

> Niemand sonst hat eine Kopie, deshalb kann niemand, auch wir nicht, dein Realm ohne sie wiederherstellen.
- translates: "Nobody else holds a copy, so nobody, including us, can restore your realm without it."
- source: docs/sync/threat-model.md — asset A9, invariant I7 (recoverability needs the code) and T-RC-01

> Wer sie hat, kann alles öffnen, also bewahre sie offline auf.
- translates: "Anyone who has it can open everything, so keep it offline."
- source: docs/sync/threat-model.md — asset A9: the code re-derives A1, the root of the realm

> Du kannst Tenshu ohne Konto nutzen.
- translates: "You can use Tenshu without an account."
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ a fully local app

> Auf einem einzelnen Gerät funktioniert Tenshu ohne Konto und sendet nichts an unsere Server.
- translates: "On one device, Tenshu works with no account and sends nothing to our servers."
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic
- source: docs/proposals/commerce-accounts-and-sessions.md — commerce is reached only to sign in or redeem

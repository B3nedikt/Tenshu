// The terms of use (Nutzungsbedingungen), `/legal/terms/<lang>`: built by the support-page generator
// (generate.ts buildLegalPages) into static pages with the landing page's header and footer, beside
// the imprint (imprint.ts) and the privacy policy (privacy.ts), and linked from every page's footer.
// Caddy serves them as files before the SPA loads, so the pre-launch curtain never covers them
// (src/lib/alwaysPublicPaths.ts lists only SPA routes); `/legal/terms` 302s by Accept-Language.
//
// ⚖️ Written 2026-10-28 by the maintainer acting as counsel (no outside lawyer yet). German is the
// binding text; English and French are translations and say so at the top. All three are marked
// `reviewed: false`: no native speaker has read them yet, the German included. What a real lawyer
// should look at before paid launch is listed in docs/policy/acceptable-use-and-limits.md
// ("Terms of use — open for the pre-paid-launch review"); keep those notes out of this page.
//
// The rules come from the policy, never invented here:
//   - storage, quota, nothing deleted for lapse / over quota / inactivity:
//     docs/policy/acceptable-use-and-limits.md §1, §4.2
//   - transfer limits: §2 (defaults not yet enforced — the text reserves them, quotes no figure)
//   - notice and action, content-blind enforcement: §3, §6 (no report form exists: support@)
//   - account deletion, 30-day grace, sign-in again keeps the data, per-file deletion: §4.3, §4.4,
//     commerce-accounts-and-sessions.md §9.7 (Control "Services → Delete account", /account/delete)
//   - plans, prices, monthly renewal, cancel/forfeit/no pro-rata: §5.1–§5.3,
//     commerce-accounts-and-sessions.md §2.1 (Basic 50 GiB €2.99, Premium 2 TiB €9.99, Free 5 GiB)
//   - rails: commerce-store-link-out.md §0 (D3–D6): Google / Apple sell store builds, Stripe sells
//     web, desktop and non-Play APKs; we can stop Stripe and Play, never Apple
//   - withdrawal: §5.4. Cloud storage is a digital SERVICE (§ 327 Abs. 2 BGB), so the checkout tick
//     is the request to start early (§ 356 Abs. 4, § 357a Abs. 2 BGB) and a withdrawal inside 14 days
//     owes a pro-rata refund, not nothing. Both checkout screens say so and link `#withdrawal`.
//     The Widerrufsbelehrung is the statutory model (Anlage 1 zu Art. 246a § 1 EGBGB) in its own
//     "Sie" form: rewording it would leave the model. ⚠ The model's protection (Art. 246a § 1 Abs. 2
//     S. 2 EGBGB) also needs it handed over correctly and in TEXT FORM — this page alone is not
//     that; the § 312f contract confirmation e-mail must carry it (owed, policy "Terms of use").
//   - contract formation, languages, storage of the text (§ 312i BGB, Art. 246c EGBGB) and
//     platforms (Art. 246a § 1 Abs. 1 Nr. 14/15): the "contract" section
//   - DSA Art. 11/12 contact point, Art. 16 notice contents, Art. 17 statement of reasons: the
//     "acceptable-use" section. ⚠ No admin tool revokes a share by id yet: never promise "at once".
//   - ordinary termination of FREE accounts by us, 2 months' notice: maintainer-as-counsel default
//     (policy "Terms of use")
//   - minimum age 16; under 18 a guardian's consent, for a paid plan in every case: §7
//   - operator, address, mails: privacy.ts (the same constants)
//
// When the text changes in substance: bump TERMS_DATE, and move CommercePublisherClient.TOS_VERSION
// and web/apex commerceApi.ts TOS_VERSION with it (commerceApi.test.ts and
// TermsVersionParityTest pin both to TERMS_DATE). There is no re-acceptance flow yet: a new
// version is recorded only when a user next signs in.
//
// ⚠ Pure: imports only other pure support-page modules. Plain Node runs it for the support-page build.
import type { LegalPage } from './imprint.ts'
import { licensesUrl } from './licenses.ts'
import { COMPANY_MAIL, SUPPORT_MAIL, address, privacyUrl } from './privacy.ts'

/** The date this version took effect, ISO. Shown at the top of every language; = TOS_VERSION. */
export const TERMS_DATE = '2026-10-28'

const ACCOUNT_DELETION = '<a href="https://tenshu.app/account/delete">tenshu.app/account/delete</a>'

const link = (href: string, text: string) => `<a href="${href}">${text}</a>`

const de: LegalPage = {
  title: 'Nutzungsbedingungen',
  description: 'Die Nutzungsbedingungen von Tenshu: Vertrag, Konto, Pläne und Zahlung, Kündigung und Widerruf, Speicherplatz, Regeln für die Nutzung und Haftung.',
  reviewed: false,
  html: [
    `<p><strong>Stand: 28. Oktober 2026</strong> (Version ${TERMS_DATE})</p>`,
    `<p>Diese Bedingungen gelten für die Tenshu-Apps (Control, Drive, Photos, Fit, Timeline, Maps, Calendar, Contacts, Home, Mia), die Website tenshu.app und die Server, mit denen die Apps arbeiten. Wie wir mit deinen Daten umgehen, steht in der ${link(privacyUrl('de'), 'Datenschutzerklärung')}.</p>`,

    '<h2 id="who">Wer wir sind</h2>',
    '<p>Tenshu wird betrieben von:</p>',
    address('Deutschland'),
    `<p>Vertreten durch den Geschäftsführer Benedikt Löbbecke. E-Mail: ${SUPPORT_MAIL} oder ${COMPANY_MAIL}. Weitere Angaben findest du im ${link('/legal/imprint/de', 'Impressum')}.</p>`,

    '<h2 id="service">Was Tenshu ist</h2>',
    '<ul>',
    '<li>Tenshu ist eine Reihe von Apps, die deine Daten auf deinen Geräten verschlüsseln und zwischen ihnen synchronisieren (Ende-zu-Ende-Verschlüsselung). Deine Inhalte speichern und übertragen unsere Server nur verschlüsselt; die Schlüssel haben nur deine Geräte. <strong>Wir können deine Inhalte nicht lesen.</strong></li>',
    '<li>Auf einem einzelnen Gerät kannst du die Apps ohne Konto nutzen. Für die Synchronisation zwischen Geräten, das Speichern auf unseren Servern und das Teilen brauchst du ein Konto.</li>',
    '<li>Jedes Konto hat den kostenlosen Plan mit 5 GB Speicherplatz. Mehr Speicherplatz bekommst du mit einem kostenpflichtigen Plan (siehe „Pläne, Preise und Zahlung“).</li>',
    `<li>Du darfst die Apps für deine eigenen Zwecke nutzen. Für enthaltene Software Dritter gelten deren ${link(licensesUrl('de'), 'Lizenzen')}.</li>`,
    '<li>Deine Inhalte bleiben deine. Du räumst uns nur die Rechte ein, die wir brauchen, um sie verschlüsselt zu speichern, zu übertragen und – wenn du einen Freigabe-Link erstellst – an dessen Empfänger auszuliefern.</li>',
    '</ul>',

    '<h2 id="contract">Vertragsschluss</h2>',
    '<ul>',
    '<li><strong>Kostenloser Plan:</strong> Du gibst in der App oder im Web deine E-Mail-Adresse ein, forderst einen Code per E-Mail an und gibst ihn ein. Mit der Bestätigung des Codes kommt der Vertrag zustande.</li>',
    '<li><strong>Kostenpflichtiger Plan:</strong> Bei uns (Stripe) kommt der Vertrag zustande, wenn du auf der Zahlungsseite von Stripe die Zahlung auslöst; vorher siehst du Plan und Preis und kannst deine Angaben korrigieren oder abbrechen. In Google Play oder im App Store kommt er über deren Kaufschaltfläche zustande.</li>',
    '<li><strong>Sprachen:</strong> Den Vertrag kannst du auf Deutsch, Englisch oder Französisch schließen. Maßgeblich ist diese deutsche Fassung; die englische und die französische sind Übersetzungen.</li>',
    '<li><strong>Vertragstext:</strong> Wir speichern jede Fassung dieser Bedingungen mit ihrer Versionsnummer und vermerken, welche Fassung du angenommen hast. Die aktuelle Fassung findest du immer hier; frühere Fassungen schicken wir dir auf Anfrage.</li>',
    '<li><strong>Plattformen:</strong> Tenshu läuft auf Android, iOS, macOS, Windows, Linux und im Browser; nicht jede App gibt es überall. Deine Inhalte lassen sich nur mit deinen Schlüsseln öffnen, also auf deinen gekoppelten Geräten oder mit deiner Wiederherstellungsphrase.</li>',
    '</ul>',

    '<h2 id="account">Dein Konto</h2>',
    '<ul>',
    '<li>Du meldest dich mit deiner E-Mail-Adresse und einem Einmalcode an, den wir dir per E-Mail schicken. Ein Passwort gibt es nicht. Nutze eine Adresse, die dir gehört und die du erreichst.</li>',
    '<li>Ein Konto ist für eine Person. Lege keine weiteren Konten an, um Limits zu umgehen.</li>',
    '<li>Halte deine Geräte und deine Wiederherstellungsphrase sicher. Wer eines deiner gekoppelten Geräte oder deine Wiederherstellungsphrase hat, kann auf deine Daten zugreifen. Bemerkst du, dass jemand Unbefugtes dein Konto nutzt, schreib uns sofort.</li>',
    '<li><strong>Wir können verschlüsselte Daten nicht wiederherstellen.</strong> Wir haben keine Schlüssel und keine unverschlüsselte Kopie. Verlierst du alle deine Geräte und deine Wiederherstellungsphrase, kann niemand deine Inhalte wieder lesbar machen, auch wir nicht.</li>',
    '</ul>',

    '<h2 id="plans">Pläne, Preise und Zahlung</h2>',
    '<ul>',
    '<li><strong>Free:</strong> 5 GB, kostenlos.</li>',
    '<li><strong>Basic:</strong> 50 GB, 2,99 € pro Monat.</li>',
    '<li><strong>Premium:</strong> 2 TB, 9,99 € pro Monat.</li>',
    '</ul>',
    '<p>Die Preise enthalten die gesetzliche Umsatzsteuer. Es gilt der Preis, der dir vor dem Kauf angezeigt wird; in den App-Stores kann er je nach Land abweichen. Ein kostenpflichtiger Plan ist ein Abonnement: Er verlängert sich jeden Monat, bis du kündigst, und wird jeweils für den kommenden Monat im Voraus bezahlt. Er gilt für dein Konto, also auf all deinen Geräten, egal wo du ihn gekauft hast.</p>',
    '<p>Wo du kaufst, entscheidet, bei wem du kaufst:</p>',
    '<ul>',
    '<li><strong>Im Web, in den Desktop-Apps und in Android-Apps außerhalb von Google Play</strong> kaufst du bei uns. Die Zahlung wickelt unser Zahlungsdienstleister Stripe ab. Ein Wechsel in einen größeren Plan gilt sofort; der Preis wird anteilig verrechnet. Ein Wechsel in einen kleineren Plan gilt ab dem nächsten Abrechnungsmonat.</li>',
    '<li><strong>In den Apps aus Google Play und – sobald möglich – aus dem App Store</strong> kaufst du über Google bzw. Apple. Verkäufer ist dann Google bzw. Apple, und für Zahlung, Verlängerung, Planwechsel, Kündigung und Erstattung gelten zusätzlich deren Bedingungen.</li>',
    '</ul>',

    '<h2 id="cancellation">Verlängerung und Kündigung</h2>',
    '<ul>',
    `<li>Du kannst einen Plan jederzeit kündigen, ohne Grund und ohne Gebühr. <strong>Bei uns gekauft (Stripe):</strong> in Control unter „Dienste“ → „Abo verwalten“ bzw. im Web unter „Abrechnung verwalten“, oder per E-Mail an ${SUPPORT_MAIL}. <strong>Über Google Play oder Apple gekauft:</strong> in den Abo-Einstellungen von Google Play bzw. deines Apple-Accounts. Ein über Apple gekauftes Abo können wir nicht für dich kündigen.</li>`,
    '<li>Nach der Kündigung läuft dein Plan bis zum Ende des bezahlten Monats und wechselt dann in den kostenlosen Plan. Es wird nichts gelöscht (siehe „Speicherplatz und Limits“).</li>',
    '<li>Für den laufenden, bereits bezahlten Monat gibt es bei einer Kündigung, einem Wechsel in einen kleineren Plan oder dem Löschen des Kontos keine anteilige Erstattung. Dein Widerrufsrecht und deine gesetzlichen Rechte bei Mängeln bleiben davon unberührt, ebenso das Recht beider Seiten, aus wichtigem Grund fristlos zu kündigen.</li>',
    '</ul>',

    '<h2 id="withdrawal">Widerrufsrecht und Erstattungen</h2>',
    '<p><strong>Käufe über Google Play oder den App Store:</strong> Für Widerruf und Erstattung gelten die Regeln des jeweiligen Stores; eine Erstattung beantragst du dort. Wir selbst können diese Käufe nicht erstatten.</p>',
    '<p><strong>Käufe bei uns (Stripe):</strong> Als Verbraucher hast du das folgende gesetzliche Widerrufsrecht. Vor dem Kauf verlangst du ausdrücklich, dass wir vor Ablauf der Widerrufsfrist beginnen, damit du den Speicherplatz gleich nutzen kannst. Widerrufst du danach innerhalb der Frist, zahlst du nur den Anteil des Preises für die Zeit bis zu deinem Widerruf; den Rest erstatten wir. Eine Verlängerung in einem späteren Monat ist kein neuer Vertrag und begründet kein neues Widerrufsrecht.</p>',
    '<h3>Widerrufsbelehrung</h3>',
    '<p><strong>Widerrufsrecht</strong></p>',
    '<p>Sie haben das Recht, binnen vierzehn Tagen ohne Angabe von Gründen diesen Vertrag zu widerrufen. Die Widerrufsfrist beträgt vierzehn Tage ab dem Tag des Vertragsabschlusses.</p>',
    `<p>Um Ihr Widerrufsrecht auszuüben, müssen Sie uns (Cajetan UG (haftungsbeschränkt), Lucas-Cranach-Straße 5, 84453 Mühldorf am Inn, Deutschland, E-Mail: ${SUPPORT_MAIL}) mittels einer eindeutigen Erklärung (z. B. ein mit der Post versandter Brief oder eine E-Mail) über Ihren Entschluss, diesen Vertrag zu widerrufen, informieren. Sie können dafür das beigefügte Muster-Widerrufsformular verwenden, das jedoch nicht vorgeschrieben ist.</p>`,
    '<p>Zur Wahrung der Widerrufsfrist reicht es aus, dass Sie die Mitteilung über die Ausübung des Widerrufsrechts vor Ablauf der Widerrufsfrist absenden.</p>',
    '<p><strong>Folgen des Widerrufs</strong></p>',
    '<p>Wenn Sie diesen Vertrag widerrufen, haben wir Ihnen alle Zahlungen, die wir von Ihnen erhalten haben, unverzüglich und spätestens binnen vierzehn Tagen ab dem Tag zurückzuzahlen, an dem die Mitteilung über Ihren Widerruf dieses Vertrags bei uns eingegangen ist. Für diese Rückzahlung verwenden wir dasselbe Zahlungsmittel, das Sie bei der ursprünglichen Transaktion eingesetzt haben, es sei denn, mit Ihnen wurde ausdrücklich etwas anderes vereinbart; in keinem Fall werden Ihnen wegen dieser Rückzahlung Entgelte berechnet.</p>',
    '<p>Haben Sie verlangt, dass die Dienstleistungen während der Widerrufsfrist beginnen sollen, so haben Sie uns einen angemessenen Betrag zu zahlen, der dem Anteil der bis zu dem Zeitpunkt, zu dem Sie uns von der Ausübung des Widerrufsrechts hinsichtlich dieses Vertrags unterrichten, bereits erbrachten Dienstleistungen im Vergleich zum Gesamtumfang der im Vertrag vorgesehenen Dienstleistungen entspricht.</p>',
    '<h3>Muster-Widerrufsformular</h3>',
    '<p>(Wenn Sie den Vertrag widerrufen wollen, dann füllen Sie bitte dieses Formular aus und senden Sie es zurück.)</p>',
    '<ul>',
    '<li>An Cajetan UG (haftungsbeschränkt), Lucas-Cranach-Straße 5, 84453 Mühldorf am Inn, Deutschland, E-Mail: support@tenshu.app:</li>',
    '<li>Hiermit widerrufe(n) ich/wir (*) den von mir/uns (*) abgeschlossenen Vertrag über die Erbringung der folgenden Dienstleistung (*)</li>',
    '<li>Bestellt am (*)</li>',
    '<li>Name des/der Verbraucher(s)</li>',
    '<li>Anschrift des/der Verbraucher(s)</li>',
    '<li>Unterschrift des/der Verbraucher(s) (nur bei Mitteilung auf Papier)</li>',
    '<li>Datum</li>',
    '</ul>',
    '<p>(*) Unzutreffendes streichen.</p>',

    '<h2 id="storage">Speicherplatz und Limits</h2>',
    '<ul>',
    '<li>Alles, was du auf unseren Servern speicherst, zählt gegen den Speicherplatz deines Plans, auch die eigene verschlüsselte Kopie, die ein Freigabe-Link oder ein geteilter Ordner hält.</li>',
    '<li>Bist du über deinem Limit, nehmen wir keine neuen Uploads an, bis du wieder darunter bist – weil du etwas löschst oder einen größeren Plan wählst. <strong>Was du schon gespeichert hast, löschen wir nicht.</strong></li>',
    '<li>Wir löschen deine Inhalte auch nicht, weil eine Zahlung fehlschlägt, ein Plan endet oder du Tenshu länger nicht nutzt.</li>',
    '<li>Um den Dienst vor Missbrauch und Überlastung zu schützen, können wir begrenzen, wie viele Daten über Freigabe-Links pro Tag abgerufen werden. Ist ein Limit erreicht, ist ein Link vorübergehend nicht abrufbar.</li>',
    '</ul>',

    '<h2 id="acceptable-use">Was nicht erlaubt ist</h2>',
    '<p>Nutze Tenshu nicht, um rechtswidrige Inhalte zu speichern oder zu verbreiten, Rechte anderer zu verletzen (etwa Urheber-, Marken- oder Persönlichkeitsrechte), Schadsoftware zu verbreiten, unsere Server oder andere Nutzer anzugreifen oder zu überlasten, oder Limits und Sicherheitsmaßnahmen zu umgehen. Für das, was du speicherst und teilst, bist du selbst verantwortlich.</p>',
    '<p>Wir können deine Inhalte nicht lesen und durchsuchen sie nicht. Alles, was wir gegen Missbrauch tun, geschieht ohne Blick auf Inhalte:</p>',
    '<ul>',
    `<li><strong>Kontaktstelle:</strong> Unsere zentrale Kontaktstelle für Behörden, die EU-Kommission und Nutzer nach dem Digital Services Act (Art. 11 und 12 DSA) ist ${SUPPORT_MAIL}, auf Deutsch oder Englisch.</li>`,
    `<li><strong>Meldungen:</strong> Wer einen Freigabe-Link für rechtswidrig hält, kann ihn an ${SUPPORT_MAIL} melden. Die Meldung sollte enthalten: den Link, und zwar ohne den Teil ab „#“ (das ist der Schlüssel, den brauchen wir nicht); warum der Inhalt rechtswidrig ist; deinen Namen und deine E-Mail-Adresse; und die Erklärung, dass deine Angaben nach bestem Wissen richtig und vollständig sind. Wir bestätigen den Eingang und teilen dir unsere Entscheidung mit.</li>`,
    '<li><strong>Entscheidung:</strong> Wir prüfen jede Meldung und sperren oder löschen einen Link, wenn er offensichtlich rechtswidrig ist oder wir gesetzlich dazu verpflichtet sind. Wir entschlüsseln dafür nichts. Sperren oder löschen wir einen deiner Links, begründen wir das dir gegenüber, soweit wir dürfen, und nennen dir deine Rechtsbehelfe: Du kannst uns schreiben, wenn du die Entscheidung für falsch hältst, und den Rechtsweg zu den Gerichten gehen.</li>',
    '<li><strong>Folgen:</strong> Bei schweren oder wiederholten Verstößen können wir das Teilen für dein Konto sperren und das Konto aus wichtigem Grund sperren oder kündigen (siehe „Kündigung durch uns“). Wo uns das Gesetz dazu verpflichtet, geben wir Hinweise an die zuständigen Behörden weiter.</li>',
    '</ul>',

    '<h2 id="closure">Konto löschen</h2>',
    '<ul>',
    `<li>Du kannst dein Konto jederzeit löschen: in Control unter „Dienste“ → „Konto löschen“ (Android und Desktop) oder auf ${ACCOUNT_DELETION}, jeweils mit einem Code, den wir dir per E-Mail schicken.</li>`,
    '<li>Abos, die du bei uns (Stripe) oder über Google Play abgeschlossen hast, werden dabei gekündigt: Ein Stripe-Abo endet sofort, und der Rest des bezahlten Monats verfällt ohne Erstattung; ein Google-Play-Abo verlängert sich nicht mehr. Ein Abo über Apple bleibt davon unberührt; kündige es selbst in den Einstellungen deines Apple-Accounts. Willst du den bezahlten Monat noch nutzen, kündige zuerst und lösche das Konto später.</li>',
    '<li><strong>30 Tage nach der Löschung löschen wir alle verschlüsselten Inhalte deines Kontos aus unserem laufenden Speicher.</strong> Meldest du dich vorher auf einem Gerät wieder an, das deine Daten noch hat, bleiben sie erhalten. Danach kannst du sie nicht mehr abrufen. Eine verschlüsselte Sicherungskopie kann länger bestehen; wie lange, steht in der Datenschutzerklärung. Sichere vorher, was du behalten willst.</li>',
    `<li>Einzelne Dateien löschst du in der App. Wir können eine bestimmte Datei nicht auf Anfrage löschen, weil wir nicht wissen, welche verschlüsselten Daten zu ihr gehören. Was wir länger aufbewahren müssen (etwa Rechnungen) und wie lange Sicherungskopien bestehen, steht in der ${link(privacyUrl('de'), 'Datenschutzerklärung')}.</li>`,
    '</ul>',

    '<h2 id="termination">Kündigung durch uns</h2>',
    '<ul>',
    '<li>Ein Konto mit dem kostenlosen Plan können wir mit einer Frist von zwei Monaten kündigen; wir sagen dir das per E-Mail.</li>',
    '<li>Aus wichtigem Grund, etwa bei schweren oder wiederholten Verstößen gegen diese Bedingungen, können wir dein Konto sperren oder fristlos kündigen; wenn es zumutbar ist, warnen wir dich vorher.</li>',
    '<li>Kündigen wir dein Konto, gilt für deine Inhalte dasselbe wie beim Löschen (siehe „Konto löschen“). Bereits bezahlte Zeit nach dem Ende erstatten wir anteilig, außer bei Missbrauch.</li>',
    '</ul>',

    '<h2 id="age">Mindestalter</h2>',
    '<p>Tenshu ist für Menschen ab 16 Jahren. Mit dem Anlegen eines Kontos bestätigst du, dass du mindestens 16 bist. Bist du noch nicht 18, brauchst du die Zustimmung deiner Eltern oder eines anderen gesetzlichen Vertreters; für einen kostenpflichtigen Plan in jedem Fall.</p>',

    '<h2 id="availability">Verfügbarkeit und Änderungen am Dienst</h2>',
    '<ul>',
    '<li>Wir arbeiten daran, dass Tenshu zuverlässig läuft, können aber keine ununterbrochene Verfügbarkeit zusagen: Wartung, Störungen und Ereignisse außerhalb unserer Kontrolle können den Dienst zeitweise einschränken. Was schon auf deinen Geräten ist, kannst du dort auch ohne Verbindung zu unseren Servern weiter nutzen.</li>',
    '<li>Wir entwickeln Tenshu weiter und dürfen den Dienst aus triftigem Grund ändern, etwa für neue Funktionen, für die Sicherheit, wegen technischer Entwicklungen oder neuer gesetzlicher Anforderungen, wenn dir dadurch keine zusätzlichen Kosten entstehen. Über Änderungen informieren wir dich klar und verständlich, etwa in den Versionshinweisen. Beeinträchtigt eine Änderung deinen bezahlten Plan mehr als nur unerheblich, sagen wir dir das rechtzeitig vorher per E-Mail, und du kannst den Vertrag innerhalb von 30 Tagen nach der Änderung kostenlos beenden.</li>',
    '<li>Sollten wir Tenshu einstellen, sagen wir dir das mindestens drei Monate vorher, damit du deine Daten auf deine Geräte holen kannst, und erstatten bereits bezahlte Zeit nach dem Ende anteilig.</li>',
    '</ul>',

    '<h2 id="liability">Haftung</h2>',
    '<ul>',
    '<li>Wir haften unbeschränkt bei Vorsatz und grober Fahrlässigkeit, bei Verletzung von Leben, Körper oder Gesundheit, nach dem Produkthaftungsgesetz und soweit wir eine Garantie übernommen haben.</li>',
    '<li>Bei leichter Fahrlässigkeit haften wir nur, wenn wir eine wesentliche Vertragspflicht verletzen – eine Pflicht, deren Erfüllung die ordnungsgemäße Durchführung des Vertrags überhaupt erst ermöglicht und auf deren Einhaltung du regelmäßig vertrauen darfst –, und dann nur für den vertragstypischen, vorhersehbaren Schaden. Im Übrigen haften wir für leichte Fahrlässigkeit nicht.</li>',
    '<li>Diese Regeln gelten auch für unsere gesetzlichen Vertreter und Erfüllungsgehilfen. Deine gesetzlichen Rechte bei Mängeln bleiben unberührt.</li>',
    '</ul>',

    '<h2 id="changes">Änderungen dieser Bedingungen</h2>',
    '<p>Wir passen diese Bedingungen an, wenn sich Tenshu oder die Rechtslage ändert. Das Datum oben zeigt, seit wann diese Fassung gilt. Über Änderungen informieren wir dich vorher, in der App oder per E-Mail. Eine Änderung, die deine Rechte oder Pflichten zu deinem Nachteil berührt, gilt für dich erst, wenn du ihr zustimmst, etwa bei deiner nächsten Anmeldung. Stimmst du nicht zu, gelten für dich die bisherigen Bedingungen weiter; dein Konto kannst du jederzeit löschen.</p>',

    '<h2 id="law">Anwendbares Recht</h2>',
    '<p>Es gilt deutsches Recht. Bist du Verbraucher und hast deinen gewöhnlichen Aufenthalt in einem anderen Staat, behältst du den Schutz der zwingenden Verbraucherschutzvorschriften dieses Staates (Art. 6 Abs. 2 Rom-I-Verordnung).</p>',

    '<h2 id="disputes">Streitbeilegung</h2>',
    `<p>Wir sind nicht bereit und nicht verpflichtet, an Streitbeilegungsverfahren vor einer Verbraucherschlichtungsstelle teilzunehmen. Wenn etwas nicht stimmt, schreib uns an ${SUPPORT_MAIL}; wir suchen gern direkt eine Lösung.</p>`,

    '<h2 id="contact">Kontakt</h2>',
    `<p>Fragen zu diesen Bedingungen, Kündigung, Widerruf und Meldungen: ${SUPPORT_MAIL}. Post: Cajetan UG (haftungsbeschränkt), Lucas-Cranach-Straße 5, 84453 Mühldorf am Inn, Deutschland.</p>`,
  ].join('\n'),
}

const en: LegalPage = {
  title: 'Terms of use',
  description: 'Tenshu’s terms of use: the contract, your account, plans and payment, cancellation and withdrawal, storage, the rules of use, and liability.',
  translationNotice: 'This is a translation; the German version is legally binding.',
  reviewed: false,
  html: [
    `<p><strong>Effective: 28 October 2026</strong> (version ${TERMS_DATE})</p>`,
    `<p>These terms apply to the Tenshu apps (Control, Drive, Photos, Fit, Timeline, Maps, Calendar, Contacts, Home, Mia), the website tenshu.app and the servers the apps work with. How we handle your data is set out in the ${link(privacyUrl('en'), 'privacy policy')}.</p>`,

    '<h2 id="who">Who we are</h2>',
    '<p>Tenshu is operated by:</p>',
    address('Germany'),
    `<p>Represented by its managing director, Benedikt Löbbecke. Email: ${SUPPORT_MAIL} or ${COMPANY_MAIL}. More details are in the ${link('/legal/imprint/en', 'imprint')}.</p>`,

    '<h2 id="service">What Tenshu is</h2>',
    '<ul>',
    '<li>Tenshu is a set of apps that encrypt your data on your devices and sync it between them (end-to-end encryption). Our servers store and transfer your content only in encrypted form; only your devices hold the keys. <strong>We cannot read your content.</strong></li>',
    '<li>You can use the apps on a single device without an account. To sync between devices, store data on our servers and share, you need an account.</li>',
    '<li>Every account comes with the free plan and 5 GB of storage. A paid plan gives you more (see “Plans, prices and payment”).</li>',
    `<li>You may use the apps for your own purposes. Third-party software they contain is covered by its own ${link(licensesUrl('en'), 'licences')}.</li>`,
    '<li>Your content stays yours. You grant us only the rights we need to store it encrypted, transfer it and – when you create a share link – deliver it to the link’s recipients.</li>',
    '</ul>',

    '<h2 id="contract">How the contract is made</h2>',
    '<ul>',
    '<li><strong>Free plan:</strong> you enter your email address in the app or on the web, request a code by email and enter it. The contract is made when the code is confirmed.</li>',
    '<li><strong>Paid plan:</strong> with us (Stripe), the contract is made when you trigger the payment on Stripe’s payment page; before that you see the plan and the price and can correct your details or cancel. In Google Play or the App Store, it is made through their purchase button.</li>',
    '<li><strong>Languages:</strong> you can conclude the contract in German, English or French. The German version is binding; the English and French versions are translations.</li>',
    '<li><strong>Contract text:</strong> we store every version of these terms with its version number and record which version you accepted. The current version is always here; we send you earlier versions on request.</li>',
    '<li><strong>Platforms:</strong> Tenshu runs on Android, iOS, macOS, Windows, Linux and in the browser; not every app is available everywhere. Your content can be opened only with your keys, that is on your paired devices or with your recovery phrase.</li>',
    '</ul>',

    '<h2 id="account">Your account</h2>',
    '<ul>',
    '<li>You sign in with your email address and a one-time code we email you. There is no password. Use an address that belongs to you and that you can reach.</li>',
    '<li>An account is for one person. Do not create more accounts to get around limits.</li>',
    '<li>Keep your devices and your recovery phrase safe. Anyone who has one of your paired devices or your recovery phrase can access your data. If you notice someone using your account without permission, write to us at once.</li>',
    '<li><strong>We cannot recover encrypted data.</strong> We hold no keys and no unencrypted copy. If you lose all your devices and your recovery phrase, nobody can make your content readable again, us included.</li>',
    '</ul>',

    '<h2 id="plans">Plans, prices and payment</h2>',
    '<ul>',
    '<li><strong>Free:</strong> 5 GB, free of charge.</li>',
    '<li><strong>Basic:</strong> 50 GB, €2.99 per month.</li>',
    '<li><strong>Premium:</strong> 2 TB, €9.99 per month.</li>',
    '</ul>',
    '<p>Prices include VAT. The price shown to you before you buy applies; in the app stores it may differ by country. A paid plan is a subscription: it renews every month until you cancel, and each month is paid in advance. It belongs to your account, so it works on all your devices wherever you bought it.</p>',
    '<p>Where you buy decides who you buy from:</p>',
    '<ul>',
    '<li><strong>On the web, in the desktop apps and in Android apps from outside Google Play</strong> you buy from us. Payment is handled by our payment provider Stripe. A move to a bigger plan applies at once, with the price adjusted pro rata. A move to a smaller plan applies from the next billing month.</li>',
    '<li><strong>In the apps from Google Play and – as soon as possible – the App Store</strong> you buy through Google or Apple. Google or Apple is then the seller, and their terms also govern payment, renewal, plan changes, cancellation and refunds.</li>',
    '</ul>',

    '<h2 id="cancellation">Renewal and cancellation</h2>',
    '<ul>',
    `<li>You can cancel a plan at any time, without a reason and without a fee. <strong>Bought from us (Stripe):</strong> in Control under “Services” → “Manage subscription”, on the web under “Manage billing”, or by email to ${SUPPORT_MAIL}. <strong>Bought through Google Play or Apple:</strong> in the subscription settings of Google Play or your Apple account. We cannot cancel a subscription bought through Apple for you.</li>`,
    '<li>After you cancel, your plan runs to the end of the month you have paid for and then moves to the free plan. Nothing is deleted (see “Storage and limits”).</li>',
    '<li>Cancelling, moving to a smaller plan or deleting your account does not refund the current, already paid month pro rata. Your right of withdrawal and your statutory rights if the service is defective are not affected, nor is either side’s right to terminate without notice for good cause.</li>',
    '</ul>',

    '<h2 id="withdrawal">Right of withdrawal and refunds</h2>',
    '<p><strong>Purchases through Google Play or the App Store:</strong> withdrawal and refunds follow that store’s rules, and you request a refund there. We cannot refund these purchases ourselves.</p>',
    '<p><strong>Purchases from us (Stripe):</strong> as a consumer you have the statutory right of withdrawal set out below. Before you buy, you expressly ask us to start before the withdrawal period ends, so that you can use the storage at once. If you then withdraw within the period, you pay only the share of the price for the time up to your withdrawal, and we refund the rest. A renewal in a later month is not a new contract and does not start a new withdrawal period.</p>',
    '<h3>Withdrawal notice</h3>',
    '<p><strong>Right of withdrawal</strong></p>',
    '<p>You have the right to withdraw from this contract within 14 days without giving any reason. The withdrawal period will expire after 14 days from the day of the conclusion of the contract.</p>',
    `<p>To exercise the right of withdrawal, you must inform us (Cajetan UG (haftungsbeschränkt), Lucas-Cranach-Straße 5, 84453 Mühldorf am Inn, Germany, email: ${SUPPORT_MAIL}) of your decision to withdraw from this contract by an unequivocal statement (e.g. a letter sent by post or an email). You may use the attached model withdrawal form, but it is not obligatory.</p>`,
    '<p>To meet the withdrawal deadline, it is sufficient for you to send your communication concerning your exercise of the right of withdrawal before the withdrawal period has expired.</p>',
    '<p><strong>Effects of withdrawal</strong></p>',
    '<p>If you withdraw from this contract, we shall reimburse to you all payments received from you without undue delay and in any event not later than 14 days from the day on which we are informed about your decision to withdraw from this contract. We will carry out such reimbursement using the same means of payment as you used for the initial transaction, unless you have expressly agreed otherwise; in any event, you will not incur any fees as a result of such reimbursement.</p>',
    '<p>If you requested to begin the performance of services during the withdrawal period, you shall pay us an amount which is in proportion to what has been provided until you have communicated us your withdrawal from this contract, in comparison with the full coverage of the contract.</p>',
    '<h3>Model withdrawal form</h3>',
    '<p>(Complete and return this form only if you wish to withdraw from the contract.)</p>',
    '<ul>',
    '<li>To Cajetan UG (haftungsbeschränkt), Lucas-Cranach-Straße 5, 84453 Mühldorf am Inn, Germany, email: support@tenshu.app:</li>',
    '<li>I/We (*) hereby give notice that I/We (*) withdraw from my/our (*) contract for the provision of the following service (*)</li>',
    '<li>Ordered on (*)</li>',
    '<li>Name of consumer(s)</li>',
    '<li>Address of consumer(s)</li>',
    '<li>Signature of consumer(s) (only if this form is notified on paper)</li>',
    '<li>Date</li>',
    '</ul>',
    '<p>(*) Delete as appropriate.</p>',

    '<h2 id="storage">Storage and limits</h2>',
    '<ul>',
    '<li>Everything you store on our servers counts against your plan’s storage, including the separate encrypted copy that a share link or a shared folder holds.</li>',
    '<li>If you are over your limit, we accept no new uploads until you are back under it – because you delete something or choose a bigger plan. <strong>We do not delete what you have already stored.</strong></li>',
    '<li>Nor do we delete your content because a payment fails, a plan ends or you have not used Tenshu for a while.</li>',
    '<li>To protect the service from abuse and overload, we may limit how much data can be downloaded through share links per day. When a limit is reached, a link is temporarily unavailable.</li>',
    '</ul>',

    '<h2 id="acceptable-use">What is not allowed</h2>',
    '<p>Do not use Tenshu to store or distribute unlawful content, infringe other people’s rights (such as copyright, trademarks or personality rights), spread malware, attack or overload our servers or other users, or get around limits and security measures. You are responsible for what you store and share.</p>',
    '<p>We cannot read your content and we do not scan it. Everything we do against abuse is done without looking at content:</p>',
    '<ul>',
    `<li><strong>Point of contact:</strong> our single point of contact for authorities, the European Commission and users under the Digital Services Act (Art. 11 and 12 DSA) is ${SUPPORT_MAIL}, in German or English.</li>`,
    `<li><strong>Reports:</strong> anyone who believes a share link is unlawful can report it to ${SUPPORT_MAIL}. A report should contain: the link, without the part from “#” onwards (that is the key, and we do not need it); why the content is unlawful; your name and email address; and a statement that your information is accurate and complete to the best of your knowledge. We confirm receipt and tell you our decision.</li>`,
    '<li><strong>Decision:</strong> we review every report and block or delete a link if it is clearly unlawful or the law requires us to. We decrypt nothing to do so. If we block or delete one of your links, we give you our reasons, as far as we are allowed to, and tell you your remedies: you can write to us if you think the decision is wrong, and you can go to court.</li>',
    '<li><strong>Consequences:</strong> for serious or repeated breaches we may block sharing for your account, and suspend or terminate the account for good cause (see “Termination by us”). Where the law requires it, we pass information to the competent authorities.</li>',
    '</ul>',

    '<h2 id="closure">Deleting your account</h2>',
    '<ul>',
    `<li>You can delete your account at any time: in Control under “Services” → “Delete account” (Android and desktop), or at ${ACCOUNT_DELETION}, in both cases with a code we email you.</li>`,
    '<li>Subscriptions you took out with us (Stripe) or through Google Play are cancelled: a Stripe subscription ends immediately, and the rest of the paid month is forfeited without refund; a Google Play subscription stops renewing. A subscription through Apple is not affected; cancel it yourself in your Apple account settings. If you want to use the month you have paid for, cancel first and delete the account later.</li>',
    '<li><strong>30 days after you delete your account, we delete all of its encrypted content from our live storage.</strong> If you sign in again before then on a device that still has your data, it is kept. After that you can no longer retrieve it. An encrypted backup copy may exist for longer; the privacy policy says how long. Save what you want to keep beforehand.</li>',
    `<li>You delete individual files in the app. We cannot delete a particular file on request, because we do not know which encrypted data belongs to it. What we have to keep for longer (such as invoices) and how long backup copies exist is set out in the ${link(privacyUrl('en'), 'privacy policy')}.</li>`,
    '</ul>',

    '<h2 id="termination">Termination by us</h2>',
    '<ul>',
    '<li>We may terminate an account on the free plan with two months’ notice; we tell you by email.</li>',
    '<li>For good cause, such as serious or repeated breaches of these terms, we may suspend your account or terminate it without notice; where reasonable, we warn you first.</li>',
    '<li>If we terminate your account, the same applies to your content as when you delete it (see “Deleting your account”). We refund any time already paid for after the end pro rata, except in cases of abuse.</li>',
    '</ul>',

    '<h2 id="age">Minimum age</h2>',
    '<p>Tenshu is for people aged 16 and over. By creating an account you confirm that you are at least 16. If you are under 18, you need the consent of your parents or another legal guardian; for a paid plan in every case.</p>',

    '<h2 id="availability">Availability and changes to the service</h2>',
    '<ul>',
    '<li>We work to keep Tenshu running reliably but cannot promise uninterrupted availability: maintenance, faults and events outside our control may limit the service at times. What is already on your devices stays usable there without a connection to our servers.</li>',
    '<li>We keep developing Tenshu and may change the service for a valid reason, such as new features, security, technical developments or new legal requirements, provided it costs you nothing extra. We tell you about changes clearly and comprehensibly, for example in the release notes. If a change affects your paid plan more than insignificantly, we tell you by email in good time beforehand, and you may end the contract free of charge within 30 days of the change.</li>',
    '<li>Should we ever shut Tenshu down, we will tell you at least three months beforehand so that you can move your data to your devices, and refund any time already paid for after the end pro rata.</li>',
    '</ul>',

    '<h2 id="liability">Liability</h2>',
    '<ul>',
    '<li>We are liable without limitation for intent and gross negligence, for injury to life, body or health, under the German Product Liability Act, and where we have given a guarantee.</li>',
    '<li>For slight negligence we are liable only if we breach an essential contractual obligation – one whose fulfilment makes the proper performance of the contract possible in the first place and on whose fulfilment you may regularly rely – and then only for the damage typical for the contract and foreseeable. Otherwise we are not liable for slight negligence.</li>',
    '<li>These rules also apply to our legal representatives and agents. Your statutory rights if the service is defective are not affected.</li>',
    '</ul>',

    '<h2 id="changes">Changes to these terms</h2>',
    '<p>We update these terms when Tenshu or the law changes. The date at the top shows since when this version applies. We tell you about changes beforehand, in the app or by email. A change that affects your rights or obligations to your disadvantage applies to you only once you agree to it, for example at your next sign-in. If you do not agree, the previous terms continue to apply to you; you can delete your account at any time.</p>',

    '<h2 id="law">Governing law</h2>',
    '<p>German law applies. If you are a consumer habitually resident in another country, you keep the protection of that country’s mandatory consumer protection rules (Art. 6(2) Rome I Regulation).</p>',

    '<h2 id="disputes">Dispute resolution</h2>',
    `<p>We are neither willing nor obliged to take part in dispute resolution proceedings before a consumer arbitration board. If something is wrong, write to us at ${SUPPORT_MAIL}; we are glad to find a solution directly.</p>`,

    '<h2 id="contact">Contact</h2>',
    `<p>Questions about these terms, cancellation, withdrawal and reports: ${SUPPORT_MAIL}. Post: Cajetan UG (haftungsbeschränkt), Lucas-Cranach-Straße 5, 84453 Mühldorf am Inn, Germany.</p>`,
  ].join('\n'),
}

const fr: LegalPage = {
  title: 'Conditions d’utilisation',
  description: 'Les conditions d’utilisation de Tenshu : le contrat, votre compte, les offres et le paiement, la résiliation et la rétractation, le stockage, les règles d’usage et la responsabilité.',
  translationNotice: 'Ceci est une traduction ; seule la version allemande fait foi.',
  reviewed: false,
  html: [
    `<p><strong>En vigueur : 28 octobre 2026</strong> (version ${TERMS_DATE})</p>`,
    `<p>Ces conditions s’appliquent aux applications Tenshu (Control, Drive, Photos, Fit, Timeline, Maps, Calendar, Contacts, Home, Mia), au site tenshu.app et aux serveurs avec lesquels ces applications fonctionnent. La manière dont nous traitons vos données est décrite dans la ${link(privacyUrl('fr'), 'politique de confidentialité')}.</p>`,

    '<h2 id="who">Qui nous sommes</h2>',
    '<p>Tenshu est exploité par :</p>',
    address('Allemagne'),
    `<p>Représentée par son gérant, Benedikt Löbbecke. E-mail : ${SUPPORT_MAIL} ou ${COMPANY_MAIL}. Plus d’informations dans les ${link('/legal/imprint/fr', 'mentions légales')}.</p>`,

    '<h2 id="service">Ce qu’est Tenshu</h2>',
    '<ul>',
    '<li>Tenshu est un ensemble d’applications qui chiffrent vos données sur vos appareils et les synchronisent entre eux (chiffrement de bout en bout). Nos serveurs ne stockent et ne transmettent vos contenus que sous forme chiffrée ; seuls vos appareils détiennent les clés. <strong>Nous ne pouvons pas lire vos contenus.</strong></li>',
    '<li>Sur un seul appareil, vous pouvez utiliser les applications sans compte. Pour synchroniser plusieurs appareils, stocker des données sur nos serveurs et partager, il vous faut un compte.</li>',
    '<li>Chaque compte inclut l’offre gratuite avec 5 Go de stockage. Une offre payante vous en donne davantage (voir « Offres, prix et paiement »).</li>',
    `<li>Vous pouvez utiliser les applications pour vos propres besoins. Les logiciels tiers qu’elles contiennent relèvent de leurs propres ${link(licensesUrl('fr'), 'licences')}.</li>`,
    '<li>Vos contenus restent les vôtres. Vous ne nous accordez que les droits nécessaires pour les stocker chiffrés, les transmettre et – lorsque vous créez un lien de partage – les remettre à ses destinataires.</li>',
    '</ul>',

    '<h2 id="contract">Conclusion du contrat</h2>',
    '<ul>',
    '<li><strong>Offre gratuite :</strong> vous saisissez votre adresse e-mail dans l’application ou sur le web, demandez un code par e-mail et le saisissez. Le contrat est conclu lorsque le code est confirmé.</li>',
    '<li><strong>Offre payante :</strong> auprès de nous (Stripe), le contrat est conclu lorsque vous déclenchez le paiement sur la page de paiement de Stripe ; auparavant, vous voyez l’offre et le prix et pouvez corriger vos informations ou abandonner. Dans Google Play ou l’App Store, il est conclu par leur bouton d’achat.</li>',
    '<li><strong>Langues :</strong> vous pouvez conclure le contrat en allemand, en anglais ou en français. La version allemande fait foi ; les versions anglaise et française sont des traductions.</li>',
    '<li><strong>Texte du contrat :</strong> nous conservons chaque version de ces conditions avec son numéro de version et enregistrons la version que vous avez acceptée. La version actuelle se trouve toujours ici ; nous vous envoyons les versions antérieures sur demande.</li>',
    '<li><strong>Plateformes :</strong> Tenshu fonctionne sous Android, iOS, macOS, Windows, Linux et dans le navigateur ; toutes les applications ne sont pas disponibles partout. Vos contenus ne peuvent être ouverts qu’avec vos clés, c’est-à-dire sur vos appareils appairés ou avec votre phrase de récupération.</li>',
    '</ul>',

    '<h2 id="account">Votre compte</h2>',
    '<ul>',
    '<li>Vous vous connectez avec votre adresse e-mail et un code à usage unique que nous vous envoyons par e-mail. Il n’y a pas de mot de passe. Utilisez une adresse qui vous appartient et que vous consultez.</li>',
    '<li>Un compte est destiné à une seule personne. Ne créez pas d’autres comptes pour contourner les limites.</li>',
    '<li>Protégez vos appareils et votre phrase de récupération. Quiconque dispose de l’un de vos appareils appairés ou de votre phrase de récupération peut accéder à vos données. Si vous constatez qu’une personne non autorisée utilise votre compte, écrivez-nous immédiatement.</li>',
    '<li><strong>Nous ne pouvons pas récupérer des données chiffrées.</strong> Nous ne détenons ni clés ni copie non chiffrée. Si vous perdez tous vos appareils et votre phrase de récupération, personne ne peut rendre vos contenus à nouveau lisibles, pas même nous.</li>',
    '</ul>',

    '<h2 id="plans">Offres, prix et paiement</h2>',
    '<ul>',
    '<li><strong>Free :</strong> 5 Go, gratuit.</li>',
    '<li><strong>Basic :</strong> 50 Go, 2,99 € par mois.</li>',
    '<li><strong>Premium :</strong> 2 To, 9,99 € par mois.</li>',
    '</ul>',
    '<p>Les prix incluent la TVA. Le prix applicable est celui qui vous est affiché avant l’achat ; dans les boutiques d’applications, il peut varier selon le pays. Une offre payante est un abonnement : il se renouvelle chaque mois jusqu’à sa résiliation, et chaque mois est payé d’avance. Il est rattaché à votre compte et fonctionne donc sur tous vos appareils, où que vous l’ayez acheté.</p>',
    '<p>L’endroit où vous achetez détermine auprès de qui vous achetez :</p>',
    '<ul>',
    '<li><strong>Sur le web, dans les applications de bureau et dans les applications Android hors Google Play</strong>, vous achetez auprès de nous. Le paiement est traité par notre prestataire de paiement Stripe. Le passage à une offre plus grande s’applique immédiatement, avec un ajustement du prix au prorata. Le passage à une offre plus petite s’applique à partir du mois de facturation suivant.</li>',
    '<li><strong>Dans les applications issues de Google Play et – dès que possible – de l’App Store</strong>, vous achetez par l’intermédiaire de Google ou d’Apple. Google ou Apple est alors le vendeur, et ses conditions régissent également le paiement, le renouvellement, les changements d’offre, la résiliation et le remboursement.</li>',
    '</ul>',

    '<h2 id="cancellation">Renouvellement et résiliation</h2>',
    '<ul>',
    `<li>Vous pouvez résilier une offre à tout moment, sans motif et sans frais. <strong>Achetée auprès de nous (Stripe) :</strong> dans Control, sous « Services » → « Manage subscription », sur le web sous « Manage billing », ou par e-mail à ${SUPPORT_MAIL}. <strong>Achetée via Google Play ou Apple :</strong> dans les réglages d’abonnement de Google Play ou de votre compte Apple. Nous ne pouvons pas résilier pour vous un abonnement souscrit via Apple.</li>`,
    '<li>Après la résiliation, votre offre court jusqu’à la fin du mois payé, puis passe à l’offre gratuite. Rien n’est supprimé (voir « Stockage et limites »).</li>',
    '<li>Une résiliation, le passage à une offre plus petite ou la suppression du compte ne donnent lieu à aucun remboursement au prorata du mois en cours déjà payé. Votre droit de rétractation et vos droits légaux en cas de défaut du service ne sont pas affectés, pas plus que le droit de chaque partie de résilier sans préavis pour motif grave.</li>',
    '</ul>',

    '<h2 id="withdrawal">Droit de rétractation et remboursements</h2>',
    '<p><strong>Achats via Google Play ou l’App Store :</strong> la rétractation et les remboursements suivent les règles de la boutique concernée, auprès de laquelle vous demandez un remboursement. Nous ne pouvons pas rembourser ces achats nous-mêmes.</p>',
    '<p><strong>Achats auprès de nous (Stripe) :</strong> en tant que consommateur, vous disposez du droit légal de rétractation décrit ci-dessous. Avant l’achat, vous nous demandez expressément de commencer avant la fin du délai de rétractation, afin de pouvoir utiliser le stockage tout de suite. Si vous vous rétractez ensuite dans le délai, vous ne payez que la part du prix correspondant à la période écoulée jusqu’à votre rétractation, et nous remboursons le reste. Un renouvellement un mois ultérieur n’est pas un nouveau contrat et n’ouvre pas de nouveau délai de rétractation.</p>',
    '<h3>Informations sur la rétractation</h3>',
    '<p><strong>Droit de rétractation</strong></p>',
    '<p>Vous avez le droit de vous rétracter du présent contrat sans donner de motif dans un délai de quatorze jours. Le délai de rétractation expire quatorze jours après le jour de la conclusion du contrat.</p>',
    `<p>Pour exercer le droit de rétractation, vous devez nous notifier (Cajetan UG (haftungsbeschränkt), Lucas-Cranach-Straße 5, 84453 Mühldorf am Inn, Allemagne, e-mail : ${SUPPORT_MAIL}) votre décision de rétractation du présent contrat au moyen d’une déclaration dénuée d’ambiguïté (par exemple, lettre envoyée par la poste ou courrier électronique). Vous pouvez utiliser le modèle de formulaire de rétractation ci-dessous, mais ce n’est pas obligatoire.</p>`,
    '<p>Pour que le délai de rétractation soit respecté, il suffit que vous transmettiez votre communication relative à l’exercice du droit de rétractation avant l’expiration du délai de rétractation.</p>',
    '<p><strong>Effets de la rétractation</strong></p>',
    '<p>En cas de rétractation de votre part du présent contrat, nous vous rembourserons tous les paiements reçus de vous sans retard excessif et, en tout état de cause, au plus tard quatorze jours à compter du jour où nous sommes informés de votre décision de rétractation du présent contrat. Nous procéderons au remboursement en utilisant le même moyen de paiement que celui que vous aurez utilisé pour la transaction initiale, sauf si vous convenez expressément d’un moyen différent ; en tout état de cause, ce remboursement n’occasionnera pas de frais pour vous.</p>',
    '<p>Si vous avez demandé de commencer la prestation de services pendant le délai de rétractation, vous devrez nous payer un montant proportionnel à ce qui vous a été fourni jusqu’au moment où vous nous avez informés de votre rétractation du présent contrat, par rapport à l’ensemble des prestations prévues par le contrat.</p>',
    '<h3>Modèle de formulaire de rétractation</h3>',
    '<p>(Veuillez compléter et renvoyer le présent formulaire uniquement si vous souhaitez vous rétracter du contrat.)</p>',
    '<ul>',
    '<li>À l’attention de Cajetan UG (haftungsbeschränkt), Lucas-Cranach-Straße 5, 84453 Mühldorf am Inn, Allemagne, e-mail : support@tenshu.app :</li>',
    '<li>Je/Nous (*) vous notifie/notifions (*) par la présente ma/notre (*) rétractation du contrat portant sur la prestation de services ci-dessous (*)</li>',
    '<li>Commandé le (*)</li>',
    '<li>Nom du (des) consommateur(s)</li>',
    '<li>Adresse du (des) consommateur(s)</li>',
    '<li>Signature du (des) consommateur(s) (uniquement en cas de notification du présent formulaire sur papier)</li>',
    '<li>Date</li>',
    '</ul>',
    '<p>(*) Rayez la mention inutile.</p>',

    '<h2 id="storage">Stockage et limites</h2>',
    '<ul>',
    '<li>Tout ce que vous stockez sur nos serveurs compte dans l’espace de votre offre, y compris la copie chiffrée distincte que détient un lien de partage ou un dossier partagé.</li>',
    '<li>Si vous dépassez votre limite, nous n’acceptons plus de nouveaux téléversements jusqu’à ce que vous repassiez en dessous – en supprimant quelque chose ou en choisissant une offre plus grande. <strong>Nous ne supprimons pas ce que vous avez déjà stocké.</strong></li>',
    '<li>Nous ne supprimons pas non plus vos contenus parce qu’un paiement échoue, qu’une offre prend fin ou que vous n’utilisez plus Tenshu depuis un moment.</li>',
    '<li>Pour protéger le service contre les abus et la surcharge, nous pouvons limiter la quantité de données téléchargées par jour via des liens de partage. Lorsqu’une limite est atteinte, un lien est temporairement indisponible.</li>',
    '</ul>',

    '<h2 id="acceptable-use">Ce qui n’est pas autorisé</h2>',
    '<p>N’utilisez pas Tenshu pour stocker ou diffuser des contenus illicites, porter atteinte aux droits d’autrui (droit d’auteur, marques ou droits de la personnalité, par exemple), diffuser des logiciels malveillants, attaquer ou surcharger nos serveurs ou d’autres utilisateurs, ou contourner les limites et les mesures de sécurité. Vous êtes responsable de ce que vous stockez et partagez.</p>',
    '<p>Nous ne pouvons pas lire vos contenus et nous ne les analysons pas. Tout ce que nous faisons contre les abus se fait sans regarder les contenus :</p>',
    '<ul>',
    `<li><strong>Point de contact :</strong> notre point de contact unique pour les autorités, la Commission européenne et les utilisateurs au titre du règlement sur les services numériques (art. 11 et 12 DSA) est ${SUPPORT_MAIL}, en allemand ou en anglais.</li>`,
    `<li><strong>Signalements :</strong> toute personne qui estime qu’un lien de partage est illicite peut le signaler à ${SUPPORT_MAIL}. Un signalement devrait contenir : le lien, sans la partie qui commence par « # » (c’est la clé, et nous n’en avons pas besoin) ; la raison pour laquelle le contenu est illicite ; votre nom et votre adresse e-mail ; et une déclaration selon laquelle vos informations sont exactes et complètes à votre connaissance. Nous accusons réception et vous informons de notre décision.</li>`,
    '<li><strong>Décision :</strong> nous examinons chaque signalement et bloquons ou supprimons un lien s’il est manifestement illicite ou si la loi nous y oblige. Nous ne déchiffrons rien pour cela. Si nous bloquons ou supprimons l’un de vos liens, nous vous en exposons les motifs, dans la mesure où nous y sommes autorisés, et vous indiquons vos voies de recours : vous pouvez nous écrire si vous estimez la décision erronée, et saisir les tribunaux.</li>',
    '<li><strong>Conséquences :</strong> en cas de manquements graves ou répétés, nous pouvons bloquer le partage pour votre compte, et suspendre ou résilier le compte pour motif grave (voir « Résiliation par nous »). Lorsque la loi l’exige, nous transmettons des informations aux autorités compétentes.</li>',
    '</ul>',

    '<h2 id="closure">Supprimer votre compte</h2>',
    '<ul>',
    `<li>Vous pouvez supprimer votre compte à tout moment : dans Control, sous « Services » → « Delete account » (Android et ordinateur), ou sur ${ACCOUNT_DELETION}, dans les deux cas avec un code que nous vous envoyons par e-mail.</li>`,
    '<li>Les abonnements souscrits auprès de nous (Stripe) ou via Google Play sont résiliés : un abonnement Stripe prend fin immédiatement et le reste du mois payé est perdu sans remboursement ; un abonnement Google Play n’est plus renouvelé. Un abonnement via Apple n’est pas concerné : vous résiliez vous-même un abonnement souscrit via Apple dans les réglages de votre compte Apple. Si vous souhaitez profiter du mois payé, résiliez d’abord et supprimez le compte plus tard.</li>',
    '<li><strong>30 jours après la suppression de votre compte, nous supprimons tous ses contenus chiffrés de notre stockage en service.</strong> Si vous vous reconnectez avant cette date sur un appareil qui détient encore vos données, elles sont conservées. Ensuite, vous ne pouvez plus y accéder. Une copie de sauvegarde chiffrée peut exister plus longtemps ; la politique de confidentialité indique pendant combien de temps. Sauvegardez auparavant ce que vous souhaitez garder.</li>',
    `<li>Vous supprimez les fichiers un par un dans l’application. Nous ne pouvons pas supprimer un fichier précis sur demande, car nous ignorons quelles données chiffrées lui appartiennent. Ce que nous devons conserver plus longtemps (comme les factures) et la durée des copies de sauvegarde sont décrits dans la ${link(privacyUrl('fr'), 'politique de confidentialité')}.</li>`,
    '</ul>',

    '<h2 id="termination">Résiliation par nous</h2>',
    '<ul>',
    '<li>Nous pouvons résilier un compte bénéficiant de l’offre gratuite moyennant un préavis de deux mois ; nous vous en informons par e-mail.</li>',
    '<li>Pour motif grave, par exemple en cas de manquements graves ou répétés à ces conditions, nous pouvons suspendre votre compte ou le résilier sans préavis ; lorsque c’est raisonnable, nous vous avertissons d’abord.</li>',
    '<li>Si nous résilions votre compte, il en va de vos contenus comme lors d’une suppression (voir « Supprimer votre compte »). Nous remboursons au prorata la période déjà payée au-delà de la fin, sauf en cas d’abus.</li>',
    '</ul>',

    '<h2 id="age">Âge minimum</h2>',
    '<p>Tenshu s’adresse aux personnes âgées de 16 ans et plus. En créant un compte, vous confirmez avoir au moins 16 ans. Si vous avez moins de 18 ans, il vous faut l’accord de vos parents ou d’un autre représentant légal ; pour une offre payante, dans tous les cas.</p>',

    '<h2 id="availability">Disponibilité et évolutions du service</h2>',
    '<ul>',
    '<li>Nous faisons en sorte que Tenshu fonctionne de manière fiable, mais ne pouvons pas promettre une disponibilité ininterrompue : maintenance, pannes et événements indépendants de notre volonté peuvent limiter temporairement le service. Ce qui se trouve déjà sur vos appareils reste utilisable sans connexion à nos serveurs.</li>',
    '<li>Nous continuons à développer Tenshu et pouvons modifier le service pour un motif valable – nouvelles fonctions, sécurité, évolutions techniques ou nouvelles exigences légales – sans frais supplémentaires pour vous. Nous vous informons des modifications de manière claire et compréhensible, par exemple dans les notes de version. Si une modification affecte votre offre payante de manière plus que négligeable, nous vous en informons par e-mail suffisamment à l’avance, et vous pouvez mettre fin au contrat sans frais dans les 30 jours suivant la modification.</li>',
    '<li>Si nous devions arrêter Tenshu, nous vous préviendrions au moins trois mois à l’avance pour que vous puissiez rapatrier vos données sur vos appareils, et nous rembourserions au prorata la période déjà payée au-delà de la fin.</li>',
    '</ul>',

    '<h2 id="liability">Responsabilité</h2>',
    '<ul>',
    '<li>Notre responsabilité est illimitée en cas de faute intentionnelle et de négligence grave, d’atteinte à la vie, à l’intégrité physique ou à la santé, en vertu de la loi allemande sur la responsabilité du fait des produits, et lorsque nous avons donné une garantie.</li>',
    '<li>En cas de négligence légère, nous ne sommes responsables que si nous manquons à une obligation contractuelle essentielle – une obligation dont l’exécution rend possible la bonne exécution du contrat et au respect de laquelle vous pouvez normalement vous fier – et uniquement pour le dommage typique et prévisible pour ce type de contrat. Pour le reste, nous ne sommes pas responsables en cas de négligence légère.</li>',
    '<li>Ces règles s’appliquent aussi à nos représentants légaux et à nos auxiliaires. Vos droits légaux en cas de défaut du service ne sont pas affectés.</li>',
    '</ul>',

    '<h2 id="changes">Modifications de ces conditions</h2>',
    '<p>Nous mettons à jour ces conditions lorsque Tenshu ou le droit évoluent. La date en haut indique depuis quand cette version s’applique. Nous vous informons des modifications à l’avance, dans l’application ou par e-mail. Une modification qui touche vos droits ou obligations à votre désavantage ne s’applique à vous qu’après votre accord, par exemple lors de votre prochaine connexion. Si vous ne l’acceptez pas, les conditions précédentes continuent de s’appliquer à vous ; vous pouvez supprimer votre compte à tout moment.</p>',

    '<h2 id="law">Droit applicable</h2>',
    '<p>Le droit allemand s’applique. Si vous êtes un consommateur résidant habituellement dans un autre pays, vous conservez la protection des dispositions impératives de protection des consommateurs de ce pays (art. 6, par. 2 du règlement Rome I).</p>',

    '<h2 id="disputes">Règlement des litiges</h2>',
    `<p>Nous ne sommes ni disposés ni tenus à participer à une procédure de règlement des litiges devant un organisme de médiation de la consommation. Si quelque chose ne va pas, écrivez-nous à ${SUPPORT_MAIL} ; nous chercherons volontiers une solution directement.</p>`,

    '<h2 id="contact">Contact</h2>',
    `<p>Questions sur ces conditions, résiliation, rétractation et signalements : ${SUPPORT_MAIL}. Courrier : Cajetan UG (haftungsbeschränkt), Lucas-Cranach-Straße 5, 84453 Mühldorf am Inn, Allemagne.</p>`,
  ].join('\n'),
}

export const TERMS: Record<string, LegalPage> = { de, en, fr }

/** The terms' languages, in the order the language picker lists them (English first). */
export const TERMS_LANGUAGES = ['en', 'de', 'fr'] as const

export function termsUrl(lang: string): string {
  return `/legal/terms/${lang}`
}

/** The withdrawal notice and model form, which both checkout screens link (§ 356 Abs. 3 BGB). */
export const TERMS_WITHDRAWAL_ANCHOR = 'withdrawal'

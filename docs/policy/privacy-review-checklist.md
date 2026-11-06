# Privacy review checklist (maintainer as counsel)

**Status:** In force since 2026-10-28 (maintainer decision). Not legal advice. It records how the
project reviews privacy changes until it can pay a lawyer.

## The decision

- **Until the project can afford counsel, the maintainer acts as counsel.** For any change to the
  privacy notice (`web/apex/support-pages/privacy.ts`) or to what a server stores about a person, the
  "⚖️ needs counsel" gate is replaced by **this checklist**. A PR must pass it before merge, and its
  description says so ("⚖️ reviewed under the maintainer-as-counsel checklist").
- **A real lawyer reviews everything once, before the paid launch.** That review covers the notice
  as it stands and every other open ⚖️ item at once. This checklist does not close those items; it
  only stops them from blocking merges in the meantime. They are tracked where they arise:
  - privacy notice and store listing: [`store-presence-and-support-pages.md`](../proposals/store-presence-and-support-pages.md) §1.4 ("Open for counsel")
  - retention and closure: [`retention-quota-model.md`](../proposals/retention-quota-model.md) §3.2–§3.3a, [`acceptable-use-and-limits.md`](acceptable-use-and-limits.md)
  - relay metadata: [`relay-metadata-minimisation.md`](../proposals/relay-metadata-minimisation.md)
  - commerce accounts and erasure: [`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md) §9.7, [`commerce-end-to-end-dry-run.md`](../proposals/commerce-end-to-end-dry-run.md)
  - store declarations: [`how-to/play-console-answers.md`](../how-to/play-console-answers.md), [`how-to/app-store-release.md`](../how-to/app-store-release.md)
  - face grouping: [`in6-face-clustering-counsel-packet.md`](in6-face-clustering-counsel-packet.md)
  - everything else: `git grep -n '⚖️' -- docs` (the threat model, `tech-debt.md` and the per-app
    proposals carry more).
- **What the checklist does not replace:** House Rule 2 (threat-model amendments) and the deploy
  order below. It is a floor, not a review of the whole notice.

## The checklist

Each item is one line, with the reason it exists. A PR description ticks them or says why an item
does not apply.

1. **The notice matches the deployed code in both directions.** It may not promise anything ahead
   of the code, and nothing the servers store may go unmentioned. *Why:* both drifts have happened
   already. The 2026-10-23 text said closure deletion was "not in operation" a day after it went
   live, and the next text then said "everything is deleted" while the blob mirror keeps everything.
2. **Deploy order is relay → apex** (and commerce → apex) whenever the notice describes server
   behaviour. *Why:* the published text must never describe a server that has not shipped yet.
3. **The German (binding) text changes in the same PR, and EN/FR say the same thing.** *Why:* only
   the German text binds. The French text once missed an edit and kept saying the opposite of the
   German for days.
4. **Every newly stored item has a legal basis and a retention period, or the criteria that set
   one** (Art. 13(1)(c), 13(2)(a) GDPR). "Indefinitely" is acceptable only together with the
   criterion that ends it. *Why:* a missing period or criterion is the most common gap in notices.
5. **Every legitimate-interest claim (Art. 6(1)(f)) has a short balancing note in a doc, linked
   from the PR.** The note covers the interest, why the data is necessary, and why the person's
   interests do not override it. *Why:* Art. 6(1)(f) needs a balancing test, and Art. 5(2) requires
   us to be able to show it.
6. **Closure and erasure reach the new data**, including the relay database snapshots, the commerce
   dumps and the weekly blob mirror. If they do not, the notice says what is kept and for how long.
   *Why:* erasure that skips backups without saying so is the EDPB 2025 finding below. A restore
   must also re-apply erasures ([`erasure-request-runbook.md`](erasure-request-runbook.md)).
7. **Nothing pseudonymous is called anonymous.** A hash of a key, an `erased:<id>` row or a short id
   prefix is pseudonymous for whoever can re-link it (Recital 26; CJEU C‑413/23 P). *Why:* the EDPB
   CEF 2025 report lists mislabelled anonymisation as a common failing (Issue 7), and the notice made
   exactly that mistake ("anonymised pseudonymised marker").

When the notice text changes in substance, also move `PRIVACY_POLICY_DATE` and its two mirrors
(`CommercePublisherClient.PRIVACY_VERSION` and web/apex `commerceApi.ts`; see the header of
`privacy.ts`). The maintainer then decides whether the change is *material* under the notice's
"Änderungen" section, which promises prior notice.

## Sources

- EDPB, *Coordinated Enforcement Framework 2025: report on the implementation of the right to
  erasure*, adopted 10 Feb 2026:
  <https://www.edpb.europa.eu/system/files/2026-02/edpb_cef-report_2025_right-to-erasure_en.pdf>
  It covers erasure in backups and mislabelled anonymisation.
- ICO, *Right to erasure*, including backups and putting data "beyond use":
  <https://ico.org.uk/for-organisations/guide-to-dp/guide-to-the-uk-gdpr/individual-rights/right-to-erasure>
- CJEU, C‑413/23 P, *EDPS v SRB*, 4 September 2025: pseudonymised data is personal data for whoever
  can re-link it, but not necessarily for a recipient who cannot.
